// The thermal state (platform/android/Android.hpp, plan-vector D17).
#if defined(__ANDROID__)

#include "platform/android/Android.hpp"

#include <cmath>
#include <limits>

namespace atlas::platform {

namespace {

struct ThermalApi {
    void* (*acquire)() = nullptr;
    void (*release)(void*) = nullptr;
    int (*status)(void*) = nullptr;
    float (*headroom)(void*, int) = nullptr;
};

const ThermalApi& api() {
    static const ThermalApi a = [] {
        ThermalApi t;
        if (api_level() < 30) return t;
        t.acquire = reinterpret_cast<void* (*)()>(android_symbol("AThermal_acquireManager"));            // NOLINT
        t.release = reinterpret_cast<void (*)(void*)>(android_symbol("AThermal_releaseManager"));        // NOLINT
        t.status = reinterpret_cast<int (*)(void*)>(android_symbol("AThermal_getCurrentThermalStatus")); // NOLINT
        if (api_level() >= 31)
            t.headroom =
                reinterpret_cast<float (*)(void*, int)>(android_symbol("AThermal_getThermalHeadroom")); // NOLINT
        return t;
    }();
    return a;
}

} // namespace

Thermal::Thermal() noexcept {
    if (api().acquire != nullptr) manager_ = api().acquire();
}

Thermal::~Thermal() {
    if (manager_ != nullptr && api().release != nullptr) api().release(manager_);
}

float Thermal::headroom(int seconds) const noexcept {
    if (manager_ == nullptr || api().headroom == nullptr) return std::numeric_limits<float>::quiet_NaN();
    return api().headroom(manager_, seconds);
}

int Thermal::status() const noexcept {
    if (manager_ == nullptr || api().status == nullptr) return -1;
    return api().status(manager_);
}

} // namespace atlas::platform

#endif
