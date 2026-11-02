#!/usr/bin/env python3
"""仅启动配套 Kotlin 工具，不复制校验、布局或文本规则。"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    candidates = [os.environ.get("RAFTER_AUTHOR"), shutil.which("rafter-author")]
    checkout = Path(__file__).resolve().parents[3]
    candidates.append(str(checkout / "tools/authoring-cli/build/install/rafter-author/bin/rafter-author"))
    for value in candidates:
        if value and Path(value).is_file() and os.access(value, os.X_OK):
            return subprocess.call([value, *sys.argv[1:]], shell=False)
    print(json.dumps({"status": "tool-unavailable", "message": "Install the matching rafter-author distribution on PATH or set RAFTER_AUTHOR. In a source checkout build :tools:authoring-cli:installDist with authorization. No tool was built or downloaded automatically."}))
    return 64


if __name__ == "__main__":
    sys.exit(main())
