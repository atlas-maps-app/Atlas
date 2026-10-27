// The style's test entry points in the host build (plan-vector K5), for :mapcorecheck's MT/style tests on the JVM:
// the theme block and the camera state as the core reads them, the shared layouts' tables, and light.slang run as C++
// (the twin) and as compute entries on the host's Vulkan device (style/HostProbe.hpp), in binary64 (the exact light)
// and in binary32 (light_f32). They bind to the Kotlin object app.atlas.mapcorecheck.style.StyleHost by JNI's names;
// nothing of them is in the phone's library.
#include "style/CameraState.hpp"
#include "style/HostProbe.hpp"
#include "style/Layouts.hpp"
#include "style/Light.hpp"
#include "style/Theme.hpp"

#include <jni.h>

#include <bit>
#include <cstddef>
#include <format>
#include <span>
#include <string>
#include <vector>

namespace {

using atlas::shaders::Theme;
using atlas::style::LightInput;
using atlas::style::LightInputF32;

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

void throw_state(JNIEnv* env, const std::string& why) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), why.c_str());
}

/// The shaders' themes made from `blocks` (256 bytes each) through the core's own reader; empty after throwing.
std::vector<Theme> themes_of(JNIEnv* env, jobject blocks) {
    const auto raw = bytes_of(env, blocks);
    if (env->ExceptionCheck() == JNI_TRUE) {
        return {};
    }
    if (raw.size() % atlas::style::theme_block_size != 0 || raw.empty()) {
        throw_state(env, "the theme blocks are not a whole number of 256-byte blocks");
        return {};
    }
    std::vector<Theme> themes;
    for (std::size_t at = 0; at < raw.size(); at += atlas::style::theme_block_size) {
        auto block = atlas::style::read_theme_block(raw.subspan(at, atlas::style::theme_block_size));
        if (!block) {
            throw_state(env, std::format("theme block {} refused: {}", at / atlas::style::theme_block_size,
                                         atlas::style::name_of(block.error())));
            return {};
        }
        themes.push_back(atlas::style::shader_theme(*block));
    }
    return themes;
}

/// The surfaces: eight doubles each (base rgb, the sun bit, the normal's xyz, the theme's index); false after throwing.
bool inputs_of(JNIEnv* env, jdoubleArray inputs, std::size_t n_themes, std::vector<LightInput>& out) {
    const jsize n = env->GetArrayLength(inputs);
    if (n % 8 != 0) {
        throw_state(env, "the inputs are not a whole number of eight doubles");
        return false;
    }
    std::vector<double> raw(static_cast<std::size_t>(n));
    env->GetDoubleArrayRegion(inputs, 0, n, raw.data());
    out.resize(raw.size() / 8);
    for (std::size_t i = 0; i < out.size(); ++i) {
        const std::span<const double> v = std::span(raw).subspan(8 * i, 8);
        if (!(v[7] >= 0.0) || v[7] >= static_cast<double>(n_themes) || (v[3] != 0.0 && v[3] != 1.0)) {
            throw_state(env, "an input names a theme that is not there, or has a sun bit that is not 0 or 1");
            return false;
        }
        out[i] = LightInput{.base = {v[0], v[1], v[2]},
                            .normal = {v[4], v[5], v[6]},
                            .lit = v[3] != 0.0 ? 1U : 0U,
                            .theme = static_cast<std::uint32_t>(v[7])};
    }
    return true;
}

/// The same surfaces as float vertex data, as a phone without shaderFloat64 gets them.
std::vector<LightInputF32> as_f32(const std::vector<LightInput>& in) {
    std::vector<LightInputF32> out(in.size());
    for (std::size_t i = 0; i < in.size(); ++i) {
        const LightInput& p = in[i];
        const atlas::shaders::float4 base = {static_cast<float>(p.base[0]), static_cast<float>(p.base[1]),
                                             static_cast<float>(p.base[2]), p.lit != 0U ? 1.0F : 0.0F};
        const atlas::shaders::float4 normal = {static_cast<float>(p.normal[0]), static_cast<float>(p.normal[1]),
                                               static_cast<float>(p.normal[2]), static_cast<float>(p.theme)};
        out[i] = LightInputF32{.base = base, .normal = normal};
    }
    return out;
}

/// `values` filled with three doubles per input (the Kotlin array must have that length) and the packed bytes as an
/// int array; null after throwing.
jintArray answer(JNIEnv* env, const std::vector<std::uint32_t>& bytes, const std::vector<double>& values,
                 jdoubleArray out) {
    if (static_cast<std::size_t>(env->GetArrayLength(out)) != values.size()) {
        throw_state(env, "the values array does not have three doubles for each input");
        return nullptr;
    }
    env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(values.size()), values.data());
    std::vector<jint> packed(bytes.size());
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        packed[i] = static_cast<jint>(bytes[i]);
    }
    jintArray array = env->NewIntArray(static_cast<jsize>(packed.size()));
    env->SetIntArrayRegion(array, 0, static_cast<jsize>(packed.size()), packed.data());
    return array;
}

std::string table(std::span<const atlas::style::Field> fields) {
    std::string out;
    for (const auto& f : fields) {
        out += std::format("{} {} {}\n", f.name, f.offset, f.size);
    }
    return out;
}

/// The device the last GPU run used, for the report.
std::string& last_device() {
    static std::string device;
    return device;
}

/// The probe's error as Kotlin sees it: "unavailable: ..." where there is no Vulkan here, else the failure.
std::string reason(const atlas::style::ProbeError& e) { return (e.unavailable ? "unavailable: " : "") + e.what; }

} // namespace

extern "C" {

/// The theme block as the core reads it (style/Theme.hpp's dump), or `error=<why>` when it refuses it.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_themeDump(JNIEnv* env, jclass /*unused*/,
                                                                                jobject block) {
    const auto raw = bytes_of(env, block);
    if (env->ExceptionCheck() == JNI_TRUE) {
        return nullptr;
    }
    const auto b = atlas::style::read_theme_block(raw);
    const std::string text = b ? atlas::style::dump(*b) : std::string("error=") + atlas::style::name_of(b.error());
    return env->NewStringUTF(text.c_str());
}

/// The camera state as the core reads it: `name=value` lines (doubles as their bits), or `error=<why>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_cameraDump(JNIEnv* env, jclass /*unused*/,
                                                                                 jobject state) {
    const auto raw = bytes_of(env, state);
    if (env->ExceptionCheck() == JNI_TRUE) {
        return nullptr;
    }
    const auto s = atlas::style::read_camera_state(raw);
    if (!s) {
        return env->NewStringUTF((std::string("error=") + atlas::style::name_of(s.error())).c_str());
    }
    auto bits = [](double v) { return std::format("{:016x}", std::bit_cast<std::uint64_t>(v)); };
    std::string out = std::format(
        "version={}\nflags={}\nsequence={}\nlatitude={}\nlongitude={}\nbearing={}\npitch={}\ndistance={}\n"
        "principal_x={}\nprincipal_y={}\ndensity={}\nwidth_px={}\nheight_px={}\nwidth_dp={}\nheight_dp={}\n"
        "pack_id={}\nprofile={}\ntheme_revision={}\nlabels_revision={}\noverlays_revision={}\nkeep_out_count={}\n",
        s->version, s->flags, s->sequence, bits(s->latitude), bits(s->longitude), bits(s->bearing), bits(s->pitch),
        bits(s->distance), bits(s->principal_x), bits(s->principal_y), bits(s->density), s->width_px, s->height_px,
        s->width_dp, s->height_dp, s->pack_id, static_cast<std::uint32_t>(s->profile), s->theme_revision,
        s->labels_revision, s->overlays_revision, s->keep_out_count);
    for (std::size_t i = 0; i < s->keep_outs().size(); ++i) {
        const auto& k = s->keep_outs()[i];
        out += std::format("keep_out.{}={:08x} {:08x} {:08x} {:08x} {:08x} {} {} {}\n", i,
                           std::bit_cast<std::uint32_t>(k.left), std::bit_cast<std::uint32_t>(k.top),
                           std::bit_cast<std::uint32_t>(k.right), std::bit_cast<std::uint32_t>(k.bottom),
                           std::bit_cast<std::uint32_t>(k.radius), k.blur16, static_cast<unsigned>(k.saturation),
                           static_cast<unsigned>(k.flags));
    }
    if (s->has(atlas::style::camera_flag::location)) {
        const auto& l = s->location;
        out += std::format("location={} {} {:08x} {:08x}\n", bits(l.latitude), bits(l.longitude),
                           std::bit_cast<std::uint32_t>(l.accuracy), std::bit_cast<std::uint32_t>(l.heading));
    }
    return env->NewStringUTF(out.c_str());
}

/// A shared layout's table, a line each: `name offset size` for theme_block, camera_state and shader_theme, `name
/// value` for constants.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_layout(JNIEnv* env, jclass /*unused*/,
                                                                             jstring which) {
    const char* w = env->GetStringUTFChars(which, nullptr);
    const std::string name = w != nullptr ? w : "";
    env->ReleaseStringUTFChars(which, w);
    std::string out;
    if (name == "theme_block") {
        out = table(atlas::style::theme_block_fields());
    } else if (name == "camera_state") {
        out = table(atlas::style::camera_state_fields());
    } else if (name == "shader_theme") {
        out = table(atlas::style::shader_theme_fields());
    } else if (name == "constants") {
        for (const auto& c : atlas::style::layout_constants()) {
            out += std::format("{} {}\n", c.name, c.value);
        }
    } else {
        throw_state(env, "no layout named " + name);
        return nullptr;
    }
    return env->NewStringUTF(out.c_str());
}

/// light.slang's exact light as C++ (the twin): per input, tohex packed r | g << 8 | b << 16, and into `values` the
/// light before rounding, three doubles per input.
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_lightTwin(JNIEnv* env, jclass /*unused*/,
                                                                                  jdoubleArray inputs, jobject blocks,
                                                                                  jdoubleArray values) {
    const std::vector<Theme> themes = themes_of(env, blocks);
    std::vector<LightInput> in;
    if (themes.empty() || !inputs_of(env, inputs, themes.size(), in)) {
        return nullptr;
    }
    std::vector<std::uint32_t> bytes(in.size());
    std::vector<double> v(3 * in.size());
    for (std::size_t i = 0; i < in.size(); ++i) {
        const LightInput& p = in[i];
        const atlas::shaders::double3 base = {p.base[0], p.base[1], p.base[2]};
        const atlas::shaders::double3 n = {p.normal[0], p.normal[1], p.normal[2]};
        const atlas::shaders::double3 c = atlas::shaders::light(themes[p.theme], base, n, p.lit != 0U);
        const atlas::shaders::uint3 b = atlas::shaders::tohex3(c);
        bytes[i] = b.x | (b.y << 8U) | (b.z << 16U);
        v[3 * i] = c.x;
        v[(3 * i) + 1] = c.y;
        v[(3 * i) + 2] = c.z;
    }
    return answer(env, bytes, v, values);
}

/// light.slang's exact light as a compute entry on the host's Vulkan device (lavapipe): the same results; throws with
/// the reason ("unavailable: ..." where there is no Vulkan device with shaderFloat64) when it cannot run, or when the
/// validation layer speaks.
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_lightGpu(JNIEnv* env, jclass /*unused*/,
                                                                                 jdoubleArray inputs, jobject blocks,
                                                                                 jdoubleArray values) {
    const std::vector<Theme> themes = themes_of(env, blocks);
    std::vector<LightInput> in;
    if (themes.empty() || !inputs_of(env, inputs, themes.size(), in)) {
        return nullptr;
    }
    auto out = atlas::style::run_light_probe(in, themes);
    if (!out) {
        throw_state(env, reason(out.error()));
        return nullptr;
    }
    last_device() = out->device;
    return answer(env, out->bytes, out->values, values);
}

/// light_f32 as C++ on the inputs rounded to float: per input, tohex_f32 packed, and its value (three per input).
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_lightTwinF32(JNIEnv* env, jclass /*unused*/,
                                                                                     jdoubleArray inputs,
                                                                                     jobject blocks,
                                                                                     jdoubleArray values) {
    const std::vector<Theme> themes = themes_of(env, blocks);
    std::vector<LightInput> in;
    if (themes.empty() || !inputs_of(env, inputs, themes.size(), in)) {
        return nullptr;
    }
    const std::vector<LightInputF32> f = as_f32(in);
    std::vector<std::uint32_t> bytes(in.size());
    std::vector<double> v(3 * in.size());
    for (std::size_t i = 0; i < f.size(); ++i) {
        const LightInputF32& p = f[i];
        const atlas::shaders::float3 c =
            atlas::shaders::light_f32(themes[in[i].theme], p.base.xyz, p.normal.xyz, p.base.w != 0.0F);
        const atlas::shaders::uint3 b = atlas::shaders::tohex3_f32(c);
        bytes[i] = b.x | (b.y << 8U) | (b.z << 16U);
        v[3 * i] = static_cast<double>(c.x);
        v[(3 * i) + 1] = static_cast<double>(c.y);
        v[(3 * i) + 2] = static_cast<double>(c.z);
    }
    return answer(env, bytes, v, values);
}

/// light_f32 as a compute entry on the host's Vulkan device, compiled as a phone would run it: the same results.
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_lightGpuF32(JNIEnv* env, jclass /*unused*/,
                                                                                    jdoubleArray inputs, jobject blocks,
                                                                                    jdoubleArray values) {
    const std::vector<Theme> themes = themes_of(env, blocks);
    std::vector<LightInput> in;
    if (themes.empty() || !inputs_of(env, inputs, themes.size(), in)) {
        return nullptr;
    }
    auto out = atlas::style::run_light_probe_f32(as_f32(in), themes);
    if (!out) {
        throw_state(env, reason(out.error()));
        return nullptr;
    }
    last_device() = out->device;
    std::vector<double> v(3 * in.size());
    for (std::size_t i = 0; i < in.size(); ++i) {
        v[3 * i] = static_cast<double>(out->values[i].x);
        v[(3 * i) + 1] = static_cast<double>(out->values[i].y);
        v[(3 * i) + 2] = static_cast<double>(out->values[i].z);
    }
    return answer(env, out->bytes, v, values);
}

/// The device the last GPU run used.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_gpuDevice(JNIEnv* env, jclass /*unused*/) {
    return env->NewStringUTF(last_device().c_str());
}

/// light_f32's bound against the port, in levels (style/Light.hpp).
JNIEXPORT jdouble JNICALL Java_app_atlas_mapcorecheck_style_StyleHost_lightF32Bound(JNIEnv* /*env*/,
                                                                                    jclass /*unused*/) {
    return atlas::shaders::light_f32_bound;
}

} // extern "C"
