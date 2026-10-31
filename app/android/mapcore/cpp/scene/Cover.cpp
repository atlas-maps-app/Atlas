// The cover (scene/Cover.hpp).
#include "scene/Cover.hpp"

#include "base/pymath/PyMath.hpp"
#include "tiles/Grid.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <numeric>
#include <optional>
#include <span>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace atlas::scene {

namespace {

/// The heaps' bytes a resident tile holds: every pass's ranges.
[[nodiscard]] std::uint64_t heap_bytes(const ResidentTile& t) noexcept {
    std::uint64_t n = 0;
    for (const PassTile& p : t.passes) {
        for (const HeapRange& r : p.ranges) n += r.size;
    }
    return n;
}

/// The coarsest level a place has a tile of (an index into its keys; 0: the finest alone).
[[nodiscard]] std::size_t coarsest_of(const std::array<std::optional<tiles::TileKey>, 3>& keys) noexcept {
    std::size_t k = keys.size();
    while (k-- > 1) {
        if (keys.at(k)) return k;
    }
    return 0;
}

/// A tile's number among the packs' tiles, for the bytes remembered and counted: its pack's id hashed with its key.
[[nodiscard]] std::uint64_t tile_id(std::string_view pack, const tiles::TileKey& k) noexcept {
    const std::uint64_t key = (std::uint64_t{k.z} << 58U) | (std::uint64_t{k.x} << 29U) | std::uint64_t{k.y};
    return std::hash<std::string_view>{}(pack) ^ (key * 0x9E37'79B9'7F4A'7C15ULL);
}

} // namespace

void Cover::request(const tiles::TileKey& key, Lane lane, double priority, std::uint8_t tolerance,
                    std::string_view pack, std::uint64_t bytes) {
    for (Request& r : requests_) {
        if (r.key == key && r.pack == pack) {
            if (lane < r.lane || (lane == r.lane && priority > r.priority)) {
                r.lane = lane;
                r.priority = priority;
            }
            return;
        }
    }
    requests_.push_back(
        {.key = key, .pack = pack, .lane = lane, .priority = priority, .level = tolerance, .bytes = bytes});
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
    spots_.clear();
    kept_.clear();
    planning_ = in.budget > 0;
    drawn_count_ = 0;
    planned_ = 0;
    coarsened_ = 0;
    holes_ = 0;
    near_holes_ = 0;
    stand_ins_.clear();
    spare_.clear();
    standing_.clear();
    backing_.clear();
    planned_handles_.clear();
    missing_ = 0;
    fading_ = false;
    fade_seconds_ = in.fade_seconds;
    fine_far_ = in.fine_far;
    for (Shown& s : shown_) s.drawn = false;
    pack_id_.clear();
    coarser_id_.clear();
    if (in.pack == nullptr || !in.pack->pack.is_render() || in.pack->pack.head() == nullptr) {
        shown_.clear();
        return;
    }
    const Frustum frustum(view.camera(), in.margin_px, in.far);
    // a frame cell's levels wanted with the levels alone; a region's own levels always (CoverInputs::levels)
    const auto levels = [&in](const tiles::StorePack& p) { return in.levels || p.kind != tiles::PackKind::window; };
    pack_id_ = in.pack->id;
    walk({.pack = in.pack, .id = pack_id_, .active = true, .tolerance = in.tolerance, .levels = levels(*in.pack)},
         frames, frustum, in, std::nullopt, {});
    const bool coarser = in.coarser != nullptr && in.coarser != in.pack && in.coarser->pack.is_render() &&
                         in.coarser->pack.head() != nullptr;
    if (coarser) {
        coarser_id_ = in.coarser->id;
        walk({.pack = in.coarser,
              .id = coarser_id_,
              .active = false,
              .tolerance = in.coarser_tolerance,
              .levels = levels(*in.coarser)},
             frames, frustum, in, in.mask, {});
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
        walk({.pack = n,
              .id = neighbour_ids_.at(i),
              .active = false,
              .tolerance = in.neighbour_tolerances.at(i),
              .levels = levels(*n)},
             frames, frustum, in, std::nullopt, nearer);
        above.at(above_count++) = n;
    }
    if (!spots_.empty()) measure(residency);
    plan(in, residency);
    for (std::size_t p = 0; p < drawn_count_; ++p) emit(p, view, frames, frustum, residency, seconds);
    sort_out(residency);
    std::erase_if(shown_, [](const Shown& s) { return !s.drawn; });
    // stable: tiles at one distance keep the cover's order, on every libc++
    std::ranges::stable_sort(tiles_, [](const CoverTile& a, const CoverTile& b) { return a.distance < b.distance; });
    std::ranges::sort(requests_, [](const Request& a, const Request& b) {
        return a.lane != b.lane ? a.lane < b.lane : a.priority > b.priority;
    });
}

void Cover::walk(const Drawn& drawn, const view::Frames& frames, const Frustum& frustum, const CoverInputs& in,
                 const std::optional<tiles::GridBox>& mask, std::span<const tiles::StorePack* const> above) {
    const tiles::Pack& pack = drawn.pack->pack;
    const tiles::RenderHead& head = *pack.head();
    if (head.levels.empty()) return;
    const auto index = static_cast<std::uint8_t>(drawn_count_);
    drawn_.at(drawn_count_++) = drawn;
    // Boxes and cells in the frame drawn (their grid boxes are degrees): the camera's.
    const view::GroundFrame frame = frames.current().value_or(view::frame_of(head.frame));
    // The pack's levels, finest first: its places' (L14), and up to two coarser ones (a frame cell's coarse and far
    // levels, ENGINE.md one pack per country, step 7; a region's L12). A place asks for the finest whose reach holds
    // it: the finest within fine_reach, the next within coarse_reach where a third follows it, the last beyond.
    const std::uint8_t fine = head.levels[0];
    const std::size_t count = std::min<std::size_t>(head.levels.size(), 3);
    // A chunk wholly inside the mask (the active pack's box) is left out: the active pack draws there.
    auto masked = [&mask](const tiles::GridBox& b) {
        return mask && b.south >= mask->south && b.north <= mask->north && b.west >= mask->west && b.east <= mask->east;
    };
    // A tile where a pack above this one has a tile of its finest level is that pack's to draw.
    auto taken = [&above](const tiles::TileKey& key) {
        return std::ranges::any_of(above, [&key](const tiles::StorePack* p) {
            return p != nullptr && p->pack.head() != nullptr && !p->pack.head()->levels.empty() &&
                   p->pack.head()->levels[0] == key.z && p->pack.chunks().find(key) != nullptr;
        });
    };
    // One place of the finest level, at `d` from the eye, of priority `priority`: the level whose reach holds it,
    // where the pack has that level's tile there; beyond the coarser levels' reach, the place's own where the pack
    // holds no coarser tile there (a region without levels, a cell before the phone took them), within the reach the
    // cover had before the levels (fine_far): out to the levels' 30 km a cell's windows took the heaps, a place apiece.
    auto place = [&](std::uint32_t x, std::uint32_t y, double d, double priority, bool has_fine) {
        Spot s{.x = x, .y = y, .d = d, .priority = priority, .pack = index};
        if (has_fine) s.keys[0] = tiles::TileKey(fine, x, y);
        for (std::size_t k = 1; k < count; ++k) {
            const std::uint8_t z = head.levels[k];
            const tiles::TileKey key(z, x >> static_cast<std::uint32_t>(fine - z),
                                     y >> static_cast<std::uint32_t>(fine - z));
            if (pack.chunks().find(key) != nullptr) s.keys.at(k) = key;
        }
        // without its levels, a cell's place is its window within fine_far, as before them; its levels' tiles there
        // only stand in while it comes
        if (!drawn.levels) {
            if (!s.keys[0] || d > in.fine_far) return;
            spots_.push_back(s);
            return;
        }
        if (!s.keys[1] && !s.keys[2] && d > in.fine_far) return;
        if (s.keys[0] && ((drawn.active && in.pinned) || d <= in.fine_reach)) {
            s.want = 0;
        } else if (s.keys[1] && (count < 3 || !s.keys[2] || d <= in.coarse_reach)) {
            s.want = 1;
        } else if (s.keys[2]) {
            s.want = 2;
        } else if (!s.keys[0]) {
            return;
        }
        s.plan = s.want;
        spots_.push_back(s);
    };

    for (const tiles::ChunkRef& c : pack.chunks().level(fine)) {
        if (masked(c.reach.box) || taken(c.tile)) continue;
        const Box box = box_of(c.reach.box, c.reach.top_cm, frame);
        if (!frustum.sees(box)) continue;
        const double d = frustum.distance(box);
        place(c.tile.x, c.tile.y, d, frustum.share(box) + (1.0e-3 / (1.0 + d)), true); // by share, nearest first
    }
    // The coarser levels' places where no finer level has a tile (the sea, a lake's middle; the places of a frame
    // cell whose windows the phone has not taken), each at its own distance; none without the levels.
    for (std::size_t k = 1; k < count && drawn.levels; ++k) {
        const std::uint8_t z = head.levels[k];
        const auto gap = static_cast<std::uint32_t>(fine - z);
        const std::uint32_t n = std::uint32_t{1} << gap;
        for (const tiles::ChunkRef& c : pack.chunks().level(z)) {
            if (masked(c.reach.box)) continue;
            if (!frustum.sees(box_of(c.reach.box, c.reach.top_cm, frame))) continue;
            for (std::uint32_t dy = 0; dy < n; ++dy) {
                for (std::uint32_t dx = 0; dx < n; ++dx) {
                    const tiles::TileKey child{fine, (c.tile.x << gap) + dx, (c.tile.y << gap) + dy};
                    if (pack.chunks().find(child) != nullptr || taken(child)) continue;
                    // a finer coarse level's tile there: its own loop's place
                    bool finer = false;
                    for (std::size_t j = 1; j < k; ++j) {
                        const auto g = static_cast<std::uint32_t>(fine - head.levels[j]);
                        finer = finer || pack.chunks().find(
                                             tiles::TileKey(head.levels[j], child.x >> g, child.y >> g)) != nullptr;
                    }
                    if (finer) continue;
                    const std::array<std::int32_t, 4> b = tiles::tile_box(child);
                    const Box place_box =
                        box_of({.south = b[0], .west = b[1], .north = b[2], .east = b[3]}, c.reach.top_cm, frame);
                    if (!frustum.sees(place_box)) continue;
                    const double d = frustum.distance(place_box);
                    place(child.x, child.y, d, frustum.share(place_box) + (1.0e-3 / (1.0 + d)), false);
                }
            }
        }
    }
}

void Cover::measure(const Residency& residency) {
    std::array<double, 32> heap{};
    std::array<double, 32> raw{};
    double all_heap = 0;
    double all_raw = 0;
    if (bytes_.size() > 8192) bytes_.clear();
    for (const std::shared_ptr<const ResidentTile>& t : residency.resident()) {
        const Drawn* by = nullptr;
        for (std::size_t p = 0; p < drawn_count_; ++p) {
            if (drawn_.at(p).id == t->pack) by = &drawn_.at(p);
        }
        if (by == nullptr) continue;
        const std::uint64_t bytes = heap_bytes(*t);
        bytes_[tile_id(t->pack, t->key)] = bytes;
        const tiles::ChunkRef* c = by->pack->pack.chunks().find(t->key);
        if (c == nullptr || c->raw == 0 || t->key.z >= heap.size()) continue;
        heap.at(t->key.z) += static_cast<double>(bytes);
        raw.at(t->key.z) += static_cast<double>(c->raw);
        all_heap += static_cast<double>(bytes);
        all_raw += static_cast<double>(c->raw);
    }
    for (std::size_t z = 0; z < heap.size(); ++z) {
        if (raw.at(z) > 0) ratio_.at(z) = heap.at(z) / raw.at(z);
    }
    // a level none of whose tiles was resident yet: the resident tiles' of every level (the far level's come first)
    if (all_raw > 0) any_ = all_heap / all_raw;
}

std::uint64_t Cover::estimate(const Drawn& p, const tiles::TileKey& k, const Residency& residency) const {
    if (const ResidentTile* r = residency.find(k, p.id)) return heap_bytes(*r);
    if (const auto it = bytes_.find(tile_id(p.id, k)); it != bytes_.end()) return it->second;
    const tiles::ChunkRef* c = p.pack->pack.chunks().find(k);
    if (c == nullptr) return 0;
    const double ratio = k.z < ratio_.size() && ratio_.at(k.z) > 0 ? ratio_.at(k.z) : any_;
    return static_cast<std::uint64_t>(static_cast<double>(c->raw) * ratio);
}

void Cover::plan(const CoverInputs& in, const Residency& residency) {
    if (in.budget == 0 || spots_.empty()) return;
    std::unordered_set<std::uint64_t> counted;
    const auto take = [&](const Drawn& p, const tiles::TileKey& k, bool whatever) {
        const std::uint64_t id = tile_id(p.id, k);
        if (counted.contains(id)) return true;
        const std::uint64_t c = estimate(p, k, residency);
        if (!whatever && planned_ + c > in.budget) return false;
        planned_ += c;
        counted.insert(id);
        return true;
    };
    const auto coarsest = [](const Spot& s) { return coarsest_of(s.keys); };
    // every place's coarsest level, whatever it costs: the stand-in under every view; and the finest level within its
    // reach, whatever it costs: the near view drawn as it was before the levels
    for (const Spot& s : spots_) {
        if (const std::optional<tiles::TileKey>& k = s.keys.at(coarsest(s))) (void)take(drawn_.at(s.pack), *k, true);
        if (const std::optional<tiles::TileKey>& k = s.keys[0]; k && s.want == 0) {
            (void)take(drawn_.at(s.pack), *k, true);
        }
    }
    // then nearest first, each other place the level its distance asks for where it fits, else the next coarser that
    // fits
    std::vector<std::size_t> order(spots_.size());
    std::ranges::iota(order, std::size_t{0});
    std::ranges::stable_sort(order, {}, [this](std::size_t i) { return spots_[i].d; });
    for (const std::size_t i : order) {
        Spot& s = spots_[i];
        if (s.want == 0) continue;
        const std::size_t base = coarsest(s);
        s.plan = static_cast<std::uint8_t>(base);
        for (std::size_t k = s.want; k < base; ++k) {
            if (const std::optional<tiles::TileKey>& key = s.keys.at(k); key && take(drawn_.at(s.pack), *key, false)) {
                s.plan = static_cast<std::uint8_t>(k);
                break;
            }
        }
        if (s.plan > s.want) ++coarsened_;
    }
}

void Cover::emit(std::size_t p, const view::View& view, const view::Frames& frames, const Frustum& frustum,
                 const Residency& residency, double seconds) {
    const Drawn& drawn = drawn_.at(p);
    const std::string_view id = drawn.id;
    const tiles::RenderHead& head = *drawn.pack->pack.head();
    const view::GroundFrame frame = frames.current().value_or(view::frame_of(head.frame));
    const std::uint8_t fine = head.levels[0];
    const std::size_t count = std::min<std::size_t>(head.levels.size(), 3);
    const std::size_t pending_from = pending_.size();

    // An ancestor's cells over a descendant place (x, y) of the finest level, drawn once whatever how many it stands
    // in for (or the place's own tile, its plan's level: planned).
    auto stand_in = [&](const tiles::TileKey& ancestor, std::uint32_t x, std::uint32_t y, bool planned = false) {
        const ResidentTile* r = residency.find(ancestor, id);
        if (r == nullptr) return false;
        const std::uint64_t over = cells_over(r->cells_per_side, static_cast<std::uint32_t>(fine - ancestor.z), x, y);
        for (std::size_t k = pending_from; k < pending_.size(); ++k) {
            if (pending_[k].key == ancestor) {
                pending_[k].cells |= over;
                pending_[k].planned = pending_[k].planned || planned;
                return true;
            }
        }
        pending_.push_back({.key = ancestor, .cells = over, .planned = planned});
        return true;
    };

    // Each place drawn by its planned level; until that tile is resident, another level's that is resident stands in
    // (the finer one first: zooming out from it, then the coarser), and the cheapest coarser one is asked for as its
    // ancestor (design item 9: nothing pops, nothing is a hole).
    for (const Spot& s : spots_) {
        if (s.pack != p) continue;
        const std::array<std::optional<tiles::TileKey>, 3>& keys = s.keys;
        const std::size_t want = s.plan;
        const std::optional<tiles::TileKey>& planned = keys.at(want);
        if (!planned) continue; // the plan gives a place only a level it has a tile of
        const tiles::TileKey key = *planned;
        // its coarsest level kept under it while in view, the stand-in under every view (the plan counts it whatever
        // it costs), and asked for after the visible tiles where it is not resident yet
        if (const std::size_t base = coarsest_of(keys); planning_ && base > want) {
            if (const std::optional<tiles::TileKey>& under = keys.at(base)) {
                if (const ResidentTile* r = residency.find(*under, id)) {
                    keep(r->handle);
                } else {
                    request(*under, Lane::ancestor, s.priority, drawn.tolerance, id,
                            estimate(drawn, *under, residency));
                }
            }
        }
        if (want == 0) {
            if (const ResidentTile* r = residency.find(key, id)) {
                const std::uint64_t cells = visible_cells(r->cells, r->cells_per_side, frame, frustum);
                if (cells != 0) {
                    draw(*r, cells, view, frames, frustum, key, seconds);
                } else {
                    keep(r->handle);
                }
                planned_handles_.push_back(r->handle);
                continue;
            }
            if (drawn.active) ++missing_;
        } else if (stand_in(key, s.x, s.y, true)) {
            continue;
        }
        request(key, Lane::visible, s.priority, drawn.tolerance, id, estimate(drawn, key, residency));
        // the cheapest coarser level as its ancestor, while it comes (without the levels, none: they only stand in)
        for (std::size_t k = count; drawn.levels && k-- > want + 1;) {
            if (const std::optional<tiles::TileKey>& ancestor = keys.at(k)) {
                request(*ancestor, Lane::ancestor, s.priority, drawn.tolerance, id,
                        estimate(drawn, *ancestor, residency));
                break;
            }
        }
        // a resident level standing in: the finer ones first (zooming out from them), then the coarser
        const ResidentTile* stood = nullptr;
        for (std::size_t k = want; stood == nullptr && k-- > 0;) {
            const std::optional<tiles::TileKey>& finer = keys.at(k);
            if (!finer) continue;
            if (k == 0) {
                if (const ResidentTile* r = residency.find(*finer, id)) {
                    const std::uint64_t cells = visible_cells(r->cells, r->cells_per_side, frame, frustum);
                    if (cells != 0) draw(*r, cells, view, frames, frustum, *finer, seconds);
                    stood = r;
                }
            } else if (stand_in(*finer, s.x, s.y)) {
                stood = residency.find(*finer, id);
            }
        }
        for (std::size_t k = want + 1; stood == nullptr && k < count; ++k) {
            const std::optional<tiles::TileKey>& coarser = keys.at(k);
            if (coarser && stand_in(*coarser, s.x, s.y)) stood = residency.find(*coarser, id);
        }
        if (stood == nullptr) {
            ++holes_;
            if (keys[0] && s.d <= fine_far_) ++near_holes_;
            continue;
        }
        // the place's other resident level, which would stand in without it
        std::optional<tiles::TileKey> backup;
        for (std::size_t k = 0; k < count; ++k) {
            const std::optional<tiles::TileKey>& other = keys.at(k);
            if (k == want || !other || *other == stood->key) continue;
            if (const ResidentTile* r = residency.find(*other, id)) {
                if (!backup) backup = *other;
                backing_.push_back(r->handle);
            }
        }
        stand_ins_.push_back({.key = stood->key,
                              .pack = id,
                              .place = tiles::TileKey(fine, s.x, s.y),
                              .wanted = key,
                              .bytes = heap_bytes(*stood),
                              .share = s.priority,
                              .backup = backup});
        standing_.push_back(stood->handle);
    }
    for (std::size_t k = pending_from; k < pending_.size(); ++k) {
        const ResidentTile* r = residency.find(pending_[k].key, id);
        if (r == nullptr) continue;
        const std::uint64_t cells = visible_cells(r->cells, r->cells_per_side, frame, frustum) & pending_[k].cells;
        if (cells != 0) {
            draw(*r, cells, view, frames, frustum, pending_[k].key, seconds);
        } else if (pending_[k].planned) {
            keep(r->handle);
        }
        if (pending_[k].planned) planned_handles_.push_back(r->handle);
    }
}

void Cover::keep(std::uint32_t handle) {
    if (std::ranges::find(kept_, handle) == kept_.end()) kept_.push_back(handle);
}

void Cover::sort_out(const Residency& residency) {
    // A stand-in that a place in view plans, or keeps under it, is no stand-in: it stays while they are in view.
    const auto fixed = [this](std::uint32_t handle) {
        return std::ranges::find(planned_handles_, handle) != planned_handles_.end() ||
               std::ranges::find(kept_, handle) != kept_.end();
    };
    std::size_t n = 0;
    for (std::size_t i = 0; i < stand_ins_.size(); ++i) {
        if (fixed(standing_[i])) continue;
        stand_ins_[n] = stand_ins_[i];
        standing_[n++] = standing_[i];
    }
    stand_ins_.resize(n);
    standing_.resize(n);
    // The resident tiles of the places in view that nothing draws or keeps, and that stand in at none nor would, each
    // once.
    std::vector<std::uint32_t> seen;
    seen.reserve(tiles_.size() + kept_.size() + standing_.size() + backing_.size());
    for (const CoverTile& t : tiles_) seen.push_back(t.drawn->handle);
    seen.insert(seen.end(), kept_.begin(), kept_.end());
    seen.insert(seen.end(), standing_.begin(), standing_.end());
    seen.insert(seen.end(), backing_.begin(), backing_.end());
    for (const Spot& s : spots_) {
        const std::string_view id = drawn_.at(s.pack).id;
        for (const std::optional<tiles::TileKey>& k : s.keys) {
            if (!k) continue;
            const ResidentTile* r = residency.find(*k, id);
            if (r == nullptr || std::ranges::find(seen, r->handle) != seen.end()) continue;
            seen.push_back(r->handle);
            spare_.push_back({.key = r->key, .pack = id, .place = r->key, .wanted = r->key, .bytes = heap_bytes(*r)});
        }
    }
}

} // namespace atlas::scene
