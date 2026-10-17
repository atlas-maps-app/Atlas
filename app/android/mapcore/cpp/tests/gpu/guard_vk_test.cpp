// The crash guard on the host's Vulkan (plan-vector D5.10, K1's host acceptance): a planted abort in pipeline creation
// leaves the guard's marker, which names the pipeline being made; the next start reads it, prints the `vk guard` line
// with that pipeline and retries once without the pipeline cache; when that crashes too, the start after turns Vulkan
// off for the driver, its version and this build of the core. A new driver version starts again, and so does another
// build of the core (its build ID), or a reset; off lines and markers from before build IDs turn nothing off. The
// crashing starts run in child processes: this test binary, spawned with one test case.
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
// for the host build's offscreen images (as VK bench's prepare does when PowerVR crashed); the Android build has no
// offscreen output (platform/host), so there the child makes the pipelines every configuration shares, the shadow's
// among them.
#if defined(__ANDROID__)
constexpr std::string_view planted = "shadow (module standin_standin";
constexpr const char* plant_at = "pipelines:shadow";
#else
constexpr std::string_view planted = "composite (module standin_composite";
constexpr const char* plant_at = "pipelines:composite";
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
    // pipelines stage, those every configuration shares and (on hosts) the configuration's, the composite among them,
    // each announced to the guard first; the plant (if any) aborts the process there.
    const char* dir = std::getenv("ATLAS_GUARD_CHILD"); // NOLINT(concurrency-mt-unsafe)
    if (dir == nullptr) return;
    const char* plant = std::getenv("ATLAS_GUARD_PLANT"); // NOLINT(concurrency-mt-unsafe)
    const char* build = std::getenv("ATLAS_GUARD_BUILD"); // NOLINT(concurrency-mt-unsafe)
    auto started = gpu::start_guarded(options_in(dir, plant == nullptr ? "" : plant, build == nullptr ? "" : build));
    ATLAS_REQUIRE(started.has_value(), started.error().reason);
    started->guard->enter(gpu::Stage::pipelines);
    auto city = std::make_shared<const standin::City>(standin::City::generate(32, 13.0));
    auto standin = standin::StandIn::create(*started->device, *started->cache, city, started->guard.get());
    ATLAS_REQUIRE(standin.has_value(), standin.error().text());
#if !defined(__ANDROID__)
    auto output = platform::Offscreen::create(*started->device, {.width = 64, .height = 64}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    auto renderer = gpu::Renderer::create(*started->device, **output, **standin, started->device->facts().choice);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    renderer->reset();
#endif
    started->guard->leave();
    // A start that survives writes its cache, as the core does after its pipelines.
    auto saved = started->cache->save();
    ATLAS_REQUIRE(saved.has_value(), saved.error().text());
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
    CHECK(has(off, std::format("|build-a|pipelines|{}", planted)));
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
    fs::remove_all(dir, ec);
}
