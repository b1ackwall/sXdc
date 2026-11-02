# Python tests

Host-side pytest tests for the Python layer (`app/src/main/python/`). They run on the laptop, not the device.

## Setup (once)

From the repo root. The virtualenv is project-local and gitignored; nothing is installed globally.

```
uv venv .venv --python 3.12
uv pip install --python .venv/bin/python -r requirements-dev.txt
```

`requirements-dev.txt` pins the dev tools: pytest and ruff (see `docs/code-quality.md`). Do not install yt-dlp or mutagen: the tests replace yt-dlp with a fake.

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

## Rules

- Tests never touch the network. yt-dlp is always the fake.
- Every filesystem path comes from `tmp_path`.
- A test that fails because of production behaviour stays failing; it is not skipped or marked xfail.
- `test_pins_current_ydl_options_settings_preview_must_be_updated_with_it` pins the options passed to yt-dlp. When it has to change, update `settings/YtDlpCommandPreview.kt` and the golden file too.

## The golden options file

`tests/golden/ytdlp-options.properties` guards both sides. `test_options_golden.py` builds the real options through `fake_ytdlp` and checks every golden option against them, in full. The JVM test `YtDlpCommandPreviewGoldenTest` checks the Settings preview against the same file. The preview illustrates the options; it is not an exact command.

When you add or change a yt-dlp option in `download_audio` or `build_ydl_opts`:
- `test_real_options_and_golden_keys_cover_each_other_exactly` fails until the option has a golden key (and a row in `DIRECT_OPTIONS`) or is added to `ALLOWED_UNLISTED` with a reason.
- If the option has a CLI form, add it to `YtDlpCommandPreview` and to the flag map in `YtDlpCommandPreviewGoldenTest`.

## The classify corpus

`classify` lives in `app/src/main/python/sxdc_classify.py`; `sxdc_download.py` re-exports it and the `KIND_*` ids. `tests/python/test_classify_corpus.py` runs real yt-dlp 2026.10.26 error messages through it. yt-dlp is not installed for these tests, so stand-in classes reproduce yt-dlp's class names and the attributes `classify` reads (`.status`, `.cause`, `.expected`, `exc_info`, `__context__`).

The `SHAPES` table builds each failure the way yt-dlp actually delivers it to `download_audio`. Keep new rows in that form:
- `page_http`: a failed page fetch. The HTTPError is in `exc_info`, and the `ExtractorError` whose `.cause` is that HTTPError is in `__context__`. The status is trusted.
- `media_http`: a 4xx on the media request. There is no ExtractorError, and the status is trusted.
- `bare`: what the media download leaves after its own retries (5xx, resets, short reads): no `exc_info`, no `__context__`, text only.
- `inside_except`: an extractor error raised while handling an HTTPError (geo, login required, private). The status is ignored and the text decides.
- `unavailable_video`: `UnavailableVideoError` unwrapped, with the `OSError` in `__context__`.

Cases marked `pin` or `pinned` assert today's behaviour on purpose; change them only together with the code that changes it. Add a corpus row before changing a marker. Never add a bare status code as a marker: the generic extractor uses the URL's last path segment as the video id, so `[generic] 429:` appears in unrelated errors.
