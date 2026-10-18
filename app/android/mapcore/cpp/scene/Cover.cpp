// The cover (scene/Cover.hpp).
#include "scene/Cover.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>

namespace atlas::scene {

void Cover::request(const tiles::TileKey& key, Lane lane, double priority, std::uint8_t tolerance) {
    for (Request& r : requests_) {
        if (r.key == key) {
            if (lane < r.lane || (lane == r.lane && priority > r.priority)) {
                r.lane = lane;
                r.priority = priority;
            }
            return;
        }
    }
    requests_.push_back({.key = key, .pack = pack_id_, .lane = lane, .priority = priority, .level = tolerance});
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
    const double fade = pymath::max(0.0, pymath::min(1.0, (seconds - shown->since) / 0.18));
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
    for (Shown& s : shown_) s.drawn = false;
    if (in.pack == nullptr || !in.pack->pack.is_render() || in.pack->pack.head() == nullptr) {
        shown_.clear();
        return;
    }
    const tiles::Pack& pack = in.pack->pack;
    const tiles::RenderHead& head = *pack.head();
    if (head.levels.empty()) return;
    pack_id_ = in.pack->id;
    const view::GroundFrame frame = view::frame_of(head.frame);
    const Frustum frustum(view.camera(), in.margin_px, in.far);
    const std::uint8_t fine = head.levels[0];
    const bool has_coarse = head.levels.size() > 1;
    const std::uint8_t coarse = has_coarse ? head.levels[1] : fine;
    const std::uint32_t gap = has_coarse ? static_cast<std::uint32_t>(fine - coarse) : 0;

    // An ancestor's cells over a descendant it stands in for, drawn once whatever how many it stands in for.
    auto stand_in = [&](const tiles::TileKey& parent, std::uint32_t x, std::uint32_t y) {
        const ResidentTile* r = residency.find(parent, pack_id_);
        if (r == nullptr) return false;
        const std::uint64_t over = cells_over(r->cells_per_side, gap, x, y);
        for (Pending& p : pending_) {
            if (p.key == parent) {
                p.cells |= over;
                return true;
            }
        }
        pending_.push_back({.key = parent, .cells = over});
        return true;
    };

    for (const tiles::ChunkRef& c : pack.chunks().level(fine)) {
        const Box box = box_of(c.reach.box, c.reach.top_cm, frame);
        if (!frustum.sees(box)) continue;
        const double d = frustum.distance(box);
        const double share = frustum.share(box);
        const double priority = share + 1.0e-3 / (1.0 + d); // by share of the screen, nearest first
        const tiles::TileKey parent{coarse, c.tile.x >> gap, c.tile.y >> gap};
        if (in.pinned || d <= in.fine_reach || !has_coarse) {
            if (const ResidentTile* r = residency.find(c.tile, pack_id_)) {
                const std::uint64_t cells = visible_cells(r->cells, r->cells_per_side, frame, frustum);
                if (cells != 0) draw(*r, cells, view, frames, frustum, c.tile, seconds);
                continue;
            }
            ++missing_;
            request(c.tile, Lane::visible, priority, in.tolerance);
            if (has_coarse) {
                request(parent, Lane::ancestor, priority, in.tolerance);
                (void)stand_in(parent, c.tile.x, c.tile.y);
            }
        } else if (!stand_in(parent, c.tile.x, c.tile.y)) {
            request(parent, Lane::visible, priority, in.tolerance);
        }
    }
    // The next level's cells where the finest level has no tile.
    if (has_coarse) {
        for (const tiles::ChunkRef& c : pack.chunks().level(coarse)) {
            const Box box = box_of(c.reach.box, c.reach.top_cm, frame);
            if (!frustum.sees(box)) continue;
            const std::uint32_t n = std::uint32_t{1} << gap;
            for (std::uint32_t dy = 0; dy < n; ++dy) {
                for (std::uint32_t dx = 0; dx < n; ++dx) {
                    const tiles::TileKey child{fine, (c.tile.x << gap) + dx, (c.tile.y << gap) + dy};
                    if (pack.chunks().find(child) != nullptr) continue;
                    if (!stand_in(c.tile, child.x, child.y)) {
                        const double d = frustum.distance(box);
                        request(c.tile, Lane::visible, frustum.share(box) + 1.0e-3 / (1.0 + d), in.tolerance);
                    }
                }
            }
        }
    }
    for (const Pending& p : pending_) {
        const ResidentTile* r = residency.find(p.key, pack_id_);
        if (r == nullptr) continue;
        const std::uint64_t cells = visible_cells(r->cells, r->cells_per_side, frame, frustum) & p.cells;
        if (cells != 0) draw(*r, cells, view, frames, frustum, p.key, seconds);
    }
    std::erase_if(shown_, [](const Shown& s) { return !s.drawn; });
    std::ranges::sort(tiles_, [](const CoverTile& a, const CoverTile& b) { return a.distance < b.distance; });
    std::ranges::sort(requests_, [](const Request& a, const Request& b) {
        return a.lane != b.lane ? a.lane < b.lane : a.priority > b.priority;
    });
}

} // namespace atlas::scene
