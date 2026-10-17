// The core's session (standin/Session.hpp) on the host's Vulkan, driven in VK bench's own order
// (MV/vk/VkBenchScreen.kt's execute): the matrix at the frame's 8 bits, a host switch, the pipelines' timing, then the
// colour stage over the configurations at 10 bits and at 8 beside them, with a window lost and back, a refused attach,
// a resize and a device loss inside it, mode 0, and the close; through offscreen windows (and, where the host has one,
// a headless surface, whose queries are the surface stage's). After each step the crash guard (gpu/Guard.hpp) holds
// what the step left open and nothing else: the colour stage exactly while something of 10 bits is alive, the frames
// stage over the first 120 frames only, no marker between steps after them, none after the close. ATLAS_SESSION_FULL=1
// takes the colour stage over every configuration, as VK bench does (the layer run's order, which notes the guard's
// marker at every driver call: every call is behind one).
#include "gpu/Guard.hpp"
#include "standin/City.hpp"
#include "standin/Session.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

using namespace atlas;

#if !defined(__ANDROID__)

namespace {

namespace fs = std::filesystem;

[[nodiscard]] std::string read(const fs::path& p) {
    std::ifstream in(p);
    std::ostringstream out;
    out << in.rdbuf();
    return out.str();
}

/// The stage the guard's marker on disk names ("" for none).
[[nodiscard]] std::string marked(const fs::path& dir) {
    const std::string text = read(dir / "guard");
    if (!text.starts_with("stage ")) return {};
    return text.substr(6, text.find('\n') - 6);
}

[[nodiscard]] bool has(std::string_view text, std::string_view part) { return text.find(part) != std::string::npos; }

[[nodiscard]] bool env_on(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before the test's threads
    return v != nullptr && std::string_view(v) == "1";
}

/// The configurations VK bench's matrix draws on this device (MV/vk/VkBench.kt's matrix): per configuration both forms
/// through each route, and off a tiler the render pass's composite straight into the 1x image.
[[nodiscard]] std::vector<gpu::Configuration> matrix(const gpu::Device& device) {
    const gpu::Facts& f = device.facts();
    std::vector<gpu::Configuration> out;
    for (const gpu::FrameConfig c :
         {gpu::FrameConfig::a, gpu::FrameConfig::b, gpu::FrameConfig::c, gpu::FrameConfig::x1}) {
        if (!f.supports(c)) continue;
        const bool one = c == gpu::FrameConfig::x1;
        for (const gpu::Route route : {gpu::Route::renderpass, gpu::Route::dynamic}) {
            if (route == gpu::Route::dynamic && !device.enabled().local_read) continue;
            out.push_back({.config = c,
                           .route = route,
                           .form = gpu::FrameForm::onepass,
                           .target = gpu::CompositeTarget::resolve});
            if (one) {
                out.push_back({.config = c,
                               .route = route,
                               .form = gpu::FrameForm::composite,
                               .target = gpu::CompositeTarget::direct});
            } else {
                out.push_back({.config = c,
                               .route = route,
                               .form = gpu::FrameForm::composite,
                               .target = gpu::CompositeTarget::resolve});
                if (route == gpu::Route::renderpass && !f.tiler) {
                    out.push_back({.config = c,
                                   .route = route,
                                   .form = gpu::FrameForm::composite,
                                   .target = gpu::CompositeTarget::direct});
                }
            }
        }
    }
    return out;
}

/// A guarded start in `dir`, as the façade starts (it presents), with this test's build and plant.
[[nodiscard]] gpu::StartOptions start_in(const fs::path& dir, std::string plant = {}) {
    return {.guard_dir = dir,
            .cache_file = dir / "pipelines.bin",
            .system = "host-test-system",
            .build = "session-test",
            .plant = std::move(plant),
            .validation = true,
            .require_validation = test::vulkan_required(),
            .present = true,
            .spirv = 42};
}

[[nodiscard]] standin::SessionOptions session_in(const fs::path& dir) {
    return {.cache_file = dir / "pipelines.bin",
            .spirv = 42,
            .ci_log = false,
            .city = std::make_shared<const standin::City>(standin::City::generate(32, 13.0))};
}

constexpr vk::Extent2D size{.width = 96, .height = 160};

/// Draws `n` frames of the board's view; how many went out.
int draw(standin::Session& session, int n) {
    const style::CameraState camera = standin::board_view(size.width, size.height, 1.0);
    int drawn = 0;
    for (int i = 0; i < n; ++i) {
        const standin::Drawn d = session.draw(camera, {});
        drawn += d.drawn ? 1 : 0;
        if (d.stale) session.remake();
    }
    return drawn;
}

constexpr std::uint32_t pills = standin::bench_flag::pills;
constexpr std::uint32_t ten = standin::bench_flag::ten_bit;

/// Whether the host's Vulkan opens the façade's start (a device that presents): else the test skips, saying why (the
/// arm64 runner's lavapipe has no window system), or fails where Vulkan is required and presentation expected.
[[nodiscard]] bool can_start(const fs::path& dir) {
    const auto probe = gpu::start_guarded(start_in(dir));
    if (probe) return true;
    if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, probe.error().reason);
    return false;
}

} // namespace

TEST_CASE("the core's session runs VK bench's order with every stage closed behind it, the colour stage holding "
          "10-bit work alone") {
    if (!test::open_session()) return; // no Vulkan here
    const fs::path dir = fs::temp_directory_path() / std::format("atlas-session-{}", ::getpid());
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir);
    if (!can_start(dir)) {
        fs::remove_all(dir, ec);
        return;
    }
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        standin::Session session(*started, session_in(dir));
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        CHECK(marked(dir).empty());

        // VK bench's first facts: Android's profiles read behind their own stage, after the start.
        const std::string facts = session.facts();
        CHECK(has(facts, "profiles: VP_ANDROID_"));
        CHECK(has(facts, "guard: clear\n"));
        CHECK(marked(dir).empty());

        // 1. The matrix at the frame's 8 bits: the first configuration draws past the first 120 frames, behind the
        // frames stage until then, the others a few frames each. Between steps nothing stays open.
        const std::vector<gpu::Configuration> configurations = matrix(*started->device);
        ATLAS_REQUIRE(!configurations.empty(), "the device draws no configuration");
        MESSAGE(configurations.size() << " configurations, the device picks "
                                      << gpu::name_of(started->device->facts().choice));
        ATLAS_REQUIRE(session.bench(1, configurations.front(), pills).empty(), "the first configuration");
        CHECK(draw(session, 60) == 60);
        CHECK(marked(dir) == "frames"); // the first 120 frames are guarded
        CHECK(draw(session, 61) == 61);
        CHECK(marked(dir).empty()); // and after them, nothing
        for (const gpu::Configuration& c : configurations) {
            const std::string refused = session.bench(1, c, pills);
            CHECK_MESSAGE(refused.empty(), gpu::name_of(c) << ": " << refused);
            CHECK(draw(session, 3) == 3);
            CHECK(marked(dir).empty());
            CHECK_FALSE(session.output()->colour().ten_bit());
        }

        // 2. A host switch: the window goes and another comes, the bench drawing the same configuration on it.
        session.detach();
        CHECK_FALSE(session.attached());
        CHECK(marked(dir).empty());
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        CHECK(session.drawing()); // the bench's renderer made again on the new window
        CHECK(draw(session, 3) == 3);
        CHECK(session.bench(1, configurations.front(), pills | standin::bench_flag::texture).empty());

        // 3. The pipelines' timing, as VK bench asks for it after the soak.
        const std::string timed = session.pipelines();
        CHECK_MESSAGE(timed.starts_with("vkbench pipelines: this launch "), timed);
        CHECK_FALSE(has(timed, "failed"));
        CHECK(marked(dir).empty());

        // 4. The colour stage, where the device draws the frame in 10 bits (lavapipe does, offscreen).
        const gpu::Facts& f = started->device->facts();
        if (!f.deepest.ten_bit()) {
            MESSAGE("the colour stage skipped: " << gpu::colour_stage(f));
        } else {
            const bool full = env_on("ATLAS_SESSION_FULL");
            const std::size_t cells = full ? configurations.size() : std::min<std::size_t>(3, configurations.size());
            for (std::size_t i = 0; i < cells; ++i) {
                const gpu::Configuration& c = configurations[i];
                for (const bool at_ten : (i % 2 == 0) ? std::vector{true, false} : std::vector{false, true}) {
                    const std::string refused = session.bench(1, c, pills | (at_ten ? ten : 0U));
                    ATLAS_REQUIRE(refused.empty(), gpu::name_of(c)
                                                       << (at_ten ? " at 10 bits: " : " at 8 bits: ") << refused);
                    CHECK(session.output()->colour().ten_bit() == at_ten);
                    // Open exactly while something of 10 bits is alive.
                    CHECK(session.colour_open() == at_ten);
                    CHECK(marked(dir) == (at_ten ? "colour" : ""));
                    CHECK(draw(session, 3) == 3);
                }
            }
            // Inside the stage again: the window goes (the stage closes with the 10-bit frame and output) and comes
            // back (it opens again); a step while it is gone changes nothing.
            ATLAS_REQUIRE(session.bench(1, configurations.front(), pills | ten).empty());
            CHECK(session.colour_open());
            session.detach();
            CHECK_FALSE(session.colour_open());
            CHECK(marked(dir).empty());
            CHECK(session.bench(1, configurations.front(), pills | ten) ==
                  "no surface is attached (the window went away)");
            CHECK(marked(dir).empty());
            // A window that comes back refused (its swapchain): nothing held, the stage closed, nothing on disk.
            CHECK_FALSE(session.attach(std::make_unique<test::OffscreenWindow>(1), size, why));
            CHECK(has(why, "planted: the driver refused the swapchain"));
            CHECK_FALSE(session.attached());
            CHECK_FALSE(session.colour_open());
            CHECK(marked(dir).empty());
            ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
            CHECK(session.colour_open());
            CHECK(session.output()->colour().ten_bit());
            CHECK(draw(session, 3) == 3);
            // A resize inside it: the 10-bit output made again, still inside.
            session.resize({.width = 120, .height = 200});
            CHECK(session.output()->extent().width == 120);
            CHECK(session.output()->colour().ten_bit());
            CHECK(marked(dir) == "colour");
            CHECK(draw(session, 3) == 3);
            session.resize(size);
            // A device loss inside it: the 10-bit frame and output go inside it, it closes, the device is made again
            // behind the start's stages, and the stage opens again for the 10-bit output and frame.
            session.lose_device();
            CHECK(session.drawing());
            CHECK(session.colour_open());
            CHECK(session.output()->colour().ten_bit());
            CHECK(marked(dir) == "colour");
            CHECK(draw(session, 3) == 3);
            CHECK(has(session.facts(), "profiles: VP_ANDROID_")); // read of the physical device, kept across the loss
            // Out of it as VK bench leaves it, in mode 0: nothing of 10 bits is left, and the stage is closed.
            CHECK(session.bench(0, configurations.front(), 0).empty());
            CHECK_FALSE(session.colour_open());
            CHECK_FALSE(session.output()->colour().ten_bit());
            CHECK(marked(dir).empty());
        }
        CHECK(session.bench(0, configurations.front(), 0).empty());
        const std::vector<std::string> messages = started->instance->messages().take();
        CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));

        // 5. The close: every stage behind it, its own last; nothing on disk after it.
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
        CHECK(started->device == nullptr);
        CHECK(started->instance == nullptr);
        CHECK(fs::exists(dir / "pipelines.bin")); // its last write
    }
    fs::remove_all(dir, ec);
}

TEST_CASE("on a headless surface the session's surface, its queries and its outputs are the surface stage's") {
    test::Session probe;
    probe.instance = test::open_instance({.validation = true, .require_validation = test::vulkan_required()});
    if (!probe.instance) return;
    if (!probe.instance->has_extension(vk::EXTHeadlessSurfaceExtensionName)) {
        MESSAGE("skipped: the loader offers no VK_EXT_headless_surface");
        return;
    }
    probe.instance.reset();
    const fs::path dir = fs::temp_directory_path() / std::format("atlas-session-headless-{}", ::getpid());
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir);
    if (!can_start(dir)) {
        fs::remove_all(dir, ec);
        return;
    }
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        standin::Session session(*started, session_in(dir));
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::HeadlessWindow>(test::headless_swapchains()), size, why),
                      why);
        CHECK(marked(dir).empty());
        const std::string facts = session.facts();
        CHECK(has(facts, "surface.formats: "));
        CHECK(has(facts, "surface.present: yes"));
        const gpu::Configuration c = started->device->facts().choice;
        ATLAS_REQUIRE(session.bench(1, c, pills).empty());
        CHECK(draw(session, 5) == 5);
        // The headless surface offers no 10 bits in sRGB: the colour stage refuses, opening nothing.
        const std::string refused = session.bench(1, c, pills | ten);
        if (!refused.empty()) {
            MESSAGE("the colour stage on a headless surface: " << refused);
            CHECK(refused.starts_with("no 10 bits here: "));
        }
        CHECK(session.bench(0, c, 0).empty());
        CHECK_FALSE(session.colour_open());
        session.detach();
        CHECK(marked(dir).empty());
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
    fs::remove_all(dir, ec);
}

#else

TEST_CASE("the core's session runs VK bench's order with every stage closed behind it, the colour stage holding "
          "10-bit work alone") {
    MESSAGE("skipped: the Android build has no offscreen output to draw the session's frames into");
}

#endif
