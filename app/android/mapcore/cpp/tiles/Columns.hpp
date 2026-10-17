// The column codecs (rendertiles' codec/Columns.kt): one decoder per codec, each checking every read against the
// column's bytes and leaving none over. Arithmetic wraps where Kotlin's Long arithmetic wraps, so a damaged column
// fails the same checks the Kotlin reader makes instead of overflowing.
//
// The geometry codec (Codec::geom): every feature's vertices, those neighbours share (party walls, junctions, quay
// edges) written once. Ten sub-blocks, each a varint length and its bytes:
//  1. counts: a varint vertex count per feature;
//  2. kinds: a byte per vertex, 0 a new vertex, 1 a repeat of an earlier vertex of the column;
//  3. jumps: for a new vertex that starts its feature, zig-zag varint deltas of latitude and longitude from the
//  column's
//     previous vertex ((0, 0) at the start);
//  4–7. for every other new vertex, the zig-zag of its latitude and of its longitude delta from the previous vertex, as
//     16-bit values in byte planes (latitude low, latitude high, longitude low, longitude high); 0xFFFF escapes to
//  8. escapes: the value in full as a varint, in vertex order, latitude before longitude;
//  9. first references: for a repeat that starts its feature or follows a new vertex, how many vertices back it lies;
// 10. next references: for a repeat after a repeat, a zig-zag varint: its vertex minus (the previous repeat's − 1).
#pragma once

#include "base/arena.hpp"
#include "tiles/Payload.hpp"

#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::tiles {

/// What a column decoder needs to know about its column.
struct ColumnInput {
    LayerId layer = LayerId::none;
    std::string_view name;
    Codec codec = Codec::u8;
    std::uint32_t count = 0; ///< the layer's features
    std::span<const std::string_view> strings;
};

/// Decodes one column's bytes. For a keep column, `ring` is its geometry column (absent: every ring is empty).
[[nodiscard]] Result<Column> decode_column(const ColumnInput& in, std::span<const std::byte> bytes, const Column* ring,
                                           Arena& arena, TileDecoder::Scratch& scratch);

} // namespace atlas::tiles
