#include "geometry/Seams.hpp"

#include "base/pymath/PyMath.hpp"
#include "tiles/Schema.hpp"

#include <algorithm>
#include <cstddef>
#include <numeric>
#include <type_traits>
#include <utility>

namespace atlas::geometry {

static_assert(std::is_standard_layout_v<V2> && sizeof(V2) == 2 * sizeof(double), "V2 is x then z, two doubles");

void seams_at(const tiles::RingView& ring, std::span<const std::uint32_t> kept, std::vector<std::uint8_t>& out) {
    out.clear();
    if (!ring.has_keep) return;
    const auto marked = [&](std::uint32_t v) { return ring.keep[v] > tiles::keep::all; };
    if (std::ranges::none_of(kept, marked)) return;
    out.reserve(kept.size());
    for (const std::uint32_t v : kept) {
        const std::uint8_t code = ring.keep[v];
        out.push_back(code == tiles::keep::seam_piece   ? seam_kind::piece
                      : code == tiles::keep::seam_court ? seam_kind::court
                                                        : seam_kind::none);
    }
}

void seams_at(const tiles::RingView& ring, std::vector<std::uint8_t>& out) {
    out.clear();
    if (!ring.has_keep) return;
    const auto marked = [](std::uint8_t code) { return code > tiles::keep::all; };
    if (std::ranges::none_of(ring.keep, marked)) return;
    out.reserve(ring.size());
    for (const std::uint8_t code : ring.keep) {
        out.push_back(code == tiles::keep::seam_piece   ? seam_kind::piece
                      : code == tiles::keep::seam_court ? seam_kind::court
                                                        : seam_kind::none);
    }
}

V2 corner_mean(std::span<const V2> ring, std::span<const std::uint8_t> seam) noexcept {
    if (seam.size() != ring.size() || std::ranges::none_of(seam, [](std::uint8_t k) { return k != 0; })) {
        return centroid(ring);
    }
    pymath::Summer sx;
    pymath::Summer sz;
    std::size_t n = 0;
    for (std::size_t i = 0; i < ring.size(); ++i) {
        if (seam[i] != 0) continue;
        sx.add(ring[i].x);
        sz.add(ring[i].z);
        ++n;
    }
    if (n == 0) return centroid(ring);
    return {sx.value() / static_cast<double>(n), sz.value() / static_cast<double>(n)};
}

Split split(std::span<const V2> ring, std::span<const std::uint8_t> seam, double least_face) {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast): V2 is two doubles, x then z
    return split_xz({reinterpret_cast<const double*>(ring.data()), 2 * ring.size()}, seam, least_face);
}

Split split_xz(std::span<const double> xz, std::span<const std::uint8_t> seam, double least_face) {
    const std::size_t n = xz.size() / 2;
    Split out;
    const bool seamed =
        seam.size() == n && std::ranges::any_of(seam, [](std::uint8_t k) { return k != seam_kind::none; });
    // the ring as one piece: flat where it has seams, which carry nothing
    const auto whole = [&](bool paired) {
        Piece p;
        p.edges.resize(n);
        std::iota(p.edges.begin(), p.edges.end(), 0U);
        p.courtyard = seamed;
        out.off.clear();
        if (seamed) {
            out.off.resize(n);
            for (std::size_t i = 0; i < n; ++i) out.off[i] = seam[i] != seam_kind::none ? 1 : 0;
        }
        out.pieces.clear();
        out.pieces.push_back(std::move(p));
        out.paired = paired;
    };
    if (!seamed) {
        whole(true);
        return out;
    }
    // metres from the ring's first corner, so that an area's sum loses nothing to the frame's distance
    const auto x = [&](std::size_t i) { return xz[2 * (i % n)] - xz[0]; };
    const auto z = [&](std::size_t i) { return xz[(2 * (i % n)) + 1] - xz[1]; };
    // two corners on one grid point, as the tile has them: the same doubles
    const auto same = [&](std::size_t i, std::size_t j) {
        return xz[2 * (i % n)] == xz[2 * (j % n)] && xz[(2 * (i % n)) + 1] == xz[(2 * (j % n)) + 1];
    };
    // The ring's walks between its seams: a seam opens a walk, its way back closes it, and the walk it left goes on
    // (the library joins each ring to the ones before it at one of their vertices: bridge.rs). Each walk is one of the
    // footprint's rings; its area is its own edges' shoelace sum, positive as the cleaning wrote an outer ring.
    constexpr std::uint32_t none = 0xFFFF'FFFFU;
    struct Walk {
        std::uint32_t parent = none; ///< the walk its seam left
        std::uint32_t seam = none;   ///< the seam that opened it
        double area = 0.0;           ///< twice its area
        bool kept = false;           ///< drawn with its piece: the piece's outer ring, or a courtyard of it
    };
    std::vector<Walk> walks(1);
    std::vector<std::uint32_t> walk_of(n, 0); // an edge's walk; a seam's: the walk it opens or closes
    std::vector<std::uint32_t> open;          // the seams not yet run back, the last opened last
    std::vector<std::uint32_t> at{0};         // the walks they opened, under them the ring's first
    for (std::uint32_t e = 0; e < n; ++e) {
        if (seam[e] == seam_kind::none) {
            walk_of[e] = at.back();
            walks[at.back()].area += (x(e) * z(e + 1)) - (x(e + 1) * z(e));
            continue;
        }
        const bool back = !open.empty() && same(open.back(), e + 1) && same(open.back() + 1, e);
        if (back) {
            if (seam[open.back()] != seam[e]) { // there as one kind and back as the other: no ring the cut wrote
                whole(false);
                return out;
            }
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
    if (!open.empty()) {
        whole(false);
        return out;
    }
    // A piece is the walks its courtyards' seams join.
    const auto count = static_cast<std::uint32_t>(walks.size());
    const auto court = [&](std::uint32_t w) { return seam[walks[w].seam] == seam_kind::court; };
    std::vector<std::uint32_t> piece(count);
    std::ranges::iota(piece, 0U);
    const auto root = [&](std::uint32_t w) {
        while (piece[w] != w) w = piece[w] = piece[piece[w]];
        return w;
    };
    for (std::uint32_t w = 1; w < count; ++w) {
        if (court(w)) piece[root(w)] = root(walks[w].parent);
    }
    // Its outer ring is its walk of the largest area, where that is more than 0. Its courtyards are the walks that
    // still turn the other way round, joined to the outer ring by courtyards that do.
    std::vector<std::uint32_t> outer(count, none);
    for (std::uint32_t w = 0; w < count; ++w) {
        std::uint32_t& o = outer[root(w)];
        if (walks[w].area > 0.0 && (o == none || walks[w].area > walks[o].area)) o = w;
    }
    for (std::uint32_t w = 0; w < count; ++w) {
        if (outer[w] != none) walks[outer[w]].kept = true;
    }
    for (bool more = true; more;) {
        more = false;
        for (std::uint32_t w = 1; w < count; ++w) {
            if (!court(w)) continue;
            Walk& a = walks[w];
            Walk& b = walks[a.parent];
            if (a.kept == b.kept) continue;
            Walk& joined = a.kept ? b : a;
            if (joined.area < 0.0) {
                joined.kept = true;
                more = true;
            }
        }
    }
    // Whether a piece is drawn: its area, its courtyards taken off.
    std::vector<double> area(count, 0.0);
    std::vector<std::uint8_t> yard(count, 0);
    for (std::uint32_t w = 0; w < count; ++w) {
        if (walks[w].kept) area[root(w)] += walks[w].area / 2;
        if (w != 0 && court(w)) yard[root(w)] = 1;
    }
    const auto drawn = [&](std::uint32_t r) { return area[r] > 0.0 && area[r] >= least_face; };
    // A seam to a courtyard is its piece's where both the walks it joins are drawn.
    const auto own = [&](std::uint32_t e) {
        const Walk& w = walks[walk_of[e]];
        if (seam[e] == seam_kind::none) return w.kept;
        return seam[e] == seam_kind::court && w.kept && walks[w.parent].kept;
    };
    out.off.assign(n, 1);
    std::vector<std::uint32_t> index(count, none);
    for (std::uint32_t e = 0; e < n; ++e) {
        if (seam[e] == seam_kind::piece) continue;
        const std::uint32_t r = root(walk_of[e]);
        const bool is_drawn = drawn(r);
        if (is_drawn && !own(e)) continue; // a courtyard left out of a drawn piece
        if (index[r] == none) {
            index[r] = static_cast<std::uint32_t>(out.pieces.size());
            out.pieces.push_back({.edges = {}, .courtyard = yard[r] != 0, .drawn = is_drawn});
        }
        out.pieces[index[r]].edges.push_back(e);
        if (is_drawn && seam[e] == seam_kind::none) out.off[e] = 0;
    }
    return out;
}

} // namespace atlas::geometry
