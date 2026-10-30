// The app's map drawn on the host as the phone draws it (ENGINE.md, "On the user's phone first"), for :mapcorecheck's
// phone look tool (MT/likeness/PhoneLook.kt): the map frame the phone's Bridge drives (frame/MapFrame.hpp, through the
// stand-in's session, jni/Bridge.cpp), its packs listed with their kinds and numbers as the app lists them (PackList:
// windows the core takes into their frame cells' packs, render packs, the APK's), the screen's haze, vignette and grain
// and the painter switch as the Look settings hand them over, the moment's theme block and each frame's camera state as
// Kotlin writes them (CoreMoment, MapCameraSource), drawn into offscreen images the session made in the frame's colour
// (10 bits a channel where the device draws in them, as the phone's swapchain) on the host's Vulkan (lavapipe on this
// Mac and on CI) until the picture is complete, then read back at 8 bits a channel. The windows the map's world wants
// are given back, nearest first, so the tool takes them from their country's tiles as the phone's Windows does. They
// bind to the Kotlin object app.atlas.mapcorecheck.likeness.LookHost by JNI's names; nothing of them is in the phone's
// library.
#include "frame/MapFrame.hpp"
#include "gen/spirv/modules.hpp"
#include "gpu/Guard.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "jni/TestFrame.hpp"
#include "load/Window.hpp"
#include "standin/Session.hpp"
#include "style/CameraState.hpp"
#include "style/Theme.hpp"

#include <jni.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <format>
#include <memory>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace {

using namespace atlas;
namespace fs = std::filesystem;

/// The layout of this file's calls; MT/likeness/PhoneLook.kt's LookHost.ABI.
constexpr jint look_abi = 1;

thread_local std::string last_error; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): per thread

/// An offscreen window that keeps its images, to read the frames back.
class KeptWindow final : public standin::SessionWindow {
public:
    [[nodiscard]] gpu::Result<vk::raii::SurfaceKHR> surface(const gpu::Instance& /*instance*/) override {
        return vk::raii::SurfaceKHR{nullptr};
    }
    [[nodiscard]] gpu::Result<std::unique_ptr<gpu::Output>> output(const gpu::Device& device,
                                                                   vk::SurfaceKHR /*surface*/, vk::Extent2D wanted,
                                                                   const gpu::Output* /*old*/,
                                                                   gpu::Depth depth) override {
        auto images = gpu::Offscreen::create(device, wanted, 3, depth);
        if (!images) return std::unexpected(images.error());
        kept = images->get();
        return std::unique_ptr<gpu::Output>(std::move(*images));
    }
    gpu::Offscreen* kept = nullptr;
};

/// One map on the host: the started device, the map, the session that draws it, and its window's images. Declared in
/// the order they are made; they go in the reverse (the session lets go of the map before the map goes).
struct Look {
    gpu::Started started;
    std::unique_ptr<frame::MapFrame> map;
    std::unique_ptr<standin::Session> session;
    KeptWindow* window = nullptr;
    vk::Extent2D size{};

    Look() = default;
    Look(const Look&) = delete;
    Look& operator=(const Look&) = delete;
    Look(Look&&) = delete;
    Look& operator=(Look&&) = delete;
    ~Look() {
        if (session) {
            (void)session->map(nullptr);
            session->close();
        }
        session.reset();
        map.reset();
    }
};

[[nodiscard]] Look* look_of(jlong handle) {
    return reinterpret_cast<Look*>(handle); // NOLINT(performance-no-int-to-ptr): the handle open() gave
}

[[nodiscard]] std::span<const std::byte> bytes_of(JNIEnv* env, jobject buffer) {
    void* p = buffer != nullptr ? env->GetDirectBufferAddress(buffer) : nullptr;
    const jlong n = buffer != nullptr ? env->GetDirectBufferCapacity(buffer) : -1;
    if (p == nullptr || n < 0) return {};
    return {static_cast<const std::byte*>(p), static_cast<std::size_t>(n)};
}

[[nodiscard]] std::string string_of(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

/// Element `i` of a String[], as the Bridge reads its packs' ids and files.
[[nodiscard]] std::string string_at(JNIEnv* env, jobjectArray a, jsize i) {
    auto* s = reinterpret_cast<jstring>(env->GetObjectArrayElement(a, i));
    std::string out = string_of(env, s);
    env->DeleteLocalRef(s);
    return out;
}

[[nodiscard]] jstring java(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_abi(JNIEnv* /*env*/, jclass /*unused*/) {
    return look_abi;
}

/// Why the last call on this thread failed.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_lastError(JNIEnv* env, jclass /*unused*/) {
    return java(env, last_error);
}

/// The version of a window built now (load::window_version, MapCore.Window.version): its stage P's and its style's.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_windowVersion(JNIEnv* env, jclass /*unused*/) {
    return java(env, load::window_version());
}

/// A map of `width` × `height` px drawn as the phone's (its device started as the Bridge starts it, without the crash
/// guard; the pipeline cache and the noise fields under `cache`): a handle, or 0 with lastError set.
JNIEXPORT jlong JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_open(JNIEnv* env, jclass /*unused*/, jint width,
                                                                           jint height, jstring cache) {
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
        last_error = std::format("a map of {} × {} px", width, height);
        return 0;
    }
    const fs::path dir = string_of(env, cache);
    std::error_code ec;
    fs::create_directories(dir, ec);
    const std::uint64_t spirv = gpu::spirv_hash(spirv::hashes);
    auto started = gpu::start_guarded({.cache_file = dir / "pipelines.bin",
                                       .system = "host-phone-look",
                                       .build = "phone-look",
                                       .present = true,
                                       .spirv = spirv});
    if (!started) {
        last_error = "the device: " + started.error().reason;
        return 0;
    }
    auto look = std::make_unique<Look>();
    look->started = std::move(*started);
    // The passes' fonts from the repo's mapview assets, as the host frame takes them (the phone, from its assets).
    atlas::jni::host_frame::Request folders;
    atlas::jni::host_frame::folders_from_jvm(env, folders);
    look->map = std::make_unique<frame::MapFrame>(
        2, [] {},
        frame::MapOptions{.cache_dir = dir,
                          .resources =
                              folders.resources.empty() ? frame::Resources{} : frame::files_under(folders.resources)});
    look->session = std::make_unique<standin::Session>(
        look->started, standin::SessionOptions{.cache_file = dir / "pipelines.bin", .spirv = spirv});
    if (std::string why = look->session->map(look->map.get()); !why.empty()) {
        last_error = "the map: " + why;
        return 0;
    }
    auto window = std::make_unique<KeptWindow>();
    look->window = window.get();
    look->size = {.width = static_cast<std::uint32_t>(width), .height = static_cast<std::uint32_t>(height)};
    std::string why;
    if (!look->session->attach(std::move(window), look->size, why)) {
        last_error = "the window: " + why;
        return 0;
    }
    last_error.clear();
    return reinterpret_cast<jlong>(look.release()); // NOLINT(performance-no-int-to-ptr): the handle
}

/// The packs the app lists from now on (MapCore.packs: numbers, kinds as MapCore.PackRef's, ids and files): a line per
/// pack, as the Bridge's.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_packs(JNIEnv* env, jclass /*unused*/,
                                                                              jlong handle, jlongArray numbers,
                                                                              jintArray kinds, jobjectArray ids,
                                                                              jobjectArray paths) {
    Look* look = look_of(handle);
    const jsize n = env->GetArrayLength(numbers);
    std::vector<jlong> number(static_cast<std::size_t>(n));
    std::vector<jint> kind(static_cast<std::size_t>(n));
    env->GetLongArrayRegion(numbers, 0, n, number.data());
    env->GetIntArrayRegion(kinds, 0, n, kind.data());
    std::vector<frame::MapPack> packs;
    std::string refused;
    for (jsize i = 0; i < n; ++i) {
        const auto at = static_cast<std::size_t>(i);
        frame::MapPack p;
        p.number = static_cast<std::uint64_t>(number[at]);
        p.ref.id = string_at(env, ids, i);
        p.ref.path = string_at(env, paths, i);
        if (kind[at] < 0 || kind[at] > static_cast<jint>(tiles::PackKind::world_base) || p.ref.id.empty() ||
            number[at] <= 0) {
            refused += std::format("{}: not a pack the map can list\n", p.ref.id);
            continue;
        }
        p.ref.kind = static_cast<tiles::PackKind>(kind[at]);
        // the file's time and size as the app lists them (PackList.refs): a window built again is new bytes
        std::error_code ec;
        const auto bytes = fs::file_size(p.ref.path, ec);
        p.ref.length = ec ? 0 : bytes;
        const auto time = fs::last_write_time(p.ref.path, ec);
        p.ref.offset = ec ? 0 : static_cast<std::uint64_t>(time.time_since_epoch().count());
        packs.push_back(std::move(p));
    }
    return java(env, refused + look->map->set_packs(std::move(packs)));
}

/// The moment's theme from now on (a theme block as CoreMoment writes it): false where it does not read.
JNIEXPORT jboolean JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_theme(JNIEnv* env, jclass /*unused*/,
                                                                               jlong handle, jobject theme) {
    const auto block = style::read_theme_block(bytes_of(env, theme));
    if (!block) {
        last_error = std::string("the theme block: ") + style::name_of(block.error());
        return JNI_FALSE;
    }
    look_of(handle)->map->set_theme(*block);
    return JNI_TRUE;
}

/// The screen's haze, vignette and grain (MapCore.screenEffects) and the painter switch (MapCore.painterOrder).
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_screen(JNIEnv* /*env*/, jclass /*unused*/,
                                                                            jlong handle, jboolean haze,
                                                                            jboolean vignette, jboolean grain,
                                                                            jboolean painter) {
    Look* look = look_of(handle);
    look->map->set_screen(haze == JNI_TRUE, vignette == JNI_TRUE, grain == JNI_TRUE);
    look->map->set_painter(painter == JNI_TRUE);
}

/// Draws at `camera` (a camera state as MapCameraSource writes it) until the picture is complete and a few frames on,
/// as the phone's frame loop draws at rest, or until it cannot be with the packs listed (no tile under way and no fade:
/// windows it wants that no pack holds, which the tool takes and draws again with, or none within reach), or `seconds`
/// pass; "<frames> frames, complete <yes|no>, pack <drawn>; <tiles line>", or "" with lastError set.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_draw(JNIEnv* env, jclass /*unused*/,
                                                                             jlong handle, jobject camera,
                                                                             jdouble seconds) {
    Look* look = look_of(handle);
    const auto cam = style::read_camera_state(bytes_of(env, camera));
    if (!cam) {
        last_error = std::string("the camera state: ") + style::name_of(cam.error());
        return java(env, "");
    }
    frame::MapFrame& map = *look->map;
    standin::Session& session = *look->session;
    const auto until =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(static_cast<std::int64_t>(seconds * 1000));
    int frames = 0;
    int complete_frames = 0;
    while (std::chrono::steady_clock::now() < until) {
        const standin::Drawn d = session.draw(*cam, {});
        if (d.stale) session.remake();
        frames += d.drawn ? 1 : 0;
        complete_frames = map.complete() ? complete_frames + 1 : 0;
        // complete, and the fades over: a few frames more at rest
        if (complete_frames > 4 && !map.animating()) break;
        if (map.animating() || map.world() == nullptr) continue;
        map.world()->residency().wait_idle();
        // as far as it gets with these packs: nothing under way (windows wanted that are not listed, or none within the
        // finest level's reach)
        if (frames > 4 && !map.complete() && map.world()->residency().stats().pending == 0) break;
    }
    last_error.clear();
    return java(env, std::format("{} frames, complete {}, pack {}; {}", frames, map.complete() ? "yes" : "no",
                                 map.drawn_pack(), map.tiles_line()));
}

/// The windows the map's world wants built, nearest first (MapCore.wantedWindows), packed z << 48 | x << 24 | y.
JNIEXPORT jlongArray JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_wanted(JNIEnv* env, jclass /*unused*/,
                                                                                  jlong handle) {
    const std::vector<tiles::TileKey> keys = look_of(handle)->map->wanted_windows();
    std::vector<jlong> packed(keys.size());
    for (std::size_t i = 0; i < keys.size(); ++i) packed[i] = static_cast<jlong>(keys[i].packed());
    jlongArray out = env->NewLongArray(static_cast<jsize>(packed.size()));
    if (out != nullptr && !packed.empty())
        env->SetLongArrayRegion(out, 0, static_cast<jsize>(packed.size()), packed.data());
    return out;
}

/// The last frame drawn into `pixels` (width · height · 4 bytes: R, G, B, A rows at 8 bits a channel): false with
/// lastError set where it could not be read.
JNIEXPORT jboolean JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_read(JNIEnv* env, jclass /*unused*/,
                                                                              jlong handle, jobject pixels) {
    Look* look = look_of(handle);
    if (look->window == nullptr || look->window->kept == nullptr) {
        last_error = "no frame drawn yet";
        return JNI_FALSE;
    }
    auto read = look->window->kept->read(look->window->kept->latest());
    if (!read) {
        last_error = "the read back: " + read.error().text();
        return JNI_FALSE;
    }
    void* out = env->GetDirectBufferAddress(pixels);
    const jlong capacity = env->GetDirectBufferCapacity(pixels);
    if (out == nullptr || std::cmp_less(capacity, read->rgba.size())) {
        last_error = std::format("the pixel buffer holds {} bytes, the frame {}", capacity, read->rgba.size());
        return JNI_FALSE;
    }
    std::memcpy(out, read->rgba.data(), read->rgba.size());
    return JNI_TRUE;
}

/// Lets go of the map, its session and its device.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_likeness_LookHost_close(JNIEnv* /*env*/, jclass /*unused*/,
                                                                           jlong handle) {
    delete look_of(handle); // NOLINT(cppcoreguidelines-owning-memory): the handle open() released
}

} // extern "C"
