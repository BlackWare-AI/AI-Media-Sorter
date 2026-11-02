#!/usr/bin/env python3
"""Fail before Android builds on merge markers or malformed app/src XML.

Checks Git-tracked source when run at a worktree root, or a safely filtered
extracted source tree when Git metadata is absent. Output contains only
source-relative paths, line numbers and static diagnostic codes, never content.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


MARKER = re.compile(r"^(?:<<<<<<<(?: .*)?|=======|>>>>>>>(?: .*)?|\|\|\|\|\|\|\|(?: .*)?)$")
TEXT_SUFFIXES = {".kt", ".kts", ".java", ".xml", ".yml", ".yaml", ".md", ".py",
                 ".sh", ".json", ".toml", ".properties", ".txt", ".gradle", ".bat", ".pro"}
SKIP_DIRS = {".git", ".gradle", ".kotlin", ".android", ".idea", ".vscode", "build",
             "__pycache__", "node_modules", ".venv", "venv", "android-sdk", "sdk",
             "jdk", "jdk17", "out", "dist", ".secrets", ".ssh", ".aws", ".azure",
             "credentials", "secrets"}
SECRET_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
SECRET_NAMES = {"local.properties", "keystore.properties", "credentials.json", "secrets.json",
                "google-services.json"}


def _source_path(path):
    """Only source-like files, excluding cache/SDK/credential locations."""
    if path.is_absolute() or ".." in path.parts:
        return False
    if any(part.lower() in SKIP_DIRS or part.lower().startswith(("android-sdk-", "jdk-"))
           for part in path.parts[:-1]):
        return False
    name = path.name.lower()
    if name.startswith(".env") or name in SECRET_NAMES or path.suffix.lower() in SECRET_SUFFIXES:
        return False
    if name.endswith(("-credentials.json", "-secrets.json")):
        return False
    return path.suffix.lower() in TEXT_SUFFIXES or name in {"gradlew", ".gitignore"}


def _files(root):
    try:
        top = subprocess.run(["git", "-C", str(root), "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=False)
    except OSError:
        top = None
    if top is not None and top.returncode == 0 and Path(top.stdout.strip()).resolve() == root:
        try:
            tracked = subprocess.run(["git", "-C", str(root), "ls-files", "--cached", "-z"],
                                     capture_output=True, check=False)
        except OSError:
            return "git_tracked", [], [{"path": ".", "line": 1, "code": "SOURCE_ENUMERATION_FAILED"}]
        if tracked.returncode:
            return "git_tracked", [], [{"path": ".", "line": 1, "code": "SOURCE_ENUMERATION_FAILED"}]
        paths = [Path(os.fsdecode(item)) for item in tracked.stdout.split(b"\0") if item]
        return "git_tracked", sorted({p for p in paths if _source_path(p)}), []

    paths = []
    errors = []
    def failed(_):
        errors.append({"path": ".", "line": 1, "code": "SOURCE_ENUMERATION_FAILED"})
    for directory, dirs, names in os.walk(root, followlinks=False, onerror=failed):
        dirs[:] = [name for name in dirs if name.lower() not in SKIP_DIRS and
                   not name.lower().startswith(("android-sdk-", "jdk-")) and
                   not (Path(directory) / name).is_symlink()]
        for name in names:
            relative = (Path(directory) / name).relative_to(root)
            if _source_path(relative):
                paths.append(relative)
    return "extracted_source", sorted(paths), errors


def check_tree(root):
    try:
        root = Path(root).resolve()
        available = root.is_dir()
    except (OSError, RuntimeError):
        available = False
    if not available:
        return {"schema_version": 1, "mode": "unavailable", "source_files_checked": 0,
                "xml_files_checked": 0, "status": "FAIL",
                "issues": [{"path": ".", "line": 1, "code": "SOURCE_ROOT_UNAVAILABLE"}]}
    mode, paths, issues = _files(root)
    checked = xml_checked = 0
    for relative in paths:
        path = root / relative
        source_name = relative.as_posix()
        try:
            confined = not path.is_symlink() and path.resolve().is_relative_to(root)
        except (OSError, RuntimeError):
            confined = False
        if not confined:
            issues.append({"path": source_name, "line": 1, "code": "UNSAFE_SOURCE_PATH"})
            continue
        try:
            with path.open(encoding="utf-8-sig") as source:
                for line_number, line in enumerate(source, 1):
                    if MARKER.fullmatch(line.rstrip("\r\n")):
                        issues.append({"path": source_name, "line": line_number,
                                       "code": "UNRESOLVED_MERGE_MARKER"})
            checked += 1
        except (OSError, UnicodeError):
            issues.append({"path": source_name, "line": 1, "code": "SOURCE_READ_FAILED"})
            continue
        if relative.suffix.lower() == ".xml" and relative.parts[:2] == ("app", "src"):
            xml_checked += 1
            try:
                ET.parse(path)
            except ET.ParseError as error:
                issues.append({"path": source_name, "line": max(1, error.position[0]),
                               "code": "INVALID_XML"})
            except OSError:
                issues.append({"path": source_name, "line": 1, "code": "SOURCE_READ_FAILED"})
    if not checked:
        issues.append({"path": ".", "line": 1, "code": "NO_SOURCE_FILES"})
    issues.sort(key=lambda item: (item["path"], item["line"], item["code"]))
    return {"schema_version": 1, "mode": mode, "source_files_checked": checked,
            "xml_files_checked": xml_checked, "status": "FAIL" if issues else "PASS", "issues": issues}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    result = check_tree(args.root)
    print(json.dumps(result, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
