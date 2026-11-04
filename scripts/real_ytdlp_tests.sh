#!/usr/bin/env bash
# Opt-in tests of the Python layer against the REAL yt-dlp and mutagen (#48), offline.
#
#   ./scripts/real_ytdlp_tests.sh [pytest args...]
#
# Builds (or reuses) the git-ignored .venv-real with Python 3.12, installs exactly
# app/requirements-android.txt (the pinned yt-dlp and mutagen, hash-checked) plus the pytest
# version of requirements-dev.txt, and runs `pytest -m real_ytdlp tests/python/real`.
# Needs ffmpeg and ffprobe on PATH and network access only for the install. The tests themselves
# talk to a server on 127.0.0.1. Not part of scripts/check.sh; see docs/python-tests.md.
# Never touches .venv or requirements-dev.txt.
set -eu

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if ! command -v ffmpeg >/dev/null 2>&1 || ! command -v ffprobe >/dev/null 2>&1; then
    echo "SKIPPED: ffmpeg and ffprobe are required on PATH; the real-yt-dlp tests did not run." >&2
    exit 0
fi
if ! command -v uv >/dev/null 2>&1; then
    echo "uv is required to build .venv-real (see docs/linux-setup.md)." >&2
    exit 2
fi

VENV=.venv-real
PY=$VENV/bin/python
if [ ! -x "$PY" ]; then
    uv venv "$VENV" --python 3.12
fi

# Exactly the pins the APK ships. --require-hashes makes uv refuse anything unhashed.
uv pip install --python "$PY" --require-hashes -r app/requirements-android.txt
# pytest comes from the dev pins, separately, because hash mode would reject an unhashed line.
pytest_pin=$(grep -E '^pytest==' requirements-dev.txt)
uv pip install --python "$PY" "$pytest_pin"

export PYTHONDONTWRITEBYTECODE=1
exec "$PY" -m pytest -m real_ytdlp tests/python/real "$@"
