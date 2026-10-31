import json
import os

import pytest

from sxdc_download import DEFAULT_CODEC, download_audio

URL = "https://music.example.com/watch?v=abc"
FFMPEG_DIR = "/fake/ffmpeg-bin"
KEYS = {"ok", "path", "title", "size", "kind", "error", "trace"}


@pytest.fixture
def out_dir(tmp_path):
    d = tmp_path / "staging"
    d.mkdir()
    return d


def call(out_dir, codec=None, url=URL):
    if codec is None:
        raw = download_audio(url, str(out_dir), FFMPEG_DIR)
    else:
        raw = download_audio(url, str(out_dir), FFMPEG_DIR, codec)
    assert isinstance(raw, str)
    return json.loads(raw)


def make_file(directory, name, size=123):
    p = directory / name
    p.write_bytes(b"x" * size)
    return p


# --- success and path resolution ----------------------------------------------------------

def test_success_via_requested_downloads_filepath(fake_ytdlp, out_dir):
    f = make_file(out_dir, "Song.flac", 4321)
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(f)}]}

    result = call(out_dir)

    assert set(result) == KEYS
    assert result == {
        "ok": True,
        "path": str(f),
        "title": "Song",
        "size": 4321,
        "kind": None,
        "error": None,
        "trace": None,
    }
    assert result["size"] == os.path.getsize(f)
    assert fake_ytdlp.prepare_calls == []


def test_filepath_preferred_over_underscore_filename(fake_ytdlp, out_dir):
    a = make_file(out_dir, "a.flac", 1)
    b = make_file(out_dir, "b.flac", 2)
    fake_ytdlp.info = {"title": "T", "requested_downloads": [{"filepath": str(a), "_filename": str(b)}]}
    assert call(out_dir)["path"] == str(a)


def test_falls_back_to_underscore_filename(fake_ytdlp, out_dir):
    f = make_file(out_dir, "Song.flac")
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"_filename": str(f)}]}
    result = call(out_dir)
    assert result["ok"] is True
    assert result["path"] == str(f)
    assert fake_ytdlp.prepare_calls == []


def test_skips_requested_downloads_entries_without_a_path(fake_ytdlp, out_dir):
    f = make_file(out_dir, "Song.flac")
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{}, {"filepath": str(f)}]}
    assert call(out_dir)["path"] == str(f)


@pytest.mark.parametrize("requested", [None, [], [{}]], ids=["missing", "empty", "no-path"])
def test_falls_back_to_prepare_filename(fake_ytdlp, out_dir, requested):
    f = make_file(out_dir, "Song.flac")
    fake_ytdlp.info = {"title": "Song", "requested_downloads": requested}
    fake_ytdlp.filename = str(f)

    result = call(out_dir)

    assert result["ok"] is True
    assert result["path"] == str(f)
    assert len(fake_ytdlp.prepare_calls) == 1
    assert fake_ytdlp.prepare_calls[0] is fake_ytdlp.info


def test_same_stem_with_codec_extension_when_reported_path_missing(fake_ytdlp, out_dir):
    flac = make_file(out_dir, "Song.flac", 77)
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(out_dir / "Song.webm")}]}

    result = call(out_dir)

    assert result["ok"] is True
    assert result["path"] == str(flac)
    assert result["size"] == 77


def test_stem_fallback_applies_to_prepare_filename_too(fake_ytdlp, out_dir):
    flac = make_file(out_dir, "Song.flac")
    fake_ytdlp.info = {"title": "Song"}
    fake_ytdlp.filename = str(out_dir / "Song.webm")
    assert call(out_dir)["path"] == str(flac)


def test_stem_fallback_uses_the_codec_argument(fake_ytdlp, out_dir):
    opus = make_file(out_dir, "Song.opus")
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(out_dir / "Song.webm")}]}
    result = call(out_dir, codec="opus")
    assert result["ok"] is True
    assert result["path"] == str(opus)


def test_stem_fallback_ignores_a_file_with_the_wrong_extension(fake_ytdlp, out_dir):
    make_file(out_dir, "Song.flac")
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(out_dir / "Song.webm")}]}
    result = call(out_dir, codec="opus")
    assert result["ok"] is False
    assert result["kind"] == "no_output"


# --- no output file -----------------------------------------------------------------------

def test_postprocessing_finished_but_no_file(fake_ytdlp, out_dir):
    missing = out_dir / "Ghost.webm"
    fake_ytdlp.info = {"title": "Ghost", "requested_downloads": [{"filepath": str(missing)}]}

    result = call(out_dir)

    assert set(result) == KEYS
    assert result["ok"] is False
    assert result["kind"] == "no_output"
    assert result["path"] == str(missing)
    assert result["title"] == "Ghost"
    assert result["size"] is None
    assert "no output file" in result["error"].lower()
    assert result["trace"] is None


def test_no_path_anywhere_is_no_output(fake_ytdlp, out_dir):
    fake_ytdlp.info = {"title": "Ghost"}
    fake_ytdlp.filename = None
    result = call(out_dir)
    assert result["ok"] is False
    assert result["kind"] == "no_output"
    assert result["path"] is None
    assert result["size"] is None


# --- failure paths ------------------------------------------------------------------------

def test_yt_dlp_import_failure_is_engine_kind(no_ytdlp, out_dir):
    result = call(out_dir)
    assert set(result) == KEYS
    assert result["ok"] is False
    assert result["kind"] == "engine"
    assert result["error"].startswith("yt-dlp is unavailable")
    assert result["trace"] is None
    assert result["path"] is None and result["title"] is None and result["size"] is None


@pytest.mark.parametrize(
    "exc, kind",
    [
        (OSError("[Errno 28] No space left on device"), "storage"),
        (Exception("ERROR: Unable to download webpage: <urlopen error [Errno -2] Name or service not known>"), "network"),
        (ConnectionResetError("peer closed"), "network"),
        (Exception("ERROR: ffprobe and ffmpeg not found"), "media_tools"),
        (Exception("ERROR: Sign in to confirm your age"), "auth"),
        (Exception("ERROR: Unsupported URL: https://example.com"), "unsupported"),
        (RuntimeError("something odd"), "unknown"),
    ],
    ids=["storage", "network-text", "network-class", "media-tools", "auth", "unsupported", "unknown"],
)
def test_exception_is_classified_and_formatted(fake_ytdlp, out_dir, exc, kind):
    fake_ytdlp.raises = exc

    result = call(out_dir)

    assert set(result) == KEYS
    assert result["ok"] is False
    assert result["kind"] == kind
    assert result["error"] == "%s: %s" % (type(exc).__name__, exc)
    assert result["trace"]
    assert "Traceback" in result["trace"]
    assert result["path"] is None and result["title"] is None and result["size"] is None


def test_exception_from_youtubedl_constructor_is_reported_not_raised(fake_ytdlp, out_dir):
    fake_ytdlp.init_raises = RuntimeError("bad opts")
    result = call(out_dir)
    assert result["ok"] is False
    assert result["error"] == "RuntimeError: bad opts"


def test_context_manager_exits_when_extract_raises(fake_ytdlp, out_dir):
    fake_ytdlp.raises = RuntimeError("boom")
    call(out_dir)
    assert fake_ytdlp.instances[0].exited


def test_no_metadata_branch_reports_a_metadata_error(fake_ytdlp, out_dir):
    # INTENDED behaviour: extract_info returning None is a clean failure whose message mentions
    # the missing metadata. If this fails, see the issue about the one-argument _fail() call.
    fake_ytdlp.info = None
    result = call(out_dir)
    assert result["ok"] is False
    assert "metadata" in (result["error"] or "").lower()


# --- options handed to yt-dlp -------------------------------------------------------------

def test_pins_current_ydl_options_settings_preview_must_be_updated_with_it(fake_ytdlp, out_dir):
    """Characterization test. If this fails because the options changed on purpose, update
    YtDlpCommandPreview in SettingsActivity.kt (and the golden file, once there is one) as well."""
    f = make_file(out_dir, "Song.flac")
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(f)}]}

    call(out_dir)

    opts = fake_ytdlp.last_opts
    assert opts["format"] == "bestaudio/best"
    assert opts["paths"] == {"home": str(out_dir), "temp": str(out_dir)}
    assert opts["postprocessors"] == [
        {"key": "FFmpegExtractAudio", "preferredcodec": "flac", "preferredquality": "0"},
        {"key": "FFmpegMetadata"},
        {"key": "EmbedThumbnail", "already_have_thumbnail": False},
    ]
    assert opts["writethumbnail"] is True
    assert opts["restrictfilenames"] is True
    assert opts["retries"] == 3
    assert opts["fragment_retries"] == 3
    assert opts["ffmpeg_location"] == FFMPEG_DIR
    assert opts["noprogress"] is True
    assert opts["quiet"] is True
    assert opts["no_warnings"] is True
    assert opts["outtmpl"] == "%(title)s.%(ext)s"
    assert opts["noplaylist"] is True
    assert fake_ytdlp.extract_calls == [(URL, True)]


def test_codec_defaults_to_flac_and_follows_the_argument(fake_ytdlp, out_dir):
    assert DEFAULT_CODEC == "flac"
    fake_ytdlp.info = {"title": "x"}

    call(out_dir)
    assert fake_ytdlp.last_opts["postprocessors"][0]["preferredcodec"] == "flac"

    call(out_dir, codec="opus")
    assert fake_ytdlp.last_opts["postprocessors"][0]["preferredcodec"] == "opus"


def test_postprocessor_order_is_extract_then_metadata_then_thumbnail(fake_ytdlp, out_dir):
    fake_ytdlp.info = {"title": "x"}
    call(out_dir)
    keys = [p["key"] for p in fake_ytdlp.last_opts["postprocessors"]]
    assert keys == ["FFmpegExtractAudio", "FFmpegMetadata", "EmbedThumbnail"]


# --- filesystem and return contract -------------------------------------------------------

def test_out_dir_is_created_when_missing(fake_ytdlp, tmp_path):
    target = tmp_path / "a" / "b" / "staging"
    assert not target.exists()
    fake_ytdlp.info = {"title": "x"}
    call(target)
    assert target.is_dir()


def test_out_dir_creation_failure_is_reported_not_raised(fake_ytdlp, tmp_path):
    blocker = tmp_path / "file"
    blocker.write_text("not a directory")
    fake_ytdlp.info = {"title": "x"}
    result = call(blocker / "child")
    assert result["ok"] is False
    assert result["error"]
    assert fake_ytdlp.instances == []


@pytest.mark.parametrize("scenario", ["success", "no_output", "exception", "no_metadata", "bad_info"])
def test_always_returns_a_json_string_with_the_seven_keys_and_never_raises(fake_ytdlp, out_dir, scenario):
    if scenario == "success":
        f = make_file(out_dir, "Song.flac")
        fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(f)}]}
    elif scenario == "no_output":
        fake_ytdlp.info = {"title": "x", "requested_downloads": [{"filepath": str(out_dir / "nope.flac")}]}
    elif scenario == "exception":
        fake_ytdlp.raises = Exception("boom")
    elif scenario == "no_metadata":
        fake_ytdlp.info = None
    elif scenario == "bad_info":
        fake_ytdlp.info = "not a dict"  # .get() on a str raises AttributeError inside the function

    raw = download_audio(URL, str(out_dir), FFMPEG_DIR)

    assert isinstance(raw, str)
    assert set(json.loads(raw)) == KEYS
