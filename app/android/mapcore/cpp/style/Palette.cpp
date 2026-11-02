#include "style/Palette.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <utility>

namespace atlas::style {

namespace {

struct Lab {
    double l = 0.0;
    double a = 0.0;
    double b = 0.0;
};

[[nodiscard]] double linear(std::uint32_t c) noexcept {
    const double v = static_cast<double>(c & 0xFFU) / 255.0;
    return v <= 0.04045 ? v / 12.92 : pymath::strict::pow((v + 0.055) / 1.055, 2.4);
}

[[nodiscard]] std::uint32_t encoded(double v) noexcept {
    const double c = v <= 0.0031308 ? 12.92 * v : (1.055 * pymath::strict::pow(v, 1.0 / 2.4)) - 0.055;
    return static_cast<std::uint32_t>(std::clamp(std::lround(c * 255.0), 0L, 255L));
}

// Björn Ottosson's Oklab, as mapview's Oklab
[[nodiscard]] Lab lab_of(std::uint32_t rgb) noexcept {
    const double r = linear(rgb >> 16U);
    const double g = linear(rgb >> 8U);
    const double b = linear(rgb);
    const double l = pymath::strict::cbrt((0.4122214708 * r) + (0.5363325363 * g) + (0.0514459929 * b));
    const double m = pymath::strict::cbrt((0.2119034982 * r) + (0.6806995451 * g) + (0.1073969566 * b));
    const double s = pymath::strict::cbrt((0.0883024619 * r) + (0.2817188376 * g) + (0.6299787005 * b));
    return {.l = (0.2104542553 * l) + (0.7936177850 * m) - (0.0040720468 * s),
            .a = (1.9779984951 * l) - (2.4285922050 * m) + (0.4505937099 * s),
            .b = (0.0259040371 * l) + (0.7827717662 * m) - (0.8086757660 * s)};
}

[[nodiscard]] std::uint32_t rgb_of(const Lab& c) noexcept {
    const auto cube = [](double x) { return x * x * x; };
    const double l = cube(c.l + (0.3963377774 * c.a) + (0.2158037573 * c.b));
    const double m = cube(c.l - (0.1055613458 * c.a) - (0.0638541728 * c.b));
    const double s = cube(c.l - (0.0894841775 * c.a) - (1.2914855480 * c.b));
    const std::uint32_t r = encoded((4.0767416621 * l) - (3.3077115913 * m) + (0.2309699292 * s));
    const std::uint32_t g = encoded((-1.2684380046 * l) + (2.6097574011 * m) - (0.3413193965 * s));
    const std::uint32_t b = encoded((-0.0041960863 * l) - (0.7034186147 * m) + (1.7076147010 * s));
    return (r << 16U) | (g << 8U) | b;
}

} // namespace

std::uint32_t palette_move(std::uint32_t c, std::uint32_t from, std::uint32_t to) noexcept {
    if (c == from) return to;
    const Lab v = lab_of(c);
    const Lab f = lab_of(from);
    const Lab t = lab_of(to);
    // the lightness: a darker tone the same share of the way to black, a lighter one of the way to white
    double l = t.l;
    if (v.l < f.l) l = t.l * (v.l / f.l);
    if (v.l > f.l && f.l < 1.0) l = 1.0 - ((1.0 - t.l) * ((1.0 - v.l) / (1.0 - f.l)));
    // the tints per unit of lightness: c's against from's, turned as from's turns into to's and lessened as to's is
    // greyer, never strengthened (so a grey's tones' small tints are not blown up; `grey` the tint under which a colour
    // has no hue to turn by)
    constexpr double grey = 0.02;
    const auto tint = [](const Lab& x) { return x.l > 1e-9 ? Lab{.l = 1.0, .a = x.a / x.l, .b = x.b / x.l} : Lab{}; };
    const Lab vt = tint(v);
    const Lab ft = tint(f);
    const Lab tt = tint(t);
    const double fm = pymath::hypot(ft.a, ft.b);
    const double tm = pymath::hypot(tt.a, tt.b);
    double re = 0.0;
    double im = 0.0;
    if (fm > 1e-9) {
        const double d = fm * std::max({fm, tm, grey});
        re = ((tt.a * ft.a) + (tt.b * ft.b)) / d;
        im = ((tt.b * ft.a) - (tt.a * ft.b)) / d;
    }
    const double da = vt.a - ft.a;
    const double db = vt.b - ft.b;
    return rgb_of({.l = l, .a = (tt.a + (da * re) - (db * im)) * l, .b = (tt.b + (da * im) + (db * re)) * l});
}

std::optional<std::size_t> land_slot(std::string_view kind) noexcept {
    namespace slot = palette_slot;
    static constexpr std::array<std::pair<std::string_view, std::size_t>, 35> kinds{{
        {"residential", slot::paper},
        {"industrial", slot::paper},
        {"commercial", slot::paper},
        {"retail", slot::paper},
        {"construction", slot::paper},
        {"railway", slot::paper},
        {"military", slot::paper},
        {"brownfield", slot::paper},
        {"education", slot::paper},
        {"static_caravan", slot::paper},
        {"farmyard", slot::paper},
        {"port", slot::paper},
        {"farmland", slot::lawn},
        {"greenfield", slot::lawn},
        {"meadow", slot::lawn},
        {"grass", slot::lawn},
        {"grassland", slot::lawn},
        {"village_green", slot::lawn},
        {"recreation_ground", slot::lawn},
        {"park", slot::lawn},
        {"garden", slot::lawn},
        {"cemetery", slot::lawn},
        {"allotments", slot::lawn},
        {"orchard", slot::lawn},
        {"golf_course", slot::lawn},
        {"pitch", slot::lawn},
        {"flowerbed", slot::lawn},
        {"scrub", slot::woods},
        {"forest", slot::woods},
        {"wood", slot::woods},
        {"wetland", slot::woods},
        {"heath", slot::woods},
        {"pedestrian", slot::squares},
        {"parking", slot::parking},
        {"apron", slot::parking},
    }};
    if (kind == "playground" || kind == "track") return slot::playground;
    const auto* it = std::ranges::find(kinds, kind, &std::pair<std::string_view, std::size_t>::first);
    return it != kinds.end() ? std::optional(it->second) : std::nullopt;
}

std::optional<std::size_t> area_slot(std::string_view kind) noexcept {
    namespace slot = palette_slot;
    if (kind == "park" || kind == "garden" || kind == "grass") return slot::lawn;
    if (kind == "playground") return slot::playground;
    if (kind == "memorial") return slot::memorial;
    if (kind == "parking") return slot::parking;
    if (kind == "bridge") return slot::bridge_deck;
    return slot::squares; // a pedestrian area, a square and every other kind (area_other)
}

std::optional<std::size_t> surface_slot(std::string_view surface) noexcept {
    namespace slot = palette_slot;
    if (surface == "asphalt") return slot::asphalt;
    if (surface == "paving_stones") return slot::paving_stones;
    if (surface == "sett" || surface == "cobblestone") return slot::setts;
    if (surface == "concrete") return slot::concrete_paving;
    if (surface == "bricks") return slot::brick_paving;
    if (surface == "wood") return slot::boardwalk;
    return std::nullopt;
}

std::uint32_t paint(const PaletteBlock* palette, std::size_t code, std::uint32_t own, std::uint32_t c) noexcept {
    if (palette == nullptr) return c;
    const std::optional<std::uint32_t> to = palette->colour(code);
    return to ? palette_move(c, own, *to) : c;
}

bool PaletteBlock::today() const noexcept {
    return flags == 0U && std::ranges::all_of(colours, [](std::uint32_t c) { return c == 0U; });
}

std::string_view name_of(PaletteError error) noexcept {
    switch (error) {
    case PaletteError::size: return "size";
    case PaletteError::version: return "version";
    case PaletteError::flags: return "flags";
    case PaletteError::reserved: return "reserved";
    case PaletteError::colour: return "colour";
    }
    return "?";
}

std::expected<PaletteBlock, PaletteError> read_palette_block(std::span<const std::byte> bytes) noexcept {
    if (bytes.size() != palette_block_size) return std::unexpected(PaletteError::size);
    PaletteBlock b{};
    std::memcpy(&b, bytes.data(), sizeof b);
    if (b.version != palette_block_version) return std::unexpected(PaletteError::version);
    if ((b.flags & ~palette_flag::known) != 0U) return std::unexpected(PaletteError::flags);
    if (b.reserved != 0U) return std::unexpected(PaletteError::reserved);
    if (!std::ranges::all_of(b.colours, [](std::uint32_t c) { return c == 0U || (c >> 24U) == 1U; })) {
        return std::unexpected(PaletteError::colour);
    }
    return b;
}

} // namespace atlas::style
