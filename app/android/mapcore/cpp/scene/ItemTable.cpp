// The item table (scene/ItemTable.hpp).
#include "scene/ItemTable.hpp"

namespace atlas::scene {

std::uint32_t ItemTable::add(const ItemSpec& item) {
    std::uint32_t row = 0;
    if (!free_.empty()) {
        row = free_.back();
        free_.pop_back();
    } else {
        row = static_cast<std::uint32_t>(x_.size());
        x_.push_back(0);
        y_.push_back(0);
        z_.push_back(0);
        offset_.push_back(0);
        cut_x_.push_back(0);
        cut_z_.push_back(0);
        order_.push_back(0);
        kind_.push_back(0);
        flags_.push_back(0);
        min_depth_.push_back(0);
        radius_.push_back(0);
        tile_.push_back(0);
        slots_.resize(slots_.size() + bands, 0);
        alive_.push_back(0);
    }
    x_[row] = item.x;
    y_[row] = item.y;
    z_[row] = item.z;
    offset_[row] = item.offset;
    cut_x_[row] = item.cut_x;
    cut_z_[row] = item.cut_z;
    order_[row] = item.order;
    kind_[row] = item.kind;
    flags_[row] = item.flags;
    min_depth_[row] = item.min_depth;
    radius_[row] = item.radius;
    tile_[row] = item.tile;
    for (std::size_t b = 0; b < bands; ++b) slots_[row * bands + b] = item.slots[b];
    alive_[row] = 1;
    ++live_;
    ++changes_;
    return row;
}

void ItemTable::remove(std::uint32_t row) noexcept {
    if (!alive(row)) return;
    alive_[row] = 0;
    free_.push_back(row);
    --live_;
    ++changes_;
}

std::size_t ItemTable::remove_tile(std::uint32_t tile) noexcept {
    std::size_t n = 0;
    for (std::uint32_t row = 0; row < alive_.size(); ++row) {
        if (alive_[row] != 0 && tile_[row] == tile) {
            remove(row);
            ++n;
        }
    }
    return n;
}

void ItemTable::clear() noexcept {
    x_.clear();
    y_.clear();
    z_.clear();
    offset_.clear();
    cut_x_.clear();
    cut_z_.clear();
    order_.clear();
    kind_.clear();
    flags_.clear();
    min_depth_.clear();
    radius_.clear();
    tile_.clear();
    slots_.clear();
    alive_.clear();
    free_.clear();
    live_ = 0;
    ++changes_;
}

} // namespace atlas::scene
