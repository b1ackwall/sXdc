# Python tests

Host-side pytest tests for the Python layer (`app/src/main/python/`). They run on the laptop, not the device.

## Setup (once)

From the repo root. The virtualenv is project-local and gitignored; nothing is installed globally.

```
uv venv .venv --python 3.12
uv pip install --python .venv/bin/python -r requirements-dev.txt
```

`requirements-dev.txt` pins the dev tools: pytest and ruff (see `docs/code-quality.md`). Do not install yt-dlp or mutagen there: the tests replace yt-dlp with a fake. The real ones live in a separate venv, see "The real-yt-dlp suite".

## Run

From the repo root:

```
PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -q
```

`pytest.ini` points `testpaths` at `tests/python` and `pythonpath` at `app/src/main/python`, so `import sxdc_download` works with no install step. The tests live outside `app/src` so Chaquopy never packages them into the APK. `conftest.py` disables bytecode writing, so a bare pytest run keeps `app/src/main/python` clean; `check.sh` and CI still export `PYTHONDONTWRITEBYTECODE=1`.

## The fake yt-dlp

`tests/python/conftest.py` defines two fixtures:

- `fake_ytdlp` puts a fake `yt_dlp` module in `sys.modules` for one test. Set `fake_ytdlp.info`, `.raises`, `.init_raises` or `.filename` to control it, then read `.last_opts`, `.extract_calls`, `.prepare_calls` and `.instances` to see what the code did.
- `no_ytdlp` makes `import yt_dlp` raise `ImportError`.

The fake's `add_post_processor` records what `download_audio` adds (the thumbnail guard, #47: it also converts covers over 1 MiB or with a long edge over 1600 px to a scaled-down JPEG, retries once at lower quality, then drops the cover, #76), and `extract_info` runs every `before_dl` postprocessor on `info` right after `match_filter`, the way yt-dlp does.

## The FLAC builder

`tests/python/flac_builder.py` builds small, valid FLAC files with the standard library only: verbatim subframes, fixed or variable block size, a Vorbis comment and picture blocks. The integrity and warnings tests (#45, #46) use it in place of real encoder output, so mutagen and ffmpeg are not needed on the host. Its CRCs are derived independently of the ones in `sxdc_flac.py`.

## Rules

- Tests never touch the network. In the normal suite yt-dlp is always the fake, and ffmpeg is faked where it would run. The opt-in suite above uses the real ones against 127.0.0.1 only.
- Every filesystem path comes from `tmp_path`.
- A test that fails because of production behaviour stays failing; it is not skipped or marked xfail.
- `test_pins_current_ydl_options_settings_preview_must_be_updated_with_it` pins the options passed to yt-dlp. When it has to change, update `settings/YtDlpCommandPreview.kt` and the golden file too.

## The golden options file

`tests/golden/ytdlp-options.properties` guards both sides. `test_options_golden.py` builds the real options through `fake_ytdlp` and checks every golden option against them, in full. The JVM test `YtDlpCommandPreviewGoldenTest` checks the Settings preview against the same file. The preview illustrates the options; it is not an exact command.

When you add or change a yt-dlp option in `download_audio` or `build_ydl_opts`:
- `test_real_options_and_golden_keys_cover_each_other_exactly` fails until the option has a golden key (and a row in `DIRECT_OPTIONS`) or is added to `ALLOWED_UNLISTED` with a reason.
- If the option has a CLI form, add it to `YtDlpCommandPreview` and to the flag map in `YtDlpCommandPreviewGoldenTest`.

## The real-yt-dlp suite (opt-in)

`tests/python/real/` runs `download_audio` against the **real** yt-dlp and mutagen at the versions pinned in `app/requirements-android.txt`, plus a system ffmpeg (#48). The fake in `conftest.py` cannot notice a renamed postprocessor, a changed hook payload or a different exception shape; this suite can.

```
./scripts/real_ytdlp_tests.sh            # extra arguments go to pytest, e.g. -k abort -q
```

The script builds (or reuses) the git-ignored `.venv-real` with Python 3.12, installs exactly `app/requirements-android.txt` (hash-checked) plus the pytest pin from `requirements-dev.txt`, and runs `pytest -m real_ytdlp tests/python/real`. It needs `uv`, and `ffmpeg` and `ffprobe` on `PATH` (it skips with a message if they are missing). The network is needed only for the install. The tests use a stdlib server on 127.0.0.1 (`http_fixture.py`, a port of the instrumented `LocalHttpServer`), so they never reach the internet.

It is not part of `scripts/check.sh` or the normal pytest run: `tests/python/conftest.py` skips the directory unless `-m real_ytdlp` is given, and `.venv` never gets yt-dlp. Run it **before bumping yt-dlp** and again after (step 5 of the update procedure in [dependencies.md](dependencies.md)), and after changing any hook, the embed fallback, the thumbnail guard or `classify`.

CI runs it too, in `.github/workflows/real-ytdlp.yml`: on demand, weekly, and on a push to main that changes `app/requirements-android.txt`. It is never a required check, so a failure gates nothing. Notes:

- It is offline (the local HTTP server), so the network flakiness it can see is only the PyPI and apt installs.
- It uses the runner's apt `ffmpeg`, not the bundled 8.1.1 arm64 build. That is enough for yt-dlp's event shapes; the bundled encoder is covered on the device.
- Live YouTube is deliberately not tested in CI: datacenter IPs get bot-challenged, so it would fail for reasons unrelated to the code. HTTP 403s stay a manual check on a device.
- Schedules run only from the default branch, and a new workflow cannot be dispatched until it is on main. GitHub disables scheduled workflows after 60 days without repository activity.

## The classify corpus

`classify` lives in `app/src/main/python/sxdc_classify.py`; `sxdc_download.py` re-exports it and the `KIND_*` ids. `tests/python/test_classify_corpus.py` runs real yt-dlp 2026.10.26 error messages through it. yt-dlp is not installed for these tests, so stand-in classes reproduce yt-dlp's class names and the attributes `classify` reads (`.status`, `.cause`, `.expected`, `exc_info`, `__context__`).

The `SHAPES` table builds each failure the way yt-dlp actually delivers it to `download_audio`. Keep new rows in that form:
- `page_http`: a failed page fetch. The HTTPError is in `exc_info`, and the `ExtractorError` whose `.cause` is that HTTPError is in `__context__`. The status is trusted.
- `media_http`: a 4xx on the media request. There is no ExtractorError, and the status is trusted.
- `bare`: what the media download leaves after its own retries (5xx, resets, short reads): no `exc_info`, no `__context__`, text only.
- `inside_except`: an extractor error raised while handling an HTTPError (geo, login required, private). The status is ignored and the text decides.
- `inside_transport`: an extractor error raised while handling a `TransportError` (login required, private). The transport error is not trusted and the text decides.
- `media_http` 403 and the bare text `unable to download video data: HTTP Error 403` both give `media_refused`; a page 403 stays `unavailable`.
- `unavailable_video`: `UnavailableVideoError` unwrapped, with the `OSError` in `__context__`.

Cases marked `pin` or `pinned` assert today's behaviour on purpose; change them only together with the code that changes it. Add a corpus row before changing a marker. Never add a bare status code as a marker: the generic extractor uses the URL's last path segment as the video id, so `[generic] 429:` appears in unrelated errors.
