#include "labels/Collision.hpp"

#include <algorithm>
#include <cmath>

namespace atlas::labels {

namespace {

constexpr std::uint32_t none = UINT32_MAX;

/// The cell of coordinate `v` along an axis of `count` cells of `cell` px, clamped to the axis (NaN to its first).
[[nodiscard]] std::uint32_t cell_at(float v, float cell, std::uint32_t count) noexcept {
    const float c = std::floor(v / cell);
    if (!(c > 0.0F)) return 0;
    if (c >= static_cast<float>(count - 1)) return count - 1;
    return static_cast<std::uint32_t>(c);
}

} // namespace

bool overlaps_any(std::span<const Box> boxes, const Box& box) noexcept {
    return std::ranges::any_of(boxes, [&](const Box& b) { return overlaps(b, box); });
}

void CollisionIndex::reset(float width, float height, float cell) {
    cell_ = cell > 0.0F ? cell : default_cell;
    const auto cells = [&](float extent) {
        const float n = std::ceil(extent / cell_);
        return n >= 1.0F && n < 4096.0F ? static_cast<std::uint32_t>(n) : 1U;
    };
    columns_ = cells(width);
    rows_ = cells(height);
    heads_.assign(static_cast<std::size_t>(columns_) * rows_, none);
    boxes_.clear();
    entry_box_.clear();
    entry_next_.clear();
}

CollisionIndex::Span CollisionIndex::cells_of(const Box& box) const noexcept {
    return {.x0 = cell_at(box.l, cell_, columns_),
            .y0 = cell_at(box.t, cell_, rows_),
            .x1 = cell_at(box.r, cell_, columns_),
            .y1 = cell_at(box.b, cell_, rows_)};
}

void CollisionIndex::insert(const Box& box) {
    const auto index = static_cast<std::uint32_t>(boxes_.size());
    boxes_.push_back(box);
    const Span s = cells_of(box);
    for (std::uint32_t y = s.y0; y <= s.y1; ++y) {
        for (std::uint32_t x = s.x0; x <= s.x1; ++x) {
            std::uint32_t& head = heads_[(static_cast<std::size_t>(y) * columns_) + x];
            entry_box_.push_back(index);
            entry_next_.push_back(head);
            head = static_cast<std::uint32_t>(entry_box_.size() - 1);
        }
    }
}

bool CollisionIndex::hits(const Box& box) const noexcept {
    if (boxes_.empty()) return false;
    const Span s = cells_of(box);
    for (std::uint32_t y = s.y0; y <= s.y1; ++y) {
        for (std::uint32_t x = s.x0; x <= s.x1; ++x) {
            for (std::uint32_t e = heads_[(static_cast<std::size_t>(y) * columns_) + x]; e != none;
                 e = entry_next_[e]) {
                if (overlaps(boxes_[entry_box_[e]], box)) return true;
            }
        }
    }
    return false;
}

} // namespace atlas::labels
