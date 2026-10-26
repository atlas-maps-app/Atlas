// The water's glints (passes/Glints.cpp; fx/Glints.hpp): the moment's table of the filter's 256 results is the
// colour matrix as WebKit's float code applies it (A = 11 · red − 6.9 · 255 through one fused rounding, then rint;
// the glint's colour premultiplied by it), written back as sRGB; and on the host's Vulkan, the Westerkerk home by day,
// the ground drawn alone with the glints and without (no reflections or glitter, the shadows at no opacity): over the
// bare water, in full mode with the fields bound, each pixel is the table's entry at the glints' field's red there
// composited at the rect's opacity over the frame without them, within a level (one 8-bit store); in structural mode,
// whose references leave out an element of a generator-only filter, nothing.
#include "fx/ByteTables.hpp"
#include "fx/Glints.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

using namespace atlas;

TEST_CASE("fx: the glints' table is the colour matrix's result for each red, written back as sRGB") {
    for (const bool night : {false, true}) {
        const std::array<float, 3> gl = fx::glint_colour(night);
        const std::array<fx::Argb, 256> table = fx::glint_table(gl);
        std::size_t drawn = 0;
        for (std::uint32_t r = 0; r < 256; ++r) {
            // row 15 of the matrix: fma(11, r, 0); then fma(-6.9f, 255, t), one rounding of the exact sum; rint
            const double exact = (11.0 * r) + (static_cast<double>(-6.9F) * 255.0);
            const double a = std::clamp(std::nearbyint(static_cast<double>(static_cast<float>(exact))), 0.0, 255.0);
            fx::Argb want = 0;
            if (a > 0) {
                const auto alpha = static_cast<std::uint32_t>(a);
                std::array<std::uint32_t, 3> c{};
                for (std::size_t k = 0; k < 3; ++k) {
                    c[k] = static_cast<std::uint32_t>(std::nearbyint(static_cast<double>(gl[k] * 255.0F)));
                }
                const fx::Argb linear = fx::bytes::premultiply(fx::bytes::pack(alpha, c[0], c[1], c[2]));
                want = fx::bytes::premultiply(fx::bytes::unpremultiply_exact(linear, false));
                ++drawn;
            }
            CHECK_MESSAGE(table[r] == want, (night ? "night" : "day") << " red " << r);
        }
        // the glints show where the noise's red passes 160
        CHECK(drawn == 95);
    }
}

#if !defined(__ANDROID__)

#include "fx/FieldCache.hpp"
#include "fx/Fields.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <format>
#include <memory>
#include <string>
#include <vector>

namespace {

std::array<double, 3> channels(const style::Rgb8& c) {
    return {static_cast<double>(c.r), static_cast<double>(c.g), static_cast<double>(c.b)};
}

/// What the frames with and without the glints show over the bare water.
struct Count {
    std::size_t bare = 0;
    std::size_t glinted = 0;
    std::size_t wrong = 0;
    std::string first;
};

} // namespace

TEST_CASE("ground: the glints are their filter's table at the field's red, over the water at the rect's opacity") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the glints are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const auto settle = [&fields] { fields->worker().wait_idle(); };
    const style::CameraState camera = test::home_camera();
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    for (const frame::Mode mode : {frame::Mode::full, frame::Mode::structural}) {
        const bool full = mode == frame::Mode::full;
        double opacity = 0;
        bool filtered = false;
        double wgrad_y1 = 0;
        double wgrad_y2 = 0;
        style::ThemeBlock th{};
        const auto quiet = [](passes::Ground& g) {
            g.draw_reflections(false);
            g.draw_glitter(false);
        };
        const auto a = test::draw_ground(
            *session, camera, day, dir, "amsterdam",
            [&](passes::Ground& g, const frame::FrameState& state) {
                const passes::GroundFrame& f = g.decided();
                opacity = f.wind_glints ? f.wind_glints_opacity : 0;
                filtered = (state.block.info.x & shaders::FRAME_FIELDS) != 0U;
                wgrad_y1 = f.wgrad_y1;
                wgrad_y2 = f.wgrad_y2;
                th = state.theme;
            },
            40, quiet, mode, fields, settle);
        ATLAS_REQUIRE(a.has_value(), "the home was not drawn with its glints");
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
        CHECK(filtered == full);
        CHECK((opacity > 0) == full);
        const auto b = test::draw_ground(
            *session, camera, day, dir, "amsterdam", {}, 40,
            [&](passes::Ground& g) {
                quiet(g);
                g.draw_wind_glints(false);
            },
            mode, fields, settle);
        ATLAS_REQUIRE(b.has_value(), "the home was not drawn without its glints");
        if (!full) {
            CHECK_MESSAGE(a->pixels.rgba == b->pixels.rgba, "structural mode drew the glints");
            continue;
        }
        const std::uint32_t w = a->pixels.width;
        const std::uint32_t h = a->pixels.height;
        const std::shared_ptr<const fx::ScreenFields> latest = fields->worker().latest();
        ATLAS_REQUIRE(latest != nullptr, "no fields");
        REQUIRE(latest->size().width == w);
        const std::vector<std::uint8_t> red = latest->bytes_of(fx::Field::glints);
        const std::array<fx::Argb, 256> table = fx::glint_table(fx::glint_colour(false));
        // the bare water of the frame without them: wgrad under the tint, a pixel's neighbours too
        const std::array<double, 3> top = channels(th.water_top);
        const std::array<double, 3> bottom = channels(th.water_bottom);
        const std::array<double, 3> water = channels(th.water);
        constexpr double tint = 0.12;
        std::vector<std::uint8_t> plain(std::size_t{w} * h, 0);
        for (std::size_t i = 0; i < plain.size(); ++i) {
            const std::size_t row = i / w;
            const double css_y = (static_cast<double>(row) + 0.5) / static_cast<double>(camera.density);
            const double t = wgrad_y2 != wgrad_y1 ? std::clamp((css_y - wgrad_y1) / (wgrad_y2 - wgrad_y1), 0.0, 1.0)
                                                  : (css_y < wgrad_y1 ? 0.0 : 1.0);
            bool bare = true;
            for (std::size_t k = 0; k < 3; ++k) {
                const double g = top[k] + ((bottom[k] - top[k]) * t);
                bare = bare && std::abs(static_cast<double>(b->pixels.rgba[(i * 4) + k]) -
                                        ((g * (1 - tint)) + (water[k] * tint))) <= 2;
            }
            plain[i] = bare ? 1 : 0;
        }
        Count c;
        for (std::size_t row = 1; row + 1 < h; ++row) {
            for (std::size_t column = 1; column + 1 < w; ++column) {
                bool around = true;
                for (std::size_t y = row - 1; y <= row + 1 && around; ++y) {
                    for (std::size_t x = column - 1; x <= column + 1; ++x) around = around && plain[(y * w) + x] != 0;
                }
                if (!around) continue;
                ++c.bare;
                const std::size_t i = (row * w) + column;
                // the filter's result there, at the rect's opacity over the water
                const fx::Argb f = table[red[i]];
                const auto fa = static_cast<double>(f >> 24U);
                if (fa == 0) continue;
                ++c.glinted;
                const std::array<double, 3> fc{static_cast<double>((f >> 16U) & 0xFFU),
                                               static_cast<double>((f >> 8U) & 0xFFU), static_cast<double>(f & 0xFFU)};
                double worst = 0;
                std::array<double, 3> want{};
                for (std::size_t k = 0; k < 3; ++k) {
                    const auto under = static_cast<double>(b->pixels.rgba[(i * 4) + k]);
                    want[k] = (fc[k] * opacity) + (under * (1 - (fa / 255.0 * opacity)));
                    worst = std::max(worst, std::abs(static_cast<double>(a->pixels.rgba[(i * 4) + k]) - want[k]));
                }
                if (worst <= 1) continue;
                if (c.wrong == 0) {
                    c.first = std::format("pixel {} {}: {} {} {}, expected {:.1f} {:.1f} {:.1f}", column, row,
                                          int(a->pixels.rgba[i * 4]), int(a->pixels.rgba[(i * 4) + 1]),
                                          int(a->pixels.rgba[(i * 4) + 2]), want[0], want[1], want[2]);
                }
                ++c.wrong;
            }
        }
        MESSAGE(
            std::format("the glints at the home: opacity {:.2f}; {} pixels of bare water, {} under them, {} not their "
                        "composite",
                        opacity, c.bare, c.glinted, c.wrong));
        CHECK(c.glinted > 2000U);
        CHECK_MESSAGE(c.wrong == 0, c.first);
    }
}

#endif
