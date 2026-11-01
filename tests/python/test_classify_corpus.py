"""Regression corpus for classify(): real yt-dlp wording against the expected error kind.

Messages are taken from yt-dlp 2026.10.26 (the version the app was last built with). yt-dlp is
never imported: the helper classes below only reproduce its class names and the way DownloadError
carries the original exception in exc_info. Add a row here before changing a marker.
"""

import pytest
import sxdc_download as sd
from sxdc_download import classify


class ExtractorError(Exception):
    pass


class GeoRestrictedError(ExtractorError):
    pass


class UnsupportedError(ExtractorError):
    pass


class PostProcessingError(Exception):
    pass


class FFmpegPostProcessorError(PostProcessingError):
    pass


class UnavailableVideoError(Exception):
    pass


class HTTPError(Exception):
    pass


class TransportError(Exception):
    pass


class DownloadError(Exception):
    def __init__(self, msg, inner=None):
        super().__init__(msg)
        self.exc_info = (type(inner), inner, None) if inner is not None else None


def wrapped(inner_cls, message):
    return DownloadError(message, inner_cls(message))


PREMIUM = "ERROR: Unsupported URL: https://www.premiumbeat.com/track/1"
DRM_URL = "ERROR: Unsupported URL: https://drmusic.example/album"
FFMPEG_URL = "ERROR: Unsupported URL: https://example.com/ffmpeg-tutorial"
GEO = "ERROR: [Ie] id: This video is not available from your location due to geo restriction"
BUG = (
    "; please report this issue on  https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the "
    "appropriate issue template. Confirm you are on the latest version using  yt-dlp -U"
)

FIXED = [
    ("network-page-fetch", TransportError,
     "ERROR: [Ie] id: Unable to download webpage: [Errno 111] Connection refused", "network"),
    ("network-with-url", ExtractorError,
     "ERROR: [Ie] id: Unable to download webpage: [Errno 111] Connection refused "
     "(https://www.premiumbeat.com/track/1)", "network"),
    ("unsupported-premium-url", UnsupportedError, PREMIUM, "unsupported"),
    ("unsupported-drm-url", UnsupportedError, DRM_URL, "unsupported"),
    ("unsupported-ffmpeg-url", UnsupportedError, FFMPEG_URL, "unsupported"),
    ("text-only-premium-url", ExtractorError, PREMIUM, "unsupported"),
    ("text-only-drm-url", ExtractorError, DRM_URL, "unsupported"),
    ("text-only-ffmpeg-url", ExtractorError, FFMPEG_URL, "unsupported"),
    ("geo-class", GeoRestrictedError, GEO, "restricted"),
    ("geo-text-only", ExtractorError, GEO, "restricted"),
    ("geo-with-countries", GeoRestrictedError, GEO + " This video is available in Germany.", "restricted"),
    ("drm", ExtractorError, "ERROR: [Ie] id: This video is DRM protected", "restricted"),
    ("registered-users", ExtractorError,
     "ERROR: [Ie] id: This video is only available for registered users", "auth"),
    ("curly-apostrophe-bot-check", ExtractorError,
     "ERROR: [youtube] id: Sign in to confirm you’re not a bot", "auth"),
    ("video-unavailable", ExtractorError, "ERROR: [Ie] id: Video unavailable", "unavailable"),
    ("private-video", ExtractorError,
     "ERROR: [Ie] id: Private video. Sign in if you've been granted access to this video", "unavailable"),
    ("extractor-unable-to-extract", ExtractorError,
     "ERROR: [Ie] id: Unable to extract initial player response" + BUG, "extractor"),
    ("extractor-no-formats", ExtractorError, "ERROR: [Ie] id: No video formats found!" + BUG, "extractor"),
    ("storage-unavailable-video-error", UnavailableVideoError,
     "Unable to download video: [Errno 28] No space left on device", "storage"),
    ("storage-beats-postprocessing", PostProcessingError,
     "ERROR: Postprocessing: [Errno 28] No space left on device", "storage"),
    ("postprocessing-conversion", PostProcessingError, "ERROR: Postprocessing: Conversion failed!", "media_tools"),
    ("postprocessing-ffmpeg-missing", FFmpegPostProcessorError,
     "ERROR: Postprocessing: ffprobe and ffmpeg not found.", "media_tools"),
    ("format-not-available", ExtractorError,
     "ERROR: [Ie] id: Requested format is not available. Use --list-formats for a list of "
     "available formats", "unsupported"),
]

# Pinned on purpose: these assert today's behaviour, wrong or debatable as it may be.
PINNED = [
    # Issue #64: no marker covers bare HTTP status errors yet.
    ("pinned-http-429", HTTPError, "ERROR: unable to download video data: HTTP Error 429: Too Many Requests", "unknown"),
    ("pinned-http-403", HTTPError, "ERROR: unable to download video data: HTTP Error 403: Forbidden", "unknown"),
    # A network cause inside a postprocessor failure stays media_tools; the wording is illustrative.
    ("pinned-pp-wraps-network", PostProcessingError,
     "ERROR: Postprocessing: ffmpeg exited with code 1: Connection reset by peer", "media_tools"),
    # "does not exist" is generic; accepted until a better signal exists.
    ("pinned-does-not-exist", ExtractorError, "ERROR: [generic] 'My Video' does not exist", "unavailable"),
]


@pytest.mark.parametrize("inner_cls, message, expected", [c[1:] for c in FIXED], ids=[c[0] for c in FIXED])
def test_corpus_wrapped_in_download_error(inner_cls, message, expected):
    assert classify(wrapped(inner_cls, message)) == expected


@pytest.mark.parametrize("inner_cls, message, expected", [c[1:] for c in PINNED], ids=[c[0] for c in PINNED])
def test_corpus_pinned_limitations(inner_cls, message, expected):
    assert classify(wrapped(inner_cls, message)) == expected


def test_class_check_reads_the_wrapped_exception_not_the_outer_one():
    # The outer DownloadError says nothing; only exc_info[1] names the class.
    outer = DownloadError("ERROR: [Ie] id: something odd", GeoRestrictedError("x"))
    assert classify(outer) == "restricted"


def test_unwrapped_exception_class_is_used_directly():
    assert classify(UnsupportedError("odd")) == "unsupported"
    assert classify(FFmpegPostProcessorError("odd")) == "media_tools"


class NoStr:
    def __str__(self):
        raise RuntimeError("no str")


class StrRaises(Exception):
    def __str__(self):
        raise RuntimeError("no str")


def with_exc_info(value):
    err = DownloadError("ERROR: boom")
    err.exc_info = value
    return err


@pytest.mark.parametrize(
    "exc",
    [
        None,
        "text",
        42,
        NoStr(),
        with_exc_info(None),
        with_exc_info(()),
        with_exc_info((1,)),
        with_exc_info((None, None, None)),
        StrRaises(),
    ],
    ids=["none", "str", "int", "no-str-object", "exc-info-none", "exc-info-empty",
         "exc-info-short", "exc-info-nones", "str-raises"],
)
def test_classify_never_raises(exc):
    assert classify(exc) == "unknown"


def test_every_returned_kind_is_a_known_constant():
    known = {getattr(sd, name) for name in dir(sd) if name.startswith("KIND_")}
    results = {classify(wrapped(cls, msg)) for _, cls, msg, _ in FIXED + PINNED}
    assert results <= known
