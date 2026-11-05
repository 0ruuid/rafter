#!/usr/bin/env python3
"""生成 opaque item dialog 的 640×480 验收操作。"""

import argparse
import json
from pathlib import Path


MENU = "rafterprobe:item-opaque"


def requests(pack):
    layout = json.loads((pack / "item-layouts.json").read_text())
    flow = layout["flows"][MENU + "/home"]
    modal = layout["modal-flows"][MENU + "/confirm@from/" + MENU + "/home"]
    output = []

    def op(operation, **values):
        output.append({"op": operation, **values})

    def wait():
        op("wait", ms=900)

    def click(x, y):
        op("click", x=x, y=y)
        wait()

    def body_top(height):
        return min(63, 480 - 5 - min(height, 480 - 38))

    def action(target):
        plan = modal["layout"]["plan"]
        button = next(button for button in plan["buttons"] if button.get("target") == target)
        box = button["bounds"]
        left = (640 - plan["bodyWidth"] - 20) // 2 + 10
        click(left + box["x"] + box["width"] // 2,
              body_top(plan["bodyHeight"]) + box["y"] + box["height"] // 2)

    left = (640 - flow["width"] - 10 - 20) // 2 + 10 + 5
    top = body_top(flow["bodyHeight"] + 30) + 4 + flow["inset"]

    op("start")
    op("command", value="rafter open " + MENU)
    wait()
    op("snap", name="opaque-page1")
    op("hover", x=left + 274, y=top + 270)
    wait()
    op("snap", name="opaque-tooltip")
    click(left + 530, top + 270)
    op("snap", name="opaque-page2")
    click(left + 480, top + 108)
    op("snap", name="opaque-modal")
    action("cancel")
    op("snap", name="opaque-restored")
    op("close")
    for path in ("minecraft:shaders/core/position_tex_color.vsh",
                 "rafter:font/images.json",
                 "rafter:font/modal_backdrop/shared/images.json"):
        op("resource", path=path)
    op("ui")
    op("stop")
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, required=True)
    args = parser.parse_args()
    for request in requests(args.pack):
        print(json.dumps(request, ensure_ascii=False))
