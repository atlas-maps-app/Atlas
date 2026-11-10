// The app's map's thin water's cull on the CPU (passes/Water.cpp's Ground::cull_thin, by passes/Ground.hpp's
// thin_folded): on every tile of the bundled packs, seen from 150 m to 15 km, from straight down to 66 degrees from it
// in any heading, at rest and moving, with weather and without, each stretch of a chunk the cull leaves out is one
// that the vertex stage folds (thin_of, its twin here in float on camera.slang's own projection and snap) or draws
// wholly beyond one side of the clip volume, its quad and its band; and pack's chunks hold their stretches.
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBlock.hpp"
#include "geometry/Ground.hpp"
#include "passes/Ground.hpp"
#include "tests/base/fma_inputs.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <numbers>
#include <span>
#include <string>
#include <vector>

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;
namespace sh = atlas::shaders;
namespace tl = atlas::tiles;

namespace {

/// ground.slang's LEAST_PX, LEAST_ALONG and THIN_UNITS.
constexpr float least_px = 1.5F;
constexpr float least_along = 32.0F;
constexpr float thin_units = 1024.0F;

/// A view: its size (CSS px), its density and its focal length (CSS px a unit of tangent).
struct Screen {
    float width = 0;
    float height = 0;
    float density = 0;
    float focal = 0;
};

/// The frame's camera (camera.slang's FrameCamera) looking `pitch` radians from straight down towards `heading`
/// radians clockwise from north (x east, z south, y up), its principal point `down` of the way down the view, the near
/// plane the engine's 0.6 m, at rest (snapping) or moving.
sh::FrameCamera frame_camera(const Screen& s, double pitch, double heading, double down, bool rest) {
    const auto f32 = [](double v) { return static_cast<float>(v); };
    const double sin_p = pm::sin(pitch);
    const double cos_p = pm::cos(pitch);
    const double sin_h = pm::sin(heading);
    const double cos_h = pm::cos(heading);
    // forward f, right r level, up r × f
    const sh::float4 right = {f32(cos_h), 0.0F, f32(sin_h), 0.5F * s.width};
    const sh::float4 up = {f32(sin_h * cos_p), f32(sin_p), f32(-cos_h * cos_p), f32(down) * s.height};
    const sh::float4 forward = {f32(sin_p * sin_h), f32(-cos_p), f32(-sin_p * cos_h), s.focal};
    const sh::float4 frame = {s.width, s.height, s.density, 0.6F};
    const sh::float4 device = {2.0F / (s.width * s.density), 2.0F / (s.height * s.density), 1.0F / s.density, 0.0F};
    const sh::float4 turn = {1.0F, 0.0F, 0.0F, 1.0F};
    const sh::uint4 flags = {rest ? sh::CAMERA_REST : 0U, 0U, 0U, 0U};
    sh::FrameCamera c{};
    c.right = right;
    c.up = up;
    c.forward = forward;
    c.frame = frame;
    c.device = device;
    c.turn = turn;
    c.flags = flags;
    return c;
}

/// ground.slang's thin_px: the water's width on the screen (device px) at an end w with wm across the water from it,
/// at height y.
float thin_px(const sh::FrameCamera& c, const sh::TilePlace& t, float y, sh::float2 w, sh::float2 wm) {
    const sh::float2 across = w - wm;
    const float l = std::sqrt(sh::dot(across, across));
    if (!(l > 0.0F)) return least_px;
    const sh::float2 mid = 0.5F * (w + wm);
    const sh::float2 turned = {-across.y, across.x};
    const sh::float2 along = turned * (least_along / l);
    const auto k = [&c, &t, y](sh::float2 p) {
        const sh::float3 local = {p.x, y, p.y};
        return sh::camera_of(c, sh::eye_relative(t, local));
    };
    const sh::float3 kw = k(w);
    const sh::float3 km = k(wm);
    const sh::float3 ka = k(mid + along);
    const sh::float3 kb = k(mid - along);
    if (sh::min(sh::min(kw.z, km.z), sh::min(ka.z, kb.z)) <= c.frame.w) return least_px;
    const sh::float2 d = sh::css_of(c, ka) - sh::css_of(c, kb);
    const sh::float2 s = sh::css_of(c, kw) - sh::css_of(c, km);
    const float ld = std::sqrt(sh::dot(d, d));
    if (!(ld > 0.0F)) return least_px;
    return std::abs((s.x * d.y) - (s.y * d.x)) / ld * c.frame.z;
}

/// ground.slang's place: a tile-local point's clip position, snapped at rest.
sh::float4 place(const sh::FrameCamera& c, const sh::TilePlace& t, sh::float2 p, float y) {
    const sh::float3 local = {p.x, y, p.y};
    const sh::float3 k = sh::camera_of(c, sh::eye_relative(t, local));
    return sh::clip_of(c, sh::project_css(c, k), k.z, 0.5F);
}

/// A stretch as thin_of draws it at height y: its widths on the screen at its ends, folded or not, and its quad's
/// corners and its band's in clip space.
struct Drawn {
    float pa = 0;
    float pb = 0;
    bool folded = true;
    std::array<sh::float4, 4> quad{};
    std::array<sh::float4, 4> band{};
};

/// ground.slang's thin_of for the record `r` (THIN_WORDS words).
Drawn thin_of(const sh::FrameCamera& c, const sh::TilePlace& t, std::span<const std::uint32_t> r, float y) {
    const sh::float2 a = {sh::asfloat(r[0]), sh::asfloat(r[1])};
    const sh::float2 b = {sh::asfloat(r[2]), sh::asfloat(r[3])};
    const std::uint32_t widths = r[4];
    const sh::float2 e = b - a;
    const sh::float2 left = {-e.y, e.x};
    const sh::float2 n = left / std::sqrt(sh::dot(e, e));
    const sh::float2 fa = a + (n * (static_cast<float>(widths & 0xFFFFU) / thin_units));
    const sh::float2 fb = b + (n * (static_cast<float>(widths >> 16U) / thin_units));
    Drawn out;
    out.pa = thin_px(c, t, y, a, fa);
    out.pb = thin_px(c, t, y, b, fb);
    if (!(sh::min(out.pa, out.pb) < least_px)) return out;
    out.folded = false;
    for (std::size_t q = 0; q < 4; ++q) {
        const bool at_b = q == 1 || q == 2;
        const sh::float2 w = at_b ? b : a;
        const sh::float2 wm = at_b ? fb : fa;
        const sh::float2 p = q >= 2 ? wm : w;
        const float px = at_b ? out.pb : out.pa;
        sh::float2 wide = p;
        if (px < least_px) {
            const sh::float2 mid = 0.5F * (w + wm);
            wide = mid + ((p - mid) * (least_px / sh::max(px, 1.0e-4F)));
        }
        out.quad[q] = place(c, t, p, y);
        out.band[q] = place(c, t, wide, y);
    }
    return out;
}

/// Whether every corner lies beyond one side of the clip volume: x or y beyond ±w, or behind the eye.
bool beyond(const std::array<sh::float4, 4>& v) {
    const auto all = [&v](auto out) { return std::ranges::all_of(v, out); };
    return all([](const sh::float4& p) { return p.x < -p.w; }) || all([](const sh::float4& p) { return p.x > p.w; }) ||
           all([](const sh::float4& p) { return p.y < -p.w; }) || all([](const sh::float4& p) { return p.y > p.w; }) ||
           all([](const sh::float4& p) { return p.z < 0.0F; });
}

} // namespace

TEST_CASE("ground: a chunk of thin edges the frame's cull leaves out draws nothing") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the real packs are not checked here");
        return;
    }
    const std::array<Screen, 3> screens{Screen{.width = 412, .height = 915, .density = 2.625F, .focal = 790},
                                        Screen{.width = 384, .height = 832, .density = 3.5F, .focal = 1150},
                                        Screen{.width = 800, .height = 1280, .density = 2.0F, .focal = 600}};
    const std::array<double, 6> heights{150, 430, 1000, 2500, 6000, 15000};
    const std::array<double, 4> pitches{0.0, 0.5, 0.9, 1.15};
    const std::array<float, 4> scales{1.0F, 1.0F, 0.83F, 1.21F};
    atlas::test::SplitMix64 random(0x7417C011ULL);
    const auto unit = [&random] { return static_cast<double>(random.next() >> 11U) * 0x1.0p-53; };
    std::uint64_t seen = 0;
    std::uint64_t drawn = 0;
    std::uint64_t culled = 0;
    std::uint64_t bad = 0;
    std::size_t tiles = 0;
    std::size_t cameras = 0;
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack((std::filesystem::path(dir) / (std::string(name) + ".atlas")).string());
        ATLAS_REQUIRE(pack.has_value(), "the pack opens");
        const tl::RenderHead& head = *pack->head();
        ATLAS_REQUIRE(pack->style() != nullptr);
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        atlas::Arena arena(4U << 20U, atlas::Growth::grows, "thin cull test");
        for (const tl::ChunkRef& c : pack->chunks().level(head.levels.front())) {
            arena.reset();
            auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile.has_value(), "the tile decodes");
            const geo::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
            const geo::GroundTile g = geo::build_ground(*tile, head, *pack->style(), 7, origin);
            const geo::GroundGpu gpu = geo::pack(g);
            if (gpu.thin.count == 0) continue;
            ++tiles;
            const auto record = [&gpu](std::uint32_t i) {
                return std::span<const std::uint32_t>(gpu.ground)
                    .subspan(gpu.thin.first + (geo::thin_words * i), geo::thin_words);
            };
            const auto level = [&gpu](std::uint32_t fill) {
                return sh::asfloat(gpu.ground[gpu.fills.first + (5 * fill)]);
            };
            // the chunks hold the stretches in their order, with their pieces' pairs, levels and least width
            std::uint32_t next = 0;
            std::size_t off = 0;
            for (const geo::ThinChunk& k : gpu.thin_chunks) {
                if (k.first != next || k.count == 0 || k.count > geo::thin_chunk) ++off;
                next = k.first + k.count;
                for (std::uint32_t i = k.first; i < next && i < gpu.thin.count; ++i) {
                    const std::span<const std::uint32_t> r = record(i);
                    const auto y = static_cast<double>(level(r[5]));
                    const double wa = static_cast<double>(r[4] & 0xFFFFU) / geo::thin_units;
                    const double wb = static_cast<double>(r[4] >> 16U) / geo::thin_units;
                    const std::array<std::uint32_t, 2> pair{r[5], r[6]};
                    const std::span<const std::array<std::uint32_t, 2>> named(
                        k.pair.data(), std::min<std::size_t>(k.pairs, geo::thin_pairs));
                    const bool held = k.y0 <= y && y <= k.y1 && k.least <= pm::min(wa, wb) &&
                                      (k.pairs > geo::thin_pairs || std::ranges::find(named, pair) != named.end());
                    if (!held) ++off;
                }
            }
            CHECK(next == gpu.thin.count);
            CHECK(off == 0);
            // the eye's foot about the tile's middle, as far off as its view reaches
            const double mid_x = 0.5 * (g.far_corner.x - g.origin.x);
            const double mid_z = 0.5 * (g.far_corner.z - g.origin.z);
            const double size = pm::max(std::abs(g.far_corner.x - g.origin.x), std::abs(g.far_corner.z - g.origin.z));
            for (const double h : heights) {
                for (const double pitch : pitches) {
                    for (int n = 0; n < 2; ++n) {
                        ++cameras;
                        const double reach = size + (h * (1.0 + (2.0 * pm::tan(pitch))));
                        const double ex = mid_x + ((2.0 * unit() - 1.0) * reach);
                        const double ez = mid_z + ((2.0 * unit() - 1.0) * reach);
                        const double heading = 2.0 * std::numbers::pi * unit();
                        const bool rest = (random.next() & 1U) != 0;
                        const bool weather = (random.next() & 1U) != 0;
                        const Screen& screen = screens[random.below(3)];
                        const float sx = scales[random.below(4)];
                        const sh::FrameCamera cam = frame_camera(screen, pitch, heading, 0.4 + (0.2 * unit()), rest);
                        sh::TilePlace place{};
                        const sh::float4 offset = {static_cast<float>(-ex * static_cast<double>(sx)),
                                                   static_cast<float>(-h), static_cast<float>(-ez), 0.0F};
                        const sh::float4 scale = {sx, 1.0F, 1.0F, 1.0F};
                        place.offset = offset;
                        place.scale = scale;
                        const atlas::passes::ThinView view(cam);
                        for (const geo::ThinChunk& k : gpu.thin_chunks) {
                            const bool left_out =
                                atlas::passes::thin_folded(k, place, view, weather ? k.y0 : 0.0, weather ? k.y1 : 0.0);
                            for (std::uint32_t i = k.first; i < k.first + k.count; ++i) {
                                const std::span<const std::uint32_t> r = record(i);
                                const Drawn d = thin_of(cam, place, r, weather ? level(r[5]) : 0.0F);
                                const bool draws = !d.folded && (!beyond(d.quad) || !beyond(d.band));
                                ++seen;
                                if (draws) ++drawn;
                                if (!left_out) continue;
                                ++culled;
                                if (!draws) continue;
                                if (++bad <= 5) {
                                    MESSAGE(name << " tile " << tiles << ": stretch " << i << " left out, but drawn at "
                                                 << d.pa << " and " << d.pb << " px from " << h << " m, pitch " << pitch
                                                 << ", heading " << heading << ", sx " << sx
                                                 << (rest ? ", at rest" : "") << (weather ? ", with weather" : "")
                                                 << "; its chunk's box x " << k.x0 << " to " << k.x1 << ", z " << k.z0
                                                 << " to " << k.z1 << ", least " << k.least);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    const auto needless = static_cast<double>(seen - drawn);
    MESSAGE(tiles << " tiles, " << cameras << " cameras: " << seen << " stretches seen, " << drawn
                  << " drawn (unfolded, on the screen); the cull left out " << culled << " of the " << (seen - drawn)
                  << " the vertex stage folds or draws off the screen ("
                  << (needless > 0 ? 100.0 * static_cast<double>(culled) / needless : 0.0) << " %), " << bad
                  << " it draws");
    CHECK(tiles > 0);
    CHECK(bad == 0);
}
