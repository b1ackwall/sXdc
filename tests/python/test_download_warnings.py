"""The finished-file checks in download_audio: KIND_INCOMPLETE (#46) and the warnings (#45).

Both run on the normal path and on the embed fallback, against the real file on disk.
"""

import json
import sys

import pytest
from flac_builder import build_flac, write_flac
from sxdc_classify import KIND_INCOMPLETE
from sxdc_download import download_audio
from sxdc_embed import (
    WARN_NO_COVER,
    WARN_NO_TAGS,
    WARN_SHORT_AUDIO,
    _shorter_than_source,
    embed_warnings,
)

URL = "https://music.example.com/watch?v=abc"
FFMPEG_DIR = "/fake/ffmpeg-bin"
THUMBS = [{"url": "https://i.example.com/t.jpg"}]
JPEG = b"\xff\xd8\xff\xe0" + b"j" * 40
PNG = b"\x89PNG\r\n\x1a\n" + b"p" * 40
HTML = b"<!DOCTYPE html><html></html>"
EMBED_FAILED = Exception("ERROR: Postprocessing: Conversion failed!")


@pytest.fixture(autouse=True)
def no_mutagen(monkeypatch):
    monkeypatch.setitem(sys.modules, "mutagen", None)
    monkeypatch.setitem(sys.modules, "mutagen.flac", None)


@pytest.fixture
def out_dir(tmp_path):
    d = tmp_path / "staging"
    d.mkdir()
    return d


def call(out_dir, codec=None):
    args = (URL, str(out_dir), FFMPEG_DIR) + (() if codec is None else (codec,))
    return json.loads(download_audio(*args))


def deliver(fake_ytdlp, out_dir, built_kwargs=None, data=None, **info):
    """Run the normal path on a file built from `built_kwargs` (or `data`), with `info` added."""
    f = out_dir / "Song.flac"
    f.write_bytes(data if data is not None else build_flac(**(built_kwargs or {})).data)
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(f)}], **info}
    return call(out_dir), f


# --- the normal path ----------------------------------------------------------------------


def test_complete_file_with_tags_and_cover_has_no_warnings(fake_ytdlp, out_dir):
    result, _ = deliver(
        fake_ytdlp, out_dir, {"pictures": [("image/jpeg", JPEG)]}, thumbnails=THUMBS, duration=4
    )
    assert result["ok"] is True
    assert result["warnings"] == []


def test_missing_title_tag_is_no_tags(fake_ytdlp, out_dir):
    result, _ = deliver(fake_ytdlp, out_dir, {"title": None})
    assert result["warnings"] == [WARN_NO_TAGS]


def test_blank_title_tag_is_no_tags(fake_ytdlp, out_dir):
    result, _ = deliver(fake_ytdlp, out_dir, {"title": "  "})
    assert result["warnings"] == [WARN_NO_TAGS]


@pytest.mark.parametrize("key, value", [("thumbnails", THUMBS), ("thumbnail", "https://i/t.jpg")])
def test_offered_thumbnail_without_a_picture_is_no_cover(fake_ytdlp, out_dir, key, value):
    result, _ = deliver(fake_ytdlp, out_dir, **{key: value})
    assert result["warnings"] == [WARN_NO_COVER]


def test_picture_that_is_not_an_image_is_no_cover(fake_ytdlp, out_dir):
    result, _ = deliver(
        fake_ytdlp, out_dir, {"pictures": [("image/None", HTML)]}, thumbnails=THUMBS
    )
    assert result["warnings"] == [WARN_NO_COVER]


def test_png_picture_counts_as_a_cover(fake_ytdlp, out_dir):
    result, _ = deliver(fake_ytdlp, out_dir, {"pictures": [("image/png", PNG)]}, thumbnails=THUMBS)
    assert result["warnings"] == []


def test_no_thumbnail_offered_means_no_cover_warning(fake_ytdlp, out_dir):
    result, _ = deliver(fake_ytdlp, out_dir, thumbnails=[])
    assert result["warnings"] == []


def test_both_missing_lists_tags_then_cover(fake_ytdlp, out_dir):
    result, _ = deliver(fake_ytdlp, out_dir, {"title": None}, thumbnails=THUMBS)
    assert result["warnings"] == [WARN_NO_TAGS, WARN_NO_COVER]


# The built file is 4.248 s of audio.
@pytest.mark.parametrize(
    "info, short",
    [
        ({"duration": 60}, True),
        ({"duration": 9.3}, True),  # 5.05 s short
        ({"duration": 9.2}, False),  # 4.95 s short: inside the 5 s floor
        ({"duration": 4}, False),  # longer than reported is fine
        ({"duration": 60, "is_live": True}, False),
        ({"duration": 60, "live_status": "is_live"}, False),
        ({"duration": 60, "live_status": "is_upcoming"}, False),
        ({"duration": 60, "live_status": "was_live"}, True),
        ({}, False),
        ({"duration": None}, False),
        ({"duration": True}, False),
        ({"duration": "60"}, False),
        ({"duration": 0}, False),
        ({"duration": -5}, False),
    ],
)
def test_short_audio(fake_ytdlp, out_dir, info, short):
    result, _ = deliver(fake_ytdlp, out_dir, **info)
    assert result["ok"] is True
    assert result["warnings"] == ([WARN_SHORT_AUDIO] if short else [])


@pytest.mark.parametrize("actual, short", [(970.5, False), (969.5, True), (None, False)])
def test_short_audio_uses_the_three_percent_margin_on_long_sources(actual, short):
    # 1000 s reported: the margin is 30 s, not 5 s.
    assert _shorter_than_source(actual, {"duration": 1000}) is short


def test_truncated_file_is_incomplete_and_not_delivered(fake_ytdlp, out_dir):
    built = build_flac()
    result, f = deliver(fake_ytdlp, out_dir, data=built.data[:-10], thumbnails=THUMBS)

    assert result["ok"] is False
    assert result["kind"] == KIND_INCOMPLETE
    assert result["path"] is None
    assert result["warnings"] == []
    assert str(f) in result["error"]
    assert f.exists()  # left for the end-of-run staging sweep


def test_killed_encoder_output_is_incomplete(fake_ytdlp, out_dir):
    result, _ = deliver(fake_ytdlp, out_dir, {"total_samples": 0})
    assert result["kind"] == KIND_INCOMPLETE


def test_unreadable_flac_fails_open_with_no_warnings(fake_ytdlp, out_dir):
    result, _ = deliver(fake_ytdlp, out_dir, data=b"garbage" * 50, thumbnails=THUMBS, duration=600)
    assert result["ok"] is True
    assert result["warnings"] == []


def test_other_codecs_are_not_checked(fake_ytdlp, out_dir):
    f = out_dir / "Song.opus"
    f.write_bytes(build_flac(title=None).data[:-10])
    fake_ytdlp.info = {
        "title": "Song",
        "requested_downloads": [{"filepath": str(f)}],
        "thumbnails": THUMBS,
    }
    result = call(out_dir, codec="opus")
    assert result["ok"] is True
    assert result["warnings"] == []


# --- the embed fallback -------------------------------------------------------------------


def fallback(fake_ytdlp, out_dir, step, data, **source):
    f = out_dir / "Song.flac"
    f.write_bytes(data)
    fake_ytdlp.add_pp_event(step, "started", str(f), "My Song", **source)
    fake_ytdlp.raises = EMBED_FAILED
    return call(out_dir), f


def test_tags_step_failed_reports_no_tags_and_no_cover(fake_ytdlp, out_dir):
    # FFmpegMetadata failed, so EmbedThumbnail never ran: no title tag and no picture.
    result, f = fallback(
        fake_ytdlp, out_dir, "Metadata", build_flac(title=None).data, thumbnails=THUMBS, duration=4
    )
    assert result["ok"] is True
    assert result["path"] == str(f)
    assert result["warnings"] == [WARN_NO_TAGS, WARN_NO_COVER]


def test_cover_step_failed_reports_no_cover(fake_ytdlp, out_dir):
    result, _ = fallback(
        fake_ytdlp, out_dir, "EmbedThumbnail", build_flac().data, thumbnail="https://i/t.jpg"
    )
    assert result["warnings"] == [WARN_NO_COVER]


def test_fallback_reads_the_duration_from_the_recorded_event(fake_ytdlp, out_dir):
    result, _ = fallback(fake_ytdlp, out_dir, "EmbedThumbnail", build_flac().data, duration=120)
    assert result["warnings"] == [WARN_SHORT_AUDIO]


def test_truncated_file_kept_by_the_fallback_is_incomplete(fake_ytdlp, out_dir, capsys):
    result, f = fallback(fake_ytdlp, out_dir, "EmbedThumbnail", build_flac().data[:-10])
    assert result["ok"] is False
    assert result["kind"] == KIND_INCOMPLETE
    assert result["warnings"] == []
    assert f.exists()


# --- embed_warnings directly --------------------------------------------------------------


@pytest.mark.parametrize("source", [None, "x", [], {"thumbnails": None}])
def test_embed_warnings_tolerates_odd_sources(tmp_path, source):
    p = tmp_path / "a.flac"
    write_flac(p)
    assert embed_warnings(str(p), "flac", source) == []


@pytest.mark.parametrize("path", [None, "/no/such.flac", 7])
def test_embed_warnings_of_a_bad_path_is_empty(path):
    assert embed_warnings(path, "flac", {"thumbnails": THUMBS}) == []
