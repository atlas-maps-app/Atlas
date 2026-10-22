// Generic looks for buildings whose colour or material is not mapped (ENGINE.md step 3, stage P): render/generic.py's
// `apply(S, region, landuse_at, keep_homes)` as the port computes it (R/Generic.kt): each building's and part's kind
// (from its building=* type, its use tags, the land use it stands in, its footprint), its wall and roof, their
// materials and colours as real OSM values, mapped values always winning (a colour word as the colour it names, or as
// house paint on a tradition's walls), the drawn colours varied a little per building, its window rows, and why,
// recorded as guessed.
//
// Where the region has a building tradition (load/Tradition.hpp), its walls, roofs and details by kind and era come
// first: the levels it usually builds where nothing says otherwise, a roof shape from its rules or roofs with their
// pitch, its weighted picks drawn from each building's own random streams, and the details it hands on (windows,
// shutters, eaves, boards, a base band, a cornice, a hoist beam, chimneys, crenellations). A building it says nothing
// about takes the generic look. Before the buildings: a building without height or levels takes the median of its
// mapped neighbours' (Paris, and every tradition), and the walls shared with a neighbour are found where the
// tradition's chimneys stand on them. Paris's own: its stone's tones and the mansards on its old stone fronts.
//
// Amsterdam's New looks (`keep_homes`): its homes and old town houses keep the brick and plaster guess_tags gave them
// and gain a roof material; every other building takes its kind's look.
#pragma once

#include "load/Scene.hpp"
#include "load/Tradition.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

namespace atlas::load {

/// Where generic.py's `landuse_at` looks: regions.py's land cover (`S['land']`, by box and ring), or amsterdam.py's
/// areas tagged with a land use (the living map's looks and kit).
enum class LandUseFrom : std::uint8_t { land, areas };

/// How the looks are applied: generic.py's `region` (the region's name; `ams` for the living map), the region's
/// tradition (none: the generic looks alone), where the land use comes from, and Amsterdam's `keep_homes`.
struct Looks {
    std::string_view region;
    std::shared_ptr<const Tradition> tradition;
    LandUseFrom landuse = LandUseFrom::land;
    bool keep_homes = false;
};

/// generic.py's `colour_hex(v)`: `#rrggbb` or `#rgb` as upper-case `#RRGGBB`, or the colour a CSS name means.
[[nodiscard]] std::optional<std::string> colour_hex(std::optional<std::string_view> v);

/// generic.py's `paint(v, k)`: a colour word as house paint, its CSS colour `k` of the way toward a warm white; a hex
/// value, white and black exact.
[[nodiscard]] std::optional<std::string> paint(std::optional<std::string_view> v, double k = 0.16);

/// generic.py's `jitter(hexcol, seed, k)`: the colour a little lighter or darker, from random.Random(seed).
[[nodiscard]] std::string jitter(std::string_view hexcol, std::int64_t seed, double k = 0.05);

/// generic.py's `neighbour_levels(S, r)`: a building with neither height nor levels mapped takes the median levels of
/// the mapped buildings within `r` metres, where there are three or more (guessed, `lv_why`). How many did.
std::size_t neighbour_levels(Scene& s, double r = 80.0);

/// generic.py's `party_walls(S, tol)`: each building that is not hidden gets the edges lying along another's (`party`).
void party_walls(Scene& s, double tol = 0.8);

/// `GN.apply(S, region, landuse_at, keep_homes)`, with S['tradition'] as `looks.tradition` (which the scene keeps).
void apply_looks(Scene& s, const Looks& looks);

} // namespace atlas::load
