// The buildings pass's light (passes/Walls.hpp; ENGINE.md gain 5): each drawn tile's faces' colours at the frame's
// moment, lit and unlit (building_colours: light.slang's light() and tohex in binary64, the port's bits on every
// device), and its windowed walls' reveals' colours, into one of the tile's two areas of them
// (BuildingTile::colours_at, reveals_at). Both frame slots read the area that holds the moment they draw, so a tile is
// lit once a moment; a moment new to a tile goes into the area that the other slot's frame, which may be in flight,
// does not read (this slot's last frame has finished), and the frames before it go on reading the other.
//
// A new moment is lit ahead (ready_for): while the frames still show the moment before (the frame builder waits for
// every pass), a thread of the pass's own lights the tiles they draw, at the default priority, and calls the frame
// loop's wake once done; the frame that takes the moment copies their colours in, every drawn tile's in that one frame,
// so the frame that first shows the moment shows it whole and no frame lights a whole view on the render thread. A tile
// none of those frames drew (one new in that frame, or one no frame has drawn) is lit on the render thread as it is
// drawn, as every tile is the first time.
#include "base/trace.hpp"
#include "frame/FrameBuilder.hpp"
#include "passes/Walls.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <functional>
#include <mutex>
#include <span>
#include <stop_token>
#include <thread>
#include <utility>
#include <vector>

#if defined(__ANDROID__)
#include <pthread.h>
#include <sys/resource.h>
#endif

namespace atlas::passes {

namespace {

using clock = std::chrono::steady_clock;

[[nodiscard]] double ms_since(clock::time_point t) {
    return std::chrono::duration<double, std::milli>(clock::now() - t).count();
}

[[nodiscard]] bool same(const shaders::Theme& a, const shaders::Theme& b) noexcept {
    return std::ranges::equal(std::as_bytes(std::span(&a, 1)), std::as_bytes(std::span(&b, 1)));
}

} // namespace

/// The light of a new moment, done ahead on a thread of its own: the moment asked for, the tiles the frames drew while
/// it was asked for and their colours as lit so far, and what that cost; the tiles the frame that takes the moment
/// took, the render thread's from then on.
struct Buildings::Ahead {
    /// A tile to light: its handle, its light inputs (held while it is lit), and its colours and its reveals' colours
    /// at the moment asked for once lit.
    struct Tile {
        std::uint32_t handle = 0;
        std::shared_ptr<const BuildingTile> tile;
        std::vector<std::uint32_t> colours; ///< two words a face (building_colours)
        std::vector<std::uint32_t> reveals; ///< a word a windowed wall (building_reveals)
    };

    Ahead() {
        thread = std::jthread([this](const std::stop_token& stop) { work(stop); });
    }
    ~Ahead() = default; // the thread stops first, the last member
    Ahead(const Ahead&) = delete;
    Ahead& operator=(const Ahead&) = delete;
    Ahead(Ahead&&) = delete;
    Ahead& operator=(Ahead&&) = delete;

    /// Asks for moment `next`'s light of the tiles `drawn` (the render thread): a moment other than the one asked for
    /// so far starts afresh (the tiles lit for that one are let go of, and the one under way is dropped), the same one
    /// adds the tiles not asked for yet; `wake` is called from the thread once every tile asked for is lit. Whether
    /// every one is now.
    [[nodiscard]] bool ask(const shaders::Theme& next, std::vector<Tile> drawn, const std::function<void()>& wake) {
        {
            const std::scoped_lock lock(mutex);
            if (!asked || !same(theme, next)) {
                theme = next;
                asked = true;
                ++generation;
                tiles.clear();
                lit = 0;
                faces = 0;
                ms = 0;
            }
            on_lit = wake;
            for (Tile& t : drawn) {
                const auto listed = [&t](const Tile& o) { return o.handle == t.handle && o.tile == t.tile; };
                if (std::ranges::none_of(tiles, listed)) tiles.push_back(std::move(t));
            }
            if (lit == tiles.size()) return true;
        }
        wake_up.notify_one();
        return false;
    }

    /// The tiles lit for moment `now` (every one when the frame waited for them), taken by the frame that takes it
    /// (the render thread), what their light cost the thread added to `lighting`; whatever was asked for, the thread
    /// rests until the next ask.
    void take(const shaders::Theme& now, Lighting& lighting) {
        taken.clear();
        const std::scoped_lock lock(mutex);
        if (asked && same(theme, now)) {
            tiles.resize(lit);
            taken = std::move(tiles);
            lighting.ahead_tiles += lit;
            lighting.ahead_faces += faces;
            lighting.ahead_ms += ms;
        }
        rest();
    }

    /// Lets go of what was asked for (the render thread): the tiles and their colours, the one under way dropped; the
    /// thread rests until the next ask.
    void forget() {
        const std::scoped_lock lock(mutex);
        rest();
    }

    /// Nothing asked for (under mutex).
    void rest() {
        tiles.clear();
        asked = false;
        ++generation; // a tile under way is dropped
        lit = 0;
        faces = 0;
        ms = 0;
    }

    /// The tile `handle` taken, lit from `tile`, or null.
    [[nodiscard]] const Tile* find(std::uint32_t handle, const BuildingTile* tile) const noexcept {
        const auto at =
            std::ranges::find_if(taken, [&](const Tile& t) { return t.handle == handle && t.tile.get() == tile; });
        return at != taken.end() ? &*at : nullptr;
    }

    /// Waits until every tile asked for is lit, or nothing is asked for (tests).
    void wait() {
        std::unique_lock lock(mutex);
        idle.wait(lock, [this] { return !asked || lit == tiles.size(); });
    }

    void work(const std::stop_token& stop) {
#if defined(__ANDROID__)
        (void)::pthread_setname_np(::pthread_self(), "atlas-light");
        // behind the render thread (made from it, the thread would take its display priority), ahead of the tiles'
        // workers: this thread alone on Linux
        (void)::setpriority(PRIO_PROCESS, 0, 0);
#endif
        std::unique_lock lock(mutex);
        for (;;) {
            if (!wake_up.wait(lock, stop, [this] { return asked && lit < tiles.size(); }) || stop.stop_requested()) {
                return;
            }
            const std::uint64_t asked_as = generation;
            const std::size_t i = lit;
            const std::shared_ptr<const BuildingTile> b = tiles[i].tile;
            const shaders::Theme at = theme;
            lock.unlock();
            const auto started = clock::now();
            std::vector<std::uint32_t> colours(std::size_t{b->faces} * 2);
            std::vector<std::uint32_t> reveals(b->window_walls, 0U);
            {
                const trace::Section traced("atlas light ahead");
                building_colours(at, b->lights, colours);
                building_reveals(at, b->lights, b->window_faces, reveals);
            }
            const double took = ms_since(started);
            lock.lock();
            if (asked_as != generation) continue; // another moment asked for meanwhile, or this one taken
            tiles[i].colours = std::move(colours);
            tiles[i].reveals = std::move(reveals);
            ++lit;
            faces += b->faces;
            ms += took;
            if (lit < tiles.size()) continue;
            // every tile asked for is lit: the frame loop draws the moment
            idle.notify_all();
            const std::function<void()> wake = on_lit;
            lock.unlock();
            if (wake) wake();
            lock.lock();
        }
    }

    /// The render thread's: the tiles the frame that takes a moment took.
    std::vector<Tile> taken;

    std::mutex mutex;
    std::condition_variable_any wake_up; ///< the thread's
    std::condition_variable idle;        ///< wait's
    // under mutex
    shaders::Theme theme{};
    bool asked = false;
    std::uint64_t generation = 0; ///< bumped as a moment is asked for afresh or taken: a tile under way is dropped
    std::vector<Tile> tiles;
    std::size_t lit = 0; ///< tiles[0, lit) are lit
    std::uint64_t faces = 0;
    double ms = 0;
    std::function<void()> on_lit;
    std::jthread thread; ///< last: it starts once every member above is made, and stops before they go
};

Buildings::Buildings() = default;
Buildings::~Buildings() = default;

bool Buildings::ready_for(const frame::FrameState& last, const shaders::Theme& next,
                          const std::function<void()>& wake) {
    asked_ahead_ = true;
    // the light of the moment the frames show (the moment changed elsewhere in its block)
    if (moment_ != 0 && std::ranges::equal(std::as_bytes(std::span(&next, 1)), theme_)) return true;
    // the tiles the frame before drew, a tile once though it draws for several (a stand-in ancestor)
    std::vector<Ahead::Tile> drawn;
    if (last.cover != nullptr) {
        const std::uint32_t me = index();
        for (const scene::CoverTile& t : last.cover->tiles()) {
            if (t.drawn == nullptr || me >= t.drawn->passes.size()) continue;
            const scene::PassTile& mine = t.drawn->passes[me];
            const auto* b = mine.cpu_as<BuildingTile>();
            if (b == nullptr || !mine.ranges[0]) continue;
            const std::uint32_t handle = t.drawn->handle;
            if (std::ranges::any_of(drawn, [handle](const Ahead::Tile& d) { return d.handle == handle; })) continue;
            drawn.push_back({.handle = handle, .tile = std::shared_ptr<const BuildingTile>(mine.cpu, b)});
        }
    }
    if (drawn.empty()) return true; // nothing drawn to light ahead: a tile drawn then is lit as it is drawn
    if (!ahead_) ahead_ = std::make_unique<Ahead>();
    return ahead_->ask(next, std::move(drawn), wake);
}

void Buildings::wait_ahead() {
    if (ahead_) ahead_->wait();
}

void Buildings::take_ahead(const shaders::Theme& theme) {
    if (ahead_) ahead_->take(theme, lighting_);
}

void Buildings::drop_ahead() noexcept {
    if (ahead_) ahead_->taken.clear();
}

void Buildings::forget_ahead() {
    if (!asked_ahead_ && ahead_) ahead_->forget();
    asked_ahead_ = false;
}

std::uint32_t Buildings::light(scene::Heaps& heaps, const shaders::Theme& theme, std::uint32_t slot,
                               const scene::ResidentTile& tile, const std::optional<frame::AskedBuilding>& asked,
                               const scene::PassTile& mine, const BuildingTile& b, bool& wrote) {
    // the items of the building asked about, where this tile holds it
    asked_items_.clear();
    if (asked) {
        for (std::size_t k = 0; k < b.ids.size() && k < b.owners.size(); ++k) {
            const bool relation = (b.owners[k] & 2U) != 0;
            if (b.ids[k] == asked->id && relation == asked->relation)
                asked_items_.push_back(static_cast<std::uint16_t>(k));
        }
    }
    const std::uint64_t asked_now = asked_items_.empty() ? 0 : asked_revision_;
    Lit& lit = lit_[tile.handle];
    // the area this slot's frames read, else the other, once it holds the frame's moment and the asked building's
    // revision (the other slot's frame lit it): both slots read it from then on
    for (const std::uint32_t area : {std::uint32_t{lit.read[slot]}, 1U - lit.read[slot]}) {
        if (lit.moment[area] != moment_ || lit.asked[area] != asked_now) continue;
        lit.read[slot] = static_cast<std::uint8_t>(area);
        return area;
    }
    // else the moment goes into the area the other slot's frame, which may be in flight, does not read (this slot's
    // last frame has finished): copied in where the light ahead lit the tile, else lit now
    const std::uint32_t area = 1U - lit.read[1U - slot];
    const auto started = clock::now();
    const Ahead::Tile* ahead = ahead_ ? ahead_->find(tile.handle, &b) : nullptr;
    if (ahead == nullptr) {
        colours_.resize(std::size_t{b.faces} * 2);
        building_colours(theme, b.lights, colours_);
        reveals_.assign(b.window_walls, 0U);
        building_reveals(theme, b.lights, b.window_faces, reveals_);
    }
    std::span<const std::uint32_t> colours = ahead != nullptr ? ahead->colours : colours_;
    const std::span<const std::uint32_t> reveals = ahead != nullptr ? ahead->reveals : reveals_;
    if (asked && !asked_items_.empty()) {
        // the asked building's walls in its flat colour, lit and unlit alike (0xBBGGRR, as building_colours writes
        // them), over the colours lit now or a copy of the light ahead's
        if (ahead != nullptr) colours_.assign(ahead->colours.begin(), ahead->colours.end());
        const std::uint32_t c =
            ((asked->wall & 0xFFU) << 16U) | (asked->wall & 0xFF00U) | ((asked->wall >> 16U) & 0xFFU);
        for (std::size_t i = 0; i < b.face_items.size() && i < b.face_walls.size() && (2 * i) + 1 < colours_.size();
             ++i) {
            if (b.face_walls[i] == 0 || std::ranges::find(asked_items_, b.face_items[i]) == asked_items_.end())
                continue;
            colours_[2 * i] = c;
            colours_[(2 * i) + 1] = c;
        }
        colours = colours_;
    }
    constexpr auto word = static_cast<std::uint32_t>(sizeof(std::uint32_t));
    heaps.write(mine.ranges[0], std::as_bytes(colours), (b.colours_at * 16) + (area * b.faces * 2 * word));
    if (b.window_walls > 0) {
        heaps.write(mine.ranges[0], std::as_bytes(reveals), (b.reveals_at * 16) + (area * b.window_walls * word));
    }
    wrote = true;
    lit.moment[area] = moment_;
    lit.asked[area] = asked_now;
    lit.read[slot] = static_cast<std::uint8_t>(area);
    const double ms = ms_since(started);
    if (ahead != nullptr) {
        ++lighting_.copied;
        lighting_.copy_ms += ms;
    } else {
        ++lighting_.tiles;
        lighting_.faces += b.faces;
        lighting_.ms += ms;
        lighting_.longest_ms = std::max(lighting_.longest_ms, ms);
    }
    return area;
}

} // namespace atlas::passes
