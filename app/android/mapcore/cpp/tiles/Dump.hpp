// The canonical text of a decoded tile (plan-vector D16, L0 (b); rendertiles' test fixture CanonicalDump): one line per
// cell that holds features, then one per feature in the tile's order, its fields sorted by name with every number
// exact. It must equal, byte for byte, the text the Kotlin reader gives for the same tile, so the core's decoding is
// held to the Kotlin writer's records by text or by hash, on every host and on the emulator.
//
//   tile <level>/<x>/<y> n=<cells per side>
//   cell <i> box=<s>,<w>,<n>,<e> top=<cm> <layer>=<count>...
//   <layer> @<cell> <field>=<value>...
//
// A field holding its column's default (null, zero, an empty list or map, an empty ring without keep codes) is left
// out. Values: integers in decimal; a double as `d` and its bits in hex; a string in double quotes with `"` and `\`
// escaped and control characters as \u00xx; a point (lat,lon); a ring [lat,lon/keep;...]; a list <a,b>; a map {"k":v}.
#pragma once

#include "tiles/Checksums.hpp"
#include "tiles/Payload.hpp"

#include <cstddef>
#include <string>
#include <vector>

namespace atlas::tiles {

/// The tile's canonical text.
[[nodiscard]] std::string canonical_dump(const Tile& tile);

/// The SHA-256 of the canonical text's bytes (computed as it is written, without holding the text).
[[nodiscard]] Sha256Digest canonical_hash(const Tile& tile);

/// How many of a layer's lines two tiles' canonical texts hold, and how many of them differ (line for line in the
/// layer's order; a line one side lacks differs).
struct LayerLines {
    std::string layer;
    std::size_t lines = 0;
    std::size_t differ = 0;
};

/// Adds to `into` (a layer once, in the order the layers first come) how the features of `a` and `b` compare, layer by
/// layer: for a change that is to touch some layers alone, the others' lines must not differ. The tile's and the
/// cells' lines, which sum over every layer, are left out.
void compare_layers(const Tile& a, const Tile& b, std::vector<LayerLines>& into);

} // namespace atlas::tiles
