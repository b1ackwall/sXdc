# Third-party licenses

sXdc is licensed under GPL-3.0-or-later (see [LICENSE](LICENSE)). The app bundles the
components below, each under its own license. All of them are compatible with distributing the
app under GPL-3.0-or-later.

The machine-readable list is [licenses/components.tsv](licenses/components.tsv). The app shows
it, with every license text, under Settings > Third-party licenses. The texts live in
[licenses/](licenses/) and are copied into the APK at build time (`assets/licenses/`).

Versions are the ones in the built release APK. Python packages are hash-pinned in
`app/requirements-android.txt`; Maven versions come from
`./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath`; the Python runtime and
its native libraries are the ones Chaquopy 17.0.0 ships for Python 3.12.12.

## Summary

| Component | Version | License (SPDX) | Project | How it is used | Text |
|---|---|---|---|---|---|
| FFmpeg (`ffmpeg`, `ffprobe`) | 8.1.1 | LGPL-2.1-or-later | https://ffmpeg.org | Prebuilt executables in `app/src/main/jniLibs/arm64-v8a/`, run as separate processes. Details below. | [FFmpeg-LGPL-2.1.txt](licenses/FFmpeg-LGPL-2.1.txt) |
| mutagen | 1.48.1 | GPL-2.0-or-later | https://github.com/quodlibet/mutagen | Python package. Used in-process by yt-dlp to embed cover art in FLAC. | [mutagen-GPL-2.0.txt](licenses/mutagen-GPL-2.0.txt) |
| yt-dlp | 2026.10.26 | Unlicense | https://github.com/yt-dlp/yt-dlp | Python package. Downloads and post-processes audio. | [yt-dlp-Unlicense.txt](licenses/yt-dlp-Unlicense.txt) |
| CPython | 3.12.12 | PSF-2.0 | https://www.python.org | Python runtime bundled by Chaquopy. The text includes the licenses of software incorporated into Python. | [CPython-LICENSE.txt](licenses/CPython-LICENSE.txt) |
| Chaquopy runtime | 17.0.0 | MIT | https://chaquo.com/chaquopy/ | Embeds Python in the app. | [Chaquopy-LICENSE.txt](licenses/Chaquopy-LICENSE.txt) |
| OpenSSL | 3.0.18 | Apache-2.0 | https://www.openssl.org | Used by the Python runtime (`ssl`, `hashlib`). | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| SQLite | 3.50.4 | blessing (public domain) | https://sqlite.org | Used by the Python runtime (`sqlite3`). | [SQLite-LICENSE.md](licenses/SQLite-LICENSE.md) |
| xz / liblzma | 5.4.6 | public domain (liblzma in this version) | https://tukaani.org/xz/ | Used by the Python runtime (`lzma`). | [xz-COPYING.txt](licenses/xz-COPYING.txt) |
| bzip2 | 1.0.8 | bzip2-1.0.6 | https://sourceware.org/bzip2/ | Used by the Python runtime (`bz2`). | [bzip2-LICENSE.txt](licenses/bzip2-LICENSE.txt) |
| libffi | 3.4.4 | MIT | https://github.com/libffi/libffi | Used by the Python runtime (`ctypes`). | [libffi-LICENSE.txt](licenses/libffi-LICENSE.txt) |
| setuptools (`pkg_resources`) | 68.2.2 | MIT | https://github.com/pypa/setuptools | Part of the Chaquopy runtime. | [setuptools-LICENSE.txt](licenses/setuptools-LICENSE.txt) |
| importlib_resources | 5.10.2 | Apache-2.0 | https://github.com/python/importlib_resources | Vendored by setuptools. | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| jaraco.context, jaraco.functools, jaraco.text | 4.3.0, 3.6.0, 3.7.0 | MIT | https://github.com/jaraco | Vendored by setuptools. | [jaraco-zipp-LICENSE.txt](licenses/jaraco-zipp-LICENSE.txt) |
| more_itertools | 9.1.0 | MIT | https://github.com/more-itertools/more-itertools | Vendored by setuptools. | [more-itertools-LICENSE.txt](licenses/more-itertools-LICENSE.txt) |
| packaging | 23.1 | Apache-2.0 OR BSD-2-Clause | https://github.com/pypa/packaging | Vendored by setuptools. | [packaging-LICENSE.BSD.txt](licenses/packaging-LICENSE.BSD.txt), [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| platformdirs | 2.6.2 | MIT | https://github.com/platformdirs/platformdirs | Vendored by setuptools. | [platformdirs-LICENSE.txt](licenses/platformdirs-LICENSE.txt) |
| typing_extensions | 4.4.0 | PSF-2.0 | https://github.com/python/typing_extensions | Vendored by setuptools. | [typing-extensions-LICENSE.txt](licenses/typing-extensions-LICENSE.txt) |
| zipp | 3.7.0 | MIT | https://github.com/jaraco/zipp | Vendored by setuptools. | [jaraco-zipp-LICENSE.txt](licenses/jaraco-zipp-LICENSE.txt) |
| pyelftools | 0.26 | Unlicense (public domain) | https://github.com/eliben/pyelftools | Part of the Chaquopy runtime. | [pyelftools-LICENSE.txt](licenses/pyelftools-LICENSE.txt) |
| construct (pyelftools fork) | 2.06 | MIT | https://github.com/eliben/pyelftools | Vendored by pyelftools. | [construct-LICENSE.txt](licenses/construct-LICENSE.txt) |
| CA certificate bundle (certifi `cacert.pem`) | 2025.08.03 | MPL-2.0 | https://github.com/certifi/python-certifi | Shipped unmodified by Chaquopy as `assets/chaquopy/cacert.pem`; Mozilla's root certificate data. | [certifi-LICENSE.txt](licenses/certifi-LICENSE.txt), [MPL-2.0.txt](licenses/MPL-2.0.txt) |
| AndroidX and Jetpack Compose: Activity 1.13.0, Annotation 1.9.1, Arch Core 2.2.0, Autofill 1.0.0, Collection 1.5.0, Compose BOM 2024.09.00 (Animation, Foundation, Material 1.7.0; Material 3 1.3.0; Runtime, UI 1.9.2), Concurrent 1.1.0, Core 1.18.0, CustomView PoolingContainer 1.0.0, DocumentFile 1.1.0, Emoji2 1.4.0, Graphics Path 1.0.1, Interpolator 1.0.0, Lifecycle 2.10.0, NavigationEvent 1.0.0, ProfileInstaller 1.4.0, Room 2.8.4, SavedState 1.4.0, SQLite 2.6.2, Startup 1.1.1, Tracing 1.2.0, VersionedParcelable 1.1.1 | as listed | Apache-2.0 | https://developer.android.com/jetpack/androidx | App UI, lifecycle, SAF access and the persistent download queue. Exact per-artifact versions in `licenses/components.tsv`. | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| Kotlin standard library | 2.2.10 | Apache-2.0 | https://github.com/JetBrains/kotlin | Kotlin runtime. | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines | Background work and the download queue. | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| kotlinx.serialization | 1.7.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization | Transitive dependency of AndroidX. | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| JetBrains Java Annotations | 23.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations | Transitive dependency (nullability annotations). | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| JSpecify | 1.0.0 | Apache-2.0 | https://jspecify.dev | Transitive dependency (nullability annotations). | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| Guava ListenableFuture | 1.0 | Apache-2.0 | https://github.com/google/guava | Transitive dependency of AndroidX. | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| Outfit font (variable) | 1.100 | OFL-1.1 | https://github.com/Outfitio/Outfit-Fonts | App typeface, `app/src/main/res/font/outfit_variable.ttf`. | [Outfit-OFL.txt](licenses/Outfit-OFL.txt) |
| Material Icons | n/a | Apache-2.0 | https://github.com/google/material-design-icons | Shapes of the notification icons `ic_stat_done`, `ic_stat_download`, `ic_stat_error` (check, file download, error). | [Apache-2.0.txt](licenses/Apache-2.0.txt) |
| Launcher icon | n/a | not asserted | | Android Studio project template; template, to be replaced. | |

Only yt-dlp and mutagen are installed from PyPI. yt-dlp's optional extras (certifi, brotli,
websockets, pycryptodomex, yt-dlp-ejs and others) are not installed; see
[docs/dependencies.md](docs/dependencies.md).

### Debug builds

The debug APK is instrumented for test coverage and includes the JaCoCo agent runtime
(EPL-2.0), which is not compatible with the GPL. **Never distribute a debug APK.** Only the
release variant is distributed; it contains no JaCoCo code. See
[docs/releasing.md](docs/releasing.md).

## Where each license text comes from

Every file in `licenses/` is an unmodified copy:

| File | Copied from |
|---|---|
| `FFmpeg-LGPL-2.1.txt` | FFmpeg's own `COPYING.LGPLv2.1` |
| `mutagen-GPL-2.0.txt` | `mutagen-1.48.1.dist-info/licenses/COPYING` in the built APK |
| `yt-dlp-Unlicense.txt` | `yt_dlp-2026.10.26.dist-info/licenses/LICENSE` in the built APK |
| `Outfit-OFL.txt` | The Outfit project's `OFL.txt` |
| `Apache-2.0.txt` | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `CPython-LICENSE.txt` | https://raw.githubusercontent.com/python/cpython/v3.12.12/LICENSE |
| `Chaquopy-LICENSE.txt` | https://raw.githubusercontent.com/chaquo/chaquopy/17.0.0/LICENSE.txt |
| `setuptools-LICENSE.txt` | `setuptools-68.2.2.dist-info/LICENSE` in the built APK (identical to the upstream v68.2.2 `LICENSE`) |
| `libffi-LICENSE.txt` | https://raw.githubusercontent.com/libffi/libffi/v3.4.4/LICENSE |
| `xz-COPYING.txt` | https://raw.githubusercontent.com/tukaani-project/xz/v5.4.6/COPYING |
| `bzip2-LICENSE.txt` | https://sourceware.org/git/?p=bzip2.git;a=blob_plain;f=LICENSE;hb=bzip2-1.0.8 |
| `SQLite-LICENSE.md` | https://sqlite.org/src/raw?name=LICENSE.md&ci=version-3.50.4 |
| `MPL-2.0.txt` | https://www.mozilla.org/media/MPL/2.0/index.txt |
| `certifi-LICENSE.txt` | https://raw.githubusercontent.com/certifi/python-certifi/2025.08.03/LICENSE |
| `pyelftools-LICENSE.txt` | `pyelftools-0.26.dist-info/LICENSE.txt` in the built APK (identical to the upstream v0.26 `LICENSE`) |
| `construct-LICENSE.txt` | https://raw.githubusercontent.com/eliben/pyelftools/v0.26/elftools/construct/LICENSE |
| `jaraco-zipp-LICENSE.txt` | https://raw.githubusercontent.com/pypa/setuptools/v68.2.2/pkg_resources/_vendor/jaraco.context-4.3.0.dist-info/LICENSE (byte-identical to the jaraco.functools 3.6.0, jaraco.text 3.7.0 and zipp 3.7.0 files there) |
| `more-itertools-LICENSE.txt` | https://raw.githubusercontent.com/pypa/setuptools/v68.2.2/pkg_resources/_vendor/more_itertools-9.1.0.dist-info/LICENSE |
| `packaging-LICENSE.BSD.txt` | https://raw.githubusercontent.com/pypa/packaging/23.1/LICENSE.BSD |
| `platformdirs-LICENSE.txt` | https://raw.githubusercontent.com/pypa/setuptools/v68.2.2/pkg_resources/_vendor/platformdirs-2.6.2.dist-info/licenses/LICENSE |
| `typing-extensions-LICENSE.txt` | https://raw.githubusercontent.com/pypa/setuptools/v68.2.2/pkg_resources/_vendor/typing_extensions-4.4.0.dist-info/LICENSE |

The CA bundle in the APK is byte-identical to `certifi/cacert.pem` at certifi tag 2025.08.03
and to `cacert.pem` in the Chaquopy 17.0.0 Gradle plugin.

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

- **Attached to every release.** Every sXdc GitHub release that ships these binaries has the
  exact source tarball `ffmpeg-8.1.1.tar.xz` and the build script and configure flags above
  attached next to the APK, so the source is available from the same place as the binaries.
- Upstream copies: https://ffmpeg.org/releases/ffmpeg-8.1.1.tar.xz and tag `n8.1.1` of
  https://git.ffmpeg.org/ffmpeg.git.

**Written offer.** For at least three years after the last distribution of a given sXdc version,
the maintainer of sXdc will provide, on request, the complete corresponding source code of the
FFmpeg build distributed with that version. To request it, open an issue on this repository.

## mutagen and yt-dlp

mutagen is licensed under GPL-2.0-or-later. It runs in the same process as the app, which makes
sXdc a combined work; sXdc is therefore distributed under GPL-3.0-or-later. The APK carries
mutagen and yt-dlp as compiled Python bytecode. Their corresponding source is the exact pinned
release on PyPI:

- mutagen 1.48.1: https://pypi.org/project/mutagen/1.48.1/#files (source
  `mutagen-1.48.1.tar.gz`, sha256
  `8f95637ab9f6f305cec6bd1294e197debe207998e3e068596563c74f86b0a173`; wheel
  `mutagen-1.48.1-py3-none-any.whl`, sha256
  `4f077fe87d3fc7fba259aa63d8c026b18382ca6a42ef37c61e16f1b1b5b82fe7`). Project:
  https://github.com/quodlibet/mutagen.
- yt-dlp 2026.10.26: https://pypi.org/project/yt-dlp/2026.10.26/#files (source
  `yt_dlp-2026.10.26.tar.gz`, sha256
  `abe5c6b94c3b2f0560500add1c42e0a97641752743a5b65588ac2007a6e23eb6`; wheel
  `yt_dlp-2026.10.26-py3-none-any.whl`, sha256
  `a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae`). Project:
  https://github.com/yt-dlp/yt-dlp.

The source of sXdc itself, including its Python code, is at https://github.com/b1ackwall/sXdc.
