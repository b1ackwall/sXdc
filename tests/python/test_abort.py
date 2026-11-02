"""Cooperative abort of download_audio (#60), against the fake yt-dlp in conftest.

The order rule under test: once the caller's abort is set, download_audio returns KIND_ABORTED,
before classify runs and before the embed-failure fallback can turn the run into a success.
Every test that goes through download_audio checks that classify never saw the aborted run.
"""

import json
import sys

import pytest
import sxdc_download as sd
from sxdc_classify import KIND_ABORTED, KIND_NETWORK, KIND_RATE_LIMITED, classify
from sxdc_download import SxdcAborted, download_audio

URL = "https://music.example.com/watch?v=abc"
FFMPEG_DIR = "/fake/ffmpeg-bin"


class Abort:
    """Stands in for the threading.Event that PythonDownloadBridge passes."""

    def __init__(self, already=False):
        self._set = already

    def set(self):
        self._set = True

    def is_set(self):
        return self._set


class BrokenAbort:
    def is_set(self):
        raise RuntimeError("bridge object went away")


# Shaped like yt-dlp's, matched by name the way sxdc_classify matches the real ones.
class DownloadError(Exception):
    def __init__(self, msg, exc_info=None):
        super().__init__(msg)
        self.exc_info = exc_info


class HTTPError(Exception):
    def __init__(self, status, msg):
        super().__init__(msg)
        self.status = status


STALL = DownloadError("ERROR: [download] Got error: timed out. Giving up after 3 retries")
_HTTP_429 = HTTPError(429, "HTTP Error 429: Too Many Requests")
RATE_LIMITED = DownloadError(
    "ERROR: unable to download video data: HTTP Error 429: Too Many Requests",
    exc_info=(HTTPError, _HTTP_429, None),
)
EMBED_FAILED = DownloadError("ERROR: Postprocessing: Conversion failed!")


@pytest.fixture
def out_dir(tmp_path):
    d = tmp_path / "staging"
    d.mkdir()
    return d


@pytest.fixture(autouse=True)
def no_mutagen(monkeypatch):
    """The fLaC marker alone makes a file valid audio, as in test_embed_fallback."""
    monkeypatch.setitem(sys.modules, "mutagen", None)
    monkeypatch.setitem(sys.modules, "mutagen.flac", None)


@pytest.fixture
def classify_calls(monkeypatch):
    """Records every exception download_audio hands to classify."""
    calls = []

    def recording(exc):
        calls.append(exc)
        return classify(exc)

    monkeypatch.setattr(sd, "classify", recording)
    return calls


def call(out_dir, abort):
    return json.loads(download_audio(URL, str(out_dir), FFMPEG_DIR, abort=abort))


def flac(directory, name="Song [abc].flac"):
    p = directory / name
    p.write_bytes(b"fLaC" + b"\0" * 64)
    return p


def set_on(abort, status, postprocessor=None):
    """An on_event callback that sets `abort` at the first matching payload."""

    def on_event(event):
        if event.get("status") == status and event.get("postprocessor") == postprocessor:
            abort.set()

    return on_event


def assert_aborted(result):
    assert result["ok"] is False
    assert result["kind"] == KIND_ABORTED
    assert result["path"] is None
    assert result["trace"] is None


# --- the check points --------------------------------------------------------------------


def test_abort_set_before_the_call_never_starts_yt_dlp(fake_ytdlp, out_dir, classify_calls):
    result = call(out_dir, Abort(already=True))

    assert_aborted(result)
    assert fake_ytdlp.instances == []
    assert classify_calls == []


def test_abort_set_during_a_progress_hook(fake_ytdlp, out_dir, classify_calls):
    abort = Abort()
    fake_ytdlp.progress_events = [{"status": "downloading"}, {"status": "downloading"}]
    fake_ytdlp.add_pp_event("ExtractAudio", "started", str(out_dir / "Song [abc].webm"))
    seen = []
    fake_ytdlp.on_event = lambda event: (seen.append(event["status"]), abort.set())

    assert_aborted(call(out_dir, abort))
    # The first progress hook raised: nothing after it ran.
    assert seen == ["downloading"]
    assert classify_calls == []


def test_abort_set_during_a_postprocessor_hook_is_not_delivered(
    fake_ytdlp, out_dir, classify_calls
):
    abort = Abort()
    converted = flac(out_dir)
    fake_ytdlp.add_pp_event("ExtractAudio", "finished", str(out_dir / "Song [abc].webm"))
    fake_ytdlp.add_pp_event("Metadata", "started", str(converted))
    fake_ytdlp.on_event = set_on(abort, "started", "Metadata")
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(converted)}]}

    result = call(out_dir, abort)

    assert_aborted(result)
    assert converted.is_file()  # left for the staging sweep
    assert classify_calls == []


def test_match_filter_is_the_check_between_extraction_and_download(fake_ytdlp, out_dir):
    abort = Abort()
    call(out_dir, abort)
    check = fake_ytdlp.last_opts["match_filter"]

    assert check({"id": "abc"}, incomplete=False) is None
    abort.set()
    with pytest.raises(SxdcAborted):
        check({"id": "abc"}, incomplete=False)


def test_every_check_point_is_installed_and_the_abort_check_runs_first(fake_ytdlp, out_dir):
    call(out_dir, Abort())
    opts = fake_ytdlp.last_opts
    check = opts["match_filter"]

    assert opts["progress_hooks"] == [check]
    assert opts["postprocessor_hooks"][0] is check
    assert len(opts["postprocessor_hooks"]) == 2


# --- the order rule ----------------------------------------------------------------------


def test_stalled_network_error_with_abort_set_is_aborted_not_network(
    fake_ytdlp, out_dir, classify_calls
):
    abort = Abort()
    fake_ytdlp.before_finish = abort.set
    fake_ytdlp.raises = STALL

    assert_aborted(call(out_dir, abort))
    assert classify_calls == []
    assert classify(STALL) == KIND_NETWORK  # what it would have been


def test_rate_limit_in_the_chain_with_abort_set_is_aborted(fake_ytdlp, out_dir, classify_calls):
    abort = Abort()
    fake_ytdlp.before_finish = abort.set
    fake_ytdlp.raises = RATE_LIMITED

    assert_aborted(call(out_dir, abort))
    assert classify_calls == []
    assert classify(RATE_LIMITED) == KIND_RATE_LIMITED  # which would have halted the queue


def test_embed_fallback_is_skipped_when_aborted(fake_ytdlp, out_dir, classify_calls):
    converted = flac(out_dir)
    fake_ytdlp.add_pp_event("Metadata", "started", str(converted))
    fake_ytdlp.raises = EMBED_FAILED

    # Control: without an abort, the fallback keeps the converted file as a success.
    assert call(out_dir, Abort())["ok"] is True
    classify_calls.clear()

    abort = Abort()
    fake_ytdlp.before_finish = abort.set
    assert_aborted(call(out_dir, abort))
    assert classify_calls == []


def test_normal_return_with_abort_set_is_aborted(fake_ytdlp, out_dir, classify_calls):
    abort = Abort()
    f = flac(out_dir)
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(f)}]}
    fake_ytdlp.before_finish = abort.set

    assert_aborted(call(out_dir, abort))
    assert f.is_file()
    assert classify_calls == []


# --- no abort ----------------------------------------------------------------------------


@pytest.mark.parametrize("abort", [None, Abort(), BrokenAbort()], ids=["none", "unset", "broken"])
def test_without_a_set_abort_the_download_is_unchanged(fake_ytdlp, out_dir, abort):
    f = flac(out_dir)
    fake_ytdlp.progress_events = [{"status": "downloading"}, {"status": "finished"}]
    fake_ytdlp.add_pp_event("Metadata", "started", str(f))
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(f)}]}

    result = call(out_dir, abort)

    assert result["ok"] is True
    assert result["path"] == str(f)


def test_sxdc_aborted_is_a_plain_exception():
    assert issubclass(SxdcAborted, Exception)
    assert not issubclass(SxdcAborted, OSError)


def test_classify_never_turns_an_abort_into_network_or_rate_limited():
    for cause in (STALL, RATE_LIMITED, _HTTP_429):
        try:
            try:
                raise cause
            except Exception as interrupted:
                raise SxdcAborted("cancelled by the app") from interrupted
        except SxdcAborted as e:
            assert classify(e) == KIND_ABORTED
        wrapped = DownloadError("ERROR: cancelled", exc_info=(SxdcAborted, SxdcAborted("x"), None))
        wrapped.__context__ = cause
        assert classify(wrapped) == KIND_ABORTED


# --- the finished-file checks and the thumbnail guard (#45, #46, #47) --------------------


def test_abort_during_the_thumbnail_guard_is_aborted(
    fake_ytdlp, out_dir, classify_calls, monkeypatch
):
    import sxdc_thumbnail

    abort = Abort()
    image = out_dir / "Song [abc].jpg"
    image.write_bytes(b"\xff\xd8\xff\xe0" + b"j" * 40)
    f = flac(out_dir)
    fake_ytdlp.info = {
        "title": "Song",
        "requested_downloads": [{"filepath": str(f)}],
        "thumbnails": [{"url": "u", "filepath": str(image)}],
    }
    monkeypatch.setattr(sxdc_thumbnail, "_ffmpeg", lambda *_args: abort.set() or True)
    seen = []
    fake_ytdlp.progress_events = [{"status": "downloading"}]
    fake_ytdlp.on_event = seen.append

    result = call(out_dir, abort)

    assert_aborted(result)
    assert result["warnings"] == []
    assert seen == []  # the guard raised before the download started
    assert classify_calls == []


@pytest.mark.parametrize("step", ["check_integrity", "embed_warnings"])
@pytest.mark.parametrize("path_kind", ["normal", "fallback"])
def test_abort_set_while_the_file_is_checked_wins_over_the_delivery(
    fake_ytdlp, out_dir, monkeypatch, step, path_kind
):
    abort = Abort()
    f = flac(out_dir)
    real = getattr(sd, step)

    def checking(*args):
        abort.set()
        return real(*args)

    monkeypatch.setattr(sd, step, checking)
    if path_kind == "normal":
        fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(f)}]}
    else:
        fake_ytdlp.add_pp_event("Metadata", "started", str(f))
        fake_ytdlp.raises = EMBED_FAILED

    result = call(out_dir, abort)

    assert_aborted(result)
    assert result["warnings"] == []
    assert f.is_file()  # left for the staging sweep


@pytest.mark.parametrize("path_kind", ["normal", "fallback"])
def test_the_file_checks_never_run_for_an_aborted_result(
    fake_ytdlp, out_dir, monkeypatch, path_kind
):
    calls = []
    monkeypatch.setattr(sd, "check_integrity", lambda *a: calls.append("integrity"))
    monkeypatch.setattr(sd, "embed_warnings", lambda *a: calls.append("warnings") or [])
    abort = Abort()
    f = flac(out_dir)
    fake_ytdlp.before_finish = abort.set
    if path_kind == "normal":
        fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(f)}]}
    else:
        fake_ytdlp.add_pp_event("Metadata", "started", str(f))
        fake_ytdlp.raises = EMBED_FAILED

    assert_aborted(call(out_dir, abort))
    assert calls == []
