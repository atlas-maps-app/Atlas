#include "twin/item/ItemTwin.hpp"

#include "frame/FrameBuilder.hpp"
#include "frame/Ranks.hpp"
#include "scene/Cover.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <format>
#include <optional>
#include <string>

namespace atlas::twin {

namespace {

[[nodiscard]] Point point(hlsl::float2 p) noexcept { return {.x = p.x, .y = p.y}; }

/// SVG's fill where the port's path names none: black (a line paints nothing with it).
constexpr Paint black = Paint::colour(0xFF00'0000U);

/// A record's point `i`.
[[nodiscard]] Point corner(const shaders::ItemRecord& r, std::uint32_t i) noexcept {
    return point(shaders::item_point(r, i));
}

/// draw_items' item kind of a region item (the stream's item kinds).
[[nodiscard]] std::string_view kind_name(std::uint32_t kind) noexcept {
    switch (kind) {
    case shaders::ITEM_TOWER: return "tower";
    case shaders::ITEM_TURBINE: return "turbine";
    case shaders::ITEM_CRANE: return "crane";
    case shaders::ITEM_MAST: return "mast";
    case shaders::ITEM_COASTER: return "coaster";
    case shaders::ITEM_WOOD: return "wood";
    case shaders::ITEM_GANTRY: return "gantry";
    case shaders::ITEM_CONVEYOR: return "conveyor";
    default: return "power";
    }
}

} // namespace

ItemTwin::ItemTwin(const frame::FrameState& state, std::span<const std::uint32_t> tones, Stream& out)
    : out_(out), camera_(state.block.camera) {
    std::copy_n(tones.begin(), std::min(tones.size(), tones_.size()), tones_.begin());
    // the objects group: the port's `<g stroke-linejoin="round">`, with its `mat` filter where the moment has weather
    const bool wx = (state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U;
    objects_ = out.group({.node = {.filter = wx ? out.filter("mat") : none}, .parent = out.root()});
}

Paint ItemTwin::paint(std::uint32_t bgr) noexcept {
    const std::uint32_t rgb = ((bgr & 0xFFU) << 16U) | (bgr & 0xFF00U) | ((bgr >> 16U) & 0xFFU);
    return Paint::colour(0xFF00'0000U | rgb);
}

float ItemTwin::tone(std::uint32_t i) const noexcept { return std::bit_cast<float>(tones_[i]); }

void ItemTwin::face_path(const shaders::ItemRecord& r, std::uint32_t slot, bool stroked, std::int32_t group,
                         std::int32_t item) {
    const std::uint32_t n = shaders::item_count(r);
    std::array<Point, 5> pts{};
    for (std::uint32_t i = 0; i < n; ++i) pts[i] = corner(r, i);
    out_.at(slot, group, item);
    Style s{.fill = paint(r.a.y)};
    if (stroked) {
        s.stroke = paint(r.a.y);
        s.stroke_width = 0.25F;
        s.join = Join::round;
    }
    out_.path(s, std::span<const Point>(pts.data(), n), true);
}

void ItemTwin::item(const scene::CoverTile& tile, const passes::ItemTile& items, std::span<const std::byte> heap,
                    std::uint32_t base, std::uint32_t item, std::uint32_t rank, const passes::ItemChoice& choice,
                    std::span<const std::uint32_t> extra) {
    const std::size_t at = std::size_t{item} * shaders::ITEM_VECTORS * sizeof(hlsl::uint4);
    if (at + (shaders::ITEM_VECTORS * sizeof(hlsl::uint4)) > heap.size() || item >= items.items.size()) return;
    std::array<hlsl::uint4, shaders::ITEM_VECTORS> words{};
    std::memcpy(words.data(), heap.data() + at, sizeof words);
    const shaders::ItemWords w = shaders::item_words(words[0], words[1], words[2]);
    const geometry::RegionItem& it = items.items[item];
    shaders::ItemJob j{};
    j.place = tile.place;
    j.flags = choice.flags;
    j.slot = shaders::rank_slot(rank);
    j.a = choice.a;
    j.b = choice.b;
    j.c = choice.c;
    j.d = choice.d;
    const std::uint32_t kind = j.flags & shaders::ITEM_JOB_KIND;
    const bool way =
        it.way || kind == shaders::ITEM_GANTRY || kind == shaders::ITEM_CONVEYOR || kind == shaders::ITEM_POWER;
    const std::int32_t owner =
        out_.item({.owner = way ? Owner::way : Owner::node, .id = it.id, .kind = kind_name(kind)});
    face_records_.assign(shaders::item_face_count(kind, static_cast<std::uint32_t>(w.extra)), shaders::item_nothing());
    part_records_.assign(shaders::item_part_count(kind), shaders::item_nothing());
    side_records_.assign(shaders::item_side_count(kind), shaders::item_nothing());
    const hlsl::float3 foot = shaders::item_eye(j.place, w.foot);
    const hlsl::float3 end = shaders::item_eye(j.place, hlsl::float3{w.end.x, w.foot.y, w.end.y});
    switch (kind) {
    case shaders::ITEM_TOWER: tower(j, w, it, foot, owner); break;
    case shaders::ITEM_CRANE: crane(j, w, foot, extra, owner); break;
    case shaders::ITEM_MAST: mast(j, w, foot, extra, owner); break;
    case shaders::ITEM_GANTRY:
    case shaders::ITEM_CONVEYOR: boxes(j, w, foot, end, kind, extra, owner); break;
    case shaders::ITEM_TURBINE: turbine(j, w, foot, owner); break;
    case shaders::ITEM_POWER: power(j, w, foot, end, owner); break;
    case shaders::ITEM_COASTER: coaster(j, w, foot, end, heap, base, extra, owner); break;
    case shaders::ITEM_WOOD: wood(j, w, foot, std::span<const hlsl::uint4>(words).subspan(3, 4), owner); break;
    default: break;
    }
    if (faces_ != nullptr) faces_->insert(faces_->end(), face_records_.begin(), face_records_.end());
    if (parts_ != nullptr) parts_->insert(parts_->end(), part_records_.begin(), part_records_.end());
    // the translucent ones in the GPU's order: the jobs' blocks far to near, each in its own order
    if (sides_ != nullptr) sides_->insert(sides_->begin(), side_records_.begin(), side_records_.end());
}

void ItemTwin::tower(const shaders::ItemJob& j, const shaders::ItemWords& w, const geometry::RegionItem& it,
                     hlsl::float3 foot, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const bool cab = (w.flags & shaders::ITEM_CAB) != 0U;
    const bool high = (j.flags & shaders::ITEM_JOB_HIGH) != 0U;
    const float h = w.h;
    const float rs = cab ? 3.2F : std::max(1.2F, h / 30.0F);
    const std::uint32_t n = cab ? 5U : 1U;
    // the bands' y0 as the port computes them, for the gradients' ids: 'tw%d_%d' % (tw['id'] % 100000, int(y0))
    const std::array<double, 5> y0s{0.0, it.h - 14, it.h - 11, it.h - 4.5, it.h - 3.2};
    const std::int64_t id = ((it.id % 100000) + 100000) % 100000;
    for (std::uint32_t k = 0; k < n; ++k) {
        float y0 = 0.0F;
        float y1 = cab ? h - 14.0F : h;
        float r = rs;
        std::uint32_t tone = 0;
        if (k == 1) {
            y0 = h - 14.0F;
            y1 = h - 11.0F;
            r = rs * 2.3F;
        } else if (k == 2) {
            y0 = h - 11.0F;
            y1 = h - 4.5F;
            r = rs * 2.6F;
            tone = 1;
        } else if (k == 3) {
            y0 = h - 4.5F;
            y1 = h - 3.2F;
            r = rs * 2.7F;
            tone = 2;
        } else if (k == 4) {
            y0 = h - 3.2F;
            y1 = h;
            r = 0.5F;
            tone = 3;
        }
        const shaders::ItemPoly f = shaders::item_band_poly(c, foot, y0, y1, r);
        if (f.count == 0) continue;
        const std::uint32_t t = shaders::ITEM_TONE_TOWER + (3 * tone);
        const shaders::ItemRecord rec =
            shaders::item_band(c, f, j.slot + k, k, tones_[t], tones_[t + 1], tones_[t + 2], high);
        face_records_[k] = rec;
        // its gradient: x1 0 x2 1 across its box, its stops at 0, .35 or .65, and 1
        const std::string gid = std::format("tw{}_{}", id, static_cast<std::int64_t>(std::trunc(y0s[k])));
        const std::array<float, 3> offsets{0.0F, high ? 0.65F : 0.35F, 1.0F};
        const std::array<std::uint32_t, 3> colours{paint(tones_[t]).argb, paint(tones_[t + 1]).argb,
                                                   paint(tones_[t + 2]).argb};
        const std::int32_t g = out_.gradient({.id = gid,
                                              .radial = false,
                                              .coords = {0.0F, 0.0F, 1.0F, 0.0F, 0.0F},
                                              .units = Units::bounding_box,
                                              .offsets = offsets,
                                              .colours = colours});
        const std::uint32_t count = shaders::item_count(rec);
        std::array<Point, 5> pts{};
        for (std::uint32_t i = 0; i < count; ++i) pts[i] = corner(rec, i);
        out_.at(j.slot + k, objects_, item);
        out_.path({.fill = Paint::gradient(g)}, std::span<const Point>(pts.data(), count), true);
    }
}

void ItemTwin::boxes(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 a, hlsl::float3 b,
                     std::uint32_t kind, std::span<const std::uint32_t> extra, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const auto gaps = static_cast<std::uint32_t>(w.extra);
    const std::uint32_t n = shaders::item_part_total(kind, w.flags, gaps);
    for (std::uint32_t s = 0; s < n; ++s) {
        const std::uint32_t k = kind == shaders::ITEM_MAST ? s
                                : shaders::ITEM_EXTRA_COLOURS + s < extra.size()
                                    ? extra[shaders::ITEM_EXTRA_COLOURS + s]
                                    : s;
        const shaders::ItemPart p = shaders::item_part(c, w, kind, a, b, k);
        for (std::uint32_t face = 0; face < 5; ++face) {
            const shaders::ItemPoly f = shaders::item_box_face(c, p.box, face);
            if (f.count == 0) continue;
            const std::size_t at = (std::size_t{face} * 2) + (p.second ? 1 : 0);
            const std::uint32_t colour = at < extra.size() ? extra[at] : 0U;
            const std::uint32_t slot = j.slot + (s * 5) + face;
            const shaders::ItemRecord r = shaders::item_face(c, f, slot, face, colour, 0.125F);
            if ((s * 5) + face < face_records_.size()) face_records_[(s * 5) + face] = r;
            face_path(r, slot, true, objects_, item);
        }
    }
}

void ItemTwin::crane(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 foot,
                     std::span<const std::uint32_t> extra, std::int32_t item) {
    boxes(j, w, foot, foot, shaders::ITEM_CRANE, extra, item);
    if ((w.flags & shaders::ITEM_YARD) != 0U) return;
    // its stays: one path of open subpaths, M top L e for e in (tip, tail) where both project
    const shaders::FrameCamera& c = camera_;
    const hlsl::float3 top = shaders::camera_of(c, shaders::item_stay_point(w, foot, 0));
    if (!shaders::tree_ahead(c, top)) return;
    const hlsl::float2 p0 = shaders::tree_printed(c, shaders::css_of(c, top));
    std::array<std::array<Point, 2>, 2> stays{};
    std::vector<Subpath> subpaths;
    for (std::uint32_t e = 1; e < 3; ++e) {
        const hlsl::float3 k = shaders::camera_of(c, shaders::item_stay_point(w, foot, e));
        if (!shaders::tree_ahead(c, k)) continue;
        const hlsl::float2 p1 = shaders::tree_printed(c, shaders::css_of(c, k));
        part_records_[e - 1] = shaders::item_segment(p0, p1, shaders::item_depth(c, top.z, j.slot + 60, 1U),
                                                     shaders::item_depth(c, k.z, j.slot + 60, 1U), j.a * 0.5F, 0U,
                                                     tones_[shaders::ITEM_TONE_STEEL], 1.0F, 0U);
        stays[e - 1] = {point(p0), point(p1)};
        subpaths.push_back({.points = stays[e - 1], .closed = false});
    }
    if (subpaths.empty()) return;
    out_.at(j.slot + 60, objects_, item);
    out_.path({.fill = {}, .stroke = paint(tones_[shaders::ITEM_TONE_STEEL]), .stroke_width = j.a, .join = Join::round},
              subpaths);
}

void ItemTwin::mast(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 foot,
                    std::span<const std::uint32_t> extra, std::int32_t item) {
    boxes(j, w, foot, foot, shaders::ITEM_MAST, extra, item);
    if ((j.flags & shaders::ITEM_JOB_NIGHT) == 0U || (w.flags & shaders::ITEM_LIGHTING) == 0U) return;
    const shaders::FrameCamera& c = camera_;
    const hlsl::float3 k = shaders::camera_of(c, hlsl::float3{foot.x, foot.y + w.h, foot.z});
    if (!shaders::tree_ahead(c, k)) return;
    const hlsl::float2 at = shaders::tree_printed(c, shaders::css_of(c, k));
    side_records_[0] = shaders::item_glow(at, j.a, shaders::item_depth(c, k.z, j.slot + 10, 0U));
    part_records_[0] = shaders::item_circle(at, j.b, tones_[shaders::ITEM_TONE_CORE], 1.0F,
                                            shaders::item_depth(c, k.z, j.slot + 11, 1U));
    // the glow's gradient, once a frame: <radialGradient id="mastglow"> of the three stops
    if (glow_ == none) {
        const std::array<float, 3> offsets{0.0F, 0.35F, 1.0F};
        const auto stop = [&](std::uint32_t colour, std::uint32_t opacity) {
            const auto alpha = static_cast<std::uint32_t>(std::lround(tone(opacity) * 255.0F));
            return (alpha << 24U) | (paint(tones_[colour]).argb & 0xFF'FFFFU);
        };
        const std::array<std::uint32_t, 3> colours{
            stop(shaders::ITEM_TONE_GLOW, shaders::ITEM_TONE_GLOW_OPACITY),
            stop(shaders::ITEM_TONE_GLOW + 1, shaders::ITEM_TONE_GLOW_OPACITY + 1),
            stop(shaders::ITEM_TONE_GLOW + 2, shaders::ITEM_TONE_GLOW_OPACITY + 2)};
        glow_ = out_.gradient({.id = "mastglow",
                               .radial = true,
                               .coords = {0.5F, 0.5F, 0.5F, 0.5F, 0.5F},
                               .units = Units::bounding_box,
                               .offsets = offsets,
                               .colours = colours});
    }
    out_.at(j.slot + 10, objects_, item);
    out_.circle({.fill = Paint::gradient(glow_)}, at.x, at.y, j.a);
    out_.at(j.slot + 11, objects_, item);
    out_.circle({.fill = paint(tones_[shaders::ITEM_TONE_CORE])}, at.x, at.y, j.b);
}

void ItemTwin::turbine(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 foot, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const float h = w.h;
    const float rr = w.extra;
    const float s = j.a;
    const hlsl::float3 kb = shaders::camera_of(c, foot);
    const hlsl::float3 kt = shaders::camera_of(c, hlsl::float3{foot.x, foot.y + h, foot.z});
    if (!shaders::tree_ahead(c, kb) || !shaders::tree_ahead(c, kt)) return;
    const hlsl::float2 pb = shaders::css_of(c, kb);
    const hlsl::float2 pt = shaders::css_of(c, kt);
    shaders::ItemPoly t = shaders::item_poly_none();
    t.count = 4;
    t.p0 = hlsl::float2{pb.x - (2.2F * s), pb.y};
    t.p1 = hlsl::float2{pb.x + (2.2F * s), pb.y};
    t.p2 = hlsl::float2{pt.x + (1.2F * s), pt.y};
    t.p3 = hlsl::float2{pt.x - (1.2F * s), pt.y};
    t.z0 = kb.z;
    t.z1 = kb.z;
    t.z2 = kt.z;
    t.z3 = kt.z;
    face_records_[0] = shaders::item_face(c, t, j.slot, 0U, tones_[shaders::ITEM_TONE_TURBINE], 0.0F);
    face_path(face_records_[0], j.slot, false, objects_, item);
    const float fx = tone(shaders::ITEM_TONE_WIND);
    const float fz = tone(shaders::ITEM_TONE_WIND + 1);
    const hlsl::float3 hub{foot.x + (fx * 2.0F), foot.y + h, foot.z + (fz * 2.0F)};
    const float rx = -fz;
    const float rz = fx;
    const hlsl::float3 kh = shaders::camera_of(c, hub);
    const bool hub_ahead = shaders::tree_ahead(c, kh);
    const float half = tone(shaders::ITEM_TONE_BLADE_WIDTH) * 0.5F;
    std::array<std::array<Point, 3>, 3> blades{};
    std::vector<Subpath> subpaths;
    for (std::uint32_t k = 0; k < 3; ++k) {
        const std::uint32_t at = shaders::ITEM_TONE_BLADES + (4 * k);
        const float ca = tone(at);
        const float sa = tone(at + 1);
        const float cm = tone(at + 2);
        const float sm = tone(at + 3);
        const hlsl::float3 tip{hub.x + (rx * ca * rr), foot.y + (h + (sa * rr)), hub.z + (rz * ca * rr)};
        const hlsl::float3 mid{hub.x + (rx * cm * rr * 0.3F), foot.y + (h + (sm * rr * 0.3F)),
                               hub.z + (rz * cm * rr * 0.3F)};
        const hlsl::float3 kk = shaders::camera_of(c, tip);
        const hlsl::float3 km = shaders::camera_of(c, mid);
        if (!hub_ahead || !shaders::tree_ahead(c, kk) || !shaders::tree_ahead(c, km)) continue;
        shaders::ItemPoly b = shaders::item_poly_none();
        b.count = 3;
        b.p0 = shaders::css_of(c, kh);
        b.p1 = shaders::css_of(c, km);
        b.p2 = shaders::css_of(c, kk);
        b.z0 = kh.z;
        b.z1 = km.z;
        b.z2 = kk.z;
        const shaders::ItemRecord fill =
            shaders::item_face(c, b, j.slot + 1, 0U, tones_[shaders::ITEM_TONE_TURBINE + 1], 0.0F);
        face_records_[1 + k] = fill;
        for (std::uint32_t e = 0; e < 3; ++e) {
            const hlsl::float2 p = shaders::item_poly_point(b, e);
            const hlsl::float2 q = shaders::item_poly_point(b, (e + 1) % 3);
            part_records_[(3 * k) + e] =
                shaders::item_segment(shaders::tree_printed(c, p), shaders::tree_printed(c, q),
                                      shaders::item_depth(c, shaders::item_poly_z(b, e), j.slot + 2, 1U),
                                      shaders::item_depth(c, shaders::item_poly_z(b, (e + 1) % 3), j.slot + 2, 1U),
                                      half, shaders::ITEM_ROUND, tones_[shaders::ITEM_TONE_TURBINE + 2], 1.0F, 0U);
        }
        blades[k] = {corner(fill, 0), corner(fill, 1), corner(fill, 2)};
        subpaths.push_back({.points = blades[k], .closed = true});
    }
    if (!subpaths.empty()) {
        // one path: its fill (slot 1), its stroke over every fill (slot 2)
        out_.at(j.slot + 1, objects_, item);
        out_.path({.fill = paint(tones_[shaders::ITEM_TONE_TURBINE + 1]),
                   .stroke = paint(tones_[shaders::ITEM_TONE_TURBINE + 2]),
                   .stroke_width = tone(shaders::ITEM_TONE_BLADE_WIDTH),
                   .join = Join::round},
                  subpaths);
    }
    if (!hub_ahead) return;
    const hlsl::float2 ph = shaders::css_of(c, kh);
    const hlsl::float2 at = shaders::tree_printed(c, ph);
    part_records_[9] = shaders::item_circle(at, j.c, tones_[shaders::ITEM_TONE_TURBINE + 3], 1.0F,
                                            shaders::item_depth(c, kh.z, j.slot + 3, 2U));
    out_.at(j.slot + 3, objects_, item);
    out_.circle({.fill = paint(tones_[shaders::ITEM_TONE_TURBINE + 3])}, at.x, at.y, j.c);
    if ((j.flags & shaders::ITEM_JOB_NIGHT) != 0U) {
        const hlsl::float2 light = shaders::tree_printed(c, hlsl::float2{ph.x, ph.y - j.b});
        const float opacity = tone(shaders::ITEM_TONE_LIGHT_OPACITY);
        side_records_[0] = shaders::item_circle(light, j.d, tones_[shaders::ITEM_TONE_TURBINE + 4], opacity,
                                                shaders::item_depth(c, kh.z, j.slot + 4, 3U));
        out_.at(j.slot + 4, objects_, item);
        out_.circle({.fill = paint(tones_[shaders::ITEM_TONE_TURBINE + 4])}, light.x, light.y, j.d,
                    {.opacity = opacity});
    }
}

void ItemTwin::coaster(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 foot, hlsl::float3 end,
                       std::span<const std::byte> heap, std::uint32_t base, std::span<const std::uint32_t> extra,
                       std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const std::uint32_t kind =
        (w.flags & shaders::ITEM_WOODEN) != 0U ? 1U : ((w.flags & shaders::ITEM_MONO) != 0U ? 2U : 0U);
    const std::uint32_t lit = tones_[shaders::ITEM_TONE_COASTER + (2 * kind)];
    const std::uint32_t dark = tones_[shaders::ITEM_TONE_COASTER + (2 * kind) + 1];
    const float s = j.a;
    const hlsl::float3 ka = shaders::camera_of(c, hlsl::float3{foot.x, foot.y + w.h, foot.z});
    const hlsl::float3 kb = shaders::camera_of(c, hlsl::float3{end.x, end.y + w.extra, end.z});
    if (!shaders::tree_ahead(c, ka) || !shaders::tree_ahead(c, kb)) return;
    const hlsl::float2 qa = shaders::css_of(c, ka);
    const hlsl::float2 qb = shaders::css_of(c, kb);
    const hlsl::float2 pb = shaders::tree_printed(c, qb);
    if ((j.flags & shaders::ITEM_JOB_POST) != 0U) {
        const hlsl::float3 kg = shaders::camera_of(c, end);
        if (shaders::tree_ahead(c, kg)) {
            const hlsl::float2 g0 = shaders::tree_printed(c, shaders::css_of(c, kg));
            part_records_[0] =
                shaders::item_segment(g0, pb, shaders::item_depth(c, kg.z, j.slot, 0U),
                                      shaders::item_depth(c, kb.z, j.slot, 0U), j.c * 0.5F, 0U, dark, 1.0F, 0U);
            const std::array<Point, 2> post{point(g0), point(pb)};
            out_.at(j.slot, objects_, item);
            out_.path({.fill = black, .stroke = paint(dark), .stroke_width = j.c, .join = Join::round}, post, false);
            const std::size_t at = extra.empty() ? 0 : (std::size_t{extra[0]} - base) * sizeof(hlsl::uint4);
            if ((j.flags & shaders::ITEM_JOB_BRACES) != 0U && !extra.empty() && extra[0] >= base &&
                at + (3 * sizeof(hlsl::uint4)) <= heap.size()) {
                // the post before: its foot q0 and its top q1; M q0 L pb M q1 L g0, at .85
                std::array<hlsl::uint4, 3> pw{};
                std::memcpy(pw.data(), heap.data() + at, sizeof pw);
                const shaders::ItemWords prev = shaders::item_words(pw[0], pw[1], pw[2]);
                const hlsl::float3 pe = shaders::item_eye(j.place, hlsl::float3{prev.end.x, prev.foot.y, prev.end.y});
                const hlsl::float3 k0 = shaders::camera_of(c, pe);
                const hlsl::float3 k1 = shaders::camera_of(c, hlsl::float3{pe.x, pe.y + prev.extra, pe.z});
                if (shaders::tree_ahead(c, k0) && shaders::tree_ahead(c, k1)) {
                    const float half = j.d * 0.5F;
                    const hlsl::float2 q0 = shaders::tree_printed(c, shaders::css_of(c, k0));
                    const hlsl::float2 q1 = shaders::tree_printed(c, shaders::css_of(c, k1));
                    side_records_[0] =
                        shaders::item_segment(q0, pb, shaders::item_depth(c, k0.z, j.slot + 1, 0U),
                                              shaders::item_depth(c, kb.z, j.slot + 1, 0U), half, 0U, dark, 0.85F, 0U);
                    side_records_[1] =
                        shaders::item_segment(q1, g0, shaders::item_depth(c, k1.z, j.slot + 1, 0U),
                                              shaders::item_depth(c, kg.z, j.slot + 1, 0U), half, 0U, dark, 0.85F, 1U);
                    const std::array<Point, 2> first{point(q0), point(pb)};
                    const std::array<Point, 2> second{point(q1), point(g0)};
                    const std::array<Subpath, 2> braces{Subpath{.points = first, .closed = false},
                                                        Subpath{.points = second, .closed = false}};
                    out_.at(j.slot + 1, objects_, item);
                    out_.path({.fill = black, .stroke = paint(dark), .stroke_width = j.d, .join = Join::round}, braces,
                              {.opacity = 0.85F});
                }
            }
        }
    }
    // the track: a dark underside 0.35 s px down, the lit top over it
    const hlsl::float2 ua = shaders::tree_printed(c, hlsl::float2{qa.x, qa.y + (0.35F * s)});
    const hlsl::float2 ub = shaders::tree_printed(c, hlsl::float2{qb.x, qb.y + (0.35F * s)});
    part_records_[1] = shaders::item_segment(ua, ub, shaders::item_depth(c, ka.z, j.slot + 2, 0U),
                                             shaders::item_depth(c, kb.z, j.slot + 2, 0U), j.b * 0.5F,
                                             shaders::ITEM_ROUND, dark, 1.0F, 0U);
    const std::array<Point, 2> under{point(ua), point(ub)};
    out_.at(j.slot + 2, objects_, item);
    out_.path({.fill = black, .stroke = paint(dark), .stroke_width = j.b, .cap = Cap::round, .join = Join::round},
              under, false);
    const float top = extra.size() > 1 ? std::bit_cast<float>(extra[1]) : 0.0F;
    const hlsl::float2 pa = shaders::tree_printed(c, qa);
    part_records_[2] = shaders::item_segment(pa, pb, shaders::item_depth(c, ka.z, j.slot + 3, 1U),
                                             shaders::item_depth(c, kb.z, j.slot + 3, 1U), top * 0.5F,
                                             shaders::ITEM_ROUND, lit, 1.0F, 0U);
    const std::array<Point, 2> line{point(pa), point(pb)};
    out_.at(j.slot + 3, objects_, item);
    out_.path({.fill = black, .stroke = paint(lit), .stroke_width = top, .cap = Cap::round, .join = Join::round}, line,
              false);
}

void ItemTwin::wood(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 foot,
                    std::span<const hlsl::uint4> draws, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const float r = w.end.x / 2.0F;
    const float h = w.h;
    const float s = j.a;
    const std::uint32_t greens = shaders::ITEM_TONE_WOODS + (3 * static_cast<std::uint32_t>(w.extra));
    const float kz = shaders::camera_of(c, hlsl::float3{foot.x, foot.y + (h * 0.7F), foot.z}).z;
    const bool sun = (j.flags & shaders::ITEM_JOB_SUN) != 0U;
    const hlsl::float3 l{tone(shaders::ITEM_TONE_SUN), tone(shaders::ITEM_TONE_SUN + 1),
                         tone(shaders::ITEM_TONE_SUN + 2)};
    const auto draw = [&](std::uint32_t i) { return shaders::item_draw(draws[0], draws[1], draws[2], draws[3], i); };
    struct Circle {
        hlsl::float2 p;
        float r;
    };
    std::array<std::optional<Circle>, 9> circles{};
    for (std::uint32_t k = 0; k < 4; ++k) {
        const hlsl::float3 clump{foot.x + draw(4 * k), foot.y + ((h * 0.7F) + draw((4 * k) + 1)),
                                 foot.z + draw((4 * k) + 2)};
        const hlsl::float3 kp = shaders::camera_of(c, clump);
        if (!shaders::tree_ahead(c, kp)) continue;
        const float rr = draw((4 * k) + 3) * s;
        const hlsl::float2 pp = shaders::css_of(c, kp);
        const Circle under{.p = shaders::tree_printed(c, pp), .r = shaders::tree_print(c, rr)};
        circles.at(k) = under;
        part_records_[k] =
            shaders::item_circle(under.p, under.r, tones_[greens], 1.0F, shaders::item_depth(c, kz, j.slot + k, 0U));
        if (!sun) continue;
        const hlsl::float3 kq = shaders::camera_of(
            c, hlsl::float3{clump.x + (l.x * r * 0.3F), clump.y + (l.y * r * 0.3F), clump.z + (l.z * r * 0.3F)});
        if (!shaders::tree_ahead(c, kq)) continue;
        const hlsl::float2 qq = shaders::css_of(c, kq);
        const Circle lit{.p = shaders::tree_printed(c, qq), .r = shaders::tree_print(c, rr * 0.74F)};
        circles.at(4 + k) = lit;
        part_records_[4 + k] = shaders::item_circle(lit.p, lit.r, tones_[greens + 1], 1.0F,
                                                    shaders::item_depth(c, kz, j.slot + 4 + k, 1U));
        if (k == 0) {
            const hlsl::float2 gl{qq.x + ((qq.x - pp.x) * 0.5F), qq.y + ((qq.y - pp.y) * 0.5F)};
            const Circle glint{.p = shaders::tree_printed(c, gl), .r = shaders::tree_print(c, rr * 0.36F)};
            circles.at(8) = glint;
            part_records_[8] = shaders::item_circle(glint.p, glint.r, tones_[greens + 2], 1.0F,
                                                    shaders::item_depth(c, kz, j.slot + 8, 2U));
        }
    }
    // the port's order: every clump, then every top, then the glint, each layer a group of its own (<g fill="...">)
    std::array<std::int32_t, 3> layers{none, none, none};
    for (std::uint32_t k = 0; k < circles.size(); ++k) {
        const std::optional<Circle>& cc = circles.at(k);
        if (!cc) continue;
        const std::uint32_t layer = k < 4 ? 0U : (k < 8 ? 1U : 2U);
        if (layers.at(layer) == none) layers.at(layer) = out_.group({.node = {}, .parent = objects_});
        out_.at(j.slot + k, layers.at(layer), item);
        out_.circle({.fill = paint(tones_[greens + layer])}, cc->p.x, cc->p.y, cc->r);
    }
}

void ItemTwin::power(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 a, hlsl::float3 b,
                     std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const float h = w.h;
    const bool fading = (j.flags & shaders::ITEM_JOB_FADE) != 0U;
    const float fade = fading ? j.b : 1.0F;
    // the group at its fade, where it fades: `<g opacity="%.2f">`
    const std::int32_t group = fading ? out_.group({.node = {.opacity = fade}, .parent = objects_}) : objects_;
    const hlsl::float3 k0 = shaders::camera_of(c, a);
    const hlsl::float3 k1 = shaders::camera_of(c, hlsl::float3{a.x, a.y + (h + 2.0F), a.z});
    if (shaders::tree_ahead(c, k0) && shaders::tree_ahead(c, k1)) {
        const hlsl::float2 p0 = shaders::tree_printed(c, shaders::css_of(c, k0));
        const hlsl::float2 p1 = shaders::tree_printed(c, shaders::css_of(c, k1));
        const shaders::ItemRecord t = shaders::item_segment(p0, p1, shaders::item_depth(c, k0.z, j.slot, 0U),
                                                            shaders::item_depth(c, k1.z, j.slot, 0U), j.a * 0.5F, 0U,
                                                            tones_[shaders::ITEM_TONE_POWER], fade, 0U);
        if (fading) {
            side_records_[0] = t;
        } else {
            part_records_[0] = t;
        }
        const std::array<Point, 2> line{point(p0), point(p1)};
        out_.at(j.slot, group, item);
        out_.path({.fill = black,
                   .stroke = paint(tones_[shaders::ITEM_TONE_POWER]),
                   .stroke_width = j.a,
                   .join = Join::round},
                  line, false);
    }
    const float half = tone(shaders::ITEM_TONE_CONDUCTOR_WIDTH) * 0.5F;
    const float opacity = tone(shaders::ITEM_TONE_CONDUCTOR_OPACITY);
    std::array<std::array<Point, 5>, 3> conductors{};
    std::vector<Subpath> subpaths;
    std::uint32_t written = 0;
    for (std::uint32_t o = 0; o < 3; ++o) {
        const float off = o == 0 ? -6.0F : (o == 1 ? 0.0F : 6.0F);
        std::array<hlsl::float3, 5> q{};
        bool all = true;
        for (std::uint32_t f = 0; f < 5 && all; ++f) {
            q[f] = shaders::camera_of(c, shaders::item_conductor(a, b, h, w.sag, off, f));
            all = shaders::tree_ahead(c, q[f]);
        }
        if (!all) continue;
        std::array<hlsl::float2, 5> p{};
        for (std::uint32_t f = 0; f < 5; ++f) p[f] = shaders::tree_printed(c, shaders::css_of(c, q[f]));
        for (std::uint32_t f = 0; f < 4; ++f) {
            const std::uint32_t caps =
                (f > 0 ? shaders::ITEM_ROUND_START : 0U) | (f < 3 ? shaders::ITEM_ROUND_END : 0U);
            side_records_[1 + written] =
                shaders::item_segment(p[f], p[f + 1], shaders::item_depth(c, q[f].z, j.slot + 1, 0U),
                                      shaders::item_depth(c, q[f + 1].z, j.slot + 1, 0U), half, caps,
                                      tones_[shaders::ITEM_TONE_POWER + 1], opacity * fade, written);
            ++written;
        }
        for (std::uint32_t f = 0; f < 5; ++f) conductors[o][f] = point(p[f]);
        subpaths.push_back({.points = conductors[o], .closed = false});
    }
    if (subpaths.empty()) return;
    out_.at(j.slot + 1, group, item);
    out_.path({.fill = {},
               .stroke = paint(tones_[shaders::ITEM_TONE_POWER + 1]),
               .stroke_width = tone(shaders::ITEM_TONE_CONDUCTOR_WIDTH),
               .join = Join::round},
              subpaths, {.opacity = opacity});
}

} // namespace atlas::twin
