// The quay walls (passes/Ground.hpp; ENGINE.md step 4, the water; render/amsterdam.py:534 water_layer's quay walls,
// R/Amsterdam.kt waterLayer): with weather, every water edge that carries a wall, in water_edges' order, drawn after
// the ground's shadows (render's quay_svg) where it faces the camera, its edge's midpoint within the radius cut, in the
// pieces the sun's scene splits it into outside every bridge's deck (sun::QuayPiece: the decks of each tile at its
// level, as the port's per-view scene has them; a tile without its bits yet draws its edges whole). Each piece: its
// wall from the water's level + 0.35 m to the street in the region's QUAY_COL, lit where the moment's sun is on its
// face and its sample says (K8's quay samples), its own .3 stroke edging it; a tall wall in sun (TONE, water deeper
// than 2 m) in columns of about 5 m, three bands high, one path of the lit bands and one of the unlit; the dark band at
// its waterline; its coping, lit as the wall. Each as face() keeps it: Camera.poly at 300 px of margin, 0.12 px² of
// area at least (a band's over 0.03). Eye-level courses (CU.coursing) are still to come.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"
#include "style/Light.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <span>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

bool beyond_radius(double x, double z, double tx, double tz, double radius) noexcept {
    const double dx = x - tx;
    const double dz = z - tz;
    const double d2 = (dx * dx) + (dz * dz);
    const double r2 = radius * radius;
    if (d2 < r2 * (1 - 1e-9)) return false;
    if (d2 > r2 * (1 + 1e-9)) return true;
    return pm::hypot(dx, dz) > radius;
}

bool face_kept(const view::Camera& cam, std::span<const view::Vec3> q, double margin, double least, bool strictly,
               std::vector<view::Vec3>& cs) {
    cs.clear();
    const double near = cam.near();
    const view::Vec3 first = cam.cam(q[0]);
    view::Vec3 a = first;
    for (std::size_t i = 1; i < q.size(); ++i) {
        const view::Vec3 b = cam.cam(q[i]);
        ProjectedPoly::clip_edge(a, b, near, cs);
        a = b;
    }
    ProjectedPoly::clip_edge(a, first, near, cs);
    if (cs.size() < 3) return false;
    double min_x = 0;
    double max_x = 0;
    double min_y = 0;
    double max_y = 0;
    double sum = 0;
    std::vector<view::Vec2> sp;
    sp.reserve(cs.size());
    for (const view::Vec3& k : cs) sp.push_back(cam.pc(k));
    for (std::size_t i = 0; i < sp.size(); ++i) {
        const view::Vec2& p = sp[i];
        if (i == 0) {
            min_x = max_x = p.x;
            min_y = max_y = p.y;
        } else {
            min_x = pm::min(min_x, p.x);
            max_x = pm::max(max_x, p.x);
            min_y = pm::min(min_y, p.y);
            max_y = pm::max(max_y, p.y);
        }
        const view::Vec2& r = sp[(i + 1) % sp.size()];
        sum += (p.x * r.y) - (r.x * p.y);
    }
    if (max_x < -margin || min_x > cam.width() + margin || max_y < -margin || min_y > cam.height() + margin) {
        return false;
    }
    const double area = std::abs(sum) / 2;
    return strictly ? area > least : area >= least;
}

bool sun_bit(const DrawnTile& t, std::uint32_t i) noexcept {
    if (!t.sun || t.sun->now == nullptr || i >= t.sun->now->count) return false;
    return t.sun->now->bit(i);
}

namespace {

/// The light of a quay's face (light.slang's light and tohex in binary64, the port's bits), 0xRRGGBB.
[[nodiscard]] std::uint32_t lit_colour(const shaders::Theme& theme, tiles::StyleRgb base, double nx, double nz,
                                       bool lit) {
    const shaders::double3 rgb{static_cast<double>((base >> 16U) & 0xFFU), static_cast<double>((base >> 8U) & 0xFFU),
                               static_cast<double>(base & 0xFFU)};
    const hlsl::uint3 c = shaders::light_bytes(theme, rgb, shaders::double3{nx, 0.0, nz}, lit);
    return (c.x << 16U) | (c.y << 8U) | c.z;
}

/// A piece's band (column j of `columns`, band m of three): its corners as water_layer's TONE loop takes them.
[[nodiscard]] std::array<view::Vec3, 4> band_of(const QuayFace& f, double wy, std::uint32_t j, std::uint32_t m) {
    const auto k = static_cast<double>(f.columns);
    const auto jj = static_cast<double>(j);
    const double pax = f.ax + (((f.bx - f.ax) * jj) / k);
    const double paz = f.az + (((f.bz - f.az) * jj) / k);
    const double pbx = f.ax + (((f.bx - f.ax) * (jj + 1)) / k);
    const double pbz = f.az + (((f.bz - f.az) * (jj + 1)) / k);
    constexpr double nb = 3;
    const double y0 = (wy + 0.35) - (((wy + 0.35) * static_cast<double>(m)) / nb);
    const double y1 = (wy + 0.35) - (((wy + 0.35) * static_cast<double>(m + 1)) / nb);
    return {view::Vec3{pax, y0, paz}, view::Vec3{pbx, y0, pbz}, view::Vec3{pbx, y1, pbz}, view::Vec3{pax, y1, paz}};
}

/// A face's corners by its kind (a bands' path has its bands' own).
[[nodiscard]] std::array<view::Vec3, 4> face_of(const QuayFace& f, double wy) {
    switch (f.kind) {
    case QuayFace::Kind::waterline:
        return {view::Vec3{f.ax, wy, f.az}, view::Vec3{f.bx, wy, f.bz}, view::Vec3{f.bx, wy + 0.35, f.bz},
                view::Vec3{f.ax, wy + 0.35, f.az}};
    case QuayFace::Kind::coping:
        return {view::Vec3{f.ax, -0.18, f.az}, view::Vec3{f.bx, -0.18, f.bz}, view::Vec3{f.bx, 0.02, f.bz},
                view::Vec3{f.ax, 0.02, f.az}};
    default:
        return {view::Vec3{f.ax, wy + 0.35, f.az}, view::Vec3{f.bx, wy + 0.35, f.bz}, view::Vec3{f.bx, 0.0, f.bz},
                view::Vec3{f.ax, 0.0, f.az}};
    }
}

} // namespace

void Ground::decide_quays(const view::Camera& cam, const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.quays.clear();
    // `if th.get('wx')`: without weather the water is flat and has no walls
    if (!f.weather || no_quays_ || f.out(style::camera_part::quays)) return;
    const shaders::Theme& theme = state.block.theme;
    const std::array<double, 3>& light = f.light;
    // every piece of the drawn tiles, in water_edges' order: the sun's pieces where its bits have come, else each
    // edge whole and unlit
    struct Piece {
        std::uint32_t tile;
        std::uint32_t edge;
        std::uint32_t index;
        std::uint32_t order;
        double ax;
        double az;
        double bx;
        double bz;
        std::uint32_t sample;
        std::uint32_t bands;
        std::uint32_t columns;
        bool sampled;
    };
    std::vector<Piece> pieces;
    for (std::uint32_t ti = 0; ti < f.tiles.size(); ++ti) {
        const DrawnTile& t = f.tiles[ti];
        const geo::GroundTile& g = *t.ground;
        if (g.quays.empty()) continue;
        const auto* quays = t.sun && t.sun->now != nullptr ? t.sun->now->quays.get() : nullptr;
        if (quays != nullptr) {
            for (std::uint32_t k = 0; k < quays->size(); ++k) {
                const sun::QuayPiece& q = (*quays)[k];
                if (q.record >= g.quays.size() || !g.quays[q.record].drawn) continue;
                pieces.push_back({.tile = ti,
                                  .edge = q.record,
                                  .index = g.quays[q.record].index,
                                  .order = k,
                                  .ax = q.ax,
                                  .az = q.az,
                                  .bx = q.bx,
                                  .bz = q.bz,
                                  .sample = q.sample,
                                  .bands = q.bands,
                                  .columns = q.columns,
                                  .sampled = true});
            }
            continue;
        }
        for (std::uint32_t e = 0; e < g.quays.size(); ++e) {
            const geo::QuayEdge& q = g.quays[e];
            if (!q.drawn) continue;
            pieces.push_back({.tile = ti,
                              .edge = e,
                              .index = q.index,
                              .order = e,
                              .ax = q.a.x,
                              .az = q.a.z,
                              .bx = q.b.x,
                              .bz = q.b.z,
                              .sample = 0,
                              .bands = 0,
                              .columns = 0,
                              .sampled = false});
        }
    }
    std::ranges::stable_sort(pieces, [](const Piece& a, const Piece& b) {
        return a.index != b.index ? a.index < b.index : a.order < b.order;
    });
    const view::Vec3 eye = cam.eye();
    std::vector<view::Vec3> cs;
    for (const Piece& p : pieces) {
        const DrawnTile& t = f.tiles[p.tile];
        const geo::GroundTile& g = *t.ground;
        const geo::QuayEdge& q = g.quays[p.edge];
        // `if far(mx, mz): continue`, at the edge's midpoint
        if (f.engine && beyond_radius((q.a.x + q.b.x) / 2, (q.a.z + q.b.z) / 2, f.target_x, f.target_z, f.radius))
            continue;
        const double wy = q.wy;
        const double mx = (p.ax + p.bx) / 2;
        const double mz = (p.az + p.bz) / 2;
        // the wall faces the camera: vdot(n3, vsub(cam.C, (mx, WY / 2, mz))) > 0
        if ((q.nx * (eye.x - mx)) + (q.nz * (eye.z - mz)) <= 0) continue;
        const bool toward = !f.night && ((q.nx * light[0]) + (q.nz * light[2])) > 0;
        const bool lit = toward && p.sampled && sun_bit(t, p.sample);
        const std::uint32_t wall_lit = lit_colour(theme, g.quay_colour, q.nx, q.nz, toward);
        const std::uint32_t wall_unlit = lit_colour(theme, g.quay_colour, q.nx, q.nz, false);
        QuayFace face{.kind = QuayFace::Kind::wall,
                      .tile = p.tile,
                      .edge = p.edge,
                      .ax = p.ax,
                      .az = p.az,
                      .bx = p.bx,
                      .bz = p.bz,
                      .bands = p.bands,
                      .columns = p.columns,
                      .colour = lit ? wall_lit : wall_unlit,
                      .lit = wall_lit,
                      .unlit = wall_unlit,
                      .sample = p.sample,
                      .sampled = toward && p.sampled};
        const auto wall = face_of(face, wy);
        const bool f1 = face_kept(cam, wall, 300, 0.12, false, cs);
        if (f1 && g.tone && toward && wy < -2 && p.sampled && p.columns > 0) {
            // a tall quay wall in sun: its lit bands' path, then its unlit ones'
            for (const bool want : {true, false}) {
                QuayFace b = face;
                b.kind = want ? QuayFace::Kind::lit_bands : QuayFace::Kind::dark_bands;
                b.colour = want ? wall_lit : wall_unlit;
                b.lit = wall_lit;
                b.unlit = wall_unlit;
                b.sampled = true;
                bool any = false;
                for (std::uint32_t j = 0; j < p.columns && !any; ++j) {
                    for (std::uint32_t m = 0; m < 3 && !any; ++m) {
                        if (sun_bit(t, p.bands + (3 * j) + m) != want) continue;
                        any = face_kept(cam, band_of(b, wy, j, m), 300, 0.03, true, cs);
                    }
                }
                if (any) f.quays.push_back(b);
            }
        } else if (f1) {
            f.quays.push_back(face);
        }
        QuayFace waterline = face;
        waterline.kind = QuayFace::Kind::waterline;
        waterline.colour = f.night ? g.quay_waterline.night : g.quay_waterline.day;
        waterline.lit = waterline.unlit = waterline.colour;
        waterline.sampled = false;
        if (face_kept(cam, face_of(waterline, wy), 300, 0.12, false, cs)) f.quays.push_back(waterline);
        QuayFace coping = face;
        coping.kind = QuayFace::Kind::coping;
        coping.lit = lit_colour(theme, g.quay_coping, q.nx, q.nz, toward);
        coping.unlit = lit_colour(theme, g.quay_coping, q.nx, q.nz, false);
        coping.colour = lit ? coping.lit : coping.unlit;
        if (face_kept(cam, face_of(coping, wy), 300, 0.12, false, cs)) f.quays.push_back(coping);
    }
}

double sun_amount(const DrawnTile& t, std::uint32_t i) noexcept {
    if (!t.sun || t.sun->now == nullptr || i >= t.sun->now->count) return 0.0;
    const bool now = t.sun->now->bit(i);
    const bool before = t.sun->before != nullptr && i < t.sun->before->count ? t.sun->before->bit(i) : now;
    return sun::lit_amount(before, now, t.sun->weight);
}

std::uint32_t lit_mix(std::uint32_t lit, std::uint32_t unlit, double k) noexcept {
    std::uint32_t out = 0xFF00'0000U;
    for (const std::uint32_t shift : {16U, 8U, 0U}) {
        const auto u = static_cast<double>((unlit >> shift) & 0xFFU);
        const auto l = static_cast<double>((lit >> shift) & 0xFFU);
        const auto c = static_cast<std::uint32_t>(std::lround(u + ((l - u) * k)));
        out |= std::min(c, 255U) << (16U - shift);
    }
    return out;
}

namespace {

[[nodiscard]] std::uint32_t bits_of(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// A face's record's flags (ground.slang's QUAY_STROKE): edged by its own .3 stroke.
constexpr std::uint32_t quay_stroke = 1;

} // namespace

void Ground::write_quays(std::vector<std::uint32_t>& words) {
    Words& w = *words_;
    w.quays = static_cast<std::uint32_t>(words.size());
    w.quay_count = 0;
    std::vector<view::Vec3> cs;
    const auto put = [&](std::uint32_t tile, std::uint32_t slot, std::uint32_t colour, bool stroke,
                         const std::array<view::Vec3, 4>& q, const geo::P2& origin) {
        words.push_back(tile);
        words.push_back(slot);
        words.push_back(colour);
        words.push_back(stroke ? quay_stroke : 0U);
        for (const view::Vec3& c : q) {
            words.push_back(bits_of(static_cast<float>(c.x - origin.x)));
            words.push_back(bits_of(static_cast<float>(c.y)));
            words.push_back(bits_of(static_cast<float>(c.z - origin.z)));
        }
        ++w.quay_count;
    };
    for (std::size_t j = 0; j < frame_.quays.size(); ++j) {
        const QuayFace& e = frame_.quays[j];
        const DrawnTile& t = frame_.tiles[e.tile];
        if (!t.pass->ranges[1] && !t.pass->ranges[0]) continue;
        const geo::GroundTile& g = *t.ground;
        const double wy = g.quays[e.edge].wy;
        const std::uint32_t slot_value = slot(geo::group::quay, static_cast<std::uint32_t>(j), 0);
        if (e.kind == QuayFace::Kind::lit_bands || e.kind == QuayFace::Kind::dark_bands) {
            const bool want = e.kind == QuayFace::Kind::lit_bands;
            for (std::uint32_t c = 0; c < e.columns; ++c) {
                for (std::uint32_t m = 0; m < 3; ++m) {
                    const std::uint32_t sample = e.bands + (3 * c) + m;
                    if (sun_bit(t, sample) != want) continue;
                    const auto q = band_of(e, wy, c, m);
                    put(e.tile, slot_value, lit_mix(e.lit, e.unlit, sun_amount(t, sample)), true, q, g.origin);
                }
            }
            continue;
        }
        const double k = e.sampled ? sun_amount(t, e.sample) : 0.0;
        const std::uint32_t colour =
            e.kind == QuayFace::Kind::waterline ? lit_mix(e.colour, e.colour, 0.0) : lit_mix(e.lit, e.unlit, k);
        put(e.tile, slot_value, colour, e.kind == QuayFace::Kind::wall, face_of(e, wy), g.origin);
    }
}

void Ground::record_quays(const frame::Recording& r) {
    const Words& w = *words_;
    if (w.quay_count == 0 || !*pipelines_->quay) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->quay);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    const auto samples = static_cast<std::uint32_t>(r.frame.pass.spec().config.samples());
    pc.mode = {frame_.night ? ground_flag::night : 0U, 0U, w.quays, samples};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(6, w.quay_count, 0, 0);
    r.counters.draw("ground quays", std::uint64_t{w.quay_count} * 2, w.quay_count);
}

void Ground::emit_quays(const frame::FrameState& state, twin::Stream& out) const {
    const GroundFrame& f = frame_;
    if (f.quays.empty() || !state.view) return;
    const view::Camera& cam = state.view->camera();
    TwinPlacer pr(state, cam);
    std::vector<twin::Point> points;
    std::vector<std::uint32_t> ends;
    std::vector<twin::Subpath> subpaths;
    std::vector<view::Vec3> cs;
    for (std::size_t j = 0; j < f.quays.size(); ++j) {
        const QuayFace& e = f.quays[j];
        const DrawnTile& t = f.tiles[e.tile];
        const double wy = t.ground->quays[e.edge].wy;
        points.clear();
        ends.clear();
        if (e.kind == QuayFace::Kind::lit_bands || e.kind == QuayFace::Kind::dark_bands) {
            const bool want = e.kind == QuayFace::Kind::lit_bands;
            for (std::uint32_t c = 0; c < e.columns; ++c) {
                for (std::uint32_t m = 0; m < 3; ++m) {
                    if (sun_bit(t, e.bands + (3 * c) + m) != want) continue;
                    const auto q = band_of(e, wy, c, m);
                    if (!face_kept(cam, q, 300, 0.03, true, cs)) continue;
                    if (pr.poly(t, q, points) >= 3) ends.push_back(static_cast<std::uint32_t>(points.size()));
                }
            }
        } else if (pr.poly(t, face_of(e, wy), points) >= 3) {
            ends.push_back(static_cast<std::uint32_t>(points.size()));
        }
        if (ends.empty()) continue;
        subpaths.clear();
        std::uint32_t start = 0;
        for (const std::uint32_t end : ends) {
            subpaths.push_back({.points = std::span<const twin::Point>(points).subspan(start, end - start)});
            start = end;
        }
        out.at(slot(geo::group::quay, static_cast<std::uint32_t>(j), 0), out.root());
        twin::Style s;
        s.fill = twin::Paint::colour(0xFF00'0000U | e.colour);
        // the wall and its bands edged by their own .3 stroke
        if (e.kind != QuayFace::Kind::waterline && e.kind != QuayFace::Kind::coping) {
            s.stroke = twin::Paint::colour(0xFF00'0000U | e.colour);
            s.stroke_width = 0.3F;
        }
        out.path(s, subpaths);
    }
}

} // namespace atlas::passes
