// The ADPF hint session of the render thread (platform/android/Android.hpp, plan-vector D17).
#if defined(__ANDROID__)

#include "platform/android/Android.hpp"

#include <format>

namespace atlas::platform {

namespace {

struct HintApi {
    void* (*get_manager)() = nullptr;
    void* (*create_session)(void*, const std::int32_t*, std::size_t, std::int64_t) = nullptr;
    int (*report)(void*, std::int64_t) = nullptr;
    void (*close)(void*) = nullptr;
    // API 35: a work duration with the GPU's share.
    void* (*duration_create)() = nullptr;
    void (*duration_release)(void*) = nullptr;
    void (*set_start)(void*, std::int64_t) = nullptr;
    void (*set_total)(void*, std::int64_t) = nullptr;
    void (*set_cpu)(void*, std::int64_t) = nullptr;
    void (*set_gpu)(void*, std::int64_t) = nullptr;
    int (*report2)(void*, void*) = nullptr;

    [[nodiscard]] bool basic() const noexcept { return get_manager && create_session && report && close; }
    [[nodiscard]] bool gpu() const noexcept {
        return duration_create && duration_release && set_start && set_total && set_cpu && set_gpu && report2;
    }
};

template <class F>
void bind(F& f, const char* name) {
    f = reinterpret_cast<F>(android_symbol(name));
} // NOLINT

const HintApi& api() {
    static const HintApi a = [] {
        HintApi h;
        if (api_level() < 33) return h;
        bind(h.get_manager, "APerformanceHint_getManager");
        bind(h.create_session, "APerformanceHint_createSession");
        bind(h.report, "APerformanceHint_reportActualWorkDuration");
        bind(h.close, "APerformanceHint_closeSession");
        if (api_level() >= 35) {
            bind(h.duration_create, "AWorkDuration_create");
            bind(h.duration_release, "AWorkDuration_release");
            bind(h.set_start, "AWorkDuration_setWorkPeriodStartTimestampNanos");
            bind(h.set_total, "AWorkDuration_setActualTotalDurationNanos");
            bind(h.set_cpu, "AWorkDuration_setActualCpuDurationNanos");
            bind(h.set_gpu, "AWorkDuration_setActualGpuDurationNanos");
            bind(h.report2, "APerformanceHint_reportActualWorkDuration2");
        }
        return h;
    }();
    return a;
}

} // namespace

HintSession::~HintSession() {
    if (session_ != nullptr) api().close(session_);
}

bool HintSession::open(pid_t tid, std::int64_t target_ns) noexcept {
    const HintApi& a = api();
    if (!a.basic() || session_ != nullptr) return session_ != nullptr;
    void* manager = a.get_manager();
    if (manager == nullptr) return false;
    const std::int32_t tids[1] = {static_cast<std::int32_t>(tid)};
    session_ = a.create_session(manager, tids, 1, target_ns);
    gpu_ = session_ != nullptr && a.gpu();
    return session_ != nullptr;
}

void HintSession::report(std::int64_t start_ns, std::int64_t cpu_ns, std::int64_t gpu_ns) noexcept {
    if (session_ == nullptr || cpu_ns <= 0) return;
    const HintApi& a = api();
    if (gpu_ && gpu_ns > 0) {
        void* d = a.duration_create();
        if (d != nullptr) {
            a.set_start(d, start_ns);
            a.set_total(d, cpu_ns > gpu_ns ? cpu_ns : gpu_ns);
            a.set_cpu(d, cpu_ns);
            a.set_gpu(d, gpu_ns);
            (void)a.report2(session_, d);
            a.duration_release(d);
            return;
        }
    }
    (void)a.report(session_, cpu_ns);
}

std::string HintSession::describe() const {
    if (session_ == nullptr) return api_level() < 33 ? "none (API 29-32)" : "none (refused)";
    return gpu_ ? "ADPF 35 (CPU and GPU durations)" : "ADPF 33 (CPU durations)";
}

} // namespace atlas::platform

#endif
