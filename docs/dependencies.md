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
   preview do not contain the version. Then run `./scripts/real_ytdlp_tests.sh`, the opt-in suite
   against the real pinned yt-dlp (see [python-tests.md](python-tests.md)); it needs the new pin
   from step 2 already in place.
5. **Do a real download on a device**: install the debug build and share one YouTube and one
   YouTube Music link. Check that the FLAC arrives in the chosen folder with cover art and tags.
   Run the instrumented suite (`./gradlew :app:connectedDebugAndroidTest`) as well. Also watch
   the log, as in "Manual check: the JavaScript runtime warning" below.
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

## Known limitation: no JavaScript runtime

yt-dlp can use a JavaScript runtime plus `yt-dlp-ejs` to solve YouTube's player challenges. sXdc
ships neither, so yt-dlp logs "No supported JavaScript runtime could be found" and, without a
runtime, requests formats through one player client only (`visionos`). This works today. On a
desktop with yt-dlp 2026.10.26 and no runtime, a public video offered its audio as 251 (opus,
129k) and 140 (m4a, 129k), and the FLAC extraction succeeded. With node and `yt-dlp-ejs` the
same video also offered 5.1 streams up to 388k, which is a small gain for music.

- `yt-dlp-ejs` alone adds nothing: without a runtime it is never used.
- The supported runtimes are external executables that yt-dlp starts as a subprocess: deno
  2.3.0 or newer, node 22 or newer, bun 1.2.11 to 1.3.14, quickjs (2023-12-09 or newer) or
  quickjs-ng. None of them ships on Android, and the PyPI `quickjs` package is a C binding that
  yt-dlp does not use.
- The only route would be a custom static arm64 `qjs` binary in `jniLibs` (installed like
  ffmpeg), plus `js_runtimes={"quickjs": {"path": ...}}` in the options, a license row for
  QuickJS, and a hashed `yt-dlp-ejs` pin with its own row (see Rules above).
- Revisit when `visionos` failures rise, not by date. The check below is how to notice.

### Manual check: the JavaScript runtime warning

Part of step 5 of the update procedure, with the debug build on a device:

1. `adb logcat -c`, then `adb logcat -s python.stderr python.stdout` while the downloads run.
2. Share one normal YouTube video and one YouTube Music track.
3. Expect both to complete as FLAC with cover and tags, and in the log only the line
   `yt-dlp: [youtube] No supported JavaScript runtime could be found…` (warnings and
   errors from yt-dlp reach logcat prefixed with `yt-dlp:`, cut to 500 characters).
4. Escalate if `visionos` player errors repeat ("This video is unavailable", `playability status:
   ERROR`) or normal public videos fail with HTTP 403.
5. Repeat on Wi-Fi and on mobile data.
