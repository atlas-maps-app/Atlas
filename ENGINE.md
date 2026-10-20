# Atlas's map engine

Atlas draws its map with its own engine. This is the one document about it: what it is, how it works, what exists and what is left. It replaces every earlier plan, brief and note about the engine. Those are archived in `cache/claude-session/archive/engine-2026-10/` as history, and nothing here needs them.

## What it is

- **The centerpiece of the app, purpose built for Atlas.** It is done first, and the rest of the app builds on it.
- **Built from the ground up.** A C++23 core on Vulkan (`app/android/mapcore/cpp`), behind a small Kotlin façade (`app.atlas.mapcore.MapCore`). It draws vector tiles every frame the camera moves, at the screen's own density, and nothing at rest. Labels are placed again for each view, and detail comes and goes with the zoom.
- **Global.** The map covers the entire world, from the globe down to a single street, online and offline.
- **Resembles the renderer.** The Python renderer (`render/*.py`, ported byte for byte to Kotlin in `app/android/render`) is the starting point for the look: its features, rules, colours and light.
  - The renderer is unfinished and has its issues, so it is a yardstick, not a specification.
  - It is frozen: the look is refined in the engine.
  - The engine fixes what is wrong, and goes on in the renderer's spirit where it stops.
  - When the engine is finished, the renderer and its port are retired.
- **Custom where it counts.** The data model, tile format, rules, look, passes and shaders are Atlas's own. No general map engine and no third-party tile schema: OpenMapTiles is abolished.
  - An outside piece comes in only where it is cutting edge, fits, and plugs in without a sacrifice. Today that means Vulkan-Hpp, VMA, zstd, HarfBuzz, earcut.hpp and doctest in the core, Slang for the shaders, and Planetiler as the build tool for the world's mid zooms.
  - Each is pinned (`cpp/third_party/VERSIONS.md`) and can be replaced.
- **As cutting edge, optimized and efficient as possible, without sacrifices.** 60 fps while moving, idle and cool at rest, on any Vulkan phone. Every choice is made at run time from each phone's own facts, never tuned to one phone.
- **Easy to keep cutting edge.** The engine is built to be improved and refined for years: new hardware features, better techniques and newer libraries go in one piece at a time.

## The design

1. **One data pipeline for the whole world, ours.** Atlas's packer (`:packer`, `:rendertiles`) builds everything on GitHub from OpenStreetMap and open data:
   - render packs for street level everywhere, sharded so each job fits a free runner, and rebuilt monthly so only changed chunks change;
   - a mid-zoom base, with Planetiler running Atlas's own profile into Atlas's own format;
   - a small globe base, bundled in the APK;
   - terrain and relief from the best elevation model for each region (national lidar where it exists, such as AHN in the Netherlands), and a global model everywhere else.

   OpenStreetMap comes from Geofabrik's extracts, which are made for bulk processing and updated daily. Overpass is made for small live queries, and its public servers limit bulk use (about 10,000 queries and 1 GB a day), so it cannot build the world.

   The phone never builds tiles. It reads the chunks it shows by ranges and keeps them, and it can download whole regions for offline use.
2. **Render tiles** (pack format 3) hold every value the look derives from the data, already derived: looks, heights, roof shapes, water levels, label candidates.
   - The values are stored in columns compressed with zstd, with a keep code per vertex for every simplification level.
   - A tile is memory-mapped, decoded straight into the GPU's heaps, and kept on disk once built.
3. **The style is the renderer's:** building traditions by country, and place-specific profiles wherever a place needs one.
   - Close up across Amsterdam: the living map's profile.
   - Williamsburg: `real.py`'s profile, if the owner says yes.
   - Everywhere else: the regions' rules, with each country's building tradition.
   - At the planet: the Globe board's look.
   - The colours, widths, rules, traditions and profiles live in the engine's own style file, which the tiles carry. Refining the look, or adding a tradition or a profile, is an edit to data.
4. **Compact records, expanded on the GPU.** Walls, wall cells, windows, roof faces, trees and street furniture are records of a few bytes that the vertex stage expands, and repeated detail is instanced. Draws are indexed and batched per pipeline. meshoptimizer is the candidate for vertex-cache order and quantised vertices, if it measures better.
5. **One pass in tile memory.** The main render pass has a scene subpass and a composite subpass at 4x MSAA, with its attachments kept in tile memory where the GPU allows.
   - The composite applies the screen effects once per pixel: haze, vignette, grain, `mat`, the wash and cloud shadows.
   - Offscreen passes run before it only where an effect needs one: blurs, crowns, reflections, glows.
   - Newer Vulkan features are used where the phone has them, picked at run time from VK facts: dynamic rendering with local reads, synchronization2, present timing, and fragment shading rate for the thermal ladder.
6. **True depth for everything that stands up.** Buildings, parts, roofs, trees and things are drawn with a real depth buffer, kept in tile memory beside the samples, and roughly front to back for early rejection. They occlude correctly at every angle.
   - The renderer orders whole items by one point each, so at some angles its buildings melt into each other. The engine does not copy that.
   - The ground keeps the renderer's layering on its one plane: land cover, areas, roads, water and shadows, in its order.
   - Flat detail on a wall (cells, windows, sashes, shutters, plinths, cornices, shop glazing, doors, numbers) is drawn by the wall's own shading from its record: crisp at any distance, with no overdraw and nothing fighting for depth.
   - What stands out from a wall (chimneys, dormers, hoist beams, shopfronts, signs, terraces) is geometry with depth.
7. **Light and sun.** The renderer's light model and themes for the moment, and the sun test at every sample point, recomputed on the workers when the sun moves 0.5°.
8. **Text.**
   - Names on the ground (water and canal names along their ways) are shaped by HarfBuzz as Android shapes them, and drawn from the glyphs' outlines (Slug).
   - Place names in every script are sprites drawn by Android's own text stack.
   - The core places them all for every view, with collisions and fades.
9. **Levels** run from the street to the planet: full detail, a simplified level from the keep codes, blocks of merged footprints, the mid-zoom base, the globe. Fades and a triangle governor mean nothing pops and nothing is a hole.
10. **Work only when needed.** Frames come on vsync only while something changes, with ADPF hints. Tiles, the sun and the noise fields are done on background workers. Nothing is done per frame that can be done once.
11. **Deterministic everywhere, exact where it shows.**
    - The core's maths library (`pymath`: fdlibm as Java defines it, with Python's rounding and random stream) gives the same bits on every phone and host. So frames can be compared, and no decision differs between devices.
    - It also matches the renderer's arithmetic, for as long as the renderer is the yardstick.
    - The GPU uses plain float where the difference cannot be seen.
    - Neither the look nor the speed is given up for the other.
12. **Built to keep improving:**
    - each pass, its shaders, its geometry builder and its twin live in files of their own;
    - device features are picked at run time, so a new extension is one more branch, not a rewrite;
    - outside pieces are updated in commits of their own, with the checks' numbers before and after;
    - a desktop render tool draws any view and moment to a PNG on the Mac, to judge the look without a phone and to make the boards;
    - the twin, the engine's own references and the checks make every refactor safe.

**What the rethink of 20 October changed** from the old plan:
- **The renderer is a yardstick, not the eternal reference.** It is frozen, the look is refined in the engine, and the engine fixes the renderer's issues and replaces it.
- **Buildings never melt.** True depth for what stands up, instead of the renderer's painter's order, which the old plan copied on purpose.
- **The whole world is covered from GitHub.** One pipeline builds street level for every place on Earth, so the phone never builds tiles.
- **All data is Atlas's own.** OpenMapTiles is gone; Planetiler stays only as a build tool, running Atlas's own profile.
- **Only the checks below remain.** The old plan's other gates are dropped.
- **No cut changes the result.** A simplification may change how the engine gets there, never what Amsterdam or any rendered place looks like. The engine's references prove it.
- **Two pieces get simpler.** VK check (never built) becomes VK facts' hash lines. Taps on buildings use a hit test against the tile's own buildings instead of an ID pass.
- **One agent works through one ordered list,** instead of waves of packages. The engine comes before the rest of the app.

## Checks

**While the renderer exists, it is the yardstick:**
1. **Parity with the port:** the sun's answers, stage T's points, tile decoding, and pymath bit for bit.
2. **Element by element:** the twin (the core's per-frame code and the shaders' maths compiled as C++) emits what the GPU will draw. `L1Test` matches it against the port's SVG at the boards' views and the sweeps: in order for the ground, item by item for what stands up. Every new pass brings its twin and its cases.
3. **The GPU against the twin** on lavapipe, per pass.
4. **Likeness:** the smoke cases (the Westerkerk home and the Main board, by day and at night) against the port's renders on CI.

**Where the renderer is wrong, the engine does it right.** The difference is named in the case files (melting buildings is the first), so the yardstick never pulls the engine back to the renderer's issues.

**The engine's own references, from step 1.** These are its frames at fixed views and moments: the boards' views first, then places around the world from step 4. They are stored per engine version, and every finished part is held to them. Every simplification is proven against them: Amsterdam and every other rendered place must turn out the same.

**Always:**
5. **CI:**
   - `core.yml`: host builds and tests on x86_64 and arm64 under the validation layer, sanitizers, fuzzing, clang-tidy and the rule table.
   - `android.yml`: the APK, every module's tests, and the emulator run of every screen through `ci/smoke.sh`.
6. **The owner's phone at each alpha:**
   - VK facts;
   - VK bench, with presented frames at p50 ≤ 16.7 ms and p95 ≤ 20 ms on the paths;
   - the ten-minute soak, with no climb in thermal headroom;
   - a look.

## What exists so far

Nothing is finished. Each part below is a first version on `app` (at `10c4529`, with CI green). It gets reworked wherever the design above asks for more, starting with true depth.

| Part | State |
|---|---|
| Toolchain and CI | NDK r30, CMake presets for the hosts and Android, Slang shaders checked at build time, lavapipe under the validation layer |
| Vulkan device layer | VK facts, the crash guard, frame configurations, both frame routes, the pipeline cache and the SurfaceView host; the VK facts and VK bench screens, measured on the owner's phone |
| Data | render tiles, the packer's `render-tiles` and the core's reader (fuzzed); the Westerkerk and the canal ring bundled; render packs that build for `amsterdam` and `grachten` |
| Numerics and style | pymath; the light, the theme and `ScreenLayers`; the rule table, which lists every line of the renderer that draws |
| Frame core | camera, ladder, cover, residency, heaps, ranks and pass registry; the composite (haze, vignette, `mat`'s rule, grain); the twin and L1; the coarser pack around the active one |
| Sun | the sun test on the workers, equal to the port on every sample of 21 board renders |
| Ground and water | drawn and registered; L1 matched in all 11 cases |
| Buildings, first half | walls, parts, flat and gabled roofs and houseboats, with sun bits and edges; held to the port's polygons; not registered yet |
| Text | shaping, glyphs, canal names, and the board's labels placed; not registered yet |
| Noise fields, thin strokes | WebKit's turbulence byte for byte (scalar and NEON); `ObjectStrokes` |
| Frosted glass | drawn by the core under the chrome |
| Likeness harness | references from the port, candidates from the host build |
| The app | the façade drives the core's map, but the home still shows today's map (the port on Canvas) until step 1 |

**Pending work, not on `app`**, one local branch each:
- `wip/build-pack`: the pack-level stage T (`Pass::build_pack`). It builds, but has no test.
- `wip/buildings-registration`: the buildings' registration line. The rows of `registry_vk_test` still need shifting ("a kind 0" becomes "a kind 1").
- `wip/app-map`: three commits: `AtlasMap`, the app starting the core, and the map phase of `smoke.sh`.
- `wip/text-registration`: the text passes' registration. It needs `build_pack` and the providers.
- `wip/exact-haze`: haze and vignette in whole numbers, equal to the twin. Run host-debug, host-tsan and the oracle comparison before merging.

The other local branches and the worktrees under `/Volumes/Download/atlas` are history.

## What is left, in order

The engine is done first: it is the app's centerpiece, and the rest of the app builds on it. Sizes are rough hours of one agent's work, about 175 in all. If the estimates hold, steps 1 and 2 take this week, steps 3 to 5 the next, and step 6 the week after.

### 1. The new map on the phone (about 18 h)
- **True depth first,** before more passes are built on the old order:
  - the walls' pass writes real depth, roughly front to back;
  - the ground keeps its order and writes the plane's depth;
  - `L1Test` compares what stands up item by item;
  - the renderer's melting is named in the case files.
- **Land the pending branches.** Write `build_pack`'s test, then register the buildings and the text passes:
  - `build_pack` feeds `text::pack_canals` and `GroundText::use_pack`;
  - the providers feed `GlyphGlobals` and `SpriteGlobals`;
  - the font source becomes an option.

  Then merge `wip/app-map`.
- Wire `World::set_coarser` from `PackList` and `MapFrame`, so the canal ring is drawn around the Westerkerk.
- Give the canal pack its sun bits: the world runs no sun bucket for it yet.
- Give `core.yml`'s host jobs the bundled render packs (`ATLAS_TILES_DIR`), so the pack tests run on CI instead of skipping.
- Turn on the ground's `mat` (`ATLAS_GROUND_MAT`).
- Show the honest "can't draw the map" state after three device losses in a row.
- **The desktop render tool:** the host build draws any view and moment to a PNG.
- **The engine's own references begin:** the boards' views and moments, stored per engine version.
- The core draws where it has tiles. Today's map stays everywhere else until step 4.
- **Prove it:** run every preset, tidy, `L1Test` and `StageTParityTest`, push, and get CI green. Then tag an alpha.
- **On the owner's phone:**
  - the first picture within 0.5 s warm;
  - VK bench on the Main board and Westerkerk paths;
  - the soak;
  - the frosted pills in the same frame as the map.

### 2. Every roof, the labels, the style file (about 18 h)
- **Roofs:**
  - hipped, pyramidal and mansard roofs;
  - the `CLOSEST` roof mapping;
  - gable triangles in the wall's material, with their 0.25 px edges;
  - the roofs' edge strokes through `ObjectStrokes`.
- **The exact haze:** merge `wip/exact-haze`.
- **Labels** are drawn by the core's frame instead of the Compose canvas, with the fonts loaded at the map's start.
- **All of Amsterdam from the core:** `PackList.kt`, then the first publish of the `amsterdam` and `grachten` render packs after the owner's yes (a `packs.yml` run with `renders=only`).
- **The style file:** the tiles' style section becomes the engine's own, edited as data. Every pass reads its colours and widths from it, before step 3 adds more.
- **VK facts and VK bench on the device:**
  - VK facts prints the hashes of the twin, pymath and the noise fields, to compare with CI's;
  - it checks the composite's resolve form against the direct form, so a driver that clips the full-screen triangle uses the direct form;
  - VK bench runs the real paths: the Westerkerk, the Main board, pitch 70°, and out to 8 km.
- **Refinements:**
  - `ObjectStrokes`' round joins;
  - snapping slivers;
  - the densest tile's 12.7 MB, with 32-byte wall records;
  - indexed ground draws;
  - an island's water level from the ring around it.
- Tag an alpha.

### 3. The renderer's detail (about 42 h)
In this order, each held by L1 at the boards' views and by the engine's own references:
- **Facades.** Flat detail is drawn by the wall's own shading from its record:
  - windows, dark or lit by their own sun bits, with deep windows, sashes, shutters and the glass gradients;
  - plinths, cornices, shop glazing and material faces;
  - church windows;
  - tradition walls and roofs (the tiles must carry the traditions' entries);
  - doors and house numbers.

  Chimneys, dormers, hoist beams, shopfronts, signs and terraces are geometry with depth.
- **Trees and things:**
  - trees with their clumps;
  - lamps, benches, racks, baskets, bollards, railings and sett marks;
  - towers, turbines turned to the wind, cranes, masts, gantries, conveyors, coasters and power lines;
  - woods: the renderer's lattice at Efteling and ARTIS, anchored to the world;
  - the overlays: rails, big roads, runways with their designators, taxiways, marine lines and park edges.
- **Water:** quays, reflections, sun glitter, drizzle rings, night streaks and houseboat cabins.
- **Effects:**
  - the crown, leaf, foliage and canopy filters, with the per-tree fields on the GPU in plain float;
  - the soft shadow blur, ripple and glints;
  - cloud shadows, rain and the golden wash;
  - clouds and the sun's glow when the sky shows.
- **Textures while moving:** a switch between "fixed to the screen" and "fixed to the surfaces", for the owner to judge on the phone. The lean:
  - paper grain on the screen;
  - `mat` and ripple on their surfaces, rescaled with the zoom so their density on screen stays the same;
  - cloud shadows moving with the ground.
- **Night:** lamp pools, lit streets, wet cores, the glows of open places, lit shops and windows, and the city glow.
- **The renderer's other ten regions** get their render packs as their features land, each with its own profile.
- Tag an alpha after the water and another after the night. By then the Westerkerk home is within about 2.5 levels of its render, apart from the renderer's named issues.

### 4. The world (about 55 h)
- **Street level for the whole world:**
  - render packs from every Geofabrik extract, built on GitHub in windows, so a building on a window's edge is drawn once;
  - inside the renderer's regions, each region's own profile, so Amsterdam and the others draw exactly as from their own packs (a check compares the frames);
  - sharded to fit free runners, and rebuilt monthly;
  - the phone reads what it shows by ranges and keeps it, and whole regions download for offline use.
- **Levels:**
  - the simplified level from the keep codes;
  - blocks of merged footprints from about 5 km out;
  - fades, and a tile's ancestor drawn until the tile arrives;
  - the triangle governor.
- **Mid zooms everywhere:** the world's mid-zoom base, read by ranges and kept on the phone.
  - It holds land cover by the look's `LAND` kinds, water, roads by class, places and built-up areas.
  - It is built on GitHub from Geofabrik's extracts, by Planetiler running Atlas's own profile, and written in Atlas's format by the packer.
- **The globe:** a small bundled base from Natural Earth and OSM's coastline, in the Globe board's style, with the true terminator and city lights.
- **Names:** places and water everywhere, in every script, with collisions and fades.
- **The style ladder:** the renderer's far rules continued, blending into the Globe board's look.
- **The app off OpenMapTiles:** its addresses, way names and POIs come from Atlas's packs, and `packs.yml` stops building OpenMapTiles tiles.
- **References around the world:** a big city, a mountain town, a desert, a coast, the open sea and the poles.
- Today's map leaves the phone. Tag an alpha.

### 5. Finish the map (about 25 h)
- Markers, the route and the location dot, drawn by the core in the map's own frame.
- Taps on buildings: a hit test against the tile's own buildings, giving the OSM id.
- **Power:** ADPF, and a short thermal ladder measured on the owner's phone:
  - 2x MSAA while moving, where the GPU has it;
  - the offscreen passes at half resolution while moving;
  - follow mode at 30 fps.
- **Terrain and relief everywhere,** from the best elevation model for each region and a global one elsewhere, with the dunes and San Gimignano among them.
- Williamsburg in `real.py`'s own look, if the owner says yes.
- **Cleanup:**
  - the CPU frame path, the anchors and `SvgWarp` leave the app;
  - the old plan's citations (`plan-vector D…`, `K…`) leave the code's comments.
- Tag an alpha.

### 6. The engine becomes the main piece (about 15 h)
- **Data:** the look's derivations (looks, traditions, heights, roof shapes, water levels, label candidates) move from the port's loaders into the packer's own code, so the packer no longer needs the port.
- **The app:** what it still takes from the port (the packs library and readers, the Overpass client, the moment and the weather themes, the shade walk's scoring) moves into modules of its own.
- **Retire the renderer:** `render/`, the Kotlin port `:render` and the CPU raster leave the build. They are kept in git history, or in an archive if the owner prefers.
- Tag the final alpha.

## The owner's answers and open questions

**Answered:**
- Vulkan, with no GLES path unless a phone needs one.
- Kotlin for the app side only.
- Full density, with a lower internal resolution only as a measured fallback.
- Never Google.
- Global: the map covers the entire world.
- The close-range look (quays, reflections, doors, shopfronts) applies across Amsterdam.
- The city continues past the renders' radius.
- Canal names read upright, with hysteresis.
- The canal ring is bundled for the first look offline.
- The look is refined in the engine, not in the renderer. The renderer and its port are frozen as the yardstick from 20 October 2026.

**Open**, each with a default so work never waits:
- **The renderer's known issues:** melting buildings is fixed by design (true depth). Others are fixed in the engine as they are found and named in the case files (the default), or the owner lists them to be fixed first.
- **Textures while the map moves:** decided on the phone with step 3's switch. The default is the lean given there.
- **The crooked windows:** as the renders draw them (the default), kept in one place so one edit changes them.
- **Williamsburg in its own look:** yes (the default), done last.
- **The close-range look beyond Amsterdam:** no (the default).

## How to work

- **Where.** One agent, in its own worktree under `/Volumes/Download/atlas/<name>/`, on its own branch. The main disk has only a few GB free.
- **Keep the Mac awake and on power** while the agent runs.
- **Commits.** Commit in small steps, by explicit paths, in the repo's style, each message ending with the co-author line. When a step's tests pass:
  1. fast-forward `app` in the main checkout;
  2. push it: `git push https://github.com/atlas-maps-app/Atlas.git app`;
  3. let CI decide;
  4. at the end of a step, tag an alpha and tell the owner.
- **Ask first** before publishing anything else: render packs, other release assets, or a new workflow's first run.
- **Stop at the owner's budget cap:** commit tested work, report, and wait.
- **Build.** In `app/android/mapcore/cpp`, with `PATH=$HOME/Library/Android/sdk/cmake/4.1.2/bin:$PATH` and `ATLAS_REQUIRE_VULKAN=1`:
  - `cmake --preset host-debug`;
  - `cmake --build --preset host-debug -j3`;
  - `ctest --preset host-debug -j2`;
  - the same for `host-tsan` and `host-release`.
- **Android:** `./gradlew :mapcore:assembleDebug`.
- **The JVM checks:** `:mapcorecheck:test` and `:likeness:testDebugUnitTest`.
- **Gradle settings:**
  - flags: `--no-daemon --max-workers=2 -Patlas.tools=<the main checkout>/cache/atlas-tools`;
  - `GRADLE_USER_HOME` on the Download volume;
  - `GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches`.
- **Load.** Run lavapipe with `LP_NUM_THREADS=2`, and one heavy step at a time: the Mac has 14 GB and 8 cores.
- **zsh.** The shell is zsh, and a variable does not split into words. Use while-read loops or `--pathspec-from-file`.

**Learned the hard way:**
- **Python's `min`, `max` and `round` go through pymath** (`pymath::min`, `pymath::max`, `pymath::round`), and Kotlin's through `pymath::java`. clang -O2 on arm64 turns a select against zero into `fminnm`, which orders -0.0 below +0.0.
- **No specialization constants in shaders.** PowerVR's compiler crashed on one; a variant is its own module.
- **Use `SV_VulkanVertexID` and `SV_VulkanInstanceID`,** never `SV_VertexID` or `SV_InstanceID`: Slang's x86_64 build emitted different bytes for those.
- **Announce each driver call that can crash** to the crash guard (`gpu::announce`), for that call alone.
- **No blend reads the destination's alpha,** because colour attachments may be 10-bit.
- **Timing gates hold only with `ATLAS_TIMED=1`** on a quiet machine.
- **The renderer's reference is a fresh run of `render/*.py`.** `cache/render` is the owner's and may be outdated: never write there.
- **Never read the renders' big SVGs into context.** Script them instead.
