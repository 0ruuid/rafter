#!/usr/bin/env python3
"""独立协议原型：标题绘制、正文空带载体、原生文本命中。不是公开配置格式或运行时实现。"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import zlib


ROOT = Path(__file__).resolve().parents[2]
PROFILE = "rafter_v260200"
PADDING = 4
BODY_GAP = 10


def png(path, width, height, pixels):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    raw = b"".join(b"\0" + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-pack", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--datapack", type=Path, required=True)
    args = parser.parse_args()
    fixture = json.loads((ROOT / "e2e/fixtures/flow-canvas.json").read_text())
    width, height = fixture["canvas"]["width"], fixture["canvas"]["height"]
    slots = fixture["slots"]
    capacity = slots["columns"] * slots["rows"]
    assert slots["size"] % 9 == 0 and capacity == 18
    pack = args.output / "resource-pack"
    if pack.exists():
        parser.error("Use a new output directory; previous prototype evidence is never replaced")
    shutil.copytree(args.base_pack, pack)
    shutil.copytree(ROOT / "e2e/fixtures/item-resource-pack/assets", pack / "assets", dirs_exist_ok=True)
    space_font = json.loads((pack / "assets/rafter/font/space.json").read_text())
    advances = space_font["providers"][0]["advances"]
    units = {int(v): k for k, v in advances.items() if v == int(v) and v}
    def space(amount):
        direction = 1 if amount >= 0 else -1
        rest = abs(amount)
        result = ""
        for unit in sorted((u for u in units if u > 0), reverse=True):
            while rest >= unit:
                result += units[unit * direction]
                rest -= unit
        assert rest == 0
        return {"text": result, "font": "rafter:space", "bold": False, "italic": False, "shadow_color": 0}
    def message(parts):
        return {"type": "minecraft:plain_message", "width": width + 10,
                "contents": {"text": "", "extra": parts, "shadow_color": 0}}
    bodies, routes, body_y = [], [], 0
    def append_body(body, native_height):
        nonlocal body_y
        if bodies:
            body_y += BODY_GAP
        bodies.append(body)
        y = body_y
        body_y += native_height
        return y
    samples = fixture["items"]
    def carrier(index):
        stack = samples[index % len(samples)]
        native_width = 1 + index * 2
        y = append_body({"type": "minecraft:item", "item": stack, "width": native_width,
                         "height": 1, "show_decorations": False, "show_tooltip": False}, 1)
        routes.append({"index": index, "native-width": native_width, "source-y": y,
                       "x": slots["x"] + index % slots["columns"] * slots["step"],
                       "y": slots["y"] + index // slots["columns"] * slots["step"],
                       "size": slots["size"], "item": stack})
    for index in range(slots["columns"]):
        carrier(index)
    for row in range(slots["rows"]):
        parts = []
        for line in range(slots["size"] // 9):
            anchor = {"text": "\ue001", "font": "rafter:fill", "shadow_color": 0}
            parts.extend((anchor, space(-9)))
            parts.append(space(1 + slots["x"]))
            for column in range(slots["columns"]):
                index = row * slots["columns"] + column
                amount = samples[index % len(samples)].get("count", 1)
                if line == slots["size"] // 9 - 1 and amount > 1:
                    number = str(amount)
                    cell = {"text": "", "extra": [space(slots["size"] - len(number) * 6),
                            {"text": number, "font": "minecraft:default", "color": "white", "bold": False, "shadow_color": 0}]}
                else:
                    cell = space(slots["size"])
                cell["hover_event"] = {"action": "show_item", **samples[index % len(samples)]}
                # 测试命名空间回执；正式接入继续使用 Rafter 的会话句柄。
                cell["click_event"] = {"action": "custom", "id": f"rafterprobe:flow/{index}"}
                parts.append(cell)
                if column < slots["columns"] - 1:
                    parts.append(space(slots["step"] - slots["size"]))
            used = slots["x"] + (slots["columns"] - 1) * slots["step"] + slots["size"]
            parts.append(space(width + 1 - used))
            parts.extend((space(-9), anchor))
            if line < slots["size"] // 9 - 1:
                parts.append({"text": "\n"})
        y = append_body(message(parts), slots["size"] + PADDING * 2)
        assert y == slots["y"] + row * slots["step"], (y, slots)
    for index in range(slots["columns"], capacity):
        carrier(index)
    # 真实布局盒保持 342+8；只复用空白，不缩小画布、截断内容或隐藏滚动条。
    remaining = height + PADDING * 2 - body_y - BODY_GAP
    assert remaining >= 8 and (remaining - 8) % 9 == 0
    filler = []
    for line in range((remaining - 8) // 9):
        if line:
            filler.append({"text": "\n"})
        filler.append(space(width + 2))
    append_body(message(filler), remaining)
    assert body_y == height + PADDING * 2
    layout_height = body_y + BODY_GAP + 20

    # 画面是普通装饰，不包含物品图片；物品和数量继续由客户端原生渲染。
    pixels = bytearray(bytes((24, 29, 38, 255)) * (width * height))
    def rect(x, y, w, h, color):
        for py in range(y, y + h):
            for px in range(x, x + w):
                offset = (py * width + px) * 4
                pixels[offset:offset + 4] = bytes(color)
    rect(0, 0, width, 3, (115, 167, 255, 255))
    rect(18, 24, width - 36, 2, (54, 69, 89, 255))
    for route in routes:
        x, y, size = route["x"], route["y"], route["size"]
        rect(x, y, size, size, (104, 132, 172, 255))
        rect(x + 1, y + 1, size - 2, size - 2, (12, 17, 25, 255))
    providers, title = [], []
    index = 0
    for y in range(0, height, 248):
        for x in range(0, width, 248):
            tw, th = min(248, width - x), min(248, height - y)
            tile = bytearray(256 * 256 * 4)
            def set_pixel(px, py, color):
                at = (py * 256 + px) * 4
                tile[at:at + 4] = bytes(color)
            for corner in range(4):
                set_pixel((corner & 1) * 255, (corner >> 1) * 255, (82, 70, corner, 255))
            set_pixel(0, 2, (167, 211, 91, 255))
            set_pixel(1, 2, (2, 4, 248, 255))
            for field, value in enumerate((width, height, x, y, tw, th, 2, layout_height)):
                set_pixel(field + 2, 2, (value & 255, value >> 8, 0, 255))
            for py in range(th):
                source = ((y + py) * width + x) * 4
                target = ((py + 4) * 256 + 4) * 4
                tile[target:target + tw * 4] = pixels[source:source + tw * 4]
            texture = f"font/flow/{index}.png"
            png(pack / f"assets/rafterflow/textures/{texture}", 256, 256, tile)
            char = chr(0xE000 + index)
            providers.append({"type": "bitmap", "file": f"rafterflow:{texture}", "height": 4096, "ascent": 7, "chars": [char]})
            title.extend(({"text": char, "font": "rafterflow:canvas", "color": "white", "shadow_color": 0}, space(-4097)))
            index += 1
    def write_json(path, obj):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n")
    metadata = json.loads((pack / "pack.mcmeta").read_text())
    metadata["pack"]["description"] = "Rafter experimental flow proof - not production"
    write_json(pack / "pack.mcmeta", metadata)
    write_json(pack / "assets/rafterflow/font/canvas.json", {"providers": providers})
    dialog = {"type": "minecraft:multi_action", "title": {"text": "", "extra": title},
              "pause": False, "after_action": "none", "body": bodies, "actions": [{"label": "Close"}]}
    write_json(args.output / "dialog.json", dialog)
    args.datapack.mkdir(parents=True, exist_ok=True)
    write_json(args.datapack / "pack.mcmeta", {"pack": {"description": "Rafter flow prototype only", "min_format": [107, 1], "max_format": [107, 1]}})
    function = args.datapack / "data/rafterprobe/function/flow_canvas.mcfunction"
    function.parent.mkdir(parents=True, exist_ok=True)
    command = "dialog show @s " + json.dumps(dialog, ensure_ascii=False, separators=(",", ":")) + "\n"
    function.write_text(command)
    (args.output / "flow_canvas.mcfunction").write_text(command)

    def source_x(route):
        return f"trunc((dimensions.x - {width + 30}.0) * 0.5) + 10.0 + {((width + 10) - route['native-width']) // 2}.0"
    def source_y(route):
        return f"min(63.0, dimensions.y - 5.0 - min({layout_height}.0, max(dimensions.y - 38.0, 0.0))) + {route['source-y']}.0"
    def target(route):
        return f"vec2(trunc((dimensions.x - {width}.0) * 0.5) + {route['x'] + 2}.0, min(63.0, dimensions.y - 5.0 - min({layout_height}.0, max(dimensions.y - 38.0, 0.0))) + {PADDING + route['y'] + 2}.0)"
    item_cases = []
    for route in routes:
        n = route["index"]
        source = f"vec2({source_x(route)}, {source_y(route)})"
        scale = (route["size"] - 4) / 16
        item_cases.append(f"""
        vec2 source{n} = {source};
        vec2 local{n} = position.xy - source{n};
        if ((abs(local{n}.x) < 0.02 || abs(local{n}.x - 16.0) < 0.02) &&
            (abs(local{n}.y) < 0.02 || abs(local{n}.y - 16.0) < 0.02)) {{
            gl_Position = ProjMat * vec4({target(route)} + local{n} * {scale}, position.zw);
            return;
        }}
        """)
    vanilla = (ROOT / "project/pack/src/main/resources/shaders/client/26_2/position_tex_color.vsh").read_text()
    vanilla = vanilla.replace("    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);", "    vec4 position = ModelViewMat * vec4(Position, 1.0);\n    gl_Position = ProjMat * position;")
    vanilla = vanilla.replace("    vertexColor = Color;", "    vertexColor = Color;\n    if (abs(ProjMat[3][3] - 1.0) > 0.0001) return;\n    vec2 dimensions = round(abs(vec2(2.0/ProjMat[0][0], 2.0/ProjMat[1][1])));\n" + "\n".join(item_cases))
    item_shader = pack / f"{PROFILE}/assets/minecraft/shaders/core/position_tex_color.vsh"
    item_shader.write_text(vanilla)
    include = pack / "assets/rafter/shaders/include/dialog_frame_vertex.glsl"
    code = include.read_text()
    code = code.replace("kind > 1", "kind > 2")
    code = code.replace("    } else {\n        vec4 panelLayout", "    } else if (kind == 2) {\n        extent = vec2(source);\n        topLeft = vec2(floor((gui.x-extent.x)*0.5), rafter_modal_body_top(gui, float(layoutIndex), RAFTER_MODAL_FOOTER_HEIGHT) + 4.0);\n    } else {\n        vec4 panelLayout")
    assert "kind == 2" in code
    include.write_text(code)
    write_json(args.output / "plan.json", {"scope": "26.2-prototype-only", "canvas": fixture["canvas"], "body-height": body_y,
               "layout-height": layout_height, "routes": routes, "item-shader-sha256": hashlib.sha256(item_shader.read_bytes()).hexdigest(),
               "native-hit-bands": [{"y": slots["y"] + row * slots["step"], "height": slots["size"]} for row in range(slots["rows"])]})
    print(json.dumps({"pack": str(pack), "body-height": body_y, "layout-height": layout_height, "slots": capacity}))


if __name__ == "__main__":
    main()
