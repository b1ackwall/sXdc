# Dependencies

How the components that ship in the APK are pinned, listed and updated. The license side is in
[THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md); the release steps are in
[releasing.md](releasing.md).

## Where versions live

| What | Pinned in | Listed in |
|---|---|---|
| yt-dlp, mutagen (Python, from PyPI) | `app/requirements-android.txt` (exact version and sha256) | `licenses/components.tsv`, `THIRD_PARTY_LICENSES.md` |
| AndroidX, Compose, Kotlin, coroutines (Maven) | `gradle/libs.versions.toml` | `licenses/components.tsv` (`maven` column), checked by `scripts/check_license_inventory.py` |
| Python runtime and its native libraries, setuptools, pyelftools, CA bundle | Chaquopy version (`gradle/libs.versions.toml`) | `licenses/components.tsv`, kept by hand |
| FFmpeg / ffprobe | the binaries in `app/src/main/jniLibs/arm64-v8a/` | `licenses/components.tsv`, `THIRD_PARTY_LICENSES.md` |

The Chaquopy pip block in `app/build.gradle.kts` installs `-r requirements-android.txt` with
`--require-hashes`. pip then refuses any package whose file does not match its hash, and any
package, including a new transitive dependency, that has no hashed line at all. A changed or
unexpected package fails the build instead of shipping.

## Updating yt-dlp or mutagen

When YouTube changes and the pinned yt-dlp stops working, bump it like this. mutagen works the
same way.

1. **Get the hash** in a throwaway virtualenv outside the repo (never the project `.venv`):

   ```sh
   uv venv /tmp/sxdc-pin -p 3.12
   uv pip install -p /tmp/sxdc-pin/bin/python pip
   /tmp/sxdc-pin/bin/python -m pip download --no-deps -d /tmp/sxdc-pin/wheels yt-dlp==<version>
   /tmp/sxdc-pin/bin/python -m pip hash /tmp/sxdc-pin/wheels/*
   ```

   Check that the file is the `py3-none-any` wheel (the one Chaquopy installs) and that the
   hash equals the sha256 shown for that file on `https://pypi.org/project/yt-dlp/<version>/#files`.
2. **Edit `app/requirements-android.txt`**: the version after `==` and the hash after
   `--hash=sha256:`.
3. **Update every version mention**:
   - `licenses/components.tsv` (the yt-dlp row; `LicenseInventoryRepoTest` fails until it
     matches the pin);
   - `THIRD_PARTY_LICENSES.md` (summary table, and the source links with both sha256 values);
   - the docstrings that name the version the Python layer was checked against
     (`grep -rn "<old version>" app/src/main/python tests/python`).
   If the wheel's `dist-info` license file changed, replace `licenses/yt-dlp-Unlicense.txt` with
   the new one from the built APK.
4. **Run the checks**: `./scripts/check.sh` and `./gradlew assembleDebug`. The pytest suite uses a
   fake yt-dlp, so it does not exercise the new version; the golden options file and the command
   preview do not contain the version.
5. **Do a real download on a device**: install the debug build and share one YouTube and one
   YouTube Music link. Check that the FLAC arrives in the chosen folder with cover art and tags.
   Run the instrumented suite (`./gradlew :app:connectedDebugAndroidTest`) as well.
6. **Ship** following [releasing.md](releasing.md).

## Rules

- `requirements-dev.txt` holds host-side dev tools only (pytest, ruff). It must never contain
  yt-dlp or mutagen: the Python tests replace yt-dlp with a fake (`docs/python-tests.md`).
- yt-dlp's optional extras are **not** installed: certifi, brotli, websockets, pycryptodomex,
  requests, urllib3, yt-dlp-ejs and the others in its `default` extra. Adding any of them needs
  a hashed pin in `app/requirements-android.txt` (for it and each of its own dependencies), a
  row in `licenses/components.tsv` with its license text in `licenses/`, and an entry in
  `THIRD_PARTY_LICENSES.md`.
- A new Maven dependency needs a `components.tsv` row whose `maven` cell covers its group (or
  `group:artifact` prefix). `./scripts/check.sh` fails until it has one.
- Every file in `licenses/` must be an unmodified copy from an authoritative source; record the
  source in the table in `THIRD_PARTY_LICENSES.md`.
