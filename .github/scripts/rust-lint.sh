#!/bin/bash
# rust-lint.sh: rustfmt and clippy on the core's Rust, as CI's tidy job runs them (core.yml), with the Rust that
# `.github/scripts/rust.sh lint` installs (the pinned toolchain with its rustfmt and clippy). It finds the crates itself:
# every app/android/mapcore/rust/*/Cargo.toml, so a crate added there is linted without a word elsewhere; none found
# fails. For each crate:
# - rust-version: the pin's major and minor (app/android/rust-toolchain.toml's channel), so a raised pin takes the
#   crates' minimum with it.
# - rustfmt: the crate's files as the rustfmt.toml above them formats them (mapcore/rust/rustfmt.toml). Where they
#   differ, cargo fmt formats them here and the patch it made is printed, to `git apply` where rustfmt is not
#   installed.
# - clippy, every warning an error: the library as the core links it (no_std), then with its tests and examples
#   (feature std). The lints are clippy's defaults and those the crate's Cargo.toml sets ([lints.clippy]: every panic
#   written as one is refused), with the clippy.toml above it (a test may panic).
# - every place in the library where a panic can come unwritten, as clippy's indexing_slicing and
#   arithmetic_side_effects name them (an index or a slice out of bounds; an integer overflow where the checks are on,
#   a division by zero): each one in the log, and their count by file in the job's summary. Each lint's count is held
#   to the one the crate's Cargo.toml keeps ([package.metadata.atlas.unwritten_panics]): more fails, and so does
#   fewer, asking for the kept count to be lowered to it, so that a place taken away cannot come back unseen.
# Fails if a crate's rust-version is not the pin's, rustfmt would change a file, clippy finds anything, or a count is
# not the kept one.
set -uo pipefail
if [ $# -ne 0 ]; then
  echo "::error::rust-lint.sh takes no crate: it lints every app/android/mapcore/rust/*/Cargo.toml"
  exit 1
fi
root=$(git rev-parse --show-toplevel)
summary=${GITHUB_STEP_SUMMARY:-/dev/null}
toml="$root/app/android/rust-toolchain.toml"
channel=$(sed -n 's/^channel = "\(.*\)"$/\1/p' "$toml")
pin=$(sed -n 's/^\([0-9][0-9]*\.[0-9][0-9]*\)\(\.[0-9][0-9]*\)*$/\1/p' <<< "$channel")
if [ -z "$pin" ]; then
  echo "::error::no channel of a Rust version (major.minor[.patch]) in $toml: '$channel'"
  exit 1
fi
crates=()
for manifest in "$root"/app/android/mapcore/rust/*/Cargo.toml; do
  [ -f "$manifest" ] && crates+=("${manifest%/Cargo.toml}")
done
if [ ${#crates[@]} -eq 0 ]; then
  echo "::error::no crate under app/android/mapcore/rust (no */Cargo.toml): nothing to lint"
  exit 1
fi
echo "rust-lint.sh: ${#crates[@]} crate(s), the pin $channel"
status=0
for dir in "${crates[@]}"; do
  crate=${dir#"$root"/}
  cd "$dir" || exit 1
  echo "== $crate: $(cargo fmt --version), $(cargo clippy --version)"

  # the crate's own package, by its manifest's path as cargo writes it (with no link in it)
  if ! package=$(cargo metadata --offline --locked --no-deps --format-version 1 |
    jq -ce --arg m "$(pwd -P)/Cargo.toml" '.packages[] | select(.manifest_path == $m)'); then
    echo "::error::cargo metadata could not read $crate/Cargo.toml"
    status=1
    continue
  fi
  version=$(jq -r '.rust_version // ""' <<< "$package")
  if [ "$version" = "$pin" ]; then
    echo "rust-version: $version, the pin's"
  else
    echo "::error::$crate/Cargo.toml's rust-version is '$version', not the pin's $pin ($toml)"
    status=1
  fi

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
  held=""
  for lint in indexing_slicing arithmetic_side_effects; do
    count=$(grep -c " $lint\$" <<< "$places" || true)
    kept=$(jq -r --arg l "$lint" '.metadata.atlas.unwritten_panics[$l] // ""' <<< "$package")
    held="$held, $lint $count (kept $kept)"
    if ! [[ "$kept" =~ ^[0-9]+$ ]]; then
      echo "::error::$crate/Cargo.toml keeps no count of $lint ([package.metadata.atlas.unwritten_panics]); it is $count"
      status=1
    elif [ "$count" -gt "$kept" ]; then
      echo "::error::$crate: $count places of $lint, more than the $kept its Cargo.toml keeps: the new ones are among" \
        "those listed above"
      status=1
    elif [ "$count" -lt "$kept" ]; then
      echo "::error::$crate: $count places of $lint, fewer than the $kept its Cargo.toml keeps: lower it to $count" \
        "([package.metadata.atlas.unwritten_panics])"
      status=1
    fi
  done
  echo "$crate: held${held#,}"
  {
    echo "### $crate: where its library can panic"
    echo "No panic written as one (clippy refuses them). $n places where one can come unwritten, as clippy's"
    echo "indexing_slicing and arithmetic_side_effects name them (the job's log lists each one):${held#,}."
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
