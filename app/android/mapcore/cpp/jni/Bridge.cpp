// The façade's natives (jni/Bridge.hpp): the process's one core, its guarded start, and on the phone the frame loop:
// the render thread (an ALooper at display priority), Choreographer's vsync, the camera pulled from Kotlin at each
// paced vsync (FrameSource.camera, allocation-free on both sides), the swapchain on the SurfaceView's or the
// TextureView's window, the frame through the device layer, the ADPF hint, the frame records VK bench reads, the crash
// guard's first 120 frames, and device loss rebuilt at most twice a session.
#include "jni/Bridge.hpp"

#include "base/build_info.hpp"
#include "base/log.hpp"
#include "check/NumericsCheck.hpp"
#include "gen/spirv/standin/blur.hpp"
#include "gen/spirv/standin/composite.hpp"
#include "gen/spirv/standin/composite_x1.hpp"
#include "gen/spirv/standin/composite_x1_loc1.hpp"
#include "gen/spirv/standin/frosted.hpp"
#include "gen/spirv/standin/standin.hpp"
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
} // namespace bench_flag

/// The hash of the core's shaders: the pipeline cache's key.
[[nodiscard]] std::uint64_t core_spirv() noexcept {
    const std::array<std::string_view, 6> hashes{
        spirv::standin_standin_sha256,           spirv::standin_composite_sha256, spirv::standin_composite_x1_sha256,
        spirv::standin_composite_x1_loc1_sha256, spirv::standin_blur_sha256,      spirv::standin_frosted_sha256};
    return gpu::spirv_hash(hashes);
}

[[nodiscard, maybe_unused]] std::uint32_t config_bits(const gpu::Configuration& c, bool texture, bool pills) noexcept {
    auto b = static_cast<std::uint32_t>(c.config);
    b |= (c.route == gpu::Route::dynamic ? 1U : 0U) << 3;
    b |= (c.form == gpu::FrameForm::onepass ? 1U : 0U) << 4;
    b |= (c.target == gpu::CompositeTarget::direct ? 1U : 0U) << 5;
    b |= (texture ? 1U : 0U) << 6;
    b |= (pills ? 1U : 0U) << 7;
    return b << frame_flag::config_shift;
}

/// The options Native.create passes.
struct CoreOptions {
    std::string cache_dir;
    std::string guard_dir;
    std::string system;
    std::string plant;
    std::uint32_t flags = 0;
};

[[nodiscard]] gpu::StartOptions start_options(const CoreOptions& o) {
    return {.guard_dir = o.guard_dir,
            .cache_file = o.cache_dir.empty() ? std::filesystem::path()
                                              : std::filesystem::path(o.cache_dir) / "vk" / "pipelines.bin",
            .system = o.system,
            .plant = o.plant,
            .validation = (o.flags & core_flag::validation) != 0,
            .require_validation = false,
            .msaa2_close = (o.flags & core_flag::msaa2) != 0,
            .spirv = core_spirv()};
}

[[nodiscard]] std::string facts_text(const gpu::Facts& facts, const gpu::Started& s) {
    std::string out = std::format("build: {}\n", build_info());
    for (const std::string& l : gpu::facts_lines(facts)) out += l + "\n";
    out += std::format("pipeline.cache: {}\n", s.cache ? s.cache->status() : std::string("none"));
    out += std::format("guard: {}\n", s.guard_line.empty() ? std::string("clear") : s.guard_line);
    return out;
}

/// A refused start, as the facts show it: the guard's line, why, and the facts an earlier start kept.
[[nodiscard]] std::string refusal_text(const gpu::StartRefusal& r) {
    std::string out = std::format("build: {}\n", build_info());
    out += std::format("vulkan: off ({})\n", r.reason);
    if (!r.line.empty()) out += std::format("guard: {}\n", r.line);
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
            loop_->run([this] { release_surface(); });
            loop_.reset();
        }
#endif
        if (started_.device) started_.device->wait_idle();
        save_cache();
    }

    [[nodiscard]] std::string facts() {
        const std::scoped_lock lock(facts_mutex_);
        return facts_text(started_.device->facts(), started_);
    }

    void save_cache() const {
        if (started_.cache) {
            auto saved = started_.cache->save();
            if (!saved) log::warn("vk pipeline cache not saved: {}", saved.error().text());
        }
    }

    [[nodiscard]] std::string stats() const {
        const std::scoped_lock lock(records_mutex_);
        if (count_ == 0) return "vk no frames";
        const FrameRecord& r = ring_[(write_ + ring_.size() - 1) % ring_.size()];
        const gpu::MemoryStats m = started_.device->memory().stats();
        return std::format(
            "vk frame {} cpu {:.2f} ms gpu {:.2f} ms record {:.2f} ms draws {} triangles {}k graphics {:.1f} MB",
            r.frame, static_cast<double>(r.end_ns - r.start_ns) / 1e6, static_cast<double>(r.gpu_ms),
            static_cast<double>(r.record_ms), r.draws, r.triangles_k, static_cast<double>(m.allocated) / 1e6);
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
    Core(CoreOptions options, gpu::Started started) : options_(std::move(options)), started_(std::move(started)) {}

#if defined(__ANDROID__)
    // ---- On the render thread.

    /// Runs `step` behind the guard's pipelines stage (D5.10), then goes back to the frames stage while the first
    /// frames are guarded.
    template <class Step>
    auto behind_pipelines(Step step) {
        started_.guard->enter(gpu::Stage::pipelines);
        auto result = step();
        started_.guard->leave();
        if (guarding_) started_.guard->enter(gpu::Stage::frames);
        return result;
    }

    bool make_surface(platform::Window window, int width, int height, std::string& why) {
        release_surface();
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
        auto swapchain = gpu::Swapchain::create(*started_.device, *surface_, wanted_, nullptr);
        if (!swapchain) {
            why = swapchain.error().text();
            return false;
        }
        swapchain_ = std::move(*swapchain);
        frame_rate_ = window_.set_frame_rate(60.0F);
        log::info("vk surface {} x {} transform {} images {} copy-source {} frame-rate {}", swapchain_->extent().width,
                  swapchain_->extent().height, vk::to_string(swapchain_->transform()), swapchain_->images().size(),
                  swapchain_->transfer_source() ? "yes" : "no", frame_rate_ ? "60 asked" : "not available");
        if (!hint_.active() && loop_) (void)hint_.open(loop_->tid(), 16'666'667);
        if (mode_ == 1) {
            if (std::string w = make_renderer(); !w.empty()) {
                why = w;
                return false;
            }
        }
        return true;
    }

    void release_surface() {
        if (renderer_) renderer_->drain();
        renderer_.reset();
        swapchain_.reset();
        surface_.clear();
        window_ = platform::Window();
        requested_ = false;
    }

    void remake_swapchain() {
        if (!*surface_) return;
        if (renderer_) renderer_->drain();
        auto swapchain = gpu::Swapchain::create(*started_.device, *surface_, wanted_, std::move(swapchain_));
        if (!swapchain) {
            log::error("vk swapchain: {}", swapchain.error().text());
            renderer_.reset();
            return;
        }
        swapchain_ = std::move(*swapchain);
        if (renderer_) {
            auto retargeted = behind_pipelines([&] { return renderer_->retarget(*swapchain_); });
            if (!retargeted) {
                log::error("vk retarget: {}", retargeted.error().text());
                renderer_.reset();
            }
        }
    }

    /// The stand-in and the renderer for the current configuration, behind the guard's pipelines stage.
    std::string make_renderer() {
        if (!swapchain_) return "no swapchain";
        std::string why = behind_pipelines([&]() -> std::string {
            if (!standin_) {
                if (!city_) city_ = std::make_shared<const standin::City>(standin::City::generate());
                auto standin = standin::StandIn::create(*started_.device, *started_.cache, city_);
                if (!standin) return standin.error().text();
                standin_ = std::move(*standin);
            }
            standin_->set_pills(pills_);
            auto renderer = gpu::Renderer::create(*started_.device, *swapchain_, *standin_, config_);
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
                ATLAS_GPU_LET(standin, standin::StandIn::create(*started_.device, cache, city_));
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
        const gpu::Facts& facts = started_.device->facts();
        if (!facts.supports(config.config))
            return std::format("this device has no configuration {}", gpu::name_of(config.config));
        if (config.route == gpu::Route::dynamic && !started_.device->enabled().local_read)
            return "this device has no dynamic rendering with local reads";
        mode_ = mode;
        config_ = config;
        pills_ = (flags & bench_flag::pills) != 0;
        texture_ = (flags & bench_flag::texture) != 0;
        if (mode_ == 0) {
            if (renderer_) renderer_->drain();
            renderer_.reset();
            return {};
        }
        if (!renderer_) return make_renderer();
        standin_->set_pills(pills_);
        auto done = behind_pipelines([&] { return renderer_->reconfigure(config_); });
        if (!done) {
            renderer_.reset();
            return done.error().text();
        }
        log_configuration();
        return {};
    }

    void log_configuration() const {
        const gpu::Facts& f = started_.device->facts();
        const gpu::Enabled& e = started_.device->enabled();
        log::info("vk config {} route={} frame={} host={} target={} pills={}", gpu::name_of(config_.config),
                  gpu::name_of(config_.route), gpu::name_of(config_.form), texture_ ? "texture" : "surface",
                  gpu::name_of(config_.target), pills_ ? "on" : "off");
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
                  config_bits(config_, texture_, pills_);
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

    void device_lost() {
        ++lost_;
        if (lost_ >= 3) {
            log::error("vk device lost ×3: the map is off for this session");
            renderer_.reset();
            return;
        }
        log::error("device lost ({} this session): rebuilding the device", lost_);
        renderer_.reset();
        standin_.reset();
        swapchain_.reset();
        started_.cache.reset();
        started_.device.reset();
        auto device = gpu::Device::open(*started_.instance, {.surface = *surface_});
        if (!device) {
            log::error("vk rebuild failed: {}", device.error().text());
            lost_ = 3;
            return;
        }
        started_.device = std::move(*device);
        const gpu::StartOptions o = start_options(options_);
        auto cache = gpu::PipelineCache::open(started_.device->handle(), started_.device->facts(), o.cache_file,
                                              o.spirv, !o.cache_file.empty());
        if (!cache) {
            log::error("vk rebuild: {}", cache.error().text());
            lost_ = 3;
            return;
        }
        started_.cache = std::make_unique<gpu::PipelineCache>(std::move(*cache));
        if (*surface_) {
            auto swapchain = gpu::Swapchain::create(*started_.device, *surface_, wanted_, nullptr);
            if (!swapchain) {
                log::error("vk rebuild: {}", swapchain.error().text());
                lost_ = 3;
                return;
            }
            swapchain_ = std::move(*swapchain);
        }
        if (mode_ == 1) {
            if (const std::string why = make_renderer(); !why.empty()) log::error("vk rebuild: {}", why);
        }
        requested_ = true;
        ask_vsync();
    }

    JavaVM* vm_ = nullptr;
    JNIEnv* render_env_ = nullptr;
    std::unique_ptr<platform::RenderLoop> loop_;
    platform::Window window_;
    platform::HintSession hint_;
    platform::Thermal thermal_;
    vk::raii::SurfaceKHR surface_{nullptr};
    std::unique_ptr<gpu::Swapchain> swapchain_;
    std::unique_ptr<gpu::Renderer> renderer_;
    std::unique_ptr<standin::StandIn> standin_;
    std::shared_ptr<const standin::City> city_;
    std::vector<gpu::PresentedTiming> timings_;
    jobject source_ = nullptr;
    jobject camera_ = nullptr;
    const std::byte* camera_bytes_ = nullptr;
    jmethodID camera_mid_ = nullptr;
    jmethodID presented_mid_ = nullptr;
    vk::Extent2D wanted_{};
    gpu::Configuration config_{};
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

    CoreOptions options_;
    gpu::Started started_;
    std::mutex facts_mutex_;
    mutable std::mutex records_mutex_;
    std::array<FrameRecord, 1024> ring_{};
    std::uint64_t write_ = 0;
    std::uint64_t published_ = 0;
    std::uint64_t read_ = 0;
    std::uint64_t count_ = 0;
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

const std::array<JNINativeMethod, 17>& methods() {
    static const std::array<JNINativeMethod, 17> table{{
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
    }};
    return table;
}

} // namespace

std::span<const JNINativeMethod> facade_methods() noexcept { return methods(); }

} // namespace atlas::jni
