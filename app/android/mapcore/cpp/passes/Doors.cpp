// The doors and their house numbers (passes/Doors.hpp).
#include "passes/Doors.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "gen/spirv/item/draw.hpp"
#include "gen/spirv/text/slug.hpp"
#include "gpu/Descriptors.hpp"
#include "scene/Cover.hpp"
#include "scene/ItemTable.hpp"
#include "scene/Packs.hpp"
#include "scene/Residency.hpp"
#include "style/CameraState.hpp"
#include "style/Theme.hpp"
#include "text/Fonts.hpp"
#include "text/Utf8.hpp"
#include "twin/Twin.hpp"
#include "twin/door/DoorTwin.hpp"
#include "twin/item/ItemShader.hpp"
#include "view/Frames.hpp"
#include "view/Ladder.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <format>
#include <map>
#include <optional>
#include <set>
#include <tuple>

namespace atlas::passes {

namespace {

namespace sh = atlas::shaders;
namespace sl = layouts::text_slug;

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// How far facade_details sets a door and its outline off their wall (`o`), and its stoop.
constexpr double door_off = 0.06;
constexpr double stoop_off = 0.6;

/// face()'s Camera.poly margin and least area (CSS px, px²).
constexpr double face_margin = 300.0;
constexpr double face_area = 0.12;

/// The leads of a door's parts (steps of 2^-16 of their reciprocal depth nearer), over the most a wall's windows take
/// (20: their sills), so a door covers the windows, the plinth and the glazing of its own wall: its face and its stoop,
/// its outline over its face, its plate, its number over its plate.
constexpr std::uint32_t door_lead = 21;
constexpr std::uint32_t outline_lead = 22;
constexpr std::uint32_t plate_lead = 23;
constexpr std::uint32_t number_lead = 24;

/// The leads a door takes over the doors it overlaps on its wall before it, in painter's order: its parts' four.
constexpr std::uint32_t overlap_lead = 4;

/// A door's parts' slots among its item's five (geometry::door_slots): its face, its outline, its stoop, its plate and
/// its number.
constexpr std::uint32_t face_slot = 0;
constexpr std::uint32_t outline_slot = 1;
constexpr std::uint32_t stoop_slot = 2;
constexpr std::uint32_t plate_slot = 3;
constexpr std::uint32_t number_slot = 4;

/// The door kind's detail thresholds: two that never split, so every door's item has detail bits 0 and 1, but the
/// items of the building the app asks about, which the ranks leave bit 0 alone (plain, without their facade's detail).
constexpr std::uint32_t facade_detail = 2;

/// The coverage tables, a face each: the identity, as the ground's text has them until calibrated (D13).
constexpr std::size_t table_entries = 256 * text::face_count;

/// The jobs' buffers: room to grow by half again where a frame needs more.
[[nodiscard]] gpu::Result<bool> fit(const gpu::Device& device, gpu::Buffer& b, vk::DeviceSize bytes) {
    if (b && b.size() >= bytes) return false;
    ATLAS_GPU_LET(made, device.memory().buffer(std::max<vk::DeviceSize>(bytes + (bytes / 2), 1024),
                                               vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload));
    b = std::move(made);
    return true;
}

// render/city.py's vectors, in the port's order of operations.
[[nodiscard]] view::Vec3 vsub(view::Vec3 a, view::Vec3 b) noexcept { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
[[nodiscard]] double vdot(view::Vec3 a, view::Vec3 b) noexcept { return (a.x * b.x) + (a.y * b.y) + (a.z * b.z); }
[[nodiscard]] view::Vec3 vnorm(view::Vec3 a) noexcept {
    const double l = pymath::sqrt(vdot(a, a));
    return {a.x / l, a.y / l, a.z / l};
}

/// facade_details' quad(w, y0, y1, off): `w` wide about the door's point on its wall, from y0 up to y1, `off` out along
/// the normal `nrm`.
[[nodiscard]] std::array<view::Vec3, 4> quad(const geometry::Door& d, view::Vec3 nrm, double w, double y0, double y1,
                                             double off) noexcept {
    const double ax = d.qx - (d.ux * w / 2) + (nrm.x * off);
    const double az = d.qz - (d.uz * w / 2) + (nrm.z * off);
    const double bx = d.qx + (d.ux * w / 2) + (nrm.x * off);
    const double bz = d.qz + (d.uz * w / 2) + (nrm.z * off);
    return {{{ax, y0, az}, {bx, y0, bz}, {bx, y1, bz}, {ax, y1, az}}};
}

/// render/amsterdam.py:610 face's polygon: Camera.poly(q, margin=300) (city.py:45: cut at the near plane, dropped where
/// it lies wholly beyond the frame and the margin), kept where its area (city.py:80) is 0.12 px² or more.
[[nodiscard]] DoorFace face(const view::Camera& cam, const std::array<view::Vec3, 4>& q) {
    DoorFace f;
    std::array<view::Vec3, 4> cs{};
    for (std::size_t i = 0; i < cs.size(); ++i) cs.at(i) = cam.cam(q.at(i));
    const double near = cam.near();
    std::array<view::Vec3, 5> kept{};
    std::uint32_t n = 0;
    for (std::size_t i = 0; i < cs.size(); ++i) {
        const view::Vec3 a = cs.at(i);
        const view::Vec3 b = cs.at((i + 1) % cs.size());
        const bool ai = a.z >= near;
        const bool bi = b.z >= near;
        if (ai && n < kept.size()) kept.at(n++) = a;
        if (ai != bi && n < kept.size()) {
            const double t = (near - a.z) / (b.z - a.z);
            kept.at(n++) = {a.x + ((b.x - a.x) * t), a.y + ((b.y - a.y) * t), near};
        }
    }
    if (n < 3) return f;
    std::array<view::Vec2, 5> sp{};
    for (std::uint32_t i = 0; i < n; ++i) sp.at(i) = cam.pc(kept.at(i));
    double x0 = sp[0].x;
    double x1 = sp[0].x;
    double y0 = sp[0].y;
    double y1 = sp[0].y;
    for (std::uint32_t i = 1; i < n; ++i) {
        x0 = pymath::min(x0, sp.at(i).x);
        x1 = pymath::max(x1, sp.at(i).x);
        y0 = pymath::min(y0, sp.at(i).y);
        y1 = pymath::max(y1, sp.at(i).y);
    }
    if (x1 < -face_margin || x0 > cam.width() + face_margin || y1 < -face_margin || y0 > cam.height() + face_margin) {
        return f;
    }
    double a = 0.0;
    for (std::uint32_t i = 0; i < n; ++i) {
        const view::Vec2 p = sp.at(i);
        const view::Vec2 r = sp.at((i + 1) % n);
        a += (p.x * r.y) - (r.x * p.y);
    }
    if (pymath::fabs(a) / 2 < face_area) return f;
    f.count = n;
    for (std::uint32_t i = 0; i < n; ++i) {
        f.points.at(i) = sp.at(i);
        f.depths.at(i) = kept.at(i).z;
    }
    return f;
}

/// The reciprocal depth (near / k.z) over the screen of the plane through `on` with the horizontal normal `nrm`: on a
/// plane it is affine in the screen (painter.slang), r = near · (n · dir) / (nrm · (on − C)) with n the normal in eye
/// space and dir = ((x − cx) / f, −(y − cy) / f, 1) the eye's ray through CSS point (x, y): r = r0 + gx x + gy y.
struct Plane {
    double r0 = 0;
    double gx = 0;
    double gy = 0;

    [[nodiscard]] double at(double x, double y) const noexcept { return r0 + (gx * x) + (gy * y); }
};
[[nodiscard]] Plane plane_of(const view::Camera& cam, view::Vec3 nrm, view::Vec3 on) noexcept {
    const view::Vec3 n{vdot(nrm, cam.right()), vdot(nrm, cam.up()), vdot(nrm, cam.forward())};
    const double k = cam.near() / (vdot(nrm, vsub(on, cam.eye())) * cam.focal());
    return {.r0 = k * ((n.z * cam.focal()) - (n.x * cam.cx()) + (n.y * cam.cy())), .gx = k * n.x, .gy = -k * n.y};
}

/// A door kept in one frame given in another (the camera stands in another pack's frame): its node, its point on the
/// wall and its direction there.
[[nodiscard]] geometry::Door placed(const geometry::Door& d, const view::GroundFrame& from,
                                    const view::GroundFrame& to) noexcept {
    geometry::Door o = d;
    const view::Vec3 n = view::convert(from, to, {d.x, 0.0, d.z});
    const view::Vec3 q = view::convert(from, to, {d.qx, 0.0, d.qz});
    const view::Vec3 e = view::convert(from, to, {d.qx + d.ux, 0.0, d.qz + d.uz});
    o.x = n.x;
    o.z = n.z;
    o.qx = q.x;
    o.qz = q.z;
    const double l = pymath::hypot(e.x - q.x, e.z - q.z);
    o.ux = (e.x - q.x) / l;
    o.uz = (e.z - q.z) / l;
    return o;
}

/// The first of a tile's items that are doors.
[[nodiscard]] std::uint32_t first_door(const scene::ResidentTile& tile, std::uint16_t kind) noexcept {
    for (std::size_t i = 0; i < tile.items.size(); ++i) {
        if (tile.items[i].kind == kind) return static_cast<std::uint32_t>(i);
    }
    return static_cast<std::uint32_t>(tile.items.size());
}

/// A colour of the style (0xRRGGBB) as the shaders take it, 0xBBGGRR.
[[nodiscard]] std::uint32_t bgr(std::uint32_t rgb) noexcept {
    return ((rgb >> 16U) & 0xFFU) | (rgb & 0xFF00U) | ((rgb & 0xFFU) << 16U);
}

/// The box (CSS px: x0, y0, x1, y1) of what a door draws: its face, its stoop and its plate.
[[nodiscard]] std::array<double, 4> box_of(const DoorShape& d) noexcept {
    std::array<double, 4> b{1e300, 1e300, -1e300, -1e300};
    const auto add = [&b](double x, double y) {
        b[0] = std::min(b[0], x);
        b[1] = std::min(b[1], y);
        b[2] = std::max(b[2], x);
        b[3] = std::max(b[3], y);
    };
    for (const DoorFace* f : {&d.door, &d.stoop}) {
        for (std::uint32_t i = 0; i < f->count; ++i) add(f->points.at(i).x, f->points.at(i).y);
    }
    if (d.plate) {
        add(d.x, d.y);
        add(d.x + d.width, d.y + d.height);
    }
    return b;
}

/// The doors of one wall that overlap (their boxes, with a pixel to spare; one wall: one building, one direction),
/// ordered as the port draws them, by their items' slots: each its place among them, their plates at the nearest depth
/// any of them has, so the later's parts and plate cover the earlier's at true depth as the port's do.
void order_overlaps(std::vector<DoorShape>& doors) {
    const std::size_t n = doors.size();
    std::vector<std::array<double, 4>> boxes(n);
    for (std::size_t i = 0; i < n; ++i) boxes[i] = box_of(doors[i]);
    std::vector<std::size_t> root(n);
    for (std::size_t i = 0; i < n; ++i) root[i] = i;
    const auto find = [&root](std::size_t i) {
        while (root[i] != i) i = root[i] = root[root[i]];
        return i;
    };
    for (std::size_t i = 0; i < n; ++i) {
        for (std::size_t j = i + 1; j < n; ++j) {
            const DoorShape& a = doors[i];
            const DoorShape& b = doors[j];
            if (a.building != b.building || a.ux != b.ux || a.uz != b.uz) continue;
            const std::array<double, 4>& p = boxes[i];
            const std::array<double, 4>& q = boxes[j];
            if (p[0] > q[2] + 1 || q[0] > p[2] + 1 || p[1] > q[3] + 1 || q[1] > p[3] + 1) continue;
            root[find(i)] = find(j);
        }
    }
    std::map<std::size_t, std::vector<std::size_t>> groups;
    for (std::size_t i = 0; i < n; ++i) groups[find(i)].push_back(i);
    for (auto& [_, group] : groups) {
        if (group.size() < 2) continue;
        std::ranges::stable_sort(group, {}, [&](std::size_t i) { return sh::rank_slot(doors[i].rank); });
        double r = 0.0;
        for (const std::size_t i : group) r = std::max(r, doors[i].plate ? doors[i].plate_r : 0.0);
        for (std::size_t k = 0; k < group.size(); ++k) {
            DoorShape& d = doors[group[k]];
            d.order = static_cast<std::uint32_t>(k);
            if (d.plate) d.plate_r = r;
        }
    }
}

} // namespace

std::vector<DoorShape> door_shapes(const frame::FrameState& state, std::uint32_t pass, std::uint16_t kind,
                                   const tiles::DoorsStyle& style) {
    std::vector<DoorShape> out;
    // `if th.get('wx') and not S.get('no_facades')`: with weather (only the living map's tiles carry doors)
    if (state.cover == nullptr || state.ranks == nullptr || state.items == nullptr || !state.view ||
        !state.theme.has(style::theme_flag::weather)) {
        return out;
    }
    const view::Camera& cam = state.view->camera();
    // far(n['x'], n['z']): the renders' radius cut, as the ranks cut the doors' items (frame/FrameBuilder.cpp), in
    // engine mode about the view's target
    std::optional<std::array<double, 3>> cut; // radius, x, z
    if (state.camera.has(style::camera_flag::engine_mode) && state.placed) {
        if (const auto radius = state.placed->number("radius")) {
            const view::Vec3 target = state.placed->camera().target;
            cut = std::array{*radius, target.x, target.z};
        }
    }
    // the cover's doors, each tile's once: those the port's tests keep (`far`, the node 2 to 75 m deep, the wall facing
    // the eye), which alone take spots, in S['nodes'] order
    struct At {
        geometry::Door door;
        std::uint32_t row = 0;
        double d0 = 0;
        view::Vec3 nrm;
    };
    std::vector<At> kept;
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    const std::span<const std::uint16_t> flags = state.items->flags();
    const view::Vec3 eye = cam.eye();
    for (std::size_t c = 0; c < tiles.size(); ++c) {
        const scene::CoverTile& t = tiles[c];
        if (t.drawn == nullptr || pass >= t.drawn->passes.size()) continue;
        const auto* doors = t.drawn->passes[pass].cpu_as<DoorTile>();
        if (doors == nullptr || doors->doors.empty()) continue;
        // a tile's doors once, though it draws for several tiles (a stand-in ancestor, its cells over each)
        bool seen = false;
        for (std::size_t e = 0; e < c && !seen; ++e) seen = tiles[e].drawn == t.drawn;
        if (seen) continue;
        const bool convert = state.ground_frame && *state.ground_frame != t.drawn->frame;
        // none of its nodes 2 to 75 m deep: the depth is linear, so its box's corners bound it
        double least = 1e300;
        double most = -1e300;
        for (const auto& [x, z] : std::array<std::array<double, 2>, 4>{{{doors->box[0], doors->box[1]},
                                                                        {doors->box[2], doors->box[1]},
                                                                        {doors->box[0], doors->box[3]},
                                                                        {doors->box[2], doors->box[3]}}}) {
            const view::Vec3 p =
                convert ? view::convert(t.drawn->frame, *state.ground_frame, {x, 1.0, z}) : view::Vec3{x, 1.0, z};
            const double d = cam.depth(p);
            least = std::min(least, d);
            most = std::max(most, d);
        }
        if (least > 75 + 1 || most < 2 - 1) continue;
        const std::uint32_t first = first_door(*t.drawn, kind);
        for (std::size_t i = 0; i < doors->doors.size() && first + i < t.drawn->rows.size(); ++i) {
            const std::uint32_t row = t.drawn->rows[first + i];
            // a coarser pack's door within the active pack's box is the active pack's to draw (none)
            if (row >= flags.size() || (flags[row] & scene::item_flag::masked) != 0) continue;
            const geometry::Door d =
                convert ? placed(doors->doors[i], t.drawn->frame, *state.ground_frame) : doors->doors[i];
            if (cut && view::java_hypot(d.x - (*cut)[1], d.z - (*cut)[2]) > (*cut)[0]) continue;
            const double d0 = cam.depth({d.x, 1.0, d.z});
            if (d0 < 2 || d0 > 75) continue;
            const view::Vec3 nrm = vnorm({d.uz, 0.0, -d.ux});
            if (vdot(nrm, vsub(eye, {d.qx, 1.2, d.qz})) <= 0) continue; // that wall faces away
            kept.push_back({.door = d, .row = row, .d0 = d0, .nrm = nrm});
        }
    }
    std::ranges::stable_sort(kept, {}, [](const At& a) { return a.door.node; });

    // one door a spot on a wall: doors = {}, keyed (b['id'], round(qx), round(qz)), the first address's
    struct Spot {
        DoorShape shape;
        bool drawn = false; // its item drawn (within the frame) and its building not the one the app asks about
        std::set<std::int64_t> numbers;
        double d0 = 0;
        std::optional<view::Vec2> p;
        view::Vec3 nrm;
        view::Vec3 on;
        Plane plane; // the door's, 0.06 m off its wall
    };
    std::vector<Spot> spots;
    std::map<std::tuple<std::int64_t, std::int64_t, std::int64_t>, std::size_t> keys;
    const std::span<const std::uint32_t> table = state.ranks->table();
    const std::span<const double> depth = state.ranks->depth();
    for (const At& a : kept) {
        const geometry::Door& d = a.door;
        const view::Vec3 nrm = a.nrm;
        const double d0 = a.d0;
        if (a.row >= table.size()) continue;
        const auto key = std::tuple{d.building, pymath::round(d.qx), pymath::round(d.qz)};
        if (const auto it = keys.find(key); it != keys.end()) {
            if (d.number) spots[it->second].numbers.insert(*d.number);
            continue;
        }
        keys.emplace(key, spots.size());
        Spot s;
        const std::uint32_t rank = table[a.row];
        s.drawn = rank != sh::RANK_NONE && (sh::rank_detail(rank) & facade_detail) != 0;
        s.shape.rank = rank;
        s.shape.depth = depth[a.row];
        s.shape.id = d.id;
        s.shape.building = d.building;
        s.shape.ux = d.ux;
        s.shape.uz = d.uz;
        s.shape.colour = style.doors.at(static_cast<std::size_t>(pymath::mod(d.id, std::int64_t{5})));
        s.shape.door = face(cam, quad(d, nrm, 1.15, 0.0, 2.45, door_off));
        s.shape.stoop = face(cam, quad(d, nrm, 1.5, 0.0, 0.32, stoop_off));
        s.on = {d.qx + (nrm.x * door_off), 2.75, d.qz + (nrm.z * door_off)};
        s.p = cam.proj(s.on);
        s.nrm = nrm;
        s.plane = plane_of(cam, nrm, s.on);
        s.d0 = d0;
        if (d.number) s.numbers.insert(*d.number);
        spots.push_back(std::move(s));
    }

    for (Spot& s : spots) {
        DoorShape& d = s.shape;
        if (!s.numbers.empty()) {
            const std::int64_t least = *s.numbers.begin();
            const std::int64_t most = *s.numbers.rbegin();
            d.number = s.numbers.size() == 1 ? std::format("{}", least) : std::format("{}–{}", least, most);
        }
        if (s.p && s.d0 < 45 && !d.number.empty()) {
            const double fs = pymath::max(3.0, 0.32 * cam.focal() / s.d0);
            const auto len = static_cast<double>(text::code_points(d.number).size()); // len(num)
            d.plate = true;
            d.x = s.p->x - (fs * (0.4 + (0.3 * len)));
            d.y = s.p->y - (fs * 0.85);
            d.width = fs * (0.8 + (0.6 * len));
            d.height = fs * 1.15;
            d.rx = fs * 0.15;
            d.text_x = s.p->x;
            d.text_y = s.p->y + (fs * 0.08);
            d.size = fs;
            // its depth: the nearest the door's plane has under it (r is affine there, so at a corner), at the near
            // plane at most; the anchor lies within it, ahead, so it is positive
            double r = 0.0;
            for (const auto& [x, y] : std::array<std::array<double, 2>, 4>{
                     {{d.x, d.y}, {d.x + d.width, d.y}, {d.x, d.y + d.height}, {d.x + d.width, d.y + d.height}}}) {
                r = std::max(r, s.plane.at(x, y));
            }
            d.plate_r = std::min(r, 1.0);
        }
        d.plane_gx = s.plane.gx;
        d.plane_gy = s.plane.gy;
        // `if sv:`: a door none of whose parts face() keeps draws nothing
        if (!s.drawn || (d.door.count == 0 && d.stoop.count == 0 && !d.plate)) continue;
        out.push_back(std::move(d));
    }
    order_overlaps(out);
    return out;
}

void door_records(const DoorShape& d, const sh::FrameCamera& camera, const tiles::DoorsStyle& style, bool night,
                  std::vector<sh::ItemRecord>& faces, std::vector<sh::ItemRecord>& parts) {
    const std::uint32_t slot = sh::rank_slot(d.rank);
    const std::uint32_t over = overlap_lead * d.order;
    const bool rest = sh::camera_rests(camera);
    const auto at = [rest](double v) { return rest ? text::printed_float(v, 1) : static_cast<float>(v); };
    // a face's polygon as printed, each corner at its own eye depth
    const auto poly = [&](const DoorFace& f) {
        sh::ItemPoly p = sh::item_poly_none();
        for (std::uint32_t i = 0; i < f.count; ++i) {
            const hlsl::float2 q{at(f.points.at(i).x), at(f.points.at(i).y)};
            const auto z = static_cast<float>(f.depths.at(i));
            if (i == 0) {
                p.p0 = q;
                p.z0 = z;
            } else if (i == 1) {
                p.p1 = q;
                p.z1 = z;
            } else if (i == 2) {
                p.p2 = q;
                p.z2 = z;
            } else if (i == 3) {
                p.p3 = q;
                p.z3 = z;
            } else {
                p.p4 = q;
                p.z4 = z;
            }
        }
        p.count = f.count;
        return p;
    };
    if (d.door.count > 0) {
        const sh::ItemPoly p = poly(d.door);
        faces.push_back(sh::item_face(camera, p, slot + face_slot, door_lead + over, bgr(d.colour), 0.0F));
        // its outline: stroke-width .5 with the objects group's round joins, a round-capped segment an edge, on the
        // door's plane across its width (its lead's depth there; under the painter switch its slot's, flat)
        const float half = style.outline_width / 2;
        const double lead = 1.0 + (static_cast<double>(sh::ITEM_LEAD) * (outline_lead + over));
        const double scale = (camera.flags.x & sh::CAMERA_PAINTER) != 0U   ? 0.0
                             : (camera.flags.x & sh::CAMERA_DEPTH24) != 0U ? 0.5 * lead
                                                                           : lead;
        const hlsl::float2 gradient{static_cast<float>(d.plane_gx * scale), static_cast<float>(d.plane_gy * scale)};
        for (std::uint32_t e = 0; e < p.count; ++e) {
            const std::uint32_t k = (e + 1) % p.count;
            parts.push_back(sh::item_on_plane(
                sh::item_segment(
                    sh::item_poly_point(p, e), sh::item_poly_point(p, k),
                    sh::item_depth(camera, sh::item_poly_z(p, e), slot + outline_slot, outline_lead + over),
                    sh::item_depth(camera, sh::item_poly_z(p, k), slot + outline_slot, outline_lead + over), half,
                    sh::ITEM_ROUND, bgr(style.outline), 1.0F, 0U),
                gradient));
        }
    }
    if (d.stoop.count > 0) {
        faces.push_back(sh::item_face(camera, poly(d.stoop), slot + stoop_slot, door_lead + over,
                                      bgr(night ? style.stoop.night : style.stoop.day), 0.0F));
    }
    if (d.plate) {
        // the rect's inner box stroked in its own colour rx either side: SVG's rect with rx (and ry = rx)
        const float x = at(d.x);
        const float y = at(d.y);
        const float w = at(d.width);
        const float h = at(d.height);
        const float rx = at(d.rx);
        const auto z = static_cast<float>(static_cast<double>(camera.frame.w) / d.plate_r);
        sh::ItemPoly p = sh::item_poly_none();
        p.p0 = {x + rx, y + rx};
        p.p1 = {x + w - rx, y + rx};
        p.p2 = {x + w - rx, y + h - rx};
        p.p3 = {x + rx, y + h - rx};
        p.z0 = z;
        p.z1 = z;
        p.z2 = z;
        p.z3 = z;
        p.count = 4;
        faces.push_back(sh::item_face(camera, p, slot + plate_slot, plate_lead + over,
                                      bgr(night ? style.plate.night : style.plate.day), rx));
    }
}

text::TextRun door_number_run(const DoorShape& d, const tiles::DoorsStyle& style, bool night, bool rest) {
    const auto at = [rest](double v) { return rest ? static_cast<double>(text::printed_float(v, 1)) : v; };
    text::TextRun run;
    run.text = d.number;
    run.matrix = {1.0, 0.0, 0.0, 1.0, at(d.text_x), at(d.text_y)};
    run.family = twin::door_number_family;
    run.size = static_cast<double>(text::printed_float(d.size, 1)); // shaped at the size the SVG prints
    run.weight = 400;
    run.spacing = 0.0;
    run.anchor = text::Anchor::middle;
    run.fill = night ? style.number.night : style.number.day;
    run.opacity = 1.0;
    return run;
}

float door_number_depth(const DoorShape& d, const sh::FrameCamera& camera) {
    return sh::item_depth(camera, static_cast<float>(static_cast<double>(camera.frame.w) / d.plate_r),
                          sh::rank_slot(d.rank) + number_slot, number_lead + (overlap_lead * d.order));
}

Doors::Doors() = default;

Doors::~Doors() { Doors::release(); }

std::span<const sh::ItemRecord> Doors::faces() const noexcept { return faces_; }

std::span<const sh::ItemRecord> Doors::parts() const noexcept { return parts_; }

gpu::Result<void> Doors::prepare(const frame::PassContext& context) {
    release();
    // none in the owner mode: the likeness harness's owner maps draw no facade details yet
    if (context.owners) return {};
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(items, frame::make_module(context, "item_draw", spirv::item_draw));
    items_module_ = std::move(items);
    ATLAS_GPU_LET(glyph_module, frame::make_module(context, "text_slug", spirv::text_slug));
    text_module_ = std::move(glyph_module);
    // Set 2: the numbers' glyphs and the coverage tables (slug.slang's bindings 0 and 1), the faces and the outlines
    // (items.slangh's records, draw.slang's bindings 4 and 6).
    using Stage = vk::ShaderStageFlagBits;
    const auto storage = vk::DescriptorType::eStorageBuffer;
    const std::array bindings{
        gpu::Binding{.slot = sl::glyph_draws.binding, .type = storage, .stages = Stage::eVertex | Stage::eFragment},
        gpu::Binding{.slot = sl::coverage_tables.binding, .type = storage, .stages = Stage::eFragment},
        gpu::Binding{.slot = 4, .type = storage, .stages = Stage::eVertex},
        gpu::Binding{.slot = 6, .type = storage, .stages = Stage::eVertex}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, static_cast<std::uint32_t>(sets_.size())));
    pool_ = std::move(pool);
    for (vk::raii::DescriptorSet& set : sets_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        set = std::move(made);
    }
    ATLAS_GPU_LET(tables,
                  context.device.memory().buffer(table_entries * sizeof(float), vk::BufferUsageFlagBits::eStorageBuffer,
                                                 gpu::MemoryUse::upload));
    tables_ = std::move(tables);
    std::vector<float> identity(table_entries);
    for (std::size_t i = 0; i < table_entries; ++i) identity[i] = static_cast<float>(i % 256) / 255.0F;
    std::memcpy(tables_.bytes().data(), identity.data(), identity.size() * sizeof(float));
    tables_.flush();
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    // the faces, their edges and the outlines: at their corners' depths, writing the depth and the objects bit, through
    // the region items' stages
    const auto opaque = [&](const char* name, const char* vertex, const char* fragment) {
        gpu::GraphicsDesc desc{.label = {.name = name, .module = "item_draw", .configuration = context.configuration},
                               .module = *items_module_,
                               .vertex = vertex,
                               .fragment = fragment,
                               .layout = *layout_,
                               .target = context.pass.scene_target(),
                               .depth_test = true,
                               .depth_write = true,
                               .depth_op = vk::CompareOp::eGreater,
                               .stencil_test = true,
                               .stencil = {.failOp = vk::StencilOp::eKeep,
                                           .passOp = vk::StencilOp::eReplace,
                                           .depthFailOp = vk::StencilOp::eKeep,
                                           .compareOp = vk::CompareOp::eAlways,
                                           .compareMask = 0,
                                           .writeMask = shaders::OBJECTS_BIT,
                                           .reference = shaders::OBJECTS_BIT}};
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        return frame::make_pipeline(context, desc, frame::Overdraw::opaque);
    };
    ATLAS_GPU_LET(fills, opaque("door fills", "items_faces_vs", "items_fills_fs"));
    fills_ = std::move(fills);
    ATLAS_GPU_LET(faces, opaque("door faces", "items_faces_vs", "items_faces_fs"));
    faces_pipeline_ = std::move(faces);
    ATLAS_GPU_LET(parts, opaque("door outlines", "items_parts_vs", "items_parts_fs"));
    parts_pipeline_ = std::move(parts);
    // the numbers' glyphs: translucent, the test on and writes off
    gpu::GraphicsDesc numbers{
        .label = {.name = "door numbers", .module = "text_slug", .configuration = context.configuration},
        .module = *text_module_,
        .vertex = "wall_text_vs",
        .fragment = "wall_text_fs",
        .layout = *layout_,
        .target = context.pass.scene_target(),
        .depth_test = true,
        .depth_write = false,
        .depth_op = vk::CompareOp::eGreater};
    numbers.blends[0] = gpu::Blend::over();
    if (numbers.target.colour_count == 2) numbers.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(numbers_pipeline, frame::make_pipeline(context, numbers, frame::Overdraw::translucent));
    numbers_ = std::move(numbers_pipeline);
    return {};
}

void Doors::release() noexcept {
    numbers_.clear();
    parts_pipeline_.clear();
    faces_pipeline_.clear();
    fills_.clear();
    layout_.clear();
    for (vk::raii::DescriptorSet& s : sets_) s.clear();
    pool_.clear();
    set_layout_.clear();
    text_module_.clear();
    items_module_.clear();
    faces_buffer_ = {};
    parts_buffer_ = {};
    glyphs_buffer_ = {};
    tables_ = {};
    counts_ = {};
}

void Doors::kinds(frame::Selectors& selectors) { kind_ = selectors.kind("door"); }

void Doors::select(const frame::FrameState& state, frame::Selectors& selectors) {
    (void)state;
    const std::array<double, 2> details{frame::Selectors::never, frame::Selectors::never};
    selectors.set_details(kind_, details);
}

gpu::Result<void> Doors::build(scene::TileBuild& b) const {
    geometry::TileDoors g = geometry::build_doors({.tile = &b.tile(), .head = &b.head(), .kind = kind_});
    if (g.doors.empty()) return {};
    for (const scene::ItemSpec& item : g.items) b.add_item(item);
    auto kept = std::make_shared<DoorTile>();
    kept->doors = std::move(g.doors);
    kept->box = {kept->doors[0].x, kept->doors[0].z, kept->doors[0].x, kept->doors[0].z};
    for (const geometry::Door& d : kept->doors) {
        kept->box = {std::min(kept->box[0], d.x), std::min(kept->box[1], d.z), std::max(kept->box[2], d.x),
                     std::max(kept->box[3], d.z)};
    }
    const std::size_t bytes = sizeof(DoorTile) + (kept->doors.capacity() * sizeof(geometry::Door));
    b.keep(std::shared_ptr<const DoorTile>(std::move(kept)), bytes);
    return {};
}

const Doors::Shaped& Doors::shaped(const text::TextRun& run) {
    static const Shaped nothing;
    const text::Rounding rounding = text::platform_rounding();
    const auto style = run.shape_style(rounding);
    if (!style) return nothing;
    const std::string key = std::format("{}\x1f{}\x1f{}\x1f{:08x}\x1f{}", run.text, static_cast<int>(style->font.face),
                                        style->font.weight, bits(style->size), static_cast<int>(rounding));
    if (const auto it = shaped_.find(key); it != shaped_.end()) return it->second;
    const text::Fonts& fonts = text::shared_fonts();
    const auto instance = fonts.instance(style->font);
    if (!instance) return nothing;               // the face is not loaded yet: asked again next frame
    if (shaped_.size() >= 4096) shaped_.clear(); // the numbers and sizes of a session: kept within bounds
    Shaped s;
    if (auto run_shaped = text::shape(fonts, *style, run.text)) {
        s.run = std::move(*run_shaped);
        s.places.reserve(s.run.glyphs.size());
        text::slug::GlyphAtlas& glyphs = text::slug::shared_glyphs();
        for (const std::uint32_t id : s.run.glyphs) s.places.push_back(glyphs.glyph(**instance, id));
        s.ok = true;
    }
    return shaped_.emplace(key, std::move(s)).first->second;
}

void Doors::update(const frame::FrameState& state) {
    counts_ = {};
    faces_.clear();
    parts_.clear();
    glyphs_.clear();
    if (fills_ == nullptr || !state.view) return;
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    std::vector<DoorShape> doors = door_shapes(state, index(), kind_, style->doors);
    if (doors.empty()) return;
    // the nearest first (the latest slots), so early depth testing rejects what they hide
    std::ranges::stable_sort(doors, std::ranges::greater{}, [](const DoorShape& d) { return sh::rank_slot(d.rank); });
    const bool night = state.theme.has(style::theme_flag::night);
    const sh::FrameCamera& camera = state.block.camera;
    for (const DoorShape& d : doors) {
        door_records(d, camera, style->doors, night, faces_, parts_);
        if (!d.plate) continue;
        const text::TextRun run = door_number_run(d, style->doors, night, state.rest);
        const Shaped& s = shaped(run);
        if (!s.ok) continue;
        laid_.clear();
        text::lay_out(run, run.matrix, s.run, s.places, 1.0, laid_);
        const auto face = text::resolve(run.family, run.weight, false);
        const std::uint32_t table = face ? static_cast<std::uint32_t>(face->face) : 0U;
        const std::uint32_t depth = bits(door_number_depth(d, camera));
        const std::uint32_t slot = sh::rank_slot(d.rank) + number_slot;
        for (const text::GlyphInstance& g : laid_) {
            if (glyphs_.size() >= max_glyphs) break;
            const text::slug::Glyph& at = *g.glyph;
            glyphs_.push_back({.row0 = {g.row0[0], g.row0[1], g.row0[2], 0.0F},
                               .row1 = {g.row1[0], g.row1[1], g.row1[2], 0.0F},
                               .box = {at.box.x0, at.box.y0, at.box.x1, at.box.y1},
                               .bands = {at.scale_x, at.scale_y, at.offset_x, at.offset_y},
                               .location = {at.band_x, at.band_y, at.vbands - 1, at.hbands - 1},
                               .colour = {g.colour[0], g.colour[1], g.colour[2], g.colour[3]},
                               .info = {table, slot, depth, 0}});
        }
    }
    // into this frame slot's buffers (its previous frame has finished: its buffers and set are free)
    const std::uint32_t slot = state.slot % 2;
    const auto records = [](std::size_t n) {
        return vk::DeviceSize{std::max<std::size_t>(n, 1)} * sizeof(sh::ItemRecord);
    };
    auto faces_made = fit(*device_, faces_buffer_.at(slot), records(faces_.size()));
    auto parts_made = fit(*device_, parts_buffer_.at(slot), records(parts_.size()));
    auto glyphs_made = fit(*device_, glyphs_buffer_.at(slot),
                           vk::DeviceSize{std::max<std::size_t>(glyphs_.size(), 1)} * sizeof(sl::GlyphDraw));
    if (!faces_made || !parts_made || !glyphs_made) {
        const gpu::Error& e = !faces_made ? faces_made.error() : !parts_made ? parts_made.error() : glyphs_made.error();
        log::error("vk doors: {} doors, {} faces, {} outlines and {} glyphs: {}", doors.size(), faces_.size(),
                   parts_.size(), glyphs_.size(), e.text());
        faces_buffer_.at(slot) = {};
        parts_buffer_.at(slot) = {};
        glyphs_buffer_.at(slot) = {};
        return;
    }
    if (*faces_made || *parts_made || *glyphs_made) {
        gpu::SetWriter(*sets_.at(slot))
            .buffer(sl::glyph_draws.binding, vk::DescriptorType::eStorageBuffer, glyphs_buffer_.at(slot).handle())
            .buffer(sl::coverage_tables.binding, vk::DescriptorType::eStorageBuffer, tables_.handle())
            .buffer(4, vk::DescriptorType::eStorageBuffer, faces_buffer_.at(slot).handle())
            .buffer(6, vk::DescriptorType::eStorageBuffer, parts_buffer_.at(slot).handle())
            .apply(device_->handle());
    }
    // each list where it has any: an empty vector's data may be null, which memcpy may not take even for no bytes
    if (!faces_.empty()) {
        std::memcpy(faces_buffer_.at(slot).bytes().data(), faces_.data(), faces_.size() * sizeof(sh::ItemRecord));
    }
    faces_buffer_.at(slot).flush();
    if (!parts_.empty()) {
        std::memcpy(parts_buffer_.at(slot).bytes().data(), parts_.data(), parts_.size() * sizeof(sh::ItemRecord));
    }
    parts_buffer_.at(slot).flush();
    if (!glyphs_.empty()) {
        std::memcpy(glyphs_buffer_.at(slot).bytes().data(), glyphs_.data(), glyphs_.size() * sizeof(sl::GlyphDraw));
    }
    glyphs_buffer_.at(slot).flush();
    slot_ = slot;
    counts_ = {.doors = static_cast<std::uint32_t>(doors.size()),
               .faces = static_cast<std::uint32_t>(faces_.size()),
               .parts = static_cast<std::uint32_t>(parts_.size()),
               .glyphs = static_cast<std::uint32_t>(glyphs_.size())};
}

void Doors::record(const frame::Recording& recording, std::uint32_t phase) {
    if (recording.state.camera.has(style::camera_flag::no_facades)) return; // the map leaves the facades' detail out
    switch (phase) {
    case draw_phase: record_draw(recording); break;
    case numbers_phase: record_numbers(recording); break;
    default: break;
    }
}

void Doors::record_draw(const frame::Recording& recording) {
    if (fills_ == nullptr || counts_.doors == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot_), {});
    const auto samples = static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples());
    const hlsl::uint4 push{samples, 0U, 0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
    if (counts_.faces > 0) {
        const std::uint32_t vertices = counts_.faces * sh::ITEM_FACE_VERTICES;
        if ((recording.state.block.camera.flags.x & sh::CAMERA_PAINTER) == 0U) {
            // at true depth the fans first, alone and plain (ENGINE.md gain 18), then the plates' edges alone
            cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, hlsl::uint4{samples, 1U, 0U, 0U});
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *fills_);
            cmd.draw(vertices, 1, 0, 0);
            recording.counters.draw("door fills", std::uint64_t{counts_.faces} * 3);
            cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, hlsl::uint4{samples, 2U, 0U, 0U});
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *faces_pipeline_);
            cmd.draw(vertices, 1, 0, 0);
            recording.counters.draw("door faces", std::uint64_t{counts_.faces} * 10);
            cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
        } else {
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *faces_pipeline_);
            cmd.draw(vertices, 1, 0, 0);
            recording.counters.draw("door faces", std::uint64_t{counts_.faces} * 13);
        }
    }
    if (counts_.parts > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *parts_pipeline_);
        cmd.draw(counts_.parts * sh::ITEM_PART_VERTICES, 1, 0, 0);
        recording.counters.draw("door outlines", std::uint64_t{counts_.parts} * 2);
    }
}

void Doors::record_numbers(const frame::Recording& recording) {
    if (numbers_ == nullptr || counts_.glyphs == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *numbers_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot_), {});
    cmd.draw(6, counts_.glyphs, 0, 0);
    recording.counters.draw("door numbers", 2ULL * counts_.glyphs, counts_.glyphs);
}

void Doors::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    if (phase != draw_phase || !state.view) return;
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    const std::vector<DoorShape> doors = door_shapes(state, index(), kind_, style->doors);
    twin::emit_doors(state, doors, style->doors, out);
}

} // namespace atlas::passes
