#include "geometry/Items.hpp"

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "geometry/RoofShapes.hpp"
#include "load/Real.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <limits>
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

/// A roof mast (roofs): how near a solid's outline a tower stands on it, how far within it it then stands; the least a
/// mast reaches above its roof, the most on a roof whose height the loader guessed; how near the masts of one site
/// stand, and how near a taller one's top a mast's top lies to add no panels of its own.
constexpr double roof_reach = 1.0;
/// m²: a solid smaller than this is a tower's own foot or its cabin, not a roof (Noord-Holland's 22 such, 19 of their
/// towers' tags 8 to 26 m above them, where four in five of its masts stand on roofs of 400 m² or more)
constexpr double roof_least = 50.0;
constexpr double roof_inset = 0.5;
constexpr double mast_least = 2.0;
constexpr double mast_guessed = 3.5;
constexpr double site_reach = 1.5;
constexpr double site_tier = 0.5;

/// The buildings' `guessed` column: the tags the loader guessed.
constexpr std::uint8_t building_guessed = tl::slot_of(tl::building::columns, "guessed");

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
            it.communication = type == "communication";
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

/// Whether the loader guessed solid `i`'s height (10 m where none is mapped: load::guess_tags).
[[nodiscard]] bool height_guessed(const tl::Layer& layer, std::uint32_t i) noexcept {
    const std::optional<std::span<const std::uint32_t>> guessed = layer.string_list(building_guessed, i);
    return guessed && std::ranges::any_of(*guessed, [&](std::uint32_t s) { return layer.strings[s] == "height"; });
}

/// The point of `ring`'s outline nearest (x, z), and how far it lies.
struct Nearest {
    double x = 0;
    double z = 0;
    double d = std::numeric_limits<double>::infinity();
};

[[nodiscard]] Nearest nearest(std::span<const V2> ring, double x, double z) noexcept {
    Nearest best;
    for (std::size_t k = 0; k < ring.size(); ++k) {
        const V2& a = ring[k];
        const V2& b = ring[(k + 1) % ring.size()];
        const double dx = b.x - a.x;
        const double dz = b.z - a.z;
        const double length2 = (dx * dx) + (dz * dz);
        const double t =
            length2 > 0 ? pymath::min(1.0, pymath::max(0.0, (((x - a.x) * dx) + ((z - a.z) * dz)) / length2)) : 0.0;
        const double px = a.x + (t * dx);
        const double pz = a.z + (t * dz);
        const double d = pymath::hypot(px - x, pz - z);
        if (d < best.d) best = {.x = px, .z = pz, .d = d};
    }
    return best;
}

/// The roof a tower stands on: the highest solid holding it, else the nearest whose outline lies within roof_reach
/// (and where within it the tower then stands); its top, its eaves and whether its height was guessed.
struct Roof {
    double top = -1;
    double eaves = 0;
    bool guessed = false;
    double near = roof_reach;
    double near_top = -1;
    double near_eaves = 0;
    bool near_guessed = false;
    double x = 0;
    double z = 0;
};

/// The tile's communication towers standing on a roof, as the app's map draws them (ENGINE.md step 4, "Towers on
/// roofs"): each such tower a roof mast of the app's (item_flag::app, the order key after its tower's), the tower
/// itself left to the renders' frames (item_flag::renders), which draw its shaft from the ground. A tower stands on a
/// solid of the tile (a building its parts do not draw, or a part, with a height, of 50 m² or more: a smaller one is a
/// tower's own foot or its cabin) that holds it, the highest, or else within 1 m of one's outline, the nearest (its
/// mast then 0.5 m within it, across the outline's nearest point). The tag `height` (the Antenneregister's) is its
/// antennas' height above the ground: on a roof of a mapped height its top is the tag, 2 m above the roof at least; on
/// one whose height the loader guessed (10 m) the tag says nothing of the roof drawn, so it reaches the tag's height
/// above it within 2 to 3.5 m (the median of the tags above a mapped roof). It stands from the roof's eaves, the roof's
/// slope hiding it below, its panels down from its top half its length above the roof (0.6 to 2 m), its first panel's
/// way from its id. The masts of a site (within 1.5 m of its first) share the tallest's pole and its panels' way;
/// another draws its panels on that pole, or nothing where its top lies within 0.5 m of a taller one's.
void roofs(const tl::Tile& tile, const tl::Frame& frame, TileRegionItems& out) {
    std::vector<std::size_t> towers;
    for (std::size_t i = 0; i < out.items.size(); ++i) {
        if (out.items[i].kind == RegionItemKind::tower && out.items[i].communication) towers.push_back(i);
    }
    if (towers.empty()) return;
    // the towers by x, so that a solid looks only at those within its bounds' x
    const auto tower_x = [&](std::size_t m) { return out.items[towers[m]].x; };
    std::vector<std::size_t> by_x(towers.size());
    for (std::size_t m = 0; m < by_x.size(); ++m) by_x[m] = m;
    std::ranges::sort(by_x, {}, tower_x);
    std::vector<Roof> found(towers.size());
    std::vector<V2> ring;
    for (const tl::LayerId id : {tl::LayerId::building, tl::LayerId::part}) {
        const tl::Layer* layer = tile.layer(id);
        if (layer == nullptr) continue;
        for (std::uint32_t i = 0; i < layer->count; ++i) {
            if (id == tl::LayerId::building && layer->boolean(tl::building::hidden, i).value_or(false)) continue;
            const tl::RingView r = layer->ring(tl::building::ring, i);
            if (r.size() < 3) continue;
            // its bounds from the grid's first (most of a tile's solids stand far from its towers): x grows with the
            // longitude, z falls with the latitude
            const auto [south, north] = std::ranges::minmax(r.lat);
            const auto [west, east] = std::ranges::minmax(r.lon);
            const double z0 = frame.z(north) - roof_reach;
            const double z1 = frame.z(south) + roof_reach;
            const auto from = std::ranges::lower_bound(by_x, frame.x(west) - roof_reach, {}, tower_x);
            const auto to = std::ranges::upper_bound(from, by_x.end(), frame.x(east) + roof_reach, {}, tower_x);
            const auto around = [&](std::size_t m) {
                const RegionItem& it = out.items[towers[m]];
                return it.z >= z0 && it.z <= z1;
            };
            const std::optional<double> top = layer->decimal(tl::building::h, i);
            if (!top || std::ranges::none_of(from, to, around)) continue;
            ring.clear();
            for (std::size_t k = 0; k < r.size(); ++k) ring.emplace_back(frame.x(r.lon[k]), frame.z(r.lat[k]));
            if (std::abs(signed_area(ring)) < roof_least) continue;
            const double eaves = *top - layer->decimal(tl::building::roof_h, i).value_or(0.0);
            for (auto at = from; at != to; ++at) {
                const std::size_t m = *at;
                if (!around(m)) continue;
                const RegionItem& it = out.items[towers[m]];
                Roof& f = found[m];
                if (pip(it.x, it.z, ring)) {
                    if (*top > f.top) {
                        f.top = *top;
                        f.eaves = eaves;
                        f.guessed = height_guessed(*layer, i);
                    }
                    continue;
                }
                const Nearest p = nearest(ring, it.x, it.z);
                if (!(p.d < f.near) || p.d <= 0) continue;
                const double x = p.x + ((p.x - it.x) / p.d * roof_inset);
                const double z = p.z + ((p.z - it.z) / p.d * roof_inset);
                if (!pip(x, z, ring)) continue;
                f.near = p.d;
                f.near_top = *top;
                f.near_eaves = eaves;
                f.near_guessed = height_guessed(*layer, i);
                f.x = x;
                f.z = z;
            }
        }
    }
    // each such tower's roof mast; the tower left to the renders
    std::vector<RegionItem> masts;
    std::vector<std::size_t> tower_of;
    for (std::size_t m = 0; m < towers.size(); ++m) {
        const Roof& f = found[m];
        const bool holds = f.top >= 0;
        if (!holds && f.near_top < 0) continue;
        out.specs[towers[m]].flags |= scene::item_flag::renders;
        RegionItem it = out.items[towers[m]];
        const double roof = holds ? f.top : f.near_top;
        const double eaves = holds ? f.eaves : f.near_eaves;
        if (!holds) {
            it.x = f.x;
            it.z = f.z;
        }
        const double top = (holds ? f.guessed : f.near_guessed)
                               ? roof + pymath::min(mast_guessed, pymath::max(mast_least, it.h - roof))
                               : pymath::max(it.h, roof + mast_least);
        it.kind = RegionItemKind::roof_mast;
        it.base = eaves;
        it.h = top - eaves;
        it.panel = pymath::min(2.0, pymath::max(0.6, (top - roof) / 2));
        it.pole = it.h;
        const double a = pymath::radians(static_cast<double>(pymath::mod(it.id, std::int64_t{120})));
        it.ux = pymath::cos(a);
        it.uz = pymath::sin(a);
        masts.push_back(it);
        tower_of.push_back(towers[m]);
    }
    // each site: its masts in the tile's order, the tallest's pole (the first of equals)
    std::vector<bool> placed(masts.size(), false);
    std::vector<bool> kept(masts.size(), true);
    std::vector<std::size_t> site;
    for (std::size_t a = 0; a < masts.size(); ++a) {
        if (placed[a]) continue;
        site.clear();
        const RegionItem& first = masts[a];
        for (std::size_t b = a; b < masts.size(); ++b) {
            if (placed[b] || pymath::hypot(masts[b].x - first.x, masts[b].z - first.z) > site_reach) continue;
            placed[b] = true;
            site.push_back(b);
        }
        std::ranges::stable_sort(site, std::ranges::greater{},
                                 [&](std::size_t i) { return masts[i].base + masts[i].h; });
        const RegionItem lead = masts[site.front()];
        std::vector<double> tops{lead.base + lead.h};
        for (std::size_t k = 1; k < site.size(); ++k) {
            RegionItem& it = masts[site[k]];
            const double top = it.base + it.h;
            if (std::ranges::any_of(tops, [&](double t) { return t - top < site_tier; })) {
                kept[site[k]] = false;
                continue;
            }
            tops.push_back(top);
            it.x = lead.x;
            it.z = lead.z;
            it.ux = lead.ux;
            it.uz = lead.uz;
            it.base = lead.base;
            it.h = top - lead.base;
            it.pole = lead.h;
            it.panels_only = true;
        }
    }
    // the kept ones, the app's, after the points' items: each at its middle, the order key after its tower's, its own
    // reach and slots
    for (std::size_t m = 0; m < masts.size(); ++m) {
        if (!kept[m]) continue;
        const RegionItem& it = masts[m];
        scene::ItemSpec spec = out.specs[tower_of[m]];
        spec.x = it.x;
        spec.z = it.z;
        spec.cut_x = it.x;
        spec.cut_z = it.z;
        spec.y = it.base + (it.h / 2);
        spec.order = order(it.kind, it.index, 1);
        spec.flags = scene::item_flag::app;
        spec.radius = sphere(pymath::max(0.08, it.pole / 80) + 0.4, it.h / 2);
        const std::uint32_t slots = region_item_slots(it);
        spec.slots = {slots, slots, slots, slots};
        out.items.push_back(it);
        out.specs.push_back(spec);
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
    case RegionItemKind::roof_mast: return 16;
    }
    return 0;
}

TileRegionItems build_region_items(const RegionItemInput& in) {
    TileRegionItems out;
    const tl::Frame& frame = in.head->frame;
    if (const tl::Layer* layer = in.tile->layer(tl::LayerId::item)) points(*layer, frame, in.kind, out);
    roofs(*in.tile, frame, out);
    if (const tl::Layer* layer = in.tile->layer(tl::LayerId::line)) lines(*layer, frame, in.kind, out);
    return out;
}

} // namespace atlas::geometry
