// The water's glints (ENGINE.md step 4, the effects; render/amsterdam.py's water_layer: "wind ripples: sky-coloured
// glints where the noise peaks, stretched along the water"): at a map pitch one rect over the frame, filtered by
// `glint0` (userSpaceOnUse): feTurbulence fractalNoise .02 × .3, 2 octaves, seed 5 (the glints' screen field,
// fx/Fields.hpp), then feColorMatrix "0 0 0 0 r  0 0 0 0 g  0 0 0 0 b  11 0 0 0 -6.9", the glint's colour (white by
// day, .55 .62 .8 at night) where the noise's red peaks, at the rect's opacity: min(.4, .08 + wind · .012) by day, .18
// at night. At eye level a rect a glint band, its noise's frequencies by its distance: still to come.
//
// The matrix reads the noise's red alone (as the field keeps it: premultiplied, then unpremultiplied, as MatrixNode
// reads its input), so the filter's result at a pixel is one of 256 pixels, the moment's table: the matrix applied as
// WebKit's float code does it (bytes::ColourMatrix), premultiplied, written back as premultiplied sRGB.
#pragma once

#include "fx/Noise.hpp"

#include <array>

namespace atlas::fx {

/// The glint's colour as water_layer prints it into the matrix ('%.2f'), read as floats: (1, 1, 1) by day, (.55, .62,
/// .8) at night.
[[nodiscard]] std::array<float, 3> glint_colour(bool night) noexcept;

/// The rect's opacity as water_layer prints it ('%.2f'): min(.4, .08 + wind · .012) by day, .18 at night.
[[nodiscard]] double glint_opacity(double wind, bool night) noexcept;

/// The filter's result for each of the 256 reds of the noise (premultiplied sRGB, a r g b), with glint colour `gl`.
[[nodiscard]] std::array<Argb, 256> glint_table(const std::array<float, 3>& gl) noexcept;

/// wfx's cloud shadows (render/amsterdam.py:943, `cloudsh`): feColorMatrix "0 0 0 0 .16  0 0 0 0 .19  0 0 0 0 .32  7 0
/// 0 0 cut" over the noise's red (the cloud shadows' screen field), made as glint_table makes the glints': the filter's
/// result for each of the 256 reds (premultiplied sRGB, a r g b), `cut` the matrix's last entry as the render prints
/// it ('%.2f' of -7 t0).
[[nodiscard]] std::array<Argb, 256> cloud_shadow_table(float cut) noexcept;

} // namespace atlas::fx
