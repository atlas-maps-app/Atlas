// The likeness harness's candidate stage in the host build (plan-vector K4, D16): one case drawn offscreen through the
// map's own frame, read back with its owner map, for :mapcorecheck's MT/likeness tests on the JVM. It binds to the
// Kotlin object app.atlas.mapcorecheck.likeness.TestCases by JNI's names; nothing of it is in the phone's library.
//
// The frame is the map's (K7, jni/TestFrame.hpp): the frame builder with every registered pass, over the world of the
// case's render pack, at the camera state and the theme block Kotlin writes for the case as the app writes them
// (CameraWriter, ThemeBlock), drawn on the host's Vulkan (lavapipe on this Mac and on CI) until the world settles. The
// colour depth (plan-vector D5, "Colour depth"): the case takes the frame's colour as the phone does, 10 bits a channel
// where the device draws the frame in them (lavapipe does), and gpu::Offscreen::read gives it back at 8 bits
// exactly (each channel at its nearest level), so the gates see 8 bits. The owner map is the frame drawn again in the
// owner mode at one sample: each pixel's latest painter slot, its item's OSM owner (FrameBuilder::owner_map); the
// ground's slots have none yet.
//
// The case buffer (256 bytes, little-endian; MT/likeness/Candidate.kt writes it): 0 magic "LKC1", 4 version, 8 width
// px, 12 height px, 16 density (f64), 24 clear colour (ARGB), 28 mode (0 structural, 1 full, 2 effect), 32 flags (bit
// 0: engine mode), 36 moment kind (0 named, 1 live), 40 lat, 48 lon, 56 zoom, 64 bearing, 72 map pitch, 80 principal
// point in dp (f64 each), 88 width dp, 92 height dp, 96 the moment's name or instant (64 bytes), 160 the pack (64
// bytes). The camera and the moment the frame draws are the camera state's and the theme block's; the case gives the
// size, the clear colour, the mode and the pack. The packs are files in the folder TestCases.tiles names: the case's
// pack `bundled` is the app's bundled Westerkerk (amsterdam.atlas), any other `<name>.atlas`.
#include "frame/PassRegistry.hpp"
#include "jni/TestFrame.hpp"

#include <jni.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <expected>
#include <format>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

/// The case buffer's layout version; MT/likeness/Candidate.kt's TestCases.ABI. Version 2: the camera state and the
/// theme block come beside the case.
constexpr jint case_abi = 2;
constexpr std::size_t case_bytes = 256;
constexpr std::uint32_t case_magic = 0x31434B4C; // "LKC1"

/// What render() filled, as bits of its result.
constexpr jint filled_pixels = 1;
constexpr jint filled_owners = 2;

/// render()'s errors.
enum class Failure : std::int8_t { bad_case = -1, no_device = -2, device = -3, memory = -4 };

/// One case, as the buffer gives it (what the frame does not take from the camera state and the theme block).
struct Case {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    double density = 0;
    std::uint32_t clear_argb = 0;
    std::uint32_t mode = 0;
    std::string pack;
};

template <class T>
[[nodiscard]] T read_le(std::span<const std::byte> b, std::size_t at) {
    std::array<std::byte, sizeof(T)> raw{};
    std::memcpy(raw.data(), b.data() + at, sizeof(T));
    if constexpr (std::endian::native == std::endian::big) {
        std::ranges::reverse(raw);
    }
    return std::bit_cast<T>(raw);
}

[[nodiscard]] std::expected<Case, std::string> read_case(std::span<const std::byte> b) {
    if (b.size() < case_bytes) {
        return std::unexpected(std::format("a case is {} bytes, not {}", case_bytes, b.size()));
    }
    if (read_le<std::uint32_t>(b, 0) != case_magic) {
        return std::unexpected(std::string("not a likeness case (magic)"));
    }
    if (const auto v = read_le<std::uint32_t>(b, 4); v != static_cast<std::uint32_t>(case_abi)) {
        return std::unexpected(std::format("a case of layout {}, this library reads {}", v, case_abi));
    }
    Case c{.width = read_le<std::uint32_t>(b, 8),
           .height = read_le<std::uint32_t>(b, 12),
           .density = read_le<double>(b, 16),
           .clear_argb = read_le<std::uint32_t>(b, 24),
           .mode = read_le<std::uint32_t>(b, 28),
           .pack = {}};
    if (c.width == 0 || c.height == 0 || c.width > 16384 || c.height > 16384) {
        return std::unexpected(std::format("a frame of {} × {} px", c.width, c.height));
    }
    if (std::isnan(c.density) || c.density <= 0.25 || c.density >= 16.0) {
        return std::unexpected(std::format("a density of {}", c.density));
    }
    if (c.mode > 2) {
        return std::unexpected(std::format("mode {}", c.mode));
    }
    for (std::size_t i = 0; i < 64; ++i) {
        const auto ch = static_cast<char>(b[160 + i]);
        if (ch == '\0') break;
        c.pack.push_back(ch);
    }
    if (c.pack.empty() || c.pack.find_first_of("/\\") != std::string::npos || c.pack.starts_with('.')) {
        return std::unexpected(std::format("a pack named '{}'", c.pack));
    }
    return c;
}

/// The folder of render packs (TestCases.tiles), the last failure's words and the last device, per thread.
thread_local std::string tiles_dir;   // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): per thread
thread_local std::string last_error;  // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): per thread
thread_local std::string last_device; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): per thread

/// A direct ByteBuffer's bytes, or an empty span.
[[nodiscard]] std::span<std::byte> bytes_of(JNIEnv* env, jobject buffer) {
    void* p = buffer != nullptr ? env->GetDirectBufferAddress(buffer) : nullptr;
    const jlong n = buffer != nullptr ? env->GetDirectBufferCapacity(buffer) : -1;
    if (p == nullptr || n < 0) {
        return {};
    }
    return {static_cast<std::byte*>(p), static_cast<std::size_t>(n)};
}

[[nodiscard]] jint fail(Failure f, std::string why) {
    last_error = std::move(why);
    return static_cast<jint>(f);
}

/// A channel of an ARGB colour, 0 to 1.
[[nodiscard]] float channel(std::uint32_t argb, unsigned shift) {
    return static_cast<float>((argb >> shift) & 0xFFU) / 255.0F;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_abi(JNIEnv* /*env*/, jclass /*unused*/) {
    return case_abi;
}

/// The passes a case is drawn with, comma-separated: the registry's.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_passes(JNIEnv* env, jclass /*unused*/) {
    std::string names;
    for (const atlas::frame::Registration& r : atlas::frame::registrations()) {
        names += (names.empty() ? "" : ",") + std::string(r.name);
    }
    return env->NewStringUTF(names.c_str());
}

/// The folder the cases' render packs are in (the bundled tiles the packer makes: -Datlas.tiles).
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_tiles(JNIEnv* env, jclass /*unused*/,
                                                                            jstring dir) {
    const char* chars = env->GetStringUTFChars(dir, nullptr);
    tiles_dir = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(dir, chars);
}

/// Why the last render on this thread failed.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_lastError(JNIEnv* env, jclass /*unused*/) {
    return env->NewStringUTF(last_error.c_str());
}

/// The device the last case on this thread was drawn on, its configuration, and how the world settled.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_device(JNIEnv* env, jclass /*unused*/) {
    return env->NewStringUTF(last_device.c_str());
}

/// Draws a case (`case_buffer`, with the camera state and theme block Kotlin wrote for it) into `pixels` (R, G, B, A
/// rows) and, when given, its owner map into `owners` (a little-endian u64 a pixel); returns what it filled.
JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_render(JNIEnv* env, jclass /*unused*/,
                                                                             jobject case_buffer, jobject camera,
                                                                             jobject theme, jobject pixels,
                                                                             jobject owners) {
    const auto in = bytes_of(env, case_buffer);
    auto c = read_case(in);
    if (!c) {
        return fail(Failure::bad_case, c.error());
    }
    const std::size_t n = static_cast<std::size_t>(c->width) * c->height;
    const auto out = bytes_of(env, pixels);
    if (out.size() < n * 4) {
        return fail(Failure::bad_case,
                    std::format("the pixel buffer holds {} bytes, the frame needs {}", out.size(), n * 4));
    }
    const auto own = bytes_of(env, owners);
    if (owners != nullptr && own.size() < n * 8) {
        return fail(Failure::bad_case,
                    std::format("the owner buffer holds {} bytes, the frame needs {}", own.size(), n * 8));
    }
    if (tiles_dir.empty()) {
        return fail(Failure::bad_case, "no folder of render packs (TestCases.tiles)");
    }
    atlas::jni::host_frame::Request request;
    const std::string file = c->pack == "bundled" ? std::string("amsterdam") : c->pack;
    request.packs.emplace_back(c->pack, tiles_dir + "/" + file + ".atlas");
    request.active = c->pack;
    request.camera = bytes_of(env, camera);
    request.theme = bytes_of(env, theme);
    request.full = c->mode == 1;
    request.clear = {channel(c->clear_argb, 16), channel(c->clear_argb, 8), channel(c->clear_argb, 0), 1.0F};
    request.pixels = true;
    request.owners = owners != nullptr;
    atlas::jni::host_frame::folders_from_jvm(env, request);
    const atlas::jni::host_frame::Result result = atlas::jni::host_frame::draw(request);
    last_device = result.device + "; " + result.notes;
    if (!result.error.empty()) {
        const bool no_device = result.error.starts_with("no Vulkan") || result.error.starts_with("no device");
        return fail(no_device ? Failure::no_device : Failure::device, result.error);
    }
    if (result.width != c->width || result.height != c->height || result.rgba.size() != n * 4) {
        return fail(Failure::bad_case, std::format("the camera state's frame is {} × {} px, the case's {} × {}",
                                                   result.width, result.height, c->width, c->height));
    }
    std::memcpy(out.data(), result.rgba.data(), n * 4);
    jint filled = filled_pixels;
    if (owners != nullptr && result.owners.size() == n) {
        for (std::size_t i = 0; i < n; ++i) {
            const std::uint64_t k = result.owners[i];
            for (std::size_t b = 0; b < 8; ++b) own[(i * 8) + b] = static_cast<std::byte>((k >> (8U * b)) & 0xFFU);
        }
        filled |= filled_owners;
    }
    last_error.clear();
    return filled;
}

} // extern "C"
