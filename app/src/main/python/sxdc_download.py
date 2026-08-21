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


def download_audio(url, out_dir, ffmpeg_dir, codec=DEFAULT_CODEC):
    """Download `url` and extract its audio into `out_dir` using the ffmpeg at `ffmpeg_dir`.

    Returns a JSON string: {"ok": bool, "path": str|None, "title": str|None,
                            "size": int|None, "error": str|None, "trace": str|None}
    Never raises -- the service treats any failure as a notification state, not a crash.
    """
    try:
        import yt_dlp
    except Exception as e:
        return _fail("yt-dlp is unavailable: %s" % e)

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
                return _fail("yt-dlp returned no metadata for this URL")

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
                "error": None if exists else "Post-processing finished but no output file was found",
                "trace": None,
            })

    except Exception as e:
        name = type(e).__name__
        # DownloadError already carries a user-readable message from the extractor.
        return _fail("%s: %s" % (name, e), traceback.format_exc())


def _fail(message, trace=None):
    return json.dumps({
        "ok": False,
        "path": None,
        "title": None,
        "size": None,
        "error": message,
        "trace": trace,
    })
