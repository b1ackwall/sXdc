"""Read just enough of a FLAC file to check it, with the standard library only (#45, #46).

`read_flac` walks the metadata blocks: STREAMINFO, the Vorbis comment tags and the pictures.
`check_integrity` looks at the last 64 KB only: it finds the final frame and checks that it is
complete and ends at the sample count STREAMINFO announces.

Both fail open. A file that cannot be parsed, or whose structure is unexpected, is "unknown" and
passes: only a definite truncation is ever reported. A flipped byte in the middle of the audio
is out of scope; that needs a full decode.
"""

import os
from dataclasses import dataclass, field

INTEGRITY_OK = "ok"
INTEGRITY_TRUNCATED = "truncated"
INTEGRITY_UNKNOWN = "unknown"

TAIL_WINDOW = 64 * 1024

_MAGIC = b"fLaC"
_STREAMINFO, _VORBIS_COMMENT, _PICTURE = 0, 4, 6
_MAX_BLOCKS = 1024
_PICTURE_HEAD = 16

# Frame header codes (RFC 9639, section 9.1).
_BLOCK_SIZES = {1: 192, 2: 576, 3: 1152, 4: 2304, 5: 4608}
_SAMPLE_RATES = {
    1: 88200,
    2: 176400,
    3: 192000,
    4: 8000,
    5: 16000,
    6: 22050,
    7: 24000,
    8: 32000,
    9: 44100,
    10: 48000,
    11: 96000,
}
_SAMPLE_SIZES = {1: 8, 2: 12, 4: 16, 5: 20, 6: 24, 7: 32}


@dataclass
class Picture:
    type: int
    mime: str
    data_length: int
    # The first bytes of the image data: enough to tell a JPEG or PNG from anything else.
    head: bytes


@dataclass
class FlacInfo:
    min_blocksize: int
    max_blocksize: int
    max_framesize: int
    sample_rate: int
    channels: int
    bits_per_sample: int
    total_samples: int
    audio_offset: int
    size: int
    # Lower-cased field name -> values, in file order.
    tags: dict = field(default_factory=dict)
    pictures: list = field(default_factory=list)

    @property
    def duration(self):
        """Seconds of audio STREAMINFO announces, or None if it does not say."""
        if self.sample_rate <= 0 or self.total_samples <= 0:
            return None
        return self.total_samples / self.sample_rate


def read_flac(path):
    """The metadata of the FLAC file at `path`, or None if it is not one we can parse."""
    if not isinstance(path, str | os.PathLike) or not path:
        return None  # an int would open() a file descriptor
    try:
        with open(path, "rb") as f:
            return _read(f, os.fstat(f.fileno()).st_size)
    except Exception:
        return None


def check_integrity(path):
    """INTEGRITY_OK, INTEGRITY_TRUNCATED or INTEGRITY_UNKNOWN for the file at `path`.

    Truncated means one of: STREAMINFO announces no samples although audio follows; the final
    frame is incomplete; or the final frame is complete but does not end at STREAMINFO's total
    (a cut on a clean frame boundary). Never raises.
    """
    try:
        info = read_flac(path)
        if info is None:
            return INTEGRITY_UNKNOWN
        with open(path, "rb") as f:
            return _check_tail(f, info)
    except Exception:
        return INTEGRITY_UNKNOWN


# ------------------------------------------------------------------ metadata


def _read(f, size):
    if f.read(4) != _MAGIC:
        return None
    info = None
    last = False
    blocks = 0
    while not last:
        blocks += 1
        header = f.read(4)
        if len(header) != 4 or blocks > _MAX_BLOCKS:
            return None
        last = bool(header[0] & 0x80)
        kind = header[0] & 0x7F
        length = int.from_bytes(header[1:4], "big")
        start = f.tell()
        if start + length > size:
            return None
        if blocks == 1:
            if kind != _STREAMINFO or length != 34:
                return None
            info = _streaminfo(f.read(34), size)
            if info is None:
                return None
        elif kind == _VORBIS_COMMENT:
            _vorbis_comment(f.read(length), info.tags)
        elif kind == _PICTURE:
            picture = _picture(f, length)
            if picture is not None:
                info.pictures.append(picture)
        f.seek(start + length)
    info.audio_offset = f.tell()
    return info


def _streaminfo(data, size):
    bits = int.from_bytes(data[10:18], "big")
    info = FlacInfo(
        min_blocksize=int.from_bytes(data[0:2], "big"),
        max_blocksize=int.from_bytes(data[2:4], "big"),
        max_framesize=int.from_bytes(data[7:10], "big"),
        sample_rate=bits >> 44,
        channels=((bits >> 41) & 0x7) + 1,
        bits_per_sample=((bits >> 36) & 0x1F) + 1,
        total_samples=bits & 0xFFFFFFFFF,
        audio_offset=0,
        size=size,
    )
    if info.max_blocksize < 16 or info.sample_rate <= 0:
        return None
    return info


def _vorbis_comment(data, tags):
    """Little-endian lengths, unlike the rest of FLAC. A damaged block keeps what parsed."""
    vendor = int.from_bytes(data[0:4], "little")
    pos = 4 + vendor
    count = int.from_bytes(data[pos : pos + 4], "little")
    pos += 4
    for _ in range(count):
        n = int.from_bytes(data[pos : pos + 4], "little")
        pos += 4
        if n <= 0 or pos + n > len(data):
            return
        entry = data[pos : pos + n].decode("utf-8", "replace")
        pos += n
        key, sep, value = entry.partition("=")
        if sep:
            tags.setdefault(key.lower(), []).append(value)


def _picture(f, length):
    end = f.tell() + length
    fixed = f.read(8)
    if len(fixed) != 8:
        return None
    kind = int.from_bytes(fixed[0:4], "big")
    mime_len = int.from_bytes(fixed[4:8], "big")
    if f.tell() + mime_len > end:
        return None
    mime = f.read(mime_len).decode("ascii", "replace")
    desc_len = int.from_bytes(f.read(4), "big")
    if f.tell() + desc_len + 20 > end:
        return None
    f.seek(desc_len + 16, os.SEEK_CUR)  # description, width, height, depth, colours
    data_len = int.from_bytes(f.read(4), "big")
    if f.tell() + data_len > end:
        return None
    return Picture(kind, mime, data_len, f.read(min(data_len, _PICTURE_HEAD)))


# ------------------------------------------------------------------ the final frame


def _check_tail(f, info):
    audio = info.size - info.audio_offset
    if audio <= 0:
        return INTEGRITY_UNKNOWN
    if info.total_samples == 0:
        # What an encoder that was killed leaves: it fills the count in only when it finishes.
        return INTEGRITY_TRUNCATED
    if info.size >= 128 + info.audio_offset:
        f.seek(info.size - 128)
        if f.read(3) == b"TAG":
            return INTEGRITY_UNKNOWN  # an ID3v1 trailer: not something sXdc writes
    start = max(info.audio_offset, info.size - TAIL_WINDOW)
    f.seek(start)
    buf = f.read()
    headers = [h for h in (_header(buf, p, info) for p in _sync_positions(buf)) if h]

    # Nearest the end first: a later "header" can be a false sync inside the real last frame.
    for pos, first, blocksize in reversed(headers):
        if _crc16(buf, pos, len(buf)) == 0:
            ends_at_total = first + blocksize == info.total_samples
            return INTEGRITY_OK if ends_at_total else INTEGRITY_TRUNCATED

    # No complete frame ends the file. That is a truncation only if real frames are there.
    for (pos, _, _), (nxt, _, _) in zip(headers, headers[1:], strict=False):
        if _crc16(buf, pos, nxt) == 0:
            return INTEGRITY_TRUNCATED
    if info.max_framesize and len(buf) >= 2 * info.max_framesize:
        # Any stretch that long holds a whole frame of a valid stream.
        return INTEGRITY_TRUNCATED
    return INTEGRITY_UNKNOWN


def _sync_positions(buf):
    pos = buf.find(b"\xff")
    while pos != -1:
        if pos + 1 < len(buf) and buf[pos + 1] & 0xFE == 0xF8:
            yield pos
        pos = buf.find(b"\xff", pos + 1)


def _header(buf, pos, info):
    """(pos, first sample, block size) for a frame header at `pos` that fits the stream, or None."""
    if pos + 6 > len(buf):
        return None
    variable = buf[pos + 1] & 1
    bs_code, sr_code = buf[pos + 2] >> 4, buf[pos + 2] & 0x0F
    ch_code, ss_code = buf[pos + 3] >> 4, (buf[pos + 3] >> 1) & 0x7
    if bs_code == 0 or sr_code == 0x0F or ch_code > 10 or ss_code == 3 or buf[pos + 3] & 1:
        return None
    if ch_code <= 7 and ch_code + 1 != info.channels or ch_code > 7 and info.channels != 2:
        return None
    if ss_code and _SAMPLE_SIZES[ss_code] != info.bits_per_sample:
        return None
    if sr_code in _SAMPLE_RATES and _SAMPLE_RATES[sr_code] != info.sample_rate:
        return None
    number, q = _utf8(buf, pos + 4)
    if number is None:
        return None
    if bs_code == 6:
        blocksize, q = buf[q] + 1, q + 1
    elif bs_code == 7:
        blocksize, q = int.from_bytes(buf[q : q + 2], "big") + 1, q + 2
    elif bs_code in _BLOCK_SIZES:
        blocksize = _BLOCK_SIZES[bs_code]
    else:
        blocksize = 256 << (bs_code - 8)
    q += {12: 1, 13: 2, 14: 2}.get(sr_code, 0)
    if q >= len(buf) or blocksize > info.max_blocksize or _crc8(buf, pos, q) != buf[q]:
        return None
    first = number if variable else number * info.max_blocksize
    return pos, first, blocksize


def _utf8(buf, pos):
    """FLAC's UTF-8-style coded number at `pos`: (value, next position), or (None, pos)."""
    if pos >= len(buf):
        return None, pos
    lead = buf[pos]
    if lead < 0x80:
        return lead, pos + 1
    ones = 0
    while ones < 8 and lead & (0x80 >> ones):
        ones += 1
    if not 2 <= ones <= 7:
        return None, pos
    count = ones - 1  # continuation bytes
    value = lead & (0xFF >> (ones + 1))
    if pos + count >= len(buf):
        return None, pos
    for b in buf[pos + 1 : pos + 1 + count]:
        if b & 0xC0 != 0x80:
            return None, pos
        value = value << 6 | b & 0x3F
    return value, pos + 1 + count


def _crc8(buf, start, end):
    crc = 0
    for b in buf[start:end]:
        crc = _CRC8[crc ^ b]
    return crc


def _crc16(buf, start, end):
    """CRC-16 (0x8005) of buf[start:end]; 0 when the range ends with its own correct CRC."""
    crc = 0
    table = _CRC16
    for b in buf[start:end]:
        crc = (crc << 8 & 0xFFFF) ^ table[crc >> 8 ^ b]
    return crc


def _table(width, poly):
    top, mask = 1 << (width - 1), (1 << width) - 1
    rows = []
    for i in range(256):
        c = i << (width - 8)
        for _ in range(8):
            c = (c << 1 ^ poly) & mask if c & top else c << 1 & mask
        rows.append(c)
    return rows


_CRC8 = _table(8, 0x07)
_CRC16 = _table(16, 0x8005)
