import json

import pytest

from sxdc_download import expand_urls

URL = "https://music.example.com/watch?v=abc"


def run(url=URL):
    return json.loads(expand_urls(url))


def test_single_video_is_one_element_with_title_none(fake_ytdlp):
    # Documented behaviour: a single video's title is unknown until the real download runs,
    # so the title is None even though yt-dlp's info dict carries one.
    fake_ytdlp.info = {"id": "abc", "title": "Some Song", "webpage_url": URL}
    assert run() == [{"url": URL, "title": None}]


def test_playlist_one_element_per_entry_in_order(fake_ytdlp):
    fake_ytdlp.info = {
        "_type": "playlist",
        "entries": [
            {"url": "https://x/1", "title": "One"},
            {"url": "https://x/2", "title": "Two"},
            {"url": "https://x/3", "title": "Three"},
        ],
    }
    assert run() == [
        {"url": "https://x/1", "title": "One"},
        {"url": "https://x/2", "title": "Two"},
        {"url": "https://x/3", "title": "Three"},
    ]


def test_entry_url_preferred_over_webpage_url(fake_ytdlp):
    fake_ytdlp.info = {
        "_type": "playlist",
        "entries": [{"url": "https://x/direct", "webpage_url": "https://x/page", "title": "T"}],
    }
    assert run()[0]["url"] == "https://x/direct"


def test_webpage_url_used_when_url_missing_or_empty(fake_ytdlp):
    fake_ytdlp.info = {
        "_type": "playlist",
        "entries": [
            {"webpage_url": "https://x/page1", "title": "A"},
            {"url": "", "webpage_url": "https://x/page2", "title": "B"},
        ],
    }
    assert run() == [
        {"url": "https://x/page1", "title": "A"},
        {"url": "https://x/page2", "title": "B"},
    ]


def test_entries_without_any_url_are_dropped(fake_ytdlp):
    fake_ytdlp.info = {
        "_type": "playlist",
        "entries": [
            {"title": "no url"},
            {"url": None, "webpage_url": None, "title": "also none"},
            {"url": "https://x/keep", "title": "Keep"},
        ],
    }
    assert run() == [{"url": "https://x/keep", "title": "Keep"}]


def test_none_entries_are_dropped(fake_ytdlp):
    fake_ytdlp.info = {"_type": "playlist", "entries": [None, {"url": "https://x/1", "title": "One"}, None]}
    assert run() == [{"url": "https://x/1", "title": "One"}]


def test_missing_title_becomes_none(fake_ytdlp):
    fake_ytdlp.info = {"_type": "playlist", "entries": [{"url": "https://x/1"}]}
    assert run() == [{"url": "https://x/1", "title": None}]


def test_entries_key_without_type_is_treated_as_playlist(fake_ytdlp):
    fake_ytdlp.info = {"entries": [{"url": "https://x/1", "title": "One"}]}
    assert run() == [{"url": "https://x/1", "title": "One"}]


def test_one_entry_playlist_keeps_entry_url_and_title(fake_ytdlp):
    fake_ytdlp.info = {"_type": "playlist", "entries": [{"url": "https://x/only", "title": "Only"}]}
    assert run() == [{"url": "https://x/only", "title": "Only"}]


def test_extra_entry_keys_are_not_leaked(fake_ytdlp):
    fake_ytdlp.info = {
        "_type": "playlist",
        "entries": [{"url": "https://x/1", "title": "One", "id": "1", "ie_key": "Youtube", "duration": 9}],
    }
    out = run()
    assert set(out[0]) == {"url", "title"}


def test_options_and_call_arguments(fake_ytdlp):
    fake_ytdlp.info = {"_type": "playlist", "entries": [{"url": "https://x/1"}]}
    expand_urls(URL)

    opts = fake_ytdlp.last_opts
    assert opts["noplaylist"] is False
    assert opts["extract_flat"] == "in_playlist"
    assert opts["skip_download"] is True
    assert opts["ffmpeg_location"] is None
    assert opts["quiet"] is True
    assert opts["no_warnings"] is True
    assert fake_ytdlp.extract_calls == [(URL, False)]
    assert len(fake_ytdlp.instances) == 1


def test_context_manager_is_entered_and_exited(fake_ytdlp):
    fake_ytdlp.info = {"title": "x"}
    expand_urls(URL)
    ydl = fake_ytdlp.instances[0]
    assert ydl.entered and ydl.exited


def test_context_manager_exits_when_extract_raises(fake_ytdlp):
    fake_ytdlp.raises = RuntimeError("boom")
    expand_urls(URL)
    assert fake_ytdlp.instances[0].exited


@pytest.mark.parametrize(
    "info",
    [
        None,
        {"_type": "playlist", "entries": []},
        {"_type": "playlist", "entries": None},
        {"_type": "playlist", "entries": [None, {"title": "no url"}]},
        {"title": "plain video"},
    ],
    ids=["none", "empty-entries", "entries-none", "no-usable-entries", "plain-video"],
)
def test_fallback_when_nothing_usable(fake_ytdlp, info):
    fake_ytdlp.info = info
    assert run() == [{"url": URL, "title": None}]


def test_fallback_on_extract_exception(fake_ytdlp):
    fake_ytdlp.raises = Exception("Unable to download webpage")
    assert run() == [{"url": URL, "title": None}]


def test_fallback_when_youtubedl_constructor_raises(fake_ytdlp):
    fake_ytdlp.init_raises = RuntimeError("bad opts")
    assert run() == [{"url": URL, "title": None}]


def test_fallback_when_yt_dlp_cannot_be_imported(no_ytdlp):
    assert run() == [{"url": URL, "title": None}]


def test_return_is_json_string_of_list_of_url_title_dicts(fake_ytdlp):
    fake_ytdlp.info = {"_type": "playlist", "entries": [{"url": "https://x/1", "title": "A"}, {"url": "https://x/2"}]}
    raw = expand_urls(URL)
    assert isinstance(raw, str)
    parsed = json.loads(raw)
    assert isinstance(parsed, list)
    assert all(isinstance(t, dict) and set(t) == {"url", "title"} for t in parsed)


def test_fallback_return_is_also_a_json_string(no_ytdlp):
    raw = expand_urls(URL)
    assert isinstance(raw, str)
    assert json.loads(raw) == [{"url": URL, "title": None}]
