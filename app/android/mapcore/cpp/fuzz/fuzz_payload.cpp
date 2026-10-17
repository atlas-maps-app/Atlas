// Fuzzes the tile decoder (tiles/Payload.hpp, Columns.hpp, Values.hpp) with raw tile bytes: decoding must never read
// past its input or fail an invariant, and every tile it accepts must hold together: each layer's cell ranges cover its
// features in order, every column's arrays have the lengths its codec promises, every accessor stays in bounds, the
// canonical dump hashes to canonical_hash, simplifying every ring at every level keeps vertices in order, and a tile
// with features reaches a box that holds every cell's and passes the check against a chunk of its own key and reach.
// Seeds: raw tiles of the bundled packs (fuzz/corpus/fuzz_payload, made by tests/tiles/seeds_test.cpp).
#include "base/arena.hpp"
#include "base/assert.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Simplify.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace {

using namespace atlas::tiles;

void check_layer(const Layer& l, std::size_t cells) {
    ATLAS_ASSERT(l.cell_first.size() == cells + 1 && l.cell_first[0] == 0 && l.cell_first[cells] == l.count,
                 "cell ranges");
    for (std::size_t c = 0; c < cells; ++c) ATLAS_ASSERT(l.cell_first[c] <= l.cell_first[c + 1], "cells in order");
    ATLAS_ASSERT(l.columns.size() == l.schema->columns.size(), "a column per slot");
    const Frame frame{.lat0 = 52.3746, .lon0 = 4.884, .kx = 67954.56338551514, .kz = 110540.0};
    std::vector<std::uint32_t> kept;
    for (std::size_t s = 0; s < l.columns.size(); ++s) {
        const Column& col = l.columns[s];
        const auto slot = static_cast<std::uint8_t>(s);
        if (!col.present) continue;
        switch (col.codec) {
        case Codec::geom:
            ATLAS_ASSERT(col.offsets.size() == l.count + std::size_t{1} && col.offsets[l.count] == col.lat.size() &&
                             col.lat.size() == col.lon.size(),
                         "geometry arrays");
            break;
        case Codec::point: ATLAS_ASSERT(col.lat.size() == l.count && col.lon.size() == l.count, "point arrays"); break;
        case Codec::tags:
            ATLAS_ASSERT(col.offsets.size() == l.count + std::size_t{1} &&
                             col.refs.size() == 2 * std::size_t{col.offsets[l.count]},
                         "tag arrays");
            break;
        case Codec::ids: ATLAS_ASSERT(col.offsets[l.count] == col.ints.size(), "id arrays"); break;
        case Codec::strs:
            ATLAS_ASSERT(col.offsets[l.count] == col.refs.size() && col.flags.size() == l.count, "list arrays");
            break;
        case Codec::value: ATLAS_ASSERT(col.refs.size() == l.count, "value roots"); break;
        default: break;
        }
        for (std::uint32_t i = 0; i < l.count; ++i) {
            switch (col.codec) {
            case Codec::u8:
            case Codec::varu:
            case Codec::varz:
            case Codec::delta: (void)l.integer(slot, i); break;
            case Codec::nvarz:
            case Codec::ndelta: (void)l.nullable_integer(slot, i); break;
            case Codec::centi:
            case Codec::f64: (void)l.decimal(slot, i); break;
            case Codec::str: (void)l.string(slot, i); break;
            case Codec::strn: (void)l.string_or_number(slot, i); break;
            case Codec::boolean: (void)l.boolean(slot, i); break;
            case Codec::geom: {
                const RingView ring = l.ring(slot, i);
                ATLAS_ASSERT(!ring.has_keep || ring.keep.size() == ring.size(), "a code per vertex");
                kept.resize(ring.size());
                for (unsigned level = 0; level < keep::tolerances.size(); ++level) {
                    const Simplified s2 = simplify(ring, level, frame, kept);
                    ATLAS_ASSERT(s2.count <= ring.size(), "simplify keeps at most the ring");
                    for (std::size_t k = 1; k < s2.count; ++k)
                        ATLAS_ASSERT(kept[k - 1] < kept[k], "kept vertices in order");
                }
                break;
            }
            case Codec::point: (void)l.point(slot, i); break;
            case Codec::tags:
                for (std::size_t k = 0; k < l.tag_count(slot, i); ++k) (void)l.tag(slot, i, k);
                break;
            case Codec::ids: (void)l.ids(slot, i); break;
            case Codec::strs:
                if (const auto list = l.string_list(slot, i)) {
                    for (const std::uint32_t r : *list)
                        ATLAS_ASSERT(r < l.strings.size(), "a list's string in the table");
                }
                break;
            case Codec::value: (void)l.value(slot, i).size(); break;
            case Codec::keep: break;
            }
        }
    }
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    atlas::Arena arena(1U << 16U, atlas::Growth::grows, "fuzz");
    static TileDecoder decoder; // as a worker keeps one
    const auto tile = decoder.decode(bytes, arena);
    if (!tile) {
        (void)describe(tile.error());
        return 0;
    }
    const std::size_t cells = std::size_t{tile->header.cells_per_side} * tile->header.cells_per_side;
    ATLAS_ASSERT(tile->cells.size() == cells, "a cell per cell");
    for (const Layer& l : tile->layers) check_layer(l, cells);
    const std::string dump = canonical_dump(*tile);
    ATLAS_ASSERT(canonical_hash(*tile) == Sha256::of(as_bytes(dump)), "the dump's hash");
    if (const auto reach = reach_of(*tile)) {
        for (const CellInfo& c : tile->cells) {
            if (c.total == 0) continue;
            ATLAS_ASSERT(reach->box.south <= c.south && reach->box.west <= c.west && reach->box.north >= c.north &&
                             reach->box.east >= c.east && reach->top_cm >= c.top_cm && reach->top_cm >= 0,
                         "the reach holds every cell");
        }
        ChunkRef own;
        own.tile = {tile->header.level, tile->header.x, tile->header.y};
        own.reach = *reach;
        ATLAS_ASSERT(check_against(*tile, own).has_value(), "a tile agrees with a chunk of its own key and reach");
    }
    return 0;
}
