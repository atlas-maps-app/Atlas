// The façade's test entry points in the host build (plan-vector K1): the ABI check of JNI_OnLoad run against a class of
// the test's choosing, so :mapcorecheck's FacadeTest proves a façade of another ABI is refused at load. They bind to
// app.atlas.mapcorecheck.facade.FacadeHost by JNI's names; nothing of them is in the phone's library.
#include "jni/Bridge.hpp"

#include <jni.h>

#include <string>

extern "C" [[gnu::visibility("default")]] JNIEXPORT jstring JNICALL
Java_app_atlas_mapcorecheck_facade_FacadeHost_registerInto(JNIEnv* env, jclass /*cls*/, jclass target) {
    const std::string refused = atlas::jni::register_facade(env, target);
    return env->NewStringUTF(refused.c_str());
}

extern "C" [[gnu::visibility("default")]] JNIEXPORT jint JNICALL
Java_app_atlas_mapcorecheck_facade_FacadeHost_coreAbi(JNIEnv* /*env*/, jclass /*cls*/) {
    return atlas::jni::abi;
}
