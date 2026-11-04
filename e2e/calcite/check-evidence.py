#!/usr/bin/env python3
"""核对真实客户端的物品数据、行记录和分页证据；视觉位置与 tooltip 需另行看图。"""

import argparse
import json
import re
import struct
from pathlib import Path


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--evidence", type=Path, required=True)
parser.add_argument("--suite", choices=("canvas", "native"), default="canvas")
args = parser.parse_args()


def snapshot(name):
    state = json.loads((args.evidence / f"{name}.ui.json").read_text())
    if state.get("screen") != "net.minecraft.client.gui.screens.dialog.MultiButtonDialogScreen":
        raise ValueError(f"{name} 不是打开的原生 Dialog")
    if state.get("overlay") is not None:
        raise ValueError(f"{name} 仍在加载或被其他界面覆盖")
    with (args.evidence / f"{name}.png").open("rb") as stream:
        header = stream.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError(f"{name} 缺少有效 PNG 截图")
    if min(struct.unpack(">II", header[16:24])) <= 0:
        raise ValueError(f"{name} 截图尺寸无效")
    return state


def items(state):
    return [w for w in state["widgets"] if w["type"].endswith("ItemDisplayWidget")]


def text(state):
    return re.sub("[\ue000-\uf8ff]", "", "".join(w.get("message", "") for w in state["widgets"]))


def positions(state):
    return [(w["x"], w["y"], w["width"], w["height"]) for w in items(state)]


def row_ids(state):
    return set(re.findall(r"(?:rows|left|right)-\d+", text(state).split("clicked:")[0]))


def ids(source, start, end):
    return {f"{source}-{i}" for i in range(start, end)}


def real_items(state):
    return [w for w in items(state) if w.get("itemModel") != "rafter:empty"]


def complete_components(icons):
    expected = {"minecraft:diamond": 32, "minecraft:oak_planks": 7, "minecraft:diamond_sword": 1, "minecraft:paper": 3}
    return (
        {w["itemType"]: w["count"] for w in icons} == expected
        and any(w.get("itemModel") == "rafterprobe:custom" and w.get("itemName") == "Model sample" for w in icons)
        and any(w.get("foil") and w.get("damage") == 7 for w in icons)
    )


if args.suite == "native":
    baseline = items(snapshot("native-items-fixed"))
    compact = items(snapshot("compact-native-fixed"))
    checks = {
        "native-item-components": len(baseline) == 4 and complete_components(baseline),
        "single-compact-body": len(compact) == 1 and compact[0].get("itemModel") == "rafterprobe:custom",
    }
    visual = ["native-custom-tooltip-fixed"]
else:
    grid = snapshot("canvas-grid-final")
    page2 = snapshot("canvas-grid-page2")
    free = snapshot("canvas-free-final")
    large = snapshot("canvas-large-final")
    initial = snapshot("canvas-regions-initial")
    left = snapshot("canvas-regions-left-next")
    both = snapshot("canvas-regions-both-next")
    records = [json.loads(line) for line in (args.evidence / "rafter-item-probe-actions.jsonl").read_text().splitlines()]
    start = max(i for i, r in enumerate(records) if r["request"].get("op") == "start" and "error" not in r["result"])
    records = records[start:]
    resources = [r["result"] for r in records if r["request"].get("op") == "resource" and "error" not in r["result"]]
    chats = [r["result"] for r in records if r["request"].get("op") == "chat"]
    clicks = [r["message"] for r in chats[-1] if r["message"].startswith("CANVAS_ITEM_CLICK ")]
    builtin_grid = snapshot("builtin-items-grid")
    builtin_left = snapshot("builtin-items-lists-left-next")
    builtin_both = snapshot("builtin-items-lists-both-next")
    checks = {
        "client-26.2": records[0]["result"].get("version") == "26.2",
        "loaded-compiled-item-shader": any(r.get("compiledRoutes") and r.get("cornerPhaseIndependent") and r.get("pack", "").startswith("file/") for r in resources),
        "eighteen-distinct-row-records": len(items(grid)) == 18 and row_ids(grid) == ids("rows", 0, 18),
        "complete-native-item-components": complete_components(items(grid)),
        "native-source-tooltips-disabled": all(w.get("tooltipEnabled") is False for w in items(grid)),
        "short-page-retains-carriers": len(items(page2)) == 18 and len(real_items(page2)) == 6 and row_ids(page2) == ids("rows", 18, 24),
        "paging-keeps-source-geometry": positions(grid) == positions(page2),
        "first-page-action-record": "clicked: rows-0" in text(snapshot("canvas-grid-click-final")),
        "second-page-action-record": "clicked: rows-18" in text(snapshot("canvas-grid-page2-click")),
        "free-and-large-item-data": len(items(free)) == 4 and len(items(large)) == 1 and items(large)[0].get("itemModel") == "rafterprobe:custom",
        "independent-source-records": row_ids(initial) == ids("left", 0, 6) | ids("right", 0, 4),
        "left-page-does-not-change-right": row_ids(left) == ids("left", 6, 12) | ids("right", 0, 4),
        "right-page-does-not-change-left": row_ids(both) == ids("left", 6, 12) | ids("right", 4, 8),
        "independent-region-click-records": "clicked: left-0" in text(left) and "clicked: right-0" in text(snapshot("canvas-regions-right-click")),
        "business-actions-once-per-click": clicks == ["CANVAS_ITEM_CLICK rows-0", "CANVAS_ITEM_CLICK rows-18", "CANVAS_ITEM_CLICK left-0", "CANVAS_ITEM_CLICK right-0"],
        "builtin-grid-navigation": len(items(builtin_grid)) == 6 and "多排网格" in text(builtin_grid),
        "builtin-independent-paging": "旅伴页 2 / 2收藏页 1 / 2" in text(builtin_left) and "旅伴页 2 / 2收藏页 2 / 2" in text(builtin_both),
    }
    visual = ["canvas-grid-diamond-tooltip-final", "canvas-grid-custom-tooltip-final", "canvas-free-final", "canvas-large-final", "canvas-regions-both-next", "builtin-items-grid"]

for name in visual:
    snapshot(name)
print(json.dumps({"suite": args.suite, "checks": checks, "manual-visual-review": visual}, ensure_ascii=False, indent=2))
raise SystemExit(0 if all(checks.values()) else 1)
