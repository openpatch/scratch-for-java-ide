#!/usr/bin/env bash
# Linux AppImage of the IDE: the jpackage app image in an AppDir, packed by
# appimagetool (downloaded once). One file, runs on most distributions.
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/package-ide.sh app-image
OUT=target/package
APP="$OUT/Scratch for Java Studio"
DIR="$OUT/ScratchForJavaStudio.AppDir"
rm -rf "$DIR"
mkdir -p "$DIR/usr"
cp -a "$APP/." "$DIR/usr/"
cat > "$DIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/Scratch for Java Studio" "$@"
RUN
chmod +x "$DIR/AppRun"
cat > "$DIR/scratch4j-studio.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Scratch for Java Studio
Exec=AppRun %f
Icon=scratch4j-studio
Categories=Development;Education;
MimeType=application/x-scratch4j-project;
DESKTOP
# the .scratch4j type, for desktops that integrate AppImages (appimaged, AppImageLauncher)
mkdir -p "$DIR/usr/share/mime/packages"
cat > "$DIR/usr/share/mime/packages/scratch4j-studio.xml" <<'MIME'
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="application/x-scratch4j-project">
    <comment>Scratch for Java project</comment>
    <sub-class-of type="application/zip"/>
    <glob pattern="*.scratch4j"/>
  </mime-type>
</mime-info>
MIME
cp ui/src/main/resources/org/openpatch/scratch4j/ui/branding/app-icon.png \
  "$DIR/scratch4j-studio.png"
TOOL="$OUT/appimagetool"
if [ ! -x "$TOOL" ]; then
  curl -fsSL -o "$TOOL" \
    https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x "$TOOL"
fi
ARCH=x86_64 "$TOOL" --appimage-extract-and-run "$DIR" "$OUT/Scratch_for_Java_Studio-x86_64.AppImage"
echo "built $OUT/Scratch_for_Java_Studio-x86_64.AppImage"
