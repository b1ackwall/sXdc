# Third-party licenses

sXdc is licensed under GPL-3.0-or-later (see [LICENSE](LICENSE)). The app bundles the
components below, each under its own license. All of them are compatible with distributing the
app under GPL-3.0-or-later.

Versions are taken from the build files (`app/build.gradle.kts`, `gradle/libs.versions.toml`).
Components pulled in transitively are listed by `./gradlew :app:dependencies`. Where a version
cannot be read from this repository, the entry says "see upstream".

## Summary

| Component | Version | License (SPDX) | Project | How it is used |
|---|---|---|---|---|
| FFmpeg (`ffmpeg`, `ffprobe`) | 8.1.1 | LGPL-2.1-or-later | https://ffmpeg.org | Prebuilt executables in `app/src/main/jniLibs/arm64-v8a/`, run as separate processes. Details below. |
| mutagen | resolved at build time (unpinned) | GPL-2.0-or-later | https://github.com/quodlibet/mutagen | Python package installed by Chaquopy pip. Used in-process by yt-dlp to embed cover art in FLAC. Text: [licenses/mutagen-GPL-2.0.txt](licenses/mutagen-GPL-2.0.txt). |
| yt-dlp | resolved at build time (unpinned) | Unlicense | https://github.com/yt-dlp/yt-dlp | Python package installed by Chaquopy pip. Downloads and post-processes audio. |
| CPython | 3.12 | PSF-2.0 | https://www.python.org | Python runtime bundled by Chaquopy. |
| Chaquopy | 17.0.0 | MIT | https://github.com/chaquo/chaquopy | Gradle plugin and runtime that embeds Python in the app. |
| OpenSSL | 3.0.x, as shipped by Chaquopy | Apache-2.0 | https://www.openssl.org | Used by the bundled Python runtime (`ssl`, `hashlib`). |
| SQLite | see upstream | Public domain (SPDX: blessing) | https://sqlite.org | Used by the bundled Python runtime (`sqlite3`). |
| libffi | see upstream | MIT | https://github.com/libffi/libffi | Linked into the bundled Python runtime (`ctypes`). |
| xz / liblzma | see upstream | 0BSD | https://tukaani.org/xz/ | Linked into the bundled Python runtime (`lzma`). |
| bzip2 | see upstream | bzip2-1.0.6 | https://sourceware.org/bzip2/ | Linked into the bundled Python runtime (`bz2`). |
| AndroidX: Core 1.18.0, Activity Compose 1.13.0, Lifecycle 2.10.0, DocumentFile 1.1.0, Room 2.8.4, Compose (BOM 2024.09.00: UI, Material 3, Material Icons Core) and their transitive AndroidX modules | as listed | Apache-2.0 | https://developer.android.com/jetpack/androidx | App UI, lifecycle, SAF access and the persistent download queue. |
| Kotlin standard library | 2.2.10 (AGP built-in Kotlin) | Apache-2.0 | https://github.com/JetBrains/kotlin | Kotlin runtime. |
| kotlinx.coroutines | transitive, see `./gradlew :app:dependencies` | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines | Background work and the download queue. |
| kotlinx.serialization | transitive, see `./gradlew :app:dependencies` | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization | Transitive dependency of AndroidX. |
| JetBrains Java Annotations | transitive, see `./gradlew :app:dependencies` | Apache-2.0 | https://github.com/JetBrains/java-annotations | Transitive dependency (nullability annotations). |
| JSpecify | transitive, see `./gradlew :app:dependencies` | Apache-2.0 | https://jspecify.dev | Transitive dependency (nullability annotations). |
| Guava ListenableFuture | transitive, see `./gradlew :app:dependencies` | Apache-2.0 | https://github.com/google/guava | Transitive dependency of AndroidX. |
| Outfit font (variable) | see upstream | OFL-1.1 | https://github.com/Outfitio/Outfit-Fonts | App typeface, `app/src/main/res/font/outfit_variable.ttf`. Text: [licenses/Outfit-OFL.txt](licenses/Outfit-OFL.txt). |
| Material Icons | see upstream | Apache-2.0 | https://github.com/google/material-design-icons | Shapes of the notification icons `ic_stat_done`, `ic_stat_download`, `ic_stat_error` (check, file download, error). |

yt-dlp and mutagen are installed by Chaquopy's `pip` block without a version pin, so the version
is whatever pip resolves at build time. The exact versions are the ones inside the built APK
(`assets/chaquopy/requirements-common.imy`, `*.dist-info` directories).

License texts not included in this repository are available from the project URLs above and at
https://spdx.org/licenses/.

## FFmpeg and ffprobe

`app/src/main/jniLibs/arm64-v8a/libffmpeg.so` and `libffprobe.so` are the `ffmpeg` and `ffprobe`
command-line executables of **FFmpeg 8.1.1**, licensed under **LGPL-2.1-or-later**. They are
named `lib*.so` only so that Android extracts them to the app's native library directory; they
are complete executables, not libraries.

- **Unmodified upstream build.** They are unmodified prebuilts of upstream FFmpeg 8.1.1, built
  with `--disable-gpl --disable-nonfree`. No external codec libraries are enabled (no
  `--enable-lib*` options). They link only against Android system libraries.
- **Separate processes.** sXdc does not link against FFmpeg. yt-dlp runs `ffmpeg` and `ffprobe`
  as separate processes.
- **Provenance.** Taken from
  [hzw1199/Android-FFmpeg-Prebuilt](https://github.com/hzw1199/Android-FFmpeg-Prebuilt),
  directory `ffmpeg-8.1.1/bin` (byte-identical to the files there). Built by that project with
  Android NDK r28c for Android API level 28, arm64-v8a.
- **SHA-256**
  - `libffmpeg.so`: `480defd776199b5434008565974de5bf99822371c3cd9c1f130fda2c57de6439`
  - `libffprobe.so`: `e66d481eb55af07282ea4efcdba5d18b564f809e9e40441dede7cc6ee9f88224`

### Build configuration

The configuration embedded in both binaries (shown by `ffmpeg -version` as `configuration:`),
with the builder's local file-system paths replaced by `<redacted>`:

```
--prefix=<redacted> --disable-gpl --disable-nonfree --enable-static --disable-shared --enable-small --disable-vulkan --enable-runtime-cpudetect --enable-gray --enable-swscale-alpha --enable-ffmpeg --enable-ffplay --enable-ffprobe --enable-doc --enable-htmlpages --enable-manpages --enable-podpages --enable-txtpages --enable-avdevice --enable-avcodec --enable-avformat --enable-avutil --enable-swresample --enable-swscale --enable-avfilter --enable-network --disable-d3d11va --disable-dxva2 --disable-vaapi --disable-vdpau --disable-videotoolbox --enable-encoders --enable-decoders --enable-hwaccels --enable-muxers --enable-demuxers --enable-parsers --enable-bsfs --enable-protocols --enable-filters --enable-iconv --disable-audiotoolbox --enable-linux-perf --disable-bzlib --enable-jni --enable-mediacodec --enable-decoder=h264_mediacodec --enable-decoder=hevc_mediacodec --enable-decoder=mpeg4_mediacodec --target-os=android --arch=aarch64 --cpu=armv8-a --cc=<redacted> --cxx=<redacted> --ar=<redacted> --nm=<redacted> --ranlib=<redacted> --strip=<redacted> --enable-cross-compile --sysroot=<redacted> --extra-cflags='-Os -fpic -march=armv8-a' --extra-ldflags=
```

The redacted `--cc`, `--cxx`, `--ar`, `--nm`, `--ranlib`, `--strip` and `--sysroot` values point
to the standard Android NDK r28c LLVM toolchain (`aarch64-linux-android28-clang` and the matching
LLVM tools and sysroot).

### Corresponding source

- Release tarball: https://ffmpeg.org/releases/ffmpeg-8.1.1.tar.xz
- Git: tag `n8.1.1` of https://git.ffmpeg.org/ffmpeg.git

**Written offer.** On request, the maintainer of sXdc will provide the complete corresponding
source code of the FFmpeg build distributed with sXdc, for as long as these binaries are
distributed. To request it, open an issue on this repository.

### License text

The full LGPL-2.1 text is included as
[licenses/FFmpeg-LGPL-2.1.txt](licenses/FFmpeg-LGPL-2.1.txt) (FFmpeg's own `COPYING.LGPLv2.1`).

## mutagen

mutagen is licensed under GPL-2.0-or-later. It runs in the same process as the app, which makes
sXdc a combined work; sXdc is therefore distributed under GPL-3.0-or-later. The GPL-2.0 text, as
shipped in mutagen's own package, is included as
[licenses/mutagen-GPL-2.0.txt](licenses/mutagen-GPL-2.0.txt). Source:
https://github.com/quodlibet/mutagen and https://pypi.org/project/mutagen/.
