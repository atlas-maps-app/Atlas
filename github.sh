#!/bin/zsh
# github.sh [dir]: copies what goes to GitHub (README, render/, fetch/, data/ without the big records and the downloads, reviews/) into a folder with its own git,
# default ~/Documents/atlas-github. New York's extract rides along so that render/check.py runs from a fresh clone without a fetch.
set -e
A=${0:A:h}; G=${1:-$HOME/Documents/atlas-github}
mkdir -p "$G"; cd "$A"
rsync -a --delete --exclude '.git' --exclude 'cache' --exclude 'google' --exclude 'data/live/rec_*' --exclude 'data/transit/*.zip' --exclude 'data/traffic/flat_wide.json' --include 'data/regions/newyork/raw.json' --exclude 'data/regions/*/raw.json' --exclude 'data/regions/*/dem*.json' --exclude '.DS_Store' README.md render fetch data reviews "$G/"
cd "$G"; [[ -d .git ]] || git init -q -b main
printf 'cache/\n__pycache__/\n.DS_Store\n' > .gitignore
git add -A; git commit -q -m "Atlas" 2>/dev/null || true
echo "GitHub part in $G: $(git ls-files | wc -l | tr -d ' ') files, $(du -sh . | cut -f1); push it with: cd $G && git remote add origin <url> && git push -u origin main"
