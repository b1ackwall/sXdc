# Python tests

Host-side pytest tests for the Python layer (`app/src/main/python/`). They run on the laptop, not the device.

## Setup (once)

From the repo root. The virtualenv is project-local and gitignored; nothing is installed globally.

```
uv venv .venv --python 3.12
uv pip install --python .venv/bin/python pytest
```

Only pytest is installed. Do not install yt-dlp or mutagen: the tests replace yt-dlp with a fake.

## Run

From the repo root:

```
PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -q
```

`pytest.ini` points `testpaths` at `tests/python` and `pythonpath` at `app/src/main/python`, so `import sxdc_download` works with no install step. The tests live outside `app/src` so Chaquopy never packages them into the APK. `PYTHONDONTWRITEBYTECODE=1` stops a stray `__pycache__` appearing inside the Python source directory.

## The fake yt-dlp

`tests/python/conftest.py` defines two fixtures:

- `fake_ytdlp` puts a fake `yt_dlp` module in `sys.modules` for one test. Set `fake_ytdlp.info`, `.raises`, `.init_raises` or `.filename` to control it, then read `.last_opts`, `.extract_calls`, `.prepare_calls` and `.instances` to see what the code did.
- `no_ytdlp` makes `import yt_dlp` raise `ImportError`.

## Rules

- Tests never touch the network. yt-dlp is always the fake.
- Every filesystem path comes from `tmp_path`.
- A test that fails because of production behaviour stays failing; it is not skipped or marked xfail.
- `test_pins_current_ydl_options_settings_preview_must_be_updated_with_it` pins the options passed to yt-dlp. When it has to change, update `YtDlpCommandPreview` in `SettingsActivity.kt` too.
