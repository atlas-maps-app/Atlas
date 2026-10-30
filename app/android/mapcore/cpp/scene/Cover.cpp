// The cover (scene/Cover.hpp).
#include "scene/Cover.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <span>

namespace atlas::scene {

void Cover::request(const tiles::TileKey& key, Lane lane, double priority, std::uint8_t tolerance,
                    std::string_view pack) {
    for (Request& r : requests_) {
        if (r.key == key && r.pack == pack) {
            if (lane < r.lane || (lane == r.lane && priority > r.priority)) {
                r.lane = lane;
                r.priority = priority;
            }
            return;
        }
    }
    requests_.push_back({.key = key, .pack = pack, .lane = lane, .priority = priority, .level = tolerance});
}

void Cover::draw(const ResidentTile& tile, std::uint64_t cells, const view::View& view, const view::Frames& frames,
                 const Frustum& frustum, tiles::TileKey wanted, double seconds) {
    // Its fade: from the frame it was first drawn in.
    Shown* shown = nullptr;
    for (Shown& s : shown_) {
        if (s.handle == tile.handle) shown = &s;
    }
    if (shown == nullptr) shown = &shown_.emplace_back(Shown{.handle = tile.handle, .since = seconds, .drawn = false});
    shown->drawn = true;
    const double fade =
        fade_seconds_ > 0 ? pymath::max(0.0, pymath::min(1.0, (seconds - shown->since) / fade_seconds_)) : 1.0;
    if (fade < 1.0) fading_ = true;
    const view::Vec3 target = view.camera().spec().target;
    const view::Placement p = frames.place(tile.frame, tile.origin, target, seconds);
    const Box box = box_of(tile.reach.box, tile.reach.top_cm, tile.frame);
    tiles_.push_back({.key = wanted,
                      .drawn = &tile,
                      .cells = cells,
                      .fade = static_cast<float>(fade),
                      .place = view.tile_place(p.origin, p.scale, fade),
                      .distance = frustum.distance(box),
                      .share = frustum.share(box)});
}

void Cover::update(const view::View& view, const view::Frames& frames, const CoverInputs& in,
                   const Residency& residency, double seconds) {
    tiles_.clear();
    requests_.clear();
    pending_.clear();
    missing_ = 0;
    fading_ = false;
    fade_seconds_ = in.fade_seconds;
    for (Shown& s : shown_) s.drawn = false;
    pack_id_.clear();
    coarser_id_.clear();
    if (in.pack == nullptr || !in.pack->pack.is_render() || in.pack->pack.head() == nullptr) {
        shown_.clear();
        return;
    }
    pack_id_ = in.pack->id;
    cover(*in.pack, pack_id_, true, view, frames, in, in.tolerance, std::nullopt, {}, residency, seconds);
    const bool coarser = in.coarser != nullptr && in.coarser != in.pack && in.coarser->pack.is_render() &&
                         in.coarser->pack.head() != nullptr;
    if (coarser) {
        coarser_id_ = in.coarser->id;
        cover(*in.coarser, coarser_id_, false, view, frames, in, in.coarser_tolerance, in.mask, {}, residency, seconds);
    }
    // The neighbours, in the world's order, each where neither the active nor the coarser pack nor a neighbour before
    // it has a tile of its finest level.
    std::array<const tiles::StorePack*, 2 + max_neighbours> above{in.pack, coarser ? in.coarser : nullptr};
    std::size_t above_count = 2;
    for (std::size_t i = 0; i < max_neighbours; ++i) {
        const tiles::StorePack* n = in.neighbours.at(i);
        if (n == nullptr || !n->pack.is_render() || n->pack.head() == nullptr) continue;
        const std::span<const tiles::StorePack* const> nearer = std::span(above).first(above_count);
        if (std::ranges::find(nearer, n) != nearer.end()) continue; // drawn already
        neighbour_ids_.at(i) = n->id;
        cover(*n, neighbour_ids_.at(i), false, view, frames, in, in.neighbour_tolerances.at(i), std::nullopt, nearer,
              residency, seconds);
        above.at(above_count++) = n;
    }
    std::erase_if(shown_, [](const Shown& s) { return !s.drawn; });
    // stable: tiles at one distance keep the cover's order, on every libc++
    std::ranges::stable_sort(tiles_, [](const CoverTile& a, const CoverTile& b) { return a.distance < b.distance; });
    std::ranges::sort(requests_, [](const Request& a, const Request& b) {
        return a.lane != b.lane ? a.lane < b.lane : a.priority > b.priority;
    });
}

void Cover::cover(const tiles::StorePack& store_pack, std::string_view id, bool active, const view::View& view,
                  const view::Frames& frames, const CoverInputs& in, std::uint8_t tolerance,
                  const std::optional<tiles::GridBox>& mask, std::span<const tiles::StorePack* const> above,
                  const Residency& residency, double seconds) {
    const tiles::Pack& pack = store_pack.pack;
    const tiles::RenderHead& head = *pack.head();
    if (head.levels.empty()) return;
    // Boxes and cells in the frame drawn (their grid boxes are degrees): the camera's.
    const view::GroundFrame frame = frames.current().value_or(view::frame_of(head.frame));
    const Frustum frustum(view.camera(), in.margin_px, in.far);
    const std::uint8_t fine = head.levels[0];
    const bool has_coarse = head.levels.size() > 1;
    const std::uint8_t coarse = has_coarse ? head.levels[1] : fine;
    const std::uint32_t gap = has_coarse ? static_cast<std::uint32_t>(fine - coarse) : 0;
    // A chunk wholly inside the mask (the active pack's box) is left out: the active pack draws there.
    auto masked = [&mask](const tiles::GridBox& b) {
        return mask && b.south >= mask->south && b.north <= mask->north && b.west >= mask->west && b.east <= mask->east;
    };
    const std::size_t pending_from = pending_.size();

    // An ancestor's cells over a descendant it stands in for, drawn once whatever how many it stands in for.
    auto stand_in = [&](const tiles::TileKey& parent, std::uint32_t x, std::uint32_t y) {
        const ResidentTile* r = residency.find(parent, id);
        if (r == nullptr) return false;
        const std::uint64_t over = cells_over(r->cells_per_side, gap, x, y);
        for (std::size_t k = pending_from; k < pending_.size(); ++k) {
            if (pending_[k].key == parent) {
                pending_[k].cells |= over;
                return true;
            }
        }
        pending_.push_back({.key = parent, .cells = over});
        return true;
    };

    // A tile where a pack above this one has a tile of its finest level is that pack's to draw.
    auto taken = [&above](const tiles::TileKey& key) {
        return std::ranges::any_of(above, [&key](const tiles::StorePack* p) {
            return p != nullptr && p->pack.head() != nullptr && !p->pack.head()->levels.empty() &&
                   p->pack.head()->levels[0] == key.z && p->pack.chunks().find(key) != nullptr;
        });
    };

    for (const tiles::ChunkRef& c : pack.chunks().level(fine)) {
        if (masked(c.reach.box) || taken(c.tile)) continue;
        const Box box = box_of(c.reach.box, c.reach.top_cm, frame);
        if (!frustum.sees(box)) continue;
        const double d = frustum.distance(box);
        const double share = frustum.share(box);
        const double priority = share + 1.0e-3 / (1.0 + d); // by share of the screen, nearest first
        const tiles::TileKey parent{coarse, c.tile.x >> gap, c.tile.y >> gap};
        if ((active && in.pinned) || d <= in.fine_reach || !has_coarse) {
            if (const ResidentTile* r = residency.find(c.tile, id)) {
                const std::uint64_t cells = visible_cells(r->cells, r->cells_per_side, frame, frustum);
                if (cells != 0) draw(*r, cells, view, frames, frustum, c.tile, seconds);
                continue;
            }
            if (active) ++missing_;
            request(c.tile, Lane::visible, priority, tolerance, id);
            if (has_coarse) {
                request(parent, Lane::ancestor, priority, tolerance, id);
                (void)stand_in(parent, c.tile.x, c.tile.y);
            }
        } else if (!stand_in(parent, c.tile.x, c.tile.y)) {
            request(parent, Lane::visible, priority, tolerance, id);
        }
    }
    // The next level's cells where the finest level has no tile.
    if (has_coarse) {
        for (const tiles::ChunkRef& c : pack.chunks().level(coarse)) {
            if (masked(c.reach.box)) continue;
            const Box box = box_of(c.reach.box, c.reach.top_cm, frame);
            if (!frustum.sees(box)) continue;
            const std::uint32_t n = std::uint32_t{1} << gap;
            for (std::uint32_t dy = 0; dy < n; ++dy) {
                for (std::uint32_t dx = 0; dx < n; ++dx) {
                    const tiles::TileKey child{fine, (c.tile.x << gap) + dx, (c.tile.y << gap) + dy};
                    if (pack.chunks().find(child) != nullptr) continue;
                    if (!stand_in(c.tile, child.x, child.y)) {
                        const double d = frustum.distance(box);
                        request(c.tile, Lane::visible, frustum.share(box) + 1.0e-3 / (1.0 + d), tolerance, id);
                    }
                }
            }
        }
    }
    for (std::size_t k = pending_from; k < pending_.size(); ++k) {
        const ResidentTile* r = residency.find(pending_[k].key, id);
        if (r == nullptr) continue;
        const std::uint64_t cells = visible_cells(r->cells, r->cells_per_side, frame, frustum) & pending_[k].cells;
        if (cells != 0) draw(*r, cells, view, frames, frustum, pending_[k].key, seconds);
    }
}

} // namespace atlas::scene
