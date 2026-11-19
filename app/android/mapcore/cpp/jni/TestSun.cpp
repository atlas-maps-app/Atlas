// The sun worker's test entry point in the host build (plan-vector K8), for :mapcorecheck's MT/sun/SunCheckTest on the
// JVM: the bundled check over a render pack, as the app's VK facts runs it, and the ground's check beside it (K9). It
// binds to the Kotlin object app.atlas.mapcorecheck.sun.SunHost by JNI's names; nothing of it is in the phone's
// library.
#include "geometry/Ground.hpp"
#include "sun/Buckets.hpp"
#include "tiles/Container.hpp"
#include "tiles/Source.hpp"

#include <jni.h>

#include <chrono>
#include <format>
#include <string>

namespace {

using namespace atlas::sun;
namespace tl = atlas::tiles;

/// The layout version of these entry points; SunHost refuses another.
constexpr jint test_sun_abi = 2;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring text(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_abi(JNIEnv*, jclass) { return test_sun_abi; }

/// The bundled check over the pack at `path`, as the app's VK facts runs it on the APK's tiles/amsterdam.atlas: its
/// line, then `hash <16 hex digits>` and `ms <time>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_bundled(JNIEnv* env, jclass, jstring path) {
    auto source = tl::MappedSource::open(string_of(env, path));
    if (!source) return text(env, "error=" + describe(source.error()));
    auto pack = tl::Pack::open(std::move(*source));
    if (!pack) return text(env, "error=" + describe(pack.error()));
    const auto t0 = std::chrono::steady_clock::now();
    const std::string line = sun_line(*pack);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    const auto h = bundled_check(*pack);
    return text(env, std::format("{}\nhash {:016x}\nms {:.1f}", line, h ? *h : 0, ms));
}

/// The ground's check over the pack at `path`, as the app's VK facts runs it: its line, then `hash <16 hex digits>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_ground(JNIEnv* env, jclass, jstring path) {
    auto source = tl::MappedSource::open(string_of(env, path));
    if (!source) return text(env, "error=" + describe(source.error()));
    auto pack = tl::Pack::open(std::move(*source));
    if (!pack) return text(env, "error=" + describe(pack.error()));
    const std::string line = atlas::geometry::ground_line(*pack);
    const auto h = atlas::geometry::ground_check(*pack);
    return text(env, std::format("{}\nhash {:016x}", line, h ? *h : 0));
}

} // extern "C"
