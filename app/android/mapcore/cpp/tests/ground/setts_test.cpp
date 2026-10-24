// A sett road's courses (ENGINE.md step 4, "Trees and things"; amsterdam.py's road loop): stage T's lines on the
// bundled packs (ATLAS_TILES_DIR), each across its road every 0.6 m with its reach back, and the line stage's layer
// (shaders/ground/setts.slangh, run as C++ by twin/ground/LineShader.hpp): along the bundled Westerkerk's sett roads,
// far, close and at their sharpest bends, in the three widths' modes (a hairline, the exact area, the samples), the
// shares the shader's own look back gives each line compose, pixel by pixel, the path's exact layer over every line;
// and the canal ring's alike.
#include "base/pymath/PyMath.hpp"
#include "geometry/Ground.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "twin/ground/LineShader.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <format>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;
namespace sh = atlas::shaders;
namespace tl = atlas::tiles;

namespace {

/// A bundled sett road: its lines' ends (frame metres, two a line), their height and reaches.
struct SettRoad {
    std::vector<geo::P2> ends;
    double y = 0;
    std::vector<std::uint32_t> reaches;
};

/// Every sett road of the pack's tiles at its first level.
std::vector<SettRoad> sett_roads(const tl::Pack& pack) {
    const tl::RenderHead& head = *pack.head();
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "setts test");
    std::vector<SettRoad> out;
    for (const tl::ChunkRef& c : pack.chunks().level(head.levels.front())) {
        arena.reset();
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        REQUIRE(tile.has_value());
        const geo::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
        const geo::GroundTile g = geo::build_ground(*tile, head, *pack.style(), 7, origin);
        for (const geo::Stroke& s : g.strokes) {
            if (s.kind != geo::StrokeKind::setts) continue;
            REQUIRE(s.count == 1);
            const geo::Polyline& pl = g.polylines[s.first];
            REQUIRE(pl.pairs);
            REQUIRE(pl.count % 2 == 0);
            SettRoad r;
            r.ends.assign(g.points.begin() + pl.first, g.points.begin() + pl.first + pl.count);
            r.y = pl.y;
            r.reaches.assign(g.reaches.begin() + pl.reach, g.reaches.begin() + pl.reach + (pl.count / 2));
            out.push_back(std::move(r));
        }
    }
    return out;
}

/// What one frame of a road composed: the pixels (samples, in the samples mode) its lines cover, the lines their
/// fragments looked back at, the samples blended other than once (the samples mode), the lines over the most crowded
/// pixel, the largest difference between the shares' composite and the exact layer and how many differ by more than
/// 1e-4.
struct Composed {
    std::size_t covered = 0;
    std::size_t looked = 0;
    std::size_t twice = 0;
    std::uint32_t crowd = 0;
    double worst = 0;
    std::size_t off = 0;
};

/// The road's lines at camera `cam` on a device of `density`, composed by their shares and exactly.
Composed compose(const SettRoad& road, const atlas::view::Camera& cam, double density) {
    const auto hw = static_cast<float>(0.4 * density / 2);
    const float wide = 2 * hw;
    const sh::uint mode = wide <= 1.0F ? sh::LINE_HAIR : (wide < 2.0F ? sh::LINE_AREA : sh::LINE_SAMPLES);
    const float op = 153.0F / 255.0F;
    const std::size_t n = road.ends.size() / 2;
    std::vector<sh::SettLine> lines(n);
    for (std::size_t j = 0; j < n; ++j) {
        const auto a = cam.proj({road.ends[2 * j].x, road.y, road.ends[2 * j].z});
        const auto b = cam.proj({road.ends[(2 * j) + 1].x, road.y, road.ends[(2 * j) + 1].z});
        lines[j].drawn = a.has_value() && b.has_value();
        if (!lines[j].drawn) continue;
        lines[j].a = sh::float2{static_cast<float>(a->x * density), static_cast<float>(a->y * density)};
        lines[j].b = sh::float2{static_cast<float>(b->x * density), static_cast<float>(b->y * density)};
    }
    // each line's reach as stroke_vs picks it: the level for the ground's stretch at its ends (unsnapped)
    std::vector<sh::uint> reach(n, 0);
    for (std::size_t j = 0; j < n; ++j) {
        if (!lines[j].drawn) continue;
        const geo::P2 pa = road.ends[2 * j];
        const geo::P2 pb = road.ends[(2 * j) + 1];
        const double l = pm::hypot(pb.x - pa.x, pb.z - pa.z);
        const double ux = (pb.x - pa.x) / l;
        const double uz = (pb.z - pa.z) / l;
        float tolerance = 0;
        for (const geo::P2 p : {pa, pb}) {
            auto device = [&](double x, double z) {
                const atlas::view::Vec2 c = cam.pc(cam.cam({x, road.y, z}));
                return sh::float2{static_cast<float>(c.x * density), static_cast<float>(c.y * density)};
            };
            const sh::float2 s0 = device(p.x, p.z);
            const sh::float2 su = device(p.x + ux, p.z + uz) - s0;
            const sh::float2 sn = device(p.x - uz, p.z + ux) - s0;
            tolerance = sh::max(tolerance, sh::sett_tolerance(su, sn, hw));
        }
        reach[j] = sh::sett_reach(road.reaches[j], tolerance);
    }
    const sh::SettRun run{.lines = lines.data()};
    const auto width = static_cast<std::int64_t>(std::lround(cam.width() * density));
    const auto height = static_cast<std::int64_t>(std::lround(cam.height() * density));
    // per pixel (per sample in the samples mode): the exact layer's transmittance and the shares' composite
    struct Acc {
        double exact = 1;
        double composed = 1;
        std::uint32_t lines = 0;
        std::uint32_t blended = 0;
    };
    std::unordered_map<std::int64_t, Acc> acc;
    std::size_t looked = 0;
    const std::array<sh::float2, 4> samples{sh::float2{0.375F, 0.125F}, sh::float2{0.875F, 0.375F},
                                            sh::float2{0.125F, 0.625F}, sh::float2{0.625F, 0.875F}};
    for (std::size_t j = 0; j < n; ++j) {
        const sh::SettLine& e = lines[j];
        if (!e.drawn) continue;
        const float margin = hw + 2.0F;
        const auto x0 =
            std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(std::min(e.a.x, e.b.x) - margin)));
        const auto x1 =
            std::min<std::int64_t>(width - 1, static_cast<std::int64_t>(std::floor(std::max(e.a.x, e.b.x) + margin)));
        const auto y0 =
            std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(std::min(e.a.y, e.b.y) - margin)));
        const auto y1 =
            std::min<std::int64_t>(height - 1, static_cast<std::int64_t>(std::floor(std::max(e.a.y, e.b.y) + margin)));
        const auto line = static_cast<sh::uint>(j);
        for (std::int64_t py = y0; py <= y1; ++py) {
            for (std::int64_t px = x0; px <= x1; ++px) {
                const sh::float2 pixel{static_cast<float>(px), static_cast<float>(py)};
                const sh::float2 centre = pixel + 0.5F;
                const std::int64_t at = (py * width) + px;
                if (mode == sh::LINE_SAMPLES) {
                    // stroke_fs: the samples inside its shape, less those the lines before it cover
                    const sh::Seg g = sh::seg_make(e.a, e.b, e.a, e.b, 0U, hw, 0U);
                    sh::uint own = 0;
                    for (sh::uint i = 0; i < 4; ++i) {
                        if (sh::seg_inside(g, pixel + samples.at(i))) own |= 1U << i;
                    }
                    if (own == 0) continue;
                    const sh::uint before =
                        sh::sett_before_mask(run, line, reach[j], hw, pixel + samples[0], pixel + samples[1],
                                             pixel + samples[2], pixel + samples[3], 4);
                    looked += std::min<std::size_t>(reach[j], j);
                    for (sh::uint i = 0; i < 4; ++i) {
                        if ((own & (1U << i)) == 0) continue;
                        Acc& a = acc[(at * 4) + i];
                        a.exact = 0;
                        ++a.lines;
                        if ((before & (1U << i)) == 0) ++a.blended;
                    }
                    continue;
                }
                // stroke_fs: its own coverage, then its share of the layer
                const float own = sh::min(sh::sett_cover(e, hw, mode, pixel, centre), 1.0F);
                if (own <= 0.0F) continue;
                const float before = sh::sett_before(run, line, reach[j], hw, mode, pixel, centre);
                const float share = sh::sett_share(own, before, op);
                looked += std::min<std::size_t>(reach[j], j);
                Acc& a = acc[at];
                a.exact *= 1.0 - static_cast<double>(own);
                a.composed *= 1.0 - (static_cast<double>(op) * static_cast<double>(share));
                ++a.lines;
            }
        }
    }
    Composed c;
    c.looked = looked;
    for (const auto& [at, a] : acc) {
        (void)at;
        ++c.covered;
        c.crowd = std::max(c.crowd, a.lines);
        if (mode == sh::LINE_SAMPLES) {
            if (a.blended != 1) ++c.twice;
            continue;
        }
        const double exact = 1.0 - (static_cast<double>(op) * (1.0 - a.exact));
        const double d = std::abs(a.composed - exact);
        c.worst = std::max(c.worst, d);
        if (d > 1e-4) ++c.off;
    }
    return c;
}

/// The yaw (render/city.py's Camera: 0 looks toward -z) of the horizontal direction (dx, dz), in degrees.
[[nodiscard]] double yaw_of(double dx, double dz) noexcept { return pm::degrees(pm::atan2(dx, -dz)); }

} // namespace

TEST_CASE("setts: the bundled sett roads' courses, a line across the road every 0.6 m, and their reaches") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack((std::filesystem::path(dir) / (std::string(name) + ".atlas")).string());
        ATLAS_REQUIRE(pack.has_value(), "the pack opens");
        const std::vector<SettRoad> roads = sett_roads(*pack);
        std::size_t lines = 0;
        std::size_t reaching = 0;
        std::array<std::uint32_t, geo::setts_tolerances.size()> farthest{};
        std::size_t uneven = 0;
        std::size_t unordered = 0;
        for (const SettRoad& r : roads) {
            lines += r.reaches.size();
            for (std::size_t j = 0; j < r.reaches.size(); ++j) {
                // a line spans its road: its ends w apart, the road's width
                const geo::P2 a = r.ends[2 * j];
                const geo::P2 b = r.ends[(2 * j) + 1];
                const double w = pm::hypot(b.x - a.x, b.z - a.z);
                if (w <= 0.5 || w >= 40) ++uneven;
                // its reaches, six bits a level: none past the lines before it, none less at a wider tolerance
                std::uint32_t last = 0;
                for (std::size_t l = 0; l < farthest.size(); ++l) {
                    const std::uint32_t reach = (r.reaches[j] >> (6 * l)) & 63U;
                    CHECK(reach <= std::min<std::size_t>(j, geo::setts_back));
                    if (reach < last) ++unordered;
                    last = reach;
                    farthest.at(l) = std::max(farthest.at(l), reach);
                }
                if ((r.reaches[j] & 63U) > 0) ++reaching;
            }
        }
        MESSAGE(std::format(
            "{}: {} sett roads, {} lines, {} of them within 0.5 m of an earlier one; the farthest reaches "
            "{}, {}, {}, {} and {} lines",
            name, roads.size(), lines, reaching, farthest[0], farthest[1], farthest[2], farthest[3], farthest[4]));
        CHECK(uneven == 0);
        CHECK(unordered == 0);
        for (std::size_t l = 0; l < geo::setts_tolerances.size(); ++l) {
            CHECK(static_cast<float>(geo::setts_tolerances.at(l)) == sh::SETTS_TOLERANCES[l]);
        }
        if (std::string(name) == "amsterdam") CHECK(!roads.empty());
    }
}

TEST_CASE("setts: the line stage's shares compose the path's exact layer, far, close and at bends, in every mode") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        if (dir.empty() || !std::filesystem::exists(path)) {
            MESSAGE("ATLAS_TILES_DIR has no " << name << ".atlas: its layer is not checked here");
            continue;
        }
        auto pack = atlas::test::open_pack(path.string());
        ATLAS_REQUIRE(pack.has_value(), "the pack opens");
        std::vector<SettRoad> roads = sett_roads(*pack);
        ATLAS_REQUIRE(!roads.empty(), "the bundled Westerkerk has sett roads");
        // the longest roads and those with the farthest reaches (their sharpest bends)
        std::ranges::sort(roads,
                          [](const SettRoad& a, const SettRoad& b) { return a.reaches.size() > b.reaches.size(); });
        std::vector<const SettRoad*> chosen;
        for (std::size_t i = 0; i < std::min<std::size_t>(4, roads.size()); ++i) chosen.push_back(&roads[i]);
        auto farthest = [](const SettRoad& r) {
            std::uint32_t f = 0;
            for (const std::uint32_t w : r.reaches) f = std::max(f, w & 63U);
            return f;
        };
        std::vector<const SettRoad*> bent;
        bent.reserve(roads.size());
        for (const SettRoad& r : roads) bent.push_back(&r);
        std::ranges::sort(bent, [&](const SettRoad* a, const SettRoad* b) { return farthest(*a) > farthest(*b); });
        for (const SettRoad* r : bent) {
            if (chosen.size() >= 8) break;
            if (std::ranges::find(chosen, r) == chosen.end()) chosen.push_back(r);
        }
        std::size_t covered = 0;
        std::size_t off = 0;
        std::size_t twice = 0;
        std::size_t looked = 0;
        double worst = 0;
        std::uint32_t crowd = 0;
        for (const SettRoad* r : chosen) {
            const std::size_t n = r->reaches.size();
            // the road's middle and its direction there; its sharpest bend
            const std::size_t m = n / 2;
            const geo::P2 mid{.x = (r->ends[2 * m].x + r->ends[(2 * m) + 1].x) / 2,
                              .z = (r->ends[2 * m].z + r->ends[(2 * m) + 1].z) / 2};
            const std::size_t first = m > 0 ? m - 1 : 0;
            const std::size_t last = std::min(n - 1, m + 1);
            const double dx = ((r->ends[2 * last].x + r->ends[(2 * last) + 1].x) -
                               (r->ends[2 * first].x + r->ends[(2 * first) + 1].x)) /
                              2;
            const double dz = ((r->ends[2 * last].z + r->ends[(2 * last) + 1].z) -
                               (r->ends[2 * first].z + r->ends[(2 * first) + 1].z)) /
                              2;
            const auto bend = static_cast<std::size_t>(
                std::ranges::max_element(r->reaches, {}, [](std::uint32_t w) { return w & 63U; }) - r->reaches.begin());
            const geo::P2 at{.x = (r->ends[2 * bend].x + r->ends[(2 * bend) + 1].x) / 2,
                             .z = (r->ends[2 * bend].z + r->ends[(2 * bend) + 1].z) / 2};
            const double along = yaw_of(dx, dz);
            struct Shot {
                geo::P2 target;
                double yaw = 0;
                double pitch = 0;
                double dist = 0;
            };
            const std::array<Shot, 4> shots{Shot{.target = mid, .yaw = along, .pitch = 12, .dist = 700},
                                            Shot{.target = mid, .yaw = along, .pitch = 30, .dist = 250},
                                            Shot{.target = mid, .yaw = along + 90, .pitch = 25, .dist = 40},
                                            Shot{.target = at, .yaw = along + 45, .pitch = 55, .dist = 25}};
            for (const Shot& s : shots) {
                const atlas::view::Camera cam({.target = {s.target.x, r->y, s.target.z},
                                               .yaw = s.yaw,
                                               .pitch = s.pitch,
                                               .dist = s.dist,
                                               .width = 412,
                                               .height = 916});
                for (const double density : {1.0, 1080.0 / 412.0, 5.5}) {
                    const Composed c = compose(*r, cam, density);
                    covered += c.covered;
                    looked += c.looked;
                    off += c.off;
                    twice += c.twice;
                    worst = std::max(worst, c.worst);
                    crowd = std::max(crowd, c.crowd);
                }
            }
        }
        MESSAGE(std::format(
            "{}: {} roads at four cameras and three densities: {} pixels and samples covered, as many as {} "
            "lines over one, each line's fragments looking back at {:.2f} lines on average; the shares' "
            "composite off the exact layer by at most {:.2e}, {} of them past 1e-4; {} samples blended other "
            "than once",
            name, chosen.size(), covered, crowd, static_cast<double>(looked) / static_cast<double>(covered), worst, off,
            twice));
        CHECK(covered > 0);
        CHECK(crowd > 4);
        // exact but for float rounding, and beyond the last tolerance (a pixel spanning over 2 m, at the farthest
        // views) within a hundredth of a level; every sample of the samples mode blended once
        CHECK(worst < 1e-3);
        CHECK(twice == 0);
    }
}
