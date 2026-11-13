// The quay walls (passes/Ground.hpp; ENGINE.md step 4, the water; render/amsterdam.py:534 water_layer's quay walls,
// R/Amsterdam.kt waterLayer): with weather, every water edge that carries a wall, in water_edges' order, drawn after
// the ground's shadows (render's quay_svg) where it faces the camera, its edge's midpoint within the radius cut, in the
// pieces the sun's scene splits it into outside every bridge's deck (sun::QuayPiece: the decks of each tile at its
// level, as the port's per-view scene has them; a tile without its bits yet draws its edges whole). Each piece: its
// wall from the water's level + 0.35 m to the street in the region's QUAY_COL, lit where the moment's sun is on its
// face and its sample says (K8's quay samples), its own .3 stroke edging it; a tall wall in sun (TONE, water deeper
// than 2 m) in columns of about 5 m, three bands high, one path of the lit bands and one of the unlit; the dark band at
// its waterline; its coping, lit as the wall. Each as face() keeps it: Camera.poly at 300 px of margin, 0.12 px² of
// area at least (a band's over 0.03).
//
// At eye level (the gaze less than about 14.5° down: `cam.f[1] > -0.25`) by day, a wall that is drawn shows its
// courses (render/closeup.py's coursing, with the region's QUAY_COURSE: a brick's 6.25 cm, a cut stone's 45 cm in Paris
// and Bergen, none for concrete), after its wall and before its waterline: the piece in chunks of 5 m at most, each
// with a course 1.6 px high or more at its middle's depth a path of its bed joints (a line a course from the wall's
// foot, 0.38 m above the water, up to 0.2 m under the street, 12 mm before the wall, each clipped at the near plane
// and kept within 5 px of the frame), and for a cut stone its head joints staggered course by course; stroked
// max(.35, focal · .011 / depth) wide (.008 for stone) in #E2D9CB (#5E5548) at .3 (.26) of how far the course's
// height has come from 1.6 px toward 3.8. Each path is a slot among the quay's faces; its lines go through the line
// stage (shaders/ground/ground.slang's stroke_vs under GROUND_COURSES: their ends in the frame's words, at the near
// plane clipped as _seg clips them). Named difference: a cut stone's head joints blend over its bed joints where they
// meet (the port's path composites them as one layer). The app's camera never looks that level (its steepest tilt
// 20° down), so only the renders' own frames draw them.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"
#include "style/Light.hpp"
#include "style/Palette.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
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
               std::vector<view::Vec3>& cs, std::array<double, 4>* box) {
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
    // the screen points one after another (each projected once, none kept): their box, and the shoelace sum of each
    // with the next, the last with the first
    const view::Vec2 p0 = cam.pc(cs[0]);
    double min_x = p0.x;
    double max_x = p0.x;
    double min_y = p0.y;
    double max_y = p0.y;
    double sum = 0;
    view::Vec2 p = p0;
    for (std::size_t i = 1; i <= cs.size(); ++i) {
        const view::Vec2 r = i < cs.size() ? cam.pc(cs[i]) : p0;
        if (i < cs.size()) {
            min_x = pm::min(min_x, r.x);
            max_x = pm::max(max_x, r.x);
            min_y = pm::min(min_y, r.y);
            max_y = pm::max(max_y, r.y);
        }
        sum += (p.x * r.y) - (r.x * p.y);
        p = r;
    }
    if (max_x < -margin || min_x > cam.width() + margin || max_y < -margin || min_y > cam.height() + margin) {
        return false;
    }
    const double area = std::abs(sum) / 2;
    const bool kept = strictly ? area > least : area >= least;
    if (kept && box != nullptr) *box = {min_x, min_y, max_x, max_y};
    return kept;
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

Ground::QuaysMoment Ground::quays_moment(const frame::FrameState& state) const {
    const std::uint32_t walls =
        state.palette != nullptr ? state.palette->colours.at(style::palette_slot::quay_walls) : 0U;
    static_assert(sizeof(GroundFrame::light) == 3 * sizeof(double));
    QuaysMoment m{};
    std::memcpy(m.data(), &state.block.theme, sizeof(shaders::Theme));
    std::memcpy(m.data() + sizeof(shaders::Theme), frame_.light.data(), sizeof(frame_.light));
    std::memcpy(m.data() + sizeof(shaders::Theme) + sizeof(frame_.light), &walls, sizeof(walls));
    return m;
}

void Ground::decide_quays(const frame::FrameState& state) {
    GroundFrame& f = frame_;
    quays_made_ = quays_moment(state);
    f.quays.clear();
    // `if th.get('wx')`: without weather the water is flat and has no walls; and each of a wall's faces the map leaves
    // out (style::camera_part's quays, quay_waterlines, quay_tops: each its own switch) is not decided
    const bool walls = !f.out(style::camera_part::quays);
    const bool waterlines = !f.out(style::camera_part::quay_waterlines);
    const bool tops = !f.out(style::camera_part::quay_tops);
    if (!f.weather || no_quays_ || (!walls && !waterlines && !tops)) return;
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
    std::vector<view::Vec3> cs;
    for (const Piece& p : pieces) {
        const DrawnTile& t = f.tiles[p.tile];
        const view::Camera& cam = *t.cam; // in the tile's own frame
        const view::Vec3 eye = cam.eye();
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
        // a piece without a sample (its tile drawn before its first bits) lit by day, never as if in the shade
        const bool lit = toward && (!p.sampled || sun_bit(t, p.sample));
        const QuayColours colours = quay_colours(g, state.palette, f.night);
        const std::uint32_t wall_lit = lit_colour(theme, colours.wall, q.nx, q.nz, toward);
        const std::uint32_t wall_unlit = lit_colour(theme, colours.wall, q.nx, q.nz, false);
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
        const bool f1 = walls && face_kept(cam, wall, 300, 0.12, false, cs);
        bool wall_drawn = false; // the port's f1 as drawn: the wall, or its bands' paths
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
                if (any) {
                    f.quays.push_back(b);
                    wall_drawn = true;
                }
            }
        } else if (f1) {
            f.quays.push_back(face);
            wall_drawn = true;
        }
        // at eye level the quay wall shows its brick courses: `if cam.f[1] > -0.25 and th.get('wx') and not night and
        // f1`, f1 the wall as drawn (its bands' paths where it is in bands)
        if (wall_drawn && !f.night && !no_courses_ && g.quay_course && *g.quay_course != 0.0 &&
            cam.forward().y > -0.25) {
            course_paths(cam, face, wy, *g.quay_course, f.quays);
        }
        QuayFace waterline = face;
        waterline.kind = QuayFace::Kind::waterline;
        waterline.colour = colours.waterline;
        waterline.lit = waterline.unlit = waterline.colour;
        waterline.sampled = false;
        if (waterlines && face_kept(cam, face_of(waterline, wy), 300, 0.12, false, cs)) f.quays.push_back(waterline);
        QuayFace coping = face;
        coping.kind = QuayFace::Kind::coping;
        coping.lit = lit_colour(theme, colours.coping, q.nx, q.nz, toward);
        coping.unlit = lit_colour(theme, colours.coping, q.nx, q.nz, false);
        coping.colour = lit ? coping.lit : coping.unlit;
        if (tops && face_kept(cam, face_of(coping, wy), 300, 0.12, false, cs)) f.quays.push_back(coping);
    }
}

QuayColours quay_colours(const geo::GroundTile& g, const style::PaletteBlock* palette, bool night) noexcept {
    const auto walls = [palette](tiles::StyleRgb c) {
        return style::paint(palette, style::palette_slot::quay_walls, style::quay_walls_own, c);
    };
    return {.wall = walls(g.quay_colour),
            .waterline = walls(night ? g.quay_waterline.night : g.quay_waterline.day),
            .coping = walls(g.quay_coping)};
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

void course_paths(const view::Camera& cam, const QuayFace& wall, double wy, double course, std::vector<QuayFace>& out) {
    // coursing(cam, a, b, b[0] - a[0], b[1] - a[1], el_, n3, WY + 0.38, -0.2, QUAY_COURSE), `if el_ > 0.2`
    const double ex = wall.bx - wall.ax;
    const double ez = wall.bz - wall.az;
    const double elen = pm::hypot(ex, ez);
    const double y0 = wy + 0.38;
    constexpr double top = -0.2;
    if (!(elen > 0.2) || top - y0 < 0.3) return;
    const bool stone = course >= 0.3;
    const auto n = static_cast<std::uint32_t>(pm::max(1.0, pm::ceil(elen / 5.0)));
    for (std::uint32_t k = 0; k < n; ++k) {
        const double u0 = static_cast<double>(k) / n;
        const double u1 = static_cast<double>(k + 1) / n;
        const double dm =
            pm::max(cam.depth({wall.ax + ((ex * (u0 + u1)) / 2), (y0 + top) / 2, wall.az + ((ez * (u0 + u1)) / 2)}),
                    cam.near());
        const double sp = (cam.focal() * course) / dm; // the course's height on the screen
        if (sp < 1.6) continue;
        const double op = pm::min(1.0, (sp - 1.6) / 2.2);
        QuayFace c = wall;
        c.kind = QuayFace::Kind::courses;
        c.colour = stone ? 0x5E5548U : 0xE2D9CBU;
        c.lit = c.unlit = c.colour;
        c.sampled = false;
        c.chunk = k;
        c.chunks = n;
        c.course = course;
        c.width = printed(pm::max(0.35, (cam.focal() * (stone ? 0.008 : 0.011)) / dm), 2);
        c.opacity = printed((stone ? 0.26 : 0.3) * op, 2);
        out.push_back(c);
    }
}

void course_lines(const view::Camera& cam, const QuayFace& f, double wy, double nx, double nz,
                  std::vector<CourseLine>& out) {
    // closeup.py's coursing over its chunk k of n: `u0, u1 = k / n, (k + 1) / n`
    const double ex = f.bx - f.ax;
    const double ez = f.bz - f.az;
    const double elen = pm::hypot(ex, ez);
    const double u0 = static_cast<double>(f.chunk) / f.chunks;
    const double u1 = static_cast<double>(f.chunk + 1) / f.chunks;
    const double y0 = wy + 0.38;
    constexpr double top = -0.2;
    const double course = f.course;
    const double near = cam.near();
    // _seg: both ends in camera coordinates, the one behind the near plane moved to it; none where both are
    const auto seg = [&](const view::Vec3& p, const view::Vec3& q, CourseLine& line) {
        view::Vec3 a = cam.cam(p);
        view::Vec3 b = cam.cam(q);
        if (a.z < near && b.z < near) return false;
        if (a.z < near) {
            const double t = (near - a.z) / (b.z - a.z);
            a = {a.x + ((b.x - a.x) * t), a.y + ((b.y - a.y) * t), a.z + ((b.z - a.z) * t)};
        }
        if (b.z < near) {
            const double t = (near - b.z) / (a.z - b.z);
            b = {b.x + ((a.x - b.x) * t), b.y + ((a.y - b.y) * t), b.z + ((a.z - b.z) * t)};
        }
        line = {.a = p, .b = q, .pa = cam.pc(a), .pb = cam.pc(b)};
        return true;
    };
    const std::size_t before = out.size();
    // the bed joints: `y = y0 + course; while y < top - 0.02`
    const double p0x = (f.ax + (ex * u0)) + (nx * 0.012);
    const double p0z = (f.az + (ez * u0)) + (nz * 0.012);
    const double p1x = (f.ax + (ex * u1)) + (nx * 0.012);
    const double p1z = (f.az + (ez * u1)) + (nz * 0.012);
    double y = y0 + course;
    while (y < top - 0.02) {
        CourseLine line;
        const bool ahead = seg({p0x, y, p0z}, {p1x, y, p1z}, line);
        // the port leaves out a line wholly more than 5 px beside the frame
        const bool beside = pm::max(line.pa.x, line.pb.x) < -5 || pm::min(line.pa.x, line.pb.x) > cam.width() + 5 ||
                            pm::max(line.pa.y, line.pb.y) < -5 || pm::min(line.pa.y, line.pb.y) > cam.height() + 5;
        if (ahead && !beside) out.push_back(line);
        y += course;
    }
    // cut stone: the blocks' head joints too, 2.1 courses long, staggered course by course (`if stone and segs`)
    if (!(course >= 0.3) || out.size() == before) return;
    const double bl = course * 2.1;
    const double seg_len = elen * (u1 - u0);
    std::uint32_t row = 0;
    y = y0;
    while (y < top - (course * 0.5)) {
        const double y1 = pm::min(top, y + course);
        double t = (static_cast<double>(row % 2) * bl) / 2;
        while (t < seg_len) {
            const double at = u0 + (t / elen);
            const double x = (f.ax + (ex * at)) + (nx * 0.012);
            const double z = (f.az + (ez * at)) + (nz * 0.012);
            CourseLine line;
            if (seg({x, y, z}, {x, y1, z}, line)) out.push_back(line);
            t += bl;
        }
        y += course;
        ++row;
    }
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
        if (e.kind == QuayFace::Kind::courses) continue; // the line stage's (write_course_points)
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
        // unsampled: before the tile's first bits (by day), or turned from the sun (its lit colour its unlit one)
        const double k = e.sampled ? sun_amount(t, e.sample) : 1.0;
        const std::uint32_t colour =
            e.kind == QuayFace::Kind::waterline ? lit_mix(e.colour, e.colour, 0.0) : lit_mix(e.lit, e.unlit, k);
        put(e.tile, slot_value, colour, e.kind == QuayFace::Kind::wall, face_of(e, wy), g.origin);
    }
}

void Ground::write_course_points(std::vector<std::uint32_t>& words, std::vector<CoursePath>& paths) const {
    std::vector<CourseLine> lines;
    for (std::size_t j = 0; j < frame_.quays.size(); ++j) {
        const QuayFace& e = frame_.quays[j];
        if (e.kind != QuayFace::Kind::courses) continue;
        const DrawnTile& t = frame_.tiles[e.tile];
        if (!t.pass->ranges[1] && !t.pass->ranges[0]) continue;
        const geo::GroundTile& g = *t.ground;
        const geo::QuayEdge& q = g.quays[e.edge];
        lines.clear();
        course_lines(*t.cam, e, q.wy, q.nx, q.nz, lines);
        // a run's segments are 14 bits of its instance word
        if (lines.empty() || lines.size() > 0x4000) continue;
        paths.push_back({.face = static_cast<std::uint32_t>(j),
                         .first = static_cast<std::uint32_t>(words.size()),
                         .lines = static_cast<std::uint32_t>(lines.size())});
        for (const CourseLine& l : lines) {
            for (const view::Vec3& c : {l.a, l.b}) {
                words.push_back(bits_of(static_cast<float>(c.x - g.origin.x)));
                words.push_back(bits_of(static_cast<float>(c.y)));
                words.push_back(bits_of(static_cast<float>(c.z - g.origin.z)));
            }
        }
    }
}

void Ground::record_courses(const frame::Recording& r) {
    const Words& w = *words_;
    if (w.course_count == 0 || !*pipelines_->line) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->line);
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    const auto samples = static_cast<std::uint32_t>(r.frame.pass.spec().config.samples());
    // each line from the near plane on, as _seg clips it; its points the words'
    pc.mode = {ground_flag::near_clip | ground_flag::courses, 0U, w.course_instances, samples};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(6, w.course_count, 0, 0);
    r.counters.draw("ground quay courses", std::uint64_t{w.course_count} * 2, w.course_count);
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
    cmd.draw(face_vertices, w.quay_count, 0, 0);
    r.counters.draw("ground quays", std::uint64_t{w.quay_count} * (face_vertices / 3), w.quay_count);
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
    std::vector<CourseLine> courses;
    for (std::size_t j = 0; j < f.quays.size(); ++j) {
        const QuayFace& e = f.quays[j];
        const DrawnTile& t = f.tiles[e.tile];
        const double wy = t.ground->quays[e.edge].wy;
        points.clear();
        ends.clear();
        if (e.kind == QuayFace::Kind::courses) {
            // `<path d="M%.1f %.1fl%.1f %.1f..." stroke=... stroke-width="%.2f" opacity="%.2f" fill="none"/>`: each
            // line from its first end as printed by its printed way to its other
            const geo::QuayEdge& q = t.ground->quays[e.edge];
            courses.clear();
            course_lines(*t.cam, e, wy, q.nx, q.nz, courses);
            if (courses.empty()) continue;
            subpaths.clear();
            for (const CourseLine& l : courses) {
                const auto x = static_cast<float>(printed(l.pa.x, 1));
                const auto y = static_cast<float>(printed(l.pa.y, 1));
                points.push_back({.x = x, .y = y});
                points.push_back({.x = x + static_cast<float>(printed(l.pb.x - l.pa.x, 1)),
                                  .y = y + static_cast<float>(printed(l.pb.y - l.pa.y, 1))});
            }
            for (std::size_t k = 0; k < courses.size(); ++k) {
                subpaths.push_back({.points = std::span<const twin::Point>(points).subspan(2 * k, 2), .closed = false});
            }
            out.at(slot(geo::group::quay, static_cast<std::uint32_t>(j), 0), out.root());
            twin::Style s;
            s.stroke = twin::Paint::colour(0xFF00'0000U | e.colour);
            s.stroke_width = static_cast<float>(e.width);
            out.path(s, subpaths, {.opacity = static_cast<float>(e.opacity)});
            continue;
        }
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
