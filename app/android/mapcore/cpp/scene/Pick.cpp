// What a press on the map points at (scene/Pick.hpp).
#include "scene/Pick.hpp"

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "tiles/Store.hpp"
#include "tiles/layers/Buildings.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <unordered_map>
#include <vector>

namespace atlas::scene {

namespace {

namespace tl = atlas::tiles;

constexpr double none = std::numeric_limits<double>::infinity();
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

/// The least distance along the ray (eye + t dir, t > 0, dir of unit length) at which it meets the prism of `ring` from
/// `base` to `top`: a wall (each edge's face between them), or its lid at `top` from above; none where it meets
/// neither.
[[nodiscard]] double meets(std::span<const P2> ring, double base, double top, const view::Vec3& eye,
                           const view::Vec3& dir) noexcept {
    double best = none;
    if (dir.y < 0.0) {
        const double t = (top - eye.y) / dir.y;
        if (t > 0.0 && inside(ring, eye.x + (t * dir.x), eye.z + (t * dir.z))) best = t;
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
        if (t <= 0.0 || t >= best || s < 0.0 || s > 1.0) continue;
        const double y = eye.y + (t * dir.y);
        if (y >= base && y <= top) best = t;
    }
    return best;
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

std::optional<Picked> pick_building(const tiles::Store& store, std::span<const PickPack> packs,
                                    const view::GroundFrame& ground, const view::Vec3& eye, const view::Vec3& ray) {
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
                    const double t = meets(ring, base, *top, eye, dir);
                    if (t == none || (best && t >= best->distance)) continue;
                    const std::int64_t id = l->integer(tl::building::id, i);
                    Picked p{.relation = l->string(tl::building::otype, i) == "relation",
                             .id = id,
                             .distance = t,
                             .latitude = lat_of(eye.z + (t * dir.z)),
                             .longitude = lon_of(eye.x + (t * dir.x))};
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
