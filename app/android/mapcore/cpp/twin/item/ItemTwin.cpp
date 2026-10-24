#include "twin/item/ItemTwin.hpp"

#include "frame/FrameBuilder.hpp"
#include "frame/Ranks.hpp"
#include "scene/Cover.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <format>
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
    case shaders::ITEM_MAST: return "mast";
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
                    std::uint32_t item, std::uint32_t rank, const passes::ItemChoice& choice,
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
    const bool line = kind == shaders::ITEM_GANTRY || kind == shaders::ITEM_CONVEYOR || kind == shaders::ITEM_POWER;
    const std::int32_t owner =
        out_.item({.owner = line ? Owner::way : Owner::node, .id = it.id, .kind = kind_name(kind)});
    face_records_.assign(shaders::item_face_count(kind, static_cast<std::uint32_t>(w.extra)), shaders::item_nothing());
    part_records_.assign(shaders::item_part_count(kind), shaders::item_nothing());
    side_records_.assign(shaders::item_side_count(kind), shaders::item_nothing());
    const hlsl::float3 foot = shaders::item_eye(j.place, w.foot);
    const hlsl::float3 end = shaders::item_eye(j.place, hlsl::float3{w.end.x, w.foot.y, w.end.y});
    switch (kind) {
    case shaders::ITEM_TOWER: tower(j, w, it, foot, owner); break;
    case shaders::ITEM_MAST: mast(j, w, foot, extra, owner); break;
    case shaders::ITEM_GANTRY:
    case shaders::ITEM_CONVEYOR: boxes(j, w, foot, end, kind, extra, owner); break;
    case shaders::ITEM_TURBINE: turbine(j, w, foot, owner); break;
    case shaders::ITEM_POWER: power(j, w, foot, end, owner); break;
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
        const shaders::ItemPart p = shaders::item_part(c, kind, w.flags, gaps, a, b, w.h, k);
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
