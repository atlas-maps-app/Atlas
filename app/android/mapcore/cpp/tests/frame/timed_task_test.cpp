// The render thread's timer without its descriptor (frame/TimedTask.hpp, platform/android/Looper.cpp's fallback): the
// loop's own wait ends at the task's time, so a task set ahead runs once and not before, and the loop does not spin
// meanwhile. Until part 2b's point 5 such a task was posted at once, and Bridge's task that arms itself again until its
// time (arm_timer) turned the render thread over and over: 1 s, or 3 s for a hold. On a clock the test moves.
#include "frame/TimedTask.hpp"

#include <doctest/doctest.h>

#include <cstdint>

using namespace atlas;

namespace {

constexpr std::int64_t ms = 1'000'000;

/// What the loop does each turn, on the test's clock: it waits the poll's timeout (exactly, as epoll does where nothing
/// else wakes it), and a turn's own work takes `work_ns`; then the due task runs. Bridge's task arms itself again where
/// its time has not come (arm_timer): `runs` counts its runs, `early` those before its time. The turns until the task
/// has run at its time, or `cap` where it never did.
struct Loop {
    frame::TimedTask timer;
    std::int64_t now = 0;
    int runs = 0;
    int early = 0;
    std::int64_t ran_at = -1;

    void arm(std::int64_t at) {
        timer.set(at, [this, at] {
            ++runs;
            if (now < at) {
                ++early;
                arm(at); // arm_timer: the other, where it is still to come
                return;
            }
            ran_at = now;
        });
    }

    int turns(std::int64_t work_ns, int cap) {
        for (int turn = 1; turn <= cap; ++turn) {
            const int timeout = timer.timeout_ms(now);
            if (timeout < 0) return turn - 1;
            now += timeout * ms + work_ns;
            if (auto task = timer.take(now)) task();
            if (ran_at >= 0) return turn;
        }
        return cap;
    }
};

} // namespace

TEST_CASE("timed task: the poll's timeout ends at the task's time, rounded up, and the task comes once at its time") {
    frame::TimedTask t;
    int ran = 0;
    CHECK(t.timeout_ms(0) == -1); // nothing waits
    t.set(200 * ms, [&] { ++ran; });
    CHECK(t.timeout_ms(0) == 200);
    CHECK(t.timeout_ms(50 * ms) == 150);
    CHECK(t.timeout_ms(199 * ms + ms / 2) == 1); // half a millisecond left: never a wake before the time by rounding
    // nothing before its time
    CHECK_FALSE(t.take(0));
    CHECK_FALSE(t.take(50 * ms));
    CHECK_FALSE(t.take(200 * ms - 1));
    CHECK(ran == 0);
    // at 200 ms once, then nothing waits
    auto task = t.take(200 * ms);
    REQUIRE(task);
    task();
    CHECK(ran == 1);
    CHECK_FALSE(t.take(200 * ms));
    CHECK_FALSE(t.take(300 * ms));
    CHECK(t.timeout_ms(200 * ms) == -1);
}

TEST_CASE("timed task: a time already passed gives 0 and the task once") {
    frame::TimedTask t;
    int ran = 0;
    t.set(100 * ms, [&] { ++ran; });
    CHECK(t.timeout_ms(150 * ms) == 0);
    auto task = t.take(150 * ms);
    REQUIRE(task);
    task();
    CHECK(ran == 1);
    CHECK_FALSE(t.take(150 * ms));
    CHECK(t.timeout_ms(150 * ms) == -1);
}

TEST_CASE("timed task: set again replaces the task and its time; called off, nothing waits") {
    frame::TimedTask t;
    int first = 0;
    int second = 0;
    t.set(200 * ms, [&] { ++first; });
    t.set(300 * ms, [&] { ++second; });
    CHECK(t.timeout_ms(0) == 300);
    CHECK_FALSE(t.take(200 * ms));
    auto task = t.take(300 * ms);
    REQUIRE(task);
    task();
    CHECK(first == 0);
    CHECK(second == 1);
    t.set(400 * ms, [&] { ++first; });
    t.cancel();
    CHECK(t.timeout_ms(0) == -1);
    CHECK_FALSE(t.take(500 * ms));
    CHECK(first == 0);
}

TEST_CASE("timed task: a loop that sleeps the timeout each turn takes one turn before Bridge's task runs at its time") {
    Loop loop;
    loop.arm(200 * ms);
    // a microsecond of the loop's own work a turn: today's rule (the task at once, armed again at once) would turn the
    // loop 200,000 times before the time came
    CHECK(loop.turns(1'000, 10'000) == 1);
    CHECK(loop.runs == 1);
    CHECK(loop.early == 0);
    CHECK(loop.ran_at >= 200 * ms);
    CHECK(loop.ran_at < 201 * ms);
    CHECK(loop.timer.timeout_ms(loop.now) == -1); // nothing waits after it
}
