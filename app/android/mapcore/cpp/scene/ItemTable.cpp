// The item table (scene/ItemTable.hpp).
#include "scene/ItemTable.hpp"

#include <algorithm>

namespace atlas::scene {

std::uint32_t ItemTable::add(const ItemSpec& item) {
    std::uint32_t row = 0;
    if (!free_.empty()) {
        row = free_.back();
        free_.pop_back();
    } else {
        row = static_cast<std::uint32_t>(x_.size());
        // no room: every column to twice the rows at once, so that they stay of one size
        if (row == capacity()) reserve(std::max<std::size_t>(2 * capacity(), 64));
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
        max_depth_.push_back(0);
        radius_.push_back(0);
        tile_.push_back(0);
        owner_.push_back(0);
        building_.push_back(0);
        cell_.push_back(0);
        place_.push_back(0);
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
    max_depth_[row] = item.max_depth;
    radius_[row] = item.radius;
    tile_[row] = item.tile;
    owner_[row] = item.owner;
    building_[row] = item.building;
    cell_[row] = item.cell;
    place_[row] = 0;
    for (std::size_t b = 0; b < bands; ++b) slots_[row * bands + b] = item.slots[b];
    alive_[row] = 1;
    ++live_;
    ++changes_;
    return row;
}

void ItemTable::reserve(std::size_t rows) {
    if (rows <= capacity()) return;
    x_.reserve(rows);
    y_.reserve(rows);
    z_.reserve(rows);
    offset_.reserve(rows);
    cut_x_.reserve(rows);
    cut_z_.reserve(rows);
    order_.reserve(rows);
    kind_.reserve(rows);
    flags_.reserve(rows);
    min_depth_.reserve(rows);
    max_depth_.reserve(rows);
    radius_.reserve(rows);
    tile_.reserve(rows);
    owner_.reserve(rows);
    building_.reserve(rows);
    cell_.reserve(rows);
    place_.reserve(rows);
    slots_.reserve(rows * bands);
    alive_.reserve(rows); // last: capacity() reads it, so a reserve that threw part way is done again
    ++growths_;
}

void ItemTable::update(std::uint32_t row, const ItemSpec& item) noexcept {
    if (!alive(row)) return;
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
    max_depth_[row] = item.max_depth;
    radius_[row] = item.radius;
    tile_[row] = item.tile;
    owner_[row] = item.owner;
    building_[row] = item.building;
    cell_[row] = item.cell;
    for (std::size_t b = 0; b < bands; ++b) slots_[row * bands + b] = item.slots[b];
}

void ItemTable::set_flag(std::uint32_t row, std::uint16_t flag, bool on) noexcept {
    if (!alive(row)) return;
    flags_[row] = static_cast<std::uint16_t>(on ? (flags_[row] | flag) : (flags_[row] & ~flag));
}

std::uint32_t ItemTable::add_place(const ItemPlace& place) {
    places_.push_back(place);
    return static_cast<std::uint32_t>(places_.size() - 1);
}

void ItemTable::move_place(std::uint32_t place, const ItemPlace& to) noexcept {
    if (place != 0 && place < places_.size()) places_[place] = to;
}

void ItemTable::set_place(std::uint32_t row, std::uint32_t place) noexcept {
    if (alive(row) && place < places_.size()) place_[row] = place;
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
    max_depth_.clear();
    radius_.clear();
    tile_.clear();
    owner_.clear();
    building_.clear();
    cell_.clear();
    place_.clear();
    slots_.clear();
    alive_.clear();
    free_.clear();
    live_ = 0;
    ++changes_;
}

} // namespace atlas::scene
