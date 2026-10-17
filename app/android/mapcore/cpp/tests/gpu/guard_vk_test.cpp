// The crash guard on the host's Vulkan (plan-vector D5.10, K1's host acceptance): a planted abort in pipeline creation
// leaves the guard's marker, which names the pipeline being made; the next start reads it, prints the `vk guard` line
// with that pipeline and retries once without the pipeline cache; when that crashes too, the start after turns Vulkan
// off for the driver, its version and this build of the core. A new driver version starts again, and so does another
// build of the core (its build ID), or a reset; off lines and markers from before build IDs turn nothing off. A crash
// making a shader module names the module; one in the device's facts (the queries its choice makes) follows the ladder
// keyed by the system build. VK bench's colour stage is the rung below: a planted abort in a pipeline of the 10-bit
// frame, or as the 10-bit frame goes, turns off 10 bits only, the next start draws at 8 bits with them off and says
// so, and a reset brings them back. The crashing starts run in child processes: this test binary, spawned with one test
// case.
#include "base/log.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Guard.hpp"
#include "gpu/PipelineCache.hpp"
#include "standin/StandIn.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"
#endif

#include <doctest/doctest.h>

#include <fcntl.h>
#include <spawn.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <mach-o/dyld.h>
#endif

#include <algorithm>
#include <array>
#include <climits>
#include <csignal>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

// POSIX's environment (unistd.h declares it only on some systems).
// NOLINTNEXTLINE(readability-redundant-declaration,cppcoreguidelines-avoid-non-const-global-variables)
extern char** environ;

using namespace atlas;

namespace {

namespace fs = std::filesystem;

// The pipeline the crash is planted in: on hosts the composite, which the child makes as it prepares a configuration
// for the host build's offscreen images (as VK bench's prepare does when PowerVR crashed), at the frame's 8 bits and in
// the colour stage at 10; the Android build has no offscreen output (platform/host), so there the child makes the
// pipelines every configuration shares, the shadow's among them, in both stages.
#if defined(__ANDROID__)
constexpr std::string_view planted = "shadow (module standin_standin";
constexpr const char* plant_at = "pipelines:shadow";
constexpr const char* colour_plant_at = "colour:shadow";
#else
constexpr std::string_view planted = "composite (module standin_composite";
constexpr const char* plant_at = "pipelines:composite";
constexpr const char* colour_plant_at = "colour:composite";
#endif

[[nodiscard]] std::string self_path() {
#if defined(__APPLE__)
    std::array<char, PATH_MAX> buffer{};
    std::uint32_t size = buffer.size();
    if (_NSGetExecutablePath(buffer.data(), &size) != 0) return {};
    return fs::weakly_canonical(buffer.data()).string();
#else
    return fs::read_symlink("/proc/self/exe").string();
#endif
}

[[nodiscard]] std::string read(const fs::path& p) {
    std::ifstream in(p);
    std::ostringstream out;
    out << in.rdbuf();
    return out.str();
}

[[nodiscard]] bool has(std::string_view text, std::string_view part) { return text.find(part) != std::string::npos; }

/// A child's end: its wait status and its own log (stdout and stderr).
struct Child {
    int status = -1;
    std::string log;
};

/// Runs this binary's child test case with the guard's folder, the plant and the core's build ID.
Child spawn_child(const fs::path& dir, const std::string& plant, const std::string& build) {
    const std::string exe = self_path();
    std::vector<std::string> args{exe, "--test-case=guard child start", "--no-intro=true"};
    std::vector<std::string> env;
    for (char** e = environ; *e != nullptr; ++e) env.emplace_back(*e);
    env.push_back("ATLAS_GUARD_CHILD=" + dir.string());
    env.push_back("ATLAS_GUARD_PLANT=" + plant);
    env.push_back("ATLAS_GUARD_BUILD=" + build);
    std::vector<char*> argv;
    argv.reserve(args.size() + 1);
    for (std::string& a : args) argv.push_back(a.data());
    argv.push_back(nullptr);
    std::vector<char*> envp;
    envp.reserve(env.size() + 1);
    for (std::string& e : env) envp.push_back(e.data());
    envp.push_back(nullptr);
    // The child's own report (a planted abort ends it as a crash) goes to a file beside the guard's, not into ours.
    static int children = 0;
    const fs::path log = dir / std::format("child-{}.log", ++children);
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, log.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    posix_spawn_file_actions_adddup2(&actions, STDOUT_FILENO, STDERR_FILENO);
    pid_t pid = 0;
    const int spawned = ::posix_spawn(&pid, exe.c_str(), &actions, nullptr, argv.data(), envp.data());
    posix_spawn_file_actions_destroy(&actions);
    Child child;
    if (spawned != 0) return child;
    (void)::waitpid(pid, &child.status, 0);
    child.log = read(log);
    return child;
}

[[nodiscard]] bool aborted(const Child& c) {
    int status = c.status; // macOS's wait macros take the address of their operand, so not a const one
    return WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT;
}

/// The core's lines (tag AtlasMap) of a log, the last one last.
[[nodiscard]] std::vector<std::string> core_lines(const std::string& log) {
    std::vector<std::string> out;
    std::istringstream in(log);
    for (std::string line; std::getline(in, line);) {
        if (line.starts_with("AtlasMap ")) out.push_back(line);
    }
    return out;
}

[[nodiscard]] gpu::StartOptions options_in(const fs::path& dir, const std::string& plant, const std::string& build) {
    return {.guard_dir = dir,
            .cache_file = dir / "pipelines.bin",
            .system = "host-test-system",
            .build = build,
            .plant = plant,
            .validation = false,
            .spirv = 42};
}

/// The guard's state line about the driver turned off at `stage`, or "" when there is none.
[[nodiscard]] std::string off_line(const std::vector<std::string>& state, std::string_view stage) {
    for (const std::string& l : state) {
        if (l.starts_with("guard.off: ") && has(l, std::format(" at {}", stage))) return l;
    }
    return {};
}

} // namespace

TEST_CASE("guard child start") {
    // Runs only in the child the guard's test spawns: a guarded start, then the stand-in's pipelines behind the
    // pipelines stage at the frame's 8 bits, those every configuration shares and (on hosts) the configuration's, the
    // composite among them, each shader module and pipeline announced to the guard first, and the frame as it goes;
    // then, as VK bench does after its 8-bit runs (jni/Bridge.cpp's order), its colour stage where 10 bits can be had:
    // the device asked about them inside it, the configuration's pipelines again at 10 bits (on Android the shared ones
    // again), and everything of them let go of, announced, before it closes. The plant (if any) aborts the process
    // there.
    const char* dir = std::getenv("ATLAS_GUARD_CHILD"); // NOLINT(concurrency-mt-unsafe)
    if (dir == nullptr) return;
    const char* plant = std::getenv("ATLAS_GUARD_PLANT"); // NOLINT(concurrency-mt-unsafe)
    const char* build = std::getenv("ATLAS_GUARD_BUILD"); // NOLINT(concurrency-mt-unsafe)
    auto started = gpu::start_guarded(options_in(dir, plant == nullptr ? "" : plant, build == nullptr ? "" : build));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    gpu::Guard& guard = *started->guard;
    const gpu::Facts& facts = started->device->facts();
    ATLAS_REQUIRE(!facts.ten_bit_queried(), "the start asked the device about 10 bits");
    guard.enter(gpu::Stage::pipelines);
    auto city = std::make_shared<const standin::City>(standin::City::generate(32, 13.0));
    auto standin = standin::StandIn::create(*started->device, *started->cache, city, &guard);
    ATLAS_REQUIRE(standin.has_value(), standin.error().text());
#if !defined(__ANDROID__)
    {
        auto output = platform::Offscreen::create(*started->device, {.width = 64, .height = 64}, 2);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        ATLAS_REQUIRE(!(*output)->colour().ten_bit(), "the frame's default is 10 bits");
        auto renderer = gpu::Renderer::create(*started->device, **output, **standin, facts.choice, &guard);
        ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
        renderer->reset(); // the 8-bit frame goes, announced, inside the pipelines stage
    }
#endif
    guard.leave();
    // A start that survives writes its cache, as the core does after its pipelines.
    auto saved = started->cache->save();
    ATLAS_REQUIRE(saved.has_value(), saved.error().text());

    // The colour stage, after the 8-bit work: skipped where 10 bits cannot be had or the guard has them off.
    if (!facts.deepest.ten_bit()) {
        log::info("guard child: the colour stage skipped ({})", gpu::colour_stage(facts));
        return;
    }
    guard.enter_colour();
    started->device->check_ten_bit(); // inside the stage, as VK bench's colour stage asks
    if (!facts.deepest.ten_bit()) {
        guard.leave_colour();
        log::info("guard child: the colour stage skipped, the device asked ({})", gpu::colour_stage(facts));
        return;
    }
    guard.enter(gpu::Stage::pipelines);
#if !defined(__ANDROID__)
    {
        auto output =
            platform::Offscreen::create(*started->device, {.width = 64, .height = 64}, 2, gpu::Depth::deepest);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        ATLAS_REQUIRE((*output)->colour().ten_bit(), "the colour stage's output is not 10 bits");
        auto renderer = gpu::Renderer::create(*started->device, **output, **standin, facts.choice, &guard);
        ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
        // The 10-bit frame goes inside the stage, announced: its pipelines, passes, framebuffers and images, then its
        // main pass (`colour:release` aborts here); then the 10-bit images it drew into.
        renderer->reset();
        ATLAS_REQUIRE(!(*standin)->holds_prepared(), "the stand-in kept a frame its renderer prepared");
        output->reset();
    }
#else
    {
        auto again = standin::StandIn::create(*started->device, *started->cache, city, &guard);
        ATLAS_REQUIRE(again.has_value(), again.error().text());
    }
#endif
    guard.leave();
    guard.leave_colour();
    log::info("guard child: the colour stage drawn in {}", gpu::colour_name(facts.deepest.format));
}

TEST_CASE("the crash guard names the pipeline, retries once without the cache, then turns Vulkan off for that driver "
          "and build") {
    if (!test::open_session()) return; // no Vulkan here: nothing to guard
    const fs::path dir = fs::temp_directory_path() / std::format("atlas-guard-{}", ::getpid());
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir);

    // A clean start writes the pipeline cache.
    Child child = spawn_child(dir, "", "build-a");
    ATLAS_REQUIRE(WIFEXITED(child.status), "the clean child did not exit (wait status " << child.status << ")");
    CHECK(WEXITSTATUS(child.status) == 0);
    CHECK(fs::exists(dir / "pipelines.bin"));
    CHECK_FALSE(fs::exists(dir / "guard"));

    // 1. A crash compiling the composite (the shadow on Android): the marker names the stage, the pipeline, the build
    // and the first attempt, and the child's last line before the crash named the pipeline too (logcat's last line on
    // the phone).
    child = spawn_child(dir, plant_at, "build-a");
    CHECK(aborted(child));
    const std::string marker = read(dir / "guard");
    MESSAGE("marker after the first crash:\n" << marker);
    CHECK(has(marker, "stage pipelines\n"));
    CHECK(has(marker, std::format("\npipeline {}", planted)));
    CHECK(has(marker, "\nbuild build-a\n"));
    CHECK(has(marker, "\nattempt 1\n"));
    CHECK_FALSE(has(marker, "driver unknown")); // the driver was known by then
    const std::vector<std::string> lines = core_lines(child.log);
    ATLAS_REQUIRE(lines.size() >= 2, "the child logged " << lines.size() << " lines:\n" << child.log);
    CHECK(has(lines[lines.size() - 2], std::format("vk pipeline {}", planted)));
    CHECK(has(lines.back(), std::format("vk guard planted crash at pipelines, pipeline {}", planted)));

    // 2. The next start finds it and says which pipeline crashed; it retries once without the pipeline cache (the
    // file is gone), and crashes again.
    child = spawn_child(dir, plant_at, "build-a");
    CHECK(aborted(child));
    CHECK(has(child.log, "vk guard pipelines "));
    CHECK(has(child.log, std::format(" attempt 1 pipeline {}", planted)));
    CHECK(has(child.log, "retrying once without the pipeline cache"));
    CHECK_FALSE(fs::exists(dir / "pipelines.bin"));
    CHECK(has(read(dir / "guard"), "\nattempt 2\n"));

    // 3. The start after: Vulkan off for this driver, its version and this build; the `vk guard` line names the
    // pipeline; the facts are kept, and the guard's state says what is off.
    auto refused = gpu::start_guarded(options_in(dir, "", "build-a"));
    ATLAS_REQUIRE(!refused.has_value(), "the start after a second crash opened Vulkan");
    const std::string line = refused.error().line;
    MESSAGE("refused: " << line << " (" << refused.error().reason << ")");
    CHECK(line.starts_with("vk guard pipelines "));
    CHECK(has(line, std::format(" attempt 2 pipeline {}", planted)));
    CHECK(line.ends_with(" build build-a"));
    CHECK(has(refused.error().kept_facts, "gpu: "));
    CHECK_FALSE(fs::exists(dir / "guard"));
    const std::string off = read(dir / "off");
    MESSAGE("off: " << off);
    CHECK(has(off, std::format("|build-a|pipelines|pipeline {}", planted)));
    const std::string off_state = off_line(refused.error().guard, "pipelines");
    CHECK(has(off_state, std::format("pipeline {}", planted)));
    CHECK(has(off_state, "(this build: Vulkan is off for that driver)")); // refused before it read the driver

    // 4. It stays off at every later start of the same driver version and build.
    auto again = gpu::start_guarded(options_in(dir, "", "build-a"));
    ATLAS_REQUIRE(!again.has_value(), "a later start of the same driver version and build opened Vulkan");
    CHECK(again.error().line == line);
    CHECK(has(off_line(again.error().guard, "pipelines"), "(this driver and build: Vulkan is off)"));

    // 5. Another build of the core starts: it is not held to an older build's crash.
    {
        auto newer = gpu::start_guarded(options_in(dir, "", "build-b"));
        ATLAS_REQUIRE(newer.has_value(),
                      "a new build stayed locked out by an older build's crash: " << newer.error().line);
        CHECK(newer->guard_line.empty());
        CHECK(has(off_line(newer->guard->state(), "pipelines"), "(another build of the core: not off for this one)"));
    }

    // 6. A driver update (another version) starts again.
    {
        std::string updated = off;
        const std::size_t bar = updated.find('|');
        const std::size_t version = bar == std::string::npos ? bar : updated.find('|', bar + 1);
        ATLAS_REQUIRE(version != std::string::npos, "the off file names no driver and version: " << off);
        updated.replace(bar + 1, version - bar - 1, "0x00000000");
        std::ofstream(dir / "off", std::ios::trunc) << updated;
        auto after_update = gpu::start_guarded(options_in(dir, "", "build-a"));
        CHECK(after_update.has_value());
    }

    // 7. A reset (VK facts' "Reset guard") forgets it: the same driver and build start again.
    std::ofstream(dir / "off", std::ios::trunc) << off;
    CHECK_FALSE(gpu::start_guarded(options_in(dir, "", "build-a")).has_value());
    const std::string said = gpu::Guard(dir, "host-test-system", "build-a", "").clear();
    MESSAGE(said);
    CHECK(said == "vk guard reset: forgot 1 driver turned off");
    CHECK_FALSE(fs::exists(dir / "off"));
    {
        auto reset = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(reset.has_value(), "the start after a reset stayed off: " << reset.error().line);
        CHECK(reset->guard_line.empty());
        CHECK(std::ranges::contains(reset->guard->state(), std::string("guard.off: none")));
    }
    CHECK(gpu::Guard(dir, "host-test-system", "build-a", "").clear() == "vk guard reset: nothing to forget");

    // 8. The plant `pipelines` aborts at the stage's first pipeline, whose name the marker keeps. A start of another
    // build finds that crash, names it, and starts as its own first attempt (without the cache), not off.
    child = spawn_child(dir, "pipelines", "build-c");
    CHECK(aborted(child));
    CHECK(has(read(dir / "guard"), "\npipeline atlas (module standin_standin, configuration any)\n"));
    {
        auto other = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(other.has_value(), "another build's crash turned this build off: " << other.error().line);
        MESSAGE("another build's crash: " << other->guard_line);
        CHECK(other->guard_line.starts_with("vk guard pipelines "));
        CHECK(has(other->guard_line,
                  " attempt 1 pipeline atlas (module standin_standin, configuration any) build build-c"));
        CHECK(other->guard_line.ends_with("(another build; this build build-a starts afresh)"));
        CHECK(other->retried);
    }

    // 9. What alpha 6 left (before build IDs) turns nothing off: an off line driver|version|stage, and a marker of a
    // second attempt without a build.
    {
        const std::size_t bar = off.find('|');
        const std::size_t version = bar == std::string::npos ? bar : off.find('|', bar + 1);
        ATLAS_REQUIRE(version != std::string::npos, "the off file names no driver and version: " << off);
        std::ofstream(dir / "off", std::ios::trunc) << off.substr(0, version) << "|pipelines\n";
        std::ofstream(dir / "guard", std::ios::trunc)
            << "stage pipelines\ndriver " << off.substr(0, bar) << "\nversion "
            << off.substr(bar + 1, version - bar - 1) << "\nattempt 2\n";
        auto old = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(old.has_value(), "alpha 6's guard files turned Vulkan off: " << old.error().line);
        CHECK(has(old->guard_line, " attempt 2 build unknown (another build; this build build-a starts afresh)"));
        CHECK(has(off_line(old->guard->state(), "pipelines"), "(a build from before build IDs: not off for this one)"));
        CHECK_FALSE(fs::exists(dir / "guard"));
    }

    // 10. A crash before the driver is known (the instance stage) keys the system build, for this build of the core.
    fs::remove(dir / "off", ec);
    CHECK(aborted(spawn_child(dir, "instance", "build-a")));
    CHECK(aborted(spawn_child(dir, "instance", "build-a")));
    {
        auto system_off = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(!system_off.has_value(), "the start after two crashes at the instance opened Vulkan");
        CHECK(system_off.error().line == "vk guard instance unknown host-test-system attempt 2 build build-a");
        CHECK(has(read(dir / "off"), "system|host-test-system|build-a|instance|\n"));
    }
    CHECK(gpu::start_guarded(options_in(dir, "", "build-b")).has_value());

    // 11. So does a crash in the device's facts, every query the choice of the device makes of the driver (between the
    // instance and the device, before the driver is known): marked, retried once without the cache, then Vulkan off.
    fs::remove(dir / "off", ec);
    CHECK(aborted(spawn_child(dir, "facts", "build-a")));
    const std::string facts_marker = read(dir / "guard");
    MESSAGE("marker after a crash in the facts:\n" << facts_marker);
    CHECK(facts_marker.starts_with("stage facts\n"));
    CHECK(has(facts_marker, "\ndriver unknown\nversion host-test-system\nbuild build-a\nattempt 1\n"));
    const Child retry = spawn_child(dir, "facts", "build-a");
    CHECK(aborted(retry));
    CHECK(has(retry.log, "vk guard facts unknown host-test-system attempt 1 build build-a: retrying once without the "
                         "pipeline cache"));
    {
        auto facts_off = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(!facts_off.has_value(), "the start after two crashes in the device's facts opened Vulkan");
        CHECK(facts_off.error().line == "vk guard facts unknown host-test-system attempt 2 build build-a");
        CHECK(has(read(dir / "off"), "system|host-test-system|build-a|facts|\n"));
    }
    fs::remove_all(dir, ec);
}

TEST_CASE("a crash in VK bench's colour stage turns off 10 bits only, for that driver and build; a reset brings them "
          "back") {
    if (!test::open_session()) return; // no Vulkan here: nothing to guard
    const fs::path dir = fs::temp_directory_path() / std::format("atlas-guard-colour-{}", ::getpid());
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir);
    std::string configuration; // the device's, as the child draws it
    std::string ten;           // the colour stage's colour
    {
        auto probe = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(probe.has_value(), probe.error().reason);
        const gpu::Facts& facts = probe->device->facts();
        if (!facts.deepest.ten_bit()) {
            MESSAGE("skipped: no 10 bits here (" << gpu::colour_stage(facts) << ")");
            fs::remove_all(dir, ec);
            return;
        }
        configuration = gpu::name_of(facts.choice);
        ten = gpu::colour_name(facts.deepest.format);
    }

    // 1. A clean start draws its 8 bits, then the colour stage's 10, and leaves no marker.
    Child child = spawn_child(dir, "", "build-a");
    ATLAS_REQUIRE(WIFEXITED(child.status), "the clean child did not exit (wait status " << child.status << ")");
    CHECK(WEXITSTATUS(child.status) == 0);
    CHECK(has(child.log, "guard child: the colour stage drawn in "));
    CHECK_FALSE(fs::exists(dir / "guard"));

    // 2. A planted abort in a pipeline of the 10-bit frame: the 8-bit one was made before it, and the marker is the
    // colour stage's, naming the pipeline and its colour (on Android the shared shadow, made again in the stage).
    child = spawn_child(dir, colour_plant_at, "build-a");
    CHECK(aborted(child));
    const std::string marker = read(dir / "guard");
    MESSAGE("marker after the colour stage's crash:\n" << marker);
    CHECK(has(marker, "stage colour\n"));
    CHECK(has(marker, std::format("\npipeline {}", planted)));
    CHECK(has(marker, "\nbuild build-a\n"));
    CHECK(has(marker, "\nattempt 1\n"));
#if !defined(__ANDROID__)
    CHECK(has(marker, std::format("{}, configuration {} {})\n", planted, configuration, ten)));
    CHECK(has(child.log, std::format("vk pipeline {}", planted))); // the 8-bit composite, made first
    CHECK(has(child.log, " rgba8)"));
#endif
    const std::vector<std::string> lines = core_lines(child.log);
    ATLAS_REQUIRE(!lines.empty(), "the child logged nothing:\n" << child.log);
    CHECK(has(lines.back(), std::format("vk guard planted crash at colour, pipeline {}", planted)));

    // 3. The next start: Vulkan on, at 8 bits, without the pipeline cache; 10 bits off for this driver and build, and
    // the facts and the guard's state say so.
    std::string off;
    {
        auto next = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(next.has_value(), "the start after a crash in the colour stage turned Vulkan off: "
                                            << next.error().line << " (" << next.error().reason << ")");
        MESSAGE("found: " << next->guard_line);
        CHECK(next->guard_line.starts_with("vk guard colour "));
        CHECK(has(next->guard_line, std::format(" attempt 1 pipeline {}", planted)));
        CHECK(next->guard_line.ends_with(" build build-a"));
        CHECK(next->retried);
        CHECK_FALSE(fs::exists(dir / "pipelines.bin"));
        const gpu::Facts& facts = next->device->facts();
        CHECK(facts.ten_bit_off.starts_with("vk guard colour "));
        CHECK(has(facts.ten_bit_off, std::format(" attempt 1 pipeline {}", planted)));
        CHECK_FALSE(facts.deepest.ten_bit());
        CHECK_FALSE(facts.colour.ten_bit());
        CHECK_FALSE(gpu::colour_offscreen(facts, gpu::Depth::deepest).ten_bit());
        const std::vector<std::string> said = gpu::facts_lines(facts);
        const auto line_of = [&](std::string_view prefix) {
            const auto it = std::ranges::find_if(said, [&](const std::string& l) { return l.starts_with(prefix); });
            return it == said.end() ? std::string() : *it;
        };
        MESSAGE(line_of("colour: "));
        CHECK(has(line_of("colour: "), "; 10 bits: off, 10 bits are off for this driver and build: vk guard colour "));
        CHECK(line_of("colour.stage: ") ==
              std::format("colour.stage: off (10 bits are off for this driver and build: {})", facts.ten_bit_off));
        const std::string state = off_line(next->guard->state(), "colour");
        MESSAGE(state);
        CHECK(has(state, std::format("pipeline {}", planted)));
        CHECK(has(state, "(this driver and build: 10 bits are off)"));
        CHECK(off_line(next->guard->state(), "pipelines").empty());
        off = read(dir / "off");
        CHECK(has(off, std::format("|build-a|colour|pipeline {}", planted)));
    }

    // 4. Every later start of this driver and build skips the colour stage and draws its 8 bits: the planted pipeline
    // of the 10-bit frame is never reached, and the start keeps everything else.
    child = spawn_child(dir, colour_plant_at, "build-a");
    ATLAS_REQUIRE(WIFEXITED(child.status), "the start after the colour stage's crash did not exit:\n" << child.log);
    CHECK(WEXITSTATUS(child.status) == 0);
    CHECK(has(child.log, "guard child: the colour stage skipped (off (10 bits are off for this driver and build: vk "
                         "guard colour "));
    // No crash found this time: the verdict stands quietly, no `vk guard` line.
    CHECK(std::ranges::none_of(core_lines(child.log), [](const std::string& l) {
        return l.size() > 11 && std::string_view(l).substr(11).starts_with("vk guard ");
    }));
    CHECK(fs::exists(dir / "pipelines.bin")); // the 8-bit start wrote its cache
    CHECK_FALSE(fs::exists(dir / "guard"));

    // 5. Another build of the core is not held to it.
    {
        auto other = gpu::start_guarded(options_in(dir, "", "build-b"));
        ATLAS_REQUIRE(other.has_value(), other.error().line);
        CHECK(other->device->facts().ten_bit_off.empty());
        CHECK(other->device->facts().deepest.ten_bit());
        CHECK(has(off_line(other->guard->state(), "colour"), "(another build of the core: not off for this one)"));
    }

    // 6. The reset brings 10 bits back: the start after it draws the colour stage again, and the plant aborts there
    // again.
    const std::string said = gpu::Guard(dir, "host-test-system", "build-a", "").clear(dir / "pipelines.bin");
    MESSAGE(said);
    CHECK(said == "vk guard reset: forgot 10 bits turned off for 1 driver and the pipeline cache");
    {
        auto back = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(back.has_value(), back.error().line);
        CHECK(back->guard_line.empty());
        CHECK(back->device->facts().ten_bit_off.empty());
        CHECK(back->device->facts().deepest.ten_bit());
        CHECK(std::ranges::contains(back->guard->state(), std::string("guard.off: none")));
    }
    child = spawn_child(dir, "", "build-a");
    CHECK(has(child.log, "guard child: the colour stage drawn in "));
    child = spawn_child(dir, colour_plant_at, "build-a");
    CHECK(aborted(child));
    CHECK(has(read(dir / "guard"), "stage colour\n"));
    fs::remove_all(dir, ec);
}

TEST_CASE("a crash as VK bench's 10-bit frame goes is the colour stage's: 10 bits off, the frame named") {
    // jni/Bridge.cpp lets go of the 10-bit frame (the stand-in's pipelines, passes, framebuffers and images made for
    // it, then the renderer's main pass) and the 10-bit swapchain before it closes the colour stage. The child does as
    // it does, offscreen: a planted abort as the 10-bit frame goes leaves the colour stage's marker naming the frame,
    // and the next start turns off 10 bits only. The 8-bit frame goes in the pipelines stage, the ladder's.
#if defined(__ANDROID__)
    MESSAGE("skipped: the Android build has no offscreen output to prepare a 10-bit frame for");
#else
    if (!test::open_session()) return; // no Vulkan here: nothing to guard
    const fs::path dir = fs::temp_directory_path() / std::format("atlas-guard-release-{}", ::getpid());
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir);
    std::string ten_frame;   // the 10-bit frame, as the guard names it
    std::string eight_frame; // the 8-bit one
    {
        auto probe = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(probe.has_value(), probe.error().reason);
        gpu::Device& device = *probe->device;
        device.check_ten_bit();
        const gpu::Facts& facts = device.facts();
        if (!facts.deepest.ten_bit()) {
            MESSAGE("skipped: no 10 bits here (" << gpu::colour_stage(facts) << ")");
            fs::remove_all(dir, ec);
            return;
        }
        const std::string configuration = gpu::name_of(facts.choice);
        ten_frame = std::format("frame (configuration {} {})", configuration, gpu::colour_name(facts.deepest.format));
        eight_frame = std::format("frame (configuration {} {})", configuration,
                                  gpu::colour_name(gpu::colour_offscreen(facts, gpu::default_depth).format));
    }

    // 1. The plant at the 10-bit frame's going: the marker is the colour stage's and names the frame; the child's last
    // lines name it too (logcat's on the phone).
    Child child = spawn_child(dir, "colour:release", "build-a");
    CHECK(aborted(child));
    const std::string marker = read(dir / "guard");
    MESSAGE("marker after the crash as the 10-bit frame went:\n" << marker);
    CHECK(marker.starts_with(std::format("stage colour\nrelease {}\n", ten_frame)));
    CHECK(has(marker, "\nbuild build-a\nattempt 1\n"));
    const std::vector<std::string> lines = core_lines(child.log);
    ATLAS_REQUIRE(lines.size() >= 2, "the child logged " << lines.size() << " lines:\n" << child.log);
    CHECK(has(lines[lines.size() - 2], std::format("vk release {}", ten_frame)));
    CHECK(has(lines.back(), std::format("vk guard planted crash at colour, release {}", ten_frame)));
    CHECK(has(child.log, std::format("vk release {}", eight_frame))); // the 8-bit frame went before, unplanted

    // 2. The next start: Vulkan on at 8 bits without the cache, 10 bits off for this driver and build, by the frame.
    {
        auto next = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(next.has_value(), "the start after a crash as the 10-bit frame went turned Vulkan off: "
                                            << next.error().line << " (" << next.error().reason << ")");
        MESSAGE("found: " << next->guard_line);
        CHECK(next->guard_line.starts_with("vk guard colour "));
        CHECK(next->guard_line.ends_with(std::format(" attempt 1 release {} build build-a", ten_frame)));
        CHECK(next->retried);
        CHECK(has(next->device->facts().ten_bit_off, std::format("release {}", ten_frame)));
        CHECK_FALSE(next->device->facts().deepest.ten_bit());
        CHECK(has(read(dir / "off"), std::format("|build-a|colour|release {}\n", ten_frame)));
        CHECK(has(off_line(next->guard->state(), "colour"), "(this driver and build: 10 bits are off)"));
    }

    // 3. The 8-bit frame going is the pipelines stage's (another build, which the colour verdict does not hold).
    child = spawn_child(dir, "pipelines:release", "build-b");
    CHECK(aborted(child));
    const std::string eight_marker = read(dir / "guard");
    MESSAGE("marker after a crash as the 8-bit frame went:\n" << eight_marker);
    CHECK(eight_marker.starts_with(std::format("stage pipelines\nrelease {}\n", eight_frame)));
    {
        auto next = gpu::start_guarded(options_in(dir, "", "build-b"));
        ATLAS_REQUIRE(next.has_value(), next.error().line);
        CHECK(next->guard_line.starts_with("vk guard pipelines "));
        CHECK(has(next->guard_line, std::format(" attempt 1 release {} build build-b", eight_frame)));
    }
    fs::remove_all(dir, ec);
#endif
}

TEST_CASE("the crash guard names the shader module a driver crashes making") {
    // vkCreateShaderModule runs before any pipeline of the module: the marker names the module, not the pipeline made
    // before it (the blur's module, made with the shared pipelines' before the first of them).
    if (!test::open_session()) return;
    const fs::path dir = fs::temp_directory_path() / std::format("atlas-guard-module-{}", ::getpid());
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir);
    const Child child = spawn_child(dir, "pipelines:standin_blur", "build-m");
    CHECK(aborted(child));
    const std::string marker = read(dir / "guard");
    MESSAGE("marker:\n" << marker);
    CHECK(has(marker, "stage pipelines\nmodule standin_blur\n"));
    CHECK_FALSE(has(marker, "\npipeline "));
    const std::vector<std::string> lines = core_lines(child.log);
    ATLAS_REQUIRE(lines.size() >= 2, "the child logged " << lines.size() << " lines:\n" << child.log);
    CHECK(has(lines[lines.size() - 2], "vk module standin_blur"));
    CHECK(has(lines.back(), "vk guard planted crash at pipelines, module standin_blur"));
    auto next = gpu::start_guarded(options_in(dir, "", "build-m"));
    ATLAS_REQUIRE(next.has_value(), next.error().line);
    MESSAGE("found: " << next->guard_line);
    CHECK(has(next->guard_line, " attempt 1 module standin_blur build build-m"));
    CHECK(next->retried);
    fs::remove_all(dir, ec);
}
