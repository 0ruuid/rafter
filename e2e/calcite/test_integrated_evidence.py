#!/usr/bin/env python3
"""正式 integrated checker 的失败诊断回归；不伪造通过证据。"""

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path


CHECKER = Path(__file__).with_name("check-integrated-evidence.py")
SNAPSHOTS = (
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
SCREEN = "net.minecraft.client.gui.screens.dialog.MultiButtonDialogScreen"
ITEM_SHADER_SOURCE = b"isolated checker fixture shader"
ITEM_SHADER = hashlib.sha256(ITEM_SHADER_SOURCE).hexdigest()
ENGINE_VERSION = "0.0.5"
CONTRACT_VERSION = 16


def record(op, result, timestamp, **request):
    request["op"] = op
    return {"time": timestamp, "request": request, "result": result}


def png_bytes():
    return b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR" + b"\x00\x00\x00\x01\x00\x00\x00\x01"


def state(*, bad=False):
    widgets = [
        {
            "type": "net.minecraft.client.gui.components.ScrollableLayout$Container",
            "maxScrollAmount": 1 if bad else 0,
            "scrollAmount": 0,
            "contentHeight": 380,
        }
    ]
    return {
        "guiScaledWidth": 320 if bad else 640,
        "guiScaledHeight": 480,
        "screen": SCREEN,
        "overlay": None,
        "widgets": widgets,
    }


def write_snapshot(evidence, name, ui_state):
    ui_bytes = (json.dumps(ui_state, ensure_ascii=False, indent=2) + "\n").encode()
    png = png_bytes()
    (evidence / f"{name}.ui.json").write_bytes(ui_bytes)
    (evidence / f"{name}.png").write_bytes(png)
    return {
        "pngPath": str(evidence / f"{name}.png"),
        "uiPath": str(evidence / f"{name}.ui.json"),
        "ui": ui_state,
        "pngSha256": hashlib.sha256(png).hexdigest(),
        "uiSha256": hashlib.sha256(ui_bytes).hexdigest(),
    }


def write_manifest(run):
    plugin = run / "artifacts/Rafter.jar"
    contract = run / "artifacts/contract/authoring.json"
    resource_pack = run / "artifacts/resource-pack"
    image_font = resource_pack / "assets/rafter/font/images.json"
    modal_font = resource_pack / "assets/rafter/font/modal_backdrop/shared/images.json"
    item_shader = resource_pack / "rafter_v260200/assets/minecraft/shaders/core/position_tex_color.vsh"
    plugin.parent.mkdir(parents=True)
    contract.parent.mkdir(parents=True)
    image_font.parent.mkdir(parents=True)
    modal_font.parent.mkdir(parents=True)
    item_shader.parent.mkdir(parents=True)
    plugin.write_bytes(b"fake plugin")
    contract.write_text(
        json.dumps({"engine-version": ENGINE_VERSION, "contract-version": CONTRACT_VERSION}),
        encoding="utf-8",
    )
    image_font.write_text('{"providers":[]}', encoding="utf-8")
    modal_font.write_text('{"providers":[]}', encoding="utf-8")
    item_shader.write_bytes(ITEM_SHADER_SOURCE)
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()
    (run / "manifest.json").write_text(
        json.dumps(
            {
                "engine-version": ENGINE_VERSION,
                "contract-version": CONTRACT_VERSION,
                "plugin-sha256": digest(plugin),
                "item-shader-sha256": ITEM_SHADER,
                "plugin-file": "artifacts/Rafter.jar",
                "contract-file": "artifacts/contract/authoring.json",
                "resource-pack": "artifacts/resource-pack",
                "image-font-sha256": digest(image_font),
                "modal-backdrop-font-sha256": digest(modal_font),
            }
        ),
        encoding="utf-8",
    )
    return json.loads((run / "manifest.json").read_text(encoding="utf-8"))


def seed(run, *, runs=None, chat=True, bad=False, resource_hash=ITEM_SHADER):
    evidence = run / "evidence"
    evidence.mkdir(parents=True)
    manifest = write_manifest(run)
    plans = runs or [{"time": "2026-11-04T00:00:00.000Z", "names": SNAPSHOTS}]
    names = {name for plan in plans for name in plan["names"]}
    results = {name: write_snapshot(evidence, name, state(bad=bad)) for name in names}
    records = []
    for plan in plans:
        timestamp = plan["time"]
        records.append(record("start", {"version": "26.2"}, timestamp))
        records.append(
            record(
                "resource",
                {
                    "pack": "file/rafter",
                    "compiledRoutes": True,
                    "cornerPhaseIndependent": True,
                    "sha256": resource_hash,
                },
                timestamp,
                path="minecraft:shaders/core/position_tex_color.vsh",
            )
        )
        records.append(
            record(
                "resource",
                {"pack": "file/rafter", "sha256": manifest["image-font-sha256"]},
                timestamp,
                path="rafter:font/images.json",
            )
        )
        records.append(
            record(
                "resource",
                {"pack": "file/rafter", "sha256": manifest["modal-backdrop-font-sha256"]},
                timestamp,
                path="rafter:font/modal_backdrop/shared/images.json",
            )
        )
        for name in plan["names"]:
            records.append(record("snap", results[name], timestamp, name=name))
        if chat and plan.get("chat", True):
            records.append(
                record(
                    "chat",
                    [{"message": "Rafter version 0.0.5"}] + [{"message": message} for message in EXPECTED_CLICKS],
                    timestamp,
                )
            )
        records.append(record("close", True, timestamp))
        records.append(record("ui", {"screen": None, "overlay": None, "widgets": []}, timestamp))
        records.append(record("stop", {"stopped": True}, timestamp))
    (evidence / "rafter-item-probe-actions.jsonl").write_text(
        "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in records),
        encoding="utf-8",
    )


def run_checker(run):
    return subprocess.run(
        [
            sys.executable,
            str(CHECKER),
            "--run",
            str(run),
            "--manifest",
            str(run / "manifest.json"),
        ],
        capture_output=True,
        text=True,
        check=False,
    )


def test_missing_run_is_diagnostic():
    with tempfile.TemporaryDirectory() as directory:
        run = Path(directory)
        write_manifest(run)
        result = run_checker(run)
        output = result.stdout + result.stderr
        assert result.returncode == 2, output
        assert "rafter-item-probe-actions" in output
        assert "IndexError" not in output and "Traceback" not in output


def test_missing_chat_is_explicit():
    with tempfile.TemporaryDirectory() as directory:
        run = Path(directory)
        seed(run, chat=False)
        result = run_checker(run)
        output = result.stdout + result.stderr
        assert result.returncode == 2, output
        assert "chat" in output.lower()
        assert "IndexError" not in output and "Traceback" not in output


def test_cross_run_snapshot_cannot_be_assembled():
    with tempfile.TemporaryDirectory() as directory:
        run = Path(directory)
        seed(
            run,
            runs=[
                {"time": "2026-11-04T00:00:00.000Z", "names": ("integrated-grid",)},
                {"time": "2026-11-04T00:00:01.000Z", "names": SNAPSHOTS[1:]},
            ],
        )
        result = run_checker(run)
        output = result.stdout + result.stderr
        assert result.returncode == 2, output
        assert "integrated-grid" in output
        assert "2026-11-04T00:00:01.000Z" in output


def test_bad_ui_is_reported_as_named_data_diagnostics():
    with tempfile.TemporaryDirectory() as directory:
        run = Path(directory)
        seed(run, bad=True)
        result = run_checker(run)
        assert result.returncode == 1, result.stdout + result.stderr
        assert "Traceback" not in result.stdout + result.stderr
        checks = json.loads(result.stdout)["checks"]
        assert checks["integrated-gui-640x480"] is False
        assert checks["integrated-scroll-max-zero"] is False
        assert checks["grid-real-items-18"] is False


def test_loaded_shader_hash_must_match_manifest():
    with tempfile.TemporaryDirectory() as directory:
        run = Path(directory)
        seed(run, resource_hash="c" * 64)
        result = run_checker(run)
        assert result.returncode == 1, result.stdout + result.stderr
        checks = json.loads(result.stdout)["checks"]
        assert checks["loaded-item-shader-hash-matches-manifest"] is False


def test_manifest_font_hash_mismatch_is_diagnostic():
    with tempfile.TemporaryDirectory() as directory:
        run = Path(directory)
        seed(run)
        (run / "artifacts/resource-pack/assets/rafter/font/images.json").write_text(
            '{"providers":["stale-glyph-ledger"]}',
            encoding="utf-8",
        )
        result = run_checker(run)
        output = result.stdout + result.stderr
        assert result.returncode == 2, output
        assert "images.json" in output and "SHA256" in output


def test_manifest_shader_hash_mismatch_is_diagnostic():
    with tempfile.TemporaryDirectory() as directory:
        run = Path(directory)
        seed(run)
        (run / "artifacts/resource-pack/rafter_v260200/assets/minecraft/shaders/core/position_tex_color.vsh").write_bytes(b"stale shader")
        result = run_checker(run)
        output = result.stdout + result.stderr
        assert result.returncode == 2, output
        assert "shader SHA256" in output


def test_panel_overflow_is_rejected_without_moving_controls():
    spec = importlib.util.spec_from_file_location("integrated_checker", CHECKER)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    with tempfile.TemporaryDirectory() as directory:
        pack = Path(directory)
        panel = {"width": 280, "height": 224, "offsetY": 202, "plan": {"bodyHeight": 413}}
        path = pack / "item-layouts.json"
        def save():
            path.write_text(json.dumps({"modal-flows": {"rafterprobe:item-viewport/form@from/grid": {"layout": panel}}}))
        save()
        assert not checker.modal_panels_fit(pack, 640, 480)
        panel["height"] = 211
        save()
        assert checker.modal_panels_fit(pack, 640, 480)
        assert panel["plan"]["bodyHeight"] == 413
        assert panel["offsetY"] == 202


def main():
    for test in (
        test_missing_run_is_diagnostic,
        test_missing_chat_is_explicit,
        test_cross_run_snapshot_cannot_be_assembled,
        test_bad_ui_is_reported_as_named_data_diagnostics,
        test_loaded_shader_hash_must_match_manifest,
        test_manifest_font_hash_mismatch_is_diagnostic,
        test_manifest_shader_hash_mismatch_is_diagnostic,
        test_panel_overflow_is_rejected_without_moving_controls,
    ):
        test()
        print(f"ok: {test.__name__}")


if __name__ == "__main__":
    main()
