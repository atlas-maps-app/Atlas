#include "geometry/Seams.hpp"

#include "load/CleanAbi.h"

#include <algorithm>
#include <cstddef>
#include <numeric>
#include <optional>
#include <type_traits>

namespace atlas::geometry {

static_assert(std::is_standard_layout_v<V2> && sizeof(V2) == 2 * sizeof(double), "V2 is x then z, two doubles");

std::vector<std::uint8_t> seams(std::span<const V2> ring) {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast): V2 is two doubles, x then z
    return seams_xz({reinterpret_cast<const double*>(ring.data()), 2 * ring.size()});
}

std::vector<std::uint8_t> seams_xz(std::span<const double> xz) {
    std::vector<std::uint8_t> out(xz.size() / 2);
    if (atlas_clean_seams(xz.data(), out.size(), out.data()) == 0) out.clear();
    return out;
}

std::vector<std::vector<std::uint32_t>> pieces_of(std::span<const V2> ring, std::span<const std::uint8_t> seam) {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast): V2 is two doubles, x then z
    return pieces_xz({reinterpret_cast<const double*>(ring.data()), 2 * ring.size()}, seam);
}

std::vector<std::vector<std::uint32_t>> pieces_xz(std::span<const double> xz, std::span<const std::uint8_t> seam) {
    const std::size_t n = xz.size() / 2;
    const auto whole = [n] {
        std::vector<std::vector<std::uint32_t>> one(1);
        one[0].resize(n);
        std::iota(one[0].begin(), one[0].end(), 0U);
        return one;
    };
    if (seam.size() != n || std::ranges::find(seam, std::uint8_t{1}) == seam.end()) return whole();
    const auto x = [&](std::size_t i) { return xz[2 * (i % n)]; };
    const auto z = [&](std::size_t i) { return xz[(2 * (i % n)) + 1]; };
    // The ring's walks between its seams: a seam opens a walk, its way back closes it, and the walk it left goes on
    // (the library joins each ring to the ones before it at one of their vertices: bridge.rs). Each walk is one of the
    // footprint's rings.
    constexpr std::uint32_t none = 0xFFFF'FFFFU;
    struct Walk {
        std::uint32_t parent = none; ///< the walk its seam left
        std::uint32_t seam = none;   ///< the seam that opened it
    };
    std::vector<Walk> walks(1);
    std::vector<std::uint32_t> walk_of(n, 0); // an edge's walk; a seam's: the walk it opens or closes
    std::vector<std::uint32_t> open;          // the seams not yet run back, the last opened last
    std::vector<std::uint32_t> at{0};         // the walks they opened, under them the ring's first
    for (std::uint32_t e = 0; e < n; ++e) {
        if (seam[e] == 0) {
            walk_of[e] = at.back();
            continue;
        }
        const bool back = !open.empty() && x(open.back()) == x(e + 1) && z(open.back()) == z(e + 1) &&
                          x(open.back() + 1) == x(e) && z(open.back() + 1) == z(e);
        if (back) {
            walk_of[e] = at.back();
            open.pop_back();
            at.pop_back();
        } else {
            walks.push_back({.parent = at.back(), .seam = e});
            open.push_back(e);
            at.push_back(static_cast<std::uint32_t>(walks.size() - 1));
            walk_of[e] = at.back();
        }
    }
    if (!open.empty()) return whole();
    // A seam joins a courtyard to its piece where its middle lies in the footprint, and two pieces where it lies
    // outside (bridge.rs's clear; it crosses no edge and passes no vertex, so its middle is well in or out): by the
    // ring's edges that are no seams, even-odd. A piece is the walks its courtyards' seams join.
    const auto in_footprint = [&](double px, double pz) {
        bool in = false;
        for (std::size_t i = 0; i < n; ++i) {
            if (seam[i] != 0) continue;
            const double ax = x(i);
            const double az = z(i);
            const double bx = x(i + 1);
            const double bz = z(i + 1);
            if ((az > pz) != (bz > pz) && px < ax + (((pz - az) * (bx - ax)) / (bz - az))) in = !in;
        }
        return in;
    };
    std::vector<std::uint32_t> piece(walks.size());
    std::iota(piece.begin(), piece.end(), 0U);
    const auto root = [&](std::uint32_t w) {
        while (piece[w] != w) w = piece[w] = piece[piece[w]];
        return w;
    };
    std::vector<std::uint8_t> kept(walks.size(), 0); // the seam that opened the walk stays, in its piece
    for (std::uint32_t w = 1; w < walks.size(); ++w) {
        const std::uint32_t e = walks[w].seam;
        if (!in_footprint((x(e) + x(e + 1)) / 2, (z(e) + z(e + 1)) / 2)) continue;
        kept[w] = 1;
        piece[root(w)] = root(walks[w].parent);
    }
    std::vector<std::vector<std::uint32_t>> out;
    std::vector<std::uint32_t> index(walks.size(), none);
    for (std::uint32_t e = 0; e < n; ++e) {
        const std::uint32_t w = walk_of[e];
        if (seam[e] != 0 && kept[w] == 0) continue;
        const std::uint32_t r = root(w);
        if (index[r] == none) {
            index[r] = static_cast<std::uint32_t>(out.size());
            out.emplace_back();
        }
        out[index[r]].push_back(e);
    }
    return out;
}

bool has_courtyard(std::span<const std::uint32_t> piece, std::span<const std::uint8_t> seam) noexcept {
    return !seam.empty() && std::ranges::any_of(piece, [&](std::uint32_t e) { return seam[e] != 0; });
}

std::optional<std::vector<RingPiece>> rings_of(const tiles::RingView& ring) {
    std::vector<atlas_clean_point> in(ring.size());
    for (std::size_t i = 0; i < ring.size(); ++i) in[i] = {.lat = ring.lat[i], .lon = ring.lon[i]};
    atlas_clean_rings_output out{};
    if (atlas_clean_rings(in.data(), in.size(), &out) != 0) return std::nullopt;
    std::vector<RingPiece> pieces;
    // NOLINTBEGIN(cppcoreguidelines-pro-bounds-pointer-arithmetic): the library's arrays, of the counts it gave
    for (std::size_t r = 0; r < out.ring_count; ++r) {
        std::vector<tiles::GridPoint> points;
        for (std::size_t v = out.ring_first[r]; v < out.ring_first[r + 1]; ++v) {
            points.emplace_back(out.points[v].lat, out.points[v].lon);
        }
        if (out.piece[r] >= pieces.size()) {
            pieces.push_back({.outer = std::move(points), .holes = {}});
        } else {
            pieces.back().holes.push_back(std::move(points));
        }
    }
    // NOLINTEND(cppcoreguidelines-pro-bounds-pointer-arithmetic)
    atlas_clean_rings_free(&out);
    return pieces;
}

} // namespace atlas::geometry
