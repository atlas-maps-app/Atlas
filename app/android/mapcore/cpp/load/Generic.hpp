// Generic looks for buildings whose colour or material is not mapped (ENGINE.md step 3, stage P): render/generic.py's
// `apply(S, region, landuse_at)` as the port computes it (R/Generic.kt), for a region without a building tradition:
// each building's and part's kind (from its building=* type, its use tags, the land use it stands in, its footprint),
// its wall and roof, their materials and colours as real OSM values, mapped values always winning (a colour word as the
// colour it names), the drawn colours varied a little per building, its window rows, and why, recorded as guessed.
// Not yet: a region's tradition (render/traditions: trad_look, neighbour_levels, party_walls, Paris's roofs).
#pragma once

#include "load/Scene.hpp"

#include <optional>
#include <string>
#include <string_view>

namespace atlas::load {

/// generic.py's `colour_hex(v)`: `#rrggbb` or `#rgb` as upper-case `#RRGGBB`, or the colour a CSS name means.
[[nodiscard]] std::optional<std::string> colour_hex(std::optional<std::string_view> v);

/// generic.py's `jitter(hexcol, seed, k)`: the colour a little lighter or darker, from random.Random(seed).
[[nodiscard]] std::string jitter(std::string_view hexcol, std::int64_t seed, double k = 0.05);

/// `GN.apply(S, region, landuse_at)` for a region without a tradition: the land use a building stands in is the first
/// of `S['land']`'s whose kind generic.py knows and whose box and ring hold the building's centroid.
void apply_looks(Scene& s, std::string_view region);

} // namespace atlas::load
