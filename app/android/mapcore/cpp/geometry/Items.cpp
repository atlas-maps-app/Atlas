#include "geometry/Items.hpp"

#include "base/pymath/PyMath.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <optional>
#include <string_view>

namespace atlas::geometry {

namespace {

namespace tl = atlas::tiles;

/// The item layer's sources (rendertiles' Schema.ITEM): S['cranes'] 0, S['masts'] 1, S['mtowers'] 2, S['towers'] 3
/// (pylons: their lines draw their towers), S['turbines'] 4.
constexpr std::int64_t source_mast = 1;
constexpr std::int64_t source_tower = 2;
constexpr std::int64_t source_turbine = 4;

/// The line layer's sources: S['power'] 4, S['gantries'] 7, S['conveyors'] 8.
constexpr std::int64_t line_power = 4;
constexpr std::int64_t line_gantry = 7;
constexpr std::int64_t line_conveyor = 8;

/// An owner's kinds in scene::owner_key (AnchorItems' OwnerType).
constexpr std::uint8_t owner_node = 1;
constexpr std::uint8_t owner_way = 2;

/// draw_items' `c[2] < 5: continue`.
constexpr float least_depth = 5.0F;

/// Python's `x or default` for a height: none or 0 is not mapped.
[[nodiscard]] double or_default(std::optional<double> v, double otherwise) noexcept {
    return v && *v != 0.0 ? *v : otherwise;
}

/// An item's place in draw_items' order: its group, its place in its list and its segment.
[[nodiscard]] std::uint64_t order(RegionItemKind kind, std::uint32_t index, std::uint32_t segment) noexcept {
    const std::uint64_t group = region_item_group[static_cast<std::size_t>(kind)];
    return scene::order_of(region_items_list, (group << 37U) | (std::uint64_t{index} << 16U) | (segment & 0xFFFFU));
}

/// The sphere about an item's point that holds what it draws: a reach about it and a height above or below it.
[[nodiscard]] float sphere(double reach, double up) noexcept {
    return static_cast<float>(pymath::hypot(reach, up) + 0.01);
}

void points(const tl::Layer& layer, const tl::Frame& frame, std::uint16_t kind, TileRegionItems& out) {
    for (std::uint32_t i = 0; i < layer.count; ++i) {
        const std::int64_t source = layer.integer(tl::item::source, i);
        if (source != source_tower && source != source_turbine && source != source_mast) continue;
        RegionItem it;
        const tl::GridPoint p = layer.point(tl::item::pos, i);
        it.x = layer.decimal(tl::item::x, i).value_or(frame.x(p.lon));
        it.z = layer.decimal(tl::item::z, i).value_or(frame.z(p.lat));
        it.id = layer.nullable_integer(tl::item::id, i).value_or(0);
        it.index = static_cast<std::uint32_t>(layer.integer(tl::item::index, i));
        const std::optional<double> h = layer.decimal(tl::item::h, i);
        const auto tag = [&](std::string_view key) { return layer.tag(tl::item::tags, i, key); };
        double y = 0;     // the depth's point, draw_items' cam.cam((x, y, z))
        double reach = 0; // what it draws about its axis
        double up = 0;    // and above or below that point
        if (source == source_tower) {
            // tw['h']: towers are mapped with a height; `cab = t.get('aeroway') == 'tower' or t.get('tower:type') in
            // ('observation', 'control')`; rs = 3.2 if cab else max(1.2, H / 30), the cab's widest band 2.7 rs
            it.kind = RegionItemKind::tower;
            it.h = h.value_or(0.0);
            const std::optional<std::string_view> type = tag("tower:type");
            it.cab = tag("aeroway") == "tower" || type == "observation" || type == "control";
            y = it.h / 2;
            reach = it.cab ? 3.2 * 2.7 : pymath::max(1.2, it.h / 30);
            up = std::abs(it.h) / 2;
        } else if (source == source_turbine) {
            // H = tb['h'] or 100.0; Rr = (tb['rotor'] or 100.0) / 2; its hub 2 m upwind, its blades Rr long
            it.kind = RegionItemKind::turbine;
            it.h = or_default(h, 100.0);
            it.rotor = or_default(layer.decimal(tl::item::rotor, i), 100.0) / 2;
            y = it.h / 2;
            reach = it.rotor + 3.0;
            up = (std::abs(it.h) / 2) + it.rotor;
        } else {
            // lighting = t.get('tower:type') == 'lighting'; H = m['h'] or (30.0 if lighting else 25.0); its shaft 0.45
            // m about its axis, its frame of floodlights 1.7 by 0.6 m and 1.3 m above it, a glow 9 m across at night
            it.kind = RegionItemKind::mast;
            it.lighting = tag("tower:type") == "lighting";
            it.h = or_default(h, it.lighting ? 30.0 : 25.0);
            y = it.h / 2;
            reach = 9.0;
            up = (std::abs(it.h) / 2) + 1.3;
        }
        out.specs.push_back({.x = it.x,
                             .y = y,
                             .z = it.z,
                             .offset = 0.0,
                             .cut_x = it.x,
                             .cut_z = it.z,
                             .order = order(it.kind, it.index, 0),
                             .kind = kind,
                             .flags = scene::item_flag::radius,
                             .slots = {},
                             .min_depth = least_depth,
                             .radius = sphere(reach, up),
                             .tile = 0,
                             .owner = scene::owner_key(owner_node, static_cast<std::uint64_t>(it.id))});
        const std::uint32_t slots = region_item_slots(it);
        out.specs.back().slots = {slots, slots, slots, slots};
        out.items.push_back(it);
    }
}

void lines(const tl::Layer& layer, const tl::Frame& frame, std::uint16_t kind, TileRegionItems& out) {
    std::vector<std::array<double, 2>> pts;
    for (std::uint32_t i = 0; i < layer.count; ++i) {
        const std::int64_t source = layer.integer(tl::line::source, i);
        if (source != line_power && source != line_gantry && source != line_conveyor) continue;
        const tl::RingView r = layer.ring(tl::line::line, i);
        if (r.size() < 2) continue;
        pts.clear();
        for (std::size_t k = 0; k < r.size(); ++k) pts.push_back({frame.x(r.lon[k]), frame.z(r.lat[k])});
        const auto index = static_cast<std::uint32_t>(layer.integer(tl::line::index, i));
        const std::int64_t id = layer.nullable_integer(tl::line::id, i).value_or(0);
        const RegionItemKind k = source == line_power    ? RegionItemKind::power
                                 : source == line_gantry ? RegionItemKind::gantry
                                                         : RegionItemKind::conveyor;
        // H = gt['h'] or (7.0 if kind == 'gantry' else 6.0); a power line's 38
        const double height = k == RegionItemKind::power
                                  ? power_height
                                  : or_default(layer.decimal(tl::line::h, i), k == RegionItemKind::gantry ? 7.0 : 6.0);
        for (std::size_t s = 0; s + 1 < pts.size(); ++s) {
            const std::array<double, 2>& a = pts[s];
            const std::array<double, 2>& b = pts[s + 1];
            RegionItem it;
            it.kind = k;
            it.id = id;
            it.index = index;
            it.segment = static_cast<std::uint32_t>(s);
            it.x = a[0];
            it.z = a[1];
            it.bx = b[0];
            it.bz = b[1];
            it.h = height;
            const double mx = (a[0] + b[0]) / 2;
            const double mz = (a[1] + b[1]) / 2;
            double reach = 0;
            if (k == RegionItemKind::power) {
                // dx, dz = b - a; Lg = math.hypot(dx, dz) or 1; the conductors at H - 4 sin(pi f) min(1, Lg / 300) * 8
                // / 8, 6 m either side of the line, its tower at a to H + 2
                const double dx = b[0] - a[0];
                const double dz = b[1] - a[1];
                double lg = pymath::hypot(dx, dz);
                if (lg == 0.0) lg = 1.0;
                const std::array<double, 3> f{0.25, 0.5, 0.75};
                for (std::size_t q = 0; q < 3; ++q) {
                    it.sag[q] = height - (4 * pymath::sin(pymath::pi * f[q]) * pymath::min(1.0, lg / 300) * 8 / 8);
                }
                reach = (lg / 2) + 6.5;
            } else {
                // Lg = math.dist(a, b); `if Lg < 0.5 ... continue`
                const double lg = pymath::dist(a, b);
                if (lg < 0.5) continue;
                if (k == RegionItemKind::gantry) {
                    it.legs_a = s == 0;
                    it.legs_b = s + 2 == pts.size();
                    reach = (lg / 2) + 0.6;
                } else {
                    // nl = max(1, int(Lg / 22)): its legs every Lg / nl
                    it.gaps = static_cast<std::uint32_t>(std::max<std::int64_t>(1, pymath::to_int(lg / 22)));
                    reach = (lg / 2) + 1.5;
                }
            }
            // c = cam.cam((mid[0], H, mid[1])); its radius cut is at either end (the pass's)
            out.specs.push_back({.x = mx,
                                 .y = height,
                                 .z = mz,
                                 .offset = 0.0,
                                 .cut_x = mx,
                                 .cut_z = mz,
                                 .order = order(k, index, it.segment),
                                 .kind = kind,
                                 .flags = 0,
                                 .slots = {},
                                 .min_depth = least_depth,
                                 .radius = sphere(reach, std::abs(height) + 2.0),
                                 .tile = 0,
                                 .owner = scene::owner_key(owner_way, static_cast<std::uint64_t>(id))});
            const std::uint32_t slots = region_item_slots(it);
            out.specs.back().slots = {slots, slots, slots, slots};
            out.items.push_back(it);
        }
    }
}

} // namespace

std::uint32_t region_item_slots(const RegionItem& item) noexcept {
    switch (item.kind) {
    case RegionItemKind::tower:
    case RegionItemKind::turbine: return 5;
    case RegionItemKind::crane: return 0;
    case RegionItemKind::mast: return 12;
    case RegionItemKind::gantry: return 15;
    case RegionItemKind::conveyor: return 5 * (item.gaps + 2);
    case RegionItemKind::power: return 2;
    }
    return 0;
}

TileRegionItems build_region_items(const RegionItemInput& in) {
    TileRegionItems out;
    const tl::Frame& frame = in.head->frame;
    if (const tl::Layer* layer = in.tile->layer(tl::LayerId::item)) points(*layer, frame, in.kind, out);
    if (const tl::Layer* layer = in.tile->layer(tl::LayerId::line)) lines(*layer, frame, in.kind, out);
    return out;
}

} // namespace atlas::geometry
