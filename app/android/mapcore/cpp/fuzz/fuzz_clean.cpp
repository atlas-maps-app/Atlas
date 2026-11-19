// Fuzzes the buildings' cleaning (load/CleanAbi.h, the Rust library behind load/Clean.hpp) with arbitrary footprints,
// packed close together so that they overlap, touch, share walls and cross themselves: their rings, courtyards, heights
// and parts from the input's bytes. Whatever it is given, the cleaning must end, and what it gives back must hold: a
// state for each footprint, a ring of three points or more where it gives one, each point within the box of what it
// was given (a crossing lands on the grid point nearest to it, inside both edges' boxes), no two of a ring's edges
// crossing (its seams run along nothing), and a base no lower than the one given. Seeds: fuzz/corpus/fuzz_clean.
#include "base/assert.hpp"
#include "load/CleanAbi.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace {

/// The input's bytes, read in order; zeros past their end.
class Bytes {
public:
    explicit Bytes(std::span<const std::uint8_t> b) noexcept : b_(b) {}
    std::uint8_t u8() noexcept { return at_ < b_.size() ? b_[at_++] : 0; }
    std::int32_t i16() noexcept {
        const auto lo = u8();
        const auto hi = u8();
        return static_cast<std::int16_t>(static_cast<std::uint16_t>(lo | (hi << 8U)));
    }
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

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    Bytes in(std::span(data, size));
    // about the Westerkerk: grid units within ±32767 of it (some 360 m by 220 m)
    constexpr std::int32_t lat0 = 523746000;
    constexpr std::int32_t lon0 = 48840000;
    std::vector<atlas_clean_point> points;
    std::vector<std::uint32_t> sizes;
    std::vector<atlas_clean_footprint> footprints;
    std::int32_t s = lat0 + 40000;
    std::int32_t w = lon0 + 40000;
    std::int32_t n = lat0 - 40000;
    std::int32_t e = lon0 - 40000;
    const std::size_t count = 1 + (in.u8() % 24U);
    for (std::size_t f = 0; f < count && !in.done(); ++f) {
        const std::uint8_t kind = in.u8();
        const std::size_t rings = 1 + (kind % 3U);
        const auto first = static_cast<std::uint32_t>(sizes.size());
        for (std::size_t r = 0; r < rings; ++r) {
            const std::size_t vertices = 3 + (in.u8() % 10U);
            for (std::size_t v = 0; v < vertices; ++v) {
                const atlas_clean_point p{.lat = lat0 + in.i16(), .lon = lon0 + in.i16()};
                s = std::min(s, p.lat);
                n = std::max(n, p.lat);
                w = std::min(w, p.lon);
                e = std::max(e, p.lon);
                points.push_back(p);
            }
            sizes.push_back(static_cast<std::uint32_t>(vertices));
        }
        const double min = in.u8() / 8.0;
        footprints.push_back({.first_ring = first,
                              .rings = static_cast<std::uint32_t>(rings),
                              .min = min,
                              .top = min + (in.u8() / 4.0),
                              .part = static_cast<std::uint8_t>((kind >> 2U) & 1U),
                              .pad = {}});
    }
    const atlas_clean_input input{.points = points.data(),
                                  .point_count = points.size(),
                                  .ring_sizes = sizes.data(),
                                  .ring_count = sizes.size(),
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
    std::vector<std::uint8_t> seams;
    for (std::size_t k = 0; k < footprints.size(); ++k) {
        ATLAS_ASSERT(mins[k] >= footprints[k].min, "a base lowered");
        if ((state[k] & ATLAS_CLEAN_RING) == 0) continue;
        const auto ring = ring_points.subspan(first[k], first[k + 1] - first[k]);
        ATLAS_ASSERT(ring.size() >= 3, "a ring of fewer than three points");
        for (const atlas_clean_point& p : ring) {
            ATLAS_ASSERT(s <= p.lat && p.lat <= n && w <= p.lon && p.lon <= e, "a point outside what was given");
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
