// Pacing by the frame's own time (ENGINE.md, gain 15). Choreographer offers each vsync's frame several timelines, each
// an expected presentation and the deadline for the frame to be ready by. The preferred one is the platform's default
// schedule for the display, not this frame's work: a frame whose stage F, recording and GPU work run past its deadline
// is shown a refresh late and the next one on time, a long gap and then a short one. So the frame loop takes, from the
// preferred timeline on, the first whose deadline leaves the frame its recent work (the 90th percentile of its last
// frames' CPU and GPU time, plus a margin) and whose presentation keeps the frames' cadence (no sooner than the cadence
// after the last one asked for, less half a display period), and asks the swapchain to present there
// (VK_GOOGLE_display_timing's desired time): a refresh more of latency where the work needs it, every frame on time.
// Portable and pure, so the hosts test it; the frame loop (jni/Bridge.cpp) gives it Choreographer's timelines.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace atlas::frame {

/// One of Choreographer's frame timelines: its expected presentation, and the deadline for the frame to be ready by.
struct Timeline {
    std::int64_t present_ns = 0;
    std::int64_t deadline_ns = 0;
};

/// The frame loop's pacing: its recent frames' work, and the last presentation it asked for.
class Pacer {
public:
    /// The frames whose work the percentile reads.
    static constexpr std::size_t window = 32;
    /// The frames measured before their work is trusted: until then the preferred timeline is taken.
    static constexpr std::size_t least = 8;
    /// What the work is given beyond its percentile.
    static constexpr std::int64_t margin_ns = 1'000'000;

    /// A drawn frame's work, ns: its CPU time from the draw's start and its GPU time. Not positive: not counted.
    void add_work(std::int64_t work_ns) noexcept;
    /// The work a frame is given: the window's 90th percentile plus the margin; 0 while fewer than `least` are known.
    [[nodiscard]] std::int64_t work_ns() const noexcept;
    /// The timeline to present at, among `timelines` (earliest first): from `preferred` on, the first whose deadline
    /// is at least `now_ns` plus the work and whose presentation is at least `cadence_ns` (0: none) after the last one
    /// asked for, less half of `period_ns`; else the last. It is remembered as the last one asked for. No timelines:
    /// 0, and nothing remembered.
    [[nodiscard]] std::size_t choose(std::span<const Timeline> timelines, std::size_t preferred, std::int64_t now_ns,
                                     std::int64_t cadence_ns, std::int64_t period_ns) noexcept;
    /// The map rested: the next frame keeps no cadence with the last one.
    void rest() noexcept { last_present_ns_ = 0; }
    /// The last presentation asked for, 0 for none since the map last rested.
    [[nodiscard]] std::int64_t last_present_ns() const noexcept { return last_present_ns_; }

private:
    std::array<std::int64_t, window> work_{};
    std::size_t count_ = 0;
    std::size_t next_ = 0;
    std::int64_t last_present_ns_ = 0;
};

} // namespace atlas::frame
