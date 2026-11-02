# Releasing

Checklist for a public sXdc release. Only the owner creates tags and GitHub releases; nobody
else, and no automation, does.

## Before building

- [ ] `./scripts/check.sh` passes (it includes the license inventory check). See
      [code-quality.md](code-quality.md).
- [ ] The full instrumented suite passes on the S25:
      `./gradlew :app:connectedDebugAndroidTest`.
- [ ] The pins in `app/requirements-android.txt` match the yt-dlp and mutagen rows in
      `licenses/components.tsv` and `THIRD_PARTY_LICENSES.md` (`LicenseInventoryRepoTest`
      checks the first two). Bumping a pin: [dependencies.md](dependencies.md).
- [ ] `versionCode` and `versionName` in `app/build.gradle.kts` are bumped, and the sXdc row in
      `licenses/components.tsv` shows the new `versionName`.

## Build

- [ ] **Never distribute a debug APK.** The debug build is instrumented for coverage and contains
      the JaCoCo agent (EPL-2.0), which is not compatible with the GPL. It also uses the
      `.debug` applicationId.
- [ ] Build only the release variant: `./gradlew assembleRelease`.
- [ ] Sign it. **The build defines no signing config yet**, so today `assembleRelease` writes
      `app/build/outputs/apk/release/app-release-unsigned.apk`. Before the first signed release
      a signing config must be added to `app/build.gradle.kts` that reads the keystore path,
      store password, key alias and key password from `local.properties` (gitignored) or
      environment variables. Never commit a keystore, a password or a `local.properties` with
      secrets.
- [ ] Check the APK: `unzip -l <apk> | grep assets/licenses/` lists `LICENSE`,
      `components.tsv` and every file in `licenses/`; Settings > Third-party licenses opens
      on a device.

## Attach to the GitHub release

- [ ] The signed release APK.
- [ ] `ffmpeg-8.1.1.tar.xz`, the exact FFmpeg source of the bundled `ffmpeg` and `ffprobe`
      (from https://ffmpeg.org/releases/ffmpeg-8.1.1.tar.xz; check it against the upstream
      signature).
- [ ] The FFmpeg build script and configure flags: the build script of the prebuilt's source
      project and the configuration listed in `THIRD_PARTY_LICENSES.md`, "Build configuration".

THIRD_PARTY_LICENSES.md and the in-app screen promise that these files are attached to every
release that ships the binaries, and that the FFmpeg source stays available on request for at
least three years after the last distribution of that version. Keep both promises.
