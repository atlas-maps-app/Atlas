// The core's session (standin/Session.hpp) on the host's Vulkan, driven in VK bench's own order
// (MV/vk/VkBenchScreen.kt's execute): the matrix at the frame's 8 bits, a host switch, the pipelines' timing, then the
// colour stage over the configurations at 10 bits and at 8 beside them, with a window lost and back, a refused attach,
// a resize and a device loss inside it, mode 0, and the close; through offscreen windows (and, where the host has one,
// a headless surface, whose queries are the surface stage's). After each step the crash guard (gpu/Guard.hpp) holds
// what the step left open and nothing else: the colour stage exactly while something of 10 bits is alive, the frames
// stage over each renderer's first 120 frames only, never while the frame loop rests and always naming the frame drawn
// now (a switch within the last frame's first 120 included), no marker between steps after them, none after the close
// nor while the frame loop rests (a trim from TRIM_MEMORY_UI_HIDDEN among its reasons, inside the colour stage too);
// the marker never names a module, a pipeline or a thing going once its call has returned, and names VK bench's cell
// whenever the frame is one the facts would not pick; a cell the guard turned off is refused, the pick never; mode 0
// lets go of everything whatever it names and makes nothing; and the pipeline cache is written back only where a
// pipeline was made through it since its last write. ATLAS_SESSION_FULL=1 takes the colour stage over every
// configuration, as VK bench does (the layer run's order, which notes the guard's marker at every driver call: every
// call is behind one, and named only while it runs).
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
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <tuple>
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

/// What the marker on disk names besides its stage: a module, a pipeline or a thing going ("" for none). Between the
/// session's calls nothing is being made or destroyed, so it is always "".
[[nodiscard]] std::string named(const fs::path& dir) {
    const std::string text = read(dir / "guard");
    const std::size_t first = text.find('\n');
    if (first == std::string::npos) return {};
    const std::string line = text.substr(first + 1, text.find('\n', first + 1) - first - 1);
    return line.starts_with("pipeline ") || line.starts_with("module ") || line.starts_with("release ") ? line
                                                                                                        : std::string();
}

/// The value of the marker's line `key …` on disk ("" for none, or no marker).
[[nodiscard]] std::string marker_field(const fs::path& dir, std::string_view key) {
    const std::string text = read(dir / "guard");
    const std::string head = std::format("\n{} ", key);
    const std::size_t at = text.find(head);
    if (at == std::string::npos) return {};
    const std::size_t from = at + head.size();
    return text.substr(from, text.find('\n', from) - from);
}

/// The frame the frames stage's marker on disk names as drawn (its `drawing …` line), "" for none.
[[nodiscard]] std::string drawing(const fs::path& dir) { return marker_field(dir, "drawing"); }

/// The VK bench cell the marker on disk names (its `cell …` line: a configuration the facts would not pick), "" for
/// none.
[[nodiscard]] std::string cell_in(const fs::path& dir) { return marker_field(dir, "cell"); }

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
            .force_local_read = true, // both routes for coverage (tests/gpu/gpu_fixture.hpp's open_session says why)
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

/// A fresh guard folder for a test case, removed when it ends.
class Folder {
public:
    explicit Folder(std::string_view name)
        : path_(fs::temp_directory_path() / std::format("atlas-session-{}-{}", name, ::getpid())) {
        std::error_code ec;
        fs::remove_all(path_, ec);
        fs::create_directories(path_);
    }
    Folder(const Folder&) = delete;
    Folder& operator=(const Folder&) = delete;
    Folder(Folder&&) = delete;
    Folder& operator=(Folder&&) = delete;
    ~Folder() {
        std::error_code ec;
        fs::remove_all(path_, ec);
    }
    [[nodiscard]] const fs::path& path() const noexcept { return path_; }

private:
    fs::path path_;
};

/// Whether this device draws the frame in 10 bits offscreen (lavapipe does): the colour stage's tests need it.
[[nodiscard]] bool ten_bits_here(const gpu::Started& started) {
    const gpu::Facts& f = started.device->facts();
    if (f.deepest.ten_bit()) return true;
    MESSAGE("skipped: no 10 bits here (" << gpu::colour_stage(f) << ")");
    return false;
}

} // namespace

TEST_CASE("the core's session runs VK bench's order with every stage closed behind it, the colour stage holding "
          "10-bit work alone") {
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("order");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
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

        // 1. The matrix at the frame's 8 bits: the first configuration draws past its first 120 frames, behind the
        // frames stage until then; each other configuration's first frames are behind it again (a new frame's first
        // draws). Nothing is named between steps.
        const std::vector<gpu::Configuration> configurations = matrix(*started->device);
        ATLAS_REQUIRE(!configurations.empty(), "the device draws no configuration");
        const gpu::Configuration pick = started->device->facts().choice;
        MESSAGE(configurations.size() << " configurations, the device picks " << gpu::name_of(pick));
        // A configuration the facts would not pick is a VK bench cell: the marker of its frame's work names it.
        const auto cell_of = [&](const gpu::Configuration& c) { return c == pick ? std::string() : gpu::name_of(c); };
        ATLAS_REQUIRE(session.bench(1, configurations.front(), pills).empty(), "the first configuration");
        CHECK(named(dir).empty());
        CHECK(draw(session, 60) == 60);
        CHECK(marked(dir) == "frames"); // the first 120 frames are guarded
        CHECK(named(dir).empty());
        CHECK(cell_in(dir) == cell_of(configurations.front()));
        CHECK(draw(session, 61) == 61);
        CHECK(marked(dir).empty()); // and after them, nothing
        for (const gpu::Configuration& c : configurations) {
            const std::string refused = session.bench(1, c, pills);
            CHECK_MESSAGE(refused.empty(), gpu::name_of(c) << ": " << refused);
            CHECK(named(dir).empty());
            CHECK(draw(session, 3) == 3);
            // A configuration other than the one drawn: a new frame, whose first draws the frames stage holds.
            CHECK(marked(dir) == (c == configurations.front() ? "" : "frames"));
            CHECK(named(dir).empty());
            CHECK(cell_in(dir) == (c == configurations.front() ? std::string() : cell_of(c)));
            CHECK_FALSE(session.output()->colour().ten_bit());
        }
        // The frame loop at rest: the frames stage ends, and the next draw opens it again.
        session.rest();
        CHECK(marked(dir).empty());
        CHECK(draw(session, 1) == 1);
        CHECK(marked(dir) == "frames");

        // 2. A host switch: the window goes and another comes, the bench drawing the same configuration on it.
        session.detach();
        CHECK_FALSE(session.attached());
        CHECK(marked(dir).empty());
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        CHECK(session.drawing()); // the bench's renderer made again on the new window
        CHECK(named(dir).empty());
        CHECK(draw(session, 3) == 3);
        CHECK(marked(dir) == "frames"); // a new renderer: its first frames guarded
        CHECK(session.bench(1, configurations.front(), pills | standin::bench_flag::texture).empty());

        // 3. The pipelines' timing, as VK bench asks for it after the soak: its caches in the cache stage, its stand-in
        // in the pipelines stage, each named only as it goes.
        const std::string timed = session.pipelines();
        CHECK_MESSAGE(timed.starts_with("vkbench pipelines: this launch "), timed);
        CHECK_FALSE(has(timed, "failed"));
        CHECK(named(dir).empty());
        CHECK(draw(session, 130) == 130);
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
                    CHECK(named(dir).empty());
                    CHECK(draw(session, 3) == 3);
                    CHECK(marked(dir) == (at_ten ? "colour" : "frames"));
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
            // The window back: the stage opens again for its 10-bit output and frame.
            ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
            CHECK(session.colour_open());
            CHECK(session.output()->colour().ten_bit());
            CHECK(named(dir).empty());
            CHECK(draw(session, 3) == 3);
            session.detach();
            // A window that comes back refused (its swapchain, at 10 bits and at the 8 made in their place): nothing
            // held, the stage closed, nothing on disk. The next window draws at 8, and the next 10-bit step opens the
            // stage again on it.
            CHECK_FALSE(session.attach(std::make_unique<test::OffscreenWindow>(2), size, why));
            CHECK(has(why, "planted: the driver refused the swapchain"));
            CHECK_FALSE(session.attached());
            CHECK_FALSE(session.colour_open());
            CHECK(marked(dir).empty());
            auto last = std::make_unique<test::OffscreenWindow>();
            const test::OffscreenWindow* w = last.get();
            ATLAS_REQUIRE(session.attach(std::move(last), size, why), why);
            CHECK_FALSE(session.colour_open());
            CHECK_FALSE(session.output()->colour().ten_bit());
            ATLAS_REQUIRE(session.bench(1, configurations.front(), pills | ten).empty());
            CHECK(session.colour_open());
            CHECK(session.output()->colour().ten_bit());
            CHECK(named(dir).empty());
            CHECK(draw(session, 3) == 3);
            // A resize inside it: the 10-bit output made again, still inside.
            session.resize({.width = 120, .height = 200});
            CHECK(session.output()->extent().width == 120);
            CHECK(session.output()->colour().ten_bit());
            CHECK(marked(dir) == "colour");
            CHECK(named(dir).empty());
            CHECK(draw(session, 3) == 3);
            session.resize(size);
            // A device loss inside it: the 10-bit frame and output go inside it, it closes, the device is made again
            // behind the start's stages, and the stage opens again for the 10-bit output and frame.
            session.lose_device();
            CHECK(session.drawing());
            CHECK(session.colour_open());
            CHECK(session.output()->colour().ten_bit());
            CHECK(marked(dir) == "colour");
            CHECK(named(dir).empty());
            CHECK(draw(session, 3) == 3);
            CHECK(has(session.facts(), "profiles: VP_ANDROID_")); // read of the physical device, kept across the loss
            // Out of it as VK bench leaves it, in mode 0: nothing of 10 bits is left, the stage is closed, and no
            // output is made in its place (the next step that draws makes the one it needs).
            const int made = w->made();
            CHECK(session.bench(0, configurations.front(), 0).empty());
            CHECK_FALSE(session.colour_open());
            CHECK(session.output() == nullptr);
            CHECK(w->made() == made);
            CHECK(marked(dir).empty());
        }
        CHECK(session.bench(0, configurations.front(), 0).empty());
        CHECK(marked(dir).empty());
        const std::vector<std::string> messages = started->instance->messages().take();
        CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));

        // 5. The close: every stage behind it, its own last; nothing on disk after it.
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
        CHECK(started->device == nullptr);
        CHECK(started->instance == nullptr);
        CHECK(fs::exists(dir / "pipelines.bin")); // its last write
    }
}

TEST_CASE("each renderer's first frames are the frames stage's: a new configuration, a new size, a rebuilt device") {
    // D5.10 guards the first draws, where drivers crash: of every frame the core makes, not of the session's first
    // alone, so a crash in VK bench's second configuration's first frame, or in the first frame after a device loss,
    // leaves a marker. The stage ends after a renderer's 120 frames, when the frame loop rests and with the renderer.
    if (!test::open_session()) return;
    const Folder folder("frames");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        standin::Session session(*started, session_in(dir));
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        const std::vector<gpu::Configuration> configurations = matrix(*started->device);
        ATLAS_REQUIRE(configurations.size() >= 2, "the device draws " << configurations.size() << " configurations");
        ATLAS_REQUIRE(session.bench(1, configurations[0], pills).empty());
        CHECK(draw(session, 120) == 120);
        CHECK(marked(dir).empty());
        // A second configuration: its first frame is guarded, until its own 120th, the marker naming the frame drawn.
        ATLAS_REQUIRE(session.bench(1, configurations[1], pills).empty());
        CHECK(marked(dir).empty()); // nothing drawn yet
        CHECK(draw(session, 1) == 1);
        CHECK(marked(dir) == "frames");
        CHECK(has(read(dir / "guard"),
                  std::format("\ndrawing frame (configuration {} rgba8)\n", gpu::name_of(configurations[1]))));
        CHECK(draw(session, 118) == 118);
        CHECK(marked(dir) == "frames");
        CHECK(draw(session, 1) == 1);
        CHECK(marked(dir).empty());
        // A new size: the frame made again, its first frames guarded.
        session.resize({.width = 100, .height = 150});
        CHECK(draw(session, 1) == 1);
        CHECK(marked(dir) == "frames");
        // At rest: the stage ends, and opens again at the next draw (still within the 120).
        session.rest();
        CHECK(marked(dir).empty());
        CHECK(draw(session, 1) == 1);
        CHECK(marked(dir) == "frames");
        CHECK(draw(session, 118) == 118);
        CHECK(marked(dir).empty());
        // A device lost and made again: the rebuilt renderer's first frames are guarded.
        session.lose_device();
        CHECK(session.drawing());
        CHECK(marked(dir).empty());
        CHECK(draw(session, 1) == 1);
        CHECK(marked(dir) == "frames");
        // The renderer gone (mode 0): the stage ends with it.
        CHECK(session.bench(0, configurations[0], 0).empty());
        CHECK(marked(dir).empty());
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
}

TEST_CASE("the frames stage's marker names the frame drawn now, after a switch within the last frame's first 120 too") {
    // VK bench's CI cells draw far fewer than 120 frames (0.8 s), and so does a phone's cell below 30 frames a second,
    // so the next configuration comes while the last one's frames stage is still open. That stage ends with the frame
    // it guarded: nothing of it is on disk until the new frame draws (inside the colour stage, the colour stage's
    // marker alone, naming no frame), and then its marker names the configuration and colour drawn now, at 8 bits and
    // at 10. A frame made again for a new size, within its first 120, the same.
    if (!test::open_session()) return;
    const Folder folder("named");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        standin::Session session(*started, session_in(dir));
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        const std::vector<gpu::Configuration> configurations = matrix(*started->device);
        ATLAS_REQUIRE(configurations.size() >= 2, "the device draws " << configurations.size() << " configurations");
        const bool ten_here = started->device->facts().deepest.ten_bit();
        if (!ten_here) MESSAGE("at 8 bits alone: " << gpu::colour_stage(started->device->facts()));
        for (const bool at_ten : ten_here ? std::vector{false, true} : std::vector{false}) {
            const std::uint32_t flags = pills | (at_ten ? ten : 0U);
            const std::string between = at_ten ? "colour" : ""; // what is on disk before the new frame draws
            const std::string stage = at_ten ? "colour" : "frames";
            for (const gpu::Configuration& c : {configurations[0], configurations[1], configurations[0]}) {
                ATLAS_REQUIRE(session.bench(1, c, flags).empty(), gpu::name_of(c) << (at_ten ? " at 10 bits" : ""));
                CHECK(marked(dir) == between);
                CHECK(drawing(dir).empty());
                CHECK(draw(session, 3) == 3);
                const std::string frame = std::format("frame (configuration {} {})", gpu::name_of(c),
                                                      gpu::colour_name(session.output()->format()));
                CHECK(marked(dir) == stage);
                CHECK_MESSAGE(drawing(dir) == frame, "the marker: " << read(dir / "guard"));
                CHECK(session.output()->colour().ten_bit() == at_ten);
            }
            // A new size within the frame's first 120: made again, the stage ends, and the next draw names it.
            session.resize({.width = 120, .height = 200});
            CHECK(marked(dir) == between);
            CHECK(drawing(dir).empty());
            CHECK(draw(session, 2) == 2);
            CHECK(marked(dir) == stage);
            CHECK(has(drawing(dir), std::format("(configuration {} ", gpu::name_of(configurations[0]))));
            session.resize(size);
        }
        CHECK(session.bench(0, configurations[0], 0).empty());
        CHECK(marked(dir).empty());
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
}

TEST_CASE("the pipeline cache is written back only where a pipeline was made through it since its last write") {
    // A trim at each level from TRIM_MEMORY_UI_HIDDEN on (BACKGROUND, MODERATE and COMPLETE come as the system makes
    // ready to kill cached processes), the close and the session's first 120 frames ask for a write: each writes only
    // what is new, else nothing, behind no marker.
    if (!test::open_session()) return;
    const Folder folder("cache");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        standin::Session session(*started, session_in(dir));
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        const std::vector<gpu::Configuration> configurations = matrix(*started->device);
        ATLAS_REQUIRE(configurations.size() >= 2, "the device draws " << configurations.size() << " configurations");
        ATLAS_REQUIRE(session.bench(1, configurations[0], pills).empty());
        CHECK(fs::exists(dir / "pipelines.bin")); // written after the renderer's pipelines were made
        const fs::path file = dir / "pipelines.bin";
        fs::remove(file);
        session.save_cache(); // nothing new: nothing written
        CHECK_FALSE(fs::exists(file));
        CHECK(draw(session, 121) == 121); // the session's first 120 frames ask for one too
        CHECK_FALSE(fs::exists(file));
        CHECK(marked(dir).empty());
        // Another configuration's pipelines: written back as soon as they are made (the write that first holds a VK
        // bench cell's pipelines is the cell's, under its marker), and nothing is new after it.
        ATLAS_REQUIRE(session.bench(1, configurations[1], pills).empty());
        CHECK(fs::exists(file));
        fs::remove(file);
        session.save_cache();
        CHECK_FALSE(fs::exists(file));
        // The close writes nothing new either.
        session.close();
        CHECK_FALSE(fs::exists(file));
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
}

TEST_CASE("the colour stage closes as soon as nothing of 10 bits is left: refused outputs, a refused remake, a refused "
          "rebuild") {
    // A 10-bit swapchain the driver refuses: the stage closes, and the frame's 8-bit one is made in its place outside
    // it (so the stage never holds 8-bit work); VK bench is told there are no 10 bits here, and is not refused again on
    // this window until it is attached again or the device made again (each reads the surface afresh). A remake refused
    // inside the stage leaves nothing of 10 bits (the old output and the frame went), so the stage closes; the next
    // step opens it again. A device lost inside the stage and made again with its 10-bit output refused goes on at 8
    // bits outside it, or, with the 8-bit one refused too, turns the session off with nothing open and no marker on
    // disk.
    if (!test::open_session()) return;
    const Folder folder("colour");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        standin::Session session(*started, session_in(dir));
        if (!ten_bits_here(*started)) return;
        const gpu::Configuration c = started->device->facts().choice;
        std::string why;

        // 1. The 10-bit output refused as the stage opens: closed, the 8-bit output made outside it.
        auto refusing = std::make_unique<test::OffscreenWindow>(1, true);
        ATLAS_REQUIRE(session.attach(std::move(refusing), size, why), why); // at 8 bits: not refused
        ATLAS_REQUIRE(session.bench(1, c, pills).empty());
        CHECK(draw(session, 2) == 2);
        why = session.bench(1, c, pills | ten);
        MESSAGE("the 10-bit output refused: " << why);
        CHECK(why.starts_with("no 10 bits here: the driver refused a swapchain of "));
        CHECK(has(why, "planted: the driver refused the 10-bit swapchain"));
        CHECK_FALSE(session.colour_open());
        ATLAS_REQUIRE(session.output() != nullptr);
        CHECK_FALSE(session.output()->colour().ten_bit());
        CHECK(marked(dir).empty());
        CHECK(has(session.facts(), "colour.stage: none (the driver refused a swapchain of "));
        // Not tried again on this window while it stays: the next 10-bit step is told at once, nothing made.
        CHECK(session.bench(1, c, pills | ten).starts_with("no 10 bits here: the driver refused"));
        CHECK_FALSE(session.colour_open());
        // The 8-bit steps draw as before, outside the stage.
        CHECK(session.bench(1, c, pills).empty());
        CHECK(draw(session, 2) == 2);
        CHECK(marked(dir) == "frames");
        CHECK_FALSE(session.colour_open());
        session.detach();
        CHECK(marked(dir).empty());

        // 2. A remake refused inside the stage (a resize): the 10-bit frame and output gone, the stage closed, nothing
        // on disk; the next step opens it again with a 10-bit output.
        auto window = std::make_unique<test::OffscreenWindow>();
        test::OffscreenWindow* w = window.get();
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why); // a new window: 10 bits tried again
        ATLAS_REQUIRE(session.bench(1, c, pills | ten).empty(), "10 bits on a new window");
        CHECK(session.colour_open());
        CHECK(draw(session, 2) == 2);
        w->refuse(1);
        session.resize({.width = 120, .height = 200});
        CHECK(session.output() == nullptr);
        CHECK_FALSE(session.drawing());
        CHECK_FALSE(session.colour_open());
        CHECK(marked(dir).empty());
        ATLAS_REQUIRE(session.bench(1, c, pills | ten).empty(), "10 bits again after a refused remake");
        CHECK(session.colour_open());
        CHECK(session.output()->colour().ten_bit());
        CHECK(draw(session, 2) == 2);
        CHECK(marked(dir) == "colour");

        // 3. A device lost inside the stage, made again with its 10-bit output refused: on at 8 bits, outside it.
        w->refuse(1);
        session.lose_device();
        CHECK(session.drawing());
        CHECK_FALSE(session.colour_open());
        CHECK_FALSE(session.output()->colour().ten_bit());
        CHECK(draw(session, 2) == 2);
        CHECK(marked(dir) == "frames");

        // 4. Into the stage again, then a device lost with every output refused: the session is off, nothing open,
        // nothing on disk, and VK bench's mode 0 has nothing left to close.
        session.detach();
        auto last = std::make_unique<test::OffscreenWindow>();
        test::OffscreenWindow* l = last.get();
        ATLAS_REQUIRE(session.attach(std::move(last), size, why), why);
        ATLAS_REQUIRE(session.bench(1, c, pills | ten).empty(), "10 bits on the last window");
        CHECK(session.colour_open());
        l->refuse(2);
        session.lose_device();
        CHECK_FALSE(session.drawing());
        CHECK_FALSE(session.colour_open());
        CHECK(marked(dir).empty());
        CHECK(
            has(session.facts(), "vulkan.session: off (the swapchain could not be made again after the device's loss"));
        CHECK_FALSE(session.bench(0, c, 0).empty()); // the session is off
        CHECK(marked(dir).empty());
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
}

TEST_CASE("on a headless surface the session's surface, its queries and its outputs are the surface stage's") {
    test::Session probe;
    probe.instance = test::open_instance({.validation = true, .require_validation = test::vulkan_required()});
    if (!probe.instance) return;
    if (!probe.instance->has_extension(vk::EXTHeadlessSurfaceExtensionName)) {
        test::cannot_present(*probe.instance, "the loader offers no VK_EXT_headless_surface");
        return;
    }
    probe.instance.reset();
    const Folder folder("headless");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
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
        CHECK(named(dir).empty());
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
}

TEST_CASE("VK bench's mode 0 lets go of the frame and of 10 bits whatever configuration it names, and makes nothing") {
    // VkBenchScreen calls mode 0 as the app leaves (its pause, at ON_STOP) and at the run's end. Whatever configuration
    // it names, one this device lacks included, the renderer goes, and inside the colour stage the 10-bit output too,
    // the stage closing after them; no output is made in their place, so nothing reaches the driver while the app
    // leaves but what goes (on Android 14 the SurfaceView keeps its window in the background, and a swapchain made then
    // would be the surface stage's for nothing). The next step that draws makes the output it needs.
    if (!test::open_session()) return;
    const Folder folder("mode0");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        standin::Session session(*started, session_in(dir));
        if (!ten_bits_here(*started)) return;
        const gpu::Configuration c = started->device->facts().choice;
        const gpu::Configuration lacking{.config = gpu::FrameConfig::d}; // FA's, not built: no device has it
        auto window = std::make_unique<test::OffscreenWindow>();
        const test::OffscreenWindow* w = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        CHECK(session.bench(1, lacking, pills) == "this device has no configuration D"); // a step is refused it
        // At 8 bits: the renderer goes, the output stays, nothing is made.
        ATLAS_REQUIRE(session.bench(1, c, pills).empty());
        CHECK(draw(session, 2) == 2);
        int made = w->made();
        CHECK(session.bench(0, lacking, 0).empty());
        CHECK_FALSE(session.drawing());
        CHECK(session.output() != nullptr);
        CHECK(w->made() == made);
        CHECK(marked(dir).empty());
        // Inside the colour stage: the 10-bit frame and output go, the stage closes, and no 8-bit output is made in
        // their place, though the configuration named is one the device lacks.
        ATLAS_REQUIRE(session.bench(1, c, pills | ten).empty());
        CHECK(session.colour_open());
        CHECK(draw(session, 2) == 2);
        CHECK(marked(dir) == "colour");
        made = w->made();
        CHECK(session.bench(0, lacking, 0).empty());
        CHECK_FALSE(session.colour_open());
        CHECK(session.output() == nullptr);
        CHECK(w->made() == made);
        CHECK(marked(dir).empty());
        CHECK_FALSE(session.drawing());
        // The next step that draws makes its output, at the depth it asks for.
        ATLAS_REQUIRE(session.bench(1, c, pills).empty());
        CHECK(w->made() == made + 1);
        CHECK_FALSE(session.output()->colour().ten_bit());
        CHECK(draw(session, 2) == 2);
        ATLAS_REQUIRE(session.bench(1, c, pills | ten).empty());
        CHECK(session.output()->colour().ten_bit());
        CHECK(session.bench(0, c, 0).empty());
        CHECK_FALSE(session.colour_open());
        const std::vector<std::string> messages = started->instance->messages().take();
        CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
}

TEST_CASE("the frame loop at rest leaves no marker on disk, inside VK bench's colour stage too: a trim from "
          "TRIM_MEMORY_UI_HIDDEN rests it") {
    // So that a routine kill of a cached process rarely finds a marker (D5.10): the frame loop rests when nothing
    // changes and when the app's windows are hidden (a trim at TRIM_MEMORY_UI_HIDDEN, 20, or deeper: BACKGROUND 40,
    // MODERATE 60 and COMPLETE 80 come as the system makes ready to kill cached processes). At rest the frames stage
    // ends, and inside VK bench's colour stage the stage is left as mode 0 leaves it: its 10-bit frame and output go
    // and it closes, so the core alone leaves no marker behind, whatever VK bench's own pause does. A trim below
    // UI_HIDDEN (the app in the foreground) only writes back what is new.
    if (!test::open_session()) return;
    const Folder folder("rest");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    {
        standin::Session session(*started, session_in(dir));
        if (!ten_bits_here(*started)) return;
        const gpu::Configuration c = started->device->facts().choice;
        auto window = std::make_unique<test::OffscreenWindow>();
        const test::OffscreenWindow* w = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        // At 8 bits: a trim in the foreground goes on drawing; from UI_HIDDEN the frames stage ends, and the frame and
        // its output stay for the frame loop's return.
        ATLAS_REQUIRE(session.bench(1, c, pills).empty());
        CHECK(draw(session, 2) == 2);
        CHECK(marked(dir) == "frames");
        CHECK_FALSE(session.trim(15)); // TRIM_MEMORY_RUNNING_CRITICAL
        CHECK(marked(dir) == "frames");
        CHECK(session.trim(20));
        CHECK(marked(dir).empty());
        CHECK(session.drawing());
        CHECK(draw(session, 1) == 1);
        CHECK(marked(dir) == "frames"); // the frame loop back, within the frame's first 120
        // Inside the colour stage: the frame loop at rest leaves it, as mode 0 does, and nothing is made.
        ATLAS_REQUIRE(session.bench(1, c, pills | ten).empty());
        CHECK(draw(session, 2) == 2);
        CHECK(marked(dir) == "colour");
        int made = w->made();
        session.rest();
        CHECK_FALSE(session.colour_open());
        CHECK_FALSE(fs::exists(dir / "guard"));
        CHECK(session.output() == nullptr);
        CHECK_FALSE(session.drawing());
        CHECK(w->made() == made);
        // VK bench's next 10-bit step opens it again.
        ATLAS_REQUIRE(session.bench(1, c, pills | ten).empty());
        CHECK(session.colour_open());
        CHECK(draw(session, 2) == 2);
        // A trim in the foreground inside it changes nothing; each from UI_HIDDEN leaves nothing on disk, the first
        // leaving the stage, and none writes the pipeline cache (nothing new: the stage's pipelines went through its
        // own).
        CHECK_FALSE(session.trim(15));
        CHECK(session.colour_open());
        CHECK(marked(dir) == "colour");
        const fs::path file = dir / "pipelines.bin";
        ATLAS_REQUIRE(fs::exists(file), "the 8-bit frame's pipelines were written back");
        const auto written = fs::last_write_time(file);
        made = w->made();
        for (const int level : {20, 40, 60, 80}) {
            CHECK(session.trim(level));
            CHECK_FALSE(session.colour_open());
            CHECK_FALSE(fs::exists(dir / "guard"));
        }
        CHECK(fs::last_write_time(file) == written);
        CHECK(w->made() == made);
        CHECK(session.bench(0, c, 0).empty());
        const std::vector<std::string> messages = started->instance->messages().take();
        CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
}

TEST_CASE("VK bench's cells: the marker of a cell's work names it, the pick's never; a cell turned off is refused, the "
          "pick never") {
    // VK bench draws configurations, routes, forms and targets the facts would not pick, to measure them (its cells):
    // a crash in a cell's pipelines, its first 120 frames, its going or the pipeline cache's write after its pipelines
    // proves only that cell bad on that driver, so its marker names the cell (gpu/Guard.hpp), and the pick's (the
    // map's frame) never does. A cell the guard turned off is refused before anything is made for it, VK bench going on
    // without it; an off line can never refuse the pick, which follows Vulkan's ladder.
    if (!test::open_session()) return;
    const Folder folder("cells");
    const fs::path& dir = folder.path();
    if (!can_start(dir)) return;
    std::string driver;
    std::string version;
    gpu::Configuration pick;
    std::optional<gpu::Configuration> first_cell;
    {
        auto started = gpu::start_guarded(start_in(dir));
        ATLAS_REQUIRE(started.has_value(), started.error().reason);
        std::tie(driver, version) = gpu::guard_key(started->device->facts());
        pick = started->device->facts().choice;
        standin::Session session(*started, session_in(dir));
        std::string why;
        ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), size, why), why);
        // Each configuration's first frames: the marker names the cell, or no cell for the pick.
        const std::vector<gpu::Configuration> configurations = matrix(*started->device);
        ATLAS_REQUIRE(configurations.size() >= 2, "the device draws " << configurations.size() << " configurations");
        for (const gpu::Configuration& c : configurations) {
            ATLAS_REQUIRE(session.bench(1, c, pills).empty(), gpu::name_of(c));
            CHECK(draw(session, 2) == 2);
            CHECK(marked(dir) == "frames");
            CHECK_MESSAGE(cell_in(dir) == (c == pick ? std::string() : gpu::name_of(c)), read(dir / "guard"));
            if (c != pick && !first_cell) first_cell = c;
        }
        ATLAS_REQUIRE(first_cell.has_value(), "no configuration besides the pick");
        // At rest: no marker, the cell's with it.
        session.rest();
        CHECK_FALSE(fs::exists(dir / "guard"));
        session.close();
    }
    // An earlier run's crash in that cell turned it off for this driver and build; a line naming the pick as a cell
    // (left by a build whose facts picked another) cannot refuse the pick.
    const gpu::Configuration cell = *first_cell;
    std::ofstream(dir / "off", std::ios::trunc)
        << std::format("{}|{}|session-test|cell|{}|1\n", driver, version, gpu::name_of(cell))
        << std::format("{}|{}|session-test|cell|{}|1\n", driver, version, gpu::name_of(pick));
    auto started = gpu::start_guarded(start_in(dir));
    ATLAS_REQUIRE(started.has_value(), "a cell turned off turned Vulkan off: " << started.error().line);
    CHECK(started->guard_line.empty());
    const std::string off =
        std::format("vk guard cell {} {} attempt 1 {} build session-test", driver, version, gpu::name_of(cell));
    CHECK(started->guard->cell_off(cell) == off);
    CHECK(started->guard->cell_off(pick).empty());
    {
        standin::Session session(*started, session_in(dir));
        const std::string facts = session.facts();
        CHECK_MESSAGE(
            has(facts, std::format("guard.off: {} {} at cell, {} build session-test (this driver and build: VK "
                                   "bench's cell {} is off)\n",
                                   driver, version, gpu::name_of(cell), gpu::name_of(cell))),
            facts);
        auto window = std::make_unique<test::OffscreenWindow>();
        const test::OffscreenWindow* w = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        ATLAS_REQUIRE(session.bench(1, pick, pills).empty(), "the pick refused");
        CHECK(draw(session, 2) == 2);
        const int made = w->made();
        // The cell is refused with the guard's line, nothing made: the pick's frame goes on.
        CHECK(session.bench(1, cell, pills) == std::format("off (the driver crashed in it: {})", off));
        CHECK(w->made() == made);
        CHECK(draw(session, 1) == 1);
        CHECK(has(drawing(dir), std::format("(configuration {} ", gpu::name_of(pick))));
        CHECK(cell_in(dir).empty());
        // At 10 bits too: an off cell is refused before anything of the colour stage opens.
        CHECK(session.bench(1, cell, pills | ten).starts_with("off (the driver crashed in it: "));
        CHECK_FALSE(session.colour_open());
        CHECK(session.bench(0, pick, 0).empty());
        session.close();
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
}

#else

TEST_CASE("the core's session runs VK bench's order with every stage closed behind it, the colour stage holding "
          "10-bit work alone") {
    MESSAGE("skipped: the Android build has no offscreen output to draw the session's frames into");
}

#endif
