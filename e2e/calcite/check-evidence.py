#!/usr/bin/env python3
"""核对真实客户端的物品数据、行记录和分页证据；视觉位置与 tooltip 需另行看图。"""

import argparse
import hashlib
import json
import re
import struct
import sys
from pathlib import Path


SCREEN = "net.minecraft.client.gui.screens.dialog.MultiButtonDialogScreen"
EXPECTED_CANVAS_CLICKS = [
    "CANVAS_ITEM_CLICK rows-0",
    "CANVAS_ITEM_CLICK rows-18",
    "CANVAS_ITEM_CLICK left-0",
    "CANVAS_ITEM_CLICK right-0",
]
CANVAS_SNAPSHOTS = (
    "canvas-grid-final",
    "canvas-grid-page2",
    "canvas-free-final",
    "canvas-large-final",
    "canvas-regions-initial",
    "canvas-regions-left-next",
    "canvas-regions-both-next",
    "canvas-grid-click-final",
    "canvas-grid-page2-click",
    "canvas-regions-right-click",
    "builtin-items-grid",
    "builtin-items-lists-left-next",
    "builtin-items-lists-both-next",
    "canvas-grid-diamond-tooltip-final",
    "canvas-grid-custom-tooltip-final",
)
NATIVE_SNAPSHOTS = (
    "native-items-fixed",
    "compact-native-fixed",
    "native-custom-tooltip-fixed",
)
VIEWPORT_SNAPSHOTS = ("canvas-viewport-18", "viewport-native-minimum")
VIEWPORT_FIELDS = ("contentHeight", "maxScrollAmount", "scrollAmount")
BUILD_FIELDS = ("engine-version", "contract-version", "plugin-sha256", "item-shader-sha256")


class EvidenceError(ValueError):
    """证据不足以完成核对。"""


def successful_result(result):
    return isinstance(result, dict) and "error" not in result


def read_snapshot(evidence, name):
    ui_path = evidence / f"{name}.ui.json"
    png_path = evidence / f"{name}.png"
    try:
        ui_bytes = ui_path.read_bytes()
        state = json.loads(ui_bytes)
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceError(f"{name} 缺少有效 UI 证据: {error}") from error
    if not isinstance(state, dict):
        raise EvidenceError(f"{name} 的 UI 证据不是对象")
    if state.get("screen") != SCREEN:
        raise EvidenceError(f"{name} 不是打开的原生 Dialog")
    if state.get("overlay") is not None:
        raise EvidenceError(f"{name} 仍在加载或被其他界面覆盖")
    try:
        png_bytes = png_path.read_bytes()
    except OSError as error:
        raise EvidenceError(f"{name} 缺少 PNG 截图: {error}") from error
    if len(png_bytes) < 24 or png_bytes[:8] != b"\x89PNG\r\n\x1a\n" or png_bytes[12:16] != b"IHDR":
        raise EvidenceError(f"{name} 缺少有效 PNG 截图")
    if min(struct.unpack(">II", png_bytes[16:24])) <= 0:
        raise EvidenceError(f"{name} 截图尺寸无效")
    return state, ui_bytes, png_bytes


def items(state):
    return [widget for widget in state["widgets"] if widget["type"].endswith("ItemDisplayWidget")]


def text(state):
    return re.sub("[\ue000-\uf8ff]", "", "".join(widget.get("message", "") for widget in state["widgets"]))


def positions(state):
    return [(widget["x"], widget["y"], widget["width"], widget["height"]) for widget in items(state)]


def row_ids(state):
    return set(re.findall(r"(?:rows|left|right)-\d+", text(state).split("clicked:")[0]))


def ids(source, start, end):
    return {f"{source}-{i}" for i in range(start, end)}


def real_items(state):
    return [widget for widget in items(state) if widget.get("itemModel") != "rafter:empty"]


def complete_components(icons):
    expected = {"minecraft:diamond": 32, "minecraft:oak_planks": 7, "minecraft:diamond_sword": 1, "minecraft:paper": 3}
    return (
        {widget["itemType"]: widget["count"] for widget in icons} == expected
        and any(widget.get("itemModel") == "rafterprobe:custom" and widget.get("itemName") == "Model sample" for widget in icons)
        and any(widget.get("foil") and widget.get("damage") == 7 for widget in icons)
    )


def load_records(evidence):
    action_file = evidence / "rafter-item-probe-actions.jsonl"
    try:
        lines = action_file.read_text().splitlines()
    except OSError as error:
        raise EvidenceError(f"缺少运行记录 {action_file}: {error}") from error
    records = []
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise EvidenceError(f"运行记录第 {line_number} 行不是有效 JSON: {error}") from error
        if not isinstance(record, dict) or not isinstance(record.get("request"), dict):
            raise EvidenceError(f"运行记录第 {line_number} 行缺少 request")
        records.append(record)
    if not records:
        raise EvidenceError("运行记录为空")
    return records


def split_runs(records):
    starts = [
        index
        for index, record in enumerate(records)
        if record["request"].get("op") == "start"
    ]
    if not starts:
        raise EvidenceError("运行记录中没有 start 边界")
    boundaries = starts + [len(records)]
    return [
        {"start": start, "end": end, "records": records[start:end]}
        for start, end in zip(boundaries, boundaries[1:])
    ]


def run_label(run):
    first = run["records"][0]
    last = run["records"][-1]
    return (
        f"run 记录 {run['start'] + 1}-{run['end']}"
        f"（{first.get('time', 'unknown')} 至 {last.get('time', 'unknown')}）"
    )


def run_info(run):
    stop_times = [
        record.get("time")
        for record in run["records"]
        if record["request"].get("op") == "stop"
        and isinstance(record.get("result"), dict)
        and record["result"].get("stopped") is True
    ]
    return {
        "recordStart": run["start"] + 1,
        "recordEnd": run["end"],
        "start": run["records"][0].get("time"),
        "end": run["records"][-1].get("time"),
        "stop": stop_times[-1] if stop_times else None,
    }


def operation_errors(run):
    failures = []
    for index, record in enumerate(run["records"], run["start"]):
        result = record.get("result")
        if isinstance(result, dict) and "error" in result:
            operation = record["request"].get("op", "?")
            code = f" ({result['code']})" if result.get("code") else ""
            failures.append(f"记录 {index + 1} {operation}{code}: {result['error']}")
    return failures


def failed_snapshots(run, name):
    failures = []
    for index, record in enumerate(run["records"], run["start"]):
        request = record["request"]
        result = record.get("result")
        if request.get("op") == "snap" and request.get("name") == name and isinstance(result, dict) and "error" in result:
            code = f" ({result['code']})" if result.get("code") else ""
            failures.append(f"记录 {index + 1}{code}: {result['error']}")
    return failures


def successful_stop_indices(run):
    return [
        index
        for index, record in enumerate(run["records"], run["start"])
        if record["request"].get("op") == "stop"
        and isinstance(record.get("result"), dict)
        and record["result"].get("stopped") is True
    ]


def select_run(records, requested_start):
    runs = split_runs(records)
    if requested_start is None:
        return runs[-1]
    matches = [
        run
        for run in runs
        if run["records"][0].get("time") == requested_start
    ]
    if not matches:
        available = ", ".join(run["records"][0].get("time", "unknown") for run in runs)
        raise EvidenceError(f"--run-start 必须精确匹配 start 时间戳 {requested_start!r}；可用值: {available}")
    if len(matches) > 1:
        raise EvidenceError(f"--run-start 时间戳 {requested_start!r} 对应多个 start 段")
    return matches[0]


def prepare_run(evidence, records, requested_start, required_snapshots):
    run = select_run(records, requested_start)
    problems = []
    start_result = run["records"][0].get("result")
    if not successful_result(start_result):
        problems.append("start 未成功")
    stops = successful_stop_indices(run)
    if not stops:
        problems.append("stop 未成功")
    failures = operation_errors(run)
    if failures:
        problems.append(f"存在操作错误: {'；'.join(failures)}")

    snapshots = {}
    for name in required_snapshots:
        candidates = [
            (index, record)
            for index, record in enumerate(run["records"], run["start"])
            if record["request"].get("op") == "snap"
            and record["request"].get("name") == name
            and successful_result(record.get("result"))
        ]
        if not candidates:
            failed = failed_snapshots(run, name)
            detail = f"缺少选定段内成功的 snapshot {name}"
            if failed:
                detail += f"（失败记录: {'; '.join(failed)}）"
            problems.append(detail)
            continue
        try:
            state, ui_bytes, png_bytes = read_snapshot(evidence, name)
        except EvidenceError as error:
            problems.append(str(error))
            continue
        for index, result_record in candidates:
            if stops and index >= max(stops):
                problems.append(f"snapshot {name} 不在成功 stop 之前")
            result = result_record["result"]
            if "ui" not in result:
                problems.append(f"snapshot {name} 记录缺少 result.ui")
            elif result["ui"] != state:
                problems.append(f"snapshot {name} 的 result.ui 与 {name}.ui.json 不一致")
            for field, payload in (("pngSha256", png_bytes), ("uiSha256", ui_bytes)):
                if field not in result:
                    continue
                actual = hashlib.sha256(payload).hexdigest()
                expected = result[field]
                if not isinstance(expected, str) or expected.lower() != actual:
                    problems.append(f"snapshot {name} 的 {field} 与实际文件 SHA256 不一致（期望 {expected!r}，实际 {actual}）")
        snapshots[name] = state

    if problems:
        raise EvidenceError(f"{run_label(run)}：" + "；".join(problems))
    return run, snapshots


def canvas_clicks(run):
    chats = [
        record["result"]
        for record in run["records"]
        if record["request"].get("op") == "chat" and isinstance(record.get("result"), list)
    ]
    if not chats:
        raise EvidenceError(f"{run_label(run)}：缺少选定段内成功的 chat 结果")
    return [
        message["message"]
        for message in chats[-1]
        if isinstance(message, dict)
        and isinstance(message.get("message"), str)
        and message["message"].startswith("CANVAS_ITEM_CLICK ")
    ]


def viewport_metadata(evidence, run):
    start_result = run["records"][0].get("result")
    if not successful_result(start_result) or start_result.get("version") != "26.2":
        raise EvidenceError(f"{run_label(run)}：start.version 必须为 26.2")
    build_path = evidence.parent / "build.json"
    try:
        build = json.loads(build_path.read_bytes())
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceError(f"{run_label(run)}：缺少有效 build.json: {error}") from error
    missing = [field for field in BUILD_FIELDS if field not in build or build[field] in (None, "")]
    if missing:
        raise EvidenceError(f"{run_label(run)}：build.json 缺少字段: {', '.join(missing)}")
    if str(build["engine-version"]) != "0.0.5":
        raise EvidenceError(f"{run_label(run)}：build.json engine-version 不是 0.0.5")

    messages = [
        message["message"]
        for record in run["records"]
        if record["request"].get("op") == "chat" and isinstance(record.get("result"), list)
        for message in record["result"]
        if isinstance(message, dict) and isinstance(message.get("message"), str)
    ]
    version = str(build["engine-version"])
    if not any(re.search(r"\bRafter version\s+" + re.escape(version) + r"\b", message) for message in messages):
        raise EvidenceError(f"{run_label(run)}：chat 缺少 Rafter version {version}")

    resources = [
        record["result"]
        for record in run["records"]
        if record["request"].get("op") == "resource"
        and record["request"].get("path") == "minecraft:shaders/core/position_tex_color.vsh"
        and successful_result(record.get("result"))
    ]
    if not resources:
        raise EvidenceError(f"{run_label(run)}：缺少 item shader resource 记录")
    loaded_resources = [
        resource
        for resource in resources
        if resource.get("compiledRoutes")
        and resource.get("cornerPhaseIndependent")
        and str(resource.get("pack", "")).startswith("file/")
    ]
    if not loaded_resources:
        raise EvidenceError(f"{run_label(run)}：同段 resource 未确认已加载生成的 item shader")
    expected_hash = str(build["item-shader-sha256"]).lower()
    mismatches = [
        str(resource.get("sha256"))
        for resource in resources
        if not isinstance(resource.get("sha256"), str) or resource["sha256"].lower() != expected_hash
    ]
    if mismatches:
        raise EvidenceError(
            f"{run_label(run)}：resource.sha256 与 build.json item-shader-sha256 不一致"
            f"（期望 {expected_hash}，记录 {mismatches!r}）"
        )
    return build


def viewport_containers(state):
    return [
        widget
        for widget in state["widgets"]
        if widget["type"].endswith("ScrollableLayout$Container")
    ]


def check_viewport(evidence, requested_start):
    records = load_records(evidence)
    run, snapshots = prepare_run(evidence, records, requested_start, VIEWPORT_SNAPSHOTS)
    viewport_metadata(evidence, run)
    canvas = snapshots["canvas-viewport-18"]
    native = snapshots["viewport-native-minimum"]
    states = (canvas, native)
    container_groups = [viewport_containers(state) for state in states]
    containers = [container for group in container_groups for container in group]
    checks = {
        "viewport-client-26.2": True,
        "viewport-loaded-item-shader": True,
        "viewport-640x480": all(
            state.get("guiScaledWidth") == 640 and state.get("guiScaledHeight") == 480
            for state in states
        ),
        "canvas-viewport-18-items": len(items(canvas)) == 18,
        "viewport-native-minimum-18-one-pixel-items": len(items(native)) == 18 and all(
            widget.get("height") == 1 for widget in items(native)
        ),
        "viewport-container-metrics": all(container_groups) and all(
            field in container for container in containers for field in VIEWPORT_FIELDS
        ),
        "viewport-max-scroll-zero": all(container_groups) and all(
            container.get("maxScrollAmount") == 0 for container in containers
        ),
    }
    return checks, [], run


def check_native(evidence, requested_start):
    records = load_records(evidence)
    run, snapshots = prepare_run(evidence, records, requested_start, NATIVE_SNAPSHOTS)
    baseline = items(snapshots["native-items-fixed"])
    compact = items(snapshots["compact-native-fixed"])
    checks = {
        "native-item-components": len(baseline) == 4 and complete_components(baseline),
        "single-compact-body": len(compact) == 1 and compact[0].get("itemModel") == "rafterprobe:custom",
    }
    return checks, ["native-custom-tooltip-fixed"], run


def check_canvas(evidence, requested_start):
    records = load_records(evidence)
    run, snapshots = prepare_run(evidence, records, requested_start, CANVAS_SNAPSHOTS)
    clicks = canvas_clicks(run)
    grid = snapshots["canvas-grid-final"]
    page2 = snapshots["canvas-grid-page2"]
    free = snapshots["canvas-free-final"]
    large = snapshots["canvas-large-final"]
    initial = snapshots["canvas-regions-initial"]
    left = snapshots["canvas-regions-left-next"]
    both = snapshots["canvas-regions-both-next"]
    builtin_grid = snapshots["builtin-items-grid"]
    builtin_left = snapshots["builtin-items-lists-left-next"]
    builtin_both = snapshots["builtin-items-lists-both-next"]
    checks = {
        "client-26.2": run["records"][0]["result"].get("version") == "26.2",
        "loaded-compiled-item-shader": any(
            record["result"].get("compiledRoutes")
            and record["result"].get("cornerPhaseIndependent")
            and record["result"].get("pack", "").startswith("file/")
            for record in run["records"]
            if record["request"].get("op") == "resource" and successful_result(record.get("result"))
        ),
        "eighteen-distinct-row-records": len(items(grid)) == 18 and row_ids(grid) == ids("rows", 0, 18),
        "complete-native-item-components": complete_components(items(grid)),
        "native-source-tooltips-disabled": all(widget.get("tooltipEnabled") is False for widget in items(grid)),
        "short-page-retains-carriers": len(items(page2)) == 18 and len(real_items(page2)) == 6 and row_ids(page2) == ids("rows", 18, 24),
        "paging-keeps-source-geometry": positions(grid) == positions(page2),
        "first-page-action-record": "clicked: rows-0" in text(snapshots["canvas-grid-click-final"]),
        "second-page-action-record": "clicked: rows-18" in text(snapshots["canvas-grid-page2-click"]),
        "free-and-large-item-data": len(items(free)) == 4 and len(items(large)) == 1 and items(large)[0].get("itemModel") == "rafterprobe:custom",
        "independent-source-records": row_ids(initial) == ids("left", 0, 6) | ids("right", 0, 4),
        "left-page-does-not-change-right": row_ids(left) == ids("left", 6, 12) | ids("right", 0, 4),
        "right-page-does-not-change-left": row_ids(both) == ids("left", 6, 12) | ids("right", 4, 8),
        "independent-region-click-records": "clicked: left-0" in text(left) and "clicked: right-0" in text(snapshots["canvas-regions-right-click"]),
        "business-actions-once-per-click": clicks == EXPECTED_CANVAS_CLICKS,
        "builtin-grid-navigation": len(items(builtin_grid)) == 6 and "多排网格" in text(builtin_grid),
        "builtin-independent-paging": "旅伴页 2 / 2收藏页 1 / 2" in text(builtin_left) and "旅伴页 2 / 2收藏页 2 / 2" in text(builtin_both),
    }
    visual = ["canvas-grid-diamond-tooltip-final", "canvas-grid-custom-tooltip-final", "canvas-free-final", "canvas-large-final", "canvas-regions-both-next", "builtin-items-grid"]
    return checks, visual, run


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--suite", choices=("canvas", "native", "viewport"), default="canvas")
    parser.add_argument("--run-start", help="精确匹配某条 start 记录的时间戳；省略时只使用最后一个 start 段")
    args = parser.parse_args(argv)
    try:
        if args.suite == "native":
            checks, visual, run = check_native(args.evidence, args.run_start)
        elif args.suite == "viewport":
            checks, visual, run = check_viewport(args.evidence, args.run_start)
        else:
            checks, visual, run = check_canvas(args.evidence, args.run_start)
    except EvidenceError as error:
        print(f"check-evidence: {error}", file=sys.stderr)
        return 2
    print(json.dumps({"suite": args.suite, "run": run_info(run), "checks": checks, "manual-visual-review": visual}, ensure_ascii=False, indent=2))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
