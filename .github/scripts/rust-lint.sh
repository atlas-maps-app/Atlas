#!/bin/bash
# rust-lint.sh <crate folder>...: rustfmt and clippy on the core's Rust, as CI's tidy job runs them (core.yml), with
# the Rust that `.github/scripts/rust.sh lint` installs (the pinned toolchain with its rustfmt and clippy). Each crate
# folder is relative to the repository's top. For each crate:
# - rustfmt: the crate's files as the rustfmt.toml above them formats them (mapcore/rust/rustfmt.toml). Where they
#   differ, cargo fmt formats them here and the patch it made is printed, to `git apply` where rustfmt is not
#   installed.
# - clippy, every warning an error: the library as the core links it (no_std), then with its tests and examples
#   (feature std). The lints are clippy's defaults and those the crate's Cargo.toml sets ([lints.clippy]: every panic
#   written as one is refused), with the clippy.toml above it (a test may panic).
# - every place in the library where a panic can come unwritten, as clippy's indexing_slicing and
#   arithmetic_side_effects name them (an index or a slice out of bounds; an integer overflow where the checks are on,
#   a division by zero): each one in the log, and their count by file in the job's summary. They are named, not
#   refused.
# Fails if rustfmt would change a file or clippy finds anything; the list fails nothing.
set -uo pipefail
root=$(git rev-parse --show-toplevel)
summary=${GITHUB_STEP_SUMMARY:-/dev/null}
status=0
for crate in "$@"; do
  cd "$root/$crate" || exit 1
  echo "== $crate: $(cargo fmt --version), $(cargo clippy --version)"

  if cargo fmt --check > /dev/null; then
    echo "rustfmt: as rustfmt.toml formats it"
  else
    cargo fmt
    git --no-pager diff -- .
    echo "::error::rustfmt would change $crate: the patch above is what cargo fmt makes of it (git apply it)"
    status=1
  fi

  cargo clippy --offline --locked --lib -- -D warnings ||
    { echo "::error::clippy on $crate's library (above)"; status=1; }
  cargo clippy --offline --locked --all-targets --features std -- -D warnings ||
    { echo "::error::clippy on $crate's tests and examples (above)"; status=1; }

  # file:line:column lint, for each place clippy names
  if ! places=$(cargo clippy --offline --locked --lib --quiet --message-format=json -- \
      -W clippy::indexing_slicing -W clippy::arithmetic_side_effects |
    jq -r 'select(.reason == "compiler-message") | .message as $m | select($m.code != null)
      | ($m.code.code | ltrimstr("clippy::")) as $c | select($c == "indexing_slicing" or $c == "arithmetic_side_effects")
      | $m.spans[] | select(.is_primary) | "\(.file_name):\(.line_start):\(.column_start) \($c)"' |
    sort -u | sort -t: -k1,1 -k2,2n -k3,3n); then
    echo "::error::clippy could not list $crate's places where a panic can come unwritten"
    status=1
    continue
  fi
  n=$(grep -c . <<< "$places" || true)
  echo "$crate: $n places in the library where a panic can come unwritten (clippy's indexing_slicing and" \
    "arithmetic_side_effects):"
  [ "$n" -gt 0 ] && echo "$places"
  {
    echo "### $crate: where its library can panic"
    echo "No panic written as one (clippy refuses them). $n places where one can come unwritten, as clippy's"
    echo "indexing_slicing and arithmetic_side_effects name them (the job's log lists each one):"
    echo
    echo "| file | an index or a slice | integer arithmetic |"
    echo "|---|---|---|"
    if [ "$n" -gt 0 ]; then
      awk '{ split($1, a, ":"); f[a[1]] = 1; c[a[1] " " $2]++ }
        END { for (k in f) printf "| %s | %d | %d |\n", k, c[k " indexing_slicing"], c[k " arithmetic_side_effects"] }' \
        <<< "$places" | sort
    fi
  } >> "$summary"
done
exit $status
