// A region's load (ENGINE.md step 3, stage P): render/regions.py's `load(name)` as the port computes it (R/Regions.kt),
// the rules the renders draw every place by besides the living map. On top of the living map's load
// (load/Amsterdam.hpp, in the region's frame, with its palette), what regions add:
//   - buildings and parts mapped as multipolygon relations, their outer ways joined into rings (Notre-Dame, the
//     Louvre), with guess_tags's values and their outlines' `hidden`;
//   - roof shapes the renders do not draw taken as the closest they do (a dome as pyramidal, a gambrel as gabled);
//   - land cover by regions.py's LAND, runways and taxiways, big roads, railways, power lines and their towers, marine
//     works, cranes, masts, gantries, conveyors, wind turbines, towers with a height, and national parks' edges;
//   - water mapped as multipolygons and its islands, the sea closed from the coastline along the extract's edge (or a
//     bay's ring where no coastline is), and which shores are natural (no quay wall, but in the engine's close-up cut,
//     RegionConfig::close_up, where buildings line an edge);
//   - the land cover in its drawing order, and each part's outline.
//   - each building's look (load/Generic.hpp): its tradition's where the region has one, else the generic look.
//   - a theme park's coaster tracks (load/Coasters.hpp), where its rides' profiles are given, and a park's or a zoo's
//     planted woods (load/Woods.hpp), once for the region.
// Not yet: the terrain and the water levels from a survey.
//
// As load_amsterdam, what the renders cannot load the core loads on: a coastline way without a geometry and a node
// without a position are left out; a position off the Earth refuses the document.
#pragma once

#include "load/Amsterdam.hpp"
#include "load/Coasters.hpp"
#include "load/Scene.hpp"
#include "load/Tradition.hpp"
#include "load/Woods.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <expected>
#include <memory>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::load {

/// What regions.py's `configure` takes from a region: its name, its frame (the doubles the port computed, which a
/// pack's rhead holds), its extract's box and its building tradition, and the palette its guessed colours come from.
struct RegionConfig {
    std::string_view name;
    tiles::Frame frame;
    std::array<double, 4> bbox{}; ///< south, west, north, east (region.json's `bbox`)
    /// region.json's `tradition`, read (render/traditions/<id>.json), or null for none.
    std::shared_ptr<const Tradition> tradition;
    /// Amsterdam's homes keep their guessed brick and plaster (generic.py's `keep_homes`, the renders' New looks).
    bool keep_homes = false;
    /// The engine's own cut, which the map draws close up (the phone's windows, the app's canal ring), where the
    /// renders cut a region for a view from afar: an edge of a natural shore but the sea's carries a quay wall where
    /// buildings line it (load/Cut's water_edges), where regions.py gives every region's water natural shores but
    /// Paris's, the port's, a bay's and an island's; and the facades carry their doors and house numbers as the living
    /// map's do, where regions set no_facades (a frame ranks only the doors it may draw: scene::ItemSpec::max_depth).
    bool close_up = false;
    Palette palette;
    /// A theme park's rides' profiles (region.json's `park`: data/park/<park>/pf), whose coaster tracks it loads; null
    /// for a region that is no theme park.
    const RideProfiles* park = nullptr;
    /// Where a theme park or a zoo (region.json's `park` or `zoo`) plants its woods about; none for another region.
    std::optional<WoodsAnchor> woods;
    /// Whether the woods in and about each zoo and theme park in the data (tourism=zoo, theme_park, of zoo_least_area
    /// or more and no petting zoo) are planted, as the renders plant a zoo's or a park's region (`woods`): every tree
    /// of woods.py's planting that stands in its outline or within zoo_woods_reach of it (load/Woods.hpp). The phone's
    /// windows, which no region.json marks (load::place_of); the forest and wood elsewhere stay outlines, as in every
    /// renders' region but a park's and a zoo's. Ignored where `woods` is given.
    bool zoo_woods = false;
};

/// regions.py's PALETTE for a region (Paris, Schiphol, the port), else Amsterdam's.
[[nodiscard]] Palette region_palette(std::string_view name) noexcept;

/// regions.py's `simplify(pts, tol)`: Douglas-Peucker on a closed ring, from its first point and the point farthest
/// from it; the ring as it was where fewer than three points or an area of 2 m² or less would be left.
[[nodiscard]] std::vector<V2> simplify(std::span<const V2> pts, double tol);

/// `load(view)` of a region seen at `tolerance` (0: whole, as a render pack loads it; else its footprints and areas
/// simplified, as a view or a band of the live map loads it).
[[nodiscard]] std::expected<Scene, LoadError> load_region(const osm::Document& doc, const RegionConfig& region,
                                                          double tolerance = 0.0);

} // namespace atlas::load
