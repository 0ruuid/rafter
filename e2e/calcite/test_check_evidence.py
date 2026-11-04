#!/usr/bin/env python3
"""check-evidence.py 的轻量标准库回归。"""

import json
import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path


CHECKER = Path(__file__).with_name("check-evidence.py")
SCREEN = "net.minecraft.client.gui.screens.dialog.MultiButtonDialogScreen"
EXPECTED_CLICKS = [
    "CANVAS_ITEM_CLICK rows-0",
    "CANVAS_ITEM_CLICK rows-18",
    "CANVAS_ITEM_CLICK left-0",
    "CANVAS_ITEM_CLICK right-0",
]
VIEWPORT_NAMES = ("canvas-viewport-18", "viewport-native-minimum")
VIEWPORT_BUILD = {
    "engine-version": "0.0.5",
    "contract-version": 15,
    "plugin-sha256": "plugin",
    "item-shader-sha256": "shader",
}
CANVAS_NAMES = (
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


def item(index, kind, *, empty=False):
    if empty:
        return {
            "type": "ItemDisplayWidget",
            "x": index,
            "y": 1,
            "width": 1,
            "height": 1,
            "itemType": "minecraft:air",
            "count": 0,
            "itemModel": "rafter:empty",
            "tooltipEnabled": False,
        }
    values = {
        "diamond": ("minecraft:diamond", 32, "minecraft:diamond", "Diamond", False, 0),
        "oak": ("minecraft:oak_planks", 7, "minecraft:oak_planks", "Oak Planks", False, 0),
        "sword": ("minecraft:diamond_sword", 1, "minecraft:diamond_sword", "Diamond Sword", True, 7),
        "paper": ("minecraft:paper", 3, "rafterprobe:custom", "Model sample", False, 0),
    }
    item_type, count, model, name, foil, damage = values[kind]
    return {
        "type": "ItemDisplayWidget",
        "x": index,
        "y": 1,
        "width": 1,
        "height": 1,
        "itemType": item_type,
        "count": count,
        "itemModel": model,
        "itemName": name,
        "tooltipEnabled": False,
        "foil": foil,
        "damage": damage,
    }


def state(items=(), *messages):
    widgets = list(items)
    widgets.extend({"type": "TextWidget", "message": message} for message in messages)
    return {"screen": SCREEN, "overlay": None, "widgets": widgets}


def write_png(path):
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR" + b"\x00\x00\x00\x01\x00\x00\x00\x01")


def write_snapshot(evidence, name, ui_state):
    (evidence / f"{name}.ui.json").write_text(json.dumps(ui_state), encoding="utf-8")
    write_png(evidence / f"{name}.png")


def viewport_state(viewport_items, max_scroll):
    return {
        "guiScaledWidth": 640,
        "guiScaledHeight": 480,
        "screen": SCREEN,
        "overlay": None,
        "widgets": [
            {
                "type": "net.minecraft.client.gui.components.ScrollableLayout$Container",
                "contentHeight": 440,
                "maxScrollAmount": max_scroll,
                "scrollAmount": 0,
            },
            *viewport_items,
        ],
    }


def seed_viewport_evidence(evidence, max_scroll, native_count=18):
    write_snapshot(evidence, "canvas-viewport-18", viewport_state(
        [item(i, ("diamond", "oak", "sword", "paper")[i % 4]) for i in range(18)],
        max_scroll,
    ))
    write_snapshot(evidence, "viewport-native-minimum", viewport_state([item(i, "diamond") for i in range(native_count)], max_scroll))


def write_viewport_build(evidence):
    (evidence.parent / "build.json").write_text(json.dumps(VIEWPORT_BUILD), encoding="utf-8")


def viewport_evidence(directory):
    evidence = Path(directory) / "evidence"
    evidence.mkdir()
    write_viewport_build(evidence)
    return evidence


def seed_evidence(evidence):
    grid_items = [item(i, ("diamond", "oak", "sword", "paper")[i % 4]) for i in range(18)]
    page2_items = [item(i, "diamond", empty=i < 12) for i in range(18)]
    for name in ("canvas-grid-final", "canvas-grid-diamond-tooltip-final", "canvas-grid-custom-tooltip-final"):
        write_snapshot(evidence, name, state(grid_items, " ".join(f"rows-{i}" for i in range(18))))
    write_snapshot(evidence, "canvas-grid-page2", state(page2_items, " ".join(f"rows-{i}" for i in range(18, 24))))
    write_snapshot(evidence, "canvas-grid-click-final", state(grid_items, "rows-0 clicked: rows-0"))
    write_snapshot(evidence, "canvas-grid-page2-click", state(page2_items, "rows-18 clicked: rows-18"))
    write_snapshot(evidence, "canvas-free-final", state(grid_items[:4], "free"))
    write_snapshot(evidence, "canvas-large-final", state([item(0, "paper")], "large"))
    write_snapshot(evidence, "canvas-regions-initial", state((), "left-0 left-1 left-2 left-3 left-4 left-5 right-0 right-1 right-2 right-3"))
    write_snapshot(evidence, "canvas-regions-left-next", state((), "left-6 left-7 left-8 left-9 left-10 left-11 right-0 right-1 right-2 right-3 clicked: left-0"))
    write_snapshot(evidence, "canvas-regions-both-next", state((), "left-6 left-7 left-8 left-9 left-10 left-11 right-4 right-5 right-6 right-7"))
    write_snapshot(evidence, "canvas-regions-right-click", state((), "left-6 right-4 clicked: right-0"))
    write_snapshot(evidence, "builtin-items-grid", state([item(i, "diamond") for i in range(6)], "多排网格"))
    write_snapshot(evidence, "builtin-items-lists-left-next", state((), "旅伴页 2 / 2收藏页 1 / 2"))
    write_snapshot(evidence, "builtin-items-lists-both-next", state((), "旅伴页 2 / 2收藏页 2 / 2"))


def record(op, result, timestamp="2026-11-04T00:00:00.000Z", **request):
    request["op"] = op
    return {"time": timestamp, "request": request, "result": result}


def snapshot_result(evidence, name, *, hashes=False):
    result = {
        "pngPath": str(evidence / f"{name}.png"),
        "uiPath": str(evidence / f"{name}.ui.json"),
        "ui": json.loads((evidence / f"{name}.ui.json").read_text()),
    }
    if hashes:
        result["pngSha256"] = hashlib.sha256((evidence / f"{name}.png").read_bytes()).hexdigest()
        result["uiSha256"] = hashlib.sha256((evidence / f"{name}.ui.json").read_bytes()).hexdigest()
    return result


def write_actions(evidence, runs):
    records = []
    for run_index, run in enumerate(runs):
        timestamp = run.get("start", f"2026-11-04T00:00:0{run_index}Z")
        records.append(record("start", {"version": "26.2"}, timestamp))
        if run.get("resource", True):
            records.append(record(
                "resource",
                {"compiledRoutes": ["item"], "cornerPhaseIndependent": True, "pack": "file/test"},
                timestamp,
                path="minecraft:shaders/core/position_tex_color.vsh",
            ))
        if run.get("snapshots", True):
            for name in run.get("snapshot_names", CANVAS_NAMES):
                records.append(record("snap", snapshot_result(evidence, name, hashes=run.get("hashes", False)), timestamp, name=name))
        if "chat" in run:
            records.append(record("chat", [{"message": message} for message in run["chat"]], timestamp))
        records.append(record("stop", {"stopped": True}, timestamp))
    (evidence / "rafter-item-probe-actions.jsonl").write_text(
        "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
        encoding="utf-8",
    )


def write_viewport_actions(evidence, *, split=False):
    first_time = "2026-11-04T00:00:00Z"
    second_time = "2026-11-04T00:00:01Z"
    resource = {
        "pack": "file/test",
        "compiledRoutes": True,
        "cornerPhaseIndependent": True,
        "sha256": VIEWPORT_BUILD["item-shader-sha256"],
    }
    chat = [{"message": "Rafter version 0.0.5"}]
    records = [record("start", {"version": "26.2"}, first_time)]
    if split:
        records.extend([
            record("resource", resource, first_time, path="minecraft:shaders/core/position_tex_color.vsh"),
            record("snap", snapshot_result(evidence, VIEWPORT_NAMES[0]), first_time, name=VIEWPORT_NAMES[0]),
            record("stop", {"stopped": True}, first_time),
            record("start", {"version": "26.2"}, second_time),
            record("resource", resource, second_time, path="minecraft:shaders/core/position_tex_color.vsh"),
            record("snap", snapshot_result(evidence, VIEWPORT_NAMES[1]), second_time, name=VIEWPORT_NAMES[1]),
            record("chat", chat, second_time),
            record("stop", {"stopped": True}, second_time),
        ])
    else:
        records.extend([
            record("resource", resource, first_time, path="minecraft:shaders/core/position_tex_color.vsh"),
            record("snap", snapshot_result(evidence, VIEWPORT_NAMES[0]), first_time, name=VIEWPORT_NAMES[0]),
            record("snap", snapshot_result(evidence, VIEWPORT_NAMES[1]), first_time, name=VIEWPORT_NAMES[1]),
            record("chat", chat, first_time),
            record("stop", {"stopped": True}, first_time),
        ])
    (evidence / "rafter-item-probe-actions.jsonl").write_text(
        "".join(json.dumps(action, ensure_ascii=False) + "\n" for action in records),
        encoding="utf-8",
    )


def run_checker(evidence, suite="canvas", run_start=None):
    command = [sys.executable, str(CHECKER), "--evidence", str(evidence), "--suite", suite]
    if run_start is not None:
        command.extend(["--run-start", run_start])
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        check=False,
    )


def test_latest_run_does_not_fallback_to_history():
    with tempfile.TemporaryDirectory() as directory:
        evidence = Path(directory)
        seed_evidence(evidence)
        write_actions(evidence, [
            {"chat": EXPECTED_CLICKS},
            {"resource": False, "snapshots": False},
        ])
        result = run_checker(evidence)
        output = result.stdout + result.stderr
        if result.returncode == 0 or "2026-11-04T00:00:01Z" not in output or "canvas-grid-final" not in output:
            raise AssertionError(output)


def test_explicit_historical_run_is_allowed():
    with tempfile.TemporaryDirectory() as directory:
        evidence = Path(directory)
        seed_evidence(evidence)
        write_actions(evidence, [
            {"chat": EXPECTED_CLICKS},
            {"resource": False, "snapshots": False},
        ])
        result = run_checker(evidence, run_start="2026-11-04T00:00:00Z")
        if result.returncode != 0:
            raise AssertionError(result.stdout + result.stderr)
        if json.loads(result.stdout)["run"]["start"] != "2026-11-04T00:00:00Z":
            raise AssertionError(result.stdout)


def test_historical_run_rejects_later_same_name_overwrite():
    with tempfile.TemporaryDirectory() as directory:
        evidence = Path(directory)
        seed_evidence(evidence)
        write_actions(evidence, [
            {"chat": EXPECTED_CLICKS},
            {"resource": False, "snapshot_names": ("canvas-grid-final",)},
        ])
        write_snapshot(evidence, "canvas-grid-final", state((), "overwritten by later run"))
        result = run_checker(evidence, run_start="2026-11-04T00:00:00Z")
        output = result.stdout + result.stderr
        if result.returncode == 0 or "canvas-grid-final" not in output or "result.ui" not in output:
            raise AssertionError(output)


def test_snapshot_hashes_are_checked_when_present():
    with tempfile.TemporaryDirectory() as directory:
        evidence = Path(directory)
        seed_evidence(evidence)
        write_actions(evidence, [{"chat": EXPECTED_CLICKS, "hashes": True}])
        lines = (evidence / "rafter-item-probe-actions.jsonl").read_text().splitlines()
        for index, line in enumerate(lines):
            action = json.loads(line)
            if action["request"].get("op") == "snap" and action["request"].get("name") == "canvas-grid-final":
                action["result"]["pngSha256"] = "0" * 64
                lines[index] = json.dumps(action)
                break
        (evidence / "rafter-item-probe-actions.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
        result = run_checker(evidence)
        output = result.stdout + result.stderr
        if result.returncode == 0 or "pngSha256" not in output or "SHA256" not in output:
            raise AssertionError(output)


def test_missing_chat_reports_diagnosis():
    with tempfile.TemporaryDirectory() as directory:
        evidence = Path(directory)
        seed_evidence(evidence)
        write_actions(evidence, [{}])
        result = run_checker(evidence)
        output = result.stdout + result.stderr
        if result.returncode == 0 or "IndexError" in output or "chat" not in output:
            raise AssertionError(output)


def test_split_data_stays_incomplete():
    with tempfile.TemporaryDirectory() as directory:
        evidence = Path(directory)
        seed_evidence(evidence)
        write_actions(evidence, [
            {"chat": EXPECTED_CLICKS[:2]},
            {"chat": EXPECTED_CLICKS[2:]},
        ])
        result = run_checker(evidence)
        if result.returncode != 1:
            raise AssertionError(result.stdout + result.stderr)
        checks = json.loads(result.stdout)["checks"]
        if checks["business-actions-once-per-click"] is not False:
            raise AssertionError(result.stdout)


def test_viewport_clean_run_passes():
    with tempfile.TemporaryDirectory() as directory:
        evidence = viewport_evidence(directory)
        seed_viewport_evidence(evidence, max_scroll=0)
        write_viewport_actions(evidence)
        result = run_checker(evidence, "viewport")
        if result.returncode != 0:
            raise AssertionError(result.stdout + result.stderr)


def test_viewport_overflow_is_exit_one():
    with tempfile.TemporaryDirectory() as directory:
        evidence = viewport_evidence(directory)
        seed_viewport_evidence(evidence, max_scroll=1)
        write_viewport_actions(evidence)
        result = run_checker(evidence, "viewport")
        output = result.stdout + result.stderr
        if result.returncode != 1 or "Traceback" in output:
            raise AssertionError(output)
        checks = json.loads(result.stdout)["checks"]
        if checks["viewport-max-scroll-zero"] is not False:
            raise AssertionError(result.stdout)


def test_viewport_native_requires_18_one_pixel_items():
    with tempfile.TemporaryDirectory() as directory:
        evidence = viewport_evidence(directory)
        seed_viewport_evidence(evidence, max_scroll=0, native_count=4)
        write_viewport_actions(evidence)
        result = run_checker(evidence, "viewport")
        if result.returncode != 1:
            raise AssertionError(result.stdout + result.stderr)
        checks = json.loads(result.stdout)["checks"]
        if checks["viewport-native-minimum-18-one-pixel-items"] is not False:
            raise AssertionError(result.stdout)


def test_viewport_snapshots_stay_in_one_run():
    with tempfile.TemporaryDirectory() as directory:
        evidence = viewport_evidence(directory)
        seed_viewport_evidence(evidence, max_scroll=0)
        write_viewport_actions(evidence, split=True)
        result = run_checker(evidence, "viewport")
        output = result.stdout + result.stderr
        if result.returncode == 0 or "canvas-viewport-18" not in output or "2026-11-04T00:00:01Z" not in output:
            raise AssertionError(output)


def test_viewport_error_is_not_success():
    with tempfile.TemporaryDirectory() as directory:
        evidence = viewport_evidence(directory)
        seed_viewport_evidence(evidence, max_scroll=0)
        write_viewport_actions(evidence)
        lines = (evidence / "rafter-item-probe-actions.jsonl").read_text().splitlines()
        for index, line in enumerate(lines):
            failed_snap = json.loads(line)
            if failed_snap["request"].get("op") == "snap" and failed_snap["request"].get("name") == VIEWPORT_NAMES[0]:
                failed_snap["result"] = {"error": "snapshot failed", "code": "snap_failed"}
                lines[index] = json.dumps(failed_snap)
                break
        (evidence / "rafter-item-probe-actions.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
        result = run_checker(evidence, "viewport")
        output = result.stdout + result.stderr
        if result.returncode == 0 or "存在操作错误" not in output or "Traceback" in output:
            raise AssertionError(output)


def main():
    for test in (
        test_latest_run_does_not_fallback_to_history,
        test_explicit_historical_run_is_allowed,
        test_historical_run_rejects_later_same_name_overwrite,
        test_snapshot_hashes_are_checked_when_present,
        test_missing_chat_reports_diagnosis,
        test_split_data_stays_incomplete,
        test_viewport_clean_run_passes,
        test_viewport_overflow_is_exit_one,
        test_viewport_native_requires_18_one_pixel_items,
        test_viewport_snapshots_stay_in_one_run,
        test_viewport_error_is_not_success,
    ):
        test()
        print(f"ok: {test.__name__}")


if __name__ == "__main__":
    main()
