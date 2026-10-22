#!/usr/bin/env bash
# The nightly alpha's choice (.github/workflows/alpha.yml): the latest commit of the work branch whose push Core
# (core.yml) and Android (android.yml) both built green, when the app (app/, and render/traditions, which the core
# compiles in) changed between the last alpha and it. Writes `tag` and `sha` to $GITHUB_OUTPUT (stdout outside Actions)
# and the release notes (the commits since the last alpha, each by its first sentence, ENGINE.md's own left out) to
# $NOTES. With nothing to release it says why and writes neither. Reads GitHub's runs with gh; changes nothing.
set -euo pipefail

repo="${GITHUB_REPOSITORY:-atlas-maps-app/Atlas}"
branch="${ALPHA_BRANCH:-app}"
remote="${ALPHA_REMOTE:-origin}"
out="${GITHUB_OUTPUT:-/dev/stdout}"
notes="${NOTES:-${RUNNER_TEMP:-/tmp}/alpha-notes.txt}"

git fetch --quiet --tags "$remote" "$branch"
head=$(git rev-parse FETCH_HEAD)

# the last alpha: the highest v0.1.0-alpha.<n>
last=$(git tag --list 'v0.1.0-alpha.*' | sed 's/^v0\.1\.0-alpha\.//' | grep -E '^[0-9]+$' | sort -n | tail -n 1 || true)
if [ -z "$last" ]; then
  echo "no alpha tag yet: the first is tagged by hand"
  exit 0
fi
last_tag="v0.1.0-alpha.$last"
last_sha=$(git rev-list -n 1 "$last_tag")

# the conclusion of the latest completed push run of workflow $1 on commit $2 ("" where none has completed: a commit
# pushed under a later one has no runs of its own)
conclusion() {
  gh run list --repo "$repo" --workflow "$1" --branch "$branch" --commit "$2" --event push --limit 10 \
    --json status,conclusion --jq '[.[] | select(.status == "completed")][0].conclusion // ""'
}

green=""
for sha in $(git rev-list --max-count=100 "$head" "^$last_sha"); do
  [ "$(conclusion android.yml "$sha")" = success ] || continue
  [ "$(conclusion core.yml "$sha")" = success ] || continue
  green=$sha
  break
done
if [ -z "$green" ]; then
  echo "no commit of $branch since $last_tag is green in both Core and Android"
  exit 0
fi
if git diff --quiet "$last_sha" "$green" -- app render/traditions; then
  echo "the app has not changed between $last_tag and ${green:0:7}"
  exit 0
fi

tag="v0.1.0-alpha.$((last + 1))"
{
  echo "Nightly alpha from ${green:0:7}, the latest commit of $branch that Core and Android built green."
  echo
  echo "Since $last_tag:"
  git log --reverse --format='%s' "$last_sha..$green" -- . ':(exclude)ENGINE.md' |
    perl -pe 's/^(.*?\.)\s.*$/$1/' | cut -c1-240 | sed 's/^/- /'
} > "$notes"
echo "$tag from ${green:0:7} ($(git log -1 --format=%cs "$green")); its notes in $notes"
echo "tag=$tag" >> "$out"
echo "sha=$green" >> "$out"
