"""yt-dlp audio extraction, driven from DownloadService over the Chaquopy bridge.

Results come back as a JSON string rather than a dict: Chaquopy maps a Python dict to a
java.util.Map of PyObject keys/values, which is awkward to read from Kotlin. A JSON string
parses cleanly with org.json.JSONObject, which is already in the Android framework.
"""

import json
import os
import sys
import traceback

from sxdc_classify import (
    KIND_ABORTED,
    KIND_AUTH,
    KIND_ENGINE,
    KIND_EXTRACTOR,
    KIND_MEDIA_TOOLS,
    KIND_NETWORK,
    KIND_NO_OUTPUT,
    KIND_RATE_LIMITED,
    KIND_RESTRICTED,
    KIND_STORAGE,
    KIND_UNAVAILABLE,
    KIND_UNKNOWN,
    KIND_UNSUPPORTED,
    classify,
)
from sxdc_verify import OUTPUT_TEMPLATE, build_ydl_opts

DEFAULT_CODEC = "flac"

# classify and the KIND_* ids live in sxdc_classify. They are re-exported here so existing imports,
# the golden kinds test and the Kotlin side keep finding them on this module.
__all__ = [
    "DEFAULT_CODEC",
    "KIND_ABORTED",
    "KIND_AUTH",
    "KIND_ENGINE",
    "KIND_EXTRACTOR",
    "KIND_MEDIA_TOOLS",
    "KIND_NETWORK",
    "KIND_NO_OUTPUT",
    "KIND_RATE_LIMITED",
    "KIND_RESTRICTED",
    "KIND_STORAGE",
    "KIND_UNAVAILABLE",
    "KIND_UNKNOWN",
    "KIND_UNSUPPORTED",
    "SxdcAborted",
    "classify",
    "download_audio",
    "expand_urls",
    "is_valid_audio",
]


class SxdcAborted(Exception):
    """Raised by sxdc's own yt-dlp hooks once the caller's abort is set (#60).

    A plain Exception subclass on purpose. Checked against yt-dlp 2026.10.26: an Exception raised
    from a progress hook, a postprocessor hook or match_filter comes out of extract_info
    unwrapped; an OSError subclass comes back wrapped in UnavailableVideoError; a BaseException
    would escape download_audio's own except and break its never-raises contract.
    """


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


def download_audio(url, out_dir, ffmpeg_dir, codec=DEFAULT_CODEC, abort=None):
    """Download `url` and extract its audio into `out_dir` using the ffmpeg at `ffmpeg_dir`.

    Returns a JSON string: {"ok": bool, "path": str|None, "title": str|None,
                            "size": int|None, "kind": str, "error": str|None, "trace": str|None}
    Never raises -- the service treats any failure as a notification state, not a crash.

    `abort` is anything with is_set(): the per-call threading.Event from PythonDownloadBridge, or
    None. Once it is set the call returns KIND_ABORTED, whatever else happened (#60). yt-dlp only
    notices at its next hook call (progress, postprocessor, match_filter), so extraction, a
    stalled socket and a running ffmpeg step finish first. Leftovers stay in out_dir for the
    staging sweep.
    """
    if _is_aborted(abort):
        return _aborted()
    try:
        import yt_dlp
    except Exception as e:
        return _fail(KIND_ENGINE, "yt-dlp is unavailable: %s" % e)

    hook, converted = _make_converted_recorder(codec)
    check_abort = _make_abort_check(abort)

    try:
        os.makedirs(out_dir, exist_ok=True)

        opts = build_ydl_opts(ffmpeg_dir, outtmpl=OUTPUT_TEMPLATE)
        opts.update({
            # check_abort first, so an aborted call records nothing more. The recorder keeps the
            # converted file so an embedding failure can still deliver the audio; see
            # _make_converted_recorder.
            "postprocessor_hooks": [check_abort, hook],
            # The download's own check points; match_filter is the one between extraction and
            # the download.
            "progress_hooks": [check_abort],
            "match_filter": check_abort,
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
            # Set after the last hook ran: still not delivered.
            if _is_aborted(abort):
                return _aborted()
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
        # The abort comes first (#60): before classify, whose chain walk would report the
        # TransportError or 429 an abort interrupted as network or rate_limited, and before the
        # embed fallback, which would deliver the converted file as a success. Checked on the
        # flag, not the exception type: a stall or a killed ffmpeg arrives as a DownloadError.
        if _is_aborted(abort):
            return _aborted()
        # The raw text still travels in "error" for the log; "kind" is what the user sees,
        # because an extractor message like "ERROR: unable to download webpage: <urlopen
        # error [Errno 7] ...>" is not something to put in a notification.
        kind = classify(e)
        kept = _kept_after_embed_failure(kind, converted, codec, e)
        if kept is not None:
            return kept
        return _fail(kind, "%s: %s" % (type(e).__name__, e), traceback.format_exc())


# ------------------------------------------------------------------ cooperative abort


def _is_aborted(abort):
    """Whether the caller asked this call to stop. Never raises: a broken object counts as unset."""
    if abort is None:
        return False
    try:
        return bool(abort.is_set())
    except Exception:
        return False


def _make_abort_check(abort):
    """One callable for all three yt-dlp check points: a progress hook and a postprocessor hook
    (one event argument) and match_filter (info_dict, incomplete=...), which must return None to
    let the download go ahead. Raises SxdcAborted once `abort` is set."""

    def check(*_args, **_kwargs):
        if _is_aborted(abort):
            raise SxdcAborted("cancelled by the app")
        return None

    return check


def _aborted():
    return _fail(KIND_ABORTED, "Cancelled by the app")


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


# ------------------------------------------------------------------ result helpers


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
