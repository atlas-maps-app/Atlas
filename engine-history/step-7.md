# Step 7: The engine becomes the main piece

Moved as it was from `ENGINE.md` on 17 November 2026: its lines 1711 to 1720 at c9602bc1. Nothing was reworded. The engine's document is `ENGINE.md` at the repository's root; this file is history, opened when a job needs it.

### 7. The engine becomes the main piece
- **The app:** what it still takes from the port moves into modules of its own. Since today's map left the phone (step 3, 5 November) that is:
  - the packs' library and readers (`PackLibrary`, `PackReader`, `PackFeatures`: `PackList`, `LocalData`, `PackWindows`, `HereWindows`), and `PyJson` for the packs' manifests;
  - the Overpass client and the region builder (`OverpassClient`, `HttpPost`, `RegionBuilder`, `OverpassJson`: the windows' data, the offline regions, `OnlineElements`);
  - the moment and the weather themes (`CoreMoment`: `Amsterdam`'s themes and sun, `Regions.resetPlace`, `LiveMoment` and `EngineState` under `LiveEngine.lock`), and the forecast's hours (`WxHour`);
  - the cameras' frames (`RegionFrame`, `LiveCamera`, `LiveView`: `MapCamera`, `HomeView`, `CameraOps`, `MapController`, VK bench), and `Moment`;
  - the shade walk's scoring.
- **Retire the renderer:** `render/`, the Kotlin port `:render`, `:rendertiles`' Kotlin derivation and the CPU raster (mapview's `src/raster`, `:anchor`'s SVG model and parser, which the likeness and the phone look tool's canvas side draw with) leave the build. `:mapview` then depends on `:mapcore` alone for drawing. They stay in git history, or in an archive if the user prefers.
- Tag the final alpha.
