# Licences of what `:guide` vendors

## osrm-text-instructions (BSD-2-Clause)

- What: the v5 instruction templates of 17 languages (`src/main/resources/app/atlas/guide/osrm/translations/{en,nl,de,fr,es,it,pt-BR,pl,sv,da,no,fi,ru,ja,zh-Hans,tr,uk}.json`) and the grammar rules for Danish, French and Russian (`.../osrm/grammar/{da,fr,ru}.json`). The test fixtures in `src/test/resources/osrm/fixtures-v5.json` are the library's own `test/fixtures/v5`, trimmed to these languages.
- Where from: https://github.com/Project-OSRM/osrm-text-instructions, master at commit `b4fa9efc0501b931408bdf0d8d2c627e38ea829b` (12 October 2026; package version 0.15.0). The JSON is re-serialised without whitespace; the content is unchanged.
- `OsrmInstructions.kt` is a Kotlin port of the library's `index.js` (`compile`, `getWayName`, `laneConfig`, `directionFromDegree`, `ordinalize`, `tokenize`, `grammarize`).
- The licence text ships with the app at `src/main/resources/app/atlas/guide/osrm/LICENSE.txt`, and the app's licence screen must show it:

```
Copyright (c) 2016, Project OSRM contributors
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Atlas's own phrase tables

`src/main/resources/app/atlas/guide/atlas/*.json` (landmarks, warnings, mode callouts, Ears-only phrases and spoken units) are Atlas's own work, under the project's MIT licence.

## Ideas, not code

The announcement timing follows the published behaviour of OsmAnd, Organic Maps and Valhalla, and Ears-only mode follows Microsoft Soundscape's ideas (callouts, "around me", the audio beacon). No code from them is included.

## Test data

`RealDataTest` reads `data/ams/raw.json` from the repository: OpenStreetMap data, © OpenStreetMap contributors, ODbL 1.0.
