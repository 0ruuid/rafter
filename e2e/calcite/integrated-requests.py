#!/usr/bin/env python3
"""按正式构包的布局生成 640×480 物品与 modal 验收操作。"""

import argparse
import json
from pathlib import Path


MENU = "rafterprobe:item-viewport"


def requests(pack):
    layout = json.loads((pack / "item-layouts.json").read_text())
    flows = layout["flows"]
    modal_flows = layout["modal-flows"]
    output = []

    def op(operation, **values):
        output.append({"op": operation, **values})

    def wait():
        # 等待真实客户端接收重绘和点击冷却，不修改引擎节流。
        op("wait", ms=900)

    def click(x, y):
        op("click", x=x, y=y)
        wait()

    def snap(name):
        op("snap", name="integrated-" + name)

    def hover(x, y, name):
        op("hover", x=x, y=y)
        wait()
        snap(name)

    def command(value):
        op("command", value=value)
        wait()

    def body_top(height):
        return min(63, 480 - 5 - min(height, 480 - 33 - 5))

    def modal(page, origin="grid"):
        return modal_flows[f"{MENU}/{page}@from/{MENU}/{origin}"]

    def action(page, target, origin="grid"):
        plan = modal(page, origin)["layout"]["plan"]
        cell = next(cell for cell in plan["buttons"] if cell.get("target") == target)
        box = cell["bounds"]
        left = (640 - plan["bodyWidth"] - 20) // 2 + 10
        click(left + box["x"] + box["width"] // 2,
              body_top(plan["bodyHeight"]) + box["y"] + box["height"] // 2)

    def fill_form():
        variant = modal("form")
        first_input = len(variant["flow"]["blocks"]) + len(variant["layout"]["plan"]["descriptions"])
        op("input", path=f"1.{first_input}", value="Edited name")
        op("input", path=f"1.{first_input + 2}", value="Edited first line\nEdited second line")

    op("start")
    command("version Rafter")
    for path in ("minecraft:shaders/core/position_tex_color.vsh", "rafter:font/images.json",
                 "rafter:font/modal_backdrop/shared/images.json"):
        op("resource", path=path)
    command("rafter open " + MENU)
    snap("grid")
    hover(76, 184, "hover-first")
    hover(238, 184, "hover-custom")
    for point in ((76, 184), (508, 184), (76, 238), (508, 238)):
        click(*point)
    click(103, 184)
    click(220, 391)
    snap("page2")
    click(350, 391)
    click(80, 391)
    snap("modal")
    op("hover", x=76, y=184)
    wait()
    click(76, 184)
    snap("modal-background")
    fill_form()
    action("form", "confirm")
    snap("confirm")
    action("confirm", "confirm")
    snap("return")
    action("form", "cancel")
    snap("restored")
    click(220, 391)
    click(80, 391)
    snap("page2-modal")
    action("form", "cancel")
    snap("page2-restored")

    command("itemprobe occlusion")
    snap("occlusion-before")
    click(80, 391)
    snap("occlusion-modal")
    hover(544, 292, "occlusion-isolated")
    action("form", "cancel", "occlusion")
    snap("occlusion-return")
    op("close")
    command("itemprobe")
    snap("initial-form")
    op("close")
    command("itemprobe empty")
    snap("empty")
    op("close")
    command("itemprobe regions")
    snap("regions")
    flow = flows[f"{MENU}/regions"]
    top = body_top(flow["bodyHeight"] + 30) + 4 + flow["inset"]
    click(274, top + 18)
    snap("regions-left")
    click(514, top + 18)
    snap("regions-right")
    op("chat")
    op("close")
    wait()
    op("ui")
    op("stop")
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, required=True)
    args = parser.parse_args()
    for request in requests(args.pack):
        print(json.dumps(request, ensure_ascii=False))
