import pytest

import sxdc_download as sd
from sxdc_download import classify


def test_kind_constant_values():
    # Kotlin's DownloadService mirrors these strings; a rename here silently breaks the mapping.
    assert sd.KIND_NETWORK == "network"
    assert sd.KIND_UNAVAILABLE == "unavailable"
    assert sd.KIND_UNSUPPORTED == "unsupported"
    assert sd.KIND_RESTRICTED == "restricted"
    assert sd.KIND_AUTH == "auth"
    assert sd.KIND_STORAGE == "storage"
    assert sd.KIND_MEDIA_TOOLS == "media_tools"
    assert sd.KIND_NO_OUTPUT == "no_output"
    assert sd.KIND_ENGINE == "engine"
    assert sd.KIND_EXTRACTOR == "extractor"
    assert sd.KIND_UNKNOWN == "unknown"


def test_kind_constants_are_distinct():
    kinds = [v for k, v in vars(sd).items() if k.startswith("KIND_")]
    assert len(kinds) == 11
    assert len(set(kinds)) == 11


def test_default_codec():
    assert sd.DEFAULT_CODEC == "flac"


@pytest.mark.parametrize(
    "message, expected",
    [
        ("[Errno 28] No space left on device", "storage"),
        ("ERROR: ffprobe not found. Please install", "media_tools"),
        ("Sign in to confirm your age. This video may be inappropriate", "auth"),
        ("The uploader has not made this video available in your country", "restricted"),
        ("Private video. Sign in if you've been granted access", "unavailable"),
        ("Unsupported URL: https://example.com/page", "unsupported"),
        ("Unable to download webpage: <urlopen error [Errno -2] Name or service not known>", "network"),
        ("something entirely different happened", "unknown"),
    ],
)
def test_one_representative_message_per_kind(message, expected):
    assert classify(Exception(message)) == expected


def test_exception_class_name_participates():
    class SocketTimeoutProblem(Exception):
        pass

    # The message alone matches nothing...
    assert classify(Exception("boom")) == "unknown"
    # ...but "socket"/"timeout" in the class name is enough.
    assert classify(SocketTimeoutProblem("boom")) == "network"


def test_builtin_exception_class_names_participate():
    assert classify(ConnectionResetError()) == "network"
    assert classify(TimeoutError()) == "network"


def test_search_text_is_type_name_then_message_lowercased():
    class FfmpegBroke(Exception):
        pass

    assert classify(FfmpegBroke("")) == "media_tools"


@pytest.mark.parametrize(
    "message, expected",
    [
        ("NO SPACE LEFT ON DEVICE", "storage"),
        ("UNSUPPORTED URL: x", "unsupported"),
        ("Sign In To Confirm Your Age", "auth"),
        ("PRIVATE VIDEO", "unavailable"),
    ],
)
def test_matching_is_case_insensitive(message, expected):
    assert classify(Exception(message)) == expected


# --- ordering: the first kind in _MARKERS whose marker appears wins ------------------------

def test_storage_beats_everything():
    msg = "no space left on device; ffmpeg postprocessing failed; connection reset; sign in to confirm"
    assert classify(Exception(msg)) == "storage"


def test_media_tools_beats_network():
    assert classify(Exception("ffmpeg: connection reset by peer")) == "media_tools"


def test_media_tools_beats_auth_and_unavailable():
    assert classify(Exception("ffprobe failed: private video, sign in to confirm")) == "media_tools"


def test_auth_beats_unavailable():
    assert classify(Exception("Sign in to confirm your age. Private video")) == "auth"


def test_restricted_beats_unavailable():
    assert classify(Exception("This video is not available in your country; video unavailable")) == "restricted"


def test_unavailable_beats_unsupported_and_network():
    assert classify(Exception("Video unavailable; connection reset")) == "unavailable"
    assert classify(Exception("This playlist is empty; unsupported url")) == "unavailable"


def test_unsupported_beats_network():
    assert classify(Exception("Unsupported URL, connection refused")) == "unsupported"


# --- extractor breakage ------------------------------------------------------------------

# yt-dlp's bug_reports_message() suffix, as ExtractorError appends it to an unexpected error.
BUG_SUFFIX = (
    "; please report this issue on  https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the "
    "appropriate issue template. Confirm you are on the latest version using  yt-dlp -U"
)


def test_unable_to_extract_is_extractor():
    assert classify(Exception("ERROR: [youtube] abc: Unable to extract initial player response")) == "extractor"


def test_bug_report_suffix_is_extractor():
    assert classify(Exception("ERROR: [youtube] abc: Something odd happened" + BUG_SUFFIX)) == "extractor"


@pytest.mark.parametrize(
    "marker",
    ["please report this issue", "confirm you are on the latest version"],
)
def test_each_bug_report_suffix_marker_alone_is_extractor(marker):
    assert classify(Exception("ERROR: x. " + marker.capitalize())) == "extractor"


@pytest.mark.parametrize(
    "prefix, expected",
    [
        ("[Errno 28] No space left on device", "storage"),
        ("ERROR: ffprobe not found", "media_tools"),
        ("Sign in to confirm your age", "auth"),
        ("The uploader has not made this video available in your country", "restricted"),
    ],
)
def test_earlier_kinds_beat_extractor(prefix, expected):
    assert classify(Exception(prefix + "; Unable to extract initial player response")) == expected
    assert classify(Exception(prefix + BUG_SUFFIX)) == expected


def test_extractor_beats_unavailable_unsupported_and_network():
    assert classify(Exception("Unable to extract uploader id; connection reset")) == "extractor"
    assert classify(Exception("Video unavailable" + BUG_SUFFIX)) == "extractor"
    assert classify(Exception("Unsupported URL" + BUG_SUFFIX)) == "extractor"
    assert classify(Exception("Unable to download webpage: timed out" + BUG_SUFFIX)) == "extractor"


def test_real_unavailable_message_is_still_unavailable():
    assert classify(Exception("ERROR: [youtube] abc: Video unavailable")) == "unavailable"


def test_network_message_is_still_network():
    assert classify(Exception("ERROR: [youtube] abc: Unable to download webpage: HTTP Error 503")) == "network"


# --- odd inputs ---------------------------------------------------------------------------

def test_empty_message_is_unknown():
    assert classify(Exception("")) == "unknown"
    assert classify(Exception()) == "unknown"


def test_whitespace_and_symbols_are_unknown():
    assert classify(Exception("   \n\t  ")) == "unknown"
    assert classify(Exception("!!! ??? ###")) == "unknown"


def test_unicode_message_does_not_raise():
    assert classify(Exception("ошибка загрузки 🎵")) == "unknown"


def test_non_exception_string_is_accepted():
    # classify only formats the object, so a bare string works; its type name is "str".
    assert classify("No space left on device") == "storage"
    assert classify("nothing recognisable") == "unknown"


def test_non_exception_objects_are_accepted():
    assert classify(None) == "unknown"
    assert classify(42) == "unknown"
