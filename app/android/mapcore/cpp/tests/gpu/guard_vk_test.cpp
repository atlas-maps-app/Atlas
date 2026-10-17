// The crash guard on the host's Vulkan (plan-vector D5.10, K1's host acceptance): a planted abort in pipeline creation
// leaves the guard's marker; the next start reads it and retries once without the pipeline cache; when that crashes
// too, the start after turns Vulkan off for the driver and its version and prints the `vk guard` line; a new driver
// version starts again. The crashing starts run in child processes: this test binary, spawned with one test case.
#include "gpu/Guard.hpp"
#include "gpu/PipelineCache.hpp"
#include "platform/host/Offscreen.hpp"
#include "standin/StandIn.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <fcntl.h>
#include <spawn.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <mach-o/dyld.h>
#endif

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

/// Runs this binary's child test case with the guard's folder and the plant; returns its wait status.
int spawn_child(const fs::path& dir, const std::string& plant) {
    const std::string exe = self_path();
    std::vector<std::string> args{exe, "--test-case=guard child start", "--no-intro=true"};
    std::vector<std::string> env;
    for (char** e = environ; *e != nullptr; ++e) env.emplace_back(*e);
    env.push_back("ATLAS_GUARD_CHILD=" + dir.string());
    env.push_back("ATLAS_GUARD_PLANT=" + plant);
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
    const std::string log = (dir / std::format("child-{}.log", ++children)).string();
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, log.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    posix_spawn_file_actions_adddup2(&actions, STDOUT_FILENO, STDERR_FILENO);
    pid_t pid = 0;
    const int spawned = ::posix_spawn(&pid, exe.c_str(), &actions, nullptr, argv.data(), envp.data());
    posix_spawn_file_actions_destroy(&actions);
    if (spawned != 0) return -1;
    int status = 0;
    (void)::waitpid(pid, &status, 0);
    return status;
}

[[nodiscard]] gpu::StartOptions options_in(const fs::path& dir, const std::string& plant) {
    return {.guard_dir = dir,
            .cache_file = dir / "pipelines.bin",
            .system = "host-test-system",
            .plant = plant,
            .validation = false,
            .spirv = 42};
}

} // namespace

TEST_CASE("guard child start") {
    // Runs only in the child the guard's test spawns: a guarded start, then the stand-in's pipelines behind the
    // pipelines stage, where the plant (if any) aborts the process.
    const char* dir = std::getenv("ATLAS_GUARD_CHILD"); // NOLINT(concurrency-mt-unsafe)
    if (dir == nullptr) return;
    const char* plant = std::getenv("ATLAS_GUARD_PLANT"); // NOLINT(concurrency-mt-unsafe)
    auto started = gpu::start_guarded(options_in(dir, plant == nullptr ? "" : plant));
    REQUIRE_MESSAGE(started.has_value(), started.error().reason);
    started->guard->enter(gpu::Stage::pipelines);
    auto city = std::make_shared<const standin::City>(standin::City::generate(32, 13.0));
    auto standin = standin::StandIn::create(*started->device, *started->cache, city);
    REQUIRE(standin.has_value());
    started->guard->leave();
    // A start that survives writes its cache, as the core does after its pipelines.
    REQUIRE(started->cache->save().has_value());
}

TEST_CASE("the crash guard: a planted abort, one retry without the cache, then Vulkan off for that driver") {
    if (!test::open_session()) return; // no Vulkan here: nothing to guard
    const fs::path dir = fs::temp_directory_path() / std::format("atlas-guard-{}", ::getpid());
    std::error_code ec;
    fs::remove_all(dir, ec);
    fs::create_directories(dir);

    // A clean start writes the pipeline cache.
    int status = spawn_child(dir, "");
    REQUIRE_MESSAGE(WIFEXITED(status), "the clean child did not exit");
    CHECK(WEXITSTATUS(status) == 0);
    CHECK(fs::exists(dir / "pipelines.bin"));
    CHECK_FALSE(fs::exists(dir / "guard"));

    // 1. A crash in pipeline creation: the marker stays, naming the stage and the first attempt.
    status = spawn_child(dir, "pipelines");
    CHECK(WIFSIGNALED(status));
    if (WIFSIGNALED(status)) CHECK(WTERMSIG(status) == SIGABRT);
    const std::string marker = read(dir / "guard");
    MESSAGE("marker after the first crash:\n" << marker);
    CHECK(marker.find("stage pipelines") != std::string::npos);
    CHECK(marker.find("attempt 1") != std::string::npos);
    CHECK(marker.find("driver unknown") == std::string::npos); // the driver was known by then

    // 2. The next start retries once without the pipeline cache (the file is gone), and crashes again.
    status = spawn_child(dir, "pipelines");
    CHECK(WIFSIGNALED(status));
    CHECK_FALSE(fs::exists(dir / "pipelines.bin"));
    const std::string marker2 = read(dir / "guard");
    CHECK(marker2.find("attempt 2") != std::string::npos);

    // 3. The start after: Vulkan off for this driver and version, the `vk guard` line, the facts kept.
    auto refused = gpu::start_guarded(options_in(dir, ""));
    REQUIRE_FALSE(refused.has_value());
    MESSAGE("refused: " << refused.error().line << " (" << refused.error().reason << ")");
    CHECK(refused.error().line.starts_with("vk guard pipelines "));
    CHECK(refused.error().line.ends_with(" attempt 2"));
    CHECK(refused.error().kept_facts.find("gpu: ") != std::string::npos);
    CHECK_FALSE(fs::exists(dir / "guard"));
    const std::string off = read(dir / "off");
    CHECK(off.find("|pipelines") != std::string::npos);

    // 4. It stays off at every later start of the same driver version.
    auto again = gpu::start_guarded(options_in(dir, ""));
    REQUIRE_FALSE(again.has_value());
    CHECK(again.error().line.starts_with("vk guard pipelines "));

    // 5. A driver update (another version) starts again.
    std::string updated = off;
    const std::size_t bar = updated.find('|');
    REQUIRE(bar != std::string::npos);
    const std::size_t version = updated.find('|', bar + 1);
    updated.replace(bar + 1, version - bar - 1, "0x00000000");
    std::ofstream(dir / "off", std::ios::trunc) << updated;
    auto after_update = gpu::start_guarded(options_in(dir, ""));
    CHECK(after_update.has_value());

    // A crash before the driver is known (the instance stage) keys the system build instead.
    fs::remove(dir / "off", ec);
    status = spawn_child(dir, "instance");
    CHECK(WIFSIGNALED(status));
    status = spawn_child(dir, "instance");
    CHECK(WIFSIGNALED(status));
    auto system_off = gpu::start_guarded(options_in(dir, ""));
    REQUIRE_FALSE(system_off.has_value());
    CHECK(system_off.error().line.starts_with("vk guard instance unknown host-test-system attempt 2"));
    fs::remove_all(dir, ec);
}
