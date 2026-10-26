// The water's ripple on the host's Vulkan (passes/Ripple.cpp): the Westerkerk home by day, the ground drawn alone in
// full mode with the noise fields bound (no glitter or glints, the shadows at no opacity). The reflections' layer as
// the pass resolved it, through the CPU's filter (fx/Ripple.hpp: SvgFilterRaster's chain, the ripple's field from the
// CPU's fields), is the pass's result, word for word. Over the bare water (where the frame drawn without the
// reflections shows wgrad's rect under the tint), the frame is that result written back as sRGB at the group's opacity
// over wgrad, under the tint at .12, within 2.5 levels: the frame stores the surface, the composite and the tint, each
// blended in 8 bits (the bare water alone, two of them, lies within 1.6 levels of its model on lavapipe), where a
// result read at the wrong pixel would be tens of levels off at the reflections' edges.
#if !defined(__ANDROID__)

#include "fx/FieldCache.hpp"
#include "fx/Fields.hpp"
#include "fx/Ripple.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <format>
#include <memory>
#include <string>
#include <vector>

using namespace atlas;

namespace {

std::array<double, 3> channels(const style::Rgb8& c) {
    return {static_cast<double>(c.r), static_cast<double>(c.g), static_cast<double>(c.b)};
}

/// What a frame's ripple left: its layer and its result as the pass wrote them, and what the composite read.
struct RippleLook {
    bool drew = false;
    bool fused = false; ///< the stages ran fused (ripple_fused_cs)
    std::uint32_t w = 0;
    std::uint32_t h = 0;
    std::vector<fx::Argb> layer;
    std::vector<fx::Argb> result;
    float scale = 0;
    double wgrad_y1 = 0;
    double wgrad_y2 = 0;
    double opacity = 0;
    style::ThemeBlock th{};
    passes::PixelBox out;   ///< the result's region (the buffers hold nothing beyond it)
    passes::PixelBox drawn; ///< the layer's (the layer holds nothing beyond it)
};

void take(RippleLook& look, passes::Ground& g, const frame::FrameState& state) {
    look.drew = g.drew_ripple();
    const passes::RippleLayer* s = g.ripple_layer();
    if (!look.drew || s == nullptr) return;
    look.fused = s->ran_fused;
    look.w = s->extent.width;
    look.h = s->extent.height;
    const std::size_t n = std::size_t{look.w} * look.h;
    std::vector<std::uint8_t> texels(n * 4);
    s->layer_copy.invalidate();
    std::memcpy(texels.data(), s->layer_copy.bytes().data(), texels.size());
    look.layer.resize(n);
    for (std::size_t i = 0; i < n; ++i) {
        look.layer[i] = (fx::Argb{texels[(i * 4) + 3]} << 24U) | (fx::Argb{texels[i * 4]} << 16U) |
                        (fx::Argb{texels[(i * 4) + 1]} << 8U) | fx::Argb{texels[(i * 4) + 2]};
    }
    const gpu::Buffer& out = s->buffers[s->result];
    out.invalidate();
    look.result.resize(n);
    std::memcpy(look.result.data(), out.bytes().data(), n * 4);
    look.out = s->out;
    look.drawn = s->drawn;
    look.scale = fx::ripple_scale(state.theme.wind);
    const passes::GroundFrame& f = g.decided();
    look.wgrad_y1 = f.wgrad_y1;
    look.wgrad_y2 = f.wgrad_y2;
    look.opacity = f.tiles.front().ground->reflection_style.opacity[0];
    look.th = state.theme;
}

/// The pass's result against the CPU's chain over the pass's own layer (the ripple's field the CPU's): within the
/// result's region word for word, and beyond it transparent (the region holds all of it).
struct Chain {
    std::vector<fx::Argb> want;
    std::size_t covered = 0;
    std::size_t differ = 0;
    std::size_t beyond = 0; ///< pixels the CPU's result covers beyond the region
    std::string first;
};

Chain chain_of(const RippleLook& look, const fx::ScreenFields& fields, float density) {
    Chain c;
    const std::vector<std::uint8_t> field = fields.bytes_of(fx::Field::ripple);
    c.want = fx::ripple_cpu(look.layer, field, look.w, look.h, look.scale, density);
    for (std::size_t i = 0; i < c.want.size(); ++i) {
        const bool in = look.out.contains(static_cast<std::int32_t>(i % look.w), static_cast<std::int32_t>(i / look.w));
        if ((c.want[i] >> 24U) != 0) {
            ++c.covered;
            if (!in) ++c.beyond;
        }
        if (!in || look.result[i] == c.want[i]) continue;
        if (c.differ == 0) {
            c.first =
                std::format("pixel {} {}: {:08x}, the CPU's {:08x}", i % look.w, i / look.w, look.result[i], c.want[i]);
        }
        ++c.differ;
    }
    return c;
}

void quiet(passes::Ground& g) {
    g.keep_ripple(true);
    g.draw_glitter(false);
    g.draw_wind_glints(false);
}

} // namespace

TEST_CASE("ground: the reflections' ripple is SvgFilterRaster's chain over their layer, word for word") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the ripple is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const style::CameraState camera = test::home_camera();
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    RippleLook look;
    const auto settle = [&fields] { fields->worker().wait_idle(); };
    const auto a = test::draw_ground(
        *session, camera, day, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) { take(look, g, state); }, 40, quiet, frame::Mode::full,
        fields, settle);
    ATLAS_REQUIRE(a.has_value(), "the home was not drawn with its ripple");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE_MESSAGE(look.drew, "the frame drew its reflections without their ripple (the fields not bound?)");
    const std::shared_ptr<const fx::ScreenFields> latest = fields->worker().latest();
    ATLAS_REQUIRE(latest != nullptr, "no fields");
    REQUIRE(latest->size().width == look.w);
    REQUIRE(latest->size().height == look.h);
    const auto density = static_cast<float>(camera.density);
    const Chain chain = chain_of(look, *latest, density);
    const fx::RippleBlur blur = fx::ripple_blur(density);
    MESSAGE(std::format("the ripple at the home: scale {}, boxes {} × {} ({}); {} pixels of the result covered, {} "
                        "differing from the CPU's",
                        look.scale, blur.x, blur.y, blur.square() ? "vImage's squares" : "WebKit's passes",
                        chain.covered, chain.differ));
    MESSAGE(std::format("the result's region: {} × {} of the frame's {} × {} px", look.out.x1 - look.out.x0,
                        look.out.y1 - look.out.y0, look.w, look.h));
    CHECK(blur.square());
    CHECK(look.fused); // the boxes' reaches fit the fused stages' shared memory
    CHECK(chain.covered > 10000);
    CHECK_MESSAGE(chain.differ == 0, chain.first);
    CHECK_MESSAGE(chain.beyond == 0, chain.beyond << " pixels of the result lie beyond its region");
    const std::uint32_t w = look.w;
    const std::uint32_t h = look.h;
    const std::vector<fx::Argb>& want = chain.want;
    const style::ThemeBlock& th = look.th;
    const double opacity = look.opacity;
    const double wgrad_y1 = look.wgrad_y1;
    const double wgrad_y2 = look.wgrad_y2;

    // the frame over the bare water: the same without the reflections, and the result composited over wgrad there
    const auto b = test::draw_ground(
        *session, camera, day, dir, "amsterdam", {}, 40,
        [](passes::Ground& g) {
            quiet(g);
            g.draw_reflections(false);
        },
        frame::Mode::full, fields, settle);
    ATLAS_REQUIRE(b.has_value(), "the home was not drawn without its reflections");
    REQUIRE(a->pixels.width == w);
    REQUIRE(a->pixels.height == h);
    const std::array<double, 3> top = channels(th.water_top);
    const std::array<double, 3> bottom = channels(th.water_bottom);
    const std::array<double, 3> water = channels(th.water);
    constexpr double tint = 0.12;
    const auto wgrad_at = [&](std::size_t row) {
        const double css_y = (static_cast<double>(row) + 0.5) / static_cast<double>(camera.density);
        const double t = wgrad_y2 != wgrad_y1 ? std::clamp((css_y - wgrad_y1) / (wgrad_y2 - wgrad_y1), 0.0, 1.0)
                                              : (css_y < wgrad_y1 ? 0.0 : 1.0);
        std::array<double, 3> out{};
        for (std::size_t k = 0; k < 3; ++k) out[k] = top[k] + ((bottom[k] - top[k]) * t);
        return out;
    };
    std::vector<std::uint8_t> plain(std::size_t{w} * h, 0);
    double plain_worst = 0;
    for (std::size_t i = 0; i < plain.size(); ++i) {
        const std::array<double, 3> g = wgrad_at(i / w);
        bool bare = true;
        for (std::size_t k = 0; k < 3; ++k) {
            const double tinted = (g[k] * (1 - tint)) + (water[k] * tint);
            bare = bare && std::abs(static_cast<double>(b->pixels.rgba[(i * 4) + k]) - tinted) <= 2;
        }
        plain[i] = bare ? 1 : 0;
        if (bare) {
            for (std::size_t k = 0; k < 3; ++k) {
                const double tinted = (g[k] * (1 - tint)) + (water[k] * tint);
                plain_worst =
                    std::max(plain_worst, std::abs(static_cast<double>(b->pixels.rgba[(i * 4) + k]) - tinted));
            }
        }
    }
    std::size_t bare = 0;
    std::size_t under = 0;
    std::size_t wrong = 0;
    std::size_t past = 0;
    double worst_seen = 0;
    std::string first_wrong;
    for (std::size_t row = 1; row + 1 < h; ++row) {
        for (std::size_t column = 1; column + 1 < w; ++column) {
            bool around = true;
            for (std::size_t y = row - 1; y <= row + 1 && around; ++y) {
                for (std::size_t x = column - 1; x <= column + 1; ++x) around = around && plain[(y * w) + x] != 0;
            }
            if (!around) continue;
            ++bare;
            const std::size_t i = (row * w) + column;
            const fx::Argb f = fx::ripple_to_srgb(want[i]);
            const auto fa = static_cast<double>(f >> 24U);
            if (fa == 0) continue;
            ++under;
            const std::array<double, 3> g = wgrad_at(row);
            const std::array<double, 3> fc{static_cast<double>((f >> 16U) & 0xFFU),
                                           static_cast<double>((f >> 8U) & 0xFFU), static_cast<double>(f & 0xFFU)};
            double worst = 0;
            std::array<double, 3> expected{};
            for (std::size_t k = 0; k < 3; ++k) {
                // the result over wgrad at the group's opacity, the tint over that
                const double rippled = (fc[k] * opacity) + (g[k] * (1 - (fa / 255.0 * opacity)));
                expected[k] = (water[k] * tint) + (rippled * (1 - tint));
                worst = std::max(worst, std::abs(static_cast<double>(a->pixels.rgba[(i * 4) + k]) - expected[k]));
            }
            worst_seen = std::max(worst_seen, worst);
            if (worst > 1.5) ++past;
            if (worst <= 2.5) continue;
            if (wrong == 0) {
                first_wrong = std::format("pixel {} {}: {} {} {}, expected {:.1f} {:.1f} {:.1f}", column, row,
                                          int(a->pixels.rgba[i * 4]), int(a->pixels.rgba[(i * 4) + 1]),
                                          int(a->pixels.rgba[(i * 4) + 2]), expected[0], expected[1], expected[2]);
            }
            ++wrong;
        }
    }
    MESSAGE(std::format("the ripple over the home's water: {} pixels of bare water (the worst {:.2f} levels from its "
                        "model), {} under the rippled reflections, {} more than 1.5 levels from their composite, the "
                        "worst {:.2f}; {} past 2.5",
                        bare, plain_worst, under, past, worst_seen, wrong));
    CHECK(under > 10000);
    CHECK_MESSAGE(wrong == 0, first_wrong);
}

TEST_CASE("ground: the ripple's separable boxes, at a density whose two blur sizes differ, are its chain's too") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the ripple is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    // the home at 3 px a dp: WebKit's boxes for .1 and .45 of it are 2 and 3, so the blur runs its separable passes
    style::CameraState camera = test::home_camera();
    camera.density = 3.0;
    camera.width_px = 1236;
    camera.height_px = 2748;
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    RippleLook look;
    const auto a = test::draw_ground(
        *session, camera, day, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) { take(look, g, state); }, 40, quiet, frame::Mode::full,
        fields, [&fields] { fields->worker().wait_idle(); });
    ATLAS_REQUIRE(a.has_value(), "the home was not drawn with its ripple");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE_MESSAGE(look.drew, "the frame drew its reflections without their ripple (the fields not bound?)");
    const std::shared_ptr<const fx::ScreenFields> latest = fields->worker().latest();
    ATLAS_REQUIRE(latest != nullptr, "no fields");
    REQUIRE(latest->size().width == look.w);
    const auto density = static_cast<float>(camera.density);
    const fx::RippleBlur blur = fx::ripple_blur(density);
    const Chain chain = chain_of(look, *latest, density);
    MESSAGE(std::format("the ripple at 3 px a dp: boxes {} × {}; {} pixels of the result covered, {} differing from "
                        "the CPU's",
                        blur.x, blur.y, chain.covered, chain.differ));
    CHECK(!blur.square());
    CHECK(look.fused);
    CHECK(chain.covered > 10000);
    CHECK_MESSAGE(chain.differ == 0, chain.first);
    CHECK_MESSAGE(chain.beyond == 0, chain.beyond << " pixels of the result lie beyond its region");
}

TEST_CASE("ground: the ripple's stages apart, where the fused stages are switched off, are the same chain") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the ripple is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const style::CameraState camera = test::home_camera();
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    RippleLook look;
    const auto a = test::draw_ground(
        *session, camera, day, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) { take(look, g, state); }, 40,
        [](passes::Ground& g) {
            quiet(g);
            g.fuse_ripple(false);
        },
        frame::Mode::full, fields, [&fields] { fields->worker().wait_idle(); });
    ATLAS_REQUIRE(a.has_value(), "the home was not drawn with its ripple");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE_MESSAGE(look.drew, "the frame drew its reflections without their ripple (the fields not bound?)");
    const std::shared_ptr<const fx::ScreenFields> latest = fields->worker().latest();
    ATLAS_REQUIRE(latest != nullptr, "no fields");
    const auto density = static_cast<float>(camera.density);
    const Chain chain = chain_of(look, *latest, density);
    MESSAGE(std::format("the ripple's stages apart at the home: {} pixels of the result covered, {} differing from the "
                        "CPU's",
                        chain.covered, chain.differ));
    CHECK(!look.fused);
    CHECK(chain.covered > 10000);
    CHECK_MESSAGE(chain.differ == 0, chain.first);
    CHECK_MESSAGE(chain.beyond == 0, chain.beyond << " pixels of the result lie beyond its region");
}

TEST_CASE("ground: the reflected trees' leaf filter moves their circles' samples in the ripple's layer, at a canal") {
    // amsterdam.py's `leaf` on each reflected tree's group, at a close look along the Prinsengracht (the home shows no
    // reflected tree): the layer drawn with the filter and without; within the layer's region they differ, only within
    // the trees' regions, and where the layer without it is empty, the layer with it holds the samples the CPU's own
    // leaf-moved circles cover
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) return;
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const style::CameraState camera = test::s1_camera(52.3747, 4.8852, 52.0, 60.0, 120.0);
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    const auto settle = [&fields] { fields->worker().wait_idle(); };
    RippleLook with;
    RippleLook without;
    const auto a = test::draw_ground(
        *session, camera, day, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) { take(with, g, state); }, 160, quiet, frame::Mode::full,
        fields, settle);
    ATLAS_REQUIRE(a.has_value(), "the canal was not drawn with its leaves");
    const auto b = test::draw_ground(
        *session, camera, day, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) { take(without, g, state); }, 160,
        [](passes::Ground& g) {
            quiet(g);
            g.draw_leaves(false);
        },
        frame::Mode::full, fields, settle);
    ATLAS_REQUIRE(b.has_value(), "the canal was not drawn without its leaves");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE((with.drew && without.drew));
    REQUIRE((with.w == without.w && with.h == without.h));
    const std::shared_ptr<const fx::ScreenFields> latest = fields->worker().latest();
    ATLAS_REQUIRE(latest != nullptr, "no fields");
    const passes::GroundFrame& f = a->decided;
    const auto density = static_cast<float>(camera.density);
    const fx::RippleDisplacement d = fx::ripple_displacement(2.6F, density);
    std::array<std::int32_t, 256> offsets{};
    for (std::uint32_t c = 0; c < 256; ++c) {
        offsets[c] = static_cast<std::int32_t>(std::fma(d.factor, static_cast<float>(c), d.offset));
    }
    // each pixel's samples the trees' leaf-moved circles cover (4x standard positions), within each tree's region
    constexpr std::array<std::array<float, 2>, 4> positions{
        {{0.375F, 0.125F}, {0.875F, 0.375F}, {0.125F, 0.625F}, {0.625F, 0.875F}}};
    const auto w = static_cast<std::int32_t>(with.w);
    const auto h = static_cast<std::int32_t>(with.h);
    std::vector<std::uint8_t> mask(static_cast<std::size_t>(w) * static_cast<std::size_t>(h), 0);
    std::vector<std::uint8_t> reached(mask.size(), 0);
    std::size_t trees = 0;
    for (const passes::Reflection& e : f.reflections) {
        if (e.kind != passes::Reflection::Kind::tree) continue;
        ++trees;
        const std::array<std::int32_t, 4>& r = e.region;
        for (std::int32_t y = std::max(0, r[1]); y < std::min(h, r[3]); ++y) {
            for (std::int32_t x = std::max(0, r[0]); x < std::min(w, r[2]); ++x) {
                const std::size_t i =
                    (static_cast<std::size_t>(y) * static_cast<std::size_t>(w)) + static_cast<std::size_t>(x);
                reached[i] = 1;
                const std::array<std::uint8_t, 2> rg =
                    latest->at(fx::Field::leaf, static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y));
                const std::int32_t ox = offsets[rg[0]];
                const std::int32_t oy = offsets[rg[1]];
                if (x + ox < r[0] || y + oy < r[1] || x + ox >= r[2] || y + oy >= r[3]) continue;
                for (std::size_t k = 0; k < positions.size(); ++k) {
                    const float sx = static_cast<float>(x + ox) + positions[k][0];
                    const float sy = static_cast<float>(y + oy) + positions[k][1];
                    for (std::uint32_t c = 0; c < e.count; ++c) {
                        const passes::ReflectionCircle& q = f.reflection_circles[e.first + c];
                        const float dx = sx - (static_cast<float>(q.cx) * density);
                        const float dy = sy - (static_cast<float>(q.cy) * density);
                        if (std::sqrt((dx * dx) + (dy * dy)) <= static_cast<float>(q.r) * density) {
                            mask[i] |= static_cast<std::uint8_t>(1U << k);
                            break;
                        }
                    }
                }
            }
        }
    }
    std::size_t differ = 0;
    std::size_t astray = 0;
    std::size_t checked = 0;
    std::size_t wrong = 0;
    // the layer's content lies within its render area (the copy holds nothing of the frame beyond it)
    REQUIRE((with.drawn.x0 == without.drawn.x0 && with.drawn.y0 == without.drawn.y0 &&
             with.drawn.x1 == without.drawn.x1 && with.drawn.y1 == without.drawn.y1));
    for (std::size_t i = 0; i < mask.size(); ++i) {
        const auto x = static_cast<std::int32_t>(i % static_cast<std::size_t>(w));
        const auto y = static_cast<std::int32_t>(i / static_cast<std::size_t>(w));
        if (!with.drawn.contains(x, y)) continue;
        if (with.layer[i] != without.layer[i]) {
            ++differ;
            if (reached[i] == 0) ++astray;
        }
        if (reached[i] == 0 || (without.layer[i] >> 24U) != 0) continue;
        // nothing of the layer here without the filter: the leaves' samples alone
        const auto k = static_cast<std::uint32_t>(std::popcount(mask[i]));
        const auto alpha = static_cast<std::int32_t>(with.layer[i] >> 24U);
        const auto want = static_cast<std::int32_t>(std::lround(255.0 * k / 4.0));
        if (std::abs(alpha - want) > 2) ++wrong;
        ++checked;
    }
    MESSAGE("the canal: " << trees << " reflected trees, the leaf's reach " << d.reach << " px; " << differ
                          << " pixels of the layer differ with the filter, " << astray << " beyond the trees' regions; "
                          << checked << " empty without it held to the leaves' samples, " << wrong << " not");
    CHECK(trees >= 3);
    CHECK(differ > 200);
    CHECK(astray == 0);
    CHECK(checked > 1000);
    CHECK(wrong <= checked / 200);
}

#endif
