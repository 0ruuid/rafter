#!/usr/bin/env python3
"""生成 640×480 布局验收输入；通过标准输入传给 run.mjs，不改客户端布局。"""
import argparse
import json
from pathlib import Path


def minimum_dialog():
    # 不带 description，使用 Codec 接受的最小高度，隔离框架与资源包的布局影响。
    return {
        "type": "minecraft:multi_action",
        "title": "Minimum native carriers: 1px",
        "body": [
            {"type": "minecraft:item", "item": {"id": "minecraft:diamond"},
             "width": 16, "height": 1, "show_decorations": False, "show_tooltip": False}
            for _ in range(18)
        ] + [{
            "type": "minecraft:plain_message", "width": 570,
            "contents": "\n".join(
                "Canvas start (342px)" if row == 0 else "Canvas end (342px)" if row == 37 else "|"
                for row in range(38)
            ),
        }],
        "actions": [{"label": "Close"}],
    }


def requests():
    yield {"op": "start"}
    yield {"op": "command", "value": "version Rafter"}
    yield {"op": "command", "value": "rafter export"}
    yield {"op": "command", "value": "rafter open rafterprobe:item-viewport"}
    yield {"op": "wait", "ms": 1000}
    yield {"op": "resource", "path": "minecraft:shaders/core/position_tex_color.vsh"}
    yield {"op": "snap", "name": "canvas-viewport-18"}
    # 长 SNBT 经服务端函数读取，避免超过聊天命令数据包的长度限制。
    yield {"op": "command", "value": "function rafterprobe:viewport_minimum"}
    yield {"op": "wait", "ms": 1000}
    yield {"op": "snap", "name": "viewport-native-minimum"}
    yield {"op": "chat"}
    yield {"op": "close"}
    yield {"op": "stop"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--datapack", type=Path, help="写入隔离 26.2 测试世界的数据包目录")
    args = parser.parse_args()
    if args.datapack:
        args.datapack.mkdir(parents=True, exist_ok=True)
        (args.datapack / "pack.mcmeta").write_text(json.dumps({
            "pack": {"description": "Rafter viewport limit probe", "min_format": [107, 1], "max_format": [107, 1]},
        }) + "\n")
        function = args.datapack / "data/rafterprobe/function/viewport_minimum.mcfunction"
        function.parent.mkdir(parents=True, exist_ok=True)
        function.write_text("dialog show @s " + json.dumps(minimum_dialog(), separators=(",", ":")) + "\n")
    for request in requests():
        print(json.dumps(request, ensure_ascii=False))
