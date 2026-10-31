// The world's index (ENGINE.md step 5, "Packs"): `world.idx`, as the packer's `app.atlas.tiles.WorldIndex` writes it
// ("ATWX", version 1, little-endian): the data's date, the parts the packs lie in (each its name and bytes), and every
// pack: its block (the L6 tile of a fine pack, the L3 tile of a coarse one), its tiles' levels, its part and the range
// of it the pack is, how many tiles it holds, the box it was built for and its SHA-256. The store holds each such pack
// as a `world_mid` pack, a range of its part, and the globe (`world-globe.atlas`, beside the index) as `world_base`.
#pragma once

#include "tiles/Checksums.hpp"
#include "tiles/Error.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace atlas::tiles {

class Store;

/// One pack of the world's index: its block, its tiles' levels, its part and range there, its tiles, the box it was
/// built for (south, west, north, east, the grid's 1e-7°) and its SHA-256.
struct WorldEntry {
    std::uint8_t z = 0;
    std::uint8_t first = 0;
    std::uint8_t last = 0;
    std::uint8_t part = 0;
    std::uint32_t x = 0;
    std::uint32_t y = 0;
    std::uint64_t offset = 0;
    std::uint64_t length = 0;
    std::uint32_t tiles = 0;
    std::array<std::int32_t, 4> box{};
    Sha256Digest sha256{};
};

/// The world's index: the data's date (seconds since the epoch), its parts (name and bytes) and its packs.
struct WorldIndex {
    std::int64_t date = 0;
    std::vector<std::pair<std::string, std::uint64_t>> parts;
    std::vector<WorldEntry> entries;
};

/// The index's magic and the version this reader knows (WorldIndex.MAGIC and VERSION).
inline constexpr std::string_view world_index_magic = "ATWX";
inline constexpr std::uint16_t world_index_version = 1;
/// Each entry's bytes.
inline constexpr std::size_t world_entry_bytes = 80;
/// The globe's file beside the index.
inline constexpr std::string_view world_globe_file = "world-globe.atlas";
/// The store's id of the globe.
inline constexpr std::string_view world_globe_id = "world-globe";

/// world.idx's bytes as an index; nothing for bytes that are not one (cut short or run on, another magic, or a version
/// this reader does not know).
[[nodiscard]] std::optional<WorldIndex> read_world_index(std::span<const std::byte> bytes);

/// The store's id of a world pack: `world/<z>/<x>/<y>`, its block.
[[nodiscard]] std::string world_pack_id(const WorldEntry& entry);

/// What add_world added: the packs, the globe (or not), and their tiles.
struct WorldAdded {
    std::size_t packs = 0;
    bool globe = false;
    std::uint64_t tiles = 0;
};

/// The world in folder `dir` (world.idx, its parts beside it, and world-globe.atlas where it is there) added to
/// `store`: each pack a `world_mid` range of its part (opened, mapped and the descriptor closed), the globe
/// `world_base`. A host's and a test's way; the app lists the same packs through MapCore.packs. An index that cannot be
/// read, a part that is not there or is shorter than its range, or a pack the store refuses, is an error.
[[nodiscard]] Result<WorldAdded> add_world(Store& store, const std::string& dir);

} // namespace atlas::tiles
