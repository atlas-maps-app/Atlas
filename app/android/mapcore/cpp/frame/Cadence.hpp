// The frame loop's cadence on the display (plan-vector D5.8, ENGINE.md gain 11): at most 60 frames a second, every
// vsync at 60 Hz, every second one at 120 Hz, at 90 Hz every vsync where the phone is cool and 60 a second by phase
// where it runs warm (one vsync and two in turn, where every second one gave 45 until 3 November); a frame that comes
// later than that is never held back. The display's period is the spacing of the vsync's frame timelines, which
// SurfaceFlinger spaces by the app's vsync period (API 33); before API 33, the shortest of the last gaps between the
// vsyncs the loop received, each a whole number of periods. Until 3 November it was the mean of those gaps: after a
// frame longer than a refresh the loop's next vsync comes two periods on, so at 120 Hz the mean settled at 11 to 12 ms,
// and on a warm phone the limit took that for 90 Hz and kept the frames 22 ms apart, past the vsync at 16.7 ms: frames
// of 7 to 11 ms showed at 40 to 52 a second instead of 60 (the stutter review of 3 November). Portable and pure, so the
// hosts test it; the frame loop (jni/Bridge.cpp) asks it at each vsync, and Warmth whether the phone runs warm.
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <span>

namespace atlas::frame {

/// The display's period and the limit of 60 frames a second, from the vsyncs the frame loop receives.
class Cadence {
public:
    /// Which vsyncs may draw: every one, every second one (displays faster than 100 Hz), or by the phase of 60 a second
    /// (a warm phone at 90 Hz: one vsync and two in turn).
    enum class Step : std::uint8_t { every, second, phase };

    /// The gaps between vsyncs whose shortest is the period where a vsync has no frame timelines to space.
    static constexpr std::size_t window = 16;
    /// A period or a gap outside these is no display's (the loop rested between the vsyncs, or the data is off).
    static constexpr std::int64_t shortest_ns = 4'000'000;
    static constexpr std::int64_t longest_ns = 50'000'000;
    /// Under this period (displays faster than 100 Hz) frames go out every second vsync; under the warm one (90 Hz)
    /// by the phase where the phone runs warm.
    static constexpr std::int64_t fast_ns = 10'000'000;
    static constexpr std::int64_t warm_ns = 14'000'000;
    /// How far apart the phase puts the frames: a sixtieth of a second.
    static constexpr std::int64_t sixtieth_ns = 16'666'667;
    /// What a vsync may fall short of the time its frame is due, and still draw.
    static constexpr std::int64_t slack_ns = 2'000'000;

    /// A vsync at `frame_ns`, with its frame timelines' expected presentations, earliest first (none before API 33).
    void at_vsync(std::int64_t frame_ns, std::span<const std::int64_t> presents) noexcept {
        if (last_ns_ != 0) {
            const std::int64_t gap = frame_ns - last_ns_;
            if (gap > shortest_ns && gap < longest_ns) {
                gaps_[next_] = gap;
                next_ = (next_ + 1) % window;
                count_ = std::min(count_ + 1, window);
            }
        }
        last_ns_ = frame_ns;
        const std::int64_t spacing = presents.size() >= 2 ? presents[1] - presents[0] : 0;
        if (spacing > shortest_ns && spacing < longest_ns) {
            period_ns_ = spacing;
        } else if (count_ > 0) {
            period_ns_ = *std::ranges::min_element(std::span(gaps_.data(), count_));
        }
    }

    /// The display's period, ns: 0 until known.
    [[nodiscard]] std::int64_t period_ns() const noexcept { return period_ns_; }

    /// Which vsyncs may draw: every second one under the fast period; under the warm one, by the phase where `warm()`
    /// says the phone runs warm (asked only there), else every one; every one at slower displays.
    template <class Warm>
    [[nodiscard]] Step step(const Warm& warm) const {
        if (period_ns_ == 0 || period_ns_ >= warm_ns) return Step::every;
        if (period_ns_ < fast_ns) return Step::second;
        return warm() ? Step::phase : Step::every;
    }

    /// Whether the vsync at `frame_ns` may draw at `step`: any vsync at every; from two periods after the vsync the
    /// last frame was drawn for at second, from the phase's time at phase, each less the slack.
    [[nodiscard]] bool due(std::int64_t frame_ns, Step step) const noexcept {
        if (drawn_ns_ == 0 || step == Step::every) return true;
        if (step == Step::second) return frame_ns - drawn_ns_ >= (2 * period_ns_) - slack_ns;
        return frame_ns >= phase_ns_ - slack_ns;
    }

    /// A frame was drawn for the vsync at `frame_ns` at `step`. The phase goes a sixtieth of a second on where the
    /// frame came at the first vsync it allowed, so that frames under a period take one vsync and two in turn; where it
    /// came a vsync or more later (its frame's work, a rest, or another step), from this frame, so that a later frame
    /// is never followed by a run of vsyncs.
    void drew(std::int64_t frame_ns, Step step) noexcept {
        const bool kept = step == Step::phase && drawn_ns_ != 0 && frame_ns < phase_ns_ + period_ns_ - slack_ns;
        phase_ns_ = (kept ? phase_ns_ : frame_ns) + sixtieth_ns;
        drawn_ns_ = frame_ns;
    }

    /// The least spacing of the frames' presentations, ns (gain 15's pacing): two periods where every second vsync
    /// draws, else one (by the phase a frame may follow the last at the next vsync); 0 while unknown.
    [[nodiscard]] std::int64_t cadence_ns(Step step) const noexcept {
        return step == Step::second ? 2 * period_ns_ : period_ns_;
    }

private:
    std::array<std::int64_t, window> gaps_{};
    std::size_t count_ = 0;
    std::size_t next_ = 0;
    std::int64_t last_ns_ = 0;
    std::int64_t drawn_ns_ = 0;
    std::int64_t phase_ns_ = 0; // when the phase lets the next frame draw: a sixtieth of a second after the last one's
    std::int64_t period_ns_ = 0;
};

/// Whether the phone runs warm, for the limit at 90 Hz: its thermal headroom over 0.6 (the share of its thermal
/// envelope in use, 1.0 at severe throttling). It is read at most once a second, each read a call into the system's
/// thermal service: the NDK's header asks for no more, though AOSP's own code has no such limit (Java's PowerManager
/// alone answers NaN to a read within 500 ms), so this is a hedge. A reading that is not a number (none on this
/// device, or one refused) keeps the last that was, and a phone never read is taken for cool.
class Warmth {
public:
    static constexpr std::int64_t every_ns = 1'000'000'000;
    static constexpr float warm_over = 0.6F;

    /// At `now_ns`: whether the phone runs warm, `read()` giving the headroom where a second has gone by since the
    /// last read, or none was made.
    template <class Read>
    [[nodiscard]] bool warm(std::int64_t now_ns, const Read& read) {
        if (read_ns_ == 0 || now_ns - read_ns_ >= every_ns) {
            const float headroom = read();
            if (!std::isnan(headroom)) headroom_ = headroom;
            read_ns_ = now_ns;
        }
        return headroom_ > warm_over;
    }

private:
    float headroom_ = 0.0F; // the last reading that was a number (cool until one is)
    std::int64_t read_ns_ = 0;
};

} // namespace atlas::frame
