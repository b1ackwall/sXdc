"""What download_audio checks about the embedding steps (#45).

The embed-failure fallback keeps the converted audio when only embedding fails: download_audio
installs the recorder as a yt-dlp postprocessor hook and, when yt-dlp raises, asks
_kept_after_embed_failure whether the converted file can still be delivered.

embed_warnings then looks at the final file, whichever way it was kept, and says what it lacks.
"""

import os
import sys

from sxdc_classify import KIND_STORAGE
from sxdc_flac import read_flac

# Warning ids in a success result's "warnings". WarningKinds on the Kotlin side mirrors them.
WARN_NO_COVER = "no_cover"
WARN_NO_TAGS = "no_tags"
WARN_SHORT_AUDIO = "short_audio"

# The info_dict fields embed_warnings reads about the source. The recorder copies them, so the
# fallback, which has no final info_dict, can still be checked.
SOURCE_KEYS = ("duration", "is_live", "live_status", "thumbnail", "thumbnails")

# Live sources have no meaningful duration to compare against.
_LIVE_STATUSES = ("is_live", "is_upcoming")

# Shorter than the source says by more than this many seconds, or this fraction, is short_audio.
_SHORT_SECONDS = 5.0
_SHORT_FRACTION = 0.03

_IMAGE_MAGIC = (b"\xff\xd8\xff", b"\x89PNG\r\n\x1a\n")

# yt-dlp's postprocessor names (pp_key) for the two steps that only embed into the finished file.
_EMBED_POSTPROCESSORS = ("Metadata", "EmbedThumbnail")


def _make_converted_recorder(codec):
    """Return (hook, record): a postprocessor hook and the dict it fills with "path", "title" and
    "source" (the SOURCE_KEYS of that event's info_dict).

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
                record["source"] = {key: info.get(key) for key in SOURCE_KEYS}
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
    """The recorder's record if only embedding failed and its file can be delivered, else None.

    download_audio still runs the integrity check and embed_warnings on it, as on the normal path.
    """
    path = converted.get("path")
    if kind == KIND_STORAGE or not path or not is_valid_audio(path, codec):
        return None
    print(
        f"sxdc: embedding failed, audio kept at {path} ({type(exc).__name__}: {exc})",
        file=sys.stderr,
    )
    return converted


def embed_warnings(path, codec, source):
    """The warning ids for a delivered file, from the file itself. Never raises.

    no_tags: no TITLE tag. no_cover: no JPEG or PNG picture, raised only when the source offered a
    thumbnail. short_audio: shorter than the duration yt-dlp reported, for a source that is not
    live. A file that cannot be read gets no warnings at all (fail open).
    """
    try:
        if codec != "flac":
            return []
        info = read_flac(path)
        if info is None:
            return []
        source = source if isinstance(source, dict) else {}
        warnings = []
        if not any(isinstance(v, str) and v.strip() for v in info.tags.get("title", [])):
            warnings.append(WARN_NO_TAGS)
        if _offered_thumbnail(source) and not any(
            p.head.startswith(_IMAGE_MAGIC) for p in info.pictures
        ):
            warnings.append(WARN_NO_COVER)
        if _shorter_than_source(info.duration, source):
            warnings.append(WARN_SHORT_AUDIO)
        return warnings
    except Exception:
        return []


def _offered_thumbnail(source):
    return bool(source.get("thumbnail") or source.get("thumbnails"))


def _shorter_than_source(actual, source):
    expected = source.get("duration")
    if isinstance(expected, bool) or not isinstance(expected, int | float) or expected <= 0:
        return False
    if source.get("is_live") is True or source.get("live_status") in _LIVE_STATUSES:
        return False
    if actual is None:
        return False
    return expected - actual > max(_SHORT_SECONDS, _SHORT_FRACTION * expected)
