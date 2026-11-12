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
#include "frame/Cadence.hpp"
#include "frame/CompositeCheck.hpp"
#include "frame/Due.hpp"
#include "frame/MapFrame.hpp"
#include "frame/Motion.hpp"
#include "frame/Pacing.hpp"
#include "frame/TwinCheck.hpp"
#include "fx/Fields.hpp"
#include "fx/GpuFields.hpp"
#include "gen/spirv/modules.hpp"
#include "geometry/Ground.hpp"
#include "gpu/Facts.hpp"
#include "gpu/Guard.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Swapchain.hpp"
#include "labels/Names.hpp"
#include "labels/Sprites.hpp"
#include "load/PackStyle.hpp"
#include "load/Window.hpp"
#include "markers/Markers.hpp"
#include "osm/Diff.hpp"
#include "osm/Edits.hpp"
#include "passes/BlurCheck.hpp"
#include "routes/Routes.hpp"
#include "scene/WorldNames.hpp"
#include "standin/Session.hpp"
#include "style/CameraState.hpp"
#include "style/Palette.hpp"
#include "style/Theme.hpp"
#include "sun/Buckets.hpp"
#include "text/Fonts.hpp"
#include "text/Shaper.hpp"
#include "tiles/ExpandedCache.hpp"
#include "tiles/Kept.hpp"
#include "tiles/Source.hpp"
#include "tiles/WorldIndex.hpp"

#if defined(__ANDROID__)
#include "platform/android/Android.hpp"
#endif

#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <format>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
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
    bool set_frame_rate(float fps, bool fixed_source) override { return window_.set_frame_rate(fps, fixed_source); }

private:
    platform::Window window_;
};

/// The threads the map's frame runs on as the ground last told them (frame/RenderThreads.hpp), for the render thread's
/// hint session to take in once the frame is out (render thread only).
class TookThreads final : public frame::RenderThreads {
public:
    void took(std::span<const std::int32_t> tids) override {
        tids_.assign(tids.begin(), tids.end());
        fresh_ = true;
    }
    /// The threads told since the last call, or none.
    [[nodiscard]] std::optional<std::span<const std::int32_t>> take() noexcept {
        if (!fresh_) return std::nullopt;
        fresh_ = false;
        return std::span<const std::int32_t>(tids_);
    }

private:
    std::vector<std::int32_t> tids_;
    bool fresh_ = false;
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
        core->compression_switch_ =
            core->started_.device != nullptr && core->started_.device->enabled().swapchain_compression_control;
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
        // The app's map, its tiles built on 2 or 3 workers by core count (T8), at normal priority with a hint session
        // of their own while a tile in view is missing (gain 8); a tile published wakes the frame loop; the noise
        // fields kept under the app's cache.
        core->map_ = std::make_unique<frame::MapFrame>(
            map_workers(), [self] { self->wake(); },
            frame::MapOptions{.cache_dir = options.cache_dir,
                              .worker_hints = [] { return std::make_shared<platform::WorkerSession>(); },
                              .render_threads = core->took_threads_});
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

    /// The world's cache of remote packs' heads and chunks (ENGINE.md step 6) for the map from now on.
    void world_cache(std::shared_ptr<tiles::ExpandedCache> cache) {
        {
            const std::scoped_lock lock(world_cache_mutex_);
            world_cache_ = cache;
        }
        on_session([this, &cache] {
            map_->set_world_cache(cache);
            ask_frame();
        });
    }

    /// The world's tiles the map wants whose chunks are not on the phone, nearest first (scene::WorldTiles::wants).
    [[nodiscard]] std::vector<scene::WorldWant> world_wants() {
        return on_session([this] { return map_->world_wants(); });
    }

    /// The world's blocks the map's cover reaches without an open pack, nearest first (scene::WorldTiles::blocks).
    [[nodiscard]] std::vector<tiles::TileKey> world_blocks() {
        return on_session([this] { return map_->world_blocks(); });
    }

    /// A range of the world pack `id`'s bytes the app fetched, its whole chunks checked and kept in the world's cache
    /// (tiles::Store::keep) on the caller's thread; the next frame builds the tiles kept. Nothing where the map's world
    /// holds no such pack of that identity (another version's) or has no cache.
    [[nodiscard]] std::optional<tiles::KeptChunks>
    world_keep(const std::string& id, const tiles::Sha256Digest& identity, const tiles::PackBytes& range) {
        std::shared_ptr<tiles::ExpandedCache> cache;
        {
            const std::scoped_lock lock(world_cache_mutex_);
            cache = world_cache_;
        }
        const std::shared_ptr<const tiles::StorePack> pack = on_session([this, &id] { return map_->world_pack(id); });
        if (cache == nullptr || pack == nullptr || !pack->remote || pack->identity != identity) return std::nullopt;
        thread_local tiles::Zstd zstd;
        tiles::KeptChunks kept = tiles::Store::keep(*cache, *pack, range, zstd);
        if (!kept.kept.empty()) on_session([this] { ask_frame(); });
        return kept;
    }

    /// The windows the map's world wants built, nearest first (scene::World::wanted_windows).
    [[nodiscard]] std::vector<tiles::TileKey> wanted_windows() {
        return on_session([this] { return map_->wanted_windows(); });
    }

    /// The places in view the map's world draws from windows, nearest first (scene::World::windows_in_view).
    [[nodiscard]] std::vector<tiles::TileKey> windows_in_view() {
        return on_session([this] { return map_->windows_in_view(); });
    }

    /// The frame cells' levels' tiles the map's world wants, in its order (scene::World::wanted_levels).
    [[nodiscard]] std::vector<tiles::TileKey> wanted_levels() {
        return on_session([this] { return map_->wanted_levels(); });
    }

    /// The frame cells' levels' tiles the map's world draws or keeps in view (scene::World::levels_in_view).
    [[nodiscard]] std::vector<tiles::TileKey> levels_in_view() {
        return on_session([this] { return map_->levels_in_view(); });
    }

    /// The moment's theme for the map from now on, drawn with the next frame. At rest it asks for none of its own, as
    /// before: the light moves on in steps of five minutes (MapController.MOMENT_STEP_S), and each step's frames would
    /// come while the map lies still, at any time of the five minutes (on CI's emulator too, whose clock is the
    /// wall's).
    void moment(const style::ThemeBlock& theme) {
        on_session([this, &theme] {
            map_->set_theme(theme);
            ask_camera();
        });
    }

    /// The user's palette for the map from now on (the Map look), drawn at once, at rest too.
    void palette(const style::PaletteBlock& palette) {
        on_session([this, &palette] {
            map_->set_palette(palette);
            ask_frame();
        });
    }

    /// Where the fling under way lands (ENGINE.md gain 26), or none: handed to the map's world without waiting for the
    /// render thread, since a fling begins on the main thread.
    void landing(std::optional<scene::World::Landing> l) {
#if defined(__ANDROID__)
        loop_->post([this, l] {
            if (closing_.load()) return;
            map_->set_landing(l);
        });
#else
        const std::scoped_lock lock(session_mutex_);
        map_->set_landing(l);
#endif
    }

    /// The screen's haze, vignette and paper grain for the map from now on, on or off.
    void screen(bool haze, bool vignette, bool grain) {
        on_session([this, haze, vignette, grain] {
            map_->set_screen(haze, vignette, grain);
            ask_frame();
        });
    }

    /// The kept walls' and near cells' shape records for the map from now on, on (the default) or off (VK bench's
    /// comparison of the two): "" where its buildings draw from them, else why they cannot or not yet.
    [[nodiscard]] std::string shape_records(bool on) {
        return on_session([this, on] { return map_->set_shape_records(on); });
    }

    /// The app's labels for the map from now on (null: none).
    void labels(std::shared_ptr<const labels::LabelSet> set) {
        on_session([this, &set] {
            map_->set_labels(std::move(set));
            ask_frame();
        });
    }

    /// The names the map's frames show without a sprite, by rank (labels::NameStore::ask: each asked for once in two
    /// seconds, on the bridge's own clock, which runs while the map rests).
    [[nodiscard]] std::vector<labels::NameWant> names_wanted() {
        const double now = std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
        return on_session([this, now] { return map_->names().ask(now); });
    }

    /// The names' sprites the app hands back (labels::NameStore::give); "" or why they were refused.
    [[nodiscard]] std::string names(std::vector<labels::LabelSprite> sprites, float large_scale, float floor_dp) {
        return on_session([this, &sprites, large_scale, floor_dp] {
            const auto kept = map_->names().give(std::move(sprites), large_scale, floor_dp, map_->seconds());
            if (!kept) return std::string(labels::describe(kept.error()));
            ask_frame();
            return std::string();
        });
    }

    /// Names no tile holds, picked as a tile's are from now on (a test's).
    void test_names(std::vector<scene::WorldName> names) {
        on_session([this, &names] {
            map_->set_extra_names(std::move(names));
            ask_frame();
        });
    }

    /// The app's markers for the map from now on (null: none).
    void markers(std::shared_ptr<const markers::MarkerSet> set) {
        on_session([this, &set] {
            map_->set_markers(std::move(set));
            ask_frame();
        });
    }

    /// The app's routes and areas for the map from now on (null: none).
    void routes(std::shared_ptr<const routes::RouteSet> set) {
        on_session([this, &set] {
            map_->set_routes(std::move(set));
            ask_frame();
        });
    }

    /// The building the app asks about from now on (none: no question open).
    void ask(std::optional<frame::AskedBuilding> asked) {
        on_session([this, asked] {
            map_->set_asked(asked);
            ask_frame();
        });
    }

    /// The building under the view's device point (x, y) as the map's last frame drew it, or none (no map).
    [[nodiscard]] std::optional<scene::Picked> pick(double x, double y) {
        return on_session([this, x, y] { return map_->pick(x, y); });
    }

    /// What the view's device point (x, y) shows of the ground as the map's last frame drew it, or none (no map).
    [[nodiscard]] std::optional<scene::PickedGround> pick_ground(double x, double y) {
        return on_session([this, x, y] { return map_->pick_ground(x, y); });
    }

    /// VK facts' twin check over the bundled Westerkerk (frame/TwinCheck.hpp) on the session's device, a world and a
    /// frame builder of its own that draw nothing: `twin <hash> ok`, `… FAIL expected <hash>`, or why it could not run.
    [[nodiscard]] std::string twin_line(const tiles::PackRef& westerkerk) {
        return on_session([this, &westerkerk] {
            if (!started_.device || !started_.cache) return std::string("twin unavailable: the session has no device");
            return frame::twin_line(*started_.device, *started_.cache, westerkerk);
        });
    }

    /// VK facts' fields check on the session's device (fx/GpuFields.hpp), behind the guard's noise stage: the screen
    /// fields at S1 made by the CPU on four threads and, where the GPU's noise is on, by the GPU after its byte check,
    /// each timed and held to fields.inc's hashes: `fields 1080x2400 cpu <ms> gpu <ms> hash <h> ok`, `… gpu off (<why>)
    /// …`, or `… FAIL …`.
    [[nodiscard]] std::string fields_line() {
        return on_session([this] {
            return session_->noise([](const gpu::Started& s, std::string_view off) {
                const fx::FieldSize s1 = fx::size_of(1080, 2400, 1080.0 / 412.0);
                if (!off.empty()) return fx::fields_line(s1, 4, fx::GpuPart{.off = std::string(off)});
                return fx::fields_line(s1, 4, *s.device, *s.cache, s.guard.get(), off);
            });
        });
    }

    /// VK facts' composite check (frame/CompositeCheck.hpp) on the session's device, behind the guard's checks stage:
    /// `composite.check: …`.
    [[nodiscard]] std::string composite_line() {
        return on_session([this] {
            return session_->check("composite.check",
                                   [](const gpu::Started& s) { return frame::composite_line(*s.device, *s.cache); });
        });
    }

    /// VK facts' blur check (passes/BlurCheck.hpp) on the session's device, behind the guard's checks stage: the soft
    /// shadows' blur of a known pattern against the CPU's, block by block.
    [[nodiscard]] std::string blur_line() {
        return on_session([this] {
            return session_->check("shadow.blur",
                                   [](const gpu::Started& s) { return passes::blur_line(*s.device, *s.cache); });
        });
    }

    /// Copies the frame records not read yet into `out` (at most `capacity`); returns how many. Any thread.
    std::size_t records(FrameRecord* out, std::size_t capacity) { return session_->records(out, capacity); }

    /// The frame loop's health (Session::health): any thread, never waiting for the render thread, so the panel still
    /// answers while a frame is stuck.
    [[nodiscard]] std::string health() const { return session_->health(); }

    /// A debug build's switch: each frame presented at a timeline its recent work can make (gain 15's pacer, gone
    /// since alpha 16) instead of as soon as it is ready. Any thread; the next frame takes it.
    void set_pacing(bool on) noexcept { pacing_.store(on, std::memory_order_relaxed); }

    /// The Map look's switch of the screen's compression: on, the swapchain's images with the driver's own framebuffer
    /// compression (its default); off, without it where the driver lets an app ask (gpu::Compression). The swapchain
    /// is made again with it on the render thread, and drawn.
    void compression(bool on) {
        on_session([this, on] {
            session_->set_compression(on ? gpu::Compression::driver : gpu::Compression::off);
            ask_frame();
        });
    }

    /// Whether the driver lets an app make the swapchain's images without its compression: the switch's off acts only
    /// there. Any thread: read as the core starts (a device made again after a loss is the same driver's).
    [[nodiscard]] bool compression_switch() const noexcept { return compression_switch_; }

    /// The Map look's Fast refresh: on (until it is set), the window's frames are said to come at 120 a second while
    /// the map moves and at 60 at rest, with fixed-source compatibility; off, at 60 with default compatibility
    /// throughout, the vote of alphas up to 27 (Session::set_fast_refresh, ENGINE.md gain 11). Said again at once on
    /// the render thread; the swapchain is untouched, and no frame is asked for.
    void fast_refresh(bool on) {
        on_session([this, on] { session_->set_fast_refresh(on); });
    }

#if defined(__ANDROID__)
    bool attach(JNIEnv* env, jobject surface, int width, int height, jobject source, jobject camera, std::string& why) {
        if (camera == nullptr || env->GetDirectBufferAddress(camera) == nullptr ||
            std::cmp_less(env->GetDirectBufferCapacity(camera), style::camera_state_size)) {
            why = std::format("the camera buffer must be a direct ByteBuffer of {} bytes", style::camera_state_size);
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
        // Optional: told once when the core turns the map off for the session (FrameSource.off's default does nothing).
        jmethodID off_mid = env->GetMethodID(cls, "off", "(Ljava/lang/String;)V");
        if (off_mid == nullptr) env->ExceptionClear();
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
            due_.attached();
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
            off_mid_ = off_mid;
            off_told_ = false;
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
    std::mutex world_cache_mutex_;
    std::shared_ptr<tiles::ExpandedCache> world_cache_; // world_cache, under world_cache_mutex_
    std::atomic<bool> pacing_{false};                   // set_pacing
    bool compression_switch_ = false;                   // set as the core starts, read only after

#if defined(__ANDROID__)
    // ---- On the render thread.

    /// A frame is asked for (render thread): the next paced vsync asks Kotlin for the camera, and draws if it moved.
    void ask_camera() {
        requested_ = true;
        ask_vsync();
    }

    /// The map's picture changed at the same camera (render thread): what the app handed it (the labels, the markers,
    /// the routes, the building asked about, the screen's effects, the packs) or a tile of its world. The next paced
    /// vsync draws it, at rest too, at the camera Kotlin last wrote, where Kotlin's own answer is that nothing moved
    /// (frame/Due.hpp).
    void ask_frame() {
        due_.changed();
        ask_camera();
    }

    /// A tile of the map's world was published (on a worker, World::set_wake): the next vsync draws it, at rest too.
    void wake() {
        if (closing_.load()) return;
        loop_->post([this] {
            if (closing_.load()) return;
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
        pacer_.rest();
        session_->rest();
        // The window's frames come at 60 a second again once the map has been still for a second since it moved
        // (frame/Motion.hpp): the render thread's timer says so then, unless the map moves again first.
        if (motion_.rested(platform::now_ns())) {
            loop_->set_timer(motion_.drop_ns(), [this] {
                if (!closing_.load() && motion_.drop()) session_->set_moving(false);
            });
        }
        // The map at rest: its tiles, once a rest (Appendix C's `vk tiles` line).
        if (map_on_ && drew_since_rest_) log::info("{}", map_->tiles_line());
        drew_since_rest_ = false;
    }

    void on_vsync(const platform::Vsync& v) {
        if (!requested_ || source_ == nullptr) return;
        if (!session_->drawing()) {
            session_->note_not_drawing(); // the panel's health: a frame was due, with none to draw
            return;
        }
        // The display's period, from the vsync's frame timelines; at most 60 frames a second (D5.8,
        // frame/Cadence.hpp): every vsync at 60 Hz, every second one at 120 Hz, at 90 Hz every vsync where the phone is
        // cool and 60 a second by phase where it runs warm (one vsync and two in turn), every second one there from a
        // GPU figure of 16 ms or more until one under 14 ms.
        cadence_.at_vsync(v.frame_ns, std::span(v.presents.data(), v.timelines));
        const frame::Cadence::Step step =
            cadence_.step([this, &v] { return warmth_.warm(v.frame_ns, [this] { return thermal_.headroom(0); }); });
        if (!cadence_.due(v.frame_ns, step)) {
            ask_vsync();
            return;
        }
        // The camera for this vsync, from Kotlin (the gesture camera, written into the reused direct buffer).
        const jboolean more = [&] {
            const platform::TraceSection trace("atlas camera");
            return render_env_->CallBooleanMethod(source_, camera_mid_, static_cast<jlong>(v.frame_ns), camera_);
        }();
        if (render_env_->ExceptionCheck() == JNI_TRUE) {
            render_env_->ExceptionDescribe();
            render_env_->ExceptionClear();
            rest();
            return;
        }
        // The map's picture may change at the same camera: the app handed it something new, a tile landed (the world's
        // wake) or a fade or the frame's blend is under way, and the frame is drawn again at the camera Kotlin last
        // wrote.
        if (!due_.at_vsync(more == JNI_TRUE, map_on_, map_on_ && map_->animating())) {
            rest(); // nothing changed: no frame, and no more vsyncs until one is asked for again
            return;
        }
        const auto camera = style::read_camera_state(std::span(camera_bytes_, style::camera_state_size));
        if (!camera) {
            if (!camera_warned_) log::error("vk camera refused: {}", style::name_of(camera.error()));
            camera_warned_ = true;
            rest();
            return;
        }
        cadence_.drew(v.frame_ns, step);
        // ADPF's target follows the frames' interval: 11.1 ms at 90 Hz where every vsync draws (the phone cool, the GPU
        // light), 16.7 ms elsewhere (two periods at 120 Hz; at 90 Hz by the phase and behind a heavy GPU alike), told
        // only when it changes.
        hint_.target(cadence_.target_ns(step));
        // Shown as soon as it is ready: no presentation is asked for (0), so no frame waits for a later refresh than
        // the first it makes. Pacing to a timeline the frame's work could make (gain 15) put a refresh or more between
        // a touch and its frame where the phone is slow; the user (27 October): it "makes slow phones feel less
        // responsive", and more frames despite more stutter is better there. A debug build's switch paces again
        // (set_pacing): the zoom that stops at z 17 came with alpha 16, which took the pacing out.
        const std::int64_t cadence = cadence_.cadence_ns(step);
        draw(*camera, v, pacing_.load(std::memory_order_relaxed) ? present_of(v, cadence) : 0);
        // The map moves from its second new camera since the loop last rested: the window's frames are said to come at
        // 120 a second until it has rested a second (frame/Motion.hpp), so that the display stays at 120 Hz where it
        // has it, through a fling's tail and the camera's animations as under the finger, and pan, lift and pan again
        // does not switch it at each rest (ENGINE.md gain 11); a drop to 60 to come is off. Said once the frame is out,
        // so that no frame waits for it; one camera alone, or a tile or a fade at rest, moves nothing.
        if (motion_.drew(more == JNI_TRUE)) {
            loop_->cancel_timer();
            session_->set_moving(true);
        }
        ask_vsync();
    }

    /// The presentation a paced frame asks for at vsync `v`: the pacer's timeline where Choreographer offers them (API
    /// 33), else the preferred one's (0 where there is none: as soon as possible).
    [[nodiscard]] std::int64_t present_of(const platform::Vsync& v, std::int64_t cadence_ns) {
        if (v.timelines == 0) return v.present_ns;
        std::array<frame::Timeline, platform::max_timelines> t{};
        for (std::uint32_t i = 0; i < v.timelines; ++i)
            t.at(i) = {.present_ns = v.presents.at(i), .deadline_ns = v.deadlines.at(i)};
        const std::size_t pick = pacer_.choose(std::span(t.data(), v.timelines), v.preferred, platform::now_ns(),
                                               cadence_ns, cadence_.period_ns());
        return t.at(pick).present_ns;
    }

    void draw(const style::CameraState& camera, const platform::Vsync& v, std::int64_t present_ns) {
        const platform::TraceSection trace("atlas frame");
        const standin::Drawn d = session_->draw(camera, {.vsync_ns = v.frame_ns, .present_ns = present_ns});
        if (!off_told_ && off_mid_ != nullptr && !session_->off_reason().empty()) {
            // The map is off for this session (the device lost three times): the app shows its honest state.
            off_told_ = true;
            jstring why = render_env_->NewStringUTF(session_->off_reason().c_str());
            render_env_->CallVoidMethod(source_, off_mid_, why);
            if (render_env_->ExceptionCheck() == JNI_TRUE) render_env_->ExceptionClear();
            render_env_->DeleteLocalRef(why);
        }
        if (d.rebuilt) {
            requested_ = true; // the device was lost and rebuilt: draw again
            return;
        }
        if (!d.drawn) return;
        drew_since_rest_ = true;
        const FrameRecord& r = d.record;
        // ADPF hears the render thread's own work: the frame's time less its waits for the slot's previous frame (the
        // GPU) and the swapchain's image (the display), as Android's own render thread leaves out its dequeue. With
        // them in, a frame the GPU or the display held told ADPF the CPU missed its 16.7 ms, and its clocks rose for
        // nothing (the stutter review, 3 November). The GPU's time as before: an earlier frame's.
        hint_.report(r.start_ns, (r.end_ns - r.start_ns) - d.wait_ns,
                     static_cast<std::int64_t>(static_cast<double>(r.gpu_ms) * 1e6));
        // The ground's helpers, as its team is made (once the frame is out, so that no frame waits for the call): the
        // run the render thread waits for them in is in its own time above, so the boost that time asks for reaches
        // them too (API 34).
        if (const auto tids = took_threads_->take()) hint_.threads(*tids);
        // The pacer's work, for the debug switch's pacing: the frame's CPU time, its waits included (they hold its
        // image back as its work does), and its GPU time, where the GPU's clock measured it (an earlier frame's, read
        // back after its fence: the percentile does not mind). The cadence takes the same figure for its step at 90 Hz
        // (the GPU heavy from 16 ms until under 14).
        if ((r.flags & standin::frame_flag::gpu) != 0) {
            const auto gpu_ns = static_cast<std::int64_t>(static_cast<double>(r.gpu_ms) * 1e6);
            cadence_.gpu_took(gpu_ns);
            pacer_.add_work((r.end_ns - r.start_ns) + gpu_ns);
        }
        // Complete: the map's picture final for this camera (every tile resident and drawn, every fade done).
        const bool complete = !map_on_ || map_->complete();
        render_env_->CallVoidMethod(source_, presented_mid_, static_cast<jlong>(r.frame), static_cast<jlong>(r.end_ns),
                                    complete ? JNI_TRUE : JNI_FALSE);
        if (render_env_->ExceptionCheck() == JNI_TRUE) render_env_->ExceptionClear();
        if (d.stale) session_->remake();
        // A frame the swapchain did not show (out of date, as the first after the app comes back from the background
        // can be, or its image did not come) is drawn again at the next vsync, at the same camera and at rest too, up
        // to 8 in a row (the user, 1 November: "when you exit the app and come back its black until you move it").
        const bool skipped = (r.flags & standin::frame_flag::skipped) != 0;
        skipped_in_row_ = skipped ? skipped_in_row_ + 1 : 0;
        if (skipped && skipped_in_row_ <= 8) due_.changed();
    }

    // Declared after session_, so that the render thread (whose last task closed the session) goes before it.
    JavaVM* vm_ = nullptr;
    JNIEnv* render_env_ = nullptr;
    platform::HintSession hint_;
    std::shared_ptr<TookThreads> took_threads_ = std::make_shared<TookThreads>(); // the map's frame's threads
    platform::Thermal thermal_;
    frame::Pacer pacer_; // the presentation each frame asks for under the debug switch's pacing (set_pacing)
    jobject source_ = nullptr;
    jobject camera_ = nullptr;
    const std::byte* camera_bytes_ = nullptr;
    jmethodID camera_mid_ = nullptr;
    jmethodID presented_mid_ = nullptr;
    jmethodID off_mid_ = nullptr; // FrameSource.off, or null
    bool off_told_ = false;
    bool requested_ = false;
    bool camera_warned_ = false;
    frame::Due due_;                   // whether a vsync draws: a new camera, or the picture changed (ask_frame)
    int skipped_in_row_ = 0;           // frames the swapchain skipped since the last it showed
    bool drew_since_rest_ = false;     // a frame went out since the frame loop last rested
    std::atomic<bool> closing_{false}; // the core is closing: the map's wake posts nothing more
    frame::Cadence cadence_;           // the display's period, and which vsyncs may draw (at most 60 a second)
    frame::Warmth warmth_;             // whether the phone runs warm, its headroom read at most once a second
    frame::Motion motion_;             // whether the map moves, for the window's frame rate (120, or 60 at rest)
    std::unique_ptr<platform::RenderLoop> loop_;
#else
    /// Hosts have no frame loop: nothing to ask.
    void ask_camera() noexcept {}
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

jstring native_health(JNIEnv* env, jclass /*cls*/, jlong handle) {
    const Core* core = core_of(handle);
    return java(env, core == nullptr ? std::string("vk no core") : core->health());
}

void native_set_pacing(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jboolean on) {
    if (Core* core = core_of(handle); core != nullptr) core->set_pacing(on == JNI_TRUE);
}

void native_set_compression(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jboolean on) {
    if (Core* core = core_of(handle); core != nullptr) core->compression(on == JNI_TRUE);
}

void native_set_fast_refresh(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jboolean on) {
    if (Core* core = core_of(handle); core != nullptr) core->fast_refresh(on == JNI_TRUE);
}

jboolean native_compression_switch(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    const Core* core = core_of(handle);
    return core != nullptr && core->compression_switch() ? JNI_TRUE : JNI_FALSE;
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
/// duplicate of the descriptor, the caller closes its own), or a remote world pack's SHA-256 in `identities` (null for
/// every other pack; ENGINE.md step 6), its length its size: opened from its head in the world's cache, each chunk
/// loaded from there (tiles::Store::absent). A line per pack: opened, listed, kept, or why not.
jstring native_packs(JNIEnv* env, jclass /*cls*/, jlong handle, jobjectArray ids, jlongArray numbers, jintArray kinds,
                     jobjectArray paths, jintArray fds, jlongArray offsets, jlongArray lengths,
                     jobjectArray identities) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    if (ids == nullptr || numbers == nullptr || kinds == nullptr || paths == nullptr || fds == nullptr ||
        offsets == nullptr || lengths == nullptr || identities == nullptr) {
        return java(env, "packs: an array is missing");
    }
    const jsize n = env->GetArrayLength(ids);
    if (env->GetArrayLength(numbers) != n || env->GetArrayLength(kinds) != n || env->GetArrayLength(paths) != n ||
        env->GetArrayLength(fds) != n || env->GetArrayLength(offsets) != n || env->GetArrayLength(lengths) != n ||
        env->GetArrayLength(identities) != n) {
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
        if (auto* identity = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(identities, i));
            identity != nullptr) {
            const bool world = p.ref.kind == tiles::PackKind::world_mid || p.ref.kind == tiles::PackKind::world_base;
            const bool sized = env->GetArrayLength(identity) == static_cast<jsize>(p.ref.identity.size());
            if (sized) {
                env->GetByteArrayRegion(identity, 0, static_cast<jsize>(p.ref.identity.size()),
                                        reinterpret_cast<jbyte*>( // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
                                            p.ref.identity.data()));
            }
            env->DeleteLocalRef(identity);
            if (!world || !sized || !p.ref.path.empty() || fd[at] >= 0 || length[at] <= 0) {
                refused += std::format("{}: not a remote world pack the map can list\n", p.ref.id);
                continue;
            }
            p.ref.fetch = tiles::Store::absent();
        }
        // The map's own descriptor, closed when the pack goes (frame/MapFrame.hpp).
        if (fd[at] >= 0) p.ref.fd = ::fcntl(fd[at], F_DUPFD_CLOEXEC, 0);
        packs.push_back(std::move(p));
    }
    return java(env, refused + core->packs(std::move(packs)));
}

/// Tiles' keys for Kotlin, each packed as z << 48 | x << 24 | y.
jlongArray packed_keys(JNIEnv* env, const std::vector<tiles::TileKey>& keys) {
    std::vector<jlong> packed(keys.size());
    for (std::size_t i = 0; i < keys.size(); ++i) packed[i] = static_cast<jlong>(keys[i].packed());
    jlongArray out = env->NewLongArray(static_cast<jsize>(packed.size()));
    if (out != nullptr && !packed.empty()) {
        env->SetLongArrayRegion(out, 0, static_cast<jsize>(packed.size()), packed.data());
    }
    return out;
}

/// The windows the map wants built, nearest first, their keys packed.
jlongArray native_wanted_windows(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    return packed_keys(env, core != nullptr ? core->wanted_windows() : std::vector<tiles::TileKey>{});
}

/// The places in view the map draws from windows, nearest first, their keys packed.
jlongArray native_windows_in_view(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    return packed_keys(env, core != nullptr ? core->windows_in_view() : std::vector<tiles::TileKey>{});
}

/// The frame cells' levels' tiles the map wants, in its order, their keys packed.
jlongArray native_wanted_levels(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    return packed_keys(env, core != nullptr ? core->wanted_levels() : std::vector<tiles::TileKey>{});
}

/// The frame cells' levels' tiles the map draws or keeps in view, nearest first, their keys packed.
jlongArray native_levels_in_view(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    return packed_keys(env, core != nullptr ? core->levels_in_view() : std::vector<tiles::TileKey>{});
}

/// The world's cache in folder `dir` (ENGINE.md step 6: the world's own LRU of its remote packs' heads and chunks),
/// within `budget` bytes from now on: one a folder for the process, which every map and the app's checks share, opened
/// at its first use (its entries listed and checked, so off the main thread).
std::shared_ptr<tiles::ExpandedCache> world_cache_in(const std::string& dir, std::uint64_t budget, std::string& why) {
    static std::mutex mutex;
    static std::map<std::string, std::shared_ptr<tiles::ExpandedCache>> caches;
    const std::scoped_lock lock(mutex);
    if (const auto it = caches.find(dir); it != caches.end()) {
        if (it->second->budget() != budget) it->second->set_budget(budget);
        return it->second;
    }
    auto opened = tiles::ExpandedCache::open(dir, budget);
    if (!opened) {
        why = std::format("the world's cache in {}: {}", dir, tiles::describe(opened.error()));
        return nullptr;
    }
    std::shared_ptr<tiles::ExpandedCache> cache(std::move(*opened));
    caches.emplace(dir, cache);
    return cache;
}

/// The map's world keeps its remote packs' heads and chunks in the world's cache in `dir` within `budget` bytes
/// (world_cache_in); "" or why not.
jstring native_world_cache(JNIEnv* env, jclass /*cls*/, jlong handle, jstring dir, jlong budget) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    if (budget <= 0) return java(env, std::format("the world's cache: no budget ({})", budget));
    std::string why;
    auto cache = world_cache_in(string_of(env, dir), static_cast<std::uint64_t>(budget), why);
    if (cache == nullptr) return java(env, why);
    core->world_cache(std::move(cache));
    return java(env, "");
}

/// Whether the world's cache in `dir` holds each pack's head, `identities` their SHA-256s end to end (32 bytes each);
/// null where the cache does not open.
jbooleanArray native_world_held(JNIEnv* env, jclass /*cls*/, jstring dir, jlong budget, jbyteArray identities) {
    if (identities == nullptr || budget <= 0) return nullptr;
    std::string why;
    const auto cache = world_cache_in(string_of(env, dir), static_cast<std::uint64_t>(budget), why);
    if (cache == nullptr) {
        log::warn("vk world: {}", why);
        return nullptr;
    }
    const jsize bytes = env->GetArrayLength(identities);
    const jsize n = bytes / static_cast<jsize>(sizeof(tiles::Sha256Digest));
    std::vector<jbyte> all(static_cast<std::size_t>(bytes));
    env->GetByteArrayRegion(identities, 0, bytes, all.data());
    std::vector<jboolean> held(static_cast<std::size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        tiles::CacheKey key{.kind = tiles::CacheKind::pack_head};
        std::memcpy(key.source.data(), all.data() + (static_cast<std::size_t>(i) * key.source.size()),
                    key.source.size());
        held[static_cast<std::size_t>(i)] = cache->holds(key) ? JNI_TRUE : JNI_FALSE;
    }
    jbooleanArray out = env->NewBooleanArray(n);
    if (out != nullptr && n > 0) env->SetBooleanArrayRegion(out, 0, n, held.data());
    return out;
}

/// A world pack's head the app fetched into the world's cache in `dir` (tiles::Store::keep_head): the pack of `kind`
/// (tiles::PackKind) and `length` bytes whose SHA-256 is `identity`, its bytes but its chunks as ranges, each from its
/// offset in `offsets`. "" or why it was not kept.
jstring native_world_head(JNIEnv* env, jclass /*cls*/, jstring dir, jlong budget, jint kind, jlong length,
                          jbyteArray identity, jlongArray offsets, jobjectArray ranges) {
    if (identity == nullptr || offsets == nullptr || ranges == nullptr || budget <= 0 || length <= 0) {
        return java(env, "a world pack's head: an argument is missing");
    }
    if (kind != static_cast<jint>(tiles::PackKind::world_mid) &&
        kind != static_cast<jint>(tiles::PackKind::world_base)) {
        return java(env, std::format("a world pack's head: not a world pack's kind ({})", kind));
    }
    tiles::PackRef ref{.kind = static_cast<tiles::PackKind>(kind), .length = static_cast<std::uint64_t>(length)};
    if (env->GetArrayLength(identity) != static_cast<jsize>(ref.identity.size())) {
        return java(env, "a world pack's head: its identity is not a SHA-256");
    }
    env->GetByteArrayRegion(
        identity, 0, static_cast<jsize>(ref.identity.size()),
        reinterpret_cast<jbyte*>(ref.identity.data())); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
    const jsize n = env->GetArrayLength(offsets);
    if (env->GetArrayLength(ranges) != n) return java(env, "a world pack's head: the arrays differ in length");
    std::vector<jlong> offset(static_cast<std::size_t>(n));
    env->GetLongArrayRegion(offsets, 0, n, offset.data());
    std::vector<std::vector<std::byte>> bytes(static_cast<std::size_t>(n));
    std::vector<tiles::PackBytes> parts;
    for (jsize i = 0; i < n; ++i) {
        auto* array = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(ranges, i));
        if (array == nullptr || offset[static_cast<std::size_t>(i)] < 0)
            return java(env, "a world pack's head: a range is missing");
        auto& b = bytes[static_cast<std::size_t>(i)];
        b.resize(static_cast<std::size_t>(env->GetArrayLength(array)));
        env->GetByteArrayRegion(
            array, 0, static_cast<jsize>(b.size()),
            reinterpret_cast<jbyte*>(b.data())); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
        env->DeleteLocalRef(array);
        parts.push_back({.offset = static_cast<std::uint64_t>(offset[static_cast<std::size_t>(i)]), .bytes = b});
    }
    std::string why;
    const auto cache = world_cache_in(string_of(env, dir), static_cast<std::uint64_t>(budget), why);
    if (cache == nullptr) return java(env, why);
    // as the map's store opens the pack: a world pack in the core's world style (ENGINE.md step 5)
    const auto kept = tiles::Store::keep_head(*cache, ref, parts, load::world_style());
    return java(env, kept ? std::string() : std::format("a world pack's head: {}", tiles::describe(kept.error())));
}

/// Empties the world's cache in `dir` of every head and chunk (a new version of the world drops the old one's:
/// ENGINE.md step 6), its budget `budget` from now on; "" or why not.
jstring native_world_drop(JNIEnv* env, jclass /*cls*/, jstring dir, jlong budget) {
    if (budget <= 0) return java(env, std::format("the world's cache: no budget ({})", budget));
    std::string why;
    const auto cache = world_cache_in(string_of(env, dir), static_cast<std::uint64_t>(budget), why);
    if (cache == nullptr) return java(env, why);
    // the old world's keep with it: the app pins the new world's as it fetches it (native_world_kept)
    if (const auto unpinned = cache->set_pinned({}); !unpinned) {
        return java(env, std::format("the world's cache: {}", tiles::describe(unpinned.error())));
    }
    cache->evict_to(0);
    return java(env, "");
}

/// The world a kept country keeps (ENGINE.md step 11; tiles/Kept.hpp), pinned in the world's cache in `dir` in place of
/// what was pinned there: the packs of the mid-zoom base whose lengths are `lengths` and whose SHA-256s lie end to end
/// in `identities` (32 bytes each), pack i's tiles to keep the next `counts[i]` of `tiles` (packed keys). What the
/// cache lacks of them: the tiles no pack holds, the chunks held and their bytes, how many heads are lacking, those
/// packs' places, then four longs a chunk lacking: its pack's place, its tile (packed), its offset in the pack and its
/// length. Null where the cache does not open, the arrays do not agree, or the pins cannot be written.
jlongArray native_world_kept(JNIEnv* env, jclass /*cls*/, jstring dir, jlong budget, jlongArray lengths,
                             jbyteArray identities, jintArray counts, jlongArray tiles) {
    if (lengths == nullptr || identities == nullptr || counts == nullptr || tiles == nullptr || budget <= 0) {
        return nullptr;
    }
    const jsize n = env->GetArrayLength(lengths);
    const jsize all = env->GetArrayLength(tiles);
    if (env->GetArrayLength(counts) != n ||
        env->GetArrayLength(identities) != n * static_cast<jsize>(sizeof(tiles::Sha256Digest))) {
        return nullptr;
    }
    std::vector<jlong> length(static_cast<std::size_t>(n));
    std::vector<jint> count(static_cast<std::size_t>(n));
    std::vector<jbyte> identity(static_cast<std::size_t>(n) * sizeof(tiles::Sha256Digest));
    std::vector<jlong> tile(static_cast<std::size_t>(all));
    env->GetLongArrayRegion(lengths, 0, n, length.data());
    env->GetIntArrayRegion(counts, 0, n, count.data());
    env->GetByteArrayRegion(identities, 0, static_cast<jsize>(identity.size()), identity.data());
    env->GetLongArrayRegion(tiles, 0, all, tile.data());
    std::vector<tiles::KeptPack> packs(static_cast<std::size_t>(n));
    std::size_t at = 0;
    for (std::size_t i = 0; i < packs.size(); ++i) {
        if (length[i] <= 0 || count[i] < 0 || static_cast<std::size_t>(count[i]) > tile.size() - at) return nullptr;
        tiles::KeptPack& p = packs[i];
        p.length = static_cast<std::uint64_t>(length[i]);
        std::memcpy(p.identity.data(), identity.data() + (i * p.identity.size()), p.identity.size());
        p.tiles.reserve(static_cast<std::size_t>(count[i]));
        for (jint k = 0; k < count[i]; ++k) {
            p.tiles.push_back(tiles::TileKey::unpack(static_cast<std::uint64_t>(tile[at++])));
        }
    }
    std::string why;
    const auto cache = world_cache_in(string_of(env, dir), static_cast<std::uint64_t>(budget), why);
    if (cache == nullptr) {
        log::warn("vk world: {}", why);
        return nullptr;
    }
    const auto kept = tiles::keep_packs(*cache, packs, load::world_style());
    if (!kept) {
        log::warn("vk world: the keep's pins were not written: {}", tiles::describe(kept.error()));
        return nullptr;
    }
    std::vector<jlong> out{static_cast<jlong>(kept->unknown), static_cast<jlong>(kept->held),
                           static_cast<jlong>(kept->held_bytes), static_cast<jlong>(kept->heads.size())};
    for (const std::uint32_t head : kept->heads) out.push_back(static_cast<jlong>(head));
    for (const tiles::KeptChunk& c : kept->chunks) {
        out.push_back(static_cast<jlong>(c.pack));
        out.push_back(static_cast<jlong>(c.tile.packed()));
        out.push_back(static_cast<jlong>(c.offset));
        out.push_back(static_cast<jlong>(c.length));
    }
    jlongArray array = env->NewLongArray(static_cast<jsize>(out.size()));
    if (array != nullptr) env->SetLongArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

/// A range of the bytes of a kept world pack the app fetched, into the world's cache in `dir` (tiles::keep_range): the
/// pack of `length` bytes whose SHA-256 is `identity`, `bytes` from `offset`. How many chunks were kept, then the tiles
/// (packed) whose bytes were refused; null where the cache holds no head that opens the pack.
jlongArray native_world_kept_range(JNIEnv* env, jclass /*cls*/, jstring dir, jlong budget, jlong length,
                                   jbyteArray identity, jlong offset, jbyteArray bytes) {
    tiles::KeptPack pack;
    if (identity == nullptr || bytes == nullptr || budget <= 0 || length <= 0 || offset < 0 ||
        env->GetArrayLength(identity) != static_cast<jsize>(pack.identity.size())) {
        return nullptr;
    }
    pack.length = static_cast<std::uint64_t>(length);
    env->GetByteArrayRegion(
        identity, 0, static_cast<jsize>(pack.identity.size()),
        reinterpret_cast<jbyte*>(pack.identity.data())); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
    std::vector<std::byte> b(static_cast<std::size_t>(env->GetArrayLength(bytes)));
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(b.size()),
                            reinterpret_cast<jbyte*>(b.data())); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
    std::string why;
    const auto cache = world_cache_in(string_of(env, dir), static_cast<std::uint64_t>(budget), why);
    if (cache == nullptr) {
        log::warn("vk world: {}", why);
        return nullptr;
    }
    const auto kept = tiles::keep_range(*cache, pack, {.offset = static_cast<std::uint64_t>(offset), .bytes = b},
                                        load::world_style());
    if (!kept) return nullptr;
    std::vector<jlong> out{static_cast<jlong>(kept->kept.size())};
    for (const tiles::TileKey& t : kept->refused) out.push_back(static_cast<jlong>(t.packed()));
    jlongArray array = env->NewLongArray(static_cast<jsize>(out.size()));
    if (array != nullptr) env->SetLongArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

/// The world's tiles the map wants whose chunks are not on the phone, nearest first: eight longs each, its pack's block
/// and its tile (packed), the chunk's offset in the pack and its length, then the pack's SHA-256 as four big-endian
/// longs.
jlongArray native_world_wants(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    const std::vector<scene::WorldWant> wants = core != nullptr ? core->world_wants() : std::vector<scene::WorldWant>{};
    std::vector<jlong> out;
    out.reserve(wants.size() * 8);
    for (const scene::WorldWant& w : wants) {
        const std::optional<tiles::TileKey> block = scene::world_block_of(w.pack);
        if (!block) continue;
        out.push_back(static_cast<jlong>(block->packed()));
        out.push_back(static_cast<jlong>(w.tile.packed()));
        out.push_back(static_cast<jlong>(w.offset));
        out.push_back(static_cast<jlong>(w.length));
        for (std::size_t i = 0; i < w.identity.size(); i += 8) {
            std::uint64_t v = 0;
            for (std::size_t j = 0; j < 8; ++j) v = (v << 8U) | std::to_integer<std::uint64_t>(w.identity.at(i + j));
            out.push_back(static_cast<jlong>(v));
        }
    }
    jlongArray array = env->NewLongArray(static_cast<jsize>(out.size()));
    if (array != nullptr && !out.empty()) env->SetLongArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

/// The world's blocks the map's cover reaches without an open pack, nearest first, their keys packed.
jlongArray native_world_blocks(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    return packed_keys(env, core != nullptr ? core->world_blocks() : std::vector<tiles::TileKey>{});
}

/// A range of the bytes of the world pack of `block` (packed) and `identity` (its SHA-256) from `offset` the app
/// fetched: its whole chunks checked and kept in the map's world cache. The number of chunks kept, then the refused
/// ones' tiles (packed); null where the map's world holds no such pack or no cache.
jlongArray native_world_keep(JNIEnv* env, jclass /*cls*/, jlong handle, jlong block, jbyteArray identity, jlong offset,
                             jbyteArray bytes) {
    Core* core = core_of(handle);
    tiles::Sha256Digest sha{};
    if (core == nullptr || bytes == nullptr || identity == nullptr || offset < 0 ||
        env->GetArrayLength(identity) != static_cast<jsize>(sha.size())) {
        return nullptr;
    }
    env->GetByteArrayRegion(
        identity, 0, static_cast<jsize>(sha.size()),
        reinterpret_cast<jbyte*>(sha.data())); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
    const tiles::TileKey key = tiles::TileKey::unpack(static_cast<std::uint64_t>(block));
    const std::string id = tiles::world_pack_id({.z = key.z, .x = key.x, .y = key.y});
    std::vector<std::byte> b(static_cast<std::size_t>(env->GetArrayLength(bytes)));
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(b.size()),
                            reinterpret_cast<jbyte*>(b.data())); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
    const auto kept = core->world_keep(id, sha, {.offset = static_cast<std::uint64_t>(offset), .bytes = b});
    if (!kept) return nullptr;
    std::vector<jlong> out{static_cast<jlong>(kept->kept.size())};
    for (const tiles::TileKey& t : kept->refused) out.push_back(static_cast<jlong>(t.packed()));
    jlongArray array = env->NewLongArray(static_cast<jsize>(out.size()));
    if (array != nullptr) env->SetLongArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

/// The box whose data window (z, x, y) needs, [south, west, north, east] degrees (load::window_box).
jdoubleArray native_window_box(JNIEnv* env, jclass /*cls*/, jint z, jint x, jint y) {
    const std::array<double, 4> box = load::window_box(
        tiles::TileKey(static_cast<std::uint8_t>(z), static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y)));
    jdoubleArray out = env->NewDoubleArray(4);
    if (out != nullptr) env->SetDoubleArrayRegion(out, 0, 4, box.data());
    return out;
}

/// The version of a window built now (load::window_version): its stage P's and its style's.
jstring native_window_version(JNIEnv* env, jclass /*cls*/) { return java(env, load::window_version()); }

/// Writes `bytes` to `target` by a file beside it renamed once whole: "" or why not.
std::string write_whole(const std::string& target, std::span<const char> bytes) {
    const std::string part = target + ".part";
    {
        std::ofstream out(part, std::ios::binary | std::ios::trunc);
        out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
        if (!out.good()) return std::format("{} could not be written", part);
    }
    std::error_code e;
    std::filesystem::rename(part, target, e);
    if (e) return std::format("{} could not be renamed: {}", part, e.message());
    return {};
}

/// Builds window (z, x, y) from Overpass's JSON for its box, in the file at `json` (mapped: the data never passes
/// through the app's heap), and writes its pack to `path`, by a file beside it renamed once whole: "" or why not. On
/// the caller's thread, a background one (a few seconds, tens of MB).
jstring native_build_window(JNIEnv* env, jclass /*cls*/, jint z, jint x, jint y, jstring json, jstring path) {
    if (std::cmp_not_equal(z, load::window_level) || x < 0 || y < 0 || x >= (1 << 14) || y >= (1 << 14)) {
        return java(env, std::format("no window {}/{}/{}", z, x, y));
    }
    const std::string source = string_of(env, json);
    const auto data = tiles::MappedSource::open(source);
    if (!data)
        return java(env, std::format("the window's data {} does not open: {}", source, tiles::describe(data.error())));
    const tiles::TileKey key(static_cast<std::uint8_t>(z), static_cast<std::uint32_t>(x),
                             static_cast<std::uint32_t>(y));
    const auto built = load::build_window((*data)->view(0, (*data)->size()), key, load::place_of(key));
    if (!built) return java(env, built.error().what);
    const auto bytes = std::as_bytes(std::span(built->pack));
    const std::string why =
        write_whole(string_of(env, path), std::span(reinterpret_cast<const char*>(bytes.data()),
                                                    bytes.size())); // NOLINT: the pack's bytes as chars
    if (!why.empty()) return java(env, why);
    log::info("vk window {}/{}/{}: {} features, {} bytes; loaded in {:.0f} ms, cut in {:.0f} ms, written in {:.0f} ms",
              z, x, y, built->features, built->pack.size(), built->load_ms, built->cut_ms, built->write_ms);
    return java(env, "");
}

/// Window (z, x, y)'s kept data (Overpass's JSON of its query, as fetched or as merged before, in the file at `kept`,
/// mapped) brought up to the date of Overpass's diff of the same query since then (its XML, a direct buffer), written
/// to `out` by a file beside it renamed once whole: true; false where the diff changes nothing the data holds (no file
/// written). Where the diff or the data cannot be merged exactly, an IllegalStateException says why (the app then
/// fetches the window whole). On the caller's thread, a background one.
jboolean native_merge_window(JNIEnv* env, jclass /*cls*/, jint z, jint x, jint y, jstring kept, jobject diff,
                             jstring out) {
    const auto refuse = [env](const std::string& why) -> jboolean {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), why.c_str());
        return JNI_FALSE;
    };
    void* address = diff == nullptr ? nullptr : env->GetDirectBufferAddress(diff);
    const jlong capacity = diff == nullptr ? 0 : env->GetDirectBufferCapacity(diff);
    if (address == nullptr || capacity <= 0) return refuse("the diff must be a direct buffer");
    const std::string source = string_of(env, kept);
    const auto data = tiles::MappedSource::open(source);
    if (!data) return refuse(std::format("the kept data {} does not open: {}", source, tiles::describe(data.error())));
    const auto t0 = std::chrono::steady_clock::now();
    const auto read =
        osm::read_diff(std::span(static_cast<const std::byte*>(address), static_cast<std::size_t>(capacity)));
    if (!read) {
        return refuse(std::format("the diff is refused: {} at byte {}: {}", osm::name_of(read.error().fault),
                                  read.error().at, read.error().what));
    }
    const auto merged = osm::merge_diff((*data)->view(0, (*data)->size()), *read);
    if (!merged) {
        return refuse(std::format("the merge is refused: {} at byte {}: {}", osm::name_of(merged.error().fault),
                                  merged.error().at, merged.error().what));
    }
    log::info("vk window {}/{}/{}: a diff to {}, {} changes: {} kept, {} replaced, {} added, {} removed, {} absent; "
              "merged in {:.0f} ms",
              z, x, y, read->osm_base, read->changes.size(), merged->kept, merged->replaced, merged->added,
              merged->removed, merged->absent,
              std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    if (!merged->changed()) return JNI_FALSE;
    const std::string why = write_whole(string_of(env, out), merged->json);
    if (!why.empty()) return refuse(why);
    return JNI_TRUE;
}

/// Window (z, x, y)'s data (Overpass's JSON of its query, as kept, in the file at `kept`, mapped) with the user's own
/// edits waiting on the phone (osm/Edits.hpp: their JSON in a direct buffer), written to `out` by a file beside it
/// renamed once whole, for the window to be built from: true; false where no edit applies to its data (no file
/// written). Where the data or the edits do not read, an IllegalStateException says why (the app then builds the
/// window without them). On the caller's thread, a background one.
jboolean native_edit_window(JNIEnv* env, jclass /*cls*/, jint z, jint x, jint y, jstring kept, jobject edits,
                            jstring out) {
    const auto refuse = [env](const std::string& why) -> jboolean {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), why.c_str());
        return JNI_FALSE;
    };
    void* address = edits == nullptr ? nullptr : env->GetDirectBufferAddress(edits);
    const jlong capacity = edits == nullptr ? 0 : env->GetDirectBufferCapacity(edits);
    if (address == nullptr || capacity <= 0) return refuse("the edits must be a direct buffer");
    const auto read =
        osm::read_edits(std::span(static_cast<const std::byte*>(address), static_cast<std::size_t>(capacity)));
    if (!read) return refuse(std::format("the edits are refused: {}", read.error().what));
    const std::string source = string_of(env, kept);
    const auto data = tiles::MappedSource::open(source);
    if (!data) return refuse(std::format("the kept data {} does not open: {}", source, tiles::describe(data.error())));
    const auto t0 = std::chrono::steady_clock::now();
    const auto edited = osm::apply_edits((*data)->view(0, (*data)->size()), *read);
    if (!edited) {
        return refuse(std::format("the edits are refused: {} at byte {}: {}", osm::name_of(edited.error().fault),
                                  edited.error().at, edited.error().what));
    }
    log::info("vk window {}/{}/{}: the user's {} edits: {} applied, {} carried by its data, {} stale, {} not in it; "
              "in {:.0f} ms",
              z, x, y, read->size(), edited->applied, edited->carried, edited->stale, edited->absent,
              std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    if (!edited->changed()) return JNI_FALSE;
    const std::string why = write_whole(string_of(env, out), edited->json);
    if (!why.empty()) return refuse(why);
    return JNI_TRUE;
}

/// The user's palette for the map (MK/state/PaletteBlock.kt's 1,040 bytes in a direct buffer); "" or why it was
/// refused.
jstring native_palette(JNIEnv* env, jclass /*cls*/, jlong handle, jobject palette) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    void* address = palette == nullptr ? nullptr : env->GetDirectBufferAddress(palette);
    const jlong capacity = palette == nullptr ? 0 : env->GetDirectBufferCapacity(palette);
    if (address == nullptr || std::cmp_less(capacity, style::palette_block_size)) {
        return java(env, "the palette must be a direct buffer of 1,040 bytes");
    }
    const auto block =
        style::read_palette_block(std::span(static_cast<const std::byte*>(address), style::palette_block_size));
    if (!block) return java(env, std::format("the palette was refused: {}", style::name_of(block.error())));
    core->palette(*block);
    return java(env, "");
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

void native_landing(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jboolean has, jdouble latitude, jdouble longitude,
                    jdouble bearing, jdouble pitch, jdouble distance) {
    Core* core = core_of(handle);
    if (core == nullptr) return;
    if (has != JNI_TRUE) {
        core->landing(std::nullopt);
        return;
    }
    core->landing(scene::World::Landing{
        .latitude = latitude, .longitude = longitude, .bearing = bearing, .pitch = pitch, .distance = distance});
}

void native_screen(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jboolean haze, jboolean vignette, jboolean grain) {
    Core* core = core_of(handle);
    if (core != nullptr) core->screen(haze == JNI_TRUE, vignette == JNI_TRUE, grain == JNI_TRUE);
}

/// The kept walls' and near cells' shape records for the map from now on, `on` (the default) or off, each kept wall
/// then decided at each point (frame/MapFrame.hpp's set_shape_records, for VK bench): "" where its buildings draw from
/// them, else why they cannot or not yet.
jstring native_shape_records(JNIEnv* env, jclass /*cls*/, jlong handle, jboolean on) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    return java(env, core->shape_records(on == JNI_TRUE));
}

/// The app's labels for the map (MK/state/LabelBlock.kt's block in a direct buffer, labels/Sprites.hpp's
/// read_label_block: each label at its own size and at the band's top), at `revision`, drawn no smaller than `least` of
/// their own size; an empty block takes them away.
/// "" or why they were refused.
jstring native_labels(JNIEnv* env, jclass /*cls*/, jlong handle, jobject block, jlong revision, jfloat least) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    void* address = block == nullptr ? nullptr : env->GetDirectBufferAddress(block);
    const jlong capacity = block == nullptr ? 0 : env->GetDirectBufferCapacity(block);
    if (address == nullptr || capacity < 0) return java(env, "the labels must be a direct buffer");
    auto read =
        labels::read_label_block(std::span(static_cast<const std::byte*>(address), static_cast<std::size_t>(capacity)));
    if (!read) return java(env, std::format("the labels were refused: {}", read.error()));
    if (read->sprites.empty()) {
        core->labels(nullptr);
        return java(env, "");
    }
    auto set = labels::LabelSet::make(std::move(read->sprites), static_cast<std::uint64_t>(revision), least,
                                      read->large_scale);
    if (!set) return java(env, std::format("the labels were refused: {}", labels::describe(set.error())));
    core->labels(std::move(*set));
    return java(env, "");
}

/// The world's names the map's frames show without a sprite, by rank, for the app to draw (ENGINE.md step 5, "Names"):
/// little-endian, their count (u32), then each one's id (u32), its look (u32: scene::NameStyle) and its text's bytes
/// (u32, then UTF-8). Each is asked for once in two seconds.
jbyteArray native_names_wanted(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    const std::vector<labels::NameWant> wants =
        core != nullptr ? core->names_wanted() : std::vector<labels::NameWant>{};
    std::vector<jbyte> out;
    const auto put = [&out](std::uint32_t v) {
        for (unsigned k = 0; k < 4; ++k) out.push_back(static_cast<jbyte>((v >> (8U * k)) & 0xFFU));
    };
    put(static_cast<std::uint32_t>(wants.size()));
    for (const labels::NameWant& w : wants) {
        put(w.id);
        put(w.style);
        put(static_cast<std::uint32_t>(w.text.size()));
        for (const char c : w.text) out.push_back(static_cast<jbyte>(c));
    }
    jbyteArray array = env->NewByteArray(static_cast<jsize>(out.size()));
    if (array != nullptr) env->SetByteArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

/// The names' sprites the app hands back (MK/state/LabelBlock.kt's block in a direct buffer, each sprite without
/// points; one without an image is a name the device has no glyphs for), their text drawn no smaller than `floor_dp`
/// (the readable floor: 12 sp times Android's font scale). "" or why they were refused.
jstring native_names(JNIEnv* env, jclass /*cls*/, jlong handle, jobject block, jfloat floor_dp) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    void* address = block == nullptr ? nullptr : env->GetDirectBufferAddress(block);
    const jlong capacity = block == nullptr ? 0 : env->GetDirectBufferCapacity(block);
    if (address == nullptr || capacity < 0) return java(env, "the names must be a direct buffer");
    auto read =
        labels::read_label_block(std::span(static_cast<const std::byte*>(address), static_cast<std::size_t>(capacity)));
    if (!read) return java(env, std::format("the names were refused: {}", read.error()));
    if (read->sprites.empty()) return java(env, "");
    const std::string why = core->names(std::move(read->sprites), read->large_scale, floor_dp);
    return java(env, why.empty() ? why : std::format("the names were refused: {}", why));
}

/// Names no tile holds, picked as a tile's are from now on (a test's: smoke's eight scripts): little-endian, their
/// count (u32), then each one's kind (u32: scene::NameKind), latitude, longitude and people (f64 each: a water's
/// area in m², a river's length in m) and its text's bytes (u32, then UTF-8). "" or why they were refused.
jstring native_test_names(JNIEnv* env, jclass /*cls*/, jlong handle, jbyteArray block) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    std::vector<jbyte> bytes(block == nullptr ? 0 : static_cast<std::size_t>(env->GetArrayLength(block)));
    if (!bytes.empty()) env->GetByteArrayRegion(block, 0, static_cast<jsize>(bytes.size()), bytes.data());
    std::size_t at = 0;
    const auto take = [&](void* to, std::size_t n) {
        if (bytes.size() - at < n) return false;
        std::memcpy(to, bytes.data() + at, n);
        at += n;
        return true;
    };
    std::uint32_t count = 0;
    if (!take(&count, 4)) return java(env, "the names were refused: no count");
    std::vector<scene::WorldName> names;
    for (std::uint32_t i = 0; i < count; ++i) {
        std::uint32_t kind = 0;
        std::uint32_t length = 0;
        scene::WorldName name;
        if (!take(&kind, 4) || !take(&name.lat, 8) || !take(&name.lon, 8) || !take(&name.people, 8) ||
            !take(&length, 4) || bytes.size() - at < length ||
            kind > static_cast<std::uint32_t>(scene::NameKind::river)) {
            return java(env, std::format("the names were refused: name {} is cut short, or of no kind", i));
        }
        name.kind = static_cast<scene::NameKind>(kind);
        if (scene::is_water(name.kind)) {
            name.extent = name.people;
            name.people = 0.0;
        }
        name.text.assign(reinterpret_cast<const char*>(bytes.data() + at), length);
        at += length;
        names.push_back(std::move(name));
    }
    core->test_names(std::move(names));
    return java(env, "");
}

/// The app's markers for the map (MK/state/MarkerBlock.kt's block in a direct buffer, markers/Markers.hpp's
/// read_marker_block: each marker's look as the app drew it, the clusters' counts, and what the core places them by),
/// at `revision`; an empty block takes them away. "" or why they were refused.
jstring native_markers(JNIEnv* env, jclass /*cls*/, jlong handle, jobject block, jlong revision) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    void* address = block == nullptr ? nullptr : env->GetDirectBufferAddress(block);
    const jlong capacity = block == nullptr ? 0 : env->GetDirectBufferCapacity(block);
    if (address == nullptr || capacity < 0) return java(env, "the markers must be a direct buffer");
    auto read = markers::read_marker_block(
        std::span(static_cast<const std::byte*>(address), static_cast<std::size_t>(capacity)));
    if (!read) return java(env, std::format("the markers were refused: {}", read.error()));
    if (read->markers.empty()) {
        core->markers(nullptr);
        return java(env, "");
    }
    auto set = markers::MarkerSet::make(std::move(read->markers), std::move(read->looks), std::move(read->counts),
                                        read->density, static_cast<std::uint64_t>(revision));
    if (!set) return java(env, std::format("the markers were refused: {}", markers::describe(set.error())));
    core->markers(std::move(*set));
    return java(env, "");
}

/// The app's routes and areas for the map (MK/state/RouteBlock.kt's block in a direct buffer, routes/Routes.hpp's
/// read_route_block: each route's look, points and chip, and the areas), at `revision`; an empty block takes them
/// away. "" or why they were refused.
jstring native_routes(JNIEnv* env, jclass /*cls*/, jlong handle, jobject block, jlong revision) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "no core");
    void* address = block == nullptr ? nullptr : env->GetDirectBufferAddress(block);
    const jlong capacity = block == nullptr ? 0 : env->GetDirectBufferCapacity(block);
    if (address == nullptr || capacity < 0) return java(env, "the routes must be a direct buffer");
    auto read =
        routes::read_route_block(std::span(static_cast<const std::byte*>(address), static_cast<std::size_t>(capacity)));
    if (!read) return java(env, std::format("the routes were refused: {}", read.error()));
    if (read->routes.empty() && read->areas.empty()) {
        core->routes(nullptr);
        return java(env, "");
    }
    auto set = routes::RouteSet::make(std::move(read->areas), std::move(read->routes), std::move(read->chips),
                                      read->density, static_cast<std::uint64_t>(revision));
    if (!set) return java(env, std::format("the routes were refused: {}", routes::describe(set.error())));
    core->routes(std::move(*set));
    return java(env, "");
}

/// The building the app asks about (FillIn's question; frame/Asked.hpp): way or relation `id` (0: none, the question
/// closed), its walls' flat colour `wall` (0xRRGGBB; negative: their colour is not asked about, and they keep the
/// colour its tile gives them), whether it is drawn hatched (the question still open), and the roof it is asked about
/// in (`roof`: 0 its own, 1 flat, 2 gabled, 3 hipped: scene::house_roof).
void native_ask(JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jboolean relation, jlong id, jint wall, jboolean hatched,
                jint roof) {
    Core* core = core_of(handle);
    if (core == nullptr) return;
    if (id == 0) {
        core->ask(std::nullopt);
        return;
    }
    core->ask(frame::AskedBuilding{.id = id,
                                   .relation = relation == JNI_TRUE,
                                   .wall = wall < 0 ? 0U : static_cast<std::uint32_t>(wall) & 0xFF'FFFFU,
                                   .hatched = wall >= 0 && hatched == JNI_TRUE,
                                   .roof = static_cast<std::uint8_t>(roof >= 0 && roof <= 3 ? roof : 0),
                                   .own_wall = wall < 0});
}

/// The building under the view's device point (`x`, `y`) as the map's last frame drew it (frame/MapFrame.hpp's pick):
/// "way <id> <part> <latitude> <longitude> <roof|wall> <slot> <rgb>" (or "relation …"; the part 0 where the ray met
/// the outline itself), where the ray met it, the face it met, that face's slot word and its base colour (decimal);
/// "" where it met none.
jstring native_pick(JNIEnv* env, jclass /*cls*/, jlong handle, jfloat x, jfloat y) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "");
    const auto picked = core->pick(static_cast<double>(x), static_cast<double>(y));
    if (!picked) return java(env, "");
    return java(env, std::format("{} {} {} {:.7f} {:.7f} {} {} {}", picked->relation ? "relation" : "way", picked->id,
                                 picked->part, picked->latitude, picked->longitude, picked->roof ? "roof" : "wall",
                                 picked->slot, picked->rgb));
}

/// What the view's device point (`x`, `y`) shows of the ground as the map's last frame drew it (frame/MapFrame.hpp's
/// pick_ground): "<plate|fill|water|quay> <slot> <latitude> <longitude>", what the ray met, the slot of the user's
/// palette that paints it (0: none does) and where; "" where it met none.
jstring native_pick_ground(JNIEnv* env, jclass /*cls*/, jlong handle, jfloat x, jfloat y) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "");
    const auto picked = core->pick_ground(static_cast<double>(x), static_cast<double>(y));
    if (!picked) return java(env, "");
    const auto what = [](scene::PickedGround::What w) -> std::string_view {
        switch (w) {
        case scene::PickedGround::What::plate: return "plate";
        case scene::PickedGround::What::fill: return "fill";
        case scene::PickedGround::What::water: return "water";
        case scene::PickedGround::What::quay: return "quay";
        }
        return "fill";
    };
    return java(
        env, std::format("{} {} {:.7f} {:.7f}", what(picked->what), picked->slot, picked->latitude, picked->longitude));
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

/// K17a's fields check without a core (VK facts' honest state): the renders' eight screen-wide fields at S1 (1080 ×
/// 2400 at 1080/412, the CI emulator's size) made on four threads, timed, each held to fields.inc's hashes (the
/// port's): `fields 1080x2400 cpu <ms> gpu off (no Vulkan) hash <h> ok`, or `… FAIL <field> <h> expected <h>`. Only
/// when VK facts asks, never on the start's or the map's path; with a core, its own (native_core_fields_line) runs the
/// GPU's too.
jstring native_fields_line(JNIEnv* env, jclass /*cls*/) {
    return java(env, fx::fields_line(fx::size_of(1080, 2400, 1080.0 / 412.0), 4, fx::GpuPart{.off = "no Vulkan"}));
}

/// K17a's fields check on the core's device (Core::fields_line, on the render thread): the CPU's fields at S1 and,
/// where the GPU's noise is on, the GPU's after its byte check, behind the guard's noise stage: `fields 1080x2400 cpu
/// <ms> gpu <ms> hash <h> ok`, `… gpu off (<why>) …`, or `… FAIL …`.
jstring native_core_fields_line(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    if (core == nullptr) return native_fields_line(env, nullptr);
    return java(env, core->fields_line());
}

// ---- end of K17a's natives ----

// ---- K9's natives: the ground's check (VK facts) ----

/// K9's ground check over the bundled Westerkerk, an asset's descriptor, offset and length (about 40 ms of one core):
/// `ground <hash> ok` or `ground <hash> FAIL expected <hash>`.
jstring native_ground_line(JNIEnv* env, jclass /*cls*/, jint fd, jlong offset, jlong length) {
    return java(env, geometry::ground_line(fd, static_cast<std::uint64_t>(offset), static_cast<std::uint64_t>(length)));
}
// ---- end of K9's natives ----

/// VK facts' composite check (frame/CompositeCheck.hpp: the composite's resolve form against its direct form, a few
/// frames offscreen behind the crash guard's checks stage, on the render thread): `composite.check: …`.
jstring native_composite_line(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "composite.check: not run (no core)");
    return java(env, core->composite_line());
}

/// VK facts' blur check (passes/BlurCheck.hpp: the soft shadows' blur of a known pattern three ways, held to the CPU's
/// block by block): `shadow.blur: exact (…)`, `shadow.blur: differs (…)` or `shadow.blur: unavailable (…)`.
jstring native_blur_line(JNIEnv* env, jclass /*cls*/, jlong handle) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "shadow.blur: not run (no core)");
    return java(env, core->blur_line());
}

/// The twin's check over the bundled Westerkerk, an asset's descriptor, offset and length (frame/TwinCheck.hpp: about
/// a second of two cores on a phone, on the render thread): `twin <hash> ok` or `twin <hash> FAIL expected <hash>`.
jstring native_twin_line(JNIEnv* env, jclass /*cls*/, jlong handle, jint fd, jlong offset, jlong length) {
    Core* core = core_of(handle);
    if (core == nullptr) return java(env, "twin unavailable: no core");
    return java(env, core->twin_line({.id = "amsterdam",
                                      .kind = tiles::PackKind::render,
                                      .fd = fd,
                                      .offset = static_cast<std::uint64_t>(offset),
                                      .length = static_cast<std::uint64_t>(length)}));
}

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

const std::array<JNINativeMethod, 68>& methods() {
    static const std::array<JNINativeMethod, 68> table{{
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
        method("health", "(J)Ljava/lang/String;", fn(&native_health)),
        method("setPacing", "(JZ)V", fn(&native_set_pacing)),
        method("setCompression", "(JZ)V", fn(&native_set_compression)),
        method("compressionSwitch", "(J)Z", fn(&native_compression_switch)),
        method("setFastRefresh", "(JZ)V", fn(&native_set_fast_refresh)),
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
        method("twinLine", "(JIJJ)Ljava/lang/String;", fn(&native_twin_line)),
        method("compositeLine", "(J)Ljava/lang/String;", fn(&native_composite_line)),
        method("blurLine", "(J)Ljava/lang/String;", fn(&native_blur_line)),
        method("coreFieldsLine", "(J)Ljava/lang/String;", fn(&native_core_fields_line)),
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
        method("packs", "(J[Ljava/lang/String;[J[I[Ljava/lang/String;[I[J[J[[B)Ljava/lang/String;", fn(&native_packs)),
        method("moment", "(JLjava/nio/ByteBuffer;)Ljava/lang/String;", fn(&native_moment)),
        method("palette", "(JLjava/nio/ByteBuffer;)Ljava/lang/String;", fn(&native_palette)),
        method("labels", "(JLjava/nio/ByteBuffer;JF)Ljava/lang/String;", fn(&native_labels)),
        method("namesWanted", "(J)[B", fn(&native_names_wanted)),
        method("names", "(JLjava/nio/ByteBuffer;F)Ljava/lang/String;", fn(&native_names)),
        method("testNames", "(J[B)Ljava/lang/String;", fn(&native_test_names)),
        method("markers", "(JLjava/nio/ByteBuffer;J)Ljava/lang/String;", fn(&native_markers)),
        method("routes", "(JLjava/nio/ByteBuffer;J)Ljava/lang/String;", fn(&native_routes)),
        method("pick", "(JFF)Ljava/lang/String;", fn(&native_pick)),
        method("pickGround", "(JFF)Ljava/lang/String;", fn(&native_pick_ground)),
        method("ask", "(JZJIZI)V", fn(&native_ask)),
        method("screen", "(JZZZ)V", fn(&native_screen)),
        method("shapeRecords", "(JZ)Ljava/lang/String;", fn(&native_shape_records)),
        method("landing", "(JZDDDDD)V", fn(&native_landing)),
        method("wantedWindows", "(J)[J", fn(&native_wanted_windows)),
        method("windowBox", "(III)[D", fn(&native_window_box)),
        method("windowVersion", "()Ljava/lang/String;", fn(&native_window_version)),
        method("buildWindow", "(IIILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;", fn(&native_build_window)),
        method("windowsInView", "(J)[J", fn(&native_windows_in_view)),
        method("wantedLevels", "(J)[J", fn(&native_wanted_levels)),
        method("levelsInView", "(J)[J", fn(&native_levels_in_view)),
        method("worldCache", "(JLjava/lang/String;J)Ljava/lang/String;", fn(&native_world_cache)),
        method("worldHeld", "(Ljava/lang/String;J[B)[Z", fn(&native_world_held)),
        method("worldHead", "(Ljava/lang/String;JIJ[B[J[[B)Ljava/lang/String;", fn(&native_world_head)),
        method("worldDrop", "(Ljava/lang/String;J)Ljava/lang/String;", fn(&native_world_drop)),
        method("worldKept", "(Ljava/lang/String;J[J[B[I[J)[J", fn(&native_world_kept)),
        method("worldKeptRange", "(Ljava/lang/String;JJ[BJ[B)[J", fn(&native_world_kept_range)),
        method("worldWants", "(J)[J", fn(&native_world_wants)),
        method("worldBlocks", "(J)[J", fn(&native_world_blocks)),
        method("worldKeep", "(JJ[BJ[B)[J", fn(&native_world_keep)),
        method("mergeWindow", "(IIILjava/lang/String;Ljava/nio/ByteBuffer;Ljava/lang/String;)Z",
               fn(&native_merge_window)),
        method("editWindow", "(IIILjava/lang/String;Ljava/nio/ByteBuffer;Ljava/lang/String;)Z",
               fn(&native_edit_window)),
    }};
    return table;
}

} // namespace

std::span<const JNINativeMethod> facade_methods() noexcept { return methods(); }

} // namespace atlas::jni
