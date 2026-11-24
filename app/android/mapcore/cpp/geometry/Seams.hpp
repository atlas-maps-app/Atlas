// A building's seams (load/Clean.hpp): the cleaning joins a footprint's courtyards and pieces into its one ring by
// seams, an edge run there and back, which draw nothing. The cut marks each in the tile, on the corner it starts at,
// with its kind (tiles/Schema.hpp's keep::seam_piece and keep::seam_court; load/Clean.hpp's seam_kinds decides the
// kind once, on level 0's ring). Both ends of a seam are kept at every level, so a ring read at any level has its
// seams where its marks are: stage T reads them (seams_at) and never looks for one, nor calls the cleaning's library.
//
// Whatever stands on a footprint's edges (its walls, their windows and doors, its sun samples, its eaves, its mirrored
// walls, what a finger meets) asks one function, split, for the ring's pieces at the level and for the edges nothing
// stands on. A roof is laid over each piece by itself: a piece without a courtyard takes the building's pitched roof,
// one with a courtyard is drawn flat, until the roofs are built from any footprint. A piece a level has simplified to
// no area, or to less than the level's least face, draws nothing there.
#pragma once

#include "base/assert.hpp"
#include "geometry/RoofShapes.hpp"
#include "tiles/Payload.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <ranges>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::geometry {

/// What an edge of a ring is (edge i runs from vertex i to i + 1).
namespace seam_kind {
inline constexpr std::uint8_t none = 0;  ///< an edge of the footprint
inline constexpr std::uint8_t piece = 1; ///< a seam between two pieces of the footprint
inline constexpr std::uint8_t court = 2; ///< a seam that joins a courtyard to its piece
} // namespace seam_kind

/// The seams of a tile's ring read at a level: for each corner kept there (`kept`: tiles::simplify's indexes), what
/// the edge from it to the next kept corner is, by the corner's mark. Empty where the ring has no mark: no seam.
void seams_at(const tiles::RingView& ring, std::span<const std::uint32_t> kept, std::vector<std::uint8_t>& out);

/// The same of the ring whole, every corner kept: level 0's.
void seams_at(const tiles::RingView& ring, std::vector<std::uint8_t>& out);

/// Where a footprint is, for what needs one point of it (a building's sort point and radius cut, its door's and its
/// shopfront's depth, its mirror's head): the mean of its corners, each counted once. A ring's own mean (centroid)
/// counts both ends of every seam twice, so it leans to where the cleaning happened to join the footprint; here a
/// corner counts where an edge that is no seam starts (`seam`: seams_at's kinds, or a split's `off`). A ring without
/// a seam: centroid's own bits.
[[nodiscard]] V2 corner_mean(std::span<const V2> ring, std::span<const std::uint8_t> seam) noexcept;

/// One piece of a ring with seams.
struct Piece {
    /// The ring's edges that are its own, in the ring's order, which follow each other round it: its outer ring's
    /// and, where it has courtyards drawn at the level, theirs and the seams that join them to it. The seams between
    /// two pieces are in none. So the first points of a piece's edges are a ring: a simple one where it holds no
    /// seam, else one with seams of its own.
    std::vector<std::uint32_t> edges;
    /// It has a courtyard (a seam to one is its own, at level 0): drawn flat, also at a level that has simplified
    /// the courtyard away.
    bool courtyard = false;
    /// Whether it is drawn at the level: its area there (its outer ring's less its courtyards'), in the direction
    /// the cleaning wrote it, is more than 0 and not under the level's least face.
    bool drawn = true;
};

/// A ring split at a level.
struct Split {
    /// Its pieces, in the order of their first edges. One piece of every edge, flat, where the marks do not pair up
    /// there and back (`paired`: no ring the cut wrote).
    std::vector<Piece> pieces;
    /// Per edge of the ring, 1 where nothing stands on it: a seam, or an edge of a piece, or of a courtyard, that is
    /// not drawn at the level. An edge of a drawn piece's `edges` with 0 here carries its wall.
    std::vector<std::uint8_t> off;
    bool paired = true;
};

/// Splits `ring`, whose seams are `seam` (seams_at's, not empty), at a level whose least face is `least_face` m² (0 at
/// a window's level and its bands: tiles/LeastFace.hpp).
///
/// The ring's walks between its seams are its footprint's rings: a seam opens a walk, its way back closes it. A seam
/// to a courtyard joins two walks into one piece, a seam between pieces parts them. Of a piece's walks the one of the
/// largest area is its outer ring; another is a courtyard where the level leaves it turning the other way round and
/// joined to the outer ring by courtyards that are. A courtyard that no longer encloses ground is left out of its
/// piece, with what hangs on it.
[[nodiscard]] Split split(std::span<const V2> ring, std::span<const std::uint8_t> seam, double least_face);

/// The same for a ring given as x, z pairs one after another.
[[nodiscard]] Split split_xz(std::span<const double> xz, std::span<const std::uint8_t> seam, double least_face);

/// An edge of a ring a wall stands on: its number on the ring, and its two corners.
template <typename P>
struct WallEdge {
    std::size_t edge;
    const P& a;
    const P& b;
};

/// The one walk of a ring's walls (walls_of): its edges in the ring's order, but those nothing stands on.
template <typename P>
class RingWalls {
public:
    RingWalls(std::span<const P> ring, std::span<const std::uint8_t> off) noexcept : ring_(ring), off_(off) {
        ATLAS_ASSERT(off.empty() || off.size() == ring.size(), "a flag for each edge of the ring, or none");
    }

    struct End {};

    class Iterator {
    public:
        Iterator(const RingWalls& of, std::size_t at) noexcept : of_(&of), at_(of.next(at)) {}
        [[nodiscard]] WallEdge<P> operator*() const noexcept {
            return {.edge = at_, .a = of_->ring_[at_], .b = of_->ring_[(at_ + 1) % of_->ring_.size()]};
        }
        Iterator& operator++() noexcept {
            at_ = of_->next(at_ + 1);
            return *this;
        }
        [[nodiscard]] bool operator!=(End /*end*/) const noexcept { return at_ < of_->ring_.size(); }

    private:
        const RingWalls* of_;
        std::size_t at_;
    };

    [[nodiscard]] Iterator begin() const noexcept { return Iterator(*this, 0); }
    [[nodiscard]] End end() const noexcept { return {}; }

private:
    /// The first edge from `i` on that something stands on, or the ring's size.
    [[nodiscard]] std::size_t next(std::size_t i) const noexcept {
        while (i < ring_.size() && !off_.empty() && off_[i] != 0) ++i;
        return i;
    }

    std::span<const P> ring_;
    std::span<const std::uint8_t> off_;
};

/// The walls of `ring`: `for (const auto w : walls_of(ring, off))` gives each edge something stands on (w.edge, from
/// its corner w.a to the next, w.b), in the ring's order. `off`: Split::off, the edges nothing stands on (a seam, an
/// edge of a piece or of a courtyard not drawn at the level); empty where a wall stands on every edge, as on a ring
/// without seams. Whatever walks a ring's edges to build or to measure a wall takes them from here: the walls, their
/// sun samples and mirrored walls, what a finger meets, a houseboat's hull and cabin, a house's shortest side, the
/// edge a tower on a roof stands by.
template <std::ranges::contiguous_range R>
[[nodiscard]] auto walls_of(const R& ring, std::span<const std::uint8_t> off) noexcept {
    using P = std::ranges::range_value_t<R>;
    return RingWalls<P>(std::span<const P>(ring), off);
}

/// The roof shapes stage T draws pitched (geometry/Roofs.hpp), a quadruple saltbox as hipped.
inline constexpr std::array<std::string_view, 6> pitched_shapes = {"gabled",   "pyramidal", "hipped",
                                                                   "skillion", "mansard",   "quadruple_saltbox"};

/// Whether stage T draws a roof of `shape` and height `roof_h` pitched over a piece of a footprint: a shape it draws,
/// with a height, where the piece has no courtyard (`courtyard`: Piece::courtyard; a ring without seams has none).
/// Anything else it draws flat, the walls reaching the top.
[[nodiscard]] constexpr bool drawn_pitched(std::optional<std::string_view> shape, double roof_h,
                                           bool courtyard) noexcept {
    return shape.has_value() && std::ranges::find(pitched_shapes, *shape) != pitched_shapes.end() && roof_h != 0.0 &&
           !courtyard;
}

} // namespace atlas::geometry
