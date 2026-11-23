// Fuzzes the buildings' cleaning (load/CleanAbi.h, the Rust library behind load/Clean.hpp) with arbitrary footprints,
// packed close together so that they overlap, touch, share walls and cross themselves: their rings, courtyards, heights
// and parts from the input's bytes. Whatever it is given, the cleaning must end, and what it gives back must hold: a
// state for each footprint, a ring of three points or more where it gives one, each point within the box of what it
// was given (a crossing lands on the grid point nearest to it, inside both edges' boxes), no two of a ring's edges
// crossing (its seams run along nothing), and a base no lower than the one given. And its counts must hold: where
// nothing was left unsettled, no pair still overlaps. Seeds: fuzz/corpus/fuzz_clean.
//
// A footprint of a kind of 0xF8 and up is a large one made from a few bytes (large below), so that an input of a few
// dozen bytes meets each of the cleaning's bounds: an arrangement's MAX_SEGMENTS (rust/clean/src/arrange.rs), the
// work's bound WORK (clean.rs) and the seams' BUDGET (bridge.rs). Past a bound the cleaning puts what it was doing
// back as mapped, within a bounded memory: libFuzzer runs this target with -malloc_limit_mb=256 (LLVMFuzzerInitialize
// below), where the largest allocation the cleaning needs for 24 footprints of 65,535 points is some 32 MB.
#include "base/assert.hpp"
#include "load/CleanAbi.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace {

/// The input's bytes, read in order; zeros past their end.
class Bytes {
public:
    explicit Bytes(std::span<const std::uint8_t> b) noexcept : b_(b) {}
    std::uint8_t u8() noexcept { return at_ < b_.size() ? b_[at_++] : 0; }
    std::uint32_t u16() noexcept {
        const auto lo = u8();
        const auto hi = u8();
        return static_cast<std::uint32_t>(lo) | (static_cast<std::uint32_t>(hi) << 8U);
    }
    std::int32_t i16() noexcept { return static_cast<std::int16_t>(static_cast<std::uint16_t>(u16())); }
    [[nodiscard]] bool done() const noexcept { return at_ >= b_.size(); }

private:
    std::span<const std::uint8_t> b_;
    std::size_t at_ = 0;
};

[[nodiscard]] std::int64_t cross(atlas_clean_point o, atlas_clean_point a, atlas_clean_point b) noexcept {
    return (std::int64_t{a.lon - o.lon} * (b.lat - o.lat)) - (std::int64_t{a.lat - o.lat} * (b.lon - o.lon));
}

[[nodiscard]] bool crosses(atlas_clean_point a, atlas_clean_point b, atlas_clean_point c,
                           atlas_clean_point d) noexcept {
    const auto sign = [](std::int64_t v) { return (v > 0) - (v < 0); };
    return sign(cross(c, d, a)) * sign(cross(c, d, b)) < 0 && sign(cross(a, b, c)) * sign(cross(a, b, d)) < 0;
}

/// A window's points, rings and the box round what was given (grid units about the Westerkerk).
struct Given {
    static constexpr std::int32_t lat0 = 523746000;
    static constexpr std::int32_t lon0 = 48840000;
    std::vector<atlas_clean_point> points;
    std::vector<std::uint32_t> sizes;
    std::int32_t s = lat0 + 40000;
    std::int32_t w = lon0 + 40000;
    std::int32_t n = lat0 - 40000;
    std::int32_t e = lon0 - 40000;

    /// A point x east and y north of the Westerkerk, in grid units.
    void at(std::int32_t x, std::int32_t y) {
        const atlas_clean_point p{.lat = lat0 + y, .lon = lon0 + x};
        s = std::min(s, p.lat);
        n = std::max(n, p.lat);
        w = std::min(w, p.lon);
        e = std::max(e, p.lon);
        points.push_back(p);
    }
    /// Ends a ring begun at point `first`.
    void ring(std::size_t first) { sizes.push_back(static_cast<std::uint32_t>(points.size() - first)); }
};

/// A large footprint of the given shape (a kind's two low bits), its rings from a few bytes; how many rings it has.
/// - 0 and 1, a comb: a ring of 2 × teeth + 2 points (up to 4,095 teeth), its teeth across longitude (0) or latitude
///   (1), each as long as the comb is wide and 40 units more. Two combs of each kind whose corners lie 20 units apart
///   (`x y` = 0 -20 and -20 0) cross tooth by tooth: of 1,499 teeth, two rings of 3,000 edges and 9 million crossings.
/// - 2, a tangle: a ring of up to 65,535 points whose first and third edges cross, counterclockwise, in a strip of
///   its own by its place in the window. Its test for crossing itself and its arrangement cost 1.5 times the square
///   of its points and end at once (the crossing found first, the arrangement past MAX_SEGMENTS), so eleven of them
///   spend WORK in a moment.
/// - 3, a footprint whose courtyard sees none of the up to 5,989 points of its outer ring nearest it: a second
///   courtyard, a wall, stands between them. Joining it tries them all first, each at the cost of every edge:
///   past some 4,090 of them (their count times itself and 11), past BUDGET.
std::size_t large(std::uint8_t shape, std::size_t place, Bytes& in, Given& g) {
    if (shape <= 1) {
        const auto teeth = static_cast<std::int32_t>(1 + (in.u16() % 4095U));
        const auto pitch = static_cast<std::int32_t>(1 + (in.u8() % 4U));
        const std::int32_t x0 = in.i16();
        const std::int32_t y0 = in.i16();
        const std::int32_t span = 2 * teeth * pitch;
        const std::int32_t length = span + 40;
        // in the comb's own frame its teeth stand along v; across latitude, u and v swap
        const auto at = [&](std::int32_t u, std::int32_t v) {
            if (shape == 0) {
                g.at(x0 + u, y0 + v);
            } else {
                g.at(x0 + v, y0 + u);
            }
        };
        const std::size_t first = g.points.size();
        at(0, 0);
        for (std::int32_t k = 0; k < teeth; ++k) {
            at(((2 * k) + 1) * pitch, length);
            at(((2 * k) + 2) * pitch, 0);
        }
        at(span, -1);
        g.ring(first);
        return 1;
    }
    if (shape == 2) {
        const std::uint32_t count = 8 + (in.u16() % 65528U);
        const std::int32_t x0 = -32000;
        const std::int32_t y0 = -32768 + (2730 * static_cast<std::int32_t>(place % 24));
        const std::size_t first = g.points.size();
        g.at(x0, y0);
        g.at(x0 + 10, y0 + 10);
        g.at(x0 + 10, y0);
        g.at(x0, y0 + 10);
        for (std::uint32_t k = 4; k < count - 2; ++k) {
            g.at(x0 + 8 + static_cast<std::int32_t>(k), y0 + 20 + (static_cast<std::int32_t>(k % 2) * 2000));
        }
        // back along a line over its teeth: counterclockwise, as the cleaning takes an outer ring, so that it is not
        // turned and its test for a crossing meets the first one at once
        g.at(x0 + 8 + static_cast<std::int32_t>(count - 3), y0 + 2100);
        g.at(x0 - 2, y0 + 2100);
        g.ring(first);
        return 1;
    }
    const auto dense = static_cast<std::int32_t>(in.u16() % 5990U);
    const auto pitch = static_cast<std::int32_t>(1 + (in.u8() % 4U));
    const std::int32_t x0 = in.i16();
    const std::int32_t y0 = in.i16();
    const std::int32_t away = (dense * pitch) + 2000;
    // the outer ring: a row of points along its south side east of the wall (a zigzag of a unit), its other corners
    // farther from the courtyard than any of them
    std::size_t first = g.points.size();
    g.at(x0 - away, y0);
    for (std::int32_t k = 1; k <= dense; ++k) g.at(x0 + (k * pitch), y0 + (k % 2));
    g.at(x0 + (dense * pitch) + 1, y0 + away);
    g.at(x0 - away, y0 + away);
    g.ring(first);
    // the wall, 150 units wide, from 2 units over the row to 1,000; west of it the courtyard, 150 units square, its
    // least point 160 units from the wall at 400: every line from there to the row passes the wall's west side at
    // 400 × x / (x + 160) units up for a point x east of it, so 2.4 or more, and crosses it. Each courtyard is past
    // the cleaning's least (0.5 m²), so it is joined and not filled.
    first = g.points.size();
    g.at(x0, y0 + 2);
    g.at(x0 + 150, y0 + 2);
    g.at(x0 + 150, y0 + 1000);
    g.at(x0, y0 + 1000);
    g.ring(first);
    first = g.points.size();
    g.at(x0 - 160, y0 + 400);
    g.at(x0 - 10, y0 + 400);
    g.at(x0 - 10, y0 + 550);
    g.at(x0 - 160, y0 + 550);
    g.ring(first);
    return 3;
}

} // namespace

/// libFuzzer's flags for this target, before those it was given (which win): an allocation past 256 MB is a failure.
/// The cleaning's largest is some 32 MB; one past it is an arrangement grown past its bound, which the bound is there
/// to stop.
// NOLINTNEXTLINE(readability-identifier-naming): libFuzzer's name for it
extern "C" int LLVMFuzzerInitialize(int* argc, char*** argv) {
    static std::vector<char*> args;
    static std::string limit = "-malloc_limit_mb=256";
    const std::span<char*> given(*argv, static_cast<std::size_t>(*argc));
    args.assign(given.begin(), given.end());
    args.insert(args.begin() + 1, limit.data());
    args.push_back(nullptr);
    *argc = static_cast<int>(args.size() - 1);
    *argv = args.data();
    return 0;
}

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    Bytes in(std::span(data, size));
    Given g;
    std::vector<atlas_clean_footprint> footprints;
    const std::size_t count = 1 + (in.u8() % 24U);
    for (std::size_t f = 0; f < count && !in.done(); ++f) {
        const std::uint8_t kind = in.u8();
        const auto first = static_cast<std::uint32_t>(g.sizes.size());
        std::size_t rings = 0;
        if (kind >= 0xF8U) {
            rings = large(static_cast<std::uint8_t>(kind & 3U), f, in, g);
        } else {
            // about the Westerkerk: grid units within ±32767 of it (some 360 m by 220 m)
            rings = 1 + (kind % 3U);
            for (std::size_t r = 0; r < rings; ++r) {
                const std::size_t begun = g.points.size();
                const std::size_t vertices = 3 + (in.u8() % 10U);
                for (std::size_t v = 0; v < vertices; ++v) {
                    const std::int32_t y = in.i16();
                    g.at(in.i16(), y);
                }
                g.ring(begun);
            }
        }
        const double min = in.u8() / 8.0;
        footprints.push_back({.first_ring = first,
                              .rings = static_cast<std::uint32_t>(rings),
                              .min = min,
                              .top = min + (in.u8() / 4.0),
                              .part = static_cast<std::uint8_t>((kind >> 2U) & 1U),
                              .pad = {}});
    }
    const atlas_clean_input input{.points = g.points.data(),
                                  .point_count = g.points.size(),
                                  .ring_sizes = g.sizes.data(),
                                  .ring_count = g.sizes.size(),
                                  .footprints = footprints.data(),
                                  .footprint_count = footprints.size(),
                                  .metres_per_lon = 0.00679,
                                  .metres_per_lat = 0.011054};
    atlas_clean_output out{};
    ATLAS_ASSERT(atlas_clean_run(&input, &out) == 0, "a whole input refused");
    const std::span<const std::uint8_t> state(out.state, footprints.size());
    const std::span<const double> mins(out.min, footprints.size());
    const std::span<const std::size_t> first(out.ring_first, footprints.size() + 1);
    const std::span<const atlas_clean_point> ring_points(out.points, first.back());
    // the overlaps it counts after its work are whole only where it left nothing unsettled (clean.rs's counts)
    if (out.counts.unsettled == 0) ATLAS_ASSERT(out.counts.overlapping_pairs == 0, "a pair left overlapping");
    std::vector<std::uint8_t> seams;
    for (std::size_t k = 0; k < footprints.size(); ++k) {
        ATLAS_ASSERT(mins[k] >= footprints[k].min, "a base lowered");
        if ((state[k] & ATLAS_CLEAN_RING) == 0) continue;
        const auto ring = ring_points.subspan(first[k], first[k + 1] - first[k]);
        ATLAS_ASSERT(ring.size() >= 3, "a ring of fewer than three points");
        for (const atlas_clean_point& p : ring) {
            ATLAS_ASSERT(g.s <= p.lat && p.lat <= g.n && g.w <= p.lon && p.lon <= g.e,
                         "a point outside what was given");
        }
        for (std::size_t i = 0; i < ring.size(); ++i) {
            for (std::size_t j = i + 2; j < ring.size(); ++j) {
                if (i == 0 && j + 1 == ring.size()) continue;
                ATLAS_ASSERT(!crosses(ring[i], ring[i + 1], ring[j], ring[(j + 1) % ring.size()]),
                             "a ring that crosses itself");
            }
        }
        std::vector<double> xz;
        for (const atlas_clean_point& p : ring) {
            xz.push_back(static_cast<double>(p.lon));
            xz.push_back(static_cast<double>(p.lat));
        }
        seams.resize(ring.size());
        (void)atlas_clean_seams(xz.data(), ring.size(), seams.data());
    }
    atlas_clean_free(&out);
    return 0;
}
