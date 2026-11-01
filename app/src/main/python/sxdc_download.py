"""yt-dlp audio extraction, driven from DownloadService over the Chaquopy bridge.

Results come back as a JSON string rather than a dict: Chaquopy maps a Python dict to a
java.util.Map of PyObject keys/values, which is awkward to read from Kotlin. A JSON string
parses cleanly with org.json.JSONObject, which is already in the Android framework.
"""

import json
import os
import re
import sys
import traceback

from sxdc_verify import build_ydl_opts

DEFAULT_CODEC = "flac"


def expand_urls(url):
    """Resolve `url` to the list of tracks it actually names, without downloading anything.

    Returns a JSON string: a list of {"url": str, "title": str|None}. A single video comes back
    as a one-element list with title=None -- title is unknown until the real download runs, same
    as today's single-track path. A playlist comes back as one element per entry, using each
    entry's own url/title from yt-dlp directly rather than reconstructing a canonical URL by hand,
    so the dedup check downstream matches the same string a direct share of that track would use.

    `noplaylist=False` here is deliberate and the opposite of `download_audio`'s default: a flat
    pass needs playlist entries to actually come back rather than collapsing to a single video.
    No `ffmpeg_location` is needed -- `skip_download=True` means no postprocessor ever runs, so
    `build_ydl_opts` is called with `bin_dir=None`. Never raises -- an extraction failure here
    just falls back to treating `url` as one track, which is exactly what would happen if this
    function did not exist at all.
    """
    try:
        import yt_dlp

        opts = build_ydl_opts(
            None,
            noplaylist=False,
            extra={"extract_flat": "in_playlist", "skip_download": True},
        )
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)

        if info is not None and (info.get("_type") == "playlist" or "entries" in info):
            entries = [e for e in (info.get("entries") or []) if e]
            tracks = [
                {"url": e.get("url") or e.get("webpage_url"), "title": e.get("title")}
                for e in entries
            ]
            tracks = [t for t in tracks if t["url"]]
            if tracks:
                return json.dumps(tracks)

    except Exception:
        pass

    return json.dumps([{"url": url, "title": None}])


def download_audio(url, out_dir, ffmpeg_dir, codec=DEFAULT_CODEC):
    """Download `url` and extract its audio into `out_dir` using the ffmpeg at `ffmpeg_dir`.

    Returns a JSON string: {"ok": bool, "path": str|None, "title": str|None,
                            "size": int|None, "kind": str, "error": str|None, "trace": str|None}
    Never raises -- the service treats any failure as a notification state, not a crash.
    """
    try:
        import yt_dlp
    except Exception as e:
        return _fail(KIND_ENGINE, "yt-dlp is unavailable: %s" % e)

    hook, converted = _make_converted_recorder(codec)

    try:
        os.makedirs(out_dir, exist_ok=True)

        opts = build_ydl_opts(ffmpeg_dir, outtmpl="%(title)s.%(ext)s")
        opts.update({
            # Records the converted file so an embedding failure can still deliver the audio.
            # See _make_converted_recorder.
            "postprocessor_hooks": [hook],
            "format": "bestaudio/best",
            # `paths` keeps both the partial and the finished file inside out_dir, so a failed
            # run cannot strand a .part file somewhere unwritable.
            "paths": {"home": out_dir, "temp": out_dir},
            # Order matters: yt-dlp appends these to the "post_process" bucket in list order
            # (YoutubeDL.add_post_processor), and both embedding steps must see the final FLAC
            # container. yt-dlp's own CLI builds the same sequence for
            # `-x --audio-format flac --embed-metadata --embed-thumbnail`.
            "postprocessors": [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": codec,
                    # Ignored for a lossless target, but kept to mirror `--audio-quality 0`.
                    "preferredquality": "0",
                },
                # Must follow extraction: the pre-conversion container (webm/3gp) often cannot
                # hold the tags at all, and ffmpeg only preserves metadata both ends support.
                # The bare key reproduces `--embed-metadata` exactly -- the PP's own defaults
                # (add_metadata=True, add_chapters=True, add_infojson="if_exists") are the same
                # values the CLI resolves that flag to.
                {"key": "FFmpegMetadata"},
                # Must follow FFmpegMetadata: ffmpeg rewrites the file wholesale when it writes
                # tags, which would drop a picture block embedded before it.
                {
                    "key": "EmbedThumbnail",
                    # False => the downloaded sidecar image is deleted once embedded, matching
                    # `--embed-thumbnail` without `--write-thumbnail`.
                    "already_have_thumbnail": False,
                },
            ],
            # Required by EmbedThumbnail: it embeds the image yt-dlp has already written to
            # disk, so with this off there is nothing for it to pick up.
            "writethumbnail": True,
            # Android filesystems and SAF are both unhappy with the full unicode/punctuation
            # range that video titles contain; restrict to a safe ASCII subset.
            "restrictfilenames": True,
            "noprogress": True,
            "retries": 3,
            "fragment_retries": 3,
        })

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if info is None:
                return _fail(KIND_NO_OUTPUT, "yt-dlp returned no metadata for this URL")

            # After a postprocessor runs, the authoritative output path lives in
            # requested_downloads; prepare_filename() still reports the pre-extraction name.
            path = None
            for entry in (info.get("requested_downloads") or []):
                path = entry.get("filepath") or entry.get("_filename")
                if path:
                    break
            if not path:
                path = ydl.prepare_filename(info)

            # The extractor rewrites the extension; trust the file that actually exists.
            if path and not os.path.exists(path):
                stem = os.path.splitext(path)[0]
                candidate = "%s.%s" % (stem, codec)
                if os.path.exists(candidate):
                    path = candidate

            exists = bool(path) and os.path.exists(path)
            return json.dumps({
                "ok": exists,
                "path": path,
                "title": info.get("title"),
                "size": os.path.getsize(path) if exists else None,
                "kind": None if exists else KIND_NO_OUTPUT,
                "error": None if exists else "Post-processing finished but no output file was found",
                "trace": None,
            })

    except Exception as e:
        # The raw text still travels in "error" for the log; "kind" is what the user sees,
        # because an extractor message like "ERROR: unable to download webpage: <urlopen
        # error [Errno 7] ...>" is not something to put in a notification.
        kind = classify(e)
        kept = _kept_after_embed_failure(kind, converted, codec, e)
        if kept is not None:
            return kept
        return _fail(kind, "%s: %s" % (type(e).__name__, e), traceback.format_exc())


# ------------------------------------------------------------------ embed-failure fallback

# yt-dlp's postprocessor names (pp_key) for the two steps that only embed into the finished file.
_EMBED_POSTPROCESSORS = ("Metadata", "EmbedThumbnail")


def _make_converted_recorder(codec):
    """Return (hook, record): a postprocessor hook and the dict it fills with "path" and "title".

    Only the "started" event of an embedding step is trusted. yt-dlp hands every hook a copy of
    info_dict taken before the postprocessor runs, so ExtractAudio's own "finished" event still
    names the pre-conversion file; the next step's "started" event is the first to carry the
    converted path. Events fire more than once; the last valid one wins. Never raises: a hook
    exception would propagate out of yt-dlp and fail a download that was otherwise fine.
    """
    record = {}
    suffix = "." + codec

    def hook(event):
        try:
            if event.get("status") != "started":
                return
            if event.get("postprocessor") not in _EMBED_POSTPROCESSORS:
                return
            info = event.get("info_dict") or {}
            path = info.get("filepath")
            if isinstance(path, str) and path.endswith(suffix):
                title = info.get("title")
                record["path"] = path
                record["title"] = title if isinstance(title, str) else None
        except Exception:
            pass

    return hook, record


def is_valid_audio(path, codec):
    """Whether `path` is a usable, finished audio file in `codec`. Never raises.

    Non-empty, and for flac the "fLaC" stream marker; when mutagen is importable (it is bundled
    in the APK) the file must also parse as FLAC with a positive duration.
    """
    try:
        if not path or not os.path.isfile(path) or os.path.getsize(path) <= 0:
            return False
        if codec != "flac":
            return True
        with open(path, "rb") as f:
            if f.read(4) != b"fLaC":
                return False
        return _flac_parses(path)
    except Exception:
        return False


def _flac_parses(path):
    try:
        from mutagen.flac import FLAC
    except ImportError:
        return True
    return FLAC(path).info.length > 0


def _kept_after_embed_failure(kind, converted, codec, exc):
    """A success result for the converted file if only embedding failed, else None."""
    path = converted.get("path")
    if kind == KIND_STORAGE or not path or not is_valid_audio(path, codec):
        return None
    try:
        size = os.path.getsize(path)
    except OSError:
        return None
    print(
        "sxdc: embedding failed, audio kept at %s (%s: %s)" % (path, type(exc).__name__, exc),
        file=sys.stderr,
    )
    return json.dumps({
        "ok": True,
        "path": path,
        "title": converted.get("title"),
        "size": size,
        "kind": None,
        "error": None,
        "trace": None,
    })


# ------------------------------------------------------------------ error classification

# Stable identifiers. DownloadService maps each to a string resource; nothing user-facing is
# worded here, so the wording stays translatable and in one place on the Kotlin side.
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
KIND_UNKNOWN = "unknown"

# Ordered: the first kind whose markers appear wins, so the specific cases are listed before
# the broad ones. yt-dlp funnels nearly everything through DownloadError, which makes the
# message text -- not the exception class -- the only thing worth matching on.
_MARKERS = (
    (KIND_STORAGE, ("no space left", "enospc", "disk quota", "errno 28")),
    # Ahead of the extractor kinds: "ffprobe not found" is a tooling fault, not a missing video.
    (KIND_MEDIA_TOOLS, ("ffmpeg", "ffprobe", "postprocessing", "postprocessor")),
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
        "this playlist is empty",
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
    )),
)


# Messages echo the URL the user shared (`Unsupported URL: https://premiumbeat.com/...`), so a
# marker such as "premium" or "drm" inside it must not count. Curly apostrophes come from the
# YouTube alerts that yt-dlp passes through verbatim.
_URL = re.compile(r"https?://\S+")
_APOSTROPHES = str.maketrans({"\u2019": "'", "\u2018": "'", "\u02bc": "'"})

# yt-dlp class names, matched as strings so this module never imports yt_dlp. Checked along the
# MRO of the original exception, in this order.
_CLASS_KINDS = (
    ("GeoRestrictedError", KIND_RESTRICTED),
    ("UnsupportedError", KIND_UNSUPPORTED),
    ("PostProcessingError", KIND_MEDIA_TOOLS),
)


def _message(exc):
    try:
        return str(exc)
    except Exception:
        return ""


def _inner_exception(exc):
    """The exception yt-dlp wrapped in a DownloadError, else `exc` itself. Never raises."""
    try:
        info = getattr(exc, "exc_info", None)
        if isinstance(info, tuple) and len(info) > 1 and isinstance(info[1], Exception):
            return info[1]
    except Exception:
        pass
    return exc


def classify(exc):
    """Map an exception onto one of the KIND_* identifiers.

    Order: storage text, then the class of the wrapped exception (yt-dlp funnels nearly
    everything through DownloadError, so the class is read from exc_info), then the remaining
    text markers. Best-effort by design: an unrecognised failure becomes KIND_UNKNOWN and gets
    a generic message rather than a raw exception dump. The full text is still logged.
    """
    message = _URL.sub(" ", _message(exc)).translate(_APOSTROPHES)
    text = ("%s %s" % (type(exc).__name__, message)).lower()
    storage_kind, storage_markers = _MARKERS[0]
    if any(marker in text for marker in storage_markers):
        return storage_kind
    names = {cls.__name__ for cls in type(_inner_exception(exc)).__mro__}
    for name, kind in _CLASS_KINDS:
        if name in names:
            return kind
    for kind, markers in _MARKERS[1:]:
        if any(marker in text for marker in markers):
            return kind
    return KIND_UNKNOWN


def _fail(kind, message, trace=None):
    return json.dumps({
        "ok": False,
        "path": None,
        "title": None,
        "size": None,
        "kind": kind,
        "error": message,
        "trace": trace,
    })
