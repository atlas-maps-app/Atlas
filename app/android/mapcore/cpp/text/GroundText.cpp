#include "text/GroundText.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <bit>
#include <charconv>
#include <cstdint>
#include <format>
#include <iterator>
#include <limits>
#include <vector>

namespace atlas::text {

namespace {

#include "text/Digits.inc"

[[nodiscard]] std::string_view anchor_name(Anchor anchor) noexcept {
    switch (anchor) {
    case Anchor::start: return "start";
    case Anchor::middle: return "middle";
    case Anchor::end: return "end";
    }
    return "middle";
}

[[nodiscard]] World plus(const World& a, const World& b) noexcept { return {a.x + b.x, a.y + b.y, a.z + b.z}; }

/// Python's str.strip() for the white space a ref holds (ASCII's and the Unicode spaces up to U+3000).
[[nodiscard]] std::string_view strip(std::string_view s) noexcept {
    auto space = [](std::string_view rest) -> std::size_t {
        // the length of the white space starting `rest`, 0 for none
        if (rest.empty()) return 0;
        const auto c = static_cast<unsigned char>(rest.front());
        if (c == ' ' || (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x1F)) return 1;
        if (rest.starts_with("\xC2\x85") || rest.starts_with("\xC2\xA0")) return 2;
        for (const std::string_view w :
             {"\xE1\x9A\x80", "\xE2\x80\xA8", "\xE2\x80\xA9", "\xE2\x80\xAF", "\xE2\x81\x9F", "\xE3\x80\x80"}) {
            if (rest.starts_with(w)) return 3;
        }
        if (rest.size() >= 3 && rest.starts_with("\xE2\x80") && static_cast<unsigned char>(rest[2]) >= 0x80 &&
            static_cast<unsigned char>(rest[2]) <= 0x8A) {
            return 3; // U+2000 to U+200A
        }
        return 0;
    };
    while (const std::size_t n = space(s)) s.remove_prefix(n);
    // From the end: try each width the white space can have.
    for (bool cut = true; cut;) {
        cut = false;
        for (std::size_t n = 1; n <= 3 && n <= s.size(); ++n) {
            if (space(s.substr(s.size() - n)) == n) {
                s.remove_suffix(n);
                cut = true;
                break;
            }
        }
    }
    return s;
}

/// The port's leadingDigits (Character.isDigit on each UTF-16 unit): the decimal digits of the Basic Multilingual Plane
/// that start `s`, as ASCII digits; empty when it starts with none.
[[nodiscard]] std::string leading_digits(std::string_view s) {
    std::string out;
    for (const char32_t c : code_points(s)) {
        if (c >= 0x10000) break;
        const auto* const after = std::ranges::upper_bound(digit_zeros, c);
        if (after == digit_zeros.begin()) break;
        const char32_t value = c - *std::prev(after);
        if (value >= 10) break;
        out += static_cast<char>('0' + value);
    }
    return out;
}

/// The port's number of a designator's ASCII digits: BigInteger's toDouble, the integer correctly rounded (ties to
/// even), infinite past the doubles. (No std::from_chars: Apple's libc++ has it only from macOS 26.)
[[nodiscard]] double number_of(std::string_view digits) {
    const std::size_t first = digits.find_first_not_of('0');
    if (first == std::string_view::npos) return 0.0;
    digits.remove_prefix(first);
    if (digits.size() <= 19) { // below 10^19 < 2^64: exact in 64 bits, then rounded once
        std::uint64_t n = 0;
        for (const char c : digits) n = (n * 10) + static_cast<std::uint64_t>(c - '0');
        return static_cast<double>(n);
    }
    // Longer: the integer in 32-bit limbs, least significant first, then its top 64 bits rounded to 53.
    std::vector<std::uint32_t> limbs{0};
    for (const char c : digits) {
        auto carry = static_cast<std::uint64_t>(c - '0');
        for (std::uint32_t& limb : limbs) {
            const std::uint64_t v = (std::uint64_t{limb} * 10) + carry;
            limb = static_cast<std::uint32_t>(v);
            carry = v >> 32U;
        }
        if (carry != 0) limbs.push_back(static_cast<std::uint32_t>(carry));
    }
    const std::size_t bits = (32 * (limbs.size() - 1)) + static_cast<std::size_t>(std::bit_width(limbs.back()));
    if (bits > 1024) return std::numeric_limits<double>::infinity();
    auto bit = [&](std::size_t i) { return ((limbs[i / 32] >> (i % 32)) & 1U) != 0; };
    std::uint64_t top = 0;
    for (std::size_t i = 0; i < 64; ++i) top = (top << 1U) | (bit(bits - 1 - i) ? 1U : 0U);
    bool sticky = false;
    for (std::size_t i = 0; i + 64 < bits && !sticky; ++i) sticky = bit(i);
    std::uint64_t mantissa = top >> 11U;
    const std::uint64_t rest = top & 0x7FFU;
    if (rest > 0x400U || (rest == 0x400U && (sticky || (mantissa & 1U) != 0))) ++mantissa; // 2^53 stays exact
    return pymath::ldexp(static_cast<double>(mantissa), static_cast<int>(bits) - 53);
}

constexpr std::array<double, 23> powers_of_ten = [] {
    std::array<double, 23> p{};
    p[0] = 1.0;
    for (std::size_t i = 1; i < p.size(); ++i) p[i] = p[i - 1] * 10;
    return p;
}();

/// SvgNumbers' nextDouble (the app's SVG parser) of a printed decimal, as the oracle reads the port's print back: the
/// first 18 significant digits as an integer (the integer part's further digits as powers of ten, the fraction's
/// dropped), scaled by a power of ten in one multiplication or division (Math.pow's beyond 10^22; StrictMath's here),
/// the sign applied last.
[[nodiscard]] double svg_number(std::string_view s) noexcept {
    std::size_t i = 0;
    bool negative = false;
    if (i < s.size() && (s[i] == '-' || s[i] == '+')) {
        negative = s[i] == '-';
        ++i;
    }
    std::int64_t mantissa = 0;
    int digits = 0;
    int scale = 0;
    auto digit = [&](char c, bool fraction) {
        if (digits < 18) {
            mantissa = (mantissa * 10) + (c - '0');
            if (mantissa != 0) ++digits;
            if (fraction) --scale;
        } else if (!fraction) {
            ++scale;
        }
    };
    for (; i < s.size() && s[i] >= '0' && s[i] <= '9'; ++i) digit(s[i], false);
    if (i < s.size() && s[i] == '.') {
        for (++i; i < s.size() && s[i] >= '0' && s[i] <= '9'; ++i) digit(s[i], true);
    }
    double v = 0.0;
    if (mantissa != 0) {
        const auto m = static_cast<double>(mantissa);
        if (scale == 0) {
            v = m;
        } else if (scale >= 1 && scale <= 22) {
            v = m * powers_of_ten[static_cast<std::size_t>(scale)];
        } else if (scale >= -22 && scale <= -1) {
            v = m / powers_of_ten[static_cast<std::size_t>(-scale)];
        } else {
            v = m * pymath::pow(10.0, scale);
        }
    }
    return negative ? -v : v;
}

} // namespace

std::string printed(double value, int digits) { return std::format("{:.{}f}", value, digits); }

float printed_float(double value, int digits) noexcept {
    std::array<char, 400> buffer{}; // room for every double's fixed print at the renders' 1 to 3 places
    const auto [end, error] =
        std::to_chars(buffer.data(), buffer.data() + buffer.size(), value, std::chars_format::fixed, digits);
    if (error != std::errc{}) return static_cast<float>(value);
    // SvgNumbers' next(): the printed decimal read as a double, then rounded to a float
    return static_cast<float>(svg_number(std::string_view(buffer.data(), end)));
}

std::string TextRun::svg() const {
    return std::format("<text transform=\"matrix({} {} {} {} {} {})\" font-family=\"{}\" font-size=\"{}\" "
                       "font-weight=\"{}\" letter-spacing=\"{}\" text-anchor=\"{}\" fill=\"#{:06X}\" opacity=\"{}\">{}"
                       "</text>",
                       printed(matrix[0], 3), printed(matrix[1], 3), printed(matrix[2], 3), printed(matrix[3], 3),
                       printed(matrix[4], 1), printed(matrix[5], 1), family, printed(size, 2), weight,
                       printed(spacing, 2), anchor_name(anchor), fill, printed(opacity, 2), text);
}

std::array<float, 6> TextRun::snapped_matrix() const noexcept {
    return {printed_float(matrix[0], 3), printed_float(matrix[1], 3), printed_float(matrix[2], 3),
            printed_float(matrix[3], 3), printed_float(matrix[4], 1), printed_float(matrix[5], 1)};
}

float TextRun::printed_size() const noexcept { return printed_float(size, 2); }
float TextRun::printed_spacing() const noexcept { return printed_float(spacing, 2); }
float TextRun::printed_opacity() const noexcept { return printed_float(opacity, 2); }

std::optional<ShapeStyle> TextRun::shape_style(Rounding rounding) const noexcept {
    const auto font = resolve(family, weight, false);
    if (!font) return std::nullopt;
    return ShapeStyle{.font = *font,
                      .size = printed_size(),
                      .letter_spacing = printed_spacing(),
                      .anchor = anchor,
                      .rounding = rounding};
}

TextRun TextRun::turned() const {
    TextRun t = *this;
    for (std::size_t i = 0; i < 4; ++i) t.matrix[i] = -matrix[i];
    return t;
}

std::optional<TextRun> ground_text(const view::Camera& camera, std::string_view text, const World& p0, const World& u,
                                   const World& v, double size, std::uint32_t fill, double opacity, int weight,
                                   double spacing, Anchor anchor) {
    const auto s0 = camera.proj(p0);
    const auto sx = camera.proj(plus(p0, u));
    const auto sy = camera.proj(plus(p0, v));
    if (!s0 || !sx || !sy) return std::nullopt;
    const double a = sx->x - s0->x;
    const double b = sx->y - s0->y;
    const double c = sy->x - s0->x;
    const double d = sy->y - s0->y;
    if (pymath::fabs((a * d) - (b * c)) < 0.02) return std::nullopt;
    return TextRun{.text = std::string(text),
                   .matrix = {a, b, c, d, s0->x, s0->y},
                   .family = geist_family,
                   .size = size,
                   .weight = weight,
                   .spacing = spacing,
                   .anchor = anchor,
                   .fill = fill,
                   .opacity = opacity};
}

std::vector<TextRun> runway_designators(const view::Camera& camera, const Runway& runway, std::uint32_t mark) {
    std::vector<TextRun> out;
    for (int end = 0; end < 2; ++end) {
        if (auto run = runway_designator(camera, runway, mark, end)) out.push_back(std::move(*run));
    }
    return out;
}

std::optional<TextRun> runway_designator(const view::Camera& camera, const Runway& runway, std::uint32_t mark,
                                         int end) {
    const auto& pts = runway.points;
    if (pts.size() < 2) return std::nullopt;
    // names = [s.strip() for s in ref.split('/')] if '/' in ref else [None, None]
    std::vector<std::string_view> names;
    const std::string_view ref = runway.ref;
    if (ref.find('/') != std::string_view::npos) {
        std::size_t start = 0;
        while (true) {
            const std::size_t slash = ref.find('/', start);
            names.push_back(
                strip(ref.substr(start, slash == std::string_view::npos ? std::string_view::npos : slash - start)));
            if (slash == std::string_view::npos) break;
            start = slash + 1;
        }
    }
    const auto& p0 = end == 0 ? pts[0] : pts[pts.size() - 1];
    const auto& p1 = end == 0 ? pts[1] : pts[pts.size() - 2];
    const double dx = p1[0] - p0[0];
    const double dz = p1[1] - p0[1];
    double length = pymath::hypot(dx, dz);
    if (length == 0.0) length = 1.0;
    const double ux = dx / length;
    const double uz = dz / length;
    const double nx = -uz;
    const double nz = ux;
    // which name belongs to this end: the one whose heading matches the direction of travel from it
    const double heading = pymath::mod(pymath::degrees(pymath::atan2(ux, -uz)) + 360, 360.0);
    std::string_view name;
    for (const std::string_view s : names) {
        const std::string digits = leading_digits(s);
        if (digits.empty()) continue;
        const double number = number_of(digits);
        if (pymath::fabs(pymath::mod(((number * 10) - heading) + 180, 360.0) - 180) < 30) name = s;
    }
    if (name.empty()) return std::nullopt;
    return ground_text(camera, name, {p0[0] + (ux * 60), 0.09, p0[1] + (uz * 60)}, {nx, 0.0, nz}, {ux, 0.0, uz}, 18,
                       mark, 1.0, 800, 0.05);
}

void lay_out(const TextRun& run, const std::array<double, 6>& matrix, const ShapedRun& shaped,
             std::span<const slug::Glyph* const> places, double density, std::vector<GlyphInstance>& out) {
    const auto size = static_cast<double>(run.printed_size());
    const auto opacity = static_cast<float>(run.printed_opacity());
    const std::array<float, 4> colour{static_cast<float>((run.fill >> 16U) & 0xFFU) / 255.0F * opacity,
                                      static_cast<float>((run.fill >> 8U) & 0xFFU) / 255.0F * opacity,
                                      static_cast<float>(run.fill & 0xFFU) / 255.0F * opacity, opacity};
    const double a = matrix[0];
    const double b = matrix[1];
    const double c = matrix[2];
    const double d = matrix[3];
    const double e = matrix[4];
    const double f = matrix[5];
    for (std::size_t i = 0; i < shaped.glyphs.size() && i < places.size(); ++i) {
        const slug::Glyph* g = places[i];
        if (g == nullptr || !g->drawn()) continue;
        // em (y up) to text space: (x_i + size em.x, -size em.y); to CSS px by the matrix; to device px by density
        const auto x = static_cast<double>(shaped.anchored_x(i, run.anchor));
        out.push_back({.row0 = {static_cast<float>(density * a * size), static_cast<float>(-density * c * size),
                                static_cast<float>(density * ((a * x) + e))},
                       .row1 = {static_cast<float>(density * b * size), static_cast<float>(-density * d * size),
                                static_cast<float>(density * ((b * x) + f))},
                       .glyph = g,
                       .colour = colour});
    }
}

} // namespace atlas::text
