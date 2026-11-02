#!/usr/bin/env bash
# Runs every sXdc code-quality check. See docs/code-quality.md.
#
#   ./scripts/check.sh                 everything
#   ./scripts/check.sh --python-only   file size, ruff, pytest
#   ./scripts/check.sh --android-only  Gradle lint/test/build, ktlint, detekt
#
# ENFORCED steps decide the exit code. REPORT-ONLY steps only print counts.
# Never runs device tasks, formatters or auto-fixers.
set -u

usage() {
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    echo
    echo "Env: PYTHON overrides the interpreter (default .venv/bin/python)."
}

RUN_PY=1
RUN_ANDROID=1
case "${1:-}" in
    "") ;;
    --python-only) RUN_ANDROID=0 ;;
    --android-only) RUN_PY=0 ;;
    -h | --help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
esac

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2
export PYTHONDONTWRITEBYTECODE=1
START=$SECONDS

if [ -t 1 ]; then
    BOLD=$'\e[1m' RED=$'\e[31m' GREEN=$'\e[32m' RESET=$'\e[0m'
else
    BOLD='' RED='' GREEN='' RESET=''
fi

ROWS=()
ENFORCED_FAILED=0

header() { echo; echo "${BOLD}== $1${RESET}"; }

# record <step> <ENFORCED|REPORT-ONLY> <PASS|FAIL|info text>
record() {
    ROWS+=("$1|$2|$3")
    if [ "$2" = "ENFORCED" ] && [ "$3" = "FAIL" ]; then ENFORCED_FAILED=1; fi
}

# enforced <step name> <command...>: runs the command and records PASS or FAIL.
enforced() {
    local name=$1
    shift
    if "$@"; then record "$name" ENFORCED PASS; else record "$name" ENFORCED FAIL; fi
}

# ---------------------------------------------------------------- Python

PY=""
if [ "$RUN_PY" = 1 ]; then
    PY=${PYTHON:-}
    if [ -z "$PY" ]; then
        if [ -x .venv/bin/python ]; then
            PY=.venv/bin/python
        else
            echo "${RED}No .venv/bin/python found.${RESET} Create it from the repo root:" >&2
            echo "    uv venv .venv --python 3.12" >&2
            echo "    uv pip install --python .venv/bin/python -r requirements-dev.txt" >&2
            echo "See docs/python-tests.md, or point PYTHON at a Python 3.12+ interpreter that has" >&2
            echo "requirements-dev.txt installed." >&2
            exit 2
        fi
    fi
    if ! command -v "$PY" >/dev/null 2>&1; then
        echo "${RED}PYTHON=$PY is not an executable interpreter.${RESET} See docs/python-tests.md." >&2
        exit 2
    fi

    header "File size (scripts/check_file_size.py)"
    enforced "file size" "$PY" scripts/check_file_size.py

    header "ruff check"
    enforced "ruff check" "$PY" -m ruff check app/src/main/python tests/python scripts

    header "ruff format --check (report-only)"
    fmt_out=$("$PY" -m ruff format --check app/src/main/python tests/python scripts 2>&1)
    # Parse ruff's summary line ("N file(s) would be reformatted"); absent means none.
    fmt_count=$(grep -oE '^[0-9]+ files? would be reformatted' <<<"$fmt_out" | grep -oE '^[0-9]+')
    fmt_count=${fmt_count:-0}
    echo "$fmt_count file(s) would be reformatted (not applied)."
    record "ruff format --check" REPORT-ONLY "$fmt_count file(s) would reformat"

    header "pytest"
    deselect=()
    if [ -f scripts/known-failures.txt ]; then
        while IFS= read -r line || [ -n "$line" ]; do
            line=${line%%#*}
            line=$(echo "$line" | tr -d '[:space:]')
            [ -n "$line" ] && deselect+=(--deselect "$line")
        done <scripts/known-failures.txt
    fi
    echo "$((${#deselect[@]} / 2)) known failing test(s) deselected (see scripts/known-failures.txt)"
    enforced "pytest" "$PY" -m pytest -q "${deselect[@]}"
fi

# ---------------------------------------------------------------- Android

# Prints the number of <error entries across the given checkstyle XML files.
count_errors() {
    local total=0 n f
    for f in "$@"; do
        [ -f "$f" ] || continue
        n=$(grep -c '<error ' "$f")
        total=$((total + n))
    done
    echo "$total"
}

if [ "$RUN_ANDROID" = 1 ]; then
    header "Gradle: lintDebug testDebugUnitTest assembleDebug"
    enforced "gradle lint/test/build" ./gradlew --console=plain lintDebug testDebugUnitTest assembleDebug
    echo "Lint report: app/build/reports/lint-results-debug.html"

    tasks=$(./gradlew --console=plain -q tasks --all 2>/dev/null)

    if grep -q '^app:ktlintCheck' <<<"$tasks"; then
        header "ktlintCheck (report-only)"
        if ./gradlew --console=plain -q ktlintCheck >/dev/null 2>&1; then
            shopt -s nullglob
            reports=(build/reports/ktlint/*/*.xml app/build/reports/ktlint/*/*.xml)
            shopt -u nullglob
            n=$(count_errors "${reports[@]}")
            echo "$n finding(s). Reports: app/build/reports/ktlint/ and build/reports/ktlint/"
            record "ktlintCheck" REPORT-ONLY "$n finding(s)"
        else
            echo "ktlintCheck did not run cleanly; rerun ./gradlew ktlintCheck to see why."
            record "ktlintCheck" REPORT-ONLY "task error"
        fi
    fi

    if grep -qE '^app:detekt( |$)' <<<"$tasks"; then
        header "detekt (report-only)"
        if ./gradlew --console=plain -q :app:detekt >/dev/null 2>&1; then
            n=$(count_errors app/build/reports/detekt/detekt.xml)
            echo "$n new finding(s) not in config/detekt/baseline.xml. Report: app/build/reports/detekt/detekt.html"
            record "detekt" REPORT-ONLY "$n new finding(s)"
        else
            echo "detekt did not run cleanly; rerun ./gradlew :app:detekt to see why."
            record "detekt" REPORT-ONLY "task error"
        fi
    fi
fi

# ---------------------------------------------------------------- Summary

header "Summary"
printf '%-24s %-12s %s\n' "STEP" "SEVERITY" "RESULT"
for row in "${ROWS[@]}"; do
    IFS='|' read -r step sev res <<<"$row"
    color=''
    [ "$res" = PASS ] && color=$GREEN
    [ "$res" = FAIL ] && color=$RED
    printf '%-24s %-12s %s\n' "$step" "$sev" "${color}${res}${RESET}"
done
elapsed=$((SECONDS - START))
printf 'Total runtime: %dm %02ds\n' $((elapsed / 60)) $((elapsed % 60))

if [ "$ENFORCED_FAILED" = 1 ]; then
    echo "${RED}At least one ENFORCED check failed.${RESET}"
    exit 1
fi
echo "${GREEN}All ENFORCED checks passed.${RESET}"
