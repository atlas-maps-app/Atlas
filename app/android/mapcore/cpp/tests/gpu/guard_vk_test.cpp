// The crash guard on the host's Vulkan (plan-vector D5.10, K1's host acceptance): a planted abort in pipeline creation
// leaves the guard's marker, which names the pipeline being made; the next start reads it, prints the `vk guard` line
// with that pipeline and retries once without the pipeline cache; when that crashes too, the start after turns Vulkan
// off for the driver, its version and this build of the core. A new driver version starts again, and so does another
// build of the core (its build ID), or a reset; off lines and markers from before build IDs turn nothing off. A crash
// making a shader module names the module; one in the device's facts (the queries its choice makes) follows the ladder
// keyed by the system build, one in the surface stage or the close the driver's. VK bench's colour stage is the rung
// below: a planted abort in a pipeline of the 10-bit frame, or as the 10-bit frame or swapchain goes, turns off 10 bits
// only, the next start draws at 8 bits with them off, keeps its pipeline cache and says so, and a reset brings them
// back; a crash in the profiles stage turns off the profiles check alone. A marker whose process Android recorded as
// stopped, not crashed, counts for nothing. The crashing starts run in child processes: this test binary, spawned with
// one test case, which on hosts runs the core's session (standin/Session.hpp) in VK bench's order.
#include "base/log.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Guard.hpp"
#include "gpu/PipelineCache.hpp"
#include "standin/Session.hpp"
#include "standin/StandIn.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"

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
#include <charconv>
#include <chrono>
#include <climits>
#include <csignal>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <sstream>
#include <string>
#include <tuple>
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

/// A child's end: its wait status, its own log (stdout and stderr) and its process's id.
struct Child {
    int status = -1;
    std::string log;
    pid_t pid = 0;
};

/// Runs this binary's child test case with the guard's folder, the plant, the core's build ID and the app's process
/// exits as the façade would pass them (gpu::parse_exits' text; "" for none known).
Child spawn_child(const fs::path& dir, const std::string& plant, const std::string& build,
                  const std::string& exits = {}) {
    const std::string exe = self_path();
    std::vector<std::string> args{exe, "--test-case=guard child start", "--no-intro=true"};
    std::vector<std::string> env;
    for (char** e = environ; *e != nullptr; ++e) env.emplace_back(*e);
    env.push_back("ATLAS_GUARD_CHILD=" + dir.string());
    env.push_back("ATLAS_GUARD_PLANT=" + plant);
    env.push_back("ATLAS_GUARD_BUILD=" + build);
    env.push_back("ATLAS_GUARD_EXITS=" + exits);
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
    child.pid = pid;
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
    // Runs only in the child the guard's test spawns: a guarded start, then on hosts the core's session in VK bench's
    // order (jni/Bridge.cpp hands it the phone's window): the facts, Android's profiles read behind their stage; a
    // window of offscreen images; the stand-in at the frame's 8 bits behind the pipelines stage, each shader module and
    // pipeline announced to the guard first, and a few frames; then, where 10 bits can be had, the colour stage (the
    // 8-bit frame and output gone first, the device asked about 10 bits inside it, the 10-bit output and frame made
    // inside it), left as VK bench leaves it (mode 0: the 10-bit frame, output and cache gone inside it); and the
    // core's close. The plant (if any) aborts the process where it names.
    const char* dir = std::getenv("ATLAS_GUARD_CHILD"); // NOLINT(concurrency-mt-unsafe)
    if (dir == nullptr) return;
    const char* plant = std::getenv("ATLAS_GUARD_PLANT"); // NOLINT(concurrency-mt-unsafe)
    const char* build = std::getenv("ATLAS_GUARD_BUILD"); // NOLINT(concurrency-mt-unsafe)
    const char* exits = std::getenv("ATLAS_GUARD_EXITS"); // NOLINT(concurrency-mt-unsafe)
    gpu::StartOptions options = options_in(dir, plant == nullptr ? "" : plant, build == nullptr ? "" : build);
    options.exits = gpu::parse_exits(exits == nullptr ? "" : exits);
    auto started = gpu::start_guarded(options);
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    const gpu::Facts& facts = started->device->facts();
    ATLAS_REQUIRE(!facts.ten_bit_queried(), "the start asked the device about 10 bits");
#if !defined(__ANDROID__)
    standin::Session session(*started,
                             {.cache_file = fs::path(dir) / "pipelines.bin",
                              .spirv = 42,
                              .city = std::make_shared<const standin::City>(standin::City::generate(32, 13.0))});
    (void)session.facts();
    std::string why;
    ATLAS_REQUIRE(session.attach(std::make_unique<test::OffscreenWindow>(), {.width = 64, .height = 64}, why), why);
    const gpu::Configuration config = facts.choice;
    ATLAS_REQUIRE(session.bench(1, config, standin::bench_flag::pills).empty(), "the frame at 8 bits");
    const style::CameraState camera = standin::board_view(64, 64, 1.0);
    for (int i = 0; i < 2; ++i) (void)session.draw(camera, {});
    // The colour stage, after the 8-bit work: skipped where 10 bits cannot be had or the guard has them off.
    if (!facts.deepest.ten_bit()) {
        log::info("guard child: the colour stage skipped ({})", gpu::colour_stage(facts));
        session.close();
        return;
    }
    why = session.bench(1, config, standin::bench_flag::pills | standin::bench_flag::ten_bit);
    if (!why.empty()) {
        log::info("guard child: the colour stage skipped, the device asked ({})", why);
        session.close();
        return;
    }
    const std::string drawn = gpu::colour_name(session.output()->format());
    for (int i = 0; i < 2; ++i) (void)session.draw(camera, {});
    ATLAS_REQUIRE(session.bench(0, config, 0).empty(), "out of the colour stage");
    ATLAS_REQUIRE(!session.colour_open(), "the colour stage stayed open");
    session.close();
    log::info("guard child: the colour stage drawn in {}", drawn);
#else
    // The Android build has no offscreen output: the shared pipelines, at 8 bits and again inside the colour stage.
    gpu::Guard& guard = *started->guard;
    auto city = std::make_shared<const standin::City>(standin::City::generate(32, 13.0));
    guard.enter(gpu::Stage::pipelines);
    {
        auto standin = standin::StandIn::create(*started->device, *started->cache, city, &guard);
        ATLAS_REQUIRE(standin.has_value(), standin.error().text());
    }
    guard.leave();
    if (!facts.deepest.ten_bit()) {
        log::info("guard child: the colour stage skipped ({})", gpu::colour_stage(facts));
        return;
    }
    guard.enter_colour();
    started->device->check_ten_bit();
    guard.enter(gpu::Stage::pipelines);
    {
        auto again = standin::StandIn::create(*started->device, *started->cache, city, &guard);
        ATLAS_REQUIRE(again.has_value(), again.error().text());
    }
    guard.leave();
    guard.leave_colour();
    log::info("guard child: the colour stage drawn in {}", gpu::colour_name(facts.deepest.format));
#endif
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
        CHECK(has(read(dir / "off"), "system|host-test-system|build-a|instance||2\n"));
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
        CHECK(has(read(dir / "off"), "system|host-test-system|build-a|facts||2\n"));
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

    // 3. The next start: Vulkan on, at 8 bits, with its pipeline cache (the colour stage's pipelines went through a
    // cache of its own: the one on disk holds no 10-bit pipeline); 10 bits off for this driver and build, and the facts
    // and the guard's state say so.
    std::string off;
    {
        auto next = gpu::start_guarded(options_in(dir, "", "build-a"));
        ATLAS_REQUIRE(next.has_value(), "the start after a crash in the colour stage turned Vulkan off: "
                                            << next.error().line << " (" << next.error().reason << ")");
        MESSAGE("found: " << next->guard_line);
        CHECK(next->guard_line.starts_with("vk guard colour "));
        CHECK(has(next->guard_line, std::format(" attempt 1 pipeline {}", planted)));
        CHECK(next->guard_line.ends_with(" build build-a"));
        CHECK_FALSE(next->retried);
        CHECK(fs::exists(dir / "pipelines.bin"));
        CHECK(next->cache->status() == "hit");
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
    // The core's session lets go of the 10-bit frame (the stand-in's pipelines, passes, framebuffers and images made
    // for it, then the renderer's main pass) and the 10-bit swapchain before it closes the colour stage. The child runs
    // it, offscreen: a planted abort as the 10-bit frame goes (`colour:release`, the stage's first thing of 10 bits to
    // go) leaves the colour stage's marker naming the frame, and the next start turns off 10 bits only. The 8-bit frame
    // goes before the stage opens, in the pipelines stage, the ladder's.
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
        CHECK_FALSE(next->retried);
        CHECK(has(next->device->facts().ten_bit_off, std::format("release {}", ten_frame)));
        CHECK_FALSE(next->device->facts().deepest.ten_bit());
        CHECK(has(read(dir / "off"), std::format("|build-a|colour|release {}|1\n", ten_frame)));
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

#if !defined(__ANDROID__)

namespace {

/// The facts line of `facts` that starts with `prefix` ("" when none does).
[[nodiscard]] std::string line_of(const gpu::Facts& facts, std::string_view prefix) {
    for (const std::string& l : gpu::facts_lines(facts)) {
        if (l.starts_with(prefix)) return l;
    }
    return {};
}

/// Exits as the façade passes them: one of process `pid`, ended at `at_ms` for `reason`.
[[nodiscard]] std::string exit_of(pid_t pid, std::int64_t at_ms, bool counted, std::string_view reason) {
    return std::format("{} {} {} {}", pid, at_ms, counted ? 1 : 0, reason);
}

[[nodiscard]] std::int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch())
        .count();
}

/// A fresh folder for one test case's guard.
[[nodiscard]] fs::path fresh(std::string_view name) {
    const fs::path dir = fs::temp_directory_path() / std::format("atlas-guard-{}-{}", name, ::getpid());
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir);
    return dir;
}

} // namespace

TEST_CASE("the profiles check is a stage of its own, never the start's: a crash there turns it off alone") {
    // The Vulkan Profiles library's checks of Android's profiles ask the device about formats the start does not take,
    // the 10-bit ones among them, and decide nothing: they run behind their own stage when VK facts or VK bench asks
    // for the facts (the child's session does), and a crash in them turns off the profiles check alone, at once.
    if (!test::open_session()) return;
    const fs::path dir = fresh("profiles");
    {
        auto started = gpu::start_guarded(options_in(dir, "", "build-p"));
        ATLAS_REQUIRE(started.has_value(), started.error().reason);
        CHECK(started->device->facts().profiles.empty());
        CHECK(has(line_of(started->device->facts(), "profiles: "), "not read yet"));
    }
    Child child = spawn_child(dir, "profiles", "build-p");
    CHECK(aborted(child));
    const std::string marker = read(dir / "guard");
    MESSAGE("marker after the profiles stage's crash:\n" << marker);
    CHECK(marker.starts_with("stage profiles\n"));
    CHECK_FALSE(has(marker, "driver unknown")); // the driver is known by then
    {
        auto next = gpu::start_guarded(options_in(dir, "", "build-p"));
        ATLAS_REQUIRE(next.has_value(),
                      "the start after a crash in the profiles check turned Vulkan off: " << next.error().line);
        MESSAGE("found: " << next->guard_line);
        CHECK(next->guard_line.starts_with("vk guard profiles "));
        CHECK(next->guard_line.ends_with(" attempt 1 build build-p"));
        CHECK_FALSE(next->retried); // the cache is not the profiles' to lose
        const gpu::Facts& facts = next->device->facts();
        CHECK(facts.profiles_off == next->guard_line);
        CHECK(line_of(facts, "profiles: ") ==
              std::format("profiles: off (the check crashed under this driver and build: {})", next->guard_line));
        CHECK(facts.ten_bit_off.empty()); // 10 bits are not the profiles' to lose
        CHECK(has(off_line(next->guard->state(), "profiles"), "(this driver and build: the profiles check is off)"));
        CHECK(has(read(dir / "off"), "|build-p|profiles||1\n"));
    }
    // Every later start leaves them unread: the planted abort is never reached, and the start ends well.
    child = spawn_child(dir, "profiles", "build-p");
    CHECK(WIFEXITED(child.status));
    CHECK(WEXITSTATUS(child.status) == 0);
    CHECK_FALSE(fs::exists(dir / "guard"));
    CHECK(gpu::Guard(dir, "host-test-system", "build-p", "").clear() ==
          "vk guard reset: forgot the profiles check turned off for 1 driver");
    std::error_code ec;
    fs::remove_all(dir, ec);
}

TEST_CASE("the surface stage and the close follow Vulkan's ladder, each thing that goes named") {
    if (!test::open_session()) return;
    const fs::path dir = fresh("surface");
    // 1. The 8-bit swapchain going (the child's goes as its colour stage opens, or at its close): the surface stage's
    // marker names it; the next start retries, and a second crash turns Vulkan off.
    Child child = spawn_child(dir, "surface:release:swapchain", "build-s");
    CHECK(aborted(child));
    std::string marker = read(dir / "guard");
    MESSAGE("marker as the 8-bit swapchain went:\n" << marker);
    CHECK(marker.starts_with("stage surface\nrelease swapchain rgba8\n"));
    CHECK(aborted(spawn_child(dir, "surface:release:swapchain", "build-s")));
    {
        auto off = gpu::start_guarded(options_in(dir, "", "build-s"));
        ATLAS_REQUIRE(!off.has_value(), "the start after two crashes in the surface stage opened Vulkan");
        CHECK(off.error().line.starts_with("vk guard surface "));
        CHECK(off.error().line.ends_with(" attempt 2 release swapchain rgba8 build build-s"));
    }
    CHECK(gpu::Guard(dir, "host-test-system", "build-s", "").clear() == "vk guard reset: forgot 1 driver turned off");
    // 2. The plant `surface` aborts as the stage begins (the window's surface and its queries).
    CHECK(aborted(spawn_child(dir, "surface", "build-s")));
    CHECK(read(dir / "guard").starts_with("stage surface\ndriver "));
    std::error_code ec;
    fs::remove(dir / "guard", ec);
    // 3. The device going at the core's close: the close stage's marker names it; the next start retries.
    child = spawn_child(dir, "close:release:device", "build-s");
    CHECK(aborted(child));
    marker = read(dir / "guard");
    MESSAGE("marker as the device went at the close:\n" << marker);
    CHECK(marker.starts_with("stage close\nrelease device (and its allocator)\n"));
    {
        auto next = gpu::start_guarded(options_in(dir, "", "build-s"));
        ATLAS_REQUIRE(next.has_value(), next.error().line);
        CHECK(next->guard_line.ends_with(" attempt 1 release device (and its allocator) build build-s"));
        CHECK(next->guard_line.starts_with("vk guard close "));
        CHECK(next->retried);
    }
    // 3b. The pipeline cache on disk going at the close is named so, not by the stand-in that went before it.
    child = spawn_child(dir, "close:release:cache", "build-s2");
    CHECK(aborted(child));
    marker = read(dir / "guard");
    MESSAGE("marker as the pipeline cache went at the close:\n" << marker);
    CHECK(marker.starts_with("stage close\nrelease cache (the one on disk)\n"));
    fs::remove(dir / "guard", ec);
    // 4. The 10-bit swapchain going is the colour stage's, by name: 10 bits off, nothing else.
    {
        auto probe = gpu::start_guarded(options_in(dir, "", "build-s"));
        ATLAS_REQUIRE(probe.has_value(), probe.error().reason);
        if (!probe->device->facts().deepest.ten_bit()) {
            MESSAGE("the colour stage's swapchain skipped: no 10 bits here");
            fs::remove_all(dir, ec);
            return;
        }
    }
    child = spawn_child(dir, "colour:release:swapchain", "build-s");
    CHECK(aborted(child));
    marker = read(dir / "guard");
    MESSAGE("marker as the 10-bit swapchain went:\n" << marker);
    CHECK(marker.starts_with("stage colour\nrelease swapchain rgb10a2\n"));
    {
        auto next = gpu::start_guarded(options_in(dir, "", "build-s"));
        ATLAS_REQUIRE(next.has_value(), next.error().line);
        CHECK(next->guard_line.ends_with(" attempt 1 release swapchain rgb10a2 build build-s"));
        CHECK(has(next->device->facts().ten_bit_off, "release swapchain rgb10a2"));
        CHECK_FALSE(next->retried);
    }
    // 5. The colour stage's own pipeline cache, the last thing of it to go (another build: 10 bits are back for it).
    child = spawn_child(dir, "colour:release:cache", "build-c");
    CHECK(aborted(child));
    marker = read(dir / "guard");
    MESSAGE("marker as the colour stage's cache went:\n" << marker);
    CHECK(marker.starts_with("stage colour\nrelease cache (the colour stage's)\n"));
    fs::remove_all(dir, ec);
}

TEST_CASE("a marker whose process was stopped, not crashed, counts for nothing; a crash, a hang or no record counts") {
    // A marker survives a swipe-away, a low-memory kill or a force-stop as it survives a crash. The façade passes the
    // app's process exits as Android records them; the guard looks the marker's process up among them (its id, the
    // first exit after its start). The child leaves a real marker: `frames` aborts as its first frame begins.
    if (!test::open_session()) return;
    const fs::path dir = fresh("exits");
    const Child child = spawn_child(dir, "frames", "build-k");
    CHECK(aborted(child));
    const std::string marker = read(dir / "guard");
    MESSAGE("the frames stage's marker:\n" << marker);
    CHECK(marker.starts_with("stage frames\n"));
    // The frames stage names the frame it draws: a crash in its first draws says which configuration it was.
    CHECK(has(marker, "\ndrawing frame (configuration "));
    CHECK(has(marker, std::format("\npid {}\nstart ", child.pid)));
    const std::int64_t later = now_ms();
    const auto start_with = [&](const std::string& exits, std::string_view at = {}, std::int64_t process_start = 0) {
        std::ofstream(dir / "guard", std::ios::trunc) << (at.empty() ? marker : std::string(at));
        gpu::StartOptions o = options_in(dir, "", "build-k");
        o.exits = gpu::parse_exits(exits);
        o.process_start_ms = process_start;
        return gpu::start_guarded(o);
    };
    // 1. Swiped away (the user), killed for memory (the system), updated (the package), three times running: never
    // counted, nothing turned off, the facts saying so.
    for (const std::string_view reason : {"user-requested", "low-memory", "package-updated"}) {
        auto stopped = start_with(exit_of(child.pid, later, false, reason));
        ATLAS_REQUIRE(stopped.has_value(), stopped.error().line);
        CHECK(stopped->guard_line.empty());
        CHECK(stopped->discarded_line == std::format("vk guard: frames marker after {}, not counted", reason));
        CHECK_FALSE(stopped->retried);
        CHECK_FALSE(fs::exists(dir / "guard"));
        CHECK_FALSE(fs::exists(dir / "off"));
    }
    // 2. A native crash counts (the retry), and the start knows how its process ended; so does a hang (an ANR).
    for (const std::string_view reason : {"crash-native", "anr"}) {
        auto crashed = start_with(exit_of(child.pid, later, true, reason));
        ATLAS_REQUIRE(crashed.has_value(), crashed.error().line);
        CHECK(crashed->guard_line.starts_with("vk guard frames "));
        CHECK(crashed->exit == reason);
        CHECK(crashed->retried);
    }
    // 3. No record of the process counts: none at all, or only one of another process of its id, ended before it began.
    auto unrecorded = start_with("none");
    ATLAS_REQUIRE(unrecorded.has_value(), unrecorded.error().line);
    CHECK(unrecorded->exit == "no record");
    CHECK(unrecorded->retried);
    std::int64_t began = 0;
    const std::string_view start_text = std::string_view(marker).substr(marker.find("\nstart ") + 7);
    (void)std::from_chars(start_text.data(), start_text.data() + start_text.size(), began);
    CHECK(began > 0);
    auto earlier = start_with(exit_of(child.pid, began - 1000, false, "user-requested"));
    ATLAS_REQUIRE(earlier.has_value(), earlier.error().line);
    CHECK(earlier->exit == "no record");
    CHECK(earlier->discarded_line.empty());
    // 4. Exits not known (API 29): every marker counts, as before.
    auto unknown = start_with("");
    ATLAS_REQUIRE(unknown.has_value(), unknown.error().line);
    CHECK(unknown->guard_line.starts_with("vk guard frames "));
    CHECK(unknown->exit.empty());
    // 5. A marker of this very process (an earlier core in it, which runs: its id, and its start as this process's,
    // within the second the façade's two clocks allow): not counted, whatever Android records, API 29's nothing too.
    std::string own = marker;
    own.replace(own.find(std::format("\npid {}\n", child.pid)), std::format("\npid {}\n", child.pid).size(),
                std::format("\npid {}\n", ::getpid()));
    for (const std::string_view exits : {"none", ""}) {
        auto mine = start_with(std::string(exits), own, began + 400);
        ATLAS_REQUIRE(mine.has_value(), mine.error().line);
        CHECK(mine->discarded_line == "vk guard: frames marker of this running process, not counted");
        CHECK(mine->guard_line.empty());
        CHECK_FALSE(mine->retried);
    }
    // 5b. This process's id with another start is another process, one whose id this one reused: looked up among the
    // exits as any other (no record of it: it counts), never taken for this one's.
    auto reused = start_with("none", own, began + 60'000);
    ATLAS_REQUIRE(reused.has_value(), reused.error().line);
    CHECK(reused->discarded_line.empty());
    CHECK(reused->exit == "no record");
    CHECK(reused->guard_line.starts_with("vk guard frames "));
    CHECK(reused->retried);
    // 5c. A routine trim of a cached process (REASON_OTHER: "too many cached", MK/ProcessExits.kt) and a signal while
    // cached are kills too: the façade passes them as not counted.
    for (const std::string_view reason : {"other", "signaled (cached)"}) {
        auto trimmed = start_with(exit_of(child.pid, later, false, reason));
        ATLAS_REQUIRE(trimmed.has_value(), trimmed.error().line);
        CHECK(trimmed->discarded_line == std::format("vk guard: frames marker after {}, not counted", reason));
        CHECK_FALSE(trimmed->retried);
    }
    // 6. A retry that was stopped goes on as the retry: discarded, its start runs without the cache at attempt 2, so
    // the next crash turns Vulkan off.
    std::string retry = marker;
    retry.replace(retry.find("\nattempt 1\n"), 11, "\nattempt 2\n");
    std::ofstream(dir / "guard", std::ios::trunc) << retry;
    const Child again = spawn_child(dir, "frames", "build-k", exit_of(child.pid, later, false, "user-requested"));
    CHECK(aborted(again));
    CHECK(has(again.log, "vk guard: frames marker after user-requested, not counted"));
    CHECK(has(read(dir / "guard"), "\nattempt 2\n"));
    {
        gpu::StartOptions o = options_in(dir, "", "build-k");
        o.exits = gpu::parse_exits(exit_of(again.pid, now_ms(), true, "crash-native"));
        auto off = gpu::start_guarded(o);
        ATLAS_REQUIRE(!off.has_value(), "a crash after a stopped retry did not turn Vulkan off");
        CHECK(off.error().line.starts_with("vk guard frames "));
        CHECK(has(off.error().line, " attempt 2 drawing frame (configuration "));
        CHECK(off.error().line.ends_with(" build build-k"));
    }
    std::error_code ec;
    fs::remove_all(dir, ec);
}

TEST_CASE("10 bits turned off in a retry start say the attempt it crashed in") {
    // A crash in the 8-bit frame's composite, then, in the retry, one in the 10-bit frame's: the colour verdict names
    // attempt 2, at the start that finds it and in every later start's facts (the off line keeps the attempt).
    if (!test::open_session()) return;
    const fs::path dir = fresh("attempt");
    {
        auto probe = gpu::start_guarded(options_in(dir, "", "build-t"));
        ATLAS_REQUIRE(probe.has_value(), probe.error().reason);
        probe->device->check_ten_bit();
        if (!probe->device->facts().deepest.ten_bit()) {
            MESSAGE("skipped: no 10 bits here");
            std::error_code ec;
            fs::remove_all(dir, ec);
            return;
        }
    }
    CHECK(aborted(spawn_child(dir, plant_at, "build-t")));
    CHECK(aborted(spawn_child(dir, colour_plant_at, "build-t")));
    CHECK(has(read(dir / "guard"), "stage colour\n"));
    CHECK(has(read(dir / "guard"), "\nattempt 2\n"));
    {
        auto next = gpu::start_guarded(options_in(dir, "", "build-t"));
        ATLAS_REQUIRE(next.has_value(), next.error().line);
        CHECK(has(next->guard_line, std::format(" attempt 2 pipeline {}", planted)));
        CHECK(has(next->device->facts().ten_bit_off, " attempt 2 "));
    }
    {
        auto later = gpu::start_guarded(options_in(dir, "", "build-t"));
        ATLAS_REQUIRE(later.has_value(), later.error().line);
        CHECK(later->guard_line.empty());
        CHECK(has(later->device->facts().ten_bit_off, std::format(" attempt 2 pipeline {}", planted)));
    }
    std::error_code ec;
    fs::remove_all(dir, ec);
}

namespace {

/// What the marker on disk names besides its stage ("" for nothing, or no marker).
[[nodiscard]] std::string named_in(const fs::path& dir) {
    const std::string text = read(dir / "guard");
    const std::size_t first = text.find('\n');
    if (first == std::string::npos) return {};
    const std::string line = text.substr(first + 1, text.find('\n', first + 1) - first - 1);
    return line.starts_with("pipeline ") || line.starts_with("module ") || line.starts_with("release ") ? line
                                                                                                        : std::string();
}

/// The stage the marker on disk names ("" for none).
[[nodiscard]] std::string stage_in(const fs::path& dir) {
    const std::string text = read(dir / "guard");
    if (!text.starts_with("stage ")) return {};
    return text.substr(6, text.find('\n') - 6);
}

} // namespace

TEST_CASE("the crash guard's marker names an announced call for that call alone") {
    // A name lasts as long as what announce returned (gpu/Pipelines.hpp's Announced), the announced call: once it
    // returns, the marker names the stage alone, or the call it ran inside, so that whatever the driver does next,
    // unannounced, is never charged to the module, pipeline or thing named before.
    const fs::path dir = fresh("notes");
    gpu::Guard guard(dir, "host-test-system", "build-n", "");
    const gpu::PipelineLabel box{.name = "box", .module = "standin_standin"};
    guard.enter(gpu::Stage::pipelines);
    CHECK(stage_in(dir) == "pipelines");
    CHECK(named_in(dir).empty());
    {
        const gpu::Announced named = gpu::announce(box, &guard);
        CHECK(named_in(dir) == "pipeline box (module standin_standin, configuration any)");
    }
    CHECK(stage_in(dir) == "pipelines");
    CHECK(named_in(dir).empty());
    {
        const gpu::Announced frame = gpu::announce_release(
            {.kind = "frame", .detail = "(configuration C renderpass composite resolve rgba8)"}, &guard);
        CHECK(named_in(dir) == "release frame (configuration C renderpass composite resolve rgba8)");
        {
            const gpu::Announced inner = gpu::announce_module("standin_composite", &guard);
            CHECK(named_in(dir) == "module standin_composite");
        }
        CHECK(named_in(dir) == "release frame (configuration C renderpass composite resolve rgba8)");
    }
    CHECK(named_in(dir).empty());
    guard.leave();
    CHECK_FALSE(fs::exists(dir / "guard"));
    // A pipeline outside the pipelines and colour stages is named by none, yet its end pairs with it: the thing going
    // around it keeps its name until it has gone.
    guard.enter(gpu::Stage::surface);
    {
        const gpu::Announced swapchain = gpu::announce_release({.kind = "swapchain", .detail = "rgba8"}, &guard);
        {
            const gpu::Announced unnamed = gpu::announce(box, &guard);
            CHECK(named_in(dir).empty());
        }
        CHECK(named_in(dir) == "release swapchain rgba8");
    }
    CHECK(named_in(dir).empty());
    CHECK(stage_in(dir) == "surface");
    guard.leave();
    // A stage's change inside a named call ends the name, and the call's end then changes nothing.
    guard.enter(gpu::Stage::pipelines);
    {
        const gpu::Announced named = gpu::announce(box, &guard);
        guard.leave();
        guard.enter(gpu::Stage::cache);
        CHECK(named_in(dir).empty());
    }
    CHECK(stage_in(dir) == "cache");
    guard.leave();
    // Inside the colour stage, the same: each name for its call, the colour stage's marker throughout.
    guard.enter_colour();
    guard.enter(gpu::Stage::pipelines);
    {
        const gpu::Announced named = gpu::announce({.name = "composite", .module = "standin_composite"}, &guard);
        CHECK(stage_in(dir) == "colour");
        CHECK(named_in(dir) == "pipeline composite (module standin_composite, configuration any)");
    }
    CHECK(named_in(dir).empty());
    guard.leave();
    CHECK(stage_in(dir) == "colour");
    guard.leave_colour();
    CHECK_FALSE(fs::exists(dir / "guard"));
    std::error_code ec;
    fs::remove_all(dir, ec);
}

TEST_CASE("a refused start lets go of what it made behind a stage of its own, keyed to the system build") {
    // Once the instance exists, a start the guard refuses (Vulkan off for the driver) or a step that fails destroys
    // what it made: the device, if any, and the instance. That runs at every launch once a driver is off, so a driver
    // that crashed there would crash every launch: behind the refused stage, keyed to the system build as the instance
    // stage is, its first crash retries and its second refuses the next start before the instance, crashing no more.
    if (!test::open_session()) return;
    const fs::path dir = fresh("refused");
    std::string driver;
    std::string version;
    {
        auto probe = gpu::start_guarded(options_in(dir, "", "build-r"));
        ATLAS_REQUIRE(probe.has_value(), probe.error().reason);
        std::tie(driver, version) = gpu::guard_key(probe->device->facts());
    }
    // Vulkan off for this driver and build: the start is refused once it knows the driver, after the instance.
    std::ofstream(dir / "off", std::ios::trunc) << std::format("{}|{}|build-r|pipelines||2\n", driver, version);
    {
        auto refused = gpu::start_guarded(options_in(dir, "", "build-r"));
        ATLAS_REQUIRE(!refused.has_value(), "a start with Vulkan off for its driver opened");
        CHECK(refused.error().line.starts_with("vk guard pipelines "));
        CHECK_FALSE(fs::exists(dir / "guard")); // its teardown's stage closed behind it
    }
    // A crash as the refused start's instance goes: the marker is the refused stage's, keyed to the system build.
    Child child = spawn_child(dir, "refused:release:instance", "build-r");
    CHECK(aborted(child));
    std::string marker = read(dir / "guard");
    MESSAGE("marker as a refused start's instance went:\n" << marker);
    CHECK(marker.starts_with("stage refused\nrelease instance (a refused start's)\ndriver unknown\nversion "
                             "host-test-system\nbuild build-r\nattempt 1\n"));
    // The next start retries (without the cache), is refused again, and crashes again there.
    child = spawn_child(dir, "refused:release:instance", "build-r");
    CHECK(aborted(child));
    CHECK(has(child.log, "vk guard refused unknown host-test-system attempt 1 release instance (a refused start's) "
                         "build build-r: retrying once without the pipeline cache"));
    marker = read(dir / "guard");
    CHECK(has(marker, "\nattempt 2\n"));
    // The start after: Vulkan off for the system build, refused before the instance, so nothing is made and nothing
    // crashes, at this and every later start of this build.
    for (int i = 0; i < 2; ++i) {
        auto off = gpu::start_guarded(options_in(dir, "", "build-r"));
        ATLAS_REQUIRE(!off.has_value(), "the start after two crashes in a refused start's teardown opened");
        CHECK(off.error().line ==
              "vk guard refused unknown host-test-system attempt 2 release instance (a refused start's) build build-r");
        CHECK_FALSE(fs::exists(dir / "guard"));
    }
    CHECK(has(read(dir / "off"), "system|host-test-system|build-r|refused|release instance (a refused start's)|2\n"));
    // Another build of the core starts afresh: neither verdict is its.
    CHECK(gpu::start_guarded(options_in(dir, "", "build-s")).has_value());
    std::error_code ec;
    fs::remove_all(dir, ec);
}

#endif
