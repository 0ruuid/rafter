#!/usr/bin/env python3
"""核对四槽物品夹具的原生数据和图标内区，不修改截图。"""

import argparse
import importlib.util
import json
import sys
from pathlib import Path

from PIL import Image


SNAPSHOTS = ("opaque-page1", "opaque-page2", "opaque-modal")
EXPECTED = (
    [("diamond", 3), ("bread", 12), ("compass", 1), ("apple", 4)],
    [("iron_ingot", 5), ("emerald", 6), ("gold_ingot", 7), ("redstone", 8)],
)


def check(root):
    spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("check-evidence.py"))
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    evidence = root / "evidence"
    run, snapshots = helper.prepare_run(evidence, helper.load_records(evidence), None, SNAPSHOTS)
    spec = importlib.util.spec_from_file_location("integrated", Path(__file__).with_name("check-integrated-evidence.py"))
    integrated = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(integrated)
    manifest = integrated.load_manifest(root, root / "manifest.json")
    integrated.validate_artifacts(root, manifest)
    layout = json.loads((root / "artifacts/resource-pack/item-layouts.json").read_bytes())
    flow = layout["flows"]["rafterprobe:item-opaque/home"]
    routes = sorted(
        (route for route in layout["routes"] if route["menu"] == "rafterprobe:item-opaque"
         and route["page"] == "home" and not route.get("origin") and not route.get("popup")),
        key=lambda route: route["source-row"],
    )
    if len(routes) != 4:
        raise ValueError("Expected exactly four compiled routes in this fixture")
    observations = []
    for name, expected in zip(SNAPSHOTS, (*EXPECTED, EXPECTED[1])):
        state = snapshots[name]
        width, height = state["guiScaledWidth"], state["guiScaledHeight"]
        if (width, height) != (640, 480):
            raise ValueError(f"{name}: expected logical GUI 640x480")
        items = [widget for widget in state["widgets"] if widget.get("itemType")
                 and widget.get("itemModel") != "rafter:empty"]
        if len(items) != 4:
            raise ValueError(f"{name}: expected four real ItemDisplayWidget values")
        with Image.open(evidence / f"{name}.png") as source:
            image = source.convert("RGB")
        scale = image.width / width
        if image.height / height != scale:
            raise ValueError(f"{name}: screenshot/GUI scale mismatch")
        for route, widget, (kind, count) in zip(routes, items, expected):
            container = route["source-maximum-width"]
            left = (width - container - 20) // 2 + 10 + (container - flow["width"] - 10) // 2 + 5
            top = min(63, height - 5 - min(route["layout-height"], height - 38)) + 4 + flow["inset"]
            box = route["bounds"]
            rect = (left + box["x"] + 4, top + box["y"] + 4,
                    left + box["x"] + box["width"] - 8, top + box["y"] + box["height"] - 12)
            crop = image.crop(tuple(round(value * scale) for value in rect))
            colors = len(crop.getcolors(crop.width * crop.height))
            observations.append({"snapshot": name, "node": route["node"], "colors": colors,
                                 "data-match": widget["itemType"] == f"minecraft:{kind}" and widget["count"] == count,
                                 "icon-visible": colors > 1})
    modal = layout["modal-flows"]["rafterprobe:item-opaque/confirm@from/rafterprobe:item-opaque/home"]
    panel = modal["layout"]
    plan = panel["plan"]
    top = min(63, 480 - 5 - min(plan["bodyHeight"], 480 - 38))
    left = (640 - plan["bodyWidth"] - 20) // 2 + 10
    buttons = [widget for widget in snapshots["opaque-modal"]["widgets"]
               if "Button" in widget.get("type", "")]
    controls_match = all(any(
        (widget.get("x"), widget.get("y"), widget.get("width"), widget.get("height"))
        == (left + cell["bounds"]["x"], top + cell["bounds"]["y"], cell["bounds"]["width"], 20)
        for widget in buttons) for cell in plan["buttons"] if cell.get("target"))
    checks = {
        "all-live-and-frozen-icons-visible": all(row["data-match"] and row["icon-visible"] for row in observations),
        "modal-panel-fits": 0 <= top + panel["offsetY"] <= 480 - panel["height"],
        "modal-keeps-authored-leading-inset": modal["flow"]["inset"] == 0,
        "native-actions-match-compiled-panel": controls_match,
        "frozen-item-tooltips-disabled": len(integrated.real_items(snapshots["opaque-modal"])) == 4
            and all(widget.get("tooltipEnabled") is False
                for widget in integrated.real_items(snapshots["opaque-modal"])),
        "loaded-item-shader-matches": integrated.resource_hash_check(
            run, integrated.SHADER_PATH, manifest["item-shader-sha256"], helper, shader=True),
    }
    passed = all(checks.values())
    return {"result": "PASS" if passed else "FAIL", "run": helper.run_info(run),
            "checks": checks, "observations": observations,
            "scope": "fixture-specific icon interiors plus native item values; not a general item recognizer",
            "manual-visual-review": list(SNAPSHOTS)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = check(args.run)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"check-opaque-evidence: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
