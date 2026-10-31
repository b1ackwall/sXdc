"""yt-dlp audio extraction, driven from DownloadService over the Chaquopy bridge.

Results come back as a JSON string rather than a dict: Chaquopy maps a Python dict to a
java.util.Map of PyObject keys/values, which is awkward to read from Kotlin. A JSON string
parses cleanly with org.json.JSONObject, which is already in the Android framework.
"""

import json
import os
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

    try:
        os.makedirs(out_dir, exist_ok=True)

        opts = build_ydl_opts(ffmpeg_dir, outtmpl="%(title)s.%(ext)s")
        opts.update({
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
        return _fail(classify(e), "%s: %s" % (type(e).__name__, e), traceback.format_exc())


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
    )),
    (KIND_RESTRICTED, (
        "available in your country", "geo restricted", "georestricted",
        "blocked it in your country", "unavailable in your location", "drm",
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


def classify(exc):
    """Map an exception onto one of the KIND_* identifiers.

    Best-effort by design: an unrecognised failure becomes KIND_UNKNOWN and gets a generic
    message rather than a raw exception dump. The full text is still logged.
    """
    text = ("%s %s" % (type(exc).__name__, exc)).lower()
    for kind, markers in _MARKERS:
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
