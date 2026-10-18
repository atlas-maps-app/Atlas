// The crash guard (plan-vector D5.10). Most driver failures on Android are native crashes inside the vendor's driver
// (in pipeline compilation, cache loading or the first draws), and a crash at start repeats on every launch. So every
// call the core makes of the driver runs behind one of the guard's stages: before the stage the guard writes a marker
// (the stage, the driver, the core's build, the attempt, the process's id and start), synced to disk, and deletes it
// after. The stages, in the order a start and then the core's session (standin/Session.hpp) pass them:
//   instance   the loader and the instance;
//   facts      the device's facts: every query the choice of the device makes of the driver (collect_facts), a start's
//              and a rebuild's after a device loss;
//   device     the device and its allocator, and after a device loss the lost device's teardown (its stand-in, the
//              pipeline cache, the device);
//   cache      a pipeline cache made or loaded (the one on disk, and VK bench's timing's), every write back
//              (vkGetPipelineCacheData), and the timing's caches as they go;
//   surface    the window's surface and its queries, and the swapchain at the frame's 8 bits: made, remade, destroyed;
//   pipelines  the shader modules and pipelines, made, and a frame's, destroyed as the frame goes;
//   frames     the first 120 frames of each renderer: of each configuration VK bench draws, of a frame made again for a
//              new size, of the device rebuilt after a loss (the frame loop's per-frame calls after them run unmarked:
//              D5.10 guards the first draws); it ends whenever the frame loop rests (nothing to draw, or the app's
//              windows hidden), the renderer goes or the frame it draws changes (another configuration, an output
//              made again), and opens again at the next draw within them, naming the frame drawn then;
//   profiles   Android's Vulkan profiles (the Profiles library's checks), read only when VK facts or VK bench asks,
//              never on the start's path: they decide nothing, VK facts reports them;
//   colour     VK bench's colour stage (below);
//   close      the core's close: the stand-in's shared objects, the pipeline cache, the device and its allocator, the
//              instance;
//   refused    a start the guard or a failed step refused: what it had made goes (the device, the instance).
// While the driver makes a shader module or a pipeline, the marker also names it (in the pipelines and colour stages:
// a module by its symbol, a pipeline by its name, its module and the frame configuration, gpu/Pipelines.hpp), and while
// the driver destroys something as a whole, in any stage, it names that (gpu/Pipelines.hpp's Release: a frame, a
// swapchain, a pipeline cache, the stand-in, the device, the instance). Each name lasts its call alone: once the call
// returns the marker names the stage again (rewritten without a sync, since a process's crash leaves the page cache
// whole), so whatever the driver does next, unannounced, is the stage's and no one object's. Between named calls the
// frames stage's marker names the frame it draws (`drawing frame (configuration <…> <colour>)`), so that a crash in a
// renderer's first draws says which configuration it was.
//
// VK bench draws configurations, routes, forms and targets the facts would not pick, to measure them: its cells. A
// renderer tells the guard whose frame its work is (gpu/Pipelines.hpp's PipelineWatch::frame_of), and while that frame
// is a cell other than the facts' own pick (set_pick), the marker of the pipelines, frames and cache stages names the
// cell too (`cell <configuration>`): its pipelines, passes and images made and let go of, its first 120 frames, and a
// write of the pipeline cache while it is drawn (the write that first holds its pipelines comes right after they are
// made, standin/Session.hpp). A crash there proves only that cell bad on that driver. The stand-in's shared objects,
// the window's surface and swapchain, the device and every other step are work the map would do, and never a cell's;
// so is the pick's frame, whatever VK bench draws it for.
//
// At the next start a surviving marker means a crash in that stage, unless the process that left it was stopped, not
// crashed. A marker survives a swipe-away, a low-memory kill or a force-stop just as it survives a crash, so on API 30
// and newer the façade passes the app's process exits as Android records them (ApplicationExitInfo), and the marker's
// own process (its id, and an exit after its start) is looked up among them: killed by the user, the system (low
// memory, a routine trim of cached processes, the freezer) or the package's update, or ended by an uncaught exception
// of the app's own code (a Kotlin or Java bug, not the driver's: the core's build ID would not change with its fix),
// the marker is discarded and counts for nothing (`vk guard: <stage> marker after <reason>, not counted`, in VK facts);
// a native crash, a hang (an ANR: a driver hang repeats), a signal or an unknown end while in the foreground or
// visible, or no record at all, counts (the façade decides which reasons count: MK/ProcessExits.kt). A marker of this
// very process (its id and its start: an earlier core in it, which did not crash) counts for nothing either, whatever
// Android records. A discarded marker does not reset the ladder: the start goes on at its attempt. On API 29 every
// other marker counts.
//
// What a counted crash does depends on its stage:
//   instance, facts, refused: the first time, the core retries once with the pipeline cache deleted (a corrupt cache
//     can crash a driver); the second time Vulkan is turned off for the system build and this build of the core: every
//     start runs these before it knows the driver (a rebuild's facts after a device loss make the same queries, without
//     the window's surface, whose presentation the surface stage asks as at an attach, though the driver is known by
//     then), and a refused start's teardown at every launch once a driver is off, so only a verdict read before the
//     instance stops the crash coming back;
//   device, cache, surface, pipelines, frames, close: the same, for that driver and version and this build;
//   a VK bench cell's work (its marker names the cell): that cell alone off at once, for that driver and version and
//     this build (the rung below Vulkan off for work only VK bench does): VK bench skips it from then on, and Vulkan,
//     the pick and every other cell go on; the pipeline cache is kept unless the crash was in the cache stage, and a
//     retry stays the retry;
//   colour: 10 bits off at once, for that driver and version and this build (the rung below Vulkan off);
//   profiles: the profiles check off at once, for them; nothing else changes.
// The `vk guard` line names the stage, the cell, the module, pipeline or thing that went, the attempt and the build,
// and VK facts carries it. A verdict keyed to the driver ends with a driver update (another version), one keyed to the
// system build (instance, facts, refused) with a system update (another fingerprint; a GPU driver updated apart from
// the system keeps it); either with another build of the core (its build ID, base/build_info.hpp), or a reset (VK
// facts' "Reset guard" in the debug and bench builds).
//
// VK bench's colour stage, the frame at 10 bits a channel measured after its 8-bit runs (plan-vector D5, "Colour
// depth"), is open exactly while something of 10 bits may reach the driver: the device asked about the 10-bit formats
// (Device::check_ten_bit), the 10-bit swapchain, the shader modules and pipelines of the 10-bit frame (through a
// pipeline cache of the stage's own, which goes with it: the cache on disk never holds a 10-bit pipeline) and its
// frames, made and destroyed. It closes as soon as nothing of 10 bits is left: a 10-bit swapchain the driver refuses,
// or a device that cannot draw the frame in 10 bits after all, closes it before the frame's 8-bit swapchain is made in
// its place, outside it; a window gone, a refused remake or a lost device close it too. Outside it nothing of 10 bits
// is made, and the core's own questions about 10 bits are asked inside it alone: the start asks about the 8-bit formats
// only. The one other reader of the 10-bit formats, the Profiles library's checks of Android's profiles, runs in the
// profiles stage, never on the start's path and never inside this one, so a crash there turns off the profiles check,
// not 10 bits. While the colour stage is open every marker is its own, whatever runs inside it, and a crash in it turns
// off 10 bits only: the next start runs at 8 bits, VK facts' `colour:` line and VK bench's report say so, and
// everything else goes on as before. A reset brings 10 bits back.
//
// The guard's folder is one process's (MK/CoreConfig.kt keys it by the process's name), and one core runs in a process
// at a time (MK/MapCore.kt): the marker is a single file, which two cores at once would overwrite and delete under each
// other.
//
// Files in the guard's folder (noBackupFilesDir/vk on the phone): `guard` (the marker, replaced whole on each write),
// `off` (what is turned off, a line each: driver|version|build|stage|what|attempt, or system|<fingerprint>|build|stage|
// what|attempt when the driver was not known yet or the start was refused; the stage `colour` turns off 10 bits,
// `profiles` the profiles check, `cell` the VK bench cell its what names (driver|version|build|cell|<configuration>|
// attempt), any other Vulkan; what is "pipeline <…>", "module <…>" or "release <…>" when the driver was making a
// pipeline or a module or destroying something, "drawing <…>" in a frame's first draws), `facts`
// (the last facts read, shown when Vulkan is off). An off line from before build IDs (driver|version|stage, alpha 6)
// names no build, so it turns off none; one from before attempts (driver|version|build|stage|what, alpha 7's first)
// reads as attempt 2, or 1 for the colour stage.
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

/// The guarded stages (the file's head).
enum class Stage : std::uint8_t {
    instance,
    facts,
    device,
    cache,
    surface,
    pipelines,
    frames,
    profiles,
    colour,
    close,
    refused
};

[[nodiscard]] std::string_view name_of(Stage stage) noexcept;
[[nodiscard]] std::optional<Stage> stage_named(std::string_view name) noexcept;

/// One exit of the app's processes as Android recorded it (ApplicationExitInfo), as the façade passes it.
struct ProcessExit {
    std::int64_t pid = 0;
    std::int64_t at_ms = 0; // when it ended, in ms since the epoch (System.currentTimeMillis)
    /// A native crash, a hang, or a signal or an unknown end in the foreground or visible; false for a kill by the
    /// user, the system (a routine trim among them) or an update, an uncaught exception of the app's own code, and a
    /// signal or an unknown end in the background.
    bool counted = true;
    /// Its name: "crash-native", "anr", "user-requested", "low-memory", "signaled (cached)", "crash (an uncaught
    /// exception, not the driver's)", ….
    std::string reason;
};

/// The exits the façade passes into a start. Not `known` where Android keeps no records (API 29) or the caller has
/// none (hosts, unless a test injects them): then every marker counts but this process's own, as before API 30's
/// records.
struct ProcessExits {
    bool known = false;
    std::vector<ProcessExit> exits;
};

/// The façade's text of the exits: "" (none known), "none" (known, none recorded), else one line each,
/// "<pid> <ms> <counted 0|1> <reason>". A line that does not read is left out.
[[nodiscard]] ProcessExits parse_exits(std::string_view text);

/// What the last start left, as the guard reads it before this one.
struct GuardVerdict {
    /// Vulkan is off for this driver and this build (or, for a stage keyed to the system build, for it). A crash in
    /// VK bench's colour stage turns off 10 bits only (Guard::ten_bit_off), one in the profiles stage the profiles
    /// check (Guard::profiles_off), and the start goes on.
    bool off = false;
    /// Delete the pipeline cache before this one (the retry, a new build's first start).
    bool retry_without_cache = false;
    /// The `vk guard <stage> <driver> <version> attempt <n>[ cell <…>][ pipeline <…>| module <…>| release <…>| drawing
    /// <…>] build <id>` line, when a crash was found or Vulkan is off.
    std::string line;
    /// `vk guard: <stage> marker after <reason>, not counted` (or `… marker of this running process, not counted`) when
    /// the marker was left by a process that was stopped, not crashed: discarded, it counts for nothing.
    std::string discarded;
    /// How the process that left a counted marker ended, where Android recorded it ("crash-native", …; "no record").
    std::string exit;
};

/// One driver the guard turned off, or turned 10 bits, the profiles check or a VK bench cell off for, as its off file
/// records it.
struct GuardOff {
    std::string driver;  // "system" when the crash came before the driver was known
    std::string version; // the driver's version, or the system build's fingerprint
    std::string build;   // the core's build ID; empty on a line from before build IDs
    /// The stage that crashed (twice, but once for the colour and profiles stages), or "cell" for a VK bench cell
    /// (once).
    std::string stage;
    /// What the driver did: "pipeline <…>", "module <…>", "release <…>", "drawing <…>", or ""; for a cell, the cell
    /// ("B dynamic onepass resolve").
    std::string what;
    std::uint32_t attempt = 0; // the attempt of the start that crashed; 0 on a line from before attempts
};

/// The guard of one process's core: told about each shader module and pipeline before it is made, about each thing
/// before it goes, and as each of those calls returns. One thread at a time (the core's render thread, or the start
/// before it).
class Guard final : public PipelineWatch {
public:
    /// `dir` holds the guard's files; `system` names the system build (Android's build fingerprint), the driver's
    /// stand-in before the driver is known; `build` is the core's build ID (base/build_info.hpp), whose verdicts these
    /// are; `plant` names where to abort on purpose (debug intent extra `atlas.vk.plant`, the planted-crash tests),
    /// empty for nowhere:
    ///   `<stage>`                 as the stage is entered (`facts`, `surface`, `frames`, `profiles`, `close`,
    ///                             `refused`, …), but `pipelines` and `colour` at the stage's first pipeline, so the
    ///                             marker names it;
    ///   `<stage>:<name>`          in the pipelines or the colour stage, as the pipeline or shader module <name> is
    ///                             made (`pipelines:composite`, `colour:standin_composite`: in the colour stage only
    ///                             the 10-bit frame's are made, the stand-in's shared ones before it opens);
    ///   `<stage>:release`         as the stage's first thing goes; in the colour stage its first 10-bit frame or
    ///                             10-bit swapchain, whichever goes first, never anything of 8 bits nor its cache;
    ///   `<stage>:release:<kind>`  as the stage's first thing of that kind goes (gpu/Pipelines.hpp's Release: frame,
    ///                             swapchain, cache, standin, device, instance), in the colour stage a 10-bit one:
    ///                             `colour:release:frame` as the 10-bit frame goes, `colour:release:swapchain` as the
    ///                             10-bit swapchain goes, `colour:release:cache` as the stage's own pipeline cache
    ///                             goes, `pipelines:release:frame` as an 8-bit frame goes, `surface:release:swapchain`
    ///                             as an 8-bit swapchain goes, `close:release:cache` as the pipeline cache on disk goes
    ///                             at the close, `close:release:device` as the device goes at the close,
    ///                             `refused:release:instance` as a refused start's instance goes;
    ///   `cell:<plant>`            any of the above in a VK bench cell's work alone, its marker naming the cell
    ///                             (`cell:pipelines:box`, `cell:frames`, `cell:pipelines:release:frame`, `cell:cache`):
    ///                             the first such moment of a configuration other than the facts' pick.
    /// A plant fires only in the stage the marker names: none of another stage fires inside the colour stage.
    Guard(std::filesystem::path dir, std::string system, std::string build, std::string plant);

    /// When this process started, in ms since the epoch (the façade's; a start may give 0, and then the time this
    /// process's first guard was made stands for it): the marker carries it with the process's id, so that the next
    /// start finds the process's exit among Android's records, and a start in this same process knows it for its own.
    void set_process_start(std::int64_t ms) noexcept;
    /// The frame the frames stage draws ("frame (configuration A renderpass composite resolve rgba8)", as a release
    /// names it), which its marker carries (`drawing <frame>`), so that a crash in a renderer's first draws names the
    /// configuration and colour it drew; "" for none.
    void set_drawing(std::string_view frame);
    /// The facts' own pick (gpu/Facts.hpp's choice), the frame the map would draw: a renderer's frame of any other
    /// configuration is a VK bench cell (the file's head). Set once the device is known, and again after its rebuild.
    void set_pick(const Configuration& pick);
    /// The renderer's work that follows is the frame of `configuration`, or no frame's (nullptr): while that frame is a
    /// cell, the markers of the pipelines, frames and cache stages name it, rewritten as it changes.
    void frame_of(const Configuration* configuration) override;

    /// Before the instance: a marker from the last start (`exits` says how its process ended, where known), or Vulkan
    /// off for this system build.
    [[nodiscard]] GuardVerdict check_start(const ProcessExits& exits = {});
    /// Once the driver is known (before the device): Vulkan off for this driver and version.
    [[nodiscard]] GuardVerdict check_driver(std::string_view driver, std::string_view version);

    /// Writes the marker for `stage` (synced), then aborts there if the stage is planted. Inside the colour stage the
    /// marker stays the colour stage's. `Stage::colour` opens the colour stage (enter_colour).
    void enter(Stage stage);
    /// In the pipelines stage or the colour stage: writes the pipeline about to be made into the marker (synced), so
    /// that a crash in the driver's compiler names it at the next start; then aborts there if it is planted.
    /// Elsewhere, nothing (but after still pairs with it).
    void before(const PipelineLabel& label) override;
    /// The same for a shader module (vkCreateShaderModule).
    void before_module(std::string_view module) override;
    /// In any stage: the same for a thing about to go.
    void before_release(const Release& release) override;
    /// The call last named returned: the marker names what it named before (the stage alone, unless announcements
    /// nest), rewritten whole but not synced: a crash of the process leaves the page cache, and so the rewrite, whole.
    void after() override;
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
    /// The `vk guard profiles …` line when the profiles check is off for this driver and version and this build: it
    /// crashed under them. "" when it is not off.
    [[nodiscard]] std::string profiles_off() const;
    /// The `vk guard cell … <configuration> …` line when VK bench's cell `configuration` is off for this driver and
    /// version and this build: the driver crashed in its work. "" when it is not off, and always for the facts' pick,
    /// which follows Vulkan's ladder.
    [[nodiscard]] std::string cell_off(const Configuration& configuration) const;
    /// Keeps the facts' text, shown when Vulkan is off later.
    void keep_facts(std::string_view text) const;
    /// The facts kept by an earlier start ("" when none).
    [[nodiscard]] std::string kept_facts() const;
    /// Every driver turned off, or turned 10 bits, the profiles check or a VK bench cell off for, for any build.
    [[nodiscard]] std::vector<GuardOff> turned_off() const;
    /// The guard's state as VK facts prints it: `guard.build`, `guard.driver` and a `guard.off` line per driver turned
    /// off or turned something off for (or `guard.off: none`): `(this driver and build: Vulkan is off)`, `… 10 bits are
    /// off`, `… the profiles check is off`, `… VK bench's cell <configuration> is off`.
    [[nodiscard]] std::vector<std::string> state() const;
    /// Forgets every crash, every driver turned off and everything turned off for one (VK bench's cells among them),
    /// and deletes the pipeline cache at `cache_file` (if named), as a fresh install does; says what it forgot. Only
    /// with no core alive, or the core writes its cache back (jni/Bridge.cpp refuses it then).
    [[nodiscard]] std::string clear(const std::filesystem::path& cache_file = {}) const;

    [[nodiscard]] const std::filesystem::path& dir() const noexcept { return dir_; }
    [[nodiscard]] const std::string& build() const noexcept { return build_; }

private:
    /// Reads the last start's `marker` into `v` and deletes it: true when it decided the start (a crash found), false
    /// when the off file still has its say (a marker discarded as not counted).
    bool read_marker(const std::string& marker, const ProcessExits& exits, GuardVerdict& v);
    /// Writes the marker of the stage open now (if any) with what the driver is making or destroying; `synced` before
    /// a stage or a named call, not as a named call returns (after).
    void write_marker(bool synced = true) const;
    /// The `vk guard` line of the off line of `stage` (colour, profiles) for this driver and build, or "".
    [[nodiscard]] std::string rung_off(std::string_view stage) const;
    /// Whether the plant aborts at the pipeline (`pipeline`) or the shader module `name` about to be made.
    [[nodiscard]] bool planted_making(std::string_view name, bool pipeline) const;
    /// Whether the plant aborts as `release` goes.
    [[nodiscard]] bool planted_release(const Release& release) const;
    /// Whether the marker names the cell now: a cell's frame in the pipelines, frames or cache stage, outside the
    /// colour stage (whose marker is its own).
    [[nodiscard]] bool cell_marked() const noexcept;
    /// Whether a plant may fire here: anywhere, or only in a cell's work (`cell:`).
    [[nodiscard]] bool plant_here() const noexcept { return !plant_cell_ || cell_marked(); }
    /// Writes `what` into the marker ("" for a call this stage does not name), then aborts if `planted`.
    void note(std::string what, bool planted);
    [[noreturn]] void abort_planted(std::string_view where) const;
    /// The name of the stage the marker names now ("" for none).
    [[nodiscard]] std::string_view marked() const noexcept;
    /// What the marker names besides its stage: the innermost call named and not returned yet ("" for none).
    [[nodiscard]] std::string_view making() const noexcept;
    /// The stage the marker names, and the cell where it names one, as the planted crash's line says them.
    [[nodiscard]] std::string where() const;

    std::filesystem::path dir_;
    std::string system_;
    std::string build_;
    std::string plant_;
    std::string driver_ = "unknown";
    std::string version_;
    std::optional<Stage> current_;
    bool colour_ = false; // the colour stage is open
    /// The calls announced and not returned yet, innermost last: "pipeline <…>", "module <…>", "release <…>", or ""
    /// for one this stage does not name. A stage's change clears them.
    std::vector<std::string> notes_;
    std::string drawing_;               // the frame the frames stage draws (set_drawing)
    std::optional<Configuration> pick_; // the facts' own pick (set_pick)
    std::string cell_;                  // the VK bench cell whose frame the renderer works on ("" for none, the pick)
    bool plant_cell_ = false;           // the plant fires in a cell's work alone (`cell:`)
    std::uint32_t attempt_ = 1;
    std::int64_t pid_ = 0;
    std::int64_t start_ms_ = 0;
};

/// How the core starts Vulkan.
struct StartOptions {
    std::filesystem::path guard_dir;   // the guard's folder (noBackupFilesDir/vk); empty: no guard
    std::filesystem::path cache_file;  // the pipeline cache (cacheDir/vk/pipelines.bin); empty: none on disk
    std::string system;                // the system build (Android's build fingerprint)
    std::string build;                 // the core's build ID (base/build_info.hpp), which keys the guard's verdicts
    std::string plant;                 // where to abort on purpose (debug and bench builds; Guard's constructor)
    ProcessExits exits;                // how the app's last processes ended (API 30), which the guard reads a marker by
    std::int64_t process_start_ms = 0; // when this process started (ms since the epoch); 0: its first guard's time
    bool validation = false;
    bool require_validation = false;
    bool present = false;     // the device presents (DeviceOptions::present): the façade's always does
    bool msaa2_close = false; // K6's answer for configuration B
    std::uint32_t max_version = VK_API_VERSION_1_4;
    std::string loader;
    std::uint64_t spirv = 0; // the hash of the core's shaders (the cache's key)
};

/// What a guarded start made, destroyed in the right order (the cache, the device, the instance, the guard), behind the
/// close stage when the core closes (standin/Session.hpp).
struct Started {
    std::unique_ptr<Guard> guard;
    std::unique_ptr<Instance> instance;
    std::unique_ptr<Device> device;
    std::unique_ptr<PipelineCache> cache;
    DeviceOptions device_options; // how the device was chosen and opened (a rebuild after device loss opens it so)
    std::string guard_line;       // the crash this start found ("vk guard …"), else empty
    std::string discarded_line;   // a marker this start discarded ("vk guard: … not counted"), else empty
    std::string exit;             // how the process of a counted marker ended, where Android recorded it
    bool retried = false;         // this start runs without the pipeline cache, after a crash
};

/// Why a guarded start gave up: Vulkan off by the guard, or a step that failed.
struct StartRefusal {
    std::string line;               // "vk guard …" when the guard has Vulkan off
    std::string reason;             // in words
    std::string kept_facts;         // the facts an earlier start kept, shown in their place
    std::vector<std::string> guard; // the guard's state (Guard::state)
    std::string discarded;          // a marker the start discarded ("vk guard: … not counted"), else empty
};

/// The driver's name and version as the guard keys them ("ArmProprietary", "0x…").
[[nodiscard]] std::pair<std::string, std::string> guard_key(const Facts& facts);

/// Opens Vulkan behind the guard: the guard's verdict, the instance, the device's facts (the queries its choice
/// makes), the driver's verdict, the device and the pipeline cache, each stage marked on disk while it runs (D5.10).
/// The device's facts carry the colour and profiles stages' verdicts (Facts::ten_bit_off, Facts::profiles_off), and are
/// kept for the honest state; the guard knows the facts' pick (set_pick). A start refused once the instance exists lets
/// go of what it made (the device, the instance) behind the refused stage before it returns.
[[nodiscard]] std::expected<Started, StartRefusal> start_guarded(const StartOptions& options);

} // namespace atlas::gpu
