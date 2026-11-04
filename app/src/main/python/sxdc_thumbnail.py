"""The thumbnail guard (#47): check the cover yt-dlp saved before EmbedThumbnail can embed it.

yt-dlp names the thumbnail after its URL and embeds whatever bytes the server sent, so an HTML
error page or an empty file named .jpg ends up in the FLAC as a picture. download_audio adds this
guard as a "before_dl" postprocessor: it runs once the thumbnail is on disk and before the audio
download starts, so it never adds a yt-dlp option and the golden options stay as they are.

Per thumbnail on disk: the magic bytes must say JPEG, PNG, WebP or GIF, and the bundled ffmpeg
must decode it without an error. WebP, GIF, anything over MAX_COVER_BYTES and anything with a
long edge over MAX_COVER_EDGE (read from the header, without decoding) is converted to JPEG,
scaled down to MAX_COVER_EDGE. A converted cover still over MAX_COVER_BYTES is retried once at
lower quality and dropped if it is still too big (#76). A thumbnail that fails any step is deleted and loses its "filepath", so EmbedThumbnail
skips the cover and the final-file inspection reports no_cover.
"""

import os
import subprocess

MAX_COVER_BYTES = 1024 * 1024
MAX_COVER_EDGE = 1600

_SCALE = (
    f"scale='min({MAX_COVER_EDGE},iw)':'min({MAX_COVER_EDGE},ih)'"
    ":force_original_aspect_ratio=decrease"
)
_QUALITY = "2"
_RETRY_QUALITY = "5"

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


def png_dimensions(head):
    """(width, height) from a PNG's IHDR chunk, or None if the bytes do not say."""
    try:
        if len(head) < 24 or not head.startswith(_PNG) or head[12:16] != b"IHDR":
            return None
        return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")
    except Exception:
        return None


def jpeg_dimensions(f):
    """(width, height) from the first SOF marker of a JPEG in binary file `f`, or None."""
    try:
        if f.read(2) != b"\xff\xd8":
            return None
        while True:
            b = f.read(1)
            if not b:
                return None
            if b != b"\xff":
                continue
            marker = f.read(1)
            while marker == b"\xff":
                marker = f.read(1)
            if not marker:
                return None
            m = marker[0]
            if m == 0 or m == 0x01 or 0xD0 <= m <= 0xD8:
                continue  # stuffed byte, TEM, RSTn, SOI: no length field
            if m == 0xD9 or m == 0xDA:
                return None  # EOI, or the scan starts without a frame header
            seg = f.read(2)
            if len(seg) < 2:
                return None
            length = int.from_bytes(seg, "big")
            if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
                data = f.read(5)
                if length < 7 or len(data) < 5:
                    return None
                return int.from_bytes(data[3:5], "big"), int.from_bytes(data[1:3], "big")
            if length < 2:
                return None
            f.seek(length - 2, 1)
    except Exception:
        return None


def _dimensions(f, kind):
    """Image size from the header, or None (unknown kind, unparseable, or a zero side)."""
    try:
        f.seek(0)
        size = png_dimensions(f.read(24)) if kind == "png" else None
        if kind == "jpeg":
            size = jpeg_dimensions(f)
        return size if size and min(size) > 0 else None
    except Exception:
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
        size = _dimensions(f, kind)
    if kind is None or not _decodes(ffmpeg_dir, path):
        _drop(info, thumbnail)
        return
    oversized = size is not None and max(size) > MAX_COVER_EDGE
    if kind in ("jpeg", "png") and not oversized and os.path.getsize(path) <= MAX_COVER_BYTES:
        return
    converted = os.path.splitext(path)[0] + ".cover.jpg"
    if not _convert_within_cap(ffmpeg_dir, path, converted):
        _remove(converted)
        _drop(info, thumbnail)
        return
    _remove(path)
    thumbnail["filepath"] = converted
    _rename_in_files_to_move(info, path, converted)


def _decodes(ffmpeg_dir, path):
    return _ffmpeg(ffmpeg_dir, ["-v", "error", "-i", path, "-f", "null", "-"])


def _convert_within_cap(ffmpeg_dir, src, dst):
    """Convert once; if the JPEG is still over the cap, once more at lower quality."""
    for quality in (_QUALITY, _RETRY_QUALITY):
        if _to_jpeg(ffmpeg_dir, src, dst, quality) and os.path.getsize(dst) <= MAX_COVER_BYTES:
            return True
    return False


def _to_jpeg(ffmpeg_dir, src, dst, quality=_QUALITY):
    args = ["-v", "error", "-y", "-i", src, "-frames:v", "1", "-vf", _SCALE, "-q:v", quality]
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
