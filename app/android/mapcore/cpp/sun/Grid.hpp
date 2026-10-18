// The shadows' grid (plan-vector D8, K8): the port's Real.Shadows.Index. The solids the sun worker holds (buildings
// not hidden with a height, parts with a height, of the resident tiles and a 220 m halo) as flat-topped prisms over
// their footprints, listed in a 16 m grid of their boxes. The grid does not depend on the sun, so one serves every
// bucket while the tiles stay.
//
// The port lists each solid, in the scene's order, in every cell its box covers: the box is Python's min and max of the
// footprint's x and z, a cell is (floor(x / 16), floor(z / 16)) and a cell's key GridMap.key(i, j). A march asks a
// cell for its solids; the order within a cell does not change an answer (any hit is a hit), and each entry carries
// the solid's box and height beside its id, so a march reads one array.
//
// The tallest solid ends every march (Shadows.lit's tmax): it is the scene's, the largest height over every solid the
// port's scene holds, which for a render pack is the whole pack at the frame's level (scene_maxh in Samples.hpp), not
// only the tiles a grid holds. A march's steps above every solid's top can never hit one (a step hits only where its
// height is at most the solid's), so a larger value adds only steps that cannot hit, but for one: the first step at or
// past the true tmax can land a rounding (an ulp or two) below the tallest top, where the port's own answer turns on
// the last bit of maxh. So the core takes the scene's exact value, and a pack too big to scan, or windowed, wants rhead
// to carry it (K2, K20).
#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::sun {

/// A solid's place in a grid: its order among the solids the grid was built from.
using PrismId = std::uint32_t;

/// No solid: a march that leaves none out.
inline constexpr PrismId no_prism = 0xFFFF'FFFFU;

/// The grid's cell side, metres (Shadows.Index.cell).
inline constexpr double cell_size = 16.0;

/// The port's GridMap.key of cell (i, j): i in the high half, j's low 32 bits in the low half.
[[nodiscard]] constexpr std::uint64_t cell_key(std::int64_t i, std::int64_t j) noexcept {
    return (static_cast<std::uint64_t>(i) << 32U) ^ (static_cast<std::uint64_t>(j) & 0xFFFF'FFFFU);
}

/// The cell holding coordinate `v` (metres): PyMath.floorInt(v / 16). Finite coordinates only.
[[nodiscard]] std::int64_t cell_of(double v) noexcept;

/// One entry of a cell: a solid's box, its height and its id.
struct CellEntry {
    double min_x;
    double max_x;
    double min_z;
    double max_z;
    double h;
    PrismId id;
};

/// The solids as prisms in a 16 m grid. Immutable once built: any number of threads march through it at once.
class Grid {
public:
    /// Collects solids in their order, then builds the grid.
    class Builder {
    public:
        /// Adds a solid: its footprint as x and z metres (a ring, its first vertex not repeated, at least one vertex)
        /// and its height. Returns its id, its place among the solids added.
        PrismId add(std::span<const double> x, std::span<const double> z, double h);

        /// The grid over the solids added; `scene_maxh` is the scene's tallest solid (at least the tallest added).
        [[nodiscard]] Grid build(double scene_maxh) &&;

        /// The tallest solid added so far, as Python's max of their heights (the first of the largest); 0 for none.
        [[nodiscard]] double maxh() const noexcept { return maxh_; }

        /// How many solids were added.
        [[nodiscard]] std::size_t size() const noexcept { return hs_.size(); }

    private:
        std::vector<double> xz_;              ///< every footprint's x, z pairs, solid after solid
        std::vector<std::uint32_t> first_{0}; ///< where each solid's pairs begin in xz_ (one more than solids)
        std::vector<double> hs_;
        std::vector<double> boxes_; ///< min x, max x, min z, max z per solid
        double maxh_ = 0.0;
    };

    Grid() = default;

    /// The scene's tallest solid, which ends each march.
    [[nodiscard]] double maxh() const noexcept { return maxh_; }

    /// How many solids the grid holds.
    [[nodiscard]] std::size_t size() const noexcept { return hs_.size(); }

    /// The solids listed in the cell of key `key` (cell_key), or none.
    [[nodiscard]] std::span<const CellEntry> cell(std::uint64_t key) const noexcept;

    /// Solid `id`'s footprint as x, z pairs.
    [[nodiscard]] std::span<const double> footprint(PrismId id) const noexcept {
        return std::span<const double>(xz_).subspan(2 * std::size_t{first_[id]},
                                                    2 * std::size_t{first_[id + 1] - first_[id]});
    }

    /// Solid `id`'s height.
    [[nodiscard]] double height(PrismId id) const noexcept { return hs_[id]; }

    /// How many cells hold a solid, and how many entries they hold together.
    [[nodiscard]] std::size_t cells() const noexcept { return slots_used_; }
    [[nodiscard]] std::size_t entries() const noexcept { return entries_.size(); }

private:
    struct Slot {
        std::uint64_t key;
        std::uint32_t first;
        std::uint32_t count; ///< 0: an empty slot
    };

    std::vector<double> xz_;
    std::vector<std::uint32_t> first_;
    std::vector<double> hs_;
    std::vector<CellEntry> entries_; ///< cell after cell, each cell's solids in their order
    std::vector<Slot> slots_;        ///< open addressing over the cells' keys (a power of two)
    std::size_t slots_used_ = 0;
    double maxh_ = 0.0;
};

/// The port's pipXZ: whether (x, z) lies inside a footprint given as x, z pairs, by the even-odd rule with its
/// arithmetic in its order.
[[nodiscard]] bool inside(double x, double z, std::span<const double> xz) noexcept;

} // namespace atlas::sun
