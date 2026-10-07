#!/usr/bin/env python3
"""Launch the native packaged Studio and verify its disposable offline smoke report."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("image", type=Path)
args = parser.parse_args()
image = args.image.resolve()
if os.name == "nt":
    launcher = image / "Scratch for Java Studio.exe"
elif (image / "Contents").is_dir():
    launcher = image / "Contents/MacOS/Scratch for Java Studio"
else:
    launcher = image / "bin/Scratch for Java Studio"
if not launcher.is_file():
    raise SystemExit("Native launcher missing: " + str(launcher))
with tempfile.TemporaryDirectory(prefix="scratch-studio-smoke-") as temporary:
    result = subprocess.run([str(launcher), "--smoke", temporary], capture_output=True, text=True, timeout=120)
    report = Path(temporary) / "report.txt"
    message = report.read_text() if report.is_file() else "No packaged smoke report"
    if result.returncode or not message.startswith("PASS:"):
        raise SystemExit(message + "\n" + result.stdout[-2000:] + result.stderr[-4000:])
    print(message.strip())
