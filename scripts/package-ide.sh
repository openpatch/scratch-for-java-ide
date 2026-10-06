#!/usr/bin/env bash
# Builds Scratch for Java Studio as a self-contained app for this OS.
# The JetBrains Runtime runs both the IDE and student programs, including
# enhanced hot reload. JavaFX ships beside the application jars.
#
#   scripts/package-ide.sh [app-image|deb|rpm|msi|exe|dmg|pkg] [all-jar]
#
# The second argument (or SCRATCH4J_ALL_JAR) is the library's -all.jar, shipped
# in <app>/library so new projects work offline; otherwise it is downloaded.
set -euo pipefail
cd "$(dirname "$0")/.."
TYPE="${1:-app-image}"
MAVEN_VERSION="$(mvn ${MVN_ARGS:-} -q help:evaluate -Dexpression=project.version -DforceStdout)"
# jpackage needs a numeric version; the Maven qualifier identifies the alpha.
VERSION="${MAVEN_VERSION%%-*}"
NAME="Scratch for Java Studio"
ICON_DIR="ui/src/main/resources/org/openpatch/scratch4j/ui/branding"
case "$(uname -s)" in
  Darwin)
    ICON="$ICON_DIR/app-icon.icns"
    # macOS rejects app versions whose first component is zero. Shift the
    # major component so version ordering is preserved for 0.x previews.
    MAJOR="${VERSION%%.*}"
    VERSION="$((MAJOR + 1)).${VERSION#*.}"
    ;;
  MINGW*|MSYS*|CYGWIN*) ICON="$ICON_DIR/app-icon.ico" ;;
  *) ICON="$ICON_DIR/app-icon.png" ;;
esac
OUT="target/package"
# Maven's dependency copier leaves old versions in app/target on incremental builds.
rm -rf "$OUT" app/target
mkdir -p "$OUT"

mvn ${MVN_ARGS:-} --batch-mode --no-transfer-progress -DskipTests package

# the library jar students' projects carry in +libs
ALL_JAR="${2:-${SCRATCH4J_ALL_JAR:-}}"
if [ -z "$ALL_JAR" ]; then
  ALL_JAR="$(ls runner/target/bundled/scratch-*-all.jar 2>/dev/null | head -1 || true)"
fi
if [ -z "$ALL_JAR" ]; then
  V="$(grep -o 'SCRATCH_VERSION = "[^"]*"' runner/src/main/java/org/openpatch/scratch4j/runner/LibraryJarSource.java | cut -d'"' -f2)"
  ALL_JAR="$OUT/scratch-$V-all.jar"
  curl -fsSL -o "$ALL_JAR" "https://github.com/openpatch/scratch-for-java/releases/download/v$V/scratch-$V-all.jar"
fi

INPUT="$OUT/input"
mkdir -p "$INPUT/library"
cp app/target/libs/*.jar "$INPUT/"
cp "app/target/app-$MAVEN_VERSION.jar" "$INPUT/"
cp "$ALL_JAR" "$INPUT/library/"
# the NRW flavour (Abiturklassen List) - offline switching between both
NRW_JAR="${SCRATCH4J_NRW_ALL_JAR:-$(dirname "$ALL_JAR")/$(basename "$ALL_JAR" -all.jar)-nrw-all.jar}"
if [ ! -f "$NRW_JAR" ]; then
  NRW_JAR="$OUT/$(basename "$ALL_JAR" -all.jar)-nrw-all.jar"
  V="$(basename "$ALL_JAR" -all.jar | sed 's/^scratch-//')"
  curl -fsSL -o "$NRW_JAR" "https://github.com/openpatch/scratch-for-java/releases/download/v$V/scratch-$V-nrw-all.jar" \
    || { echo "warning: no NRW jar bundled (offline?)"; rm -f "$NRW_JAR"; }
fi
[ -f "$NRW_JAR" ] && cp "$NRW_JAR" "$INPUT/library/"
MAIN_JAR="app-$MAVEN_VERSION.jar"

# Keep the version in sync with ProgramRuntime.JBR_VERSION/JBR_BUILD.
# SCRATCH4J_BUNDLE_JBR=0 uses a jlink runtime instead; enhanced hot reload can
# then be downloaded from within the IDE.
JBR_RUNTIME=0
if [ "${SCRATCH4J_BUNDLE_JBR:-1}" = "1" ]; then
  JBR_VERSION="25.0.4.1"
  JBR_BUILD="b635.70"
  case "$(uname -s)" in
    Darwin) JBR_OS=osx ;;
    MINGW*|MSYS*|CYGWIN*) JBR_OS=windows ;;
    *) JBR_OS=linux ;;
  esac
  case "$(uname -m)" in
    arm64|aarch64) JBR_ARCH=aarch64 ;;
    *) JBR_ARCH=x64 ;;
  esac
  JBR_TGZ="$OUT/jbr-$JBR_VERSION-$JBR_OS-$JBR_ARCH-$JBR_BUILD.tar.gz"
  [ -f "$JBR_TGZ" ] || curl -fsSL -o "$JBR_TGZ" \
    "https://cache-redirector.jetbrains.com/intellij-jbr/$(basename "$JBR_TGZ")" \
    || { echo "warning: no JetBrains Runtime bundled (offline?)"; rm -f "$JBR_TGZ"; }
  if [ -f "$JBR_TGZ" ]; then
    mkdir -p "$OUT/runtime"
    tar xzf "$JBR_TGZ" -C "$OUT/runtime" --strip-components=1
    # The second CDS archive is only for JVMs with compressed oops disabled.
    # The JVM starts normally without it, including in that uncommon mode.
    find "$OUT/runtime" -type f -name classes_nocoops.jsa -delete
    cp app/target/javafx/*.jar "$INPUT/"
    JBR_RUNTIME=1
  fi
fi

if [ "$JBR_RUNTIME" = "0" ]; then
  # Fallback for offline packaging: link the host JDK with JavaFX modules.
  JDK_MODULES="$(java --list-modules | sed 's/@.*//' | grep -Ev '^(jdk\.incubator|jdk\.internal\.vm\.ci|jdk\.graal|jdk\.jlink|jdk\.jpackage)' | paste -sd, -)"
  FX_PATH="$(ls app/target/javafx/*-[a-z]*.jar | grep -E -- '-(linux|win|mac)(-aarch64)?\.jar$' | paste -sd"$(java -XshowSettings:properties -version 2>&1 | grep -q 'path.separator = ;' && echo ';' || echo ':')" -)"
  jlink --module-path "$FX_PATH" \
    --add-modules "$JDK_MODULES,javafx.base,javafx.graphics,javafx.controls,javafx.swing" \
    --strip-debug --no-header-files --no-man-pages \
    --output "$OUT/runtime"
  NATIVE_ACCESS='ALL-UNNAMED,javafx.graphics'
else
  NATIVE_ACCESS='ALL-UNNAMED'
fi

jpackage --verbose --type "$TYPE" \
  --name "$NAME" \
  --app-version "$VERSION" \
  --vendor "OpenPatch" \
  --description "Alpha preview of a beginner IDE for Scratch for Java" \
  --icon "$ICON" \
  --input "$INPUT" \
  --main-jar "$MAIN_JAR" \
  --main-class org.openpatch.scratch4j.app.Main \
  --runtime-image "$OUT/runtime" \
  --java-options "--enable-native-access=$NATIVE_ACCESS" \
  --java-options "-Dscratch4j.libraryDir=\$APPDIR/library" \
  --java-options "-Dscratch4j.appDir=\$APPDIR" \
  --dest "$OUT"

echo "Packaged into $OUT"
