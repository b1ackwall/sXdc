"""Duplicate-detection keys for shared URLs.

Pure string work: no network, and yt_dlp is never imported here. Two URLs get the same key when
they name the same YouTube video, whatever the link shape (watch, youtu.be, shorts, music.,
tracking parameters, case, trailing slash). Everything else is keyed by a normalized URL.

The stored URL of a job is never changed; QueueProcessor compares keys computed on demand.
"""

import json
import re
from urllib.parse import parse_qs, urlsplit

_VIDEO_ID = re.compile(r"[0-9A-Za-z_-]{11}")

_YOUTUBE_HOSTS = frozenset({
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtube-nocookie.com",
    "www.youtube-nocookie.com",
})
_SHORT_HOST = "youtu.be"

# Path forms that carry the id as the next segment: /shorts/ID, /embed/ID, /live/ID, /v/ID.
_ID_PREFIXES = frozenset({"shorts", "embed", "live", "v"})


def _normalize(text):
    """The URL with lowercase scheme and host, no fragment and no trailing slash on the path.

    The query is kept. Text that is not an absolute URL comes back stripped and otherwise as is.
    """
    text = text.strip()
    parts = urlsplit(text)
    if not parts.scheme or not parts.netloc:
        return text
    userinfo, at, hostport = parts.netloc.rpartition("@")
    netloc = userinfo + at + hostport.lower()
    path = parts.path.rstrip("/") or "/"
    query = "?" + parts.query if parts.query else ""
    return f"{parts.scheme}://{netloc}{path}{query}"


def _valid_id(candidate):
    return candidate if candidate and _VIDEO_ID.fullmatch(candidate) else None


def _youtube_id(text):
    """The 11-character video id a YouTube link names, or None."""
    parts = urlsplit(text.strip())
    if parts.scheme not in ("http", "https"):
        return None
    host = parts.hostname
    segments = [s for s in parts.path.split("/") if s]
    if host == _SHORT_HOST:
        return _valid_id(segments[0]) if segments else None
    if host not in _YOUTUBE_HOSTS or not segments:
        return None
    if segments[0] == "watch":
        values = parse_qs(parts.query).get("v")
        # Some apps append a "/" to the whole link, which lands on the end of the v value.
        return _valid_id(values[0].rstrip("/")) if values else None
    if segments[0] in _ID_PREFIXES and len(segments) > 1:
        return _valid_id(segments[1])
    return None


def _as_text(value):
    if isinstance(value, str):
        return value
    try:
        return "" if value is None else str(value)
    except Exception:
        return ""


def dedup_key(url):
    """A string that is equal for two links to the same video. Never raises.

    "youtube:<id>" for a YouTube video link, "url:<normalized url>" for anything else, including
    YouTube links without a video id (playlist-only, channel). A value that is not a string, or
    that makes the parsing fail, is returned as its own text, which is the old exact-match rule.
    """
    text = _as_text(url)
    try:
        if not isinstance(url, str):
            return text
        video_id = _youtube_id(text)
        if video_id:
            return "youtube:" + video_id
        return "url:" + _normalize(text)
    except Exception:
        return text


def dedup_keys(urls):
    """dedup_key for each entry: same length and order. Never raises."""
    try:
        items = list(urls)
    except Exception:
        return []
    return [dedup_key(item) for item in items]


def dedup_keys_json(urls_json):
    """The Chaquopy entry point: a JSON list of URLs in, a JSON list of keys out."""
    return json.dumps(dedup_keys(json.loads(urls_json)))
