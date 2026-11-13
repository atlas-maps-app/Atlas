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

/// `b` grown by `d` on every side.
[[nodiscard]] Box grown(const Box& b, float d) noexcept {
    return {.l = b.l - d, .t = b.t - d, .r = b.r + d, .b = b.b + d};
}

} // namespace

Stand stand_at(const Measures& m, Point p, std::size_t spot, float rise, float mark) noexcept {
    const float cx = p.x + (spots[2 * spot] * (m.half - m.pad));
    float cy = p.y + (spots[(2 * spot) + 1] * m.text_height);
    const float half_height = (m.text_height / 2.0F) + m.pad;
    if (!(rise > 0.0F)) {
        return {.x = cx,
                .y = cy,
                .box = {.l = cx - m.half, .t = cy - half_height, .r = cx + m.half, .b = cy + half_height}};
    }
    // risen: the text's lower edge `mark` over the point at a rise of 1, the box down to the mark's own lower edge
    cy -= rise * ((m.text_height / 2.0F) + mark);
    return {.x = cx,
            .y = cy,
            .box = {.l = cx - m.half,
                    .t = cy - half_height,
                    .r = cx + m.half,
                    .b = std::max(cy + half_height, p.y + (rise * mark))}};
}

void priority_order(std::span<const Candidate> candidates, std::vector<std::uint32_t>& order) {
    order.resize(candidates.size());
    for (std::uint32_t i = 0; i < order.size(); ++i) order[i] = i;
    std::ranges::stable_sort(order, std::ranges::greater{}, [&](std::uint32_t i) { return candidates[i].priority; });
}

bool fits(const Box& box, const Frame& frame, const CollisionIndex& index) noexcept {
    if (box.l < 0.0F || box.t < 0.0F || box.r > frame.width || box.b > frame.height) return false;
    return clear_of(box, frame, index);
}

bool clear_of(const Box& box, const Frame& frame, const CollisionIndex& index) noexcept {
    return !overlaps_any(frame.keep_out, box) && !overlaps_any(frame.markers, box) && !index.hits(box);
}

Measures scaled(const Measures& m, float scale) noexcept {
    if (scale == 1.0F) return m;
    return {.half = m.half * scale,
            .pad = m.pad * scale,
            .text_height = m.text_height * scale,
            .baseline = m.baseline * scale};
}

std::uint32_t place(std::span<const Candidate> candidates, std::span<const std::uint32_t> order, const Frame& frame,
                    CollisionIndex& index, std::vector<Placed>& out, std::uint32_t* moved) {
    if (moved != nullptr) *moved = 0;
    // the frame's marks, asked for at the first stand that fits
    std::span<const Mark> marks;
    bool asked = false;
    const auto on_a_mark = [&](const Box& box, Point own) {
        if (!asked) {
            asked = true;
            if (frame.marks != nullptr && *frame.marks) marks = (*frame.marks)();
        }
        return std::ranges::any_of(marks, [&](const Mark& m) {
            // (its own point's mark is its own: a lit place's name stands over its light by its rise)
            if (m.x == own.x && m.y == own.y) return false;
            return overlaps(box, {.l = m.x - m.radius, .t = m.y - m.radius, .r = m.x + m.radius, .b = m.y + m.radius});
        });
    };
    std::uint32_t covering = 0;
    for (const std::uint32_t c : order) {
        const Candidate& label = candidates[c];
        bool placed = false;
        // the first stand that fits but covers a mark: taken where none is clear of them
        bool has_covered = false;
        Placed covered;
        for (std::size_t p = 0; p < label.points.size() && !placed; ++p) {
            if (!projected(label.points[p])) continue;
            for (std::size_t k = 0; k < spot_count; ++k) {
                const Stand s = stand_at(label.measures, label.points[p], k, label.rise, label.mark);
                if (!fits(s.box, frame, index)) continue;
                const Placed at{.candidate = c,
                                .point = static_cast<std::uint16_t>(p),
                                .spot = static_cast<std::uint8_t>(k),
                                .stand = s};
                if (on_a_mark(s.box, label.points[p])) {
                    if (!has_covered) covered = at;
                    has_covered = true;
                    continue;
                }
                index.insert(s.box);
                out.push_back(at);
                placed = true;
                if (has_covered && moved != nullptr) ++*moved;
                break;
            }
        }
        if (!placed && has_covered) {
            index.insert(covered.stand.box);
            out.push_back(covered);
            ++covering;
        }
    }
    return covering;
}

std::span<const Drawn> Placement::update(std::span<const Candidate> candidates, const Frame& frame, double scale,
                                         double seconds) {
    const auto t0 = std::chrono::steady_clock::now();
    const bool first = !started_;
    started_ = true;
    fading_ = false;
    drawn_.clear();
    placed_.clear();
    clear_.clear();
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

    // The set's size: the map's scale since its band was placed. Beyond the band (the floor raising its low end, never
    // past the set's own size) the set is placed anew at its own size, every label it had fading out where it stood.
    const double low = std::min(1.0, std::max(band_low, static_cast<double>(frame.least)));
    double size = reference_ > 0.0 && scale > 0.0 ? scale / reference_ : 1.0;
    if (reference_ <= 0.0 || size < low || size > band_high) {
        const double before = reference_;
        // at its own size; where the floor leaves less than band_room under it, that much over it
        size = scale > 0.0 ? std::max(1.0, low / band_room) : 1.0;
        reference_ = scale > 0.0 ? scale / size : 1.0;
        for (State& st : states_) {
            if (!st.present) continue;
            st.present = false;
            st.shown = false;
            if (before > 0.0) {
                st.leaving = true;
                st.left_point = st.point;
                st.left_spot = st.spot;
                st.left_at = seconds;
                st.left_reference = before;
            }
        }
    }
    size_ = static_cast<float>(size);
    scaled_.assign(candidates.begin(), candidates.end());
    for (Candidate& c : scaled_) c.measures = scaled(c.measures, size_);

    // The placed labels at their places, by priority: clear of the labels kept before them, the keep-outs and the
    // markers (a hidden one by the margin), wherever on the frame they stand; the others placed around them.
    std::vector<std::uint32_t>& others = scratch_;
    others.clear();
    for (const std::uint32_t c : order_) {
        const std::uint32_t s = state_of_[c];
        if (s == none || !states_[s].present) {
            if (!candidates[c].hidden) others.push_back(c);
            continue;
        }
        const State& st = states_[s];
        const Candidate& label = scaled_[c];
        const bool has = st.point < label.points.size() && projected(label.points[st.point]);
        const Stand stand =
            has ? stand_at(label.measures, label.points[st.point], st.spot, label.rise, label.mark) : Stand{};
        const bool clear =
            has && !label.hidden && clear_of(st.shown ? stand.box : grown(stand.box, margin), frame, index_);
        if (clear) index_.insert(stand.box);
        placed_.push_back({.candidate = c, .point = st.point, .spot = st.spot, .stand = stand});
        clear_.push_back(clear ? 1 : 0);
    }
    const std::size_t kept = placed_.size();
    on_marks_ = place(scaled_, others, frame, index_, placed_, &off_marks_);
    placed_new_ = static_cast<std::uint32_t>(placed_.size() - kept);
    clear_.resize(placed_.size(), 1);
    placed_count_ = static_cast<std::uint32_t>(std::ranges::count(clear_, std::uint8_t{1}));

    // Each candidate's place this frame.
    place_of_.assign(candidates.size(), none);
    for (std::uint32_t i = 0; i < placed_.size(); ++i) place_of_[placed_[i].candidate] = i;

    // The states follow: a new place fades in (a set's first frame at once); a kept label turning hidden or clear fades
    // from where its alpha was; a place in the band before fades out.
    const auto alpha_of = [&](const State& st) {
        return st.shown ? ramp(seconds - st.since) : 1.0F - ramp(seconds - st.since);
    };
    next_.clear();
    for (const std::uint32_t c : order_) {
        State st = state_of_[c] != none ? states_[state_of_[c]] : State{.id = candidates[c].id};
        const std::uint32_t p = place_of_[c];
        if (p != none) {
            const Placed& at = placed_[p];
            const bool clear = clear_[p] != 0;
            if (!st.present) {
                st.present = true;
                st.shown = true;
                st.point = at.point;
                st.spot = at.spot;
                st.since = first ? seconds - 1.0 : seconds;
            } else if (st.shown != clear) {
                const auto a = static_cast<double>(alpha_of(st));
                st.shown = clear;
                st.since = clear ? seconds - (a * fade_seconds) : seconds - ((1.0 - a) * fade_seconds);
            }
        }
        // What the frame draws of it: its place, and its place in the band before, fading out.
        const Candidate& label = candidates[c];
        if (st.present && p != none) {
            // a label fading in from its first frame on; one fading out until it is gone
            const float alpha = alpha_of(st);
            const Stand& stand = placed_[p].stand;
            if ((st.shown || alpha > 0.0F) && st.point < label.points.size() && projected(label.points[st.point])) {
                drawn_.push_back({.candidate = c,
                                  .stand = stand,
                                  .baseline = stand.y - scaled_[c].measures.baseline,
                                  .alpha = alpha,
                                  .scale = size_});
            }
            // fading from its first frame: coming in from 0 until 1, turning hidden from 1 until 0 (a frame at rest
            // with either ends is still due, or the label would stop unseen, or whole where it should go)
            fading_ = fading_ || (st.shown ? alpha < 1.0F : alpha > 0.0F);
        }
        if (st.leaving) {
            const float alpha = 1.0F - ramp(seconds - st.left_at);
            if (alpha > 0.0F && st.left_point < label.points.size() && projected(label.points[st.left_point])) {
                const auto was = static_cast<float>(scale / st.left_reference);
                const Measures m = scaled(label.measures, was);
                const Stand stand = stand_at(m, label.points[st.left_point], st.left_spot, label.rise, label.mark);
                drawn_.push_back({.candidate = c,
                                  .stand = stand,
                                  .baseline = stand.y - m.baseline,
                                  .leaving = true,
                                  .alpha = alpha,
                                  .scale = was});
                fading_ = true;
            } else {
                st.leaving = false;
            }
        }
        if (st.present || st.leaving) next_.push_back(st);
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
    reference_ = 0.0;
    size_ = 1.0F;
}

} // namespace atlas::labels
