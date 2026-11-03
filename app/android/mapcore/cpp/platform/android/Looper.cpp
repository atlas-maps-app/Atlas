// The render thread, Choreographer's vsync and the thread's timer (platform/android/Android.hpp, plan-vector D5.8, T8).
#if defined(__ANDROID__)

#include "base/log.hpp"
#include "platform/android/Android.hpp"

#include <android/choreographer.h>
#include <android/looper.h>

#include <algorithm>
#include <condition_variable>
#include <ctime>
#include <pthread.h>
#include <sys/eventfd.h>
#include <sys/resource.h>
#include <sys/timerfd.h>
#include <unistd.h>
#include <utility>

namespace atlas::platform {

namespace {

// API 33's vsync callback and its frame timelines, looked up at run time (the app's minSdk is 29).
using VsyncCallback = void (*)(const AChoreographerFrameCallbackData*, void*);
using PostVsyncCallback = void (*)(AChoreographer*, VsyncCallback, void*);
using GetFrameTime = std::int64_t (*)(const AChoreographerFrameCallbackData*);
using GetLength = std::size_t (*)(const AChoreographerFrameCallbackData*);
using GetTimeline = std::int64_t (*)(const AChoreographerFrameCallbackData*, std::size_t);

struct Timelines {
    PostVsyncCallback post = nullptr;
    GetFrameTime frame_time = nullptr;
    GetLength preferred = nullptr;
    GetTimeline present = nullptr;
    GetTimeline deadline = nullptr;
    GetLength count = nullptr; // the timelines' number (frame/Pacing.hpp chooses among them)

    [[nodiscard]] bool complete() const noexcept { return post && frame_time && preferred && present && deadline; }
};

const Timelines& timelines() {
    static const Timelines t = [] {
        Timelines s;
        if (api_level() < 33) return s;
        s.post = reinterpret_cast<PostVsyncCallback>(android_symbol("AChoreographer_postVsyncCallback")); // NOLINT
        s.frame_time = reinterpret_cast<GetFrameTime>(
            android_symbol("AChoreographerFrameCallbackData_getFrameTimeNanos")); // NOLINT
        s.preferred = reinterpret_cast<GetLength>(
            android_symbol("AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex")); // NOLINT
        s.present = reinterpret_cast<GetTimeline>(                                             // NOLINT
            android_symbol("AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos"));
        s.deadline = reinterpret_cast<GetTimeline>(
            android_symbol("AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos")); // NOLINT
        s.count = reinterpret_cast<GetLength>(
            android_symbol("AChoreographerFrameCallbackData_getFrameTimelinesLength")); // NOLINT
        return s;
    }();
    return t;
}

} // namespace

RenderLoop::RenderLoop(std::function<void()> on_start, std::function<void()> on_stop)
    : on_start_(std::move(on_start)), on_stop_(std::move(on_stop)), wake_fd_(::eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC)),
      timer_fd_(::timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC)) {
    std::mutex started_mutex;
    std::condition_variable started;
    bool ready = false;
    thread_ = std::jthread([&, this](const std::stop_token& stop) {
        {
            // Notified under the lock: the constructor's frame (the mutex, the condition) lives until it sees `ready`.
            const std::scoped_lock lock(started_mutex);
            ready = true;
            started.notify_one();
        }
        loop(stop);
    });
    std::unique_lock lock(started_mutex);
    started.wait(lock, [&] { return ready; });
}

RenderLoop::~RenderLoop() {
    stopping_ = true;
    thread_.request_stop();
    const std::uint64_t one = 1;
    if (wake_fd_ >= 0) (void)::write(wake_fd_, &one, sizeof one);
    if (thread_.joinable()) thread_.join();
    if (wake_fd_ >= 0) ::close(wake_fd_);
    if (timer_fd_ >= 0) ::close(timer_fd_);
}

void RenderLoop::loop(const std::stop_token& stop) {
    thread_id_ = std::this_thread::get_id();
    tid_ = ::gettid();
    (void)::pthread_setname_np(::pthread_self(), "atlas-render");
    // THREAD_PRIORITY_DISPLAY (-4), as Android's own render threads run (T8); a refusal leaves the default.
    if (::setpriority(PRIO_PROCESS, 0, -4) != 0) log::warn("vk render thread: display priority refused");
    looper_ = ALooper_prepare(0);
    ALooper_acquire(looper_);
    ALooper_addFd(looper_, wake_fd_, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, &RenderLoop::on_wake, this);
    if (timer_fd_ >= 0) {
        ALooper_addFd(looper_, timer_fd_, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, &RenderLoop::on_timer, this);
    } else {
        log::warn("vk render thread: no timer (timerfd refused), so its tasks run at once");
    }
    choreographer_ = AChoreographer_getInstance();
    if (on_start_) on_start_();
    while (!stop.stop_requested()) {
        (void)ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
    }
    drain_tasks();
    timer_task_ = nullptr;
    if (on_stop_) on_stop_();
    if (timer_fd_ >= 0) ALooper_removeFd(looper_, timer_fd_);
    ALooper_removeFd(looper_, wake_fd_);
    ALooper_release(looper_);
}

int RenderLoop::on_wake(int fd, int /*events*/, void* data) {
    std::uint64_t count = 0;
    (void)::read(fd, &count, sizeof count);
    static_cast<RenderLoop*>(data)->drain_tasks();
    return 1; // keep the descriptor registered
}

int RenderLoop::on_timer(int fd, int /*events*/, void* data) {
    // A timer set again or called off since it expired has nothing to read (timerfd_settime clears its expiries), as
    // where the vsync's callback called it off before this one ran on the same wake: its task is not this expiry's.
    std::uint64_t expiries = 0;
    if (::read(fd, &expiries, sizeof expiries) != static_cast<ssize_t>(sizeof expiries) || expiries == 0) return 1;
    auto* self = static_cast<RenderLoop*>(data);
    const std::function<void()> task = std::exchange(self->timer_task_, nullptr);
    if (task && !self->stopping_) task();
    return 1; // keep the descriptor registered
}

void RenderLoop::set_timer(std::int64_t at_ns, std::function<void()> task) {
    if (timer_fd_ < 0) {
        post(std::move(task));
        return;
    }
    timer_task_ = std::move(task);
    // A time that has passed expires at once; 0 would call the timer off.
    const std::int64_t at = std::max<std::int64_t>(at_ns, 1);
    itimerspec when{};
    when.it_value.tv_sec = static_cast<decltype(when.it_value.tv_sec)>(at / 1'000'000'000);
    when.it_value.tv_nsec = static_cast<decltype(when.it_value.tv_nsec)>(at % 1'000'000'000);
    if (::timerfd_settime(timer_fd_, TFD_TIMER_ABSTIME, &when, nullptr) != 0) post(std::exchange(timer_task_, nullptr));
}

void RenderLoop::cancel_timer() noexcept {
    timer_task_ = nullptr;
    if (timer_fd_ < 0) return;
    const itimerspec off{};
    (void)::timerfd_settime(timer_fd_, 0, &off, nullptr);
}

void RenderLoop::drain_tasks() {
    std::vector<std::function<void()>> tasks;
    {
        const std::scoped_lock lock(mutex_);
        tasks.swap(tasks_);
    }
    for (auto& task : tasks) task();
}

void RenderLoop::post(std::function<void()> task) {
    {
        const std::scoped_lock lock(mutex_);
        tasks_.push_back(std::move(task));
    }
    const std::uint64_t one = 1;
    (void)::write(wake_fd_, &one, sizeof one);
}

void RenderLoop::run(std::function<void()> task) {
    if (on_thread()) {
        task();
        return;
    }
    std::mutex done_mutex;
    std::condition_variable done;
    bool finished = false;
    post([&] {
        task();
        {
            const std::scoped_lock lock(done_mutex);
            finished = true;
        }
        done.notify_one();
    });
    std::unique_lock lock(done_mutex);
    done.wait(lock, [&] { return finished; });
}

void RenderLoop::request_vsync(std::function<void(const Vsync&)> on_vsync) {
    vsync_ = std::move(on_vsync);
    if (vsync_pending_ || stopping_) return;
    vsync_pending_ = true;
    const Timelines& t = timelines();
    if (t.complete()) {
        t.post(choreographer_, &RenderLoop::on_vsync33, this);
    } else {
        AChoreographer_postFrameCallback64(choreographer_, &RenderLoop::on_frame64, this);
    }
}

void RenderLoop::on_frame64(std::int64_t frame_ns, void* data) {
    auto* self = static_cast<RenderLoop*>(data);
    self->vsync_pending_ = false;
    if (self->vsync_ && !self->stopping_) self->vsync_(Vsync{.frame_ns = frame_ns, .present_ns = 0, .deadline_ns = 0});
}

void RenderLoop::on_vsync33(const AChoreographerFrameCallbackData* data, void* self_data) {
    auto* self = static_cast<RenderLoop*>(self_data);
    self->vsync_pending_ = false;
    const Timelines& t = timelines();
    Vsync v;
    v.frame_ns = t.frame_time(data);
    const std::size_t preferred = t.preferred(data);
    v.present_ns = t.present(data, preferred);
    v.deadline_ns = t.deadline(data, preferred);
    if (t.count != nullptr) {
        const std::size_t n = std::min(t.count(data), max_timelines);
        for (std::size_t i = 0; i < n; ++i) {
            v.presents.at(i) = t.present(data, i);
            v.deadlines.at(i) = t.deadline(data, i);
        }
        v.timelines = static_cast<std::uint32_t>(n);
        v.preferred = static_cast<std::uint32_t>(preferred);
    }
    if (self->vsync_ && !self->stopping_) self->vsync_(v);
}

} // namespace atlas::platform

#endif
