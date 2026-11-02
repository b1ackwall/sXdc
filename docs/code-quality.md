# Code quality

One script runs every check. Each tool can also be run on its own. Nothing here formats or
auto-fixes code. Run fixers only by hand, and only when you mean to.

## Run everything

From any directory, in fish or bash:

```
./scripts/check.sh                 # everything
./scripts/check.sh --python-only   # file size, ruff, pytest (seconds)
./scripts/check.sh --android-only  # Gradle lint/test/build, ktlint, detekt
./scripts/check.sh --help
```

Every selected step runs even if an earlier one fails. Then a summary table is printed. The exit
code is non-zero only if an ENFORCED step failed. `PYTHON=/path/to/python` overrides the
interpreter (default `.venv/bin/python`; set it up per `docs/python-tests.md`). The script
never installs anything.

## The tools

| Tool | Severity | Config | Report |
|---|---|---|---|
| File size (500 lines) | ENFORCED | `scripts/check_file_size.py`, `scripts/file-size-ignore.txt` | stdout |
| ruff check | ENFORCED | `ruff.toml` | stdout |
| ruff format --check | REPORT-ONLY | `ruff.toml` | stdout (count) |
| pytest | ENFORCED | `pytest.ini`, `scripts/known-failures.txt` | stdout |
| Android Lint | ENFORCED (errors) | `lint {}` in `app/build.gradle.kts`, `app/lint-baseline.xml` | `app/build/reports/lint-results-debug.html` |
| JVM unit tests, assembleDebug | ENFORCED | `app/build.gradle.kts` | `app/build/reports/tests/` |
| ktlint 1.8.0 (Gradle plugin 14.2.0) | REPORT-ONLY | `.editorconfig`, `ktlint {}` in both build files | `app/build/reports/ktlint/`, `build/reports/ktlint/` |
| detekt 2.0.0-alpha.6 | REPORT-ONLY | `detekt {}` in `app/build.gradle.kts`, `config/detekt/` | `app/build/reports/detekt/detekt.html` |

ktlint and detekt are report-only for now: their findings never fail a build. detekt is an alpha
release, the only line built for AGP 9 / Kotlin 2.2+.

Shared style settings (charset, final newline, indent, `max_line_length = 120` for Kotlin, the
ktlint code style and its Composable exception) live in `.editorconfig`.

## Each tool on its own

```
.venv/bin/python scripts/check_file_size.py [--limit N] [--warn N]
.venv/bin/ruff check app/src/main/python tests/python scripts
.venv/bin/ruff format --check app/src/main/python tests/python scripts
PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -q
./gradlew lintDebug
./gradlew testDebugUnitTest assembleDebug
./gradlew ktlintCheck        # app sources, app/*.kts and the root .kts files
./gradlew :app:detekt        # hand-written main, test and androidTest sources
```

detekt also creates type-resolution tasks (`detektMain`, `detektDebug`, ...). They are not used:
under AGP built-in Kotlin they cannot resolve generated classes (detekt issue #9402).

## Android Lint details

- `abortOnError = true`: any new lint error fails the build. Warnings do not.
- `GradleDependency`, `NewerVersionAvailable` and `AndroidGradlePluginVersion` are set to
  informational. They depend on the network and the date. They show in the report as hints but
  never fail a build. They are deliberately left out of the baseline so they stay visible.
- Every other existing warning is in `app/lint-baseline.xml`.

## Regenerating baselines

Only regenerate a baseline on purpose, for example after fixing findings. Review the diff.

- Lint: `./gradlew updateLintBaselineDebug`. Then remove the three informational ids above from
  the regenerated `app/lint-baseline.xml` (lint writes them in too).
- detekt: `./gradlew :app:detektBaseline` writes `config/detekt/baseline.xml`. Paths in it are
  relative to the repo root (`basePath`).
- ktlint has no baseline: it is report-only.
- ruff: existing findings are absorbed by `[lint.per-file-ignores]` in `ruff.toml`. Remove an
  entry once its file is clean.

## Known failing tests

`scripts/known-failures.txt` lists pytest node ids that fail because of an open production bug,
each with a pointer to its GitHub issue. `check.sh` passes them to pytest as `--deselect` and
prints how many it skipped. The tests stay in the repo and still fail when pytest runs directly.
Remove the entry when the bug is fixed.

## CI

`.github/workflows/ci.yml` (GitHub Actions) runs on every push to `main`, and on demand.
There is no pull-request trigger. Both jobs call `scripts/check.sh`, so CI and local runs match.

- **python**: Python 3.12 and `requirements-dev.txt`, then `./scripts/check.sh --python-only`.
  This runs the file-size check, ruff check, ruff format --check (report-only) and pytest.
- **android**: JDK 17, Python 3.12 as the Chaquopy build interpreter (`SXDC_BUILD_PYTHON`),
  SDK platform 36 / build-tools 36.0.0, then `./scripts/check.sh --android-only`. This runs
  lintDebug, testDebugUnitTest, assembleDebug, and report-only ktlint and detekt.

A job fails when check.sh exits non-zero, i.e. when an ENFORCED step fails. ktlint findings,
detekt findings and the ruff format count never fail a run.

Reports: the android job uploads an `android-reports` artifact on every run, kept 7 days. It has
the lint HTML, ktlint, detekt and unit-test reports. Download it from the run page in the
Actions tab.

Manual run on any branch: Actions tab → CI → "Run workflow", or
`gh workflow run ci.yml --ref <branch>`.

Instrumented (`connected*`) tests need a device and are not run in CI.

They run inside a test application, not the app's own. `SxdcTestRunner` (androidTest) swaps in
`TestSxdcApp`, which:

- uses its own database, `sxdc-test.db`, deleted at process start, so tests never read or write
  the install's real jobs. `TestDatabaseRule` empties it before each screen or service test, and
  refuses to run under any other application;
- uses its own settings file, `sxdc.settings.test`, deleted at process start, so tests never read
  or change the install's settings. No destination folder is configured unless a test sets one;
  `TestSettingsRule` empties the file before each test that does, and refuses to run under any
  other application;
- stages downloads in its own folder, `sxdc-test-staging` inside the install's staging folder,
  emptied at process start, so a service run never sweeps the install's staged files;
- skips the startup recovery pass, which would otherwise act on the install's real jobs;
- never asks for the notification permission, so no system dialog covers a screen test, whether
  or not the permission is granted.

Python start and notification channels run as usual. With no destination folder configured, a
service run never touches the install's chosen folder.
`NotificationsTest` grants POST_NOTIFICATIONS to the debug package before each of its tests and
never revokes it: revoking a runtime permission kills the app process, and with it the run.

`DownloadServiceFullDownloadTest` is the service-level test: it shares a URL on a local HTTP
server to `ShareReceiverActivity` and follows it through the real `DownloadService`, yt-dlp,
ffmpeg, SAF delivery into a test `DocumentsProvider` and the terminal notification, so it proves
the whole chain on a device without network access or real data. It cannot prove real providers,
real YouTube, or the 6-hour dataSync `onTimeout`, which stays a manual check (#61).

`./gradlew :app:connectedDebugAndroidTest` installs the debug app and the test app, runs the
tests, and then uninstalls both. That wipes the debug app's data and keeps every run a fresh
install. To keep both apps installed after a run, add
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` on the command line only, never
in `gradle.properties`; the next run then inherits the debug app's data and permissions.

Coverage data (`coverage.ec`) is only produced by a device run. `createDebugAndroidTestCoverageReport`
depends on the device tests, so on its own it reruns them, and it is skipped when a test fails.
Build the report from the last run's data instead:

```bash
./gradlew :app:createDebugAndroidTestCoverageReport -x connectedDebugAndroidTest
```

Output: `app/build/reports/coverage/androidTest/debug/connected/index.html`. A clean build removes
the data. Whether a failed device run still writes `coverage.ec` has not been verified.

## The 500-line rule

No tracked file may exceed 500 lines (see `CLAUDE.md`). `scripts/check_file_size.py` reads
`git ls-files`. It fails above 500 lines and warns above 450. It skips binary files, lock files,
jars and generated `*baseline*.xml` files. Agreed exceptions go in
`scripts/file-size-ignore.txt`, one path glob per line. Its tests are in
`tests/python/test_check_file_size.py`.

## Color palette

Defined in `ui/theme/Color.kt`; dark only, no dynamic color. Opaque ARGB.

| Token | Hex | Role |
|---|---|---|
| Accent / OnAccent | 52C9E0 / 084C61 | brand accent; text on it |
| Background | 1D0C14 | app background |
| Surface | 321523 | cards, raised panels |
| SurfaceVariant | 492738 | inputs, inset blocks |
| TextPrimary / Secondary / Tertiary | F7ECDE / EFD9BD / E0C6A3 | main, secondary, hints/disabled |
| Success / Error | 3CDD57 / FA3838 | state colors |

M3 mapping (`Theme.kt` sets every slot): primary/secondary = Accent (on = OnAccent); tertiary =
Success (on = Background); containers = SurfaceVariant (on = Accent/Success/Error); error = Error
(on = Background); surface and surfaceContainer(Low) = Surface; surfaceVariant, surfaceBright and
surfaceContainerHigh(est) = SurfaceVariant; background, surfaceDim, surfaceContainerLowest, scrim =
Background; onSurface = TextPrimary, onSurfaceVariant = TextSecondary; outline/outlineVariant =
TextTertiary at 0.40 / 0.20 alpha (`BORDER_ALPHA`, `BORDER_VARIANT_ALPHA`).

Error text is for Background and Surface. On SurfaceVariant use Error for icons or large/bold text
only (3.5:1).

To change a color: edit `Color.kt`, then `colors.xml` for Background, then run `ThemeTokensTest`
and `ContrastTest`.
