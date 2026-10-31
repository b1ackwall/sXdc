"""Fail when a git-tracked text file exceeds the project's line limit.

Usage: check_file_size.py [--limit N] [--warn N]

Files over --limit (default 500) are errors (exit 1). Files over --warn (default 450) but not
over --limit are printed as warnings (exit 0). Binary files (containing a NUL byte), lock files,
jars and generated baseline files are skipped, as are globs listed in
scripts/file-size-ignore.txt. Standard library only.
"""

from __future__ import annotations

import argparse
import fnmatch
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

DEFAULT_LIMIT = 500
DEFAULT_WARN = 450
IGNORE_FILE = "scripts/file-size-ignore.txt"

# Path globs that are never checked. fnmatch's "*" also matches "/", so "*.jar" covers any depth.
DEFAULT_EXCLUDES = (
    "*.lock",
    "*-lock.json",
    "*.lockfile",
    "*.jar",
    "*lint-baseline.xml",
    "config/detekt/baseline.xml",
    "*baseline*.xml",
)


@dataclass(frozen=True)
class Result:
    path: str
    lines: int


def is_binary(data: bytes) -> bool:
    return b"\0" in data


def count_lines(data: bytes) -> int:
    """Count lines the way an editor shows them: a last line without a newline still counts."""
    if not data:
        return 0
    return data.count(b"\n") + (0 if data.endswith(b"\n") else 1)


def load_ignore_globs(path: Path) -> list[str]:
    """One glob per line; blank lines and lines starting with # are skipped."""
    if not path.is_file():
        return []
    globs = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            globs.append(line)
    return globs


def is_excluded(path: str, globs: list[str] | tuple[str, ...]) -> bool:
    return any(fnmatch.fnmatch(path, g) for g in globs)


def collect(
    root: Path,
    paths: list[str],
    limit: int,
    warn: int,
    ignore_globs: list[str] | tuple[str, ...] = (),
) -> tuple[list[Result], list[Result]]:
    """Return (errors, warnings) for the given repo-relative paths under root."""
    errors: list[Result] = []
    warnings: list[Result] = []
    globs = (*DEFAULT_EXCLUDES, *ignore_globs)
    for rel in paths:
        if is_excluded(rel, globs):
            continue
        file = root / rel
        if not file.is_file():
            continue
        data = file.read_bytes()
        if is_binary(data):
            continue
        n = count_lines(data)
        if n > limit:
            errors.append(Result(rel, n))
        elif n > warn:
            warnings.append(Result(rel, n))
    errors.sort(key=lambda r: -r.lines)
    warnings.sort(key=lambda r: -r.lines)
    return errors, warnings


def git_tracked_files(root: Path) -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True
    ).stdout
    return [p for p in out.decode("utf-8").split("\0") if p]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check tracked files against a line limit.")
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT, help="max lines (error above)")
    parser.add_argument("--warn", type=int, default=DEFAULT_WARN, help="warn above this many lines")
    args = parser.parse_args(argv)

    root = Path(__file__).resolve().parent.parent
    ignore = load_ignore_globs(root / IGNORE_FILE)
    errors, warnings = collect(root, git_tracked_files(root), args.limit, args.warn, ignore)

    for r in warnings:
        print(f"WARNING: {r.path}: {r.lines} lines (warn above {args.warn})")
    for r in errors:
        print(f"ERROR: {r.path}: {r.lines} lines (limit {args.limit})")
    if errors:
        print(f"{len(errors)} file(s) over {args.limit} lines.")
        return 1
    print(f"File size OK: no tracked file over {args.limit} lines.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
