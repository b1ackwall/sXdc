from sxdc_verify import OUTPUT_TEMPLATE, build_ydl_opts


def test_defaults():
    assert build_ydl_opts("/some/bin") == {
        "ffmpeg_location": "/some/bin",
        "outtmpl": "%(title).100s [%(id)s].%(ext)s",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
    }


def test_bin_dir_none_is_passed_through():
    assert build_ydl_opts(None)["ffmpeg_location"] is None


def test_noplaylist_false():
    assert build_ydl_opts("/b", noplaylist=False)["noplaylist"] is False


def test_custom_outtmpl():
    assert build_ydl_opts("/b", outtmpl="%(id)s.%(ext)s")["outtmpl"] == "%(id)s.%(ext)s"


def test_empty_outtmpl_falls_back_to_default():
    assert build_ydl_opts("/b", outtmpl="")["outtmpl"] == "%(title).100s [%(id)s].%(ext)s"


def test_default_template_is_the_shared_constant():
    assert build_ydl_opts("/b")["outtmpl"] == OUTPUT_TEMPLATE


def test_template_truncates_only_the_title_and_keeps_the_id_outside_it():
    assert OUTPUT_TEMPLATE == "%(title).100s [%(id)s].%(ext)s"
    assert OUTPUT_TEMPLATE.count(".100s") == 1
    assert OUTPUT_TEMPLATE.index("%(id)s") > OUTPUT_TEMPLATE.index(".100s")


def test_extra_overrides_existing_keys():
    opts = build_ydl_opts("/b", extra={"quiet": False, "noplaylist": False, "outtmpl": "x"})
    assert opts["quiet"] is False
    assert opts["noplaylist"] is False
    assert opts["outtmpl"] == "x"


def test_extra_adds_new_keys_and_keeps_the_rest():
    opts = build_ydl_opts("/b", extra={"skip_download": True})
    assert opts["skip_download"] is True
    assert opts["ffmpeg_location"] == "/b"
    assert opts["no_warnings"] is True


def test_extra_none_and_empty_change_nothing():
    base = build_ydl_opts("/b")
    assert build_ydl_opts("/b", extra=None) == base
    assert build_ydl_opts("/b", extra={}) == base


def test_returns_a_fresh_dict_each_call():
    a = build_ydl_opts("/b")
    b = build_ydl_opts("/b")
    assert a == b
    assert a is not b
    a["quiet"] = False
    assert build_ydl_opts("/b")["quiet"] is True


def test_extra_dict_is_not_mutated():
    extra = {"skip_download": True}
    build_ydl_opts("/b", extra=extra)
    assert extra == {"skip_download": True}
