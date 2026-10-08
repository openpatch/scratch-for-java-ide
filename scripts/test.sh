#!/usr/bin/env bash
# Runs the project's checks locally.
#
#   scripts/test.sh                 unit + smoke + integration
#   scripts/test.sh unit            mvn verify
#   scripts/test.sh smoke           OpenGL, UI and export smoke (Xvfb without a display)
#   scripts/test.sh integration     release catalogs, course pack, browser transfer
#
# Several stages can be given at once (scripts/test.sh unit smoke). Integration
# uses sibling checkouts; point elsewhere with
#   SCRATCH_LIBRARY  (default ../scratch-for-java)
#   CURRICULUM       (default ../hyperbook-informatik)
#   ONLINE_IDE       (default ../online-ide)
# A missing checkout skips the checks that need it.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
sources=$(dirname "$root")
library=${SCRATCH_LIBRARY:-$sources/scratch-for-java}
curriculum=${CURRICULUM:-$sources/hyperbook-informatik}
online_ide=${ONLINE_IDE:-$sources/online-ide}
mvn=(mvn --batch-mode --no-transfer-progress)

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
skip() { printf '\033[33m-- skipped: %s\033[0m\n' "$*"; }

# a real display when there is one, Xvfb otherwise
with_display() {
  if [[ -n ${DISPLAY:-} || -n ${WAYLAND_DISPLAY:-} ]]; then
    "$@"
  else
    xvfb-run -a "$@"
  fi
}

unit() {
  step "Build and test"
  "${mvn[@]}" verify
}

smoke() {
  step "OpenGL run smoke"
  # a real stage opens a window in a separate JVM and exits via the smoke switch
  SCRATCH4J_ALL_JAR_CACHE=target/bundled with_display "${mvn[@]}" -pl runner -am test \
    -Dtest=ScratchStageRunIT -Dsurefire.failIfNoSpecifiedTests=false -Dscratch4j.gltest=true
  step "UI smoke"
  with_display "${mvn[@]}" -pl ui -am test \
    -Dtest=UiSmokeIT -Dsurefire.failIfNoSpecifiedTests=false -Dscratch4j.uismoke=true
  step "Export smoke"
  # the exported app runs the student program in its own JVM
  with_display "${mvn[@]}" -pl export -am test \
    -Dtest=StudentExportIT -Dsurefire.failIfNoSpecifiedTests=false -Dscratch4j.exporttest=true
}

integration() {
  if [[ ! -d app/target/libs ]]; then
    step "Build the Studio for the integration checks"
    "${mvn[@]}" -DskipTests verify
  fi
  tmp=$(mktemp -d)
  trap 'git -C "$library" worktree remove --force "$tmp/library" >/dev/null 2>&1 || true; rm -rf "$tmp"' EXIT

  local lib=""
  if [[ -d $library/.git ]]; then
    local version
    version=$(python3 -c "import xml.etree.ElementTree as ET; n='{http://maven.apache.org/POM/4.0.0}'; print(ET.parse('pom.xml').findtext(f'{n}properties/{n}scratch.version'))")
    # the bundled library release, without touching the library checkout
    if git -C "$library" rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
      git -C "$library" worktree add --detach -q "$tmp/library" "v$version"
      lib=$tmp/library
    else
      skip "library tag v$version not in $library (git -C $library fetch --tags)"
    fi
  else
    skip "no library checkout at $library"
  fi

  if [[ -n $lib ]]; then
    step "Release catalogs (library v$version)"
    (cd "$lib" && "${mvn[@]}" -DskipTests prepare-package -q)
    python3 "$lib/scripts/release-catalogs.py" --check --studio .
    python3 "$lib/scripts/release-catalogs.py" --archive "$tmp/examples.zip"
    python3 scripts/sync-templates.py --artifact "$tmp/examples.zip" --check
  fi

  if [[ -n $lib && -d $curriculum ]]; then
    step "Course pack"
    python3 scripts/check-course-pack.py --library "$lib" --curriculum "$curriculum"
  else
    skip "course pack needs the library and $curriculum"
  fi

  if [[ -d $online_ide && -d $curriculum ]]; then
    step "Browser transfer (online-ide)"
    [[ -d $online_ide/node_modules ]] || npm --prefix "$online_ide" ci
    (cd "$online_ide" && node scripts/test-curriculum-transfer.mjs \
      "$curriculum/book/projekte/spielwerkstatt/checkpoints/spielwerkstatt-q-07-testen.json" "$root")
  else
    skip "browser transfer needs $online_ide and $curriculum"
  fi
}

stages=("$@")
[[ ${#stages[@]} -gt 0 ]] || stages=(unit smoke integration)
for stage in "${stages[@]}"; do
  case $stage in
    unit | smoke | integration) "$stage" ;;
    *) echo "unknown stage: $stage (unit, smoke, integration)" >&2; exit 2 ;;
  esac
done
step "All requested checks passed"
