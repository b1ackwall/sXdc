# sXdc — Linux dev setup

## 1. Overview

sXdc is developed on two CachyOS (Arch-based) machines, both using the fish shell. There is no
Windows dev machine. A fresh `git clone` does not build on its own: the Chaquopy build-time Python
and a few local tools need to be set up first. This doc covers that, plus the gotchas that are
easy to break.

Build versions (AGP, Kotlin, Gradle, KSP, Room, Chaquopy) are listed in `CLAUDE.md`.

## 2. Prerequisites

- **JDK 17** — `jdk17-openjdk`, as the system default. `JAVA_HOME` is not required.
- **Android SDK** at `$ANDROID_HOME`, with platform 36 and build-tools installed.
- **adb** on `PATH` (from the SDK's platform-tools).

The Android NDK is not needed: there is no C/C++ source in this project, and the
`ndk { abiFilters += "arm64-v8a" }` block only tells AGP which ABI to package.

## 3. Python 3.12 for Chaquopy

Chaquopy needs a host Python of the same major.minor as the runtime version (`version = "3.12"`) to
resolve and install the pip requirements (yt-dlp, mutagen) at build time. It does not download one.
Do not change `version` to match whatever Python happens to be installed; install 3.12.

Install it with [uv](https://docs.astral.sh/uv/), then record the interpreter path in
`local.properties` (gitignored, per machine). In fish:

```fish
uv python install 3.12
set PY (uv python find 3.12)
echo "sxdc.buildPython=$PY" >> local.properties
```

`app/build.gradle.kts` resolves the interpreter in this order:

1. `local.properties` key `sxdc.buildPython`
2. env var `SXDC_BUILD_PYTHON`
3. `python3.12` on `PATH`

If the resolved value contains a `/` and the file does not exist, the build fails early with a
message pointing here.

## 4. Python dev tools

The host-side checks (ruff, pytest) run from a project-local `.venv` (gitignored). Create it once
from the repo root, then run the Python checks:

```fish
uv venv .venv --python 3.12
uv pip install --python .venv/bin/python -r requirements-dev.txt
./scripts/check.sh --python-only
```

`docs/python-tests.md` covers the tests themselves; `docs/code-quality.md` covers `check.sh`.

## 5. Fish notes

- Persistent environment variables: `set -Ux NAME value`.
- A command substitution cannot be the command itself in fish. Use `set VAR (cmd)`, then `$VAR`.
- Shell rc files are not inherited by Android Studio. That is why build configuration lives in
  `local.properties` rather than in shell config.

## 6. First build

```fish
./gradlew assembleDebug
```

The first run is slow while Chaquopy resolves and installs yt-dlp and mutagen. The wrapper
downloads Gradle itself. `local.properties` also needs `sdk.dir` (Android Studio writes it on first
open) plus the `sxdc.buildPython` key from step 3.

## 7. Troubleshooting

- **`permission denied` on `./gradlew`** — `chmod +x gradlew`.
- **Chaquopy: "not a valid Python 3.12 command"** — check the `sxdc.buildPython` key in
  `local.properties` (path exists, is a 3.12 interpreter).
- **uv's Python fails venv/pip inside Chaquopy** — install 3.12 from another source and point
  `sxdc.buildPython` (or `SXDC_BUILD_PYTHON`) at it.
- **adb: device not listed, or "no permissions"** — enable USB debugging on the phone and check
  your udev rules for the device. `unauthorized` means accept the USB-debugging prompt on the phone.

## 8. Gotchas

### ffmpeg / ffprobe are committed — nothing to fetch

```
app/src/main/jniLibs/arm64-v8a/libffmpeg.so     (~15.5 MB)
app/src/main/jniLibs/arm64-v8a/libffprobe.so    (~15.4 MB)
```

They are FFmpeg 8.1.1 arm64-v8a builds, vetted before commit. The `lib*.so` naming is deliberate.
At launch `FfmpegBinaries.install()` symlinks them into `filesDir/ffmpeg-bin/` as `ffmpeg` and
`ffprobe`, because yt-dlp resolves `ffmpeg_location` by those literal names. `keepDebugSymbols`
covers both files so the NDK strip tool never rewrites them; they are complete executables, not
libraries.

### `useLegacyPackaging = true` — DO NOT REMOVE

In `app/build.gradle.kts` under `packaging { jniLibs { ... } }`. AGP 9 defaults this to `false` for
debug and release. Without it the `.so` files stay compressed in the APK and are never extracted to
`nativeLibraryDir`. `FfmpegBinaries` then falls back to copying into `filesDir`, which Android
refuses to execute (W^X: `chmod +x` succeeds, `execve()` fails with `EACCES`). The result is silent
at build time: the app builds and installs, then every download fails on device. If ffmpeg stops
working after a build-file cleanup, check this line first. In the in-app System check panel,
`[NATIVE_SYMLINK]` is the healthy marker; `[NATIVE_COPY]` or `[ASSET_COPY]` means packaging is broken.
The panel fills in shortly after launch: its checks run once per process on a background thread,
and each row reads "checking…" until they finish.

### mutagen is a deliberate GPL dependency

yt-dlp's `EmbedThumbnail` postprocessor has no fallback for flac/ogg/opus and raises
`EmbedThumbnailPPError` if mutagen is missing. sXdc outputs FLAC with embedded thumbnails, so
mutagen is required. It is pure Python (no ABI concerns) and licensed GPL-2.0-or-later. Because it
runs in the same process as the app, sXdc as a whole is licensed GPL-3.0-or-later accordingly. See
`THIRD_PARTY_LICENSES.md`.

### Logcat tags

```fish
adb logcat -s DownloadService FfmpegBinaries SxdcSettings DownloadPreconditions
```

Notification error text is deliberately generic; the raw exception goes to logcat.
