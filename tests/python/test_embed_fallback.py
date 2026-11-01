"""download_audio keeps the converted audio when only metadata or thumbnail embedding fails.

The real hook function runs end to end: the fake yt-dlp feeds it the postprocessor events from
`fake_ytdlp.pp_events` before raising, the way yt-dlp 2026.10.26 does. In that version the
converted path first appears in the "started" event of Metadata and EmbedThumbnail.
"""

import json
import sys
import types

import pytest
import sxdc_download as sd
from sxdc_download import download_audio, is_valid_audio

URL = "https://music.example.com/watch?v=abc"
FFMPEG_DIR = "/fake/ffmpeg-bin"
KEYS = {"ok", "path", "title", "size", "kind", "error", "trace"}

THUMB_ERROR = "ERROR: Postprocessing: Conversion failed!"


@pytest.fixture
def out_dir(tmp_path):
    d = tmp_path / "staging"
    d.mkdir()
    return d


@pytest.fixture(autouse=True)
def no_mutagen(monkeypatch):
    """By default mutagen is not importable, so results never depend on the host venv."""
    monkeypatch.setitem(sys.modules, "mutagen", None)
    monkeypatch.setitem(sys.modules, "mutagen.flac", None)


def fake_mutagen(monkeypatch, length=1.5, raises=None):
    """Install a fake mutagen.flac whose FLAC(path) raises `raises` or reports `length`."""

    class FLAC:
        def __init__(self, path):
            if raises is not None:
                raise raises
            self.info = types.SimpleNamespace(length=length)

    package = types.ModuleType("mutagen")
    flac = types.ModuleType("mutagen.flac")
    flac.FLAC = FLAC
    package.flac = flac
    monkeypatch.setitem(sys.modules, "mutagen", package)
    monkeypatch.setitem(sys.modules, "mutagen.flac", flac)


def make_flac(directory, name="Song.flac", body=b"\x00" * 200):
    p = directory / name
    p.write_bytes(b"fLaC" + body)
    return p


def extract_audio_events(fake, src):
    fake.add_pp_event("ExtractAudio", "started", str(src))
    fake.add_pp_event("ExtractAudio", "finished", str(src))


def call(out_dir, codec=None):
    args = (URL, str(out_dir), FFMPEG_DIR) + (() if codec is None else (codec,))
    raw = download_audio(*args)
    assert isinstance(raw, str)
    return json.loads(raw)


# --- delivered ----------------------------------------------------------------------------

@pytest.mark.parametrize("failing_step", ["Metadata", "EmbedThumbnail"])
def test_embed_failure_after_started_event_delivers_the_flac(fake_ytdlp, out_dir, failing_step):
    flac = make_flac(out_dir)
    extract_audio_events(fake_ytdlp, out_dir / "Song.webm")
    if failing_step == "EmbedThumbnail":
        fake_ytdlp.add_pp_event("Metadata", "started", str(flac), "My Song")
        fake_ytdlp.add_pp_event("Metadata", "finished", str(flac), "My Song")
    fake_ytdlp.add_pp_event(failing_step, "started", str(flac), "My Song")
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    result = call(out_dir)

    assert set(result) == KEYS
    assert result == {
        "ok": True, "path": str(flac), "title": "My Song", "size": flac.stat().st_size,
        "kind": None, "error": None, "trace": None,
    }


def test_embed_failure_delivers_a_file_whose_name_has_spaces_and_brackets(fake_ytdlp, out_dir):
    flac = make_flac(out_dir, "Song [abc123_-XYZ].flac")
    extract_audio_events(fake_ytdlp, out_dir / "Song [abc123_-XYZ].webm")
    fake_ytdlp.add_pp_event("EmbedThumbnail", "started", str(flac), "Song")
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    result = call(out_dir)

    assert result["ok"] is True
    assert result["path"] == str(flac)
    assert result["size"] == flac.stat().st_size


def test_duplicate_events_keep_the_last_valid_values(fake_ytdlp, out_dir):
    flac = make_flac(out_dir)
    fake_ytdlp.add_pp_event("Metadata", "started", str(flac), "First")
    fake_ytdlp.add_pp_event("EmbedThumbnail", "started", str(out_dir / "x.webm"), "Ignored")
    fake_ytdlp.add_pp_event("EmbedThumbnail", "started", str(flac), "Last")
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    assert call(out_dir)["title"] == "Last"


def test_raw_mutagen_style_exception_is_delivered(fake_ytdlp, out_dir):
    class FLACNoHeaderError(Exception):  # not a yt-dlp error, like mutagen's own
        pass

    flac = make_flac(out_dir)
    fake_ytdlp.add_pp_event("EmbedThumbnail", "started", str(flac))
    fake_ytdlp.raises = FLACNoHeaderError("not a valid FLAC file")

    result = call(out_dir)

    assert result["ok"] is True
    assert result["path"] == str(flac)


def test_stderr_line_names_the_exception(fake_ytdlp, out_dir, capsys):
    flac = make_flac(out_dir)
    fake_ytdlp.add_pp_event("Metadata", "started", str(flac))
    fake_ytdlp.raises = RuntimeError("tag write failed")

    call(out_dir)

    err = capsys.readouterr().err.strip().splitlines()
    assert len(err) == 1
    assert "embedding failed" in err[0]
    assert "audio kept" in err[0]
    assert "RuntimeError: tag write failed" in err[0]
    assert str(flac) in err[0]


# --- not delivered: behaviour exactly as before -------------------------------------------

def test_only_extract_audio_events_stays_a_failure(fake_ytdlp, out_dir, capsys):
    make_flac(out_dir)
    extract_audio_events(fake_ytdlp, out_dir / "Song.flac")  # even a .flac path is not trusted
    fake_ytdlp.raises = Exception("ERROR: Postprocessing: audio conversion failed: boom")

    result = call(out_dir)

    assert result["ok"] is False
    assert result["kind"] == "media_tools"
    assert result["error"] == "Exception: ERROR: Postprocessing: audio conversion failed: boom"
    assert result["path"] is None
    assert capsys.readouterr().err == ""


def test_finished_events_of_embed_steps_are_not_trusted(fake_ytdlp, out_dir):
    flac = make_flac(out_dir)
    fake_ytdlp.add_pp_event("Metadata", "finished", str(flac))
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    assert call(out_dir)["ok"] is False


def test_storage_failure_is_never_delivered(fake_ytdlp, out_dir):
    flac = make_flac(out_dir)
    fake_ytdlp.add_pp_event("EmbedThumbnail", "started", str(flac))
    fake_ytdlp.raises = OSError("[Errno 28] No space left on device")

    result = call(out_dir)

    assert result["ok"] is False
    assert result["kind"] == "storage"


@pytest.mark.parametrize("content", [None, b"", b"RIFF" + b"\x00" * 100])
def test_missing_empty_or_non_flac_file_is_not_delivered(fake_ytdlp, out_dir, content):
    path = out_dir / "Song.flac"
    if content is not None:
        path.write_bytes(content)
    fake_ytdlp.add_pp_event("EmbedThumbnail", "started", str(path))
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    result = call(out_dir)

    assert result["ok"] is False
    assert result["kind"] == "media_tools"


def test_extension_must_match_the_codec(fake_ytdlp, out_dir):
    webm = out_dir / "Song.webm"
    webm.write_bytes(b"fLaC" + b"\x00" * 50)  # even with the magic, a .webm is ignored
    fake_ytdlp.add_pp_event("Metadata", "started", str(webm))
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    assert call(out_dir)["ok"] is False


def test_extension_follows_a_non_flac_codec(fake_ytdlp, out_dir):
    flac = make_flac(out_dir)
    mp3 = out_dir / "Song.mp3"
    mp3.write_bytes(b"ID3" + b"\x00" * 50)
    fake_ytdlp.add_pp_event("Metadata", "started", str(flac))
    fake_ytdlp.raises = Exception(THUMB_ERROR)
    assert call(out_dir, codec="mp3")["ok"] is False

    fake_ytdlp.pp_events.clear()
    fake_ytdlp.add_pp_event("Metadata", "started", str(mp3))
    result = call(out_dir, codec="mp3")
    assert result["ok"] is True
    assert result["path"] == str(mp3)


# --- hook robustness ----------------------------------------------------------------------

@pytest.mark.parametrize(
    "payload",
    [
        None, 42, "started", [], {},
        {"status": "started"},
        {"status": "started", "postprocessor": "Metadata"},
        {"status": "started", "postprocessor": "Metadata", "info_dict": None},
        {"status": "started", "postprocessor": "Metadata", "info_dict": "nope"},
        {"status": "started", "postprocessor": "Metadata", "info_dict": {"filepath": 7}},
        {"status": "started", "postprocessor": ["Metadata"], "info_dict": {}},
        {"status": None, "postprocessor": None, "info_dict": {"filepath": None}},
    ],
)
def test_malformed_hook_payload_does_not_raise(fake_ytdlp, out_dir, payload):
    fake_ytdlp.pp_events.append(payload)
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    result = call(out_dir)

    assert result["ok"] is False
    assert result["kind"] == "media_tools"


def test_non_string_title_is_dropped(fake_ytdlp, out_dir):
    flac = make_flac(out_dir)
    fake_ytdlp.add_pp_event("Metadata", "started", str(flac), title={"not": "a title"})
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    result = call(out_dir)

    assert result["ok"] is True
    assert result["title"] is None


def test_hook_is_registered_and_exercised(fake_ytdlp, out_dir):
    fake_ytdlp.info = {"title": "x", "requested_downloads": []}
    call(out_dir)

    hooks = fake_ytdlp.last_opts["postprocessor_hooks"]
    assert len(hooks) == 1
    assert hooks[0]({"status": "started"}) is None


# --- is_valid_audio -----------------------------------------------------------------------

def test_valid_flac_without_mutagen_uses_magic_only(tmp_path):
    assert is_valid_audio(str(make_flac(tmp_path)), "flac") is True


def test_mutagen_parse_ok_accepts(tmp_path, monkeypatch):
    fake_mutagen(monkeypatch, length=1.0)
    assert is_valid_audio(str(make_flac(tmp_path)), "flac") is True


def test_mutagen_parse_error_rejects(tmp_path, monkeypatch):
    fake_mutagen(monkeypatch, raises=ValueError("bad stream"))
    assert is_valid_audio(str(make_flac(tmp_path)), "flac") is False


@pytest.mark.parametrize("length", [0, 0.0, -1])
def test_mutagen_non_positive_length_rejects(tmp_path, monkeypatch, length):
    fake_mutagen(monkeypatch, length=length)
    assert is_valid_audio(str(make_flac(tmp_path)), "flac") is False


def test_mutagen_rejection_stops_delivery_end_to_end(fake_ytdlp, out_dir, monkeypatch):
    fake_mutagen(monkeypatch, length=0)
    flac = make_flac(out_dir)
    fake_ytdlp.add_pp_event("EmbedThumbnail", "started", str(flac))
    fake_ytdlp.raises = Exception(THUMB_ERROR)

    assert call(out_dir)["ok"] is False


def test_magic_still_required_when_mutagen_accepts(tmp_path, monkeypatch):
    fake_mutagen(monkeypatch, length=3.0)
    p = tmp_path / "x.flac"
    p.write_bytes(b"OggS" + b"\x00" * 50)
    assert is_valid_audio(str(p), "flac") is False


@pytest.mark.parametrize("path", [None, "", "/no/such/file.flac"])
def test_bad_paths_are_invalid(path):
    assert is_valid_audio(path, "flac") is False


def test_directory_is_invalid(tmp_path):
    d = tmp_path / "dir.flac"
    d.mkdir()
    assert is_valid_audio(str(d), "flac") is False


def test_non_path_object_is_invalid():
    assert is_valid_audio(object(), "flac") is False


def test_embed_postprocessor_names():
    assert sd._EMBED_POSTPROCESSORS == ("Metadata", "EmbedThumbnail")
