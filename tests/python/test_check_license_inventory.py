"""Tests for scripts/check_license_inventory.py. Fixture report text only; no Gradle needed."""

import importlib.util
import sys
from pathlib import Path

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "check_license_inventory.py"
_spec = importlib.util.spec_from_file_location("check_license_inventory", _SCRIPT)
cli = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = cli
_spec.loader.exec_module(cli)

REPORT = """
releaseRuntimeClasspath - Resolved configuration for runtime for variant: release
+--- androidx.core:core-ktx:1.18.0
|    +--- androidx.annotation:annotation:1.8.1 -> 1.9.1
|    |    \\--- androidx.annotation:annotation-jvm:1.9.1
|    |         \\--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 2.2.10 (*)
|    \\--- androidx.core:core:1.18.0 (c)
+--- androidx.compose:compose-bom:2024.09.00
|    \\--- androidx.compose.ui:ui:1.7.0 -> 1.9.2 (c)
+--- androidx.compose.ui:ui -> 1.9.2
\\--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 (*)

(c) - A dependency constraint, not a dependency.
(*) - Indicates repeated occurrences of a transitive dependency subtree.
"""

HEADER = "name\tversion\tlicense\turl\tfile\tnotes\tmaven"


def _tsv(*rows: str) -> str:
    return "\n".join([HEADER, *rows]) + "\n"


FULL = _tsv(
    "Core\t1.18.0\tApache-2.0\tu\tApache-2.0.txt\t\tandroidx.core",
    "Annotation\t1.9.1\tApache-2.0\tu\tApache-2.0.txt\t\tandroidx.annotation",
    "BOM\t2024.09.00\tApache-2.0\tu\tApache-2.0.txt\t\tandroidx.compose",
    "UI\t1.9.2\tApache-2.0\tu\tApache-2.0.txt\t\tandroidx.compose.ui",
    "Kotlin\t2.2.10\tApache-2.0\tu\tApache-2.0.txt\t\torg.jetbrains.kotlin",
    "Coroutines\t1.9.0\tApache-2.0\tu\tApache-2.0.txt\t\torg.jetbrains.kotlinx:kotlinx-coroutines",
    "FFmpeg\t8.1.1\tLGPL-2.1-or-later\tu\tFFmpeg-LGPL-2.1.txt\tnative",
)


def _run(tmp_path, report: str, tsv: str, capsys):
    (tmp_path / "deps.txt").write_text(report, encoding="utf-8")
    (tmp_path / "components.tsv").write_text(tsv, encoding="utf-8")
    code = cli.main([str(tmp_path / "deps.txt"), "--components", str(tmp_path / "components.tsv")])
    return code, capsys.readouterr().out


def test_parse_report_resolves_versions():
    coords = cli.parse_report(REPORT)
    assert coords["androidx.annotation:annotation"] == "1.9.1"
    assert coords["org.jetbrains.kotlin:kotlin-stdlib"] == "2.2.10"
    assert coords["androidx.compose.ui:ui"] == "1.9.2"
    assert coords["androidx.core:core-ktx"] == "1.18.0"
    assert "releaseRuntimeClasspath - Resolved configuration for runtime for variant" not in coords
    assert len(coords) == 8


def test_bare_group_matches_exact_group_only():
    assert cli.matches("androidx.compose:compose-bom", "androidx.compose")
    assert not cli.matches("androidx.compose.ui:ui", "androidx.compose")


def test_colon_entry_is_an_artifact_prefix():
    entry = "org.jetbrains.kotlinx:kotlinx-coroutines"
    assert cli.matches("org.jetbrains.kotlinx:kotlinx-coroutines-android", entry)
    assert not cli.matches("org.jetbrains.kotlinx:kotlinx-serialization-core", entry)


def test_all_covered_passes(tmp_path, capsys):
    code, out = _run(tmp_path, REPORT, FULL, capsys)
    assert code == 0
    assert "OK: all 8 runtime dependencies" in out


def test_missing_group_fails_with_coordinate_and_version(tmp_path, capsys):
    tsv = FULL.replace(
        "Kotlin\t2.2.10\tApache-2.0\tu\tApache-2.0.txt\t\torg.jetbrains.kotlin\n", ""
    )
    code, out = _run(tmp_path, REPORT, tsv, capsys)
    assert code == 1
    assert "org.jetbrains.kotlin:kotlin-stdlib:2.2.10" in out
    assert "1 runtime dependency(ies) have no row" in out


def test_exempt_chaquopy_group_needs_no_row(tmp_path, capsys):
    report = REPORT + "+--- com.chaquo.python.runtime:chaquopy_java:17.0.0\n"
    code, _ = _run(tmp_path, report, FULL, capsys)
    assert code == 0


def test_unused_entry_is_reported_but_does_not_fail(tmp_path, capsys):
    tsv = FULL + "Room\t2.8.4\tApache-2.0\tu\tApache-2.0.txt\t\tandroidx.room\n"
    code, out = _run(tmp_path, REPORT, tsv, capsys)
    assert code == 0
    assert "maven entry 'androidx.room' (Room) matches no runtime dependency" in out


def test_empty_report_fails(tmp_path, capsys):
    code, out = _run(tmp_path, "BUILD FAILED\n", FULL, capsys)
    assert code == 1
    assert "No dependencies found" in out


def test_components_without_maven_column_lists_everything(tmp_path, capsys):
    code, out = _run(tmp_path, REPORT, "name\tversion\tlicense\nA\t1\tMIT\n", capsys)
    assert code == 1
    assert "8 runtime dependency(ies)" in out


def test_several_entries_in_one_cell(tmp_path, capsys):
    tsv = _tsv(
        "All\t-\tApache-2.0\tu\tApache-2.0.txt\t\tandroidx.core, androidx.annotation androidx.compose"
        " androidx.compose.ui,org.jetbrains.kotlin org.jetbrains.kotlinx:kotlinx-coroutines",
    )
    code, _ = _run(tmp_path, REPORT, tsv, capsys)
    assert code == 0


def test_repo_components_tsv_has_maven_entries():
    repo = Path(__file__).resolve().parents[2]
    entries = cli.parse_maven_entries((repo / "licenses" / "components.tsv").read_text("utf-8"))
    assert "androidx.room" in entries
    assert entries["org.jetbrains.kotlinx:kotlinx-coroutines"] == "kotlinx.coroutines"
