#!/bin/sh
# Downloads the pinned open sources :places is built from into $1 (about 40 MB), for build_places_data.py.
#   sh fetch_sources.sh ../../../../cache/places-src
# Every file is a pinned npm release served by jsDelivr or the npm registry. simple-icons glyphs are fetched later,
# one per matched brand, by build_places_data.py itself.
set -eu
SRC=${1:?usage: fetch_sources.sh <directory>}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$SRC/id" "$SRC/nsi" "$SRC/si" "$SRC/pinhead" "$SRC/maki/x" "$SRC/temaki/x" "$SRC/lucide"

ID=https://cdn.jsdelivr.net/npm/@openstreetmap/id-tagging-schema@7.3.0
NSI=https://cdn.jsdelivr.net/npm/name-suggestion-index@8.0.20260918
SI=https://cdn.jsdelivr.net/npm/simple-icons@16.35.0
PIN=https://cdn.jsdelivr.net/npm/@waysidemapping/pinhead@15.25.0
LUCIDE=https://cdn.jsdelivr.net/npm/lucide-static@1.56.0

get() { [ -s "$2" ] || curl -fsSL --retry 3 -A 'Atlas places build' -o "$2" "$1"; }

for f in presets fields preset_categories preset_defaults deprecated; do get "$ID/dist/$f.min.json" "$SRC/id/$f.min.json"; done
get "$ID/LICENSE.md" "$SRC/id/LICENSE.md"
for l in en nl de fr es it pt pt-BR pl sv da nb no fi cs ja zh-CN ru ar tr; do
  get "$ID/dist/translations/$l.min.json" "$SRC/id/tr-$l.min.json"
done
get "$NSI/dist/json/nsi.min.json" "$SRC/nsi/nsi.min.json"
get "$NSI/dist/wikidata/wikidata.min.json" "$SRC/nsi/wikidata.min.json"
get "$NSI/LICENSE.md" "$SRC/nsi/LICENSE.md"
get "$SI/data/simple-icons.json" "$SRC/si/simple-icons.json"
get "$SI/LICENSE.md" "$SRC/si/LICENSE.md"
get "$SI/DISCLAIMER.md" "$SRC/si/DISCLAIMER.md"
get "$PIN/dist/icons/index.complete.json" "$SRC/pinhead/index.complete.json"
get "$PIN/LICENSE" "$SRC/pinhead/LICENSE"
get https://registry.npmjs.org/@mapbox/maki/-/maki-8.2.0.tgz "$SRC/maki/maki.tgz"
get https://registry.npmjs.org/@rapideditor/temaki/-/temaki-5.13.0.tgz "$SRC/temaki/temaki.tgz"
[ -d "$SRC/maki/x/package" ] || tar -xzf "$SRC/maki/maki.tgz" -C "$SRC/maki/x"
[ -d "$SRC/temaki/x/package" ] || tar -xzf "$SRC/temaki/temaki.tgz" -C "$SRC/temaki/x"

grep -v '^#' "$HERE/ui_icons.tsv" | cut -f2 | grep -v '^custom$' | sort -u | while read -r name; do
  get "$LUCIDE/icons/$name.svg" "$SRC/lucide/$name.svg"
done
echo "sources in $SRC"
