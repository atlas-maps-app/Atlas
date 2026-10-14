# Licences of the data bundled in `:places`

The files under `src/main/resources/atlas/places/` are built by `tools/build_places_data.py` from the pinned
releases that `tools/fetch_sources.sh` downloads. Each source keeps its licence:

| Bundled file | Source | Licence |
|---|---|---|
| `presets.json`, `fields.json`, `i18n/*.json`, cuisine names, category names in other languages | [id-tagging-schema](https://github.com/openstreetmap/id-tagging-schema) 7.3.0 | ISC, Copyright (c) iD Contributors |
| `brands.tsv` (names, Wikidata items, tags, where they trade) | [name-suggestion-index](https://github.com/osmlab/name-suggestion-index) 8.0.20260918 | BSD 3-Clause, Copyright 2026, name-suggestion-index contributors |
| `logos.json` (brand glyphs) and brand colours where no hand-set colour exists | [simple-icons](https://github.com/simple-icons/simple-icons) 16.35.0 | CC0 1.0 |
| `symbols.json` `maki-*` | [Maki](https://github.com/mapbox/maki) 8.2.0 | CC0 1.0 |
| `symbols.json` `temaki-*` | [Temaki](https://github.com/rapideditor/temaki) 5.13.0 | CC0 1.0 |
| `symbols.json` `pinhead-*` | [Pinhead](https://github.com/waysidemapping/pinhead) 15.25.0 | CC0 1.0 |
| `ui.json` (Atlas's UI symbols) | [Lucide](https://lucide.dev) 1.56.0 (`lucide-static`), plus Atlas's own drawings in `tools/custom_icons.txt` | ISC (see below) |
| `categories.json`, `cuisine.json`, the hand tables in `tools/` | Atlas, from the prototype (`fetch/categories_ams.py`, `render/poi_style.py`) | the repository's MIT licence |

Public holidays (`Holidays.kt`) are computed from rules written for Atlas; no holiday data is bundled.

## ISC licence (id-tagging-schema)

Copyright (c) iD Contributors

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby
granted, provided that the above copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
THIS SOFTWARE.

## ISC licence (Lucide)

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT). All other copyright
(c) for Lucide are held by Lucide Contributors 2022.

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby
granted, provided that the above copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
THIS SOFTWARE.

The Feather portions are under the MIT licence:

The MIT License (MIT)

Copyright (c) 2013-2022 Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## BSD 3-Clause licence (name-suggestion-index)

Copyright 2026, name-suggestion-index contributors

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
   disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
   disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
   products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## CC0 1.0 (simple-icons, Maki, Temaki, Pinhead)

These works are dedicated to the public domain under CC0 1.0 Universal
(https://creativecommons.org/publicdomain/zero/1.0/). simple-icons' disclaimer applies to its glyphs: the logos are
trademarks of their owners, and Atlas shows them only to identify the brand of a place (nominative use); some brands
publish their own guidelines for that.

## OpenStreetMap data in tests

The tests read the prototype's Overpass extracts in `data/` (not bundled), © OpenStreetMap contributors, ODbL 1.0.
