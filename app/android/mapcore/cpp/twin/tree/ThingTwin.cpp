#include "twin/tree/ThingTwin.hpp"

#include "frame/FrameBuilder.hpp"
#include "frame/Ranks.hpp"
#include "scene/Cover.hpp"

#include <algorithm>
#include <bit>
#include <cstring>

namespace atlas::twin {

namespace {

[[nodiscard]] Point point(hlsl::float2 p) noexcept { return {.x = p.x, .y = p.y}; }

/// SVG's fill where the port's path names none: black (a line paints nothing with it).
constexpr Paint black = Paint::colour(0xFF00'0000U);

} // namespace

ThingTwin::ThingTwin(const frame::FrameState& state, const tiles::Style& style, Stream& out)
    : out_(out), camera_(state.block.camera),
      tones_(passes::thing_tones((state.block.theme.flags.x & shaders::THEME_NIGHT) != 0U, style.things)),
      night_((state.block.theme.flags.x & shaders::THEME_NIGHT) != 0U),
      weather_((state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U),
      wet_((state.block.theme.flags.x & shaders::THEME_WET) != 0U),
      focal_(state.view ? state.view->camera().focal() : 0.0) {
    // the objects group: the port's `<g stroke-linejoin="round">`, with its `mat` filter where the moment has weather
    const bool wx = (state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U;
    objects_ = out.group({.node = {.filter = wx ? out.filter("mat") : none}, .parent = out.root()});
}

Paint ThingTwin::paint(std::uint32_t bgr) noexcept {
    const std::uint32_t rgb = ((bgr & 0xFFU) << 16U) | (bgr & 0xFF00U) | ((bgr >> 16U) & 0xFFU);
    return Paint::colour(0xFF00'0000U | rgb);
}

void ThingTwin::put(std::uint32_t k, const shaders::TreeRecord& r) {
    if (k < thing_records_.size()) thing_records_[k] = r;
}

void ThingTwin::thing(const scene::CoverTile& tile, const passes::ThingTile& things, std::span<const std::byte> heap,
                      std::uint32_t thing, std::uint32_t rank, double depth) {
    const std::size_t at = std::size_t{thing} * shaders::THING_VECTORS * sizeof(hlsl::uint4);
    if (at + (shaders::THING_VECTORS * sizeof(hlsl::uint4)) > heap.size()) return;
    std::array<hlsl::uint4, shaders::THING_VECTORS> words{};
    std::memcpy(words.data(), heap.data() + at, sizeof words);
    const passes::ThingChoice choice =
        passes::thing_choice(things.kinds[thing], things.posts[thing], depth, focal_, night_, weather_, wet_);
    if (!choice.drawn) return;
    shaders::ThingJob j{};
    j.place = tile.place;
    j.flags = choice.flags;
    j.slot = shaders::rank_slot(rank);
    j.a = choice.a;
    j.b = choice.b;
    j.c = choice.c;
    j.d = choice.d;
    j.rx = choice.rx;
    j.ry = choice.ry;
    j.drop = choice.drop;
    const bool railing_piece = things.kinds[thing] == geometry::ThingKind::railing;
    const std::int32_t item = out_.item({.owner = railing_piece ? Owner::way : Owner::node,
                                         .id = things.ids[thing],
                                         .kind = std::string_view(railing_piece ? "railing" : "thing")});
    thing_records_.assign(shaders::thing_record_count(j.flags), shaders::tree_nothing());
    side_records_.assign(shaders::thing_side_count(j.flags), shaders::tree_nothing());
    const shaders::FrameCamera& c = camera_;
    const hlsl::float3 foot = shaders::thing_foot(words[0]);
    const std::uint32_t kind = j.flags & shaders::THING_JOB_KIND;
    if (kind == shaders::THING_RAILING) {
        // its plane at its middle, 0.5 m over its foot (the gather's point)
        const hlsl::float3 end = shaders::thing_end(words[1], foot);
        const hlsl::float3 mid{(foot.x + end.x) * 0.5F, foot.y + 0.5F, (foot.z + end.z) * 0.5F};
        railing(j, foot, end, std::max(shaders::tree_camera(c, j.place, mid).z, c.frame.w), item);
        if (records_ != nullptr) records_->insert(records_->end(), thing_records_.begin(), thing_records_.end());
        return;
    }
    const float kz = std::max(
        shaders::tree_camera(c, j.place, shaders::thing_point(foot, 0.0F, shaders::thing_middle(kind), 0.0F)).z,
        c.frame.w);
    if (kind == shaders::THING_LAMP) {
        lamp(j, foot, kz, item);
    } else if (kind == shaders::THING_BENCH) {
        box(j, foot, 0.0F, 1.8F, 0.5F, 0.45F, tones_[shaders::THING_TONE_BENCH], tones_[shaders::THING_TONE_BENCH + 1],
            0, kz, 0, item);
        box(j, foot, 0.22F, 1.8F, 0.08F, 0.9F, tones_[shaders::THING_TONE_BACK], tones_[shaders::THING_TONE_BACK + 1],
            2, kz, 3, item);
    } else if (kind == shaders::THING_BASKET) {
        box(j, foot, 0.0F, 0.42F, 0.42F, 0.95F, tones_[shaders::THING_TONE_BASKET],
            tones_[shaders::THING_TONE_BASKET + 1], 0, kz, 0, item);
    } else if (kind == shaders::THING_BOLLARD) {
        bollard(j, foot, kz, item);
    } else {
        rack(j, foot, kz, item);
    }
    if (records_ != nullptr) records_->insert(records_->end(), thing_records_.begin(), thing_records_.end());
    // nearest first: a job's own the other way round too
    if (sides_ != nullptr) sides_->insert(sides_->end(), side_records_.rbegin(), side_records_.rend());
}

void ThingTwin::lamp(const shaders::ThingJob& j, hlsl::float3 foot, float kz, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const hlsl::float3 k0 = shaders::tree_camera(c, j.place, foot);
    const hlsl::float3 k1 = shaders::tree_camera(c, j.place, shaders::thing_point(foot, 0.0F, 4.2F, 0.0F));
    if (!shaders::tree_ahead(c, k0) || !shaders::tree_ahead(c, k1)) return;
    const hlsl::float2 p0 = shaders::css_of(c, k0);
    const hlsl::float2 p1 = shaders::css_of(c, k1);
    const float hw = j.b;
    const float hh = j.c;
    const auto argb = [](std::uint32_t rgba) {
        return (rgba & 0xFF00'FF00U) | ((rgba & 0xFFU) << 16U) | ((rgba >> 16U) & 0xFFU);
    };
    const std::uint32_t streak = (j.flags & shaders::THING_JOB_STREAK) != 0U ? 1U : 0U;
    // on a wet night, first: '<ellipse cx="%.1f" cy="%.1f" rx="%.2f" ry="%.1f" fill="url(#streak)" opacity=".75"/>' at
    // p0 + 2.6 s down
    if (streak != 0U) {
        const hlsl::float2 at = shaders::tree_printed(c, hlsl::float2{p0.x, p0.y + j.drop});
        side_records_[0] = shaders::tree_streak(at, j.rx, j.ry, 0.75F, shaders::tree_depth(c, kz, j.slot, 0U));
        const std::array<std::uint32_t, 3> colours{argb(shaders::TREE_STREAK_STOP0), argb(shaders::TREE_STREAK_STOP1),
                                                   argb(shaders::TREE_STREAK_STOP2)};
        const std::array<float, 3> offsets{0.0F, 0.5F, 1.0F};
        const std::int32_t gradient = out_.gradient({.id = "streak",
                                                     .radial = true,
                                                     .coords = {0.5F, 0.5F, 0.5F, 0.5F, 0.5F},
                                                     .units = Units::bounding_box,
                                                     .offsets = offsets,
                                                     .colours = colours});
        out_.at(j.slot, objects_, item);
        out_.ellipse({.fill = Paint::gradient(gradient)}, at.x, at.y, j.rx, j.ry, {.opacity = 0.75F});
    }
    // the mast
    const hlsl::float2 m0 = shaders::tree_printed(c, p0);
    const hlsl::float2 m1 = shaders::tree_printed(c, p1);
    const std::uint32_t mast = tones_[shaders::THING_TONE_MAST];
    put(2, shaders::tree_segment(m0, m1, 0.5F * j.a, shaders::TREE_ROUND, mast, 1.0F,
                                 shaders::tree_depth(c, kz, j.slot, 1U)));
    const std::array<Point, 2> line{point(m0), point(m1)};
    out_.at(j.slot + 1, objects_, item);
    out_.path({.fill = black, .stroke = paint(mast), .stroke_width = j.a, .cap = Cap::round, .join = Join::round}, line,
              false);
    // the lantern
    const std::array<hlsl::float2, 4> l{shaders::tree_printed(c, hlsl::float2{p1.x - hw, p1.y}),
                                        shaders::tree_printed(c, hlsl::float2{p1.x + hw, p1.y}),
                                        shaders::tree_printed(c, hlsl::float2{p1.x + hw * 0.7F, p1.y + hh}),
                                        shaders::tree_printed(c, hlsl::float2{p1.x - hw * 0.7F, p1.y + hh})};
    const std::uint32_t lantern = tones_[shaders::THING_TONE_LANTERN];
    put(1, shaders::tree_quad(l[0], l[1], l[2], l[3], lantern, 1.0F, shaders::tree_depth(c, kz, j.slot, 2U)));
    const std::array<Point, 4> lp{point(l[0]), point(l[1]), point(l[2]), point(l[3])};
    out_.at(j.slot + 2, objects_, item);
    out_.path({.fill = paint(lantern)}, lp, true);
    // the cap
    const std::array<hlsl::float2, 3> k{shaders::tree_printed(c, hlsl::float2{p1.x - hw * 1.2F, p1.y}),
                                        shaders::tree_printed(c, hlsl::float2{p1.x + hw * 1.2F, p1.y}),
                                        shaders::tree_printed(c, hlsl::float2{p1.x, p1.y - hh * 0.6F})};
    const std::uint32_t cap = tones_[shaders::THING_TONE_CAP];
    put(0, shaders::tree_quad(k[0], k[1], k[2], k[2], cap, 1.0F, shaders::tree_depth(c, kz, j.slot, 3U)));
    const std::array<Point, 3> kp{point(k[0]), point(k[1]), point(k[2])};
    out_.at(j.slot + 3, objects_, item);
    out_.path({.fill = paint(cap)}, kp, true);
    // by day the glint: 'M%.1f %.1fv%.1f', its second point the first as printed and its printed length down
    if ((j.flags & shaders::THING_JOB_GLINT) != 0U) {
        const hlsl::float2 g0 = shaders::tree_printed(c, hlsl::float2{p1.x - hw * 0.4F, p1.y + hh * 0.2F});
        const hlsl::float2 g1{g0.x, g0.y + shaders::tree_print(c, hh * 0.6F)};
        const std::uint32_t glint = tones_[shaders::THING_TONE_GLINT];
        const auto opacity = std::bit_cast<float>(tones_[shaders::THING_TONE_GLINT_OPACITY]);
        side_records_[0] =
            shaders::tree_segment(g0, g1, 0.5F * j.d, 0U, glint, opacity, shaders::tree_depth(c, kz, j.slot, 4U));
        const std::array<Point, 2> glint_line{point(g0), point(g1)};
        out_.at(j.slot + 4, objects_, item);
        out_.path({.fill = black, .stroke = paint(glint), .stroke_width = j.d, .cap = Cap::butt, .join = Join::round},
                  glint_line, false, {.opacity = opacity});
    }
    // at night its halo: '<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#halo)"/>' about p1 + 0.5 hh
    if ((j.flags & shaders::THING_JOB_HALO) != 0U) {
        const hlsl::float2 h = shaders::tree_printed(c, hlsl::float2{p1.x, p1.y + hh * 0.5F});
        side_records_[streak] = shaders::tree_halo(h, j.d, shaders::tree_depth(c, kz, j.slot, 4U));
        const std::array<std::uint32_t, 3> colours{argb(shaders::TREE_HALO_STOP0), argb(shaders::TREE_HALO_STOP1),
                                                   argb(shaders::TREE_HALO_STOP2)};
        const std::array<float, 3> offsets{0.0F, 0.35F, 1.0F};
        const std::int32_t halo = out_.gradient({.id = "halo",
                                                 .radial = true,
                                                 .coords = {0.5F, 0.5F, 0.5F, 0.5F, 0.5F},
                                                 .units = Units::bounding_box,
                                                 .offsets = offsets,
                                                 .colours = colours});
        out_.at(j.slot + 4, objects_, item);
        out_.circle({.fill = Paint::gradient(halo)}, h.x, h.y, j.d);
    }
}

void ThingTwin::box(const shaders::ThingJob& j, hlsl::float3 foot, float dz, float w, float dd, float h,
                    std::uint32_t top, std::uint32_t leg, std::uint32_t k, float kz, std::uint32_t at,
                    std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const hlsl::float3 k0 = shaders::tree_camera(c, j.place, shaders::thing_point(foot, 0.0F, 0.0F, dz));
    const hlsl::float3 k1 = shaders::tree_camera(c, j.place, shaders::thing_point(foot, 0.0F, h, dz));
    if (shaders::tree_ahead(c, k0) && shaders::tree_ahead(c, k1)) {
        const hlsl::float2 a = shaders::tree_printed(c, shaders::css_of(c, k0));
        const hlsl::float2 b = shaders::tree_printed(c, shaders::css_of(c, k1));
        put(at, shaders::tree_segment(a, b, 0.5F, 0U, leg, 1.0F, shaders::tree_depth(c, kz, j.slot, k)));
        const std::array<Point, 2> line{point(a), point(b)};
        out_.at(j.slot + k, objects_, item);
        out_.path({.fill = black, .stroke = paint(leg), .stroke_width = 1.0F, .cap = Cap::butt, .join = Join::round},
                  line, false);
    }
    const float hw = w * 0.5F;
    const float hd = dd * 0.5F;
    const shaders::ThingFace f = shaders::thing_face(
        c, j.place, shaders::thing_point(foot, -hw, h, dz - hd), shaders::thing_point(foot, hw, h, dz - hd),
        shaders::thing_point(foot, hw, h, dz + hd), shaders::thing_point(foot, -hw, h, dz + hd));
    if (f.count < 3) return;
    const float depth = shaders::tree_depth(c, kz, j.slot, k + 1);
    std::array<hlsl::float2, 5> q{};
    std::array<Point, 5> pts{};
    for (std::uint32_t i = 0; i < f.count; ++i) {
        q[i] = shaders::tree_printed(c, shaders::thing_face_corner(f, i));
        pts[i] = point(q[i]);
    }
    put(at + 1, shaders::tree_quad(q[0], q[1], q[2], f.count > 3 ? q[3] : q[2], top, 1.0F, depth));
    if (f.count > 4) put(at + 2, shaders::tree_quad(q[0], q[3], q[4], q[4], top, 1.0F, depth));
    out_.at(j.slot + k + 1, objects_, item);
    out_.path({.fill = paint(top)}, std::span<const Point>(pts.data(), f.count), true);
}

void ThingTwin::bollard(const shaders::ThingJob& j, hlsl::float3 foot, float kz, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const hlsl::float3 k0 = shaders::tree_camera(c, j.place, foot);
    const hlsl::float3 k1 = shaders::tree_camera(c, j.place, shaders::thing_point(foot, 0.0F, 0.85F, 0.0F));
    if (!shaders::tree_ahead(c, k0) || !shaders::tree_ahead(c, k1)) return;
    const hlsl::float2 p1 = shaders::css_of(c, k1);
    const hlsl::float2 a = shaders::tree_printed(c, shaders::css_of(c, k0));
    const hlsl::float2 b = shaders::tree_printed(c, p1);
    const std::uint32_t post = tones_[shaders::THING_TONE_BOLLARD];
    put(0, shaders::tree_segment(a, b, 0.5F * j.a, shaders::TREE_ROUND, post, 1.0F,
                                 shaders::tree_depth(c, kz, j.slot, 0U)));
    const std::array<Point, 2> line{point(a), point(b)};
    out_.at(j.slot, objects_, item);
    out_.path({.fill = black, .stroke = paint(post), .stroke_width = j.a, .cap = Cap::round, .join = Join::round}, line,
              false);
    if ((j.flags & shaders::THING_JOB_HEAD) != 0U) {
        const hlsl::float2 h{shaders::tree_print(c, p1.x - j.b), shaders::tree_print(c, p1.y)};
        const std::uint32_t head = tones_[shaders::THING_TONE_HEAD];
        const auto opacity = std::bit_cast<float>(tones_[shaders::THING_TONE_HEAD_OPACITY]);
        side_records_[0] = shaders::tree_circle(h, j.c, head, opacity, shaders::tree_depth(c, kz, j.slot, 1U));
        out_.at(j.slot + 1, objects_, item);
        out_.circle({.fill = paint(head)}, h.x, h.y, j.c, {.opacity = opacity});
    }
}

void ThingTwin::rack(const shaders::ThingJob& j, hlsl::float3 foot, float kz, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const auto width = std::bit_cast<float>(tones_[shaders::THING_TONE_RACK_WIDTH]);
    const std::uint32_t colour = tones_[shaders::THING_TONE_RACK];
    const float depth = shaders::tree_depth(c, kz, j.slot, 0U);
    std::array<std::array<Point, 9>, 3> arcs{};
    std::vector<Subpath> subpaths;
    for (std::uint32_t k = 0; k < 3; ++k) {
        const float dx = static_cast<float>(k) - 1.0F; // xx = n['x'] - 1.0 + k
        std::array<hlsl::float2, 9> p{};
        bool all = true;
        for (std::uint32_t i = 0; i < 9 && all; ++i) {
            const hlsl::float2 a = shaders::thing_arc(i);
            const hlsl::float3 kk = shaders::tree_camera(c, j.place, shaders::thing_point(foot, dx + a.x, a.y, 0.0F));
            all = shaders::tree_ahead(c, kk);
            if (all) p[i] = shaders::tree_printed(c, shaders::css_of(c, kk));
        }
        if (!all) continue;
        for (std::uint32_t i = 0; i < 9; ++i) arcs[k][i] = point(p[i]);
        for (std::uint32_t i = 1; i < 9; ++i) {
            put((8 * k) + (i - 1), shaders::tree_segment(p[i - 1], p[i], 0.5F * width, shaders::thing_arc_caps(i - 1),
                                                         colour, 1.0F, depth));
        }
        subpaths.push_back({.points = arcs[k], .closed = false});
    }
    if (subpaths.empty()) return;
    out_.at(j.slot, objects_, item);
    out_.path({.fill = {}, .stroke = paint(colour), .stroke_width = width, .cap = Cap::butt, .join = Join::round},
              subpaths);
}

void ThingTwin::railing(const shaders::ThingJob& j, hlsl::float3 a, hlsl::float3 b, float kz, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const float half = 0.5F * j.a;
    const std::uint32_t colour = tones_[shaders::THING_TONE_RAILING];
    const float depth = shaders::tree_depth(c, kz, j.slot, 0U);
    const std::uint32_t m = (j.flags >> shaders::THING_JOB_POSTS_SHIFT) & 0xFFU;
    std::vector<std::array<Point, 2>> segs;
    const auto segment = [&](std::uint32_t at, hlsl::float3 p, hlsl::float3 q) {
        const hlsl::float3 k0 = shaders::tree_camera(c, j.place, p);
        const hlsl::float3 k1 = shaders::tree_camera(c, j.place, q);
        if (!shaders::tree_ahead(c, k0) || !shaders::tree_ahead(c, k1)) return;
        const hlsl::float2 p0 = shaders::tree_printed(c, shaders::css_of(c, k0));
        const hlsl::float2 p1 = shaders::tree_printed(c, shaders::css_of(c, k1));
        put(at, shaders::tree_segment(p0, p1, half, shaders::TREE_ROUND, colour, 1.0F, depth));
        segs.push_back({point(p0), point(p1)});
    };
    // for yy in (y0 + 1.0, y0 + 0.12): the rail from pa to pb
    for (std::uint32_t r = 0; r < 2; ++r) {
        const float y = r == 0 ? 1.0F : 0.12F;
        segment(r, shaders::thing_point(a, 0.0F, y, 0.0F), shaders::thing_point(b, 0.0F, y, 0.0F));
    }
    // the posts: for j in range(m + 1), f = j / m, from y0 + 0.12 to y0 + 1.0
    for (std::uint32_t i = 0; i <= m; ++i) {
        const float f = static_cast<float>(i) / static_cast<float>(m);
        const hlsl::float3 p{a.x + (b.x - a.x) * f, a.y, a.z + (b.z - a.z) * f};
        segment(2 + i, shaders::thing_point(p, 0.0F, 0.12F, 0.0F), shaders::thing_point(p, 0.0F, 1.0F, 0.0F));
    }
    if (segs.empty()) return;
    std::vector<Subpath> subpaths;
    subpaths.reserve(segs.size());
    for (const std::array<Point, 2>& s : segs) subpaths.push_back({.points = s, .closed = false});
    out_.at(j.slot, objects_, item);
    out_.path({.fill = {}, .stroke = paint(colour), .stroke_width = j.a, .cap = Cap::round, .join = Join::round},
              subpaths);
}

} // namespace atlas::twin
