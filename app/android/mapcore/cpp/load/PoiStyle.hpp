// How a place looks, from its tags (ENGINE.md step 3, stage P): render/poi_style.py's `style(t)` as the port computes
// it (R/PoiStyle.kt), its tables transcribed. A place's category and colour, its glyph, its kind label (a shop the
// tables do not name is labelled by its tag, `t['shop'].replace('_', ' ').capitalize()`), and for a brand its name and
// sign colours: the table's for a brand:wikidata it lists, else a neutral sign.
#pragma once

#include "load/Scene.hpp"

#include <optional>

namespace atlas::load {

/// `style(t)`: how the place with tags `t` looks, or nothing when it is not a place.
[[nodiscard]] std::optional<PlaceStyle> place_style(const Tags& t);

} // namespace atlas::load
