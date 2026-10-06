#!/usr/bin/env bash
# Cross-compiles the Windows launcher of exported apps with mingw-w64 into the
# export module's resources (CI runs this before packaging; without it, exports
# fall back to run.bat only).
#   sudo apt-get install --no-install-recommends -y gcc-mingw-w64-x86-64
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=export/src/main/resources/org/openpatch/scratch4j/export/launcher
mkdir -p "$OUT"
x86_64-w64-mingw32-gcc -O2 -municode -mwindows -s \
  -o "$OUT/windows-x64.exe" export/src/main/c/launcher.c
echo "built $OUT/windows-x64.exe"
