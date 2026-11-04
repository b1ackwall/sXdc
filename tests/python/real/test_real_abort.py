"""The cooperative abort (#60) through the real yt-dlp hooks, offline (#48).

download_audio checks `abort.is_set()` once on entry and then from three yt-dlp check points:
match_filter, the thumbnail guard, and the progress and postprocessor hooks. These tests drive
the real yt-dlp, so a renamed hook option or a changed event order shows up here.
"""

import os

import pytest
import sxdc_download
from helpers import AbortAfter, AbortWhen, download, listing

pytestmark = pytest.mark.real_ytdlp

ABORTED = sxdc_download.KIND_ABORTED


class _Set:
    def is_set(self):
        return True


def test_abort_set_before_the_call_stages_nothing(server, ffmpeg_dir, out_dir):
    result = download(server.url("/sxdc-tone.wav"), out_dir, ffmpeg_dir, abort=_Set())

    assert not result["ok"]
    assert result["kind"] == ABORTED
    assert result["path"] is None
    assert listing(out_dir) == []


@pytest.mark.parametrize("n", [2, 3])
def test_abort_at_the_first_yt_dlp_check_points_leaves_nothing(server, ffmpeg_dir, out_dir, n):
    """Check 1 is the entry check; 2 and 3 land in match_filter and the first later hook."""
    abort = AbortAfter(n)

    result = download(server.url("/sxdc-tone.wav"), out_dir, ffmpeg_dir, abort=abort)

    assert not result["ok"]
    assert result["kind"] == ABORTED
    assert abort.calls >= n
    assert listing(out_dir) == []


def test_abort_from_the_progress_hook_returns_aborted(server, ffmpeg_dir, out_dir):
    """Trips once a downloaded file exists, so the first hook to see it is a progress event."""
    abort = AbortWhen(lambda: any(n.endswith(".wav") for n in listing(out_dir)))

    result = download(server.url(f"/long/{os.urandom(4).hex()}.wav"), out_dir, ffmpeg_dir, abort)

    assert abort.tripped
    assert not result["ok"]
    assert result["kind"] == ABORTED
    assert result["path"] is None


def test_abort_in_the_postprocessor_phase_returns_aborted(server, ffmpeg_dir, out_dir):
    """Trips once ExtractAudio has written the FLAC; the next postprocessor hook raises."""
    abort = AbortWhen(lambda: any(n.endswith(".flac") for n in listing(out_dir)))

    result = download(server.url("/sxdc-tone.wav"), out_dir, ffmpeg_dir, abort=abort)

    assert abort.tripped
    assert not result["ok"]
    assert result["kind"] == ABORTED
    assert result["path"] is None


def test_an_abort_that_is_never_set_changes_nothing(server, ffmpeg_dir, out_dir):
    result = download(server.url("/sxdc-tone.wav"), out_dir, ffmpeg_dir, abort=AbortAfter(10**9))

    assert result["ok"], result
