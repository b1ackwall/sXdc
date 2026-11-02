"""The thumbnail guard (#47), with ffmpeg faked: it never runs in these tests."""

import json

import pytest
import sxdc_thumbnail as st
from sxdc_download import SxdcAborted, download_audio

FFMPEG_DIR = "/fake/ffmpeg-bin"

JPEG = b"\xff\xd8\xff\xe0\x00\x10JFIF" + b"j" * 64
PNG = b"\x89PNG\r\n\x1a\n" + b"p" * 64
WEBP = b"RIFF\x24\x00\x00\x00WEBPVP8 " + b"w" * 64
GIF = b"GIF89a" + b"g" * 64
HTML = b"<!DOCTYPE html><html><head><title>404 Not Found</title></head></html>\n"
CONVERTED = b"\xff\xd8\xff\xdbconverted"


class FakeFfmpeg:
    """Stands in for st._ffmpeg. Probes pass unless told otherwise; a conversion writes JPEG."""

    def __init__(self):
        self.calls = []
        self.probe_ok = True
        self.convert_ok = True
        self.convert_output = CONVERTED
        self.on_call = lambda args: None

    def __call__(self, ffmpeg_dir, args):
        self.calls.append((ffmpeg_dir, list(args)))
        self.on_call(args)
        if args[-3:] == ["-f", "null", "-"]:
            return self.probe_ok
        if self.convert_output is not None:
            with open(args[-1], "wb") as f:
                f.write(self.convert_output)
        return self.convert_ok

    @property
    def conversions(self):
        return [args for _, args in self.calls if "mjpeg" in args]


@pytest.fixture
def ffmpeg(monkeypatch):
    fake = FakeFfmpeg()
    monkeypatch.setattr(st, "_ffmpeg", fake)
    return fake


def thumb(tmp_path, data, name="Song [abc].jpg"):
    p = tmp_path / name
    p.write_bytes(data)
    return p


def info_with(path, final=None):
    return {
        "thumbnails": [{"url": "https://i.example.com/t.jpg", "filepath": str(path)}],
        "__files_to_move": {str(path): final or str(path)},
    }


# --- sniff --------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "head, kind",
    [
        (JPEG, "jpeg"),
        (PNG, "png"),
        (WEBP, "webp"),
        (GIF, "gif"),
        (b"GIF87a....", "gif"),
        (HTML, None),
        (b"", None),
        (b"\xff\xd8", None),
        (b"\x89PNG", None),
        (b"RIFF\x00\x00\x00\x00WAVE", None),
        (b"BM\x00\x00", None),
        (None, None),
        ("text", None),
    ],
    ids=[
        "jpeg",
        "png",
        "webp",
        "gif89",
        "gif87",
        "html",
        "empty",
        "short-jpeg",
        "short-png",
        "riff-wave",
        "bmp",
        "none",
        "str",
    ],
)
def test_sniff(head, kind):
    assert st.sniff(head) == kind


# --- kept, converted or dropped -----------------------------------------------------------


@pytest.mark.parametrize("data", [JPEG, PNG], ids=["jpeg", "png"])
def test_small_jpeg_or_png_that_decodes_is_kept_as_is(tmp_path, ffmpeg, data):
    p = thumb(tmp_path, data)
    info = info_with(p)

    st.guard_thumbnails(info, FFMPEG_DIR)

    assert info["thumbnails"][0]["filepath"] == str(p)
    assert p.read_bytes() == data
    assert ffmpeg.conversions == []
    assert ffmpeg.calls == [(FFMPEG_DIR, ["-v", "error", "-i", str(p), "-f", "null", "-"])]


@pytest.mark.parametrize("data", [HTML, b""], ids=["html", "empty"])
def test_bogus_thumbnail_is_deleted_and_forgotten(tmp_path, ffmpeg, data):
    p = thumb(tmp_path, data)
    info = info_with(p)

    st.guard_thumbnails(info, FFMPEG_DIR)

    assert not p.exists()
    assert "filepath" not in info["thumbnails"][0]
    assert info["thumbnails"][0]["url"]  # the source still offered one: no_cover follows
    assert info["__files_to_move"] == {}
    assert ffmpeg.calls == []  # rejected on the magic bytes alone


def test_image_that_does_not_decode_is_dropped(tmp_path, ffmpeg):
    ffmpeg.probe_ok = False  # e.g. a JPEG cut short: right magic, "overread" from ffmpeg
    p = thumb(tmp_path, JPEG[:12])
    info = info_with(p)

    st.guard_thumbnails(info, FFMPEG_DIR)

    assert not p.exists()
    assert "filepath" not in info["thumbnails"][0]


@pytest.mark.parametrize("data", [WEBP, GIF], ids=["webp", "gif"])
def test_webp_or_gif_named_jpg_is_converted_to_jpeg(tmp_path, ffmpeg, data):
    p = thumb(tmp_path, data)
    info = info_with(p, final="/final/Song [abc].jpg")

    st.guard_thumbnails(info, FFMPEG_DIR)

    converted = tmp_path / "Song [abc].cover.jpg"
    assert info["thumbnails"][0]["filepath"] == str(converted)
    assert converted.read_bytes() == CONVERTED
    assert not p.exists()
    assert info["__files_to_move"] == {str(converted): "/final/Song [abc].cover.jpg"}
    (args,) = ffmpeg.conversions
    assert args[:9] == ["-v", "error", "-y", "-i", str(p), "-frames:v", "1", "-q:v", "2"]
    assert args[9:] == ["-f", "image2", "-c:v", "mjpeg", str(converted)]


def test_image_over_the_size_cap_is_converted(tmp_path, ffmpeg):
    p = thumb(tmp_path, PNG + b"\0" * st.MAX_COVER_BYTES, name="big.png")
    info = info_with(p)

    st.guard_thumbnails(info, FFMPEG_DIR)

    assert info["thumbnails"][0]["filepath"] == str(tmp_path / "big.cover.jpg")
    assert len(ffmpeg.conversions) == 1


def test_image_at_the_size_cap_is_kept(tmp_path, ffmpeg):
    p = thumb(tmp_path, JPEG + b"\0" * (st.MAX_COVER_BYTES - len(JPEG)))
    st.guard_thumbnails(info_with(p), FFMPEG_DIR)
    assert ffmpeg.conversions == []


@pytest.mark.parametrize(
    "convert_ok, output",
    [(False, CONVERTED), (True, HTML), (True, None)],
    ids=["ffmpeg-fails", "not-a-jpeg", "no-output"],
)
def test_failed_conversion_drops_the_thumbnail_and_its_partial_output(
    tmp_path, ffmpeg, convert_ok, output
):
    ffmpeg.convert_ok, ffmpeg.convert_output = convert_ok, output
    p = thumb(tmp_path, WEBP)
    info = info_with(p)

    st.guard_thumbnails(info, FFMPEG_DIR)

    assert "filepath" not in info["thumbnails"][0]
    assert list(tmp_path.iterdir()) == []


def test_every_thumbnail_on_disk_is_checked_and_others_left_alone(tmp_path, ffmpeg):
    good, bad = thumb(tmp_path, JPEG, "a.jpg"), thumb(tmp_path, HTML, "b.jpg")
    info = {
        "thumbnails": [
            {"url": "u0"},
            {"url": "u1", "filepath": str(good)},
            {"url": "u2", "filepath": str(bad)},
        ]
    }

    st.guard_thumbnails(info, FFMPEG_DIR)

    assert info["thumbnails"] == [
        {"url": "u0"},
        {"url": "u1", "filepath": str(good)},
        {"url": "u2"},
    ]


# --- never raises -------------------------------------------------------------------------


@pytest.mark.parametrize(
    "info",
    [
        None,
        "text",
        [],
        {},
        {"thumbnails": None},
        {"thumbnails": "x"},
        {"thumbnails": [None, 3]},
        {"thumbnails": [{"filepath": 7}]},
        {"thumbnails": [{"filepath": "/no/such/file.jpg"}]},
        {"thumbnails": [{"filepath": "/no/such/file.jpg"}], "__files_to_move": "odd"},
    ],
)
def test_odd_info_never_raises(ffmpeg, info):
    st.guard_thumbnails(info, FFMPEG_DIR)


def test_missing_file_is_forgotten(ffmpeg):
    info = {"thumbnails": [{"filepath": "/no/such/file.jpg"}]}
    st.guard_thumbnails(info, FFMPEG_DIR)
    assert info["thumbnails"] == [{}]


def test_ffmpeg_that_raises_is_a_dropped_thumbnail(tmp_path, monkeypatch):
    def broken(*_args):
        raise RuntimeError("exec failed")

    monkeypatch.setattr(st, "_ffmpeg", broken)
    p = thumb(tmp_path, JPEG)
    info = info_with(p)

    st.guard_thumbnails(info, FFMPEG_DIR)

    assert "filepath" not in info["thumbnails"][0]
    assert not p.exists()


@pytest.mark.parametrize("ffmpeg_dir", [None, "", "/no/such/dir"])
def test_real_ffmpeg_helper_without_a_binary_is_a_failure(ffmpeg_dir):
    assert st._ffmpeg(ffmpeg_dir, ["-version"]) is False


# --- the postprocessor and the abort ------------------------------------------------------


def raise_when(flag):
    def check(*_args, **_kwargs):
        if flag["set"]:
            raise SxdcAborted("cancelled by the app")

    return check


def test_guard_run_returns_no_files_and_the_info(tmp_path, ffmpeg):
    p = thumb(tmp_path, HTML)
    info = info_with(p)
    guard = st.make_thumbnail_guard(FFMPEG_DIR, raise_when({"set": False}))

    assert guard.run(info) == ([], info)
    assert "filepath" not in info["thumbnails"][0]


def test_abort_at_the_start_stops_before_any_work(tmp_path, ffmpeg):
    p = thumb(tmp_path, JPEG)
    guard = st.make_thumbnail_guard(FFMPEG_DIR, raise_when({"set": True}))

    with pytest.raises(SxdcAborted):
        guard.run(info_with(p))
    assert ffmpeg.calls == []
    assert p.exists()


def test_abort_set_during_the_work_propagates_at_the_finish(tmp_path, ffmpeg):
    flag = {"set": False}
    ffmpeg.on_call = lambda args: flag.update(set=True)
    guard = st.make_thumbnail_guard(FFMPEG_DIR, raise_when(flag))

    with pytest.raises(SxdcAborted):
        guard.run(info_with(thumb(tmp_path, JPEG)))
    assert len(ffmpeg.calls) == 1


def test_without_yt_dlp_the_guard_still_has_set_downloader():
    guard = st.make_thumbnail_guard(FFMPEG_DIR, lambda: None)
    guard.set_downloader("ydl")
    assert guard._downloader == "ydl"


# --- installed by download_audio ----------------------------------------------------------


def test_download_audio_adds_one_guard_before_the_download(fake_ytdlp, tmp_path, ffmpeg):
    p = thumb(tmp_path, HTML)
    fake_ytdlp.info = info_with(p)

    download_audio("https://music.example.com/watch?v=abc", str(tmp_path / "out"), FFMPEG_DIR)

    ((pp, when),) = fake_ytdlp.instances[-1].post_processors
    assert when == "before_dl"
    assert type(pp).__name__ == "SxdcThumbnailGuardPP"
    assert not p.exists()  # it ran, on the info yt-dlp extracted


def test_a_guard_that_cannot_be_added_does_not_fail_the_download(fake_ytdlp, tmp_path, capsys):
    cls = fake_ytdlp.module.YoutubeDL

    def broken(self, pp, when="post_process"):
        raise TypeError("API changed")

    cls.add_post_processor = broken
    flac = tmp_path / "Song.flac"
    flac.write_bytes(b"x" * 10)
    fake_ytdlp.info = {"title": "Song", "requested_downloads": [{"filepath": str(flac)}]}

    raw = download_audio("https://music.example.com/watch?v=abc", str(tmp_path), FFMPEG_DIR)

    assert json.loads(raw)["ok"] is True
    assert "thumbnail guard not installed (TypeError: API changed)" in capsys.readouterr().err
