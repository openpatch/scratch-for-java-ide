#!/usr/bin/env python3
"""Imports the finished tutorial projects and the demos of a scratch-for-java
checkout as bundled IDE templates.

    scripts/sync-templates.py ../scratch-for-java

Writes core/src/main/resources/org/openpatch/scratch4j/core/templates/ with one
folder per template and an index.json (id, kind, title, startClass, files).
Tutorials are copied as they are (flat BlueJ/VS Code folders). Demos live in
packages `demos.<name>` and load assets via "demos/<name>/..."; they become
flat default-package projects whose asset paths are relative to the project
root, which is the runner's working directory.
"""
import argparse
import hashlib
import json
import tempfile
import zipfile
import re
import shutil
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / \
    "core/src/main/resources/org/openpatch/scratch4j/core/templates"
TUTORIALS = ["getting-started-100", "make-it-walk-100", "catch-the-coins-100",
             "red-light-green-light-100", "guess-the-number-100",
             "bouncy-hedgehog-100", "dodge-the-rocks-100"]
SKIP_SUFFIXES = {".class", ".ctxt"}
SKIP_NAMES = {".gitkeep", ".DS_Store", "package.bluej"}
CLASS = re.compile(r"\bclass\s+(\w+)\s+extends\s+(Window|Stage)\b")
MAIN = re.compile(r"static\s+void\s+main\s*\(")


def copy_tree(src: Path, dst: Path, convert=None):
    files = []
    for f in sorted(src.rglob("*")):
        rel = f.relative_to(src)
        if not f.is_file() or f.suffix in SKIP_SUFFIXES or f.name in SKIP_NAMES:
            continue
        if rel.parts[0] == "+libs":
            continue
        target = dst / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        if convert and f.suffix == ".java":
            target.write_text(convert(f.read_text(encoding="utf-8")), encoding="utf-8")
        elif f.suffix in {".vert", ".frag", ".glsl"}:
            target.write_text("\n".join(line.expandtabs(2).rstrip() for line in f.read_text().splitlines()).rstrip() + "\n")
        else:
            shutil.copyfile(f, target)
        files.append(rel.as_posix())
    return files


def start_class(folder: Path):
    """A Window subclass wins (it creates the window and picks the stage),
    then a stage with main, then any stage, then any class with main."""
    windows, stages_main, stages, mains = [], [], [], []
    for f in sorted(folder.glob("*.java")):
        text = f.read_text(encoding="utf-8")
        has_main = bool(MAIN.search(text))
        for name, kind in CLASS.findall(text):
            if name != f.stem:
                continue
            if kind == "Window":
                windows.append(name)
            elif has_main:
                stages_main.append(name)
            else:
                stages.append(name)
        if has_main:
            mains.append(f.stem)
    for group in (windows, stages_main, stages, mains):
        if group:
            return group[0]
    return ""


def tutorial_title(folder: Path):
    readme = folder / "README.md"
    if readme.is_file():
        first = readme.read_text(encoding="utf-8").splitlines()[0]
        return re.sub(r"^#\s*", "", first).replace(" - finished project", "")
    return folder.name


TITLES = {"bouncy-hedgehog-100": "Bouncy Hedgehog", "donutIO": "Donut.io", "ui": "UI"}


def demo_title(name: str):
    return TITLES.get(name) or re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name).title()


def generate(library):
    catalog = json.loads((library / "catalogs/examples.json").read_text())
    requirements = {example["id"]: example.get("requiredSheets", []) for example in catalog["examples"]}
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    index = []
    for name in TUTORIALS:
        src = library / "docs/archives" / name
        dst = OUT / name
        files = copy_tree(src, dst)
        (dst / "package.bluej").write_text("#BlueJ package file\n", encoding="utf-8")
        files.append("package.bluej")
        index.append({"id": name, "kind": "tutorial", "title": TITLES.get(name) or tutorial_title(src),
                      "startClass": start_class(dst), "files": sorted(files),
                      "requiredSheets": requirements.get("tutorial/" + name, [])})
    demos = library / "src/examples/java/demos"
    for src in sorted(p for p in demos.iterdir() if p.is_dir()):
        name = src.name
        prefix = f"demos/{name}/"

        def convert(text, name=name, prefix=prefix):
            text = re.sub(rf"^package\s+demos\.{re.escape(name)}\s*;\s*\n", "", text, flags=re.M)
            # keep a parent folder: TiledMap resolves tilesets via getParent()
            text = text.replace(f'"{prefix}" + ', '"./" + ')
            text = text.replace(f'"{prefix}', '"')
            return text.replace(f'"demos/{name}"', '"."')

        dst = OUT / f"demo-{name}"
        files = copy_tree(src, dst, convert)
        leftovers = [f for f in dst.rglob("*.java") if "demos/" in f.read_text(encoding="utf-8")
                     or re.search(r"^package ", f.read_text(encoding="utf-8"), re.M)]
        if leftovers:
            sys.exit(f"unconverted demo paths in {leftovers}")
        index.append({"id": f"demo-{name}", "kind": "demo", "title": demo_title(name),
                      "startClass": start_class(dst), "files": sorted(files),
                      "requiredSheets": requirements.get("demo/" + name, [])})
    (OUT / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    print(f"{len(index)} templates written to {OUT}")


def main():
    global OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library", nargs="?", type=Path, help="Explicit library checkout (legacy adapter)")
    parser.add_argument("--artifact", type=Path, help="Versioned scratch-catalogs.zip; no sibling checkout required")
    parser.add_argument("--check", action="store_true", help="Check generated templates without writing")
    args = parser.parse_args()
    if bool(args.library) == bool(args.artifact):
        parser.error("Choose a library checkout or --artifact")
    committed = OUT
    with tempfile.TemporaryDirectory(prefix="scratch-templates-") as temporary:
        temporary = Path(temporary)
        library = args.library.resolve() if args.library else temporary / "library"
        if args.artifact:
            with zipfile.ZipFile(args.artifact) as archive:
                catalog = json.loads(archive.read("catalogs/examples.json"))
                (library / "catalogs").mkdir(parents=True, exist_ok=True)
                (library / "catalogs/examples.json").write_text(json.dumps(catalog))
                if catalog["schemaVersion"] != 1:
                    raise ValueError("Unsupported example catalog")
                for example in catalog["examples"]:
                    folder = library / example["source"]
                    if not folder.resolve().is_relative_to(library.resolve()):
                        raise ValueError("Unsafe example folder")
                    for entry in example["files"]:
                        target = folder / entry["path"]
                        if not target.resolve().is_relative_to(folder.resolve()):
                            raise ValueError("Unsafe example file")
                        contents = archive.read("examples/" + example["id"] + "/" + entry["path"])
                        if hashlib.sha256(contents).hexdigest() != entry["sha256"]:
                            raise ValueError("Corrupt example: " + example["id"] + "/" + entry["path"])
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(contents)
        if args.check:
            OUT = temporary / "generated"
        generate(library)
        if args.check:
            expected = {p.relative_to(OUT): p.read_bytes() for p in OUT.rglob("*") if p.is_file()}
            actual = {p.relative_to(committed): p.read_bytes() for p in committed.rglob("*") if p.is_file()}
            stale = sorted(set(expected) ^ set(actual) | {p for p in expected.keys() & actual.keys() if expected[p] != actual[p]})
            if stale:
                sys.exit("Stale templates: " + ", ".join(map(str, stale)))
            print("Bundled templates are current")
        OUT = committed


if __name__ == "__main__":
    main()
