// The frame loop's cadence (frame/Cadence.hpp, ENGINE.md gain 11): the display's period from the vsyncs' frame
// timelines, and the limit of 60 frames a second, in a loop run as jni/Bridge.cpp runs it.
#include "frame/Cadence.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <span>
#include <vector>

using namespace atlas;

namespace {

constexpr std::int64_t ms = 1'000'000;
constexpr std::int64_t period120 = 8'333'333;
constexpr std::int64_t period90 = 11'111'111;
constexpr std::int64_t period60 = 16'666'667;

/// The frame timelines' expected presentations at vsync `v` of a display of `period`: the first two periods on, each
/// a period after the last (as SurfaceFlinger lays them out).
[[nodiscard]] std::array<std::int64_t, 5> presents_at(std::int64_t v, std::int64_t period) {
    std::array<std::int64_t, 5> p{};
    for (std::size_t i = 0; i < p.size(); ++i) p[i] = v + ((static_cast<std::int64_t>(i) + 2) * period);
    return p;
}

/// What a run of the loop did.
struct Run {
    std::vector<std::int64_t> drawn;  // the vsyncs drawn for
    std::vector<std::int64_t> shown;  // when each frame was shown
    std::vector<std::int64_t> gaps;   // between the vsyncs the loop received
    std::vector<std::int64_t> period; // the cadence's period at each vsync
    int warm_asked = 0;
};

/// The frame loop on a display of `period`: at each vsync the cadence's word; where it draws, a frame of
/// `work[i % size]`, the next vsync asked for as the frame ends (the first after it), the frame shown at the first
/// refresh after it is done (FIFO, as soon as it is ready; the GPU's time left out); where it does not, the next vsync.
/// `timelines`: the vsyncs carry frame timelines (API 33). The vsyncs lie on the display's grid from 1 s.
[[nodiscard]] Run run(std::int64_t period, std::span<const std::int64_t> work, bool timelines, bool warm,
                      std::size_t frames) {
    frame::Cadence cadence;
    Run r;
    std::int64_t v = (1'000 * ms / period) * period;
    std::int64_t last = 0;
    while (r.shown.size() < frames) {
        const auto presents = presents_at(v, period);
        cadence.at_vsync(v, timelines ? std::span<const std::int64_t>(presents) : std::span<const std::int64_t>());
        if (last != 0) r.gaps.push_back(v - last);
        last = v;
        r.period.push_back(cadence.period_ns());
        const frame::Cadence::Step step = cadence.step([&] {
            ++r.warm_asked;
            return warm;
        });
        if (!cadence.due(v, step)) {
            v += period;
            continue;
        }
        cadence.drew(v, step);
        r.drawn.push_back(v);
        const std::int64_t end = v + work[r.shown.size() % work.size()];
        const std::int64_t next = ((end / period) + 1) * period;
        r.shown.push_back(next);
        v = next;
    }
    return r;
}

/// The mean of the intervals between the frames shown, from the `skip`th on.
[[nodiscard]] std::int64_t mean_interval(const Run& r, std::size_t skip) {
    const std::size_t n = r.shown.size() - 1 - skip;
    return (r.shown.back() - r.shown.at(skip)) / static_cast<std::int64_t>(n);
}

/// The gaps' mean as alpha 16 to 27 took it for the display's period: each gap within 4 to 50 ms moves it an eighth.
[[nodiscard]] std::int64_t gaps_mean(const Run& r) {
    std::int64_t mean = 0;
    for (const std::int64_t d : r.gaps) {
        if (d > 4 * ms && d < 50 * ms) mean = mean == 0 ? d : ((mean * 7) + d) / 8;
    }
    return mean;
}

/// Frames of 7 to 11 ms on the render thread, spread as the stutter review's (1.5 to 3 ms), in a fixed order.
constexpr std::array<std::int64_t, 8> mixed{7 * ms, 11 * ms, 9 * ms, 8 * ms, 10 * ms, 7 * ms, 11 * ms, 9 * ms};

} // namespace

TEST_CASE("cadence: at 120 Hz the period is the timelines' spacing, and frames of 7 to 11 ms show at 60 a second") {
    for (const bool warm : {false, true}) {
        CAPTURE(warm);
        const Run r = run(period120, mixed, true, warm, 400);
        // the period read at every vsync is the display's, however far apart the vsyncs the loop received
        for (const std::int64_t p : r.period) CHECK(p == period120);
        // a frame every second vsync, none skipped beyond it
        for (std::size_t i = 1; i < r.drawn.size(); ++i) CHECK(r.drawn[i] - r.drawn[i - 1] == 2 * period120);
        // shown as soon as each is ready, so one refresh or two after its vsync, 60 a second on the mean
        const std::int64_t mean = mean_interval(r, 2);
        CHECK(mean >= (2 * period120) - (ms / 10));
        CHECK(mean <= (2 * period120) + (ms / 10));
        // warmth decides only at 90 Hz: never asked here
        CHECK(r.warm_asked == 0);
        // The loop's received vsyncs come one period apart after a skipped one and two after a frame over a period:
        // the gaps' mean (alpha 16 to 27's period) reads a 90 Hz display's, which failed both ways (the stutter
        // review's simulation): on a cool phone every vsync drew (61 to 72 frames a second), and on a warm one the
        // frames were kept 2 x 11.5 - 2 ms apart, past the vsync at 16.7 ms (40 to 52 a second).
        const std::int64_t old = gaps_mean(r);
        CHECK(old > frame::Cadence::fast_ns);
        CHECK(old < frame::Cadence::warm_ns);
        CHECK((2 * old) - frame::Cadence::slack_ns > 2 * period120);
    }
}

TEST_CASE("cadence: at 120 Hz never more than 60 a second, and 60 wherever a frame makes every second vsync") {
    // Frames about 2 to 22 ms with 1.5 or 3 ms of spread (the stutter review's), the phone cool and warm: after a
    // frame under two periods the next is drawn two periods on (60 a second), after a longer one at the first vsync
    // after it ends, never sooner and never later. Frames of one length alone would not tell: every gap the same,
    // the gaps' mean was right for them.
    constexpr std::array<std::int64_t, 8> halves{-2, 2, 0, -1, 1, -2, 2, 0}; // each frame's offset, in half spreads
    for (const bool warm : {false, true}) {
        CAPTURE(warm);
        for (const std::int64_t spread : {3 * ms / 2, 3 * ms}) {
            CAPTURE(spread);
            for (std::int64_t centre = 2; centre <= 22; ++centre) {
                CAPTURE(centre);
                std::array<std::int64_t, halves.size()> work{};
                for (std::size_t i = 0; i < work.size(); ++i)
                    work[i] = std::max(ms / 2, (centre * ms) + (halves[i] * spread / 2));
                const Run r = run(period120, work, true, warm, 80);
                for (std::size_t i = 1; i < r.drawn.size(); ++i) {
                    const std::int64_t w = work[(i - 1) % work.size()];
                    CHECK(r.drawn[i] - r.drawn[i - 1] == std::max<std::int64_t>(2, (w / period120) + 1) * period120);
                }
            }
        }
    }
}

TEST_CASE("cadence: a frame later than two refreshes is never held back") {
    // 20 ms frames at 120 Hz: drawn at the first vsync after each ends, three periods apart (40 a second), never four
    const std::array<std::int64_t, 1> slow{20 * ms};
    const Run r = run(period120, slow, true, true, 50);
    for (std::size_t i = 1; i < r.drawn.size(); ++i) CHECK(r.drawn[i] - r.drawn[i - 1] == 3 * period120);
    // at 60 Hz the same frames take two periods (30 a second)
    const Run s = run(period60, slow, true, true, 50);
    for (std::size_t i = 1; i < s.drawn.size(); ++i) CHECK(s.drawn[i] - s.drawn[i - 1] == 2 * period60);
}

TEST_CASE("cadence: at 60 Hz every vsync draws, at 90 Hz every vsync where the phone is cool") {
    const std::array<std::int64_t, 1> light{5 * ms};
    const Run at60 = run(period60, light, true, true, 50);
    for (std::size_t i = 1; i < at60.drawn.size(); ++i) CHECK(at60.drawn[i] - at60.drawn[i - 1] == period60);
    CHECK(at60.warm_asked == 0);
    const Run cool = run(period90, light, true, false, 50);
    for (std::size_t i = 1; i < cool.drawn.size(); ++i) CHECK(cool.drawn[i] - cool.drawn[i - 1] == period90);
    CHECK(cool.warm_asked > 0);
}

TEST_CASE("cadence: at 90 Hz where the phone runs warm, 60 a second by phase, and a later frame never held") {
    // Light frames: one vsync and two in turn, every two frames three vsyncs (60 a second), where every second vsync
    // gave 45 until 3 November.
    const std::array<std::int64_t, 1> light{5 * ms};
    const Run warm = run(period90, light, true, true, 60);
    CHECK(warm.warm_asked > 0);
    for (std::size_t i = 1; i < warm.drawn.size(); ++i) {
        const std::int64_t gap = warm.drawn[i] - warm.drawn[i - 1];
        CHECK((gap == period90 || gap == 2 * period90));
    }
    for (std::size_t i = 2; i < warm.drawn.size(); ++i) CHECK(warm.drawn[i] - warm.drawn[i - 2] == 3 * period90);
    const std::int64_t mean = mean_interval(warm, 2);
    CHECK(mean >= frame::Cadence::sixtieth_ns - (ms / 10));
    CHECK(mean <= frame::Cadence::sixtieth_ns + (ms / 10));
    // Frames about 2 to 22 ms with 1.5 or 3 ms of spread: after one under a period the next comes one vsync on or
    // two, by the phase; after a longer one at the first vsync after it ends, never later (a frame over a period misses
    // the next vsync at any step: from 11.1 to 22.2 ms, 45 a second); and no two frames within fewer than three vsyncs
    // (never more than 60 a second), a later frame moving the phase on from itself, not letting the next ones catch up.
    constexpr std::array<std::int64_t, 8> halves{-2, 2, 0, -1, 1, -2, 2, 0}; // each frame's offset, in half spreads
    for (const std::int64_t spread : {3 * ms / 2, 3 * ms}) {
        CAPTURE(spread);
        for (std::int64_t centre = 2; centre <= 22; ++centre) {
            CAPTURE(centre);
            std::array<std::int64_t, halves.size()> work{};
            for (std::size_t i = 0; i < work.size(); ++i)
                work[i] = std::max(ms / 2, (centre * ms) + (halves[i] * spread / 2));
            const Run r = run(period90, work, true, true, 80);
            for (std::size_t i = 1; i < r.drawn.size(); ++i) {
                const std::int64_t w = work[(i - 1) % work.size()];
                const std::int64_t gap = r.drawn[i] - r.drawn[i - 1];
                if (w >= period90) {
                    CHECK(gap == ((w / period90) + 1) * period90);
                } else {
                    CHECK((gap == period90 || gap == 2 * period90));
                }
                if (i >= 2) CHECK(r.drawn[i] - r.drawn[i - 2] >= 3 * period90);
            }
        }
    }
}

TEST_CASE("cadence: without frame timelines, the shortest of the last gaps") {
    // Before API 33: the gap after a skipped vsync (or a frame under a period) is the period itself.
    const Run r = run(period120, mixed, false, true, 400);
    CHECK(r.period.back() == period120);
    for (std::size_t i = 2; i < r.drawn.size(); ++i) CHECK(r.drawn[i] - r.drawn[i - 1] == 2 * period120);
    // Every frame over a period: every gap two periods, read as the period, so every vsync draws, each two periods
    // after the last: still 60 a second.
    const std::array<std::int64_t, 1> over{10 * ms};
    const Run s = run(period120, over, false, true, 50);
    CHECK(s.period.back() == 2 * period120);
    for (std::size_t i = 1; i < s.drawn.size(); ++i) CHECK(s.drawn[i] - s.drawn[i - 1] == 2 * period120);
}

TEST_CASE("cadence: the period follows the display's at once, and gaps across a rest are not the display's") {
    using Step = frame::Cadence::Step;
    frame::Cadence c;
    CHECK(c.period_ns() == 0);
    CHECK(c.step([] { return true; }) == Step::every);
    CHECK(c.cadence_ns(Step::every) == 0);
    CHECK(c.due(1'000 * ms, Step::every));
    std::int64_t v = 1'000 * ms;
    const auto p60 = presents_at(v, period60);
    c.at_vsync(v, p60);
    CHECK(c.period_ns() == period60);
    CHECK(c.step([] { return true; }) == Step::every);
    CHECK(c.cadence_ns(Step::every) == period60);
    // the display goes to 120 Hz: the next vsync's timelines say so
    v += period60;
    const auto p120 = presents_at(v, period120);
    c.at_vsync(v, p120);
    CHECK(c.period_ns() == period120);
    CHECK(c.step([] { return false; }) == Step::second);
    CHECK(c.cadence_ns(Step::second) == 2 * period120);
    // and to 90 Hz: by the phase where the phone runs warm, else every vsync; the pacer's cadence a period, since by
    // the phase a frame may follow the last at the next vsync
    v += period120;
    c.at_vsync(v, presents_at(v, period90));
    CHECK(c.period_ns() == period90);
    CHECK(c.step([] { return true; }) == Step::phase);
    CHECK(c.step([] { return false; }) == Step::every);
    CHECK(c.cadence_ns(Step::phase) == period90);
    // timelines no display has (60 ms apart), or one timeline: the shortest of the gaps
    frame::Cadence e;
    v = 1'000 * ms;
    e.at_vsync(v, presents_at(v, period120));
    CHECK(e.period_ns() == period120);
    v += period60;
    const std::array<std::int64_t, 2> off{v, v + (60 * ms)};
    e.at_vsync(v, off);
    CHECK(e.period_ns() == period60); // the one gap so far
    v += period120;
    const std::array<std::int64_t, 1> one{v + (2 * period120)};
    e.at_vsync(v, one);
    CHECK(e.period_ns() == period120); // the gaps 16.7 and 8.3 ms
    // a vsync after a rest of a second: its gap is not counted
    frame::Cadence d;
    d.at_vsync(1'000 * ms, {});
    d.at_vsync(2'000 * ms, {});
    CHECK(d.period_ns() == 0);
    d.at_vsync((2'000 * ms) + period120, {});
    CHECK(d.period_ns() == period120);
    // the first frame draws at any vsync; the next from two periods less the slack
    CHECK(d.due(2'000 * ms, Step::second));
    d.drew(2'000 * ms, Step::second);
    CHECK_FALSE(d.due((2'000 * ms) + period120, Step::second));
    CHECK(d.due((2'000 * ms) + (2 * period120) - frame::Cadence::slack_ns, Step::second));
    CHECK(d.due((2'000 * ms) + period120, Step::every));
}

TEST_CASE("cadence: the headroom read at most once a second, the last number kept, a phone never read cool") {
    frame::Warmth w;
    int reads = 0;
    float headroom = std::numeric_limits<float>::quiet_NaN();
    const auto read = [&] {
        ++reads;
        return headroom;
    };
    const std::int64_t s = 1'000 * ms;
    // never read, then not a number (no headroom on this device, or a read refused): cool
    CHECK_FALSE(w.warm(s, read));
    CHECK(reads == 1);
    // within the second: no read
    headroom = 0.7F;
    CHECK_FALSE(w.warm(s + (500 * ms), read));
    CHECK(reads == 1);
    CHECK(w.warm(2 * s, read));
    CHECK(reads == 2);
    // a reading that is not a number keeps the last that was, warm or cool
    headroom = std::numeric_limits<float>::quiet_NaN();
    CHECK(w.warm(3 * s, read));
    CHECK(w.warm((4 * s) - ms, read));
    CHECK(reads == 3);
    headroom = 0.5F;
    CHECK_FALSE(w.warm(4 * s, read));
    headroom = std::numeric_limits<float>::quiet_NaN();
    CHECK_FALSE(w.warm(5 * s, read));
    // 0.6 is not over it
    headroom = 0.6F;
    CHECK_FALSE(w.warm(6 * s, read));
    CHECK(reads == 6);
}
