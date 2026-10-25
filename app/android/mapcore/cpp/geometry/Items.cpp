#include "geometry/Items.hpp"

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "load/Real.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::geometry {

namespace {

namespace tl = atlas::tiles;

/// The item layer's sources (rendertiles' Schema.ITEM): S['cranes'] 0, S['masts'] 1, S['mtowers'] 2, S['towers'] 3
/// (pylons: their lines draw their towers), S['turbines'] 4, the woods' trees 5.
constexpr std::int64_t source_crane = 0;
constexpr std::int64_t source_mast = 1;
constexpr std::int64_t source_tower = 2;
constexpr std::int64_t source_turbine = 4;
constexpr std::int64_t source_wood = 5;

/// The line layer's sources: S['power'] 4, S['gantries'] 7, S['conveyors'] 8, S['coasters'] 9.
constexpr std::int64_t line_power = 4;
constexpr std::int64_t line_gantry = 7;
constexpr std::int64_t line_conveyor = 8;
constexpr std::int64_t line_coaster = 9;

/// An owner's kinds in scene::owner_key (AnchorItems' OwnerType).
constexpr std::uint8_t owner_node = 1;
constexpr std::uint8_t owner_way = 2;

/// draw_items' `c[2] < 5: continue`; coasters.draw's `c[2] < 3` and woods.draw's `c[2] < 4`.
constexpr float least_depth = 5.0F;
constexpr float coaster_depth = 3.0F;
constexpr float wood_depth = 4.0F;

/// A crane's form in the item layer's `crane` column: 2 a yard gantry (1 a ship-to-shore crane).
constexpr std::int64_t crane_yard = 2;

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
        if (source != source_tower && source != source_turbine && source != source_mast && source != source_crane &&
            source != source_wood) {
            continue;
        }
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
        std::uint8_t owner = owner_node;
        std::uint32_t place = it.index; // its place in its group
        if (source == source_wood) {
            // woods.draw: c = cam.cam((x, h * 0.7, z)), from 4 m; r = crown / 2: its clumps within 0.42 r of its axis
            // and up to 0.66 r across, their lit tops 0.3 r toward the sun, a glint beyond; random.Random(id)'s draws
            it.kind = RegionItemKind::wood;
            it.h = h.value_or(0.0);
            it.crown = layer.decimal(tl::item::crown, i).value_or(0.0);
            // ang = rnd.uniform(0, 2 * math.pi); rad = r * 0.42 * math.sqrt(rnd.random()); by = h * 0.7 +
            // rnd.uniform(-0.25, 0.25) * r; rr = r * rnd.uniform(0.5, 0.66) * s
            pymath::PyRandom rnd(it.id);
            const double r = it.crown / 2;
            for (std::size_t k = 0; k < 4; ++k) {
                const double ang = rnd.uniform(0.0, 2 * pymath::pi);
                const double rad = r * 0.42 * pymath::sqrt(rnd.random());
                const double dy = rnd.uniform(-0.25, 0.25) * r;
                const double rr = r * rnd.uniform(0.5, 0.66);
                it.clumps.at(4 * k) = static_cast<float>(rad * pymath::cos(ang));
                it.clumps.at((4 * k) + 1) = static_cast<float>(dy);
                it.clumps.at((4 * k) + 2) = static_cast<float>(rad * pymath::sin(ang));
                it.clumps.at((4 * k) + 3) = static_cast<float>(rr);
            }
            y = it.h * 0.7;
            reach = 1.5 * (it.crown / 2);
            up = 1.3 * (it.crown / 2);
            place = wood_index_base + it.index;
        } else if (source == source_crane) {
            // the form stage P derived (draw_items' to_water, its yard's axes); H = num(t.get('height'), 44.0) on a
            // quay, 26.0 in a yard; c = cam.cam((x, 15.0, z)). On a quay its boom reaches from 22 m behind its axis to
            // dw + 52 m toward the water, its legs 8.8 m either side, its A-frame to H + 20; in a yard it spans hu by
            // hv, its trolley to H + 2.6
            it.kind = RegionItemKind::crane;
            it.yard = layer.integer(tl::item::crane, i) == crane_yard;
            it.way = layer.ring(tl::item::poly, i).size() > 0;
            it.ux = layer.decimal(tl::item::ux, i).value_or(1.0);
            it.uz = layer.decimal(tl::item::uz, i).value_or(0.0);
            it.vx = layer.decimal(tl::item::vx, i).value_or(0.0);
            it.vz = layer.decimal(tl::item::vz, i).value_or(1.0);
            it.hu = layer.decimal(tl::item::hu, i).value_or(0.0);
            it.hv = layer.decimal(tl::item::hv, i).value_or(0.0);
            it.h = load::num(tag("height")).value_or(it.yard ? 26.0 : 44.0);
            y = 15.0;
            reach = it.yard ? pymath::hypot(it.hu, it.hv) : pymath::hypot(pymath::max(22.0, it.hu + 52.0), 8.8);
            up = pymath::max(15.0, std::abs(it.h + (it.yard ? 2.6 : 20.0) - 15.0));
            if (it.way) owner = owner_way;
        } else if (source == source_tower) {
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
                             .order = order(it.kind, place, 0),
                             .kind = kind,
                             .flags = scene::item_flag::radius,
                             .slots = {},
                             .min_depth = it.kind == RegionItemKind::wood ? wood_depth : least_depth,
                             .radius = sphere(reach, up),
                             .tile = 0,
                             .owner = scene::owner_key(owner, static_cast<std::uint64_t>(it.id))});
        const std::uint32_t slots = region_item_slots(it);
        out.specs.back().slots = {slots, slots, slots, slots};
        out.items.push_back(it);
    }
}

/// A coaster track's pieces: coasters.draw's subdivide(tr['pts'], tr['ys'], 3.0), each piece an item at its middle (its
/// heights' mean), from 3 m; the track's points its radius cut tests.
void coaster(const tl::Layer& layer, const tl::Frame& frame, std::uint16_t kind, std::uint32_t i,
             TileRegionItems& out) {
    const tl::RingView r = layer.ring(tl::line::line, i);
    const std::span<const std::int64_t> bits = layer.ids(tl::line::ys, i);
    if (r.size() < 2 || bits.size() != r.size()) return;
    std::vector<std::array<double, 2>> pts;
    std::vector<double> ys;
    for (std::size_t k = 0; k < r.size(); ++k) {
        pts.push_back({frame.x(r.lon[k]), frame.z(r.lat[k])});
        ys.push_back(std::bit_cast<double>(bits[k]));
    }
    const std::string_view name = layer.string(tl::line::kind, i).value_or("steel");
    const std::uint8_t ck = name == "wood" ? 1 : (name == "mono" ? 2 : 0);
    const auto index = static_cast<std::uint32_t>(layer.integer(tl::line::index, i));
    CoasterTrackSpan span{.first = static_cast<std::uint32_t>(out.items.size()), .count = 0, .kind = ck, .cut = {}};
    for (std::size_t k = 0; k < pts.size(); k += 4) span.cut.push_back(pts[k]); // tr['pts'][::4] + [tr['pts'][-1]]
    span.cut.push_back(pts.back());
    std::vector<std::array<double, 2>> p{pts[0]};
    std::vector<double> y{ys[0]};
    for (std::size_t k = 1; k < pts.size(); ++k) {
        const std::array<double, 2>& a = pts[k - 1];
        const std::array<double, 2>& b = pts[k];
        const std::int64_t n = std::max<std::int64_t>(1, pymath::to_int(pymath::dist(a, b) / coaster_step));
        for (std::int64_t q = 1; q <= n; ++q) {
            const double f = static_cast<double>(q) / static_cast<double>(n);
            p.push_back({a[0] + ((b[0] - a[0]) * f), a[1] + ((b[1] - a[1]) * f)});
            y.push_back(ys[k - 1] + ((ys[k] - ys[k - 1]) * f));
        }
    }
    const auto track = static_cast<std::uint32_t>(out.tracks.size());
    for (std::size_t k = 1; k < p.size(); ++k) {
        RegionItem it;
        it.kind = RegionItemKind::coaster;
        it.index = index;
        it.segment = static_cast<std::uint32_t>(k - 1);
        it.x = p[k - 1][0];
        it.z = p[k - 1][1];
        it.bx = p[k][0];
        it.bz = p[k][1];
        it.ya = y[k - 1];
        it.yb = y[k];
        it.h = std::max(it.ya, it.yb);
        it.track = track;
        it.coaster = ck;
        // m = ((a[0] + b[0]) / 2, (ya + yb) / 2, (a[1] + b[1]) / 2); its post down to the ground, a brace back to the
        // post before
        const double mx = (it.x + it.bx) / 2;
        const double mz = (it.z + it.bz) / 2;
        out.specs.push_back({.x = mx,
                             .y = (it.ya + it.yb) / 2,
                             .z = mz,
                             .offset = 0.0,
                             .cut_x = mx,
                             .cut_z = mz,
                             .order = order(it.kind, index, it.segment),
                             .kind = kind,
                             .flags = 0,
                             .slots = {},
                             .min_depth = coaster_depth,
                             .radius = sphere((pymath::dist(p[k - 1], p[k]) / 2) + 12.0, it.h + 2.0),
                             .tile = 0,
                             .owner = scene::owner_key(owner_way, 0)});
        const std::uint32_t slots = region_item_slots(it);
        out.specs.back().slots = {slots, slots, slots, slots};
        out.items.push_back(it);
    }
    span.count = static_cast<std::uint32_t>(out.items.size()) - span.first;
    out.tracks.push_back(std::move(span));
}

void lines(const tl::Layer& layer, const tl::Frame& frame, std::uint16_t kind, TileRegionItems& out) {
    std::vector<std::array<double, 2>> pts;
    for (std::uint32_t i = 0; i < layer.count; ++i) {
        const std::int64_t source = layer.integer(tl::line::source, i);
        if (source == line_coaster) {
            coaster(layer, frame, kind, i, out);
            continue;
        }
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
    case RegionItemKind::crane: return item.yard ? 45 : 61;
    case RegionItemKind::mast: return 12;
    case RegionItemKind::gantry: return 15;
    case RegionItemKind::conveyor: return 5 * (item.gaps + 2);
    case RegionItemKind::power: return 2;
    case RegionItemKind::coaster: return 4;
    case RegionItemKind::wood: return 9;
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
