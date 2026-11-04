"""Fixtures for the opt-in real-yt-dlp suite (#48). See docs/python-tests.md.

These tests need the real yt-dlp and mutagen at the versions pinned in
app/requirements-android.txt and a system ffmpeg; scripts/real_ytdlp_tests.sh sets that up.
Nothing here touches the internet: every URL is on a server at 127.0.0.1.
"""

import os
import shutil
import subprocess
import sys

import pytest

sys.path.insert(0, os.path.dirname(__file__))

from http_fixture import LocalServer  # noqa: E402


def _ffmpeg(*args):
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", *args],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )


@pytest.fixture(scope="session")
def ffmpeg_dir(tmp_path_factory):
    """A directory holding just `ffmpeg` and `ffprobe`, like the app's bin directory."""
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        pytest.skip("ffmpeg and ffprobe are required on PATH")
    bin_dir = tmp_path_factory.mktemp("ffbin")
    os.symlink(ffmpeg, bin_dir / "ffmpeg")
    os.symlink(ffprobe, bin_dir / "ffprobe")
    return str(bin_dir)


@pytest.fixture(scope="session")
def server(tmp_path_factory, ffmpeg_dir):
    """The local HTTP server, with a 64x64 JPEG and a WebP of it made by ffmpeg."""
    work = tmp_path_factory.mktemp("images")
    source = ["-f", "lavfi", "-i", "gradients=s=64x64:n=2:seed=7", "-frames:v", "1"]
    _ffmpeg(*source, "-q:v", "3", str(work / "cover.jpg"))
    try:
        _ffmpeg(*source, "-c:v", "libwebp", str(work / "cover.webp"))
    except subprocess.CalledProcessError:
        pytest.skip("this ffmpeg cannot encode WebP")
    srv = LocalServer((work / "cover.jpg").read_bytes(), (work / "cover.webp").read_bytes()).start()
    yield srv
    srv.close()


@pytest.fixture
def out_dir(tmp_path):
    return str(tmp_path / "staging")
