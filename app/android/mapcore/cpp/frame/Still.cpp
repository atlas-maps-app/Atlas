#include "frame/Still.hpp"

#include "frame/MapFrame.hpp"
#include "scene/Cover.hpp"
#include "scene/SunGlobals.hpp"
#include "scene/World.hpp"
#include "sun/BitTiles.hpp"
#include "tiles/Store.hpp"

#include <algorithm>
#include <array>
#include <cstdint>

namespace atlas::frame {

namespace {

/// The sun's work so far: its scenes built, its scenes made whole, its buckets run (none without a world or a sun).
[[nodiscard]] std::array<std::uint64_t, 3> work_of(MapFrame& map) {
    scene::SunGlobals* sun = map.world() != nullptr ? map.world()->sun() : nullptr;
    if (sun == nullptr) return {};
    const scene::SunStats s = sun->stats();
    return {s.scenes, s.wholes, s.runs};
}

} // namespace

Still::Still(MapFrame& map) : map_(map), before_(work_of(map)) {}

bool Still::final_frame() {
    scene::World* world = map_.world();
    if (world == nullptr) return false;
    scene::SunGlobals* sun = world->sun();
    if (sun != nullptr) sun->wait_idle();
    if (!map_.complete()) return false;
    if (sun == nullptr) return true;
    if (work_of(map_) != before_ || sun->fading()) return false;
    const tiles::StorePack* active = world->active();
    const scene::Cover* cover = world->cover();
    if (active == nullptr || cover == nullptr) return true;
    const sun::BitTiles& bits = sun->bits();
    return std::ranges::none_of(cover->with_under(), [&](const scene::CoverTile& t) {
        return t.drawn != nullptr && t.drawn->pack == active->id && !t.sun.has_value() &&
               bits.view(t.drawn->key, map_.seconds()).has_value();
    });
}

} // namespace atlas::frame
