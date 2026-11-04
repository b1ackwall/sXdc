"""download_audio against the real yt-dlp, the real mutagen and a system ffmpeg, offline (#48).

Mirrors what the instrumented PythonDownloadBridgeTest and PythonDownloadBridgeEmbedTest assert
on the device, on the host. Every URL is on the local server from conftest.py.
"""

import os
import uuid

import pytest
import sxdc_download
import sxdc_flac
from helpers import JPEG_MAGIC, download, flac, images_in, listing

pytestmark = pytest.mark.real_ytdlp


def _cover_page(server, thumb):
    return server.url(f"/page/{thumb}/cover-{thumb}.html")


def test_local_wav_becomes_a_tagged_flac_named_title_and_id(server, ffmpeg_dir, out_dir):
    result = download(server.url("/sxdc-tone.wav"), out_dir, ffmpeg_dir)

    assert result["ok"], result
    path = result["path"]
    assert os.path.dirname(os.path.realpath(path)) == os.path.realpath(out_dir)
    assert os.path.basename(path) == "sxdc-tone [sxdc-tone].flac"
    assert result["title"] == "sxdc-tone"
    assert result["size"] == os.path.getsize(path)
    with open(path, "rb") as f:
        assert f.read(4) == b"fLaC"
    assert flac(path).get("title") == ["sxdc-tone"]
    assert listing(out_dir) == ["sxdc-tone [sxdc-tone].flac"]


def test_valid_thumbnail_is_embedded_with_no_warnings(server, ffmpeg_dir, out_dir):
    result = download(_cover_page(server, "ok"), out_dir, ffmpeg_dir)

    assert result["ok"], result
    assert result["warnings"] == []
    pictures = flac(result["path"]).pictures
    assert len(pictures) == 1
    assert pictures[0].mime == "image/jpeg"
    assert pictures[0].data[:3] == JPEG_MAGIC
    # The generic extractor numbers the page's media entries: "cover-ok (1)".
    assert flac(result["path"]).get("title") == [result["title"]]
    assert result["title"].startswith("cover-ok")
    assert images_in(out_dir) == []


def test_bogus_html_thumbnail_is_not_embedded_and_warns_no_cover(server, ffmpeg_dir, out_dir):
    result = download(_cover_page(server, "bogus"), out_dir, ffmpeg_dir)

    assert result["ok"], result
    assert result["warnings"] == ["no_cover"]
    assert flac(result["path"]).pictures == []
    assert images_in(out_dir) == []


def test_webp_named_jpg_is_embedded_as_a_jpeg(server, ffmpeg_dir, out_dir):
    result = download(_cover_page(server, "webp"), out_dir, ffmpeg_dir)

    assert result["ok"], result
    assert result["warnings"] == []
    pictures = flac(result["path"]).pictures
    assert len(pictures) == 1
    assert pictures[0].mime == "image/jpeg"
    assert pictures[0].data[:3] == JPEG_MAGIC
    assert images_in(out_dir) == []


def test_flac_from_the_encoder_passes_the_integrity_check_and_a_cut_copy_fails(
    server, ffmpeg_dir, out_dir, tmp_path
):
    result = download(server.url(f"/long/sxdc-long-{uuid.uuid4().hex}.wav"), out_dir, ffmpeg_dir)

    assert result["ok"], result
    assert result["warnings"] == []
    assert sxdc_flac.check_integrity(result["path"]) == sxdc_flac.INTEGRITY_OK
    data = open(result["path"], "rb").read()
    for name, size in (("mid", len(data) - 10), ("half", len(data) // 2)):
        cut = tmp_path / f"cut-{name}.flac"
        cut.write_bytes(data[:size])
        assert sxdc_flac.check_integrity(str(cut)) == sxdc_flac.INTEGRITY_TRUNCATED, name


# ---------------------------------------------------------------------------- failures

PAGE_KINDS = {
    404: sxdc_download.KIND_UNAVAILABLE,
    403: sxdc_download.KIND_UNAVAILABLE,
    429: sxdc_download.KIND_RATE_LIMITED,
    500: sxdc_download.KIND_NETWORK,
}

MEDIA_KINDS = {
    403: sxdc_download.KIND_EXTRACTOR,
    404: sxdc_download.KIND_UNAVAILABLE,
    429: sxdc_download.KIND_RATE_LIMITED,
    500: sxdc_download.KIND_NETWORK,
}


def _failed(url, out_dir, ffmpeg_dir):
    result = download(url, out_dir, ffmpeg_dir)
    assert not result["ok"], f"{url} succeeded"
    assert result["path"] is None
    assert result["error"]
    return result["kind"]


@pytest.mark.parametrize("status", sorted(PAGE_KINDS))
def test_http_error_on_the_page(server, ffmpeg_dir, out_dir, status):
    assert _failed(server.url(f"/status/{status}"), out_dir, ffmpeg_dir) == PAGE_KINDS[status]


@pytest.mark.parametrize("status", sorted(MEDIA_KINDS))
def test_http_error_on_the_media(server, ffmpeg_dir, out_dir, status):
    url = server.url(f"/media/{status}/{uuid.uuid4().hex}.wav")
    assert _failed(url, out_dir, ffmpeg_dir) == MEDIA_KINDS[status]


def test_connection_refused_is_network(server, ffmpeg_dir, out_dir):
    import socket

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    url = f"http://127.0.0.1:{port}/sxdc-tone.wav"
    assert _failed(url, out_dir, ffmpeg_dir) == sxdc_download.KIND_NETWORK


def test_truncated_body_is_network(server, ffmpeg_dir, out_dir):
    url = server.url(f"/truncated/{uuid.uuid4().hex}.wav")
    assert _failed(url, out_dir, ffmpeg_dir) == sxdc_download.KIND_NETWORK


# ---------------------------------------------------------------------------- logger (#77)


def test_a_normal_download_prints_nothing_but_prefixed_lines(server, ffmpeg_dir, out_dir, capfd):
    result = download(_cover_page(server, "ok"), out_dir, ffmpeg_dir)

    assert result["ok"], result
    captured = capfd.readouterr()
    assert captured.out == ""
    stray = [ln for ln in captured.err.splitlines() if not ln.startswith(("yt-dlp: ", "sxdc: "))]
    assert stray == []


def test_a_failed_download_reaches_stderr_through_the_logger(server, ffmpeg_dir, out_dir, capfd):
    download(server.url("/status/404"), out_dir, ffmpeg_dir)

    errors = [ln for ln in capfd.readouterr().err.splitlines() if ln.startswith("yt-dlp: ERROR")]
    assert len(errors) == 1
    assert len(errors[0]) <= 500 + len("yt-dlp: ")
