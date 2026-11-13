// The relief's light on the CPU (ENGINE.md part 6, terrain, step 2): the twin of shaders/shared/relief.slang (the
// shader's own text, compiled after base/hlsl.hpp), the relief's style (app/android/mapcore/style/relief.jsonc,
// compiled into the core: no pack carries it, so the map's style version does not move with the look), the frame's
// light from the moment's theme and that style (relief_light), and a tile's bake (bake_relief): from its height raster
// (tiles/Heights.hpp), a texel a sample with the surface's normal and its sky-view.
//
// The bake. A raster of side n gives n × n texels, texel (r, c) its sample's. The normal is the surface's by central
// differences of its neighbours' heights (the apron's beyond the tile's edges, so a tile's edge texels are its
// neighbours' own: no seam), over the samples' spacing on the ground at the row's latitude (Web Mercator is conformal:
// one spacing both ways). The sky-view is Zakšek, Oštir and Kokalj's (2011) sky-view factor: over `directions` azimuths
// the highest horizon within `reach` samples (heights below the point count as the horizontal), V = 1 − the mean of the
// horizons' sines; 1 on a plain or a crest, lower in a hollow. A texel is A2B10G10R10: the normal's x (east) and z
// (south) as (n + 1)·511 in ten bits each (level ground a level of its own, 511, so it reads back as exactly 0 and
// flat ground keeps its colours to the bit), the sky-view in ten, the last two spare (step 3's water mask). Ten linear
// bits keep a gentle slope's light from banding (a step of the normal moves the light by 0.14 % at the dunes' contrast)
// and filter as they are, which a square-root code in eight would not.
#pragma once

#include "frame/FrameBlock.hpp"
#include "style/Light.hpp"
#include "tiles/Heights.hpp"

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace atlas::shaders {

/// The hyperbolic tangent the relief's shoulder takes (not exact code), by fdlibm's expm1 as the core's other
/// transcendentals are: (e^2x − 1) / (e^2x + 1), 1 from 20 on.
[[nodiscard]] inline float tanh(float x) noexcept {
    if (x >= 20.0F) return 1.0F;
    if (x <= -20.0F) return -1.0F;
    const double e = pymath::expm1(2.0 * static_cast<double>(x));
    return static_cast<float>(e / (e + 2.0));
}

#include "shaders/shared/relief.slang"

static_assert(sizeof(ReliefLight) == 80, "relief.slang's ReliefLight: five four-lane vectors");

} // namespace atlas::shaders

namespace atlas::fx {

/// The relief's style: relief.jsonc's values (the file says what each is).
struct ReliefStyle {
    double sun = 0.8;
    double overcast = 0.55;
    double night = 0.25;
    std::array<double, 2> sunrise{0.0, 0.12};
    std::array<double, 3> sky_side{-0.6, 0.9, -0.6};
    double high_sun_share = 0.3;
    double high_sun_from = 0.6;
    double high_sun_to = 0.95;
    double sky_view = 0.5;
    std::uint32_t directions = 16;
    std::uint32_t reach = 8;
    double shoulder = 1.1;
    double most = 1.16;
    double floor = 0.42;
    std::array<double, 3> tint{0.93, 0.97, 1.07};
    double tint_rate = 1.6;
    double fade = 0.18;
    std::array<double, 2> far{18'000.0, 24'000.0};

    friend bool operator==(const ReliefStyle&, const ReliefStyle&) = default;
};

/// The most directions and samples of reach a bake takes (a style asking for more is refused).
inline constexpr std::uint32_t relief_max_directions = 64;
inline constexpr std::uint32_t relief_max_reach = 32;

/// The style in `jsonc` (JSON with comments, relief.jsonc's keys, every one present and in its range), or nothing.
[[nodiscard]] std::optional<ReliefStyle> read_relief_style(std::span<const std::byte> jsonc);

/// The core's own style: relief.jsonc as compiled in, read once (the defaults above where it does not read, which
/// relief_test holds it does, to the same values).
[[nodiscard]] const ReliefStyle& relief_style() noexcept;

/// The frame's light: the moment's (`theme`: its sun, night and overcast) in `style`, with the sky-view or without
/// (the part's switch), at `strength` (0 to 1) over the frame.
[[nodiscard]] shaders::ReliefLight relief_light(const shaders::Theme& theme, const ReliefStyle& style, bool sky_view,
                                                double strength) noexcept;

/// How much of the relief a camera `distance` metres from its target shows: 1 up to the style's far[0], 0 from far[1].
[[nodiscard]] double relief_strength(const ReliefStyle& style, double distance) noexcept;

/// A texel of normal lanes `x` and `z` (each −1 to 1) and sky-view `v` (0 to 1): VK_FORMAT_A2B10G10R10_UNORM_PACK32.
[[nodiscard]] constexpr std::uint32_t relief_texel(double x, double z, double v) noexcept {
    const auto lane = [](double t, double levels) {
        const double clamped = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        return static_cast<std::uint32_t>((clamped * levels) + 0.5);
    };
    return lane((x * 0.5) + 0.5, 1022.0) | (lane((z * 0.5) + 0.5, 1022.0) << 10U) | (lane(v, 1023.0) << 20U) |
           (3U << 30U);
}

/// A texel's three lanes as a sampler gives them (each 0 to 1).
[[nodiscard]] inline shaders::float3 relief_lanes(std::uint32_t texel) noexcept {
    const shaders::float3 lanes = {static_cast<float>(texel & 1023U) / 1023.0F,
                                   static_cast<float>((texel >> 10U) & 1023U) / 1023.0F,
                                   static_cast<float>((texel >> 20U) & 1023U) / 1023.0F};
    return lanes;
}

/// A tile's relief, baked: `side` × `side` texels, rows from the north.
struct ReliefBake {
    std::uint16_t side = 0;
    std::vector<std::uint32_t> texels;
};

/// The bake of `heights` (the file's head), with `style`'s directions and reach.
[[nodiscard]] ReliefBake bake_relief(const tiles::Heights& heights, const ReliefStyle& style);

/// The samples' spacing on the ground at row `r` of a raster of `side` over tile (`z`, `y`): metres, Web Mercator's at
/// the row's latitude on the sphere of 6,378,137 m.
[[nodiscard]] double relief_spacing(std::uint8_t z, std::uint32_t y, std::uint32_t side, std::int32_t r) noexcept;

} // namespace atlas::fx
