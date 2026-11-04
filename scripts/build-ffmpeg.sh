#!/usr/bin/env bash
# Builds the LGPL FFmpeg 8.1.1 command-line tools (ffmpeg, ffprobe) for
# Android arm64-v8a (API 28) and writes them as libffmpeg.so / libffprobe.so
# (executables named .so so Android unpacks them from jniLibs).
#
# Usage: scripts/build-ffmpeg.sh [OUTPUT_DIR]
#
# Environment:
#   FFMPEG_BUILD_DIR  Working folder for downloads, sources and build trees.
#                     Default: a folder named ffmpeg-build next to the repo.
#   ANDROID_NDK_ROOT  Path to NDK r28c (28.2.13676358). Default search order:
#                     $FFMPEG_BUILD_DIR/ndk/android-ndk-r28c, then
#                     $ANDROID_HOME/ndk/28.2.13676358.
#
# OUTPUT_DIR defaults to $FFMPEG_BUILD_DIR/out. Nothing is written elsewhere.
# The only network access is the two ffmpeg.org downloads below, and only when
# the files are not already present and verified. See docs/ffmpeg-build.md.
set -euo pipefail

FFMPEG_VERSION="8.1.1"
FFMPEG_SHA256="b6863adde98898f42602017462871b5f6333e65aec803fdd7a6308639c52edf3"
FFMPEG_SIGNING_FPR="FCF986EA15E6E293A5644F10B4322F04D67658D8"
NDK_VERSION="28.2.13676358" # r28c
API_LEVEL="28"
SOURCE_DATE_EPOCH="1777800000"
TARBALL="ffmpeg-${FFMPEG_VERSION}.tar.xz"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${FFMPEG_BUILD_DIR:-$(dirname "$REPO_DIR")/ffmpeg-build}"
OUT_DIR="${1:-$BUILD_DIR/out}"
DL_DIR="$BUILD_DIR/downloads"
mkdir -p "$DL_DIR" "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

die() { echo "error: $*" >&2; exit 1; }

# --- tools --------------------------------------------------------------
for t in make gpg sha256sum tar xz curl; do
  command -v "$t" >/dev/null || die "missing tool: $t"
done

# --- NDK ----------------------------------------------------------------
NDK="${ANDROID_NDK_ROOT:-}"
if [ -z "$NDK" ]; then
  for c in "$BUILD_DIR/ndk/android-ndk-r28c" "${ANDROID_HOME:-/nonexistent}/ndk/$NDK_VERSION"; do
    [ -d "$c" ] && NDK="$c" && break
  done
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || die "NDK r28c ($NDK_VERSION) not found; set ANDROID_NDK_ROOT"
grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" \
  || die "NDK at $NDK is not $NDK_VERSION"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang" ] || die "NDK clang not found"

# --- download + verify (ffmpeg.org only) ---------------------------------
fetch() { # name url
  [ -s "$DL_DIR/$1" ] || curl -fsSL --retry 5 --retry-all-errors -o "$DL_DIR/$1" "$2"
}
fetch "$TARBALL" "https://ffmpeg.org/releases/$TARBALL"
fetch "$TARBALL.asc" "https://ffmpeg.org/releases/$TARBALL.asc"
fetch ffmpeg-devel.asc "https://ffmpeg.org/ffmpeg-devel.asc"

echo "$FFMPEG_SHA256  $DL_DIR/$TARBALL" | sha256sum -c - >/dev/null \
  || die "tarball SHA-256 mismatch"
GNUPGHOME="$(mktemp -d "$BUILD_DIR/gnupg.XXXXXX")"
export GNUPGHOME
chmod 700 "$GNUPGHOME"
trap 'rm -rf "$GNUPGHOME"' EXIT
gpg --batch --quiet --import "$DL_DIR/ffmpeg-devel.asc" 2>/dev/null
gpg --batch --status-fd 1 --verify "$DL_DIR/$TARBALL.asc" "$DL_DIR/$TARBALL" 2>/dev/null \
  | grep -q "^\[GNUPG:\] VALIDSIG $FFMPEG_SIGNING_FPR " \
  || die "signature verification failed"
echo "verified $TARBALL (SHA-256 and signature)"

# --- build ----------------------------------------------------------------
# Source and build trees sit side by side at fixed relative paths, so the
# compiler sees "../src/..." and no absolute path reaches the binaries.
WORK="$BUILD_DIR/work/build-$$"
rm -rf "$WORK"
mkdir -p "$WORK/src" "$WORK/build"
tar -xJf "$DL_DIR/$TARBALL" -C "$WORK/src" --strip-components=1

# Compiler wrapper: maps the real source/build paths to fixed names, so no
# absolute path reaches the binaries or the embedded configure string.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/ndk-clang" <<WRAP
#!/usr/bin/env bash
exec "$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang" \\
  -ffile-prefix-map="$WORK/src=ffmpeg" -ffile-prefix-map="$WORK/build=build" "\$@"
WRAP
chmod +x "$WORK/bin/ndk-clang"

export PATH="$WORK/bin:$TOOLCHAIN:$PATH"
export SOURCE_DATE_EPOCH LC_ALL=C TZ=UTC

cd "$WORK/build"
../src/configure \
  --prefix=/ffmpeg \
  --disable-gpl --disable-nonfree \
  --enable-static --disable-shared --enable-small \
  --disable-vulkan --enable-runtime-cpudetect --enable-gray --enable-swscale-alpha \
  --enable-ffmpeg --disable-ffplay --enable-ffprobe \
  --disable-doc \
  --enable-avdevice --enable-avcodec --enable-avformat --enable-avutil \
  --enable-swresample --enable-swscale --enable-avfilter --enable-network \
  --disable-d3d11va --disable-dxva2 --disable-vaapi --disable-vdpau \
  --disable-videotoolbox \
  --enable-encoders --enable-decoders --enable-hwaccels --enable-muxers \
  --enable-demuxers --enable-parsers --enable-bsfs --enable-protocols \
  --enable-filters --enable-iconv --disable-audiotoolbox --enable-linux-perf \
  --disable-bzlib --enable-jni --enable-mediacodec \
  --enable-decoder=h264_mediacodec --enable-decoder=hevc_mediacodec \
  --enable-decoder=mpeg4_mediacodec \
  --target-os=android --arch=aarch64 --cpu=armv8-a \
  --cc=ndk-clang \
  --cxx="aarch64-linux-android${API_LEVEL}-clang++" \
  --ar=llvm-ar --nm=llvm-nm --ranlib=llvm-ranlib --strip=llvm-strip \
  --enable-cross-compile \
  --extra-cflags="-Os -fpic -march=armv8-a" \
  --extra-ldflags="-Wl,--build-id=none"

make -j"$(nproc)" ffmpeg ffprobe

llvm-strip --strip-all -o "$OUT_DIR/libffmpeg.so" ffmpeg
llvm-strip --strip-all -o "$OUT_DIR/libffprobe.so" ffprobe
cd "$BUILD_DIR"
rm -rf "$WORK"

sha256sum "$OUT_DIR/libffmpeg.so" "$OUT_DIR/libffprobe.so"
