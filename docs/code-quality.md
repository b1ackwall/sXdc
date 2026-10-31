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

## The 500-line rule

No tracked file may exceed 500 lines (see `CLAUDE.md`). `scripts/check_file_size.py` reads
`git ls-files`. It fails above 500 lines and warns above 450. It skips binary files, lock files,
jars and generated `*baseline*.xml` files. Agreed exceptions go in
`scripts/file-size-ignore.txt`, one path glob per line. Its tests are in
`tests/python/test_check_file_size.py`.
