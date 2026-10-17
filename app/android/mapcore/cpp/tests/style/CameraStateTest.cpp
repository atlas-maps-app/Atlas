// The camera state's reader (style/CameraState.hpp), and its profile numbers against the packs'.
#include "style/CameraState.hpp"

#include "tiles/Sections.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstring>
#include <functional>
#include <limits>
#include <string_view>
#include <vector>

namespace {

using atlas::style::CameraError;
using atlas::style::CameraState;
using atlas::style::KeepOut;
using atlas::style::Profile;
namespace flag = atlas::style::camera_flag;

/// The Main board's camera on a 1080 × 2400 phone, at rest, with the home's two frosted pills.
CameraState home() {
    CameraState s{};
    s.version = 1;
    s.flags = flag::rest;
    s.sequence = 41;
    s.latitude = 52.3746;
    s.longitude = 4.884;
    s.bearing = 52.0;
    s.pitch = 46.0;
    s.distance = 2300.0;
    s.principal_x = 206.0;
    s.principal_y = 440.0;
    s.density = 2.625;
    s.width_px = 1080;
    s.height_px = 2400;
    s.width_dp = 411; // 1080 / 2.625 = 411.4, rounded down as MK/CameraState.kt does
    s.height_dp = 914;
    s.pack_id = 0x5a1f00c3e2d4b6a7ULL;
    s.profile = Profile::region;
    s.theme_revision = 3;
    s.labels_revision = 1;
    s.overlays_revision = 0;
    s.keep_out_count = 2;
    s.keep_out[0] = KeepOut{.left = 16.0F,
                            .top = 52.0F,
                            .right = 396.0F,
                            .bottom = 104.0F,
                            .radius = 26.0F,
                            .blur16 = 24 * 16,
                            .saturation = 180,
                            .flags = 1};
    s.keep_out[1] = KeepOut{.left = 16.0F,
                            .top = 840.0F,
                            .right = 396.0F,
                            .bottom = 896.0F,
                            .radius = 28.0F,
                            .blur16 = 24 * 16,
                            .saturation = 180,
                            .flags = 1};
    return s;
}

std::array<std::byte, 512> bytes(const CameraState& s) {
    std::array<std::byte, 512> out{};
    std::memcpy(out.data(), &s, sizeof s);
    return out;
}

CameraError refusal(const CameraState& s) {
    auto raw = bytes(s);
    auto r = atlas::style::read_camera_state(raw);
    REQUIRE_FALSE(r.has_value());
    return r.error();
}

bool accepted(const CameraState& s) {
    auto raw = bytes(s);
    return atlas::style::read_camera_state(raw).has_value();
}

/// One change to a good state, and the refusal it must meet.
struct Breakage {
    const char* what;
    std::function<void(CameraState&)> apply;
    CameraError error;
};

// The camera names its pack's profile as the pack's rhead does (tiles::Profile, K2 and K3), one up, with 0 for none.
static_assert(static_cast<std::uint32_t>(Profile::living) ==
              static_cast<std::uint32_t>(atlas::tiles::Profile::living) + 1);
static_assert(static_cast<std::uint32_t>(Profile::region) ==
              static_cast<std::uint32_t>(atlas::tiles::Profile::region) + 1);
static_assert(static_cast<std::uint32_t>(Profile::williamsburg) ==
              static_cast<std::uint32_t>(atlas::tiles::Profile::williamsburg) + 1);
static_assert(static_cast<std::uint32_t>(Profile::world_base) ==
              static_cast<std::uint32_t>(atlas::tiles::Profile::world_base) + 1);

} // namespace

TEST_CASE("a camera state reads back field by field, with its keep-out shapes") {
    const CameraState want = home();
    const auto raw = bytes(want);
    const auto got = atlas::style::read_camera_state(raw);
    REQUIRE(got.has_value());
    CHECK(bytes(*got) == raw);
    CHECK(got->has(flag::rest));
    REQUIRE(got->keep_outs().size() == 2);
    CHECK(got->keep_outs()[1].frosted());
    CHECK(got->keep_outs()[1].blur_dp() == 24.0F);
}

TEST_CASE("a camera state out of its ranges is refused, with the reason") {
    std::array<std::byte, 511> short_state{};
    CHECK(atlas::style::read_camera_state(short_state).error() == CameraError::size);
    const double nan = std::numeric_limits<double>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    const std::vector<Breakage> breakages = {
        {"version 0", [](CameraState& s) { s.version = 0; }, CameraError::version},
        {"an unknown flag", [](CameraState& s) { s.flags = 16; }, CameraError::flags},
        {"reserved", [](CameraState& s) { s.reserved = 7; }, CameraError::reserved},
        {"profile 5", [](CameraState& s) { s.profile = static_cast<Profile>(5); }, CameraError::profile},
        {"a NaN distance", [nan](CameraState& s) { s.distance = nan; }, CameraError::not_finite},
        {"latitude 1000", [](CameraState& s) { s.latitude = 1000.0; }, CameraError::target},
        {"latitude -90.5", [](CameraState& s) { s.latitude = -90.5; }, CameraError::target},
        {"longitude 181", [](CameraState& s) { s.longitude = 181.0; }, CameraError::target},
        {"pitch -90", [](CameraState& s) { s.pitch = -90.0; }, CameraError::view},
        {"pitch 91", [](CameraState& s) { s.pitch = 91.0; }, CameraError::view},
        {"bearing 1e300", [](CameraState& s) { s.bearing = 1e300; }, CameraError::view},
        {"distance 1e-300", [](CameraState& s) { s.distance = 1e-300; }, CameraError::view},
        {"distance 0.5 m", [](CameraState& s) { s.distance = 0.5; }, CameraError::view},
        {"distance 2e9 m", [](CameraState& s) { s.distance = 2e9; }, CameraError::view},
        {"density 0", [](CameraState& s) { s.density = 0.0; }, CameraError::frame},
        {"density 20", [](CameraState& s) { s.density = 20.0; }, CameraError::frame},
        {"no pixels", [](CameraState& s) { s.height_px = 0; }, CameraError::frame},
        {"20000 px", [](CameraState& s) { s.width_px = 20000; }, CameraError::frame},
        {"1080 px as 7 dp", [](CameraState& s) { s.width_dp = 7; }, CameraError::frame},
        {"dp sizes a density too large", [](CameraState& s) { s.height_dp = 916; }, CameraError::frame},
        {"the principal point far right", [](CameraState& s) { s.principal_x = 900.0; }, CameraError::principal},
        {"the principal point far above", [](CameraState& s) { s.principal_y = -1000.0; }, CameraError::principal},
        {"17 shapes", [](CameraState& s) { s.keep_out_count = 17; }, CameraError::keep_outs},
        {"a shape with no area", [](CameraState& s) { s.keep_out[0].right = s.keep_out[0].left; },
         CameraError::keep_outs},
        {"an unknown shape flag", [](CameraState& s) { s.keep_out[0].flags = 2; }, CameraError::keep_outs},
        {"an infinite shape", [inf](CameraState& s) { s.keep_out[0].top = inf; }, CameraError::not_finite},
        {"a shape far off the frame", [](CameraState& s) { s.keep_out[1].bottom = 5000.0F; }, CameraError::keep_outs},
        {"a radius past half the shape", [](CameraState& s) { s.keep_out[0].radius = 40.0F; }, CameraError::keep_outs},
        {"a negative radius", [](CameraState& s) { s.keep_out[0].radius = -1.0F; }, CameraError::keep_outs},
        {"a blur of 300 dp", [](CameraState& s) { s.keep_out[0].blur16 = 300 * 16; }, CameraError::keep_outs},
        {"a stale shape in an unused slot", [](CameraState& s) { s.keep_out[5].left = 1.0F; }, CameraError::reserved},
    };
    for (const Breakage& k : breakages) {
        CameraState s = home();
        k.apply(s);
        // refusal() asserts, so no message may call it: doctest writes a message while it holds its lock
        INFO(std::string_view(k.what));
        const CameraError error = refusal(s);
        CHECK_MESSAGE(error == k.error, std::string_view(atlas::style::name_of(error)));
    }
    // what the app and the bench write is accepted: dp rounded either way, a bearing past a turn while orbiting, the
    // map seen straight down or at its steepest, a shape half off the frame, a pill's radius of half its height
    CameraState s = home();
    s.width_dp = 412;
    s.bearing = 378.0;
    s.pitch = 90.0;
    s.keep_out[0].left = -30.0F;
    s.keep_out[1].radius = 28.0F;
    CHECK(accepted(s));
    s = home();
    s.bearing = -2.0;
    s.pitch = 0.0;
    s.keep_out_count = 0;
    s.keep_out = {};
    CHECK(accepted(s));
}
