"""Map a download failure onto a stable error kind (issues #64, #65).

Never imports yt_dlp: yt-dlp classes are matched by name along each exception's MRO, so the
module loads, and the tests run, without the package. Checked against yt-dlp 2026.10.26.

How yt-dlp hands failures to download_audio, which decides what each step below can see:
  - Most failures arrive as a DownloadError whose exc_info[1] is the *root* cause, and whose
    __context__ is the ExtractorError the extractor raised. A page fetch that fails is an
    ExtractorError with .cause set to the HTTPError or TransportError.
  - An extractor that raises its own error while handling an HTTPError (geo, login required,
    private) leaves the HTTPError in exc_info[1], so its status says nothing about the reason.
  - After its own retries, the media download raises a bare DownloadError with no exc_info and no
    __context__ (5xx, connection resets, short reads). Only the text is left.
  - UnavailableVideoError arrives unwrapped, with the OSError as its __context__.
"""

import errno
import re

# Stable identifiers. ErrorMessageMapper on the Kotlin side maps each to a string resource; nothing
# user-facing is worded here, so the wording stays translatable and in one place.
KIND_NETWORK = "network"
KIND_UNAVAILABLE = "unavailable"
KIND_UNSUPPORTED = "unsupported"
KIND_RESTRICTED = "restricted"
KIND_AUTH = "auth"
KIND_STORAGE = "storage"
KIND_MEDIA_TOOLS = "media_tools"
KIND_NO_OUTPUT = "no_output"
KIND_ENGINE = "engine"
KIND_EXTRACTOR = "extractor"
KIND_RATE_LIMITED = "rate_limited"
KIND_UNKNOWN = "unknown"

# Ordered: the first kind whose markers appear wins, so the specific cases are listed before the
# broad ones. A marker is a plain substring or a compiled pattern. Never add a bare status code
# ("403", "429"): the generic extractor uses the URL's last path segment as the video id, so
# "[generic] 429: ..." turns up in unrelated errors. Match "http error NNN" or the .status attribute.
_MARKERS = (
    (KIND_STORAGE, ("no space left", "enospc", "disk quota", "errno 28")),
    # Ahead of the extractor kinds: "ffprobe not found" is a tooling fault, not a missing video.
    (KIND_MEDIA_TOOLS, ("ffmpeg", "ffprobe", "postprocessing", "postprocessor")),
    # Ahead of auth: auth's "sign in to confirm" also matches YouTube's bot check, which is the
    # site throttling this device, not a video that needs an account.
    (KIND_RATE_LIMITED, (
        "http error 429", "too many requests", "rate-limited",
        "content isn't available, try again later", "not a bot",
    )),
    (KIND_AUTH, (
        "sign in to confirm your age", "age-restricted", "age restricted",
        "sign in to confirm", "confirm you're not a bot", "login required",
        "requires authentication", "members-only", "member's only", "premium",
        "only available for registered users",
    )),
    (KIND_RESTRICTED, (
        "available in your country", "geo restricted", "georestricted",
        "blocked it in your country", "unavailable in your location", "drm",
        "not available from your location",
    )),
    # Extractor breakage, usually yt-dlp out of date after a site change. Ahead of unavailable
    # and network so a broken extractor is not reported as a missing video. The last two markers
    # are yt-dlp's bug_reports_message() suffix (yt_dlp/utils/_utils.py), which ExtractorError
    # appends to every error not flagged as expected.
    (KIND_EXTRACTOR, (
        "unable to extract", "please report this issue", "confirm you are on the latest version",
    )),
    (KIND_UNAVAILABLE, (
        "video unavailable", "this video is unavailable", "private video", "is private",
        "has been removed", "removed by the uploader", "account associated with this video",
        "does not exist", "no longer available", "video has been deleted",
        "this playlist is empty", "premieres in", "live event will begin",
        "is not currently live", "http error 404", "http error 410",
    )),
    (KIND_UNSUPPORTED, (
        "unsupported url", "is not a valid url", "no video formats", "no suitable format",
        "requested format is not available", "no media found", "there's no video",
    )),
    (KIND_NETWORK, (
        "unable to download webpage", "urlopen error", "connection", "timed out", "timeout",
        "network is unreachable", "temporary failure in name resolution", "getaddrinfo",
        "no address associated", "connection reset", "connection aborted", "connection refused",
        "unable to connect", "ssl", "handshake", "unreachable", "socket",
        # Server errors and short reads, which after yt-dlp's own retries arrive as text only.
        re.compile(r"http error 5\d\d"),
        re.compile(r"downloaded \d+ bytes, expected \d+ bytes"),
        "more expected",
    )),
)

# Messages echo the URL the user shared (`Unsupported URL: https://premiumbeat.com/...`), so a
# marker such as "premium" or "drm" inside it must not count. Curly apostrophes come from the
# YouTube alerts that yt-dlp passes through verbatim.
_URL = re.compile(r"https?://\S+")
_APOSTROPHES = str.maketrans({"’": "'", "‘": "'", "ʼ": "'"})

# Matched anywhere in the chain, in this order.
_CLASS_KINDS = (
    ("GeoRestrictedError", KIND_RESTRICTED),
    ("UnsupportedError", KIND_UNSUPPORTED),
    ("PostProcessingError", KIND_MEDIA_TOOLS),
    ("UserNotLive", KIND_UNAVAILABLE),
)

_STORAGE_ERRNOS = frozenset(
    code for code in (errno.ENOSPC, getattr(errno, "EDQUOT", None)) if code is not None
)

# Exceptions looked at per failure: the outer one plus what it wraps. Bounds a pathological chain.
_MAX_CHAIN = 8

# The 403 that yt-dlp reports for the media request itself (YoutubeDL.process_info), as opposed
# to the page. On YouTube that is almost always yt-dlp needing an update.
_MEDIA_STAGE = "unable to download video data"


def _attr(obj, name):
    try:
        return getattr(obj, name, None)
    except Exception:
        return None


def _wrapped(exc):
    """The exception a DownloadError or ExtractorError carries in exc_info, else None."""
    info = _attr(exc, "exc_info")
    if isinstance(info, tuple) and len(info) > 1 and isinstance(info[1], BaseException):
        return info[1]
    return None


def _chain(exc):
    """`exc`, then breadth-first what it wraps: exc_info[1], __cause__, __context__.

    Nearest first, each exception once (identity, so cycles end), at most _MAX_CHAIN entries.
    """
    seen = []
    queue = [exc]
    while queue and len(seen) < _MAX_CHAIN:
        current = queue.pop(0)
        if current is None or any(current is s for s in seen):
            continue
        seen.append(current)
        queue.extend((_wrapped(current), _attr(current, "__cause__"), _attr(current, "__context__")))
    return seen


def _names(obj):
    try:
        return {cls.__name__ for cls in type(obj).__mro__}
    except Exception:
        return set()


def _http_status(chain):
    """The status of an HTTPError in `chain` whose status still describes the failure, else None.

    Trusted only if no ExtractorError is in the chain (the media download), or the nearest one is
    the page-fetch wrapper whose .cause is that HTTPError. Any other ExtractorError means the
    extractor reinterpreted the response (geo, login required, private); its text decides.
    """
    http = next((e for e in chain if "HTTPError" in _names(e)), None)
    status = _attr(http, "status")
    if not isinstance(status, int) or isinstance(status, bool):
        return None
    extractor = next((e for e in chain if "ExtractorError" in _names(e)), None)
    if extractor is not None and _attr(extractor, "cause") is not http:
        return None
    return status


def _status_kind(status, text):
    if status == 429:
        return KIND_RATE_LIMITED
    if status in (404, 410):
        return KIND_UNAVAILABLE
    if status == 401:
        return KIND_AUTH
    if status == 403:
        return KIND_EXTRACTOR if _MEDIA_STAGE in text else KIND_UNAVAILABLE
    if 500 <= status <= 599:
        return KIND_NETWORK
    return None


def _message(exc):
    try:
        return str(exc)
    except Exception:
        return ""


def _matches(marker, text):
    if isinstance(marker, str):
        return marker in text
    return marker.search(text) is not None


def classify(exc):
    """Map an exception onto one of the KIND_* identifiers. Never raises.

    Order: storage (text, or ENOSPC/EDQUOT anywhere in the chain); yt-dlp classes anywhere in the
    chain; a trustworthy HTTP status; a TransportError anywhere in the chain; then the remaining
    text markers. An unrecognised failure becomes KIND_UNKNOWN and gets a generic message rather
    than a raw exception dump. The full text is still logged.
    """
    try:
        return _classify(exc)
    except Exception:
        return KIND_UNKNOWN


def _classify(exc):
    message = _URL.sub(" ", _message(exc)).translate(_APOSTROPHES)
    text = f"{type(exc).__name__} {message}".lower()
    chain = _chain(exc)

    storage_kind, storage_markers = _MARKERS[0]
    if any(_matches(m, text) for m in storage_markers):
        return storage_kind
    if any(isinstance(e, OSError) and e.errno in _STORAGE_ERRNOS for e in chain):
        return KIND_STORAGE

    names = set().union(*(_names(e) for e in chain))
    for name, kind in _CLASS_KINDS:
        if name in names:
            return kind

    status = _http_status(chain)
    if status is not None:
        kind = _status_kind(status, text)
        if kind is not None:
            return kind

    if "TransportError" in names:
        return KIND_NETWORK

    for kind, markers in _MARKERS[1:]:
        if any(_matches(m, text) for m in markers):
            return kind
    return KIND_UNKNOWN
