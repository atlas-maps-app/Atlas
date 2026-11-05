#include "geometry/Shopfronts.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/Doors.hpp"
#include "geometry/StreetWall.hpp"
#include "geometry/Trees.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"
#include "tiles/layers/Water.hpp"

#include <algorithm>
#include <limits>
#include <unordered_map>

namespace atlas::geometry {

namespace {

namespace tl = atlas::tiles;

/// How far a shopfront's board reaches from its point on the wall: the night's spill 2.8 m to each side and 2 m out,
/// the sign 3.62 m up, with room to spare.
constexpr double board_reach = 6.0;
/// A blade's sphere about its point: its corners lie within 0.72 m of it.
constexpr float blade_reach = 1.5F;
/// A table's about its top's centre: its parasol's ring 1.3 m out and 1.4 m up, its apex 1.9 m up, its chairs.
constexpr float table_reach = 2.5F;

/// An open place's glow's sphere about its point 1.5 m up: its circle's 3 px at least out to 13 km away.
constexpr float glow_reach = 40.0F;

/// The most depth of a shopfront's node on the ground at which the pass may draw it (passes/Shopfronts.cpp: 230 m a
/// metre up), with the metre that height may add looking down and the metre its tiles' box test spares.
constexpr float shop_depth = 232.0F;

/// outdoor_seating's values that set out a terrace (shopfront's).
[[nodiscard]] bool seats(std::string_view v) noexcept {
    return v == "yes" || v == "sidewalk" || v == "pedestrian_zone" || v == "limited";
}

[[nodiscard]] ShopCategory category_of(std::string_view cat) noexcept {
    if (cat == "food") return ShopCategory::food;
    if (cat == "grocery") return ShopCategory::grocery;
    if (cat == "stay") return ShopCategory::stay;
    return ShopCategory::other;
}

/// The tile's water: its rings that are no islands, each with its box (the pieces stage P cuts S['water'] into).
[[nodiscard]] std::vector<WaterPiece> water_of(const tl::Tile& tile, const tl::Frame& frame) {
    std::vector<WaterPiece> out;
    const tl::Layer* l = tile.layer(tl::LayerId::water);
    if (l == nullptr) return out;
    for (std::uint32_t i = 0; i < l->count; ++i) {
        if (l->boolean(tl::water::hole, i).value_or(false)) continue;
        const tl::RingView r = l->ring(tl::water::ring, i);
        if (r.size() < 3) continue;
        WaterPiece w;
        w.ring.reserve(r.size());
        for (std::size_t k = 0; k < r.size(); ++k) w.ring.emplace_back(frame.x(r.lon[k]), frame.z(r.lat[k]));
        w.x0 = w.x1 = w.ring[0].x;
        w.z0 = w.z1 = w.ring[0].z;
        for (const V2& p : w.ring) {
            w.x0 = std::min(w.x0, p.x);
            w.x1 = std::max(w.x1, p.x);
            w.z0 = std::min(w.z0, p.z);
            w.z1 = std::max(w.z1, p.z);
        }
        out.push_back(std::move(w));
    }
    return out;
}

} // namespace

std::optional<std::uint32_t> style_colour(std::string_view hex) noexcept {
    if (hex.size() != 7 || hex[0] != '#') return std::nullopt;
    std::uint32_t v = 0;
    for (std::size_t i = 1; i < hex.size(); ++i) {
        const char c = hex[i];
        std::uint32_t d = 0;
        if (c >= '0' && c <= '9') {
            d = static_cast<std::uint32_t>(c - '0');
        } else if (c >= 'a' && c <= 'f') {
            d = static_cast<std::uint32_t>(c - 'a' + 10);
        } else if (c >= 'A' && c <= 'F') {
            d = static_cast<std::uint32_t>(c - 'A' + 10);
        } else {
            return std::nullopt;
        }
        v = (v << 4U) | d;
    }
    return v;
}

ShopFrame shop_frame(const Shopfront& s) noexcept {
    // nrm = vnorm((uz, 0, -ux))
    const double l = pymath::sqrt((s.uz * s.uz) + (s.ux * s.ux));
    return {.qx = s.qx, .qz = s.qz, .ux = s.ux, .uz = s.uz, .nx = s.uz / l, .nz = -s.ux / l};
}

V2 shop_point(const ShopFrame& f, double u, double off) noexcept {
    return {f.qx + (f.ux * u) + (f.nx * off), f.qz + (f.uz * u) + (f.nz * off)};
}

TileShopfronts build_shopfronts(const ShopfrontInput& in) {
    TileShopfronts out;
    const tl::Frame& frame = in.head->frame;
    out.water = water_of(*in.tile, frame);
    const tl::Layer* things = in.tile->layer(tl::LayerId::thing);
    const tl::Layer* buildings = in.tile->layer(tl::LayerId::building);
    if (things == nullptr || buildings == nullptr) return out;
    std::unordered_map<std::int64_t, std::uint32_t> building_of;
    for (std::uint32_t i = 0; i < buildings->count; ++i)
        building_of.emplace(buildings->integer(tl::building::index, i), i);
    std::vector<V2> ring;
    // each distinct opening_hours once
    std::unordered_map<std::string, std::uint32_t> hours_at;
    const auto hours_of = [&](std::uint32_t i) {
        const auto oh = things->string(tl::thing::tag_opening_hours, i);
        if (!oh || oh->empty()) return no_hours;
        const auto [at, made] = hours_at.try_emplace(std::string(*oh), static_cast<std::uint32_t>(out.hours.size()));
        if (made) out.hours.emplace_back(*oh);
        return at->second;
    };
    for (std::uint32_t i = 0; i < things->count; ++i) {
        // S['pois'] with opening hours: the night's glows
        if (things->boolean(tl::thing::poi, i).value_or(false)) {
            const std::uint32_t h = hours_of(i);
            if (h != no_hours) {
                const tl::GridPoint p = things->point(tl::thing::pos, i);
                out.places.push_back({.node = static_cast<std::uint32_t>(things->integer(tl::thing::index, i)),
                                      .id = things->integer(tl::thing::id, i),
                                      .x = frame.x(p.lon),
                                      .z = frame.z(p.lat),
                                      .hours = h,
                                      .cell = things->cell_of(i)});
            }
        }
        // facade_details' biz: a place style other than a service's
        const auto cat = things->string(tl::thing::style_cat, i);
        if (!cat || *cat == "service") continue;
        const auto building = things->nullable_integer(tl::thing::building, i);
        const auto wall = things->nullable_integer(tl::thing::wall, i);
        if (!building || !wall) continue;
        const auto at = building_of.find(*building);
        if (at == building_of.end()) continue;
        const tl::RingView r = buildings->ring(tl::building::ring, at->second);
        if (*wall < 0 || static_cast<std::size_t>(*wall) >= r.size()) continue;
        ring.clear();
        for (std::size_t k = 0; k < r.size(); ++k) ring.emplace_back(frame.x(r.lon[k]), frame.z(r.lat[k]));
        Shopfront s;
        const tl::GridPoint p = things->point(tl::thing::pos, i);
        s.x = frame.x(p.lon);
        s.z = frame.z(p.lat);
        const auto k = static_cast<std::size_t>(*wall);
        const std::optional<StreetWall> w = street_wall_at(ring[k], ring[(k + 1) % ring.size()], s.x, s.z);
        if (!w) continue;
        s.node = static_cast<std::uint32_t>(things->integer(tl::thing::index, i));
        s.id = things->integer(tl::thing::id, i);
        s.building = buildings->integer(tl::building::id, at->second);
        s.qx = w->qx;
        s.qz = w->qz;
        s.ux = w->ux / w->length;
        s.uz = w->uz / w->length;
        s.category = category_of(*cat);
        s.colour = style_colour(things->string(tl::thing::style_col, i).value_or("")).value_or(0U);
        const auto bg = things->string(tl::thing::style_bg, i);
        const auto fg = things->string(tl::thing::style_fg, i);
        if (bg && !bg->empty()) s.sign = style_colour(*bg);
        if (fg && !fg->empty()) s.text = style_colour(*fg);
        s.kind = std::string(things->string(tl::thing::style_kind, i).value_or(""));
        s.brand = std::string(things->string(tl::thing::style_brand, i).value_or(""));
        s.hours = hours_of(i);
        s.seating =
            s.category == ShopCategory::food && seats(things->string(tl::thing::tag_outdoor_seating, i).value_or(""));
        // its items, each cut at its node and filed in its building's cell: the board as a door's (its building's
        // centroid at half its height, less 0.03), its blade and its tables at their own points
        const V2 c = centroid(ring);
        const double h = buildings->decimal(tl::building::h, at->second).value_or(0.0);
        const std::uint8_t cell = buildings->cell_of(at->second);
        const std::uint64_t owner = scene::owner_key(owner_node, static_cast<std::uint64_t>(s.id));
        const std::uint64_t of = scene::owner_key(scene::owner_kind_area, static_cast<std::uint64_t>(s.building));
        const auto item = [&](double x, double y, double z, double offset, std::uint32_t k, std::uint16_t kind,
                              std::uint32_t slots, float radius) {
            out.items.push_back({.x = x,
                                 .y = y,
                                 .z = z,
                                 .offset = offset,
                                 .cut_x = s.x,
                                 .cut_z = s.z,
                                 .order = scene::order_of(facades_list, (std::uint64_t{s.node} * 8U) + k),
                                 .kind = kind,
                                 .flags = scene::item_flag::radius,
                                 .slots = {slots, slots, slots, slots},
                                 .min_depth = -std::numeric_limits<float>::infinity(),
                                 .max_depth = shop_depth,
                                 .radius = radius,
                                 .tile = 0,
                                 .cell = cell,
                                 .owner = owner,
                                 .building = of});
        };
        const double reach = pymath::hypot(s.qx - c.x, s.qz - c.z) + pymath::fabs(h / 2) + board_reach;
        item(c.x, h / 2, c.z, -0.03, 0, in.board, board_slots, static_cast<float>(reach));
        const ShopFrame f = shop_frame(s);
        const V2 b = shop_point(f, shop_half_width + 0.35, 0.6);
        item(b.x, 3.15, b.z, 0.0, 1, in.blade, blade_slots, blade_reach);
        if (s.seating) {
            for (std::uint32_t t = 0; t < table_places.size(); ++t) {
                const V2 q = shop_point(f, table_places.at(t)[0], table_places.at(t)[1]);
                item(q.x, 0.75, q.z, 0.0, 2 + t, in.table, table_slots, table_reach);
            }
        }
        out.shops.push_back(std::move(s));
    }
    // the glows' items after every shopfront's, each at its node 1.5 m up, its radius cut there
    for (const OpenPlace& p : out.places) {
        out.items.push_back({.x = p.x,
                             .y = 1.5,
                             .z = p.z,
                             .offset = 0.0,
                             .cut_x = p.x,
                             .cut_z = p.z,
                             .order = scene::order_of(glows_list, p.node),
                             .kind = in.glow,
                             .flags = scene::item_flag::radius,
                             .slots = {glow_slots, glow_slots, glow_slots, glow_slots},
                             .min_depth = -std::numeric_limits<float>::infinity(),
                             .max_depth = std::numeric_limits<float>::infinity(),
                             .radius = glow_reach,
                             .tile = 0,
                             .cell = p.cell,
                             .owner = scene::owner_key(owner_node, static_cast<std::uint64_t>(p.id)),
                             .building = 0});
    }
    return out;
}

} // namespace atlas::geometry
