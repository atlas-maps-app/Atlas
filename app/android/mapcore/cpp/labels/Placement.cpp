#include "labels/Placement.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <format>
#include <functional>

namespace atlas::labels {

namespace {

constexpr std::uint32_t none = UINT32_MAX;

[[nodiscard]] float ramp(double t) noexcept {
    const double f = t / Placement::fade_seconds;
    return static_cast<float>(f > 0.0 ? (f < 1.0 ? f : 1.0) : 0.0);
}

/// Whether point `p` was projected (LabelPainter skips a point GroundView cannot project).
[[nodiscard]] bool projected(Point p) noexcept { return !std::isnan(p.x) && !std::isnan(p.y); }

} // namespace

Stand stand_at(const Measures& m, Point p, std::size_t spot) noexcept {
    const float cx = p.x + (spots[2 * spot] * (m.half - m.pad));
    const float cy = p.y + (spots[(2 * spot) + 1] * m.text_height);
    const float half_height = (m.text_height / 2.0F) + m.pad;
    return {
        .x = cx, .y = cy, .box = {.l = cx - m.half, .t = cy - half_height, .r = cx + m.half, .b = cy + half_height}};
}

void priority_order(std::span<const Candidate> candidates, std::vector<std::uint32_t>& order) {
    order.resize(candidates.size());
    for (std::uint32_t i = 0; i < order.size(); ++i) order[i] = i;
    std::ranges::stable_sort(order, std::ranges::greater{}, [&](std::uint32_t i) { return candidates[i].priority; });
}

bool fits(const Box& box, const Frame& frame, const CollisionIndex& index) noexcept {
    if (box.l < 0.0F || box.t < 0.0F || box.r > frame.width || box.b > frame.height) return false;
    return !overlaps_any(frame.keep_out, box) && !overlaps_any(frame.markers, box) && !index.hits(box);
}

void place(std::span<const Candidate> candidates, std::span<const std::uint32_t> order, const Frame& frame,
           CollisionIndex& index, std::vector<Placed>& out) {
    for (const std::uint32_t c : order) {
        const Candidate& label = candidates[c];
        bool placed = false;
        for (std::size_t p = 0; p < label.points.size() && !placed; ++p) {
            if (!projected(label.points[p])) continue;
            for (std::size_t k = 0; k < spot_count; ++k) {
                const Stand s = stand_at(label.measures, label.points[p], k);
                if (!fits(s.box, frame, index)) continue;
                index.insert(s.box);
                out.push_back({.candidate = c,
                               .point = static_cast<std::uint16_t>(p),
                               .spot = static_cast<std::uint8_t>(k),
                               .stand = s});
                placed = true;
                break;
            }
        }
    }
}

std::span<const Drawn> Placement::update(std::span<const Candidate> candidates, const Frame& frame, bool rest,
                                         double seconds) {
    const auto t0 = std::chrono::steady_clock::now();
    const bool first = !started_;
    started_ = true;
    fading_ = false;
    drawn_.clear();
    placed_.clear();
    priority_order(candidates, order_);
    index_.reset(frame.width, frame.height);

    // Each candidate's state, by id (the states are kept sorted by id).
    state_of_.assign(candidates.size(), none);
    for (std::uint32_t i = 0; i < candidates.size(); ++i) {
        const auto at = std::ranges::lower_bound(states_, candidates[i].id, {}, &State::id);
        if (at != states_.end() && at->id == candidates[i].id) {
            state_of_[i] = static_cast<std::uint32_t>(at - states_.begin());
        }
    }

    if (rest) {
        place(candidates, order_, frame, index_, placed_);
    } else {
        // The placed labels keep their place where it still fits, by priority; the others are placed around them.
        std::vector<std::uint32_t>& others = scratch_;
        others.clear();
        for (const std::uint32_t c : order_) {
            const std::uint32_t s = state_of_[c];
            const Candidate& label = candidates[c];
            if (s != none && states_[s].present && states_[s].point < label.points.size() &&
                projected(label.points[states_[s].point])) {
                const Stand st = stand_at(label.measures, label.points[states_[s].point], states_[s].spot);
                if (fits(st.box, frame, index_)) {
                    index_.insert(st.box);
                    placed_.push_back(
                        {.candidate = c, .point = states_[s].point, .spot = states_[s].spot, .stand = st});
                    continue;
                }
            }
            others.push_back(c);
        }
        place(candidates, others, frame, index_, placed_);
    }
    placed_count_ = static_cast<std::uint32_t>(placed_.size());

    // Each candidate's place this frame.
    place_of_.assign(candidates.size(), none);
    for (std::uint32_t i = 0; i < placed_.size(); ++i) place_of_[placed_[i].candidate] = i;

    // The states follow: a new place fades in as the old fades out; a label no longer placed fades out.
    next_.clear();
    for (const std::uint32_t c : order_) {
        State s = state_of_[c] != none ? states_[state_of_[c]] : State{.id = candidates[c].id};
        const std::uint32_t p = place_of_[c];
        if (p != none) {
            const Placed& at = placed_[p];
            if (!s.present) {
                s.present = true;
                s.since = first ? seconds - 1.0 : seconds; // a rest frame's first labels at once
            } else if (s.point != at.point || s.spot != at.spot) {
                s.leaving = true;
                s.left_point = s.point;
                s.left_spot = s.spot;
                s.left_at = seconds;
                s.since = seconds;
            }
            s.point = at.point;
            s.spot = at.spot;
        } else if (s.present) {
            s.leaving = true;
            s.left_point = s.point;
            s.left_spot = s.spot;
            s.left_at = seconds;
            s.present = false;
        }
        // What the frame draws of it: where it stands, and where it stood, fading out.
        const Candidate& label = candidates[c];
        if (s.present) {
            const Stand& st = placed_[p].stand;
            const float alpha = ramp(seconds - s.since);
            drawn_.push_back({.candidate = c, .stand = st, .baseline = st.y - label.measures.baseline, .alpha = alpha});
            fading_ = fading_ || alpha < 1.0F;
        }
        if (s.leaving) {
            const float alpha = 1.0F - ramp(seconds - s.left_at);
            if (alpha > 0.0F && s.left_point < label.points.size() && projected(label.points[s.left_point])) {
                const Stand st = stand_at(label.measures, label.points[s.left_point], s.left_spot);
                drawn_.push_back({.candidate = c,
                                  .stand = st,
                                  .baseline = st.y - label.measures.baseline,
                                  .leaving = true,
                                  .alpha = alpha});
                fading_ = true;
            } else {
                s.leaving = false;
            }
        }
        if (s.present || s.leaving) next_.push_back(s);
    }
    std::ranges::sort(next_, {}, &State::id);
    states_.swap(next_);
    millis_ = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return drawn_;
}

std::string Placement::line() const { return std::format("label place {:.3f} placed {}", millis_, placed_count_); }

void Placement::reset() noexcept {
    states_.clear();
    started_ = false;
    fading_ = false;
}

} // namespace atlas::labels
