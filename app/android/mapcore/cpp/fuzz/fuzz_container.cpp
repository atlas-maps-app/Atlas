// Fuzzes the pack container (tiles/Directory.hpp, Chunks.hpp, Sections.hpp, Container.hpp; formats 2 and 3: header,
// directory, chunk table, reach, rhead, style) with whole packs, mapped and read by ranges: opening must never read
// past the pack or fail an invariant, and an opened pack must find each of its chunks, keep them inside their section
// and their level, give each a non-empty reach, answer every distance with a band, and decode whatever its chunks hold,
// checked against them. The input's first byte picks how: bit 0 repairs every section's CRC-32 before opening (so the
// fuzzer reaches what lies behind them), bit 1 skips the chunks' SHA-256, bit 2 reads the pack by ranges.
// Seeds: the bundled packs and small packs of their tiles (fuzz/corpus/fuzz_container, tests/tiles/seeds_test.cpp).
#include "base/arena.hpp"
#include "base/assert.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Payload.hpp"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <span>
#include <vector>

namespace {

using namespace atlas::tiles;

/// Writes each section's CRC-32 into the directory, so a pack damaged elsewhere still opens past its CRC checks.
void repair_crcs(std::vector<std::byte>& pack) {
    const auto header = read_container_header(pack, pack.size());
    if (!header) return;
    const std::uint64_t at = header->directory_offset();
    if (at > pack.size() || header->directory_bytes > pack.size() - at) return;
    // the directory again, by hand, to find where each CRC lies
    auto p = static_cast<std::size_t>(at);
    const std::size_t end = p + header->directory_bytes;
    while (p < end) {
        Reader r(std::span<const std::byte>(pack).subspan(p, end - p));
        const std::string_view name = r.utf8();
        const auto offset = r.u64be();
        const std::uint64_t stored = r.u64be();
        (void)r.u64be();
        const std::size_t crc_at = p + r.position();
        (void)r.u32be();
        (void)r.u8();
        if (!r.ok()) return;
        (void)name;
        if (offset <= pack.size() && stored <= pack.size() - offset) {
            const std::uint32_t crc = crc32(std::span<const std::byte>(pack).subspan(static_cast<std::size_t>(offset),
                                                                                     static_cast<std::size_t>(stored)));
            for (std::size_t k = 0; k < 4; ++k) pack[crc_at + k] = static_cast<std::byte>(crc >> (24U - (8U * k)));
        }
        p += r.position();
    }
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    if (size == 0) return 0;
    const std::uint8_t mode = data[0];
    std::vector<std::byte> pack(reinterpret_cast<const std::byte*>(data) + 1,
                                reinterpret_cast<const std::byte*>(data) + size); // NOLINT
    if ((mode & 1U) != 0) repair_crcs(pack);
    const bool verify = (mode & 2U) == 0;
    std::unique_ptr<ByteSource> source;
    if ((mode & 4U) != 0) {
        const std::vector<std::byte>* bytes = &pack;
        source = std::make_unique<RangeSource>(
            pack.size(), [bytes](std::uint64_t offset, std::span<std::byte> out) -> Result<void> {
                ATLAS_ASSERT(offset <= bytes->size() && out.size() <= bytes->size() - offset,
                             "a range inside the pack");
                std::copy_n(bytes->begin() + static_cast<std::ptrdiff_t>(offset), out.size(), out.begin());
                return {};
            });
    } else {
        source = std::make_unique<MemorySource>(std::span<const std::byte>(pack));
    }
    auto opened = Pack::open(std::move(source));
    if (!opened) {
        (void)describe(opened.error());
        return 0;
    }
    const Pack& p = *opened;
    const Section* chunks = find_section(p.sections(), section_chunks);
    ATLAS_ASSERT(chunks != nullptr, "an opened pack has chunks");
    Zstd zstd;
    TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    std::size_t decoded = 0;
    std::size_t in_levels = 0;
    for (const std::uint8_t z : p.chunks().zooms()) {
        for (const ChunkRef& c : p.chunks().level(z)) ATLAS_ASSERT(c.tile.z == z, "a level's chunks are of its zoom");
        in_levels += p.chunks().level(z).size();
    }
    ATLAS_ASSERT(in_levels == p.chunks().chunks().size(), "the levels hold every chunk");
    for (const ChunkRef& c : p.chunks().chunks()) {
        ATLAS_ASSERT(p.chunks().find(c.tile) == &c, "every chunk is found by its tile");
        ATLAS_ASSERT(c.offset >= chunks->offset && c.offset - chunks->offset + c.stored <= chunks->stored,
                     "a chunk inside its section");
        if (p.is_render()) {
            ATLAS_ASSERT(c.reach.box.south <= c.reach.box.north && c.reach.box.west <= c.reach.box.east,
                         "a reach is a box");
            ATLAS_ASSERT(c.reach.meets(c.reach.box), "a reach meets itself");
        }
        if (decoded++ >= 16) continue; // enough work for one input
        if (!p.unpack(c, zstd, raw, scratch, verify)) continue;
        atlas::Arena arena(1U << 16U, atlas::Growth::grows, "fuzz");
        if (const auto tile = decoder.decode(raw, arena)) {
            (void)canonical_hash(*tile);
            const auto checked = check_against(*tile, c);
            if (checked)
                ATLAS_ASSERT(reach_of(*tile).value_or(Reach{}) == c.reach, "a checked tile reaches as its chunk says");
        }
    }
    if (const RenderHead* h = p.head()) {
        for (const HeadView& v : h->views()) {
            (void)v.camera.size();
            ATLAS_ASSERT(v.tolerance < keep::tolerances.size(), "a view on the ladder");
            ATLAS_ASSERT(h->tolerance_of(v.name, 0.0).has_value(), "a view's own tolerance");
        }
        ATLAS_ASSERT(!h->bands().empty() && !h->bands().back().up_to, "bands end with an open one");
        for (const double d : {-1.0, 0.0, 100.0, 1e6, std::numeric_limits<double>::infinity(),
                               std::numeric_limits<double>::quiet_NaN()}) {
            const auto t = h->tolerance_of(std::nullopt, d);
            ATLAS_ASSERT(t.has_value() && *t < keep::tolerances.size(), "every distance has a band on the ladder");
        }
    }
    if (const auto verified = p.verify(); !verified) (void)describe(verified.error());
    return 0;
}
