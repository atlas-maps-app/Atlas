// The render pack's writer (tiles/PackWriter.hpp).
#include "tiles/PackWriter.hpp"

#include "tiles/Checksums.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Sink.hpp"

#include <algorithm>
#include <array>
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

Result<std::vector<std::byte>> write_pack(std::span<const PackTile> tiles, const PackParts& parts) {
    // the chunks: by rhead's levels, finest first, then along the Hilbert curve
    std::vector<std::pair<std::size_t, std::uint64_t>> rank(tiles.size());
    for (std::size_t i = 0; i < tiles.size(); ++i) {
        const TileKey k = tiles[i].key;
        const auto level = std::ranges::find(parts.levels, k.z);
        if (level == parts.levels.end()) return std::unexpected(error(Fault::bad_value, Site::chunk_table, k.packed()));
        if (tiles[i].raw.size() > max_tile_bytes) {
            return std::unexpected(error(Fault::too_big, Site::chunk, k.packed()));
        }
        rank[i] = {static_cast<std::size_t>(level - parts.levels.begin()), hilbert(k.z, k.x, k.y)};
    }
    std::vector<std::size_t> order(tiles.size());
    std::ranges::iota(order, std::size_t{0});
    std::ranges::stable_sort(order, [&](std::size_t a, std::size_t b) { return rank[a] < rank[b]; });
    for (std::size_t i = 1; i < order.size(); ++i) {
        if (tiles[order[i]].key.packed() == tiles[order[i - 1]].key.packed()) {
            return std::unexpected(error(Fault::duplicate, Site::chunk_table, tiles[order[i]].key.packed()));
        }
    }
    Sink table;
    table.varu(tiles.size());
    Sink reach;
    reach.u32(reach_magic);
    reach.u16(payload_version);
    reach.u16(reach_entry_bytes);
    reach.u32(tiles.size());
    std::vector<std::vector<std::byte>> frames;
    frames.reserve(tiles.size());
    Entry chunks{.name = section_chunks, .method = Method::zstd};
    for (const std::size_t i : order) {
        const PackTile& t = tiles[i];
        std::vector<std::byte>& frame = frames.emplace_back(zstd_raw_frame(t.raw));
        const Sha256Digest digest = Sha256::of(t.raw);
        table.u8(t.key.z);
        table.varu(t.key.x);
        table.varu(t.key.y);
        table.varu(frame.size());
        table.varu(t.raw.size());
        table.varu(t.count);
        table.raw(digest);
        reach.i32(t.reach.box.south);
        reach.i32(t.reach.box.west);
        reach.i32(t.reach.box.north);
        reach.i32(t.reach.box.east);
        reach.i32(t.reach.top_cm);
        chunks.stored += frame.size();
        chunks.raw += t.raw.size();
        chunks.crc = crc32(frame, chunks.crc);
    }
    const std::vector<std::byte> style = zstd_raw_frame(parts.style);
    const std::array<Entry, 5> entries{{
        {.name = section_tiles, .stored = table.size(), .raw = table.size(), .crc = crc32(table.bytes())},
        {.name = section_reach, .stored = reach.size(), .raw = reach.size(), .crc = crc32(reach.bytes())},
        chunks,
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
    Sink out;
    std::uint64_t total = container_header_bytes + parts.manifest.size() + dir.size();
    for (const Entry& e : entries) total += e.stored;
    out.reserve(total);
    for (const char c : std::string_view("ATLASPCK")) out.u8(static_cast<std::uint8_t>(c));
    out.u8(0);
    out.u8(format_render);
    out.u8(0);
    out.u8(0);
    out.u32be(parts.manifest.size());
    out.u32be(dir.size());
    out.raw(std::as_bytes(std::span(parts.manifest.data(), parts.manifest.size())));
    out.raw(dir.bytes());
    out.raw(table.bytes());
    out.raw(reach.bytes());
    for (const std::vector<std::byte>& frame : frames) out.raw(frame);
    out.raw(parts.head);
    out.raw(style);
    return out.take();
}

} // namespace atlas::tiles
