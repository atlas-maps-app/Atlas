// The relief's light on the CPU (fx/Relief.hpp): its style, the frame's light, a tile's bake.
#include "fx/Relief.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "gen/data/relief_style.hpp"
#include "tiles/Json.hpp"
#include "tiles/Values.hpp"

#include <algorithm>
#include <numbers>

namespace atlas::fx {

namespace {

namespace tl = atlas::tiles;

/// `value`'s number within `lo` to `hi`, or nothing.
[[nodiscard]] std::optional<double> ranged(const std::optional<tl::Value>& value, double lo, double hi) noexcept {
    if (!value) return std::nullopt;
    const std::optional<double> n = value->number();
    if (!n || !std::isfinite(*n) || *n < lo || *n > hi) return std::nullopt;
    return n;
}

/// `value`'s list of N numbers, each within `lo` to `hi`, or nothing.
template <std::size_t N>
[[nodiscard]] std::optional<std::array<double, N>> list(const std::optional<tl::Value>& value, double lo,
                                                        double hi) noexcept {
    if (!value || value->type() != tl::ValueType::list || value->size() != N) return std::nullopt;
    std::array<double, N> out{};
    for (std::size_t i = 0; i < N; ++i) {
        const std::optional<double> n = ranged(value->child(i), lo, hi);
        if (!n) return std::nullopt;
        out.at(i) = *n;
    }
    return out;
}

/// 0 up to `from`, 1 from `to` on, Hermite's curve between (1 from `from` on where they meet).
[[nodiscard]] double smooth(double from, double to, double x) noexcept {
    if (to <= from) return x < from ? 0.0 : 1.0;
    const double t = std::clamp((x - from) / (to - from), 0.0, 1.0);
    return t * t * (3.0 - (2.0 * t));
}

/// The horizon's samples a direction reads: each one's offset in rows and columns, and the reciprocal of its distance
/// in samples.
struct Step {
    std::int32_t dr = 0;
    std::int32_t dc = 0;
    std::ptrdiff_t offset = 0; ///< from a sample to this one in the raster's rows
    double inverse = 0;
};

} // namespace

std::optional<ReliefStyle> read_relief_style(std::span<const std::byte> jsonc) {
    const auto read = tl::read_json(jsonc, {.comments = true});
    if (!read || read->root().type() != tl::ValueType::map) return std::nullopt;
    const tl::Value root = read->root();
    ReliefStyle s;
    const auto number = [&root](std::string_view key, double lo, double hi, double& out) {
        const std::optional<double> n = ranged(root.find(key), lo, hi);
        if (n) out = *n;
        return n.has_value();
    };
    // a contrast under 1 keeps 1 − k·L_y above 0 whatever the sun
    if (!number("sun", 0.0, 0.95, s.sun) || !number("overcast", 0.0, 0.95, s.overcast) ||
        !number("night", 0.0, 0.95, s.night) || !number("shoulder", 0.5, 4.0, s.shoulder) ||
        !number("most", 0.5, 4.0, s.most) || !number("floor", 0.0, 1.0, s.floor) ||
        !number("tint_rate", 0.0, 100.0, s.tint_rate) || !number("fade", 0.0, 10.0, s.fade)) {
        return std::nullopt;
    }
    const auto sunrise = list<2>(root.find("sunrise"), -1.0, 1.0);
    const auto side = list<3>(root.find("sky_side"), -1.0e3, 1.0e3);
    const auto tint = list<3>(root.find("tint"), 0.0, 4.0);
    const auto far = list<2>(root.find("far"), 0.0, 1.0e9);
    const std::optional<tl::Value> high = root.find("high_sun");
    const std::optional<tl::Value> sky = root.find("sky_view");
    if (!sunrise || !side || !tint || !far || !high || !sky || (*side)[1] <= 0.0 || s.most < s.shoulder) {
        return std::nullopt;
    }
    const std::optional<double> share = ranged(high->find("share"), 0.0, 1.0);
    const std::optional<double> from = ranged(high->find("from"), -1.0, 1.0);
    const std::optional<double> to = ranged(high->find("to"), -1.0, 1.0);
    const std::optional<double> factor = ranged(sky->find("factor"), 0.0, 1.0);
    const std::optional<double> directions = ranged(sky->find("directions"), 1.0, relief_max_directions);
    const std::optional<double> reach = ranged(sky->find("reach"), 1.0, relief_max_reach);
    if (!share || !from || !to || !factor || !directions || !reach) return std::nullopt;
    s.sunrise = *sunrise;
    s.sky_side = *side;
    s.tint = *tint;
    s.far = *far;
    s.high_sun_share = *share;
    s.high_sun_from = *from;
    s.high_sun_to = *to;
    s.sky_view = *factor;
    s.directions = static_cast<std::uint32_t>(*directions);
    s.reach = static_cast<std::uint32_t>(*reach);
    return s;
}

const ReliefStyle& relief_style() noexcept {
    static const ReliefStyle style = [] {
        const std::optional<ReliefStyle> read = read_relief_style(std::as_bytes(std::span(data::relief_style)));
        if (!read) log::error("vk relief: relief.jsonc does not read: the relief is lit by the core's defaults");
        return read.value_or(ReliefStyle{});
    }();
    return style;
}

shaders::ReliefLight relief_light(const shaders::Theme& theme, const ReliefStyle& style, bool sky_view,
                                  double strength) noexcept {
    // the sky's side, a unit vector
    const double side_length =
        std::sqrt((style.sky_side[0] * style.sky_side[0]) + (style.sky_side[1] * style.sky_side[1]) +
                  (style.sky_side[2] * style.sky_side[2]));
    const std::array<double, 3> side{style.sky_side[0] / side_length, style.sky_side[1] / side_length,
                                     style.sky_side[2] / side_length};
    std::array<double, 3> sun{0.0, 1.0, 0.0};
    double k = 0.0;     // the directed light's contrast on open ground
    double share = 0.0; // the sky's share from its side
    if (shaders::theme_has(theme, shaders::THEME_NIGHT)) {
        // the renders' night: the light from straight above, slope alone
        k = style.night;
    } else {
        const shaders::double3 l = shaders::theme_sun_vector(theme);
        const double length = std::sqrt((l.x * l.x) + (l.y * l.y) + (l.z * l.z));
        const double rise = length > 0.0 ? l.y / length : 0.0;
        // how much of the day's sun there is: none under an overcast sky or under the horizon
        const double day =
            shaders::theme_has(theme, shaders::THEME_OVERCAST) ? 0.0 : smooth(style.sunrise[0], style.sunrise[1], rise);
        if (day > 0.0) {
            sun = {l.x / length, rise, l.z / length};
            k = style.sun * day;
        }
        const double high = style.high_sun_share * smooth(style.high_sun_from, style.high_sun_to, rise);
        share = ((1.0 - day) * style.overcast * side[1]) + (day * high);
    }
    // f = 1 + k·(n·L − L_y) on open ground for the sun's weight s = k / (1 − k·L_y)
    const double s = k / (1.0 - (k * sun[1]));
    shaders::ReliefLight out{};
    out.sun = {static_cast<float>(sun[0]), static_cast<float>(sun[1]), static_cast<float>(sun[2]),
               static_cast<float>(s)};
    out.sky = {static_cast<float>(side[0]), static_cast<float>(side[1]), static_cast<float>(side[2]),
               static_cast<float>(std::clamp(share, 0.0, 1.0))};
    out.shape = {static_cast<float>(1.0 / (1.0 + (s * sun[1]))), static_cast<float>(style.sky_view),
                 static_cast<float>(style.shoulder), static_cast<float>(style.most)};
    out.tint = {static_cast<float>(style.tint[0]), static_cast<float>(style.tint[1]), static_cast<float>(style.tint[2]),
                static_cast<float>(style.tint_rate)};
    out.range = {static_cast<float>(style.floor), static_cast<float>(1.0 / side[1]), sky_view ? 1.0F : 0.0F,
                 static_cast<float>(std::clamp(strength, 0.0, 1.0))};
    return out;
}

double relief_strength(const ReliefStyle& style, double distance) noexcept {
    return 1.0 - smooth(style.far[0], style.far[1], distance);
}

double relief_spacing(std::uint8_t z, std::uint32_t y, std::uint32_t side, std::int32_t r) noexcept {
    constexpr double equator = 2.0 * std::numbers::pi * 6'378'137.0;
    const double tiles = std::ldexp(1.0, z);
    const double span = static_cast<double>(side - 1U);
    const double fraction = (static_cast<double>(y) + (static_cast<double>(r) / span)) / tiles;
    const double latitude = pymath::atan(pymath::sinh(std::numbers::pi * (1.0 - (2.0 * fraction))));
    return (equator * pymath::cos(latitude)) / (tiles * span);
}

ReliefBake bake_relief(const tiles::Heights& heights, const ReliefStyle& style) {
    const auto side = static_cast<std::int32_t>(heights.side);
    const auto apron = static_cast<std::int32_t>(heights.apron);
    const auto width = static_cast<std::int32_t>(heights.width());
    ReliefBake out{.side = heights.side, .texels = {}};
    if (side < 2 || heights.samples.size() != static_cast<std::size_t>(width) * static_cast<std::size_t>(width)) {
        return out;
    }
    out.texels.resize(static_cast<std::size_t>(side) * static_cast<std::size_t>(side));
    // each direction's samples: the lattice's nearest to its ray at 1 to `reach` samples
    const std::uint32_t directions = std::clamp(style.directions, 1U, relief_max_directions);
    const std::uint32_t reach = std::clamp(style.reach, 1U, relief_max_reach);
    std::vector<Step> steps;
    steps.reserve(std::size_t{directions} * reach);
    for (std::uint32_t d = 0; d < directions; ++d) {
        const double a = (2.0 * std::numbers::pi * d) / directions;
        for (std::uint32_t t = 1; t <= reach; ++t) {
            const auto dc = static_cast<std::int32_t>(std::lround(t * pymath::cos(a)));
            const auto dr = static_cast<std::int32_t>(std::lround(t * pymath::sin(a)));
            steps.push_back({.dr = dr,
                             .dc = dc,
                             .offset = (static_cast<std::ptrdiff_t>(dr) * width) + dc,
                             .inverse = 1.0 / std::sqrt(static_cast<double>((dr * dr) + (dc * dc)))});
        }
    }
    const std::int32_t* samples = heights.samples.data();
    // the sample at row r, column c of the tile, each held within the raster (an apron shorter than the reach)
    const auto at = [samples, apron, width](std::int32_t r, std::int32_t c) noexcept {
        const std::int32_t row = std::clamp(r + apron, 0, width - 1);
        const std::int32_t column = std::clamp(c + apron, 0, width - 1);
        return samples[(static_cast<std::size_t>(row) * static_cast<std::size_t>(width)) +
                       static_cast<std::size_t>(column)];
    };
    const auto far = static_cast<std::int32_t>(reach);
    for (std::int32_t r = 0; r < side; ++r) {
        // metres a step of height, over metres a sample of ground: a difference of steps times this is a slope
        const double rise = heights.step / relief_spacing(heights.tile.z, heights.tile.y, heights.side, r);
        for (std::int32_t c = 0; c < side; ++c) {
            const std::int32_t here = at(r, c);
            // the neighbours a sample has (the raster's edge where there is no apron: the difference one-sided)
            const std::int32_t west = std::max(c - 1, -apron);
            const std::int32_t east = std::min(c + 1, side - 1 + apron);
            const std::int32_t north = std::max(r - 1, -apron);
            const std::int32_t south = std::min(r + 1, side - 1 + apron);
            const double gx = (static_cast<double>(at(r, east) - at(r, west)) * rise) / std::max(1, east - west);
            const double gz = (static_cast<double>(at(south, c) - at(north, c)) * rise) / std::max(1, south - north);
            const double inverse = 1.0 / std::sqrt((gx * gx) + 1.0 + (gz * gz));
            // where every direction's samples lie in the raster (an apron as long as the reach: every texel), they are
            // read by their offsets
            const std::int32_t row = r + apron;
            const std::int32_t column = c + apron;
            const bool within = row >= far && row + far < width && column >= far && column + far < width;
            const std::int32_t* centre =
                samples + (static_cast<std::ptrdiff_t>(row) * width) + static_cast<std::ptrdiff_t>(column);
            double sines = 0.0;
            for (std::uint32_t d = 0; d < directions; ++d) {
                double horizon = 0.0; // the highest slope to a sample of the direction, the horizontal at least
                const Step* first = &steps[std::size_t{d} * reach];
                for (std::uint32_t t = 0; t < reach; ++t) {
                    const Step& s = first[t];
                    const std::int32_t there = within ? centre[s.offset] : at(r + s.dr, c + s.dc);
                    const double slope = static_cast<double>(there - here) * s.inverse;
                    horizon = std::max(horizon, slope);
                }
                const double m = horizon * rise;
                sines += m / std::sqrt(1.0 + (m * m));
            }
            out.texels[(static_cast<std::size_t>(r) * static_cast<std::size_t>(side)) + static_cast<std::size_t>(c)] =
                relief_texel(-gx * inverse, -gz * inverse, 1.0 - (sines / directions));
        }
    }
    return out;
}

} // namespace atlas::fx
