#!/usr/bin/env bash
# The nightly alpha's choice (.github/workflows/alpha.yml): the latest commit of the work branch whose push Android
# (android.yml) built green and whose core Core (core.yml) built and tested green, on that push or, where the push left
# the core alone, on the nearest push before it that tested it (core_word), when the app (app/, and render/traditions,
# which the core compiles in) changed between the last alpha and it. Writes `tag` and `sha` to $GITHUB_OUTPUT (stdout
# outside Actions) and the release notes (the commits since the last alpha, each by its first sentence, ENGINE.md's own
# left out) to $NOTES. With nothing to release it says why and writes neither. Reads GitHub's runs with gh; changes
# nothing.
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

# the conclusion of the latest completed push run of workflow $1 on commit $2: "none" where the commit has no run of it
# (a commit pushed under a later one has none, nor one whose push changed nothing of the workflow's paths), "" where
# none has completed yet
conclusion() {
  gh run list --repo "$repo" --workflow "$1" --branch "$branch" --commit "$2" --event push --limit 10 \
    --json status,conclusion \
    --jq 'if length == 0 then "none" else ([.[] | select(.status == "completed")][0].conclusion // "") end'
}

# the latest completed push run of workflow $1 on commit $2 as "<its id> <its conclusion>"; "none" and "" as conclusion's
latest() {
  gh run list --repo "$repo" --workflow "$1" --branch "$branch" --commit "$2" --event push --limit 10 \
    --json databaseId,status,conclusion \
    --jq 'if length == 0 then "none" else ([.[] | select(.status == "completed")][0] | if . == null then "" else "\(.databaseId) \(.conclusion)" end) end'
}

# Core's word on the core that commit $1 carries: the conclusion of the latest completed Core push run that built and
# tested the core (core.yml's matrix, whose host-debug jobs are its build and tests) on the commit or, where it has none,
# on the nearest commit before it with one: "success", "failure", "cancelled" and the like; "" while that run is under
# way; "unknown" where none is found within 100 commits. A push that leaves the core alone starts no Core run, or one of
# the rule table's check alone (render/**): its core is the one tested before it, red or green. (Such a push counted as
# green, so a Kotlin push after a red Core run would have made an alpha of the red core.) A run that failed without the
# matrix is no too.
core_word() {
  local sha=$1 run tested n=0
  while [ "$n" -lt 100 ]; do
    run=$(latest core.yml "$sha")
    case "$run" in
      none) ;;
      "") echo ""; return ;;
      *" success")
        tested=$(gh run view "${run%% *}" --repo "$repo" --json jobs \
          --jq '[.jobs[] | select((.name | startswith("host-debug ")) and .conclusion != "skipped")] | length')
        if [ "${tested:-0}" -gt 0 ]; then echo success; return; fi
        ;;
      *) echo "${run#* }"; return ;;
    esac
    sha=$(git rev-parse --verify --quiet "$sha^") || break
    n=$((n + 1))
  done
  echo unknown
}

# green: Android built and tested the commit's push, and Core the core it carries
green=""
for sha in $(git rev-list --max-count=100 "$head" "^$last_sha"); do
  [ "$(conclusion android.yml "$sha")" = success ] || continue
  core=$(core_word "$sha")
  if [ "$core" != success ]; then
    echo "${sha:0:7}: Android built it green, but Core's word on the core it carries is '${core:-under way}'"
    continue
  fi
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
