// The ground (passes/Ground.hpp): the pass, its fills, its plate, its decisions and its painter order.
#include "passes/Ground.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/ground/ground.hpp"
#include "gpu/Descriptors.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"
#include "sun/Buckets.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <ctime>
#include <format>
#include <limits>

namespace atlas::passes {

namespace geo = atlas::geometry;

namespace {

[[nodiscard]] std::uint32_t bits_of(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// A tile's words in the frame's tile table (ground.slang's TILE_WORDS).
constexpr std::size_t tile_words = 20;

/// The CPU time this thread has run, milliseconds (what select costs on one core, whatever else the machine runs).
[[nodiscard]] double thread_ms() noexcept {
    timespec t{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return (static_cast<double>(t.tv_sec) * 1e3) + (static_cast<double>(t.tv_nsec) * 1e-6);
}

} // namespace

// ---------------------------------------------------------------- the renders' cull, in double

void ProjectedPoly::clip_edge(const view::Vec3& a, const view::Vec3& b, double near, std::vector<view::Vec3>& out) {
    // render/city.py:41: `if ai: out.append(a)`, then where the edge crosses the near plane
    const bool ai = a.z >= near;
    const bool bi = b.z >= near;
    if (ai) out.push_back(a);
    if (ai != bi) {
        const double t = (near - a.z) / (b.z - a.z);
        out.emplace_back(a.x + ((b.x - a.x) * t), a.y + ((b.y - a.y) * t), near);
    }
}

ProjectedPoly project_poly(const view::Camera& cam, std::span<const geo::P2> pts, double y, double margin,
                           std::vector<view::Vec3>& cs, std::vector<view::Vec2>& sp) {
    ProjectedPoly out;
    const std::size_t n = pts.size();
    cs.clear();
    if (n == 0) return out;
    const double near = cam.near();
    const view::Vec3 first = cam.cam({pts[0].x, y, pts[0].z});
    view::Vec3 a = first;
    for (std::size_t i = 1; i < n; ++i) {
        const view::Vec3 b = cam.cam({pts[i].x, y, pts[i].z});
        ProjectedPoly::clip_edge(a, b, near, cs);
        a = b;
    }
    ProjectedPoly::clip_edge(a, first, near, cs);
    out.points = cs.size();
    if (cs.size() < 3) return out;
    sp.resize(cs.size());
    for (std::size_t k = 0; k < cs.size(); ++k) sp[k] = cam.pc(cs[k]);
    // the box as the port's loop finds it (Python's max and min agree for numbers)
    double max_x = sp[0].x;
    double min_x = sp[0].x;
    double max_y = sp[0].y;
    double min_y = sp[0].y;
    for (std::size_t k = 1; k < sp.size(); ++k) {
        const view::Vec2& p = sp[k];
        max_x = pymath::max(max_x, p.x);
        min_x = pymath::min(min_x, p.x);
        max_y = pymath::max(max_y, p.y);
        min_y = pymath::min(min_y, p.y);
    }
    out.min_x = min_x;
    out.max_x = max_x;
    out.min_y = min_y;
    out.max_y = max_y;
    // `if max(xs) < -margin or ...: return None` (a NaN box is kept, as Python keeps it)
    out.inside = true;
    if (max_x < -margin || min_x > cam.width() + margin || max_y < -margin || min_y > cam.height() + margin) {
        out.inside = false;
    }
    // render/city.py:72 area: the shoelace sum in order; the area is its absolute value over two
    double s = 0.0;
    const std::size_t m = sp.size();
    for (std::size_t k = 0; k < m; ++k) {
        const view::Vec2& p = sp[k];
        const view::Vec2& q = sp[(k + 1) % m];
        s += (p.x * q.y) - (q.x * p.y);
    }
    out.sum = s;
    return out;
}

Box box_test(const view::Camera& cam, const geo::Fill& f, double y, double margin) {
    // The ring lies in its box on the plane y. Where every corner of the box lies ahead of the near plane, the ring
    // projects into the projection of the box (a projective map keeps the convex hull), so a box wholly beyond the
    // frame and the margin leaves the ring there too: cam.poly drops it.
    const std::array<view::Vec3, 4> corners{{{f.x0, y, f.z0}, {f.x1, y, f.z0}, {f.x1, y, f.z1}, {f.x0, y, f.z1}}};
    double min_x = std::numeric_limits<double>::infinity();
    double max_x = -min_x;
    double min_y = min_x;
    double max_y = -min_x;
    for (const view::Vec3& p : corners) {
        const view::Vec3 k = cam.cam(p);
        if (k.z < cam.near()) return Box::unknown;
        const view::Vec2 s = cam.pc(k);
        min_x = pymath::min(min_x, s.x);
        max_x = pymath::max(max_x, s.x);
        min_y = pymath::min(min_y, s.y);
        max_y = pymath::max(max_y, s.y);
    }
    // a hair of room for the rounding of the ring's own projection, which the port computes point by point
    const double eps = 1e-6 * (1 + std::abs(margin) + cam.width() + cam.height());
    if (max_x < -margin - eps || min_x > cam.width() + margin + eps || max_y < -margin - eps ||
        min_y > cam.height() + margin + eps) {
        return Box::outside;
    }
    return Box::unknown;
}

bool near_line(const GroundFrame& f, std::span<const geo::P2> line, const std::array<double, 4>& near) {
    if (near[1] >= near[0]) {
        const double nx = pymath::max(0.0, pymath::max(near[0] - f.target_x, f.target_x - near[1]));
        const double nz = pymath::max(0.0, pymath::max(near[2] - f.target_z, f.target_z - near[3]));
        const double fx = pymath::max(std::abs(near[0] - f.target_x), std::abs(near[1] - f.target_x));
        const double fz = pymath::max(std::abs(near[2] - f.target_z), std::abs(near[3] - f.target_z));
        const double r2 = f.radius * f.radius;
        if ((fx * fx) + (fz * fz) < r2 * (1 - 1e-9)) return true;  // every sample within
        if ((nx * nx) + (nz * nz) > r2 * (1 + 1e-9)) return false; // none
    }
    return geo::near_radius(line, f.target_x, f.target_z, f.radius);
}

Box cell_test(const view::Camera& cam, const geo::CellBox& c) {
    if (c.empty()) return Box::outside;
    double min_x = std::numeric_limits<double>::infinity();
    double max_x = -min_x;
    double min_y = min_x;
    double max_y = -min_x;
    for (const double x : {c.x0, c.x1}) {
        for (const double y : {c.y0, c.y1}) {
            for (const double z : {c.z0, c.z1}) {
                const view::Vec3 k = cam.cam({x, y, z});
                if (k.z < cam.near()) return Box::unknown;
                const view::Vec2 s = cam.pc(k);
                min_x = pymath::min(min_x, s.x);
                max_x = pymath::max(max_x, s.x);
                min_y = pymath::min(min_y, s.y);
                max_y = pymath::max(max_y, s.y);
            }
        }
    }
    // the box's corners ahead of the near plane: every ring in it projects inside their projections' hull
    const double eps = 1e-6 * (1 + std::abs(c.margin) + cam.width() + cam.height());
    if (max_x < -c.margin - eps || min_x > cam.width() + c.margin + eps || max_y < -c.margin - eps ||
        min_y > cam.height() + c.margin + eps) {
        return Box::outside;
    }
    if (min_x > eps && max_x < cam.width() - eps && min_y > eps && max_y < cam.height() - eps) return Box::inside;
    return Box::unknown;
}

// ---------------------------------------------------------------- the twin's projections and prints

std::size_t twin_ring(const twin::Projector& projector, const shaders::TilePlace& place, std::span<const geo::P2> ring,
                      geo::P2 origin, double y, std::vector<twin::Point>& out) {
    // the vertex stage's float camera coordinates of each point, clipped at the near plane as Camera.poly clips
    const float near = projector.camera().frame.w;
    std::vector<hlsl::float3> ks;
    ks.reserve(ring.size() + 2);
    auto k_of = [&](const geo::P2& p) {
        const hlsl::float3 local = {static_cast<float>(p.x - origin.x), static_cast<float>(y),
                                    static_cast<float>(p.z - origin.z)};
        return projector.camera_of(place, local);
    };
    const std::size_t n = ring.size();
    for (std::size_t i = 0; i < n; ++i) {
        const hlsl::float3 a = k_of(ring[i]);
        const hlsl::float3 b = k_of(ring[(i + 1) % n]);
        const bool ai = a.z >= near;
        const bool bi = b.z >= near;
        if (ai) ks.push_back(a);
        if (ai != bi) {
            const float t = (near - a.z) / (b.z - a.z);
            const hlsl::float3 c = {a.x + ((b.x - a.x) * t), a.y + ((b.y - a.y) * t), near};
            ks.push_back(c);
        }
    }
    if (ks.size() < 3) return 0;
    for (const hlsl::float3& k : ks) out.push_back(projector.css(k));
    return ks.size();
}

void twin_runs(const twin::Projector& projector, const shaders::TilePlace& place, std::span<const geo::P2> line,
               geo::P2 origin, double y, std::vector<twin::Point>& points, std::vector<std::uint32_t>& ends) {
    // regions.py's line_d (and the tram's and bridge's own loops): the points ahead of the near plane, by runs
    std::size_t run = 0;
    for (const geo::P2& p : line) {
        const hlsl::float3 local = {static_cast<float>(p.x - origin.x), static_cast<float>(y),
                                    static_cast<float>(p.z - origin.z)};
        const std::optional<twin::Point> q = projector.css(place, local);
        if (q) {
            points.push_back(*q);
            ++run;
            continue;
        }
        if (run == 1) points.pop_back();
        if (run > 1) ends.push_back(static_cast<std::uint32_t>(points.size()));
        run = 0;
    }
    if (run == 1) points.pop_back();
    if (run > 1) ends.push_back(static_cast<std::uint32_t>(points.size()));
}

TwinPlacer::TwinPlacer(const frame::FrameState& state, const view::Camera& cam)
    : state_(state), cam_(cam), projector_(state.block.camera) {}

twin::Point TwinPlacer::point(view::Vec2 s) const {
    if (state_.rest) return {.x = static_cast<float>(printed(s.x, 1)), .y = static_cast<float>(printed(s.y, 1))};
    return {.x = static_cast<float>(s.x), .y = static_cast<float>(s.y)};
}

bool TwinPlacer::exact(const DrawnTile& t) const noexcept {
    return t.tile != nullptr && state_.ground_frame && t.tile->frame == *state_.ground_frame;
}

std::size_t TwinPlacer::exact_ring(std::span<const geo::P2> ring, double y, std::vector<twin::Point>& out) {
    // render/city.py:45 Camera.poly in double: each edge clipped at the near plane in camera coordinates, then pc
    cs_.clear();
    const std::size_t n = ring.size();
    if (n == 0) return 0;
    const double near = cam_.near();
    const view::Vec3 first = cam_.cam({ring[0].x, y, ring[0].z});
    view::Vec3 a = first;
    for (std::size_t i = 1; i < n; ++i) {
        const view::Vec3 b = cam_.cam({ring[i].x, y, ring[i].z});
        ProjectedPoly::clip_edge(a, b, near, cs_);
        a = b;
    }
    ProjectedPoly::clip_edge(a, first, near, cs_);
    if (cs_.size() < 3) return 0;
    for (const view::Vec3& k : cs_) out.push_back(point(cam_.pc(k)));
    return cs_.size();
}

std::size_t TwinPlacer::ring(const DrawnTile& t, std::span<const geo::P2> ring, double y,
                             std::vector<twin::Point>& out) {
    if (exact(t)) return exact_ring(ring, y, out);
    return twin_ring(projector_, t.place, ring, t.ground->origin, y, out);
}

std::size_t TwinPlacer::frame_ring(std::span<const geo::P2> ring, double y, std::vector<twin::Point>& out) {
    return exact_ring(ring, y, out);
}

void TwinPlacer::runs(const DrawnTile& t, std::span<const geo::P2> line, double y, std::vector<twin::Point>& points,
                      std::vector<std::uint32_t>& ends) {
    if (!exact(t)) {
        twin_runs(projector_, t.place, line, t.ground->origin, y, points, ends);
        return;
    }
    // regions.py:521 line_d: cam.proj of each point (None behind the near plane), runs of two points or more
    std::size_t run = 0;
    for (const geo::P2& p : line) {
        const view::Vec3 k = cam_.cam({p.x, y, p.z});
        if (!(k.z < cam_.near())) {
            points.push_back(point(cam_.pc(k)));
            ++run;
            continue;
        }
        if (run == 1) points.pop_back();
        if (run > 1) ends.push_back(static_cast<std::uint32_t>(points.size()));
        run = 0;
    }
    if (run == 1) points.pop_back();
    if (run > 1) ends.push_back(static_cast<std::uint32_t>(points.size()));
}

void TwinPlacer::joined(const DrawnTile& t, std::span<const geo::P2> line, double y, std::vector<twin::Point>& points,
                        std::vector<std::uint32_t>& ends) {
    const std::size_t start = points.size();
    const bool in_frame = exact(t);
    for (const geo::P2& p : line) {
        if (in_frame) {
            const view::Vec3 k = cam_.cam({p.x, y, p.z});
            if (k.z < cam_.near()) continue;
            points.push_back(point(cam_.pc(k)));
            continue;
        }
        const hlsl::float3 local = {static_cast<float>(p.x - t.ground->origin.x), static_cast<float>(y),
                                    static_cast<float>(p.z - t.ground->origin.z)};
        if (const std::optional<twin::Point> q = projector_.css(t.place, local)) points.push_back(*q);
    }
    if (points.size() - start > 1) {
        ends.push_back(static_cast<std::uint32_t>(points.size()));
    } else {
        points.resize(start);
    }
}

double printed(double v, int decimals) {
    // '%.<n>f' rounds the exact binary value at its decimal place, ties to even, and the reader takes the double
    // nearest that decimal: Python's round(v, n), which pymath computes exactly
    return pymath::round(v, decimals);
}

// ---------------------------------------------------------------- the pass

Ground::Ground() = default;
Ground::~Ground() { Ground::release(); }

std::uint32_t Ground::slot(std::uint16_t group, std::uint32_t order, std::uint32_t sub) const noexcept {
    const std::int64_t s = frame_.base[group] + (static_cast<std::int64_t>(order) * frame_.stride[group]) + sub;
    return (frame_.tail[group] ? first_tail_slot() : first_ground_slot()) +
           static_cast<std::uint32_t>(std::max<std::int64_t>(s, 0));
}

gpu::Result<void> Ground::build(scene::TileBuild& b) const {
    auto ground = std::make_shared<geo::GroundTile>(
        geo::build_ground(b.tile(), b.head(), b.style(), b.level(), {.x = b.origin().x, .z = b.origin().z}));
    const geo::GroundGpu gpu = geo::pack(*ground);
    ground->polyline_words.resize(ground->polylines.size());
    std::uint32_t words = 0;
    for (std::size_t i = 0; i < ground->polylines.size(); ++i) {
        ground->polyline_words[i] = words;
        words += 2 * ground->polylines[i].count;
    }
    ground->layout = {.vertices = gpu.vertices,
                      .above = gpu.above,
                      .below = gpu.below,
                      .canopy = gpu.canopy,
                      .fans = gpu.fans,
                      .fills = gpu.fills,
                      .points = gpu.points,
                      .lines_at = gpu.lines_at,
                      .shadow_points = gpu.shadow_points,
                      .shadows = gpu.shadows,
                      .shadow_draw = gpu.shadow_draw};
    // the shadows' points are the GPU's now (the twin draws the port's own hulls from the casters)
    ground->shadow_points = {};
    // every range first, then the writes (Heaps' writer holds a lock)
    scene::HeapRange ground_range;
    scene::HeapRange lines_range;
    if (!gpu.ground.empty()) {
        ATLAS_GPU_LET(r, b.allocate(scene::HeapKind::ground, static_cast<std::uint32_t>(gpu.ground.size() * 4), 16));
        ground_range = r;
    }
    if (!gpu.lines.empty()) {
        ATLAS_GPU_LET(r, b.allocate(scene::HeapKind::lines, static_cast<std::uint32_t>(gpu.lines.size() * 4), 16));
        lines_range = r;
    }
    if (ground_range) b.heaps().write(ground_range, std::as_bytes(std::span(gpu.ground)));
    if (lines_range) b.heaps().write(lines_range, std::as_bytes(std::span(gpu.lines)));
    b.out().ranges[0] = ground_range;
    b.out().ranges[1] = lines_range;
    const std::size_t bytes = ground->bytes();
    b.keep(std::shared_ptr<const geo::GroundTile>(std::move(ground)), bytes);
    return {};
}

void Ground::select(const frame::FrameState& state, frame::Selectors& selectors) {
    (void)selectors;
    const double started = thread_ms();
    if (decide(state)) lay_out();
    select_ms_ = thread_ms() - started;
}

std::uint32_t Ground::ground_slots(const frame::FrameState& state) const {
    (void)state;
    return frame_.valid ? frame_.slots : 0;
}

std::uint32_t Ground::ground_tail_slots(const frame::FrameState& state) const {
    (void)state;
    return frame_.valid ? frame_.tail_slots : 0;
}

namespace {

/// The decisions' key at camera `cam` with the frame's settings `f`.
[[nodiscard]] DecisionKey key_of(const view::Camera& cam, const GroundFrame& f) noexcept {
    DecisionKey k;
    std::size_t n = 0;
    auto put = [&](double v) { k.bits[n++] = std::bit_cast<std::uint64_t>(v); };
    for (const view::Vec3& v : {cam.eye(), cam.forward(), cam.right(), cam.up()}) {
        put(v.x);
        put(v.y);
        put(v.z);
    }
    for (const double v : {cam.focal(), cam.cx(), cam.cy(), cam.width(), cam.height(), cam.near(), f.radius, f.target_x,
                           f.target_z, f.ground_r}) {
        put(v);
    }
    k.bits[n++] = (f.weather ? 1U : 0U) | (f.night ? 2U : 0U) | (f.engine ? 4U : 0U) | (f.casting ? 8U : 0U);
    return k;
}

} // namespace

bool Ground::decide(const frame::FrameState& state) {
    GroundFrame& f = frame_;
    // the tiles' PassTile of this pass: the one at its place among the passes that built them (FrameState::cover)
    const std::uint32_t me = index();
    // the world's ground: a frame without a scene (a test's) draws none, and takes no painter slot
    f.kept = false;
    if (!state.view || state.cover == nullptr) {
        f.valid = false;
        keep_ = false;
        return true;
    }
    f.valid = true;
    const view::Camera& cam = state.view->camera();
    f.exact = state.rest;
    // the moment's switches and the camera's settings
    f.weather = state.theme.has(style::theme_flag::weather);
    f.night = state.theme.has(style::theme_flag::night);
    f.engine = state.camera.has(style::camera_flag::engine_mode) && state.placed.has_value();
    f.radius = 0;
    f.ground_r = view::default_ground_r;
    f.target_x = 0;
    f.target_z = 0;
    if (state.placed) {
        if (const auto r = state.placed->number("radius")) f.radius = *r;
        if (const auto g = state.placed->number("ground_r")) f.ground_r = *g;
        const view::Vec3 t = state.placed->camera().target;
        f.target_x = t.x;
        f.target_z = t.z;
    }
    if (f.radius == 0) f.engine = false;
    // the ground shadows: `if not night and L[1] > 0` (and the region's no_shadows, per tile), toward the moment's sun
    const sun::Sun light = sun::bucket_of(state.theme).sun;
    f.sun_x = light.x;
    f.sun_y = light.y;
    f.sun_z = light.z;
    f.casting = !f.night && light.y > 0;
    // the tiles the cover draws, the visible cells of each together
    f.tiles.clear();
    if (state.cover != nullptr) {
        for (const scene::CoverTile& c : state.cover->tiles()) {
            if (c.drawn == nullptr || me >= c.drawn->passes.size()) continue;
            const scene::PassTile& p = c.drawn->passes[me];
            const auto* g = p.cpu_as<geo::GroundTile>();
            if (g == nullptr) continue;
            // a tile drawing itself answers for all its cells (the renders' margin tests decide, not the frame's
            // visibility: they draw what lies up to 900 px beyond it, the tram tracks and the bridges' lines
            // wherever they are ahead); a stand-in ancestor for its wanted tile's cells alone
            const std::uint64_t cells = c.drawn->key == c.key ? ~std::uint64_t{0} : c.cells;
            const auto it = std::ranges::find(f.tiles, c.drawn, &DrawnTile::tile);
            if (it != f.tiles.end()) {
                it->cells |= cells;
                continue;
            }
            f.tiles.push_back({.tile = c.drawn, .pass = &p, .ground = g, .cells = cells, .place = c.place});
        }
    }
    std::uint32_t fills = 0;
    std::uint32_t strokes = 0;
    std::uint32_t shadows = 0;
    for (DrawnTile& t : f.tiles) {
        t.decisions = fills;
        t.strokes = strokes;
        t.shadows = shadows;
        fills += static_cast<std::uint32_t>(t.ground->fills.size());
        strokes += static_cast<std::uint32_t>(t.ground->strokes.size());
        shadows += static_cast<std::uint32_t>(t.ground->shadows.size());
    }
    // At rest the decisions are made once for a camera (frames at rest draw again only as tiles land and fade, or as
    // the water moves): a tile decided at this camera with these cells keeps its decisions.
    const DecisionKey key = key_of(cam, f);
    const bool keep = f.exact && keep_ && key == key_;
    key_ = key;
    keep_ = f.exact;
    auto find = [&](const DrawnTile& t) -> TileDecisions* {
        for (const std::unique_ptr<TileDecisions>& d : decisions_) {
            if (d->ground.get() == t.ground && d->cells == t.cells) return d.get();
        }
        return nullptr;
    };
    bool same = keep && last_tiles_.size() == f.tiles.size();
    for (std::size_t i = 0; same && i < f.tiles.size(); ++i) {
        same = last_tiles_[i].first == f.tiles[i].ground && last_tiles_[i].second == f.tiles[i].cells;
    }
    last_tiles_.clear();
    for (const DrawnTile& t : f.tiles) last_tiles_.emplace_back(t.ground, t.cells);
    if (same) {
        // the same camera over the same tiles: every decision and slot as the last frame's
        for (DrawnTile& t : f.tiles) t.decided = find(t);
        f.kept = true;
        return false;
    }
    // the kept decisions this frame uses, then the others decided (into what is not kept)
    for (const std::unique_ptr<TileDecisions>& d : decisions_) {
        d->used = false;
        if (!keep) d->ground.reset();
    }
    std::vector<DrawnTile*> missing;
    for (DrawnTile& t : f.tiles) {
        TileDecisions* d = keep ? find(t) : nullptr;
        if (d != nullptr) {
            d->used = true;
            t.decided = d;
        } else {
            missing.push_back(&t);
        }
    }
    std::size_t spare = 0;
    for (DrawnTile* t : missing) {
        while (spare < decisions_.size() && decisions_[spare]->used) ++spare;
        if (spare == decisions_.size()) decisions_.push_back(std::make_unique<TileDecisions>());
        TileDecisions& d = *decisions_[spare];
        d.used = true;
        d.ground = f.exact ? t->pass->cpu : nullptr;
        d.cells = t->cells;
        decide_tile(cam, *t, d);
        t->decided = &d;
    }
    // what no drawn tile uses lets its ground go
    for (const std::unique_ptr<TileDecisions>& d : decisions_) {
        if (!d->used) d->ground.reset();
    }
    // the tiles' decisions in the frame's
    f.drawn.assign(fills, 0);
    f.stroke_drawn.assign(strokes, 0);
    f.stroke_width.assign(strokes, 0.0);
    f.shadow_drawn.assign(shadows, 0);
    f.shadows = false;
    for (const DrawnTile& t : f.tiles) {
        std::ranges::copy(t.decided->drawn, f.drawn.begin() + t.decisions);
        std::ranges::copy(t.decided->stroke_drawn, f.stroke_drawn.begin() + t.strokes);
        std::ranges::copy(t.decided->stroke_width, f.stroke_width.begin() + t.strokes);
        std::ranges::copy(t.decided->shadow_drawn, f.shadow_drawn.begin() + t.shadows);
        f.shadows =
            f.shadows || std::ranges::find(t.decided->shadow_drawn, std::uint8_t{1}) != t.decided->shadow_drawn.end();
    }
    // the land kinds in the order of their first drawn ring in S['land'] (draw_under's OrderedDict); the canopy
    f.kind_rank.fill(-1);
    std::array<std::uint32_t, geo::group::land_kinds> first_ring{};
    first_ring.fill(std::numeric_limits<std::uint32_t>::max());
    f.canopy = false;
    for (const DrawnTile& t : f.tiles) {
        const geo::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
            if ((f.drawn[t.decisions + i] & drawn_bit::street) == 0) continue;
            const geo::Fill& fill = g.fills[i];
            if (fill.kind == geo::FillKind::land) {
                const std::uint32_t k = fill.group - geo::group::land;
                first_ring[k] = std::min(first_ring[k], fill.order);
            }
            if (fill.kind == geo::FillKind::canopy) f.canopy = true;
        }
    }
    std::array<std::uint32_t, geo::group::land_kinds> kinds{};
    std::uint32_t nk = 0;
    for (std::uint32_t k = 0; k < geo::group::land_kinds; ++k) {
        if (first_ring[k] != std::numeric_limits<std::uint32_t>::max()) kinds[nk++] = k;
    }
    std::sort(kinds.begin(), kinds.begin() + nk,
              [&](std::uint32_t a, std::uint32_t b) { return first_ring[a] < first_ring[b]; });
    for (std::uint32_t r = 0; r < nk; ++r) f.kind_rank[kinds[r]] = static_cast<std::int32_t>(r);
    decide_water(cam);
    decide_sky(cam);
    // the plate: gp(rect(-GR, GR, -GR, GR), th['ground']) at 0, margin 900, its area over 0.2
    const double gr = f.ground_r;
    const std::array<geo::P2, 4> rect{
        {{.x = -gr, .z = -gr}, {.x = gr, .z = -gr}, {.x = gr, .z = gr}, {.x = -gr, .z = gr}}};
    const ProjectedPoly p = project_poly(cam, rect, 0.0, 900, cam_, sp_);
    f.plate = p.points >= 3 && p.inside && (std::abs(p.sum) / 2) > 0.2;
    return true;
}

void Ground::decide_tile(const view::Camera& cam, const DrawnTile& t, TileDecisions& d) {
    const GroundFrame& f = frame_;
    const geo::GroundTile& g = *t.ground;
    d.drawn.assign(g.fills.size(), 0);
    d.water.clear();
    // the visible cells first, whole: a cell beyond the margin drops its fills, a cell inside the frame keeps them
    // from the margin test (the area test still decides each at rest)
    std::array<Box, geo::max_cells> cells{};
    for (std::size_t c = 0; c < geo::max_cells; ++c) {
        cells[c] = ((t.cells >> c) & 1U) != 0 ? cell_test(cam, g.cells[c]) : Box::outside;
    }
    for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
        const geo::Fill& fill = g.fills[i];
        if (fill.kind == geo::FillKind::water) {
            // every piece of a ring counts for its ring, in a visible cell or not (the port decides the ring whole)
            if (fill.hole && !f.weather) continue; // without weather the islands are not drawn
            d.water.push_back(view_water(cam, g, i));
            continue;
        }
        const Box cell = fill.cell < geo::max_cells ? cells[fill.cell] : Box::outside;
        if (cell == Box::outside) continue;                          // (and a cell the cover does not show)
        if (fill.kind == geo::FillKind::canopy && f.night) continue; // the canopy by day only
        // draw_over's radius cut, in engine mode
        if (f.engine && fill.line_count > 0 &&
            !near_line(f, std::span(g.points).subspan(fill.line, fill.line_count), fill.near)) {
            continue;
        }
        bool drawn = false;
        if (cell != Box::inside && box_test(cam, fill, fill.y, fill.margin) == Box::outside) {
            drawn = false;
        } else if (!f.exact) {
            drawn = true; // moving: what the box keeps is drawn
        } else {
            const ProjectedPoly p =
                project_poly(cam, std::span(g.points).subspan(fill.first, fill.count), fill.y, fill.margin, cam_, sp_);
            drawn = p.points >= 3 && p.inside && (std::abs(p.sum) / 2) > fill.min_area;
        }
        if (drawn) d.drawn[i] = drawn_bit::street;
    }
    decide_strokes(cam, t, d);
    decide_shadows(t, d);
}

void Ground::lay_out() {
    GroundFrame& f = frame_;
    f.used.fill(false);
    f.base.fill(0);
    f.stride.fill(0);
    f.tail.fill(false);
    if (!f.valid) {
        f.slots = 0;
        f.tail_slots = 0;
        return;
    }
    // each group's least and greatest order among what is drawn
    std::array<std::uint32_t, geo::group::count> lo{};
    std::array<std::uint32_t, geo::group::count> hi{};
    lo.fill(std::numeric_limits<std::uint32_t>::max());
    auto use = [&](std::uint16_t g, std::uint32_t order) {
        f.used[g] = true;
        lo[g] = std::min(lo[g], order);
        hi[g] = std::max(hi[g], order);
    };
    for (const DrawnTile& t : f.tiles) {
        const geo::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.fills.size(); ++i) {
            if ((f.drawn[t.decisions + i] & drawn_bit::street) == 0) continue;
            const geo::Fill& fill = g.fills[i];
            if (fill.kind == geo::FillKind::water) continue;
            use(fill.group, fill.order);
        }
        for (std::uint32_t i = 0; i < g.strokes.size(); ++i) {
            if (f.stroke_drawn[t.strokes + i] == 0) continue;
            use(g.strokes[i].group, g.strokes[i].order);
        }
    }
    use(geo::group::sky, 0);
    if (f.plate) use(geo::group::plate, 0);
    if (f.water) use(geo::group::water, 0);
    if (f.shadows) use(geo::group::shadow, 0);
    // the groups in the renders' order: each takes (greatest - least + 1) · stride slots
    std::int64_t cursor = 0;
    auto place = [&](std::uint16_t g) {
        if (!f.used[g]) return;
        const std::uint32_t stride = geo::stride_of(g);
        f.stride[g] = stride;
        f.base[g] = cursor - (static_cast<std::int64_t>(lo[g]) * stride);
        cursor += (static_cast<std::int64_t>(hi[g]) - lo[g] + 1) * stride;
    };
    place(geo::group::sky);
    place(geo::group::plate);
    // the land kinds by rank, a slot each (every ring of a kind is one path), then the canopy's one path
    std::array<std::uint16_t, geo::group::land_kinds> by_rank{};
    std::uint32_t kinds = 0;
    for (std::uint32_t k = 0; k < geo::group::land_kinds; ++k) {
        if (f.kind_rank[k] >= 0) {
            by_rank[static_cast<std::uint32_t>(f.kind_rank[k])] = static_cast<std::uint16_t>(k);
            ++kinds;
        }
    }
    for (std::uint32_t r = 0; r < kinds; ++r) {
        const auto g = static_cast<std::uint16_t>(geo::group::land + by_rank[r]);
        f.used[g] = true;
        f.stride[g] = 0;
        f.base[g] = cursor++;
    }
    if (f.used[geo::group::canopy]) {
        f.stride[geo::group::canopy] = 0;
        f.base[geo::group::canopy] = cursor++;
    }
    place(geo::group::water);
    place(geo::group::area);
    for (std::uint16_t c = 0; c < 16; ++c) place(static_cast<std::uint16_t>(geo::group::road + c));
    for (std::uint16_t c = 0; c < 8; ++c) place(static_cast<std::uint16_t>(geo::group::over + c));
    for (std::uint16_t c = 0; c < 8; ++c) place(static_cast<std::uint16_t>(geo::group::marks + c));
    // Over the marks lie the canal names (passes/GroundText.cpp: the next pass's slot), then the shadows: the tail,
    // laid out after every pass's ground (frame::Pass::ground_tail_slots).
    const std::int64_t head = std::min<std::int64_t>(cursor, shaders::PAINTER_SLOTS / 2);
    cursor = 0;
    f.tail[geo::group::shadow] = f.used[geo::group::shadow];
    place(geo::group::shadow);
    f.slots = static_cast<std::uint32_t>(head);
    f.tail_slots = static_cast<std::uint32_t>(std::min<std::int64_t>(cursor, (shaders::PAINTER_SLOTS / 2) - head));
}

// ---------------------------------------------------------------- the GPU

gpu::Result<void> Ground::prepare(const frame::PassContext& context) {
    release();
    return make_pipelines(context);
}

void Ground::release() noexcept {
    pipelines_.reset();
    words_.reset();
    drew_soft_ = false;
}

gpu::Result<void> Ground::make_pipelines(const frame::PassContext& context) {
    auto p = std::make_unique<Pipelines>();
    p->device = &context.device;
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(module, frame::make_module(context, "ground_ground", spirv::ground_ground));
    p->module = std::move(module);
    // set 2: the frame's words (one set per frame slot, each its own buffer); the soft shadows' compute stages read
    // them too
    constexpr vk::ShaderStageFlags stages =
        vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment | vk::ShaderStageFlagBits::eCompute;
    const std::array bindings{gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = stages}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    p->set_layout = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 2));
    p->pool = std::move(pool);
    for (vk::raii::DescriptorSet& s : p->sets) {
        ATLAS_GPU_LET(set, gpu::allocate_set(d, p->pool, p->set_layout));
        s = std::move(set);
    }
    // set 3: the soft shadows' grid, their blurred mask, and the grid as words for the compute stages (Shadows.cpp)
    const std::array soft_bindings{
        gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = stages},
        gpu::Binding{
            .slot = 1, .type = vk::DescriptorType::eStorageBuffer, .stages = vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{
            .slot = 2, .type = vk::DescriptorType::eStorageBuffer, .stages = vk::ShaderStageFlagBits::eCompute}};
    ATLAS_GPU_LET(soft_layout, gpu::make_set_layout(d, soft_bindings));
    p->soft_set_layout = std::move(soft_layout);
    const std::array<std::span<const gpu::Binding>, 1> soft_kinds{soft_bindings};
    ATLAS_GPU_LET(soft_pool, gpu::make_pool(d, soft_kinds, 1));
    p->soft_pool = std::move(soft_pool);
    ATLAS_GPU_LET(soft_set, gpu::allocate_set(d, p->soft_pool, p->soft_set_layout));
    p->soft_set = std::move(soft_set);
    const std::array<vk::DescriptorSetLayout, 2> extra{*p->set_layout, *p->soft_set_layout};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    p->layout = std::move(layout);
    ATLAS_GPU_CHECK(make_fill_pipelines(context, *p));
    ATLAS_GPU_CHECK(make_water_pipelines(context, *p));
    ATLAS_GPU_CHECK(make_sky_pipelines(context, *p));
    ATLAS_GPU_CHECK(make_line_pipelines(context, *p));
    ATLAS_GPU_CHECK(make_shadow_pipelines(context, *p));
    pipelines_ = std::move(p);
    words_ = std::make_unique<Words>();
    return {};
}

namespace {

/// The scene subpass's blends: `b` on its colour attachment, the composite's untouched (the dynamic route at 1x).
void scene_blend(gpu::GraphicsDesc& d, const gpu::Blend& b) {
    d.blends[0] = b;
    for (std::uint32_t i = 1; i < d.target.colour_count && i < d.blends.size(); ++i)
        d.blends[i] = gpu::Blend::untouched();
}

} // namespace

gpu::GraphicsDesc ground_desc(const frame::PassContext& context, const Ground::Pipelines& p, std::string_view name,
                              const char* vertex, const char* fragment) {
    gpu::GraphicsDesc d{.label = {.name = name, .module = "ground_ground", .configuration = context.configuration},
                        .module = *p.module,
                        .vertex = vertex,
                        .fragment = fragment,
                        .layout = *p.layout,
                        .target = context.pass.scene_target()};
    scene_blend(d, gpu::Blend::opaque());
    return d;
}

void colour_off(gpu::GraphicsDesc& d) { scene_blend(d, gpu::Blend::untouched()); }

void translucent(gpu::GraphicsDesc& d) {
    scene_blend(d, gpu::Blend::over());
    d.depth_test = true;
    d.depth_write = false;
    d.depth_op = vk::CompareOp::eGreater;
}

void opaque_depth(gpu::GraphicsDesc& d) {
    d.depth_test = true;
    d.depth_write = true;
    d.depth_op = vk::CompareOp::eGreater;
}

gpu::Result<void> Ground::make_fill_pipelines(const frame::PassContext& context, Pipelines& p) const {
    // the fills: opaque, top first, at their painter depth
    gpu::GraphicsDesc fill = ground_desc(context, p, "ground fills", "fill_vs", "colour_fs");
    opaque_depth(fill);
    ATLAS_GPU_LET(f, frame::make_pipeline(context, fill, frame::Overdraw::opaque));
    p.fill = std::move(f);
    gpu::GraphicsDesc plate = ground_desc(context, p, "ground plate", "plate_vs", "colour_fs");
    opaque_depth(plate);
    ATLAS_GPU_LET(pl, frame::make_pipeline(context, plate, frame::Overdraw::opaque));
    p.plate = std::move(pl);
    // the canopy: its rings' union in the stencil, then one composite at .55 where it is set, clearing it
    gpu::GraphicsDesc un = ground_desc(context, p, "ground canopy union", "fill_vs", "colour_fs");
    colour_off(un);
    un.stencil_test = true;
    un.stencil = {.failOp = vk::StencilOp::eKeep,
                  .passOp = vk::StencilOp::eReplace,
                  .depthFailOp = vk::StencilOp::eReplace,
                  .compareOp = vk::CompareOp::eAlways,
                  .compareMask = 0,
                  .writeMask = ground_stencil::canopy,
                  .reference = ground_stencil::canopy};
    ATLAS_GPU_LET(u, frame::make_pipeline(context, un, frame::Overdraw::none));
    p.canopy_union = std::move(u);
    gpu::GraphicsDesc co = ground_desc(context, p, "ground canopy composite", "screen_vs", "flat_fs");
    translucent(co);
    co.stencil_test = true;
    co.stencil = {.failOp = vk::StencilOp::eKeep,
                  .passOp = vk::StencilOp::eZero,
                  .depthFailOp = vk::StencilOp::eZero,
                  .compareOp = vk::CompareOp::eEqual,
                  .compareMask = ground_stencil::canopy,
                  .writeMask = ground_stencil::canopy,
                  .reference = ground_stencil::canopy};
    ATLAS_GPU_LET(c, frame::make_pipeline(context, co, frame::Overdraw::translucent));
    p.canopy_composite = std::move(c);
    return {};
}

void Ground::write_words(const frame::FrameState& state) {
    if (!state.view) return;
    Words& w = *words_;
    const GroundFrame& f = frame_;
    std::vector<std::uint32_t>& v = w.w;
    v.clear();
    // the tile table: every drawn tile, then the frame's own place (the plate's)
    w.tiles = 0;
    for (const DrawnTile& t : f.tiles) {
        const shaders::TilePlace& p = t.place;
        for (const float x :
             {p.offset.x, p.offset.y, p.offset.z, p.offset.w, p.scale.x, p.scale.y, p.scale.z, p.scale.w}) {
            v.push_back(bits_of(x));
        }
        const scene::HeapRange& g = t.pass->ranges[0];
        const scene::HeapRange& l = t.pass->ranges[1];
        const geo::GroundLayout& lay = t.ground->layout;
        v.push_back(g.offset / 4);
        v.push_back(lay.vertices.first);
        v.push_back(lay.fills.first);
        v.push_back(l.offset / 4);
        v.push_back(lay.lines_at.first);
        v.push_back(0); // the bits' words, below
        v.push_back(0);
        v.push_back(0);
        v.push_back(lay.shadow_points.first);
        v.push_back(lay.shadows.first);
        // the shadows the soft shadows' box measures: none where the shadows' draw draws none
        const bool casts = lay.shadow_draw.count != 0 && static_cast<bool>(g) && !t.ground->no_shadows;
        v.push_back(casts ? static_cast<std::uint32_t>(t.ground->shadows.size()) : 0U);
        v.push_back(0);
    }
    w.plate_tile = static_cast<std::uint32_t>(f.tiles.size());
    {
        const shaders::TilePlace p = state.view->tile_place({0.0, 0.0, 0.0}, 1.0, 1.0);
        for (const float x :
             {p.offset.x, p.offset.y, p.offset.z, p.offset.w, p.scale.x, p.scale.y, p.scale.z, p.scale.w}) {
            v.push_back(bits_of(x));
        }
        v.insert(v.end(), tile_words - 8, 0U);
    }
    // the slot table: per group its absolute base (signed) and stride
    w.slots = static_cast<std::uint32_t>(v.size());
    for (std::size_t g = 0; g < geo::group::count; ++g) {
        const std::int64_t base =
            static_cast<std::int64_t>(f.tail[g] ? first_tail_slot() : first_ground_slot()) + f.base[g];
        v.push_back(static_cast<std::uint32_t>(static_cast<std::int32_t>(base)));
        v.push_back(f.stride[g]);
    }
    // the theme's colours the fills name: th['ground'], th['road'] (five times darker again at night), th['water']
    w.theme = static_cast<std::uint32_t>(v.size());
    const style::ThemeBlock& th = state.theme;
    auto rgba = [](const style::Rgb8& c) { return geo::Rgba{.r = c.r, .g = c.g, .b = c.b, .a = 255}; };
    const geo::Rgba road = f.night ? geo::darken(rgba(th.road), 0.2) : rgba(th.road);
    v.push_back(rgba(th.ground).packed());
    v.push_back(road.packed());
    v.push_back(rgba(th.water).packed());
    v.push_back(0);
    // each tile's bits: its fills drawn (at the street, for the water), its water pieces drawn at their level, its
    // shadows drawn
    for (std::size_t i = 0; i < f.tiles.size(); ++i) {
        const DrawnTile& t = f.tiles[i];
        const auto n = static_cast<std::uint32_t>(t.ground->fills.size());
        const std::uint32_t words = (n + 31) / 32;
        const auto street = static_cast<std::uint32_t>(v.size());
        v.insert(v.end(), std::size_t{words} * 2, 0U);
        const std::uint32_t level = street + words;
        for (std::uint32_t k = 0; k < n; ++k) {
            const std::uint8_t d = f.drawn[t.decisions + k];
            if ((d & drawn_bit::street) != 0) v[street + (k >> 5U)] |= 1U << (k & 31U);
            if ((d & drawn_bit::level) != 0) v[level + (k >> 5U)] |= 1U << (k & 31U);
        }
        const auto ns = static_cast<std::uint32_t>(t.ground->shadows.size());
        const auto shadows = static_cast<std::uint32_t>(v.size());
        v.insert(v.end(), std::size_t{(ns + 31) / 32}, 0U);
        for (std::uint32_t k = 0; k < ns; ++k) {
            if (f.shadow_drawn[t.shadows + k] != 0) v[shadows + (k >> 5U)] |= 1U << (k & 31U);
        }
        v[w.tiles + (i * tile_words) + 13] = street;
        v[w.tiles + (i * tile_words) + 14] = level;
        v[w.tiles + (i * tile_words) + 15] = shadows;
    }
    // the lines' runs and segments
    write_lines(state, v, w.runs, w.instances);
    // into this frame slot's buffer (a frame slot's previous frame has finished: its buffer and set are free)
    const std::uint32_t slot = state.slot & 1U;
    Pipelines& p = *pipelines_;
    const std::size_t bytes = std::max<std::size_t>(v.size() * 4, 256);
    if (!p.buffers[slot] || p.buffers[slot].size() < bytes) {
        auto made =
            p.device->memory().buffer(bytes * 2, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
        if (!made) {
            log::error("vk ground: the frame's words ({} bytes): {}", bytes * 2, made.error().text());
            w.ready = false;
            return;
        }
        p.buffers[slot] = std::move(*made);
        gpu::SetWriter(*p.sets[slot])
            .buffer(0, vk::DescriptorType::eStorageBuffer, p.buffers[slot].handle())
            .apply(p.device->handle());
    }
    std::memcpy(p.buffers[slot].bytes().data(), v.data(), v.size() * 4);
    p.buffers[slot].flush();
    w.slot = slot;
    w.ready = true;
}

void Ground::update(const frame::FrameState& state) {
    if (!frame_.valid || pipelines_ == nullptr || words_ == nullptr) return;
    write_words(state);
}

void Ground::push(const frame::Recording& r, std::uint32_t flags, std::uint32_t slot_value) const {
    const Words& w = *words_;
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {flags | (frame_.night ? ground_flag::night : 0U), slot_value, 0U, 0U};
    r.frame.cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
}

void Ground::record(const frame::Recording& r, std::uint32_t phase) {
    if (!frame_.valid || pipelines_ == nullptr || words_ == nullptr || !words_->ready) return;
    if (phase == phase_shadow_mask) {
        // before the main pass: it binds every set it draws with
        record_shadow_mask(r);
        return;
    }
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *pipelines_->layout, 2, *pipelines_->sets[words_->slot],
                           {});
    switch (phase) {
    case phase_opaque:
        // top first: what lies above the water, the water, the land, the plate, the sky
        record_fills(r, true);
        record_water_opaque(r);
        record_fills(r, false);
        record_plate(r);
        record_sky(r);
        break;
    case phase_canopy: record_canopy(r); break;
    case phase_water_tint: record_water_tint(r); break;
    case phase_water_end: record_water_end(r); break;
    case phase_over:
    case phase_marks: record_lines(r, phase); break;
    case phase_shadows: record_shadows(r); break;
    default: break;
    }
}

void Ground::record_fills(const frame::Recording& r, bool above) {
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    bool bound = false;
    for (std::size_t i = 0; i < frame_.tiles.size(); ++i) {
        const DrawnTile& t = frame_.tiles[i];
        const geo::Words draw = above ? t.ground->layout.above : t.ground->layout.below;
        if (draw.count == 0 || !t.pass->ranges[0]) continue;
        if (!bound) {
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->fill);
            push(r, 0, 0);
            bound = true;
        }
        cmd.draw(draw.count, 1, (t.pass->ranges[0].offset / 4) + draw.first, static_cast<std::uint32_t>(i));
        r.counters.draw(above ? "ground above" : "ground land", draw.count / 3);
    }
}

void Ground::record_plate(const frame::Recording& r) {
    if (!frame_.plate) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->plate);
    const Words& w = *words_;
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::plate, 0, 0), w.plate_tile, 0U};
    pc.rows = {0.0F, 0.0F, 1.0F, static_cast<float>(frame_.ground_r)};
    pc.colour = {0U, 0U, w.w[w.theme + static_cast<std::uint32_t>(geo::ThemeColour::ground)], 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(6, 1, 0, 0);
    r.counters.draw("ground plate", 2);
}

void Ground::record_canopy(const frame::Recording& r) {
    if (!frame_.canopy) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    // the union of the drawn canopy rings in the stencil, then one composite at .55 where it is set
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->canopy_union);
    push(r, 0, 0);
    for (std::size_t i = 0; i < frame_.tiles.size(); ++i) {
        const DrawnTile& t = frame_.tiles[i];
        const geo::Words draw = t.ground->layout.canopy;
        if (draw.count == 0 || !t.pass->ranges[0]) continue;
        cmd.draw(draw.count, 1, (t.pass->ranges[0].offset / 4) + draw.first, static_cast<std::uint32_t>(i));
        r.counters.draw("ground canopy", draw.count / 3);
    }
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->canopy_composite);
    const Words& w = *words_;
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::canopy, 0, 0), 0U, 0U};
    pc.rows = {0.0F, 0.0F, 0.55F, 0.0F};
    pc.colour = {0U, 0U, geo::Rgba{.r = 0, .g = 0, .b = 0, .a = 255}.packed(), 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground canopy", 1);
}

void Ground::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    if (phase != phase_opaque || !frame_.valid) return;
    out.layer(twin::Layer::ground);
    // every element hangs from the frame's root group (the SVG's `<g clip-path="url(#vb)">`). render defines its `mat`
    // filter with weather alone: draw_under's group names it always (without weather a reference to nothing, which
    // the parser drops and the renders draw unfiltered), and render opens its group around the areas, the roads and
    // draw_over only with weather
    const std::int32_t mat = frame_.weather ? out.filter("mat") : twin::none;
    const std::int32_t above = frame_.weather ? out.group({.node = {.filter = mat}, .parent = out.root()}) : out.root();
    emit_sky(state, out);
    emit_fills(state, out, mat, above);
    emit_water(state, out);
    emit_lines(state, out, above);
    emit_shadows(state, out);
}

} // namespace atlas::passes
