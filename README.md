# sXdc

sXdc (synthX Downloader Companion) is a small Android app for saving audio from links you share
to it. Share a YouTube or YouTube Music link to sXdc and it:

1. downloads the audio with [yt-dlp](https://github.com/yt-dlp/yt-dlp),
2. converts it to FLAC with FFmpeg and embeds the cover art and metadata,
3. saves the file into a folder you pick once through the Android Storage Access Framework (SAF).

Downloads run in a persistent queue, so several shares (including playlists) are processed one
after another.

sXdc is a companion to [synthX](https://github.com/b1ackwall/synthX), a separate music app that
reads the folder sXdc writes to. The two apps share no code.

## Disclaimer

sXdc is only for content you have the right to download. You are responsible for complying with
the terms of service of the sites you download from and with the laws that apply where you live.
sXdc is not affiliated with or endorsed by YouTube, Google or any other content provider.

## Requirements

- Android 9 (API 28) or newer (`minSdk` 28).
- An arm64-v8a device. No other ABI is built.

## Building

The build needs a Python 3.12 interpreter for Chaquopy and a few local tools. See
[docs/linux-setup.md](docs/linux-setup.md) for setup, then:

```sh
./gradlew assembleDebug
```

Checks (lint, tests, ktlint, detekt, ruff, file size) are described in
[docs/code-quality.md](docs/code-quality.md).

## License

sXdc is licensed under the GNU General Public License v3.0 or later (GPL-3.0-or-later). See
[LICENSE](LICENSE).

The app bundles third-party components under their own licenses, including FFmpeg
(LGPL-2.1-or-later) and mutagen (GPL-2.0-or-later). See
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for the full list, the FFmpeg build
configuration and how to obtain the corresponding source.
