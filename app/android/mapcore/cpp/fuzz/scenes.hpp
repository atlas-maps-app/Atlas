// The scenes fuzz_load loads and fuzz_stage_t builds, from the same document by the input's size, so that the two
// targets fuzz the same loads: a region in a box around the Westerkerk, named Paris (its generic looks' stone and
// mansards) or not, with a tradition with every rule and detail generic.py reads (load/Generic.hpp's looks) or none,
// whole or simplified, a theme park (its coaster tracks, with no ride's profile; its woods are not planted here, a
// wood's lattice is the size of its outline); its window, the Westerkerk's L14 tile; and the living map with
// Amsterdam's New looks or none.
#pragma once

#include "base/assert.hpp"
#include "load/Amsterdam.hpp"
#include "load/Coasters.hpp"
#include "load/Generic.hpp"
#include "load/Regions.hpp"
#include "load/Tradition.hpp"
#include "tiles/Grid.hpp"

#include <cstddef>
#include <memory>
#include <span>
#include <string_view>

namespace atlas::fuzz {

// a tradition with every rule and detail generic.py reads, its kit's details whole as the style reads them (a band's
// colour, the boards' spacing and corner: tiles/Style.cpp refuses a pack whose tradition lacks them)
inline constexpr std::string_view tradition =
    R"({"name": "fuzz", "says": "a fuzzed house", "says_by": {"wood": "a wooden one"},
 "floor_h": 3.1, "modern_from": 1960, "levels": {"default": 3, "house": 2}, "heights": {"tower": 30, "worship": 15.5},
 "walls": {"home": {"before:1900": [["brick", "#6E3B2E", 2], ["plaster", "#E8E2D4", 1]], "after:1990": null,
                    "default": [["wood", "#ECE8DE", 1]]},
           "modern": [["glass", "#93ABBC", 1]], "industrial": [["metal", "#9AA1A7", 1]],
           "default": [["limestone", "#DCD0B6", 1]]},
 "roof": {"home": {"shapes": [["gabled", 3], ["hipped", 1], ["skillion", 1], ["flat", 1]], "material": "roof_tiles",
                   "colours": ["#8C3B2A", "#3F4044"], "pitch": 0.8, "terrace_across": true},
          "default": {"before:1800": {"shapes": [["mansard", 1]], "material": "slate", "colours": ["#535C66"]},
                      "default": {"shapes": [["flat", 1]], "material": "metal", "colours": ["#737E88"]}}},
 "rules": [{"when": {"kind": ["home"], "building": ["house", "yes"], "material": ["brick"],
                     "year": {"from": 1600, "to": 1900, "unknown": true}, "levels": [1, 9], "area": [10, 100000],
                     "narrow": 11, "min_h": 2},
            "roof": {"shape": "gabled", "orient": "along", "pitch": 1.1, "roof_h_range": [3.0, 5.0],
                     "material": "roof_tiles", "colours": ["#4A3E3A"]}},
           {"when": {"kind": ["office"]}, "roof": {"shape": "mansard", "roof_h": 3.4}}],
 "windows": {"bay": 2.9, "kinds": ["home", "default"], "materials": ["brick", "plaster"]},
 "shutters": {"share": 0.5, "colours": ["#56705A"], "kinds": ["home"]}, "eaves": 0.35,
 "boards": {"materials": ["wood"], "spacing": 0.2, "corner": "#F4F0E6"},
 "base_band": {"materials": ["brick"], "h": 3.3, "colour": "#ECE6DA"},
 "chimneys": {"share": 0.6, "kind": "party"}, "cornice": {"before": 1800}, "hoist": true,
 "towers_crenellated": true})";

[[nodiscard]] inline std::shared_ptr<const load::Tradition> fuzz_tradition() {
    static const std::shared_ptr<const load::Tradition> t = [] {
        const auto read = load::read_tradition(std::as_bytes(std::span(tradition.data(), tradition.size())));
        ATLAS_ASSERT(read.has_value(), "the fuzz targets' own tradition is refused");
        return *read;
    }();
    return t;
}

/// The region an input of `size` bytes loads: named fuzz or paris by (size / 8) % 2, with the tradition or none by
/// (size / 2) % 2.
[[nodiscard]] inline load::RegionConfig region_of(std::size_t size) {
    static const load::RideProfiles rides;
    return {.name = (size / 8) % 2 == 0 ? "fuzz" : "paris",
            .frame = load::westerkerk_frame(),
            .bbox = {52.36, 4.87, 52.39, 4.92},
            .tradition = (size / 2) % 2 == 0 ? fuzz_tradition() : nullptr,
            .palette = load::amsterdam_palette(),
            .park = &rides};
}

/// The region's simplification: whole or 0.5 m, by size % 2.
[[nodiscard]] constexpr double simplified_of(std::size_t size) noexcept { return size % 2 == 0 ? 0.0 : 0.5; }

/// The living map's looks: Amsterdam's New looks where size % 4 < 2, else none.
[[nodiscard]] inline const load::Looks* looks_of(std::size_t size) {
    static const load::Looks looks{
        .region = "ams", .tradition = nullptr, .landuse = load::LandUseFrom::areas, .keep_homes = true};
    return size % 4 < 2 ? &looks : nullptr;
}

/// The region's window: the Westerkerk's L14 tile.
[[nodiscard]] inline tiles::TileKey window() noexcept { return tiles::tile_of(14, 523'746'000, 48'840'000); }

} // namespace atlas::fuzz
