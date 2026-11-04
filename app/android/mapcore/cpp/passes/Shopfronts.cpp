// The shopfronts, their terraces and the open places' glows (passes/Shopfronts.hpp).
#include "passes/Shopfronts.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "gen/spirv/item/draw.hpp"
#include "gen/spirv/text/slug.hpp"
#include "geometry/RoofShapes.hpp"
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
#include "twin/item/ItemShader.hpp"
#include "twin/shop/ShopTwin.hpp"
#include "view/Frames.hpp"
#include "view/Ladder.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <format>
#include <limits>

namespace atlas::passes {

namespace {

namespace sh = atlas::shaders;
namespace sl = layouts::text_slug;

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// face()'s Camera.poly margin and least area (CSS px, px²); the stripes' margin; the signs' polygons' for their text.
constexpr double face_margin = 300.0;
constexpr double face_area = 0.12;
constexpr double stripe_margin = 200.0;
constexpr double sign_margin = 60.0;

/// The leads of a front's parts over their wall (steps of 2^-16 of their reciprocal depth nearer), over the most a
/// wall's windows take (20): its faces, their outlines and lines over them, its texts over its sign and its blade.
constexpr std::uint32_t face_lead = 21;
constexpr std::uint32_t outline_lead = 22;
constexpr std::uint32_t line_lead = 23;
constexpr std::uint32_t text_lead = 24;
/// A terrace's: its parts on the pavement, its top's outline over its top.
constexpr std::uint32_t table_lead = 1;
constexpr std::uint32_t top_outline_lead = 2;

/// A board's parts' slots among its ten (geometry::board_slots), a blade's among its four and a table's among its
/// twenty-two.
constexpr std::uint32_t frame_slot = 0;
constexpr std::uint32_t glass_slot = 1;
constexpr std::uint32_t mullion_slot = 2;
constexpr std::uint32_t awning_slot = 3;
constexpr std::uint32_t awning_outline_slot = 4;
constexpr std::uint32_t stripes_slot = 5;
constexpr std::uint32_t sign_slot = 6;
constexpr std::uint32_t sign_outline_slot = 7;
constexpr std::uint32_t label_slot = 8;
constexpr std::uint32_t spill_slot = 9;
constexpr std::uint32_t bracket_slot = 0;
constexpr std::uint32_t blade_slot = 1;
constexpr std::uint32_t blade_outline_slot = 2;
constexpr std::uint32_t blade_text_slot = 3;
constexpr std::uint32_t leg_slot = 0;
constexpr std::uint32_t chair_slot = 1; ///< each chair's leg, seat and back after it
constexpr std::uint32_t top_slot = 7;
constexpr std::uint32_t top_outline_slot = 8;
constexpr std::uint32_t pole_slot = 9;
constexpr std::uint32_t panel_slot = 10;

/// The front kinds' detail thresholds, as the doors': two that never split, so every item has detail bits 0 and 1,
/// but the items of the building the app asks about, which the ranks leave bit 0 alone.
constexpr std::uint32_t facade_detail = 2;

/// The coverage tables, a face each: the identity, as the ground's text has them until calibrated (D13).
constexpr std::size_t table_entries = 256 * text::face_count;

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
[[nodiscard]] view::Vec3 vcross(view::Vec3 a, view::Vec3 b) noexcept {
    return {(a.y * b.z) - (a.z * b.y), (a.z * b.x) - (a.x * b.z), (a.x * b.y) - (a.y * b.x)};
}
[[nodiscard]] view::Vec3 vnorm(view::Vec3 a) noexcept {
    const double l = pymath::sqrt(vdot(a, a));
    return {a.x / l, a.y / l, a.z / l};
}

/// city.py's tohex of a colour (0xRRGGBB) times `k` each channel: rounded as Python rounds, clamped to 0-255.
[[nodiscard]] std::uint32_t tohex(double r, double g, double b) noexcept {
    const auto channel = [](double v) {
        return static_cast<std::uint32_t>(std::clamp<std::int64_t>(pymath::round(v), 0, 255));
    };
    return (channel(r) << 16U) | (channel(g) << 8U) | channel(b);
}
[[nodiscard]] std::uint32_t mulc(std::uint32_t rgb, double k) noexcept {
    return tohex(static_cast<double>((rgb >> 16U) & 0xFFU) * k, static_cast<double>((rgb >> 8U) & 0xFFU) * k,
                 static_cast<double>(rgb & 0xFFU) * k);
}

/// A colour of the style (0xRRGGBB) as the shaders take it, 0xBBGGRR.
[[nodiscard]] std::uint32_t bgr(std::uint32_t rgb) noexcept {
    return ((rgb >> 16U) & 0xFFU) | (rgb & 0xFF00U) | ((rgb & 0xFFU) << 16U);
}

/// city.py:45 Camera.poly(q, margin): cut at the near plane, dropped where it lies wholly beyond the frame and the
/// margin; the corners in CSS px (unsnapped) with their eye depths.
[[nodiscard]] ShopPoly poly_of(const view::Camera& cam, std::span<const view::Vec3> q, double margin) {
    ShopPoly f;
    const double near = cam.near();
    std::array<view::Vec3, 13> kept{};
    std::uint32_t n = 0;
    for (std::size_t i = 0; i < q.size(); ++i) {
        const view::Vec3 a = cam.cam(q[i]);
        const view::Vec3 b = cam.cam(q[(i + 1) % q.size()]);
        const bool ai = a.z >= near;
        const bool bi = b.z >= near;
        if (ai && n < kept.size()) kept.at(n++) = a;
        if (ai != bi && n < kept.size()) {
            const double t = (near - a.z) / (b.z - a.z);
            kept.at(n++) = {a.x + ((b.x - a.x) * t), a.y + ((b.y - a.y) * t), near};
        }
    }
    if (n < 3) return f;
    double x0 = 1e300;
    double x1 = -1e300;
    double y0 = 1e300;
    double y1 = -1e300;
    for (std::uint32_t i = 0; i < n; ++i) {
        const view::Vec2 p = cam.pc(kept.at(i));
        f.points.at(i) = p;
        f.depths.at(i) = kept.at(i).z;
        x0 = pymath::min(x0, p.x);
        x1 = pymath::max(x1, p.x);
        y0 = pymath::min(y0, p.y);
        y1 = pymath::max(y1, p.y);
    }
    if (x1 < -margin || x0 > cam.width() + margin || y1 < -margin || y0 > cam.height() + margin) return f;
    f.count = n;
    return f;
}

/// render/amsterdam.py:610 face's polygon: Camera.poly(q, margin=300), kept where its area (city.py:80) is 0.12 px² or
/// more.
[[nodiscard]] ShopPoly face_of(const view::Camera& cam, std::span<const view::Vec3> q) {
    ShopPoly f = poly_of(cam, q, face_margin);
    double a = 0.0;
    for (std::uint32_t i = 0; i < f.count; ++i) {
        const view::Vec2 p = f.points.at(i);
        const view::Vec2 r = f.points.at((i + 1) % f.count);
        a += (p.x * r.y) - (r.x * p.y);
    }
    if (pymath::fabs(a) / 2 < face_area) f.count = 0;
    return f;
}

/// The box of a polygon's corners: its width and height (CSS px).
[[nodiscard]] std::array<double, 2> extent(const ShopPoly& f) noexcept {
    double x0 = 1e300;
    double x1 = -1e300;
    double y0 = 1e300;
    double y1 = -1e300;
    for (std::uint32_t i = 0; i < f.count; ++i) {
        x0 = std::min(x0, f.points.at(i).x);
        x1 = std::max(x1, f.points.at(i).x);
        y0 = std::min(y0, f.points.at(i).y);
        y1 = std::max(y1, f.points.at(i).y);
    }
    return {x1 - x0, y1 - y0};
}

/// The nearest reciprocal depth (near / k.z, at most 1) a polygon has: a text over it draws there.
[[nodiscard]] double nearest(const view::Camera& cam, const ShopPoly& f) noexcept {
    double r = 0.0;
    for (std::uint32_t i = 0; i < f.count; ++i) r = std::max(r, cam.near() / f.depths.at(i));
    return std::min(r, 1.0);
}

/// The reciprocal depth over the screen of the plane through `on` with normal `nrm` (passes/Doors.cpp's plane_of: on a
/// plane it is affine in the screen; any normal's length).
[[nodiscard]] ShopPlane plane_of(const view::Camera& cam, view::Vec3 nrm, view::Vec3 on) noexcept {
    const view::Vec3 n{vdot(nrm, cam.right()), vdot(nrm, cam.up()), vdot(nrm, cam.forward())};
    const double k = cam.near() / (vdot(nrm, vsub(on, cam.eye())) * cam.focal());
    return {.r0 = k * ((n.z * cam.focal()) - (n.x * cam.cx()) + (n.y * cam.cy())), .gx = k * n.x, .gy = -k * n.y};
}

/// A line from a to b where both project (Camera.proj), its width as printed.
[[nodiscard]] ShopLine line_of(const view::Camera& cam, view::Vec3 a, view::Vec3 b, double width) {
    ShopLine l;
    const auto pa = cam.proj(a);
    const auto pb = cam.proj(b);
    if (!pa || !pb) return l;
    l.drawn = true;
    l.a = *pa;
    l.b = *pb;
    l.za = cam.depth(a);
    l.zb = cam.depth(b);
    l.width = static_cast<double>(text::printed_float(width, 2));
    return l;
}

/// A shopfront kept in one frame given in another (the camera stands in another pack's frame): its node, its point on
/// the wall and its direction there.
[[nodiscard]] geometry::Shopfront placed(const geometry::Shopfront& s, const view::GroundFrame& from,
                                         const view::GroundFrame& to) noexcept {
    geometry::Shopfront o = s;
    const view::Vec3 n = view::convert(from, to, {s.x, 0.0, s.z});
    const view::Vec3 q = view::convert(from, to, {s.qx, 0.0, s.qz});
    const view::Vec3 e = view::convert(from, to, {s.qx + s.ux, 0.0, s.qz + s.uz});
    o.x = n.x;
    o.z = n.z;
    o.qx = q.x;
    o.qz = q.z;
    const double l = pymath::hypot(e.x - q.x, e.z - q.z);
    o.ux = (e.x - q.x) / l;
    o.uz = (e.z - q.z) / l;
    return o;
}

/// The first of a tile's items that are the pass's (they follow one another: build adds them in one go).
[[nodiscard]] std::uint32_t first_item(const scene::ResidentTile& tile, const ShopKinds& k) noexcept {
    for (std::size_t i = 0; i < tile.items.size(); ++i) {
        const std::uint16_t kind = tile.items[i].kind;
        if (kind == k.board || kind == k.blade || kind == k.table || kind == k.glow)
            return static_cast<std::uint32_t>(i);
    }
    return static_cast<std::uint32_t>(tile.items.size());
}

/// Python's `txt[:8] + '.'`: the first eight code points of UTF-8 text, then a full stop.
[[nodiscard]] std::string shortened(std::string_view utf8) {
    const std::u32string cps = text::code_points(utf8);
    std::string out;
    for (std::size_t i = 0; i < 8 && i < cps.size(); ++i) {
        const char32_t c = cps[i];
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6U));
            out += static_cast<char>(0x80 | (c & 0x3FU));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12U));
            out += static_cast<char>(0x80 | ((c >> 6U) & 0x3FU));
            out += static_cast<char>(0x80 | (c & 0x3FU));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18U));
            out += static_cast<char>(0x80 | ((c >> 12U) & 0x3FU));
            out += static_cast<char>(0x80 | ((c >> 6U) & 0x3FU));
            out += static_cast<char>(0x80 | (c & 0x3FU));
        }
    }
    return out + ".";
}

/// Builds a frame's records from its shapes: the faces as fan pieces of five corners at most, outlines on their planes,
/// lines; every point as printed at rest.
struct Records {
    const sh::FrameCamera& camera;
    bool rest = false;

    [[nodiscard]] float at(double v) const noexcept { return rest ? text::printed_float(v, 1) : static_cast<float>(v); }

    /// Face `f` in `rgb` at `opacity` into `into`, its corners at their depths in slot `slot` with `lead`; with
    /// `bottom`, two stops down its box (ITEM_VERTICAL): `rgb` at its top, `bottom` at its foot.
    void face(const ShopPoly& f, std::uint32_t slot, std::uint32_t lead, std::uint32_t rgb,
              std::vector<sh::ItemRecord>& into, float opacity = 1.0F,
              std::optional<std::uint32_t> bottom = std::nullopt) const {
        if (f.count < 3) return;
        for (std::uint32_t k = 1; k + 1 < f.count; k += 3) {
            sh::ItemPoly p = sh::item_poly_none();
            const std::uint32_t n = std::min<std::uint32_t>(4, f.count - k) + 1;
            for (std::uint32_t i = 0; i < n; ++i) {
                const std::uint32_t c = i == 0 ? 0 : k + i - 1;
                const hlsl::float2 q{at(f.points.at(c).x), at(f.points.at(c).y)};
                const auto z = static_cast<float>(f.depths.at(c));
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
            p.count = n;
            sh::ItemRecord r = sh::item_face(camera, p, slot, lead, bgr(rgb), 0.0F);
            r.a.z = bits(opacity);
            if (bottom) {
                r.a.x = r.a.x | sh::ITEM_VERTICAL;
                r.e.w = bgr(*bottom);
            }
            into.push_back(r);
        }
    }

    /// Face `f`'s outline, `width` wide in `rgb`: a round-capped segment an edge (the objects group's round joins), on
    /// its plane across its width (its lead's depth there; under the painter switch its slot's, flat).
    void outline(const ShopPoly& f, const ShopPlane& plane, std::uint32_t slot, std::uint32_t lead, std::uint32_t rgb,
                 float width, std::vector<sh::ItemRecord>& into) const {
        if (f.count < 3) return;
        const float half = width / 2;
        const double l = 1.0 + (static_cast<double>(sh::ITEM_LEAD) * lead);
        const double scale = (camera.flags.x & sh::CAMERA_PAINTER) != 0U   ? 0.0
                             : (camera.flags.x & sh::CAMERA_DEPTH24) != 0U ? 0.5 * l
                                                                           : l;
        const hlsl::float2 gradient{static_cast<float>(plane.gx * scale), static_cast<float>(plane.gy * scale)};
        for (std::uint32_t e = 0; e < f.count; ++e) {
            const std::uint32_t k = (e + 1) % f.count;
            into.push_back(sh::item_on_plane(
                sh::item_segment({at(f.points.at(e).x), at(f.points.at(e).y)},
                                 {at(f.points.at(k).x), at(f.points.at(k).y)},
                                 sh::item_depth(camera, static_cast<float>(f.depths.at(e)), slot, lead),
                                 sh::item_depth(camera, static_cast<float>(f.depths.at(k)), slot, lead), half,
                                 sh::ITEM_ROUND, bgr(rgb), 1.0F, 0U),
                gradient));
        }
    }

    /// A butt-capped line in `rgb`, each end at its depth.
    void line(const ShopLine& l, std::uint32_t slot, std::uint32_t lead, std::uint32_t rgb,
              std::vector<sh::ItemRecord>& into) const {
        if (!l.drawn) return;
        into.push_back(sh::item_segment({at(l.a.x), at(l.a.y)}, {at(l.b.x), at(l.b.y)},
                                        sh::item_depth(camera, static_cast<float>(l.za), slot, lead),
                                        sh::item_depth(camera, static_cast<float>(l.zb), slot, lead),
                                        static_cast<float>(l.width / 2), 0U, bgr(rgb), 1.0F, 0U));
    }
};

} // namespace

ShopFrame shop_shapes(const frame::FrameState& state, std::uint32_t pass, const ShopKinds& kinds,
                      const tiles::ShopfrontsStyle& style, OpenHours& hours) {
    ShopFrame out;
    if (state.cover == nullptr || state.ranks == nullptr || state.items == nullptr || !state.view) return out;
    // `if th.get('wx') and not S.get('no_facades')`: the fronts with weather (only the living map's tiles carry them);
    // the glows `if night:`
    const bool weather = state.theme.has(style::theme_flag::weather);
    const bool night = state.theme.has(style::theme_flag::night);
    const bool fronts = weather && !state.camera.leaves_out(style::camera_part::shopfronts);
    const bool terraces = weather && !state.camera.leaves_out(style::camera_part::terraces);
    const bool glows = night && !state.camera.leaves_out(style::camera_part::open_glows);
    if (!fronts && !terraces && !glows) return out;
    const view::Camera& cam = state.view->camera();
    // far(n['x'], n['z']): the renders' radius cut, as the ranks cut the items, in engine mode about the view's target
    std::optional<std::array<double, 3>> cut; // radius, x, z
    if (state.camera.has(style::camera_flag::engine_mode) && state.placed) {
        if (const auto radius = state.placed->number("radius")) {
            const view::Vec3 target = state.placed->camera().target;
            cut = std::array{*radius, target.x, target.z};
        }
    }
    const auto kept_by_cut = [&cut](double x, double z) {
        return !cut || view::java_hypot(x - (*cut)[1], z - (*cut)[2]) <= (*cut)[0];
    };
    const std::span<const std::uint32_t> table = state.ranks->table();
    const std::span<const scene::CoverTile> tiles = state.cover->upright();
    const bool dithers = state.cover->dithers();
    const std::span<const std::uint16_t> flags = state.items->flags();
    const std::span<const std::uint8_t> cells = state.items->cell();
    const view::Vec3 eye = cam.eye();
    // a box's nodes (y up) all nearer than lo or deeper than hi: the depth is linear, so its corners bound it
    const auto beyond = [&](const std::array<double, 4>& box, const view::GroundFrame& frame, bool convert, double y,
                            double lo, double hi) {
        if (box[0] > box[2]) return true;
        double least = 1e300;
        double most = -1e300;
        for (const auto& [x, z] : std::array<std::array<double, 2>, 4>{
                 {{box[0], box[1]}, {box[2], box[1]}, {box[0], box[3]}, {box[2], box[3]}}}) {
            const view::Vec3 p = convert ? view::convert(frame, *state.ground_frame, {x, y, z}) : view::Vec3{x, y, z};
            const double d = cam.depth(p);
            least = std::min(least, d);
            most = std::max(most, d);
        }
        return least > hi + 1 || most < lo - 1;
    };
    // the water of every tile the cover draws (on_water): each tile's pieces in its own frame
    struct Water {
        const ShopTile* tile = nullptr;
        const view::GroundFrame* frame = nullptr;
        bool convert = false;
    };
    std::vector<Water> waters;
    // the cover's shopfronts, each tile's once, that the port's tests keep (`far`, the node 2 to 230 m deep, the wall
    // facing the eye), in S['nodes'] order
    struct At {
        geometry::Shopfront shop;
        const scene::ResidentTile* tile = nullptr;
        std::uint32_t at = 0; // its board's place among the tile's rows
        double d0 = 0;
        view::Vec3 nrm;
        std::int8_t now = -1; // open_at: 1 open, 0 closed, -1 none
        std::uint32_t fade = 0;
    };
    std::vector<At> kept;
    for (std::size_t c = 0; c < tiles.size(); ++c) {
        const scene::CoverTile& t = tiles[c];
        if (t.drawn == nullptr || pass >= t.drawn->passes.size()) continue;
        const auto* shops = t.drawn->passes[pass].cpu_as<ShopTile>();
        if (shops == nullptr) continue;
        const bool convert = state.ground_frame && *state.ground_frame != t.drawn->frame;
        if (!shops->water.empty()) waters.push_back({.tile = shops, .frame = &t.drawn->frame, .convert = convert});
        // a tile's shops once, though it draws for several tiles (a stand-in ancestor, its cells over each)
        if (!scene::draws_items(tiles, c)) continue;
        const std::uint32_t fade = dithers ? sh::fade_word(t.place.offset.w, t.place.scale.w) : 0U;
        const std::uint32_t first = first_item(*t.drawn, kinds);
        const std::vector<std::int8_t>& answers = hours.of(shops->serial, shops->hours, state.theme);
        const auto row_of = [&](std::uint32_t at) -> std::optional<std::uint32_t> {
            if (at >= t.drawn->rows.size()) return std::nullopt;
            const std::uint32_t row = t.drawn->rows[at];
            // a coarser pack's within the active pack's box is the active pack's to draw (none)
            if (row >= flags.size() || (flags[row] & scene::item_flag::masked) != 0) return std::nullopt;
            if (!scene::draws_item(t, cells, row)) return std::nullopt;
            return row;
        };
        if ((fronts || terraces) && !beyond(shops->shops_box, t.drawn->frame, convert, 1.0, 2.0, 230.0)) {
            for (std::size_t i = 0; i < shops->shops.size(); ++i) {
                const std::uint32_t at = first + shops->firsts[i];
                if (!row_of(at)) continue;
                const geometry::Shopfront s =
                    convert ? placed(shops->shops[i], t.drawn->frame, *state.ground_frame) : shops->shops[i];
                if (!kept_by_cut(s.x, s.z)) continue;
                const double d0 = cam.depth({s.x, 1.0, s.z});
                if (d0 < 2 || d0 > 230) continue;
                const view::Vec3 nrm = vnorm({s.uz, 0.0, -s.ux});
                if (vdot(nrm, vsub(eye, {s.qx, 1.2, s.qz})) <= 0) continue; // that wall faces away
                const std::int8_t now = s.hours < answers.size() ? answers[s.hours] : std::int8_t{-1};
                kept.push_back({.shop = s, .tile = t.drawn, .at = at, .d0 = d0, .nrm = nrm, .now = now, .fade = fade});
            }
        }
        if (glows && !beyond(shops->places_box, t.drawn->frame, convert, 1.5, 2.0, 1e300)) {
            for (std::size_t j = 0; j < shops->places.size(); ++j) {
                const auto row = row_of(first + shops->places_first + static_cast<std::uint32_t>(j));
                if (!row || *row >= table.size() || table[*row] == sh::RANK_NONE) continue;
                const geometry::OpenPlace& p = shops->places[j];
                if (p.hours >= answers.size() || answers[p.hours] != 1) continue;
                const view::Vec3 n = convert ? view::convert(t.drawn->frame, *state.ground_frame, {p.x, 0.0, p.z})
                                             : view::Vec3{p.x, 0.0, p.z};
                if (!kept_by_cut(n.x, n.z)) continue;
                const view::Vec3 k = cam.cam({n.x, 1.5, n.z});
                if (k.z < 2) continue;
                const view::Vec2 q = cam.pc(k);
                out.glows.push_back({.node = p.node,
                                     .id = p.id,
                                     .x = q.x,
                                     .y = q.y,
                                     .r = pymath::max(3.0, 5 * cam.focal() / k.z),
                                     .fade = fade});
            }
        }
    }
    std::ranges::stable_sort(kept, {}, [](const At& a) { return a.shop.node; });
    std::ranges::stable_sort(out.glows, {}, [](const GlowShape& g) { return g.node; });

    const auto on_water = [&](double x, double z) {
        for (const Water& w : waters) {
            const view::Vec3 p =
                w.convert ? view::convert(*state.ground_frame, *w.frame, {x, 0.0, z}) : view::Vec3{x, 0.0, z};
            for (const geometry::WaterPiece& piece : w.tile->water) {
                if (p.x < piece.x0 || p.x > piece.x1 || p.z < piece.z0 || p.z > piece.z1) continue;
                if (geometry::pip(p.x, p.z, piece.ring)) return true;
            }
        }
        return false;
    };
    const double w2 = geometry::shop_half_width;
    const std::array<double, 3>& sun = state.theme.sun_vector;
    for (const At& a : kept) {
        const geometry::Shopfront& s = a.shop;
        const view::Vec3 nrm = a.nrm;
        const bool open = a.now == 1;
        // the user (4 November): a terrace only while its place is open, and by day where its hours say nothing
        const bool terrace = a.now == 1 || (a.now == -1 && !night);
        const auto rank_of = [&](std::uint32_t k) -> std::uint32_t {
            const std::uint32_t at = a.at + k;
            if (at >= a.tile->rows.size()) return sh::RANK_NONE;
            const std::uint32_t row = a.tile->rows[at];
            if (row >= table.size()) return sh::RANK_NONE;
            const std::uint32_t rank = table[row];
            return rank != sh::RANK_NONE && (sh::rank_detail(rank) & facade_detail) != 0 ? rank : sh::RANK_NONE;
        };
        // p3(u, y, off) and quad(u0, u1, y0, y1, off)
        const auto p3 = [&](double u, double y, double off) {
            return view::Vec3{s.qx + (s.ux * u) + (nrm.x * off), y, s.qz + (s.uz * u) + (nrm.z * off)};
        };
        const auto quad = [&](double u0, double u1, double y0, double y1, double off) {
            return std::array<view::Vec3, 4>{p3(u0, y0, off), p3(u1, y0, off), p3(u1, y1, off), p3(u0, y1, off)};
        };
        const double sc = cam.focal() / pymath::max(a.d0, 1.0); // s
        const std::uint32_t col = s.colour;
        const bool brand = !s.brand.empty();
        // the sign board: the brand in its colours, or the kind in the category colour, dimmed at night while closed
        std::uint32_t bg = s.sign ? *s.sign : mulc(col, 0.8);
        const std::uint32_t fg = s.text ? *s.text : style.text;
        if (night && !open) bg = mulc(bg, 0.35);
        ShopShape shape{.node = s.node, .id = s.id, .open = open, .fade = a.fade};
        if (fronts) {
            if (const std::uint32_t rank = rank_of(0); rank != sh::RANK_NONE) {
                BoardShape b;
                b.rank = rank;
                b.frame = face_of(cam, quad(-w2, w2, 0.0, 2.85, 0.07));
                b.frame_colour = night ? style.frame.night : style.frame.day;
                b.glass = face_of(cam, quad(-w2 + 0.12, w2 - 0.12, 0.12, 2.7, 0.08));
                b.glass_colour = open ? style.glass_open : style.glass_closed;
                b.glass_lit = open;
                for (std::size_t k = 0; k < b.mullions.size(); ++k) {
                    const double m = k == 0 ? -w2 / 3 : w2 / 3;
                    b.mullions.at(k) = line_of(cam, p3(m, 0.12, 0.09), p3(m, 2.7, 0.09), pymath::max(0.5, 0.06 * sc));
                }
                if (s.category != geometry::ShopCategory::other || brand) { // an awning, out over the pavement
                    const std::array<view::Vec3, 4> q{p3(-w2 - 0.1, 3.0, 0.1), p3(w2 + 0.1, 3.0, 0.1),
                                                      p3(w2 + 0.1, 2.55, 1.15), p3(-w2 - 0.1, 2.55, 1.15)};
                    b.awning = face_of(cam, q);
                    b.awning_plane = plane_of(cam, vcross(vsub(q[1], q[0]), vsub(q[3], q[0])), q[0]);
                    b.awning_colour = mulc(col, night ? 0.5 : 0.95);
                    b.awning_outline = mulc(col, 0.6);
                    if (s.category == geometry::ShopCategory::food && !brand) {
                        b.stripe_colour = night ? style.stripes.night : style.stripes.day;
                        for (int k = 0; k < 7; ++k) {
                            const double u0 = (-w2 - 0.1) + ((((2 * w2) + 0.2) * (2 * k)) / 14);
                            const double u1 = u0 + (((2 * w2) + 0.2) / 14);
                            const std::array<view::Vec3, 4> sq{p3(u0, 3.0, 0.11), p3(u1, 3.0, 0.11), p3(u1, 2.55, 1.16),
                                                               p3(u0, 2.55, 1.16)};
                            ShopPoly sp = poly_of(cam, sq, stripe_margin);
                            if (sp.count > 0) b.stripes.push_back(sp);
                        }
                    }
                }
                const std::array<view::Vec3, 4> board = quad(-w2 + 0.2, w2 - 0.2, 3.08, 3.62, 0.09);
                b.sign = face_of(cam, board);
                b.sign_plane = plane_of(cam, nrm, board[0]);
                b.sign_colour = bg;
                // its text, only when the board is seen face-on enough to read
                const auto c = cam.proj(p3(0, 3.35, 0.1));
                const ShopPoly bsp = poly_of(cam, board, sign_margin);
                const std::array<double, 2> wh = extent(bsp);
                const std::string label = brand ? s.brand : text::upper(s.kind);
                const auto len = static_cast<double>(text::code_points(label).size());
                if (c && bsp.count > 0 && wh[0] > wh[1] * 1.6 && len > 0) {
                    const double fs = std::min({14.0, sc * 0.4, wh[1] * 0.5, wh[0] * 0.9 / (len * 0.62)});
                    b.label = {.drawn = true,
                               .text = label,
                               .x = c->x,
                               .y = c->y + (fs * 0.35),
                               .size = fs,
                               .spacing = fs * 0.04,
                               .fill = night && !open ? mulc(fg, 0.5) : fg,
                               .r = nearest(cam, bsp)};
                }
                if (night && open) b.spill = face_of(cam, quad(-2.8, 2.8, 0.0, 0.05, 2.0));
                shape.board = std::move(b);
            }
            // a blade sign out over the pavement: what you read when you look along a street
            const double bu = w2 + 0.35;
            const std::array<view::Vec3, 4> blade{p3(bu, 2.75, 0.18), p3(bu, 2.75, 1.05), p3(bu, 3.55, 1.05),
                                                  p3(bu, 3.55, 0.18)};
            const view::Vec3 bp = p3(bu, 3.15, 0.6);
            const view::Vec3 cb = cam.cam(bp);
            if (const std::uint32_t rank = rank_of(1); rank != sh::RANK_NONE && cb.z > 1.5) {
                BladeShape bl;
                bl.rank = rank;
                bl.bracket =
                    line_of(cam, p3(bu, 3.62, 0.05), p3(bu, 3.62, 1.1), pymath::max(0.4, 0.05 * cam.focal() / cb.z));
                bl.blade = face_of(cam, blade);
                bl.plane = plane_of(cam, {s.ux, 0.0, s.uz}, bp);
                bl.colour = bg;
                const auto pc = cam.proj(bp);
                const ShopPoly sp = poly_of(cam, blade, sign_margin);
                std::string txt = brand ? s.brand : s.kind;
                auto len = static_cast<double>(text::code_points(txt).size());
                if (pc && sp.count > 0 && len > 0) {
                    const std::array<double, 2> wh = extent(sp);
                    const double bw = wh[0];
                    const double bh = wh[1];
                    double fs = std::min({bh * 0.42, bw * 0.88 / (len * 0.62), 15.0});
                    if (bw < bh * 0.7 || fs > 2.4) {
                        const bool turned = bw < bh * 0.7; // seen edge-on: the name runs down the blade, short form
                        if (turned) {
                            if (len > 9) txt = shortened(txt);
                            len = static_cast<double>(text::code_points(txt).size());
                            fs = std::min(bh / ((len * 0.72) + 0.6), 13.0);
                        }
                        bl.text = {.drawn = true,
                                   .text = txt,
                                   .x = pc->x,
                                   .y = pc->y + (fs * 0.35),
                                   .size = fs,
                                   .spacing = 0.0,
                                   .fill = fg,
                                   .turned = turned,
                                   .cx = pc->x,
                                   .cy = pc->y,
                                   .r = nearest(cam, sp)};
                    }
                }
                shape.blade = std::move(bl);
            }
        }
        // a terrace: outdoor_seating puts tables and chairs on the pavement, and a parasol by day
        if (terraces && terrace && s.seating) {
            for (std::uint32_t k = 0; k < geometry::table_places.size(); ++k) {
                const std::uint32_t rank = rank_of(2 + k);
                if (rank == sh::RANK_NONE) continue;
                const double du = geometry::table_places.at(k)[0];
                const double off = geometry::table_places.at(k)[1];
                const double x = s.qx + (s.ux * du) + (nrm.x * off);
                const double z = s.qz + (s.uz * du) + (nrm.z * off);
                if (on_water(x, z)) continue;
                const view::Vec3 cc = cam.cam({x, 0.75, z});
                if (cc.z < 1.5) continue;
                TableShape tb;
                tb.rank = rank;
                std::array<view::Vec3, 12> top{};
                for (std::size_t i = 0; i < top.size(); ++i) {
                    const double an = static_cast<double>(i) * pymath::pi / 6;
                    top.at(i) = {x + (0.38 * pymath::cos(an)), 0.75, z + (0.38 * pymath::sin(an))};
                }
                tb.wood = night ? style.wood.night : style.wood.day;
                tb.leg = line_of(cam, {x, 0.0, z}, {x, 0.75, z}, pymath::max(0.4, 0.06 * cam.focal() / cc.z));
                for (std::size_t ci = 0; ci < tb.chairs.size(); ++ci) { // two chairs either side
                    const double cu = ci == 0 ? -0.6 : 0.6;
                    const double chx = x + (s.ux * cu);
                    const double chz = z + (s.uz * cu);
                    ChairShape& ch = tb.chairs.at(ci);
                    // draw_box(cam, th, chx, chz, 0.42, 0.42, 0.46, chair)
                    const double w = 0.42;
                    const double h = 0.46;
                    const std::array<view::Vec3, 4> q{
                        view::Vec3{chx - (w / 2), h, chz - (w / 2)}, view::Vec3{chx + (w / 2), h, chz - (w / 2)},
                        view::Vec3{chx + (w / 2), h, chz + (w / 2)}, view::Vec3{chx - (w / 2), h, chz + (w / 2)}};
                    const double k3 = night ? 0.3 : 1.0;
                    const auto ch_r = static_cast<double>((style.chair >> 16U) & 0xFFU) * k3;
                    const auto ch_g = static_cast<double>((style.chair >> 8U) & 0xFFU) * k3;
                    const auto ch_b = static_cast<double>(style.chair & 0xFFU) * k3;
                    ch.seat = face_of(cam, q);
                    ch.seat_colour = night ? tohex(ch_r, ch_g, ch_b) : style.chair;
                    ch.leg = line_of(cam, {chx, 0.0, chz}, {chx, h, chz}, static_cast<double>(style.chair_leg_width));
                    ch.leg_colour = tohex(ch_r * 0.8, ch_g * 0.8, ch_b * 0.8);
                    const double bx = chx + (s.ux * cu * 0.3);
                    const double bz = chz + (s.uz * cu * 0.3);
                    const std::array<view::Vec3, 4> back{view::Vec3{bx - (0.2 * nrm.x), 0.46, bz - (0.2 * nrm.z)},
                                                         view::Vec3{bx + (0.2 * nrm.x), 0.46, bz + (0.2 * nrm.z)},
                                                         view::Vec3{bx + (0.2 * nrm.x), 0.9, bz + (0.2 * nrm.z)},
                                                         view::Vec3{bx - (0.2 * nrm.x), 0.9, bz - (0.2 * nrm.z)}};
                    ch.back = face_of(cam, back);
                    ch.back_colour = night ? style.chair_back_night : style.chair;
                }
                tb.top = face_of(cam, top);
                tb.top_plane = plane_of(cam, {0.0, 1.0, 0.0}, {x, 0.75, z});
                tb.top_colour = night ? style.top.night : style.top.day;
                if (!night && k < 2) { // a parasol over the front tables
                    tb.parasol = true;
                    tb.pole = line_of(cam, {x, 0.75, z}, {x, 2.35, z}, pymath::max(0.4, 0.05 * cam.focal() / cc.z));
                    std::array<view::Vec3, 12> ring{};
                    for (std::size_t i = 0; i < ring.size(); ++i) {
                        const double an = static_cast<double>(i) * pymath::pi / 6;
                        ring.at(i) = {x + (1.3 * pymath::cos(an)), 2.15, z + (1.3 * pymath::sin(an))};
                    }
                    const view::Vec3 apex{x, 2.65, z};
                    const std::uint32_t pc = s.sign ? *s.sign : mulc(col, 1.0);
                    for (std::size_t i = 0; i < ring.size(); ++i) {
                        const std::array<view::Vec3, 3> tri{ring.at(i), ring.at((i + 1) % ring.size()), apex};
                        view::Vec3 nr = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])));
                        if (nr.y < 0) nr = {-nr.x, -nr.y, -nr.z};
                        const double shade = vdot(nr, {sun[0], sun[1], sun[2]}) > 0 ? 1.0 : 0.78;
                        tb.panels.at(i) = face_of(cam, tri);
                        tb.panel_colours.at(i) = mulc(i % 2 != 0 ? pc : style.panel, shade);
                    }
                }
                shape.tables.push_back(tb);
            }
        }
        if (shape.board || shape.blade || !shape.tables.empty()) out.shops.push_back(std::move(shape));
    }
    return out;
}

Shopfronts::Shopfronts() = default;

Shopfronts::~Shopfronts() { Shopfronts::release(); }

std::span<const sh::ItemRecord> Shopfronts::faces() const noexcept { return faces_; }

std::span<const sh::ItemRecord> Shopfronts::parts() const noexcept { return parts_; }

std::span<const sh::ItemRecord> Shopfronts::glows() const noexcept { return glows_; }

gpu::Result<void> Shopfronts::prepare(const frame::PassContext& context) {
    release();
    // none in the owner mode: the likeness harness's owner maps draw no facade details yet
    if (context.owners) return {};
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(items, frame::make_module(context, "item_draw", spirv::item_draw));
    items_module_ = std::move(items);
    ATLAS_GPU_LET(glyph_module, frame::make_module(context, "text_slug", spirv::text_slug));
    text_module_ = std::move(glyph_module);
    // Set 2: the texts' glyphs and the coverage tables (slug.slang's bindings 0 and 1), the faces, the lines and the
    // glows (items.slangh's records, draw.slang's bindings 4, 6 and 8), and the records' standing fades (binding 9).
    using Stage = vk::ShaderStageFlagBits;
    const auto storage = vk::DescriptorType::eStorageBuffer;
    const std::array bindings{
        gpu::Binding{.slot = sl::glyph_draws.binding, .type = storage, .stages = Stage::eVertex | Stage::eFragment},
        gpu::Binding{.slot = sl::coverage_tables.binding, .type = storage, .stages = Stage::eFragment},
        gpu::Binding{.slot = 4, .type = storage, .stages = Stage::eVertex},
        gpu::Binding{.slot = 6, .type = storage, .stages = Stage::eVertex},
        gpu::Binding{.slot = 8, .type = storage, .stages = Stage::eVertex | Stage::eFragment},
        gpu::Binding{.slot = 9, .type = storage, .stages = Stage::eVertex}};
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
    // the faces and the lines: at their corners' depths, writing the depth and the objects bit, through the region
    // items' stages
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
    ATLAS_GPU_LET(fills, opaque("shop fills", "items_faces_vs", "items_fills_fs"));
    fills_ = std::move(fills);
    // and while a tile fades, on the samples its standing fade keeps (ENGINE.md's standing fades)
    ATLAS_GPU_LET(fills_fading, opaque("shop fills while fading", "items_faces_vs", "items_fills_fading_fs"));
    fills_fading_ = std::move(fills_fading);
    ATLAS_GPU_LET(parts, opaque("shop lines", "items_parts_vs", "items_parts_fs"));
    parts_pipeline_ = std::move(parts);
    // the translucent ones: the test on and writes off; the glows after every object, the test off too
    const auto translucent = [&](const char* name, const char* module, vk::ShaderModule shader, const char* vertex,
                                 const char* fragment, bool depth) {
        gpu::GraphicsDesc desc{.label = {.name = name, .module = module, .configuration = context.configuration},
                               .module = shader,
                               .vertex = vertex,
                               .fragment = fragment,
                               .layout = *layout_,
                               .target = context.pass.scene_target(),
                               .depth_test = depth,
                               .depth_write = false,
                               .depth_op = vk::CompareOp::eGreater};
        desc.blends[0] = gpu::Blend::over();
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        return frame::make_pipeline(context, desc, frame::Overdraw::translucent);
    };
    ATLAS_GPU_LET(tints,
                  translucent("shop tints", "item_draw", *items_module_, "items_faces_vs", "items_tints_fs", true));
    tints_ = std::move(tints);
    ATLAS_GPU_LET(glows,
                  translucent("open glows", "item_draw", *items_module_, "items_sides_vs", "items_radials_fs", false));
    glows_pipeline_ = std::move(glows);
    ATLAS_GPU_LET(texts, translucent("shop texts", "text_slug", *text_module_, "wall_text_vs", "wall_text_fs", true));
    texts_ = std::move(texts);
    ATLAS_GPU_LET(texts_fading, translucent("shop texts while fading", "text_slug", *text_module_, "wall_text_vs",
                                            "wall_text_fading_fs", true));
    texts_fading_ = std::move(texts_fading);
    return {};
}

void Shopfronts::release() noexcept {
    texts_fading_.clear();
    texts_.clear();
    glows_pipeline_.clear();
    tints_.clear();
    parts_pipeline_.clear();
    fills_fading_.clear();
    fills_.clear();
    layout_.clear();
    for (vk::raii::DescriptorSet& s : sets_) s.clear();
    pool_.clear();
    set_layout_.clear();
    text_module_.clear();
    items_module_.clear();
    faces_buffer_ = {};
    parts_buffer_ = {};
    glows_buffer_ = {};
    glyphs_buffer_ = {};
    fades_buffer_ = {};
    tables_ = {};
    counts_ = {};
}

void Shopfronts::kinds(frame::Selectors& selectors) {
    kinds_ = {.board = selectors.kind("shopfront"),
              .blade = selectors.kind("blade"),
              .table = selectors.kind("terrace"),
              .glow = selectors.kind("open glow")};
}

void Shopfronts::select(const frame::FrameState& state, frame::Selectors& selectors) {
    const std::array<double, 2> details{frame::Selectors::never, frame::Selectors::never};
    for (const std::uint16_t k : {kinds_.board, kinds_.blade, kinds_.table, kinds_.glow})
        selectors.set_details(k, details);
    // the parts left out of the map, and the glows by day: their items take no rank
    const bool weather = state.theme.has(style::theme_flag::weather);
    const bool night = state.theme.has(style::theme_flag::night);
    const bool fronts = weather && !state.camera.leaves_out(style::camera_part::shopfronts);
    selectors.leave_out(kinds_.board, !fronts);
    selectors.leave_out(kinds_.blade, !fronts);
    selectors.leave_out(kinds_.table, !weather || state.camera.leaves_out(style::camera_part::terraces));
    selectors.leave_out(kinds_.glow, !night || state.camera.leaves_out(style::camera_part::open_glows));
}

gpu::Result<void> Shopfronts::build(scene::TileBuild& b) const {
    geometry::TileShopfronts g = geometry::build_shopfronts({.tile = &b.tile(),
                                                             .head = &b.head(),
                                                             .board = kinds_.board,
                                                             .blade = kinds_.blade,
                                                             .table = kinds_.table,
                                                             .glow = kinds_.glow});
    if (g.shops.empty() && g.places.empty()) return {};
    for (const scene::ItemSpec& item : g.items) b.add_item(item);
    auto kept = std::make_shared<ShopTile>();
    kept->serial = next_serial();
    // each shopfront's board among the items: its board, its blade, its tables where it has a terrace
    std::uint32_t at = 0;
    kept->firsts.reserve(g.shops.size());
    for (const geometry::Shopfront& s : g.shops) {
        kept->firsts.push_back(at);
        at += 2 + (s.seating ? static_cast<std::uint32_t>(geometry::table_places.size()) : 0U);
        kept->shops_box = {std::min(kept->shops_box[0], s.x), std::min(kept->shops_box[1], s.z),
                           std::max(kept->shops_box[2], s.x), std::max(kept->shops_box[3], s.z)};
    }
    kept->places_first = at;
    for (const geometry::OpenPlace& p : g.places) {
        kept->places_box = {std::min(kept->places_box[0], p.x), std::min(kept->places_box[1], p.z),
                            std::max(kept->places_box[2], p.x), std::max(kept->places_box[3], p.z)};
    }
    std::size_t bytes = sizeof(ShopTile) + (g.shops.capacity() * sizeof(geometry::Shopfront)) +
                        (g.places.capacity() * sizeof(geometry::OpenPlace)) +
                        (kept->firsts.capacity() * sizeof(std::uint32_t));
    for (const geometry::Shopfront& s : g.shops) bytes += s.kind.capacity() + s.brand.capacity();
    for (const std::string& h : g.hours) bytes += sizeof(std::string) + h.capacity();
    for (const geometry::WaterPiece& w : g.water)
        bytes += sizeof(geometry::WaterPiece) + (w.ring.capacity() * sizeof(geometry::V2));
    kept->shops = std::move(g.shops);
    kept->places = std::move(g.places);
    kept->hours = std::move(g.hours);
    kept->water = std::move(g.water);
    b.keep(std::shared_ptr<const ShopTile>(std::move(kept)), bytes);
    return {};
}

const Shopfronts::Shaped& Shopfronts::shaped(const text::TextRun& run) {
    static const Shaped nothing;
    const text::Rounding rounding = text::platform_rounding();
    const auto style = run.shape_style(rounding);
    if (!style) return nothing;
    const std::string key =
        std::format("{}\x1f{}\x1f{}\x1f{:08x}\x1f{:08x}\x1f{}", run.text, static_cast<int>(style->font.face),
                    style->font.weight, bits(style->size), bits(style->letter_spacing), static_cast<int>(rounding));
    if (const auto it = shaped_.find(key); it != shaped_.end()) return it->second;
    const text::Fonts& fonts = text::shared_fonts();
    const auto instance = fonts.instance(style->font);
    if (!instance) return nothing;               // the face is not loaded yet: asked again next frame
    if (shaped_.size() >= 4096) shaped_.clear(); // the signs and sizes of a session: kept within bounds
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

void Shopfronts::add_text(const text::TextRun& run, std::uint32_t slot, float depth, std::uint32_t fade) {
    const Shaped& s = shaped(run);
    if (!s.ok) return;
    laid_.clear();
    text::lay_out(run, run.matrix, s.run, s.places, 1.0, laid_);
    const auto face = text::resolve(run.family, run.weight, false);
    const std::uint32_t table = face ? static_cast<std::uint32_t>(face->face) : 0U;
    for (const text::GlyphInstance& g : laid_) {
        if (glyphs_.size() >= max_glyphs) break;
        const text::slug::Glyph& at = *g.glyph;
        glyphs_.push_back({.row0 = {g.row0[0], g.row0[1], g.row0[2], 0.0F},
                           .row1 = {g.row1[0], g.row1[1], g.row1[2], 0.0F},
                           .box = {at.box.x0, at.box.y0, at.box.x1, at.box.y1},
                           .bands = {at.scale_x, at.scale_y, at.offset_x, at.offset_y},
                           .location = {at.band_x, at.band_y, at.vbands - 1, at.hbands - 1},
                           .colour = {g.colour[0], g.colour[1], g.colour[2], g.colour[3]},
                           .info = {table, slot, bits(depth), fade}});
    }
}

void Shopfronts::update(const frame::FrameState& state) {
    counts_ = {};
    faces_.clear();
    parts_.clear();
    glows_.clear();
    glyphs_.clear();
    fade_words_.clear();
    dithers_ = state.cover != nullptr && state.cover->dithers();
    if (fills_ == nullptr || !state.view) return;
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    ShopFrame frame = shop_shapes(state, index(), kinds_, style->shopfronts, hours_);
    if (frame.shops.empty() && frame.glows.empty()) return;
    // the nearest first (the latest slots), so early depth testing rejects what they hide
    const auto latest = [](const ShopShape& s) {
        std::uint32_t slot = 0;
        if (s.board) slot = std::max(slot, sh::rank_slot(s.board->rank));
        if (s.blade) slot = std::max(slot, sh::rank_slot(s.blade->rank));
        for (const TableShape& t : s.tables) slot = std::max(slot, sh::rank_slot(t.rank));
        return slot;
    };
    std::ranges::stable_sort(frame.shops, std::ranges::greater{}, latest);
    const bool night = state.theme.has(style::theme_flag::night);
    const sh::FrameCamera& camera = state.block.camera;
    const Records rec{.camera = camera, .rest = state.rest};
    const tiles::ShopfrontsStyle& st = style->shopfronts;
    // the glass's gradients by day (the walls' `glass` by the sky's cover, `glassLit`; the style's colours, 0xRRGGBB)
    const shaders::FrameWindows& w = state.block.windows;
    const bool overcast = (state.block.theme.flags.x & shaders::THEME_OVERCAST) != 0U;
    const std::array<std::uint32_t, 2> plain{overcast ? w.glass.z : w.glass.x, overcast ? w.glass.w : w.glass.y};
    const std::array<std::uint32_t, 2> lit{w.lit.x, w.lit.y};
    std::vector<std::uint32_t> part_fades;
    std::vector<sh::ItemRecord> tints;
    std::vector<std::uint32_t> tint_fades;
    std::vector<sh::ItemRecord> terrace_faces;
    std::vector<sh::ItemRecord> terrace_parts;
    std::vector<std::uint32_t> terrace_face_fades;
    std::vector<std::uint32_t> terrace_part_fades;
    const bool texts = !state.camera.leaves_out(style::camera_part::shopfronts);
    for (const ShopShape& s : frame.shops) {
        const std::size_t faces = faces_.size();
        const std::size_t parts = parts_.size();
        const std::size_t tinted = tints.size();
        const std::size_t tfaces = terrace_faces.size();
        const std::size_t tparts = terrace_parts.size();
        if (s.board) {
            const BoardShape& b = *s.board;
            const std::uint32_t slot = sh::rank_slot(b.rank);
            rec.face(b.frame, slot + frame_slot, face_lead, b.frame_colour, faces_);
            if (night) {
                rec.face(b.glass, slot + glass_slot, face_lead + 1, b.glass_colour, faces_);
            } else {
                const std::array<std::uint32_t, 2>& g = b.glass_lit ? lit : plain;
                rec.face(b.glass, slot + glass_slot, face_lead + 1, g[0], faces_, 1.0F, g[1]);
            }
            for (const ShopLine& m : b.mullions) rec.line(m, slot + mullion_slot, line_lead, st.mullion, parts_);
            rec.face(b.awning, slot + awning_slot, face_lead, b.awning_colour, faces_);
            rec.outline(b.awning, b.awning_plane, slot + awning_outline_slot, outline_lead, b.awning_outline,
                        st.awning_width, parts_);
            for (const ShopPoly& p : b.stripes) {
                rec.face(p, slot + stripes_slot, line_lead, b.stripe_colour, tints, st.stripes_opacity);
            }
            rec.face(b.sign, slot + sign_slot, face_lead, b.sign_colour, faces_);
            rec.outline(b.sign, b.sign_plane, slot + sign_outline_slot, outline_lead, st.sign_outline, st.sign_width,
                        parts_);
            rec.face(b.spill, slot + spill_slot, face_lead, st.spill, tints, st.spill_opacity);
        }
        if (s.blade) {
            const BladeShape& bl = *s.blade;
            const std::uint32_t slot = sh::rank_slot(bl.rank);
            rec.line(bl.bracket, slot + bracket_slot, face_lead, st.sign_outline, parts_);
            rec.face(bl.blade, slot + blade_slot, face_lead, bl.colour, faces_);
            rec.outline(bl.blade, bl.plane, slot + blade_outline_slot, outline_lead, st.sign_outline, st.blade_width,
                        parts_);
        }
        for (const TableShape& t : s.tables) {
            const std::uint32_t slot = sh::rank_slot(t.rank);
            rec.line(t.leg, slot + leg_slot, table_lead, t.wood, terrace_parts);
            for (std::size_t c = 0; c < t.chairs.size(); ++c) {
                const ChairShape& ch = t.chairs.at(c);
                const auto first = static_cast<std::uint32_t>(slot + chair_slot + (3 * c));
                rec.line(ch.leg, first, table_lead, ch.leg_colour, terrace_parts);
                rec.face(ch.seat, first + 1, table_lead, ch.seat_colour, terrace_faces);
                rec.face(ch.back, first + 2, table_lead, ch.back_colour, terrace_faces);
            }
            rec.face(t.top, slot + top_slot, table_lead, t.top_colour, terrace_faces);
            rec.outline(t.top, t.top_plane, slot + top_outline_slot, top_outline_lead, st.top_outline, st.top_width,
                        terrace_parts);
            if (t.parasol) {
                rec.line(t.pole, slot + pole_slot, table_lead, st.pole, terrace_parts);
                for (std::size_t i = 0; i < t.panels.size(); ++i) {
                    rec.face(t.panels.at(i), slot + panel_slot + static_cast<std::uint32_t>(i), table_lead,
                             t.panel_colours.at(i), terrace_faces);
                }
            }
        }
        // while a tile fades, its records' range of the dither (ENGINE.md's standing fades; its glyphs' in info.w)
        if (dithers_) {
            fade_words_.insert(fade_words_.end(), faces_.size() - faces, s.fade);
            part_fades.insert(part_fades.end(), parts_.size() - parts, s.fade);
            tint_fades.insert(tint_fades.end(), tints.size() - tinted, s.fade);
            terrace_face_fades.insert(terrace_face_fades.end(), terrace_faces.size() - tfaces, s.fade);
            terrace_part_fades.insert(terrace_part_fades.end(), terrace_parts.size() - tparts, s.fade);
        }
        // the signs' texts: screen-upright at their board's nearest depth, leading it
        if (!texts) continue;
        const auto run_of = [&](const ShopText& t) {
            text::TextRun run;
            run.text = t.text;
            const auto x = static_cast<double>(rec.at(t.x));
            const auto y = static_cast<double>(rec.at(t.y));
            if (t.turned) {
                // rotate(90 cx cy) of the text at (x, y)
                const auto cx = static_cast<double>(rec.at(t.cx));
                const auto cy = static_cast<double>(rec.at(t.cy));
                run.matrix = {0.0, 1.0, -1.0, 0.0, cx - (y - cy), cy + (x - cx)};
            } else {
                run.matrix = {1.0, 0.0, 0.0, 1.0, x, y};
            }
            run.family = text::geist_family;
            run.size = static_cast<double>(text::printed_float(t.size, 1)); // shaped at the size the SVG prints
            run.weight = 800;
            run.spacing = static_cast<double>(text::printed_float(t.spacing, 2));
            run.anchor = text::Anchor::middle;
            run.fill = t.fill;
            run.opacity = 1.0;
            return run;
        };
        const auto depth_of = [&](const ShopText& t, std::uint32_t slot) {
            return sh::item_depth(camera, static_cast<float>(static_cast<double>(camera.frame.w) / t.r), slot,
                                  text_lead);
        };
        if (s.board && s.board->label.drawn) {
            const std::uint32_t slot = sh::rank_slot(s.board->rank) + label_slot;
            add_text(run_of(s.board->label), slot, depth_of(s.board->label, slot), s.fade);
        }
        if (s.blade && s.blade->text.drawn) {
            const std::uint32_t slot = sh::rank_slot(s.blade->rank) + blade_text_slot;
            add_text(run_of(s.blade->text), slot, depth_of(s.blade->text, slot), s.fade);
        }
    }
    const auto front_faces = static_cast<std::uint32_t>(faces_.size());
    const auto front_parts = static_cast<std::uint32_t>(parts_.size());
    faces_.insert(faces_.end(), terrace_faces.begin(), terrace_faces.end());
    faces_.insert(faces_.end(), tints.begin(), tints.end());
    parts_.insert(parts_.end(), terrace_parts.begin(), terrace_parts.end());
    if (dithers_) {
        fade_words_.insert(fade_words_.end(), terrace_face_fades.begin(), terrace_face_fades.end());
        fade_words_.insert(fade_words_.end(), tint_fades.begin(), tint_fades.end());
        fade_words_.insert(fade_words_.end(), part_fades.begin(), part_fades.end());
        fade_words_.insert(fade_words_.end(), terrace_part_fades.begin(), terrace_part_fades.end());
    }
    // the open places' glows at night: the `open` gradient's circles
    for (const GlowShape& g : frame.glows) {
        glows_.push_back(sh::item_radial({rec.at(g.x), rec.at(g.y)}, rec.at(g.r), bgr(st.open_inner),
                                         bgr(st.open_middle), bgr(st.open_outer), st.open_inner_opacity,
                                         st.open_middle_opacity, st.open_outer_opacity, st.open_middle_offset));
        if (dithers_) fade_words_.push_back(g.fade);
    }
    // into this frame slot's buffers (its previous frame has finished: its buffers and set are free)
    const std::uint32_t slot = state.slot % 2;
    const auto records = [](std::size_t n) {
        return vk::DeviceSize{std::max<std::size_t>(n, 1)} * sizeof(sh::ItemRecord);
    };
    auto faces_made = fit(*device_, faces_buffer_.at(slot), records(faces_.size()));
    auto parts_made = fit(*device_, parts_buffer_.at(slot), records(parts_.size()));
    auto glows_made = fit(*device_, glows_buffer_.at(slot), records(glows_.size()));
    auto glyphs_made = fit(*device_, glyphs_buffer_.at(slot),
                           vk::DeviceSize{std::max<std::size_t>(glyphs_.size(), 1)} * sizeof(sl::GlyphDraw));
    auto fades_made = fit(*device_, fades_buffer_.at(slot),
                          vk::DeviceSize{std::max<std::size_t>(fade_words_.size(), 1)} * sizeof(std::uint32_t));
    if (!faces_made || !parts_made || !glows_made || !glyphs_made || !fades_made) {
        const gpu::Error& e = !faces_made    ? faces_made.error()
                              : !parts_made  ? parts_made.error()
                              : !glows_made  ? glows_made.error()
                              : !glyphs_made ? glyphs_made.error()
                                             : fades_made.error();
        log::error("vk shopfronts: {} shops, {} faces, {} lines, {} glows and {} glyphs: {}", frame.shops.size(),
                   faces_.size(), parts_.size(), glows_.size(), glyphs_.size(), e.text());
        faces_buffer_.at(slot) = {};
        parts_buffer_.at(slot) = {};
        glows_buffer_.at(slot) = {};
        glyphs_buffer_.at(slot) = {};
        fades_buffer_.at(slot) = {};
        return;
    }
    if (*faces_made || *parts_made || *glows_made || *glyphs_made || *fades_made) {
        gpu::SetWriter(*sets_.at(slot))
            .buffer(sl::glyph_draws.binding, vk::DescriptorType::eStorageBuffer, glyphs_buffer_.at(slot).handle())
            .buffer(sl::coverage_tables.binding, vk::DescriptorType::eStorageBuffer, tables_.handle())
            .buffer(4, vk::DescriptorType::eStorageBuffer, faces_buffer_.at(slot).handle())
            .buffer(6, vk::DescriptorType::eStorageBuffer, parts_buffer_.at(slot).handle())
            .buffer(8, vk::DescriptorType::eStorageBuffer, glows_buffer_.at(slot).handle())
            .buffer(9, vk::DescriptorType::eStorageBuffer, fades_buffer_.at(slot).handle())
            .apply(device_->handle());
    }
    // each list where it has any: an empty vector's data may be null, which memcpy may not take even for no bytes
    const auto upload = [](gpu::Buffer& b, const void* data, std::size_t bytes) {
        if (bytes > 0) std::memcpy(b.bytes().data(), data, bytes);
        b.flush();
    };
    upload(faces_buffer_.at(slot), faces_.data(), faces_.size() * sizeof(sh::ItemRecord));
    upload(parts_buffer_.at(slot), parts_.data(), parts_.size() * sizeof(sh::ItemRecord));
    upload(glows_buffer_.at(slot), glows_.data(), glows_.size() * sizeof(sh::ItemRecord));
    upload(glyphs_buffer_.at(slot), glyphs_.data(), glyphs_.size() * sizeof(sl::GlyphDraw));
    upload(fades_buffer_.at(slot), fade_words_.data(), fade_words_.size() * sizeof(std::uint32_t));
    slot_ = slot;
    std::uint32_t tables = 0;
    for (const ShopShape& s : frame.shops) tables += static_cast<std::uint32_t>(s.tables.size());
    counts_ = {.shops = static_cast<std::uint32_t>(frame.shops.size()),
               .tables = tables,
               .glows = static_cast<std::uint32_t>(glows_.size()),
               .front_faces = front_faces,
               .terrace_faces = static_cast<std::uint32_t>(terrace_faces.size()),
               .tints = static_cast<std::uint32_t>(tints.size()),
               .front_parts = front_parts,
               .terrace_parts = static_cast<std::uint32_t>(terrace_parts.size()),
               .glyphs = static_cast<std::uint32_t>(glyphs_.size())};
}

void Shopfronts::record(const frame::Recording& recording, std::uint32_t phase) {
    switch (phase) {
    case draw_phase: record_draw(recording); break;
    case texts_phase: record_texts(recording); break;
    case tints_phase: record_tints(recording); break;
    case glows_phase: record_glows(recording); break;
    default: break;
    }
}

void Shopfronts::record_draw(const frame::Recording& recording) {
    if (fills_ == nullptr) return;
    const Counts& n = counts_;
    if (n.front_faces + n.terrace_faces + n.front_parts + n.terrace_parts == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot_), {});
    const auto samples = static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples());
    // (w: while a tile fades, the list's first fade: the faces' the first, the lines' after every face's)
    const std::uint32_t faded = dithers_ ? 1U : 0U;
    // the fans alone, plain (no face is stroked: their outlines are lines), so early depth testing keeps
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, hlsl::uint4{samples, 1U, 0U, faded});
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, dithers_ ? *fills_fading_ : *fills_);
    if (n.front_faces > 0) {
        cmd.draw(n.front_faces * sh::ITEM_FACE_VERTICES, 1, 0, 0);
        recording.counters.draw("shop fills", std::uint64_t{n.front_faces} * 3);
    }
    if (n.terrace_faces > 0) {
        cmd.draw(n.terrace_faces * sh::ITEM_FACE_VERTICES, 1, n.front_faces * sh::ITEM_FACE_VERTICES, 0);
        recording.counters.draw("terrace fills", std::uint64_t{n.terrace_faces} * 3);
    }
    const std::uint32_t faces = n.front_faces + n.terrace_faces + n.tints;
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0,
                                   hlsl::uint4{samples, 0U, 0U, dithers_ ? faces + 1U : 0U});
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *parts_pipeline_);
    if (n.front_parts > 0) {
        cmd.draw(n.front_parts * sh::ITEM_PART_VERTICES, 1, 0, 0);
        recording.counters.draw("shop lines", std::uint64_t{n.front_parts} * 2);
    }
    if (n.terrace_parts > 0) {
        cmd.draw(n.terrace_parts * sh::ITEM_PART_VERTICES, 1, n.front_parts * sh::ITEM_PART_VERTICES, 0);
        recording.counters.draw("terrace lines", std::uint64_t{n.terrace_parts} * 2);
    }
}

void Shopfronts::record_texts(const frame::Recording& recording) {
    if (texts_ == nullptr || counts_.glyphs == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, dithers_ ? *texts_fading_ : *texts_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot_), {});
    cmd.draw(6, counts_.glyphs, 0, 0);
    recording.counters.draw("shop texts", 2ULL * counts_.glyphs, counts_.glyphs);
}

void Shopfronts::record_tints(const frame::Recording& recording) {
    if (tints_ == nullptr || counts_.tints == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *tints_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot_), {});
    const auto samples = static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples());
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, hlsl::uint4{samples, 1U, 0U, dithers_ ? 1U : 0U});
    const std::uint32_t first = counts_.front_faces + counts_.terrace_faces;
    cmd.draw(counts_.tints * sh::ITEM_FACE_VERTICES, 1, first * sh::ITEM_FACE_VERTICES, 0);
    recording.counters.draw("shop tints", std::uint64_t{counts_.tints} * 3);
}

void Shopfronts::record_glows(const frame::Recording& recording) {
    if (glows_pipeline_ == nullptr || counts_.glows == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *glows_pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot_), {});
    const auto samples = static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples());
    const std::uint32_t before =
        counts_.front_faces + counts_.terrace_faces + counts_.tints + counts_.front_parts + counts_.terrace_parts;
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0,
                                   hlsl::uint4{samples, 0U, 0U, dithers_ ? before + 1U : 0U});
    cmd.draw(counts_.glows * sh::ITEM_PART_VERTICES, 1, 0, 0);
    recording.counters.draw("open glows", std::uint64_t{counts_.glows} * 2);
}

void Shopfronts::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    if (phase != draw_phase || !state.view) return;
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    const ShopFrame frame = shop_shapes(state, index(), kinds_, style->shopfronts, hours_);
    twin::emit_shops(state, frame, style->shopfronts, out);
}

} // namespace atlas::passes
