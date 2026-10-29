// Pacing by the frame's own time (frame/Pacing.hpp).
#include "frame/Pacing.hpp"

#include <algorithm>
#include <span>

namespace atlas::frame {

void Pacer::add_work(std::int64_t work_ns) noexcept {
    if (work_ns <= 0) return;
    work_[next_] = work_ns;
    next_ = (next_ + 1) % window;
    count_ = std::min(count_ + 1, window);
}

std::int64_t Pacer::work_ns() const noexcept {
    if (count_ < least) return 0;
    // The window's samples lie at 0 to count_ until it has wrapped, and fill it after.
    std::array<std::int64_t, window> sorted = work_;
    const std::span<std::int64_t> known(sorted.data(), count_);
    const std::size_t at = (count_ * 9) / 10;
    std::ranges::nth_element(known, known.begin() + static_cast<std::ptrdiff_t>(at));
    return known[at] + margin_ns;
}

std::size_t Pacer::choose(std::span<const Timeline> timelines, std::size_t preferred, std::int64_t now_ns,
                          std::int64_t cadence_ns, std::int64_t period_ns) noexcept {
    if (timelines.empty()) return 0;
    const std::int64_t work = work_ns();
    const std::int64_t earliest =
        last_present_ns_ != 0 && cadence_ns > 0 ? last_present_ns_ + cadence_ns - (period_ns / 2) : 0;
    std::size_t pick = timelines.size() - 1;
    for (std::size_t i = std::min(preferred, timelines.size() - 1); i < timelines.size(); ++i) {
        const Timeline& t = timelines[i];
        if (work > 0 && t.deadline_ns < now_ns + work) continue;
        if (t.present_ns < earliest) continue;
        pick = i;
        break;
    }
    last_present_ns_ = timelines[pick].present_ns;
    return pick;
}

} // namespace atlas::frame
