# Atlas's map engine

Atlas draws its map with its own engine. This is the one document about it: what it is, how it works, what exists and what is left. It replaces every earlier plan, brief and note about the engine. Those are archived in `cache/claude-session/archive/engine-2026-10/` as history, and nothing here needs them.

## What it is

- **The centerpiece of the app, purpose built for Atlas.** It is done first, and the rest of the app builds on it.
- **Built from the ground up.** A C++23 core on Vulkan (`app/android/mapcore/cpp`), behind a small Kotlin façade (`app.atlas.mapcore.MapCore`). It draws vector tiles every frame the camera moves, at the screen's own density, and nothing at rest. Labels are placed again for each view, and detail comes and goes with the zoom.
- **Global, and it builds itself.** The map covers the entire world, from the globe down to a single street. The phone gathers up-to-date map data for any place, at any time, and builds the map itself. Pre-built packs are additions, not a requirement.
- **Independent.** The app never relies on a single source. Every input has more than one way in, and the map keeps working offline with what it has.
- **Matches the renderer.** The Python renderer (`render/*.py`, ported byte for byte to Kotlin in `app/android/render`) is the starting point for the look: its features, rules, colours and light.
  - The engine matches it exactly, except where the renderer is wrong.
  - The renderer is unfinished and has its issues. Each issue the engine fixes is named, such as melting buildings.
  - The renderer is frozen as the yardstick: the look is refined in the engine, which goes on in the renderer's spirit where it stops.
  - When the engine is finished, the renderer and its port are retired.
- **Custom where it counts.** The data model, tile format, rules, look, passes and shaders are Atlas's own. No general map engine and no third-party tile schema: OpenMapTiles is abolished.
  - An outside piece comes in only where it is cutting edge, fits, and plugs in without a sacrifice. Today that means Vulkan-Hpp, VMA, zstd, HarfBuzz, earcut.hpp and doctest in the core, Slang for the shaders, and Planetiler as the build tool for the world's mid zooms.
  - Each is pinned (`cpp/third_party/VERSIONS.md`) and can be replaced.
- **As cutting edge, optimized and efficient as possible, without sacrifices.** 60 fps while moving, idle and cool at rest, on any Vulkan phone. Every choice is made at run time from each phone's own facts, never tuned to one phone.
- **Easy to keep cutting edge.** The engine is built to be improved and refined for years: new hardware features, better techniques and newer libraries go in one piece at a time.

## The design

1. **The phone builds the map itself, anywhere, anytime, from more than one source.** It gathers up-to-date data for any place in the world, and the core builds the tiles on its workers:
   - **OpenStreetMap,** tile by tile with margins, so a building on a tile's edge is built once. The same data can come three ways, and the phone takes whichever answers:
     - several Overpass servers (Overpass is the renderer's own source), failing over from one to the next;
     - Geofabrik's regional extracts, read on the phone itself;
     - Atlas's own packs.
   - **The renderer's regional sources,** such as AHN and the tides in the Netherlands.
   - **Elevation** from the best model for each region (national lidar where it exists) and a global model elsewhere, each from more than one host.
   - Everything built is kept, so a place seen once works offline.

   **Packs are additions, not a requirement.**
   - Atlas's packer builds them on GitHub with the same code, for offline use and an instant first look. A packed tile and a tile the phone builds from the same data are the same, byte for byte.
   - They come from Geofabrik's extracts, which are made for bulk processing. Overpass suits a phone's live queries, but its public servers limit bulk use (about 10,000 queries and 1 GB a day).

   **The far zooms come from two small world bases,** built the same way:
   - a mid-zoom base, with Planetiler running Atlas's own profile into Atlas's format, published in more than one place;
   - a globe base, bundled in the APK.

   No live source can hand a phone whole regions and countries at once, so these are the only prebuilt data the map needs.
2. **Render tiles** (pack format 3) hold every value the look derives from the data, already derived: looks, traditions, heights, roof shapes, water levels, label candidates.
   - The core's stage P derives them on the phone; the packer runs the same code.
   - The values are stored in columns compressed with zstd, with a keep code per vertex for every simplification level.
   - A tile is memory-mapped, decoded straight into the GPU's heaps, and kept on disk once built. It is refreshed by asking Overpass only for what changed since.
3. **The style is the renderer's:** building traditions by country, and place-specific profiles wherever a place needs one.
   - Close up across Amsterdam: the living map's profile.
   - Williamsburg: `real.py`'s profile, if the user says yes.
   - Everywhere else: the regions' rules, with each country's building tradition.
   - At the planet: the Globe board's look.
   - The colours, widths, rules, traditions and profiles live in the engine's own style file, which the tiles carry. Refining the look, or adding a tradition or a profile, is an edit to data.
4. **Compact records, expanded on the GPU.** Walls, wall cells, windows, roof faces, trees and street furniture are records of a few bytes that the vertex stage expands, and repeated detail is instanced. Draws are indexed and batched per pipeline. meshoptimizer is the candidate for vertex-cache order and quantised vertices, if it measures better.
5. **One pass in tile memory.** The main render pass has a scene subpass and a composite subpass at 4x MSAA, with its attachments kept in tile memory where the GPU allows.
   - The composite applies the screen effects once per pixel: haze, vignette, grain, `mat`, the wash and cloud shadows.
   - Offscreen passes run before it only where an effect needs one: blurs, crowns, reflections, glows.
   - Newer Vulkan features are used where the phone has them, picked at run time from VK facts: dynamic rendering with local reads, synchronization2, present timing, and fragment shading rate for the thermal ladder.
6. **True depth for everything that stands up.** Buildings, parts, roofs, trees and things are drawn with a real depth buffer, kept in tile memory beside the samples, and tile by tile from the nearest for early rejection. They occlude correctly at every angle.
   - The renderer orders whole items by one point each, so at some angles its buildings melt into each other. The engine does not copy that.
   - The depth is reversed: near / k.z, in float with stencil where the phone has it (D32S8), else 24-bit, with the depth tests GREATER (`shaders/shared/painter.slang`).
   - The ground keeps the renderer's layering on its one plane: land cover, areas, roads, water and shadows, in its order. Its painter slots take the far end of the depth range, behind every true depth, so what stands up is drawn over the ground as the renderer draws it. Terrain (step 6) gives the ground a surface's true depth.
   - Where faces of one building touch, two small leads keep the renderer's order: a later cell's edge covers an earlier one's, and a roof covers the edge of its walls. Faces of two buildings that touch meet where their geometry puts them.
   - Flat detail on a wall (cells, windows, sashes, shutters, plinths, cornices, shop glazing, doors, numbers) keeps the renderer's exact shapes. It is expanded on the GPU from the wall's record and drawn clipped to its own wall, so nothing fights for depth.
   - A switch (`FrameOptions::painter`) draws the renderer's painter's order instead, to compare the two side by side; the owner maps are drawn under it.
   - The painter ranks stay, for what is translucent and for the detail bands. So the twin still emits in the renderer's order, and only its test raster gains depth.
   - What stands out from a wall (chimneys, dormers, hoist beams, shopfronts, signs, terraces) is geometry with depth.
7. **Light and sun.** The renderer's light model and themes for the moment, and the sun test at every sample point, recomputed on the workers when the sun moves 0.5°. The moment's weather comes from more than one source, and the sun alone sets the moment when none answers.
8. **Text.**
   - Names on the ground (water and canal names along their ways) are shaped by HarfBuzz as Android shapes them, and drawn from the glyphs' outlines (Slug).
   - Place names in every script are sprites drawn by Android's own text stack.
   - The core places them all for every view, with collisions and fades.
9. **Levels** run from the street to the planet: full detail, a simplified level from the keep codes, blocks of merged footprints, the mid-zoom base, the globe. Fades and a triangle governor mean nothing pops and nothing is a hole.
10. **Work only when needed.** Frames come on vsync only while something changes, with ADPF hints. Tiles, the sun and the noise fields are done on background workers. Nothing is done per frame that can be done once.
11. **Deterministic everywhere, exact wherever the result depends on it.**
    - The core's maths library (`pymath`: fdlibm as Java defines it, with Python's rounding and random stream) gives the same bits on every phone and host. So frames can be compared, and no decision differs between devices.
    - Where the renderer's arithmetic decides a pixel, the engine uses it:
      - the noise fields byte for byte, on the CPU, and on the GPU with exact fused multiply-adds where the device passes a byte check;
      - the haze and vignette in whole numbers.
    - Plain GPU float is used only where it gives the same pixels.
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
- **The bar is the renderer's result exactly, apart from its named issues, at full speed.** Exactness is built wherever the result depends on it. The heavier fallbacks (integer blending, analytic coverage) are built only if a check shows a difference.
- **The phone builds the map itself, anywhere, from live data.** The look's derivations move into the core as C++, so the phone builds natively, and the packer uses the same code. Packs become optional additions, for offline use and a fast first look.
- **All data is Atlas's own.** OpenMapTiles is gone; Planetiler stays only as a build tool, running Atlas's own profile.
- **The checks that prove the result stay; the rest go.** Every check already built keeps running, and the full set of the renders' views runs nightly. The old plan's extra gates that were never built are not built.
- **No cut changes the result.** A simplification may change how the engine gets there, never what Amsterdam or any rendered place looks like. The engine's references prove it.
- **Two pieces get simpler.** VK check (never built) becomes VK facts' hash lines. Taps on buildings use a hit test against the tile's own buildings instead of an ID pass.
- **One agent works through one ordered list,** instead of waves of packages. The engine comes before the rest of the app.

## Checks

**While the renderer exists, it is the yardstick:**
1. **Parity with the port:**
   - the core's stage P against the port's loader, record by record and field by field, from the same data;
   - the sun's answers, stage T's points, tile decoding, and pymath bit for bit;
   - the phone's tiles against the packer's, byte for byte.
2. **Element by element:** the twin (the core's per-frame code and the shaders' maths compiled as C++) emits what the GPU will draw. `L1Test` matches it against the port's SVG at the boards' views and the sweeps: in order for the ground, item by item for what stands up. Every new pass brings its twin and its cases.
3. **The GPU against the twin** on lavapipe, per pass.
4. **Likeness:** the smoke cases (the Westerkerk home and the Main board, by day and at night) against the port's renders on every push.
5. **The full set:** every named view of the renders, the sweeps and the sizes, nightly on CI. The planted failures that already exist keep running.

**Where the renderer is wrong, the engine does it right.** The difference is named in the case files (melting buildings is the first), so the yardstick never pulls the engine back to the renderer's issues.

**The engine's own references, from step 1.** These are its frames at fixed views and moments: the boards' views first, then places around the world from step 4. They are stored per engine version, and every finished part is held to them. Every simplification is proven against them: Amsterdam and every other rendered place must turn out the same.

**Always:**
6. **CI:**
   - `core.yml`: host builds and tests on x86_64 and arm64 under the validation layer, sanitizers, fuzzing, clang-tidy and the rule table.
   - `android.yml`: the APK, every module's tests, and the emulator run of every screen through `ci/smoke.sh`.
7. **The user's phone at each alpha:**
   - VK facts;
   - VK bench, with presented frames at p50 ≤ 16.7 ms and p95 ≤ 20 ms on the paths;
   - the ten-minute soak, with no climb in thermal headroom;
   - a look.

## What exists so far

Nothing is finished. Each part below is a first version on `app`. It gets reworked wherever the design above asks for more.

| Part | State |
|---|---|
| Toolchain and CI | NDK r30, CMake presets for the hosts and Android, Slang shaders checked at build time, lavapipe under the validation layer |
| Vulkan device layer | VK facts, the crash guard, frame configurations, both frame routes, the pipeline cache and the SurfaceView host; the VK facts and VK bench screens, measured on the user's phone |
| Data | render tiles, the packer's `render-tiles` and the core's reader (fuzzed); the Westerkerk and the canal ring bundled; render packs that build for `amsterdam` and `grachten` |
| Numerics and style | pymath; the light, the theme and `ScreenLayers`; the rule table, which lists every line of the renderer that draws |
| Frame core | camera, ladder, cover, residency, heaps, ranks and pass registry; the composite (haze, vignette, `mat`'s rule, grain); the twin and L1; the coarser pack around the active one, chosen as the smallest pack that holds it; the packs' own stage T (`Pass::build_pack`) |
| Sun | the sun test on the workers, equal to the port on every sample of 21 board renders; a lane for the pack drawn and one for the coarser pack around it |
| Ground and water | drawn and registered; L1 matched in all 11 cases |
| Buildings, first half | walls, parts, flat and gabled roofs and houseboats, with sun bits and edges, at their true depth (a pair the renderer melts is drawn right; the switch draws the renderer's order); held to the port's polygons and on lavapipe to a depth raster of the twin; registered |
| Text | shaping, glyphs, canal names (read from the whole pack), and the board's labels placed; registered, the fonts from the map's resources |
| Noise fields, thin strokes | WebKit's turbulence byte for byte (scalar and NEON); `ObjectStrokes` |
| Frosted glass | drawn by the core under the chrome |
| Likeness harness | references from the port, candidates from the host build |
| The app | the core draws the maps where it has tiles, under today's map and the overlays, cross-fading to today's map (the port on Canvas) elsewhere; the honest state where the phone cannot draw (three device losses included); the painter switch on VK facts in debug builds |

**Pending work, not on `app`:** `wip/exact-haze`, the haze and vignette in whole numbers, equal to the twin (step 2). Run host-debug, host-tsan and the oracle comparison before merging.

The other local branches are history; `wip/build-pack`, `wip/buildings-registration`, `wip/app-map` and `wip/text-registration` landed on 20 October 2026. The old builders' worktrees went to the Trash on 20 October 2026; their work is on `app` or these branches.

## What the deep dive found (20 October)

Before anything is built on them, the app's skeleton, the core and the modules were read for what should be done differently:
- **The core's painter's order is one contained piece.** It lives in `shaders/shared/painter.slang` and `frame/Ranks`. True depth changes what the objects' vertex stage writes, the ground's hand-over and the depth format, not the frame core.
- **The tile store already opens packs from memory, files and remote ranges.** So tiles built on the phone become local packs, with no second tile path (step 3).
- **The app's map still pulls in the CPU stack.** `:mapview` exports `:render` and `:anchor` to the app; they leave with the CPU map at the end of step 3 (the port itself in step 7).
- **The rest holds:** the device layer, frame core, scene, tile reader, sun, text, noise fields, the façade and the toolchain are current and fit the design.

The app's own findings were app work, done outside this plan on 20 October:
- one persistent map at the app's root instead of a map per screen (`MapStage`);
- one set of Overpass servers: the map's builds ask the app's own, in its order;
- a second weather source (MET Norway);
- the app's features off OpenMapTiles. Places, addresses, street names and bridges read the map's own packs through `LocalData`, and Overpass where no pack reaches: one request per route for its steps' names, one per route for its bridges. OpenFreeMap, the tile cache, the tile readers and the Planetiler step are gone. Packs built on the phone are built again after a week. An offline region's pack is read by the map and the features, and a region beyond the published packs has its pack built on the phone (up to about 1,000 km²).

The engine builds on them: step 3's "the core asks, the app fetches" has its fetching side in `LocalData` and the pack library.

## What is left, in order

The engine is done first: it is the app's centerpiece, and the rest of the app builds on it.

### 1. The new map on the phone
- Done: true depth, the pending branches (the packs' stage T, the buildings and the text registered, the app's map), the canal ring around the Westerkerk with its sun bits, the honest state after three device losses, and the bundled render packs on `core.yml`'s host jobs.
- Turn on the ground's `mat` (`ATLAS_GROUND_MAT`).
- **The desktop render tool:** the host build draws any view and moment to a PNG.
- **The engine's own references begin:** the boards' views and moments, stored per engine version.
- The core draws where it has tiles. Today's map (the renderer's Kotlin port drawing on Canvas) stays everywhere else until the end of step 3.
- **Prove it:** run every preset, tidy, `L1Test` and `StageTParityTest`, push, and get CI green. Then tag an alpha.
- **On the user's phone:**
  - the first picture within 0.5 s warm;
  - VK bench on the Main board and Westerkerk paths;
  - the soak;
  - the frosted pills in the same frame as the map.

### 2. Every roof, the labels, the style file
- **Roofs:**
  - hipped, pyramidal and mansard roofs;
  - the `CLOSEST` roof mapping;
  - gable triangles in the wall's material, with their 0.25 px edges;
  - the roofs' edge strokes through `ObjectStrokes`.
- **The exact haze:** merge `wip/exact-haze`.
- **Labels** are drawn by the core's frame instead of the Compose canvas, with the fonts loaded at the map's start.
- **All of Amsterdam from the core:** `PackList.kt`, then the first publish of the `amsterdam` and `grachten` render packs after the user's yes (a `packs.yml` run with `renders=only`).
- **The style file:** the tiles' style section becomes the engine's own, edited as data. Every pass reads its colours and widths from it, before step 4 adds more.
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

### 3. The phone builds the map anywhere
- **Stage P in the core.** The look's derivations move from the port's loaders into C++:
  - looks and traditions;
  - heights and roof shapes;
  - water levels and the sea from the coast;
  - party walls and multipolygons;
  - label candidates and keep codes.

  They are held record for record to the port's, from the same data.
- **The core asks, the app fetches.** Remote packs already work this way: the core asks for a box's data on its I/O thread, and the app's OpenStreetMap client answers.
- **Built tiles become local packs.** A C++ writer of format 3 turns each built window into a small pack, which the store opens like a downloaded one.
- **The packer runs the core's stage P** through the host build, so `:rendertiles` stays only as the Kotlin reference.
- **Budgets:** a window builds within about 50 MB of memory, in a few seconds on a mid-range phone, on background threads.
- **Live data from more than one source:**
  - several Overpass servers, failing over;
  - Geofabrik's regional extracts, read natively on the phone (libosmium is the candidate reader);
  - Atlas's packs;
  - the renderer's regional sources (AHN and the tides in the Netherlands), and the best elevation model for the place, from more than one host.

  The same data gives the same tiles, whichever source it came from.
- **Built in the background:** on the core's workers at background priority, nearest tiles first, prefetched along the camera's motion, never slowing a frame. Tiles are kept in the cache and refreshed by asking Overpass only for what changed since.
- **Packs become additions:** the packer runs the same C++ through the host build, so a pack's tiles equal the phone's byte for byte. Regions can be downloaded for offline use and an instant first look.
- **Exactly the renderer:** the phone's tiles at the boards' views equal the packer's from the same data, and the frames equal the references.
- **The old render approach leaves the phone.** The core now draws every place at street level, so today's map goes, with its CPU frame path, the anchors and `SvgWarp`. The CPU raster stays only in tests, as the likeness oracle.
- Tag an alpha.

### 4. The renderer's detail
In this order. Each feature's derivation goes into the core's stage P, and each is held by L1 at the boards' views and by the engine's own references:
- **Facades.** Flat detail keeps the renderer's exact shapes, drawn clipped to its wall:
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
  - the crown, leaf, foliage and canopy filters, with the per-tree fields made on the GPU, exact where the device passes the byte check and from the CPU elsewhere;
  - the soft shadow blur, ripple and glints;
  - cloud shadows, rain and the golden wash;
  - clouds and the sun's glow when the sky shows.
- **Textures while moving:** a switch between "fixed to the screen" and "fixed to the surfaces", for the user to judge on the phone. The lean:
  - paper grain on the screen;
  - `mat` and ripple on their surfaces, rescaled with the zoom so their density on screen stays the same;
  - cloud shadows moving with the ground.
- **Night:** lamp pools, lit streets, wet cores, the glows of open places, lit shops and windows, and the city glow.
- **The renderer's other ten regions** get their render packs as their features land, each with its own profile.
- Tag an alpha after the water and another after the night. By then the Westerkerk home is within about 2.5 levels of its render, apart from the renderer's named issues.

### 5. The world
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
- **Packs for offline use:**
  - regions are built monthly on GitHub from Geofabrik's extracts with the core's stage P, and downloaded from the app;
  - inside the renderer's regions, each keeps its own profile;
  - a check compares their frames with the phone's own builds.
- **References around the world:** a big city, a mountain town, a desert, a coast, the open sea and the poles.
- Tag an alpha.

### 6. Finish the map
- Markers, the route and the location dot, drawn by the core in the map's own frame.
- Taps on buildings: a hit test against the tile's own buildings, giving the OSM id.
- **Power:** ADPF, and a short thermal ladder measured on the user's phone:
  - 2x MSAA while moving, where the GPU has it;
  - the offscreen passes at half resolution while moving;
  - follow mode at 30 fps.
- **Terrain and relief everywhere,** from the best elevation model for each region and a global one elsewhere, with the dunes and San Gimignano among them.
- Williamsburg in `real.py`'s own look, if the user says yes.
- **Cleanup:** the old plan's citations (`plan-vector D…`, `K…`) leave the code's comments.
- Tag an alpha.

### 7. The engine becomes the main piece
- **The app:** what it still takes from the port (the packs library and readers, the Overpass client, the moment and the weather themes, the shade walk's scoring) moves into modules of its own.
- **Retire the renderer:** `render/`, the Kotlin port `:render`, `:rendertiles`' Kotlin derivation and the CPU raster leave the build. `:mapview` then depends on `:mapcore` alone for drawing. They stay in git history, or in an archive if the user prefers.
- Tag the final alpha.

## The user's answers and open questions

**Answered:**
- Vulkan, with no GLES path unless a phone needs one.
- Kotlin for the app side only.
- Full density, with a lower internal resolution only as a measured fallback.
- Never Google.
- Global: the map covers the entire world.
- The phone builds the map itself, anywhere and anytime, from up-to-date data; packs are additions, not a requirement.
- The app is as independent as possible, and never relies on a single source.
- The close-range look (quays, reflections, doors, shopfronts) applies across Amsterdam.
- The city continues past the renders' radius.
- Canal names read upright, with hysteresis.
- The canal ring is bundled for the first look offline.
- The look is refined in the engine, not in the renderer. The renderer and its port are frozen as the yardstick from 20 October 2026.

**Open**, each with a default so work never waits:
- **The renderer's known issues:** melting buildings is fixed by design (true depth). Others are fixed in the engine as they are found and named in the case files (the default), or the user lists them to be fixed first.
- **Textures while the map moves:** decided on the phone with step 3's switch. The default is the lean given there.
- **The crooked windows:** as the renders draw them (the default), kept in one place so one edit changes them.
- **Building anywhere sooner:** the renderer's Kotlin port could build tiles on the phone first, exact but slower and heavier, until the C++ lands. The default: straight to C++.
- **The sources for many users:** with several Overpass servers, Geofabrik and the packs, no one server carries the app. Before a public release, decide whether Atlas adds its own mirror. The default: public sources, with caching and changed-since refreshes.
- **Williamsburg in its own look:** yes (the default), done last.
- **The close-range look beyond Amsterdam:** no (the default).

## How to work

- **Where.** One agent, in its own worktree under `/Volumes/Download/atlas/<name>/`, on its own branch. The main disk has only a few GB free.
- **Keep the Mac awake and on power** while the agent runs.
- **Commits.** Commit in small steps, by explicit paths, in the repo's style, each message ending with the co-author line. When a step's tests pass:
  1. fast-forward `app` in the main checkout;
  2. push it: `git push https://github.com/atlas-maps-app/Atlas.git app`;
  3. let CI decide;
  4. at the end of a step, tag an alpha and tell the user.
- **Ask first** before publishing anything else: render packs, other release assets, or a new workflow's first run.
- **Stop at the user's budget cap:** commit tested work, report, and wait.
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
- **The renderer's reference is a fresh run of `render/*.py`.** `cache/render` is the user's and may be outdated: never write there.
- **Never read the renders' big SVGs into context.** Script them instead.
