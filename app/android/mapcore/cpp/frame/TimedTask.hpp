// The render thread's one timer where it has no timer descriptor (platform/android/Looper.cpp: timerfd refused, or its
// set failed): the task and its time, the timeout the loop's poll waits, and the task handed out once its time has
// come. Until part 2b's point 5 such a task was posted at once, and Bridge's task that arms itself again (arm_timer)
// spun the render thread until its time: 1 s, or 3 s for a hold. Portable and pure, so the hosts test it on a clock
// they move; the loop asks it before and after each poll. The descriptor stays the first way: it is exact to the
// nanosecond, where a millisecond's rounding up here can cost a ready frame its vsync.
#pragma once

#include <cstdint>
#include <functional>
#include <limits>
#include <utility>

namespace atlas::frame {

/// One task at one time (now_ns's clock): set again replaces it, call off drops it.
class TimedTask {
public:
    /// `task` at `at_ns`, in place of a task set before that has not been handed out.
    void set(std::int64_t at_ns, std::function<void()> task) {
        at_ns_ = at_ns;
        task_ = std::move(task);
    }
    /// Drops the task, where one is set and has not been handed out.
    void cancel() noexcept { task_ = nullptr; }

    /// The poll's timeout at `now_ns`, in whole milliseconds rounded up, so that the loop never wakes before the time
    /// by rounding: -1 where nothing waits, 0 where the time has passed.
    [[nodiscard]] int timeout_ms(std::int64_t now_ns) const noexcept {
        if (!task_) return -1;
        if (now_ns >= at_ns_) return 0;
        const std::int64_t ms = ((at_ns_ - now_ns) + (ns_per_ms - 1)) / ns_per_ms;
        return ms > std::numeric_limits<int>::max() ? std::numeric_limits<int>::max() : static_cast<int>(ms);
    }

    /// The task, once, where its time has come at `now_ns`; else none.
    [[nodiscard]] std::function<void()> take(std::int64_t now_ns) {
        if (!task_ || now_ns < at_ns_) return {};
        return std::exchange(task_, nullptr);
    }

private:
    static constexpr std::int64_t ns_per_ms = 1'000'000;
    std::int64_t at_ns_ = 0;
    std::function<void()> task_;
};

} // namespace atlas::frame
