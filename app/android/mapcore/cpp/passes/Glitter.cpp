// The sun's glitter on the water (passes/Ground.hpp; ENGINE.md step 4, the water; render/amsterdam.py:509
// water_layer's glitter, R/Amsterdam.kt waterLayer): by day with the sun up and a clear sky (no th['sky_k']), where the
// water is drawn with weather, 260 glints about the point of the water that mirrors the sun to the eye (where the
// eye's ray down at the sun's elevation meets the water's plane, WATER_Y), when that point projects ahead, within 60 px
// of the picture's sides and above its foot: random.Random(17)'s glints spread down the picture from it, wider the
// lower they lie, each an ellipse at an opacity of its own, all of them in `<g fill="#FFF7E2"
// style="mix-blend-mode:screen">` inside the water's clip, after its tint (and its glints, K17, to come).
//
// On the GPU the group is one layer screened over what lies under it: each glint an instance at the glitter's slot,
// its fragment run per sample (sample-rate shading) taking the group's layer there, its alpha the union of every glint
// over the sample (its own and those whose boxes meet its: 1 - Π(1 - opacity)), and screening the group's colour at
// that alpha once. The first glint to reach a sample takes it: the stencil marks it taken (its w0 bit cleared; the
// water's clip needs only its count from the surface on). Where the device lacks sample-rate shading a fragment runs
// per pixel over its samples, and where glints overlap within a pixel the samples it takes share their mean (a named
// difference).
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"

#include <bit>
#include <cmath>
#include <cstdint>
#include <optional>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

namespace {

/// A glint's record in the frame's words (ground.slang's GLINT_WORDS): its ellipse and opacity, and where its list of
/// neighbours lies and how long it is.
constexpr std::uint32_t glint_words = 8;

/// How many glints water_layer scatters.
constexpr int glint_count = 260;

[[nodiscard]] std::uint32_t bits_of(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

} // namespace

void Ground::decide_glitter(const view::Camera& cam, const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.glints.clear();
    // `if not night and L[1] > 0 and not th.get('sky_k')`, inside the water's clip
    if (!f.weather || !f.water || !f.water_light || no_glitter_ || only_setts_ || f.night || f.light[1] <= 0 ||
        f.tiles.empty()) {
        return;
    }
    if (state.theme.has(style::theme_flag::overcast) && state.theme.sky_k != 0.0) return;
    // the point of the water that mirrors the sun: where the eye's ray down at the sun's elevation meets WATER_Y
    const double wy = f.tiles.front().ground->water_y;
    const view::Vec3 eye = cam.eye();
    const double t = (wy - eye.y) / (-f.light[1]);
    const std::optional<view::Vec2> p = cam.proj({eye.x + (f.light[0] * t), wy, eye.z + (f.light[2] * t)});
    const double w = cam.width();
    const double h = cam.height();
    if (!p) return;
    const bool reach = -60 < p->x && p->x < w + 60 && p->y < h;
    if (!reach) return;
    pm::PyRandom rnd(17);
    f.glints.reserve(glint_count);
    for (int i = 0; i < glint_count; ++i) {
        const double g = std::abs(rnd.gauss(0, 1));
        const double y = p->y + (((g * (h - p->y)) * 0.35) * pm::pow(rnd.random(), 0.5));
        const double spread = 6 + ((y - p->y) * 0.22);
        const double x = p->x + rnd.gauss(0, spread);
        const double ww = rnd.uniform(1.2, 4.5) * (1 + ((y - p->y) / 300));
        const double op = rnd.uniform(0.35, 1);
        const double ry = pm::max(0.3, ww * 0.18);
        f.glints.push_back(f.exact ? Glint{.cx = printed(x, 1),
                                           .cy = printed(y, 1),
                                           .rx = printed(ww, 1),
                                           .ry = printed(ry, 2),
                                           .opacity = printed(op, 2)}
                                   : Glint{.cx = x, .cy = y, .rx = ww, .ry = ry, .opacity = op});
    }
}

void Ground::write_glitter(std::vector<std::uint32_t>& words) {
    Words& w = *words_;
    const std::vector<Glint>& glints = frame_.glints;
    w.glints = static_cast<std::uint32_t>(words.size());
    w.glint_count = static_cast<std::uint32_t>(glints.size());
    // each glint's record, its neighbours' list after them all: the glints whose boxes meet its, so the first glint
    // over a sample finds every other over it
    const std::size_t lists = words.size() + (glints.size() * glint_words);
    w.glint_lists = static_cast<std::uint32_t>(lists);
    std::vector<std::uint32_t> neighbours;
    for (std::size_t i = 0; i < glints.size(); ++i) {
        const Glint& a = glints[i];
        const auto first = static_cast<std::uint32_t>(lists + neighbours.size());
        for (std::size_t j = 0; j < glints.size(); ++j) {
            const Glint& b = glints[j];
            if (j == i || std::abs(a.cx - b.cx) > a.rx + b.rx || std::abs(a.cy - b.cy) > a.ry + b.ry) continue;
            neighbours.push_back(static_cast<std::uint32_t>(j));
        }
        for (const double v : {a.cx, a.cy, a.rx, a.ry, a.opacity}) words.push_back(bits_of(static_cast<float>(v)));
        words.push_back(first);
        words.push_back(static_cast<std::uint32_t>(lists + neighbours.size()) - first);
        words.push_back(0U);
    }
    words.insert(words.end(), neighbours.begin(), neighbours.end());
}

void Ground::record_glitter(const frame::Recording& r) {
    const Words& w = *words_;
    if (w.glint_count == 0 || !*pipelines_->glitter) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const tiles::ReflectionsStyle& rs = frame_.tiles.front().ground->reflection_style;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->glitter);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    const auto samples = static_cast<std::uint32_t>(r.frame.pass.spec().config.samples());
    pc.mode = {0U, slot(geo::group::water_top, 0, geo::water_sub::glitter), w.glints, samples};
    const geo::Rgba s{.r = static_cast<std::uint8_t>((rs.glitter >> 16U) & 0xFFU),
                      .g = static_cast<std::uint8_t>((rs.glitter >> 8U) & 0xFFU),
                      .b = static_cast<std::uint8_t>(rs.glitter & 0xFFU),
                      .a = 255};
    pc.colour = {s.packed(), 0U, 0U, 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(6, w.glint_count, 0, 0);
    r.counters.draw("ground glitter", std::uint64_t{w.glint_count} * 2, w.glint_count);
}

void Ground::emit_glitter(const frame::FrameState& state, twin::Stream& out, std::int32_t water) const {
    const GroundFrame& f = frame_;
    if (f.glints.empty() || !state.view) return;
    const tiles::ReflectionsStyle& rs = f.tiles.front().ground->reflection_style;
    const std::int32_t group = out.group({.node = {.blend = twin::Blend::screen}, .parent = water});
    out.at(slot(geo::group::water_top, 0, geo::water_sub::glitter), group);
    twin::Style s;
    s.fill = twin::Paint::colour(0xFF00'0000U | rs.glitter);
    for (const Glint& g : f.glints) {
        out.ellipse(s, static_cast<float>(g.cx), static_cast<float>(g.cy), static_cast<float>(g.rx),
                    static_cast<float>(g.ry), {.opacity = static_cast<float>(g.opacity)});
    }
}

} // namespace atlas::passes
