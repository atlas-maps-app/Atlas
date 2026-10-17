// The APK's assets as ranges of the APK (platform/android/Android.hpp): an uncompressed asset (the bundled render
// tiles are stored so, app/build.gradle.kts) is mapped where it lies, without a copy (D3).
#if defined(__ANDROID__)

#include "platform/android/Android.hpp"

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

namespace atlas::platform {

AAssetManager* asset_manager(JNIEnv* env, jobject assets) noexcept {
    return assets == nullptr ? nullptr : AAssetManager_fromJava(env, assets);
}

std::optional<AssetRange> asset_range(AAssetManager* assets, const char* name) noexcept {
    if (assets == nullptr) return std::nullopt;
    AAsset* asset = AAssetManager_open(assets, name, AASSET_MODE_RANDOM);
    if (asset == nullptr) return std::nullopt;
    AssetRange range;
    off64_t start = 0;
    off64_t length = 0;
    range.fd = AAsset_openFileDescriptor64(asset, &start, &length);
    range.offset = start;
    range.length = length;
    AAsset_close(asset);
    if (range.fd < 0) return std::nullopt;
    return range;
}

} // namespace atlas::platform

#endif
