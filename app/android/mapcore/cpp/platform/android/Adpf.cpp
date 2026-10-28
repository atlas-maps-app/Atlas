// The ADPF hint sessions (platform/android/Android.hpp, plan-vector D17): the render thread's, and the tile workers'
// with their priority (ENGINE.md gain 8).
#if defined(__ANDROID__)

#include "base/log.hpp"
#include "platform/android/Android.hpp"

#include <format>
#include <pthread.h>
#include <string_view>
#include <sys/resource.h>
#include <unistd.h>

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
    // API 34: a session's threads; API 35: their preference for efficiency; API 36: a workload's increase.
    int (*set_threads)(void*, const pid_t*, std::size_t) = nullptr;
    int (*prefer_efficiency)(void*, bool) = nullptr;
    int (*increase)(void*, bool, bool, const char*) = nullptr;

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
            bind(h.prefer_efficiency, "APerformanceHint_setPreferPowerEfficiency");
        }
        if (api_level() >= 34) bind(h.set_threads, "APerformanceHint_setThreads");
        if (api_level() >= 36) bind(h.increase, "APerformanceHint_notifyWorkloadIncrease");
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

namespace {

/// The workers' priorities: Android's THREAD_PRIORITY_BACKGROUND, and the default for the frame's work.
constexpr int background_nice = 10;
constexpr int normal_nice = 0;
/// What a piece of the frame's work is held to: a frame at 60 Hz, so the clocks rise while a tile in view is missing.
constexpr std::int64_t worker_target_ns = 16'666'667;

/// The workers' session as this Android has it, for the log.
[[nodiscard]] std::string_view worker_session_kind(const HintApi& a) noexcept {
    if (a.increase != nullptr) return "ADPF 36 (efficiency, workload increases)";
    if (a.prefer_efficiency != nullptr) return "ADPF 35 (efficiency)";
    return "ADPF 33";
}

} // namespace

WorkerSession::~WorkerSession() {
    if (session_ != nullptr) api().close(session_);
}

void WorkerSession::joined() {
    const std::scoped_lock lock(mutex_);
    (void)::pthread_setname_np(::pthread_self(), "atlas-tiles");
    const pid_t tid = ::gettid();
    // At background priority until the frame waits on the workers (T8); this thread alone on Linux.
    (void)::setpriority(PRIO_PROCESS, 0, urgent_ ? normal_nice : background_nice);
    tids_.push_back(tid);
    // A session opened before this worker joined takes it in (API 34; before, it keeps the threads it was opened with).
    if (session_ != nullptr && api().set_threads != nullptr)
        (void)api().set_threads(session_, tids_.data(), tids_.size());
}

void WorkerSession::urgent(bool on) {
    const std::scoped_lock lock(mutex_);
    if (on == urgent_) return;
    urgent_ = on;
    for (const pid_t tid : tids_) {
        if (::setpriority(PRIO_PROCESS, static_cast<id_t>(tid), on ? normal_nice : background_nice) != 0 && on &&
            !warned_) {
            log::warn("vk tiles: a worker's normal priority refused");
            warned_ = true;
        }
    }
    if (session_ == nullptr) return; // opened by the first piece of the frame's work
    const HintApi& a = api();
    if (a.prefer_efficiency != nullptr) (void)a.prefer_efficiency(session_, !on);
    if (on && a.increase != nullptr) (void)a.increase(session_, true, false, "atlas tiles in view");
}

void WorkerSession::report(std::int64_t start_ns, std::int64_t ns) {
    (void)start_ns;
    const HintApi& a = api();
    std::vector<pid_t> tids;
    {
        const std::scoped_lock lock(mutex_);
        if (!urgent_ || ns <= 0) return;
        if (session_ != nullptr) {
            (void)a.report(session_, ns);
            return;
        }
        if (refused_ || opening_) return;
        opening_ = true;
        tids = tids_;
    }
    // The session asked for once, on a worker at the first piece of the frame's work (every worker has joined by then),
    // outside the lock: the render thread's changes of the frame's wait never wait for its making.
    void* manager = a.basic() ? a.get_manager() : nullptr;
    void* session = manager != nullptr && !tids.empty()
                        ? a.create_session(manager, tids.data(), tids.size(), worker_target_ns)
                        : nullptr;
    log::info("vk tiles: the workers' hint session: {}",
              session != nullptr ? worker_session_kind(a)
                                 : std::string_view(api_level() < 33 ? "none (API 29-32)" : "none (refused)"));
    const std::scoped_lock lock(mutex_);
    opening_ = false;
    session_ = session;
    refused_ = session == nullptr;
    if (session_ == nullptr) return;
    // the threads that joined meanwhile (API 34), and the frame's wait as it is now
    if (tids_.size() != tids.size() && a.set_threads != nullptr)
        (void)a.set_threads(session_, tids_.data(), tids_.size());
    if (a.prefer_efficiency != nullptr) (void)a.prefer_efficiency(session_, !urgent_);
    if (urgent_) {
        if (a.increase != nullptr) (void)a.increase(session_, true, false, "atlas tiles in view");
        (void)a.report(session_, ns);
    }
}

} // namespace atlas::platform

#endif
