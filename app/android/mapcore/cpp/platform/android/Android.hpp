// The phone's platform services for the core (plan-vector T8, D5.1, D5.8, D17): the window a SurfaceView or a
// TextureView hands over, the render thread's looper with Choreographer's vsync, the ADPF hint session, the thermal
// headroom, systrace sections and the APK's assets. The app's minSdk is 29, so what later Android versions add
// (vsync callbacks with frame timelines in 33, hint sessions in 33 and GPU durations in 35, thermal headroom in 31,
// ANativeWindow_setFrameRate in 30) is looked up in libandroid.so at run time and used only where it exists.
#pragma once

#if defined(__ANDROID__)

#include <android/native_window.h>
#include <jni.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

struct AAssetManager;
struct ALooper;
struct AChoreographer;
struct AChoreographerFrameCallbackData;

namespace atlas::platform {

/// The device's API level (android_get_device_api_level).
[[nodiscard]] int api_level() noexcept;

/// A symbol of libandroid.so, or nullptr where this Android has none.
[[nodiscard]] void* android_symbol(const char* name) noexcept;

/// CLOCK_MONOTONIC in nanoseconds: Choreographer's, the display timing's and the presentation times' clock.
[[nodiscard]] std::int64_t now_ns() noexcept;

/// The window of a Surface, held until released.
class Window {
public:
    Window() = default;
    /// The window of `surface` (an android.view.Surface), acquired; empty when the surface has none.
    Window(JNIEnv* env, jobject surface) noexcept;
    Window(const Window&) = delete;
    Window& operator=(const Window&) = delete;
    Window(Window&& other) noexcept : window_(std::exchange(other.window_, nullptr)) {}
    Window& operator=(Window&& other) noexcept;
    ~Window();

    [[nodiscard]] ANativeWindow* get() const noexcept { return window_; }
    explicit operator bool() const noexcept { return window_ != nullptr; }
    [[nodiscard]] std::int32_t width() const noexcept;
    [[nodiscard]] std::int32_t height() const noexcept;
    /// Asks for `fps` frames a second (ANativeWindow_setFrameRate, API 30; only windows on the display honour it).
    /// Returns false where this Android has no such call.
    bool set_frame_rate(float fps) const noexcept;

private:
    ANativeWindow* window_ = nullptr;
};

/// The frame timelines a vsync can carry (API 33's; the platform offers a few).
inline constexpr std::size_t max_timelines = 8;

/// One vsync as Choreographer reports it.
struct Vsync {
    std::int64_t frame_ns = 0;    // the frame's time (the vsync)
    std::int64_t present_ns = 0;  // the preferred timeline's expected presentation time (API 33; else 0)
    std::int64_t deadline_ns = 0; // its deadline (API 33; else 0)
    /// Every timeline offered, earliest first: its expected presentation and its deadline (API 33; else none), and
    /// which is the preferred one (frame/Pacing.hpp chooses among them, ENGINE.md gain 15).
    std::array<std::int64_t, max_timelines> presents{};
    std::array<std::int64_t, max_timelines> deadlines{};
    std::uint32_t timelines = 0;
    std::uint32_t preferred = 0;
};

/// The render thread: a thread with an ALooper, woken by posted tasks and by Choreographer's vsync callbacks.
class RenderLoop {
public:
    /// Starts the thread; `on_start` runs on it first (attaching it to the JVM), `on_stop` last.
    RenderLoop(std::function<void()> on_start, std::function<void()> on_stop);
    RenderLoop(const RenderLoop&) = delete;
    RenderLoop& operator=(const RenderLoop&) = delete;
    RenderLoop(RenderLoop&&) = delete;
    RenderLoop& operator=(RenderLoop&&) = delete;
    /// Stops the thread after the tasks already posted.
    ~RenderLoop();

    /// Runs `task` on the render thread, after the tasks posted before it.
    void post(std::function<void()> task);
    /// Runs `task` on the render thread and waits for it.
    void run(std::function<void()> task);
    /// Asks Choreographer for the next vsync; `on_vsync` gets it on the render thread (render thread only).
    void request_vsync(std::function<void(const Vsync&)> on_vsync);
    [[nodiscard]] bool on_thread() const noexcept { return std::this_thread::get_id() == thread_id_.load(); }
    [[nodiscard]] pid_t tid() const noexcept { return tid_.load(); }

private:
    void loop(const std::stop_token& stop);
    void drain_tasks();
    static int on_wake(int fd, int events, void* data);
    static void on_frame64(std::int64_t frame_ns, void* data);
    static void on_vsync33(const ::AChoreographerFrameCallbackData* data, void* self);

    std::function<void()> on_start_;
    std::function<void()> on_stop_;
    std::mutex mutex_;
    std::vector<std::function<void()>> tasks_;
    std::function<void(const Vsync&)> vsync_;
    bool vsync_pending_ = false;
    int wake_fd_ = -1;
    std::atomic<std::thread::id> thread_id_;
    std::atomic<pid_t> tid_{0};
    std::atomic<bool> stopping_{false};
    ::ALooper* looper_ = nullptr;
    ::AChoreographer* choreographer_ = nullptr;
    std::jthread thread_;
};

/// The ADPF hint session of the render thread (API 33; GPU durations from 35), so clocks rise only as far as a frame
/// needs (D17). Without it, nothing.
class HintSession {
public:
    HintSession() = default;
    HintSession(const HintSession&) = delete;
    HintSession& operator=(const HintSession&) = delete;
    HintSession(HintSession&&) = delete;
    HintSession& operator=(HintSession&&) = delete;
    ~HintSession();

    /// Opens a session for thread `tid` with a target of `target_ns` a frame; false where Android has none.
    bool open(pid_t tid, std::int64_t target_ns) noexcept;
    /// Reports one frame's work: its CPU time on the render thread and, where known, the GPU's.
    void report(std::int64_t start_ns, std::int64_t cpu_ns, std::int64_t gpu_ns) noexcept;
    [[nodiscard]] bool active() const noexcept { return session_ != nullptr; }
    /// "ADPF 35 (GPU durations)", "ADPF 33", or "none (API 29-32)".
    [[nodiscard]] std::string describe() const;

private:
    void* session_ = nullptr;
    bool gpu_ = false;
};

/// The thermal state (D17): headroom (API 31) and status (API 30).
class Thermal {
public:
    Thermal() noexcept;
    Thermal(const Thermal&) = delete;
    Thermal& operator=(const Thermal&) = delete;
    Thermal(Thermal&&) = delete;
    Thermal& operator=(Thermal&&) = delete;
    ~Thermal();

    /// The forecast headroom `seconds` ahead (1.0 is severe throttling), NaN where unknown.
    [[nodiscard]] float headroom(int seconds) const noexcept;
    /// ATHERMAL_STATUS_* (0 none … 6 shutdown), -1 where unknown.
    [[nodiscard]] int status() const noexcept;

private:
    void* manager_ = nullptr;
};

/// A systrace section for as long as it lives (seen by Perfetto and simpleperf's traces).
class TraceSection {
public:
    explicit TraceSection(const char* name) noexcept;
    TraceSection(const TraceSection&) = delete;
    TraceSection& operator=(const TraceSection&) = delete;
    TraceSection(TraceSection&&) = delete;
    TraceSection& operator=(TraceSection&&) = delete;
    ~TraceSection();

private:
    bool on_ = false;
};

/// A file of the APK's assets as a descriptor and a range (an uncompressed asset, mapped where it lies).
struct AssetRange {
    int fd = -1;
    std::int64_t offset = 0;
    std::int64_t length = 0;
};

/// The asset `name`'s range in the APK, or nullopt (absent, or compressed: then it has no range of its own).
[[nodiscard]] std::optional<AssetRange> asset_range(AAssetManager* assets, const char* name) noexcept;

/// The native asset manager of a Java AssetManager.
[[nodiscard]] AAssetManager* asset_manager(JNIEnv* env, jobject assets) noexcept;

} // namespace atlas::platform

#endif
