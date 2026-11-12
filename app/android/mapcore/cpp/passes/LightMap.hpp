// The night's light on the ground as a map a tile (ENGINE.md part 4, the night's light by radiance cascades; behind
// the camera's flag light_cascades, off unless the user's switch is on): where the renders lay each street lamp's and
// each open place's pool as an ellipse through every wall, the map holds the same pools cut by the buildings in their
// way (fx/LightCascades.hpp).
//
// A tile's map is baked once, on the baker's thread, from the tile's own lights (its street lamps, and its open places
// while the moment has them lit) and the footprints of its buildings and its neighbours' near its sides, over the
// tile's box grown by light_margin: so each light lies in one map whole, a tile's map never waits for a neighbour's
// lights, and two maps that meet lay their lights one over the other as two pools do. It is baked again only when the
// tile's lights change (the moment's open places) or a neighbour whose buildings it reads comes or goes. The bake
// walks the box in pieces of 128 m with what lies 24 m about each, leaves out the pieces no light reaches, and keeps
// the map as blocks of 32 m, those some light reaches alone: each texel the two kinds' alphas, a byte each, with a
// texel of its neighbours about it for the frame's bilinear read.
//
// The frame draws the maps of the tiles near its target at the lamps' pools' slot, a quad on the ground a lit block
// (light_vs, light_fs), reading the blocks from a buffer of slots the pass fills as bakes arrive (passes/Ground.hpp's
// LightMaps); the lamps of a tile whose map is not there yet are drawn as the renders' ellipses meanwhile, and so are
// those farther than light_far.
#pragma once

#include "fx/LightCascades.hpp"
#include "passes/OpenHours.hpp"

#include <array>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>

namespace atlas::passes {

/// A block's side (texels of 1 m), and a slot's: the block with a texel of its neighbours about it.
inline constexpr std::int32_t light_block = 32;
inline constexpr std::int32_t light_slot = light_block + 2;
/// A slot's words: a texel 16 bits (the lamps' alpha, then the open places'), two a word, row by row.
inline constexpr std::uint32_t light_slot_words = static_cast<std::uint32_t>(light_slot * light_slot) / 2U;
/// How far past its tile's box a map reaches (m): a light's reach and the spread the cascades give it.
inline constexpr std::int32_t light_margin = 16;
/// How far from the frame's target a tile's box may lie for its map to be drawn (m): farther off, a pool is a few
/// px and its lamps keep the renders' ellipses.
inline constexpr double light_far = 1'600.0;

/// What a tile's bake reads: the grid's corner in the tile's frame (texel (0, 0)'s, m) and its size in blocks, the
/// tile's lights, and the footprints near it (each a ring of points), all in the grid's texels.
struct LightJob {
    double x0 = 0;
    double z0 = 0;
    std::int32_t blocks_x = 0;
    std::int32_t blocks_z = 0;
    std::vector<fx::LightEmitter> emitters;
    std::vector<float> ring_points;           ///< x, z of every footprint's points, ring after ring
    std::vector<std::uint32_t> ring_first{0}; ///< where each ring's points begin (one more than rings)
};

/// A tile's light, baked: for each block of its grid its place among `blocks` plus one (0: no light reaches it), and
/// the lit blocks' slots, light_slot_words each.
struct LightTile {
    double x0 = 0;
    double z0 = 0;
    std::int32_t blocks_x = 0;
    std::int32_t blocks_z = 0;
    std::vector<std::uint16_t> index;
    std::vector<std::uint32_t> blocks;
    /// What the bake took: its time on the baker's thread (ms), the pieces it walked, and the cascades' work.
    double ms = 0;
    std::uint32_t pieces = 0;
    fx::LightCost cost;

    [[nodiscard]] std::uint32_t lit() const noexcept {
        return static_cast<std::uint32_t>(blocks.size() / light_slot_words);
    }
    [[nodiscard]] std::size_t bytes() const noexcept {
        return sizeof(LightTile) + (index.capacity() * sizeof(std::uint16_t)) +
               (blocks.capacity() * sizeof(std::uint32_t));
    }
    /// The two alphas (the lamps', the open places') at texel (x, z) of the grid: 0 where no light reaches.
    [[nodiscard]] std::array<std::uint8_t, 2> at(std::int32_t x, std::int32_t z) const noexcept;
};

/// Bakes `job` (on the calling thread).
[[nodiscard]] LightTile bake_light(const LightJob& job);

/// The baker: one thread that bakes the jobs it is given, first come first baked, and keeps each result until it is
/// taken.
class LightBaker {
public:
    LightBaker();
    ~LightBaker();
    LightBaker(const LightBaker&) = delete;
    LightBaker& operator=(const LightBaker&) = delete;
    LightBaker(LightBaker&&) = delete;
    LightBaker& operator=(LightBaker&&) = delete;

    /// Asks for `job`'s bake under `ticket` (the asker's own number for it).
    void submit(std::uint64_t ticket, LightJob job);
    /// The bakes done since the last call, each with its ticket.
    [[nodiscard]] std::vector<std::pair<std::uint64_t, std::shared_ptr<const LightTile>>> take();
    /// The jobs asked for whose results have not been taken, and those of them that are done.
    [[nodiscard]] std::size_t pending() const;
    [[nodiscard]] std::size_t ready() const;
    /// Waits until every job asked for is baked (a test's).
    void wait() const;

private:
    void run(const std::stop_token& stop);

    mutable std::mutex mutex_;
    mutable std::condition_variable_any changed_;
    std::deque<std::pair<std::uint64_t, LightJob>> jobs_;
    std::vector<std::pair<std::uint64_t, std::shared_ptr<const LightTile>>> done_;
    std::size_t baking_ = 0;
    std::jthread thread_;
};

/// How many slots the frames' buffer of blocks holds (9.5 MB of them): the lit blocks of the maps drawn. A map whose
/// blocks find no room is not drawn (its lamps keep their ellipses) until farther maps have left.
inline constexpr std::uint32_t light_slots = 4096;
/// How many frames a slot let go rests before another block takes it (the frames in flight still read it).
inline constexpr std::uint64_t light_slot_rest = 3;
/// How many frames a map stays after its tile was last drawn near the target.
inline constexpr std::uint64_t light_kept_frames = 600;

/// A tile's map as the pass keeps it: the tile's ground it is of, what it was baked from (`key`: the lights lit and
/// the neighbours read), the bake under way for other lights or neighbours, and its lit blocks' slots in the buffer
/// once they have room.
struct LightEntry {
    const void* ground = nullptr;
    std::uint64_t serial = 0; ///< the open hours' key (OpenHours)
    std::shared_ptr<const LightTile> map;
    std::uint64_t key = 0;
    std::uint64_t asked = 0; ///< the ticket of the bake under way (0: none), and what it bakes from
    std::uint64_t asked_key = 0;
    std::vector<std::uint32_t> slots;
    bool uploaded = false;  ///< its blocks are in the buffer
    std::uint64_t seen = 0; ///< the last frame its tile was drawn near the target
};

/// What the maps took, for the measures: the bakes done and their time on the baker's thread (ms), the pieces they
/// walked, the lit blocks they kept; and now, the maps kept, those drawn in the last frame, the slots taken, the bakes
/// under way and the bytes the maps hold on the CPU.
struct LightStats {
    std::uint32_t bakes = 0;
    double ms = 0;
    double longest_ms = 0;
    std::uint64_t pieces = 0;
    std::uint64_t lit = 0;
    std::uint32_t maps = 0;
    std::uint32_t drawn = 0;
    std::uint32_t slots = 0;
    std::uint32_t pending = 0;
    std::size_t bytes = 0;
};

/// The ground pass's maps (passes/Ground.hpp's light_): made the first time a frame asks for them.
struct LightMaps {
    LightBaker baker;
    OpenHours hours;
    std::vector<std::unique_ptr<LightEntry>> entries;
    std::vector<std::uint32_t> free;                             ///< the slots no block holds
    std::deque<std::pair<std::uint64_t, std::uint32_t>> resting; ///< those let go, with the frame that let them go
    std::uint64_t frame = 0;
    std::uint64_t tickets = 0;
    /// The moment the last decision read the open places' hours at (year, month, day, hour, minute), and whether it
    /// left a map without room for its blocks: with a bake done, what makes a frame at rest decide the light again.
    std::array<int, 5> moment{};
    bool waiting = false;
    LightStats stats;

    LightMaps();
};

} // namespace atlas::passes
