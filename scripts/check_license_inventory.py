"""Fail when a runtime Maven dependency has no row in licenses/components.tsv.

Usage: check_license_inventory.py REPORT [--components PATH]

REPORT is the output of
    ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath
Every group:artifact in it must match the `maven` column of some components.tsv row. A `maven`
cell holds space- or comma-separated entries: a bare group (`androidx.room`) matches that exact
group; an entry with a colon (`org.jetbrains.kotlinx:kotlinx-coroutines`) is a prefix of
`group:artifact`. The Chaquopy runtime and the native components (Python, FFmpeg, ...) are not
on this classpath; their rows are kept by hand and are exempt. Standard library only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DEFAULT_COMPONENTS = "licenses/components.tsv"

# Groups never required to have a row (added by the Chaquopy plugin, kept by hand).
EXEMPT_PREFIXES = ("com.chaquo.python",)

# "+--- group:artifact:version -> resolved (*)", "\--- group:artifact -> version (c)", ...
_COORD = re.compile(r"^[\s|+\\-]*([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?::(\S+))?(?: -> (\S+))?")


def parse_report(text: str) -> dict[str, str]:
    """Returns {"group:artifact": resolved version} for every dependency line in the report."""
    found: dict[str, str] = {}
    for line in text.splitlines():
        if not line.lstrip().startswith(("+---", "\\---", "|")):
            continue
        m = _COORD.match(line)
        if not m:
            continue
        group, artifact, version, resolved = m.groups()
        found[f"{group}:{artifact}"] = resolved or version or ""
    return found


def parse_maven_entries(tsv_text: str) -> dict[str, str]:
    """Returns {maven entry: component name} from the components.tsv text."""
    lines = [line.rstrip("\r") for line in tsv_text.splitlines() if line.strip()]
    if not lines:
        raise ValueError("components.tsv is empty")
    header = [h.strip().lower() for h in lines[0].split("\t")]
    if "name" not in header:
        raise ValueError("components.tsv has no 'name' column")
    if "maven" not in header:
        return {}
    name_i, maven_i = header.index("name"), header.index("maven")
    entries: dict[str, str] = {}
    for line in lines[1:]:
        cells = line.split("\t")
        if maven_i >= len(cells):
            continue
        for entry in re.split(r"[\s,]+", cells[maven_i].strip()):
            if entry:
                entries[entry] = cells[name_i].strip()
    return entries


def matches(coord: str, entry: str) -> bool:
    if ":" in entry:
        return coord.startswith(entry)
    return coord.split(":", 1)[0] == entry


def find_unlisted(coords: dict[str, str], entries: dict[str, str]) -> list[str]:
    """Coordinates (with version) that no entry covers, sorted."""
    return sorted(
        f"{coord}:{version}" if version else coord
        for coord, version in coords.items()
        if not coord.startswith(EXEMPT_PREFIXES) and not any(matches(coord, e) for e in entries)
    )


def find_unused(coords: dict[str, str], entries: dict[str, str]) -> list[str]:
    """Entries that cover no coordinate (stale rows), sorted."""
    return sorted(e for e in entries if not any(matches(c, e) for c in coords))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("report", type=Path)
    parser.add_argument("--components", type=Path, default=Path(DEFAULT_COMPONENTS))
    args = parser.parse_args(argv)

    coords = parse_report(args.report.read_text(encoding="utf-8"))
    if not coords:
        print(f"No dependencies found in {args.report}; did the Gradle report fail?")
        return 1
    try:
        entries = parse_maven_entries(args.components.read_text(encoding="utf-8"))
    except ValueError as e:
        print(f"{args.components}: {e}")
        return 1

    unused = find_unused(coords, entries)
    for entry in unused:
        print(f"note: maven entry '{entry}' ({entries[entry]}) matches no runtime dependency")

    unlisted = find_unlisted(coords, entries)
    if unlisted:
        print(f"{len(unlisted)} runtime dependency(ies) have no row in {args.components}:")
        for coord in unlisted:
            print(f"  {coord}")
        print("Add a row (name, version, license, url, file, notes, maven) for each; see")
        print("docs/dependencies.md.")
        return 1
    print(f"OK: all {len(coords)} runtime dependencies are covered by {args.components}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
