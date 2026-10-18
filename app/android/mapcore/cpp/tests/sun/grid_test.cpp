// The shadows' grid and march (sun/Grid.hpp, sun/Shadows.hpp): the port's cell keys and floors, its even-odd rule, the
// grid's cells against every solid's box, and the march against a march that tests every solid at every step, over
// scenes drawn from PyRandom; and the sun's check, which holds the march and hull2 to the port's own Shadows on every
// host (MT/sun/SunParityTest.kt computes the expected hash with the port).
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "sun/Buckets.hpp"
#include "sun/Grid.hpp"
#include "sun/Shadows.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace {

using namespace atlas::sun;
namespace pm = atlas::pymath;

/// A solid drawn for a scene: its footprint and height.
struct Drawn {
    std::vector<double> x;
    std::vector<double> z;
    double h = 0.0;
};

std::vector<Drawn> draw(pm::PyRandom& rnd, int n, double extent) {
    std::vector<Drawn> out;
    for (int k = 0; k < n; ++k) {
        const double cx = rnd.uniform(-extent, extent);
        const double cz = rnd.uniform(-extent, extent);
        const int nv = 3 + static_cast<int>(rnd.random() * 7);
        const double r = rnd.uniform(2.0, 30.0);
        Drawn d;
        d.h = rnd.uniform(2.0, 70.0);
        for (int i = 0; i < nv; ++i) {
            const double a = (((2 * pm::pi) * i) / nv) + rnd.uniform(-0.3, 0.3);
            const double rr = r * rnd.uniform(0.4, 1.0);
            d.x.push_back(cx + (rr * pm::cos(a)));
            d.z.push_back(cz + (rr * pm::sin(a)));
        }
        out.push_back(std::move(d));
    }
    return out;
}

/// Shadows.lit with every solid tested at every step: the grid only narrows which are tested.
bool brute(const std::vector<Drawn>& solids, double maxh, const Sun& sun, double px, double py, double pz,
           PrismId skip) {
    if (sun.y <= 0) return false;
    const double tmax = pm::min(reach, (maxh - py) / sun.y);
    double t = 0.8;
    while (t < tmax) {
        const double x = px + (sun.x * t);
        const double y = py + (sun.y * t);
        const double z = pz + (sun.z * t);
        for (std::size_t k = 0; k < solids.size(); ++k) {
            if (k == skip || y > solids[k].h) continue;
            std::vector<double> xz;
            for (std::size_t i = 0; i < solids[k].x.size(); ++i) {
                xz.push_back(solids[k].x[i]);
                xz.push_back(solids[k].z[i]);
            }
            if (inside(x, z, xz)) return false;
        }
        t += 1.6;
    }
    return true;
}

} // namespace

TEST_CASE("sun: cells are the port's floors and GridMap keys") {
    CHECK(cell_of(0.0) == 0);
    CHECK(cell_of(15.999) == 0);
    CHECK(cell_of(16.0) == 1);
    CHECK(cell_of(-0.0) == 0);
    CHECK(cell_of(-1e-9) == -1);
    CHECK(cell_of(-16.0) == -1);
    CHECK(cell_of(-16.000001) == -2);
    CHECK(cell_key(0, 0) == 0);
    CHECK(cell_key(1, 2) == 0x0000'0001'0000'0002ULL);
    CHECK(cell_key(-1, 2) == 0xFFFF'FFFF'0000'0002ULL);
    CHECK(cell_key(3, -1) == 0x0000'0003'FFFF'FFFFULL); // (i shl 32) xor (j and 0xffffffff)
    CHECK(cell_key(-2, -3) == 0xFFFF'FFFE'FFFF'FFFDULL);
}

TEST_CASE("sun: the even-odd rule over a footprint") {
    // a square, and a U whose notch is outside
    const std::vector<double> square{0, 0, 10, 0, 10, 10, 0, 10};
    CHECK(inside(5, 5, square));
    CHECK(!inside(11, 5, square));
    CHECK(!inside(5, -0.1, square));
    const std::vector<double> u{0, 0, 30, 0, 30, 20, 20, 20, 20, 10, 10, 10, 10, 20, 0, 20};
    CHECK(inside(5, 15, u));
    CHECK(!inside(15, 15, u));
    CHECK(inside(15, 5, u));
}

TEST_CASE("sun: every solid is in the cells its box covers, and only there") {
    pm::PyRandom rnd(7);
    const std::vector<Drawn> solids = draw(rnd, 300, 400.0);
    Grid::Builder b;
    for (const Drawn& d : solids) (void)b.add(d.x, d.z, d.h);
    CHECK(b.size() == solids.size());
    const double maxh = b.maxh();
    const Grid g = std::move(b).build(maxh);
    CHECK(g.maxh() == maxh);
    std::size_t entries = 0;
    for (std::int64_t i = -30; i <= 30; ++i) {
        for (std::int64_t j = -30; j <= 30; ++j) {
            std::vector<PrismId> want;
            for (std::size_t k = 0; k < solids.size(); ++k) {
                double x0 = solids[k].x[0];
                double x1 = x0;
                double z0 = solids[k].z[0];
                double z1 = z0;
                for (std::size_t v = 1; v < solids[k].x.size(); ++v) {
                    x0 = pm::min(x0, solids[k].x[v]);
                    x1 = pm::max(x1, solids[k].x[v]);
                    z0 = pm::min(z0, solids[k].z[v]);
                    z1 = pm::max(z1, solids[k].z[v]);
                }
                if (cell_of(x0) <= i && i <= cell_of(x1) && cell_of(z0) <= j && j <= cell_of(z1)) {
                    want.push_back(static_cast<PrismId>(k));
                }
            }
            std::vector<PrismId> got;
            for (const CellEntry& e : g.cell(cell_key(i, j))) {
                got.push_back(e.id);
                CHECK(e.h == solids[e.id].h);
            }
            CHECK(got == want); // each cell's solids in their order
            entries += got.size();
        }
    }
    CHECK(entries > 0);
    CHECK(g.cell(cell_key(1000, 1000)).empty());
}

TEST_CASE("sun: the march through the grid answers as a march that tests every solid") {
    pm::PyRandom rnd(2026);
    const std::vector<Drawn> solids = draw(rnd, 250, 250.0);
    Grid::Builder b;
    for (const Drawn& d : solids) (void)b.add(d.x, d.z, d.h);
    const double maxh = b.maxh();
    const Grid g = std::move(b).build(maxh);
    std::size_t lit_count = 0;
    std::size_t n = 0;
    MarchCount count;
    for (const auto& [az, el] :
         std::array<std::array<double, 2>, 4>{{{140.0, 30.0}, {230.0, 8.0}, {60.0, 60.0}, {0.0, 1.0}}}) {
        const Sun sun = sun_vector(az, el);
        for (int i = 0; i < 1500; ++i) {
            const double x = rnd.uniform(-260.0, 260.0);
            const double y = rnd.uniform(0.0, 40.0);
            const double z = rnd.uniform(-260.0, 260.0);
            const PrismId skip = rnd.random() < 0.3 ? static_cast<PrismId>(rnd.random() * 250) : no_prism;
            const bool a = lit(g, sun, x, y, z, skip);
            CHECK(a == brute(solids, maxh, sun, x, y, z, skip));
            CHECK(a == lit_counted(g, sun, x, y, z, skip, count));
            lit_count += a ? 1 : 0;
            ++n;
        }
    }
    MESSAGE(lit_count, " of ", n, " lit; ", count.steps, " steps, ", count.cells, " cells, ", count.tests,
            " footprints");
    CHECK(lit_count > n / 10);
    CHECK(lit_count < n);
}

TEST_CASE("sun: a march leaves out the building it is skipped for, and nothing marches with the sun down") {
    Grid::Builder b;
    const std::vector<double> x{0, 10, 10, 0};
    const std::vector<double> z{0, 0, 10, 10};
    const PrismId own = b.add(x, z, 20.0);
    const Grid g = std::move(b).build(20.0);
    const Sun high = sun_vector(180.0, 45.0);
    CHECK(!lit(g, high, 5, 1, 5));     // inside its own building
    CHECK(lit(g, high, 5, 1, 5, own)); // its own building left out
    CHECK(lit(g, high, 5, 25, 5));     // above every top: no step at all
    CHECK(!lit(g, Sun{.x = 0.0, .y = -1.0, .z = 0.0}, 50, 1, 50));
    const Grid empty = Grid::Builder{}.build(0.0);
    CHECK(lit(empty, high, 0, 0, 0));
}

TEST_CASE("sun: the sun's check is the port's") {
    const std::uint64_t h = march_check();
    const std::string line = march_check_line();
    MESSAGE(line);
    (void)std::printf("%s\n", line.c_str()); // NOLINT: the line CI's logs keep
    CHECK(h == march_check_expected);
    CHECK(line.ends_with(" ok"));
    CHECK(march_check() == h);
}
