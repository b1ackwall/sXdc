"""Regression corpus for classify(): real yt-dlp wording against the expected error kind.

Messages and exception shapes are taken from yt-dlp 2026.10.26 (the version the app was last
built with). yt-dlp is never imported: the helper classes below only reproduce its class names,
the attributes classify reads (.status, .cause, .expected, .errno), and the way failures reach
download_audio -- see the module docstring of sxdc_classify. Add a row here before changing a marker.
"""

import errno

import pytest
import sxdc_download as sd
from sxdc_download import classify


class YoutubeDLError(Exception):
    pass


class ExtractorError(YoutubeDLError):
    def __init__(self, msg, expected=False, cause=None):
        super().__init__(msg)
        self.expected = expected
        self.cause = cause


class GeoRestrictedError(ExtractorError):
    pass


class UnsupportedError(ExtractorError):
    pass


class UserNotLive(ExtractorError):
    pass


class PostProcessingError(YoutubeDLError):
    pass


class FFmpegPostProcessorError(PostProcessingError):
    pass


class UnavailableVideoError(YoutubeDLError):
    pass


class ContentTooShortError(YoutubeDLError):
    pass


class RequestError(YoutubeDLError):
    pass


class TransportError(RequestError):
    pass


class HTTPError(RequestError):
    def __init__(self, status, reason):
        super().__init__(f"HTTP Error {status}: {reason}")
        self.status = status
        self.reason = reason


class DownloadError(YoutubeDLError):
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
    # Was "auth" before #64: the bot check is the site throttling this device.
    ("curly-apostrophe-bot-check", ExtractorError,
     "ERROR: [youtube] id: Sign in to confirm you’re not a bot", "rate_limited"),
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


# --- real failure shapes (#64, #65) ------------------------------------------------------
# Each builder reproduces how yt-dlp 2026.10.26 hands that failure to download_audio.

YT_ID = "abcdefghijk"
COOKIE_HINT = (
    "Use --cookies-from-browser or --cookies for the authentication. See  "
    "https://github.com/yt-dlp/yt-dlp/wiki/FAQ#how-do-i-pass-cookies-to-yt-dlp  for how to "
    "manually pass cookies. Also see  "
    "https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies  "
    "for tips on effectively exporting YouTube cookies"
)


def download_error(msg, inner=None, context=None):
    err = DownloadError(msg, inner)
    err.__context__ = context
    return err


def page(cause, video_id="page"):
    """A failed page fetch: ExtractorError(cause=...) raised inside the except, as _request_webpage does."""
    text = f"[generic] {video_id}: Unable to download webpage: {cause} (caused by {cause!r})"
    extractor = ExtractorError(text, expected=True, cause=cause)
    extractor.__context__ = cause
    return download_error("ERROR: " + text, inner=cause, context=extractor)


def page_http(status, reason):
    return page(HTTPError(status, reason))


def media_http(status, reason):
    """A 4xx on the media request: not retried, reported straight from process_info."""
    err = HTTPError(status, reason)
    return download_error(f"ERROR: unable to download video data: {err}", inner=err, context=err)


def bare(text):
    """What is left after the media download's own retries: no exc_info, no context."""
    return download_error(f"ERROR: [download] Got error: {text}. Giving up after 3 retries")


def youtube(reason, cls=ExtractorError):
    extractor = cls(f"[youtube] {YT_ID}: {reason}", expected=True)
    return download_error("ERROR: " + str(extractor), inner=extractor, context=extractor)


def inside_except(reason, cls=ExtractorError, status=403):
    """The extractor raised its own error while handling an HTTPError: exc_info names the HTTPError."""
    http = HTTPError(status, "Forbidden")
    extractor = cls(f"[youtube] {YT_ID}: {reason}", expected=True)
    extractor.__context__ = http
    return download_error("ERROR: " + str(extractor), inner=http, context=extractor)


def unavailable_video(code, message="Unable to download video"):
    """UnavailableVideoError arrives unwrapped, the OSError on its __context__."""
    os_error = OSError(code, "write failed", "/x/y.part")
    err = UnavailableVideoError(message)
    err.__context__ = os_error
    return err


REFUSED = TransportError("[Errno 111] Connection refused")

SHAPES = [
    ("page-404", page_http(404, "Not Found"), "unavailable"),
    ("page-403", page_http(403, "Forbidden"), "unavailable"),
    ("page-429", page_http(429, "Too Many Requests"), "rate_limited"),
    ("page-500", page_http(500, "Internal Server Error"), "network"),
    ("page-503", page_http(503, "Service Unavailable"), "network"),
    ("page-timeout", page(TransportError("timed out")), "network"),
    # The two rows pinned to "unknown" before #64.
    ("media-403", media_http(403, "Forbidden"), "extractor"),
    ("media-429", media_http(429, "Too Many Requests"), "rate_limited"),
    ("media-404", media_http(404, "Not Found"), "unavailable"),
    ("media-500-bare", bare("HTTP Error 500: Internal Server Error"), "network"),
    ("media-503-bare", bare("HTTP Error 503: Service Unavailable"), "network"),
    ("content-too-short-bare", bare("Downloaded 2000 bytes, expected 64176 bytes"), "network"),
    ("incomplete-read-bare", bare("2000 bytes read, 62176 more expected"), "network"),
    ("pin-connection-reset-bare", bare("[Errno 104] Connection reset by peer"), "network"),
    ("geo-inside-except",
     inside_except("This video is not available from your location due to geo restriction",
                   GeoRestrictedError), "restricted"),
    ("login-inside-except",
     inside_except("This video is only available for registered users. " + COOKIE_HINT), "auth"),
    ("private-inside-except",
     inside_except("Private video. Sign in if you've been granted access to this video. "
                   + COOKIE_HINT), "unavailable"),
    ("status-not-trusted-under-other-extractor-error",
     inside_except("Something odd happened", status=429), "unknown"),
    ("youtube-rate-limit",
     youtube("This content isn't available, try again later. The current session has been "
             "rate-limited by YouTube for up to an hour. It is recommended to use `-t sleep` to "
             "add a delay between video requests to avoid exceeding the rate limit. For more "
             "information, refer to  https://github.com/yt-dlp/yt-dlp/wiki/Extractors"
             "#this-content-isnt-available-try-again-later"), "rate_limited"),
    ("youtube-bot-check",
     youtube("Sign in to confirm you’re not a bot. " + COOKIE_HINT), "rate_limited"),
    ("pin-age-gate",
     youtube("Sign in to confirm your age. This video may be inappropriate for some users. "
             + COOKIE_HINT), "auth"),
    ("pin-private", youtube("Private video. Sign in if you've been granted access to this video. "
                            + COOKIE_HINT), "unavailable"),
    ("premiere", youtube("Premieres in 10 hours"), "unavailable"),
    ("live-upcoming", youtube("This live event will begin in 3 hours."), "unavailable"),
    ("user-not-live", youtube("The channel is not currently live", UserNotLive), "unavailable"),
    ("user-not-live-class-only", youtube("odd", UserNotLive), "unavailable"),
    ("pin-members-only",
     youtube("Join this channel to get access to members-only content like this video, and "
             "other exclusive perks."), "auth"),
    ("pin-copyright",
     youtube("Video unavailable. This video contains content from SME, who has blocked it on "
             "copyright grounds"), "unavailable"),
    ("pin-copyright-country",
     youtube("Video unavailable. This video contains content from SME, who has blocked it in your "
             "country on copyright grounds"), "restricted"),
    ("unavailable-video-enospc", unavailable_video(errno.ENOSPC), "storage"),
    ("unavailable-video-edquot", unavailable_video(errno.EDQUOT), "storage"),
    ("unavailable-video-enospc-text",
     unavailable_video(errno.ENOSPC, "Unable to download video: [Errno 28] No space left on device"),
     "storage"),
    ("unavailable-video-eacces", unavailable_video(errno.EACCES), "unknown"),
    ("id-hazard-429-connection-refused", page(REFUSED, video_id="429"), "network"),
    ("id-hazard-403-unsupported-class",
     download_error("ERROR: Unsupported URL: http://127.0.0.1/403",
                    UnsupportedError("Unsupported URL: http://127.0.0.1/403")), "unsupported"),
    ("id-hazard-403-unsupported-text",
     wrapped(ExtractorError, "ERROR: [generic] 403: Unsupported URL: http://127.0.0.1/403"),
     "unsupported"),
    ("id-hazard-429-in-url-only",
     wrapped(ExtractorError, "ERROR: [generic] x: Unsupported URL: https://e.com/http-error-429"),
     "unsupported"),
    ("text-only-429", Exception("HTTP Error 429: Too Many Requests"), "rate_limited"),
    ("text-only-404", Exception("HTTP Error 404: Not Found"), "unavailable"),
    ("text-only-410", Exception("HTTP Error 410: Gone"), "unavailable"),
    ("status-401", media_http(401, "Unauthorized"), "auth"),
    ("status-410", media_http(410, "Gone"), "unavailable"),
    ("status-418-falls-through", media_http(418, "I'm a teapot"), "unknown"),
    # Pinned gap: only reachable from downloaders that do not retry a short read themselves; the
    # wording "content too short" is not a marker, and the class is not mapped.
    ("pin-content-too-short-class", download_error(
        "ERROR: content too short (expected 64176 bytes and served 2000)",
        ContentTooShortError("Downloaded 2000 bytes, expected 64176 bytes")), "unknown"),
]


@pytest.mark.parametrize("exc, expected", [c[1:] for c in SHAPES], ids=[c[0] for c in SHAPES])
def test_real_failure_shapes(exc, expected):
    assert classify(exc) == expected


def test_status_needs_an_int():
    err = media_http(429, "Too Many Requests")
    err.exc_info[1].status = "429"
    # Still rate_limited, but from the text marker, not the attribute.
    assert classify(err) == "rate_limited"
    err = download_error("ERROR: odd", inner=HTTPError(429, "x"))
    err.exc_info[1].status = True
    assert classify(err) == "unknown"


def test_chain_cycle_terminates():
    a = ExtractorError("a")
    b = ExtractorError("b")
    a.__context__ = b
    b.__context__ = a
    assert classify(download_error("ERROR: odd", inner=a, context=b)) == "unknown"


def test_chain_is_capped():
    # ENOSPC more than eight hops down is not looked at.
    deepest = OSError(errno.ENOSPC, "full")
    current = deepest
    for i in range(10):
        outer = ExtractorError(f"level {i}")
        outer.__context__ = current
        current = outer
    assert classify(current) == "unknown"
    assert classify(download_error("ERROR: odd", context=deepest)) == "storage"


class AttrRaises(Exception):
    def __getattribute__(self, name):
        if name in ("exc_info", "status", "cause", "__context__", "__cause__"):
            raise RuntimeError(name)
        return super().__getattribute__(name)


def test_attribute_errors_do_not_escape():
    assert classify(AttrRaises("HTTP Error 404: Not Found")) == "unavailable"
