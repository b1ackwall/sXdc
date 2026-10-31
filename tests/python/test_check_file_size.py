"""Tests for scripts/check_file_size.py. Pure functions only; no git needed."""

import importlib.util
import sys
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "check_file_size.py"
_spec = importlib.util.spec_from_file_location("check_file_size", _SCRIPT)
cfs = importlib.util.module_from_spec(_spec)
# dataclasses looks the module up in sys.modules, so register it before executing.
sys.modules[_spec.name] = cfs
_spec.loader.exec_module(cfs)


def _write(root: Path, rel: str, lines: int) -> str:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("x\n" * lines, encoding="utf-8")
    return rel


@pytest.mark.parametrize(
    ("data", "expected"),
    [(b"", 0), (b"a", 1), (b"a\n", 1), (b"a\nb", 2), (b"a\nb\n", 2), (b"\n\n", 2)],
)
def test_count_lines(data, expected):
    assert cfs.count_lines(data) == expected


def test_binary_files_are_skipped(tmp_path):
    (tmp_path / "blob.bin").write_bytes(b"\0\n" * 1000)
    errors, warnings = cfs.collect(tmp_path, ["blob.bin"], 500, 450)
    assert errors == [] and warnings == []


@pytest.mark.parametrize(
    ("lines", "is_error", "is_warning"),
    [(450, False, False), (451, False, True), (500, False, True), (501, True, False)],
)
def test_limit_and_warn_boundaries(tmp_path, lines, is_error, is_warning):
    rel = _write(tmp_path, "src/A.kt", lines)
    errors, warnings = cfs.collect(tmp_path, [rel], 500, 450)
    assert (errors == [cfs.Result(rel, lines)]) is is_error
    assert (warnings == [cfs.Result(rel, lines)]) is is_warning


def test_custom_limit_and_warn(tmp_path):
    rel = _write(tmp_path, "a.py", 101)
    errors, _ = cfs.collect(tmp_path, [rel], 100, 90)
    assert errors == [cfs.Result(rel, 101)]


def test_ignore_list_file(tmp_path):
    ignore = tmp_path / "ignore.txt"
    ignore.write_text("# comment\n\n  docs/*.md  \n", encoding="utf-8")
    globs = cfs.load_ignore_globs(ignore)
    assert globs == ["docs/*.md"]
    rel = _write(tmp_path, "docs/big.md", 600)
    errors, warnings = cfs.collect(tmp_path, [rel], 500, 450, globs)
    assert errors == [] and warnings == []


def test_missing_ignore_file_is_empty(tmp_path):
    assert cfs.load_ignore_globs(tmp_path / "nope.txt") == []


@pytest.mark.parametrize(
    "rel",
    [
        "app/lint-baseline.xml",
        "config/detekt/baseline.xml",
        "app/detekt-baseline-main.xml",
        "gradle/wrapper/gradle-wrapper.jar",
        "uv.lock",
        "package-lock.json",
    ],
)
def test_default_exclusions(tmp_path, rel):
    _write(tmp_path, rel, 600)
    errors, warnings = cfs.collect(tmp_path, [rel], 500, 450)
    assert errors == [] and warnings == []


def test_results_sorted_largest_first(tmp_path):
    paths = [_write(tmp_path, "a.txt", 510), _write(tmp_path, "b.txt", 700)]
    errors, _ = cfs.collect(tmp_path, paths, 500, 450)
    assert [r.path for r in errors] == ["b.txt", "a.txt"]
