// The drizzle on the canals (passes/Ground.hpp; ENGINE.md step 4, the water; render/amsterdam.py:521 water_layer's
// rings, R/Amsterdam.kt waterLayer): with th['wet'], where the water is drawn with weather, a pattern of 28 rings
// scattered at random over a tile of 173 × 97 px (random.Random(9): their radii, then each ring's place and opacity;
// each an ellipse stroked in #A9B8D8, .4 wide, its height 0.36 of its width), filling a rect over the frame at .55
// inside the water's clip, after its glitter; at eye level a rect per glint band instead, the pattern scaled there by
// the band's distance (scale(k, k·f), k = min(4, max(.25, 14 / z)), f = min(1, (eh / z) / .36), moved 37 px a band;
// none where k·f < .08).
//
// On the GPU one triangle over the frame in the water's clip, at the rings' slot: at each sample of its band its place
// in the tile (the band's transform undone, the tile repeated), the rings over it there (the tile's grid lists the
// rings whose strokes reach each cell; a ring's stroke is the points within .2 of its ellipse, measured exactly), their
// layer (1 - Π(1 - opacity): one colour, painted in order) at the rect's opacity. The fragment runs per pixel over its
// samples and gives those the rings reach their mean, which is the samples' own over a backdrop one colour across the
// pixel.
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <format>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

namespace {

/// The pattern's tile (CSS px), how many rings it holds, and the grid its rings are filed in.
constexpr double tile_w = 173.0;
constexpr double tile_h = 97.0;
constexpr int ring_count = 28;
constexpr std::uint32_t grid_x = 16;
constexpr std::uint32_t grid_y = 8;

/// A ring of the tile as the pattern prints it.
struct Ring {
    double cx;
    double cy;
    double rx;
    double ry;
    double opacity;
};

/// The tile's rings, once: random.Random(9)'s 28 radii first, then each ring's centre and opacity ('%.1f', '%.1f',
/// '%.1f', '%.2f', '%.2f').
[[nodiscard]] const std::array<Ring, ring_count>& tile_rings() {
    static const std::array<Ring, ring_count> rings = [] {
        pm::PyRandom rnd(9);
        std::array<double, ring_count> radii{};
        for (double& r : radii) r = rnd.uniform(0.8, 2.4);
        std::array<Ring, ring_count> out{};
        for (std::size_t i = 0; i < out.size(); ++i) {
            const double cx = rnd.uniform(0, tile_w);
            const double cy = rnd.uniform(0, tile_h);
            const double op = rnd.uniform(0.4, 1);
            out[i] = {.cx = printed(cx, 1),
                      .cy = printed(cy, 1),
                      .rx = printed(radii[i], 1),
                      .ry = printed(radii[i] * 0.36, 2),
                      .opacity = printed(op, 2)};
        }
        return out;
    }();
    return rings;
}

[[nodiscard]] std::uint32_t bits_of(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

} // namespace

void Ground::decide_rings(const view::Camera& cam, const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.ring_bands.clear();
    // `if th.get('wet')`, inside the water's clip
    if (!f.weather || !f.water || !f.water_light || no_rings_ || only_setts_ || f.tiles.empty()) return;
    if (!state.theme.has(style::theme_flag::wet)) return;
    const double h = cam.height();
    const double hy = cam.horizon_y();
    const double eh = cam.eye().y - f.tiles.front().ground->water_y;
    // the glints' bands at eye level: long and broad near the eye, thin toward the horizon
    const bool eye = cam.forward().y > -0.25 && 0 < hy && hy < h;
    if (!eye) {
        f.ring_bands.push_back({.y0 = 0, .y1 = h, .tx = 0, .kx = 1, .ky = 1, .index = -1});
        return;
    }
    double z = 4.0;
    double yb = h;
    std::int32_t band = 0;
    while (yb > hy + 1) {
        const double z1 = z * 1.45;
        const double y0 = z1 < 3000 ? hy + ((cam.focal() * eh) / z1) : hy;
        const double y1 = pm::min(h, hy + ((cam.focal() * eh) / z));
        if (y1 > y0 + 0.5) {
            const double top = pm::max(hy, y0);
            const double zz = z * 1.2;
            const double k = pm::min(4.0, pm::max(0.25, 14.0 / zz));
            const double fk = pm::min(1.0, (eh / zz) / 0.36);
            if (k * fk >= 0.08) {
                // translate(37 i 0) scale('%.3f' k, '%.3f' k · f); the rect at '%.1f' rows
                const double y0p = printed(top, 1);
                f.ring_bands.push_back({.y0 = y0p,
                                        .y1 = y0p + printed(y1 - top, 1),
                                        .tx = 37.0 * static_cast<double>(band),
                                        .kx = printed(k, 3),
                                        .ky = printed(k * fk, 3),
                                        .index = band});
            }
            ++band;
        }
        yb = y0;
        z = z1;
        if (z > 2500) break;
    }
}

void Ground::write_rings(std::vector<std::uint32_t>& words) {
    Words& w = *words_;
    w.rings = static_cast<std::uint32_t>(words.size());
    w.ring_bands = static_cast<std::uint32_t>(frame_.ring_bands.size());
    if (frame_.ring_bands.empty()) return;
    const std::array<Ring, ring_count>& rings = tile_rings();
    const double hw = frame_.tiles.front().ground->drizzle_style.width / 2;
    // the header: the rings, the grid, the bands, and where each lies
    const auto base = static_cast<std::uint32_t>(words.size());
    const std::uint32_t ring_at = base + 8;
    const std::uint32_t cell_at = ring_at + (ring_count * 8);
    const std::uint32_t list_at = cell_at + (grid_x * grid_y * 2);
    std::vector<std::vector<std::uint32_t>> cells(std::size_t{grid_x} * grid_y);
    for (std::uint32_t i = 0; i < ring_count; ++i) {
        const Ring& r = rings[i];
        // the cells its stroke reaches within the tile (the tile clips what lies beyond it)
        const double x0 = std::clamp(r.cx - r.rx - hw, 0.0, tile_w);
        const double x1 = std::clamp(r.cx + r.rx + hw, 0.0, tile_w);
        const double y0 = std::clamp(r.cy - r.ry - hw, 0.0, tile_h);
        const double y1 = std::clamp(r.cy + r.ry + hw, 0.0, tile_h);
        const auto cell_of = [](double v, double side, std::uint32_t n) {
            return std::min(n - 1, static_cast<std::uint32_t>(std::floor(v / (side / static_cast<double>(n)))));
        };
        for (std::uint32_t cy = cell_of(y0, tile_h, grid_y); cy <= cell_of(y1, tile_h, grid_y); ++cy) {
            for (std::uint32_t cx = cell_of(x0, tile_w, grid_x); cx <= cell_of(x1, tile_w, grid_x); ++cx) {
                cells[(std::size_t{cy} * grid_x) + cx].push_back(i);
            }
        }
    }
    std::size_t listed = 0;
    for (const auto& c : cells) listed += c.size();
    const auto band_at = static_cast<std::uint32_t>(list_at + listed);
    for (const std::uint32_t v :
         {static_cast<std::uint32_t>(ring_count), grid_x, grid_y, w.ring_bands, ring_at, cell_at, list_at, band_at}) {
        words.push_back(v);
    }
    for (const Ring& r : rings) {
        for (const double v : {r.cx, r.cy, r.rx, r.ry, r.opacity}) words.push_back(bits_of(static_cast<float>(v)));
        words.insert(words.end(), 3, 0U);
    }
    std::uint32_t next = list_at;
    for (const auto& c : cells) {
        words.push_back(next);
        words.push_back(static_cast<std::uint32_t>(c.size()));
        next += static_cast<std::uint32_t>(c.size());
    }
    for (const auto& c : cells) words.insert(words.end(), c.begin(), c.end());
    for (const RingBand& b : frame_.ring_bands) {
        for (const double v : {b.y0, b.y1, b.tx, b.kx, b.ky}) words.push_back(bits_of(static_cast<float>(v)));
        words.insert(words.end(), 3, 0U);
    }
}

void Ground::record_rings(const frame::Recording& r) {
    const Words& w = *words_;
    if (w.ring_bands == 0 || !*pipelines_->rings) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const tiles::DrizzleStyle& d = frame_.tiles.front().ground->drizzle_style;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->rings);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    const auto samples = static_cast<std::uint32_t>(r.frame.pass.spec().config.samples());
    pc.mode = {0U, slot(geo::group::water_top, 0, geo::water_sub::rings), w.rings, samples};
    // the stroke's half width in the pattern's units, the rect's opacity
    pc.rows = {static_cast<float>(d.width / 2), static_cast<float>(d.opacity), 0.0F, 0.0F};
    const geo::Rgba c{.r = static_cast<std::uint8_t>((d.colour >> 16U) & 0xFFU),
                      .g = static_cast<std::uint8_t>((d.colour >> 8U) & 0xFFU),
                      .b = static_cast<std::uint8_t>(d.colour & 0xFFU),
                      .a = 255};
    pc.colour = {c.packed(), 0U, 0U, 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground rings", 1);
}

void Ground::emit_rings(const frame::FrameState& state, twin::Stream& out, std::int32_t water) const {
    const GroundFrame& f = frame_;
    if (f.ring_bands.empty() || !state.view) return;
    const tiles::DrizzleStyle& d = f.tiles.front().ground->drizzle_style;
    const auto w = static_cast<float>(state.view->camera().width());
    const std::int32_t rings = out.pattern("rings");
    out.at(slot(geo::group::water_top, 0, geo::water_sub::rings), water);
    for (const RingBand& b : f.ring_bands) {
        twin::Style s;
        s.fill = twin::Paint::pattern(b.index < 0 ? rings : out.pattern(std::format("rings{}", b.index)));
        out.rect(s, 0.0F, static_cast<float>(b.y0), w, static_cast<float>(b.y1 - b.y0), 0.0F, 0.0F,
                 {.opacity = static_cast<float>(d.opacity)});
    }
}

} // namespace atlas::passes
