#!/usr/bin/env python3
"""核对正式 Rafter Canvas item/modal flow 的同次 Calcite 证据。"""

import argparse
import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path


SCREEN = "net.minecraft.client.gui.screens.dialog.MultiButtonDialogScreen"
SHADER_PATH = "minecraft:shaders/core/position_tex_color.vsh"
INTEGRATED_SNAPSHOTS = (
    "integrated-grid",
    "integrated-hover-first",
    "integrated-hover-custom",
    "integrated-page2",
    "integrated-modal",
    "integrated-confirm",
    "integrated-return",
    "integrated-restored",
    "integrated-modal-background",
    "integrated-page2-modal",
    "integrated-page2-restored",
    "integrated-occlusion-before",
    "integrated-occlusion-modal",
    "integrated-occlusion-isolated",
    "integrated-occlusion-return",
    "integrated-initial-form",
    "integrated-empty",
    "integrated-regions",
    "integrated-regions-left",
    "integrated-regions-right",
)
EXPECTED_CLICKS = [
    "CANVAS_ITEM_CLICK rows-0",
    "CANVAS_ITEM_CLICK rows-8",
    "CANVAS_ITEM_CLICK rows-9",
    "CANVAS_ITEM_CLICK rows-17",
]
HASH_RE = re.compile(r"^[0-9a-fA-F]{64}$")
MANIFEST_PATHS = {
    "plugin-file": "artifacts/Rafter.jar",
    "contract-file": "artifacts/contract/authoring.json",
    "resource-pack": "artifacts/resource-pack",
}
FONT_FILES = {
    "image-font-sha256": "assets/rafter/font/images.json",
    "modal-backdrop-font-sha256": "assets/rafter/font/modal_backdrop/shared/images.json",
}
MANIFEST_FIELDS = (
    "engine-version",
    "contract-version",
    "plugin-sha256",
    "item-shader-sha256",
    "plugin-file",
    "contract-file",
    "resource-pack",
    "image-font-sha256",
    "modal-backdrop-font-sha256",
)


class EvidenceError(ValueError):
    """证据不足或无法安全归属于同一次运行。"""


def load_checker_helper():
    path = Path(__file__).with_name("check-evidence.py")
    spec = importlib.util.spec_from_file_location("rafter_check_evidence", path)
    if spec is None or spec.loader is None:
        raise EvidenceError(f"无法加载原 checker: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_manifest(run_dir, manifest_path):
    run_dir = run_dir.resolve()
    try:
        path = manifest_path.resolve(strict=True)
    except OSError as error:
        raise EvidenceError(f"缺少有效 manifest {manifest_path}: {error}") from error
    try:
        path.relative_to(run_dir)
    except ValueError as error:
        raise EvidenceError(f"--manifest 必须位于同一次 run 产物目录 {run_dir} 内") from error
    try:
        manifest = json.loads(path.read_bytes())
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceError(f"manifest 不是有效 JSON: {error}") from error
    if not isinstance(manifest, dict):
        raise EvidenceError("manifest 顶层必须是对象")

    missing = [field for field in MANIFEST_FIELDS if field not in manifest]
    if missing:
        raise EvidenceError(f"manifest 缺少固定字段: {', '.join(missing)}")
    if not isinstance(manifest["engine-version"], str) or not manifest["engine-version"]:
        raise EvidenceError("manifest.engine-version 必须是非空字符串")
    if (
        not isinstance(manifest["contract-version"], int)
        or isinstance(manifest["contract-version"], bool)
        or manifest["contract-version"] != 16
    ):
        raise EvidenceError("manifest.contract-version 必须是整数 16")
    for field in ("plugin-sha256", "item-shader-sha256", *FONT_FILES):
        if not isinstance(manifest[field], str) or not HASH_RE.fullmatch(manifest[field]):
            raise EvidenceError(f"manifest.{field} 必须是 64 位 SHA-256")
    for field, expected in MANIFEST_PATHS.items():
        if manifest[field] != expected:
            raise EvidenceError(f"manifest.{field} 必须固定为 {expected}")
    return manifest


def successful(record, helper):
    return helper.successful_result(record.get("result"))


def operation_succeeded(record):
    result = record.get("result")
    return result is not None and not (isinstance(result, dict) and "error" in result)


def snapshot_hashes_present(run, helper):
    problems = []
    for name in INTEGRATED_SNAPSHOTS:
        records = [
            record
            for record in run["records"]
            if record["request"].get("op") == "snap"
            and record["request"].get("name") == name
            and successful(record, helper)
        ]
        for record in records:
            result = record["result"]
            for field in ("pngSha256", "uiSha256"):
                if not isinstance(result.get(field), str) or not HASH_RE.fullmatch(result[field]):
                    problems.append(f"snapshot {name} 记录缺少有效 {field}")
    if problems:
        raise EvidenceError("；".join(problems))


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(131072), b""):
            digest.update(block)
    return digest.hexdigest()


def artifact_path(run_dir, relative, kind):
    root = run_dir.resolve()
    path = (root / relative).resolve()
    try:
        path.relative_to(root)
    except ValueError as error:
        raise EvidenceError(f"manifest {kind} 不在同一次 run 目录内: {relative}") from error
    if kind == "resource-pack":
        if not path.is_dir():
            raise EvidenceError(f"同一次 run 缺少 resource-pack 目录: {relative}")
    elif not path.is_file():
        raise EvidenceError(f"同一次 run 缺少 {kind} 文件: {relative}")
    return path


def validate_artifacts(run_dir, manifest):
    plugin = artifact_path(run_dir, manifest["plugin-file"], "plugin-file")
    contract = artifact_path(run_dir, manifest["contract-file"], "contract-file")
    pack = artifact_path(run_dir, manifest["resource-pack"], "resource-pack")
    actual_plugin = sha256_file(plugin)
    if actual_plugin != manifest["plugin-sha256"].lower():
        raise EvidenceError(
            f"plugin-file SHA256 不匹配: 期望 {manifest['plugin-sha256']}，实际 {actual_plugin}"
        )
    try:
        contract_value = json.loads(contract.read_bytes())
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceError(f"contract-file 不是有效 JSON: {error}") from error
    if not isinstance(contract_value, dict):
        raise EvidenceError("contract-file 顶层必须是对象")
    for field in ("engine-version", "contract-version"):
        if field not in contract_value:
            raise EvidenceError(f"contract-file 缺少字段: {field}")
        if contract_value[field] != manifest[field]:
            raise EvidenceError(
                f"contract-file.{field} 与 manifest 不匹配: "
                f"期望 {manifest[field]!r}，实际 {contract_value[field]!r}"
            )
    shader = pack / "rafter_v260200/assets/minecraft/shaders/core/position_tex_color.vsh"
    if not shader.is_file() or sha256_file(shader) != manifest["item-shader-sha256"].lower():
        raise EvidenceError("resource-pack 的 26.2 item shader SHA256 与 manifest 不匹配")
    actual_fonts = {}
    for field, relative in FONT_FILES.items():
        font = pack / relative
        if not font.is_file():
            raise EvidenceError(f"resource-pack 缺少字体文件: {relative}")
        actual_fonts[field] = sha256_file(font)
        if actual_fonts[field] != manifest[field].lower():
            raise EvidenceError(
                f"resource-pack/{relative} SHA256 不匹配: "
                f"期望 {manifest[field]}，实际 {actual_fonts[field]}"
            )
    return pack


def item_widgets(state):
    return [
        widget
        for widget in state.get("widgets", [])
        if isinstance(widget, dict) and str(widget.get("type", "")).endswith("ItemDisplayWidget")
    ]


def real_items(state):
    return [widget for widget in item_widgets(state) if widget.get("itemModel") != "rafter:empty"]


def item_signature(widget):
    return tuple(
        widget.get(field)
        for field in ("itemType", "count", "itemModel", "damage", "foil", "itemName")
    )


def geometry(widget):
    fields = ("x", "y", "width", "height")
    values = tuple(widget.get(field) for field in fields)
    return values if all(value is not None for value in values) else None


def scroll_containers(state):
    return [
        widget
        for widget in state.get("widgets", [])
        if isinstance(widget, dict)
        and str(widget.get("type", "")).endswith("ScrollableLayout$Container")
    ]


def input_widgets(state):
    return [
        widget
        for widget in state.get("widgets", [])
        if isinstance(widget, dict)
        and str(widget.get("type", "")).endswith(("EditBox", "MultiLineEditBox"))
    ]


def input_values_present(state):
    widgets = input_widgets(state)
    return bool(widgets) and all(
        isinstance(widget.get("value"), str) and bool(widget["value"].strip())
        for widget in widgets
    )


def values(state):
    return [widget.get("value") for widget in input_widgets(state)]


def row_ids(state, prefix):
    return set(re.findall(re.escape(prefix) + r"-\d+", str(state.get("title", ""))))


def empty_filler(widget):
    return widget.get("itemModel") == "rafter:empty" and widget.get("node") in (None, "")


def all_chat_messages(run):
    return [
        message.get("message")
        for record in run["records"]
        if record["request"].get("op") == "chat"
        and isinstance(record.get("result"), list)
        for message in record["result"]
        if isinstance(message, dict) and isinstance(message.get("message"), str)
    ]


def canvas_clicks(run, helper):
    try:
        return helper.canvas_clicks(run)
    except (ValueError, KeyError, TypeError, IndexError) as error:
        raise EvidenceError(str(error)) from error


def resource_hash_check(run, path, expected, helper, shader=False):
    resources = [
        record["result"]
        for record in run["records"]
        if record["request"].get("op") == "resource"
        and record["request"].get("path") == path
        and successful(record, helper)
    ]
    loaded = [
        resource
        for resource in resources
        if (not shader or (resource.get("compiledRoutes") and resource.get("cornerPhaseIndependent")))
        and str(resource.get("pack", "")).startswith("file/")
    ]
    expected = expected.lower()
    return bool(loaded) and bool(resources) and all(
        isinstance(resource.get("sha256"), str)
        and resource["sha256"].lower() == expected
        for resource in resources
    )


def close_check(run):
    close_indices = [
        index
        for index, record in enumerate(run["records"])
        if record["request"].get("op") == "close"
        and record.get("result") is not None
        and not (isinstance(record.get("result"), dict) and "error" in record["result"])
    ]
    if not close_indices:
        return False
    after_close = [
        record
        for index, record in enumerate(run["records"])
        if index > max(close_indices)
        and record["request"].get("op") == "ui"
        and isinstance(record.get("result"), dict)
        and "error" not in record["result"]
    ]
    return bool(after_close) and after_close[-1]["result"].get("screen") is None


def modal_panels_fit(pack, gui_width, gui_height):
    path = pack / "item-layouts.json"
    if not path.is_file():
        return False
    layouts = json.loads(path.read_bytes())
    variants = [
        value["layout"] for key, value in layouts.get("modal-flows", {}).items()
        if key.startswith("rafterprobe:item-viewport/")
    ]
    def fits(layout):
        height = layout["plan"]["bodyHeight"]
        top = min(63, gui_height - 5 - min(height, max(gui_height - 33 - 5, 0)))
        return (layout["width"] <= gui_width and top + layout["offsetY"] >= 0
                and top + layout["offsetY"] + layout["height"] <= gui_height)
    return bool(variants) and all(fits(layout) for layout in variants)


def check_integrated(run_dir, manifest_path, requested_start=None):
    helper = load_checker_helper()
    evidence = run_dir / "evidence"
    records = helper.load_records(evidence)
    try:
        run, snapshots = helper.prepare_run(
            evidence,
            records,
            requested_start,
            INTEGRATED_SNAPSHOTS,
        )
    except (ValueError, KeyError, TypeError, IndexError) as error:
        raise EvidenceError(str(error)) from error
    snapshot_hashes_present(run, helper)
    manifest = load_manifest(run_dir, manifest_path)
    pack = validate_artifacts(run_dir, manifest)
    clicks = canvas_clicks(run, helper)
    grid = snapshots["integrated-grid"]
    page2 = snapshots["integrated-page2"]
    modal = snapshots["integrated-modal"]
    confirm = snapshots["integrated-confirm"]
    returned = snapshots["integrated-return"]
    restored = snapshots["integrated-restored"]
    short_modal = snapshots["integrated-page2-modal"]
    short_restored = snapshots["integrated-page2-restored"]
    occlusion = snapshots["integrated-occlusion-before"]
    occlusion_modal = snapshots["integrated-occlusion-modal"]
    occlusion_return = snapshots["integrated-occlusion-return"]
    initial = snapshots["integrated-initial-form"]
    empty = snapshots["integrated-empty"]
    regions = snapshots["integrated-regions"]
    left = snapshots["integrated-regions-left"]
    right = snapshots["integrated-regions-right"]
    grid_items = item_widgets(grid)
    grid_real = real_items(grid)
    page2_items = item_widgets(page2)
    page2_real = real_items(page2)
    source = [item_signature(widget) for widget in grid_real]
    background_match = all(
        [item_signature(widget) for widget in real_items(state)] == source
        for state in (modal, confirm, returned)
    )
    containers = [
        container
        for state in snapshots.values()
        for container in scroll_containers(state)
    ]
    version = str(manifest["engine-version"])
    version_seen = any(re.search(r"\bRafter version\s+" + re.escape(version) + r"\b", message) for message in all_chat_messages(run))
    click_records = [
        record
        for record in run["records"]
        if record["request"].get("op") == "click" and operation_succeeded(record)
    ]
    checks = {
        "client-26.2": run["records"][0].get("result", {}).get("version") == "26.2",
        "integrated-gui-640x480": all(
            state.get("guiScaledWidth") == 640 and state.get("guiScaledHeight") == 480
            for state in snapshots.values()
        ),
        "integrated-scroll-max-zero": bool(containers) and all(
            container.get("maxScrollAmount") == 0 for container in containers
        ),
        "modal-panels-fit-640x480": modal_panels_fit(pack, 640, 480),
        "grid-real-items-18": len(grid_real) == 18,
        "page2-real-6-and-empty-fillers": (
            len(page2_items) >= 18
            and len(page2_real) == 6
            and all(empty_filler(widget) for widget in page2_items if widget not in page2_real)
        ),
        "modal-background-items-match-grid": background_match,
        "modal-input-values-present": input_values_present(modal),
        "modal-item-tooltips-disabled": bool(item_widgets(modal)) and all(
            widget.get("tooltipEnabled") is False for widget in item_widgets(modal)
        ),
        "confirm-background-items-match-grid": [item_signature(widget) for widget in real_items(confirm)] == source,
        "confirm-input-values-present": input_values_present(confirm),
        "return-background-items-match-grid": [item_signature(widget) for widget in real_items(returned)] == source,
        "return-input-values-present": input_values_present(returned),
        "submitted-values-survive-modal-replacement": (
            values(confirm) == ["Edited name"]
            and values(returned) == ["Edited name", "Edited first line\nEdited second line"]
        ),
        "restored-18-and-source-geometry": len(item_widgets(restored)) == 18
        and [geometry(widget) for widget in item_widgets(restored)] == [geometry(widget) for widget in grid_items],
        "restored-selection-preserved": "Selection: rows-17" in str(restored.get("title")),
        "short-page-modal-and-return-preserve-items": (
            len(real_items(short_modal)) == 6
            and [item_signature(widget) for widget in real_items(short_modal)]
            == [item_signature(widget) for widget in page2_real]
            == [item_signature(widget) for widget in real_items(short_restored)]
            and [geometry(widget) for widget in item_widgets(short_restored)]
            == [geometry(widget) for widget in page2_items]
        ),
        "irregular-large-models-survive-modal": (
            len(real_items(occlusion)) == 4
            and [item_signature(widget) for widget in real_items(occlusion)]
            == [item_signature(widget) for widget in real_items(occlusion_modal)]
            == [item_signature(widget) for widget in real_items(occlusion_return)]
        ),
        "irregular-background-tooltips-disabled": bool(item_widgets(occlusion_modal)) and all(
            widget.get("tooltipEnabled") is False for widget in item_widgets(occlusion_modal)
        ),
        "api-initial-modal-prefilled": (
            len(real_items(initial)) == 18
            and values(initial) == ["Initial name", "First line\nSecond line"]
        ),
        "empty-page-keeps-only-empty-carriers": len(item_widgets(empty)) >= 18
        and all(empty_filler(widget) for widget in item_widgets(empty)),
        "independent-regions-have-ten-items": all(
            len(real_items(state)) == 10 for state in (regions, left, right)
        ),
        "left-pagination-preserves-right": (
            row_ids(regions, "left") == {f"left-{i}" for i in range(6)}
            and row_ids(left, "left") == {f"left-{i}" for i in range(6, 12)}
            and row_ids(regions, "right") == row_ids(left, "right")
            == {f"right-{i}" for i in range(4)}
        ),
        "right-pagination-preserves-left": (
            row_ids(left, "left") == row_ids(right, "left")
            and row_ids(right, "right") == {f"right-{i}" for i in range(4, 8)}
        ),
        "chat-exact-canvas-item-clicks": clicks == EXPECTED_CLICKS,
        "modal-background-and-gap-no-extra-clicks": clicks == EXPECTED_CLICKS and len(click_records) >= len(EXPECTED_CLICKS) + 2,
        "manifest-version-observed": version_seen,
        "loaded-item-shader-hash-matches-manifest": resource_hash_check(
            run, SHADER_PATH, manifest["item-shader-sha256"], helper, shader=True
        ),
        "loaded-image-font-hash-matches-resource-pack": resource_hash_check(
            run, "rafter:font/images.json", manifest["image-font-sha256"], helper
        ),
        "loaded-modal-backdrop-font-hash-matches-resource-pack": resource_hash_check(
            run,
            "rafter:font/modal_backdrop/shared/images.json",
            manifest["modal-backdrop-font-sha256"],
            helper,
        ),
        "close-clears-screen": close_check(run),
        "stop-succeeded": bool(helper.successful_stop_indices(run)),
    }
    return checks, list(INTEGRATED_SNAPSHOTS), run


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--run-start", help="精确匹配某条 start 记录的时间戳；省略时使用最后一个 start 段")
    args = parser.parse_args(argv)
    try:
        checks, visual, run = check_integrated(args.run, args.manifest, args.run_start)
    except (EvidenceError, OSError, json.JSONDecodeError, KeyError, TypeError, ValueError, IndexError) as error:
        print(f"check-integrated-evidence: {error}", file=sys.stderr)
        return 2
    print(json.dumps({
        "suite": "integrated",
        "run": load_checker_helper().run_info(run),
        "checks": checks,
        "manual-visual-review": visual,
        "manual-visual-review-note": "Widget 坐标只能证明结构证据；Shader 后视觉位置和 tooltip 必须逐图人工查看。",
    }, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
