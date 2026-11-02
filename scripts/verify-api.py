#!/usr/bin/env python3
"""分别对已有 API 与插件 JAR 编译 Java 示例，不构建或运行插件。"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


API_PREFIX = "com/hiusers/rafter/api/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True, type=Path, help="JDK home containing bin/javac")
    parser.add_argument("--api-jar", required=True, type=Path, help="Existing rafter-api-0.0.2.jar")
    parser.add_argument("--plugin-jar", required=True, type=Path, help="Existing final Rafter.jar")
    parser.add_argument(
        "--classpath", required=True,
        help=f"Explicit dependency paths separated by {os.pathsep!r}; exclude Rafter API/plugin/classes",
    )
    args = parser.parse_args()
    javac = args.java_home.expanduser().resolve() / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    if not javac.is_file() or not os.access(javac, os.X_OK):
        parser.error(f"javac is not executable: {javac}")

    artifacts = [args.api_jar.expanduser().resolve(), args.plugin_jar.expanduser().resolve()]
    for jar in artifacts:
        if not jar.is_file() or not zipfile.is_zipfile(jar):
            parser.error(f"not an existing JAR: {jar}")
    if artifacts[0].samefile(artifacts[1]):
        parser.error("--api-jar and --plugin-jar must be different artifacts")

    dependencies = []
    for entry in args.classpath.split(os.pathsep):
        if not entry or "*" in entry:
            parser.error("classpath entries must be explicit paths; no empty entries or wildcards")
        path = Path(entry).expanduser().resolve()
        if path.is_dir():
            contains_api = any((path / API_PREFIX).rglob("*.class"))
        elif path.is_file() and zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as jar:
                contains_api = any(name.startswith(API_PREFIX) and name.endswith(".class") for name in jar.namelist())
        else:
            parser.error(f"classpath entry is not an existing directory or JAR: {path}")
        if contains_api:
            parser.error(f"dependency classpath must not supply Rafter API classes: {path}")
        dependencies.append(str(path))

    root = Path(__file__).resolve().parent.parent
    sources = sorted((root / "examples/api-consumer/src/main/java").rglob("*.java"))
    if not sources:
        parser.error("Java consumer fixture is missing")

    # 忽略注入的 JVM 选项，保持显式类路径和资源限制。
    injected_options = {"JDK_JAVAC_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"}
    env = {key: value for key, value in os.environ.items() if key not in injected_options}
    try:
        with tempfile.TemporaryDirectory(prefix="rafter-api-consumer-") as temporary:
            temporary = Path(temporary)
            sourcepath = temporary / "empty-sourcepath"
            sourcepath.mkdir()
            for label, jar in zip(("api", "plugin"), artifacts):
                output = temporary / label
                output.mkdir()
                command = [
                    str(javac), "-J-Xms16m", "-J-Xmx128m", "-J-XX:ActiveProcessorCount=1", "-J-XX:+UseSerialGC",
                    "--release", "17", "-encoding", "UTF-8", "-proc:none", "-implicit:none",
                    "--source-path", str(sourcepath), "--class-path", os.pathsep.join([str(jar), *dependencies]),
                    "-d", str(output), *(str(source) for source in sources),
                ]
                print(f"Compiling Java consumer against {label}: {jar}", flush=True)
                subprocess.run(command, check=True, timeout=60, stdin=subprocess.DEVNULL, env=env)
                print(f"PASS: Java consumer compiled against {label}", flush=True)
    except (OSError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
        parser.exit(1, f"FAIL: {failure}\n")
    print("PASS: both artifacts expose the exercised Java API; no runtime/client verification performed")


if __name__ == "__main__":
    main()
