"""Helper exception shapes and the FIXED/PINNED rows for test_classify_corpus.

Messages are taken from yt-dlp 2026.10.26. yt-dlp is never imported; see test_classify_corpus.
"""


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
    (
        "network-page-fetch",
        TransportError,
        "ERROR: [Ie] id: Unable to download webpage: [Errno 111] Connection refused",
        "network",
    ),
    (
        "network-with-url",
        ExtractorError,
        "ERROR: [Ie] id: Unable to download webpage: [Errno 111] Connection refused "
        "(https://www.premiumbeat.com/track/1)",
        "network",
    ),
    ("unsupported-premium-url", UnsupportedError, PREMIUM, "unsupported"),
    ("unsupported-drm-url", UnsupportedError, DRM_URL, "unsupported"),
    ("unsupported-ffmpeg-url", UnsupportedError, FFMPEG_URL, "unsupported"),
    ("text-only-premium-url", ExtractorError, PREMIUM, "unsupported"),
    ("text-only-drm-url", ExtractorError, DRM_URL, "unsupported"),
    ("text-only-ffmpeg-url", ExtractorError, FFMPEG_URL, "unsupported"),
    ("geo-class", GeoRestrictedError, GEO, "restricted"),
    ("geo-text-only", ExtractorError, GEO, "restricted"),
    (
        "geo-with-countries",
        GeoRestrictedError,
        GEO + " This video is available in Germany.",
        "restricted",
    ),
    ("drm", ExtractorError, "ERROR: [Ie] id: This video is DRM protected", "restricted"),
    (
        "registered-users",
        ExtractorError,
        "ERROR: [Ie] id: This video is only available for registered users",
        "auth",
    ),
    # Was "auth" before #64: the bot check is the site throttling this device.
    (
        "curly-apostrophe-bot-check",
        ExtractorError,
        "ERROR: [youtube] id: Sign in to confirm you’re not a bot",
        "rate_limited",
    ),
    ("video-unavailable", ExtractorError, "ERROR: [Ie] id: Video unavailable", "unavailable"),
    (
        "private-video",
        ExtractorError,
        "ERROR: [Ie] id: Private video. Sign in if you've been granted access to this video",
        "unavailable",
    ),
    (
        "extractor-unable-to-extract",
        ExtractorError,
        "ERROR: [Ie] id: Unable to extract initial player response" + BUG,
        "extractor",
    ),
    (
        "extractor-no-formats",
        ExtractorError,
        "ERROR: [Ie] id: No video formats found!" + BUG,
        "extractor",
    ),
    (
        "storage-unavailable-video-error",
        UnavailableVideoError,
        "Unable to download video: [Errno 28] No space left on device",
        "storage",
    ),
    (
        "storage-beats-postprocessing",
        PostProcessingError,
        "ERROR: Postprocessing: [Errno 28] No space left on device",
        "storage",
    ),
    (
        "postprocessing-conversion",
        PostProcessingError,
        "ERROR: Postprocessing: Conversion failed!",
        "media_tools",
    ),
    (
        "postprocessing-ffmpeg-missing",
        FFmpegPostProcessorError,
        "ERROR: Postprocessing: ffprobe and ffmpeg not found.",
        "media_tools",
    ),
    (
        "format-not-available",
        ExtractorError,
        "ERROR: [Ie] id: Requested format is not available. Use --list-formats for a list of "
        "available formats",
        "unsupported",
    ),
]

# Pinned on purpose: these assert today's behaviour, wrong or debatable as it may be.
PINNED = [
    # A network cause inside a postprocessor failure stays media_tools; the wording is illustrative.
    (
        "pinned-pp-wraps-network",
        PostProcessingError,
        "ERROR: Postprocessing: ffmpeg exited with code 1: Connection reset by peer",
        "media_tools",
    ),
    # "does not exist" is generic; accepted until a better signal exists.
    (
        "pinned-does-not-exist",
        ExtractorError,
        "ERROR: [generic] 'My Video' does not exist",
        "unavailable",
    ),
]
