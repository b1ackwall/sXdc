"""Small helpers shared by the real-yt-dlp tests."""

import json
import os

import sxdc_download

JPEG_MAGIC = b"\xff\xd8\xff"


def download(url, out_dir, ffmpeg_dir, abort=None):
    """download_audio against the real yt-dlp, result parsed."""
    return json.loads(sxdc_download.download_audio(url, out_dir, ffmpeg_dir, abort=abort))


def listing(out_dir):
    return sorted(os.listdir(out_dir)) if os.path.isdir(out_dir) else []


def flac(path):
    from mutagen.flac import FLAC

    return FLAC(path)


def images_in(out_dir):
    return [n for n in listing(out_dir) if n.rsplit(".", 1)[-1] in ("jpg", "jpeg", "png", "webp")]


class AbortAfter:
    """An abort object whose is_set() turns True from call number `n` on (1-based)."""

    def __init__(self, n):
        self.n = n
        self.calls = 0

    def is_set(self):
        self.calls += 1
        return self.calls >= self.n


class AbortWhen:
    """An abort object that turns True once `predicate()` holds, and stays True."""

    def __init__(self, predicate):
        self.predicate = predicate
        self.tripped = False

    def is_set(self):
        self.tripped = self.tripped or bool(self.predicate())
        return self.tripped
