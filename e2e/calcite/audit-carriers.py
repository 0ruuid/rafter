#!/usr/bin/env python3
"""提取本地 26.2 客户端的承载机制证据，不启动游戏或判定 UI 验收通过。"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


CLASSES = (
    "net.minecraft.network.chat.contents.objects.ObjectInfos",
    "net.minecraft.client.gui.font.AtlasGlyphProvider",
    "net.minecraft.client.gui.render.GuiItemAtlas",
    "net.minecraft.server.dialog.body.DialogBodyTypes",
    "net.minecraft.client.gui.screens.dialog.DialogScreen",
    "net.minecraft.client.gui.screens.dialog.body.DialogBodyHandlers$ItemHandler",
    "net.minecraft.client.renderer.item.CompositeModel",
    "net.minecraft.client.renderer.item.BundleSelectedItemSpecialRenderer",
    "net.minecraft.client.gui.screens.inventory.tooltip.ClientBundleTooltip",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-jar", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        client = args.client_jar.resolve(strict=True)
        javap = (args.java_home / "bin/javap").resolve(strict=True)
        with zipfile.ZipFile(client) as archive:
            version = json.loads(archive.read("version.json"))
            if version["id"] != "26.2":
                parser.error("本次审查只核对 26.2，其他版本不能继承结论")
            hashes = {
                name: hashlib.sha256(archive.read(name.replace(".", "/") + ".class")).hexdigest()
                for name in CLASSES
            }
        args.output.mkdir(parents=True, exist_ok=True)
        for name in CLASSES:
            result = subprocess.run(
                [str(javap), "-J-Xmx128m", "-J-XX:ActiveProcessorCount=1",
                 "-classpath", str(client), "-c", "-p", name],
                check=True, capture_output=True, text=True, timeout=15,
            )
            (args.output / f"{name.rsplit('.', 1)[-1]}.javap.txt").write_text(result.stdout)
        digest = hashlib.sha256()
        with client.open("rb") as stream:
            for block in iter(lambda: stream.read(131072), b""):
                digest.update(block)
        report = {
            "scope": "offline-client-bytecode-only",
            "client-version": version["id"],
            "client-jar-sha256": digest.hexdigest(),
            "classes": hashes,
            "ui-acceptance": "not-executed",
            "prototype": "not-created-by-this-tool",
        }
        (args.output / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
    except (OSError, KeyError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, f"audit-carriers: {error}\n")


if __name__ == "__main__":
    main()
