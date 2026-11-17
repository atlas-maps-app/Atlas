# Step 1: The new map on the phone

Moved as it was from `ENGINE.md` on 17 November 2026: its lines 318 to 335 at c9602bc1. Nothing was reworded. The engine's document is `ENGINE.md` at the repository's root; this file is history, opened when a job needs it.

### 1. The new map on the phone
- Done: true depth, the pending branches (the packs' stage T, the buildings and the text registered, the app's map), the canal ring around the Westerkerk with its sun bits, the honest state after three device losses, the bundled render packs on `core.yml`'s host jobs, the ground's and the buildings' `mat`, the desktop render tool and the engine's own references.
- What registering the buildings showed, and what was done:
  - the Main board at S1 overflowed the geometry heaps (a buffer per kind, each doubling within the budget): the heaps are one buffer and one virtual block of the whole budget, and a tile that finds no room is not built again, frame after frame, until a range goes back. The budget is 64 MB, D3's 40 MB until the walls' records are 32 bytes: the canal ring's densest tile holds 13 MB of walls, and its second sweep camera's tiles 50 MB in all;
  - the twin drew the canal names over the ground's shadows: the ground's shadows take tail slots after every pass's ground, as the renders' base layer has them;
  - L1 holds the buildings now: what stands up is matched item by item, its slivers (faces at the renders' 0.12 px² floor or seen edge-on, decided in floats against the port's doubles) counted apart, at most two in a thousand;
  - tidy over every file;
  - the emulator's smoke run (the first to run the app map's checks): the full map's first complete frame there takes 11 to 17 s (ten tiles at the home, the Westerkerk's three and the canal ring's seven, 32 MB, each built in 1 to 2.5 s on its two cores, under lavapipe's 250 to 400 ms frames), so its limits were 25 s and 20 s, and 25 s for both since 25 October (both runs warm, 18 to 22 s by then with step 4's features, two seconds apart from run to run for the same work; gain 7 makes a warm start quick; 30 s for both since 26 October, when a slow run on b47ff39 took the cleared start to 25.1 s with the ripple and the glints on the emulator's software GPU, its VK bench first picture 2.7 s slower than the run before); the home's pills match HWUI's within MAD 0.11, the weather chip left out when its forecast came in one capture and not the other.
- **The desktop render tool:** `./gradlew :mapcorecheck:test --tests '*RenderTool*' -Datlas.render.out=<png>`, with `-Datlas.render.pack`, `view` (`home`, a ladder knot, or `lat,lon,distance,bearing,pitch`), `moment`, `size` and `mode` (`mapcorecheck`'s `likeness/RenderTool.kt`).
- **The engine's own references:** `mapcorecheck`'s `likeness/engine.json` holds each engine version's frames: the Westerkerk home and the Main board at sunny, grey, wet and night, as the app draws them (no engine mode, every effect, S1), as each platform's SHA-256. `EngineRefsTest` holds the engine to the current version on every push (CI's likeness step). A deliberate change to what the engine draws is a new version, recorded with `-Datlas.engine.record=true`; a platform without frames of the version leaves its own in the report's `engine/engine.json`.
- The core draws where it has tiles. Today's map (the renderer's Kotlin port drawing on Canvas) stays everywhere else until the end of step 3.
- **Prove it:** run every preset, tidy, `L1Test` and `StageTParityTest`, push, and get CI green. Then tag an alpha.
- **On the user's phone:**
  - the first picture within 0.5 s warm;
  - VK bench on the Main board and Westerkerk paths;
  - the soak;
  - the frosted pills in the same frame as the map.
