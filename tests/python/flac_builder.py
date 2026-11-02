"""Small, valid FLAC files for the tests, built with the standard library only.

Every subframe is VERBATIM, so no encoder is needed: the samples are written as they are. The
frame header always stores the block size explicitly (block size code 7) and takes the sample
rate and bit depth from STREAMINFO. Fixed-blocksize streams number their frames; variable ones
store each frame's first sample, as the format requires.

The CRCs are derived bit by bit here, independently of the ones in sxdc_flac.
"""

from dataclasses import dataclass


@dataclass
class Built:
    data: bytes
    # Byte offset of every frame, in order, and the offset where the audio part starts.
    frame_offsets: list
    audio_offset: int
    total_samples: int
    sample_rate: int

    def cut(self, at):
        return self.data[:at]


def crc8(data):
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def _crc16_bitwise(crc):
    for _ in range(8):
        crc = ((crc << 1) ^ 0x8005) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


# Byte-at-a-time lookup built from the bit-by-bit definition above: the frames are large.
_CRC16 = [_crc16_bitwise(i << 8) for i in range(256)]


def crc16(data):
    crc = 0
    for byte in data:
        crc = ((crc << 8) & 0xFFFF) ^ _CRC16[(crc >> 8) ^ byte]
    return crc


def coded_number(n):
    """FLAC's UTF-8-like encoding of a frame or sample number (up to 36 bits)."""
    if n < 0x80:
        return bytes([n])
    for count in range(1, 7):  # continuation bytes
        if n < 1 << (6 * count + 6 - count):
            break
    lead_bits = 6 - count
    out = []
    for _ in range(count):
        out.append(0x80 | (n & 0x3F))
        n >>= 6
    lead = (0xFF << (7 - count)) & 0xFF | (n & ((1 << lead_bits) - 1))
    return bytes([lead] + out[::-1])


def build_flac(
    frames=30,
    blocksize=1152,
    sample_rate=8000,
    channels=2,
    bits=16,
    variable=False,
    title="Song",
    tags=None,
    pictures=(),
    total_samples=None,
    last_block=None,
):
    """A FLAC stream of `frames` frames; the last one has `last_block` samples (default: half).

    variable=True alternates between `blocksize` and half of it. `total_samples` overrides what
    STREAMINFO announces; `pictures` is a list of (mime, data).
    """
    assert bits % 8 == 0
    sizes = [
        (blocksize if i % 2 == 0 else blocksize // 2) if variable else blocksize
        for i in range(frames)
    ]
    sizes[-1] = last_block or max(1, sizes[-1] // 2)

    audio = bytearray()
    offsets, first = [], 0
    for index, size in enumerate(sizes):
        offsets.append(len(audio))
        audio += _frame(index, first, size, channels, bits, variable)
        first += size
    frame_sizes = [b - a for a, b in zip(offsets, offsets[1:] + [len(audio)], strict=True)]

    blocks = [
        _streaminfo(
            min_block=min(sizes[:-1] or sizes),
            max_block=max(sizes),
            min_frame=min(frame_sizes),
            max_frame=max(frame_sizes),
            sample_rate=sample_rate,
            channels=channels,
            bits=bits,
            total=first if total_samples is None else total_samples,
        )
    ]
    comments = dict(tags or {})
    if title is not None:
        comments.setdefault("TITLE", title)
    blocks.append((4, _vorbis_comment(comments)))
    blocks.extend((6, _picture(mime, data)) for mime, data in pictures)

    head = bytearray(b"fLaC")
    for i, (kind, body) in enumerate(blocks):
        last = 0x80 if i == len(blocks) - 1 else 0
        head += bytes([last | kind]) + len(body).to_bytes(3, "big") + body
    audio_offset = len(head)
    return Built(
        data=bytes(head + audio),
        frame_offsets=[audio_offset + o for o in offsets],
        audio_offset=audio_offset,
        total_samples=first,
        sample_rate=sample_rate,
    )


def write_flac(path, **kwargs):
    built = build_flac(**kwargs)
    path.write_bytes(built.data)
    return built


def _sample(index, channel, bits):
    """Deterministic, varied sample values, so the audio bytes are not all alike."""
    span = 1 << bits
    return (index * 7919 + channel * 104729) % span - span // 2


def _frame(index, first, size, channels, bits, variable):
    header = bytearray([0xFF, 0xF8 | (1 if variable else 0), 0x70])  # block size code 7
    size_code = {8: 1, 16: 4, 24: 6, 32: 7}[bits]
    header.append((channels - 1) << 4 | size_code << 1)
    header += coded_number(first if variable else index)
    header += (size - 1).to_bytes(2, "big")
    header.append(crc8(header))
    body = bytearray()
    width = bits // 8
    for channel in range(channels):
        body.append(0x02)  # zero bit, VERBATIM (000001), no wasted bits
        for n in range(size):
            body += _sample(first + n, channel, bits).to_bytes(width, "big", signed=True)
    frame = header + body
    return bytes(frame + crc16(frame).to_bytes(2, "big"))


def _streaminfo(min_block, max_block, min_frame, max_frame, sample_rate, channels, bits, total):
    packed = sample_rate << 44 | (channels - 1) << 41 | (bits - 1) << 36 | total
    return 0, (
        min_block.to_bytes(2, "big")
        + max_block.to_bytes(2, "big")
        + min_frame.to_bytes(3, "big")
        + max_frame.to_bytes(3, "big")
        + packed.to_bytes(8, "big")
        + bytes(16)
    )


def _vorbis_comment(comments):
    vendor = b"sxdc-test"
    out = len(vendor).to_bytes(4, "little") + vendor + len(comments).to_bytes(4, "little")
    for key, value in comments.items():
        entry = f"{key}={value}".encode()
        out += len(entry).to_bytes(4, "little") + entry
    return out


def _picture(mime, data):
    mime_bytes = mime.encode("ascii")
    return (
        (3).to_bytes(4, "big")
        + len(mime_bytes).to_bytes(4, "big")
        + mime_bytes
        + (0).to_bytes(4, "big")  # empty description
        + (64).to_bytes(4, "big")
        + (64).to_bytes(4, "big")
        + (24).to_bytes(4, "big")
        + (0).to_bytes(4, "big")
        + len(data).to_bytes(4, "big")
        + data
    )
