// The crash guard (plan-vector D5.10). Most driver failures on Android are native crashes inside the vendor's driver
// (in pipeline compilation, cache loading or the first draws), and a crash at start repeats on every launch. So before
// each risky stage the guard writes a marker (the stage, the driver, the core's build, the attempt), synced to disk,
// and deletes it after: the instance; the device's facts, every query the choice of the device makes of the driver;
// the device; the pipeline cache; the pipelines; the first 120 frames. In the pipelines stage it also writes into the
// marker each shader module and each pipeline before it is made (a module by its symbol, a pipeline by its name, its
// module and the frame configuration, gpu/Pipelines.hpp) and each frame before it goes (its main pass and the
// pipelines, passes and images made for it). At the next start a surviving marker means a crash in that stage, and
// the `vk guard` line names the module, the pipeline or the frame: the first time, the core retries once with the
// pipeline cache deleted; the second time, Vulkan is turned off for that driver and version and this build of the
// core, and the facts carry the line. A crash before the driver is known (the instance, the facts) is keyed by the
// system build instead. A driver update (another version) or another build of the core (its build ID,
// base/build_info.hpp) tries again; so does a reset (VK facts' "Reset guard" in the debug and bench builds).
//
// VK bench's colour stage, the frame at 10 bits a channel measured after its 8-bit runs (plan-vector D5, "Colour
// depth"), is guarded as a stage of its own, the rung below Vulkan off. Nothing of 10 bits reaches the driver outside
// it: the 10-bit formats are asked of the device, the 10-bit swapchain, its shader modules and pipelines and their
// frames are made, and all of them destroyed, while it is open (jni/Bridge.cpp). While it is open every marker is the
// colour stage's, whatever runs inside it, and a crash in it turns off 10 bits only, at once, for that driver and
// version and this build: the next start runs at 8 bits without the pipeline cache, VK facts' `colour:` line and VK
// bench's report say so, and everything else goes on as before. A reset brings 10 bits back.
//
// Files in the guard's folder (noBackupFilesDir/vk on the phone): `guard` (the marker, replaced whole on each write),
// `off` (what is turned off, a line each: driver|version|build|stage|what, or system|<fingerprint>|build|stage|what
// when the driver was not known yet; the stage `colour` turns off 10 bits, any other turns off Vulkan; what is
// "pipeline <…>", "module <…>" or "release <…>" when the driver was making a pipeline or a module or destroying a
// frame), `facts` (the last facts read, shown when Vulkan is off). An off line from before build IDs
// (driver|version|stage, alpha 6) names no build, so it turns off none.
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

/// The guarded stages, in the order a start passes them, then VK bench's colour stage (the file's head). `facts` is
/// the device's choice: every query it makes of the driver (collect_facts), before the driver is known.
enum class Stage : std::uint8_t { instance, facts, device, cache, pipelines, frames, colour };

[[nodiscard]] std::string_view name_of(Stage stage) noexcept;
[[nodiscard]] std::optional<Stage> stage_named(std::string_view name) noexcept;

/// What the last start left, as the guard reads it before this one.
struct GuardVerdict {
    /// Vulkan is off for this driver and this build (or, before the driver is known, for this system build). A crash in
    /// VK bench's colour stage turns off 10 bits only (Guard::ten_bit_off), and the start goes on.
    bool off = false;
    /// The last start crashed: delete the pipeline cache before this one (the retry, a new build's first start, or the
    /// start after a crash in the colour stage).
    bool retry_without_cache = false;
    /// The `vk guard <stage> <driver> <version> attempt <n>[ pipeline <…>| module <…>| release <…>] build <id>` line,
    /// when a crash was found or Vulkan is off.
    std::string line;
};

/// One driver the guard turned off, or turned 10 bits off for, as its off file records it.
struct GuardOff {
    std::string driver;  // "system" when the crash came before the driver was known
    std::string version; // the driver's version, or the system build's fingerprint
    std::string build;   // the core's build ID; empty on a line from before build IDs
    std::string stage;   // the stage that crashed (twice, but once for the colour stage, which turns off 10 bits)
    std::string what;    // what the driver was making or destroying: "pipeline <…>", "module <…>", "release <…>", or ""
};

/// The guard of one process's core: told about each shader module and pipeline before it is made, and each frame
/// before it goes.
class Guard final : public PipelineWatch {
public:
    /// `dir` holds the guard's files; `system` names the system build (Android's build fingerprint), the driver's
    /// stand-in before the driver is known; `build` is the core's build ID (base/build_info.hpp), whose verdicts these
    /// are; `plant` names a stage at which to abort on purpose (debug intent extra `atlas.vk.plant`, the planted-crash
    /// tests): `pipelines` aborts as the stage's first pipeline is about to be made, `pipelines:<name>` as that
    /// pipeline or shader module is, `pipelines:release` as the stage's first frame is about to go, `colour`,
    /// `colour:<name>` and `colour:release` the same in VK bench's colour stage, any other stage (`facts`, …) as it is
    /// entered; empty plants nothing.
    Guard(std::filesystem::path dir, std::string system, std::string build, std::string plant);

    /// Before the instance: a marker from the last start, or Vulkan off for this system build.
    [[nodiscard]] GuardVerdict check_start();
    /// Once the driver is known (before the device): Vulkan off for this driver and version.
    [[nodiscard]] GuardVerdict check_driver(std::string_view driver, std::string_view version);

    /// Writes the marker for one of the start's stages (synced), then aborts there if the stage is planted. Inside the
    /// colour stage the marker stays the colour stage's. `Stage::colour` opens the colour stage (enter_colour).
    void enter(Stage stage);
    /// In the pipelines stage or the colour stage: writes the pipeline about to be made into the marker (synced), so
    /// that a crash in the driver's compiler names it at the next start; then aborts there if it is planted.
    /// Elsewhere, nothing.
    void before(const PipelineLabel& label) override;
    /// The same for a shader module (vkCreateShaderModule).
    void before_module(std::string_view module) override;
    /// The same for a frame about to go (its pipelines, passes, framebuffers and images destroyed), or a swapchain.
    void before_release(std::string_view what) override;
    /// The stage entered passed: deletes the marker, or inside the colour stage writes the colour stage's again.
    void leave();
    /// Opens VK bench's colour stage: from here to leave_colour every marker is the colour stage's (synced), and a
    /// crash turns off 10 bits only (the file's head).
    void enter_colour();
    /// Closes the colour stage once its 10-bit work is gone: the marker is deleted, or is the stage's it ran inside.
    void leave_colour();
    /// The stage whose marker is on disk, if any (the colour stage's apart).
    [[nodiscard]] std::optional<Stage> current() const noexcept { return current_; }
    /// Whether the colour stage is open.
    [[nodiscard]] bool colour_open() const noexcept { return colour_; }

    /// The `vk guard colour …` line when 10 bits are off for this driver and version (once known) and this build: VK
    /// bench's colour stage crashed under them. "" when they are not off.
    [[nodiscard]] std::string ten_bit_off() const;
    /// Keeps the facts' text, shown when Vulkan is off later.
    void keep_facts(std::string_view text) const;
    /// The facts kept by an earlier start ("" when none).
    [[nodiscard]] std::string kept_facts() const;
    /// Every driver turned off, or turned 10 bits off for, for any build.
    [[nodiscard]] std::vector<GuardOff> turned_off() const;
    /// The guard's state as VK facts prints it: `guard.build`, `guard.driver` and a `guard.off` line per driver turned
    /// off or turned 10 bits off for (or `guard.off: none`).
    [[nodiscard]] std::vector<std::string> state() const;
    /// Forgets every crash, every driver turned off and every 10 bits turned off, and deletes the pipeline cache at
    /// `cache_file` (if named), as a fresh install does; says what it forgot. Only with no core alive, or the core
    /// writes its cache back (jni/Bridge.cpp refuses it then).
    [[nodiscard]] std::string clear(const std::filesystem::path& cache_file = {}) const;

    [[nodiscard]] const std::filesystem::path& dir() const noexcept { return dir_; }
    [[nodiscard]] const std::string& build() const noexcept { return build_; }

private:
    void write_marker() const;
    /// Whether the plant aborts at the pipeline (`pipeline`) or the shader module `name` about to be made, or (`name`
    /// "release") at the frame about to go.
    [[nodiscard]] bool planted_at(std::string_view name, bool pipeline) const;
    /// Writes `what` ("pipeline <…>", "module <…>", "release <…>") into the marker in the pipelines or colour stage,
    /// then aborts if the plant is `name`'s (planted_at).
    void note_making(std::string what, std::string_view name, bool pipeline);
    [[noreturn]] void abort_planted(std::string_view where) const;

    std::filesystem::path dir_;
    std::string system_;
    std::string build_;
    std::string plant_;
    std::string driver_ = "unknown";
    std::string version_;
    std::optional<Stage> current_;
    bool colour_ = false; // the colour stage is open
    std::string making_;  // "pipeline <…>", "module <…>" or "release <…>": what the marker names, in those two stages
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
    DeviceOptions device_options; // how the device was chosen and opened (a rebuild after device loss opens it so)
    std::string guard_line;       // the crash this start found ("vk guard …"), else empty
    bool retried = false;         // this start runs without the pipeline cache, after a crash
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

/// Opens Vulkan behind the guard: the guard's verdict, the instance, the device's facts (the queries its choice
/// makes), the driver's verdict, the device and the pipeline cache, each stage marked on disk while it runs (D5.10).
/// The device's facts carry the colour stage's verdict (Facts::ten_bit_off), and are kept for the honest state.
[[nodiscard]] std::expected<Started, StartRefusal> start_guarded(const StartOptions& options);

} // namespace atlas::gpu
