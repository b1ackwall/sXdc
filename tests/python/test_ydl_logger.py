"""The yt-dlp logger of #77: warnings and errors to stderr (logcat's python.stderr), nothing else."""

from sxdc_verify import LOG_LIMIT, LOG_PREFIX, YdlLogger, build_ydl_opts


def test_warning_is_forwarded_as_one_prefixed_line(capsys):
    YdlLogger().warning("thumbnail download failed")

    out = capsys.readouterr()
    assert out.err == LOG_PREFIX + "thumbnail download failed\n"
    assert out.out == ""


def test_error_is_forwarded_too(capsys):
    YdlLogger().error("ERROR: [youtube] abc: This video is unavailable")

    assert capsys.readouterr().err == "yt-dlp: ERROR: [youtube] abc: This video is unavailable\n"


def test_debug_and_info_stay_silent(capsys):
    logger = YdlLogger()
    logger.debug("[debug] anything")
    logger.info("[download] 50%")

    out = capsys.readouterr()
    assert out.err == "" and out.out == ""


def test_long_message_is_cut_to_the_limit(capsys):
    YdlLogger().warning("x" * 2000)

    line = capsys.readouterr().err.rstrip("\n")
    assert line.startswith(LOG_PREFIX + "xxx")
    assert len(line) == len(LOG_PREFIX) + LOG_LIMIT
    assert line.endswith("...")


def test_message_at_the_limit_is_not_cut(capsys):
    YdlLogger().warning("y" * LOG_LIMIT)

    assert capsys.readouterr().err == LOG_PREFIX + "y" * LOG_LIMIT + "\n"


def test_multiline_message_becomes_one_line(capsys):
    YdlLogger().warning("first\n  second\r\nthird\t\tfourth")

    assert capsys.readouterr().err == "yt-dlp: first second third fourth\n"


def test_weird_input_never_raises(capsys):
    class Broken:
        def __str__(self):
            raise RuntimeError("no string for you")

    logger = YdlLogger()
    for value in (None, 42, b"bytes \xff", Broken(), "\ud800 lone surrogate", ["a", "b"]):
        logger.warning(value)
        logger.error(value)
    capsys.readouterr()


def test_a_broken_stderr_does_not_raise(monkeypatch):
    class Dead:
        def write(self, _):
            raise OSError("closed")

        def flush(self):
            raise OSError("closed")

    monkeypatch.setattr("sys.stderr", Dead())

    YdlLogger().warning("anything")


def test_build_ydl_opts_installs_the_logger_and_lets_warnings_through():
    opts = build_ydl_opts("/bin")

    assert isinstance(opts["logger"], YdlLogger)
    assert opts["no_warnings"] is False
    assert opts["quiet"] is True
