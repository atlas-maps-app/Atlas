// The façade's natives (jni/Bridge.hpp): the process's one core, its guarded start, and on the phone its render thread
// (an ALooper at display priority) with Choreographer's vsync, the camera pulled from Kotlin at each paced vsync
// (FrameSource.camera, allocation-free on both sides), the window a SurfaceView or a TextureView hands over, and the
// ADPF hint. What the core draws with is its session (standin/Session.hpp): the surface and the swapchain, VK bench's
// stand-in, the frames and their records, device loss, the facts, the pipeline cache and the close, every driver call
// behind one of the crash guard's stages (gpu/Guard.hpp). The session runs on one thread at a time: on the phone every
// call into it, the façade's from other threads included (facts, the cache's write-back, the stats), runs on the
// render thread, so that no two stages ever overlap in the guard's one marker; on hosts, which draw nothing through the
// façade, under a lock. The app's map (frame/MapFrame.hpp, K11) is the session's other content: its packs and moment
// from Kotlin, its frames drawn while its picture changes at the same camera, and its frame loop woken at rest when a
// tile lands.
#include "jni/Bridge.hpp"

#include "base/build_info.hpp"
#include "base/log.hpp"
#include "check/NumericsCheck.hpp"
#include "frame/MapFrame.hpp"
#include "fx/Fields.hpp"
#include "gen/spirv/modules.hpp"
#include "geometry/Ground.hpp"
#include "gpu/Facts.hpp"
#include "gpu/Guard.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Swapchain.hpp"
#include "standin/Session.hpp"
#include "style/CameraState.hpp"
#include "style/Theme.hpp"
#include "sun/Buckets.hpp"
#include "text/Fonts.hpp"
#include "text/Shaper.hpp"

#if defined(__ANDROID__)
#include "platform/android/Android.hpp"
#endif

#include <array>
#include <atomic>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <format>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

namespace atlas::jni {

namespace {

// The last refusal of create or attach, per thread (JNI's answer to lastError).
thread_local std::string last_error; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables)

[[nodiscard]] std::string string_of(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars == nullptr ? std::string() : std::string(chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

/// Element `i` of a Java String[] as a string ("" for null), its local reference let go. The array's element is a
/// jobject; JNI's C++ types only name a reference's kind, so it is reinterpreted as the jstring it is.
[[nodiscard]] std::string string_at(JNIEnv* env, jobjectArray a, jsize i) {
    auto* s = reinterpret_cast<jstring>(env->GetObjectArrayElement(a, i));
    std::string out = string_of(env, s);
    env->DeleteLocalRef(s);
    return out;
}

[[nodiscard]] jstring java(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

/// Flags of Native.create.
namespace core_flag {
constexpr std::uint32_t validation = 1U; // the Khronos validation layer (debug builds, on request)
constexpr std::uint32_t ci = 2U;         // CI's log: a "vk frame" line per frame
constexpr std::uint32_t msaa2 = 4U;      // K6's answer: 2x within 0.15 MAD of 4x (configuration B allowed)
} // namespace core_flag

/// The cores alive in this process: the guard's reset waits for none (a live core writes its pipeline cache back).
std::atomic<int> live_cores{0}; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables)

/// The hash of the core's shaders, every embedded module's: the pipeline cache's key.
[[nodiscard]] std::uint64_t core_spirv() noexcept { return gpu::spirv_hash(spirv::hashes); }

/// The options Native.create passes.
struct CoreOptions {
    std::string cache_dir;
    std::string guard_dir;
    std::string system;
    std::string plant;
    std::uint32_t flags = 0;
    std::string exits;              // the app's process exits as Android recorded them (gpu::parse_exits' text)
    std::int64_t process_start = 0; // when this process started, ms since the epoch
};

/// The façade's start: its device always presents (the SurfaceView's or the TextureView's window), on the phone and in
/// the host build that tests the façade.
[[nodiscard]] gpu::StartOptions start_options(const CoreOptions& o) {
    return {.guard_dir = o.guard_dir,
            .cache_file = o.cache_dir.empty() ? std::filesystem::path()
                                              : std::filesystem::path(o.cache_dir) / "vk" / "pipelines.bin",
            .system = o.system,
            .build = std::string(build_id()),
            .plant = o.plant,
            .exits = gpu::parse_exits(o.exits),
            .process_start_ms = o.process_start,
            .validation = (o.flags & core_flag::validation) != 0,
            .require_validation = false,
            .present = true,
            .msaa2_close = (o.flags & core_flag::msaa2) != 0,
            .spirv = core_spirv()};
}

[[nodiscard]] standin::SessionOptions session_options(const CoreOptions& o) {
    const gpu::StartOptions start = start_options(o);
    return {.cache_file = start.cache_file, .spirv = start.spirv, .ci_log = (o.flags & core_flag::ci) != 0};
}

/// The map's tile workers (T8): three on a phone of eight cores or more, else two.
[[nodiscard]] unsigned map_workers() noexcept { return std::thread::hardware_concurrency() >= 8 ? 3U : 2U; }

/// A refused start, as the facts show it: the guard's line and state, why, and the facts an earlier start kept.
[[nodiscard]] std::string refusal_text(const gpu::StartRefusal& r) {
    std::string out = standin::build_lines();
    out += std::format("vulkan: off ({})\n", r.reason);
    if (!r.line.empty()) out += std::format("guard: {}\n", r.line);
    if (!r.discarded.empty()) out += r.discarded + "\n";
    for (const std::string& l : r.guard) out += l + "\n";
    if (!r.kept_facts.empty()) {
        out += "kept: the facts below were read before Vulkan was turned off\n";
        out += r.kept_facts;
    }
    return out;
}

#if defined(__ANDROID__)
/// An Android window (a SurfaceView's or a TextureView's) for the session: its surface, and a swapchain on it.
class AndroidWindow final : public standin::SessionWindow {
public:
    explicit AndroidWindow(platform::Window window) : window_(std::move(window)) {}

    [[nodiscard]] gpu::Result<vk::raii::SurfaceKHR> surface(const gpu::Instance& instance) override {
        return gpu::make_android_surface(instance, window_.get());
    }
    bool set_frame_rate(float fps) override { return window_.set_frame_rate(fps); }

private:
    platform::Window window_;
};
#endif

/// The process's core.
class Core {
public:
    static std::unique_ptr<Core> create(JNIEnv* env, const CoreOptions& options, std::string& why) {
        auto started = gpu::start_guarded(start_options(options));
        if (!started) {
            why = started.error().line.empty() ? started.error().reason
                                               : started.error().line + ": " + started.error().reason;
            if (!started.error().line.empty()) log::error("{}", started.error().line);
            return nullptr;
        }
        std::unique_ptr<Core> core(new Core(options, std::move(*started))); // NOLINT(cppcoreguidelines-owning-memory)
        if (!core->started_.guard_line.empty()) log::warn("{}", core->started_.guard_line);
#if defined(__ANDROID__)
        env->GetJavaVM(&core->vm_);
        // The render thread from the start: every call into the session runs on it.
        Core* self = core.get();
        core->loop_ = std::make_unique<platform::RenderLoop>(
            [self] {
                JNIEnv* e = nullptr;
                self->vm_->AttachCurrentThreadAsDaemon(&e, nullptr);
                self->render_env_ = e;
            },
            [self] { self->vm_->DetachCurrentThread(); });
        // The app's map, its tiles built on 2 or 3 workers by core count (T8); a tile published wakes the frame loop;
        // the noise fields kept under the app's cache.
        core->map_ = std::make_unique<frame::MapFrame>(
            map_workers(), [self] { self->wake(); }, frame::MapOptions{.cache_dir = options.cache_dir});
#else
        (void)env;
        core->map_ = std::make_unique<frame::MapFrame>(map_workers(), nullptr,
                                                       frame::MapOptions{.cache_dir = options.cache_dir});
#endif
        return core;
    }

    Core(const Core&) = delete;
    Core& operator=(const Core&) = delete;
    Core(Core&&) = delete;
    Core& operator=(Core&&) = delete;

    ~Core() {
#if defined(__ANDROID__)
        closing_.store(true);
#endif
        // Everything the session made goes on the thread it runs on, each behind its stage, then the stand-in, the
        // device and the instance behind the close stage (its last write of the pipeline cache before them).
        on_session([this] {
            session_->close();
#if defined(__ANDROID__)
            requested_ = false;
            release_refs();
#endif
        });
#if defined(__ANDROID__)
        loop_.reset();
#endif
        session_.reset();
        live_cores.fetch_sub(1);
    }

    [[nodiscard]] std::string facts() {
        return on_session([this] { return session_->facts(); });
    }

    /// The app was asked to trim its memory at `level` (ComponentCallbacks2, standin/Session.hpp's trim): from
    /// TRIM_MEMORY_UI_HIDDEN (the app's windows went to the background) nothing is drawn until a frame is asked for
    /// again, so the frame loop rests, its frames stage ending and VK bench's colour stage left (its 10-bit frame and
    /// output gone); and the pipeline cache goes back to disk where a pipeline was made since its last write (else
    /// nothing is written and no marker made: the deeper levels come as the system makes ready to kill cached
    /// processes). No marker stays on disk for a process the system may then freeze and kill as it trims the cached
    /// ones.
    void trim(int level) {
        on_session([this, level] {
            const bool hidden = session_->trim(level);
#if defined(__ANDROID__)
            if (hidden) requested_ = false;
#else
            (void)hidden;
#endif
        });
    }

    [[nodiscard]] std::string stats() {
        return on_session([this] {
            std::string line = session_->stats();
            if (map_on_) line += "; " + map_->tiles_line();
            return line;
        });
    }

    // ---- The app's map (K11).

    /// The app's map on (mode 1: on a SurfaceView, 2: on a TextureView) or off (0); "" or why it cannot draw.
    [[nodiscard]] std::string map(int mode) {
        return on_session([this, mode] {
            map_on_ = mode != 0;
            std::string why = session_->map(map_on_ ? map_.get() : nullptr, mode == 2);
            if (!why.empty()) log::error("vk map: {}", why);
            ask_frame();
            return why;
        });
    }

    /// The packs the app lists for the map, replacing the last list; a line per pack (frame/MapFrame.hpp).
    [[nodiscard]] std::string packs(std::vector<frame::MapPack> packs) {
        return on_session([this, &packs] {
            std::string said = map_->set_packs(std::move(packs));
            ask_frame();
            return said;
        });
    }

    /// The moment's theme for the map from now on.
    void moment(const style::ThemeBlock& theme) {
        on_session([this, &theme] {
            map_->set_theme(theme);
            ask_frame();
        });
    }

    /// Copies the frame records not read yet into `out` (at most `capacity`); returns how many. Any thread.
    std::size_t records(FrameRecord* out, std::size_t capacity) { return session_->records(out, capacity); }

#if defined(__ANDROID__)
    bool attach(JNIEnv* env, jobject surface, int width, int height, jobject source, jobject camera, std::string& why) {
        if (camera == nullptr || env->GetDirectBufferAddress(camera) == nullptr ||
            env->GetDirectBufferCapacity(camera) < 512) {
            why = "the camera buffer must be a direct ByteBuffer of 512 bytes";
            return false;
        }
        platform::Window window(env, surface);
        if (!window) {
            why = "the surface has no window";
            return false;
        }
        jclass cls = env->GetObjectClass(source);
        jmethodID camera_mid = env->GetMethodID(cls, "camera", "(JLjava/nio/ByteBuffer;)Z");
        jmethodID presented_mid = env->GetMethodID(cls, "presented", "(JJZ)V");
        env->DeleteLocalRef(cls);
        if (camera_mid == nullptr || presented_mid == nullptr) {
            env->ExceptionClear();
            why = "the frame source lacks camera(long, ByteBuffer) or presented(long, long, boolean)";
            return false;
        }
        // The source and the camera buffer are the frame loop's from here: handed over on the render thread, which
        // alone reads them, and let go of there again (detach, a refused attach, the close).
        jobject source_ref = env->NewGlobalRef(source);
        jobject camera_ref = env->NewGlobalRef(camera);
        const auto* camera_bytes = static_cast<const std::byte*>(env->GetDirectBufferAddress(camera));
        const vk::Extent2D size{.width = static_cast<std::uint32_t>(width),
                                .height = static_cast<std::uint32_t>(height)};
        return on_session([&] {
            release_refs();
            requested_ = false;
            if (!session_->attach(std::make_unique<AndroidWindow>(std::move(window)), size, why)) {
                // Nothing of a refused attach is held: the session let go of what it made, and the refs go too.
                render_env_->DeleteGlobalRef(source_ref);
                render_env_->DeleteGlobalRef(camera_ref);
                return false;
            }
            source_ = source_ref;
            camera_ = camera_ref;
            camera_bytes_ = camera_bytes;
            camera_mid_ = camera_mid;
            presented_mid_ = presented_mid;
            if (!hint_.active()) {
                (void)hint_.open(loop_->tid(), 16'666'667);
                session_->set_hint(hint_.describe());
            }
            return true;
        });
    }

    void resize(int width, int height) {
        loop_->post([this, width, height] {
            session_->resize(
                {.width = static_cast<std::uint32_t>(width), .height = static_cast<std::uint32_t>(height)});
        });
    }

    void detach() {
        on_session([this] {
            session_->detach();
            requested_ = false;
            release_refs();
        });
    }

    void request_frame() {
        loop_->post([this] {
            requested_ = true;
            ask_vsync();
        });
    }

    /// Switches VK bench's stand-in on (mode 1) or off (0) and its configuration; "" or why it could not.
    std::string bench(int mode, const gpu::Configuration& config, std::uint32_t flags) {
        return on_session([&] { return session_->bench(mode, config, flags); });
    }

    /// VK bench's pipeline creation line ("vkbench pipelines: …"), on the render thread.
    std::string pipelines() {
        return on_session([this] { return session_->pipelines(); });
    }
#endif

private:
    Core(CoreOptions options, gpu::Started started)
        : options_(std::move(options)), started_(std::move(started)),
          session_(std::make_unique<standin::Session>(started_, session_options(options_))) {
        live_cores.fetch_add(1);
    }

    /// Runs `step` with the session to itself: on the render thread (the phone), or under the session's lock (hosts).
    /// Its result (a string or a bool) comes back by value.
    template <class Step, class Result = std::invoke_result_t<Step&>>
    Result on_session(Step step) {
#if defined(__ANDROID__)
        if constexpr (std::is_void_v<Result>) {
            loop_->run([&] { step(); });
        } else {
            Result out{};
            loop_->run([&] { out = step(); });
            return out;
        }
#else
        const std::scoped_lock lock(session_mutex_);
        return step();
#endif
    }

    CoreOptions options_;
    gpu::Started started_;
    /// Declared after started_, which it holds: it goes first.
    std::unique_ptr<standin::Session> session_;
    /// The app's map (K11): the session draws it while map_on_ (the session's thread alone reads the flag).
    std::unique_ptr<frame::MapFrame> map_;
    bool map_on_ = false;

#if defined(__ANDROID__)
    // ---- On the render thread.

    /// A frame is asked for (render thread): the next paced vsync asks Kotlin for the camera.
    void ask_frame() {
        requested_ = true;
        ask_vsync();
    }

    /// A tile of the map's world was published (on a worker, World::set_wake): the next vsync draws it, at rest too.
    void wake() {
        if (closing_.load()) return;
        loop_->post([this] {
            if (closing_.load()) return;
            woken_ = true;
            ask_frame();
        });
    }

    /// Lets go of the frame source and the camera buffer (on the render thread).
    void release_refs() {
        if (source_ != nullptr) render_env_->DeleteGlobalRef(source_);
        if (camera_ != nullptr) render_env_->DeleteGlobalRef(camera_);
        source_ = nullptr;
        camera_ = nullptr;
        camera_bytes_ = nullptr;
    }

    void ask_vsync() {
        if (!requested_) return;
        loop_->request_vsync([this](const platform::Vsync& v) { on_vsync(v); });
    }

    /// The frame loop stops until the next requestFrame: no more vsyncs, and the session rests (its frames stage ends,
    /// and VK bench's colour stage is left, so no marker is on disk while nothing is drawn).
    void rest() {
        requested_ = false;
        session_->rest();
        // The map at rest: its tiles, once a rest (Appendix C's `vk tiles` line).
        if (map_on_ && drew_since_rest_) log::info("{}", map_->tiles_line());
        drew_since_rest_ = false;
    }

    void on_vsync(const platform::Vsync& v) {
        if (!requested_ || !session_->drawing() || source_ == nullptr) return;
        // The display's period, from the vsyncs themselves; at most 60 frames a second (D5.8): every vsync at 60 Hz,
        // every second one at 120 Hz, at 90 Hz every vsync unless the phone runs warm.
        if (last_vsync_ns_ != 0) {
            const std::int64_t d = v.frame_ns - last_vsync_ns_;
            if (d > 4'000'000 && d < 50'000'000) period_ns_ = period_ns_ == 0 ? d : (period_ns_ * 7 + d) / 8;
        }
        last_vsync_ns_ = v.frame_ns;
        const bool every_second =
            period_ns_ != 0 && (period_ns_ < 10'000'000 || (period_ns_ < 14'000'000 && thermal_.headroom(0) > 0.6F));
        const std::int64_t min_gap = every_second ? 2 * period_ns_ - 2'000'000 : 0;
        if (drawn_vsync_ns_ != 0 && v.frame_ns - drawn_vsync_ns_ < min_gap) {
            ask_vsync();
            return;
        }
        // The camera for this vsync, from Kotlin (the gesture camera, written into the reused direct buffer).
        const jboolean more =
            render_env_->CallBooleanMethod(source_, camera_mid_, static_cast<jlong>(v.frame_ns), camera_);
        if (render_env_->ExceptionCheck() == JNI_TRUE) {
            render_env_->ExceptionDescribe();
            render_env_->ExceptionClear();
            rest();
            return;
        }
        // The map's picture may change at the same camera: a tile landed (the world's wake) or a fade or the frame's
        // blend is under way, and the frame is drawn again at the camera Kotlin last wrote.
        const bool due = map_on_ && (woken_ || map_->animating());
        woken_ = false;
        if (more != JNI_TRUE && !due) {
            rest(); // nothing changed: no frame, and no more vsyncs until requestFrame (or the map's wake)
            return;
        }
        const auto camera = style::read_camera_state(std::span(camera_bytes_, style::camera_state_size));
        if (!camera) {
            if (!camera_warned_) log::error("vk camera refused: {}", style::name_of(camera.error()));
            camera_warned_ = true;
            rest();
            return;
        }
        drawn_vsync_ns_ = v.frame_ns;
        draw(*camera, v);
        ask_vsync();
    }

    void draw(const style::CameraState& camera, const platform::Vsync& v) {
        const platform::TraceSection trace("atlas frame");
        const standin::Drawn d = session_->draw(camera, {.vsync_ns = v.frame_ns, .present_ns = v.present_ns});
        if (d.rebuilt) {
            requested_ = true; // the device was lost and rebuilt: draw again
            return;
        }
        if (!d.drawn) return;
        drew_since_rest_ = true;
        const FrameRecord& r = d.record;
        hint_.report(r.start_ns, r.end_ns - r.start_ns, static_cast<std::int64_t>(static_cast<double>(r.gpu_ms) * 1e6));
        // Complete: the map's picture final for this camera (every tile resident and drawn, every fade done).
        const bool complete = !map_on_ || map_->complete();
        render_env_->CallVoidMethod(source_, presented_mid_, static_cast<jlong>(r.frame), static_cast<jlong>(r.end_ns),
                                    complete ? JNI_TRUE : JNI_FALSE);
        if (render_env_->ExceptionCheck() == JNI_TRUE) render_env_->ExceptionClear();
        if (d.stale) session_->remake();
    }

    // Declared after session_, so that the render thread (whose last task closed the session) goes before it.
    JavaVM* vm_ = nullptr;
    JNIEnv* render_env_ = nullptr;
    platform::HintSession hint_;
    platform::Thermal thermal_;
    jobject source_ = nullptr;
    jobject camera_ = nullptr;
    const std::byte* camera_bytes_ = nullptr;
    jmethodID camera_mid_ = nullptr;
    jmethodID presented_mid_ = nullptr;
    bool requested_ = false;
    bool camera_warned_ = false;
    bool woken_ = false;               // the map's world published a tile since the last vsync
    bool drew_since_rest_ = false;     // a frame went out since the frame loop last rested
    std::atomic<bool> closing_{false}; // the core is closing: the map's wake posts nothing more
    std::int64_t last_vsync_ns_ = 0;
    std::int64_t drawn_vsync_ns_ = 0;
    std::int64_t period_ns_ = 0;
    std::unique_ptr<platform::RenderLoop> loop_;
#else
    /// Hosts have no frame loop: nothing to ask.
    void ask_frame() noexcept {}

    std::mutex session_mutex_;
#endif
};

[[nodiscard]] Core* core_of(jlong handle) noexcept {
    // The handle is the pointer create() gave Kotlin.
    return reinterpret_cast<Core*>(handle); // NOLINT(performance-no-int-to-ptr)
}

// ---- The natives.

jint native_abi(JNIEnv* /*env*/, jclass /*cls*/) { return abi; }

jstring native_layouts(JNIEnv* env, jclass /*cls*/) { return java(env, layout_tables()); }

jstring native_last_error(JNIEnv* env, jclass /*cls*/) { return java(env, last_error); }

jstring native_facts(JNIEnv* env, jclass /*cls*/, jlong handle, jstring cache_dir, jstring guard_dir, jstring system,
                     jint flags, jstring exits, jlong process_start) {
    if (Core* core = core_of(handle); core != nullptr) return java(env, core->facts());
    // Without a core: a guarded start of its own and a session of it, read (Android's profiles among them) and closed
    // behind the close stage (Vulkan's facts need a device).
    const CoreOptions o{.cache_dir = {},
                        .guard_dir = string_of(env, guard_dir),
                        .system = string_of(env, system),
                        .plant = {},
                        .flags = static_cast<std::uint32_t>(flags),
                        .exits = string_of(env, exits),
                        .process_start = process_start};
    (void)cache_dir;
    auto started = gpu::start_guarded(start_options(o));
    if (!started) return java(env, refusal_text(started.error()));
    standin::Session session(*started, session_options(o));
    const std::string text = session.facts();
    session.close();
    return java(env, text);
}

/// VK facts' "Reset guard" (debug and bench builds): forgets every crash, every driver the guard turned off and
/// everything it turned off for one (10 bits, the profiles check), and the pipeline cache, as a fresh install would;
/// the next start tries Vulkan, 10 bits and the profiles again. Only with no core alive: a live core keeps its pipeline
/// cache in memory and writes it back (on trim, on close, after its session's first 120 frames), which would undo the
/// reset, so the reset is refused until it is closed (MapCore.resetGuard closes the process's core first). What it
/// forgot, or why not, in words.
jstring native_reset_guard(JNIEnv* env, jclass /*cls*/, jstring cache_dir, jstring guard_dir) {
    if (const int alive = live_cores.load(); alive > 0) {
        const std::string refused = std::format("vk guard reset: refused, {} core{} alive (it would write its pipeline "
                                                "cache back): close it first",
                                                alive, alive == 1 ? " is" : "s are");
        log::warn("{}", refused);
        return java(env, refused);
    }
    const CoreOptions o{.cache_dir = string_of(env, cache_dir), .guard_dir = string_of(env, guard_dir)};
    const gpu::StartOptions start = start_options(o);
    const std::string said = gpu::Guard(start.guard_dir, {}, start.build, {}).clear(start.cache_file);
    log::info("{}", said);
    return java(env, said);
}

jlong native_create(JNIEnv* env, jclass /*cls*/, jstring cache_dir, jstring guard_dir, jstring system, jstring plant,
                    jint flags, jstring exits, jlong process_start) {
    const CoreOptions o{.cache_dir = string_of(env, cache_dir),
                        .guard_dir = string_of(env, guard_dir),
                        .system = string_of(env, system),
                        .plant = string_of(env, plant),
                        .flags = static_cast<std::uint32_t>(flags),
                        .exits = string_of(env, exits),
                        .process_start = process_start};
    std::string why;
    std::unique_ptr<Core> core = Core::create(env, o, why);
    if (!core) {
        last_error = why;
        return 0;
    }
    last_error.clear();
    return reinterpret_cast<jlong>(core.release()); // NOLINT: the handle Kotlin keeps until close
}

void native_close(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    std::unique_ptr<Core> core(core_of(handle)); // NOLINT(cppcoreguidelines-owning-memory): Kotlin hands it back once
}

jstring native_stats(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    return java(env, core == nullptr ? std::string("vk no core") : core->stats());
}

jint native_records(JNIEnv* env, jclass /*cls*/, jlong handle, jobject out) {
    Core* core = core_of(handle);
    void* address = out == nullptr ? nullptr : env->GetDirectBufferAddress(out);
    const jlong capacity = out == nullptr ? 0 : env->GetDirectBufferCapacity(out);
    if (core == nullptr || address == nullptr || std::cmp_less(capacity, sizeof(FrameRecord))) return 0;
    return static_cast<jint>(
        core->records(static_cast<FrameRecord*>(address), static_cast<std::size_t>(capacity) / sizeof(FrameRecord)));
}

void native_trim(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint level) {
    if (Core* core = core_of(handle); core != nullptr) core->trim(level);
}

#if defined(__ANDROID__)
jboolean native_attach(JNIEnv* env, jclass /*cls*/, jlong handle, jobject surface, jint width, jint height,
                       jobject source, jobject camera) {
    Core* core = core_of(handle);
    std::string why;
    const bool ok = core != nullptr && core->attach(env, surface, width, height, source, camera, why);
    last_error = ok ? std::string() : (core == nullptr ? std::string("no core") : why);
    if (!ok) log::error("vk attach: {}", last_error);
    return ok ? JNI_TRUE : JNI_FALSE;
}

void native_resize(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint width, jint height) {
    if (Core* core = core_of(handle); core != nullptr) core->resize(width, height);
}

void native_detach(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    if (Core* core = core_of(handle); core != nullptr) core->detach();
}

void native_request_frame(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    if (Core* core = core_of(handle); core != nullptr) core->request_frame();
}

jstring native_bench(JNIEnv* env, jclass /*cls*/, jlong handle, jint mode, jint config, jint route, jint form,
                     jint target, jint flags) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    const gpu::Configuration c{.config = static_cast<gpu::FrameConfig>(config),
                               .route = route == 1 ? gpu::Route::dynamic : gpu::Route::renderpass,
                               .form = form == 1 ? gpu::FrameForm::onepass : gpu::FrameForm::composite,
                               .target = target == 1 ? gpu::CompositeTarget::direct : gpu::CompositeTarget::resolve};
    return java(env, core->bench(mode, c, static_cast<std::uint32_t>(flags)));
}

jstring native_pipelines(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    return java(env, core == nullptr ? std::string("vkbench pipelines: no core") : core->pipelines());
}
#else
// Hosts have no surfaces through the façade: the frame loop is the phone's (the host tests drive the session itself,
// standin/Session.hpp); the host build serves the facts, the layouts and the tests.
jboolean native_attach(JNIEnv* /*env*/, jclass /*cls*/, jlong /*handle*/, jobject /*surface*/, jint /*w*/, jint /*h*/,
                       jobject /*source*/, jobject /*camera*/) {
    last_error = "hosts have no surfaces";
    return JNI_FALSE;
}
void native_resize(JNIEnv* /*env*/, jclass /*cls*/, jlong /*handle*/, jint /*w*/, jint /*h*/) {}
void native_detach(JNIEnv* /*env*/, jclass /*cls*/, jlong /*handle*/) {}
void native_request_frame(JNIEnv* /*env*/, jclass /*cls*/, jlong /*handle*/) {}
jstring native_bench(JNIEnv* env, jclass /*cls*/, jlong /*handle*/, jint /*mode*/, jint /*config*/, jint /*route*/,
                     jint /*form*/, jint /*target*/, jint /*flags*/) {
    return java(env, "hosts have no surfaces");
}
jstring native_pipelines(JNIEnv* env, jclass /*cls*/, jlong /*handle*/) {
    return java(env, "vkbench pipelines: hosts have no surfaces");
}
#endif

// ---- The app's map (K11): Native.map, packs and moment.

/// The map on (mode 1: on a SurfaceView, 2: on a TextureView) or off (0); "" or why it cannot draw.
jstring native_map(JNIEnv* env, jclass /*cls*/, jlong handle, jint mode) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    if (mode < 0 || mode > 2) return java(env, std::format("no map mode {}", mode));
    return java(env, core->map(mode));
}

/// The packs the app lists for the map, one per index of the arrays: its id, its number (the camera state's pack_id),
/// its kind (tiles::PackKind), and a file's path, or an open descriptor's range (an APK asset; the core keeps a
/// duplicate of the descriptor, the caller closes its own). A line per pack: opened, listed, kept, or why not.
jstring native_packs(JNIEnv* env, jclass /*cls*/, jlong handle, jobjectArray ids, jlongArray numbers, jintArray kinds,
                     jobjectArray paths, jintArray fds, jlongArray offsets, jlongArray lengths) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    if (ids == nullptr || numbers == nullptr || kinds == nullptr || paths == nullptr || fds == nullptr ||
        offsets == nullptr || lengths == nullptr) {
        return java(env, "packs: an array is missing");
    }
    const jsize n = env->GetArrayLength(ids);
    if (env->GetArrayLength(numbers) != n || env->GetArrayLength(kinds) != n || env->GetArrayLength(paths) != n ||
        env->GetArrayLength(fds) != n || env->GetArrayLength(offsets) != n || env->GetArrayLength(lengths) != n) {
        return java(env, "packs: the arrays differ in length");
    }
    std::vector<jlong> number(static_cast<std::size_t>(n));
    std::vector<jint> kind(static_cast<std::size_t>(n));
    std::vector<jint> fd(static_cast<std::size_t>(n));
    std::vector<jlong> offset(static_cast<std::size_t>(n));
    std::vector<jlong> length(static_cast<std::size_t>(n));
    env->GetLongArrayRegion(numbers, 0, n, number.data());
    env->GetIntArrayRegion(kinds, 0, n, kind.data());
    env->GetIntArrayRegion(fds, 0, n, fd.data());
    env->GetLongArrayRegion(offsets, 0, n, offset.data());
    env->GetLongArrayRegion(lengths, 0, n, length.data());
    std::vector<frame::MapPack> packs;
    packs.reserve(static_cast<std::size_t>(n));
    std::string refused;
    for (jsize i = 0; i < n; ++i) {
        const auto at = static_cast<std::size_t>(i);
        frame::MapPack p;
        p.number = static_cast<std::uint64_t>(number[at]);
        p.ref.id = string_at(env, ids, i);
        p.ref.path = string_at(env, paths, i);
        if (kind[at] < 0 || kind[at] > static_cast<jint>(tiles::PackKind::world_base) || p.ref.id.empty() ||
            number[at] <= 0 || offset[at] < 0 || length[at] < 0) {
            refused += std::format("{}: not a pack the map can list\n", p.ref.id);
            continue;
        }
        p.ref.kind = static_cast<tiles::PackKind>(kind[at]);
        p.ref.offset = static_cast<std::uint64_t>(offset[at]);
        p.ref.length = static_cast<std::uint64_t>(length[at]);
        // The map's own descriptor, closed when the pack goes (frame/MapFrame.hpp).
        if (fd[at] >= 0) p.ref.fd = ::fcntl(fd[at], F_DUPFD_CLOEXEC, 0);
        packs.push_back(std::move(p));
    }
    return java(env, refused + core->packs(std::move(packs)));
}

/// The moment's theme for the map (MK/state/ThemeBlock.kt's 256 bytes in a direct buffer); "" or why it was refused.
jstring native_moment(JNIEnv* env, jclass /*cls*/, jlong handle, jobject theme) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    void* address = theme == nullptr ? nullptr : env->GetDirectBufferAddress(theme);
    const jlong capacity = theme == nullptr ? 0 : env->GetDirectBufferCapacity(theme);
    if (address == nullptr || std::cmp_less(capacity, style::theme_block_size)) {
        return java(env, "the theme must be a direct buffer of 256 bytes");
    }
    const auto block =
        style::read_theme_block(std::span(static_cast<const std::byte*>(address), style::theme_block_size));
    if (!block) return java(env, std::format("the theme was refused: {}", style::name_of(block.error())));
    core->moment(*block);
    return java(env, "");
}

template <class F>
void* fn(F f) {
    return reinterpret_cast<void*>(f);
} // NOLINT

/// A table row; JNI's struct names its strings `char*` in the JDK's header and `const char*` in Android's.
[[nodiscard]] JNINativeMethod method(const char* name, const char* signature, void* function) {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-const-cast): only the JDK's header lacks the const
    return {const_cast<char*>(name), const_cast<char*>(signature), function};
}

jstring native_build_info(JNIEnv* env, jclass /*cls*/) { return java(env, std::string(build_info())); }

/// K3b's numerics check, run once (about 6 ms): `pymath <hash> ok` or `pymath <hash> FAIL expected <hash>`.
jstring native_numerics_line(JNIEnv* env, jclass /*cls*/) { return java(env, std::string(check::numerics_line())); }

/// K8's sun check over the bundled Westerkerk, an asset's descriptor, offset and length (about 0.1 s of four cores):
/// `sun <hash> ok` or `sun <hash> FAIL expected <hash>`.
jstring native_sun_line(JNIEnv* env, jclass /*cls*/, jint fd, jlong offset, jlong length) {
    return java(env, sun::sun_line(fd, static_cast<std::uint64_t>(offset), static_cast<std::uint64_t>(length)));
}

// ---- K17a's natives: the noise fields' check (VK facts) ----

/// K17a's fields check: the renders' five screen-wide fields at S1 (1080 × 2400 at 1080/412, the CI emulator's size)
/// made on four threads, timed, each held to fields.inc's hashes (the port's): `fields 1080x2400 cpu <ms> gpu off hash
/// <h> ok`, or `… FAIL <field> <h> expected <h>`. Only when VK facts asks, never on the start's or the map's path.
jstring native_fields_line(JNIEnv* env, jclass /*cls*/) {
    return java(env, fx::fields_line(fx::size_of(1080, 2400, 1080.0 / 412.0), 4));
}

// ---- end of K17a's natives ----

// ---- K9's natives: the ground's check (VK facts) ----

/// K9's ground check over the bundled Westerkerk, an asset's descriptor, offset and length (about 40 ms of one core):
/// `ground <hash> ok` or `ground <hash> FAIL expected <hash>`.
jstring native_ground_line(JNIEnv* env, jclass /*cls*/, jint fd, jlong offset, jlong length) {
    return java(env, geometry::ground_line(fd, static_cast<std::uint64_t>(offset), static_cast<std::uint64_t>(length)));
}
// ---- end of K9's natives ----

// ---- K13's natives: the text's kerning rule, the device's glyph check (VK facts) and the bundled fonts ----

/// K13's platform rule for kerning, which the app measured with Android's Paint (mapview's TextRounding): "floor" or
/// "round" sets it, anything else leaves it; the rule the core takes now.
jstring native_text_rounding(JNIEnv* env, jclass /*cls*/, jstring rule) {
    const std::string word = string_of(env, rule);
    if (word == "floor") text::set_platform_rounding(text::Rounding::floor);
    if (word == "round") text::set_platform_rounding(text::Rounding::round);
    return java(env, std::string(text::name_of(text::platform_rounding())));
}

/// K13's device glyph check: the runs it shapes, `<head> | <text>` a line.
jstring native_text_check_runs(JNIEnv* env, jclass /*cls*/) {
    std::string out;
    for (const text::CheckRun& run : text::check_runs()) out += std::format("{} | {}\n", run.head, run.text);
    return java(env, out);
}

/// The bytes of a direct buffer, or none.
[[nodiscard]] std::span<const std::byte> direct_bytes(JNIEnv* env, jobject buffer) {
    if (buffer == nullptr) return {};
    const auto* address = static_cast<const std::byte*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity <= 0) return {};
    return {address, static_cast<std::size_t>(capacity)};
}

/// K13's device glyph check: the bundled Geist and Gelasio (their bytes) and Android's rows for the check runs, held
/// bit for bit under the platform's rule: `text.glyphs: …`.
jstring native_text_check(JNIEnv* env, jclass /*cls*/, jobject geist, jobject gelasio, jstring android) {
    text::Fonts fonts;
    const auto sans = fonts.load(text::Face::sans, direct_bytes(env, geist));
    const auto serif = fonts.load(text::Face::serif, direct_bytes(env, gelasio));
    if (!sans || !serif) {
        return java(env, std::format("text.glyphs: FAIL the fonts did not load ({})",
                                     text::describe(!sans ? sans.error() : serif.error())));
    }
    return java(env, text::glyph_check(fonts, string_of(env, android), text::platform_rounding()));
}

/// Loads the bundled fonts into the frame's text (text::shared_fonts: each face once, the bytes copied): `text.fonts:
/// ok`, or which file did not load.
jstring native_text_fonts(JNIEnv* env, jclass /*cls*/, jobject geist, jobject gelasio, jobject gelasio_italic) {
    text::Fonts& fonts = text::shared_fonts();
    for (const auto& [face, buffer] : {std::pair{text::Face::sans, geist}, std::pair{text::Face::serif, gelasio},
                                       std::pair{text::Face::serif_italic, gelasio_italic}}) {
        if (fonts.loaded(face)) continue;
        if (const auto loaded = fonts.load(face, direct_bytes(env, buffer)); !loaded) {
            return java(env,
                        std::format("text.fonts: FAIL {} ({})", text::file_of(face), text::describe(loaded.error())));
        }
    }
    return java(env, "text.fonts: ok");
}

// ---- end of K13's natives ----

const std::array<JNINativeMethod, 28>& methods() {
    static const std::array<JNINativeMethod, 28> table{{
        method("abi", "()I", fn(&native_abi)),
        method("layouts", "()Ljava/lang/String;", fn(&native_layouts)),
        method("lastError", "()Ljava/lang/String;", fn(&native_last_error)),
        method("facts",
               "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;J)Ljava/lang/String;",
               fn(&native_facts)),
        method("create",
               "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;J)J",
               fn(&native_create)),
        method("close", "(J)V", fn(&native_close)),
        method("attach", "(JLjava/lang/Object;IILapp/atlas/mapcore/FrameSource;Ljava/nio/ByteBuffer;)Z",
               fn(&native_attach)),
        method("resize", "(JII)V", fn(&native_resize)),
        method("detach", "(J)V", fn(&native_detach)),
        method("requestFrame", "(J)V", fn(&native_request_frame)),
        method("bench", "(JIIIIII)Ljava/lang/String;", fn(&native_bench)),
        method("records", "(JLjava/nio/ByteBuffer;)I", fn(&native_records)),
        method("stats", "(J)Ljava/lang/String;", fn(&native_stats)),
        method("trimMemory", "(JI)V", fn(&native_trim)),
        method("buildInfo", "()Ljava/lang/String;", fn(&native_build_info)),
        method("numericsLine", "()Ljava/lang/String;", fn(&native_numerics_line)),
        method("sunLine", "(IJJ)Ljava/lang/String;", fn(&native_sun_line)),
        // ---- K17a's natives ----
        method("fieldsLine", "()Ljava/lang/String;", fn(&native_fields_line)),
        // ---- end of K17a's natives ----
        // ---- K9's natives ----
        method("groundLine", "(IJJ)Ljava/lang/String;", fn(&native_ground_line)),
        // ---- end of K9's natives ----
        // ---- K13's natives ----
        method("textRounding", "(Ljava/lang/String;)Ljava/lang/String;", fn(&native_text_rounding)),
        method("textCheckRuns", "()Ljava/lang/String;", fn(&native_text_check_runs)),
        method("textCheck", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/lang/String;)Ljava/lang/String;",
               fn(&native_text_check)),
        method("textFonts", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Ljava/lang/String;",
               fn(&native_text_fonts)),
        // ---- end of K13's natives ----
        method("pipelines", "(J)Ljava/lang/String;", fn(&native_pipelines)),
        method("resetGuard", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", fn(&native_reset_guard)),
        // The app's map (K11).
        method("map", "(JI)Ljava/lang/String;", fn(&native_map)),
        method("packs", "(J[Ljava/lang/String;[J[I[Ljava/lang/String;[I[J[J)Ljava/lang/String;", fn(&native_packs)),
        method("moment", "(JLjava/nio/ByteBuffer;)Ljava/lang/String;", fn(&native_moment)),
    }};
    return table;
}

} // namespace

std::span<const JNINativeMethod> facade_methods() noexcept { return methods(); }

} // namespace atlas::jni
