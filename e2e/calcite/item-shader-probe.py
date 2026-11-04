#!/usr/bin/env python3
"""仅修改隔离客户端的资源包，验证原生物品像素移位；不提供正式 Canvas 能力。"""

import argparse
import json
from pathlib import Path
import zipfile


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--root", type=Path, required=True)
parser.add_argument("--state", type=Path, required=True)
parser.add_argument("--client-jar", type=Path, required=True)
parser.add_argument("--index", type=int, default=0)
parser.add_argument("--dx", type=float, default=64)
parser.add_argument("--dy", type=float, default=0)
parser.add_argument("--scale", type=float, default=2)
args = parser.parse_args()
root = args.root.resolve()
if not root.is_relative_to(Path("/Volumes/iiDisk/environment")):
    parser.error("测试输出必须位于 /Volumes/iiDisk/environment")
manifest = json.loads((root / "setup-manifest.json").read_text())
client = Path(manifest["client-data"]).resolve()
if not client.is_relative_to(Path("/Volumes/iiDisk/environment")) or client.name != "RafterItemProbe":
    parser.error("只能修改外置盘的 RafterItemProbe 隔离实例")
pack = (client / "resourcepacks/rafter-probe").resolve()
if not pack.is_relative_to(client):
    parser.error("资源包路径不能指向隔离实例之外")
state = json.loads(args.state.read_text())
items = [w for w in state["widgets"] if w["type"].endswith("ItemDisplayWidget")]
if not 0 <= args.index < len(items):
    parser.error("没有对应的真实物品控件")
item = items[args.index]
x, y, width, height = (float(item[k]) for k in ("x", "y", "width", "height"))
gui_width, gui_height = (float(state[k]) for k in ("guiScaledWidth", "guiScaledHeight"))
if not 0 < args.scale <= 4:
    parser.error("实验缩放范围为 (0, 4]，不代表框架能力")
target = [x + args.dx, y + args.dy, width * args.scale, height * args.scale]
if target[0] < 0 or target[1] < 0 or target[0] + target[2] > gui_width or target[1] + target[3] > gui_height:
    parser.error("实验目标区域超出当前实际 GUI 尺寸")

function = f"""
// 仅限隔离实验，源坐标来自真实原生控件快照。
void rafterItemProbe(inout vec4 clip) {{
    if (abs(ProjMat[3][3] - 1.0) > 0.0001 || abs(clip.w) < 0.0001) return;
    vec2 dimensions = vec2({gui_width:.8f}, {gui_height:.8f});
    vec2 p = vec2(clip.x / clip.w * 0.5 + 0.5, 0.5 - clip.y / clip.w * 0.5) * dimensions;
    vec2 source = vec2({x:.8f}, {y:.8f});
    vec2 extent = vec2({width:.8f}, {height:.8f});
    if (all(greaterThanEqual(p, source - vec2(0.01))) && all(lessThanEqual(p, source + extent + vec2(0.01)))) {{
        vec2 moved = source + vec2({args.dx:.8f}, {args.dy:.8f}) + (p - source) * {args.scale:.8f};
        clip.xy = vec2(moved.x / dimensions.x * 2.0 - 1.0, 1.0 - moved.y / dimensions.y * 2.0) * clip.w;
    }}
}}
"""


def patch(path, source, text=False):
    if not source.startswith("#version") or "void main()" not in source:
        raise ValueError(f"不支持的原版 shader 结构：{path}")
    relative = path.relative_to(pack)
    backup = root / "evidence/shader-originals" / relative
    backup.parent.mkdir(parents=True, exist_ok=True)
    if not backup.exists():
        backup.write_text(source)
    source = source.replace("void main()", function + "\nvoid main()", 1)
    end = source.rfind("}")
    call = "\n    rafterItemProbe(gl_Position);\n"
    if text:
        call = "\n#if defined(IS_GUI)\n    rafterItemProbe(gl_Position);\n#endif\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source[:end] + call + source[end:])


with zipfile.ZipFile(args.client_jar) as jar:
    shader = "assets/minecraft/shaders/core/position_tex_color.vsh"
    patch(pack / shader, jar.read(shader).decode())
text_paths = list(pack.rglob("text.vsh"))
for path in text_paths:
    source = path.read_text()
    # 重复执行时从第一次保存的基线重新生成，不叠加变换。
    saved = root / "evidence/shader-originals" / path.relative_to(pack)
    patch(path, saved.read_text() if saved.exists() else source, text=True)
plan = {
    "scope": "experimental-native-render-pixels-only",
    "source": item,
    "target": target,
    "scale": args.scale,
    "gui": [gui_width, gui_height],
    "interaction-relocated": False,
    "scroll-clipping-verified": False,
    "production-capability": False,
}
(root / "evidence/shader-plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(plan, ensure_ascii=False))
