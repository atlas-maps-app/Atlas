#include "sun/Shadows.hpp"

#include "base/pymath/PyMath.hpp"

namespace atlas::sun {

namespace {

/// Shadows.lit, with or without counting: Counter is MarchCount or a stand-in that counts nothing.
template <class Counter>
bool march(const Grid& grid, const Sun& sun, double px, double py, double pz, PrismId skip, double reach_m,
           Counter& count) noexcept {
    if (sun.y <= 0) return false;
    const double tmax = pymath::min(reach_m, (grid.maxh() - py) / sun.y);
    double t = 0.8;
    std::uint64_t last = 0;
    std::span<const CellEntry> cell;
    bool first = true;
    while (t < tmax) {
        const double x = px + (sun.x * t);
        const double y = py + (sun.y * t);
        const double z = pz + (sun.z * t);
        count.step();
        // steps of 1.6 m mostly stay in one 16 m cell: the cell is looked up again only when the key changes
        const std::uint64_t key = cell_key(cell_of(x), cell_of(z));
        if (first || key != last) {
            cell = grid.cell(key);
            last = key;
            first = false;
            count.cell();
        }
        for (const CellEntry& e : cell) {
            if (e.id == skip || y > e.h) continue;
            if (e.min_x <= x && x <= e.max_x && e.min_z <= z && z <= e.max_z) {
                count.test();
                if (inside(x, z, grid.footprint(e.id))) return false;
            }
        }
        t += 1.6;
    }
    return true;
}

struct NoCount {
    static void step() noexcept {}
    static void cell() noexcept {}
    static void test() noexcept {}
};

struct Counting {
    MarchCount& c;
    void step() const noexcept { ++c.steps; }
    void cell() const noexcept { ++c.cells; }
    void test() const noexcept { ++c.tests; }
};

} // namespace

bool lit(const Grid& grid, const Sun& sun, double px, double py, double pz, PrismId skip, double reach_m) noexcept {
    NoCount none;
    return march(grid, sun, px, py, pz, skip, reach_m, none);
}

bool lit_counted(const Grid& grid, const Sun& sun, double px, double py, double pz, PrismId skip, MarchCount& count,
                 double reach_m) noexcept {
    Counting counting{count};
    return march(grid, sun, px, py, pz, skip, reach_m, counting);
}

} // namespace atlas::sun
