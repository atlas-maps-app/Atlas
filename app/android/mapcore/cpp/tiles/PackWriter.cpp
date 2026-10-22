// The render pack's writer (tiles/PackWriter.hpp).
#include "tiles/PackWriter.hpp"

#include "tiles/Checksums.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Sink.hpp"
#include "tiles/Source.hpp"

#include <algorithm>
#include <array>
#include <memory>
#include <numeric>
#include <string_view>
#include <utility>

namespace atlas::tiles {

namespace {

/// A section of the directory, its bytes written in the directory's order.
struct Entry {
    std::string_view name;
    std::uint64_t stored = 0;
    std::uint64_t raw = 0;
    std::uint32_t crc = 0;
    Method method = Method::stored;
};

/// The directory of `entries`, the first section at `base`.
Sink directory(std::span<const Entry> entries, std::uint64_t base) {
    Sink d;
    std::uint64_t offset = base;
    for (const Entry& e : entries) {
        d.utf8(e.name);
        d.u64be(offset);
        d.u64be(e.stored);
        d.u64be(e.raw);
        d.u32be(e.crc);
        d.u8(static_cast<std::uint8_t>(e.method));
        offset += e.stored;
    }
    return d;
}

} // namespace

std::uint64_t hilbert(std::uint8_t z, std::uint32_t x, std::uint32_t y) noexcept {
    std::uint64_t d = 0;
    const std::uint32_t last = (std::uint32_t{1} << z) - 1;
    for (std::uint32_t s = (std::uint32_t{1} << z) >> 1U; s > 0; s >>= 1U) {
        const std::uint32_t rx = (x & s) != 0 ? 1 : 0;
        const std::uint32_t ry = (y & s) != 0 ? 1 : 0;
        d += std::uint64_t{s} * s * ((3 * rx) ^ ry);
        if (ry == 0) {
            if (rx == 1) {
                x = last - x;
                y = last - y;
            }
            std::swap(x, y);
        }
    }
    return d;
}

std::vector<std::byte> zstd_raw_frame(std::span<const std::byte> raw) {
    Sink out;
    out.reserve(raw.size() + 16 + ((raw.size() >> 17U) * 3));
    out.u32(0xFD2F'B528U); // the frame's magic
    out.u8(0xE0);          // eight bytes of content size, a single segment, no checksum, no dictionary
    out.u64(raw.size());
    constexpr std::size_t block = std::size_t{128} << 10U; // zstd's largest block
    std::size_t at = 0;
    do {
        const std::size_t n = std::min(block, raw.size() - at);
        const bool last = at + n == raw.size();
        const std::uint64_t header = (last ? 1U : 0U) | (std::uint64_t{n} << 3U); // a raw block (type 0)
        out.u8(header & 0xFFU);
        out.u8((header >> 8U) & 0xFFU);
        out.u8((header >> 16U) & 0xFFU);
        out.raw(raw.subspan(at, n));
        at += n;
    } while (at < raw.size());
    return out.take();
}

namespace {

/// What the chunk table and `reach` say of one chunk: its tile, its stored frame's size, its raw size, its features,
/// its SHA-256 and its reach.
struct ChunkMeta {
    TileKey key;
    std::uint64_t stored = 0;
    std::uint64_t raw = 0;
    std::uint32_t count = 0;
    Sha256Digest sha256{};
    Reach reach;
};

/// The order the chunks go in: by rhead's levels, finest first, then along the Hilbert curve; or why they cannot be
/// packed (a tile of a level rhead lacks, a tile past max_tile_bytes, a tile twice).
Result<std::vector<std::size_t>> chunk_order(std::span<const ChunkMeta> chunks, std::span<const std::uint8_t> levels) {
    std::vector<std::pair<std::size_t, std::uint64_t>> rank(chunks.size());
    for (std::size_t i = 0; i < chunks.size(); ++i) {
        const TileKey k = chunks[i].key;
        const auto level = std::ranges::find(levels, k.z);
        if (level == levels.end()) return std::unexpected(error(Fault::bad_value, Site::chunk_table, k.packed()));
        if (chunks[i].raw > max_tile_bytes) return std::unexpected(error(Fault::too_big, Site::chunk, k.packed()));
        rank[i] = {static_cast<std::size_t>(level - levels.begin()), hilbert(k.z, k.x, k.y)};
    }
    std::vector<std::size_t> order(chunks.size());
    std::ranges::iota(order, std::size_t{0});
    std::ranges::stable_sort(order, [&](std::size_t a, std::size_t b) { return rank[a] < rank[b]; });
    for (std::size_t i = 1; i < order.size(); ++i) {
        if (chunks[order[i]].key.packed() == chunks[order[i - 1]].key.packed()) {
            return std::unexpected(error(Fault::duplicate, Site::chunk_table, chunks[order[i]].key.packed()));
        }
    }
    return order;
}

/// A pack's bytes around its chunks: before them the header, the manifest, the directory, the chunk table and
/// `reach`; after them `rhead` and the style.
struct Around {
    Sink before;
    Sink after;
};

/// The bytes around chunks laid out in `order`, their frames' CRC-32 `chunks_crc`.
Around around(std::span<const ChunkMeta> chunks, std::span<const std::size_t> order, std::uint32_t chunks_crc,
              const PackParts& parts) {
    Sink table;
    table.varu(chunks.size());
    Sink reach;
    reach.u32(reach_magic);
    reach.u16(payload_version);
    reach.u16(reach_entry_bytes);
    reach.u32(chunks.size());
    Entry entry{.name = section_chunks, .crc = chunks_crc, .method = Method::zstd};
    for (const std::size_t i : order) {
        const ChunkMeta& c = chunks[i];
        table.u8(c.key.z);
        table.varu(c.key.x);
        table.varu(c.key.y);
        table.varu(c.stored);
        table.varu(c.raw);
        table.varu(c.count);
        table.raw(c.sha256);
        reach.i32(c.reach.box.south);
        reach.i32(c.reach.box.west);
        reach.i32(c.reach.box.north);
        reach.i32(c.reach.box.east);
        reach.i32(c.reach.top_cm);
        entry.stored += c.stored;
        entry.raw += c.raw;
    }
    const std::vector<std::byte> style = zstd_raw_frame(parts.style);
    const std::array<Entry, 5> entries{{
        {.name = section_tiles, .stored = table.size(), .raw = table.size(), .crc = crc32(table.bytes())},
        {.name = section_reach, .stored = reach.size(), .raw = reach.size(), .crc = crc32(reach.bytes())},
        entry,
        {.name = section_head, .stored = parts.head.size(), .raw = parts.head.size(), .crc = crc32(parts.head)},
        {.name = section_style,
         .stored = style.size(),
         .raw = parts.style.size(),
         .crc = crc32(style),
         .method = Method::zstd},
    }};
    // the directory's size does not depend on the offsets in it (each a u64)
    const std::size_t directory_bytes = directory(entries, 0).size();
    const Sink dir = directory(entries, container_header_bytes + parts.manifest.size() + directory_bytes);
    Around out;
    out.before.reserve(container_header_bytes + parts.manifest.size() + dir.size() + table.size() + reach.size());
    for (const char c : std::string_view("ATLASPCK")) out.before.u8(static_cast<std::uint8_t>(c));
    out.before.u8(0);
    out.before.u8(format_render);
    out.before.u8(0);
    out.before.u8(0);
    out.before.u32be(parts.manifest.size());
    out.before.u32be(dir.size());
    out.before.raw(std::as_bytes(std::span(parts.manifest.data(), parts.manifest.size())));
    out.before.raw(dir.bytes());
    out.before.raw(table.bytes());
    out.before.raw(reach.bytes());
    out.after.raw(parts.head);
    out.after.raw(style);
    return out;
}

} // namespace

Result<std::vector<std::byte>> write_pack(std::span<const PackTile> tiles, const PackParts& parts) {
    std::vector<ChunkMeta> chunks(tiles.size());
    for (std::size_t i = 0; i < tiles.size(); ++i) {
        chunks[i] = {.key = tiles[i].key, .raw = tiles[i].raw.size(), .count = tiles[i].count, .reach = tiles[i].reach};
    }
    const auto order = chunk_order(chunks, parts.levels);
    if (!order) return std::unexpected(order.error());
    std::vector<std::vector<std::byte>> frames(tiles.size());
    std::uint32_t crc = 0;
    std::uint64_t stored = 0;
    for (const std::size_t i : *order) {
        frames[i] = zstd_raw_frame(tiles[i].raw);
        chunks[i].stored = frames[i].size();
        chunks[i].sha256 = Sha256::of(tiles[i].raw);
        crc = crc32(frames[i], crc);
        stored += frames[i].size();
    }
    const Around a = around(chunks, *order, crc, parts);
    Sink out;
    out.reserve(a.before.size() + stored + a.after.size());
    out.raw(a.before.bytes());
    for (const std::size_t i : *order) out.raw(frames[i]);
    out.raw(a.after.bytes());
    return out.take();
}

Result<std::unique_ptr<ByteSource>> compose_pack(std::span<const ComposedChunk> chunks, const PackParts& parts) {
    std::vector<ChunkMeta> metas(chunks.size());
    for (std::size_t i = 0; i < chunks.size(); ++i) {
        const ComposedChunk& c = chunks[i];
        if (!c.frame.source || c.frame.length == 0) return std::unexpected(error(Fault::missing, Site::chunk, i));
        metas[i] = {.key = c.key,
                    .stored = c.frame.length,
                    .raw = c.raw,
                    .count = c.count,
                    .sha256 = c.sha256,
                    .reach = c.reach};
    }
    const auto order = chunk_order(metas, parts.levels);
    if (!order) return std::unexpected(order.error());
    Around a = around(metas, *order, 0, parts);
    std::vector<SourceSlice> slices;
    slices.reserve(chunks.size() + 2);
    const auto before = std::make_shared<const MemorySource>(a.before.take());
    slices.push_back({.source = before, .offset = 0, .length = before->size()});
    for (const std::size_t i : *order) slices.push_back(chunks[i].frame);
    const auto after = std::make_shared<const MemorySource>(a.after.take());
    slices.push_back({.source = after, .offset = 0, .length = after->size()});
    return std::make_unique<ConcatSource>(std::move(slices));
}

} // namespace atlas::tiles
