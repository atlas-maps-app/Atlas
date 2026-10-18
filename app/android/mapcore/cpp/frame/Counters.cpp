// The frame's counters (frame/Counters.hpp).
#include "frame/Counters.hpp"

#include <format>

namespace atlas::frame {

Count& PassCounters::of(std::string_view kind) noexcept {
    for (KindCount& k : kinds_) {
        if (k.kind == kind) return k.count;
    }
    // A pass that counts more kinds than the budget folds the rest into its last one.
    if (kinds_.full()) return kinds_[kinds_.size() - 1].count;
    return kinds_.emplace_back(KindCount{.kind = kind, .count = {}}).count;
}

void PassCounters::draw(std::string_view kind, std::uint64_t triangles, std::uint64_t instances) noexcept {
    const Count c{.draws = 1, .dispatches = 0, .triangles = triangles, .instances = instances};
    of(kind) += c;
    total_ += c;
}

void PassCounters::dispatch(std::string_view kind) noexcept {
    const Count c{.draws = 0, .dispatches = 1, .triangles = 0, .instances = 0};
    of(kind) += c;
    total_ += c;
}

void PassCounters::clear() noexcept {
    total_ = {};
    kinds_.clear();
}

std::string over_budget(const Count& count, const Budget& budget) {
    std::string out;
    auto add = [&out](const std::string& what) {
        if (!out.empty()) out += "; ";
        out += what;
    };
    if (count.draws > budget.draws) add(std::format("{} draws, over {}", count.draws, budget.draws));
    if (count.triangles > budget.triangles) {
        add(std::format("{} triangles, over {}", count.triangles, budget.triangles));
    }
    if (count.instances > budget.instances) {
        add(std::format("{} instances, over {}", count.instances, budget.instances));
    }
    return out;
}

std::string text_of(const Count& count) {
    return std::format("draws {} dispatches {} triangles {} instances {}", count.draws, count.dispatches,
                       count.triangles, count.instances);
}

} // namespace atlas::frame
