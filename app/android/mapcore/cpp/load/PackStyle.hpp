// A pack's `style` and its style version as stage P derives them (ENGINE.md step 3: built tiles become local packs;
// rendertiles' Style.of and StyleVersion), so the packs and windows the core builds carry their own style, the packer's
// bytes for the same scene (tiles/SectionWriter.hpp writes the section):
//   - the format's tables: `keep`, the ladder; `render_tags`, the tag keys each layer carries;
//   - the engine's style file (app/android/mapcore/style/engine.jsonc, compiled into the core by cmake/Data.cmake and
//     read as the packer reads it, JSON with comments), its tables in the file's order;
//   - `material` and `clay` (load/Tables.hpp), `road_order` and `aero_order`, the ground's `ground_stages` and
//     `marks_classes`, the `building_flags`;
//   - what the region's load leaves: its `palette`, amsterdam.py's module values (`state`), the scene's scalars
//     (`scene`), and its building tradition whole (`tradition`), when it has one.
// The style version (rhead's) hashes the schema, the tag keys, the ladder and the style file's tables, as
// StyleVersion.ID does, so the core's packs and the packer's of the same derivation name the same one.
#pragma once

#include "load/Amsterdam.hpp"
#include "load/Tradition.hpp"
#include "tiles/Style.hpp"
#include "tiles/Values.hpp"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::load {

/// The engine's style file's tables, in the file's order, or null when the file compiled into the core does not read
/// as JSON with comments (pack_style_test holds that it does).
[[nodiscard]] tiles::Value engine_style();

/// StyleVersion.ID: `s<payload>.<derivation>-` and the first 16 hex digits of the SHA-256 of the payload version, every
/// layer's columns and their codecs, the tag keys each layer carries, the keep ladder and the style file's tables as
/// the `style` section writes them. Empty when the style file does not read.
[[nodiscard]] std::string style_version();

/// amsterdam.py's module values a load leaves (the `state` table), as rendertiles' Style writes them.
struct ModuleState {
    double water_y = -1.5;                      ///< WATER_Y
    std::string_view quay_col = "#6E5546";      ///< QUAY_COL
    std::optional<double> quay_course = 0.0625; ///< QUAY_COURSE (None at the port and Schiphol)
    bool tone = false;                          ///< TONE: True once regions.py has loaded a region
    std::optional<std::string_view> win_style;  ///< WIN_STYLE: "paris" in Paris
    std::int32_t tz_h = 2;                      ///< TZ_H: the place's UTC offset at noon on 1 October 2026, hours
    bool air_haze = true;                       ///< AIR_HAZE
    bool clouds_lit = false;                    ///< CLOUDS_LIT
};

/// The module values regions.py's load leaves for region `name` (its quays and windows, TONE), with its UTC offset.
[[nodiscard]] ModuleState region_state(std::string_view name, std::int32_t tz_h) noexcept;

/// The scene's scalars a region's style carries (`scene`): S['region'], S['view'], S['tradition'] (there, None where
/// the region has none), S['no_facades'] (a region's), and R, the region's region.json with its views as places.py
/// frames them (the caller's, typed).
struct SceneScalars {
    std::string_view region;
    std::string_view view;
    std::optional<std::string_view> tradition;
    tiles::Value r;
};

/// What a pack's style says of its scene beyond the tables every pack carries.
struct StyleFacts {
    Palette palette;
    ModuleState state;
    const SceneScalars* scene = nullptr;  ///< null: the living map, whose `scene` is empty
    const Tradition* tradition = nullptr; ///< the region's building tradition, written whole; null: none
};

/// The `style` section's bytes before compression (rendertiles' Style.of), or nothing when the style file does not
/// read.
[[nodiscard]] std::optional<std::vector<std::byte>> style_section(const StyleFacts& facts);

/// The `style` section the core draws every world pack in (ENGINE.md step 5: the world's style in one place): the style
/// file's tables, amsterdam.py's palette and module values at UTC and the living map's empty scene, the bytes the
/// packer's WorldPack.style writes into each world pack from the same style file. Nothing when the style file does not
/// read.
[[nodiscard]] std::optional<std::vector<std::byte>> world_style_section();

/// The style ladder's values (ENGINE.md step 5, "The style ladder": tiles/WorldLadder.hpp): the knots, the day's
/// ground, water and roads, and the Globe board's Earth as CosmosArt.drawEarth draws it. The world's style holds them
/// (world_style), not its `style` section: no pack carries them, so a change to one builds no pack again.
[[nodiscard]] tiles::WorldLadder world_ladder() noexcept;

/// world_style_section's values, typed (tiles::Pack::open), with the style ladder's (world_ladder): a world pack built
/// before a change to the style file is drawn in the style the core has now, and one whose style lacks a value the
/// core reads is not refused. Made once; null when the style file does not read.
[[nodiscard]] const tiles::Style* world_style();

} // namespace atlas::load
