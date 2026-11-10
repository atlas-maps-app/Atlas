// The cover (scene/Cover.hpp).
#include "scene/Cover.hpp"

#include "base/pymath/PyMath.hpp"
#include "tiles/Grid.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
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

/// The triangles a resident tile draws with every cell drawn: every pass's count (PassTile::triangles).
[[nodiscard]] std::uint64_t tile_triangles(const ResidentTile& t) noexcept {
    std::uint64_t n = 0;
    for (const PassTile& p : t.passes) n += p.triangles;
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

/// Calls `fn(from_cell, to_cell)` for each cell of tile `to` (`n` cells a side) that overlaps one of the cells `cells`
/// of tile `from` (`m` a side; bit r · m + c the cell in row r from the north and column c, as Cells.hpp has them),
/// both laid on the grid of the finer of their cells. Sides of 1, 2, 4 or 8 cells; another meets nothing.
template <class Fn>
void each_meeting(tiles::TileKey from, std::uint8_t m, std::uint64_t cells, tiles::TileKey to, std::uint8_t n,
                  const Fn& fn) {
    if (!std::has_single_bit(m) || m > 8 || !std::has_single_bit(n) || n > 8) return;
    const auto mb = static_cast<unsigned>(std::countr_zero(m));
    const auto nb = static_cast<unsigned>(std::countr_zero(n));
    const unsigned fine = std::max(from.z + mb, to.z + nb);
    const unsigned fs = fine - from.z - mb; // a cell of `from` is 2^fs of the fine grid a side, one of `to` 2^ts
    const unsigned ts = fine - to.z - nb;
    const std::int64_t tx = static_cast<std::int64_t>(to.x) << (fine - to.z); // `to`'s north-west corner
    const std::int64_t ty = static_cast<std::int64_t>(to.y) << (fine - to.z);
    const std::int64_t last = std::int64_t{n} - 1;
    for (std::uint64_t left = cells; left != 0; left &= left - 1) {
        const auto bit = static_cast<unsigned>(std::countr_zero(left));
        if (bit >= unsigned{m} * m) break;
        // the cell's box on the fine grid from `to`'s corner, and the cells of `to` it overlaps (shifts floor)
        const std::int64_t x0 = (((static_cast<std::int64_t>(from.x) * m) + (bit % m)) << fs) - tx;
        const std::int64_t y0 = (((static_cast<std::int64_t>(from.y) * m) + (bit / m)) << fs) - ty;
        const std::int64_t span = (std::int64_t{1} << fs) - 1;
        const std::int64_t c0 = std::max<std::int64_t>(0, x0 >> ts);
        const std::int64_t c1 = std::min(last, (x0 + span) >> ts);
        const std::int64_t r0 = std::max<std::int64_t>(0, y0 >> ts);
        const std::int64_t r1 = std::min(last, (y0 + span) >> ts);
        for (std::int64_t r = r0; r <= r1; ++r) {
            for (std::int64_t c = c0; c <= c1; ++c) fn(bit, static_cast<unsigned>((r * n) + c));
        }
    }
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
    if (shown == nullptr) {
        shown = &shown_.emplace_back(
            Shown{.handle = tile.handle, .since = seconds, .drawn = false, .whole = false, .built = tile.built});
    }
    if (!shown->drawn) {
        // its first entry this frame: the passes its top-up brought since the frame before drew it (a part come back
        // on) fade in from nothing from now, each over the tile's fade time, its own fade as it was; a pass let go of
        // since, or faded in, leaves
        if (fade_seconds_ > 0) {
            for (std::uint64_t back = tile.built & ~shown->built; back != 0U; back &= back - 1) {
                shown->tops.push_back({.pass = static_cast<std::uint8_t>(std::countr_zero(back)), .since = seconds});
            }
        }
        shown->built = tile.built;
        std::erase_if(shown->tops, [&](const Top& t) {
            return ((tile.built >> t.pass) & 1U) == 0U || !((seconds - t.since) / fade_seconds_ < 1.0);
        });
        for (const Top& t : shown->tops) {
            tops_.push_back({.pass = t.pass,
                             .since = t.since,
                             .weight = static_cast<float>(pymath::max(0.0, (seconds - t.since) / fade_seconds_)),
                             .handle = tile.handle});
        }
        if (!shown->tops.empty()) fading_ = true;
    }
    shown->drawn = true;
    std::uint64_t topped = 0;
    for (const Top& t : shown->tops) topped |= std::uint64_t{1} << t.pass;
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
                      .share = frustum.share(box),
                      .topped = topped});
}

std::array<float, 2> Cover::dither(const CoverTile& t, std::size_t pass) const noexcept {
    const float start = t.place.offset.w;
    const float end = t.place.scale.w;
    if (pass >= 64 || ((t.topped >> pass) & 1U) == 0U || t.drawn == nullptr) return {start, end};
    for (const Top& top : tops_) {
        if (top.handle == t.drawn->handle && top.pass == pass) return {start, start + ((end - start) * top.weight)};
    }
    return {start, end};
}

void Cover::update(const view::View& view, const view::Frames& frames, const CoverInputs& in,
                   const Residency& residency, double seconds) {
    tiles_.clear();
    pairs_.clear();
    upright_.clear();
    dithers_ = false;
    drawn_tiles_ = 0;
    drew_before_.swap(drew_);
    drew_.clear();
    requests_.clear();
    pending_.clear();
    spots_.clear();
    kept_.clear();
    planning_ = in.budget > 0;
    drawn_count_ = 0;
    planned_ = 0;
    coarsened_ = 0;
    triangles_ = 0;
    holes_ = 0;
    near_holes_ = 0;
    stand_ins_.clear();
    spare_.clear();
    placed_.clear();
    standing_.clear();
    backing_.clear();
    planned_handles_.clear();
    missing_ = 0;
    fading_ = false;
    tops_.clear();
    lacking_ = 0;
    fade_seconds_ = in.fade_seconds;
    fine_far_ = in.fine_far;
    for (Shown& s : shown_) s.drawn = false;
    pack_id_.clear();
    coarser_id_.clear();
    if (in.pack == nullptr || !in.pack->pack.is_render() || in.pack->pack.head() == nullptr) {
        shown_.clear();
        return;
    }
    const Frustum frustum(view.camera(), in.margin_px, in.far, view.drop());
    pack_id_ = in.pack->id;
    walk({.pack = in.pack, .id = pack_id_, .active = true, .tolerance = in.tolerance}, frames, frustum, in,
         std::nullopt, {});
    const bool coarser = in.coarser != nullptr && in.coarser != in.pack && in.coarser->pack.is_render() &&
                         in.coarser->pack.head() != nullptr;
    if (coarser) {
        coarser_id_ = in.coarser->id;
        walk({.pack = in.coarser, .id = coarser_id_, .active = false, .tolerance = in.coarser_tolerance}, frames,
             frustum, in, in.mask, {});
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
        walk({.pack = n, .id = neighbour_ids_.at(i), .active = false, .tolerance = in.neighbour_tolerances.at(i)},
             frames, frustum, in, std::nullopt, nearer);
        above.at(above_count++) = n;
    }
    if (!spots_.empty()) measure(residency);
    plan(in, residency);
    if (!spots_.empty()) count_triangles(residency);
    for (std::size_t p = 0; p < drawn_count_; ++p) emit(p, view, frames, frustum, residency, seconds);
    lay_under(view, frames, frustum, residency, seconds);
    for (std::size_t i = 0; i < drawn_tiles_; ++i) {
        if (residency.lacks(*tiles_[i].drawn)) ++lacking_;
    }
    sort_out(residency);
    std::erase_if(shown_, [](const Shown& s) { return !s.drawn; });
    // stable: tiles at one distance keep the cover's order, on every libc++; those kept under after the tiles drawn,
    // each pair moved with its two entries (lay_under made them by entry, for lay_upright)
    order_.resize(tiles_.size());
    std::ranges::iota(order_, std::size_t{0});
    const auto nearer = [this](std::size_t a, std::size_t b) { return tiles_[a].distance < tiles_[b].distance; };
    std::ranges::stable_sort(std::span(order_).first(drawn_tiles_), nearer);
    std::ranges::stable_sort(std::span(order_).subspan(drawn_tiles_), nearer);
    sorted_.clear();
    moved_.resize(order_.size());
    for (std::size_t n = 0; n < order_.size(); ++n) {
        sorted_.push_back(tiles_[order_[n]]);
        moved_[order_[n]] = n;
    }
    tiles_.swap(sorted_);
    for (Pair& pair : pairs_) {
        pair.over = moved_[pair.over];
        pair.under = moved_[pair.under];
    }
    for (const CoverTile& t : tiles_) drew_.push_back(t.drawn->handle);
    std::ranges::sort(drew_);
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
    // cell whose windows the phone has not taken), each at its own distance.
    for (std::size_t k = 1; k < count; ++k) {
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
    // per zoom and pass, the heaps' bytes, the triangles and the raw bytes of the resident tiles built with the pass
    struct Sum {
        double heap = 0;
        double triangles = 0;
        double raw = 0;
    };
    std::array<std::array<Sum, share_passes>, 32> sums{};
    std::array<Sum, share_passes> all{};
    if (held_.size() > 8192) held_.clear();
    for (const std::shared_ptr<const ResidentTile>& t : residency.resident()) {
        const Drawn* by = nullptr;
        for (std::size_t p = 0; p < drawn_count_; ++p) {
            if (drawn_.at(p).id == t->pack) by = &drawn_.at(p);
        }
        if (by == nullptr) continue;
        held_[tile_id(t->pack, t->key)] = {.bytes = heap_bytes(*t), .triangles = tile_triangles(*t), .built = t->built};
        passes_seen_ = std::max(passes_seen_, std::min(t->passes.size(), share_passes));
        const tiles::ChunkRef* c = by->pack->pack.chunks().find(t->key);
        if (c == nullptr || c->raw == 0 || t->key.z >= sums.size()) continue;
        const auto raw = static_cast<double>(c->raw);
        for (std::size_t p = 0; p < t->passes.size() && p < share_passes; ++p) {
            if (!Residency::among(t->built, p)) continue; // its part left out as it was built: nothing of it
            std::uint64_t bytes = 0;
            for (const HeapRange& r : t->passes[p].ranges) bytes += r.size;
            for (Sum* s : {&sums.at(t->key.z).at(p), &all.at(p)}) {
                s->heap += static_cast<double>(bytes);
                s->triangles += static_cast<double>(t->passes[p].triangles);
                s->raw += raw;
            }
        }
    }
    const auto learn = [](Share& share, const Sum& sum) {
        if (sum.raw > 0) share = {.heap = sum.heap / sum.raw, .triangles = sum.triangles / sum.raw, .seen = true};
    };
    for (std::size_t z = 0; z < sums.size(); ++z) {
        for (std::size_t p = 0; p < share_passes; ++p) learn(shares_.at(z).at(p), sums.at(z).at(p));
    }
    // a level none of whose tiles was resident with a pass yet: the resident tiles' of every level (the far level's
    // come first)
    for (std::size_t p = 0; p < share_passes; ++p) learn(any_.at(p), all.at(p));
}

double Cover::every_pass(const Drawn& p, const tiles::TileKey& k, const Residency& residency, bool triangles) const {
    const tiles::ChunkRef* c = p.pack->pack.chunks().find(k);
    const double raw = c != nullptr ? static_cast<double>(c->raw) : 0.0;
    // what its passes built hold (resident, or as they held when it was resident before), and which passes those are
    double held = 0;
    std::uint64_t built = 0;
    if (const ResidentTile* r = residency.find(k, p.id)) {
        held = static_cast<double>(triangles ? tile_triangles(*r) : heap_bytes(*r));
        built = r->built;
    } else if (const auto it = held_.find(tile_id(p.id, k)); it != held_.end()) {
        held = static_cast<double>(triangles ? it->second.triangles : it->second.bytes);
        built = it->second.built;
    } else if (c == nullptr) {
        return 0;
    }
    // before any tile was measured: the first guess for a whole tile
    if (passes_seen_ == 0 && built == 0U) return raw * (triangles ? first_triangle_ratio : first_ratio);
    // the passes it lacks, by their shares (their part left out as it was built, or not built yet); a pass no tile was
    // built with yet (its part left out since the world began) none, nothing being known of it until one is
    double rest = 0;
    for (std::size_t q = 0; q < passes_seen_; ++q) {
        if (Residency::among(built, q)) continue;
        const Share& at = k.z < shares_.size() && shares_.at(k.z).at(q).seen ? shares_.at(k.z).at(q) : any_.at(q);
        rest += raw * (triangles ? at.triangles : at.heap);
    }
    return held + rest;
}

std::uint64_t Cover::estimate(const Drawn& p, const tiles::TileKey& k, const Residency& residency) const {
    return static_cast<std::uint64_t>(every_pass(p, k, residency, false));
}

double Cover::estimate_triangles(const Drawn& p, const tiles::TileKey& k, const Residency& residency) const {
    return every_pass(p, k, residency, true);
}

void Cover::count_triangles(const Residency& residency) {
    double sum = 0;
    // a tile's share reckoned once for a run of its places (the walk gives a tile's places one after another)
    std::size_t last_pack = drawn_.size();
    tiles::TileKey last{};
    double share = 0;
    for (const Spot& s : spots_) {
        const std::optional<tiles::TileKey>& k = s.keys.at(s.plan);
        if (!k) continue;
        if (s.pack != last_pack || !(*k == last)) {
            const Drawn& p = drawn_.at(s.pack);
            // the place's share of its tile: one of its 4^(fine - z) places
            const int gap = int{p.pack->pack.head()->levels[0]} - int{k->z};
            share = std::ldexp(estimate_triangles(p, *k, residency), -2 * std::max(gap, 0));
            last_pack = s.pack;
            last = *k;
        }
        sum += share;
    }
    triangles_ = static_cast<std::uint64_t>(sum);
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
        const tiles::TileKey place(fine, s.x, s.y);
        if (want == 0) {
            if (const ResidentTile* r = residency.find(key, id)) {
                placed_.push_back({.pack = id, .place = place, .tile = key, .d = s.d, .coarsened = s.plan > s.want});
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
            placed_.push_back({.pack = id, .place = place, .tile = key, .d = s.d, .coarsened = s.plan > s.want});
            continue;
        }
        request(key, Lane::visible, s.priority, drawn.tolerance, id, estimate(drawn, key, residency));
        // the cheapest coarser level as its ancestor, while it comes
        for (std::size_t k = count; k-- > want + 1;) {
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
        placed_.push_back({.pack = id, .place = place, .tile = stood->key, .d = s.d, .coarsened = s.plan > s.want});
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

void Cover::lay_under(const view::View& view, const view::Frames& frames, const Frustum& frustum,
                      const Residency& residency, double seconds) {
    drawn_tiles_ = tiles_.size();
    const auto drew_before = [this](std::uint32_t handle) { return std::ranges::binary_search(drew_before_, handle); };
    std::vector<const ResidentTile*> under;
    // each tile fading in, and those kept under it as they come (one fading in itself keeps its own under it)
    for (std::size_t i = 0; i < tiles_.size(); ++i) {
        if (tiles_[i].fade >= 1.0F) continue;
        const ResidentTile& t = *tiles_[i].drawn;
        const auto shown = std::ranges::find(shown_, t.handle, &Shown::handle);
        const bool whole = shown != shown_.end() && shown->whole;
        under.clear();
        // its nearest ancestor that the frame before drew (zooming in, or the stand-in it takes over from)
        for (std::uint32_t up = 1; !whole && up <= t.key.z && under.empty(); ++up) {
            const tiles::TileKey a(static_cast<std::uint8_t>(t.key.z - up), t.key.x >> up, t.key.y >> up);
            const ResidentTile* r = residency.find(a, t.pack);
            if (r != nullptr && drew_before(r->handle)) under.push_back(r);
        }
        // else its descendants that it drew (zooming out)
        if (!whole && under.empty()) {
            for (const std::shared_ptr<const ResidentTile>& r : residency.resident()) {
                if (r->key.z <= t.key.z || r->pack != t.pack || !drew_before(r->handle)) continue;
                const auto up = static_cast<std::uint32_t>(r->key.z - t.key.z);
                if ((r->key.x >> up) == t.key.x && (r->key.y >> up) == t.key.y) under.push_back(r.get());
            }
        }
        // where the frame before drew nothing it takes over from (the map's first picture, a place panned into view)
        // it is drawn whole from its first frame on, as before the fades; its fade is still the picture's to wait for
        if (under.empty()) {
            if (shown != shown_.end()) shown->whole = true;
            tiles_[i].place.scale.w = 1.0F;
            continue;
        }
        for (const ResidentTile* r : under) {
            // an ancestor's cells over the fading tile, a descendant whole
            const std::uint64_t cells =
                r->key.z < t.key.z
                    ? cells_over(r->cells_per_side, static_cast<std::uint32_t>(t.key.z - r->key.z), t.key.x, t.key.y)
                    : ~std::uint64_t{0};
            // A tile drawn (a stand-in still drawn for another place) draws its ground whole, so nothing is kept under
            // for it, but what stands up of it only in its own entries' cells: what stands up of its cells under this
            // one does, as a tile kept under's (lay_upright).
            const auto it = std::ranges::find(tiles_, r, &CoverTile::drawn);
            if (it != tiles_.end()) {
                if (it->under) it->cells |= cells;
                pairs_.push_back({.over = i, .under = static_cast<std::size_t>(it - tiles_.begin())});
                continue;
            }
            pairs_.push_back({.over = i, .under = tiles_.size()}); // what stands up of it, under this one (lay_upright)
            keep(r->handle);
            draw(*r, cells, view, frames, frustum, r->key, seconds);
            tiles_.back().under = true;
        }
    }
}

void Cover::lay_upright() {
    upright_.clear();
    // (and while a part come back on fades in on a tile: CoverTile::topped)
    dithers_ = !pairs_.empty() || !tops_.empty();
    const std::span<const CoverTile> drawn = tiles();
    // A tile drawn keeps the dither's levels from 0 to its fade where it takes over from a tile the frame before drew
    // (kept under it, or still drawn for another place), and all of them where it takes over from none (one fading in
    // over what the frame before did not draw there: as before the fades, since nothing of the frame before stands
    // where it fades in). With no pair and none fading in, the tiles drawn as they are.
    const auto fading = [](const CoverTile& t) { return t.place.scale.w < 1.0F; };
    if (pairs_.empty() && std::ranges::none_of(drawn, fading)) return;
    upright_.assign(drawn.begin(), drawn.end());
    for (std::size_t i = 0; i < drawn.size(); ++i) {
        if (std::ranges::find(pairs_, i, &Pair::over) == pairs_.end()) upright_[i].place.scale.w = 1.0F;
    }
    if (pairs_.empty()) return;
    // A tile under one fading in keeps them, in each of its cells, from the fades over it there: the fade of a tile
    // drawn over it, or of one under a tile fading in turn from where that one's own start is later (a chain of
    // levels zoomed through, so that a pixel is the topmost tile's of the chain whose range holds it); the least of
    // them where a cell lies under two tiles fading at different fades (a coarse cell under siblings: what stands up
    // there draws on the pixels the later of them takes too, never leaving a pixel to neither); none where nothing
    // fading lies over it. Pairs whose tile over is itself under one see the starts of the pairs before them, a few
    // passes over the list at most.
    starts_.resize(tiles_.size());
    for (std::array<float, 64>& s : starts_) s.fill(none_start);
    for (int pass = 0; pass < 4; ++pass) {
        bool moved = false;
        for (const Pair& p : pairs_) {
            const CoverTile& over = tiles_[p.over];
            const ResidentTile& under = *tiles_[p.under].drawn;
            std::array<float, 64>& to = starts_[p.under];
            const float fade = over.place.scale.w;
            const auto lower = [&](float start, unsigned t) {
                if (start < to[t]) {
                    to[t] = start;
                    moved = true;
                }
            };
            if (p.over < drawn_tiles_) {
                // the place the tile drawn is drawn for, whole (its own key, or the wanted tile it stands in for)
                each_meeting(over.key, 1, 1, under.key, under.cells_per_side,
                             [&](unsigned, unsigned t) { lower(fade, t); });
            }
            // and the cells of the tile over under one fading in over it in turn: a tile kept under, or a tile drawn
            // beyond its own places (still drawn for another place while one takes over from it here)
            const std::array<float, 64>& from = starts_[p.over];
            std::uint64_t cells = 0;
            for (unsigned c = 0; c < 64; ++c) {
                if (from[c] < none_start) cells |= std::uint64_t{1} << c;
            }
            each_meeting(over.drawn->key, over.drawn->cells_per_side, cells, under.key, under.cells_per_side,
                         [&](unsigned f, unsigned t) { lower(std::max(from[f], fade), t); });
        }
        if (!moved) break;
    }
    // each tile under one fading in once for each start its cells have, to its own fade where it fades in over one
    // in turn and else 1 (its place's scale.w), unless nothing lies between the two; a tile drawn in its cells there
    // but those its own entries draw whole (a stand-in's, one for each place it stands in for), marked under as one
    // kept under is (draws_items: its items are its first entry's)
    const auto level = [](float f) { return std::floor((std::clamp(f, 0.0F, 1.0F) * 255.0F) + 0.5F); }; // fade_word's
    for (std::size_t k = 0; k < tiles_.size(); ++k) {
        const std::array<float, 64>& s = starts_[k];
        std::uint64_t left = 0;
        for (unsigned c = 0; c < 64; ++c) {
            if (s[c] < none_start) left |= std::uint64_t{1} << c;
        }
        const bool drawn_tile = k < drawn_tiles_;
        if (drawn_tile) {
            for (std::size_t e = 0; e < drawn_tiles_; ++e) {
                if (tiles_[e].drawn == tiles_[k].drawn) left &= ~tiles_[e].cells;
            }
        }
        const float end = drawn_tile ? upright_[k].place.scale.w : tiles_[k].place.scale.w;
        while (left != 0) {
            const float start = s[static_cast<std::size_t>(std::countr_zero(left))];
            std::uint64_t cells = 0;
            for (std::uint64_t b = left; b != 0; b &= b - 1) {
                const auto c = static_cast<std::size_t>(std::countr_zero(b));
                if (s[c] == start) cells |= std::uint64_t{1} << c;
            }
            left &= ~cells;
            if (level(start) >= level(end)) continue;
            CoverTile& c = upright_.emplace_back(tiles_[k]);
            c.cells = cells;
            c.under = true;
            c.place.offset.w = start;
            c.place.scale.w = end;
        }
    }
    // those kept under after the tiles drawn, nearest first, as with_under has them
    std::ranges::stable_sort(std::span(upright_).subspan(drawn.size()), std::ranges::less{}, &CoverTile::distance);
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

shaders::TilePlace bent_place(const shaders::FrameCamera& camera, const CoverTile& t) noexcept {
    shaders::TilePlace place = t.place;
    const auto drop = static_cast<double>(camera.device.w);
    if (drop == 0.0 || t.drawn == nullptr) return place;
    const ResidentTile& r = *t.drawn;
    const Box box = box_of(r.reach.box, r.reach.top_cm, r.frame);
    // its middle relative to the eye (camera.slang's eye_relative), less the camera's target's
    const double dx = static_cast<double>(place.offset.x) +
                      ((((box.x0 + box.x1) * 0.5) - r.origin.x) * static_cast<double>(place.scale.x)) -
                      static_cast<double>(hlsl::asfloat(camera.flags.z));
    const double dz = static_cast<double>(place.offset.z) + (((box.z0 + box.z1) * 0.5) - r.origin.z) -
                      static_cast<double>(hlsl::asfloat(camera.flags.w));
    place.offset.y = static_cast<float>(static_cast<double>(place.offset.y) - (drop * ((dx * dx) + (dz * dz))));
    return place;
}

} // namespace atlas::scene
