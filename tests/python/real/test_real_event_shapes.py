"""The yt-dlp event payloads sxdc relies on, as the real yt-dlp sends them (#48).

The embed fallback (#45) trusts the postprocessor names in sxdc_embed and the keys of each
hook event. With a fake YoutubeDL a rename would pass every test and silently disable it.
"""

import pytest
import sxdc_download
import sxdc_embed

pytestmark = pytest.mark.real_ytdlp


@pytest.fixture
def events(server, ffmpeg_dir, tmp_path):
    import yt_dlp

    progress, post = [], []
    opts = sxdc_download.build_ydl_opts(ffmpeg_dir, outtmpl=sxdc_download.OUTPUT_TEMPLATE)
    opts.update(
        {
            "paths": {"home": str(tmp_path), "temp": str(tmp_path)},
            "progress_hooks": [progress.append],
            "postprocessor_hooks": [post.append],
            "postprocessors": [
                {"key": "FFmpegExtractAudio", "preferredcodec": "flac", "preferredquality": "0"},
                {"key": "FFmpegMetadata"},
                {"key": "EmbedThumbnail", "already_have_thumbnail": False},
            ],
            "writethumbnail": True,
            "format": "bestaudio/best",
        }
    )
    with yt_dlp.YoutubeDL(opts) as ydl:
        ydl.extract_info(server.url("/page/ok/cover-ok.html"), download=True)
    return progress, post


def test_progress_events_have_status_and_finish(events):
    progress, _ = events
    assert progress
    assert all(isinstance(e.get("status"), str) for e in progress)
    assert progress[-1]["status"] == "finished"


def test_postprocessor_events_carry_status_name_and_filepath(events):
    _, post = events
    assert post
    for event in post:
        assert event["status"] in ("started", "processing", "finished")
        assert isinstance(event["postprocessor"], str)
        assert isinstance(event["info_dict"], dict)


def test_every_embed_postprocessor_name_is_one_yt_dlp_really_reports(events):
    _, post = events
    started = {e["postprocessor"] for e in post if e["status"] == "started"}
    assert set(sxdc_embed._EMBED_POSTPROCESSORS) <= started, sorted(started)
    assert "ExtractAudio" in started


def test_the_recorder_sees_the_converted_flac_from_real_events(events):
    _, post = events
    hook, record = sxdc_embed._make_converted_recorder("flac")
    for event in post:
        hook(event)
    assert record["path"].endswith(".flac")
    assert record["source"]["thumbnail"] or record["source"]["thumbnails"]
