// The noise fields' test entry points in the host build (plan-vector K17a), for :mapcorecheck's MT/fx tests on the
// JVM: a block of WebKit's turbulence by either path, a size's kept fields, and VK facts' fields line. They bind to the
// Kotlin object app.atlas.mapcorecheck.fx.FieldHost by JNI's names; nothing of them is in the phone's library.
#include "fx/Fields.hpp"
#include "fx/Noise.hpp"

#include <jni.h>

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace {

using namespace atlas::fx;

/// The layout version of these entry points; FieldHost refuses another.
constexpr jint test_fields_abi = 1;

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_fx_FieldHost_abi(JNIEnv*, jclass) { return test_fields_abi; }

/// The noise of a block (fill_lanes when `lanes`, else fill) as the port packs it: a r g b ints, rows from the top.
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_fx_FieldHost_noise(
    JNIEnv* env, jclass, jfloat seed, jfloat base_x, jfloat base_y, jint octaves, jboolean fractal, jfloat scale_x,
    jfloat scale_y, jint left, jint top, jint width, jint height, jint need, jboolean lanes) {
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || need < 1 || need > 15) return nullptr;
    const Lattice lattice(seed);
    const Turbulence t{.seed = seed, .base_x = base_x, .base_y = base_y, .octaves = octaves, .fractal = fractal != 0};
    const Block block{.left = left, .top = top, .width = width, .height = height};
    std::vector<Argb> out(static_cast<std::size_t>(width) * static_cast<std::size_t>(height));
    const auto mask = static_cast<std::uint8_t>(need);
    if (lanes != 0) {
        fill_lanes(lattice, t, grid_of(scale_x, scale_y), block, mask, out, static_cast<std::size_t>(width));
    } else {
        fill(lattice, t, grid_of(scale_x, scale_y), block, mask, out, static_cast<std::size_t>(width));
    }
    jintArray a = env->NewIntArray(static_cast<jsize>(out.size()));
    if (a == nullptr) return nullptr;
    env->SetIntArrayRegion(a, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jint*>(out.data()));
    return a;
}

/// The five fields of a `width` × `height` frame at filter scale `scale`, made on `threads` threads: field `field`'s
/// kept bytes in its own layout (its bytes a pixel, rows from the top), fields.inc's.
JNIEXPORT jbyteArray JNICALL Java_app_atlas_mapcorecheck_fx_FieldHost_kept(JNIEnv* env, jclass, jint width, jint height,
                                                                           jfloat scale, jint threads, jboolean lanes,
                                                                           jint field) {
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || field < 0 ||
        std::cmp_greater_equal(field, field_count) || !(scale > 0.0F)) {
        return nullptr;
    }
    const FieldSize size{
        .width = static_cast<std::uint32_t>(width), .height = static_cast<std::uint32_t>(height), .scale = scale};
    const auto fields = make_fields(size, MakeOptions{.threads = static_cast<unsigned>(threads), .lanes = lanes != 0});
    const std::vector<std::uint8_t> bytes = fields->bytes_of(static_cast<Field>(field));
    jbyteArray a = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (a == nullptr) return nullptr;
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return a;
}

/// VK facts' `fields …` line for a frame of `width` × `height` px at filter scale `scale`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_fx_FieldHost_line(JNIEnv* env, jclass, jint width, jint height,
                                                                        jfloat scale, jint threads) {
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || !(scale > 0.0F)) return nullptr;
    const std::string line = fields_line(FieldSize{.width = static_cast<std::uint32_t>(width),
                                                   .height = static_cast<std::uint32_t>(height),
                                                   .scale = scale},
                                         static_cast<unsigned>(threads));
    return env->NewStringUTF(line.c_str());
}

} // extern "C"
