"""ffmpeg_check starts each bundled program exactly once per call (#21).

The System check reads both the ffmpeg and the ffprobe entry from one ffmpeg_check result, so one
call has to cover both programs, and each must be started only once.
"""

from collections import Counter

import pytest
import sxdc_verify


class _Proc:
    def __init__(self, path):
        self.returncode = 0
        self.stdout = f"{path.rsplit('/', 1)[-1]} version 8.1.1\nbuilt with clang\n".encode()


@pytest.fixture
def run_calls(monkeypatch):
    calls = Counter()

    def fake_run(args, **kwargs):
        calls[args[0]] += 1
        assert args[1:] == ["-version"]
        return _Proc(args[0])

    monkeypatch.setattr(sxdc_verify.subprocess, "run", fake_run)
    return calls


def _bin_dir(tmp_path, *names):
    for name in names:
        (tmp_path / name).write_bytes(b"\x7fELF")
    return str(tmp_path)


def test_each_program_is_started_exactly_once_per_call(tmp_path, run_calls):
    bin_dir = _bin_dir(tmp_path, "ffmpeg", "ffprobe")

    report = sxdc_verify.ffmpeg_check(bin_dir)

    assert run_calls == Counter({str(tmp_path / "ffmpeg"): 1, str(tmp_path / "ffprobe"): 1})
    assert report["ffmpeg"]["version"] == "ffmpeg version 8.1.1"
    assert report["ffprobe"]["version"] == "ffprobe version 8.1.1"
    assert report["ffmpeg"]["error"] is None


def test_a_second_call_starts_each_program_once_more(tmp_path, run_calls):
    bin_dir = _bin_dir(tmp_path, "ffmpeg", "ffprobe")

    sxdc_verify.ffmpeg_check(bin_dir)
    sxdc_verify.ffmpeg_check(bin_dir)

    assert sorted(run_calls.values()) == [2, 2]


def test_a_missing_program_is_never_started(tmp_path, run_calls):
    bin_dir = _bin_dir(tmp_path, "ffmpeg")

    report = sxdc_verify.ffmpeg_check(bin_dir)

    assert run_calls == Counter({str(tmp_path / "ffmpeg"): 1})
    assert report["ffprobe"]["version"] is None
    assert report["ffprobe"]["error"] == "not found"
