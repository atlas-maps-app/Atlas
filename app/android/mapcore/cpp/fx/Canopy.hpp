// The regions' canopy (ENGINE.md step 4, the effects; render/regions.py's draw_under: "forest and scrub seen from
// above: a canopy of crowns, generic, inside the mapped outline"): by day one path of the drawn forest, wood and scrub
// rings, black at .55, filtered by `canopy` (its region the path's box): feTurbulence fractalNoise .55, 2 octaves,
// seed 17 (the canopy's screen field, fx/Fields.hpp), feColorMatrix "0 0 0 0 .16  0 0 0 0 .24  0 0 0 0 .12  -3.4 0 0
// 0 2.1", then feComposite in the path, in linear RGB (the filters' default): .16 .24 .12 where the noise's red is low,
// nothing where it is high, so the woods' land shows through a mottled canopy of crowns.
//
// The matrix's colour rows are constants and its alpha row reads the noise's red alone (premultiplied, then
// unpremultiplied, as MatrixNode reads its input), so the field keeps the result's alpha (canopy_alpha of that red),
// and over a pixel the path covers the filter's result is one of 256 pixels by it (canopy_table): the matrix's colour
// at that alpha, premultiplied, `in` the path (which leaves a pixel it covers whole as it is), written back as
// premultiplied sRGB. The tests hold both to the port's own SvgFilterRaster (fields.inc, canopy.inc).
#pragma once

#include "fx/Noise.hpp"

#include <array>
#include <cstdint>

namespace atlas::fx {

/// The opacity regions.py draws the canopy's path at.
inline constexpr float canopy_opacity = 0.55F;

/// The canopy's colour matrix's alpha for each red of the noise as the matrix reads it: fma(2.1, 255, −3.4 · red), the
/// exact sum rounded once, then rint, within 0 to 255.
[[nodiscard]] const std::array<std::uint8_t, 256>& canopy_alpha() noexcept;

/// The filter's result over a pixel the path covers whole, for each alpha the matrix gives (premultiplied sRGB, a r g
/// b).
[[nodiscard]] const std::array<Argb, 256>& canopy_table() noexcept;

} // namespace atlas::fx
