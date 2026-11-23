#!/bin/sh
# rust.sh [android|lint]: the Rust the core's stage P is built with, as app/android/rust-toolchain.toml pins it
# (cpp/cmake/Rust.cmake builds the buildings' cleaning with it), on a runner: rustup's toolchain in its minimal profile;
# with `android` the targets of the two ABIs the NDK builds, with `lint` its rustfmt and clippy (rust-lint.sh). GitHub's Ubuntu images carry rustup; where one does
# not, rustup's own installer brings it, with no toolchain of its own. A fetch that fails is tried again, three times.
set -eu
toml="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}/app/android/rust-toolchain.toml"
channel=$(sed -n 's/^channel = "\(.*\)"$/\1/p' "$toml")
[ -n "$channel" ] || { echo "::error::no channel in $toml"; exit 1; }
export PATH="$HOME/.cargo/bin:$PATH"
if ! command -v rustup > /dev/null 2>&1; then
  curl -fsSL --retry 5 --retry-delay 10 https://sh.rustup.rs | sh -s -- -y -q --profile minimal --default-toolchain none
fi
case "${1:-}" in
  android) set -- --target aarch64-linux-android --target x86_64-linux-android ;;
  lint) set -- --component rustfmt --component clippy ;;
  '') set -- ;;
  *) echo "::error::rust.sh takes android, lint or nothing, not $1"; exit 1 ;;
esac
for try in 1 2 3; do
  rustup toolchain install "$channel" --profile minimal --no-self-update "$@" && break
  [ "$try" -lt 3 ] || { echo "::error::rustup could not install Rust $channel"; exit 1; }
  echo "::warning::rustup could not install Rust $channel (try $try of 3); again in 30 s"
  sleep 30
done
[ -n "${GITHUB_PATH:-}" ] && echo "$HOME/.cargo/bin" >> "$GITHUB_PATH"
rustc "+$channel" --version
