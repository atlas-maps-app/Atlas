// What a press on the map points at (scene/Pick.hpp).
#include "scene/Pick.hpp"

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "geometry/RoofShapes.hpp"
#include "style/Palette.hpp"
#include "tiles/Store.hpp"
#include "tiles/layers/Buildings.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace atlas::scene {

namespace {

namespace tl = atlas::tiles;

constexpr double none = std::numeric_limits<double>::infinity();
/// The buildings' and parts' slot words (their colours' slots in the user's palette).
constexpr std::uint8_t wall_slot = tl::slot_of(tl::building::columns, "wall_slot");
constexpr std::uint8_t roof_slot = tl::slot_of(tl::building::columns, "roof_slot");
/// How far around the ray's track a chunk's reach may stand, metres (the box's margin on the grid).
constexpr double track_margin = 50.0;

/// A point of the street, frame metres.
struct P2 {
    double x = 0.0;
    double z = 0.0;
};

/// Whether (x, z) lies in `ring` (even-odd).
[[nodiscard]] bool inside(std::span<const P2> ring, double x, double z) noexcept {
    bool in = false;
    for (std::size_t i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
        const P2& a = ring[i];
        const P2& b = ring[j];
        if ((a.z > z) != (b.z > z) && x < ((b.x - a.x) * (z - a.z) / (b.z - a.z)) + a.x) in = !in;
    }
    return in;
}

/// Where a ray met a prism: its distance along the ray (none: not met), and the wall it met (the ring's side from
/// vertex `edge`, `s` of the way along it, at height `y`) where it met no lid.
struct Met {
    double t = none;
    bool lid = false;
    std::size_t edge = 0;
    double s = 0.0;
    double y = 0.0;
};

/// The least distance along the ray (eye + t dir, t > 0, dir of unit length) at which it meets the prism of `ring` from
/// `base` to `top`: a wall (each edge's face between them), or its lid at `top` from above; none where it meets
/// neither.
[[nodiscard]] Met meets(std::span<const P2> ring, double base, double top, const view::Vec3& eye,
                        const view::Vec3& dir) noexcept {
    Met best;
    if (dir.y < 0.0) {
        const double t = (top - eye.y) / dir.y;
        if (t > 0.0 && inside(ring, eye.x + (t * dir.x), eye.z + (t * dir.z))) best = {.t = t, .lid = true, .y = top};
    }
    for (std::size_t i = 0; i < ring.size(); ++i) {
        const P2& a = ring[i];
        const P2& b = ring[(i + 1) % ring.size()];
        // eye.xz + t dir.xz = a + s (b - a), by the cross products of the street's vectors
        const double ex = b.x - a.x;
        const double ez = b.z - a.z;
        const double den = (dir.x * ez) - (dir.z * ex);
        if (den == 0.0) continue;
        const double wx = a.x - eye.x;
        const double wz = a.z - eye.z;
        const double t = ((wx * ez) - (wz * ex)) / den;
        const double s = ((wx * dir.z) - (wz * dir.x)) / den;
        if (t <= 0.0 || t >= best.t || s < 0.0 || s > 1.0) continue;
        const double y = eye.y + (t * dir.y);
        if (y >= base && y <= top) best = {.t = t, .lid = false, .edge = i, .s = s, .y = y};
    }
    return best;
}

/// The roof shapes geometry/Walls.cpp gives their roof's height (its `pitched`): any other, or one of them without a
/// height, is drawn as a flat top.
constexpr std::array<std::string_view, 5> pitched = {"gabled", "pyramidal", "hipped", "skillion", "mansard"};

/// A roof's shape as geometry/Walls.cpp draws it (a quadruple saltbox hipped).
[[nodiscard]] std::string_view shape_of(std::string_view shape) noexcept {
    return shape == "quadruple_saltbox" ? std::string_view{"hipped"} : shape;
}

/// The roof's height as geometry/Walls.cpp lays it out: the tiles' roof_h where the shape is pitched, else 0 (a flat
/// top).
[[nodiscard]] double pitched_height(const PickRoof& roof) noexcept {
    return std::ranges::find(pitched, shape_of(roof.shape)) != pitched.end() ? roof.roof_h : 0.0;
}

/// The grid's integer of `degrees` (degrees x 1e7), the box's edge rounded outward by `out` (-1 or 1).
[[nodiscard]] std::int32_t grid_of(double degrees, int out) noexcept {
    const double g = degrees * 1e7;
    return static_cast<std::int32_t>(out < 0 ? std::floor(g) : std::ceil(g));
}

} // namespace

view::Vec3 ray_of(const view::View& view, double x, double y) noexcept {
    const view::Camera& c = view.camera();
    const double density = view.device().density;
    const double kx = ((x / density) - c.cx()) / c.focal();
    const double ky = (c.cy() - (y / density)) / c.focal();
    return {c.forward().x + (kx * c.right().x) + (ky * c.up().x), c.forward().y + (kx * c.right().y) + (ky * c.up().y),
            c.forward().z + (kx * c.right().z) + (ky * c.up().z)};
}

bool in_walls_colour(std::span<const geometry::V2> poly, std::size_t edge, double s, double y, double top,
                     const PickRoof& roof) {
    namespace gm = atlas::geometry;
    const double rh = pitched_height(roof);
    const double eaves = top - rh;
    if (y <= eaves) return true;
    const std::string_view shape = shape_of(roof.shape);
    const gm::V2 a = poly[edge];
    const gm::V2 b = poly[(edge + 1) % poly.size()];
    if (shape == "gabled") {
        const gm::Ridge r = gm::ridge_of(poly, roof.across);
        const gm::V3 qa = gm::on_ridge(r, 0.0, top, {a.x, eaves, a.z});
        const gm::V3 qb = gm::on_ridge(r, 0.0, top, {b.x, eaves, b.z});
        if (gm::dist3(qa, qb) >= 0.3) return false; // a slope's side: the slope over it
        // the gable: (a, eaves), (b, eaves) and its apex at the top over the ridge's end, `apex` of the way along
        const double ex = b.x - a.x;
        const double ez = b.z - a.z;
        const double len2 = (ex * ex) + (ez * ez);
        const double mx = ((qa.x + qb.x) / 2) - a.x;
        const double mz = ((qa.z + qb.z) / 2) - a.z;
        const double apex = std::clamp(len2 > 0.0 ? ((mx * ex) + (mz * ez)) / len2 : 0.5, 0.0, 1.0);
        const double reach = s <= apex ? (apex > 0.0 ? s / apex : 1.0) : (apex < 1.0 ? (1.0 - s) / (1.0 - apex) : 1.0);
        return y <= eaves + (rh * reach);
    }
    if (shape == "skillion") {
        // Building::sk_top: the top at the footprint's least projection on the downhill direction, the eaves at its
        // greatest (at least 0.5 m on)
        const gm::V2 dirv = gm::roof_dir(roof.direction, poly);
        double lo = (poly[0].x * dirv.x) + (poly[0].z * dirv.z);
        double hi = lo;
        for (const gm::V2& p : poly) {
            const double pr = (p.x * dirv.x) + (p.z * dirv.z);
            lo = std::min(lo, pr);
            hi = std::max(hi, pr);
        }
        const double span = std::max(0.5, hi - lo);
        const double px = a.x + (s * (b.x - a.x));
        const double pz = a.z + (s * (b.z - a.z));
        return y <= top - ((rh * (((px * dirv.x) + (pz * dirv.z)) - lo)) / span);
    }
    return false;
}

std::optional<Picked> pick_building(const tiles::Store& store, std::span<const PickPack> packs,
                                    const view::GroundFrame& ground, const view::Vec3& eye, const view::Vec3& ray,
                                    bool plain_roofs) {
    const double length = pymath::sqrt((ray.x * ray.x) + (ray.y * ray.y) + (ray.z * ray.z));
    if (!(length > 0.0) || !(ray.y < 0.0) || ground.kx == 0.0 || ground.kz == 0.0) return std::nullopt;
    const view::Vec3 dir{ray.x / length, ray.y / length, ray.z / length};
    // The ray's track on the street: from where it is at the tallest building's height to where it meets the street.
    const double t_street = -eye.y / dir.y;
    const double t_top = std::max(0.0, (pick_top - eye.y) / dir.y);
    const auto lon_of = [&](double x) { return ground.lon0 + (x / ground.kx); };
    const auto lat_of = [&](double z) { return ground.lat0 - (z / ground.kz); };
    const double x0 = eye.x + (t_top * dir.x);
    const double z0 = eye.z + (t_top * dir.z);
    const double x1 = eye.x + (t_street * dir.x);
    const double z1 = eye.z + (t_street * dir.z);
    const double mx = track_margin;
    const tiles::GridBox box{.south = grid_of(lat_of(std::max(z0, z1) + mx), -1),
                             .west = grid_of(lon_of(std::min(x0, x1) - mx), -1),
                             .north = grid_of(lat_of(std::min(z0, z1) - mx), 1),
                             .east = grid_of(lon_of(std::max(x0, x1) + mx), 1)};
    std::optional<Picked> best;
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    Arena arena(std::size_t{4} << 20U, Growth::grows, "a pick's tile");
    std::vector<std::byte> raw;
    std::vector<P2> ring;
    std::vector<geometry::V2> poly;
    std::unordered_map<std::int64_t, bool> outlines; // a tile's outlines: whether each is a relation
    for (const PickPack& pack : packs) {
        for (const tiles::StoreHit& hit : store.reaching(pack.z, box)) {
            if (hit.pack == nullptr || hit.pack->id != pack.id) continue;
            arena.reset();
            const auto tile = store.decode(hit, zstd, decoder, arena, raw);
            if (!tile) continue;
            outlines.clear();
            if (const tl::Layer* b = tile->layer(tl::LayerId::building); b != nullptr) {
                for (std::uint32_t i = 0; i < b->count; ++i) {
                    outlines[b->integer(tl::building::id, i)] = b->string(tl::building::otype, i) == "relation";
                }
            }
            for (const tl::LayerId layer : {tl::LayerId::building, tl::LayerId::part}) {
                const tl::Layer* l = tile->layer(layer);
                if (l == nullptr) continue;
                for (std::uint32_t i = 0; i < l->count; ++i) {
                    // an outline drawn by its parts is met through them; one without a height is not drawn
                    if (layer == tl::LayerId::building && l->boolean(tl::building::hidden, i).value_or(false)) continue;
                    const std::optional<double> top = l->decimal(tl::building::h, i);
                    if (!top) continue;
                    const double base = l->decimal(tl::building::min, i).value_or(0.0);
                    const tl::RingView r = l->ring(tl::building::ring, i);
                    if (r.size() < 3) continue;
                    ring.clear();
                    for (std::size_t k = 0; k < r.size(); ++k) {
                        const double lat = tl::Frame::degrees(r.lat[k]);
                        const double lon = tl::Frame::degrees(r.lon[k]);
                        ring.push_back({.x = ground.x(lat, lon), .z = ground.z(lat, lon)});
                    }
                    const Met m = meets(ring, base, *top, eye, dir);
                    const double t = m.t;
                    if (t == none || (best && t >= best->distance)) continue;
                    const std::int64_t id = l->integer(tl::building::id, i);
                    Picked p{.relation = l->string(tl::building::otype, i) == "relation",
                             .id = id,
                             .distance = t,
                             .latitude = lat_of(eye.z + (t * dir.z)),
                             .longitude = lon_of(eye.x + (t * dir.x))};
                    // the face met, its slot word as geometry::Face::slot holds it (the columns' 10 bits) and its
                    // colour; a guessed roof's shape drawn as the plain box in its place is a flat roof's
                    const bool plain =
                        plain_roofs && l->list_holds(tl::building::guessed, i, tl::building::guessed_roof_shape);
                    const PickRoof roof{
                        .shape =
                            plain ? std::string_view{} : l->string(tl::building::roof, i).value_or(std::string_view{}),
                        .roof_h = l->decimal(tl::building::roof_h, i).value_or(0.0),
                        .across = l->string(tl::building::orient, i) == "across",
                        .direction = l->string(tl::building::tag_roof_direction, i).value_or(std::string_view{})};
                    if (!m.lid) {
                        poly.clear();
                        for (const P2& q : ring) poly.emplace_back(q.x, q.z);
                    }
                    p.roof = m.lid || !in_walls_colour(poly, m.edge, m.s, m.y, *top, roof);
                    const auto word = [&](std::uint8_t column) {
                        return static_cast<std::uint32_t>(l->integer(column, i)) & 0x3FFU;
                    };
                    if (p.roof) {
                        p.slot =
                            pitched_height(roof) == 0.0 ? style::flat_roof_slot(word(roof_slot), id) : word(roof_slot);
                        p.rgb = static_cast<std::uint32_t>(l->integer(tl::building::roof_rgb, i)) & 0xFF'FFFFU;
                    } else {
                        p.slot = word(wall_slot);
                        p.rgb = static_cast<std::uint32_t>(l->integer(tl::building::wall_rgb, i)) & 0xFF'FFFFU;
                    }
                    if (layer == tl::LayerId::part) {
                        p.part = id;
                        if (const auto parent = l->nullable_integer(tl::building::parent_id, i)) {
                            p.id = *parent;
                            const auto it = outlines.find(*parent);
                            p.relation = it != outlines.end() && it->second;
                        }
                    }
                    best = p;
                }
            }
        }
    }
    return best;
}

} // namespace atlas::scene
