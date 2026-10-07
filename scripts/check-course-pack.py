#!/usr/bin/env python3
"""Build the complete course artifact, import it, compile all projects and run checks."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--library", type=Path, required=True)
parser.add_argument("--curriculum", type=Path, required=True)
parser.add_argument("--libraries", type=Path, help="Existing standard/NRW all-JAR folder for fully offline validation")
args = parser.parse_args()
version = json.loads((args.library / "catalogs/examples.json").read_text())["libraryVersion"]
cache = args.libraries or ROOT / "runner/target/bundled"
cache.mkdir(parents=True, exist_ok=True)
jars = []
for flavour in ("", "-nrw"):
    name = f"scratch-{version}{flavour}-all.jar"
    jar = cache / name
    if not jar.is_file():
        if args.libraries:
            raise SystemExit("Missing offline course library: " + str(jar))
        with urllib.request.urlopen(f"https://github.com/openpatch/scratch-for-java/releases/download/v{version}/{name}",
                                    timeout=60) as response:
            content = response.read(64 * 1024 * 1024)
        jar.write_bytes(content)
    with zipfile.ZipFile(jar) as archive:
        if "org/openpatch/scratch/Stage.class" not in archive.namelist():
            raise SystemExit("Not a Scratch for Java library: " + str(jar))
    jars.append(jar.resolve())

classpath = [ROOT / module / "target/classes" for module in ("core", "runner", "export")]
classpath.extend(sorted((ROOT / "app/target/libs").glob("*.jar")))
if not classpath[-1].is_file():
    raise SystemExit("Build Studio with mvn verify before checking the course pack")
with tempfile.TemporaryDirectory(prefix="scratch-course-check-") as directory:
    temporary = Path(directory)
    archive = temporary / "scratch-to-java-course.zip"
    subprocess.run(["python3", str(args.library / "scripts/build-course-pack.py"), "--curriculum",
                    str(args.curriculum), "--output", str(archive)], check=True)
    subprocess.run(["java", "-cp", os.pathsep.join(map(str, classpath)),
                    "org.openpatch.scratch4j.export.ProjectTransferCli", "course", str(archive),
                    str(temporary / "import"), *map(str, jars)], check=True, timeout=180)
