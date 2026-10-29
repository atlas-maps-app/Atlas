// Pacing by the frame's own time (frame/Pacing.hpp, ENGINE.md gain 15): which of Choreographer's frame timelines a
// frame asks to be presented at, at 120 Hz with a frame every second vsync and at 60 Hz with one every vsync.
#include "frame/Pacing.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstdint>

using namespace atlas;

namespace {

constexpr std::int64_t ms = 1'000'000;
constexpr std::int64_t period120 = 8'333'333;

/// Timelines at a 120 Hz display from vsync `t0`: the first presents two periods on, each deadline 1 ms before the
/// vsync ahead of its presentation (as the platform's are).
[[nodiscard]] std::array<frame::Timeline, 5> timelines_at(std::int64_t t0) {
    std::array<frame::Timeline, 5> t{};
    for (std::size_t i = 0; i < t.size(); ++i) {
        const std::int64_t present = t0 + (static_cast<std::int64_t>(i) + 2) * period120;
        t[i] = {.present_ns = present, .deadline_ns = present - period120 - ms};
    }
    return t;
}

} // namespace

TEST_CASE("pacing: without measured work, the preferred timeline") {
    frame::Pacer p;
    const auto t = timelines_at(0);
    CHECK(p.work_ns() == 0);
    CHECK(p.choose(t, 1, 0, 2 * period120, period120) == 1);
    CHECK(p.last_present_ns() == t[1].present_ns);
    // fewer than `least` frames measured: still untrusted
    for (std::size_t i = 0; i + 1 < frame::Pacer::least; ++i) p.add_work(20 * ms);
    CHECK(p.work_ns() == 0);
    p.add_work(0); // not counted
    p.add_work(-5);
    CHECK(p.work_ns() == 0);
}

TEST_CASE("pacing: the work's 90th percentile and margin choose the first timeline whose deadline leaves it") {
    frame::Pacer p;
    for (std::size_t i = 0; i < frame::Pacer::window; ++i) p.add_work(4 * ms); // fast frames
    CHECK(p.work_ns() == 4 * ms + frame::Pacer::margin_ns);
    const auto t = timelines_at(0);
    // the preferred one (index 0) leaves 7.3 ms: 5 ms of work fits
    CHECK(p.choose(t, 0, 0, 0, period120) == 0);
    // the GPU in the low teens: 14 ms of work, 15 with the margin, needs the deadline at 15.7 ms (index 1)
    frame::Pacer slow;
    for (std::size_t i = 0; i < frame::Pacer::window; ++i) slow.add_work(14 * ms);
    CHECK(slow.choose(t, 0, 0, 0, period120) == 1);
    // one slow frame in ten is the percentile's: a stray slower frame does not move every frame later
    frame::Pacer mixed;
    for (std::size_t i = 0; i < frame::Pacer::window; ++i) mixed.add_work(i % 16 == 0 ? 30 * ms : 5 * ms);
    CHECK(mixed.work_ns() == 5 * ms + frame::Pacer::margin_ns);
    // and nothing fits: the last timeline
    frame::Pacer stuck;
    for (std::size_t i = 0; i < frame::Pacer::window; ++i) stuck.add_work(100 * ms);
    CHECK(stuck.choose(t, 0, 0, 0, period120) == t.size() - 1);
}

TEST_CASE("pacing: the cadence keeps a frame every second refresh at 120 Hz, and a rest forgets it") {
    frame::Pacer p;
    for (std::size_t i = 0; i < frame::Pacer::window; ++i) p.add_work(5 * ms);
    const std::int64_t cadence = 2 * period120;
    std::int64_t vsync = 0;
    std::int64_t last = 0;
    for (int frame = 0; frame < 12; ++frame) {
        const auto t = timelines_at(vsync);
        const std::size_t pick = p.choose(t, 0, vsync, cadence, period120);
        CHECK(pick == 0);
        if (frame > 0) CHECK(t[pick].present_ns - last == cadence); // evenly spaced, 16.7 ms apart
        last = t[pick].present_ns;
        vsync += cadence;
    }
    // A frame drawn a refresh after the last one's vsync would be shown 8.3 ms after it: the cadence takes the next.
    frame::Pacer q;
    for (std::size_t i = 0; i < frame::Pacer::window; ++i) q.add_work(5 * ms);
    const auto first = timelines_at(0);
    CHECK(q.choose(first, 0, 0, cadence, period120) == 0);
    const auto soon = timelines_at(period120);
    const std::size_t pick = q.choose(soon, 0, period120, cadence, period120);
    CHECK(pick == 1);
    CHECK(soon[pick].present_ns - first[0].present_ns == cadence);
    // At rest the cadence is forgotten: the next frame takes the first timeline that fits.
    q.rest();
    CHECK(q.last_present_ns() == 0);
    const auto after = timelines_at(2 * period120);
    CHECK(q.choose(after, 0, 2 * period120, cadence, period120) == 0);
}

TEST_CASE("pacing: no timelines, nothing chosen") {
    frame::Pacer p;
    CHECK(p.choose({}, 0, 0, 0, 0) == 0);
    CHECK(p.last_present_ns() == 0);
    // a preferred index beyond the list is the last
    const auto t = timelines_at(0);
    CHECK(p.choose(t, 9, 0, 0, period120) == t.size() - 1);
}
