// The regions' canopy (fx/Canopy.hpp; passes/Ground.cpp's record_canopy, ground.slang's canopy_fs) on the host's
// Vulkan: the canal ring's woods at Artis and on the Westerdok by day, the ground drawn alone (engine mode, at S1). In
// full mode, with the fields bound, each pixel the canopy's union covers whole (where the frame without filters draws
// it black at .55 over the frame without it, the pixel and its neighbours) is the filter's result at the canopy's alpha
// there, from the port's own arithmetic (fx::canopy_table of the field's byte, both held to SvgFilterRaster:
// canopy_test.cpp, FieldParityTest), at .55 over the frame without the canopy, both read as the image holds them (10
// bits a channel where the device draws them): the fragment's colour and alpha at the image's levels, as the blender
// takes them, over the pixel there, within the blender's own rounding (two of the image's levels: lavapipe truncates
// its product), leaning neither way, where the field read a pixel over would miss by more; in structural mode, whose
// references draw a filter as absent, it stays black at .55. And in the app's map the canopy keeps its place on
// the ground: drawn from above and again with the camera 48 device px further east, the woods show the same where the
// ground is the same, as they do not where the canopy reads the screen's field.
#if !defined(__ANDROID__)

#include "fx/Canopy.hpp"
#include "fx/FieldCache.hpp"
#include "fx/Fields.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/tiles/support.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <format>
#include <memory>
#include <optional>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// A canal ring's wood the tests look at.
struct Wood {
    const char* name;
    double lat;
    double lon;
};

/// Artis's woods and scrub, and the Westerdok's woods.
constexpr std::array<Wood, 2> woods{
    {{.name = "Artis", .lat = 52.3660, .lon = 4.9160}, {.name = "the Westerdok", .lat = 52.3845, .lon = 4.9065}}};

/// The pixels of a frame that the canopy's union covers whole, from the frames without filters with it and without it:
/// where every channel is .45 of the one without, for the pixel and its eight neighbours (land, not black).
std::vector<std::uint8_t> covered(const gpu::Pixels& with, const gpu::Pixels& without) {
    const std::uint32_t w = with.width;
    const std::uint32_t h = with.height;
    std::vector<std::uint8_t> dark(std::size_t{w} * h, 0);
    for (std::size_t i = 0; i < dark.size(); ++i) {
        bool d = without.rgba[(i * 4) + 1] >= 60;
        for (std::size_t k = 0; k < 3; ++k) {
            const double want = 0.45 * static_cast<double>(without.rgba[(i * 4) + k]);
            d = d && std::abs(static_cast<double>(with.rgba[(i * 4) + k]) - want) <= 1.0;
        }
        dark[i] = d ? 1 : 0;
    }
    std::vector<std::uint8_t> out(dark.size(), 0);
    for (std::uint32_t y = 1; y + 1 < h; ++y) {
        for (std::uint32_t x = 1; x + 1 < w; ++x) {
            bool all = true;
            for (std::uint32_t v = y - 1; v <= y + 1 && all; ++v) {
                for (std::uint32_t u = x - 1; u <= x + 1; ++u) all = all && dark[(std::size_t{v} * w) + u] != 0;
            }
            out[(std::size_t{y} * w) + x] = all ? 1 : 0;
        }
    }
    return out;
}

/// The levels a channel of the image holds: 1023 at 10 bits, else 255.
double levels_of(const gpu::Packed& p) {
    return p.format == vk::Format::eA2B10G10R10UnormPack32 || p.format == vk::Format::eA2R10G10B10UnormPack32 ? 1023.0
                                                                                                              : 255.0;
}

/// Pixel `i`'s red, green and blue as the image holds them, in 8-bit levels (fractions where it has more bits).
std::array<double, 3> levels_at(const gpu::Packed& p, std::size_t i) {
    const std::uint32_t w = p.words[i];
    switch (p.format) {
    case vk::Format::eA2B10G10R10UnormPack32:
    case vk::Format::eA2R10G10B10UnormPack32: {
        const bool red_first = p.format == vk::Format::eA2B10G10R10UnormPack32;
        const auto level = [w](std::uint32_t shift) {
            return static_cast<double>((w >> shift) & 0x3FFU) * 255.0 / 1023.0;
        };
        return red_first ? std::array{level(0), level(10), level(20)} : std::array{level(20), level(10), level(0)};
    }
    case vk::Format::eB8G8R8A8Unorm:
        return {static_cast<double>((w >> 16U) & 0xFFU), static_cast<double>((w >> 8U) & 0xFFU),
                static_cast<double>(w & 0xFFU)};
    default:
        return {static_cast<double>(w & 0xFFU), static_cast<double>((w >> 8U) & 0xFFU),
                static_cast<double>((w >> 16U) & 0xFFU)};
    }
}

/// The camera state `c` with the canopy left out.
style::CameraState without_canopy(style::CameraState c) {
    c.parts |= style::camera_part::canopy;
    return c;
}

/// The mean of each channel over the pixels `mask` holds.
std::array<double, 3> mean_of(const gpu::Pixels& px, const std::vector<std::uint8_t>& mask) {
    std::array<double, 3> sum{};
    std::size_t n = 0;
    for (std::size_t i = 0; i < mask.size(); ++i) {
        if (mask[i] == 0) continue;
        for (std::size_t k = 0; k < 3; ++k) sum[k] += px.rgba[(i * 4) + k];
        ++n;
    }
    for (double& s : sum) s /= static_cast<double>(std::max<std::size_t>(n, 1));
    return sum;
}

} // namespace

TEST_CASE("ground: the canopy is its filter's result at the field's alpha, over the woods at .55") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the canopy is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const auto settle = [&fields] { fields->worker().wait_idle(); };
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    for (const Wood& wood : woods) {
        const style::CameraState camera = test::s1_camera(wood.lat, wood.lon, 30.0, 40.0, 700.0);
        std::array<std::optional<test::GroundDraw>, 4> frames;
        bool filtered = false;
        bool canopy = false;
        for (std::size_t k = 0; k < frames.size(); ++k) {
            const bool full = k < 2;
            const bool with = k % 2 == 0;
            frames.at(k) = test::draw_ground(
                *session, with ? camera : without_canopy(camera), day, dir, "grachten",
                [&](passes::Ground& g, const frame::FrameState& state) {
                    if (full && with) {
                        filtered = g.canopy_filtered(state);
                        canopy = g.decided().canopy;
                    }
                },
                40, {}, full ? frame::Mode::full : frame::Mode::structural, fields, settle);
            ATLAS_REQUIRE(frames.at(k).has_value(), std::format("{} was not drawn", wood.name));
        }
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
        CHECK_MESSAGE(canopy, wood.name);
        CHECK_MESSAGE(filtered, wood.name);
        const gpu::Pixels& a = frames[0]->pixels;
        const gpu::Pixels& b = frames[1]->pixels;
        const std::vector<std::uint8_t> inside = covered(frames[2]->pixels, frames[3]->pixels);
        const std::shared_ptr<const fx::ScreenFields> latest = fields->worker().latest();
        ATLAS_REQUIRE(latest != nullptr, "no fields");
        REQUIRE(latest->size().width == a.width);
        const std::vector<std::uint8_t> alpha = latest->bytes_of(fx::Field::canopy);
        const std::array<fx::Argb, 256>& table = fx::canopy_table();
        const auto opacity = static_cast<double>(fx::canopy_opacity);
        // the fragment's alpha and colour at the image's levels, as the blender takes them, over the pixel there
        const double levels = levels_of(frames[0]->packed);
        const auto error_at = [&](std::size_t i, std::uint8_t a8, std::array<double, 3>& want) {
            const fx::Argb f = table.at(a8);
            const double fa = std::nearbyint(static_cast<double>(f >> 24U) / 255.0 * opacity * levels) / levels;
            const std::array<double, 3> got = levels_at(frames[0]->packed, i);
            const std::array<double, 3> bare = levels_at(frames[1]->packed, i);
            double worst = 0;
            double sum = 0;
            for (std::size_t k = 0; k < 3; ++k) {
                const auto c = static_cast<double>((f >> (16U - (8U * k))) & 0xFFU);
                const double src = std::nearbyint(c * opacity / 255.0 * levels) / levels * 255.0;
                want.at(k) = src + (bare.at(k) * (1 - fa));
                worst = std::max(worst, std::abs(got.at(k) - want.at(k)));
                sum += got.at(k) - want.at(k);
            }
            return std::array{worst, sum};
        };
        // within the rounding of a blender at the image's levels: its source's, its alpha's and its product's
        const double slack = 2.0 * 255.0 / levels;
        std::size_t under = 0;
        std::size_t shown = 0;
        std::size_t wrong = 0;
        std::size_t slipped = 0; ///< pixels the check would fail if the canopy read its field a pixel to the right
        double most = 0;
        double sum = 0;
        std::string first;
        for (std::size_t i = 0; i < inside.size(); ++i) {
            if (inside[i] == 0) continue;
            ++under;
            if ((table.at(alpha[i]) >> 24U) != 0) ++shown;
            std::array<double, 3> want{};
            const std::array<double, 2> e = error_at(i, alpha[i], want);
            std::array<double, 3> unused{};
            if (error_at(i, alpha[i + 1], unused)[0] > slack) ++slipped;
            most = std::max(most, e[0]);
            sum += e[1];
            if (e[0] <= slack) continue;
            if (wrong == 0) {
                const std::array<double, 3> got = levels_at(frames[0]->packed, i);
                first = std::format("pixel {} {}: {:.2f} {:.2f} {:.2f}, expected {:.2f} {:.2f} {:.2f} (alpha {})",
                                    i % a.width, i / a.width, got[0], got[1], got[2], want[0], want[1], want[2],
                                    int(alpha[i]));
            }
            ++wrong;
        }
        const double mean = sum / static_cast<double>(3 * std::max<std::size_t>(under, 1));
        const std::array<double, 3> through = mean_of(a, inside);
        const std::array<double, 3> black = mean_of(frames[2]->pixels, inside);
        const std::array<double, 3> bare = mean_of(b, inside);
        MESSAGE(std::format(
            "the canopy at {}: {} pixels under it whole, {} with some of it, {} not its composite (at "
            "most {:.2f} levels off, {:+.3f} on average; {} off with the field a pixel over); the woods "
            "#{:02X}{:02X}{:02X} through the filter, #{:02X}{:02X}{:02X} without it, "
            "#{:02X}{:02X}{:02X} black at .55",
            wood.name, under, shown, wrong, most, mean, slipped, int(through[0]), int(through[1]), int(through[2]),
            int(bare[0]), int(bare[1]), int(bare[2]), int(black[0]), int(black[1]), int(black[2])));
        CHECK_MESSAGE(under > 3000U, wood.name);
        CHECK_MESSAGE(shown > under / 4, wood.name);
        CHECK_MESSAGE(shown < under, wood.name);
        CHECK_MESSAGE(wrong == 0, first);
        // no lean either way, and a field read a pixel over would fail it
        CHECK(std::abs(mean) < 0.25 * 255.0 / levels);
        CHECK(slipped > shown / 10);
        // the structural frame keeps the flat black: its covered pixels are .45 of the bare land (the mask's own test)
        CHECK(black[1] < bare[1] * 0.5);
        // through the filter the woods are the bare land's tone, a little darker, never the black's
        CHECK(through[1] > bare[1] * 0.85);
        CHECK(through[1] <= bare[1] + 0.5);
    }
}

TEST_CASE("ground: in the app's map the canopy keeps its place on the ground as the camera pans") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the canopy is not drawn over the bundled packs here");
        return;
    }
    auto pack = test::open_pack((std::filesystem::path(dir) / "grachten.atlas").string());
    ATLAS_REQUIRE(pack && pack->head() != nullptr, "the canal ring's pack");
    const auto ladder = view::Ladder::of(*pack->head());
    ATLAS_REQUIRE(ladder.has_value(), "the canal ring's ladder");
    const double kx = ladder->frame().kx;
    auto session = test::open_session();
    if (!session) return;
    auto made = fx::FieldCache::create(*session->device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const auto settle = [&fields] { fields->worker().wait_idle(); };
    style::ThemeBlock day = test::sunny();
    day.shadow_opacity = 0.0;
    // Artis from above (the map flat) at a phone's quarter size, the materials' grain off (it reads the screen's field)
    constexpr std::uint32_t step = 48; // device px
    style::CameraState camera = test::s1_camera(woods[0].lat, woods[0].lon, 0.0, 0.0, 400.0);
    camera.width_px = 540;
    camera.height_px = 1200;
    camera.width_dp = 412;
    camera.height_dp = 916;
    camera.density = 540.0 / 412.0;
    camera.principal_y = 458.0;
    // the camera's eye and focal: a ground point east by d metres moves d · f / h CSS px left
    double focal = 0;
    double height = 0;
    for (const bool grounded : {true, false}) {
        // grounded: the app's map (no engine mode); else the renders' frames, the screen's field
        style::CameraState c = camera;
        if (grounded) c.flags &= ~style::camera_flag::engine_mode;
        const frame::FrameOptions options{.steps = 0, .grain = false, .grounded = grounded};
        std::array<std::optional<test::GroundDraw>, 4> frames;
        for (std::size_t k = 0; k < frames.size(); ++k) {
            style::CameraState at = c;
            if (k >= 2) {
                ATLAS_REQUIRE(focal > 0 && height > 0, "the first frame's camera");
                at.longitude += (static_cast<double>(step) / at.density) * height / focal / kx;
            }
            if (k % 2 == 1) at = without_canopy(at);
            frames.at(k) = test::draw_ground(
                *session, at, day, dir, "grachten",
                [&](passes::Ground& g, const frame::FrameState& state) {
                    if (k == 0 && state.view) {
                        focal = state.view->camera().focal();
                        height = state.view->camera().eye().y;
                        CHECK(g.canopy_filtered(state));
                        CHECK(state.grounded == grounded);
                    }
                },
                40, {}, frame::Mode::full, fields, settle, options);
            ATLAS_REQUIRE(frames.at(k).has_value(), "Artis was not drawn from above");
        }
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
        // where the canopy shows in both (its frame differs from the one without it) at the same ground: frame B's
        // pixel (x, y), frame A's (x + step, y)
        const gpu::Pixels& a = frames[0]->pixels;
        const gpu::Pixels& b = frames[2]->pixels;
        const auto shows = [](const gpu::Pixels& with, const gpu::Pixels& without, std::size_t i) {
            int d = 0;
            for (std::size_t k = 0; k < 3; ++k) {
                d = std::max(d, std::abs(int(with.rgba[(i * 4) + k]) - int(without.rgba[(i * 4) + k])));
            }
            return d >= 6;
        };
        std::size_t both = 0;
        std::size_t same = 0;
        const std::uint32_t w = a.width;
        for (std::uint32_t y = 0; y < a.height; ++y) {
            for (std::uint32_t x = 0; x + step < w; ++x) {
                const std::size_t ia = (std::size_t{y} * w) + x + step;
                const std::size_t ib = (std::size_t{y} * w) + x;
                if (!shows(a, frames[1]->pixels, ia) || !shows(b, frames[3]->pixels, ib)) continue;
                ++both;
                int d = 0;
                for (std::size_t k = 0; k < 3; ++k)
                    d = std::max(d, std::abs(int(a.rgba[(ia * 4) + k]) - int(b.rgba[(ib * 4) + k])));
                if (d <= 2) ++same;
            }
        }
        const double held = both > 0 ? static_cast<double>(same) / static_cast<double>(both) : 0.0;
        MESSAGE(std::format("the canopy {}: {} pixels show it at the same ground in both frames, {:.1f}% the same",
                            grounded ? "on the ground (the app's map)" : "at the screen's field (engine mode)", both,
                            held * 100));
        CHECK(both > 2000U);
        if (grounded) {
            CHECK(held > 0.97);
        } else {
            CHECK(held < 0.5);
        }
    }
}

#endif
