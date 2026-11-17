# Licences of what the mine package bundles

| Font | Credit | Licence | Where | Source |
|---|---|---|---|---|
| VT323 | Copyright 2011, The VT323 Project Authors (peter.hull@oikoi.com) | SIL Open Font License 1.1 | `app/src/main/res/font/mine_vt323.ttf` | Peter Hull's face, from github.com/google/fonts `ofl/vt323`: the amber digits of the fuel pump |
| Bricolage Grotesque | Copyright 2022 The Bricolage Grotesque Project Authors (https://github.com/ateliertriay/bricolage) | SIL Open Font License 1.1 | `app/src/main/res/font/mine_bricolage.ttf` | variable, by Mathieu Triay, from github.com/google/fonts `ofl/bricolagegrotesque`: the title of the Mine desk |

The paper map of the Mine board (`app/src/main/res/raw/mine_art.json`, taken from the Atlas boards by `art/mine_art.py`) is Atlas's own work, under the repository's MIT licence.

Caveat and Courier Prime, which draw the handwriting and the ticket stubs on the Mine desk, are the eat package's copies (see `ui/eat/LICENSES.md`).
The licence texts of the Open Font License fonts are in `app/LICENSES.md`.

## The licence texts About & licences opens

A tap on a line of About & licences, or on a source of its credits card, opens the licence it names in full: the
thing's own notice first, then each licence's text whole, as written. `art/mine_licences.py` reads the two tables
below into `res/raw/mine_licence_texts.json` (one copy of each text, however many lines name it) and
`res/raw/mine_licences.json` (which text each licence name opens, and each line's own notice).

Each text is taken from a copy the repository already holds where it has one, else from the licence's publisher, as
plain text, kept in `art/licences/`. A text runs from its From line to its To line (the file's first and last when
blank; a line ending in … is matched by its start). Paths are from `app/android`.

<!-- not on About: the licence texts the list opens -->

| Text | Title | Licences | File | From | To | Source |
|---|---|---|---|---|---|---|
| ofl-1.1 | SIL Open Font License 1.1 | SIL Open Font License 1.1 | `mapview/src/main/assets/svgfonts/OFL.txt` | `-----------------------------------------------------------` | | the copy the engine's fonts ship (the same as the fonts' copies in `app/LICENSES.md`, `ui/eat/LICENSES.md`) |
| apache-2.0 | Apache License 2.0 | Apache License 2.0 | `mapcore/cpp/third_party/vulkan-headers/LICENSES/Apache-2.0.txt` | | | the Apache Software Foundation's text as Vulkan-Headers ships it (the same as `core/src/test/resources/olc/LICENSE`) |
| mit | MIT License | MIT License | `mapcore/cpp/third_party/vulkan-headers/LICENSES/MIT.txt` | `Permission is hereby granted…` | | as Vulkan-Headers ships it (BRouter's and the Vulkan Memory Allocator's copies have the same words) |
| isc | ISC License | ISC License | `mapcore/cpp/third_party/earcut/LICENSE` | `Permission to use…` | | as earcut.hpp ships it (the same words as `core/src/test/resources/earcut/LICENSE`) |
| bsd-2-clause | BSD 2-Clause License | BSD 2-Clause License | `guide/src/main/resources/app/atlas/guide/osrm/LICENSE.txt` | `Redistribution and use…` | | as OSRM Text Instructions ships it |
| bsd-3-clause | BSD 3-Clause License | BSD 3-Clause License | `places/LICENSES.md` | `Redistribution and use…` | `SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.` | the name-suggestion-index's, as `places/LICENSES.md` reproduces it |
| bsd-3-clause-zstd | BSD License | | `mapcore/cpp/third_party/zstd/LICENSE` | `Redistribution and use…` | | Zstandard's own, as it ships (its third clause names Facebook and Meta) |
| old-mit | Old MIT License | Old MIT License | `mapcore/cpp/third_party/harfbuzz/COPYING` | `Permission is hereby granted…` | | HarfBuzz's own, as it ships |
| psf-2.0 | Python Software Foundation License 2.0 | Python Software Foundation License 2.0 | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/cpython-LICENSE.txt` | `PYTHON SOFTWARE FOUNDATION LICENSE VERSION 2` | `Agreement.` | CPython's `LICENSE`, from its publisher, https://raw.githubusercontent.com/python/cpython/main/LICENSE, taken 17 Nov 2026 |
| w3c-software-2023 | W3C Software and Document License | W3C Software and Document License | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/w3c-software-2023.txt` | `This work is being provided…` | `The name and trademarks of copyright holders…` | https://www.w3.org/copyright/software-license-2023/, the version in effect since 1 January 2023, taken 17 Nov 2026; W3C publishes it as a web page only: the file is the page's text, a paragraph or list item a line |
| cc0-1.0 | CC0 1.0 Universal | CC0 1.0; CC0 | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/cc0-1.0.txt` | | | https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt, taken 17 Nov 2026 |
| cc-by-4.0 | Creative Commons Attribution 4.0 International | CC BY 4.0 | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/cc-by-4.0.txt` | | | https://creativecommons.org/licenses/by/4.0/legalcode.txt, taken 17 Nov 2026 |
| cc-by-sa-4.0 | Creative Commons Attribution-ShareAlike 4.0 International | CC BY-SA 4.0 | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/cc-by-sa-4.0.txt` | | | https://creativecommons.org/licenses/by-sa/4.0/legalcode.txt, taken 17 Nov 2026 |
| odbl-1.0 | Open Database License 1.0 | ODbL | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/odbl-1.0.txt` | | | https://opendatacommons.org/licenses/odbl/odbl-10.txt, taken 17 Nov 2026 |
| nlod-2.0 | Norwegian Licence for Open Government Data 2.0 | Norwegian Licence for Open Government Data 2.0 | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/nlod-2.0.txt` | | | https://data.norge.no/nlod/en/2.0, taken 17 Nov 2026; the Norwegian Digitalisation Agency publishes it as a web page only: the file is the licence's text on it, a paragraph, term or list item a line, without the page's comments on each section |
| bsd-3-clause-mt | BSD 3-Clause License (the Mersenne Twister's) | | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/mt19937ar-header.txt` | `Redistribution and use…` | `SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.` | the Mersenne Twister's own, its third clause its own: the header of mt19937ar.c on its authors' page, http://www.math.sci.hiroshima-u.ac.jp/m-mat/MT/MT2002/CODES/mt19937ar.c, taken 17 Nov 2026; the file is that header comment, as fetched, without the code |
| copernicus-worlddem-30 | Licence for the use of the Copernicus WorldDEM-30 | Licence for the use of the Copernicus WorldDEM-30 | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/copernicus-worlddem-30.txt` | | | the EULA the GLO-30 tiles carry, https://copernicus-dem-30m.s3.eu-central-1.amazonaws.com/Copernicus_DSM_COG_10_N52_00_E004_00_DEM/INFO/eula_F.pdf (sha256 32049914…), taken 17 Nov 2026; a PDF only: its text as macOS's PDFKit reads it, checked page by page against the PDF, with what PDFKit's layout changes put back (a superscript footnote mark written ¹ ², the footnotes at the end, the pages run on) and nothing else |
| copernicus-worlddem-90 | Licence for the use of the Copernicus WorldDEM-90 | Licence for the use of the Copernicus WorldDEM-90 | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/copernicus-worlddem-90.txt` | | | ESA's Copernicus DEM licences, pages 19 to 21 ("Licence for COP-DEM-GLO-90-F"), https://dataspace.copernicus.eu/sites/default/files/media/files/2025-06/copernicus_contributing_mission_data_access_v2_cop_dem_licenses.pdf (sha256 bb4a01dc…), taken 17 Nov 2026 (the GLO-90 tiles' own eula_F.pdf is another licence, GLO-30-R's); read as the GLO-30 one, and the line PDFKit breaks after each ™ joined; its own spellings ("andinformation", "followingnotice") are the PDF's |
| mit-attribution | MIT License with Attribution Requirement | MIT with attribution | `providers/src/main/resources/app/atlas/providers/ev-models-LICENCES.txt` | `MIT License with Attribution Requirement` | `SOFTWARE.` | Open EV Data's, as the providers module ships it beside the data |
| cdla-permissive-2.0 | Community Data License Agreement – Permissive 2.0 | CDLA-Permissive-2.0 | `providers/src/main/resources/app/atlas/providers/ev-models-LICENCES.txt` | `# Community Data License Agreement - Permissive - Version 2.0` | | as the providers module ships it beside the data |

A line's own notice, where its licence asks for one and its credit on the list is not it (a font's credit is its
copyright and Reserved Font Name already), and the texts it opens where they are not those its licence's name opens.
Each line of a notice is in the file it is taken from, word for word.

<!-- not on About: the notices of the lines the list opens -->

| What | Notice | Taken from | Texts |
|---|---|---|---|
| Earcut | Copyright (c) 2026, Mapbox<br>Copyright (c) 2015, Mapbox | `core/src/main/kotlin/app/atlas/geo/Earcut.kt`; `mapcore/cpp/third_party/earcut/LICENSE` | |
| Flatbush | Copyright (c) 2022, Vladimir Agafonkin | `core/src/main/kotlin/app/atlas/geo/PackedRTree.kt` | |
| FlatQueue | Copyright (c) 2025, Volodymyr Agafonkin | `core/src/main/kotlin/app/atlas/geo/PackedRTree.kt` | |
| KDBush | Copyright (c) 2026, Vladimir Agafonkin | `core/src/main/kotlin/app/atlas/geo/PointIndex.kt` | |
| Polylabel | Copyright (c) 2016 Mapbox | `core/src/main/kotlin/app/atlas/geo/Polylabel.kt` | |
| Open Location Code | Copyright 2014 Google Inc. | `core/src/main/kotlin/app/atlas/geo/PlusCode.kt` | |
| OSRM Text Instructions | Copyright (c) 2016, Project OSRM contributors<br>All rights reserved. | `guide/src/main/resources/app/atlas/guide/osrm/LICENSE.txt` | |
| HarfBuzz | Copyright © 2010-2022  Google, Inc.<br>Copyright © 2015-2020  Ebrahim Byagowi<br>Copyright © 2019,2020  Facebook, Inc.<br>Copyright © 2012,2015  Mozilla Foundation<br>Copyright © 2011  Codethink Limited<br>Copyright © 2008,2010  Nokia Corporation and/or its subsidiary(-ies)<br>Copyright © 2009  Keith Stribley<br>Copyright © 2011  Martin Hosken and SIL International<br>Copyright © 2007  Chris Wilson<br>Copyright © 2005,2006,2020,2021,2022,2023  Behdad Esfahbod<br>Copyright © 2004,2007,2008,2009,2010,2013,2021,2022,2023  Red Hat, Inc.<br>Copyright © 1998-2005  David Turner and Werner Lemberg<br>Copyright © 2016  Igalia S.L.<br>Copyright © 2022  Matthias Clasen<br>Copyright © 2018,2021  Khaled Hosny<br>Copyright © 2018,2019,2020  Adobe, Inc<br>Copyright © 2013-2015  Alexei Podtelezhnikov<br>Copyright (c) Microsoft Corporation. | `mapcore/cpp/third_party/harfbuzz/COPYING`; `mapcore/cpp/third_party/harfbuzz/src/ms-use/COPYING` | old-mit; mit |
| Vulkan-Headers and Vulkan-Hpp | Copyright 2015-2026 The Khronos Group Inc. | `mapcore/cpp/third_party/vulkan-headers/LICENSE.md` | |
| Vulkan Memory Allocator | Copyright (c) 2017-2026 Advanced Micro Devices, Inc. All rights reserved. | `mapcore/cpp/third_party/vma/LICENSE.txt` | |
| Zstandard | Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved. | `mapcore/cpp/third_party/zstd/LICENSE` | bsd-3-clause-zstd |
| iD Tagging Schema | Copyright (c) iD Contributors | `places/LICENSES.md` | |
| Name Suggestion Index | Copyright 2026, name-suggestion-index contributors | `places/LICENSES.md` | |
| Lucide | Copyright (c) 2026 Lucide Icons and Contributors<br>Copyright (c) 2013-present Cole Bemis | `app/LICENSES.md` | isc; mit |
| Mersenne Twister | Copyright (C) 1997 - 2002, Makoto Matsumoto and Takuji Nishimura,<br>All rights reserved. | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/mt19937ar-header.txt` | bsd-3-clause-mt |
| CPython | Copyright (c) 2001 Python Software Foundation; All Rights Reserved | `app/src/main/kotlin/app/atlas/ui/mine/art/licences/cpython-LICENSE.txt` | |
| BRouter | Copyright (c) 2019 BRouter contributors | `brouter/LICENSE` | |
