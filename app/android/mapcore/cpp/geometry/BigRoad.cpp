// A big road's lanes and width as stage T draws them and stage P pads its box by (geometry/BigRoad.hpp), and the
// number a tag's value starts with that they are read by (real.py's num): in stage P's objects, which the packer's tool
// links alone, and so in the core's for stage T's ground.
#include "geometry/BigRoad.hpp"

#include "base/pymath/PyMath.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <optional>
#include <string>
#include <string_view>

namespace atlas::geometry {

namespace pm = atlas::pymath;

namespace {

/// A decimal `-?D+(.D+)?` as the double nearest it (Python's float()): Clinger's fast path where its digits fit 2^53
/// and its fraction 22 places (one rounding of exact operands), else the C library's strtod (correctly rounded on
/// Bionic, glibc and Apple's libc alike; the core's locale is C).
[[nodiscard]] double decimal(std::string_view text) noexcept {
    static constexpr std::array<double, 23> powers = {1e0,  1e1,  1e2,  1e3,  1e4,  1e5,  1e6,  1e7,
                                                      1e8,  1e9,  1e10, 1e11, 1e12, 1e13, 1e14, 1e15,
                                                      1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
    const bool negative = !text.empty() && text[0] == '-';
    std::uint64_t mantissa = 0;
    std::size_t digits = 0;
    std::size_t fraction = 0;
    bool point = false;
    for (std::size_t i = negative ? 1 : 0; i < text.size(); ++i) {
        if (text[i] == '.') {
            point = true;
            continue;
        }
        if (digits == 0 && text[i] == '0' && !point) continue; // leading zeros carry nothing
        ++digits;
        if (point) ++fraction;
        if (digits <= 19) mantissa = (mantissa * 10) + static_cast<std::uint64_t>(text[i] - '0');
    }
    if (digits <= 19 && mantissa <= (std::uint64_t{1} << 53U) && fraction < powers.size()) {
        const double v = static_cast<double>(mantissa) / powers[fraction];
        return negative ? -v : v;
    }
    const std::string copy(text);
    return std::strtod(copy.c_str(), nullptr);
}

} // namespace

double tag_number(std::optional<std::string_view> v, double fallback) noexcept {
    if (!v) return fallback;
    std::string_view s = *v;
    std::size_t i = 0;
    // Python's \s: the ASCII whitespace and the other Unicode spaces; OSM's tags have the ASCII ones
    while (i < s.size() &&
           (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r' || s[i] == '\f' || s[i] == '\v')) {
        ++i;
    }
    const std::size_t start = i;
    if (i < s.size() && s[i] == '-') ++i;
    const std::size_t digits = i;
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    if (i == digits) return fallback;
    if (i + 1 < s.size() && s[i] == '.' && s[i + 1] >= '0' && s[i + 1] <= '9') {
        ++i;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    }
    return decimal(s.substr(start, i - start));
}

BigRoadSize big_road_size(std::string_view hw, std::optional<std::string_view> lanes,
                          std::optional<std::string_view> width) noexcept {
    const std::int64_t unmapped = hw.ends_with("link") ? 1 : 2;
    const auto mapped = pm::checked::to_int(tag_number(lanes, static_cast<double>(unmapped)));
    const std::int64_t n = mapped && *mapped >= 0 && *mapped <= most_lanes ? *mapped : unmapped;
    const double w = tag_number(width, std::numeric_limits<double>::quiet_NaN());
    return {.lanes = n,
            .width =
                w >= 0.0 && w <= widest_road ? w : (static_cast<double>(n) * 3.5) + (hw == "motorway" ? 3.0 : 1.0)};
}

} // namespace atlas::geometry
