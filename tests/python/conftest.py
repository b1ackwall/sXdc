"""Shared fixtures for the Python-layer tests.

yt-dlp is never installed for these tests and must never reach the network. The production code
imports it lazily (`import yt_dlp` inside the functions), so putting a stand-in module into
`sys.modules` is enough to intercept every call.
"""

import sys
import types

import pytest

# Issue #13: keep __pycache__ out of app/src/main/python (the Chaquopy source dir). check.sh
# also exports PYTHONDONTWRITEBYTECODE=1; this covers a bare pytest run.
sys.dont_write_bytecode = True


class FakeYtDlp:
    """Controller for the fake `yt_dlp` module.

    Configure what the fake does, call the code under test, then inspect what it recorded.

    Configure:
      info          returned by extract_info()
      raises        exception instance raised by extract_info()
      init_raises   exception instance raised by YoutubeDL(...)
      filename      returned by prepare_filename()
      pp_events     postprocessor hook payloads; extract_info passes each, in order, to every
                    hook in opts["postprocessor_hooks"] before it raises or returns. Any object
                    is passed through as-is, so malformed payloads can be tested too.
      progress_events  progress hook payloads, passed the same way to opts["progress_hooks"],
                    before the postprocessor events
      on_event      called with each progress or postprocessor payload just before the hooks
      before_finish called after every event, just before extract_info raises or returns

    extract_info calls opts["match_filter"](info or {}, incomplete=False), if set, before any
    event, the way yt-dlp checks it once between extraction and the download. Right after it,
    each postprocessor added with add_post_processor(pp, when="before_dl") runs on `info` (or {}
    if info is not a dict), in the order added; an exception from one propagates like the real
    one's. Postprocessors added for any other `when` are only recorded.

    Recorded:
      instances       every YoutubeDL object constructed, in order; each has .post_processors,
                      the [(pp, when), ...] added to it
      last_opts       the options dict the most recent YoutubeDL was constructed with
      extract_calls   [(url, download), ...]
      prepare_calls   [info, ...]
    """

    def __init__(self):
        self.info = None
        self.raises = None
        self.init_raises = None
        self.filename = None
        self.pp_events = []
        self.progress_events = []
        self.on_event = lambda event: None
        self.before_finish = lambda: None
        self.instances = []
        self.extract_calls = []
        self.prepare_calls = []
        self.module = types.ModuleType("yt_dlp")
        self.module.YoutubeDL = self._build_class()

    @property
    def last_opts(self):
        return self.instances[-1].opts

    def add_pp_event(self, postprocessor, status, filepath, title="T", **info):
        """Queue one well-formed hook payload, shaped like yt-dlp's; `info` adds info_dict keys."""
        self.pp_events.append(
            {
                "status": status,
                "postprocessor": postprocessor,
                "info_dict": {"filepath": filepath, "title": title, **info},
            }
        )

    def _build_class(self):
        controller = self

        class YoutubeDL:
            # Mirrors how sxdc_download uses the real class: constructed with an options dict,
            # used as a context manager, extract_info(url, download=...) and prepare_filename(info).
            def __init__(self, opts=None):
                if controller.init_raises is not None:
                    raise controller.init_raises
                self.opts = opts
                self.post_processors = []
                self.entered = False
                self.exited = False
                controller.instances.append(self)

            def __enter__(self):
                self.entered = True
                return self

            def __exit__(self, exc_type, exc, tb):
                self.exited = True
                return False  # never swallow, like the real one

            def add_post_processor(self, pp, when="post_process"):
                self.post_processors.append((pp, when))
                if hasattr(pp, "set_downloader"):
                    pp.set_downloader(self)

            def extract_info(self, url, download=True):
                controller.extract_calls.append((url, download))
                opts = self.opts or {}
                if opts.get("match_filter") is not None:
                    opts["match_filter"](controller.info or {}, incomplete=False)
                info = controller.info if isinstance(controller.info, dict) else {}
                for pp, when in self.post_processors:
                    if when == "before_dl":
                        pp.run(info)
                # Event by event, every hook for each, the way yt-dlp dispatches them.
                for key, events in (
                    ("progress_hooks", controller.progress_events),
                    ("postprocessor_hooks", controller.pp_events),
                ):
                    for event in events:
                        controller.on_event(event)
                        for hook in opts.get(key) or []:
                            hook(event)
                controller.before_finish()
                if controller.raises is not None:
                    raise controller.raises
                return controller.info

            def prepare_filename(self, info):
                controller.prepare_calls.append(info)
                return controller.filename

        return YoutubeDL


@pytest.fixture
def fake_ytdlp(monkeypatch):
    """Install a fake `yt_dlp` module for the duration of one test."""
    fake = FakeYtDlp()
    monkeypatch.setitem(sys.modules, "yt_dlp", fake.module)
    return fake


@pytest.fixture
def no_ytdlp(monkeypatch):
    """Make `import yt_dlp` raise ImportError for the duration of one test."""
    monkeypatch.setitem(sys.modules, "yt_dlp", None)


def pytest_ignore_collect(collection_path, config):
    """Leave tests/python/real out of a normal run: it needs the real yt-dlp (#48).

    Only `-m real_ytdlp`, as scripts/real_ytdlp_tests.sh passes it, collects that directory.
    """
    if "real" in collection_path.parts and "python" in collection_path.parts:
        parts = collection_path.parts
        if parts[parts.index("python") + 1 : parts.index("python") + 2] == ("real",):
            return "real_ytdlp" not in (config.option.markexpr or "")
    return None
