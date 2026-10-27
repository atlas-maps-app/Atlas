// The core's session (plan-vector D5, D5.10, K1): what the core draws with once it has started, on its render thread:
// the window's surface and the output on it (a swapchain on the phone), VK bench's stand-in and the renderer that
// draws it in a configuration and a colour depth, the frames and their records, device loss rebuilt at most twice a
// session, the facts with Android's profiles, the pipeline cache's write-back, and the core's close.
//
// Every call it makes of the driver runs behind one of the crash guard's stages (gpu/Guard.hpp), the narrowest that
// holds it: the window's surface, its queries and the swapchain at 8 bits in the surface stage; the stand-in and a
// frame's pipelines, passes and images, made and destroyed, in the pipelines stage; a pipeline cache's making, loading
// and writes in the cache stage; the first 120 frames of each renderer in the frames stage; Android's profiles in the
// profiles stage; the GPU's noise (the map's screen fields of a size the disk does not hold, made on the GPU before the
// frame that wants them, which waits for them; VK facts' fields line) in the noise stage; everything of 10 bits in the
// colour stage; a lost device's teardown and its rebuild in the device stage (and the facts, cache and surface stages,
// as a start passes them); the close in the close stage. Each module, pipeline and thing that goes is named for its
// call alone (gpu/Pipelines.hpp's Announced). The one exception is the frame loop's own per-frame calls after a
// renderer's first 120 frames, which D5.10 leaves unguarded: the commands each frame records and submits, its acquire
// and present, its fences, queries and memory flushes, and the display's timings it reads back. No marker stays on disk
// while nothing is drawn: the frames stage ends when the frame loop rests (rest; a trim from TRIM_MEMORY_UI_HIDDEN
// rests it too), the renderer goes or the frame it draws changes (another configuration, an output made again), and
// opens again with the next draw within that renderer's first 120, naming the frame it draws; a frame loop at rest
// inside VK bench's colour stage leaves that stage as well, as mode 0 does. The stand-in's shared objects are 8-bit
// work, made before anything of 10 bits and never inside the colour stage, and written back to the pipeline cache
// before any frame's pipelines are.
//
// VK bench's cells (gpu/Guard.hpp): a frame of a configuration other than the facts' own pick is work only VK bench
// does, and the renderer tells the guard so (gpu/Pipelines.hpp's frame_of): a crash in its pipelines, its first 120
// frames, its going or a write of the pipeline cache while it is drawn turns that cell alone off. So the cache is
// written back right after each new frame's pipelines are made (a new renderer, another configuration), the write
// that first holds a cell's pipelines being the cell's; and a cell the guard turned off is refused (bench), nothing
// made for it.
//
// The colour depth (plan-vector D5, "Colour depth"): the frame is 8 bits a channel (gpu/Facts.hpp's default_depth);
// VK bench's colour stage (bench's flag 4) draws it at 10 where the surface offers them, the device (asked as the
// stage begins) can draw the frame in them and the crash guard has not turned them off. Into the stage, the 8-bit
// frame and the 8-bit swapchain go first, in their own stages; then the guard's colour stage opens, the device is asked
// about 10 bits, and the 10-bit swapchain and frame are made, the frame's pipelines through a pipeline cache of the
// stage's own. Out of it, the 10-bit frame, swapchain and cache go before the stage closes, and the 8-bit swapchain is
// made again after it. The stage closes as soon as nothing of 10 bits is left: where the device cannot draw the frame
// in 10 bits after all or the driver refuses the 10-bit swapchain, it closes before the 8-bit swapchain is made in its
// place, outside it; a refused remake, a window gone and a lost device close it too. So the colour stage holds 10-bit
// work alone, its every creation and destruction, a window that goes or comes back within it included.
//
// Platform-free: the phone's Bridge (jni/Bridge.cpp) runs it on its render thread with an Android window, and the host
// tests drive it in VK bench's own order with windows of their own (offscreen images in place of the swapchain where
// the host's Vulkan cannot make one), so the phone's flow runs under the hosts' tests and layers. One thread at a time,
// but records(), from any.
#pragma once

#include "gpu/Frames.hpp"
#include "gpu/Guard.hpp"
#include "gpu/PipelineCache.hpp"
#include "standin/City.hpp"
#include "standin/FrameRecord.hpp"
#include "standin/SessionMap.hpp"
#include "standin/StandIn.hpp"
#include "style/CameraState.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::standin {

/// A window the session draws into: an Android window on the phone (jni/Bridge.cpp), a test's on hosts.
class SessionWindow {
public:
    SessionWindow() = default;
    SessionWindow(const SessionWindow&) = delete;
    SessionWindow& operator=(const SessionWindow&) = delete;
    SessionWindow(SessionWindow&&) = delete;
    SessionWindow& operator=(SessionWindow&&) = delete;
    virtual ~SessionWindow() = default;

    /// The window's Vulkan surface on `instance` (VK_KHR_android_surface on the phone; a headless one on hosts), or an
    /// empty one for a window without a surface (a host test's offscreen images).
    [[nodiscard]] virtual gpu::Result<vk::raii::SurfaceKHR> surface(const gpu::Instance& instance) = 0;
    /// The output of `wanted` pixels on `surface` in the frame's colour at `depth`, retiring `old` (if any), which the
    /// session lets go of once this returns, made or refused, each in its stage: by default a swapchain
    /// (gpu/Swapchain.hpp). A refused one is an error, never an output of another depth.
    [[nodiscard]] virtual gpu::Result<std::unique_ptr<gpu::Output>> output(const gpu::Device& device,
                                                                           vk::SurfaceKHR surface, vk::Extent2D wanted,
                                                                           const gpu::Output* old, gpu::Depth depth);
    /// Asks the display for `fps` frames a second (ANativeWindow_setFrameRate); false where it cannot.
    virtual bool set_frame_rate(float fps) {
        (void)fps;
        return false;
    }
};

/// ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN: the process's windows are no longer shown (the levels above it are deeper
/// trims of a process in the background).
inline constexpr int trim_ui_hidden = 20;

/// Flags of Session::bench (Native.bench's).
namespace bench_flag {
inline constexpr std::uint32_t pills = 1U;   // the core draws the frosted pills (the SurfaceView host)
inline constexpr std::uint32_t texture = 2U; // the host is a TextureView (for the log and the records)
inline constexpr std::uint32_t ten_bit = 4U; // VK bench's colour stage: 10 bits a channel, not the frame's 8
} // namespace bench_flag

/// How the core was started, for the session's own uses of it.
struct SessionOptions {
    std::filesystem::path cache_file; // the pipeline cache on disk, which a rebuild after device loss opens again
    std::uint64_t spirv = 0;          // the core's shaders' hash (the cache's key)
    bool ci_log = false;              // a "vk frame" line per frame (CI)
    /// The city the stand-in draws: VK bench's (City::generate()) unless a test gives a smaller one.
    std::shared_ptr<const City> city;
    /// The GPU's noise on a CPU implementation too (lavapipe, SwiftShader), which runs it many times slower than the
    /// CPU's own make: the hosts' tests of its stage, never the phone's.
    bool noise_on_cpu = false;
};

/// One frame's times, as the frame loop has them.
struct FrameTimes {
    std::int64_t vsync_ns = 0;   // the frame's time (Choreographer's vsync)
    std::int64_t present_ns = 0; // its expected presentation time, 0 for none
};

/// What a draw did.
struct Drawn {
    bool drawn = false;   // a frame went out: `record` is it
    bool stale = false;   // the output asks to be made again (remake, after the frame's upcalls)
    bool rebuilt = false; // the device was lost and made again: another frame is wanted
    FrameRecord record{};
};

/// The core's build and its linker's identity, as VkFacts' first lines ("build: …", "build.linker: …").
[[nodiscard]] std::string build_lines();

/// The crash guard's lines of VkFacts for a start: `guard:` (clear, or the crash it found), the marker it discarded
/// (`vk guard: <stage> marker after <reason>, not counted`), how a counted marker's process ended (`guard.exit:`), and
/// the guard's state (`guard.build`, `guard.driver`, `guard.off`).
[[nodiscard]] std::string guard_lines(const gpu::Started& started);

/// The session of a started core: it holds `started`, which outlives it, and lets go of everything in it at close.
class Session {
public:
    Session(gpu::Started& started, SessionOptions options);
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    Session(Session&&) = delete;
    Session& operator=(Session&&) = delete;
    /// Closes the session, if close was not called (on the thread that destroys it).
    ~Session();

    /// Draws into `window` from now on, at `size` pixels where the surface leaves the size to the swapchain: its
    /// surface, the output on it and (when VK bench draws) the renderer. False with `why` when any of them cannot be
    /// made; then nothing of the window is held, and the colour stage is closed.
    [[nodiscard]] bool attach(std::unique_ptr<SessionWindow> window, vk::Extent2D size, std::string& why);
    /// The window changed size: the output is made again.
    void resize(vk::Extent2D size);
    /// The output asked to be made again (out of date or suboptimal), at the size it had.
    void remake();
    /// Lets go of the window: the renderer, the output, the colour stage, the surface.
    void detach();
    [[nodiscard]] bool attached() const noexcept { return window_ != nullptr; }

    /// Switches VK bench's stand-in on (mode 1) or off (0) and its configuration (bench_flag's `flags`); "" or why it
    /// could not. Mode 0, whatever configuration it names, lets go of the renderer and of anything of 10 bits (the
    /// colour stage closing) and makes nothing: the next step that draws makes its output. A cell the crash guard
    /// turned off is refused, "off (the driver crashed in it: vk guard cell …)", nothing made for it.
    [[nodiscard]] std::string bench(int mode, const gpu::Configuration& config, std::uint32_t flags);
    /// VK bench's pipeline creation line ("vkbench pipelines: …").
    [[nodiscard]] std::string pipelines();

    /// The app's map drawn from now on in VK bench's stand-in's place (standin/SessionMap.hpp), in the facts' own pick
    /// at 8 bits: what it draws with made now (behind the pipelines stage), its renderer with the window (now, if one
    /// is attached); `texture` says the host is a TextureView (the log and the records). Null: the map off, its
    /// renderer and what it drew with gone. VK bench's mode 1 takes the window from the map (its renderer goes), and
    /// the map comes back with the next call here. "" or why not.
    [[nodiscard]] std::string map(SessionMap* map, bool texture = false);

    /// Whether there is a frame to draw (a renderer, or the map's due at its first frame, and the map on for the
    /// session).
    [[nodiscard]] bool drawing() const noexcept;
    /// Whether the frame's renderer, its pipelines with it, is made (the map's at its first frame, renderer_due_).
    [[nodiscard]] bool renderer_made() const noexcept { return renderer_ != nullptr; }
    /// Draws one frame at `camera`.
    [[nodiscard]] Drawn draw(const style::CameraState& camera, const FrameTimes& times);
    /// The frame loop came to rest (nothing changed, or the app's window went to the background): the frames stage
    /// ends, and the next draw within the renderer's first 120 frames opens it again; inside VK bench's colour stage
    /// the stage is left too, as mode 0 leaves it (the 10-bit frame and output go, then it closes). So no marker is on
    /// disk while nothing is drawn.
    void rest();
    /// The app was asked to trim its memory at `level` (ComponentCallbacks2's): from TRIM_MEMORY_UI_HIDDEN (the app's
    /// windows hidden; the deeper levels come as the system makes ready to kill cached processes) the frame loop
    /// rests (rest), and the pipeline cache is written back where a pipeline was made through it since its last write
    /// (else nothing is written, and no marker made). True when the frame loop is to stop until a frame is asked for.
    bool trim(int level);

    /// VkFacts' lines as text, the guard's state and this session's among them, after reading Android's profiles behind
    /// the guard's profiles stage (once a device; never inside the colour stage).
    [[nodiscard]] std::string facts();
    /// Runs one of VK facts' checks that draw (`check`, on the session's start: its device and pipeline cache; its
    /// line) behind the guard's checks stage, never inside VK bench's colour stage: a crash in one turns them off
    /// alone, for this driver and build. Its line, or `<name>: off (…)` or `<name>: not run (…)`.
    [[nodiscard]] std::string check(std::string_view name,
                                    const std::function<std::string(const gpu::Started&)>& check);
    /// Runs work of the GPU's noise (`work`, on the session's start: VK facts' fields line, fx/GpuFields.hpp) behind
    /// the guard's noise stage, never inside VK bench's colour stage: a crash in it turns the GPU's noise off alone,
    /// for this driver and build. Where the GPU's noise is off, `work` runs outside any stage with `off` saying why
    /// (the guard's line, a CPU implementation, a device lost in it, no device) and must make nothing on the GPU.
    [[nodiscard]] std::string noise(const std::function<std::string(const gpu::Started&, std::string_view off)>& work);
    /// Writes the pipeline cache back, behind the guard's cache stage, where a pipeline was made through it since its
    /// last write (else nothing, and no marker).
    void save_cache();
    /// The panel's line.
    [[nodiscard]] std::string stats() const;
    /// Copies the frame records not read yet into `out` (at most `capacity`); returns how many. Any thread.
    std::size_t records(FrameRecord* out, std::size_t capacity);
    /// The ADPF hint session's state, for the "vk path" line (the phone's; "none" otherwise).
    void set_hint(std::string hint) { hint_ = std::move(hint); }

    /// Lets go of everything, each behind its stage, then of the stand-in's shared objects, the pipeline cache, the
    /// device and the instance behind the close stage. Once; the session does nothing after it.
    void close();

    /// The session's start (the guard, the device), for the tests and the façade's facts.
    [[nodiscard]] const gpu::Started& started() const noexcept { return started_; }
    /// The output drawn into now, if any.
    [[nodiscard]] const gpu::Output* output() const noexcept { return output_.get(); }
    /// Whether the guard's colour stage is open (VK bench's 10-bit work may reach the driver).
    [[nodiscard]] bool colour_open() const noexcept { return colour_cache_ != nullptr; }
    /// Why the map is off for this session (three devices lost, or one not made again after its loss); "" while it
    /// is on.
    [[nodiscard]] const std::string& off_reason() const noexcept { return session_off_; }
    /// The tests' device loss: what a frame does when the device is lost.
    void lose_device() { device_lost(); }

private:
    /// Runs `step` behind the guard's `stage` (D5.10), then goes back to the frames stage while the first frames are
    /// guarded. Inside VK bench's colour stage every marker stays the colour stage's.
    template <class Step>
    auto behind(gpu::Stage stage, Step step) {
        started_.guard->enter(stage);
        auto result = step();
        started_.guard->leave();
        if (guarding_) started_.guard->enter(gpu::Stage::frames);
        return result;
    }

    /// The renderer goes, behind the pipelines stage (the colour stage's while it is open): its frame announced, the
    /// stand-in's pipelines, passes, framebuffers and images made for it, then its main pass and frames in flight. The
    /// frames stage ends with it.
    void drop_renderer();
    /// The output goes, announced, behind the surface stage (the colour stage's while it is open).
    void drop_output();
    /// The output goes, announced, in the stage open now.
    void release_output();
    /// The output at `depth_`, behind the surface stage; at 10 bits inside the colour stage, opened first, where the
    /// device (asked inside it) can draw the frame in them, else (or refused by the driver) the stage closed again and
    /// the 8-bit output made in its place, `depth_` back at 8; "" or why not.
    std::string make_output();
    /// The output made again at its size, retiring the old one (gone once the new one is made or refused), and the
    /// renderer retargeted; refused, the renderer goes, and the colour stage closes.
    void remake_output();
    /// The frame drawn is about to change (another configuration, its output made again, a new renderer): the frames
    /// stage ends with the old frame (its marker taken by the stage entered next, which behind does not leave for the
    /// frames stage again), and the next draw opens it under the new frame's name, its first 120 frames guarded.
    void new_frame() noexcept;
    /// The stand-in, if there is none yet, behind the pipelines stage; "" or why not. Its shared objects are 8-bit
    /// work, so never inside the colour stage: each step makes it before an output of 10 bits opens that stage.
    std::string make_standin();
    /// The renderer of the configuration on the output, behind the pipelines stage; "" or why not.
    std::string make_renderer();
    /// What the map made goes, behind the pipelines stage (a loss and the close let go of it in their own stages).
    void release_map();
    /// Opens the guard's colour stage, with its own pipeline cache, and asks the device about 10 bits inside it.
    void open_colour();
    /// Closes the colour stage, its cache gone (announced) inside it, once nothing of 10 bits is left.
    void close_colour();
    /// Out of VK bench's colour stage, as mode 0 and the frame loop at rest leave it: the 10-bit frame and output go,
    /// each in the stage open now (the colour stage's), the stage closes, and the frame is back at its 8 bits, its
    /// output made by the next step that draws. Nothing where the frame is at 8 bits already.
    void leave_ten_bits();
    /// Everything of the window goes, each in its stage: the renderer, the output, the colour stage, the surface.
    void release_window();
    /// Reads Android's profiles behind the profiles stage, once a device, never inside the colour stage.
    void read_profiles();
    /// The facts' colour as the output made it (`colour:`), or in the colour stage their deepest one.
    void note_colour();
    /// Why the GPU's noise is off for this session ("" while it is on): the guard turned it off for this driver and
    /// build, a device was lost in it, or the device is a CPU implementation (lavapipe, SwiftShader), where the CPU's
    /// own make is many times faster. The map's worlds take the CPU's fields then.
    [[nodiscard]] std::string noise_off() const;
    void log_configuration() const;
    void push_record(const FrameRecord& r);
    /// The map off for this session, saying why (VkFacts' `vulkan.session:` line, and every bench call after).
    void turn_off_session(std::string why);
    void device_lost();
    [[nodiscard]] std::string time_pipelines();

    gpu::Started& started_;
    SessionOptions options_;
    bool closed_ = false;
    std::unique_ptr<SessionWindow> window_;
    vk::raii::SurfaceKHR surface_{nullptr};
    std::unique_ptr<gpu::Output> output_;
    std::string output_error_; // why the last output could not be made ("" once one is)
    std::shared_ptr<const City> city_;
    std::unique_ptr<StandIn> standin_;
    SessionMap* map_ = nullptr; // the app's map (mode 2), the façade's
    std::unique_ptr<gpu::Renderer> renderer_;
    /// The map's renderer is made at its first frame (draw), not where its window is attached or the map switched
    /// on: its pipelines take seconds on a slow device or with an empty cache, and the attach waits on the app's main
    /// thread (an ANR on CI's emulator, 26 October). A renderer that cannot be made there turns the map off for the
    /// session, which the frame loop tells the app (FrameSource.off).
    bool renderer_due_ = false;
    std::unique_ptr<gpu::PipelineCache> colour_cache_; // the colour stage's, while it is open
    std::vector<gpu::PresentedTiming> timings_;
    vk::Extent2D wanted_{};
    gpu::Configuration config_{};
    /// The output's colour: the frame's 8 bits, or in VK bench's colour stage as deep as the surface allows.
    gpu::Depth depth_ = gpu::default_depth;
    int mode_ = 0; // 0 nothing, 1 VK bench's stand-in, 2 the app's map (map_)
    bool pills_ = false;
    bool texture_ = false;
    /// The frames stage is open, for the frame drawn now: whatever changes that frame ends it first (new_frame,
    /// drop_renderer), so its marker never names a frame no longer drawn.
    bool guarding_ = false;
    bool profiles_read_ = false;
    bool noise_lost_ = false; // a device was lost making the noise fields on the GPU: the CPU's this session
    bool frame_rate_ = false;
    bool first_frames_saved_ = false; // the cache written back after the session's first 120 frames
    double first_pipelines_ms_ = -1;  // this launch's first pipelines (-1 until the stand-in draws)
    std::uint32_t first_pipelines_ = 0;
    int lost_ = 0;
    /// The frames the renderer drew since it was made or remade (a configuration, a size): the frames stage guards its
    /// first 120.
    std::uint32_t presented_ = 0;
    std::int64_t epoch_ns_ = 0;
    std::string session_off_; // why the map is off for this session ("" while it is on)
    std::string hint_ = "none";

    mutable std::mutex records_mutex_;
    std::array<FrameRecord, 1024> ring_{};
    std::uint64_t write_ = 0;
    std::uint64_t published_ = 0;
    std::uint64_t read_ = 0;
    std::uint64_t count_ = 0;
};

} // namespace atlas::standin
