# Atlas's map engine

Atlas draws its map with its own engine. This is the one document about it: what it is, how it works, what exists and what is left. It replaces every earlier plan, brief and note about the engine. Those are archived in `cache/claude-session/archive/engine-2026-10/` as history, and nothing here needs them.

## What it is

- **The centerpiece of the app, purpose built for Atlas.** It is done first, and the rest of the app builds on it.
- **Built from the ground up.** A C++23 core on Vulkan (`app/android/mapcore/cpp`), behind a small Kotlin façade (`app.atlas.mapcore.MapCore`). It draws vector tiles every frame the camera moves, at the screen's own density, and nothing at rest. A label stays where it is on the map and grows and shrinks with the zoom, like the map itself, and detail comes and goes with the zoom.
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
   - A switch (`FrameOptions::painter`) draws the renderer's painter's order instead; the owner maps are drawn under it. The user likes that look: drawn in the renderer's order, melting included, the map reads more like a drawing. So both stay. True depth is what the engine is built and tested for, and the painter's order stays a supported look, kept working as features land, not only a comparison. On the phone it is the app's Look settings' True depth chip (every build; the user, 23 October), and VK facts' Buildings button in debug and bench builds.
   - The painter ranks stay, for what is translucent and for the detail bands. So the twin still emits in the renderer's order, and only its test raster gains depth.
   - What stands out from a wall (chimneys, dormers, hoist beams, shopfronts, signs, terraces) is geometry with depth.
7. **Light and sun.** The renderer's light model and themes for the moment, and the sun test at every sample point, recomputed on the workers when the sun moves 0.5°. The moment's weather comes from more than one source, and the sun alone sets the moment when none answers.
8. **Text.**
   - Names on the ground (water and canal names along their ways) are shaped by HarfBuzz as Android shapes them, and drawn from the glyphs' outlines (Slug).
   - Place names in every script are sprites drawn by Android's own text stack.
   - The core places them, with collisions and fades. Once placed, every label stays fixed where it is on the map and grows and shrinks with the zoom, like the map (the user, 21 October: "The text should be fixed where it is and only update in size"; then "every label stays where it is and grows and shrinks with the zoom, like the map"): no re-placing at rest, no sliding or jumping between its spots while the map moves, no fixed screen size. Since labels and the map scale together, a placement free of collisions stays free at every zoom.
     - **A zoom band per label.** Each label scales with the map only within a band around the zoom it is drawn for, about 0.7 to 1.5 times its size there; beyond it, it fades out and the next level's labels fade in (quarters and districts out of town, streets and buildings close in).
     - **A readable floor.** No label draws below a minimum size, which follows Android's text-size setting.
     - Names over the map, of places and of waters alike (the quarters, the IJ, the Amstel), stay level on the screen when the map turns: they only stay put and grow and shrink with it (the user, 21 October). Names on the ground, such as the canal names, follow their water and read upright, with hysteresis.
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
   - The nightly's full tier runs in two shards. Each judges the planted failures whose base case falls in it, and the smoke tier judges every kind on every push. The nightlies first ran on 23 October, once `app` became the default branch: the second shard holds only the board's plants (shift, drops), and `PlantedTest` failed it for want of the paint plant until its reference set said it was a shard.

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
| Vulkan device layer | VK facts, the crash guard, frame configurations, both frame routes, the pipeline cache and the SurfaceView host; the VK facts and VK bench screens, measured on the user's phone; VK facts' hash lines (pymath, the sun, the noise fields, the ground, the twin), held to the host build's on CI's emulator, and its composite check, behind a guard stage of its own |
| Data | render tiles, the packer's `render-tiles` and the core's reader (fuzzed); the core's writer of tiles and packs, with their head and style, the packer's bytes; the core's reader of Overpass's JSON, the living map's and a region's load from it with every look (the port's records) and their cut into tiles (the packer's bytes); windows, one L14 tile built from its box's data as a pack of its own, built again after a week from Overpass's changes merged into the data it keeps (and from that data alone when the core's version changes), a frame cell's windows one pack in the store, drawn alone or beside the bundled packs; the Westerkerk and the canal ring bundled; render packs that build for `amsterdam` and `grachten` |
| Numerics and style | pymath; the light, the theme and `ScreenLayers`; the style file (`app/android/mapcore/style/engine.jsonc`), which every render pack carries and the core reads, typed, as a pack opens; the rule table, which lists every line of the renderer that draws |
| Frame core | camera, ladder, cover, residency, heaps (one buffer and one virtual block of a 64 MB budget; a tile that finds no room waits for a range to go), ranks and pass registry; the composite (haze, vignette, `mat`'s rule, grain); the twin and L1; the coarser pack around the active one, chosen as the smallest pack that holds it; the packs' own stage T (`Pass::build_pack`) |
| Sun | the sun test on the workers, equal to the port on every sample of 21 board renders; a lane for the pack drawn and one for the coarser pack around it |
| Ground and water | drawn and registered; L1 matched in all 11 cases; the soft shadows on the port's own filter grid, their boxes and fade bit for bit |
| Buildings, first half | walls, parts, every roof shape and houseboats, with sun bits, edges and the roofs' thin edges, at their true depth (a pair the renderer melts is drawn right; the switch draws the renderer's order); their flat windows, dark or lit, with the glass gradients; held to the port's polygons and on lavapipe to a depth raster of the twin with its strokes; registered |
| Text | shaping, glyphs, canal names (read from the whole pack), and the board's labels drawn by the core, fixed on the map and scaled with it; registered, the fonts from the map's resources |
| Noise fields, thin strokes | WebKit's turbulence byte for byte (scalar and NEON); `ObjectStrokes` |
| Frosted glass | drawn by the core under the chrome |
| Likeness harness | references from the port, candidates from the host build, with their owner maps (real since 21 October); the engine's own references (`likeness/engine.json`); the desktop render tool |
| The app | the core draws the maps where it has tiles, under today's map and the overlays, cross-fading to today's map (the port on Canvas) elsewhere; the honest state where the phone cannot draw (three device losses included); the painter switch on VK facts in debug builds |

The local branches are history; `wip/build-pack`, `wip/buildings-registration`, `wip/app-map` and `wip/text-registration` landed on 20 October 2026, and `wip/exact-haze`'s last commit (the rest was on `app` already) the same night. The old builders' worktrees went to the Trash on 20 October 2026; their work is on `app` or these branches.

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
- Done: true depth, the pending branches (the packs' stage T, the buildings and the text registered, the app's map), the canal ring around the Westerkerk with its sun bits, the honest state after three device losses, the bundled render packs on `core.yml`'s host jobs, the ground's and the buildings' `mat`, the desktop render tool and the engine's own references.
- What registering the buildings showed, and what was done:
  - the Main board at S1 overflowed the geometry heaps (a buffer per kind, each doubling within the budget): the heaps are one buffer and one virtual block of the whole budget, and a tile that finds no room is not built again, frame after frame, until a range goes back. The budget is 64 MB, D3's 40 MB until the walls' records are 32 bytes: the canal ring's densest tile holds 13 MB of walls, and its second sweep camera's tiles 50 MB in all;
  - the twin drew the canal names over the ground's shadows: the ground's shadows take tail slots after every pass's ground, as the renders' base layer has them;
  - L1 holds the buildings now: what stands up is matched item by item, its slivers (faces at the renders' 0.12 px² floor or seen edge-on, decided in floats against the port's doubles) counted apart, at most two in a thousand;
  - tidy over every file;
  - the emulator's smoke run (the first to run the app map's checks): the full map's first complete frame there takes 11 to 17 s (ten tiles at the home, the Westerkerk's three and the canal ring's seven, 32 MB, each built in 1 to 2.5 s on its two cores, under lavapipe's 250 to 400 ms frames), so its limits are 25 s and 20 s; the home's pills match HWUI's within MAD 0.11, the weather chip left out when its forecast came in one capture and not the other.
- **The desktop render tool:** `./gradlew :mapcorecheck:test --tests '*RenderTool*' -Datlas.render.out=<png>`, with `-Datlas.render.pack`, `view` (`home`, a ladder knot, or `lat,lon,distance,bearing,pitch`), `moment`, `size` and `mode` (`mapcorecheck`'s `likeness/RenderTool.kt`).
- **The engine's own references:** `mapcorecheck`'s `likeness/engine.json` holds each engine version's frames: the Westerkerk home and the Main board at sunny, grey, wet and night, as the app draws them (no engine mode, every effect, S1), as each platform's SHA-256. `EngineRefsTest` holds the engine to the current version on every push (CI's likeness step). A deliberate change to what the engine draws is a new version, recorded with `-Datlas.engine.record=true`; a platform without frames of the version leaves its own in the report's `engine/engine.json`.
- The core draws where it has tiles. Today's map (the renderer's Kotlin port drawing on Canvas) stays everywhere else until the end of step 3.
- **Prove it:** run every preset, tidy, `L1Test` and `StageTParityTest`, push, and get CI green. Then tag an alpha.
- **On the user's phone:**
  - the first picture within 0.5 s warm;
  - VK bench on the Main board and Westerkerk paths;
  - the soak;
  - the frosted pills in the same frame as the map.

### 2. Every roof, the labels, the style file
- Done: **every roof shape**: hipped, pyramidal and mansard roofs, the `CLOSEST` roof mapping, gable triangles in the wall's material; `L1Test` matches them.
- Done: **the roofs' thin edges**, each face's stroke as the renders draw it: .25 px in the roofs' edge colour on slopes, gable and hip ends and mansard faces, .45 px on flat tops, skillions and mansard tops, .4 px #2A2C30 on a cabin roof. An n-gon strokes its ring alone (stage T marks its triangles' edges on the ring, across points the triangulation drops), and the near plane's cut wherever it cuts a face, as the port strokes the polygon `Camera.poly` gives it.
  - Before the main pass a compute stage decides each plain primitive the frame draws once, from the frame's jobs (each drawn tile's runs, the farthest tile first, 64 a workgroup), into a record of its shape. One draw per `ObjectStrokes` variant then draws a segment along each edge a record's stroke follows. The CPU does nothing per stroke.
  - A stroke lies on its face's own plane (the reciprocal depth of the fan's triangle by its edge, carried past the edge) and 2^-16 nearer: over its own fill and over what lies behind its edge, under every nearer face. Across a triangle thinner than a pixel, whose plane tilts too steeply to carry on, it takes its edge's depth alone. Under the painter switch it draws at its face's slot.
  - Named differences from the renders: butt segments instead of round joins (`ObjectStrokes`' residual); a pixel's coverage is the exact area of the stroke's rectangle in it while visibility is decided per sample, so where a nearer face hides a stroke's inner half the outer half looks dashed at one sample a pixel and is averaged at 4x; a concave n-gon the near plane cuts twice loses its ring's bridges along the cut; at 24-bit depth the strokes' lead falls below the buffer's resolution far off, as the cells' leads do.
  - `buildings_vk_test` draws them in its exact raster (the shader's coverage in float, the depth test per sample, 8 bits a blend, in the frame's order) and holds every case within a level a blend. The engine's references are at version 4.
- Done: **the exact haze**, the composite's haze and vignette in whole numbers, bit for bit the twin's (against the port's references no case moves by more than 0.01 of MAD); the engine's references are at version 2.
- Done: **the haze and the vignette optional** (the user, 21 October: for the pure look, and they seemed to cost frame time): two chips in the app's Look settings, on by default. Off, the core's composite skips them (its per-pixel search of the haze's stops and the vignette's square root and divisions); today's map, the renderer's port, keeps them where it still draws.
- Done: **the grain optional too** (the user, 23 October): a third chip beside them, on by default (`MapCore.screenEffects`' grain, the façade's ABI 15). Off, the composite leaves out the paper grain's step; `composite_fx_vk_test` holds a frame drawn so to the twin's, with no grain in it.
- Done: **the soft shadows**, pulled forward from step 4 because the renders blur the ground's shadows in every daytime moment the app shows (`sblur`: 2.6 CSS px overcast, 0.6 with the sun between clouds).
  - The port blurs them on a grid of its own. WebKit draws a filter at most 4096² pixels large, and the shadows' filter region (their box, grown by a tenth; they reach 900 CSS px past the frame) is larger than that at the boards' views. So their grid is coarser than the device's, 0.43 of the density at the home and 0.70 at the Main board, and the renders' shadows are softer than the deviation alone makes them.
  - Each frame the GPU measures the drawn shadows' box, works out the same grid (`fx/BlurGrid.hpp`), draws their union on it into a 4x mask, runs vImage's three square boxes and the port's fade over it (`fx::square_blur_cpu`, bit for bit), and draws the result back as the port draws its bitmap, bilinear where the grid is coarser. The CPU does nothing per shadow.
  - Named differences from the port: the union's edges (4x samples against the port's coverage of each path) and the bilinear filter's rounding. At the home the frame is within two levels of the blurred result.
  - Because the grid follows the shadows' box, their softness follows the view as the camera moves, as each render would have it. The phone will show whether that reads well.
  - As the renders' other filters, they run in full mode (the app's); structural mode draws them sharp, as the likeness's structural references draw the group unfiltered.
  - Until 21 October the app's map still drew in structural mode, a setting left from before the composite's filters existed, so the phone showed sharp shadows and no `mat` or grain while the engine's references drew them. The map draws in full mode now, as the references do.
  - The engine's references are at version 3.
- Done: **the labels fixed on the map** (design point 8), drawn by the core's frame once its picture shows with nothing of today's map over it; elsewhere, and while today's map fades, the canvas draws them as before (LabelPainter, placed afresh every frame).
  - The app hands the core each label as a sprite drawn by Android exactly as the board's painter draws it (`MapCore.labels`, the façade's ABI 6), with stable ids.
  - The core places each label once, where the painter would (a set's first frame is the painter's placement exactly), and keeps its point and spot at rest and moving alike. The set grows and shrinks with the map's scale (the camera's distance, so a tilted view keeps the zoom's size) between 0.7 and 1.5 times its own size; beyond, it is placed anew at its own size as its old places fade out. A placed label that meets one kept before it (turning and tilting move the points, not the level boxes), a keep-out or a marker fades out where it stands and back once clear.
  - The readable floor: 12 sp times Android's font scale, over the smallest label's 17 dp, raises the band's low end.
  - The app draws each label twice: at its own size, which the core copies texel for texel where the label stands at its own size, and at the band's top (1.5 times, `LabelBlock.BAND_TOP`), which a grown label is drawn from, so it stays sharp. Grown or shrunk, each pixel is the area its square covers of the image's texels. The app keeps a label's two images for the next hand-over, so only labels new to the map are drawn again; a set keeps its large images within 32 MB, by priority (the façade's ABI 8).
  - Meanwhile (until step 6 brings them into the core): the core's labels lie under the canvas's routes, areas and markers, and keep clear of the chrome's shapes but not of the markers.
  - Still to come: the levels' sets handing over at the band's ends (quarters and districts out of town, streets and buildings close in: the label candidates of step 3). Water names stay level, like the quarter names (the user, 21 October).
- **All of Amsterdam from the core:** `PackList.kt`, then the first publish of the `amsterdam` and `grachten` render packs after the user's yes (a `packs.yml` run with `renders=only`).
  - Done: where the core has no tiles the map asks, online, for a published render pack that holds the place (`PackList.fetchAt`, twice a second at most); one fetched, or one that came with a region's download, is handed to the core at once (the packs are handed again whenever the library changes), not at the map's next binding.
  - Waiting for the user's yes: the first publish. It adds little until step 3: the canal ring's render pack is the APK's bundled one already, and `amsterdam`'s box is the Westerkerk's, which the bundled living map draws. `packs.yml`'s monthly schedule is off until the user says go (21 October 2026): it has never run, and its first run would publish every region's packs as well as the render packs.
- Done: **the style file** (`app/android/mapcore/style/engine.jsonc`): the colours and widths the core draws with, as data. The packer writes its tables into every render pack's `style` section, beside the format's own tables and the region's state after its load. An edit there changes the look: the app's build makes the bundled packs again with it, and a pack made with it draws with it.
  - It holds the renderer's tables the core reads (the land kinds', the areas' and the surfaces' colours) and every colour and drawn width the core typed before: the ground's (a cycleway's red, a railway's ballast and rails, the big roads' asphalt and lines, the marine works, the tram tracks, a bridge's lines, the runways' marks), the buildings' (the roofs' thin edges, a wall's stroke, a houseboat's hull, deck and cabin roof) and the canal names'.
  - Every value is the renderer's today. `EngineStyleTest` holds each to the port (its tables, or the literal where its drawing code types one) and names where the engine differs on purpose: nowhere yet. A change to the look is an edit to the file and a line in the test's `DIFFERENCES` saying why.
  - The core reads a pack's style once, typed, as the pack opens (`tiles/Style.hpp`). A style that lacks a value, or holds one of another kind, is the pack's error, named. The roofs' edges reach the GPU in the frame's block, in the style of the frame's pack.
  - Still elsewhere: the tables stage P derives from (the materials, the palettes, the traditions), which the packer writes from the port until the core derives the tiles itself (step 3); the moments' themes, which still come from the port through the app (with step 7, or earlier); the profiles, which join the file as their values come to differ; the renderer's geometric rules (a lane's width from its count, a track's gauge, where a road's lines lie), which stay in code.
- **VK facts and VK bench on the device:**
  - Done: **VK bench runs the real paths.** After the stand-in's matrix and hosts it draws the app's map over the APK's bundled packs, as the app draws it (the facts' configuration, the sunny moment, the Look settings' haze and vignette, the painter switch as VK facts left it): the Westerkerk home still, turning, panning and pinched out to 8 km and back, then the Main board and its pitch of 70°, turning. It reports the map's first picture (its tiles built after the map is turned on) and, for each path, how many of its frames were complete pictures. CI's smoke run holds each line; the numbers are the phone's.
  - Done: **VK facts prints the twin's hash** beside pymath's, the sun's, the noise fields' and the ground's, to compare with CI's. The twin check (`frame/TwinCheck.hpp`) settles the app's frame over the bundled Westerkerk at the engine references' home (S1, the sunny moment, full mode) on the phone's own device without drawing, and hashes its twin's stream: `twin <hash> ok`. The host jobs on both architectures and the emulator hold it to the host build's, so stage T, stage F and the vertex stage's maths compiled as C++ give the same bits on every phone. It leaves out the canal names, which are shaped under each phone's own kerning rule (`text.glyphs` holds that shaping to Android's), and the grain (it makes no noise fields). The frame's sorts that can tie (the cover's tiles by distance, the water's pieces by ring, the twin's land rings by order) are stable, so no libc++ orders them its own way.
  - Done: **VK facts checks the composite's resolve form against its direct form** (`frame/CompositeCheck.hpp`). A probe of unlike samples goes through the composite with its steps off, at the facts' configuration's samples, in both forms offscreen, and the two frames are compared pixel for pixel: `composite.check: …`.
    - A driver that clips the full-screen triangle to the frame draws it as two triangles, and in the resolve form the pixels on their diagonal are shaded twice in place. Where a phone's line shows that, a row of the quirk table gives it the direct form, and the render pass with it.
    - lavapipe clips (85 pixels of the probe's 96 × 72, all on the diagonal), so the hosts' tests hold the check's planted failure, and CI holds the emulator's line to the forms alike or apart on the diagonal alone.
    - It runs behind a crash-guard stage of its own (`checks`): a crash there turns off VK facts' drawing checks alone, never Vulkan. For it the offscreen output moved from the hosts' platform layer to `gpu/Offscreen.hpp`.
- **Refinements:**
  - `ObjectStrokes`' round joins;
  - snapping slivers: the area floor and the facing test decided as the port decides them, so L1 counts no slivers;
  - the densest tile's 13.4 MB (12.7 before step 4's windows), with 32-byte wall records, and the heaps back to D3's 40 MB. The heaviest view today, the canal ring's second sweep camera, holds 42.7 MiB, 35 of them the buildings'. At the densest tile the walls are 29 % of its bytes, the roofs' faces and the n-gons' triangles 30 %, the faces' geometry 25 % and their colours 12.5 %. A wall's floats must stay as they are, so the frames do not change: its corners come down to six (both ends and two heights; a skillion's wall has two tops), and its face, with a level normal, needs only the normal's and the test point's x and z. 32 bytes then means packing the face, sample, item, flags and slots into two words, within limits a denser tile elsewhere may pass, and 40 MB also needs the walls' faces compacted: about a fifth of the buildings' bytes in all. The windows have landed (step 4) with a record of their own beside the wall's, so nothing waits for it now;
  - the buildings' triangles: 2.06M at the Main board and 2.66M at the canal ring's first sweep camera, against §3's 600k for the frame (`FrameStatsTest` holds them to 2.8M meanwhile), measured on the phone by VK bench;
  - Done: **OUDEZIJDS VOORBURGWAL at the canal ring's first sweep camera** was the check's difference, not the frame's. Both draw the name with the same matrix, whose first entry the port prints as -0.000. The port's SVG reader composes each transform with the identity and so reads it as +0, while the twin keeps the -0 it snapped to. `L1Test`'s transform keys drop a printed zero's sign now, as its coordinates already did, and it names no known difference;
  - the first picture: ten tiles and 32 MB at the home before it is complete;
  - indexed ground draws;
  - an island's water level from the ring around it;
  - the soft shadows' three boxes in one dispatch where the device's shared memory holds their reach (three dispatches today, each a round trip of the mask through memory), if VK bench shows their cost;
  - the roofs' thin edges' fragments: their quads cover about one frame at the Main board and a third of one at the home, each fragment working out the exact area of the stroke in its pixel; a closed form for the pixels away from a segment's ends, and a tighter margin, if VK bench shows their cost (their vertex stage runs 30 vertices a plain primitive, most of them drawing nothing);
  - the roofs' thin edges' records: 64 bytes a plain primitive of the drawn runs in a buffer per frame slot (about 9 MB a slot at the canal ring's first sweep camera, grown by half again); one buffer for both slots, behind a barrier on the last frame's reads, would halve that;
  - the windows' triangles: 39 a window of the walls within their reach, drawn or not (the glass's fan of three, the frame's band of 36 over a 32-point outline), 446k at the smoke sweep's third camera, two thirds of the buildings' there (`FrameStatsTest` counts them apart, held to 600k). A frame of 0.7 px needs about a dozen outline points, spread by each corner's turn; the deep windows' wider frames need the frame's coverage from the fragment stage instead (each sample's distance to its window, exact at any width); and an indirect draw would draw only the windows the compute pass keeps. If VK bench shows their cost. Finding the walls within reach costs the render thread about 0.2 ms at 65,000 windowed walls on the Mac;
  - Done: **a frame builder draws a world another builder of the same passes built.** The passes found their tiles by an index each learned in its own stage T, so the likeness's owner mode and `FrameStatsTest`'s overdraw mode, each a builder of its own drawing the same world, drew none of its tiles: every case's owner metric read 0 and the overdraw bound held nothing. Each pass now finds its tiles at its own place among the frame's passes, the frame carries the world's heaps, and a builder of other passes draws none of a world's tiles (the world names the passes that built them).
  - The opaque fragments, measured for the first time: 1.04 to 1.55 full screens at the boards' views and sweeps (the composite's one among them), but 1.93 at the canal ring's second sweep camera, against §3's 1.6; `FrameStatsTest` holds them to 2.0 meanwhile. The candidate: the ground drawn after what stands up, so that early depth testing rejects the ground under the buildings (its painter slots lie behind every true depth), if VK bench shows the cost.
- Tag an alpha.

### 3. The phone builds the map anywhere
- **Stage P in the core.** The look's derivations move from the port's loaders into C++:
  - looks and traditions;
  - heights and roof shapes;
  - water levels and the sea from the coast;
  - party walls and multipolygons;
  - label candidates and keep codes.

  They are held record for record to the port's, from the same data.
  - Done: **their input, Overpass's JSON** (`osm/Overpass.hpp`). The core reads the JSON of an `out geom` query, the renderer's own `raw.json`, into its elements in the document's order, each value as Python's `json` module reads it: ids as integers, a coordinate as `float()` parses its digits, the tags in their order. A string without escapes is viewed in place.
  - `float()` of a decimal is `pymath::decimal_to_double`: one exact operation where the digits and the power of ten are exact doubles, which covers every coordinate OpenStreetMap prints, else exactly in round()'s fixed-size integer. Apple's libc++ has no `std::from_chars` for doubles before macOS 26.
  - It reads strict JSON and refuses, by name, what Overpass never writes and the port could not read alike: a key twice in one object, a null in a geometry, a lone surrogate.
  - `OverpassParityTest` holds every value to the port's own `json.load` over the raw.json it unpacks from the Westerkerk's and the canal ring's packs (27,663 and 166,905 elements, each coordinate to the bit). The core reads the canal ring's 61 MB in 171 ms, the port in 1,054.
  - A fuzz target replays its seeds on every host build.
  - Geofabrik's extracts (PBF) come next to it as a second reader, held to the same values.
  - Done: **the living map's load** (`load/Amsterdam.hpp`): amsterdam.py's `load`, as the port computes it, from Overpass's JSON. The buildings and parts with their heights, years and roof shapes, and what guess_tags gives a building that lacks them (each from its own random stream, seeded by its id, as Python's); the water, roads, tram rails, areas, trees, nodes and the names along the ways; then each place's style, the building it stands in, and the outlines their parts stand for.
  - `AmsterdamParityTest` holds every record and field to the port's, in its order and to the bit: over the Westerkerk's and the canal ring's raw.json on every push, and on the Mac over the renderer's other regions' data put through the same rules (Paris, New York, Bergen and the rest). Not one of the canal ring's 171,688 records differs. The core loads it in 0.2 s after a 0.15 s read; the port takes 0.9 to 1.8 s.
  - A tag value is read as Python reads it: a leading number by `num()`'s pattern, with Python's spaces and the digits of every script. A shop that poi_style.py's tables do not name is labelled by its tag, capitalized as the port does it (`text/Case.hpp`, held to the port on every code point).
  - What the renders cannot load raises in Python and stops the render. The core loads on and names each case: a node without a position, a road without points and a canal's name without a geometry are left out, and building:levels without a number counts the levels from the height. A position off the Earth refuses the document. A fuzz target loads arbitrary documents.
  - Done: **the living map's tiles from its raw.json** (`load/Cut.hpp`): the scene cut into render tiles as the packer's `SceneToTiles` cuts it. Every record becomes a feature in the tile and cell of its reference point, with the values the packer derives: the tags the renders read, the walls' and roofs' base colours, the flags (a quay edge within 16 m among them), the quay edges, the doors and shopfronts on the facades, the roads' ground and marks keys, and the water clipped at each tile's edges in exact integers.
  - The Westerkerk's three tiles, cut from the repository's raw.json and written by the core, are the bundled pack's byte for byte (`cut_test`, on every host build). The core reads, loads and cuts them in about 0.16 s.
  - Where the packer's build fails on a value, the core cuts on, named in `load/Cut.hpp`: a colour Java cannot read is taken as the clay, a tree's place style is left out, and a piece of a water ring that crosses itself is left out.
  - The Westerkerk's pack written whole from its raw.json, the cut's tiles with the bundled pack's `rhead` and `style`, opens, passes every checksum and holds the packer's chunks in the packer's order (`cut_test`).
  - Done: **a region's load** (`load/Regions.hpp`), regions.py's rules for every place besides the living map, on top of the living map's load: buildings and parts mapped as relations, the closest roof shape the renders draw, land cover, runways, big roads and railways above the ground, power lines and their towers, marine works, cranes, masts, wind turbines, national parks, water mapped as relations with its islands, the sea closed from the coastline, the natural shores, each part's outline, and every building's look.
  - `RegionsParityTest` holds every record to the port's, loaded as the packer loads a region: the canal ring on every push (175,845 records, none different), and on the Mac New York, Notting Hill, ARTIS, the Efteling, Bergen, Paris, the port and Schiphol.
  - Done: **generic.py's looks, all of them** (`load/Generic.hpp`). Each building's kind comes from its tags and the land use it stands in. Its walls and roofs are real OSM values: the mapped ones win, and a colour word means the colour it names. The drawn colours vary per building from its own random stream, and the building gets its window rows and why. Also: Paris's stone tones and mansards, and Amsterdam's New looks (`keep_homes`: the homes keep their brick and plaster).
  - Done: **the building traditions** (`load/Tradition.hpp`). The core reads render/traditions' files as data, each value as Python's json gives it (`tiles/Json.hpp`, the same typed values a pack's `style` carries). A tradition gives a building:
    - the levels it usually builds, or the median of the mapped neighbours' within 80 m;
    - a roof from its rules (a Paris mansard, a canal house's narrow gable) or its roofs, with their pitch;
    - its walls and roof colours, weighted picks from the building's own random streams;
    - its details: windows, shutters, eaves, timber boards, a base band, a cornice, a hoist beam, chimneys and crenellations;
    - the party walls its chimneys stand on.
  - A file is read strictly: a value generic.py could not use refuses the whole tradition, named by its path (`walls.home.before:1830[2]`), where Python would raise only for the building that reaches it.
  - Held to the port: Paris, Bergen and Notting Hill with their own traditions (`RegionsParityTest`), and the Westerkerk with its New looks and with its kit (nl_amsterdam). On top of that, all five traditions and Paris's own looks over every region's data, so every rule meets every place: 14 data sets × 6 looks on the Mac, the Westerkerk's and the canal ring's on every push. Not one line differs.
  - The tiles do not carry a tradition's details yet: the packer's schema has no column for `trad`, `party`, `lv_why` or `roof_why`, so no region with a tradition can be cut today. Step 4's facades give them columns. The canal ring needs none.
  - Done: **a region's tiles from its raw.json** (`load/Cut.hpp`'s `cut_region`): SceneToTiles for a region, at L14 and L12. What it adds to the living map's cut:
    - each ring's keep codes (`load/Keep.hpp`: regions.py's simplify tree walked once for every tolerance of the ladder; a water piece's crossings kept at every one);
    - the variants: the region loaded at each tolerance its views and the live map's bands use, a building whose attributes differ there carried for that tolerance;
    - land cover; railways, big roads, runways and taxiways, power lines, marine works and a park's edge, with their ground keys; cranes, masts, towers, pylons and wind turbines;
    - the water's islands and natural shores, the quays around islands, no quay where water lies on both sides;
    - the looks' colours in the base colours, no doors or shopfronts (regions draw no facades);
    - L12: the areas, the land cover and the water at the ladder's 4 m, and the labels.
  - The canal ring's 27 tiles, cut from its raw.json, are the packer's byte for byte: 49 variants, 154 items, 1,742 lines, 9,536 ground rings, 908 water pieces and 690 quays among them. `CutParityTest` holds them on every push, from the element pack CI has; `cut_test` holds them on the Mac. The cut takes 0.3 s, the six loads 3.8 s. A fuzz target cuts arbitrary documents' scenes.
  - Not cut yet, refused by name as the packer's build fails: gantries and conveyors, a tradition's details, a survey's terrain and water levels. A window leaves the first two out (below).
  - A water ring whose box reaches more than 4,096 tiles of a level refuses a region's or the living map's cut. A region's sea, closed along a box well beyond the region, reaches a few hundred tiles of L14. A window's cut looks at its own tile alone. A vertex off the grid now refuses the cut before anything is placed. Until 23 October, when the first nightly fuzz run found it, such a ring was cut into a piece for every tile until memory ran out, and a vertex off the grid, placed at latitude 0 and longitude 0 meanwhile, reached as far.
  - Done: **the windows the phone builds** (`load/Window.hpp`). A window is one L14 tile of the map, built from OpenStreetMap's data of its box grown by 200 m (`window_box`), so what stands on its edge and what its derivations look across are there to see: a building's mapped neighbours (80 m), the parts in an outline (120 m), a quay within 16 m, the land use under it.
    - Its frame is its frame cell's: the L10 tile holding it, its origin the cell's centre (`frame_of`). So neighbouring windows agree on their metres (an area's size, a neighbour's distance), and the world draws them in one frame.
    - Its place is its country's (`load/Countries.hpp`, the port's Countries), as the port's RegionConfig picks it for a region it builds: the country's building tradition and time zone, Amsterdam's palette, regions.py's rules.
    - It keeps the features whose reference point lies in its own tile (a building on its edge is built once, by the window of its reference point) and the water clipped at its edges, at L14, with keep codes and the variants of the live map's bands (`cut_window`).
    - It is written as a pack of its own: that tile, the core's own head (profile region, level 14, the frame, the window's name, the core's stage P version, the style version, its data's SHA-256, the bands, no views) and style.
    - A tradition's details (`trad`, `party`, `lv_why`, `roof_why`) have no columns until step 4's facades, so a window leaves them out where the packer's cut refuses them. The looks they lead to (the levels, the roofs, the colours) are in its tiles.
    - Gantries and conveyors have no columns until step 4's things, so a window leaves them out too. Until 22 October a sign gantry over a motorway (`man_made=gantry`) or a port's conveyor anywhere in a window's box failed the window whole, and the place stayed today's map. A window built with them is the window built without them, tile for tile (`window_test`).
    - Held: every L14 tile of the canal ring, built as a window from the elements of its raw.json that the renders' Overpass query returns for the window's box (in the region's frame, by its rules), is the packer's tile of the whole region feature for feature (`window_test`: 23 windows, 59,616 lines of canonical dump, none different). The places in the scene's lists are left out of that comparison, since a window's own data numbers them its own way, and so are the variants of the region's home view, which a window has no views for. A window there takes 0.45 s under debug and ASan.
    - A window anywhere: the one around the Louvre, with fr_paris's looks, builds in 2.7 s under debug and ASan (21,522 features, a 1.2 MB pack). Its memory is not measured yet.
  - Done: **a pack's head and style written by the core** (`tiles/SectionWriter.hpp`, `load/PackStyle.hpp`): `rhead` and the style's tables as the packer writes them. The style is the format's tables, the engine's style file (compiled into the core, read as JSON with comments as the packer reads it), the materials, the region's palette and module values, the scene's scalars and its tradition whole. The style version hashes what StyleVersion.ID hashes.
    - `pack_style_test`: each bundled head, written again from its values, is its own bytes. The styles the core composes for the living map and the canal ring are the bundled packs' byte for byte, and so is the style version. The Westerkerk's pack, written from its raw.json with the core's own head and style, has the packer's sections.
  - Decided, for what comes next:
    - **The data a window needs:** Overpass's answer for `window_box`, by the renders' query (every tagged node, every way, the multipolygons and parks, `out geom`), but with each relation's members whole. The renders' query sets a global box, and Overpass then leaves out the geometry of a relation's members beyond it (the port's merge fills them from another part's copy). A window cannot close such a ring, and regions.py drops a ring it cannot close. So the window's query puts the box on each statement and none on `out geom`.
    - **The ground's order across windows:** a window's ground keys rank its features within its own scene, so two windows' ranks do not compare. Overpass lists an answer's elements by type and then by id, so within one answer a list's data order is the order of (type, id, the ring in the element), and a window's lists keep the order of a bigger box's for the elements they share. The order that holds across windows is therefore the renders' own over one scene holding them all: per stage and class, a list's own sort key where the list is sorted (the land's draw order and area, a big road's layer, an aeroway's order), then the element's type and id, then its ring.
      - Window tiles carry it, in a column of their own; a pack made from one scene needs none.
      - Where a frame draws tiles of more than one scene, the ground pass ranks what it draws by it, per group, from each tile's runs (already in that order), whenever the drawn tiles change.
      - The land's areas are each window's own sum in its frame cell's metres, and neighbouring cells' metres differ in scale (about half a percent east to west at Amsterdam's latitude). Two kinds of the same draw order whose largest rings lie in windows of different cells and differ in area by less than that may then be ordered otherwise than one scene would order them: a named difference.
      - The canal ring's raw.json is four Overpass answers one after another, not (type, id) order across them, so its pack keeps its own order.
  - Done: **the world draws windows.**
    - In the store, a frame cell's windows are one pack, `cells/10/<x>/<y>` (`tiles::Store`, `PackKind::window`), composed from the windows' own files without copying them: its header, directory, chunk table and reach in memory, each window's chunk where it lies (`tiles::ConcatSource`, `compose_pack`). A window built again replaces itself, and a window of another frame or style version than its cell's is refused. The sun lights a pack's tiles from that pack's own tiles, so a cell's windows shade each other.
    - The world draws, beside the active pack and the coarser one around it, a neighbour (`World::choose_neighbour`): around a render pack, the windows of the frame cell holding the camera's target; around a cell's windows, the render pack nearest the target within the cover's reach, else the nearest other cell's windows. The cover draws its tiles unmasked, but not at a place where the active or the coarser pack has a tile; the sun lights it in a third lane of its own.
    - Each L14 place's tile so comes from the first pack that has it (bundled, then render packs, then windows): the bundled packs keep their places and windows fill around them, placed by their frame cell's affine as the coarser pack's tiles are.
    - Windows have no L12 (a window cannot see an L12 tile's sixteen), so beyond the finest level's reach the map shows the mid-zoom base (step 5), and today's map meanwhile. A view across two cells draws one of them with the active pack: the third and fourth cells around a corner wait for a world of more packs.
    - `scene_vk_test`: the nine windows around the Westerkerk's, built from the canal ring's data and added beside the bundled Westerkerk, are drawn beside it where it has no tile, lit by their own lane; with the target among them, their cell is the active pack and the Westerkerk its neighbour.
    - **A frame of windows alone,** anywhere beyond the bundled and render packs: the app names the window under the camera (its own id), and the store's pack holding it, its frame cell's, is drawn (`Store::holder_of`). That pack has no views, so it frames the live camera in its own frame with the regions' settings (`Ladder::bare`: regions.py's cfg_for for a view of nothing but its camera: no fin, the haze from the distance). The plate reaches every window drawn, since the renders' plate lies about the frame's origin, which for a frame cell's windows is the cell's centre, kilometres off; a frame without windows keeps the renders' plate.
    - Until 22 October a frame of windows alone drew nothing: the window the app named was no pack of the store, so no pack was active, and without views the camera looked at the cell's centre. The app then kept today's map wherever only windows were, and windows showed only beside the bundled packs. `order_vk_test` holds a frame of one cell's windows alone: five drawn, framed at the camera, the plate reaching them, no fin.
  - Done: **the core asks for the windows it wants** (`World::wanted_windows`): the L14 places its frustum sees within the finest level's reach, where no pack has a tile and no bundled or render pack's box holds the place, nearest first, twice a second at most, also where nothing is drawn yet. Through the façade (ABI 11): `MapCore.wantedWindows`, a window's box (`MapCore.Window.box`) and its build from Overpass's JSON into a file of its own (`MapCore.Window.build`, on the app's background thread), which the app then lists among the map's packs as a window.
  - Done: **the app fetches them** (`mapview`'s `Windows`): twice a second the map asks for the nearest window the core wants, fetches its data from the app's Overpass servers with the window's query (`OverpassClient.windowQuery`: the box on each statement, so relations come whole), one at a time, has the core build it into the engine's cache (`windows/14-<x>-<y>.atlas`) and hands its packs again; the map's pack list lists each window as a pack of kind window, read like any render pack (the core writes a window's manifest in the packer's layout). CI's demo run builds none, so the emulator asks no public server.
    - A window that failed waits before it is tried again, so the public servers are never asked for the same data over and over: one whose data did not come (no server answered, or only with older data) five minutes, then twice as long after each failure in a row, two hours at most; one whose data the core refused, a day, since the same data and core refuse it again (`Windows.waitAfter`). Until 22 October every failure was tried again after five minutes, with a whole fetch each time: a window the core refused cost up to 25 MB of a public server every five minutes while it was in view, far past their 1 GB a day.
    - A window that holds nothing to draw (no feature's reference point in its tile: the open sea out of a coastline's reach, the middle of a field) is a pack without a tile, and its manifest counts its tiles, as the packer's do (with the versions it was built with). The app does not list it, so its place is today's map's, and the store takes it into no cell (one built again empty leaves its cell).
    - A window built lately is not fetched again while the core still wants its place: one without a tile, one the store did not take into its cell, one built before the map handed the core its packs again. It is fetched whole again once its data is a week old, never from data older than it keeps (`Windows.builtLately`). Until 22 October each of these was fetched again every half second while its place was in view.
    - A window of another version than one built now (`load::window_version`: its stage P's and its style's, which its manifest names; the façade's ABI 14) is built again from the data it keeps, on the phone and with no server asked, before the map lists it again (`Windows.rebuild`). So a frame cell's windows are of one version, which the store requires of them, and an edit to the style file reaches every window. One that keeps no data that builds goes, and is fetched once the core wants its place. Until 22 October a cell whose windows were of an older version refused every new window of the cell, so after a style change new windows there would never have been drawn.
    - Named, until the world bases bring the land and the sea (step 5): a window learns the sea only from a coastline in its box, so the open sea beyond the windows along a coast has no water and shows the ground's plate there.
    - Not seen yet on a device: the whole path (fetch, build, list, draw) runs first on the user's phone after the next alpha. A window's memory on a phone, the time a dense window's data takes to come, and how the transitional rule chooses between the core and today's map around windows are to be measured there.
  - Done: **the ground's order across windows.** A window's ground features and lines carry their element (`id`, `otype`, `part`: new columns of the ground and line layers, which the packer's tiles leave empty). Stage T gives each fill and stroke its order key: its list's own sort where the renders sort it (the land's draw order and area, a big road's layer, a road's marks by class), then its element. Where the frame draws tiles of more than one scene with a window among them (a window beside another pack's tiles, or two windows: each window is a scene of its own, though a frame cell's windows are one pack), the ground pass ranks every element of the drawn tiles in its group by that key (then the pack, then the scene's order), once per set of drawn tiles; the fills read their rank from the frame's words, and the line runs and the twin take it too. A frame of one scene's tiles (the Westerkerk and the canal ring, or one window) is ordered as before. `order_vk_test` holds it on lavapipe, beside the Westerkerk and for one cell's windows alone (7,404 neighbours in rank, 21 land kinds by their draw order, 14 of them from more than one window).
    - Until 22 October two windows of one cell kept each window's own ranks, which do not compare: in that frame 4 of the 21 land kinds came out of their draw order, so a land cover could hide one that lies over it.
    - Not yet on the GPU against the twin pixel for pixel for a frame of windows (the ground's GPU tests draw the bundled packs), nor on the phone.
  - Done: **a window built again after a week, asking Overpass only for what changed** (`osm/Diff.hpp`).
    - A window keeps the data it was built from beside its pack (`windows/14-<x>-<y>.json.gz`: Overpass's JSON of its query, gzipped by the app).
    - Once that data is a week old, a window the map draws in view (`World::windows_in_view`, the façade's ABI 12) asks Overpass for the augmented diff of its own query since the data's date (`OverpassClient.windowDiffQuery`, `[adiff:…]`, in XML: Overpass's JSON prints no actions). Overpass runs the query at both dates and prints each element whose output differs, its points included, so a way whose node moved is among them.
    - The core reads the diff (a reader of the XML Overpass prints) and merges it into the kept data: what is gone leaves, each changed element's new form takes its place, each new one goes in its order. Each new form is written as Overpass's JSON prints that element, its layout and escapes included, so the merged data reads as a fresh answer reads. The window is then built from it as from any answer (`MapCore.Window.merge`, then `build`). Nothing changed: the data is only marked checked.
    - The changes are asked from half an hour before the data's date, for an edit that reached the servers after the minute it was made in. The merged data takes the diff's date, and the date it was last fetched whole.
    - Held: `diff_test` merges a diff of edits of every kind (a tag, a way's point, a relation's member, deletes, an element gone from the query, elements created between and after the others, escapes) into the Westerkerk's raw.json: element for element the edited answer's bytes, and the same window built. Against Overpass itself on 22 October 2026: the answer of a box by the Westerkerk as of 15 October, merged with the real diff since (5.9 KB, five changes), is the fresh answer of that minute byte for byte (1.25 MB). The test case takes such files when `ATLAS_DIFF_KEPT`, `ATLAS_DIFF_XML` and `ATLAS_DIFF_FRESH` name them. A fuzz target reads and merges arbitrary diffs.
    - What the merge cannot give exactly is refused, named, and the window is then fetched whole: a remark (the query failed or ran out of time), an action, element or child the reader does not know, an answer that is no diff, a point without a position, a way without nodes or a relation without members (Overpass's JSON prints a null, an empty list or no key there, and its XML does not say which), kept data that is not one answer in type and id order.
    - Fetched whole instead: where the window keeps no data, where its data was last fetched whole four weeks ago (so a change a diff could miss lives a month at most), and where no server gives the changes. Data older than what a window holds is never built: the window stays as it is.
    - Learned on 22 October 2026: the public servers' state matters as much as their answer. overpass-api.de was at capacity for history queries, overpass.kumi.systems answered 502, and overpass.private.coffee answered with data from June. So the app takes a window's data from a server whose data is of the last day where one answers (else the newest that came), and the changes only from a server not behind the kept data.
  - Done: **a window's memory measured, and its build held to one scene at a time** (`load::WindowCut`).
    - Measured on the host with the allocator's own hooks (`window_memory_test`, with `ATLAS_REGIONS_DIR`). The canal ring's densest window has 25 MB of Overpass's JSON (46,000 tagged nodes and 16,000 ways). Its build held 195 MB at once: the data's document (31 MB) and five scenes of about 30 MB each, the window at tolerance 0 and at the live map's four other tolerances.
    - Now the build takes the attributes of the tile's buildings and parts from the scene at tolerance 0 and drops it. It compares each other tolerance's scene with them as it loads, keeping a variant wherever they differ, and drops it too. Then it cuts the tile from the scene at tolerance 0, loaded again. The tiles are the same byte for byte: the canal ring's 23 windows are still the packer's tiles, and the regions' cut is unchanged.
    - The scene's largest lists are given their room up front, from the elements that fill them, and the document's arrays are trimmed to what they hold once it is read: grown by doubling, they held about a quarter more than their records. Exact as before.
    - A place's style is held in a list of the scene's own, and its node holds its place there (`Scene::styles`): a dense window's scene has 46,000 nodes, one in twenty of them a place, and each held room for a style.
    - A window's data goes from Overpass straight into a file beside its pack, and the core maps that file to build it (ABI 13), so the data never passes through the app's heap. Until now the app held it twice while the core built: the fetched bytes and a direct copy, 25 MB each for the densest. A refresh unzips the kept data into a file the core maps, and the core writes its merge to a file, which the window is built from and kept from.
    - The document holds what its elements hold (their tags, nodes, points, members and strings) as places in its own arrays and text, not as views (`osm/Overpass.hpp`): 80 bytes an element instead of 136, and 16 a tag instead of 32. The densest window's data has 62,000 elements and 393,000 tags, so its document holds 15 MB (25 before), and reading it holds at most 26 MB at once (44). The document keeps its arrays on the heap, so what views them stays valid wherever it moves, and a selection of its elements shares them.
    - The densest window now holds at most 49 MB, within the budget's 50 MB: the document 15 MB, one scene 16 MB, the attributes and the cut. The Louvre's holds 38 MB, the canal ring's others 2 to 38 MB. The extra load costs about a sixth more time. What is left, largest first, if a phone shows the need: one scene (11,000 buildings of 816 bytes each, a tradition's details 208 of them) and the cut.
  - Done: **windows built behind the screen and ahead of the camera.**
    - A window's build, its merge and its data's compression run at background priority (the app's I/O thread, its own priority given back after), so a window's seconds of work never take a core from the frames.
    - While the camera pans, the core also asks for the places its motion brings into view within 3 seconds (`scene::window_lead`), after the places in view: at most 4 at once, nearest to where the eye will be first. A pan counts from 10 m a second; a jump of more than a window between two looks is a search's, not a pan's, and the lead is never more than two windows. `scene_vk_test` pans east at 200 m a second: three places ahead of the view are asked for, east of the ones in view, and none once the camera rests.
  - Next: the windows' memory and timings on the phone (with the next alpha); the windows' data kept smaller once zstd's compressor joins the core.
- **The core asks, the app fetches.** Remote packs already work this way: the core asks for a box's data on its I/O thread, and the app's OpenStreetMap client answers. For a window: the core says which windows it wants and each one's box (done, above), the app's client answers with Overpass's JSON (several servers, failing over, one query at a time), the app's background thread builds the window through the façade and keeps its pack on disk, keyed by the window and the style version, so a place seen once works offline.
- **Built tiles become local packs.** A C++ writer of format 3 turns each built window into a small pack, which the store opens like a downloaded one.
  - Done: **the writer** (`tiles/TileWriter.hpp`, `tiles/PackWriter.hpp`). Stage P fills a tile's layers feature by feature, in the reader's own typed columns with the strings interned; the writer writes the packer's bytes for them, its string table in the order the columns use the strings. Then the pack around the tiles: the container, the chunks in level and Hilbert order, the checksums, `reach`, `rhead` and `style`.
  - `writer_test` holds it to the packer. Every tile of the bundled packs, decoded and written again, is its own bytes (30 tiles, 14 of the 15 codecs the layers use; the 15th, house numbers among strings, on generated values). The canal ring's tiles, written into a pack of their own, open, pass every checksum and read as the packer's.
  - The core vendors zstd's decompression alone, so a local pack's chunks are frames of raw blocks: the canal ring's is 4.2 MB against the packer's 1.8 MB. zstd's compressor joins `third_party` in a commit of its own; the tiles' bytes do not change with it.
- **The packer runs the core's stage P** through the host build, so `:rendertiles` stays only as the Kotlin reference.
  - Done: **the packer's tool** (`atlas_stagep`, `mapcore/cpp/stagep`). Stage P is a library of its own in the core's build (`atlas_stage_p`: pymath, the Overpass reader, the loads and the cut, the tiles' reader and writer, the text's case and code points, the roofs' shapes), which the core and the tool link alike. With `ATLAS_STAGE_P` (the `host-stagep` preset) the tool builds alone, with no slangc, Vulkan or HarfBuzz.
  - The tool reads a region's or the living map's raw.json and a job from the packer: its profile, name and levels, its frame's doubles as their bits, the port's engine version, the element pack's hash, and a region's region.json as the packer unpacked it, its views among it. It loads and cuts the tiles and writes them with each one's features, reach and layers' counts, then the pack's head and style (`load/PackHead.hpp`). Where the region holds what the tiles cannot carry yet, it says so with its own exit code (the packer's `render_gap`).
  - Done: **the head and the style from the core too.** The core takes them from the region.json, as Head.of and Style.of take them from the port's scene:
    - the views nearest first, each at the tolerance its renders load it at (regions.py's GENERALISE, compiled into the core);
    - the variants at those tolerances and the bands';
    - the palette, the module values with the time zone's offset, the scene's scalars and the tradition;
    - the living map's camera, one band and no scene.
  - The core also refuses what the port's scene refused before the tiles were cut: a theme park's coaster tracks and a park's or a zoo's planted woods (region.json's `park` or `zoo`), and a survey's heights (a `dem.json` in the region's folder), beside its own refusals (gantries, conveyors, a tradition's details). So the packer no longer loads the port's scene for a pack the core makes. A park or a zoo whose woods the port would plant empty is refused all the same: a named difference.
  - `packer render-tiles --core <tool>` writes its packs from what the tool made (`CoreTiles`, `Format3Writer.writeEncoded`), compressed as the packer compresses them. The head and the style must read back to the core's own bytes. The APK's bundled tiles take the tool with `-Patlas.stagep` (or `ATLAS_STAGEP`); without it, the port makes them, as before.
  - Held: `StagePPackerTest` (mapcorecheck, whose host build builds the tool) makes the bundled packs so, and they are the packer's own byte for byte: every tile (the canal ring's 27 in about 5 s of the tool's, its five variant loads among them), the manifest, the head and the style. It also checks the tool's refusals on made-up regions: a zoo, a theme park and a survey. `pack_style_test` makes both bundled packs' heads and styles from what the packer knows of them, byte for byte, on every host build. The tool built alone, with the shipped flags, writes the same tiles as the debug build.
  - Done: **the APK on CI is cut by the core.** android.yml's build job builds the tool (the `host-stagep` preset, with the LLVM clang and libc++ it already installs) and its APK's bundled tiles come from it (`ATLAS_STAGEP`).
  - Kept on the port's cut on purpose: core.yml's tiles job and :mapcorecheck's bundled tiles. They are the reference the parity tests hold the core to (`cut_test`, `pack_style_test`, `window_test`, `CutParityTest`, `StagePPackerTest`); cut by the core, those tests would compare the core with itself.
  - Done: **what is published is cut by the core too.** After alpha 12 was tagged (the user, 23 October), release.yml and packs.yml's renders job also build the tool, with LLVM's clang and libc++ at core.yml's pin. release.yml's APKs take it (`ATLAS_STAGEP`) from the first tag after it; a tag from before the tool is cut by the port, as it was released. The render packs take it too (`--core`), on packs.yml's first run, which waits for the user's yes.
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
  - the buildings' looks: Amsterdam with the looks from each building's tags (the renderer's New looks: the canal houses' varied brick and plaster, every other building its own type's look), which read richer than its tradition kit today; tradition walls and roofs where the renderer draws a place with them (the tiles must carry the traditions' entries). Making the tradition kits more advanced comes after the engine (the user, 21 October);
  - doors and house numbers.

  Chimneys, dormers, hoist beams, shopfronts, signs and terraces are geometry with depth.
  - Done: **the windows, flat** (`shaders/building/windows.slangh`, `passes/Windows.cpp`).
    - Stage T gives each wall with levels the record of its windows (`geometry::WindowWall`): its levels and columns as the renders count them, its style (plain, modern, Paris's) and flags (a shop floor, a warehouse, a church), its first window's sun bit, and two painter slots near and far. A part (but a church's), a houseboat, a building without windows (`no_win`) or levels and a wall of 2.2 m or less have none. It is one uint4 a wall in the walls' heap, after the cells: the densest tile holds 13.4 MB.
    - Held: stage T's windows are the port's window for window, each with its sun bit (`geometry_test`: Amsterdam's 173,809 windows on 13,919 walls, the canal ring's 10,435,272 on 752,428).
    - Each frame a compute pass decides each window once (`windows_cs`) on the walls whose buildings the ranks put within the windows' reach (390 m, or a view's `win_d`): the renders' facing test, the near plane, the margin of 300 px, an area over 0.25 px, the snap at rest. It writes the window's corners, slot and depth span and whether it is lit, and each wall's record: its plane and the boxes of its dark and its lit windows, which their gradients span.
    - A window is two opaque parts on its wall's plane: the glass (the window less half the frame's width) and over it the frame (the band between the glass and the window's outline grown by half the width, with round joins, on 32 points). Under 4x MSAA that is SVG's fill then stroke, also where one window's frame overlaps another's glass. At true depth they lead their wall by 16 and 17 steps of 2^-16 (the cells lead by at most 15), so a wall's windows win over it and its cells, never over a wall in front.
    - The paint: the glass's gradient by the sky's cover (clear, overcast) and the lit glass's, each from the top to the bottom of its wall's dark or lit windows, as the renders' `objectBoundingBox` spans them; flat colours without weather and at night; a warehouse's shutters; Paris's lit panes as its dark ones. The colours are the style file's `windows` table, held to the port's literals (`EngineStyleTest`).
    - In the renderer's order (the painter switch), each wall's dark windows, then its lit ones, after its cells.
    - With weather, a shop floor's row is left out (its glazing is drawn there), and so is a church's within 520 m (its own windows).
    - A rank now carries its item's detail beside its slot (`rank_entry`: 20 bits of slot, 12 of detail), one bit per reach of its kind: the cells' near variant (460 m), the windows (390 m), a church's windows (520 m), deep windows (160 m) and sash bars (140 m). The bands decided an item's detail until now; they now give the items' slot counts alone.
    - Held: on lavapipe, `buildings_vk_test`'s frames with windows equal a raster of the twin (its glass, then its frame on the GPU's triangles) at the Westerkerk home, the Main board, the melting pair and synthetic walls, at true depth and in the renderer's order, at rest and moving, at 1x and 4x MSAA, a gradient's pixel within one level. L1: all 11 cases, 75,634 elements matched, none out of order, differing or extra. The engine's references are at version 5: the home's four frames changed, the Main board's did not (no window there is within reach). `FrameStatsTest`: 4,360 to 11,432 windows at the smoke tier's views within their reach, none at the Main board; the canal ring's second sweep camera shades 1.96 screens of opaque fragments (1.93 before), under the bound of 2.0.
    - Next: with weather, a window within 160 m is deep (its frame wider near the camera and, where it is large enough, its reveal, its glass set back and its sill); within 140 m, with weather or without, a large window has its sash bars. Until then L1 counts them as missing. The likeness harness's owner maps draw no windows: their pixels are their walls'.
- **Trees and things:**
  - trees with their clumps;
  - lamps, benches, racks, baskets, bollards, railings and sett marks;
  - towers, turbines turned to the wind, cranes, masts, gantries, conveyors, coasters and power lines;
  - woods: the renderer's lattice at Efteling and ARTIS, anchored to the world;
  - the overlays: rails, big roads, runways with their designators, taxiways, marine lines and park edges.
- **Water:** quays, reflections, sun glitter, drizzle rings, night streaks and houseboat cabins.
- **Effects:**
  - the crown, leaf, foliage and canopy filters, with the per-tree fields made on the GPU, exact where the device passes the byte check and from the CPU elsewhere;
  - ripple and glints (the soft shadows landed in step 2);
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
- Water names over the map stay level, like the quarter names (21 October 2026).
- The canal ring is bundled for the first look offline.
- The look is refined in the engine, not in the renderer. The renderer and its port are frozen as the yardstick from 20 October 2026.
- Clouds, cloud shadows and reflections come in step 4, in the plan's order (21 October 2026); the soft shadows were pulled into step 2.
- The haze and the vignette are optional, on by default, in the app's Look settings (21 October 2026), and so is the grain (23 October 2026).

**Open**, each with a default so work never waits:
- **The renderer's known issues:** melting buildings is fixed by design (true depth). Others are fixed in the engine as they are found and named in the case files (the default), or the user lists them to be fixed first.
- **Textures while the map moves:** decided on the phone with step 3's switch. The default is the lean given there.
- **Labels in tilted views:** shrinking toward the horizon with perspective, or keeping the size their zoom gives them; decided on the phone. The default: their zoom's size, above the readable floor.
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
  4. at the end of a step, tag an alpha and tell the user. Besides, every night `alpha.yml` makes the latest commit of `app` that Core and Android built green the next alpha, when the app changed since the last one (the user, 23 October; `.github/scripts/nightly-alpha.sh` chooses, `release.yml` publishes). Scheduled workflows run from the repository's default branch only, which is `app` since 23 October (until then `main`, where neither this nor CI's nightly jobs ever ran).
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
