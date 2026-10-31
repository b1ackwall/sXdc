"""Pins download_audio's yt-dlp options to tests/golden/ytdlp-options.properties.

The same golden file is checked from Kotlin by YtDlpCommandPreviewGoldenTest, so the Settings
command preview and the options Python actually uses cannot drift apart unnoticed. The checks
here are exhaustive: every golden option equals the real one, and a real option that is neither
in the golden file nor in ALLOWED_UNLISTED fails the suite.
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


# Golden key -> the yt-dlp option key it must equal, value for value.
DIRECT_OPTIONS = {
    "outputTemplate": "outtmpl",
    "restrictFilenames": "restrictfilenames",
    "format": "format",
    "noplaylist": "noplaylist",
    "retries": "retries",
    "fragmentRetries": "fragment_retries",
    "noprogress": "noprogress",
    "quiet": "quiet",
    "noWarnings": "no_warnings",
    "writeThumbnail": "writethumbnail",
}

# Golden keys checked against the postprocessor chain instead (see the tests above).
POSTPROCESSOR_KEYS = {"codec", "quality", "embedMetadata", "embedThumbnail"}

# Real option keys that deliberately have no golden value: environment-specific paths, the
# postprocessor chain (covered key by key above) and the Python-only hook.
ALLOWED_UNLISTED = {"ffmpeg_location", "paths", "postprocessors", "postprocessor_hooks"}


def test_every_golden_option_equals_the_real_value(golden, opts):
    for golden_key, opt_key in DIRECT_OPTIONS.items():
        expected = golden[golden_key]
        actual = opts[opt_key]
        if isinstance(actual, bool):
            expected = as_bool(expected)
        elif isinstance(actual, int):
            expected = int(expected)
        assert actual == expected, "%s (%s)" % (golden_key, opt_key)


def test_real_options_and_golden_keys_cover_each_other_exactly(golden, opts):
    """A new real option fails here until it is added to the golden file or ALLOWED_UNLISTED."""
    unlisted = set(opts) - set(DIRECT_OPTIONS.values()) - ALLOWED_UNLISTED
    assert not unlisted, "real option(s) not in golden or the allowlist: %s" % sorted(unlisted)

    missing = (set(DIRECT_OPTIONS.values()) | ALLOWED_UNLISTED) - set(opts)
    assert not missing, "golden/allowlist names option(s) the code no longer sets: %s" % sorted(missing)

    option_keys = set(golden) - {"errorKinds"}
    unknown = option_keys - set(DIRECT_OPTIONS) - POSTPROCESSOR_KEYS
    assert not unknown, "golden key(s) with no real-option mapping: %s" % sorted(unknown)


def test_kind_constants_match_golden(golden):
    kinds = {
        value for name, value in vars(sxdc_download).items()
        if name.startswith("KIND_") and isinstance(value, str)
    }
    expected = [k.strip() for k in golden["errorKinds"].split(",")]
    assert len(expected) == len(set(expected))
    assert kinds == set(expected)
