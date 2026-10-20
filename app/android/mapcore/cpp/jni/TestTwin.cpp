// The frame core's test entry points in the host build (plan-vector K7), for :mapcorecheck's MT/frame and MT/twin tests
// on the JVM: a render pack's ladder, the engine camera, ViewLadder.place and ScreenLayers.of for a camera state and a
// theme block as the app writes them, the device pixels of world points through the double camera and through the
// vertex stage's float one (shaders/shared/camera.slang compiled as C++), the snap at rest, and the frame drawn over
// render packs (jni/TestFrame.hpp's host_frame::draw: its pixels, its owner map, its twin's stream). They bind to the
// Kotlin object app.atlas.mapcorecheck.frame.FrameHost by JNI's names; nothing of them is in the phone's library.
//
// Every value goes back as its bits (doubles as 16 hex digits), in the canonical text MT/frame/LadderParityTest.kt
// writes for the port's own values, so a difference shows as the first line that differs.
#include "base/hlsl.hpp"
#include "frame/Counters.hpp"
#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/FieldCache.hpp"
#include "gpu/Device.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Instance.hpp"
#include "gpu/PipelineCache.hpp"
#include "jni/TestFrame.hpp"
#include "platform/host/Offscreen.hpp"
#include "scene/World.hpp"
#include "style/CameraState.hpp"
#include "style/Theme.hpp"
#include "tiles/Container.hpp"
#include "tiles/Error.hpp"
#include "tiles/Source.hpp"
#include "twin/Twin.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"
#include "view/ScreenLayers.hpp"
#include "view/Snap.hpp"

#include <jni.h>

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <format>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace {

namespace tl = atlas::tiles;
namespace hlsl = atlas::hlsl;
namespace view = atlas::view;
namespace style = atlas::style;
namespace shaders = atlas::shaders;

/// The layout version of these entry points; FrameHost refuses another.
constexpr jint test_frame_abi = 1;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring text(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

/// A direct ByteBuffer's bytes, or an empty span after throwing IllegalArgumentException.
std::span<const std::byte> bytes_of(JNIEnv* env, jobject buffer) {
    void* p = buffer != nullptr ? env->GetDirectBufferAddress(buffer) : nullptr;
    const jlong n = buffer != nullptr ? env->GetDirectBufferCapacity(buffer) : -1;
    if (p == nullptr || n < 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "a direct ByteBuffer is needed");
        return {};
    }
    return {static_cast<const std::byte*>(p), static_cast<std::size_t>(n)};
}

std::string hex(double v) { return std::format("{:016x}", std::bit_cast<std::uint64_t>(v)); }

/// A pack opened once per JVM, with its ladder (which views the pack's `rhead`, so the pack never moves).
struct Opened {
    std::unique_ptr<tl::Pack> pack;
    view::Ladder ladder;
};

/// The packs opened so far, by path.
struct OpenedPacks {
    std::mutex mutex;
    std::vector<std::pair<std::string, std::shared_ptr<const Opened>>> packs;
};

OpenedPacks& opened_packs() {
    static OpenedPacks packs;
    return packs;
}

/// The pack at `path` with its ladder, or null with `error` set.
std::shared_ptr<const Opened> open(const std::string& path, std::string& error) {
    OpenedPacks& all = opened_packs();
    const std::lock_guard lock(all.mutex);
    if (const auto it = std::ranges::find(all.packs, path, [](const auto& e) { return e.first; });
        it != all.packs.end()) {
        return it->second;
    }
    auto source = tl::MappedSource::open(path);
    if (!source) {
        error = "error=" + tl::describe(source.error());
        return nullptr;
    }
    auto pack = tl::Pack::open(std::move(*source));
    if (!pack || pack->head() == nullptr) {
        error = pack ? "error=not a render pack" : "error=" + tl::describe(pack.error());
        return nullptr;
    }
    auto o = std::make_shared<Opened>();
    o->pack = std::make_unique<tl::Pack>(std::move(*pack));
    auto ladder = view::Ladder::of(*o->pack->head());
    if (!ladder) {
        error = "error=the pack's rhead has no ladder (a view lacks a number)";
        return nullptr;
    }
    o->ladder = std::move(*ladder);
    all.packs.emplace_back(path, o);
    return o;
}

/// The camera state and the theme block of a call, read and checked as the core reads them.
struct Inputs {
    style::CameraState camera{};
    style::ThemeBlock theme{};
};

std::optional<Inputs> inputs_of(JNIEnv* env, jobject camera, jobject theme, std::string& error) {
    const auto c = bytes_of(env, camera);
    if (env->ExceptionCheck() == JNI_TRUE) return std::nullopt;
    const auto state = style::read_camera_state(c);
    if (!state) {
        error = std::string("error=camera ") + style::name_of(state.error());
        return std::nullopt;
    }
    Inputs in{.camera = *state, .theme = {}};
    if (theme != nullptr) {
        const auto t = bytes_of(env, theme);
        if (env->ExceptionCheck() == JNI_TRUE) return std::nullopt;
        const auto block = style::read_theme_block(t);
        if (!block) {
            error = std::string("error=theme ") + style::name_of(block.error());
            return std::nullopt;
        }
        in.theme = *block;
    }
    return in;
}

/// The engine camera's values: `camera eye … f … r … u … focal … cx … cy … horizon …`.
std::string camera_line(const view::Camera& c) {
    auto v3 = [](const view::Vec3& v) { return std::format("{} {} {}", hex(v.x), hex(v.y), hex(v.z)); };
    return std::format("camera eye {} f {} r {} u {} focal {} cx {} cy {} horizon {}\n", v3(c.eye()), v3(c.forward()),
                       v3(c.right()), v3(c.up()), hex(c.focal()), hex(c.cx()), hex(c.cy()), hex(c.horizon_y()));
}

/// The screen layers' values, as LadderParityTest writes the port's ScreenLayers.Frame.
std::string layers_lines(const view::Layers& l) {
    std::string out =
        std::format("layers W {} H {} horizon {} weather {} night {} overcast {} eye {}\n", hex(l.width), hex(l.height),
                    hex(l.horizon), l.weather ? 1 : 0, l.night ? 1 : 0, l.overcast ? 1 : 0, l.eye ? 1 : 0);
    if (l.clouds) {
        out += std::format("clouds {} {} {} {} {}\n", hex(l.clouds->k), hex(l.clouds->t), hex(l.clouds->red),
                           hex(l.clouds->green), hex(l.clouds->blue));
    } else {
        out += "clouds -\n";
    }
    out += std::format("sky_bottom {}\n", hex(l.sky_bottom));
    out += l.sun ? std::format("sun {} {}\n", hex(l.sun->x), hex(l.sun->y)) : std::string("sun -\n");
    const view::Haze& h = l.haze;
    out += std::format("haze band {} y0 {} y1 {} n {}\n", h.band ? 1 : 0, hex(h.y0), hex(h.y1), h.count);
    out += "offsets";
    for (std::size_t i = 0; i < h.count; ++i) out += " " + hex(h.offsets[i]);
    out += "\nalphas";
    for (std::size_t i = 0; i < h.count; ++i) out += " " + hex(h.alphas[i]);
    out += std::format("\nwash {} cut {} rain {} fin {}\n", l.wash ? 1 : 0,
                       l.cloud_shadows() ? hex(l.cloud_shadow_cut) : std::string("-"),
                       l.rain_angle ? std::format("{}", *l.rain_angle) : std::string("-"), l.draws_fin ? 1 : 0);
    return out;
}

/// The device of a camera state: its density and px size, unturned.
view::Device device_of(const style::CameraState& c) {
    return {.density = c.density, .width_px = c.width_px, .height_px = c.height_px, .quarter_turns = 0};
}

/// Why the last frame drawn on this thread failed, and what it noted.
thread_local std::string last_error; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): per thread
thread_local std::string last_notes; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): per thread

/// Render packs given as `id=path` strings, in lookup order.
std::vector<std::pair<std::string, std::string>> packs_of(JNIEnv* env, jobjectArray packs) {
    std::vector<std::pair<std::string, std::string>> out;
    const jsize n = packs != nullptr ? env->GetArrayLength(packs) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto* item = static_cast<jstring>( // NOLINT(cppcoreguidelines-pro-type-static-cast-downcast): JNI's
            env->GetObjectArrayElement(packs, i));
        const std::string spec = string_of(env, item);
        env->DeleteLocalRef(item);
        const auto eq = spec.find('=');
        if (eq != std::string::npos) out.emplace_back(spec.substr(0, eq), spec.substr(eq + 1));
    }
    return out;
}

/// A float as the twin's vertex stage holds it, widened back for the JVM.
double wide(float f) { return static_cast<double>(f); }

/// A tile's south-west corner at street level for a frame point: the 1,024 m grid's, so the point's tile-local float
/// metres stay within a tile's span, as stage T writes them.
view::Vec3 origin_of(const view::Vec3& p) {
    constexpr double tile = 1024.0;
    return {std::floor(p.x / tile) * tile, 0.0, std::floor(p.z / tile) * tile};
}

} // namespace

namespace atlas::jni::host_frame {

namespace {

/// Settling: frames 20 ms apart until nothing is pending, at most this many.
constexpr int max_settle_frames = 300;

/// The session's handles, closed in order however a draw ends.
struct Session {
    std::unique_ptr<gpu::Instance> instance;
    std::unique_ptr<gpu::Device> device;
    Session() = default;
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    Session(Session&&) = delete;
    Session& operator=(Session&&) = delete;
    ~Session() {
        if (device) device->wait_idle();
        device.reset();
        instance.reset();
    }
};

} // namespace

void folders_from_jvm(JNIEnv* env, Request& request) {
    // A system property of the JVM ("" when it is not set or cannot be read).
    auto property = [env](const char* name) -> std::string {
        jclass system = env->FindClass("java/lang/System");
        if (system == nullptr) {
            env->ExceptionClear();
            return {};
        }
        jmethodID get = env->GetStaticMethodID(system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
        jstring key = env->NewStringUTF(name);
        auto* value = get != nullptr && key != nullptr
                          ? static_cast<jstring>(env->CallStaticObjectMethod(system, get, key)) // NOLINT
                          : nullptr;
        if (key != nullptr) env->DeleteLocalRef(key);
        env->DeleteLocalRef(system);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return {};
        }
        if (value == nullptr) return {};
        const char* chars = env->GetStringUTFChars(value, nullptr);
        std::string out = chars != nullptr ? chars : "";
        if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
        return out;
    };
    if (const std::string repo = property("atlas.repo"); !repo.empty()) {
        request.resources = repo + "/app/android/mapview/src/main/assets";
    }
    if (const std::string temporary = property("java.io.tmpdir"); !temporary.empty()) {
        request.cache = temporary + "/atlas-host-frame";
    }
}

Result draw(const Request& r) {
    Result out;
    const auto camera = style::read_camera_state(r.camera);
    if (!camera) {
        out.error = std::string("the camera state: ") + style::name_of(camera.error());
        return out;
    }
    const auto theme = style::read_theme_block(r.theme);
    if (!theme) {
        out.error = std::string("the theme block: ") + style::name_of(theme.error());
        return out;
    }
    Session session;
    auto instance =
        gpu::Instance::open({.validation = true, .require_validation = false, .app_name = "atlas host frame"});
    if (!instance) {
        out.error = "no Vulkan: " + instance.error().text();
        return out;
    }
    session.instance = std::move(*instance);
    auto device = gpu::Device::open(*session.instance, gpu::DeviceOptions{});
    if (!device) {
        out.error = "no device: " + device.error().text();
        return out;
    }
    session.device = std::move(*device);
    const gpu::Device& d = *session.device;
    auto cache = gpu::PipelineCache::open(d.handle(), d.facts(), {}, 0, false);
    if (!cache) {
        out.error = "the pipeline cache: " + cache.error().text();
        return out;
    }
    const gpu::PipelineCache& pipelines = *cache;
    auto made = frame::FrameBuilder::create(d, pipelines, frame::make_passes(), {.overdraw = false, .clear = r.clear});
    if (!made) {
        out.error = "the frame builder: " + made.error().text();
        return out;
    }
    frame::FrameBuilder& builder = **made;
    for (const auto& p : builder.passes()) out.passes += (out.passes.empty() ? "" : ",") + std::string(p->name());
    double now = 0;
    auto world =
        scene::World::create(d, builder.passes(),
                             {.workers = 2,
                              .resources = r.resources.empty() ? frame::Resources{} : frame::files_under(r.resources),
                              .clock = [&now] { return now; }});
    if (!world) {
        out.error = "the world: " + world.error().text();
        return out;
    }
    scene::World& w = **world;
    // K17a's noise fields and byte tables, as the app's map holds them; a full-mode frame waits for its fields.
    auto fields = fx::FieldCache::create(
        d,
        {.dir = r.cache.empty() ? std::filesystem::path() : std::filesystem::path(r.cache) / "fields", .threads = 2});
    if (!fields) {
        out.error = "the noise fields: " + fields.error().text();
        return out;
    }
    fx::FieldCache& field_cache = **fields;
    if (auto added = w.add_globals(std::shared_ptr<frame::GlobalProvider>(std::move(*fields))); !added) {
        out.error = "the noise fields: " + added.error().text();
        return out;
    }
    for (const auto& [id, path] : r.packs) {
        if (auto added = w.store().add({.id = id, .kind = tiles::PackKind::render, .path = path}); !added) {
            out.error = std::format("the pack {} at {}: {}", id, path, tiles::describe(added.error()));
            return out;
        }
    }
    if (!r.active.empty()) w.set_active(r.active);
    frame::FrameInputs inputs{.camera = *camera,
                              .theme = *theme,
                              .seconds = 0,
                              .mode = r.full ? frame::Mode::full : frame::Mode::structural,
                              .scene = &w};
    out.width = camera->width_px;
    out.height = camera->height_px;
    const vk::Extent2D extent{.width = camera->width_px, .height = camera->height_px};
    auto output = platform::Offscreen::create(d, extent, 2, gpu::Depth::deepest);
    if (!output) {
        out.error = "the output: " + output.error().text();
        return out;
    }
    const gpu::Configuration config = d.facts().choice;
    platform::Offscreen& target = **output;
    auto renderer = gpu::Renderer::create(d, target, builder, config);
    if (!renderer) {
        out.error = std::format("the renderer ({}): {}", gpu::name_of(config), renderer.error().text());
        return out;
    }
    gpu::Renderer& drawer = **renderer;
    out.device = std::format("{} ({}, {})", d.facts().name, gpu::name_of(config), gpu::colour_name(target.format()));
    auto frame_at = [&](double t) -> bool {
        now = t;
        inputs.seconds = t;
        builder.set_inputs(inputs);
        auto report = drawer.draw(0);
        if (!report) out.error = "a frame: " + report.error().text();
        return report.has_value();
    };
    if (r.full) {
        // The frame's fields made (or read from the cache) before its first frame, which binds them.
        field_cache.worker().request(fx::size_of(camera->width_px, camera->height_px, camera->density));
        field_cache.worker().wait_idle();
    }
    // Frames until the world settles: the cover's tiles built and drawn, the sun's bits in.
    int frames = 0;
    for (; frames < max_settle_frames; ++frames) {
        if (!frame_at(0.02 * frames)) return out;
        w.residency().wait_idle();
        if (w.sun() != nullptr) w.sun()->wait_idle();
        if (frames >= 2 && w.residency().stats().pending == 0 && w.cover()->missing() == 0) break;
    }
    // Then a second on, past every fade, twice (the frame slots).
    const double settled = (0.02 * frames) + 1.0;
    if (!frame_at(settled) || !frame_at(settled + 0.02)) return out;
    drawer.drain();
    for (const frame::PassCounters& c : builder.counters()) {
        out.counters.push_back(std::format("pass {} {}", c.pass(), frame::text_of(c.total())));
        for (const frame::KindCount& k : c.kinds()) {
            out.counters.push_back(std::format("kind {} {} {}", c.pass(), k.kind, frame::text_of(k.count)));
        }
    }
    out.counters.push_back(std::format("total {}", frame::text_of(builder.total())));
    out.notes = std::format("{} frames to settle; {} tiles drawn, {} resident, {} items", frames + 1,
                            w.cover()->tiles().size(), w.residency().stats().resident, w.items()->live());
    if (r.pixels) {
        auto pixels = target.read(target.latest());
        if (!pixels) {
            out.error = "the read back: " + pixels.error().text();
            return out;
        }
        out.rgba = std::move(pixels->rgba);
    }
    if (r.twin) {
        twin::Stream stream;
        builder.emit(stream, builder.state().number + 1);
        out.twin.assign(stream.bytes().begin(), stream.bytes().end());
    }
    renderer->reset();
    if (r.owners) {
        // The owner mode at one sample: each pixel's latest painter slot, mapped to its item's owner.
        auto owners_made = frame::FrameBuilder::create(d, pipelines, frame::make_passes(),
                                                       {.overdraw = false, .clear = {0, 0, 0, 1}, .owners = true});
        if (!owners_made) {
            out.error = "the owner mode's builder: " + owners_made.error().text();
            return out;
        }
        frame::FrameBuilder& owners_builder = **owners_made;
        auto owners_output = platform::Offscreen::create(d, extent, 2);
        if (!owners_output) {
            out.error = "the owner mode's output: " + owners_output.error().text();
            return out;
        }
        platform::Offscreen& owners_target = **owners_output;
        const gpu::Configuration one{.config = gpu::FrameConfig::x1, .target = gpu::CompositeTarget::direct};
        auto owners_renderer = gpu::Renderer::create(d, owners_target, owners_builder, one);
        if (!owners_renderer) {
            out.error = "the owner mode's renderer: " + owners_renderer.error().text();
            return out;
        }
        gpu::Renderer& owners_frames = **owners_renderer;
        inputs.seconds = settled + 0.04;
        owners_builder.set_inputs(inputs);
        for (int k = 0; k < 2; ++k) {
            if (auto report = owners_frames.draw(0); !report) {
                out.error = "an owner frame: " + report.error().text();
                return out;
            }
        }
        owners_frames.drain();
        auto pixels = owners_target.read(owners_target.latest());
        if (!pixels) {
            out.error = "the owner map's read back: " + pixels.error().text();
            return out;
        }
        out.owners.resize(static_cast<std::size_t>(extent.width) * extent.height);
        owners_builder.owner_map(pixels->rgba, out.owners);
        owners_renderer->reset();
    }
    if (r.overdraw) {
        // The overdraw mode at one sample: each opaque fragment adds a level of red, each translucent one of green.
        auto overdraw_made =
            frame::FrameBuilder::create(d, pipelines, frame::make_passes(), {.overdraw = true, .clear = {0, 0, 0, 1}});
        if (!overdraw_made) {
            out.error = "the overdraw mode's builder: " + overdraw_made.error().text();
            return out;
        }
        frame::FrameBuilder& overdraw_builder = **overdraw_made;
        auto overdraw_output = platform::Offscreen::create(d, extent, 2);
        if (!overdraw_output) {
            out.error = "the overdraw mode's output: " + overdraw_output.error().text();
            return out;
        }
        platform::Offscreen& overdraw_target = **overdraw_output;
        const gpu::Configuration one{.config = gpu::FrameConfig::x1, .target = gpu::CompositeTarget::direct};
        auto overdraw_renderer = gpu::Renderer::create(d, overdraw_target, overdraw_builder, one);
        if (!overdraw_renderer) {
            out.error = "the overdraw mode's renderer: " + overdraw_renderer.error().text();
            return out;
        }
        gpu::Renderer& overdraw_frames = **overdraw_renderer;
        inputs.seconds = settled + 0.06;
        overdraw_builder.set_inputs(inputs);
        for (int k = 0; k < 2; ++k) {
            if (auto report = overdraw_frames.draw(0); !report) {
                out.error = "an overdraw frame: " + report.error().text();
                return out;
            }
        }
        overdraw_frames.drain();
        auto pixels = overdraw_target.read(overdraw_target.latest());
        if (!pixels) {
            out.error = "the overdraw frame's read back: " + pixels.error().text();
            return out;
        }
        std::uint64_t opaque = 0;
        std::uint64_t translucent = 0;
        for (std::size_t i = 0; i + 3 < pixels->rgba.size(); i += 4) {
            opaque += pixels->rgba[i];
            translucent += pixels->rgba[i + 1];
        }
        const auto area = static_cast<double>(extent.width) * static_cast<double>(extent.height);
        out.opaque_fse = static_cast<double>(opaque) / area;
        out.translucent_fse = static_cast<double>(translucent) / area;
        overdraw_renderer->reset();
    }
    if (const auto messages = session.instance->messages().take(); !messages.empty()) {
        out.notes += std::format("; the validation layer: {} (and {} more)", messages.front(), messages.size() - 1);
    }
    return out;
}

} // namespace atlas::jni::host_frame

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_abi(JNIEnv* /*env*/, jclass /*unused*/) {
    return test_frame_abi;
}

/// The pack's ladder: `frame <lat0> <lon0> <kx> <kz> living <0|1>`, then a `knot <name> dist <d> target <x> <y> <z> yaw
/// <d> pitch <d> cy <d|->` line per knot in the ladder's order (doubles as bits), or `error=<why>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_ladder(JNIEnv* env, jclass /*unused*/,
                                                                             jstring path) {
    std::string error;
    const auto o = open(string_of(env, path), error);
    if (!o) return text(env, error);
    const view::Ladder& l = o->ladder;
    const view::GroundFrame& f = l.frame();
    std::string out = std::format("frame {} {} {} {} living {}\n", hex(f.lat0), hex(f.lon0), hex(f.kx), hex(f.kz),
                                  l.living() ? 1 : 0);
    for (const view::Ladder::Knot& k : l.knots()) {
        out += std::format("knot {} dist {} target {} {} {} yaw {} pitch {} cy {}\n", k.name, hex(k.dist),
                           hex(k.target[0]), hex(k.target[1]), hex(k.target[2]), hex(k.yaw), hex(k.pitch),
                           std::isnan(k.cy) ? std::string("-") : hex(k.cy));
    }
    return text(env, out);
}

/// The frame's view of a camera state over the pack at `path`, with the moment's theme block: the region view it
/// snapped onto (`view <name>` or `view -`), `dist <d>`, ViewLadder.place's settings (`cfg` then Placed::dump's lines),
/// the engine camera's values, and ScreenLayers.of's (the layers' lines); `error=<why>` when the core refuses a part.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_place(JNIEnv* env, jclass /*unused*/,
                                                                            jstring path, jobject camera,
                                                                            jobject theme) {
    std::string error;
    const auto o = open(string_of(env, path), error);
    if (!o) return text(env, error);
    const auto in = inputs_of(env, camera, theme, error);
    if (!in) return env->ExceptionCheck() == JNI_TRUE ? nullptr : text(env, error);
    const view::Placed placed = o->ladder.place(view::live_view(in->camera));
    const view::Camera cam(placed.camera());
    std::string out = std::format("view {}\ndist {}\ncfg\n", placed.view().value_or("-"), hex(placed.dist()));
    out += placed.dump();
    out += camera_line(cam);
    if (theme != nullptr) out += layers_lines(view::layers_of(cam, in->theme, view::settings_of(placed)));
    return text(env, out);
}

/// The device px of world points (x, y, z triples, the frame's metres) at a camera state over the pack at `path`, four
/// values each: through the engine camera in double (CSS px times the density), then through the vertex stage's float
/// camera (the frame block's, each point as float metres in a tile of the 1,024 m grid, placed relative to the eye in
/// double), moving or at rest per the camera state's rest flag; NaN for a point nearer than the near plane. Null with
/// the error in lastError when the core refuses the camera.
JNIEXPORT jdoubleArray JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_project(JNIEnv* env, jclass /*unused*/,
                                                                                   jstring path, jobject camera,
                                                                                   jdoubleArray points) {
    std::string error;
    const auto o = open(string_of(env, path), error);
    if (!o) return nullptr;
    const auto in = inputs_of(env, camera, nullptr, error);
    if (!in) return nullptr;
    const view::Placed placed = o->ladder.place(view::live_view(in->camera));
    const view::View v(placed.camera(), device_of(in->camera));
    const bool rest = (in->camera.flags & style::camera_flag::rest) != 0U;
    const shaders::FrameCamera block = v.block(rest, false, 0);
    const jsize n = env->GetArrayLength(points) / 3;
    const auto count = static_cast<std::size_t>(std::max<jsize>(n, 0));
    std::vector<double> p(count * 3U);
    env->GetDoubleArrayRegion(points, 0, n * 3, p.data());
    std::vector<double> out(count * 4U, std::numeric_limits<double>::quiet_NaN());
    for (std::size_t i = 0; i < count; ++i) {
        const view::Vec3 w{p[3 * i], p[(3 * i) + 1], p[(3 * i) + 2]};
        if (const auto d = v.device_proj(w)) {
            out[4 * i] = d->x;
            out[(4 * i) + 1] = d->y;
        }
        const view::Vec3 origin = origin_of(w);
        const shaders::TilePlace place = v.tile_place(origin, 1.0, 1.0);
        const hlsl::float3 local{static_cast<float>(w.x - origin.x), static_cast<float>(w.y - origin.y),
                                 static_cast<float>(w.z - origin.z)};
        const hlsl::float3 k = shaders::camera_of(block, shaders::eye_relative(place, local));
        if (k.z >= block.frame.w) {
            const hlsl::float2 css = shaders::project_css(block, k);
            out[(4 * i) + 2] = wide(css.x) * in->camera.density;
            out[(4 * i) + 3] = wide(css.y) * in->camera.density;
        }
    }
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(out.size()));
    if (result != nullptr) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

/// The snap at rest of each value: two values each, view::snap_tenth of the double, and of the value as a float
/// (the vertex stage's), widened.
JNIEXPORT jdoubleArray JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_snap(JNIEnv* env, jclass /*unused*/,
                                                                                jdoubleArray values) {
    const jsize n = env->GetArrayLength(values);
    std::vector<double> v(static_cast<std::size_t>(n));
    env->GetDoubleArrayRegion(values, 0, n, v.data());
    std::vector<double> out(v.size() * 2U);
    for (std::size_t i = 0; i < v.size(); ++i) {
        out[2 * i] = view::snap_tenth(v[i]);
        out[(2 * i) + 1] = wide(view::snap_tenth(static_cast<float>(v[i])));
    }
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(out.size()));
    if (result != nullptr) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

/// The SVG's print precisions (view::decimals) of each value at `places` decimals.
JNIEXPORT jdoubleArray JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_decimals(JNIEnv* env, jclass /*unused*/,
                                                                                    jdoubleArray values, jint places) {
    const jsize n = env->GetArrayLength(values);
    std::vector<double> v(static_cast<std::size_t>(n));
    env->GetDoubleArrayRegion(values, 0, n, v.data());
    for (double& x : v) x = view::decimals(x, places);
    jdoubleArray result = env->NewDoubleArray(n);
    if (result != nullptr) env->SetDoubleArrayRegion(result, 0, n, v.data());
    return result;
}

/// The twin's stream of the frame over render packs `packs` (`id=path`, in lookup order; `active` the one to draw, or
/// empty) at a camera state and a theme block as Kotlin writes them, in full mode or structural, drawn on the host's
/// Vulkan until the world settles (jni/TestFrame.hpp). Null on a failure, named by lastError.
JNIEXPORT jbyteArray JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_twin(JNIEnv* env, jclass /*unused*/,
                                                                              jobjectArray packs, jstring active,
                                                                              jobject camera, jobject theme,
                                                                              jboolean full) {
    atlas::jni::host_frame::Request request;
    request.packs = packs_of(env, packs);
    request.active = active != nullptr ? string_of(env, active) : std::string();
    request.camera = bytes_of(env, camera);
    if (env->ExceptionCheck() == JNI_TRUE) return nullptr;
    request.theme = bytes_of(env, theme);
    if (env->ExceptionCheck() == JNI_TRUE) return nullptr;
    request.full = full == JNI_TRUE;
    request.pixels = false;
    request.twin = true;
    atlas::jni::host_frame::folders_from_jvm(env, request);
    const atlas::jni::host_frame::Result result = atlas::jni::host_frame::draw(request);
    last_error = result.error;
    last_notes = result.device + "; " + result.notes + "; passes " + result.passes;
    if (!result.error.empty()) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(result.twin.size()));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(result.twin.size()),
                                reinterpret_cast<const jbyte*>(result.twin.data())); // NOLINT
    }
    return out;
}

/// The frame's counts over render packs (`id=path`, in lookup order; `active` the one to draw, or empty) at a camera
/// state and a theme block, drawn until the world settles, and the fragments its overdraw mode shades: a `pass`,
/// `kind` and `total` line each (host_frame::Result::counters), then `fse opaque <x> translucent <x>`; null on a
/// failure, named by lastError.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_stats(JNIEnv* env, jclass /*unused*/,
                                                                            jobjectArray packs, jstring active,
                                                                            jobject camera, jobject theme,
                                                                            jboolean full) {
    atlas::jni::host_frame::Request request;
    request.packs = packs_of(env, packs);
    request.active = active != nullptr ? string_of(env, active) : std::string();
    request.camera = bytes_of(env, camera);
    if (env->ExceptionCheck() == JNI_TRUE) return nullptr;
    request.theme = bytes_of(env, theme);
    if (env->ExceptionCheck() == JNI_TRUE) return nullptr;
    request.full = full == JNI_TRUE;
    request.pixels = false;
    request.overdraw = true;
    atlas::jni::host_frame::folders_from_jvm(env, request);
    const atlas::jni::host_frame::Result result = atlas::jni::host_frame::draw(request);
    last_error = result.error;
    last_notes = result.device + "; " + result.notes + "; passes " + result.passes;
    if (!result.error.empty()) return nullptr;
    std::string out;
    for (const std::string& line : result.counters) out += line + "\n";
    out += std::format("fse opaque {:.6f} translucent {:.6f}\n", result.opaque_fse, result.translucent_fse);
    return text(env, out);
}

/// Why the last frame drawn on this thread failed ("" when it did not).
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_lastError(JNIEnv* env, jclass /*unused*/) {
    return text(env, last_error);
}

/// What the last frame drawn on this thread noted: the device and configuration, settling, the passes.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_frame_FrameHost_lastNotes(JNIEnv* env, jclass /*unused*/) {
    return text(env, last_notes);
}

} // extern "C"
