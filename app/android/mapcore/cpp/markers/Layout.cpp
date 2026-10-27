#include "markers/Layout.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace atlas::markers {

namespace {

/// Kotlin's minOf and maxOf for floats (Java's Math.min and max: a NaN wins, -0.0 below +0.0), through pymath's.
[[nodiscard]] float java_min(float a, float b) noexcept {
    return static_cast<float>(pymath::java::min(static_cast<double>(a), static_cast<double>(b)));
}
[[nodiscard]] float java_max(float a, float b) noexcept {
    return static_cast<float>(pymath::java::max(static_cast<double>(a), static_cast<double>(b)));
}

/// Kotlin's Float.toInt(): toward zero, NaN as 0, Int's limits beyond them.
[[nodiscard]] std::int32_t to_int(float v) noexcept {
    if (std::isnan(v)) return 0;
    constexpr auto lo = static_cast<double>(std::numeric_limits<std::int32_t>::min());
    constexpr auto hi = static_cast<double>(std::numeric_limits<std::int32_t>::max());
    const auto d = static_cast<double>(v);
    if (d <= lo) return std::numeric_limits<std::int32_t>::min();
    if (d >= hi) return std::numeric_limits<std::int32_t>::max();
    return static_cast<std::int32_t>(d);
}

} // namespace

float Metrics::cluster_diameter(std::uint64_t count) const noexcept {
    // Kotlin's log10 of a Float: the double's, back to float
    const auto n = static_cast<float>(std::max<std::uint64_t>(1, count));
    const auto log = static_cast<float>(pymath::strict::log10(static_cast<double>(n)));
    return java_min(46.0F, 28.0F + (6.0F * log)) * density;
}

float Metrics::chip_width(bool selected, float text) const noexcept {
    (void)selected; // the board's pill grows in height only
    return chip_pad() + chip_disc() + (text > 0.0F ? chip_gap() + text + chip_end() : chip_pad());
}

labels::Box Metrics::box(const Marker& m, float x, float y) const noexcept {
    switch (m.style) {
    case Style::pin: {
        const float d = pin_diameter(m.selected);
        // centred on the place, or the chosen pin standing on its stem
        const float bottom = y + (m.selected ? pin_foot(true) : d / 2.0F);
        const float top = m.selected ? y - pin_foot(true) - pin_stem(true) - d : y - (d / 2.0F);
        labels::Box b{.l = x - (d / 2.0F), .t = top, .r = x + (d / 2.0F), .b = bottom};
        if (m.value) {
            const float w = (m.chip_text / 2.0F) + value_chip_pad();
            b.l = java_min(b.l, x - w);
            b.r = java_max(b.r, x + w);
            b.b = bottom + value_chip_gap() + value_chip_height();
        }
        return b;
    }
    case Style::dot: {
        const float d = dot_diameter(m.selected) / 2.0F;
        return {.l = x - d, .t = y - d, .r = x + d, .b = y + d};
    }
    case Style::brand: {
        const float s = brand_side(m.selected);
        return {.l = x - (s / 2.0F), .t = y - (s / 2.0F), .r = x + (s / 2.0F), .b = y + (s / 2.0F)};
    }
    case Style::cluster: {
        const float d = cluster_diameter(m.count) / 2.0F;
        return {.l = x - d, .t = y - d, .r = x + d, .b = y + d};
    }
    case Style::chip: {
        const float w = chip_width(m.selected, m.chip_text);
        const float h = chip_height(m.selected);
        return {.l = x - (w / 2.0F), .t = y - (h / 2.0F), .r = x + (w / 2.0F), .b = y + (h / 2.0F)};
    }
    }
    return {};
}

bool Metrics::name_left(const Marker& m, float x, float width) const noexcept {
    const float w = m.name;
    const float gap = (pin_diameter(m.selected) / 2.0F) + (6.0F * density);
    const float right = x + gap;
    const float left = x - gap - w;
    // the right unless the name runs off the view there and the left has room (a NaN keeps the left, as Kotlin's)
    const bool on_right = right + w + (2.0F * density) <= width || left < 0.0F;
    return !on_right;
}

labels::Box Metrics::name_box(const Marker& m, float x, float y, float width) const noexcept {
    const float r = pin_diameter(m.selected) / 2.0F;
    const float cy = m.selected ? y - pin_foot(true) - pin_stem(true) - r : y;
    const float w = m.name;
    const float gap = (pin_diameter(m.selected) / 2.0F) + (6.0F * density);
    const float left = name_left(m, x, width) ? x - gap - w : x + gap;
    return {.l = left - (2.0F * density),
            .t = cy - (name_size() * 0.7F),
            .r = left + w + (2.0F * density),
            .b = cy + (name_size() * 0.7F)};
}

void Layout::place(std::span<const Marker> markers, std::span<const float> xs, std::span<const float> ys,
                   std::span<const std::uint8_t> visible, const Metrics& metrics, float width, float height) {
    const std::size_t n = std::min({markers.size(), xs.size(), ys.size(), visible.size()});
    placed_.clear();
    boxes_.clear();
    folded_.clear();
    clusters_.clear();
    // the sort's keys: chosen, then priority (both highest first), then the list's order
    keys_.clear();
    for (std::size_t i = 0; i < n && i < MarkerSet::max_markers; ++i) {
        if (visible[i] == 0) continue;
        const Marker& m = markers[i];
        const auto priority = static_cast<std::uint64_t>(static_cast<std::int64_t>(m.priority) + 0x8000'0000LL);
        const std::uint64_t chosen = m.selected ? 0U : 1U;
        keys_.push_back((chosen << 62U) | ((0xFFFF'FFFFULL - priority) << 24U) | i);
    }
    std::ranges::sort(keys_);
    grid_.reset(width, height, 64.0F * metrics.density);
    for (const std::uint64_t key : keys_) {
        const auto i = static_cast<std::uint32_t>(key & 0xFF'FFFFU);
        const Marker& m = markers[i];
        const labels::Box box = metrics.box(m, xs[i], ys[i]);
        if (const std::int64_t hit = grid_.first_overlap(box, boxes_); hit >= 0) {
            folded_[static_cast<std::size_t>(hit)] += m.count;
            continue;
        }
        grid_.add(static_cast<std::uint32_t>(placed_.size()), box);
        placed_.push_back(i);
        boxes_.push_back(box);
        folded_.push_back(0);
    }
    // draw order: the lowest priority first, so the most important ends on top
    std::ranges::reverse(placed_);
    std::ranges::reverse(boxes_);
    std::ranges::reverse(folded_);
    clusters_.resize(placed_.size());
    for (std::size_t j = 0; j < placed_.size(); ++j) {
        const Marker& m = markers[placed_[j]];
        const bool bubble = m.style == Style::cluster || (m.style == Style::dot && folded_[j] > 0);
        clusters_[j] = bubble ? m.count + folded_[j] : 0;
    }
}

void Layout::Grid::reset(float width, float height, float cell) {
    cell_ = java_max(8.0F, cell);
    origin_x_ = -width;
    origin_y_ = -height;
    columns_ = static_cast<std::uint32_t>(std::max(1, to_int(3.0F * width / cell_) + 1));
    rows_ = static_cast<std::uint32_t>(std::max(1, to_int(3.0F * height / cell_) + 1));
    head_.assign(std::size_t{columns_} * rows_, -1);
    next_.clear();
    item_.clear();
}

std::uint32_t Layout::Grid::column(float x) const noexcept {
    return static_cast<std::uint32_t>(std::clamp(to_int((x - origin_x_) / cell_), 0, static_cast<int>(columns_) - 1));
}

std::uint32_t Layout::Grid::row(float y) const noexcept {
    return static_cast<std::uint32_t>(std::clamp(to_int((y - origin_y_) / cell_), 0, static_cast<int>(rows_) - 1));
}

void Layout::Grid::add(std::uint32_t index, const labels::Box& box) {
    for (std::uint32_t r = row(box.t); r <= row(box.b); ++r) {
        for (std::uint32_t c = column(box.l); c <= column(box.r); ++c) {
            const std::size_t k = (std::size_t{r} * columns_) + c;
            item_.push_back(index);
            next_.push_back(head_[k]);
            head_[k] = static_cast<std::int32_t>(item_.size() - 1);
        }
    }
}

std::int64_t Layout::Grid::first_overlap(const labels::Box& box, std::span<const labels::Box> boxes) const {
    std::int64_t best = -1;
    for (std::uint32_t r = row(box.t); r <= row(box.b); ++r) {
        for (std::uint32_t c = column(box.l); c <= column(box.r); ++c) {
            for (std::int32_t e = head_[(std::size_t{r} * columns_) + c]; e >= 0;
                 e = next_[static_cast<std::size_t>(e)]) {
                const std::uint32_t j = item_[static_cast<std::size_t>(e)];
                if (labels::overlaps(box, boxes[j]) && (best < 0 || std::int64_t{j} < best)) best = j;
            }
        }
    }
    return best;
}

} // namespace atlas::markers
