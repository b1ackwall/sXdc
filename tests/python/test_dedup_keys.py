import json

import pytest
from sxdc_dedup import dedup_key, dedup_keys, dedup_keys_json

ID = "dQw4w9WgXcQ"
OTHER = "9bZkp7q19f0"
LIST = "PLabcdefghijklmnopqrstuvwxyz012345"

SAME_VIDEO = [
    f"https://www.youtube.com/watch?v={ID}",
    f"https://youtube.com/watch?v={ID}",
    f"https://m.youtube.com/watch?v={ID}",
    f"https://music.youtube.com/watch?v={ID}",
    f"https://www.youtube.com/watch?v={ID}&si=abc123",
    f"https://www.youtube.com/watch?si=abc123&v={ID}",
    f"https://www.youtube.com/watch?v={ID}&feature=share",
    f"https://www.youtube.com/watch?v={ID}&t=42s",
    f"https://www.youtube.com/watch?v={ID}&list={LIST}",
    f"https://www.youtube.com/watch?v={ID}&list={LIST}&index=3&si=x&feature=y&t=9",
    f"https://music.youtube.com/watch?v={ID}&list=RDAMVM{ID}",
    f"https://youtu.be/{ID}",
    f"https://youtu.be/{ID}?si=xyz",
    f"https://youtu.be/{ID}?t=10",
    f"https://youtu.be/{ID}/",
    f"https://www.youtube.com/shorts/{ID}",
    f"https://www.youtube.com/embed/{ID}",
    f"https://www.youtube-nocookie.com/embed/{ID}",
    f"https://www.youtube.com/live/{ID}",
    f"https://www.youtube.com/v/{ID}",
    f"https://WWW.YouTube.com/watch?v={ID}",
    f"https://YOUTU.BE/{ID}",
    f"HTTPS://WWW.YOUTUBE.COM/watch?v={ID}",
    f"http://www.youtube.com/watch?v={ID}",
    f"https://www.youtube.com/watch/?v={ID}",
    f"https://www.youtube.com/watch?v={ID}/",
    f"  https://www.youtube.com/watch?v={ID}  ",
    f"https://www.youtube.com/watch?v={ID}#t=10",
]


@pytest.mark.parametrize("url", SAME_VIDEO)
def test_every_shape_of_one_video_has_the_same_key(url):
    assert dedup_key(url) == "youtube:" + ID


def test_different_ids_differ():
    assert dedup_key(f"https://youtu.be/{ID}") != dedup_key(f"https://youtu.be/{OTHER}")
    assert dedup_key(f"https://www.youtube.com/watch?v={ID}") != dedup_key(
        f"https://music.youtube.com/watch?v={OTHER}"
    )


def test_id_case_is_significant():
    assert dedup_key("https://youtu.be/dQw4w9WgXcQ") != dedup_key("https://youtu.be/DQW4W9WGXCQ")


@pytest.mark.parametrize("url", [
    f"https://www.youtube.com/playlist?list={LIST}",
    f"https://music.youtube.com/playlist?list={LIST}",
    "https://www.youtube.com/@somechannel",
    "https://www.youtube.com/",
    "https://www.youtube.com/watch",
    "https://www.youtube.com/watch?v=short",
    "https://www.youtube.com/watch?list=" + LIST,
    "https://youtu.be/",
    "https://youtu.be/tooshort",
    "https://www.youtube.com/shorts/",
])
def test_youtube_link_without_a_video_id_gets_a_url_key(url):
    key = dedup_key(url)
    assert key.startswith("url:")
    assert not key.startswith("youtube:")


def test_playlist_key_differs_from_video_keys():
    playlist = dedup_key(f"https://www.youtube.com/playlist?list={LIST}")
    assert playlist != dedup_key(f"https://www.youtube.com/watch?v={ID}")
    assert playlist != dedup_key(f"https://www.youtube.com/playlist?list={LIST}x")


def test_other_hosts_with_a_youtube_looking_path_are_not_youtube():
    assert dedup_key(f"https://example.com/watch?v={ID}").startswith("url:")
    assert dedup_key(f"https://notyoutube.com/shorts/{ID}").startswith("url:")
    assert dedup_key(f"https://youtube.com.example.com/watch?v={ID}").startswith("url:")


def test_non_youtube_trailing_slash_host_case_and_fragment_are_ignored():
    base = dedup_key("https://example.com/some/page")
    assert dedup_key("https://example.com/some/page/") == base
    assert dedup_key("https://EXAMPLE.com/some/page") == base
    assert dedup_key("HTTPS://Example.COM/some/page") == base
    assert dedup_key("https://example.com/some/page#section") == base
    assert dedup_key("  https://example.com/some/page  ") == base


def test_non_youtube_root_with_and_without_slash_are_equal():
    assert dedup_key("https://example.com") == dedup_key("https://example.com/")


def test_non_youtube_path_case_is_significant():
    assert dedup_key("https://example.com/Song") != dedup_key("https://example.com/song")


def test_non_youtube_different_path_or_query_differ():
    base = dedup_key("https://example.com/a?x=1")
    assert dedup_key("https://example.com/b?x=1") != base
    assert dedup_key("https://example.com/a?x=2") != base
    assert dedup_key("https://example.com/a") != base
    assert dedup_key("https://example.com:8080/a?x=1") != base


def test_non_youtube_key_keeps_the_query():
    assert dedup_key("https://example.com/a/?x=1#frag") == "url:https://example.com/a?x=1"


def test_user_info_case_is_kept_while_host_is_lowercased():
    assert dedup_key("https://User@EXAMPLE.com/a") == "url:https://User@example.com/a"


@pytest.mark.parametrize("value", [
    "",
    "   ",
    "not a url",
    "https://",
    "http://[::1",
    "x" * 100_000,
    "https://example.com/" + "a" * 100_000,
    "https://www.youtube.com/watch?v=" + "a" * 100_000,
    "\u0000é\U0001f525",
])
def test_odd_strings_never_raise_and_return_a_string(value):
    assert isinstance(dedup_key(value), str)


@pytest.mark.parametrize("value", [None, 5, 1.5, ["a"], {"a": 1}, object()])
def test_non_strings_never_raise_and_return_a_string(value):
    assert isinstance(dedup_key(value), str)


def test_none_has_an_empty_key():
    assert dedup_key(None) == ""


def test_an_object_whose_str_raises_has_an_empty_key():
    class Bad:
        def __str__(self):
            raise RuntimeError("no")

    assert dedup_key(Bad()) == ""


def test_dedup_keys_keeps_length_and_order():
    urls = [f"https://youtu.be/{OTHER}", f"https://youtu.be/{ID}", "https://example.com/a/"]
    assert dedup_keys(urls) == ["youtube:" + OTHER, "youtube:" + ID, "url:https://example.com/a"]


def test_dedup_keys_with_mixed_types_keeps_the_length():
    mixed = ["", None, 5, "not a url", ["x"], f"https://youtu.be/{ID}", " ", "x" * 5000]
    keys = dedup_keys(mixed)
    assert len(keys) == len(mixed)
    assert keys[5] == "youtube:" + ID
    assert all(isinstance(k, str) for k in keys)


def test_dedup_keys_of_an_empty_list_is_empty():
    assert dedup_keys([]) == []


@pytest.mark.parametrize("bad", [None, 5, object()])
def test_dedup_keys_of_a_non_iterable_is_an_empty_list(bad):
    assert dedup_keys(bad) == []


def test_dedup_keys_accepts_any_iterable():
    assert dedup_keys(iter([f"https://youtu.be/{ID}"])) == ["youtube:" + ID]


def test_json_entry_point_round_trips_a_list():
    urls = [f"https://youtu.be/{ID}?si=1", f"https://www.youtube.com/watch?v={ID}", "https://example.com/"]
    raw = dedup_keys_json(json.dumps(urls))
    assert isinstance(raw, str)
    assert json.loads(raw) == dedup_keys(urls)


def test_json_entry_point_keeps_non_string_entries_in_place():
    raw = dedup_keys_json(json.dumps(["https://example.com/a", None, 5]))
    assert len(json.loads(raw)) == 3
