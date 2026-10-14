# :search — licences

The code of this module is Atlas's own (MIT, as the repository). Nothing third-party is vendored here.

What it builds on, through other modules:

- **SQLite** (public domain), through AndroidX `sqlite-bundled` (Apache-2.0): FTS5, R*Tree and JSON1.
- **id-tagging-schema** presets, terms and translations (ISC), **name-suggestion-index** brands (BSD-3-Clause) and the
  cuisine and category tables: bundled and licensed in `:places` (see `places/LICENSES.md`).
- The search words of `Lexicon.kt`, the goods catalogue of `Goods.kt` (what shops sell, with likelihoods) and the
  refinement labels of `Refinements.kt`, in English, Dutch, German and French, were written for Atlas.

Data:

- A region's store (`PlaceStore`) holds **OpenStreetMap** data: © OpenStreetMap contributors, under the Open Database
  Licence (ODbL 1.0). Screens that show places from it carry the attribution; a store shared outside the app is a
  derived database under the ODbL.
- Answers from Overpass and Photon are OpenStreetMap data under the same licence (see `providers/LICENSES.md`).
