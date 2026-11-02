"""sxdc_flac: the metadata walker and the tail-frame integrity check (#46).

The defect rows are the ones measured on real ffmpeg output during discovery, rebuilt here on
the stdlib FLAC builder. The check fails open: anything it cannot read is "unknown" and passes.
"""

import pytest
from flac_builder import build_flac, write_flac
from sxdc_flac import (
    INTEGRITY_OK,
    INTEGRITY_TRUNCATED,
    INTEGRITY_UNKNOWN,
    TAIL_WINDOW,
    check_integrity,
    read_flac,
)

JPEG = b"\xff\xd8\xff\xe0" + b"j" * 40
PNG = b"\x89PNG\r\n\x1a\n" + b"p" * 40

STREAMS = {
    "fixed": {},
    "variable": {"variable": True},
}


def check(tmp_path, data, name="t.flac"):
    p = tmp_path / name
    p.write_bytes(data)
    return check_integrity(str(p))


def flip(data, at, count=1):
    b = bytearray(data)
    for i in range(at, at + count):
        b[i] ^= 0x5A
    return bytes(b)


@pytest.fixture(scope="module", params=sorted(STREAMS), ids=sorted(STREAMS))
def stream(request):
    built = build_flac(frames=40, **STREAMS[request.param])
    # Long enough that the 64 KB window starts in the middle of the audio, not at its start.
    assert len(built.data) - built.audio_offset > TAIL_WINDOW
    return built


# --- the discovery matrix -----------------------------------------------------------------


def test_intact_stream_is_ok(tmp_path, stream):
    assert check(tmp_path, stream.data) == INTEGRITY_OK


def test_cut_at_a_clean_frame_boundary_is_caught_by_the_end_sample(tmp_path, stream):
    # The last frame left is complete, but it ends before STREAMINFO's total.
    middle = stream.frame_offsets[len(stream.frame_offsets) // 2]
    assert check(tmp_path, stream.cut(middle)) == INTEGRITY_TRUNCATED


def test_cut_mid_frame_at_half_is_truncated(tmp_path, stream):
    middle = stream.frame_offsets[len(stream.frame_offsets) // 2]
    assert check(tmp_path, stream.cut(middle + 1234)) == INTEGRITY_TRUNCATED


def test_cut_at_99_5_percent_is_truncated(tmp_path, stream):
    assert check(tmp_path, stream.cut(int(len(stream.data) * 0.995))) == INTEGRITY_TRUNCATED


def test_last_10_bytes_cut_is_truncated(tmp_path, stream):
    assert check(tmp_path, stream.data[:-10]) == INTEGRITY_TRUNCATED


def test_second_half_zeroed_is_truncated(tmp_path, stream):
    half = len(stream.data) // 2
    data = stream.data[:half] + bytes(len(stream.data) - half)
    assert check(tmp_path, data) == INTEGRITY_TRUNCATED


def test_total_samples_zero_with_audio_is_truncated(tmp_path):
    # What a killed encoder leaves: it writes the count only when it finishes.
    assert check(tmp_path, build_flac(total_samples=0).data) == INTEGRITY_TRUNCATED


def test_one_flipped_byte_mid_file_passes(tmp_path, stream):
    # Out of scope: only a full decode sees it.
    assert check(tmp_path, flip(stream.data, len(stream.data) // 2)) == INTEGRITY_OK


def test_wrong_total_in_streaminfo_is_truncated(tmp_path):
    built = build_flac()
    assert check(tmp_path, build_flac(total_samples=built.total_samples + 1).data) == (
        INTEGRITY_TRUNCATED
    )


def test_last_frame_damaged_is_truncated(tmp_path, stream):
    assert check(tmp_path, flip(stream.data, len(stream.data) - 20)) == INTEGRITY_TRUNCATED


# --- small files: the window holds the whole audio part -----------------------------------


@pytest.mark.parametrize("variable", [False, True], ids=["fixed", "variable"])
def test_small_stream_ok_and_cut(tmp_path, variable):
    built = build_flac(frames=4, variable=variable)
    assert len(built.data) < TAIL_WINDOW
    assert check(tmp_path, built.data) == INTEGRITY_OK
    assert check(tmp_path, built.data[:-3]) == INTEGRITY_TRUNCATED
    assert check(tmp_path, built.cut(built.frame_offsets[-1])) == INTEGRITY_TRUNCATED


def test_single_frame_stream_is_ok(tmp_path):
    assert check(tmp_path, build_flac(frames=1).data) == INTEGRITY_OK


@pytest.mark.parametrize(
    "kwargs",
    [
        {"bits": 24, "channels": 1},
        {"bits": 8, "variable": True},
        {"channels": 6, "frames": 8},
        {"blocksize": 4096, "frames": 20, "sample_rate": 44100},
    ],
    ids=["24-bit-mono", "8-bit-variable", "6-channels", "44k1-4096"],
)
def test_other_stream_shapes_are_ok(tmp_path, kwargs):
    built = build_flac(**kwargs)
    assert check(tmp_path, built.data) == INTEGRITY_OK
    assert check(tmp_path, built.data[:-5]) == INTEGRITY_TRUNCATED


# --- fail open ----------------------------------------------------------------------------


@pytest.mark.parametrize(
    "data",
    [
        b"",
        b"garbage" * 1000,
        b"fLaC",
        b"fLaC" + b"\0" * 200,
        b"ID3\x04" + b"\0" * 300,
        b"OggS" + b"\0" * 100,
    ],
    ids=["empty", "garbage", "magic-only", "bad-streaminfo", "id3-prefix", "ogg"],
)
def test_unparsable_files_are_unknown(tmp_path, data):
    assert check(tmp_path, data) == INTEGRITY_UNKNOWN


def test_header_only_with_no_audio_is_unknown(tmp_path):
    built = build_flac()
    assert check(tmp_path, built.data[: built.audio_offset]) == INTEGRITY_UNKNOWN


def test_total_zero_without_audio_is_unknown(tmp_path):
    built = build_flac(total_samples=0)
    assert check(tmp_path, built.data[: built.audio_offset]) == INTEGRITY_UNKNOWN


def test_metadata_block_longer_than_the_file_is_unknown(tmp_path):
    built = build_flac()
    assert check(tmp_path, built.data[: built.audio_offset - 10]) == INTEGRITY_UNKNOWN


def test_id3v1_trailer_is_unknown(tmp_path):
    data = build_flac().data + b"TAG" + bytes(125)
    assert check(tmp_path, data) == INTEGRITY_UNKNOWN


def test_audio_that_is_not_flac_frames_is_unknown(tmp_path):
    # Valid metadata, then bytes with no frame at all and no frame size to reason from.
    built = build_flac(frames=2)
    data = bytearray(built.data[: built.audio_offset] + b"\x11" * 100)
    data[4 + 4 + 7 : 4 + 4 + 10] = bytes(3)  # max_framesize unknown
    assert check(tmp_path, bytes(data)) == INTEGRITY_UNKNOWN


@pytest.mark.parametrize("path", [None, "", "/no/such/file.flac", 42])
def test_bad_paths_are_unknown_and_never_raise(path):
    assert check_integrity(path) == INTEGRITY_UNKNOWN


def test_directory_is_unknown(tmp_path):
    assert check_integrity(str(tmp_path)) == INTEGRITY_UNKNOWN


# --- the metadata walker ------------------------------------------------------------------


def test_read_flac_returns_streaminfo_tags_and_pictures(tmp_path):
    p = tmp_path / "a.flac"
    built = write_flac(
        p,
        sample_rate=8000,
        channels=2,
        bits=16,
        tags={"ARTIST": "Someone", "artist": "Else"},
        pictures=[("image/jpeg", JPEG), ("image/png", PNG)],
    )

    info = read_flac(str(p))

    assert (info.sample_rate, info.channels, info.bits_per_sample) == (8000, 2, 16)
    assert info.total_samples == built.total_samples
    assert info.duration == pytest.approx(built.total_samples / 8000)
    assert info.audio_offset == built.audio_offset
    assert info.size == len(built.data)
    assert info.tags["title"] == ["Song"]
    assert info.tags["artist"] == ["Someone", "Else"]
    assert [(pic.type, pic.mime, pic.data_length) for pic in info.pictures] == [
        (3, "image/jpeg", len(JPEG)),
        (3, "image/png", len(PNG)),
    ]
    assert info.pictures[0].head == JPEG[:16]


def test_read_flac_without_tags_or_pictures(tmp_path):
    p = tmp_path / "a.flac"
    write_flac(p, title=None)
    info = read_flac(str(p))
    assert info.tags == {}
    assert info.pictures == []


def test_duration_is_none_without_a_total(tmp_path):
    p = tmp_path / "a.flac"
    write_flac(p, total_samples=0)
    assert read_flac(str(p)).duration is None


@pytest.mark.parametrize("data", [b"", b"nope", b"fLaC\x00\x00\x00"])
def test_read_flac_of_a_non_flac_is_none(tmp_path, data):
    p = tmp_path / "a.flac"
    p.write_bytes(data)
    assert read_flac(str(p)) is None


def test_damaged_picture_block_is_skipped(tmp_path):
    built = build_flac(pictures=[("image/jpeg", JPEG)])
    data = bytearray(built.data)
    at = data.find(b"image/jpeg")
    data[at - 4 : at] = (10_000).to_bytes(4, "big")  # mime length past the block
    p = tmp_path / "a.flac"
    p.write_bytes(bytes(data))
    info = read_flac(str(p))
    assert info.pictures == []
    assert info.tags["title"] == ["Song"]
