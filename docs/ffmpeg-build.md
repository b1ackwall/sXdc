# Building FFmpeg

`app/src/main/jniLibs/arm64-v8a/libffmpeg.so` and `libffprobe.so` are the `ffmpeg` and `ffprobe`
executables of FFmpeg 8.1.1 (LGPL-2.1-or-later), built from the unmodified upstream tarball by
[`scripts/build-ffmpeg.sh`](../scripts/build-ffmpeg.sh). They are named `.so` only so Android
unpacks them from `jniLibs`. License details: [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md).

## Toolchain

| Item | Value |
|---|---|
| Source | `ffmpeg-8.1.1.tar.xz` from https://ffmpeg.org/releases/ |
| Tarball SHA-256 | `b6863adde98898f42602017462871b5f6333e65aec803fdd7a6308639c52edf3` |
| Signing key | FFmpeg release signing key, fingerprint `FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8` |
| NDK | r28c, `28.2.13676358` (Linux x86_64 zip, SHA-1 `a7b54a5de87fecd125a17d54f73c446199e72a64`) |
| Target | Android API 28, arm64-v8a (aarch64, armv8-a) |
| Host | Linux x86_64 with `bash`, `make`, `gpg`, `curl`, `tar`, `xz`, `sha256sum` |

The NDK is not part of the repo. Download `android-ndk-r28c-linux.zip` from
https://dl.google.com/android/repository/, check it against the SHA-1 listed on
https://developer.android.com/ndk/downloads/older_releases, and unpack it to
`<folder next to the repo>/ffmpeg-build/ndk/android-ndk-r28c`, or point `ANDROID_NDK_ROOT` at it
(an `$ANDROID_HOME/ndk/28.2.13676358` install also works).

## Rebuild

```sh
scripts/build-ffmpeg.sh [OUTPUT_DIR]
```

- Works from any checkout path. Downloads, sources and build trees go in `$FFMPEG_BUILD_DIR`
  (default: a folder named `ffmpeg-build` next to the repo). Nothing else on the machine is touched.
- `OUTPUT_DIR` (default `$FFMPEG_BUILD_DIR/out`) receives `libffmpeg.so` and `libffprobe.so`.
- The only network access is the download of the tarball, its `.asc` and the signing key from
  ffmpeg.org, skipped when already present. The script checks the SHA-256 and the signature and
  stops on a mismatch.
- To use the result, copy both files to `app/src/main/jniLibs/arm64-v8a/`, update the SHA-256
  values in `THIRD_PARTY_LICENSES.md`, and rebuild the APK.

The output is reproducible: two clean builds with the same script, tarball and NDK give
byte-identical files. The script achieves that with a fixed `SOURCE_DATE_EPOCH`, a neutral
`--prefix=/ffmpeg`, a compiler wrapper that maps the real source and build paths to fixed names
(`-ffile-prefix-map`), `-Wl,--build-id=none` and `llvm-strip --strip-all`.

## Verify the signature by hand

```sh
curl -O https://ffmpeg.org/releases/ffmpeg-8.1.1.tar.xz
curl -O https://ffmpeg.org/releases/ffmpeg-8.1.1.tar.xz.asc
curl -O https://ffmpeg.org/ffmpeg-devel.asc
gpg --show-keys --with-fingerprint ffmpeg-devel.asc   # compare the fingerprint above
gpg --import ffmpeg-devel.asc
gpg --verify ffmpeg-8.1.1.tar.xz.asc ffmpeg-8.1.1.tar.xz
sha256sum ffmpeg-8.1.1.tar.xz
```

gpg prints a "not certified" warning because the key is not in your web of trust; the fingerprint
comparison is the check that matters. Use a throwaway `GNUPGHOME` to keep your keyring clean.

## Differences from the previous prebuilt

The binaries before this build came from a third party's prebuilt FFmpeg 8.1.1. The new ones
differ as follows:

- Built on a Linux host instead of macOS.
- `--disable-doc` and `--disable-ffplay` (the old build enabled docs and ffplay; neither is
  shipped or used).
- Neutral `--prefix=/ffmpeg` and no builder paths; the NDK tools are found through `PATH`.
- Stripped with the NDK's `llvm-strip`, with no build id.

The configuration of components is the same (the `ffmpeg -codecs/-encoders/-decoders/-formats/
-muxers/-demuxers/-protocols/-filters/-bsfs/-hwaccels/-pix_fmts` listings are identical to the
old binaries). Byte-identical output with the old binaries is not expected.

## What is attached to a release

Every release that ships the binaries has these assets next to the APK (see
[releasing.md](releasing.md)):

- `ffmpeg-8.1.1.tar.xz`, the exact source
- `ffmpeg-8.1.1.tar.xz.asc`, its upstream signature
- `build-ffmpeg.sh`, a copy of `scripts/build-ffmpeg.sh`
- `SHA256SUMS`, checksums of the three files above
