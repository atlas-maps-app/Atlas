#include "tiles/Container.hpp"

#include "tiles/Checksums.hpp"

#include <algorithm>
#include <array>
#include <utility>

namespace atlas::tiles {

Result<std::vector<std::byte>> read_section(const ByteSource& source, const Section& section, Site site,
                                            std::uint64_t max_raw, Zstd& zstd) {
    if (section.raw > max_raw || section.stored > max_raw)
        return std::unexpected(error(Fault::too_big, site, section.raw));
    std::vector<std::byte> scratch;
    auto stored = source.bytes(section.offset, section.stored, scratch);
    if (!stored) return std::unexpected(stored.error());
    if (crc32(*stored) != section.crc) return std::unexpected(error(Fault::damaged, site));
    switch (section.method) {
    case Method::stored:
        if (section.raw != section.stored) return std::unexpected(error(Fault::bad_count, site, section.raw));
        if (stored->data() == scratch.data()) return scratch;
        return std::vector<std::byte>(stored->begin(), stored->end());
    case Method::zstd: {
        std::vector<std::byte> raw(static_cast<std::size_t>(section.raw));
        if (auto done = zstd.frame(*stored, raw, site); !done) return std::unexpected(done.error());
        return raw;
    }
    case Method::deflate: break;
    }
    return std::unexpected(error(Fault::unsupported, site, static_cast<std::uint64_t>(section.method)));
}

Result<Pack> Pack::open(std::unique_ptr<ByteSource> source) {
    Pack p;
    p.source_ = std::move(source);
    const ByteSource& src = *p.source_;
    std::vector<std::byte> scratch;

    auto first = src.bytes(0, std::min<std::uint64_t>(container_header_bytes, src.size()), scratch);
    if (!first) return std::unexpected(first.error());
    auto header = read_container_header(*first, src.size());
    if (!header) return std::unexpected(header.error());
    p.header_ = *header;

    auto dir = src.bytes(p.header_.directory_offset(), p.header_.directory_bytes, scratch);
    if (!dir) return std::unexpected(dir.error());
    auto sections = read_directory(*dir, src.size());
    if (!sections) return std::unexpected(sections.error());
    p.sections_ = std::move(*sections);

    const Section* tiles = find_section(p.sections_, section_tiles);
    const Section* chunks = find_section(p.sections_, section_chunks);
    if (tiles == nullptr) return std::unexpected(error(Fault::missing, Site::chunk_table));
    if (chunks == nullptr) return std::unexpected(error(Fault::missing, Site::section));
    if (tiles->method != Method::stored) return std::unexpected(error(Fault::bad_value, Site::chunk_table));

    Zstd zstd;
    std::span<const std::uint8_t> levels;
    if (p.is_render()) {
        const Section* head = find_section(p.sections_, section_head);
        const Section* style = find_section(p.sections_, section_style);
        if (head == nullptr) return std::unexpected(error(Fault::missing, Site::head));
        if (style == nullptr) return std::unexpected(error(Fault::missing, Site::style));
        auto head_bytes = read_section(src, *head, Site::head, max_section_raw, zstd);
        if (!head_bytes) return std::unexpected(head_bytes.error());
        auto h = read_head(*head_bytes);
        if (!h) return std::unexpected(h.error());
        p.head_.emplace(std::move(*h));
        levels = p.head_->levels;
        auto style_bytes = read_section(src, *style, Site::style, max_section_raw, zstd);
        if (!style_bytes) return std::unexpected(style_bytes.error());
        auto s = read_style(std::move(*style_bytes));
        if (!s) return std::unexpected(s.error());
        auto typed = Style::of(*s);
        if (!typed) return std::unexpected(typed.error());
        p.style_tables_.emplace(std::move(*s));
        p.style_.emplace(std::move(*typed));
    }

    // The chunk table can be long (a planet's base asset holds about a hundred thousand chunks): no size cap but the
    // pack's.
    auto table = read_section(src, *tiles, Site::chunk_table, src.size(), zstd);
    if (!table) return std::unexpected(table.error());
    auto read = read_chunk_table(*table, *chunks, p.header_.format, levels);
    if (!read) return std::unexpected(read.error());
    p.chunks_ = std::move(*read);

    if (p.is_render()) {
        // every chunk's reach: what a frame picks its chunks by, without decompressing any
        const Section* reach = find_section(p.sections_, section_reach);
        if (reach == nullptr) return std::unexpected(error(Fault::missing, Site::reach));
        const std::uint64_t most = reach_header_bytes + (std::uint64_t{0xFFFF} * p.chunks_.chunks().size());
        auto reach_bytes = read_section(src, *reach, Site::reach, most, zstd);
        if (!reach_bytes) return std::unexpected(reach_bytes.error());
        if (auto done = read_reach(*reach_bytes, p.chunks_); !done) return std::unexpected(done.error());
    }
    return p;
}

Result<std::span<const std::byte>> Pack::stored(const ChunkRef& chunk, std::vector<std::byte>& scratch) const {
    return source_->bytes(chunk.offset, chunk.stored, scratch);
}

Result<void> Pack::unpack(const ChunkRef& chunk, Zstd& zstd, std::vector<std::byte>& out,
                          std::vector<std::byte>& scratch, bool verify) const {
    if (!is_render()) return std::unexpected(error(Fault::unsupported, Site::chunk, chunk.tile.packed()));
    auto bytes = stored(chunk, scratch);
    if (!bytes) return std::unexpected(bytes.error());
    out.resize(chunk.raw);
    return unpack_chunk(zstd, chunk, *bytes, header_.format, out, verify);
}

Result<Tile> Pack::decode(const ChunkRef& chunk, Zstd& zstd, TileDecoder& decoder, Arena& arena,
                          std::vector<std::byte>& raw, std::vector<std::byte>& scratch) const {
    if (auto done = unpack(chunk, zstd, raw, scratch); !done) return std::unexpected(done.error());
    auto tile = decoder.decode(raw, arena);
    if (!tile) return tile;
    if (auto checked = check_against(*tile, chunk); !checked) return std::unexpected(checked.error());
    return tile;
}

Result<void> Pack::verify() const {
    std::vector<std::byte> scratch;
    for (const Section& s : sections_) {
        // a section read a megabyte at a time, so a remote pack's never needs one buffer of its size
        std::uint32_t crc = 0;
        constexpr std::uint64_t step = 1U << 20U;
        for (std::uint64_t at = 0; at < s.stored; at += step) {
            auto piece = source_->bytes(s.offset + at, std::min(step, s.stored - at), scratch);
            if (!piece) return std::unexpected(piece.error());
            crc = crc32(*piece, crc);
        }
        if (crc != s.crc)
            return std::unexpected(
                error(Fault::damaged, Site::section, static_cast<std::uint64_t>(&s - sections_.data())));
    }
    if (!is_render()) return {};
    Zstd zstd;
    std::vector<std::byte> raw;
    for (const ChunkRef& c : chunks_.chunks()) {
        if (auto done = unpack(c, zstd, raw, scratch); !done) return done;
    }
    return {};
}

} // namespace atlas::tiles
