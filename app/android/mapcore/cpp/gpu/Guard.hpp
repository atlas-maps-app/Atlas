// The crash guard (plan-vector D5.10). Most driver failures on Android are native crashes inside the vendor's driver
// (in pipeline compilation, cache loading or the first draws), and a crash at start repeats on every launch. So before
// each risky stage the guard writes a marker (the stage, the driver, the attempt), synced to disk, and deletes it
// after. At the next start a surviving marker means a crash in that stage: the first time, the core retries once with
// the pipeline cache deleted; the second time, Vulkan is turned off for that driver and version, and the facts carry
// the `vk guard` line. A driver update (another version) re-enables it once.
//
// Files in the guard's folder (noBackupFilesDir/vk on the phone): `guard` (the marker), `off` (the drivers turned
// off, one per line), `facts` (the last facts read, shown when Vulkan is off).
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Instance.hpp"
#include "gpu/PipelineCache.hpp"

#include <cstdint>
#include <expected>
#include <filesystem>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

namespace atlas::gpu {

/// The guarded stages, in the order a start passes them.
enum class Stage : std::uint8_t { instance, device, cache, pipelines, frames };

[[nodiscard]] std::string_view name_of(Stage stage) noexcept;
[[nodiscard]] std::optional<Stage> stage_named(std::string_view name) noexcept;

/// What the last start left, as the guard reads it before this one.
struct GuardVerdict {
    /// Vulkan is off for this driver (or, before the driver is known, for this system build).
    bool off = false;
    /// The last start crashed once: retry with the pipeline cache deleted.
    bool retry_without_cache = false;
    /// The `vk guard <stage> <driver> <version> attempt <n>` line, when a crash was found or Vulkan is off.
    std::string line;
};

/// The guard of one process's core.
class Guard {
public:
    /// `dir` holds the guard's files; `system` names the system build (Android's build fingerprint), the driver's
    /// stand-in before the driver is known; `plant` names a stage at which to abort on purpose (debug intent extra
    /// `atlas.vk.plant`, the planted-crash test), or is empty.
    Guard(std::filesystem::path dir, std::string system, std::string plant);

    /// Before the instance: a marker from the last start, or Vulkan off for this system build.
    [[nodiscard]] GuardVerdict check_start();
    /// Once the driver is known (before the device): Vulkan off for this driver and version.
    [[nodiscard]] GuardVerdict check_driver(std::string_view driver, std::string_view version);

    /// Writes the marker for `stage` (synced), then aborts there if the stage is planted.
    void enter(Stage stage);
    /// Deletes the marker: the stage passed.
    void leave();
    /// The stage whose marker is on disk, if any.
    [[nodiscard]] std::optional<Stage> current() const noexcept { return current_; }

    /// Keeps the facts' text, shown when Vulkan is off later.
    void keep_facts(std::string_view text) const;
    /// The facts kept by an earlier start ("" when none).
    [[nodiscard]] std::string kept_facts() const;
    /// Forgets every crash and every driver turned off (a fresh install does the same).
    void clear() const;

    [[nodiscard]] const std::filesystem::path& dir() const noexcept { return dir_; }

private:
    void write_marker() const;

    std::filesystem::path dir_;
    std::string system_;
    std::string plant_;
    std::string driver_ = "unknown";
    std::string version_;
    std::optional<Stage> current_;
    std::uint32_t attempt_ = 1;
};

/// How the core starts Vulkan.
struct StartOptions {
    std::filesystem::path guard_dir;  // the guard's folder (noBackupFilesDir/vk); empty: no guard
    std::filesystem::path cache_file; // the pipeline cache (cacheDir/vk/pipelines.bin); empty: none on disk
    std::string system;               // the system build (Android's build fingerprint)
    std::string plant;                // a stage to abort at on purpose (debug and bench builds)
    bool validation = false;
    bool require_validation = false;
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
    bool retried = false;   // this start is the retry without the pipeline cache
};

/// Why a guarded start gave up: Vulkan off by the guard, or a step that failed.
struct StartRefusal {
    std::string line;       // "vk guard …" when the guard has Vulkan off
    std::string reason;     // in words
    std::string kept_facts; // the facts an earlier start kept, shown in their place
};

/// The driver's name and version as the guard keys them ("ArmProprietary", "0x…").
[[nodiscard]] std::pair<std::string, std::string> guard_key(const Facts& facts);

/// Opens Vulkan behind the guard: the guard's verdict, the instance, the driver's verdict, the device and the pipeline
/// cache, each stage marked on disk while it runs (D5.10). The facts are kept for the honest state.
[[nodiscard]] std::expected<Started, StartRefusal> start_guarded(const StartOptions& options);

} // namespace atlas::gpu
