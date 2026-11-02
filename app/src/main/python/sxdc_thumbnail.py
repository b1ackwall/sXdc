"""The thumbnail guard (#47): check the cover yt-dlp saved before EmbedThumbnail can embed it.

yt-dlp names the thumbnail after its URL and embeds whatever bytes the server sent, so an HTML
error page or an empty file named .jpg ends up in the FLAC as a picture. download_audio adds this
guard as a "before_dl" postprocessor: it runs once the thumbnail is on disk and before the audio
download starts, so it never adds a yt-dlp option and the golden options stay as they are.

Per thumbnail on disk: the magic bytes must say JPEG, PNG, WebP or GIF, and the bundled ffmpeg
must decode it without an error. WebP, GIF and anything over MAX_COVER_BYTES is converted to
JPEG. A thumbnail that fails any step is deleted and loses its "filepath", so EmbedThumbnail
skips the cover and the final-file inspection reports no_cover.
"""

import os
import subprocess

MAX_COVER_BYTES = 1024 * 1024

_TIMEOUT = 60

_JPEG = b"\xff\xd8\xff"
_PNG = b"\x89PNG\r\n\x1a\n"


def sniff(head):
    """The image kind the first bytes of a file say: "jpeg", "png", "webp", "gif", or None."""
    if not isinstance(head, bytes | bytearray):
        return None
    if head.startswith(_JPEG):
        return "jpeg"
    if head.startswith(_PNG):
        return "png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "webp"
    if head[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    return None


def make_thumbnail_guard(ffmpeg_dir, check_abort):
    """The guard postprocessor for ydl.add_post_processor(..., when="before_dl").

    `check_abort` raises SxdcAborted once the caller's abort is set; the guard calls it when it
    starts and when it finishes, and that is the only exception run() lets out.
    """
    base = _postprocessor_base()

    class SxdcThumbnailGuardPP(base):
        def run(self, info):
            check_abort()
            guard_thumbnails(info, ffmpeg_dir)
            check_abort()
            return [], info

    return SxdcThumbnailGuardPP()


def guard_thumbnails(info, ffmpeg_dir):
    """Check, convert or drop every thumbnail in `info` that is on disk. Never raises."""
    try:
        thumbnails = info.get("thumbnails") if isinstance(info, dict) else None
        for thumbnail in thumbnails if isinstance(thumbnails, list) else []:
            if isinstance(thumbnail, dict) and isinstance(thumbnail.get("filepath"), str):
                try:
                    _guard_one(info, thumbnail, ffmpeg_dir)
                except Exception:
                    _drop(info, thumbnail)
    except Exception:
        pass


def _guard_one(info, thumbnail, ffmpeg_dir):
    path = thumbnail["filepath"]
    with open(path, "rb") as f:
        kind = sniff(f.read(12))
    if kind is None or not _decodes(ffmpeg_dir, path):
        _drop(info, thumbnail)
        return
    if kind in ("jpeg", "png") and os.path.getsize(path) <= MAX_COVER_BYTES:
        return
    converted = os.path.splitext(path)[0] + ".cover.jpg"
    if not _to_jpeg(ffmpeg_dir, path, converted):
        _remove(converted)
        _drop(info, thumbnail)
        return
    _remove(path)
    thumbnail["filepath"] = converted
    _rename_in_files_to_move(info, path, converted)


def _decodes(ffmpeg_dir, path):
    return _ffmpeg(ffmpeg_dir, ["-v", "error", "-i", path, "-f", "null", "-"])


def _to_jpeg(ffmpeg_dir, src, dst):
    args = ["-v", "error", "-y", "-i", src, "-frames:v", "1", "-q:v", "2"]
    args += ["-f", "image2", "-c:v", "mjpeg", dst]
    if not _ffmpeg(ffmpeg_dir, args):
        return False
    with open(dst, "rb") as f:
        return sniff(f.read(12)) == "jpeg"


def _ffmpeg(ffmpeg_dir, args):
    """Whether the bundled ffmpeg ran `args` cleanly: exit 0 and nothing on stderr."""
    if not ffmpeg_dir:
        return False
    try:
        proc = subprocess.run(
            [os.path.join(ffmpeg_dir, "ffmpeg"), "-nostdin", "-hide_banner", *args],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            timeout=_TIMEOUT,
        )
    except Exception:
        return False
    return proc.returncode == 0 and not proc.stderr.strip()


def _drop(info, thumbnail):
    """Delete the thumbnail file and forget it, so EmbedThumbnail finds no thumbnail on disk."""
    path = thumbnail.pop("filepath", None)
    if isinstance(path, str):
        _remove(path)
        files = info.get("__files_to_move") if isinstance(info, dict) else None
        if isinstance(files, dict):
            files.pop(path, None)


def _rename_in_files_to_move(info, old, new):
    """Mirror yt-dlp's own fixup_webp, which keeps __files_to_move in step with a renamed file."""
    files = info.get("__files_to_move")
    if isinstance(files, dict) and old in files:
        final = files.pop(old)
        files[new] = os.path.splitext(final)[0] + ".cover.jpg" if final else final


def _remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


def _postprocessor_base():
    """yt-dlp's PostProcessor, so the hooks fire for the guard too; a plain stand-in without it.

    The stand-in is what the tests' fake yt-dlp gets: it has no postprocessor package.
    """
    try:
        from yt_dlp.postprocessor.common import PostProcessor

        return PostProcessor
    except Exception:
        return _PlainPostProcessor


class _PlainPostProcessor:
    def __init__(self, downloader=None):
        self._downloader = downloader

    def set_downloader(self, downloader):
        self._downloader = downloader
