#include "twin/tree/TreeTwin.hpp"

#include "frame/FrameBuilder.hpp"
#include "frame/Ranks.hpp"
#include "scene/Cover.hpp"
#include "sun/BitTiles.hpp"

#include <algorithm>
#include <bit>
#include <cstring>

namespace atlas::twin {

namespace {

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// A point the shader computed, as the stream takes it.
[[nodiscard]] Point point(hlsl::float2 p) noexcept { return {.x = p.x, .y = p.y}; }

/// The sun texel of a crown's sample, as set 1's sun_bits holds it: bit (sample % 32) of x the new bit, of y the old.
[[nodiscard]] hlsl::uint2 texel_of(const scene::CoverTile& t, std::uint32_t sample, bool& has) {
    has = t.sun_base != scene::no_sun && t.sun && t.sun->now && t.sun->before;
    hlsl::uint2 texel{0U, 0U};
    if (!has) return texel;
    const std::span<const std::uint64_t> now = t.sun->now->words;
    const std::span<const std::uint64_t> before = t.sun->before->words;
    const std::uint32_t lane = sample % 32;
    if (sample / 64 < now.size() && sun::bit(now, sample)) texel.x |= 1U << lane;
    if (sample / 64 < before.size() && sun::bit(before, sample)) texel.y |= 1U << lane;
    return texel;
}

} // namespace

TreeTwin::TreeTwin(const frame::FrameState& state, const tiles::Style& style, Stream& out)
    : state_(state), out_(out), camera_(state.block.camera),
      tones_(passes::tree_tones(state.block.theme, state.theme, style.trees)),
      weather_((state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U),
      night_((state.block.theme.flags.x & shaders::THEME_NIGHT) != 0U),
      right_(state.view && passes::tree_side_right(state.block.theme, state.view->camera())),
      focal_(state.view ? state.view->camera().focal() : 0.0) {
    // the objects group: the port's `<g stroke-linejoin="round">`, with its `mat` filter where the moment has weather,
    // under the frame's root (the buildings pass's own alike); a crown's `<g filter="url(#crown)">` and its tones'
    // groups (`<g fill=...>`, which carry no effect: each circle takes its fill), the generic tree's blobs' groups
    objects_ = out.group({.node = {.filter = weather_ ? out.filter("mat") : none}, .parent = out.root()});
    if (weather_) {
        crown_ = out.group({.node = {.filter = out.filter("crown")}, .parent = objects_});
        tones_group_ = out.group({.node = {}, .parent = crown_});
    } else {
        blobs_ = out.group({.node = {}, .parent = objects_});
    }
}

Paint TreeTwin::paint(std::uint32_t bgr) noexcept {
    const std::uint32_t rgb = ((bgr & 0xFFU) << 16U) | (bgr & 0xFF00U) | ((bgr >> 16U) & 0xFFU);
    return Paint::colour(0xFF00'0000U | rgb);
}

void TreeTwin::put(std::uint32_t k, const shaders::TreeRecord& r) {
    if (k < tree_records_.size()) tree_records_[k] = r;
}

void TreeTwin::tree(const scene::CoverTile& tile, const passes::TreeTile& trees, std::span<const std::byte> heap,
                    std::uint32_t tree, std::uint32_t rank, double depth) {
    const std::size_t at = std::size_t{tree} * shaders::TREE_VECTORS * sizeof(hlsl::uint4);
    if (at + (shaders::TREE_VECTORS * sizeof(hlsl::uint4)) > heap.size()) return;
    std::array<hlsl::uint4, shaders::TREE_VECTORS> words{};
    std::memcpy(words.data(), heap.data() + at, sizeof words);
    // the job, as the pass writes it
    const passes::TreeChoice choice = passes::tree_choice(depth, focal_, trees.r[tree], weather_, night_, right_);
    shaders::TreeJob j{};
    j.place = tile.place;
    j.flags = choice.flags;
    j.slot = shaders::rank_slot(rank);
    j.first = 0;
    j.w0 = choice.w0;
    j.w1 = choice.w1;
    j.limb = choice.limb;
    j.sun = tile.sun_base;
    j.weight = bits(tile.sun_weight);
    j.sample = (tile.sun && tile.sun->now ? tile.sun->now->crowns_from : 0U) + tree;
    j.tint = shaders::TREE_TONE_TINTS + (trees.evergreen[tree] != 0 ? shaders::TREE_TINT_TONES : 0U);
    const shaders::TreeHeader t = shaders::tree_header(words[0], words[1]);
    const std::int32_t item =
        out_.item({.owner = Owner::node, .id = trees.ids[tree], .kind = std::string_view("tree")});
    tree_records_.assign(shaders::tree_record_count(j.flags), shaders::tree_nothing());
    side_kept_ = false;
    side_record_ = shaders::tree_nothing();
    sun_texel_ = texel_of(tile, j.sample, sun_has_);
    if ((j.flags & shaders::TREE_JOB_GENERIC) != 0U) {
        generic_tree(j, t, words, item);
    } else if ((j.flags & shaders::TREE_JOB_SMALL) != 0U) {
        small_tree(j, t, item);
    } else {
        close_tree(j, t, words, item);
    }
    if (records_ != nullptr) records_->insert(records_->end(), tree_records_.begin(), tree_records_.end());
    if (sides_ != nullptr && (j.flags & shaders::TREE_JOB_SIDE) != 0U) sides_->push_back(side_record_);
}

void TreeTwin::small_tree(const shaders::TreeJob& j, const shaders::TreeHeader& t, std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const hlsl::float3 k = shaders::tree_camera(c, j.place, shaders::tree_local(t, 0.0F, t.h * 0.65F, 0.0F));
    if (!shaders::tree_ahead(c, k)) return;
    const float kz = std::max(k.z, c.frame.w);
    const hlsl::float2 p = shaders::css_of(c, k);
    const float rr = j.w0;
    const hlsl::float2 at = shaders::tree_printed(c, p);
    out_.at(j.slot, objects_, item);
    if ((j.flags & shaders::TREE_JOB_ONE) != 0U) {
        const std::uint32_t tone = tones_[j.tint + shaders::TREE_TONE_SMALL_MIX];
        put(0,
            shaders::tree_circle(at, shaders::tree_print(c, rr), tone, 1.0F, shaders::tree_depth(c, kz, j.slot, 0U)));
        out_.circle({.fill = paint(tone)}, at.x, at.y, shaders::tree_print(c, rr));
        return;
    }
    const std::uint32_t shade = tones_[j.tint + shaders::TREE_TONE_SMALL_SHADE];
    // the GPU's records nearest first: the second circle, then the first
    put(1, shaders::tree_circle(at, shaders::tree_print(c, rr), shade, 1.0F, shaders::tree_depth(c, kz, j.slot, 0U)));
    out_.circle({.fill = paint(shade)}, at.x, at.y, shaders::tree_print(c, rr));
    const hlsl::float2 q = {shaders::tree_print(c, p.x - rr * 0.2F), shaders::tree_print(c, p.y - rr * 0.25F)};
    const std::uint32_t lit = tones_[j.tint + shaders::TREE_TONE_SMALL_LIT];
    put(0,
        shaders::tree_circle(q, shaders::tree_print(c, rr * 0.62F), lit, 1.0F, shaders::tree_depth(c, kz, j.slot, 1U)));
    out_.at(j.slot + 1, objects_, item);
    out_.circle({.fill = paint(lit)}, q.x, q.y, shaders::tree_print(c, rr * 0.62F));
}

void TreeTwin::close_tree(const shaders::TreeJob& j, const shaders::TreeHeader& t, std::span<const hlsl::uint4> words,
                          std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const bool night = (j.flags & shaders::TREE_JOB_NIGHT) != 0U;
    const hlsl::float3 k0 = shaders::tree_camera(c, j.place, shaders::tree_local(t, 0.0F, 0.0F, 0.0F));
    const hlsl::float3 k1 = shaders::tree_camera(c, j.place, shaders::tree_local(t, 0.0F, t.top, 0.0F));
    if (!shaders::tree_ahead(c, k0) || !shaders::tree_ahead(c, k1)) return;
    const hlsl::float3 kc = shaders::tree_camera(c, j.place, shaders::tree_local(t, 0.0F, t.cy, 0.0F));
    const float kz = std::max(kc.z, c.frame.w);
    const hlsl::float2 c0 = shaders::css_of(c, k0);
    const hlsl::float2 c1 = shaders::css_of(c, k1);
    const std::uint32_t bark = tones_[shaders::TREE_TONE_BARK];
    // the GPU's records nearest first: the crown's circles, the limbs, the trunk
    const std::uint32_t nb = (j.flags >> shaders::TREE_JOB_NB_SHIFT) & 0xFFU;
    const std::uint32_t limbs_at = 3 * nb;
    // the trunk
    std::array<hlsl::float2, 4> q{};
    std::array<Point, 4> pts{};
    for (std::uint32_t i = 0; i < 4; ++i) {
        q[i] = shaders::tree_trunk_corner(c, c0, c1, j.w0, j.w1, i);
        pts[i] = point(q[i]);
    }
    put(limbs_at + 5, shaders::tree_quad(q[0], q[1], q[2], q[3], bark, 1.0F, shaders::tree_depth(c, kz, j.slot, 0U)));
    out_.at(j.slot, objects_, item);
    out_.path({.fill = paint(bark)}, pts, true);
    // its sunlit side
    if ((j.flags & shaders::TREE_JOB_SIDE) != 0U) {
        const float sx = (j.flags & shaders::TREE_JOB_RIGHT) != 0U ? 1.0F : -1.0F;
        for (std::uint32_t i = 0; i < 4; ++i) {
            q[i] = shaders::tree_side_corner(c, c0, c1, j.w0, j.w1, sx, i);
            pts[i] = point(q[i]);
        }
        const std::uint32_t side = tones_[shaders::TREE_TONE_SIDE];
        const auto opacity = std::bit_cast<float>(tones_[shaders::TREE_TONE_SIDE_OPACITY]);
        side_record_ =
            shaders::tree_quad(q[0], q[1], q[2], q[3], side, opacity, shaders::tree_depth(c, kz, j.slot, 1U));
        side_kept_ = true;
        out_.at(j.slot + 1, objects_, item);
        out_.path({.fill = paint(side)}, pts, true, {.opacity = opacity});
    }
    // the clumps kept at the camera
    struct Kept {
        float dd;
        std::uint32_t i;
    };
    std::vector<Kept> kept;
    const auto clump = [&](std::uint32_t i) { return shaders::tree_clump(words[shaders::TREE_HEADER_VECTORS + i]); };
    for (std::uint32_t i = 0; i < nb; ++i) {
        const shaders::TreeSeen s = shaders::tree_seen(c, j.place, t, clump(i), false);
        if (s.kept) kept.push_back({.dd = s.k.z, .i = i});
    }
    if (kept.empty()) return;
    const float big = nb < 12U ? 1.25F : 1.0F;
    // the limbs: sorted(cl, key=y)[:5], a stable sort
    if ((j.flags & shaders::TREE_JOB_LIMBS) != 0U) {
        std::vector<std::uint32_t> by_y(kept.size());
        for (std::uint32_t m = 0; m < kept.size(); ++m) by_y[m] = m;
        std::ranges::stable_sort(by_y, {}, [&](std::uint32_t m) { return clump(kept[m].i).y; });
        const hlsl::float2 top = shaders::tree_printed(c, c1);
        std::vector<std::array<Point, 2>> limbs;
        for (std::uint32_t l = 0; l < 5 && l < by_y.size(); ++l) {
            const shaders::TreeSeen s = shaders::tree_seen(c, j.place, t, clump(kept[by_y[l]].i), false);
            const hlsl::float2 end = shaders::tree_printed(c, shaders::css_of(c, s.k));
            put(limbs_at + l, shaders::tree_segment(top, end, 0.5F * j.limb, shaders::TREE_ROUND, bark, 1.0F,
                                                    shaders::tree_depth(c, kz, j.slot, 2U)));
            limbs.push_back({point(top), point(end)});
        }
        std::vector<Subpath> subpaths;
        subpaths.reserve(limbs.size());
        for (const std::array<Point, 2>& limb : limbs) subpaths.push_back({.points = limb, .closed = false});
        out_.at(j.slot + 2, objects_, item);
        out_.path({.fill = {}, .stroke = paint(bark), .stroke_width = j.limb, .cap = Cap::round, .join = Join::round},
                  subpaths);
    }
    // the crown: by depth, far to near (Python's sort by -dd is stable), three slices of four tones
    std::ranges::stable_sort(kept, std::ranges::greater{}, &Kept::dd);
    const auto n = static_cast<std::uint32_t>(kept.size());
    bool has = sun_has_;
    const float amount = shaders::tree_sun_amount(j.sample, sun_texel_, has, std::bit_cast<float>(j.weight), night);
    const bool lit = shaders::tree_sunlit(j.sample, sun_texel_, has, night);
    const hlsl::float3 l = state_.block.theme.light.xyz;
    const std::uint32_t lit_tone =
        shaders::tree_mix(tones_[j.tint + shaders::TREE_TONE_LIT], tones_[j.tint + shaders::TREE_TONE_LIT + 1], amount);
    const std::uint32_t high_tone = shaders::tree_mix(tones_[j.tint + shaders::TREE_TONE_HIGHLIGHT],
                                                      tones_[j.tint + shaders::TREE_TONE_HIGHLIGHT + 1], amount);
    const std::uint32_t third = n / 3;
    const std::uint32_t two_thirds = (2 * n) / 3;
    std::vector<std::array<Circle, 3>> circles(n);
    for (std::uint32_t p = 0; p < n; ++p) {
        const std::uint32_t slice = p < third ? 0U : (p < two_thirds ? 1U : 2U);
        const shaders::TreeClump qc = clump(kept[p].i);
        const shaders::TreeSeen s = shaders::tree_seen(c, j.place, t, qc, false);
        const float br = qc.br * big;
        const float rr = br * c.forward.w / s.k.z;
        const std::uint32_t at = 3 * (n - 1 - p);
        const std::uint32_t g = qc.under ? 0U : 1U;
        const std::uint32_t tone = tones_[j.tint + (qc.under ? shaders::TREE_TONE_UNDER : shaders::TREE_TONE_FLANK)];
        const hlsl::float2 centre = shaders::tree_printed(c, shaders::css_of(c, s.k));
        put(at + 2, shaders::tree_circle(centre, shaders::tree_print(c, rr), tone, 1.0F,
                                         shaders::tree_depth(c, kz, j.slot, 3U + (4U * slice) + g)));
        circles[p][0] = {.at = point(centre), .r = shaders::tree_print(c, rr), .tone = tone, .drawn = true};
        const float lam = shaders::tree_lam(t, qc, l, night);
        if (lam > -0.2F) {
            const hlsl::float3 m = shaders::tree_toward(c, j.place, t, qc, br, l, 0.35F);
            if (shaders::tree_ahead(c, m)) {
                const hlsl::float2 o = shaders::tree_printed(c, shaders::css_of(c, m));
                put(at + 1, shaders::tree_circle(o, shaders::tree_print(c, rr * 0.72F), lit_tone, 1.0F,
                                                 shaders::tree_depth(c, kz, j.slot, 3U + (4U * slice) + 2U)));
                circles[p][1] = {
                    .at = point(o), .r = shaders::tree_print(c, rr * 0.72F), .tone = lit_tone, .drawn = true};
            }
        }
        if (lam > 0.35F && lit) {
            const hlsl::float3 m = shaders::tree_toward(c, j.place, t, qc, br, l, 0.55F);
            if (shaders::tree_ahead(c, m)) {
                const hlsl::float2 o = shaders::tree_printed(c, shaders::css_of(c, m));
                put(at, shaders::tree_circle(o, shaders::tree_print(c, rr * 0.36F), high_tone, 1.0F,
                                             shaders::tree_depth(c, kz, j.slot, 3U + (4U * slice) + 3U)));
                circles[p][2] = {
                    .at = point(o), .r = shaders::tree_print(c, rr * 0.36F), .tone = high_tone, .drawn = true};
            }
        }
    }
    // the port's order: each slice's under or side tone's circles (groups 0 and 1), its lit tone's, its highlights'
    for (std::uint32_t slice = 0; slice < 3; ++slice) {
        const std::uint32_t from = slice == 0 ? 0U : (slice == 1 ? third : two_thirds);
        const std::uint32_t to = slice == 0 ? third : (slice == 1 ? two_thirds : n);
        for (std::uint32_t g = 0; g < 4; ++g) {
            for (std::uint32_t p = from; p < to; ++p) {
                const bool under = clump(kept[p].i).under;
                const std::size_t which = g < 2 ? 0U : g - 1U;
                if (g < 2 && (g == 0) != under) continue;
                const Circle& e = circles[p][which];
                if (!e.drawn) continue;
                out_.at(j.slot + 3 + (4 * slice) + g, tones_group_, item);
                out_.circle({.fill = paint(e.tone)}, e.at.x, e.at.y, e.r);
            }
        }
    }
}

void TreeTwin::generic_tree(const shaders::TreeJob& j, const shaders::TreeHeader& t, std::span<const hlsl::uint4> words,
                            std::int32_t item) {
    const shaders::FrameCamera& c = camera_;
    const bool night = (j.flags & shaders::TREE_JOB_NIGHT) != 0U;
    const hlsl::float3 k0 = shaders::tree_camera(c, j.place, shaders::tree_local(t, 0.0F, 0.0F, 0.0F));
    const hlsl::float3 k1 = shaders::tree_camera(c, j.place, shaders::tree_local(t, 0.0F, t.h * 0.55F, 0.0F));
    if (!shaders::tree_ahead(c, k0) || !shaders::tree_ahead(c, k1)) return;
    const hlsl::float3 kc = shaders::tree_camera(c, j.place, shaders::tree_local(t, 0.0F, t.h * 0.68F, 0.0F));
    const float kz = std::max(kc.z, c.frame.w);
    const hlsl::float2 p0 = shaders::tree_printed(c, shaders::css_of(c, k0));
    const hlsl::float2 p1 = shaders::tree_printed(c, shaders::css_of(c, k1));
    const std::uint32_t trunk = tones_[shaders::TREE_TONE_GENERIC_TRUNK];
    const std::uint32_t nb = (j.flags >> shaders::TREE_JOB_NB_SHIFT) & 0xFFU;
    // the GPU's records nearest first: the blobs, then the trunk
    put(2 * nb, shaders::tree_segment(p0, p1, 0.5F * j.w0, shaders::TREE_ROUND, trunk, 1.0F,
                                      shaders::tree_depth(c, kz, j.slot, 0U)));
    const std::array<Point, 2> line{point(p0), point(p1)};
    out_.at(j.slot, objects_, item);
    // the port's path has no fill: SVG's black, which a line paints nothing with
    out_.path({.fill = Paint::colour(0xFF00'0000U),
               .stroke = paint(trunk),
               .stroke_width = j.w0,
               .cap = Cap::round,
               .join = Join::round},
              line, false);
    struct Kept {
        float dd;
        std::uint32_t i;
    };
    std::vector<Kept> kept;
    const auto blob = [&](std::uint32_t i) {
        return shaders::tree_clump(words[shaders::TREE_HEADER_VECTORS + shaders::TREE_CLUMPS + i]);
    };
    for (std::uint32_t i = 0; i < nb; ++i) {
        const shaders::TreeSeen s = shaders::tree_seen(c, j.place, t, blob(i), true);
        if (s.kept) kept.push_back({.dd = s.k.z, .i = i});
    }
    std::ranges::stable_sort(kept, std::ranges::greater{}, &Kept::dd);
    bool has = sun_has_;
    const float amount = shaders::tree_sun_amount(j.sample, sun_texel_, has, std::bit_cast<float>(j.weight), night);
    const hlsl::float3 l = state_.block.theme.light.xyz;
    const std::uint32_t dark = tones_[shaders::TREE_TONE_GENERIC_DARK];
    const std::uint32_t mid =
        shaders::tree_mix(tones_[shaders::TREE_TONE_GENERIC_MID], tones_[shaders::TREE_TONE_GENERIC_MID + 1], amount);
    std::vector<Circle> under;
    std::vector<Circle> top;
    for (std::uint32_t p = 0; p < kept.size(); ++p) {
        const shaders::TreeClump q = blob(kept[p].i);
        const shaders::TreeSeen s = shaders::tree_seen(c, j.place, t, q, true);
        const hlsl::float2 at = shaders::css_of(c, s.k);
        const float rr = q.br * c.forward.w / s.k.z;
        const hlsl::float2 centre = shaders::tree_printed(c, at);
        const std::uint32_t pair = 2 * (static_cast<std::uint32_t>(kept.size()) - 1 - p);
        put(pair + 1, shaders::tree_circle(centre, shaders::tree_print(c, rr), dark, 1.0F,
                                           shaders::tree_depth(c, kz, j.slot, 1U)));
        under.push_back({.at = point(centre), .r = shaders::tree_print(c, rr), .tone = dark, .drawn = true});
        if (!night) {
            const hlsl::float3 m = shaders::tree_toward(c, j.place, t, q, q.br, l, 1.0F);
            if (shaders::tree_ahead(c, m)) {
                const hlsl::float2 sx = shaders::css_of(c, m);
                const hlsl::float2 o = {at.x + (sx.x - at.x) * 0.3F, at.y + (sx.y - at.y) * 0.3F};
                const hlsl::float2 printed = shaders::tree_printed(c, o);
                put(pair, shaders::tree_circle(printed, shaders::tree_print(c, rr * 0.78F), mid, 1.0F,
                                               shaders::tree_depth(c, kz, j.slot, 2U)));
                top.push_back(
                    {.at = point(printed), .r = shaders::tree_print(c, rr * 0.78F), .tone = mid, .drawn = true});
            }
        }
    }
    for (const Circle& e : under) {
        out_.at(j.slot + 1, blobs_, item);
        out_.circle({.fill = paint(e.tone)}, e.at.x, e.at.y, e.r);
    }
    for (const Circle& e : top) {
        out_.at(j.slot + 2, blobs_, item);
        out_.circle({.fill = paint(e.tone)}, e.at.x, e.at.y, e.r);
    }
}

} // namespace atlas::twin
