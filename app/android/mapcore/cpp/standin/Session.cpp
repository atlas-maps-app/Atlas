// The core's session (standin/Session.hpp).
#include "standin/Session.hpp"

#include "base/build_info.hpp"
#include "base/log.hpp"
#include "gpu/Swapchain.hpp"

#include <chrono>
#include <format>
#include <utility>

namespace atlas::standin {

namespace {

[[nodiscard]] std::uint32_t config_bits(const gpu::Configuration& c, bool texture, bool pills, bool ten_bit) noexcept {
    auto b = static_cast<std::uint32_t>(c.config);
    b |= (c.route == gpu::Route::dynamic ? 1U : 0U) << 3;
    b |= (c.form == gpu::FrameForm::onepass ? 1U : 0U) << 4;
    b |= (c.target == gpu::CompositeTarget::direct ? 1U : 0U) << 5;
    b |= (texture ? 1U : 0U) << 6;
    b |= (pills ? 1U : 0U) << 7;
    return (b << frame_flag::config_shift) | (ten_bit ? frame_flag::ten_bit : 0U);
}

/// CLOCK_MONOTONIC in nanoseconds on Android and Linux (std::chrono::steady_clock): Choreographer's clock, and the
/// records'.
[[nodiscard]] std::int64_t now_ns() noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

/// The frames the crash guard's frames stage holds (D5.10).
constexpr std::uint32_t guarded_frames = 120;

} // namespace

std::string build_lines() {
    const std::string_view linker = linker_id();
    return std::format("build: {}\nbuild.linker: {}\n", build_info(), linker.empty() ? "none" : linker);
}

std::string guard_lines(const gpu::Started& started) {
    std::string out =
        std::format("guard: {}\n", started.guard_line.empty() ? std::string("clear") : started.guard_line);
    if (!started.discarded_line.empty()) out += started.discarded_line + "\n";
    if (!started.exit.empty() && !started.guard_line.empty())
        out += std::format("guard.exit: {} (how the process that left the marker ended)\n", started.exit);
    for (const std::string& l : started.guard->state()) out += l + "\n";
    return out;
}

gpu::Result<std::unique_ptr<gpu::Output>> SessionWindow::output(const gpu::Device& device, vk::SurfaceKHR surface,
                                                                vk::Extent2D wanted, const gpu::Output* old,
                                                                gpu::Depth depth) {
    ATLAS_GPU_LET(swapchain, gpu::Swapchain::create(device, surface, wanted, old, depth));
    return std::unique_ptr<gpu::Output>(std::move(swapchain));
}

Session::Session(gpu::Started& started, SessionOptions options)
    : started_(started), options_(std::move(options)), city_(options_.city) {}

Session::~Session() { close(); }

// ---- The window.

bool Session::attach(std::unique_ptr<SessionWindow> window, vk::Extent2D size, std::string& why) {
    release_window();
    if (closed_) {
        why = "the core is closed";
        return false;
    }
    if (!session_off_.empty()) {
        why = session_off_;
        return false;
    }
    window_ = std::move(window);
    wanted_ = size;
    // The surface and its queries, at 8 bits: whether the queue presents to it, its capabilities, formats and present
    // modes, and the frame's colour on it.
    why = behind(gpu::Stage::surface, [&]() -> std::string {
        auto surface = window_->surface(*started_.instance);
        if (!surface) return surface.error().text();
        surface_ = std::move(*surface);
        if (!*surface_) {
            // A window without a surface (a host test's offscreen images): the frame's colour decided afresh for it, as
            // a surface's queries decide it for a phone's window (no driver call: the device's facts as read).
            gpu::decide_colour(started_.device->facts());
            return {};
        }
        if (!started_.device->can_present(*surface_)) return "the device's queue cannot present to this surface";
        gpu::collect_surface(started_.device->facts(), started_.device->physical(), *surface_,
                             started_.device->queue_family());
        return {};
    });
    // The bench's stand-in first, should a device's rebuild have left none (8-bit work, outside the colour stage); then
    // the output: for a window come back during VK bench's colour stage, inside the colour stage again.
    if (why.empty() && mode_ == 1) why = make_standin();
    if (why.empty()) why = make_output();
    if (why.empty()) {
        frame_rate_ = window_->set_frame_rate(60.0F);
        log::info("vk surface {} x {} transform {} images {} copy-source {} frame-rate {} colour {}",
                  output_->extent().width, output_->extent().height, vk::to_string(output_->transform()),
                  output_->images().size(), output_->transfer_source() ? "yes" : "no",
                  frame_rate_ ? "60 asked" : "not available", gpu::colour_name(output_->format()));
        if (mode_ == 1) why = make_renderer();
    }
    if (!why.empty()) {
        // Nothing of the window is held: a refused attach has no detach after it, and the colour stage must not stay
        // open on a window that went (a window that comes back attaches afresh).
        release_window();
        return false;
    }
    return true;
}

void Session::resize(vk::Extent2D size) {
    wanted_ = size;
    remake_output();
}

void Session::remake() { remake_output(); }

void Session::detach() { release_window(); }

void Session::release_window() {
    drop_renderer();
    drop_output();
    close_colour(); // nothing of 10 bits is left: a window come back opens it again (make_output)
    if (*surface_) {
        behind(gpu::Stage::surface, [&] {
            surface_.clear();
            return true;
        });
    }
    window_.reset();
}

void Session::drop_renderer() {
    if (!renderer_) return;
    // No frames without a renderer: the frames stage ends with it (its marker taken by the pipelines stage's, then
    // gone), and the next renderer's first draw opens it again. So no marker stays on disk while nothing is drawn: the
    // window gone, mode 0.
    guarding_ = false;
    behind(gpu::Stage::pipelines, [&] {
        renderer_->drain();
        renderer_.reset();
        return true;
    });
}

void Session::drop_output() {
    if (!output_) return;
    behind(gpu::Stage::surface, [&] {
        release_output();
        return true;
    });
}

void Session::release_output() {
    if (!output_) return;
    const std::string colour = gpu::colour_name(output_->format());
    const gpu::Announced named = gpu::announce_release(
        {.kind = "swapchain", .detail = colour, .ten_bit = gpu::ten_bit(output_->format())}, started_.guard.get());
    output_.reset();
}

std::string Session::make_output() {
    if (!window_) return "no surface is attached";
    gpu::Facts& facts = started_.device->facts();
    if (depth_ == gpu::Depth::deepest) {
        // VK bench's colour stage: it opens, and the device is asked about 10 bits inside it; then the output at 10
        // bits is made inside it, where the device can draw the frame in them on this surface.
        open_colour();
        if (!colour_open()) {
            // No stage, no 10 bits: the frame takes its 8, saying why.
            facts.deepest = facts.colour;
            facts.deepest.reason = "VK bench's colour stage could not open (its pipeline cache could not be made)";
        } else if (facts.deepest.ten_bit()) {
            const vk::Format asked = facts.deepest.format;
            auto made = behind(gpu::Stage::surface, [&] {
                return window_->output(*started_.device, *surface_, wanted_, nullptr, gpu::Depth::deepest);
            });
            if (made) {
                output_ = std::move(*made);
                output_error_.clear();
                facts.deepest = output_->colour();
                if (output_->colour().ten_bit()) return {};
                // An 8-bit one after all (the window's formats are not the ones the facts read): nothing of 10 bits is
                // alive, so the stage closes, and the output stays, the frame's 8 bits outside it.
                close_colour();
                depth_ = gpu::default_depth;
                return {};
            }
            // Refused by the driver: nothing of 10 bits came of it, and the frame takes its 8 bits instead.
            facts.deepest = facts.colour;
            facts.deepest.reason = std::format("the driver refused a swapchain of {} ({}): 8 bits",
                                               gpu::colour_name(asked), made.error().text());
            log::warn("vk swapchain: {}", facts.deepest.reason);
        }
        // Nothing of 10 bits is made: the stage closes, and the frame's 8-bit output is made in its place, outside.
        close_colour();
        depth_ = gpu::default_depth;
    }
    auto made = behind(gpu::Stage::surface,
                       [&] { return window_->output(*started_.device, *surface_, wanted_, nullptr, depth_); });
    if (!made) {
        output_error_ = made.error().text();
        return output_error_;
    }
    output_ = std::move(*made);
    output_error_.clear();
    note_colour();
    return {};
}

void Session::remake_output() {
    if (!window_ || !started_.device || closed_) return;
    if (!output_) {
        // None to make again (refused before): one afresh, the colour stage opening for it at 10 bits (the renderer
        // went with the last one).
        if (std::string why = make_output(); !why.empty()) log::error("vk swapchain: {}", why);
        return;
    }
    std::string why;
    // A new frame (a new size, or the old output out of date): the frames stage ends with the old one, and the new
    // one's first draws open it again under its name.
    new_frame();
    // The old output is retired by the call that makes the new one, and goes once that returns, made or refused:
    // announced as it goes (a 10-bit one inside the colour stage, an 8-bit one inside the surface stage), after the new
    // one's making, which is the stage's alone.
    behind(gpu::Stage::surface, [&] {
        if (renderer_) renderer_->drain();
        auto made = window_->output(*started_.device, *surface_, wanted_, output_.get(), depth_);
        release_output();
        if (made) {
            output_ = std::move(*made);
        } else {
            why = made.error().text();
        }
        return true;
    });
    if (!why.empty()) {
        output_error_ = why;
        log::error("vk swapchain: {}", why);
        // Nothing to draw into: the frame goes, and with it the last of 10 bits (the old output went above), so the
        // colour stage closes; the next step that needs an output makes one (make_output), the stage opening for it.
        drop_renderer();
        close_colour();
        return;
    }
    output_error_.clear();
    note_colour();
    if (depth_ == gpu::Depth::deepest && !output_->colour().ten_bit()) {
        // Made again at 8 bits after all (the window's formats changed): the 10-bit frame goes inside the colour stage,
        // which then closes, and the next step draws the frame at 8 outside it.
        drop_renderer();
        close_colour();
        depth_ = gpu::default_depth;
        return;
    }
    if (renderer_) {
        auto retargeted = behind(gpu::Stage::pipelines, [&] { return renderer_->retarget(*output_); });
        if (!retargeted) {
            log::error("vk retarget: {}", retargeted.error().text());
            drop_renderer();
        }
    }
}

void Session::new_frame() noexcept {
    guarding_ = false;
    presented_ = 0;
}

void Session::open_colour() {
    if (colour_cache_ || closed_) return;
    started_.guard->enter_colour();
    // The 10-bit frame's pipelines are made through a cache of the stage's own, which goes with it (close_colour): the
    // pipeline cache on disk never holds a 10-bit pipeline, so its writes are the cache stage's.
    auto cache =
        gpu::PipelineCache::open(started_.device->handle(), started_.device->facts(), {}, options_.spirv, false);
    if (!cache) {
        log::error("vk colour stage: {}", cache.error().text());
        started_.guard->leave_colour();
        return;
    }
    colour_cache_ = std::make_unique<gpu::PipelineCache>(std::move(*cache));
    // The device asked about 10 bits (their formats, a tiler's lazily allocated samples), inside the stage.
    started_.device->check_ten_bit();
}

void Session::close_colour() {
    if (!colour_cache_) return;
    if (standin_) standin_->use_prepare_cache(nullptr);
    {
        const gpu::Announced named = gpu::announce_release(
            {.kind = "cache", .detail = "(the colour stage's)", .ten_bit = true}, started_.guard.get());
        colour_cache_.reset();
    }
    started_.guard->leave_colour();
}

void Session::note_colour() {
    if (!output_ || !started_.device) return;
    gpu::Facts& facts = started_.device->facts();
    (depth_ == gpu::Depth::deepest ? facts.deepest : facts.colour) = output_->colour();
}

// ---- VK bench.

std::string Session::make_standin() {
    if (standin_) return {};
    // Its fixed pipelines and modules, the city's buffers and the atlas are 8-bit work: a crash in them is the
    // pipelines stage's (Vulkan's ladder), never 10 bits'. Every step makes it before an output of 10 bits opens the
    // colour stage; only one a device's rebuild failed to make again can be missing inside it, and it waits for the
    // stage's end.
    if (colour_open()) return "the stand-in is made at 8 bits, outside VK bench's colour stage";
    return behind(gpu::Stage::pipelines, [&]() -> std::string {
        if (!city_) city_ = std::make_shared<const City>(City::generate());
        auto standin = StandIn::create(*started_.device, *started_.cache, city_, started_.guard.get());
        if (!standin) return standin.error().text();
        standin_ = std::move(*standin);
        return {};
    });
}

std::string Session::make_renderer() {
    if (!output_) return output_error_.empty() ? "no swapchain" : "no swapchain: " + output_error_;
    if (std::string why = make_standin(); !why.empty()) return why;
    const bool colour = colour_open();
    new_frame(); // a new frame: its first draws are guarded (the frames stage), under its name
    std::string why = behind(gpu::Stage::pipelines, [&]() -> std::string {
        // In the colour stage the 10-bit frame's pipelines go through the stage's own cache.
        standin_->use_prepare_cache(colour ? colour_cache_.get() : nullptr);
        standin_->set_pills(pills_);
        auto renderer = gpu::Renderer::create(*started_.device, *output_, *standin_, config_, started_.guard.get());
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

std::string Session::bench(int mode, const gpu::Configuration& config, std::uint32_t flags) {
    if (closed_) return "the core is closed";
    if (!session_off_.empty()) return session_off_;
    const gpu::Facts& facts = started_.device->facts();
    if (!facts.supports(config.config))
        return std::format("this device has no configuration {}", gpu::name_of(config.config));
    if (config.route == gpu::Route::dynamic && !started_.device->enabled().local_read)
        return "this device has no dynamic rendering with local reads";
    // VK bench's colour stage: 10 bits where the surface offers them in sRGB, the device draws the frame in them and
    // the crash guard has not turned them off for this driver and build (after its crash in this stage); everything
    // else at the frame's 8.
    const bool ten = mode == 1 && (flags & bench_flag::ten_bit) != 0;
    if (ten && !facts.ten_bit_off.empty()) return "10 bits are off for this driver and build: " + facts.ten_bit_off;
    if (ten && !facts.deepest.ten_bit()) return "no 10 bits here: " + facts.deepest.reason;
    // The stand-in draws into the window: with none attached (gone with an app switch or the screen off, Android 10 to
    // 13's SurfaceView following its visibility) there is nothing to draw in, and nothing changes; a window that comes
    // back draws the bench as it was (attach).
    if (mode == 1 && !window_) return "no surface is attached (the window went away)";
    const gpu::Depth depth = ten ? gpu::Depth::deepest : gpu::default_depth;
    mode_ = mode;
    config_ = config;
    pills_ = (flags & bench_flag::pills) != 0;
    texture_ = (flags & bench_flag::texture) != 0;
    // A renderer of the other depth, or any in mode 0, goes first, in its stage.
    if (mode_ == 0 || depth != depth_) drop_renderer();
    if (depth != depth_) {
        // The other depth's output goes in its own stage (the surface stage's at 8 bits, the colour stage's at 10), and
        // the colour stage closes once nothing of 10 bits is left (out of it: the 10-bit frame and output went above,
        // inside it, and its cache goes). The output is made again below at the depth asked for: into the colour
        // stage, it opens and the device is asked about 10 bits inside it; one that cannot draw the frame in them after
        // all, or a driver that refuses the 10-bit swapchain, closes it again, nothing of 10 bits made, and the frame
        // stays at 8 (make_output). So the stage holds 10-bit work alone.
        drop_output();
        close_colour();
        depth_ = depth;
    }
    // The stand-in before any output of 10 bits: its shared objects are 8-bit work, outside the colour stage.
    if (mode_ == 1) {
        if (std::string why = make_standin(); !why.empty()) return why;
    }
    std::string unmade; // why no output could be made, if none could
    // The output at the depth asked for, or one that could not be made before (a remake, a rebuild after a loss).
    if (!output_ && window_) unmade = make_output();
    if (!unmade.empty()) log::error("vk swapchain: {}", unmade);
    if (mode_ == 0) return {};
    if (ten && !facts.deepest.ten_bit()) return "no 10 bits here: " + facts.deepest.reason;
    if (!output_) return output_error_.empty() ? "no swapchain" : "no swapchain: " + output_error_;
    if (ten && !output_->colour().ten_bit()) return "no 10 bits here: " + output_->colour().reason;
    if (!renderer_) return make_renderer();
    standin_->set_pills(pills_);
    if (renderer_->configuration() == config_) {
        // Drawn already (a host switch's attach made it for this configuration): its pipelines stand, made once.
        log_configuration();
        return {};
    }
    // A new frame: the frames stage ends with the old one (whose first 120 may not have passed: VK bench's short cells,
    // a slow phone), so that its marker never names the configuration drawn before; the new one's first draws open it
    // again under its own name.
    new_frame();
    auto done = behind(gpu::Stage::pipelines, [&] { return renderer_->reconfigure(config_); });
    if (!done) {
        drop_renderer();
        return done.error().text();
    }
    log_configuration();
    return {};
}

std::string Session::pipelines() { return time_pipelines(); }

std::string Session::time_pipelines() {
    if (!renderer_ || !output_ || !city_) return "vkbench pipelines: no frame is drawn (the bench is off)";
    if (colour_open()) return "vkbench pipelines: not timed in the colour stage (its pipelines are 10-bit ones)";
    save_cache();
    struct Made {
        double ms = 0;
        std::uint32_t count = 0;
        std::size_t bytes = 0;
    };
    // The stand-in's pipelines for the configuration drawn now, made again by a stand-in of their own, from a cache of
    // their own: empty (as a first launch makes them, though the driver may keep shaders this process compiled), or
    // loaded from the cache file as it is saved now (as the next launch makes them). The cache is made and goes in the
    // cache stage, the stand-in in the pipelines stage, each named as it goes.
    gpu::PipelineWatch* watch = started_.guard.get();
    auto make = [&](bool from_file) -> gpu::Result<Made> {
        std::optional<gpu::PipelineCache> cache;
        std::string refused;
        behind(gpu::Stage::cache, [&] {
            auto opened = gpu::PipelineCache::open(started_.device->handle(), started_.device->facts(),
                                                   from_file ? options_.cache_file : std::filesystem::path(),
                                                   options_.spirv, from_file);
            if (opened) {
                cache.emplace(std::move(*opened));
            } else {
                refused = opened.error().text();
            }
            return true;
        });
        if (!cache) return gpu::fail(refused);
        const gpu::PipelineCache& timing = *cache;
        auto made = behind(gpu::Stage::pipelines, [&]() -> gpu::Result<Made> {
            auto standin = StandIn::create(*started_.device, timing, city_, watch);
            if (!standin) return std::unexpected(std::move(standin.error()));
            const gpu::Result<void> prepared = (*standin)->prepare(renderer_->pass(), *output_);
            const Made m{.ms = (*standin)->pipelines_ms(),
                         .count = (*standin)->pipeline_count(),
                         .bytes = timing.loaded_bytes()};
            {
                const gpu::Announced named =
                    gpu::announce_release({.kind = "standin", .detail = "(the pipelines' timing's)"}, watch);
                standin->reset();
            }
            if (!prepared) return std::unexpected(prepared.error());
            return m;
        });
        behind(gpu::Stage::cache, [&] {
            const gpu::Announced named = gpu::announce_release(
                {.kind = "cache",
                 .detail = from_file ? "(the pipelines' timing's, from the file)" : "(the pipelines' timing's, empty)"},
                watch);
            cache.reset();
            return true;
        });
        return made;
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

void Session::log_configuration() const {
    const gpu::Facts& f = started_.device->facts();
    const gpu::Enabled& e = started_.device->enabled();
    log::info("vk config {} route={} frame={} host={} target={} pills={} colour={}", gpu::name_of(config_.config),
              gpu::name_of(config_.route), gpu::name_of(config_.form), texture_ ? "texture" : "surface",
              gpu::name_of(config_.target), pills_ ? "on" : "off",
              output_ ? gpu::colour_name(output_->format()) : std::string("none"));
    log::info("vk path samples={} lazy={} depth={} sync2={} timing={} shaderfma={} route={} cache={} pipelines "
              "{:.1f} ms ({} made) hint={}",
              static_cast<int>(config_.samples()), config_.lazy() ? "yes" : "no",
              f.depth_stencil == vk::Format::eD24UnormS8Uint ? "d24s8" : "d32s8", e.synchronization2 ? "yes" : "no",
              e.display_timing ? "yes" : "no", e.shader_fma ? "yes" : "no",
              standin_ && standin_->composite_glsl() ? "glsl" : "direct",
              started_.cache && started_.cache->hit() ? "hit" : "miss", standin_ ? standin_->pipelines_ms() : 0.0,
              standin_ ? standin_->pipeline_count() : 0U, hint_);
}

// ---- Frames.

bool Session::drawing() const noexcept {
    return !closed_ && renderer_ != nullptr && standin_ != nullptr && output_ != nullptr && lost_ < 3;
}

Drawn Session::draw(const style::CameraState& camera, const FrameTimes& times) {
    Drawn d;
    if (!drawing()) return d;
    const std::int64_t start = now_ns();
    if (epoch_ns_ == 0) epoch_ns_ = start;
    if (presented_ < guarded_frames && !guarding_) {
        // The frame drawn, named in the frames stage's marker as a release names it: a crash in its first draws says
        // which configuration and colour it was.
        started_.guard->set_drawing(std::format("frame (configuration {} {})", gpu::name_of(renderer_->configuration()),
                                                gpu::colour_name(output_->format())));
        started_.guard->enter(gpu::Stage::frames);
        guarding_ = true;
    }
    standin_->set_camera(camera);
    standin_->set_time(static_cast<double>(start - epoch_ns_) / 1e9);
    auto report = renderer_->draw(static_cast<std::uint64_t>(times.present_ns > 0 ? times.present_ns : 0));
    if (!report || report->status == gpu::Acquired::lost) {
        if (!report && report.error().code != vk::Result::eErrorDeviceLost) {
            log::error("a frame failed: {}", report.error().text());
            return d;
        }
        device_lost();
        d.rebuilt = true;
        return d;
    }
    const std::int64_t end = now_ns();
    FrameRecord& r = d.record;
    r.frame = report->number;
    r.vsync_ns = times.vsync_ns;
    r.start_ns = start;
    r.end_ns = end;
    r.desired_ns = times.present_ns;
    r.gpu_frame = report->gpu.frame;
    r.gpu_ms = static_cast<float>(report->gpu.total_ns / 1e6);
    r.gpu_main_ms = static_cast<float>(report->gpu.main_ns / 1e6);
    r.gpu_offscreen_ms = static_cast<float>(report->gpu.offscreen_ns / 1e6);
    r.gpu_after_ms = static_cast<float>(report->gpu.after_ns / 1e6);
    const Counters& c = standin_->counters();
    r.record_ms = static_cast<float>(c.record_ms - c.ranks_ms);
    r.prepare_ms = static_cast<float>(c.ranks_ms);
    r.draws = c.draws;
    r.triangles_k = static_cast<std::uint32_t>(c.triangles / 1000);
    r.fragments_k = static_cast<std::uint32_t>(report->gpu.fragments / 1000);
    r.flags = (report->gpu.valid ? frame_flag::gpu : 0U) | (guarding_ ? frame_flag::guarded : 0U) |
              config_bits(config_, texture_, pills_, output_->colour().ten_bit());
    d.stale = report->status == gpu::Acquired::out_of_date || report->status == gpu::Acquired::suboptimal;
    if (report->status == gpu::Acquired::out_of_date || report->status == gpu::Acquired::timeout)
        r.flags |= frame_flag::skipped;
    push_record(r);
    if (guarding_ && ++presented_ >= guarded_frames) {
        // The renderer's first 120 frames passed: the frames stage's marker goes, and the frame loop's per-frame calls
        // run unmarked from here (D5.10) until a frame is made again (a configuration, a size, a device); everything
        // else stays behind its stage. The session's first 120 write the pipeline cache back.
        started_.guard->leave();
        guarding_ = false;
        if (!first_frames_saved_) {
            first_frames_saved_ = true;
            save_cache();
        }
    }
    if (options_.ci_log) {
        log::info("vk frame {} {:.2f} gpu {:.2f}", r.frame, static_cast<double>(end - start) / 1e6,
                  static_cast<double>(r.gpu_ms));
    }
    d.drawn = true;
    return d;
}

void Session::rest() {
    if (!guarding_ || closed_) return;
    started_.guard->leave();
    guarding_ = false;
}

void Session::push_record(const FrameRecord& r) {
    // The display's own presentation times (VK_GOOGLE_display_timing) for earlier frames still in the ring.
    timings_.clear();
    if (output_) output_->past_timings(timings_);
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

std::size_t Session::records(FrameRecord* out, std::size_t capacity) {
    const std::scoped_lock lock(records_mutex_);
    std::size_t n = 0;
    while (read_ != published_ && n < capacity) {
        out[n++] = ring_[read_ % ring_.size()];
        ++read_;
    }
    return n;
}

std::string Session::stats() const {
    FrameRecord r{};
    {
        const std::scoped_lock lock(records_mutex_);
        if (count_ == 0) return "vk no frames";
        r = ring_[(write_ + ring_.size() - 1) % ring_.size()];
    }
    // The allocator's own books: no driver call.
    const double graphics_mb = started_.device ? static_cast<double>(started_.device->memory().allocated()) / 1e6 : 0;
    return std::format(
        "vk frame {} cpu {:.2f} ms gpu {:.2f} ms record {:.2f} ms draws {} triangles {}k graphics {:.1f} MB", r.frame,
        static_cast<double>(r.end_ns - r.start_ns) / 1e6, static_cast<double>(r.gpu_ms),
        static_cast<double>(r.record_ms), r.draws, r.triangles_k, graphics_mb);
}

// ---- Device loss.

void Session::turn_off_session(std::string why) {
    log::error("vk {}", why);
    lost_ = 3;
    session_off_ = std::move(why);
}

void Session::device_lost() {
    if (!started_.device || closed_) return;
    ++lost_;
    // What was made on the lost device goes, each in its stage: the frame and the output (the colour stage's while it
    // is open, which then closes, nothing of 10 bits being left).
    drop_renderer();
    drop_output();
    close_colour();
    if (lost_ >= 3) {
        turn_off_session("device lost ×3: the map is off for this session");
        return;
    }
    log::error("device lost ({} this session): rebuilding the device", lost_);
    // Then the stand-in, the pipeline cache and the lost device, behind the device stage; the profiles read of it are
    // the physical device's, which stays.
    const std::vector<std::pair<std::string, bool>> profiles = started_.device->facts().profiles;
    const std::string profiles_status = started_.device->facts().profiles_status;
    behind(gpu::Stage::device, [&] {
        gpu::PipelineWatch* watch = started_.guard.get();
        started_.device->wait_idle(); // a lost device has nothing left to wait for
        if (standin_) {
            const gpu::Announced named =
                gpu::announce_release({.kind = "standin", .detail = "(the lost device's)"}, watch);
            standin_.reset();
        }
        if (started_.cache) {
            const gpu::Announced named =
                gpu::announce_release({.kind = "cache", .detail = "(the one on disk, the lost device's)"}, watch);
            started_.cache.reset();
        }
        const gpu::Announced named = gpu::announce_release({.kind = "device", .detail = "(lost)"}, watch);
        started_.device.reset();
        return true;
    });
    // Made again as the start opened it (K6's answer for configuration B among the options), to present to this
    // surface, behind the stages a start passes: the device's facts (its choice's queries, keyed to the system build as
    // a start's are, though the driver is known by now: gpu/Guard.hpp), the device, the cache.
    gpu::DeviceOptions reopen = started_.device_options;
    reopen.surface = *surface_;
    auto picked = behind(gpu::Stage::facts, [&] { return gpu::Device::pick(*started_.instance, reopen); });
    if (!picked) {
        turn_off_session("the device could not be made again after its loss: " + picked.error().text());
        return;
    }
    auto device =
        behind(gpu::Stage::device, [&] { return gpu::Device::open(*started_.instance, std::move(*picked), reopen); });
    if (!device) {
        turn_off_session("the device could not be made again after its loss: " + device.error().text());
        return;
    }
    started_.device = std::move(*device);
    gpu::Facts& facts = started_.device->facts();
    facts.profiles = profiles;
    facts.profiles_status = profiles_status;
    // The guard's verdicts for this driver and build, as the start put them on the first device's facts.
    facts.ten_bit_off = started_.guard->ten_bit_off();
    facts.profiles_off = started_.guard->profiles_off();
    gpu::decide_colour(facts);
    auto cache = behind(gpu::Stage::cache, [&] {
        return gpu::PipelineCache::open(started_.device->handle(), facts, options_.cache_file, options_.spirv,
                                        !options_.cache_file.empty());
    });
    if (!cache) {
        turn_off_session("the pipeline cache could not be opened again after the device's loss: " +
                         cache.error().text());
        return;
    }
    started_.cache = std::make_unique<gpu::PipelineCache>(std::move(*cache));
    if (*surface_) {
        // The new device's facts, with the surface's and the frame's colour on it, as the attach read them.
        behind(gpu::Stage::surface, [&] {
            gpu::collect_surface(facts, started_.device->physical(), *surface_, started_.device->queue_family());
            return true;
        });
    }
    // The stand-in first, at 8 bits in the pipelines stage; then the output (in the colour stage, opened again, at
    // VK bench's 10 bits) and the renderer.
    if (mode_ == 1) {
        if (const std::string why = make_standin(); !why.empty()) log::error("vk rebuild: {}", why);
    }
    if (window_) {
        if (const std::string why = make_output(); !why.empty()) {
            turn_off_session("the swapchain could not be made again after the device's loss: " + why);
            return;
        }
    }
    if (mode_ == 1) {
        if (const std::string why = make_renderer(); !why.empty()) log::error("vk rebuild: {}", why);
    }
}

// ---- Facts, the cache, the close.

void Session::read_profiles() {
    if (profiles_read_ || closed_ || !started_.device || !started_.instance) return;
    gpu::Facts& facts = started_.device->facts();
    if (!facts.profiles_off.empty()) {
        profiles_read_ = true; // off after a crash in them: the facts line says so
        return;
    }
    if (colour_open()) {
        // Never inside VK bench's colour stage, whose marker a crash here would take: read once it closes.
        facts.profiles_status = "not read yet (VK bench's colour stage is open; they are read once it closes)";
        return;
    }
    behind(gpu::Stage::profiles, [&] {
        gpu::read_profiles(facts, *started_.instance, started_.device->physical());
        return true;
    });
    profiles_read_ = true;
    // The facts kept for the honest state, now with the profiles.
    std::string lines;
    for (const std::string& l : gpu::facts_lines(facts)) lines += l + "\n";
    started_.guard->keep_facts(lines);
}

std::string Session::facts() {
    read_profiles();
    std::string out = build_lines();
    if (!started_.device) {
        // The device was lost and could not be made again (device_lost), or the core is closed: why, and the facts
        // this start kept.
        out += std::format("vulkan: off for this session ({})\n", closed_ ? "the core is closed" : session_off_);
        out += guard_lines(started_);
        out += "kept: the facts below were read before the device was lost\n";
        return out + started_.guard->kept_facts();
    }
    for (const std::string& l : gpu::facts_lines(started_.device->facts())) out += l + "\n";
    out += std::format("pipeline.cache: {}\n", started_.cache ? started_.cache->status() : std::string("none"));
    out += guard_lines(started_);
    if (!session_off_.empty()) out += std::format("vulkan.session: off ({})\n", session_off_);
    return out;
}

void Session::save_cache() {
    // Only when pipelines were made through it since its last write: a trim (each level from TRIM_MEMORY_UI_HIDDEN on
    // arrives as the system makes ready to kill cached processes), the close and the session's first 120 frames
    // mostly find nothing new, and then write nothing and leave no marker. Never while VK bench's colour stage is open:
    // nothing of it goes into this cache (its pipelines go through the stage's own), and a crash in the write is the
    // cache stage's to own.
    if (!started_.cache || !started_.cache->changed() || colour_open() || closed_) return;
    behind(gpu::Stage::cache, [&] {
        auto saved = started_.cache->save();
        if (!saved) log::warn("vk pipeline cache not saved: {}", saved.error().text());
        return true;
    });
}

void Session::close() {
    if (closed_) return;
    // The frames stage ends with the session: the stages below take its marker, and the close's goes last.
    guarding_ = false;
    release_window();
    save_cache(); // the cache's last write: a reset may forget it from now on
    closed_ = true;
    gpu::Guard& guard = *started_.guard;
    guard.enter(gpu::Stage::close);
    if (started_.device) started_.device->wait_idle();
    if (standin_) {
        const gpu::Announced named = gpu::announce_release({.kind = "standin"}, &guard);
        standin_.reset();
    }
    city_.reset();
    if (started_.cache) {
        const gpu::Announced named = gpu::announce_release({.kind = "cache", .detail = "(the one on disk)"}, &guard);
        started_.cache.reset();
    }
    if (started_.device) {
        const gpu::Announced named = gpu::announce_release({.kind = "device", .detail = "(and its allocator)"}, &guard);
        started_.device.reset();
    }
    if (started_.instance) {
        const gpu::Announced named = gpu::announce_release({.kind = "instance"}, &guard);
        started_.instance.reset();
    }
    guard.leave();
}

} // namespace atlas::standin
