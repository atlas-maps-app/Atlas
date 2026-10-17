// The crash guard (plan-vector D5.10). Most driver failures on Android are native crashes inside the vendor's driver
// (in pipeline compilation, cache loading or the first draws), and a crash at start repeats on every launch. So before
// each risky stage the guard writes a marker (the stage, the driver, the core's build, the attempt), synced to disk,
// and deletes it after; in the pipelines stage it also writes each pipeline into the marker before the pipeline is made
// (its name, its module and the frame configuration, gpu/Pipelines.hpp). At the next start a surviving marker means a
// crash in that stage, and the `vk guard` line names the pipeline: the first time, the core retries once with the
// pipeline cache deleted; the second time, Vulkan is turned off for that driver and version and this build of the
// core, and the facts carry the line. A driver update (another version) or another build of the core (its build ID,
// base/build_info.hpp) tries again; so does a reset (VK facts' "Reset guard" in the debug and bench builds).
//
// Files in the guard's folder (noBackupFilesDir/vk on the phone): `guard` (the marker, replaced whole on each write),
// `off` (the drivers turned off, a line each: driver|version|build|stage|pipeline, or
// system|<fingerprint>|build|stage|pipeline when the driver was not known yet), `facts` (the last facts read, shown
// when Vulkan is off). An off line from before build IDs (driver|version|stage, alpha 6) names no build, so it turns
// off none.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Instance.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"

#include <cstdint>
#include <expected>
#include <filesystem>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::gpu {

/// The guarded stages, in the order a start passes them.
enum class Stage : std::uint8_t { instance, device, cache, pipelines, frames };

[[nodiscard]] std::string_view name_of(Stage stage) noexcept;
[[nodiscard]] std::optional<Stage> stage_named(std::string_view name) noexcept;

/// What the last start left, as the guard reads it before this one.
struct GuardVerdict {
    /// Vulkan is off for this driver and this build (or, before the driver is known, for this system build).
    bool off = false;
    /// The last start crashed: delete the pipeline cache before this one (the retry, or a new build's first start).
    bool retry_without_cache = false;
    /// The `vk guard <stage> <driver> <version> attempt <n>[ pipeline <pipeline>] build <id>` line, when a crash was
    /// found or Vulkan is off.
    std::string line;
};

/// One driver the guard turned off, as its off file records it.
struct GuardOff {
    std::string driver;   // "system" when the crash came before the driver was known
    std::string version;  // the driver's version, or the system build's fingerprint
    std::string build;    // the core's build ID; empty on a line from before build IDs
    std::string stage;    // the stage that crashed twice
    std::string pipeline; // the pipeline it was making ("" outside the pipelines stage)
};

/// The guard of one process's core: told about each pipeline before it is made.
class Guard final : public PipelineWatch {
public:
    /// `dir` holds the guard's files; `system` names the system build (Android's build fingerprint), the driver's
    /// stand-in before the driver is known; `build` is the core's build ID (base/build_info.hpp), whose verdicts these
    /// are; `plant` names a stage at which to abort on purpose (debug intent extra `atlas.vk.plant`, the planted-crash
    /// test): `pipelines` aborts as the stage's first pipeline is about to be made, `pipelines:<name>` as that pipeline
    /// is, any other stage as it is entered; empty plants nothing.
    Guard(std::filesystem::path dir, std::string system, std::string build, std::string plant);

    /// Before the instance: a marker from the last start, or Vulkan off for this system build.
    [[nodiscard]] GuardVerdict check_start();
    /// Once the driver is known (before the device): Vulkan off for this driver and version.
    [[nodiscard]] GuardVerdict check_driver(std::string_view driver, std::string_view version);

    /// Writes the marker for `stage` (synced), then aborts there if the stage is planted.
    void enter(Stage stage);
    /// In the pipelines stage: writes the pipeline about to be made into the marker (synced), so that a crash in the
    /// driver's compiler names it at the next start; then aborts there if it is planted. Outside the stage, nothing.
    void before(const PipelineLabel& label) override;
    /// Deletes the marker: the stage passed.
    void leave();
    /// The stage whose marker is on disk, if any.
    [[nodiscard]] std::optional<Stage> current() const noexcept { return current_; }

    /// Keeps the facts' text, shown when Vulkan is off later.
    void keep_facts(std::string_view text) const;
    /// The facts kept by an earlier start ("" when none).
    [[nodiscard]] std::string kept_facts() const;
    /// Every driver turned off, for any build.
    [[nodiscard]] std::vector<GuardOff> turned_off() const;
    /// The guard's state as VK facts prints it: `guard.build`, `guard.driver` and a `guard.off` line per driver turned
    /// off (or `guard.off: none`).
    [[nodiscard]] std::vector<std::string> state() const;
    /// Forgets every crash and every driver turned off, and deletes the pipeline cache at `cache_file` (if named), as
    /// a fresh install does; says what it forgot.
    [[nodiscard]] std::string clear(const std::filesystem::path& cache_file = {}) const;

    [[nodiscard]] const std::filesystem::path& dir() const noexcept { return dir_; }
    [[nodiscard]] const std::string& build() const noexcept { return build_; }

private:
    void write_marker() const;
    [[noreturn]] void abort_planted(std::string_view where) const;

    std::filesystem::path dir_;
    std::string system_;
    std::string build_;
    std::string plant_;
    std::string driver_ = "unknown";
    std::string version_;
    std::optional<Stage> current_;
    std::string pipeline_; // the pipeline the marker names, in the pipelines stage
    std::uint32_t attempt_ = 1;
};

/// How the core starts Vulkan.
struct StartOptions {
    std::filesystem::path guard_dir;  // the guard's folder (noBackupFilesDir/vk); empty: no guard
    std::filesystem::path cache_file; // the pipeline cache (cacheDir/vk/pipelines.bin); empty: none on disk
    std::string system;               // the system build (Android's build fingerprint)
    std::string build;                // the core's build ID (base/build_info.hpp), which keys the guard's verdicts
    std::string plant;                // a stage to abort at on purpose (debug and bench builds)
    bool validation = false;
    bool require_validation = false;
    bool present = false;     // the device presents (DeviceOptions::present): the façade's always does
    bool msaa2_close = false; // K6's answer for configuration B
    std::uint32_t max_version = VK_API_VERSION_1_4;
    std::string loader;
    std::uint64_t spirv = 0; // the hash of the core's shaders (the cache's key)
};

/// What a guarded start made, destroyed in the right order (the cache, the device, the instance, the guard).
struct Started {
    std::unique_ptr<Guard> guard;
    std::unique_ptr<Instance> instance;
    std::unique_ptr<Device> device;
    std::unique_ptr<PipelineCache> cache;
    std::string guard_line; // the crash this start found ("vk guard …"), else empty
    bool retried = false;   // this start runs without the pipeline cache, after a crash
};

/// Why a guarded start gave up: Vulkan off by the guard, or a step that failed.
struct StartRefusal {
    std::string line;               // "vk guard …" when the guard has Vulkan off
    std::string reason;             // in words
    std::string kept_facts;         // the facts an earlier start kept, shown in their place
    std::vector<std::string> guard; // the guard's state (Guard::state)
};

/// The driver's name and version as the guard keys them ("ArmProprietary", "0x…").
[[nodiscard]] std::pair<std::string, std::string> guard_key(const Facts& facts);

/// Opens Vulkan behind the guard: the guard's verdict, the instance, the driver's verdict, the device and the pipeline
/// cache, each stage marked on disk while it runs (D5.10). The facts are kept for the honest state.
[[nodiscard]] std::expected<Started, StartRefusal> start_guarded(const StartOptions& options);

} // namespace atlas::gpu
