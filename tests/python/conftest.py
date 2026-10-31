"""Shared fixtures for the Python-layer tests.

yt-dlp is never installed for these tests and must never reach the network. The production code
imports it lazily (`import yt_dlp` inside the functions), so putting a stand-in module into
`sys.modules` is enough to intercept every call.
"""

import sys
import types

import pytest


class FakeYtDlp:
    """Controller for the fake `yt_dlp` module.

    Configure what the fake does, call the code under test, then inspect what it recorded.

    Configure:
      info          returned by extract_info()
      raises        exception instance raised by extract_info()
      init_raises   exception instance raised by YoutubeDL(...)
      filename      returned by prepare_filename()

    Recorded:
      instances       every YoutubeDL object constructed, in order
      last_opts       the options dict the most recent YoutubeDL was constructed with
      extract_calls   [(url, download), ...]
      prepare_calls   [info, ...]
    """

    def __init__(self):
        self.info = None
        self.raises = None
        self.init_raises = None
        self.filename = None
        self.instances = []
        self.extract_calls = []
        self.prepare_calls = []
        self.module = types.ModuleType("yt_dlp")
        self.module.YoutubeDL = self._build_class()

    @property
    def last_opts(self):
        return self.instances[-1].opts

    def _build_class(self):
        controller = self

        class YoutubeDL:
            # Mirrors how sxdc_download uses the real class: constructed with an options dict,
            # used as a context manager, extract_info(url, download=...) and prepare_filename(info).
            def __init__(self, opts=None):
                if controller.init_raises is not None:
                    raise controller.init_raises
                self.opts = opts
                self.entered = False
                self.exited = False
                controller.instances.append(self)

            def __enter__(self):
                self.entered = True
                return self

            def __exit__(self, exc_type, exc, tb):
                self.exited = True
                return False  # never swallow, like the real one

            def extract_info(self, url, download=True):
                controller.extract_calls.append((url, download))
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
