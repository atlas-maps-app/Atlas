// The window of a Surface, the clock and libandroid's later symbols (platform/android/Android.hpp).
#if defined(__ANDROID__)

#include "platform/android/Android.hpp"

#include <android/api-level.h>
#include <android/native_window_jni.h>

#include <ctime>
#include <dlfcn.h>

namespace atlas::platform {

int api_level() noexcept {
    static const int level = android_get_device_api_level();
    return level;
}

void* android_symbol(const char* name) noexcept {
    static void* const library = ::dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    return library == nullptr ? nullptr : ::dlsym(library, name);
}

std::int64_t now_ns() noexcept {
    timespec t{};
    ::clock_gettime(CLOCK_MONOTONIC, &t);
    return std::int64_t{t.tv_sec} * 1'000'000'000 + t.tv_nsec;
}

Window::Window(JNIEnv* env, jobject surface) noexcept
    : window_(surface != nullptr ? ANativeWindow_fromSurface(env, surface) : nullptr) {}

Window& Window::operator=(Window&& other) noexcept {
    if (this != &other) {
        if (window_ != nullptr) ANativeWindow_release(window_);
        window_ = std::exchange(other.window_, nullptr);
    }
    return *this;
}

Window::~Window() {
    if (window_ != nullptr) ANativeWindow_release(window_);
}

std::int32_t Window::width() const noexcept { return window_ == nullptr ? 0 : ANativeWindow_getWidth(window_); }
std::int32_t Window::height() const noexcept { return window_ == nullptr ? 0 : ANativeWindow_getHeight(window_); }

bool Window::set_frame_rate(float fps) const noexcept {
    using SetFrameRate = std::int32_t (*)(ANativeWindow*, float, std::int8_t);
    static const auto set = reinterpret_cast<SetFrameRate>(android_symbol("ANativeWindow_setFrameRate")); // NOLINT
    if (set == nullptr || window_ == nullptr) return false;
    return set(window_, fps, 0 /* ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT */) == 0;
}

} // namespace atlas::platform

#endif
