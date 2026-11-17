# Step 6: Finish the map

Moved as it was from `ENGINE.md` on 17 November 2026: its lines 1539 to 1710 at c9602bc1. Nothing was reworded. The engine's document is `ENGINE.md` at the repository's root; this file is history, opened when a job needs it.

### 6. Finish the map
- Markers, the route and the location dot, drawn by the core in the map's own frame.
  - Done (27 October): **the location's puck** (`passes/Overlays.cpp`, `shaders/overlay/location.slang`). The camera state's version 2 (544 bytes, the façade's ABI 17) carries where the user is beside the camera (latitude, longitude, the fix's accuracy and its heading; the `LOCATION` and `HEADING` flags), written by `MapCameraSource` at each vsync, so the puck moves with the map in the same frame instead of being drawn by the canvas over it a frame apart. The overlays pass draws it after the labels in the composite subpass, as `OverlayPainter.drawLocation` draws it: the accuracy's circle on the street (exact: each pixel's ray meets the street, 13 % of the blue and a 1 dp edge at 35 %) where it is wider than 20 dp at the fix's depth, then the heading's cone (the board's conic gradient, turned toward a point 15 m ahead on screen), the 20 dp halo, the blurred glow, the white disc and the blue dot, every edge an analytic coverage of one pixel. The app hands it over once the start's paper is gone (as the labels; until 5 November only where nothing of today's map was over the core); until then, in the honest state and where the core refuses it, the canvas draws it.
  - Held by `location_vk_test` on every configuration and route (the dot's blue, the white ring, the cone's bright and clear sides toward the heading, each pixel well inside or outside the circle as the frame's rays meet the street, nothing without the location, the parts decided as the canvas decides them), `CameraStateTest` and both `LayoutTest`s, and `MapCameraSourceTest`.
  - Done (27 October): **the markers** (`markers/`, `passes/Markers.cpp`, `shaders/overlay/marker.slang`; `MapCore.markers`, the façade's ABI 18), drawn by the core in the map's own frame, so they move with the map in the same frame.
    - The app draws each marker's look as a sprite with Android's own Skia, exactly as `MarkerPainter` draws the marker as itself, with the marker's place on a whole pixel (`MarkerSprites`). Markers that look alike share a look. A named pin has two, its name right of the pin and left of it, and the core picks the side as the painter does, by where the pin stands on the view.
    - The core places the markers each frame as `MarkerLayout` places them (`markers/Layout.cpp`, its float arithmetic ported): within 60 dp of the view, by priority, folding into the ones placed before them, a dot or cluster that folded others drawn as a cluster of their counts. The draws go the lowest priority first.
    - A marker as itself is its look, copied texel for texel where its place lands, rounded as Kotlin rounds. A cluster's look depends on how many it holds, which changes as the map moves, so the core draws its bubble itself as `MarkerPainter.cluster` draws it (the white disc over its shadow, the disc of its colour inside), with its count's sprite over it. The app draws a count's text for every count the markers can make (up to 2,048 texts an ink).
    - The core's labels keep clear of the markers' boxes and of a named pin's name, as the canvas's labels keep clear of the canvas's markers (`FrameState::marker_boxes`).
    - The app hands the markers over once the start's paper is gone (until 5 November, where nothing of today's map was over the core) and nothing is on the ground that the canvas draws over the core's picture (a route, an area, until the core has them). There the canvas lets go of them once the core has them, so none are missing between. Elsewhere the canvas draws them, and the same rule now holds for the location's puck, which the canvas's route ribbons would otherwise cover.
    - Hit tests stay the app's: the canvas still lays the markers out each frame for taps.
    - Held by `tests/markers/` (`MarkerLayoutTest`'s cases, the counts' keys, a pin's name and its box; the block and the sets the core refuses), `markers_vk_test` on every configuration and route (a look's texels exactly, a cluster's bubble and count, the draw order, a label at a pin's place kept clear of it), mapview's `MarkerSpritesTest` (each look and count is `MarkerPainter`'s own pixels at three densities) and the façade test.
  - Done (27 October): **the routes and the areas** (`routes/`, `passes/Routes.cpp`, `shaders/overlay/route.slang`; `MapCore.routes`, the façade's ABI 19), drawn by the core on the street in the map's own frame, under its labels, markers and puck.
    - The app hands each route's look as `OverlayPainter.drawGround` sets it (its fill, faded and casing colours, its width, the alternatives first), its points with their distances along it (`RouteRibbons.along`), and its label's chip as a sprite drawn by `OverlayPainter.routeChip` (`RouteChips`); and the shaded circles.
    - Each frame the core cuts each route's segments to the part between the canvas's near clip and its haze (1.5 times 2 % of the distance, within 0.5 and 20 m; 6 times the distance), as `RouteRibbons.draw` does, and widens it by the camera's scale (the metres a dp spans at the target). Each piece is a quad over its capsule's box on the view.
    - The shader finds where each pixel's ray meets the street and measures the ribbon there, in the frame's metres: within its half-width of the piece (never thinner than its least half-width in device px), with round caps; a pixel nearer the piece before or after it in its run is that one's, so the joins draw once. Dashes are the nearest point of the nearest dash; the colour's part is over the faded part with its round cap, as the painter's two pieces are. Areas are exact circles on the street with their 1.5 dp line.
    - Each route's chip stands halfway along the part of its runs on the view, 24 dp in from the edges, with the runs' points kept as `RouteRibbons.add` keeps them; on a whole pixel, over every ribbon.
    - Unlike the canvas's ribbons (`TriangleBuffer`'s vertices, which Android does not antialias), the core's edges are antialiased.
    - The markers and the puck now go to the core once it has the routes and areas, and the canvas draws nothing on route screens either (gain 12).
    - Held by `tests/routes/block_test.cpp`, `routes_vk_test` on every configuration and route (looking straight down: the fill, the casing and the frame beyond at their widths, the travelled part faded, a join, a dash and the casing across a gap, an area's fill and line, the chip at its place), mapview's `RouteChipsTest` (a chip is `routeChip`'s pixels but for a sub-sample or two of its pill's edge; the block's routes in the painter's order and look) and the façade test.
  - Done: the building asked about drawn as the board draws it (below: its colour, 27 October; its roof, 12 November).
- Taps on buildings: a hit test against the tile's own buildings, giving the OSM id.
  - Done (27 October) for the long press (`scene/Pick.cpp`, `MapFrame::pick`; `MapCore.pick`, the façade's ABI 20): the building the ray from the last frame's eye through the pressed point meets first. The core reads the chunks of the packs its resident tiles came from, those whose reach meets the ray's track from 350 m up down to the street. Each building and part there stands as its footprint's walls from its base to its height, lidded at its height (a pitched roof's eaves stand lower, so a ray just over them may still meet the lid). An outline drawn by its parts is met through them and named by its own id. What's here takes the building (`Dest.WhatsHere`'s `osmId`), so its spot card and FillIn name the building under the finger. Before, What's here took the outline holding the street point under the finger, which may lie behind a tall front or in the next building.
  - Held by `tests/scene/pick_test.cpp` on the bundled grachten pack (40 buildings named straight down and by a steep slant, each at its height; a low slant from beside meets a nearer wall; none upward, off the packs or in a pack not given), mapview's `MapPickTest`, `LinksTest` and the façade test.
  - Done (12 November) for short taps: a tap on a building the core draws opens the place it is, or the one in it under the finger (`MapHit.OnFeature`). The tap finds the building as the long press does (`MapCore.pick`), and the screen says which place it opens (`AtlasMap`'s `featureOf`, `MapController.onTap`, `featureHit`): the building itself where Atlas lists it as a place (named, of a category: a church, a museum, a shop that is its building), else the listed place standing in its outline nearest to where the tap met it, within 20 m (the shop behind the front tapped); a building that holds none is an empty tap where the street lies under the finger, so the screens that clear a selection on an empty tap clear it on a house as before (`LocalData.feature`). The place comes from a region's store or the map's packs, else from the map's window data, waited on for 3 s at most; no server is asked for a tap. A marker under the finger still comes first, and Colour the map's own tap on a building (`onTapBuilding`) is as it was. Home, the search over the map, a query's map and On the map open places so; the screens that pick a point or a route's stop do not.
  - Held by mapview's `MapPickTest` (the place or the empty tap on the street) and the app's `LocalDataTest` (about the Westerkerk: the church itself from the store and the pack, each place opened where it stands in its building, a house empty, a tap farther than 20 m from a building's places empty).
  - Left: a short tap on a point of interest the core draws outside a building (a name or an icon on a square), which needs the labels' own hit test.
- **The user's own edits, drawn at once** (FillIn and the painter after it: `cache/claude-session/scratchpad/briefs/app-fillin.md`; the app's EditQueue holds them until they are sent):
  - An edit is data. **Done (27 October):** `osm/Edits.hpp`'s `apply_edits` writes a window's data with the user's edits kept on the phone (since 30 October only those OpenStreetMap accepted, so an edit not yet sent is never drawn as mapped) before it is loaded and cut, each applied as the app's sender applies it (OsmEdits.applied: where every key still holds what the user saw; one the data carries already, or whose element changed since, changes nothing), every other byte the kept data's. The app's `Windows` builds every window through it (`Native.editWindow`, `UserEdit`) and keeps the data as it came, so the next diff is asked from the data's own date and an edit the data carries drops out by itself; FillIn's send (`MapEngine.edited`) builds the windows whose boxes hold the building again from their kept data, before anything else and with no server asked, and the map takes the new packs (their time and size changed). A painted colour is drawn exactly as a mapped one: `tests/osm/edits_test.cpp` builds the Westerkerk's window with FillIn's brown brick on way 266556292 (its wall guessed #8D7D73) and finds #5C3A30 mapped, not guessed, byte for byte the window of an answer that maps it; the façade test runs it through the bridge.
  - Done (27 October) for the colour: **the building asked about** (`frame/Asked.hpp`, `MapFrame::set_asked`; `MapCore.ask`, the façade's ABI 21; `FillInState.asked`). While FillIn asks about a building's colour, the frame draws the building as the board's house: plain, without its windows or its facade's detail, its walls in one flat colour and unlit.
    - Before an answer (or after "Can't tell") the walls are the board's grey #A2A5A9 under its hatch: #8C857A lines 2.2 css px wide every 7 px at 45°, on the walls alone, as the board's `none_*` house. Once a colour is picked, the walls are that answer's colour as the board draws it (brown #453433, red #65413A, white #969694, sand #867C6B, black #2A2C32), unhatched, through the send. A building whose colour is mapped already is drawn as mapped.
    - The rank table marks the building's items asked (their rank entry's top bit; `Ranks`), which keeps only their near cells' detail and tells the building shader to hatch; the walls pass relights the building's wall faces in the flat colour (`passes/Walls.cpp`, each face's item and whether it is a wall: `BuildingTile`).
    - Each item carries the building it draws (`ItemSpec::building`: an outline's own key, a part's outline's where the part names one in its `parent_id`), so a building drawn by its parts is asked through all of them, as the long press names it by its outline.
    - Held by `geometry_test` (a part keyed by the outline it names) and `buildings_vk_test` (the synthetic tile's building under the view's middle, picked as a long press picks it, its raised part's rows keyed by it: while the question is open its walls grey under the hatch, once answered brown and unhatched, and with no question the frame as it was, pixel for pixel), `FillInStateTest` (the board's looks through its flow) and the façade test.
  - Done (12 November) for the roof: **the asked building as the board's house, under the roof asked about** (`scene/House.hpp`, `passes/House.hpp`, `shaders/overlay/house.slang`; `MapFrame::set_asked`, `MapCore.ask`'s `roof`, the façade's ABI 30; `FillInState.asked`, `AskedBuilding.roof`). While FillIn asks what shape a building's roof is, the map draws the building as the board's house in its place: the plain box under its white top until the answer (and after "Can't tell"), then the box under the roof picked, gabled, hipped or flat, from the pick through the send; another pick, another roof, in the next frame.
    - No tile holds that shape: a tile holds its building's own roof, mapped or guessed. The core builds the one building again from the chunk of the finest resident tile that draws it, with the roof asked for (`geometry::build_house`: stage T's own walls and roofs over the same footprint, plain: each wall its one cell, no windows, no kit, never a guess), into triangles the house's pass draws at their true depth among what stands up, and the ranks leave the building's own items out meanwhile, its parts' too (`RankOptions::replaced`), so nothing of the tile's building is drawn. A pitched roof keeps the building's own roof height where its own roof is gabled or hipped, else takes the loader's for a mapped shape without a height (0.45 of its shortest side within 2 and 5 m, at most 0.4 of the building: `house_roof_height`); the walls stand to its eaves.
    - The look is the board's: the walls in the asked building's flat colour, unlit, under its hatch before the colour's answer (the same lines, `house_hatch`); a gable in the walls' colour; a slope or a hip in the board's grey, #A2A5A9 turned away from the frame's sun to #BDBFC4 square to it (no shadow falls on it, none is lit at night); a flat top #FFFFFF under its outline (#6F695F, .45 CSS px: one device px, drawn with the roofs' thin edges). Where the colour is mapped and the roof alone is asked, the walls keep the colour their tile gives them (`AskedBuilding.OWN_WALL`), flat.
    - The house is read when the question names its roof (a tile's decode, once a roof) and again where its tile comes later or is built again in another pack (looked for twice a second while no frame draws it); it is drawn while the cover draws the tile it was read from. Where no house can stand (the outline is drawn by its parts, a houseboat, no tile draws it), the tile's own building is drawn as asked, as before. Under the painter switch (the core's checks) no house is drawn.
    - Held by `geometry_test` (the synthetic tile's flat house under each roof: its walls' top, its slopes, gables and hips; the gabled one hipped and flat; none of a houseboat, a way asked as a relation, a roof FillIn does not offer), `view_test` (the ranks leave a replaced building's items out, its part's too), `buildings_vk_test` (the building under the view's middle: the box grey under the hatch with its white top, brown under a gabled roof's and a hipped roof's greys, in its tile's own colour, the tile's own building without a rank; no house of another building, and with no question the frame as it was, pixel for pixel), `FillInStateTest` (the board's states through its flow) and the façade test.
    - Left: the house casts the shadow, and shows in the water the reflection, of the tile's own building; a building drawn by its parts has no house.
  - Offline the windows are not built (MapEngine.windows needs the network), so an edit made offline is drawn once the phone is online.
- **Power:** ADPF, and a short thermal ladder measured on the user's phone:
  - 2x MSAA while moving, where the GPU has it;
  - the offscreen passes at half resolution while moving;
  - follow mode at 30 fps.
- **Terrain and relief everywhere,** from the best elevation model for each region and a global one elsewhere, with the dunes and San Gimignano among them.
  - **History.** Designed on 12 November by the terrain session. The engine lead took its choices the same day, and the user approved the sources and their credits on 12 November.
  - **The user, 12 November:** "I think it doesnt have to match the relief images, it can be much better. Even if its in the plan, design for easy advancements later. I think the look of relief in streets.gl is good? Maybe there is something even better". Later the same day: "You dont have to pick anything from streets.gl approach. You can", and then: "You can meant you can pick anything from streets.gl approach. You dont have to". So any part of streets.gl's approach may be taken, and none has to be: each part of the look is whichever reads best on the boards' views.
  - **Sources.** Each source is one entry of `app/android/packs/terrain-sources.json`, so a further country is one entry. An entry holds:
    - its area and priority;
    - its hosts;
    - its format, CRS and datum;
    - whether it is a DTM or a DSM, and its resolution;
    - its licence and credit, and the date it was read.

    Worldwide, the Copernicus DEM:
    - GLO-90 for the globe and L6–L10, and GLO-30 for L11 and L12, read by range from its COGs on AWS.
    - It is a surface model, so the packer lowers it under OpenStreetMap's buildings by their footprints.
    - For forests outside the laser surveys, step 4 compares two options side by side on three forest views:
      - GEDTM30, OpenGeoHub's bare earth, CC BY 4.0;
      - a packer filter that lowers Copernicus under OSM's forests to their edges' ground, feathered.

      Step 4 takes whichever reads truer.

    National laser surveys at L12–L14, wherever they are open:
    - AHN4 for the Netherlands (0.5 m);
    - for Italy, Regione Toscana's 1 m lidar, with TINITALY 1.1 (10 m) elsewhere;
    - Kartverket's 1 m DTM for Norway;
    - for France, IGN's LiDAR HD and RGE ALTI;
    - the Environment Agency's LIDAR Composite DTM for England;
    - USGS 3DEP for the United States;
    - Flanders' DHMV II, Wallonia's MNT LiDAR and Luxembourg's ACT LiDAR.

    How sources combine:
    - Where several sources cover a tile, the highest priority wins and the next fills its gaps.
    - At a source's edge, the packer removes the mean offset along the edge and feathers over 1 km.
  - **Credits.** The app's licence screen shows the credits of the sources in the user's packs. The map's credit line adds "Copernicus" and the place's national source.
    - For each of WorldDEM-30 and WorldDEM-90, the licence's Art. 6(b) notice and its Art. 6(c) sentence. For GLO-30 they read: "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved", and "The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30". GLO-90's are the same with WorldDEM-90.
    - The national credits:
      - "AHN";
      - Regione Toscana, once step 8 settles its licence;
      - TINITALY 1.1, CC BY 4.0, with its citation (Tarquini et al. 2023, doi 10.13127/tinitaly/1.1);
      - "© Kartverket" with the NLOD 2.0 sentence, the licence its download feed names (the credit meets CC BY 4.0 too);
      - "IGN", Licence Ouverte 2.0;
      - the Environment Agency's notice, under the OGL v3;
      - "USGS 3DEP";
      - Digitaal Vlaanderen and the SPW;
      - ACT, for Luxembourg;
      - GEDTM30, CC BY 4.0, if step 4 takes it.
    - A CC BY credit names the source and the licence, and says the heights were resampled.
  - **Heights in packs of their own,** fetched by range. They are kept apart from the map's packs, which are rebuilt monthly, because heights change about once a year.
    - Three kinds:
      - `terrain_globe`: L0–L5, about 10 MB, resident.
      - `terrain_world`: L6–L11 in the world's blocks, on a release of its own, about 56 GB at 257².
      - `terrain_country`: L12–L14 for each packs.yml region, keyed by level, x and y over the region's land. The Netherlands is about 0.45 GB. Its tiles lie in packs of an L9 tile each (as a world pack holds an L6 tile's L9–L11), end to end in parts, with an index in the world's own format (`<region>-terrain.idx`): a pack's head is some 100 KB however large the country, and none leans on the country's windows, which Norway does not have.
    - Each map tile gets one raster in format 3's `heights` layer (layer 16; rendertiles' `Heights.kt` is its specification, the core's `tiles/Heights` its reader):
      - 257² samples over the tile's box, capped at the source's resolution: a raster is never sampled finer than 0.75 of what its source is read at, and a level that would only repeat the one above is not written (TINITALY's 10 m stops at L13);
      - the samples of a level lie on one lattice over the world, even in longitude and in Web Mercator's y, so a tile's edge samples are its neighbours' and every second sample is one of the level above;
      - an apron of 8 samples beyond the edges, the neighbours' own to the bit: the packer computes each node of the lattice once;
      - quantized to the power of two nearest 1 % of the spacing (1/16 m at L14 in the Netherlands), on the lattice of whole steps from 0 m, so tiles side by side share their step and their shared samples;
      - each sample predicted by MED from its west, north and north-west neighbours, the residuals as zigzag varints, the tile one zstd frame as every tile of the format.
    - The layer is no layer of the map's schema, so a map pack's style version does not move with it: the map's readers skip it as a layer they do not know, and the terrain's reader takes its columns (the side, the apron, the step, the base, the highest sample, flags, the sources and their shares, the samples). A later column (a mask, a baked sky-view) is no change of the format. The reader refuses a bad length, range, lowest or highest sample.
    - A terrain pack has a profile of its own (`terrain`) and a style without tables. The store holds terrain packs apart from the map's (`Store::heights`), so the map is drawn from the same packs with or without them.
    - A tile with no raster of its own level reads its nearest coarser one, cut to its box.
    - A tile with no heights yet is drawn flat, and takes its relief as a top-up when they come.
  - **Steps.** About 88 builder-hours. Each step is on its own branch off app.
    1. **Heights in packs (10 h; built 12 November, branch `heights`):**
       - the heights layer and its decoder;
       - the store's `terrain_country`;
       - the registry and `packer heights`, with `packer heights-check`: every tile decoded by the packer's reader and by the core's host build, hash for hash; every apron against its neighbours' samples; 50 samples over the boards' views read afresh from their source;
       - terrain's own workflow, `terrain.yml`, which keeps its packs as artifacts and publishes nothing;
       - the Netherlands, Tuscany and Norway, each level whole where its fetch is under about 10 GB, else around the boards' views;
       - the credits of the registry's sources on About & licences (`packer/LICENSES.md`'s table), before any phone reads a terrain pack.

       What the hosts gave, measured on 12 November:
       - No national host averages for a coarser grid. PDOK's coverage service, asked for 5 m, answers 0.17 to 0.43 m RMS off the block mean on the dunes; INGV's picks the nearest sample; Kartverket's sheets' overviews are nearest picks too. So nothing is scaled by a host.
       - AHN4 is read from PDOK's own sheets (cloud-optimised GeoTIFFs, 1,373 of them), at their 4 m overview: a filtered reduction, within 8 cm RMS of the 4 m block mean on the dunes, 5.9 GB for the country. Its 2 m overview is within 2.4 cm and about 23 GB.
       - TINITALY is read at its own 10 m from INGV's coverage service (1.8 GB for the region), and averaged in the job.
       - Kartverket's DTM 1 m is read whole, sheet by sheet (0.7 GB each), and averaged in the job: 17.5 GB for the 25 sheets round Bergen. Its nationwide 10 m model is one 19.6 GB file, over the limit, so Norway's L12 too is built around Bergen, and step 8 does the rest.
       - Regione Toscana's lidar is not read: its hosts did not answer, and an archived copy of its service sheet gives the 1 m model to public bodies only, under CC BY-NC-SA. Tuscany is TINITALY's until the user or the terrain lead settles that licence.
       - Kartverket's download feed names the Norwegian Licence for Open Government Data 2.0; the credit shown meets it and CC BY 4.0.

       What terrain.yml built (the runs' artifacts; nothing is published):

       | Region | Source | Levels | Tiles | Size | MB per 1,000 km², its levels together |
       |---|---|---|---|---|---|
       | Netherlands, whole | AHN4 at 4 m | L12–L14 | 23,193 | 470.0 MB | 12.2 |
       | Tuscany, whole | TINITALY 1.1 | L12–L13 | 4,104 | 167.4 MB | 4.0 |
       | Norway, 20 km round Bergen | Kartverket DTM 1 m | L12–L14 | 1,616 | 61.8 MB | about 34 |

       - Every tile is decoded by the packer's reader and by the core's host build, hash for hash. Every apron and edge sample equals its neighbour's to the bit.
       - 50 samples a region, read afresh from the source over the boards' views and averaged as the packer averages, stand within half a step of the pack's: the Netherlands' worst 0.030 m of a step of 1/16 m (mean 0.015 m), Tuscany's 0.061 m of 1/8 m, Norway's 0.031 m of 1/16 m.
       - A tile of open sea is not built: TINITALY and Kartverket give the sea 0 m, and the registry's `sea_m` says so.
       - The Dutch grid has a datum of its own, and GDAL chooses the operation to the tiles' CRS by the bounds of the raster it warps. The registry's `operation_area` names the box it is chosen for, whatever those bounds.
       - The check cuts a sheet's overview on the overview's own pixels (GDAL's open option `OVERVIEW_LEVEL`). With gdal_translate's `-ovr` and `-projwin` a cut lay up to half an overview pixel (2 m) from where it said, and 10 of the Netherlands' 50 points stood up to 0.43 m from the pack on slopes, while the same pixels averaged by hand gave the pack's heights.
    2. **Relief light on the plane (10 h):**
       - `shared/relief.slang` and its twin;
       - the tiles' heights as textures;
       - a sky-view baked per tile, 16 directions within 8 samples;
       - `FrameOptions::terrain` and the style's `relief` values.
    3. **The surface (18 h):**
       - stage T's mesh, RTIN with skirts;
       - every ground record laid on it;
       - the ground at true depth, its painter slots kept as leads;
       - water at its levels, the surface cut along its shores;
       - what stands up at its base, and bridges at their decks.
    4. **The world's relief (14 h):**
       - world.yml's `heights` stage for Europe and the globe;
       - `terrain_world` and `terrain_globe`;
       - the sphere at R + h;
       - the forests' comparison.
    5. **Terrain shadows (10 h):**
       - horizon maps per tile;
       - soft terrain shadows;
       - the horizon in the sun's test.
    6. **On the surface (8 h):** labels, markers, the puck, routes, the pick and the camera's target, all on the terrain.
    7. **The far look (8 h):** haze by depth and height, and the globe's relief faded out by 6,000 km.
    8. **The planet and the countries (6 h, plus CI time):**
       - every region's terrain, and the planet's;
       - the levels step 1 left around the boards' views;
       - the credits;
       - terrain in a kept country's download.
    9. **References (4 h):** terrain views in EngineRefs and WorldRefsTest, once the user approves the look.
  - **Checks.**
    - With terrain off (`FrameOptions::terrain`), every check is byte for byte as today.
    - Terrain is on everywhere, Amsterdam, the port and Schiphol among them (the engine lead, 12 November). Their checks stay flat.
    - A terrain check at the dunes and San Gimignano holds the core to the renders where the two share rules. Each region's survey is stored losslessly, and the check alone takes the renders' choices.
    - The light is the engine's own.
- Williamsburg in `real.py`'s own look, if the user says yes.
- **Cleanup:** the old plan's citations (`plan-vector D…`, `K…`) leave the code's comments.
- Tag an alpha.
