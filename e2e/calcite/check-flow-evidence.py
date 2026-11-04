#!/usr/bin/env python3
"""核对空带复用原型的同次客户端记录；不等于正式 Rafter 集成验收。"""
import argparse
import json
from pathlib import Path
import re
import runpy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    args = parser.parse_args()
    evidence = args.run / "evidence"
    helper = runpy.run_path(str(Path(__file__).with_name("check-evidence.py")))
    corners = (0, 8, 9, 17)
    names = ["flow-grid", *[f"flow-hover-{index}" for index in corners], "flow-source-hover", "flow-after-scroll"]
    try:
        plan = json.loads((args.run / "plan.json").read_text())
        records = helper["load_records"](evidence)
        run, snapshots = helper["prepare_run"](evidence, records, None, names)
        records = run["records"]
        items = helper["items"](snapshots["flow-grid"])
        routes = plan["routes"]
        counts = [route["item"].get("count", 1) for route in routes]
        labels = [str(count) for count in counts if count > 1]
        actual_labels = re.findall(r"\d+", "\n".join(
            widget.get("message", "") for widget in snapshots["flow-grid"]["widgets"]
            if widget["type"].endswith("FocusableTextWidget")
        ))
        chats = [row["result"] for row in records if row["request"]["op"] == "chat"]
        if not chats:
            raise ValueError("Missing same-run chat result")
        clicks = [message["message"] for message in chats[-1] if "FLOW_ITEM_CLICK row-" in message.get("message", "")]
        indices = [int(re.search(r"FLOW_ITEM_CLICK row-(\d+)", message).group(1)) for message in clicks]
        resources = [row["result"] for row in records if row["request"]["op"] == "resource"]
        final_ui = [row["result"] for row in records if row["request"]["op"] == "ui"]
        metrics = [[widget for widget in state["widgets"] if "maxScrollAmount" in widget] for state in snapshots.values()]
        positions = helper["positions"]
        checks = {
            "client-26.2": records[0]["result"].get("version") == "26.2",
            "logical-640x480": all(state.get("guiScaledWidth") == 640 and state.get("guiScaledHeight") == 480 for state in snapshots.values()),
            "declared-560x342-preserved": plan["canvas"] == {"width": 560, "height": 342} and plan["body-height"] == 350,
            "eighteen-native-item-stacks": len(items) == len(routes) == 18 and [item["count"] for item in items] == counts,
            "per-item-types": [item.get("itemType") for item in items] == [route["item"]["id"] for route in routes],
            "per-item-models": [item.get("itemModel") for item in items] == [route["item"].get("components", {}).get("minecraft:item_model", route["item"]["id"]) for route in routes],
            "native-foil-and-damage-kept": all(item.get("foil") and item.get("damage") == 7 for item in items if item.get("itemType") == "minecraft:diamond_sword"),
            "read-only-count-labels": actual_labels == labels,
            "source-item-tooltips-disabled": all(item.get("tooltipEnabled") is False for item in items),
            "actual-no-scroll": all(metrics) and all(widget["maxScrollAmount"] == 0 and widget["scrollAmount"] == 0 and widget["contentHeight"] == 380 for group in metrics for widget in group),
            "scroll-does-not-move-carriers": positions(snapshots["flow-grid"]) == positions(snapshots["flow-after-scroll"]),
            "four-corner-actions-once-no-gap-action": indices == list(corners),
            "matching-loaded-item-shader": len(resources) == 1 and resources[0].get("sha256") == plan["item-shader-sha256"] and resources[0].get("pack", "").startswith("file/rafter-flow"),
            "close-clears-screen": len(final_ui) == 1 and final_ui[0].get("screen") is None,
        }
        print(json.dumps({"scope": "26.2-native-wire-prototype-only", "run": helper["run_info"](run), "checks": checks,
                          "manual-visual-review": names, "production-integration": False}, ensure_ascii=False, indent=2))
        return 0 if all(checks.values()) else 1
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"check-flow-evidence: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
