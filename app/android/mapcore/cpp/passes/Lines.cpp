// The ground's lines (passes/Ground.hpp; plan-vector D10 "Roads: thin edges by exact area", "Region overlays", "Tram
// tracks", "Bridges ... lines without [weather]"; D5.12's thin strokes and lines): draw_over's rails (two at .7), a big
// road's edge lines and lane lines (px(0.18) wide where that is over 0.3, render/regions.py:551), the tram tracks (.9,
// amsterdam.py:715), a bridge's lines (1.2, by day without weather, amsterdam.py:691) and a sett road's courses (.4 at
// opacity .6, by day not at eye level, amsterdam.py:702), each the port's path: its polylines' runs of points ahead of
// the near plane (line_d), butt caps, miter joins; a sett road's lines each where both its ends are, the path one layer
// (shaders/ground/setts.slangh).
//
// Each frame (on the render thread): every stroke of the visible cells is decided as the port decides it (draw_over's
// radius cut in engine mode, a big road's lines only with its asphalt and over 0.3 px, a bridge's only by day without
// weather) and takes its width (a fixed one, or px at its line's middle point, printed '%.2f' at rest); the drawn ones
// become runs (a polyline each, its tile, its points' first word in the lines heap, its height, width, colour and
// painter slot) and the runs' segments instances, in the renders' order: draw_over's in the over phase, the marks in
// theirs. One instanced draw each: every segment a quad whose coverage the line stage finds as RefRaster does
// (shaders/ground/ground.slang).
#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"
#include "twin/ground/LineShader.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <limits>
#include <optional>
#include <utility>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

static_assert(geo::setts_back == shaders::SETTS_BACK, "stage T's reach and the line stage's look back alike");

namespace {

/// Whether a stroke draws in the over phase (draw_over's), else in the marks phase.
[[nodiscard]] bool over_phase(const geo::Stroke& s) noexcept {
    return s.group >= geo::group::over && s.group < geo::group::marks;
}

[[nodiscard]] std::uint32_t rgba_of(const geo::Paint& paint, bool night) noexcept {
    return (night ? paint.night : paint.day).packed();
}

/// A dashed polyline's dash table (stroke_vs reads it): its dash and the pattern's period (CSS px), then each point's
/// place in the pattern (its distance along the line's points on the screen from its run's first, as the vertex stage
/// places them, modulo the period), each run of points ahead of the near plane starting the pattern again, as the
/// SVG's dashes start again with each subpath.
void dash_table(const TwinPlacer& placer, const DrawnTile& t, std::span<const geo::P2> line, double y,
                const std::array<double, 2>& dash, std::vector<std::uint32_t>& out) {
    const double period = dash[0] + dash[1];
    out.push_back(std::bit_cast<std::uint32_t>(static_cast<float>(dash[0])));
    out.push_back(std::bit_cast<std::uint32_t>(static_cast<float>(period)));
    std::optional<twin::Point> before;
    double along = 0.0;
    for (const geo::P2& p : line) {
        const std::optional<twin::Point> q = placer.at(t, p, y);
        if (q && before) {
            const double dx = static_cast<double>(q->x) - static_cast<double>(before->x);
            const double dy = static_cast<double>(q->y) - static_cast<double>(before->y);
            along = std::fmod(along + pm::hypot(dx, dy), period);
        } else {
            along = 0.0;
        }
        out.push_back(std::bit_cast<std::uint32_t>(static_cast<float>(along)));
        before = q;
    }
}

} // namespace

void Ground::decide_strokes(const view::Camera& cam, const DrawnTile& t, TileDecisions& d) const {
    const GroundFrame& f = frame_;
    const geo::GroundTile& g = *t.ground;
    d.stroke_drawn.assign(g.strokes.size(), 0);
    d.stroke_width.assign(g.strokes.size(), 0.0);
    for (std::uint32_t i = 0; i < g.strokes.size(); ++i) {
        const geo::Stroke& s = g.strokes[i];
        if ((t.cells & (std::uint64_t{1} << s.cell)) == 0) continue;
        if (s.kind == geo::StrokeKind::bridge && (f.weather || f.night)) continue;
        // a sett road's courses: `not night and not eye and cam.depth((pts[0][0], 0, pts[0][1])) < min(500, sett_d)`
        if (s.kind == geo::StrokeKind::setts && (f.night || (f.weather && cam.spec().pitch < 10) ||
                                                 !(cam.depth({s.from.x, 0.0, s.from.z}) < geo::setts_depth))) {
            continue;
        }
        // draw_over's radius cut, in engine mode
        if (f.engine && s.line_count > 0 && !near_line(f, std::span(g.points).subspan(s.line, s.line_count), s.near)) {
            continue;
        }
        // a big road's lines: with its asphalt (`if not d: continue`)
        if (s.fill && (d.drawn[*s.fill] & drawn_bit::street) == 0) continue;
        double width = s.width.css;
        if (width == 0.0) {
            // regions.py's px(cam, w, p): max(0.25, w * focal / max(depth at the ground, 1))
            const view::Vec3 k = cam.cam({s.width.at.x, 0.0, s.width.at.z});
            width = pm::max(0.25, (s.width.metres * cam.focal()) / pm::max(k.z, 1.0));
            // a big road's lines: `if sw > 0.3`
            if ((s.kind == geo::StrokeKind::edges || s.kind == geo::StrokeKind::lanes) && !(width > 0.3)) continue;
            if (f.exact) width = printed(width, 2);
        }
        d.stroke_drawn[i] = 1;
        d.stroke_width[i] = width;
    }
}

gpu::Result<void> Ground::make_line_pipelines(const frame::PassContext& context, Pipelines& p) const {
    gpu::GraphicsDesc line = ground_desc(context, p, "ground lines", "stroke_vs", "stroke_fs");
    translucent(line);
    ATLAS_GPU_LET(l, frame::make_pipeline(context, line, frame::Overdraw::translucent));
    p.line = std::move(l);
    return {};
}

void Ground::write_lines(const frame::FrameState& state, std::vector<std::uint32_t>& words, std::uint32_t& runs,
                         std::array<std::uint32_t, 2>& instances) {
    // the drawn strokes in the renders' order: their phase, then their painter slot (ties as emitted); the coaster
    // tracks' shadows among draw_over's
    constexpr std::uint32_t none = std::numeric_limits<std::uint32_t>::max();
    struct Drawn {
        bool marks;
        std::uint32_t slot;
        std::uint32_t tile;
        std::uint32_t stroke;
        std::uint32_t shadow = none;
    };
    std::vector<Drawn> drawn;
    for (std::uint32_t t = 0; t < frame_.tiles.size(); ++t) {
        const DrawnTile& tile = frame_.tiles[t];
        const geo::GroundTile& g = *tile.ground;
        if (!tile.pass->ranges[1]) continue;
        for (std::uint32_t i = 0; i < g.strokes.size(); ++i) {
            if (frame_.stroke_drawn[tile.strokes + i] == 0) continue;
            const geo::Stroke& s = g.strokes[i];
            drawn.push_back(
                {.marks = !over_phase(s), .slot = slot(s.group, stroke_order(tile, i), s.sub), .tile = t, .stroke = i});
        }
    }
    for (std::uint32_t j = 0; j < frame_.track_shadows.size(); ++j) {
        const TrackShadow& e = frame_.track_shadows[j];
        if (!frame_.tiles[e.tile].pass->ranges[1]) continue;
        drawn.push_back(
            {.marks = false, .slot = slot(geo::group::over + 4, 1 + j, 0), .tile = e.tile, .stroke = 0, .shadow = j});
    }
    std::ranges::stable_sort(
        drawn, [](const Drawn& a, const Drawn& b) { return a.marks != b.marks ? b.marks : a.slot < b.slot; });
    // the runs: one per polyline, 8 words each; a dashed one's table after the segments
    runs = static_cast<std::uint32_t>(words.size());
    std::array<std::vector<std::uint32_t>, 2> segments;
    std::uint32_t run = 0;
    std::vector<std::uint32_t> tables;
    std::vector<std::pair<std::size_t, std::size_t>> dashed; // a dashed run's flags word, its table's first word
    const std::optional<TwinPlacer> placer =
        state.view ? std::optional<TwinPlacer>(std::in_place, state, state.view->camera()) : std::nullopt;
    // the woods' shadows' union is drawn between the over lines before its slot and those after
    const std::uint32_t woods_slot = slot(geo::group::over + 4, 0, 0);
    std::optional<std::uint32_t> woods_split;
    // the tracks' shadows in th['shadow'] at shadow_op · .9 as the SVG prints it
    const style::ThemeBlock& th = state.theme;
    const geo::Rgba shade{.r = th.shadow.r,
                          .g = th.shadow.g,
                          .b = th.shadow.b,
                          .a = static_cast<std::uint8_t>(std::lround(printed(th.shadow_opacity * 0.9, 2) * 255.0))};
    for (const Drawn& d : drawn) {
        if (!d.marks && !woods_split && d.slot > woods_slot) {
            woods_split = static_cast<std::uint32_t>(segments[0].size());
        }
        if (d.shadow != none) {
            // a coaster track's shadow: its points (x, z, height) moved by the sun's offset in the line stage; a post's
            // from its point's foot to its point moved; round-capped
            const TrackShadow& e = frame_.track_shadows[d.shadow];
            const DrawnTile& tile = frame_.tiles[d.tile];
            const geo::GroundTile& g = *tile.ground;
            const std::uint32_t polyline = g.tracks[e.track].polyline;
            const std::uint32_t n = e.post ? 1 : e.count - 1;
            if (n == 0 || n > 0x4000) continue;
            words.push_back(d.tile);
            words.push_back((tile.pass->ranges[1].offset / 4) + g.layout.points.first + g.polyline_words[polyline] +
                            (3 * e.first));
            words.push_back(e.count);
            words.push_back(std::bit_cast<std::uint32_t>(static_cast<float>(g.polylines[polyline].y)));
            words.push_back(std::bit_cast<std::uint32_t>(static_cast<float>(e.width)));
            words.push_back(shade.packed());
            words.push_back(d.slot);
            words.push_back(shaders::LINE_SHADOW | shaders::LINE_CAP_ROUND | (e.post ? shaders::LINE_POST : 0U));
            for (std::uint32_t k = 0; k < n; ++k) segments[0].push_back((run << 14U) | k);
            ++run;
            continue;
        }
        const DrawnTile& tile = frame_.tiles[d.tile];
        const geo::GroundTile& g = *tile.ground;
        const geo::Stroke& s = g.strokes[d.stroke];
        const std::uint32_t base = (tile.pass->ranges[1].offset / 4) + g.layout.points.first;
        const auto width = static_cast<float>(frame_.stroke_width[tile.strokes + d.stroke]);
        for (std::uint32_t p = s.first; p < s.first + s.count; ++p) {
            const geo::Polyline& pl = g.polylines[p];
            // its segments: a polyline's between its points, a pair polyline's lines
            const std::uint32_t n = pl.pairs ? pl.count / 2 : (pl.count < 2 ? 0 : pl.count - 1);
            if (n == 0 || n > 0x4000) continue;
            words.push_back(d.tile);
            words.push_back(base + g.polyline_words[p]);
            words.push_back(pl.count);
            words.push_back(std::bit_cast<std::uint32_t>(static_cast<float>(pl.y)));
            words.push_back(std::bit_cast<std::uint32_t>(width));
            words.push_back(rgba_of(s.paint, frame_.night));
            words.push_back(d.slot);
            // butt caps, miter joins (limit 4): the SVG's defaults; a sett road's courses: lines of their own, the
            // path's opacity a layer (the paint's alpha); a park's edge dashed along the screen
            std::uint32_t flags = pl.pairs ? shaders::LINE_PAIRS | shaders::LINE_LAYER : 0U;
            if (s.dash[0] > 0 && placer) {
                flags |= shaders::LINE_DASH;
                dashed.emplace_back(words.size(), tables.size());
                dash_table(*placer, tile, std::span(g.points).subspan(pl.first, pl.count), pl.y, s.dash, tables);
            }
            words.push_back(flags);
            for (std::uint32_t k = 0; k < n; ++k) segments[d.marks ? 1U : 0U].push_back((run << 14U) | k);
            ++run;
        }
    }
    words_->woods_split = woods_split.value_or(static_cast<std::uint32_t>(segments[0].size()));
    for (std::size_t phase = 0; phase < 2; ++phase) {
        instances[phase] = static_cast<std::uint32_t>(words.size());
        words.insert(words.end(), segments[phase].begin(), segments[phase].end());
        words_->instance_count[phase] = static_cast<std::uint32_t>(segments[phase].size());
    }
    // the dash tables, each run's flags naming its own
    const std::size_t base = words.size();
    words.insert(words.end(), tables.begin(), tables.end());
    for (const auto& [flags, first] : dashed) {
        ATLAS_ASSERT((base + first) < (std::size_t{1} << (32U - shaders::LINE_DASH_SHIFT)));
        words[flags] |= static_cast<std::uint32_t>(base + first) << shaders::LINE_DASH_SHIFT;
    }
}

void Ground::record_lines(const frame::Recording& r, std::uint32_t phase) {
    const std::size_t k = phase == phase_over ? 0 : 1;
    const Words& w = *words_;
    const std::uint32_t count = w.instance_count[k];
    // draw_over's woods' shadows lie among its lines (after the marine works, before the tracks' shadows)
    const bool woods = k == 0 && frame_.woods;
    if (count == 0 && !woods) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    const auto samples = static_cast<std::uint32_t>(r.frame.pass.spec().config.samples());
    pc.mode = {frame_.night ? ground_flag::night : 0U, 0U, w.instances[k], samples};
    // the moment's own sun, by which the tracks' shadows move
    pc.rows = {static_cast<float>(frame_.light[0]), static_cast<float>(frame_.light[1]),
               static_cast<float>(frame_.light[2]), 0.0F};
    const auto lines = [&](std::uint32_t first, std::uint32_t n) {
        if (n == 0) return;
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->line);
        cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
        cmd.draw(6, n, 0, first);
        r.counters.draw(k == 0 ? "ground over lines" : "ground marks", std::uint64_t{n} * 2, n);
    };
    const std::uint32_t split = woods ? std::min(w.woods_split, count) : count;
    lines(0, split);
    if (woods) record_woods(r);
    lines(split, count - split);
}

void Ground::emit_lines(const frame::FrameState& state, twin::Stream& out, std::int32_t above) const {
    if (!state.view) return;
    TwinPlacer placer(state, state.view->camera());
    std::vector<twin::Point> points;
    std::vector<std::uint32_t> ends;
    std::vector<twin::Subpath> subpaths;
    for (const DrawnTile& t : frame_.tiles) {
        const geo::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.strokes.size(); ++i) {
            if (frame_.stroke_drawn[t.strokes + i] == 0) continue;
            const geo::Stroke& s = g.strokes[i];
            points.clear();
            ends.clear();
            for (std::uint32_t p = s.first; p < s.first + s.count; ++p) {
                const geo::Polyline& pl = g.polylines[p];
                // draw_over's line_d runs; the tram tracks' and the bridges' points joined; a sett road's lines each
                // where both its ends project
                if (pl.pairs) {
                    for (std::uint32_t k = 0; k + 1 < pl.count; k += 2) {
                        placer.runs(t, std::span(g.points).subspan(pl.first + k, 2), pl.y, points, ends);
                    }
                } else if (s.kind == geo::StrokeKind::tram || s.kind == geo::StrokeKind::bridge) {
                    placer.joined(t, std::span(g.points).subspan(pl.first, pl.count), pl.y, points, ends);
                } else {
                    placer.runs(t, std::span(g.points).subspan(pl.first, pl.count), pl.y, points, ends);
                }
            }
            if (ends.empty()) continue; // `if pp.size > 1` / `if rl`: no run, no path
            subpaths.clear();
            std::uint32_t start = 0;
            for (const std::uint32_t e : ends) {
                subpaths.push_back(
                    {.points = std::span<const twin::Point>(points).subspan(start, e - start), .closed = false});
                start = e;
            }
            out.at(slot(s.group, stroke_order(t, i), s.sub), over_phase(s) ? above : out.root());
            twin::Style st;
            st.fill = twin::Paint{};
            const geo::Rgba c = frame_.night ? s.paint.night : s.paint.day;
            st.stroke =
                twin::Paint::colour(0xFF00'0000U | (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | c.b);
            st.stroke_width = static_cast<float>(frame_.stroke_width[t.strokes + i]);
            // a park's edge's stroke-dasharray
            const std::array<float, 2> dash{static_cast<float>(s.dash[0]), static_cast<float>(s.dash[1])};
            if (s.dash[0] > 0) st.dashes = dash;
            // a sett road's courses, a park's edge: the path at its opacity
            twin::Node node;
            node.opacity = static_cast<float>(s.opacity);
            out.path(st, subpaths, node);
        }
    }
}

} // namespace atlas::passes
