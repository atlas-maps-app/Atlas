// A render pack's `rhead` and `style` as the packer writes them for the living map or a region of the renders
// (rendertiles' Head.of and Style.of over the port's scene; ENGINE.md step 3: the packer runs the core's stage P), made
// from what the packer knows of the pack: its profile, levels and frame, the port's engine version, its element pack's
// SHA-256, and a region's R (its region.json, with its views as data/regions/places.py frames them).
//   - The views (ViewLadder's knots): the living map's camera (amsterdam.py's main, ViewLadder.livingView) under the
//     pack's name, at tolerance 0; a region's views from R, nearest first, the overview under the region's name and any
//     other as `<region>_<key>`, each at the ladder index of the tolerance its renders load it at (regions.py's
//     GENERALISE, as Lod.ofView looks it up).
//   - The bands: one at 0 for the living map, which is always loaded whole; the live map's for a region (Lod.BANDS
//     ending at Lod.UPTO).
//   - The style (load/PackStyle.hpp): the living map's palette and module values with an empty scene; a region's
//     palette, the module values its load leaves (TZ_H from R's time zone), its scene's scalars (its name,
//     `<region>_whole`, the tradition R names, R) and that tradition whole.
// A region's variants load at its views' tolerances and the bands' (SceneToTiles.variantTolerances): variant_ladder.
#pragma once

#include "load/Tradition.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Values.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::load {

/// regions.py's GENERALISE as Lod.ofView looks a view of `region` up in it: the view's own tolerance (metres), else
/// that of the view's name without `_detail` and `_kit`, else the region's, else 0.
[[nodiscard]] double generalise(std::string_view region, std::string_view view);

/// The live map's bands for a region (Lod.BANDS ending at Lod.UPTO, as a region's rhead holds them): the ladder index
/// a camera at that distance loads at, nearest first.
[[nodiscard]] std::span<const tiles::HeadBand> region_bands() noexcept;

/// What a pack's head and style are made from.
struct PackFacts {
    tiles::Profile profile = tiles::Profile::region; ///< living or region
    std::string_view region;                         ///< the pack's id: the region's name, or the living map's
    std::span<const std::uint8_t> levels;            ///< finest first
    tiles::Frame frame;                              ///< the port's doubles
    std::string_view engine_version;                 ///< the port's EngineVersion.ID
    std::string_view source;                         ///< the element pack's SHA-256, lower-case hex
    tiles::Value r;                                  ///< a region's region.json (null for the living map)
    const Tradition* tradition = nullptr;            ///< the tradition R names, read; null: none
};

/// One of a pack's views: its name, its camera (a value of R's, or the living map's camera) and the ladder index of
/// the tolerance its renders load the region at.
struct PackView {
    std::string name;
    tiles::Value camera;
    std::uint8_t tolerance = 0;
    double dist = 0; ///< the camera's distance, which orders the views
};

/// The pack's views, nearest first, or why there are none: a region whose R has no views, a view the renders could not
/// frame (no target of three numbers, or no yaw, pitch or distance), or a tolerance off the keep ladder.
[[nodiscard]] std::expected<std::vector<PackView>, std::string> pack_views(const PackFacts& facts);

/// The ladder indices a region's variants load at (SceneToTiles.variantTolerances): its views' and the live map's
/// bands', but 0, ascending.
[[nodiscard]] std::vector<std::uint8_t> variant_ladder(std::span<const PackView> views);

/// A pack's `rhead` and its `style` before compression.
struct PackSections {
    std::vector<std::byte> head;
    std::vector<std::byte> style;
};

/// The pack's head with `views` and its style, or why not: a head the format cannot hold, R naming a time zone the
/// core has no offset for, or the engine's style file not reading.
[[nodiscard]] std::expected<PackSections, std::string> pack_sections(const PackFacts& facts,
                                                                     std::span<const PackView> views);

} // namespace atlas::load
