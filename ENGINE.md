# Atlas's map engine

Atlas draws its map with its own engine. This is the one document about it: what it is, how it works, what exists and what is left. It replaces every earlier plan, brief and note about the engine. Those are archived in `cache/claude-session/archive/engine-2026-10/` as history, and nothing here needs them.

**Read this first.** Until 16 November 2026 the engine had to match the Python renderer, and most of this document was written for that. The user dropped that requirement (What it is; The user's answers). This document is being rewritten for it. Until it is: where a section holds the engine, a check or a step to the renderer, its port, the twin or the likeness, read it as how the engine got here, not as a rule for new work.

## What it is

- **The centerpiece of the app, purpose built for Atlas.** It is done first, and the rest of the app builds on it.
- **Built from the ground up.** A C++23 core on Vulkan (`app/android/mapcore/cpp`), behind a small Kotlin façade (`app.atlas.mapcore.MapCore`). It draws vector tiles every frame the camera moves, at the screen's own density, and nothing at rest. A label stays where it is on the map and grows and shrinks with the zoom, like the map itself, and detail comes and goes with the zoom.
- **Global, and it builds itself.** The map covers the entire world, from the globe down to a single street. The phone gathers up-to-date map data for any place, at any time, and builds the map itself. Pre-built packs are additions, not a requirement.
- **Every detail OSM has, as smooth and efficient as other map apps, or more** (the user, 25 October). Nothing OSM maps is dropped from the data; each detail draws from the zoom where it can be seen, and goes on in the renderer's spirit where the renderer stops. A frame's cost follows what is on screen, not how much is mapped.
- **Independent.** The app never relies on a single source. Every input has more than one way in, and the map keeps working offline with what it has.
- **Held to what a person sees, no longer to the renderer** (the user, 16 November). The Python renderer (`render/*.py`, ported byte for byte to Kotlin in `app/android/render`) is where the look started: its features, rules, colours and light. From 20 October to 16 November it was the frozen yardstick, and the engine matched it exactly. Since then:
  - Nothing is drawn that cannot be there: overlapping buildings, streets that follow OSM's lines where a real street would not, roofs with parts missing.
  - Other map apps are matched or beaten, judged on the engine's own pictures and on a real phone.
  - No feature or detail the user had is lost without the user's word.
  - The checks that held the engine to the renderer leave in cuts, in the order the user agreed (The user's answers); the first landed on 17 November. The renderer and its port are retired with them.
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
   - The composite applies the screen effects once per pixel: haze, vignette, grain, `mat`, the wash, cloud shadows and rain.
   - Offscreen passes run before it only where an effect needs one: blurs, crowns, reflections, glows.
   - Newer Vulkan features are used where the phone has them, picked at run time from VK facts: dynamic rendering with local reads, synchronization2, present timing, and fragment shading rate for the thermal ladder.
6. **True depth for everything that stands up.** Buildings, parts, roofs, trees and things are drawn with a real depth buffer, kept in tile memory beside the samples, and tile by tile from the nearest for early rejection. They occlude correctly at every angle.
   - The renderer orders whole items by one point each, so at some angles its buildings melt into each other. The engine does not copy that.
   - The depth is reversed: near / k.z, in float with stencil where the phone has it (D32S8), else 24-bit, with the depth tests GREATER (`shaders/shared/painter.slang`).
   - The ground keeps the renderer's layering on its one plane: land cover, areas, roads, water and shadows, in its order. Its painter slots take the far end of the depth range, behind every true depth, so what stands up is drawn over the ground as the renderer draws it. Terrain (step 6) gives the ground a surface's true depth.
   - Where faces of one building touch, two small leads keep the renderer's order: a later cell's edge covers an earlier one's, and a roof covers the edge of its walls. Faces of two buildings that touch meet where their geometry puts them.
   - Flat detail on a wall (cells, windows, sashes, shutters, plinths, cornices, shop glazing, doors, numbers) keeps the renderer's exact shapes. It is expanded on the GPU from the wall's record and drawn clipped to its own wall, so nothing fights for depth.
   - The phone draws true depth alone since 30 October 2026. The user: "Why do we have these different looks? Its overcomplicated. There should be one unified and nothing else", then "The painter order setting can go unless you think otherwise". The renderer's painter's order stays in the core for its own checks only: a switch (`FrameOptions::painter`) draws it, the owner maps are drawn under it, and the vk tests hold the buildings, doors, items and trees to the renderer's order with it. Nothing on the phone turns it on. Features are built and tested for true depth, and the painter's order is kept working only as far as those checks use it.
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
2. **Element by element:** the twin (the core's per-frame code and the shaders' maths compiled as C++) emits what the GPU will draw. `L1Test` matches it against the port's SVG at the boards' views and the sweeps: in order for the ground, item by item for what stands up. Every new pass brings its twin and its cases. On the Mac it also matches the port's and Schiphol's close views by day and at night and their overviews by day (their element packs are the Mac's alone; CI has the canal ring's), both sides without the regions' height survey, from render packs the core's stage P cuts in the test.
3. **The GPU against the twin** on lavapipe, per pass.
4. **Likeness:** the smoke cases (the Westerkerk home and the Main board, by day and at night) against the port's renders on every push.
5. **The full set:** every named view of the renders, the sweeps and the sizes, nightly on CI. The planted failures that already exist keep running.
   - The nightly's full tier runs in two shards. Each judges the planted failures whose base case falls in it, and the smoke tier judges every kind on every push. The nightlies first ran on 23 October, once `app` became the default branch: the second shard holds only the board's plants (shift, drops), and `PlantedTest` failed it for want of the paint plant until its reference set said it was a shard.

**Where the renderer is wrong, the engine does it right.** The difference is named in the case files (melting buildings is the first), so the yardstick never pulls the engine back to the renderer's issues.

**The engine's own references, from step 1.** These are its frames at fixed views and moments: the boards' views first, then places around the world from step 4. They are stored per engine version, and every finished part is held to them. Every simplification is proven against them: Amsterdam and every other rendered place must turn out the same. Each version holds the frames of the Mac's lavapipe, recorded with the version. From version 26 it also holds those of CI's lavapipe-x86_64, so CI holds the frames on Linux too. CI's first run of a new version leaves them in its likeness report (`engine/engine.json`), to paste in.

**Always:**
6. **CI** runs every check on every push to `app`, side by side on GitHub's runners (the user, 30 October: "Optimize the flow. All the tests and suites we constantly run and wait on take too much time and slow us down"). A push to `app` never cancels another push's run: each commit gets its word, and the nightly alpha chooses among them. Anywhere else a newer push or run by hand cancels the older run of its branch.
   - `core.yml`: host builds and tests on x86_64 and arm64 under the validation layer (ASan and UBSan, TSan, the release flags), fuzzing, clang-tidy with both builds' databases, clang-format, the canaries, the NDK builds and the rule table.
     - The core's tests are one ctest entry per test file: `tests/<area>/<name>.cpp` is `<area>/<name>`, labelled with its area, and `vk` for a `_vk_test.cpp`. `atlas_tests_rest` runs any case no file's entry takes, and `atlas_tests_split` proves that each case runs exactly once. A host job runs the entries as many at a time as the runner has cores, each test process with one lavapipe thread; an entry whose checks time the runner runs alone and first, with lavapipe's every thread (`gpu/standin_vk_test`, whose steady frame the release build holds to half a second).
     - A host job whose tests would run long is split into shards (`host-debug x86_64 1/3`). `tools/ctest/shards.py` gives each shard its entries by the seconds `tools/ctest/costs.tsv` measured, the costliest first, so every shard computes the same split; host-debug x86_64's shards run their entries once more with every ASan check and no Vulkan, and that run's seconds (the column `host-debug-x86_64:novk`) count in each shard's wall too. `core-tests` then holds each job's shards to having run each entry exactly once, puts each shard's seconds and the entries' peak memory in the run's summary, and keeps the table measured again, each entry's seconds the mean of the table's and the run's, since the x86_64 runners' speeds vary from run to run (artifact `ctest-costs`). When the shards drift apart, or a new test file costs much, copy that `costs.tsv` over `tools/ctest/costs.tsv`; a job that needs another shard gets another line in the matrix. A file whose cases take too long for one entry runs as parts (`_test_parts` in `CMakeLists.txt`, `<area>/<name>:<k>`): each part takes the next cases of the file in their order, the last part the rest.
     - ccache keeps each job's compiled units: one cache entry a job, kept by a push to `app` and by the nightly, which delete the job's older entries; every other run only reads them. The canaries build host-debug's and host-tsan's trees with one planted test more, from those entries. The planted sin is `-DATLAS_PLANTED_PLATFORM_SIN=ON`, defined for `base/pymath/fdlibm/Trig.cpp` alone.
     - clang-tidy is two jobs, `tidy` with the host build's database and `tidy-android` with the Android build's, each in two shards that take half the units apiece (by a hash of the path). A unit is checked again only when its key changes (`tools/tidy/cached.py`: the unit preprocessed with its comments, its flags, `.clang-tidy` and the clang-tidy's version); the nightly checks every unit without the cache. clang-format checks every file in `tidy`'s first shard, whose clang-tidy runs only after it passes.
   - `android.yml`: the APKs (`apk`), the app's unit tests (`unit-app`), every other module's (`unit-rest`), `:mapcorecheck`'s JNI tests (`mapcorecheck`) and the likeness smoke tier (`likeness`) are jobs of their own. Once the APKs are built, `ci/smoke.sh` runs in five parts, each on an emulator of its own: `bench` (the home and the filters, then the bench APK's truth phase), `vk` (VK facts, VK bench and the crash guard, the pills, the map) and `screens-1` to `screens-3` (every third of the other screens), each keeping its artifact `atlas-emulator-<part>`. `emulator-coverage` holds the parts to each screen and phase once. The hash lines must read the host build's hashes as `ci/hashes.env` holds them (kept outside the workflows: the nightly alpha's token may not tag a commit whose workflow files differ from the default branch's). The nightly has a concurrency group of its own. An emulator that dies fails its part within about 5 minutes (smoke.sh's watchdog, "DEVICE GONE"): on 28 October 7f28e5d's emulator went offline at the walk screen, and every adb call after it waited until the job's limit, 110 minutes.
   - Outside downloads survive their failures (4 November: a system image that came broken, Robolectric's own fetch failing, Maven Central's 429 Too Many Requests, the emulator's device offline for a few seconds). The emulator's SDK packages (the system image, the newest stable emulator, the pinned build) come as the archives the SDK repository lists, each checked against its SHA-1 and fetched again whole while it does not check out (`ci/sdkpkg.py`), kept in the caches by package and revision and installed where sdkmanager finds them, so the emulator action's sdkmanager fetches nothing; the pinned build replaces the newest just before the emulator starts, and the lavapipe jobs take it the same way. Gradle tries a fetch 8 times over about 4 minutes (`gradle.properties`); Robolectric's Android is a dependency Gradle fetches and hands to Robolectric to use offline (the root `build.gradle.kts`); `apk` runs `testDependencies`, so the Gradle entry it keeps holds every module's test artifacts, and the test jobs (the nightly's likeness-full and vkcheck-asset too) restore apk's entries and say what Gradle still fetched (a notice). smoke.sh runs an adb command the device's absence refused again once the device is back (`adb reconnect offline`, then up to 120 s), with a warning, and takes a log a check reads again when it came cut short; one not had whole fails the part ("DUMP FAILED").
   - A run by hand on a branch (`gh workflow run core.yml --ref <branch>`, the same for `android.yml`) runs everything, reading `app`'s caches. To measure a change to CI warm, `-f keep_caches=true` keeps the branch's own caches; delete them after (`gh cache list --ref refs/heads/<branch>`).
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
| Data | render tiles, the packer's `render-tiles` and the core's reader (fuzzed); the core's writer of tiles and packs, with their head and style, the packer's bytes; the core's reader of Overpass's JSON, the living map's and a region's load from it with every look (the port's records) and their cut into tiles (the packer's bytes); windows, one L14 tile built from its box's data as a pack of its own, built again after a week from Overpass's changes merged into the data it keeps (and from that data alone when the core's version changes), a frame cell's windows one pack in the store, drawn alone or beside the bundled packs; the canal ring bundled, which draws the Westerkerk's place too (the Westerkerk's own pack stays in the APK for VK facts' hash lines); render packs that build for `amsterdam` and `grachten` |
| Numerics and style | pymath; the light, the theme and `ScreenLayers`; the style file (`app/android/mapcore/style/engine.jsonc`), which every render pack carries and the core reads, typed, as a pack opens; the rule table, which lists every line of the renderer that draws |
| Frame core | camera, ladder, cover, residency, heaps (one buffer and one virtual block of a budget as large as the device binds, `scene::heap_budget`: 64 to 256 MB, 128 MB on every PowerVR; a tile that finds no room waits for a range to go), ranks and pass registry; the composite (haze, vignette, `mat`'s rule, grain, cloud shadows, rain, golden wash); the twin and L1; the coarser pack around the active one, chosen as the smallest pack that holds it; the packs' own stage T (`Pass::build_pack`) |
| Sun | the sun test on the workers, equal to the port on every sample of 21 board renders; a lane for the pack drawn and one for the coarser pack around it |
| Ground and water | drawn and registered; L1 matched in all 11 cases; the soft shadows on the port's own filter grid, their boxes and fade bit for bit |
| Buildings, first half | walls, parts, every roof shape and houseboats, with sun bits, edges and the roofs' thin edges, at their true depth (a pair the renderer melts is drawn right; the core's painter switch, for its checks, draws the renderer's order); their windows, dark or lit, with the glass gradients, and close up deep, with their reveals, sills and sash bars; their plinths, cornices and shop glazing; held to the port's polygons and on lavapipe to a depth raster of the twin with its strokes; registered |
| Trees and things | every mapped tree with its trunk, limbs and clumps in their tones as the renderer draws it with weather (its generic tree without), the street lamps, benches, racks, baskets and bollards and the bridges' railings, each on one plane at its true depth; the sett roads' courses on the ground, each road's lines one layer; a region's towers, turbines, cranes, masts, gantries, conveyors, coaster tracks, planted woods and power lines at their true depth; held to the port by L1, the region items at the port, Schiphol, the Efteling and ARTIS too |
| Text | shaping, glyphs, canal names (read from the whole pack), and the board's labels drawn by the core, fixed on the map and scaled with it; registered, the fonts from the map's resources |
| Noise fields, thin strokes | WebKit's turbulence byte for byte (scalar and NEON); `ObjectStrokes` |
| Frosted glass | drawn by the core under the chrome |
| Likeness harness | references from the port, candidates from the host build, with their owner maps (real since 21 October); the engine's own references (`likeness/engine.json`); the world's at the style ladder's knots, with their names (`likeness/world.json`), and the globe check against the Globe board's Earth (`likeness/GlobeCheck.kt`); the desktop render tool |
| The app | the core draws the maps where it has tiles, under today's map and the overlays, cross-fading to today's map (the port on Canvas) elsewhere; the honest state where the phone cannot draw (three device losses included) |

The local branches are history; `wip/build-pack`, `wip/buildings-registration`, `wip/app-map` and `wip/text-registration` landed on 20 October 2026, and `wip/exact-haze`'s last commit (the rest was on `app` already) the same night. The old builders' worktrees went to the Trash on 20 October 2026; their work is on `app` or these branches.

## What the deep dive found (20 October)

Before anything is built on them, the app's skeleton, the core and the modules were read for what should be done differently:
- **The core's painter's order is one contained piece.** It lives in `shaders/shared/painter.slang` and `frame/Ranks`. True depth changes what the objects' vertex stage writes, the ground's hand-over and the depth format, not the frame core.
- **The tile store already opens packs from memory, files and remote ranges.** So tiles built on the phone become local packs, with no second tile path (step 3).
- **The app's map still pulls in the CPU stack.** `:mapview` exports `:render` and `:anchor` to the app; they leave with the CPU map at the end of step 3 (the port itself in step 7). Since 5 November `:anchor` and the CPU raster are in tests alone, and `:mapview` exports `:render` for what step 7 moves.
- **The rest holds:** the device layer, frame core, scene, tile reader, sun, text, noise fields, the façade and the toolchain are current and fit the design.

The app's own findings were app work, done outside this plan on 20 October:
- one persistent map at the app's root instead of a map per screen (`MapStage`);
- one set of Overpass servers: the map's builds ask the app's own, in its order;
- a second weather source (MET Norway);
- the app's features off OpenMapTiles. Places, addresses, street names and bridges read the map's own packs through `LocalData`, and Overpass where no pack reaches: one request per route for its steps' names, one per route for its bridges. OpenFreeMap, the tile cache, the tile readers and the Planetiler step are gone. Packs built on the phone are built again after a week. An offline region's pack is read by the map and the features, and a region beyond the published packs has its pack built on the phone (up to about 1,000 km²).

The engine builds on them: step 3's "the core asks, the app fetches" has its fetching side in `LocalData` and the pack library.

## Where the time goes, and the gains (24 October)

Moved as it was to `engine-history/time-and-gains.md` (80 KB): what the core counts and where a frame's time goes, the device survey's findings, the gains 1 to 28, and "On the user's phone first".

## What is left, in order

The engine is done first: it is the app's centerpiece, and the rest of the app builds on it.

The seven steps and the account of what was done in each moved, as they were, to `engine-history/` on 17 November 2026 (the user: "Engine.md is too much and it hurts more to read it then it benefits"). Nothing was deleted or reworded. They were written while the engine had to match the renderer: read them as how the engine got here. The order of work since is in The user's answers ("The order after parity").

| Step | Its account |
|---|---|
| 1. The new map on the phone | `engine-history/step-1.md` (3 KB) |
| 2. Every roof, the labels, the style file | `engine-history/step-2.md` (24 KB) |
| 3. The phone builds the map anywhere | `engine-history/step-3.md` (191 KB) |
| 4. The renderer's detail | `engine-history/step-4.md` (236 KB) |
| 5. The world | `engine-history/step-5.md` (192 KB) |
| 6. Finish the map | `engine-history/step-6.md` (29 KB) |
| 7. The engine becomes the main piece | `engine-history/step-7.md` (1 KB) |

- **"ENGINE.md step N" (or "part N") in a comment, a workflow or a note** means one of these seven. Only step 5 has numbered parts of its own, the world's 1 to 11, so a "step 8" to "step 11" is one of them: 1, the Netherlands' world on the Mac; 2, the world pass and its cover, flat; 3, the hand-over and the fades; 4, the governor; 5, world.yml for Europe; 6, fetch and keep; 7, the bend; 8, the globe's look and the ladder; 9, names; 10, the whole world and its references; 11, packs for offline use.
- **To find something:** `grep -n '<word>' engine-history/*.md`. The user's words are quoted where they were given, so the steps hold answers this document's own list does not: step 5 has "The user's yes" (3 November) and "Decided" for the world.
- **A line number cited before this split** is of the file as it was: `git show c9602bc1:ENGINE.md`. Most notes of 17 November cite `git show ba4a2acc:ENGINE.md`, where each line from the fifth up to The user's answers is 2 less.
- **A step's new account** (a "Done" entry) goes into that step's file, not here.

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
- The canal names flow with their canal and sit on its water, as the renderer draws them (27 October 2026: "the rivers name should flow with the river and sit embeded", "They do this on the renderer", "Ive meant canal names"): the app's map drew none at the home, where the renders draw PRINSENGRACHT on the water beside the Westerkerk. The core had no fonts: only VK facts loaded them (`TextRounding.install`), so the ground's text (the canal names, the runways' designators) drew nothing in the app; the core now loads them as it starts (`MapEngine.startCore`).
- The canal ring is bundled for the first look offline.
- The look is refined in the engine, not in the renderer. The renderer and its port were frozen as the yardstick from 20 October 2026 until 16 November 2026 (the next answer).
- **Parity is dropped** (16 November 2026: "drop the parity requirement and go on developing the engine fixing things that cant be there, overlapping buildings, streets that dont exactly copy what osm says (because thats wrong for real streets), missing roof parts, and new ideas and improvements"). The engine no longer has to match the renderer or its port, exactly or at all. It is held to what What it is says: nothing drawn that cannot be there, and "Being able to match competing maps apps or much better". Thought aloud in the same message and not decided: a makeover, auditing and rewriting large parts, pre-rendered models and other techniques beside vectors, and a low-requirement, energy-efficient 2D version through the same engine beside the full 3D one.
- **The order after parity** (17 November 2026): the parity checks' first cut; the real map timed on the user's phone, beside the buildings' fix; roofs; streets; the second cut; and on. "Keep the Rust trial": the buildings' cleaning is written in Rust as one small trial. A Rust rewrite of the engine is not decided.
- **The streets step builds one network** of streets, paths and heights, which stage P builds, the map draws and navigation reads, with the ground as its zero (17 November 2026: "Do whats right", to whether it should; the engine's lead took yes). Its measured heights wait for the open question below.
- Clouds, cloud shadows and reflections come in step 4, in the plan's order (21 October 2026); the soft shadows were pulled into step 2.
- The haze and the vignette are optional in the app's Look settings (21 October 2026), and so is the grain (23 October 2026). Off by default since 27 October 2026: "Grain vigentte and haze should be disabled by default if they add performance overhead", and each does (the composite's per-pixel steps, the grain's field, the port's full-frame noise filter).

**Open**, each with a default so work never waits:
- **The renderer's known issues:** melting buildings is fixed by design (true depth). Others are fixed in the engine as they are found and named in the case files (the default), or the user lists them to be fixed first.
- **Textures while the map moves:** decided on the phone with step 3's switch. The default is the lean given there.
- **Labels in tilted views:** shrinking toward the horizon with perspective, or keeping the size their zoom gives them; decided on the phone. The default: their zoom's size, above the readable floor.
- **The crooked windows:** as the renders draw them (the default), kept in one place so one edit changes them.
- **Building anywhere sooner:** the renderer's Kotlin port could build tiles on the phone first, exact but slower and heavier, until the C++ lands. The default: straight to C++.
- **The sources for many users:** with several Overpass servers, Geofabrik and the packs, no one server carries the app. Before a public release, decide whether Atlas adds its own mirror. The default: public sources, with caching and changed-since refreshes.
  - 27 October 2026, the user: "When moving outside the pre downloaded map it says building 0kb of 0kb or it shows rastering here or nothing at all". That hour every public Overpass server failed a one-node query: overpass-api.de "at capacity" (502), private.coffee and kumi.systems no answer in 25 s, maps.mail.ru 504, openstreetmap.ru no answer; only overpass.osm.ch (Switzerland alone) answered. Outside the bundled packs the map then has nothing. Decided: Atlas publishes its own packs, built on GitHub from Geofabrik's extracts with the core's stage P (step 5's packs, brought forward), and the phone takes those first; Overpass stays for the changes since a pack's date and for places no pack holds yet. Meanwhile today's map builds no area of its own from Overpass (it asked the same servers a second time), and the map's status says what the windows do: getting the area, building it, or "OpenStreetMap's servers are busy · trying again in N min" (`Windows.Status`). The phone takes them first since 28 October (step 3: a window's data from Atlas's own packs first).
  - 30 October 2026, every source the app asks checked twice (`sources-inventory.md` in the side sessions' notes): the same pattern. overpass-api.de answered "at capacity" (502), then took 30 s for a box of 20 m; maps.mail.ru gave no answer in 40 s, then took 27 s; overpass.kumi.systems took the connection and sent nothing in 150 s; overpass.private.coffee answered 504 after 112 s. Every other source answered both times. The phone now rests a server that failed (a minute, doubling up to half an hour for the windows, up to ten minutes for the other queries), asks the one that answered last first, and fails a window at once where every server rests.
  - The options, for the user to decide (the default stays: public sources). The recommendation: packs first now, as it is free, and a decision on a mirror before a public release.
    - **Packs first, everywhere (free):** packs.yml for every country the phone is used in, rerun weekly, so Overpass serves only the changes since a pack's date and the places no pack holds. It costs GitHub Actions time (free for a public repository; the Netherlands' windows step peaked at 11 GB of its 12 GB heap) and release storage (the Netherlands: 825 MB of tiles, 5.5 GB of windows' data).
    - **Atlas's own Overpass server (paid):** asked first, the public servers as alternates. The Netherlands or Benelux alone: a VPS of about 4 cores, 16 GB and 100–200 GB of SSD, roughly €15–30 a month, with attic data for the weekly changes (the augmented diffs). The planet: a dedicated server with 64 GB and 1–2 TB of NVMe, roughly €60–120 a month, a first import of one to two days, minutely updates and someone to keep it running.
    - **An Open Charge Map app key (keyed):** free from openchargemap.org. Built into the app, it turns Open Charge Map on for everyone, not only for those who paste their own key.
    - **A GitHub mirror of BRouter's routing data:** brouter.de is its only host. packs.yml could copy the 5° tiles each published country needs to Atlas's releases (E0_N50.rd5, the Netherlands' west, is 80 MB) as a second way in, at that storage and a weekly copy.
    - **A second host for the packs workflow's extract:** packs.yml reads Geofabrik alone. download.openstreetmap.fr serves the same Netherlands extract (1.64 GB on 29 October), but cut with its own boundary, so a pack built from it lists slightly different windows: a fallback, not a drop-in.
- **Williamsburg in its own look:** yes (the default), done last.
- **The close-range look beyond Amsterdam:** no (the default).
- **Measured heights:** may building heights and bridge decks use the national surveys' open data and building models, credited in the app? Not asked yet. The default: nothing of it is fetched or used until the user has answered.

## How to work

- **Where.** One session works on one job, in its own worktree on its own branch, and only there. The main checkout stays on `app`: nobody works in it, and finished branches land there.
- **Commits.** Commit in small steps, by explicit paths, in the repo's style, each message ending with the co-author line. Never rewrite pushed history: fix a pushed branch with a commit on top.
- **Landing.** Only a lead lands a branch on `app`; a builder reports its branch and its runs to its lead. A push to a branch other than `app` starts no CI: dispatch the branch's runs (`gh workflow run core.yml --ref <branch>`, and `android.yml` the same). The lead:
  1. brings `app`'s tip into the branch, and has CI run that merge before it becomes `app`'s tip (Android for a change to the core, Core and Android for a change under `.github`);
  2. fast-forwards `app` in the main checkout;
  3. pushes it (`git push origin app`) and lets CI decide.
- **Alphas.** Every night `alpha.yml` makes the latest commit of `app` that Core and Android built green the next alpha, when the app changed since the last one (the user, 23 October; `.github/scripts/nightly-alpha.sh` chooses, `release.yml` publishes). Its token cannot tag a commit whose workflow files differ from `app`'s tip, so a change under `.github` lands early in a day or right after a nightly. Scheduled workflows run from the repository's default branch only, which is `app` since 23 October (until then `main`, where neither this nor CI's nightly jobs ever ran). An alpha tagged by hand is the user's to ask for.
- **Ask first** before publishing anything else: packs, other release assets, or a new workflow's first run. A permanent deletion (a release, a draft, data) is the user's.
- **Stop at the user's budget cap:** commit tested work, report, and wait.
- **Local checks: only what the change touches.** The full suites are CI's (Checks, 6). `python3 -I app/android/ci/affected.py` maps the branch's changed paths (against `app`, with what is not committed) to the ctest entries and the Gradle test tasks that cover them, and prints their commands: a test file's own entry, every test file that includes a changed header (from the build folder's dependency records), the changed areas' labels, and `:mapcorecheck`'s tests of those areas. Then push, and read CI's word; a push never waits for another's run.
- **Heavy steps take turns,** sized to the machine they run on. Where the fleet's wrappers are installed, every build goes through them: `gw <gradle tasks>` for Gradle, and `heavy <name> <command>` for any other compile, test run, bench or packer run. They set the jobs, the workers, lavapipe's threads, Gradle's home and the pinned tools, so none of that is set by hand.
- **Build.** In `app/android/mapcore/cpp`, with the cmake the repository pins on `PATH` (the Android SDK's; the wrappers put it there):
  - `cmake --preset host-debug`;
  - `cmake --build --preset host-debug -j<jobs>`;
  - `ctest --preset host-debug -j2 -L <area>` (or `-R '^<area>/<name>$'`, `-L vk`), as `affected.py` says;
  - the presets set `ATLAS_REQUIRE_VULKAN=1`, so a Vulkan test fails where it would otherwise be skipped: on a machine without Vulkan those tests, the frames and the emulator are CI's;
  - TSan, the release flags, the arm64 build and clang-tidy run on CI.
- **Android:** `./gradlew :mapcore:assembleDebug`, through `gw` where it is installed.
- **The JVM checks:** the tasks `affected.py` names, such as `:mapcorecheck:test --tests 'app.atlas.mapcorecheck.sun.*'`.
- **Gradle without the wrappers:**
  - flags: `--no-daemon --max-workers=2 -Patlas.tools=<one folder for the pinned tools, shared by every worktree>`;
  - the core's builds that Gradle runs (`:mapcorecheck:coreHost`, `:mapcore`'s NDK build) take three jobs at once by default and every core on CI; `-Patlas.jobs=<n>` or `ATLAS_JOBS=<n>` sets another number (the NDK build's Vulkan-Hpp units keep their own two);
  - one `GRADLE_USER_HOME` for every worktree, so each build is warm;
  - lavapipe with `LP_NUM_THREADS=2`.
- **zsh.** Where the shell is zsh, a variable does not split into words. Use while-read loops or `--pathspec-from-file`.

**Learned the hard way:**
- **Python's `min`, `max` and `round` go through pymath** (`pymath::min`, `pymath::max`, `pymath::round`), and Kotlin's through `pymath::java`. clang -O2 on arm64 turns a select against zero into `fminnm`, which orders -0.0 below +0.0.
- **No specialization constants in shaders.** PowerVR's compiler crashed on one; a variant is its own module.
- **Use `SV_VulkanVertexID` and `SV_VulkanInstanceID`,** never `SV_VertexID` or `SV_InstanceID`: Slang's x86_64 build emitted different bytes for those.
- **Announce each driver call that can crash** to the crash guard (`gpu::announce`), for that call alone.
- **No blend reads the destination's alpha,** because colour attachments may be 10-bit.
- **Timing gates hold only with `ATLAS_TIMED=1`** on a quiet machine.
- **`cache/render` is the user's own renders:** never write there. Until 16 November a fresh run of `render/*.py` was the engine's reference; it is no longer (What it is).
- **Never read the renders' big SVGs into context.** Script them instead.
