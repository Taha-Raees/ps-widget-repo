#!/usr/bin/env python3
"""Validate ps-widget-repo/catalog.json — the contract the PocketShell app
relies on when it fetches the catalog over HTTPS.

Checks (stdlib only):
  * catalog.json parses and has a "widgets" array
  * ids: unique, lowercase, match [a-z][a-z0-9-]{1,31}
  * required string fields present and non-empty
  * "version" and "minAppVersion" are valid semver (X.Y.Z[-pre])
  * "file" is a SAFE relative path for every entry; must EXIST for data
    widgets (entries without a "plugin" block)
  * plugin refs: "className" looks like a fully-qualified Java class,
    "dex" is a safe relative path that EXISTS, "sha256" is 64 lowercase
    hex chars and matches the actual artifact bytes on disk

Exit code 0 and a summary on success; non-zero and every reason on failure.

Usage: python3 tools/validate-catalog.py [path-to-catalog.json]
       (default: catalog.json next to this script's repo root)
"""

import hashlib
import json
import os
import re
import sys

ID_RE = re.compile(r"^[a-z][a-z0-9-]{1,31}$")
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")
CLASS_RE = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

REQUIRED_FIELDS = ("id", "name", "summary", "author", "version", "minAppVersion", "file")


def is_safe_relative_path(path: str) -> bool:
    """Relative, no traversal, no scheme, no backslashes — mirrors the
    app-side isSafeRelativePath guard for catalog entries."""
    if not isinstance(path, str) or not path:
        return False
    if path.startswith(("/", "\\")) or ":" in path.split("/")[0] and "://" in path:
        return False
    if "\\" in path:
        return False
    parts = path.split("/")
    return all(p not in ("", ".", "..") for p in parts)


def validate(catalog_path: str) -> list:
    errors = []
    repo_root = os.path.dirname(os.path.abspath(catalog_path))

    try:
        with open(catalog_path, encoding="utf-8") as f:
            catalog = json.load(f)
    except (OSError, ValueError) as e:
        return [f"catalog.json does not parse: {e}"]

    if not isinstance(catalog, dict) or not isinstance(catalog.get("widgets"), list):
        return ['catalog.json must be an object with a "widgets" array']

    seen_ids = set()
    for index, entry in enumerate(catalog["widgets"]):
        where = f"widgets[{index}]"

        if not isinstance(entry, dict):
            errors.append(f"{where}: not an object")
            continue

        widget_id = entry.get("id")
        if not isinstance(widget_id, str) or not ID_RE.match(widget_id):
            errors.append(f"{where}.id: {widget_id!r} does not match [a-z][a-z0-9-]{{1,31}}")
            widget_id = None
        elif widget_id in seen_ids:
            errors.append(f"{where}.id: duplicate id {widget_id!r}")
        else:
            seen_ids.add(widget_id)
        where = f"widget {widget_id!r}" if widget_id else where

        for field in REQUIRED_FIELDS:
            value = entry.get(field)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{where}.{field}: missing or empty")

        for field in ("version", "minAppVersion"):
            value = entry.get(field)
            if isinstance(value, str) and not SEMVER_RE.match(value):
                errors.append(f"{where}.{field}: {value!r} is not semver X.Y.Z")

        file_path = entry.get("file")
        has_plugin = entry.get("plugin") is not None
        if isinstance(file_path, str) and file_path:
            if not is_safe_relative_path(file_path):
                errors.append(f"{where}.file: {file_path!r} is not a safe relative path")
            elif not has_plugin and not os.path.isfile(os.path.join(repo_root, file_path)):
                errors.append(f"{where}.file: data widget file {file_path!r} does not exist")
        else:
            errors.append(f"{where}.file: missing or empty")

        plugin = entry.get("plugin")
        if plugin is None:
            continue
        if not isinstance(plugin, dict):
            errors.append(f"{where}.plugin: not an object")
            continue

        class_name = plugin.get("className")
        if not isinstance(class_name, str) or not CLASS_RE.match(class_name):
            errors.append(
                f"{where}.plugin.className: {class_name!r} is not a fully-qualified class name"
            )

        dex_path = plugin.get("dex")
        dex_file = None
        if not isinstance(dex_path, str) or not dex_path:
            errors.append(f"{where}.plugin.dex: missing or empty")
        elif not is_safe_relative_path(dex_path):
            errors.append(f"{where}.plugin.dex: {dex_path!r} is not a safe relative path")
        else:
            dex_file = os.path.join(repo_root, dex_path)
            if not os.path.isfile(dex_file):
                errors.append(f"{where}.plugin.dex: artifact {dex_path!r} does not exist")

        sha = plugin.get("sha256")
        if not isinstance(sha, str) or not SHA256_RE.match(sha):
            errors.append(
                f"{where}.plugin.sha256: {sha!r} is not 64 lowercase hex chars"
            )
        elif dex_file is not None and os.path.isfile(dex_file):
            actual = hashlib.sha256(open(dex_file, "rb").read()).hexdigest()
            if actual != sha:
                errors.append(
                    f"{where}.plugin.sha256: pinned {sha} but {dex_path!r} hashes to {actual}"
                )

    return errors


def main() -> int:
    default = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "catalog.json")
    catalog_path = sys.argv[1] if len(sys.argv) > 1 else default
    errors = validate(catalog_path)
    if errors:
        print(f"INVALID catalog ({catalog_path}) — {len(errors)} problem(s):")
        for e in errors:
            print(f"  - {e}")
        return 1
    with open(catalog_path, encoding="utf-8") as f:
        n = len(json.load(f)["widgets"])
    print(f"OK: {catalog_path} — {n} widgets, all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
