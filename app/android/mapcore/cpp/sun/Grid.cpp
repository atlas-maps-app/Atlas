#include "sun/Grid.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <utility>

namespace atlas::sun {

namespace {

namespace pm = atlas::pymath;

/// A 64-bit mix of a cell key for the table (the keys of nearby cells differ in few bits).
[[nodiscard]] std::uint64_t mixed(std::uint64_t key) noexcept {
    key ^= key >> 33U;
    key *= 0xFF51'AFD7'ED55'8CCDULL;
    key ^= key >> 33U;
    key *= 0xC4CE'B9FE'1A85'EC53ULL;
    key ^= key >> 33U;
    return key;
}

} // namespace

std::int64_t cell_of(double v) noexcept {
    // PyMath.floorInt: int(math.floor(x)), exact for every finite coordinate the scene holds
    const double f = std::floor(v / cell_size);
    ATLAS_DEBUG_ASSERT(std::isfinite(f) && std::abs(f) < 9.0e18, "a coordinate off every grid");
    return static_cast<std::int64_t>(f);
}

PrismId Grid::Builder::add(std::span<const double> x, std::span<const double> z, double h) {
    ATLAS_ASSERT(!x.empty() && x.size() == z.size(), "a footprint is a ring of x and z");
    ATLAS_ASSERT(hs_.size() < no_prism, "too many solids for one grid");
    // the box as Index's init writes it: Python's min and max of the xs and of the zs, each the first of its equals
    double min_x = x[0];
    double max_x = x[0];
    double min_z = z[0];
    double max_z = z[0];
    for (std::size_t i = 1; i < x.size(); ++i) {
        min_x = pm::min(min_x, x[i]);
        max_x = pm::max(max_x, x[i]);
        min_z = pm::min(min_z, z[i]);
        max_z = pm::max(max_z, z[i]);
    }
    for (std::size_t i = 0; i < x.size(); ++i) {
        xz_.push_back(x[i]);
        xz_.push_back(z[i]);
    }
    first_.push_back(static_cast<std::uint32_t>(xz_.size() / 2));
    boxes_.insert(boxes_.end(), {min_x, max_x, min_z, max_z});
    maxh_ = hs_.empty() ? h : pm::max(maxh_, h);
    hs_.push_back(h);
    return static_cast<PrismId>(hs_.size() - 1);
}

Grid Grid::Builder::build(double scene_maxh) && {
    ATLAS_ASSERT(hs_.empty() || !(scene_maxh < maxh_), "the scene's tallest solid is below one the grid holds");
    Grid g;
    g.maxh_ = scene_maxh;
    // every (cell, solid) pair, each solid in each cell its box covers, as Index's init lists them
    std::vector<std::pair<std::uint64_t, PrismId>> pairs;
    pairs.reserve(hs_.size() * 4);
    for (std::size_t k = 0; k < hs_.size(); ++k) {
        const double* b = &boxes_[4 * k];
        const std::int64_t i0 = cell_of(b[0]);
        const std::int64_t i1 = cell_of(b[1]);
        const std::int64_t j0 = cell_of(b[2]);
        const std::int64_t j1 = cell_of(b[3]);
        for (std::int64_t i = i0; i <= i1; ++i) {
            for (std::int64_t j = j0; j <= j1; ++j) pairs.emplace_back(cell_key(i, j), static_cast<PrismId>(k));
        }
    }
    std::ranges::sort(pairs); // by cell, then by the solids' order
    g.entries_.reserve(pairs.size());
    std::size_t keys = 0;
    for (std::size_t p = 0; p < pairs.size(); ++p) {
        if (p == 0 || pairs[p].first != pairs[p - 1].first) ++keys;
        const PrismId k = pairs[p].second;
        const double* b = &boxes_[4 * std::size_t{k}];
        g.entries_.push_back({.min_x = b[0], .max_x = b[1], .min_z = b[2], .max_z = b[3], .h = hs_[k], .id = k});
    }
    ATLAS_ASSERT(g.entries_.size() < 0xFFFF'FFFFU, "too many cell entries for one grid");
    const std::size_t capacity = std::bit_ceil(std::max<std::size_t>(16, 2 * keys));
    g.slots_.assign(capacity, Slot{.key = 0, .first = 0, .count = 0});
    const std::size_t mask = capacity - 1;
    for (std::size_t p = 0; p < pairs.size();) {
        std::size_t q = p;
        while (q < pairs.size() && pairs[q].first == pairs[p].first) ++q;
        std::size_t s = mixed(pairs[p].first) & mask;
        while (g.slots_[s].count != 0) s = (s + 1) & mask;
        g.slots_[s] = {
            .key = pairs[p].first, .first = static_cast<std::uint32_t>(p), .count = static_cast<std::uint32_t>(q - p)};
        ++g.slots_used_;
        p = q;
    }
    g.xz_ = std::move(xz_);
    g.first_ = std::move(first_);
    g.hs_ = std::move(hs_);
    return g;
}

std::span<const CellEntry> Grid::cell(std::uint64_t key) const noexcept {
    if (slots_.empty()) return {};
    const std::size_t mask = slots_.size() - 1;
    for (std::size_t s = mixed(key) & mask;; s = (s + 1) & mask) {
        const Slot& slot = slots_[s];
        if (slot.count == 0) return {};
        if (slot.key == key) return std::span<const CellEntry>(entries_).subspan(slot.first, slot.count);
    }
}

bool inside(double x, double z, std::span<const double> xz) noexcept {
    // Real.pipXZ, each operation in its order: the crossing's x is (xj - xi) * (z - zi) / (zj - zi + 1e-12) + xi
    bool ins = false;
    const std::size_t n = xz.size() / 2;
    std::size_t j = n - 1;
    for (std::size_t i = 0; i < n; ++i) {
        const double xi = xz[2 * i];
        const double zi = xz[(2 * i) + 1];
        const double xj = xz[2 * j];
        const double zj = xz[(2 * j) + 1];
        if (((zi > z) != (zj > z)) && x < (((xj - xi) * (z - zi)) / ((zj - zi) + 1e-12)) + xi) ins = !ins;
        j = i;
    }
    return ins;
}

} // namespace atlas::sun
