// The library's JNI_OnLoad (plan-vector T9): RegisterNatives for app.atlas.mapcore.Native once its ABI field equals the
// library's, so an APK built against another core fails at load (System.loadLibrary throws), never at draw. The host
// build is loaded by other test classes too (the style and likeness hosts); where Native is not on the class path,
// nothing is registered and the load goes on.
#include "base/log.hpp"
#include "jni/Bridge.hpp"

#include <format>

namespace atlas::jni {

std::string register_facade(JNIEnv* env, jclass cls) {
    jfieldID field = env->GetStaticFieldID(cls, "ABI", "I");
    if (field == nullptr || env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return "the façade class has no static int ABI";
    }
    const jint app = env->GetStaticIntField(cls, field);
    if (app != abi) return std::format("the app's façade is ABI {}, this core is ABI {}", app, abi);
    const std::span<const JNINativeMethod> methods = facade_methods();
    if (env->RegisterNatives(cls, methods.data(), static_cast<jint>(methods.size())) != JNI_OK) {
        env->ExceptionClear();
        return "RegisterNatives failed (a native's signature does not match the façade's)";
    }
    return {};
}

} // namespace atlas::jni

extern "C" [[gnu::visibility("default")]] jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR; // NOLINT
    jclass cls = env->FindClass(atlas::jni::native_class);
    if (cls == nullptr) {
        // Not the façade's load (a test class loading the host build): nothing to register.
        env->ExceptionClear();
        return JNI_VERSION_1_6;
    }
    const std::string refused = atlas::jni::register_facade(env, cls);
    env->DeleteLocalRef(cls);
    if (!refused.empty()) {
        atlas::log::error("vk abi: {}", refused);
        return JNI_ERR; // System.loadLibrary throws UnsatisfiedLinkError: the core refuses this app
    }
    return JNI_VERSION_1_6;
}
