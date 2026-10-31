"""Pins download_audio's yt-dlp options to tests/golden/ytdlp-options.properties.

The same golden file is checked from Kotlin by YtDlpCommandPreviewGoldenTest, so the Settings
command preview and the options Python actually uses cannot drift apart unnoticed.
"""

from pathlib import Path

import pytest

import sxdc_download
from sxdc_download import download_audio

GOLDEN = Path(__file__).resolve().parents[1] / "golden" / "ytdlp-options.properties"


def load_golden(path=GOLDEN):
    """Tiny key=value parser: '#' or '!' comment lines and blank lines are ignored."""
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, sep, value = line.partition("=")
        assert sep, "malformed golden line: %r" % raw
        values[key.strip()] = value.strip()
    return values


def as_bool(value):
    assert value in ("true", "false"), value
    return value == "true"


@pytest.fixture(scope="module")
def golden():
    return load_golden()


@pytest.fixture
def opts(fake_ytdlp, tmp_path):
    fake_ytdlp.info = {"title": "T", "requested_downloads": []}
    download_audio("https://music.example.com/watch?v=abc", str(tmp_path), "/fake/ffmpeg-bin")
    return fake_ytdlp.last_opts


def _keys(opts):
    return [pp["key"] for pp in opts["postprocessors"]]


def test_extract_audio_codec_and_quality(golden, opts):
    extract = opts["postprocessors"][0]
    assert extract["key"] == "FFmpegExtractAudio"
    assert extract["preferredcodec"] == golden["codec"]
    assert extract["preferredquality"] == golden["quality"]


def test_output_template(golden, opts):
    assert opts["outtmpl"] == golden["outputTemplate"]


def test_restrict_filenames(golden, opts):
    assert opts["restrictfilenames"] is as_bool(golden["restrictFilenames"])


def test_embed_postprocessors_follow_extraction_in_order(golden, opts):
    keys = _keys(opts)
    assert keys[0] == "FFmpegExtractAudio"
    expected = []
    if as_bool(golden["embedMetadata"]):
        expected.append("FFmpegMetadata")
    if as_bool(golden["embedThumbnail"]):
        expected.append("EmbedThumbnail")
    assert keys[1:] == expected


def test_kind_constants_match_golden(golden):
    kinds = {
        value for name, value in vars(sxdc_download).items()
        if name.startswith("KIND_") and isinstance(value, str)
    }
    expected = [k.strip() for k in golden["errorKinds"].split(",")]
    assert len(expected) == len(set(expected))
    assert kinds == set(expected)
