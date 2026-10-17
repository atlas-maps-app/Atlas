// The façade's natives (jni/Bridge.hpp): the process's one core, its guarded start, and on the phone the frame loop:
// the render thread (an ALooper at display priority), Choreographer's vsync, the camera pulled from Kotlin at each
// paced vsync (FrameSource.camera, allocation-free on both sides), the swapchain on the SurfaceView's or the
// TextureView's window, the frame through the device layer, the ADPF hint, the frame records VK bench reads, the crash
// guard (keyed by the core's build ID, naming each shader module and pipeline before it is made and each frame before
// it goes) through its first 120 frames and around VK bench's colour stage, and device loss rebuilt at most twice a
// session, behind the guard's stages as the start was.
//
// The frame is 8 bits a channel (gpu/Facts.hpp's default_depth); only VK bench's colour stage (Native.bench's flag 4)
// draws it at 10, where the surface offers them, the device (asked as the stage begins) can draw the frame in them and
// the crash guard has not turned them off for this driver and build. The guard's colour stage (gpu/Guard.hpp) is open
// exactly while 10-bit work may reach the driver: from before the device is asked about 10 bits until the 10-bit frame
// (the renderer's main pass and the stand-in's pipelines, passes and images made for it) and the 10-bit swapchain are
// destroyed, so a crash anywhere in between, their destruction included, turns off 10 bits only.
#include "jni/Bridge.hpp"

#include "base/build_info.hpp"
#include "base/log.hpp"
#include "check/NumericsCheck.hpp"
#include "gen/spirv/modules.hpp"
#include "gpu/Facts.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Guard.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Swapchain.hpp"
#include "standin/City.hpp"
#include "standin/StandIn.hpp"
#include "style/CameraState.hpp"

#if defined(__ANDROID__)
#include "platform/android/Android.hpp"
#endif

#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <format>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
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

[[nodiscard]] jstring java(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

/// Flags of Native.create.
namespace core_flag {
constexpr std::uint32_t validation = 1U;          // the Khronos validation layer (debug builds, on request)
[[maybe_unused]] constexpr std::uint32_t ci = 2U; // CI's log: a "vk frame" line per frame
constexpr std::uint32_t msaa2 = 4U;               // K6's answer: 2x within 0.15 MAD of 4x (configuration B allowed)
} // namespace core_flag

/// Flags of Native.bench.
namespace bench_flag {
[[maybe_unused]] constexpr std::uint32_t pills = 1U;   // the core draws the frosted pills (the SurfaceView host)
[[maybe_unused]] constexpr std::uint32_t texture = 2U; // the host is a TextureView (for the log and the records)
[[maybe_unused]] constexpr std::uint32_t ten_bit = 4U; // VK bench's colour stage: 10 bits a channel, not the frame's 8
} // namespace bench_flag

/// The cores alive in this process: the guard's reset waits for none (a live core writes its pipeline cache back).
std::atomic<int> live_cores{0}; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables)

/// The hash of the core's shaders, every embedded module's: the pipeline cache's key.
[[nodiscard]] std::uint64_t core_spirv() noexcept { return gpu::spirv_hash(spirv::hashes); }

[[nodiscard, maybe_unused]] std::uint32_t config_bits(const gpu::Configuration& c, bool texture, bool pills,
                                                      bool ten_bit) noexcept {
    auto b = static_cast<std::uint32_t>(c.config);
    b |= (c.route == gpu::Route::dynamic ? 1U : 0U) << 3;
    b |= (c.form == gpu::FrameForm::onepass ? 1U : 0U) << 4;
    b |= (c.target == gpu::CompositeTarget::direct ? 1U : 0U) << 5;
    b |= (texture ? 1U : 0U) << 6;
    b |= (pills ? 1U : 0U) << 7;
    return (b << frame_flag::config_shift) | (ten_bit ? frame_flag::ten_bit : 0U);
}

/// The options Native.create passes.
struct CoreOptions {
    std::string cache_dir;
    std::string guard_dir;
    std::string system;
    std::string plant;
    std::uint32_t flags = 0;
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
            .validation = (o.flags & core_flag::validation) != 0,
            .require_validation = false,
            .present = true,
            .msaa2_close = (o.flags & core_flag::msaa2) != 0,
            .spirv = core_spirv()};
}

/// The core's build and the linker's identity of its library, which its build ID hashes (base/build_info.hpp).
[[nodiscard]] std::string build_text() {
    const std::string_view linker = linker_id();
    return std::format("build: {}\nbuild.linker: {}\n", build_info(), linker.empty() ? "none" : linker);
}

[[nodiscard]] std::string facts_text(const gpu::Facts& facts, const gpu::Started& s) {
    std::string out = build_text();
    for (const std::string& l : gpu::facts_lines(facts)) out += l + "\n";
    out += std::format("pipeline.cache: {}\n", s.cache ? s.cache->status() : std::string("none"));
    out += std::format("guard: {}\n", s.guard_line.empty() ? std::string("clear") : s.guard_line);
    for (const std::string& l : s.guard->state()) out += l + "\n";
    return out;
}

/// A refused start, as the facts show it: the guard's line and state, why, and the facts an earlier start kept.
[[nodiscard]] std::string refusal_text(const gpu::StartRefusal& r) {
    std::string out = build_text();
    out += std::format("vulkan: off ({})\n", r.reason);
    if (!r.line.empty()) out += std::format("guard: {}\n", r.line);
    for (const std::string& l : r.guard) out += l + "\n";
    if (!r.kept_facts.empty()) {
        out += "kept: the facts below were read before Vulkan was turned off\n";
        out += r.kept_facts;
    }
    return out;
}

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
#else
        (void)env;
#endif
        return core;
    }

    Core(const Core&) = delete;
    Core& operator=(const Core&) = delete;
    Core(Core&&) = delete;
    Core& operator=(Core&&) = delete;

    ~Core() {
#if defined(__ANDROID__)
        if (loop_) {
            // Everything made on the device goes on the render thread that made it, before the device: the frame and
            // the swapchain (inside VK bench's colour stage, if it is open), then the stand-in.
            loop_->run([this] {
                release_surface();
                standin_.reset();
            });
            loop_.reset();
        }
#endif
        if (started_.device) started_.device->wait_idle();
        save_cache(); // the cache's last write: a reset may forget it from now on
        live_cores.fetch_sub(1);
    }

    [[nodiscard]] std::string facts() {
        const std::scoped_lock lock(facts_mutex_);
        if (!started_.device) {
            // The device was lost and could not be made again (device_lost): why, and the facts this start kept.
            std::string out = build_text();
            out += std::format("vulkan: off for this session ({})\n", session_off_);
            out += std::format("guard: {}\n", started_.guard_line.empty() ? std::string("clear") : started_.guard_line);
            for (const std::string& l : started_.guard->state()) out += l + "\n";
            out += "kept: the facts below were read before the device was lost\n";
            return out + started_.guard->kept_facts();
        }
        std::string out = facts_text(started_.device->facts(), started_);
        if (!session_off_.empty()) out += std::format("vulkan.session: off ({})\n", session_off_);
        return out;
    }

    void save_cache() const {
        if (started_.cache) {
            auto saved = started_.cache->save();
            if (!saved) log::warn("vk pipeline cache not saved: {}", saved.error().text());
        }
    }

    [[nodiscard]] std::string stats() {
        FrameRecord r{};
        {
            const std::scoped_lock lock(records_mutex_);
            if (count_ == 0) return "vk no frames";
            r = ring_[(write_ + ring_.size() - 1) % ring_.size()];
        }
        double graphics_mb = 0;
        {
            // The device, which a device loss replaces on the render thread under this lock.
            const std::scoped_lock lock(facts_mutex_);
            if (started_.device) graphics_mb = static_cast<double>(started_.device->memory().stats().allocated) / 1e6;
        }
        return std::format(
            "vk frame {} cpu {:.2f} ms gpu {:.2f} ms record {:.2f} ms draws {} triangles {}k graphics {:.1f} MB",
            r.frame, static_cast<double>(r.end_ns - r.start_ns) / 1e6, static_cast<double>(r.gpu_ms),
            static_cast<double>(r.record_ms), r.draws, r.triangles_k, graphics_mb);
    }

    /// Copies the frame records not read yet into `out` (at most `capacity`); returns how many.
    std::size_t records(FrameRecord* out, std::size_t capacity) {
        const std::scoped_lock lock(records_mutex_);
        std::size_t n = 0;
        while (read_ != published_ && n < capacity) {
            out[n++] = ring_[read_ % ring_.size()];
            ++read_;
        }
        return n;
    }

#if defined(__ANDROID__)
    bool attach(JNIEnv* env, jobject surface, int width, int height, jobject source, jobject camera, std::string& why) {
        if (camera == nullptr || env->GetDirectBufferAddress(camera) == nullptr ||
            env->GetDirectBufferCapacity(camera) < 512) {
            why = "the camera buffer must be a direct ByteBuffer of 512 bytes";
            return false;
        }
        if (!loop_) {
            loop_ = std::make_unique<platform::RenderLoop>(
                [this] {
                    JNIEnv* e = nullptr;
                    vm_->AttachCurrentThreadAsDaemon(&e, nullptr);
                    render_env_ = e;
                },
                [this] { vm_->DetachCurrentThread(); });
        }
        platform::Window window(env, surface);
        if (!window) {
            why = "the surface has no window";
            return false;
        }
        jclass cls = env->GetObjectClass(source);
        camera_mid_ = env->GetMethodID(cls, "camera", "(JLjava/nio/ByteBuffer;)Z");
        presented_mid_ = env->GetMethodID(cls, "presented", "(JJZ)V");
        env->DeleteLocalRef(cls);
        if (camera_mid_ == nullptr || presented_mid_ == nullptr) {
            env->ExceptionClear();
            why = "the frame source lacks camera(long, ByteBuffer) or presented(long, long, boolean)";
            return false;
        }
        source_ = env->NewGlobalRef(source);
        camera_ = env->NewGlobalRef(camera);
        camera_bytes_ = static_cast<const std::byte*>(env->GetDirectBufferAddress(camera));
        bool ok = false;
        loop_->run([&] { ok = make_surface(std::move(window), width, height, why); });
        return ok;
    }

    void resize(int width, int height) {
        if (!loop_) return;
        loop_->post([this, width, height] {
            wanted_ = {.width = static_cast<std::uint32_t>(width), .height = static_cast<std::uint32_t>(height)};
            remake_swapchain();
        });
    }

    void detach(JNIEnv* env) {
        if (loop_) loop_->run([this] { release_surface(); });
        if (source_ != nullptr) env->DeleteGlobalRef(source_);
        if (camera_ != nullptr) env->DeleteGlobalRef(camera_);
        source_ = nullptr;
        camera_ = nullptr;
        camera_bytes_ = nullptr;
    }

    void request_frame() {
        if (!loop_) return;
        loop_->post([this] {
            requested_ = true;
            ask_vsync();
        });
    }

    /// Switches VK bench's stand-in on (mode 1) or off (0) and its configuration; "" or why it could not.
    std::string bench(int mode, const gpu::Configuration& config, std::uint32_t flags) {
        if (!loop_) return "no surface is attached";
        std::string why;
        loop_->run([&] { why = configure(mode, config, flags); });
        return why;
    }

    /// VK bench's pipeline creation line ("vkbench pipelines: …"), on the render thread.
    std::string pipelines() {
        if (!loop_) return "vkbench pipelines: no surface is attached";
        std::string line;
        loop_->run([&] { line = time_pipelines(); });
        return line;
    }

    void trim(int /*level*/) {
        if (loop_)
            loop_->run([this] { save_cache(); });
        else
            save_cache();
    }
#endif

private:
    Core(CoreOptions options, gpu::Started started) : options_(std::move(options)), started_(std::move(started)) {
        live_cores.fetch_add(1);
    }

    CoreOptions options_;
    gpu::Started started_;
    /// Guards the device's facts and, as a device loss replaces them on the render thread, the device and the cache.
    std::mutex facts_mutex_;
    /// Why the map is off for this session ("" while it is on): the device lost three times, or not made again after
    /// a loss (under facts_mutex_).
    std::string session_off_;
    mutable std::mutex records_mutex_;
    std::array<FrameRecord, 1024> ring_{};
    std::uint64_t write_ = 0;
    std::uint64_t published_ = 0;
    std::uint64_t read_ = 0;
    std::uint64_t count_ = 0;

#if defined(__ANDROID__)
    // ---- On the render thread.

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

    /// Runs `step` behind the guard's pipelines stage: pipelines are made and destroyed there.
    template <class Step>
    auto behind_pipelines(Step step) {
        return behind(gpu::Stage::pipelines, std::move(step));
    }

    /// Lets go of the renderer, behind the guard's pipelines stage (inside VK bench's colour stage while that is open):
    /// its frame goes with it, announced first (gpu/Frames.hpp), the stand-in's pipelines, passes, framebuffers and
    /// images made for its pass and output, then its main pass. A 10-bit frame so goes before the colour stage closes.
    void drop_renderer() {
        if (!renderer_) return;
        renderer_->drain();
        behind_pipelines([this] {
            renderer_.reset();
            return true;
        });
    }

    /// Lets go of the swapchain, announced: inside VK bench's colour stage while that is open, so that a 10-bit
    /// swapchain goes before it closes.
    void drop_swapchain() {
        if (!swapchain_) return;
        announce_swapchain_release();
        swapchain_.reset();
    }

    /// Tells the guard the swapchain is about to go ("swapchain rgb10a2"): its marker names it in the colour stage.
    void announce_swapchain_release() const {
        if (swapchain_)
            gpu::announce_release(std::format("swapchain {}", gpu::colour_name(swapchain_->format())),
                                  started_.guard.get());
    }

    bool make_surface(platform::Window window, int width, int height, std::string& why) {
        release_surface();
        if (!session_off_.empty()) {
            why = session_off_;
            return false;
        }
        window_ = std::move(window);
        wanted_ = {.width = static_cast<std::uint32_t>(width), .height = static_cast<std::uint32_t>(height)};
        auto surface = gpu::make_android_surface(*started_.instance, window_.get());
        if (!surface) {
            why = surface.error().text();
            return false;
        }
        surface_ = std::move(*surface);
        if (!started_.device->can_present(*surface_)) {
            why = "the device's queue cannot present to this surface";
            return false;
        }
        {
            const std::scoped_lock lock(facts_mutex_);
            gpu::collect_surface(started_.device->facts(), started_.device->physical(), *surface_,
                                 started_.device->queue_family());
        }
        // A window come back during VK bench's colour stage: its 10-bit swapchain is made inside the guard's colour
        // stage again (the device was asked about 10 bits as the stage began).
        mark_colour(depth_ == gpu::Depth::deepest);
        auto swapchain = gpu::Swapchain::create(*started_.device, *surface_, wanted_, nullptr, depth_);
        if (!swapchain) {
            swapchain_error_ = swapchain.error().text();
            why = swapchain_error_;
            return false;
        }
        swapchain_ = std::move(*swapchain);
        swapchain_error_.clear();
        note_colour();
        frame_rate_ = window_.set_frame_rate(60.0F);
        log::info("vk surface {} x {} transform {} images {} copy-source {} frame-rate {} colour {}",
                  swapchain_->extent().width, swapchain_->extent().height, vk::to_string(swapchain_->transform()),
                  swapchain_->images().size(), swapchain_->transfer_source() ? "yes" : "no",
                  frame_rate_ ? "60 asked" : "not available", gpu::colour_name(swapchain_->format()));
        if (!hint_.active() && loop_) (void)hint_.open(loop_->tid(), 16'666'667);
        if (mode_ == 1) {
            if (std::string w = make_renderer(); !w.empty()) {
                why = w;
                return false;
            }
        }
        return true;
    }

    /// The facts' colour as the swapchain made it (VK facts' `colour:`), or in VK bench's colour stage their deepest
    /// one, a driver having refused the 10 bits it listed perhaps (`colour.stage:`).
    void note_colour() {
        if (!swapchain_) return;
        const std::scoped_lock lock(facts_mutex_);
        gpu::Facts& facts = started_.device->facts();
        (depth_ == gpu::Depth::deepest ? facts.deepest : facts.colour) = swapchain_->colour();
    }

    /// The crash guard's colour stage (D5.10) is open exactly while 10-bit work may reach the driver: VK bench's colour
    /// stage (depth_ the deepest) with a window attached. Opened before the device is asked about 10 bits and before
    /// the 10-bit swapchain is made; closed once the 10-bit frame (drop_renderer) and the 10-bit swapchain are gone, so
    /// that a crash in between, their destruction included, turns off 10 bits only.
    void mark_colour(bool on) {
        if (on == colour_marked_) return;
        colour_marked_ = on;
        if (on) {
            started_.guard->enter_colour();
        } else {
            started_.guard->leave_colour();
        }
    }

    void release_surface() {
        drop_renderer();
        drop_swapchain();
        surface_.clear();
        window_ = platform::Window();
        requested_ = false;
        mark_colour(false); // nothing of 10 bits is left; a window come back opens it again (make_surface)
    }

    void remake_swapchain() {
        if (!*surface_ || !started_.device) return;
        if (renderer_) renderer_->drain();
        // The old swapchain goes inside create, once the new one is made (or the driver refused it): announced first,
        // so a 10-bit one going on the way out of VK bench's colour stage goes inside it.
        announce_swapchain_release();
        auto swapchain = gpu::Swapchain::create(*started_.device, *surface_, wanted_, std::move(swapchain_), depth_);
        if (!swapchain) {
            swapchain_error_ = swapchain.error().text();
            log::error("vk swapchain: {}", swapchain_error_);
            drop_renderer();
            return;
        }
        swapchain_ = std::move(*swapchain);
        swapchain_error_.clear();
        note_colour();
        if (renderer_) {
            auto retargeted = behind_pipelines([&] { return renderer_->retarget(*swapchain_); });
            if (!retargeted) {
                log::error("vk retarget: {}", retargeted.error().text());
                drop_renderer();
            }
        }
    }

    /// The stand-in and the renderer for the current configuration, behind the guard's pipelines stage.
    std::string make_renderer() {
        if (!swapchain_) return swapchain_error_.empty() ? "no swapchain" : "no swapchain: " + swapchain_error_;
        std::string why = behind_pipelines([&]() -> std::string {
            if (!standin_) {
                if (!city_) city_ = std::make_shared<const standin::City>(standin::City::generate());
                auto standin = standin::StandIn::create(*started_.device, *started_.cache, city_, started_.guard.get());
                if (!standin) return standin.error().text();
                standin_ = std::move(*standin);
            }
            standin_->set_pills(pills_);
            auto renderer =
                gpu::Renderer::create(*started_.device, *swapchain_, *standin_, config_, started_.guard.get());
            if (!renderer) return renderer.error().text();
            renderer_ = std::move(*renderer);
            return {};
        });
        if (!why.empty()) return why;
        if (first_pipelines_ms_ < 0) {
            // This launch's first pipelines, from the cache file as the start found it (a first launch has none).
            first_pipelines_ms_ = standin_->pipelines_ms();
            first_pipelines_ = standin_->pipeline_count();
        }
        log_configuration();
        save_cache();
        return {};
    }

    /// VK bench's pipeline creation (K1: first-launch and warm milliseconds): this launch's first, then the
    /// stand-in's pipelines for the configuration drawn now made again by a stand-in of their own, from an empty
    /// cache (as a first launch makes them, though the driver may keep shaders this process compiled) and from the
    /// cache file as it is saved now (as the next launch makes them).
    std::string time_pipelines() {
        if (!renderer_ || !swapchain_ || !city_) return "vkbench pipelines: no frame is drawn (the bench is off)";
        save_cache();
        const gpu::StartOptions o = start_options(options_);
        struct Made {
            double ms = 0;
            std::uint32_t count = 0;
            std::size_t bytes = 0;
        };
        auto make = [&](bool from_file) -> gpu::Result<Made> {
            ATLAS_GPU_LET(cache, gpu::PipelineCache::open(started_.device->handle(), started_.device->facts(),
                                                          from_file ? o.cache_file : std::filesystem::path(), o.spirv,
                                                          from_file));
            return behind_pipelines([&]() -> gpu::Result<Made> {
                ATLAS_GPU_LET(standin, standin::StandIn::create(*started_.device, cache, city_, started_.guard.get()));
                ATLAS_GPU_CHECK(standin->prepare(renderer_->pass(), *swapchain_));
                return Made{
                    .ms = standin->pipelines_ms(), .count = standin->pipeline_count(), .bytes = cache.loaded_bytes()};
            });
        };
        const auto cold = make(false);
        const auto warm = make(true);
        const auto ms = [](const gpu::Result<Made>& m) {
            return m ? std::format("{:.1f} ms ({} made)", m->ms, m->count) : "failed: " + m.error().text();
        };
        return std::format("vkbench pipelines: this launch {:.1f} ms ({} made, cache file {}); again from an empty "
                           "cache {}; again from the cache file {}{}",
                           first_pipelines_ms_ < 0 ? 0.0 : first_pipelines_ms_, first_pipelines_,
                           started_.cache ? started_.cache->status() : std::string("none"), ms(cold), ms(warm),
                           warm ? std::format(" ({} bytes)", warm->bytes) : std::string());
    }

    std::string configure(int mode, const gpu::Configuration& config, std::uint32_t flags) {
        if (!session_off_.empty()) return session_off_;
        const gpu::Facts& facts = started_.device->facts();
        if (!facts.supports(config.config))
            return std::format("this device has no configuration {}", gpu::name_of(config.config));
        if (config.route == gpu::Route::dynamic && !started_.device->enabled().local_read)
            return "this device has no dynamic rendering with local reads";
        // VK bench's colour stage: 10 bits where the surface offers them in sRGB, the device draws the frame in them
        // and the crash guard has not turned them off for this driver and build (after its crash in this stage);
        // everything else at the frame's 8.
        const bool ten = mode == 1 && (flags & bench_flag::ten_bit) != 0;
        if (ten && !facts.ten_bit_off.empty()) return "10 bits are off for this driver and build: " + facts.ten_bit_off;
        if (ten && !facts.deepest.ten_bit()) return "no 10 bits here: " + facts.deepest.reason;
        // The stand-in draws into the window: with none attached (gone with an app switch or the screen off, Android
        // 10 to 13's SurfaceView following its visibility) there is nothing to draw in, and nothing changes; a window
        // that comes back draws the bench as it was (make_surface).
        if (mode == 1 && !*surface_) return "no surface is attached (the window went away)";
        const gpu::Depth depth = ten ? gpu::Depth::deepest : gpu::default_depth;
        mode_ = mode;
        config_ = config;
        pills_ = (flags & bench_flag::pills) != 0;
        texture_ = (flags & bench_flag::texture) != 0;
        // A renderer of the other depth, or any in mode 0, goes first: an 8-bit frame before the colour stage opens, a
        // 10-bit one inside it, before it closes.
        if (mode_ == 0 || depth != depth_) drop_renderer();
        if (depth != depth_) {
            if (depth == gpu::Depth::deepest) {
                // Into the colour stage: the guard's colour stage first, then the device asked about 10 bits inside it
                // (their formats, a tiler's lazily allocated samples). A device that cannot draw the frame in them
                // after all closes it again, nothing of 10 bits made, and the frame stays at 8.
                mark_colour(true);
                {
                    const std::scoped_lock lock(facts_mutex_);
                    started_.device->check_ten_bit();
                }
                if (!facts.deepest.ten_bit()) {
                    mark_colour(false);
                    return "no 10 bits here: " + facts.deepest.reason;
                }
            }
            // Into the colour stage, a swapchain of 10 bits and (below) a renderer for it, inside the stage. Out of it:
            // the 10-bit frame went with its renderer (above) and the 10-bit swapchain goes as the 8-bit one is made,
            // both inside the stage, which closes after them, nothing of 10 bits being left.
            depth_ = depth;
            remake_swapchain();
            if (depth_ != gpu::Depth::deepest) mark_colour(false);
        } else if (!swapchain_ && *surface_) {
            remake_swapchain(); // one that could not be made before (a remake, a rebuild after a device loss)
        }
        if (mode_ == 0) return {};
        if (!swapchain_) return swapchain_error_.empty() ? "no swapchain" : "no swapchain: " + swapchain_error_;
        if (ten && !swapchain_->colour().ten_bit()) return "no 10 bits here: " + swapchain_->colour().reason;
        if (!renderer_) return make_renderer();
        standin_->set_pills(pills_);
        if (renderer_->configuration() == config_) {
            // Drawn already (a host switch's attach made it for this configuration): its pipelines stand, made once.
            log_configuration();
            return {};
        }
        auto done = behind_pipelines([&] { return renderer_->reconfigure(config_); });
        if (!done) {
            drop_renderer();
            return done.error().text();
        }
        log_configuration();
        return {};
    }

    void log_configuration() const {
        const gpu::Facts& f = started_.device->facts();
        const gpu::Enabled& e = started_.device->enabled();
        log::info("vk config {} route={} frame={} host={} target={} pills={} colour={}", gpu::name_of(config_.config),
                  gpu::name_of(config_.route), gpu::name_of(config_.form), texture_ ? "texture" : "surface",
                  gpu::name_of(config_.target), pills_ ? "on" : "off",
                  swapchain_ ? gpu::colour_name(swapchain_->format()) : std::string("none"));
        log::info("vk path samples={} lazy={} depth={} sync2={} timing={} shaderfma={} route={} cache={} pipelines "
                  "{:.1f} ms ({} made) hint={}",
                  static_cast<int>(config_.samples()), config_.lazy() ? "yes" : "no",
                  f.depth_stencil == vk::Format::eD24UnormS8Uint ? "d24s8" : "d32s8", e.synchronization2 ? "yes" : "no",
                  e.display_timing ? "yes" : "no", e.shader_fma ? "yes" : "no",
                  standin_ && standin_->composite_glsl() ? "glsl" : "direct",
                  started_.cache && started_.cache->hit() ? "hit" : "miss", standin_ ? standin_->pipelines_ms() : 0.0,
                  standin_ ? standin_->pipeline_count() : 0U, hint_.describe());
    }

    void ask_vsync() {
        if (!loop_ || !requested_) return;
        loop_->request_vsync([this](const platform::Vsync& v) { on_vsync(v); });
    }

    void on_vsync(const platform::Vsync& v) {
        if (!requested_ || !renderer_ || lost_ >= 3) return;
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
            requested_ = false;
            return;
        }
        if (more != JNI_TRUE) {
            requested_ = false; // nothing changed: no frame, and no more vsyncs until requestFrame
            return;
        }
        const auto camera = style::read_camera_state(std::span(camera_bytes_, style::camera_state_size));
        if (!camera) {
            if (!camera_warned_) log::error("vk camera refused: {}", style::name_of(camera.error()));
            camera_warned_ = true;
            requested_ = false;
            return;
        }
        drawn_vsync_ns_ = v.frame_ns;
        draw(*camera, v);
        ask_vsync();
    }

    void draw(const style::CameraState& camera, const platform::Vsync& v) {
        const platform::TraceSection trace("atlas frame");
        const std::int64_t start = platform::now_ns();
        if (epoch_ns_ == 0) epoch_ns_ = start;
        if (presented_ < 120 && !guarding_) {
            started_.guard->enter(gpu::Stage::frames);
            guarding_ = true;
        }
        standin_->set_camera(camera);
        standin_->set_time(static_cast<double>(start - epoch_ns_) / 1e9);
        auto report = renderer_->draw(static_cast<std::uint64_t>(v.present_ns > 0 ? v.present_ns : 0));
        if (!report) {
            if (report.error().code == vk::Result::eErrorDeviceLost) {
                device_lost();
            } else {
                log::error("a frame failed: {}", report.error().text());
            }
            return;
        }
        if (report->status == gpu::Acquired::lost) {
            device_lost();
            return;
        }
        const std::int64_t end = platform::now_ns();
        FrameRecord r{};
        r.frame = report->number;
        r.vsync_ns = v.frame_ns;
        r.start_ns = start;
        r.end_ns = end;
        r.desired_ns = v.present_ns;
        r.gpu_frame = report->gpu.frame;
        r.gpu_ms = static_cast<float>(report->gpu.total_ns / 1e6);
        r.gpu_main_ms = static_cast<float>(report->gpu.main_ns / 1e6);
        r.gpu_offscreen_ms = static_cast<float>(report->gpu.offscreen_ns / 1e6);
        r.gpu_after_ms = static_cast<float>(report->gpu.after_ns / 1e6);
        const standin::Counters& c = standin_->counters();
        r.record_ms = static_cast<float>(c.record_ms - c.ranks_ms);
        r.prepare_ms = static_cast<float>(c.ranks_ms);
        r.draws = c.draws;
        r.triangles_k = static_cast<std::uint32_t>(c.triangles / 1000);
        r.fragments_k = static_cast<std::uint32_t>(report->gpu.fragments / 1000);
        r.flags = (report->gpu.valid ? frame_flag::gpu : 0U) | (guarding_ ? frame_flag::guarded : 0U) |
                  config_bits(config_, texture_, pills_, swapchain_->colour().ten_bit());
        const bool stale = report->status == gpu::Acquired::out_of_date || report->status == gpu::Acquired::suboptimal;
        if (report->status == gpu::Acquired::out_of_date || report->status == gpu::Acquired::timeout)
            r.flags |= frame_flag::skipped;
        push_record(r);
        hint_.report(start, end - start, static_cast<std::int64_t>(report->gpu.total_ns));
        if (guarding_ && ++presented_ >= 120) {
            started_.guard->leave();
            guarding_ = false;
            save_cache();
        }
        if ((options_.flags & core_flag::ci) != 0) {
            log::info("vk frame {} {:.2f} gpu {:.2f}", r.frame, static_cast<double>(end - start) / 1e6,
                      static_cast<double>(r.gpu_ms));
        }
        render_env_->CallVoidMethod(source_, presented_mid_, static_cast<jlong>(r.frame), static_cast<jlong>(end),
                                    JNI_TRUE);
        if (render_env_->ExceptionCheck() == JNI_TRUE) render_env_->ExceptionClear();
        if (stale) remake_swapchain();
    }

    void push_record(const FrameRecord& r) {
        // The display's own presentation times (VK_GOOGLE_display_timing) for earlier frames still in the ring.
        timings_.clear();
        if (swapchain_) swapchain_->past_timings(timings_);
        const std::scoped_lock lock(records_mutex_);
        for (const gpu::PresentedTiming& t : timings_) {
            for (std::uint64_t i = published_; i < write_; ++i) {
                FrameRecord& old = ring_[i % ring_.size()];
                if (static_cast<std::uint32_t>(old.frame) == t.present_id) {
                    old.presented_ns = static_cast<std::int64_t>(t.actual_ns);
                    old.flags |= frame_flag::presented;
                }
            }
        }
        ring_[write_ % ring_.size()] = r;
        ++write_;
        ++count_;
        // A record is final once the display has said when it showed it, or eight frames later.
        while (published_ < write_ &&
               ((ring_[published_ % ring_.size()].flags & frame_flag::presented) != 0 || write_ - published_ > 8)) {
            ++published_;
        }
        if (read_ + ring_.size() < write_) read_ = write_ - ring_.size(); // a reader too slow loses the oldest
    }

    /// The map off for this session, saying why (VkFacts' `vulkan.session:` line, and every bench call after).
    void turn_off_session(std::string why) {
        log::error("vk {}", why);
        lost_ = 3;
        const std::scoped_lock lock(facts_mutex_); // facts() reads it on other threads
        session_off_ = std::move(why);
    }

    void device_lost() {
        ++lost_;
        if (lost_ >= 3) {
            drop_renderer();
            turn_off_session("device lost ×3: the map is off for this session");
            return;
        }
        log::error("device lost ({} this session): rebuilding the device", lost_);
        // Everything made on the lost device goes, the frame announced (inside VK bench's colour stage while it is
        // open: a 10-bit frame and swapchain go inside it).
        drop_renderer();
        standin_.reset();
        drop_swapchain();
        {
            // facts() and stats() read the device and the cache on other threads.
            const std::scoped_lock lock(facts_mutex_);
            started_.cache.reset();
            started_.device.reset();
        }
        // As the start opened it (K6's answer for configuration B among the options), to present to this surface,
        // behind the guard's stages as at the start: the device's facts (its choice's queries), the device, the cache.
        gpu::DeviceOptions reopen = started_.device_options;
        reopen.surface = *surface_;
        auto picked = behind(gpu::Stage::facts, [&] { return gpu::Device::pick(*started_.instance, reopen); });
        if (!picked) {
            turn_off_session("the device could not be made again after its loss: " + picked.error().text());
            return;
        }
        auto device = behind(gpu::Stage::device,
                             [&] { return gpu::Device::open(*started_.instance, std::move(*picked), reopen); });
        if (!device) {
            turn_off_session("the device could not be made again after its loss: " + device.error().text());
            return;
        }
        {
            // The guard's colour verdict, and in VK bench's colour stage (open: a surface is attached) the device
            // asked about 10 bits again, inside it.
            const std::scoped_lock lock(facts_mutex_);
            started_.device = std::move(*device);
            started_.device->facts().ten_bit_off = started_.guard->ten_bit_off();
            gpu::decide_colour(started_.device->facts());
            if (depth_ == gpu::Depth::deepest) started_.device->check_ten_bit();
        }
        const gpu::StartOptions o = start_options(options_);
        auto cache = behind(gpu::Stage::cache, [&] {
            return gpu::PipelineCache::open(started_.device->handle(), started_.device->facts(), o.cache_file, o.spirv,
                                            !o.cache_file.empty());
        });
        if (!cache) {
            turn_off_session("the pipeline cache could not be opened again after the device's loss: " +
                             cache.error().text());
            return;
        }
        {
            const std::scoped_lock lock(facts_mutex_);
            started_.cache = std::make_unique<gpu::PipelineCache>(std::move(*cache));
        }
        if (*surface_) {
            {
                // The new device's facts, with the surface's and the frame's colour on it, as the attach read them.
                const std::scoped_lock lock(facts_mutex_);
                gpu::collect_surface(started_.device->facts(), started_.device->physical(), *surface_,
                                     started_.device->queue_family());
            }
            auto swapchain = gpu::Swapchain::create(*started_.device, *surface_, wanted_, nullptr, depth_);
            if (!swapchain) {
                swapchain_error_ = swapchain.error().text();
                turn_off_session("the swapchain could not be made again after the device's loss: " + swapchain_error_);
                return;
            }
            swapchain_ = std::move(*swapchain);
            swapchain_error_.clear();
            note_colour();
        }
        if (mode_ == 1) {
            if (const std::string why = make_renderer(); !why.empty()) log::error("vk rebuild: {}", why);
        }
        requested_ = true;
        ask_vsync();
    }

    // Declared after started_, so that whatever is left of them goes before the device and the instance they were made
    // on; the renderer after the stand-in it prepared and the swapchain it draws into, so that it goes first.
    JavaVM* vm_ = nullptr;
    JNIEnv* render_env_ = nullptr;
    std::unique_ptr<platform::RenderLoop> loop_;
    platform::Window window_;
    platform::HintSession hint_;
    platform::Thermal thermal_;
    vk::raii::SurfaceKHR surface_{nullptr};
    std::unique_ptr<gpu::Swapchain> swapchain_;
    std::string swapchain_error_; // why the last swapchain could not be made ("" once one is)
    std::shared_ptr<const standin::City> city_;
    std::unique_ptr<standin::StandIn> standin_;
    std::unique_ptr<gpu::Renderer> renderer_;
    std::vector<gpu::PresentedTiming> timings_;
    jobject source_ = nullptr;
    jobject camera_ = nullptr;
    const std::byte* camera_bytes_ = nullptr;
    jmethodID camera_mid_ = nullptr;
    jmethodID presented_mid_ = nullptr;
    vk::Extent2D wanted_{};
    gpu::Configuration config_{};
    /// The swapchain's colour: the frame's 8 bits, or in VK bench's colour stage as deep as the surface allows.
    gpu::Depth depth_ = gpu::default_depth;
    bool colour_marked_ = false; // the guard's colour stage is open (mark_colour)
    int mode_ = 0;
    bool pills_ = false;
    bool texture_ = false;
    bool requested_ = false;
    bool guarding_ = false;
    double first_pipelines_ms_ = -1; // this launch's first pipelines (-1 until the stand-in draws)
    std::uint32_t first_pipelines_ = 0;
    bool frame_rate_ = false;
    bool camera_warned_ = false;
    int lost_ = 0;
    std::uint32_t presented_ = 0;
    std::int64_t last_vsync_ns_ = 0;
    std::int64_t drawn_vsync_ns_ = 0;
    std::int64_t period_ns_ = 0;
    std::int64_t epoch_ns_ = 0;
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
                     jint flags) {
    if (Core* core = core_of(handle); core != nullptr) return java(env, core->facts());
    // Without a core: a guarded start of its own, read, closed (Vulkan's facts need a device).
    const CoreOptions o{.cache_dir = {},
                        .guard_dir = string_of(env, guard_dir),
                        .system = string_of(env, system),
                        .plant = {},
                        .flags = static_cast<std::uint32_t>(flags)};
    (void)cache_dir;
    auto started = gpu::start_guarded(start_options(o));
    if (!started) return java(env, refusal_text(started.error()));
    const std::string text = facts_text(started->device->facts(), *started);
    started->device->wait_idle();
    return java(env, text);
}

/// VK facts' "Reset guard" (debug and bench builds): forgets every crash, every driver the guard turned off and every
/// 10 bits it turned off, and the pipeline cache, as a fresh install would; the next start tries Vulkan, and 10 bits,
/// again. Only with no core alive: a live core keeps its pipeline cache in memory and writes it back (on trim, on
/// close, after its first 120 frames), which would undo the reset, so the reset is refused until it is closed
/// (MapCore.resetGuard closes the process's core first). What it forgot, or why not, in words.
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
                    jint flags) {
    const CoreOptions o{.cache_dir = string_of(env, cache_dir),
                        .guard_dir = string_of(env, guard_dir),
                        .system = string_of(env, system),
                        .plant = string_of(env, plant),
                        .flags = static_cast<std::uint32_t>(flags)};
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

void native_detach(JNIEnv* env, jclass /*cls*/, jlong handle) {
    if (Core* core = core_of(handle); core != nullptr) core->detach(env);
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

void native_trim(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint level) {
    if (Core* core = core_of(handle); core != nullptr) core->trim(level);
}

jstring native_pipelines(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    return java(env, core == nullptr ? std::string("vkbench pipelines: no core") : core->pipelines());
}
#else
// Hosts have no surfaces: the frame loop is the phone's; the host build serves the facts, the layouts and the tests.
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
void native_trim(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint /*level*/) {
    if (Core* core = core_of(handle); core != nullptr) core->save_cache();
}
jstring native_pipelines(JNIEnv* env, jclass /*cls*/, jlong /*handle*/) {
    return java(env, "vkbench pipelines: hosts have no surfaces");
}
#endif

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

const std::array<JNINativeMethod, 18>& methods() {
    static const std::array<JNINativeMethod, 18> table{{
        method("abi", "()I", fn(&native_abi)),
        method("layouts", "()Ljava/lang/String;", fn(&native_layouts)),
        method("lastError", "()Ljava/lang/String;", fn(&native_last_error)),
        method("facts", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
               fn(&native_facts)),
        method("create", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)J",
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
        method("pipelines", "(J)Ljava/lang/String;", fn(&native_pipelines)),
        method("resetGuard", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", fn(&native_reset_guard)),
    }};
    return table;
}

} // namespace

std::span<const JNINativeMethod> facade_methods() noexcept { return methods(); }

} // namespace atlas::jni
