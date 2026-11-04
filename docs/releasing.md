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
- [ ] Sign it. The build signs the release variant when all four signing items below are set,
      and otherwise leaves it unsigned (`app/build/outputs/apk/release/app-release-unsigned.apk`),
      exactly as before. See "Signing" below.
- [ ] Verify the signature:
      `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
      (`apksigner` is in `$ANDROID_HOME/build-tools/<version>/`). Check that the certificate
      is the release key's, and that it is the same key as the previous release.
- [ ] Check the APK: `unzip -l <apk> | grep assets/licenses/` lists `LICENSE`,
      `components.tsv` and every file in `licenses/`; Settings > Third-party licenses opens
      on a device.

## Attach to the GitHub release

- [ ] The signed release APK.
- [ ] `ffmpeg-8.1.1.tar.xz`, the exact FFmpeg source of the bundled `ffmpeg` and `ffprobe`
      (from https://ffmpeg.org/releases/ffmpeg-8.1.1.tar.xz), and its signature
      `ffmpeg-8.1.1.tar.xz.asc`. Check the signature first, as described in
      [ffmpeg-build.md](ffmpeg-build.md).
- [ ] `scripts/build-ffmpeg.sh`, the script that built the bundled binaries from that tarball.
- [ ] A `SHA256SUMS` file covering the three files above, made from the directory that holds
      them: `sha256sum ffmpeg-8.1.1.tar.xz ffmpeg-8.1.1.tar.xz.asc build-ffmpeg.sh > SHA256SUMS`.

THIRD_PARTY_LICENSES.md and the in-app screen promise that these files are attached to every
release that ships the binaries, and that the FFmpeg source stays available on request for at
least three years after the last distribution of that version. Keep both promises.

## Signing

Android only installs an update over an existing install when both APKs are signed with the same
key. **If the key is lost, installed copies can never be upgraded**; users would have to
uninstall (and lose their queue) and install a new build. Create the key once, keep the keystore
**outside the repository**, and back it up offline (at least two copies, plus the passwords in a
password manager).

Create it, in a directory outside the repo:

```sh
keytool -genkeypair -v -keystore /path/outside/repo/sxdc-release.jks \
  -alias sxdc -keyalg RSA -keysize 4096 -validity 10000
```

The build reads four items. Each is looked up in `local.properties` (gitignored) first, then in
the environment variable:

| Item | `local.properties` key | Environment variable |
|---|---|---|
| Keystore file path (absolute, or relative to the repo root) | `sxdc.signing.storeFile` | `SXDC_KEYSTORE` |
| Keystore password | `sxdc.signing.storePassword` | `SXDC_KEYSTORE_PASSWORD` |
| Key alias | `sxdc.signing.keyAlias` | `SXDC_KEY_ALIAS` |
| Key password | `sxdc.signing.keyPassword` | `SXDC_KEY_PASSWORD` |

- None set: `assembleRelease` succeeds and writes `app-release-unsigned.apk`.
- All four set: `assembleRelease` writes a signed `app-release.apk`.
- Only some set, or the keystore file does not exist: any release task fails during
  configuration with a message naming the missing item (never a value). Debug builds are not
  affected.

Never commit a keystore, a password or a `local.properties` holding them. `.gitignore` covers
`*.jks`, `*.keystore`, `keystore.properties` and `local.properties`. The passwords are not
logged or written to build output.

**CI never has signing secrets.** The workflows build the debug variant only and define no
signing secret; releases are signed on the owner's machine.

**Never distribute a debug APK** (it contains the JaCoCo agent, EPL-2.0, which is not compatible
with the GPL; see "Build" above).
