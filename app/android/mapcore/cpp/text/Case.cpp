#include "text/Case.hpp"

#include "text/Shaper.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <iterator>
#include <span>

namespace atlas::text {

namespace {

struct CaseRun {
    char32_t start;
    std::uint32_t count;
    std::uint32_t stride;
    std::int32_t delta;
};

struct CaseSpecial {
    char32_t from;
    std::array<char32_t, 3> to;
};

struct CasedRange {
    char32_t first;
    char32_t last;
};

#include "text/Case.inc"

/// What `runs` map `c` to: itself where no run holds it.
[[nodiscard]] char32_t mapped(std::span<const CaseRun> runs, char32_t c) noexcept {
    // The last run starting at or before c.
    const auto after = std::ranges::upper_bound(runs, c, {}, &CaseRun::start);
    if (after == runs.begin()) return c;
    const CaseRun& run = *std::prev(after);
    const char32_t offset = c - run.start;
    if (offset % run.stride != 0 || offset / run.stride >= run.count) return c;
    return static_cast<char32_t>(static_cast<std::int64_t>(c) + run.delta);
}

[[nodiscard]] bool cased(char32_t c) noexcept {
    const auto* const after = std::ranges::upper_bound(cased_ranges, c, {}, &CasedRange::first);
    return after != cased_ranges.begin() && c <= std::prev(after)->last;
}

void append_utf8(std::string& out, char32_t c) {
    if (c < 0x80) {
        out.push_back(static_cast<char>(c));
    } else if (c < 0x800) {
        out.push_back(static_cast<char>(0xC0U | (c >> 6U)));
        out.push_back(static_cast<char>(0x80U | (c & 0x3FU)));
    } else if (c < 0x10000) {
        out.push_back(static_cast<char>(0xE0U | (c >> 12U)));
        out.push_back(static_cast<char>(0x80U | ((c >> 6U) & 0x3FU)));
        out.push_back(static_cast<char>(0x80U | (c & 0x3FU)));
    } else {
        out.push_back(static_cast<char>(0xF0U | (c >> 18U)));
        out.push_back(static_cast<char>(0x80U | ((c >> 12U) & 0x3FU)));
        out.push_back(static_cast<char>(0x80U | ((c >> 6U) & 0x3FU)));
        out.push_back(static_cast<char>(0x80U | (c & 0x3FU)));
    }
}

constexpr char32_t capital_sigma = 0x03A3;
constexpr char32_t small_sigma = 0x03C3;
constexpr char32_t final_sigma = 0x03C2;

} // namespace

std::string capitalize(std::string_view utf8) {
    const std::u32string cps = code_points(utf8);
    std::string out;
    out.reserve(utf8.size() + 2);
    if (cps.empty()) return out;
    append_utf8(out, mapped(title_runs, cps[0]));
    // The rest is lowercased as a text of its own, which a sigma at its start cannot end.
    for (std::size_t i = 1; i < cps.size(); ++i) {
        const char32_t c = cps[i];
        if (c == capital_sigma) {
            const bool ends = i >= 2 && cased(cps[i - 1]) && (i + 1 == cps.size() || !cased(cps[i + 1]));
            append_utf8(out, ends ? final_sigma : small_sigma);
            continue;
        }
        const auto* const special = std::ranges::lower_bound(lower_special, c, {}, &CaseSpecial::from);
        if (special != lower_special.end() && special->from == c) {
            for (const char32_t u : special->to) {
                if (u != 0) append_utf8(out, u);
            }
            continue;
        }
        append_utf8(out, mapped(lower_runs, c));
    }
    return out;
}

} // namespace atlas::text
