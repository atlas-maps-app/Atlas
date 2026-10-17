// A pack, opened (D1's container, plan-vector D2 and Appendix A): its header, directory, chunk table and, for a render
// pack, its `reach`, `rhead` and `style`, read from a byte source (mapped, or fetched by ranges) and checked: every
// section inside the pack, the CRC-32 of each section read whole (the chunk table, `reach`, `rhead`, `style`), each
// chunk's SHA-256 when it is decompressed. The `chunks` section, possibly gigabytes read a chunk at a time (by ranges,
// for a remote pack), has its CRC-32 checked only by verify(), which reads it whole; its chunks' own hashes protect it
// chunk by chunk. Sections the core does not know are skipped.
#pragma once

#include "base/arena.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Error.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Source.hpp"

#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <vector>

namespace atlas::tiles {

/// An opened pack: format 3 (render tiles, which the core draws) or format 2 (an element pack: its container only).
class Pack {
public:
    /// Opens the pack in `source`: header, directory, chunk table; for a render pack `reach`, `rhead` and `style` too.
    [[nodiscard]] static Result<Pack> open(std::unique_ptr<ByteSource> source);

    Pack(const Pack&) = delete;
    Pack& operator=(const Pack&) = delete;
    Pack(Pack&&) noexcept = default;
    Pack& operator=(Pack&&) noexcept = default;
    ~Pack() = default;

    [[nodiscard]] std::uint8_t format() const noexcept { return header_.format; }
    [[nodiscard]] bool is_render() const noexcept { return header_.format == format_render; }
    [[nodiscard]] const ContainerHeader& header() const noexcept { return header_; }
    [[nodiscard]] std::span<const Section> sections() const noexcept { return sections_; }
    [[nodiscard]] const ChunkTable& chunks() const noexcept { return chunks_; }
    [[nodiscard]] const ByteSource& source() const noexcept { return *source_; }

    /// `rhead` and `style` (render packs only; null for an element pack).
    [[nodiscard]] const RenderHead* head() const noexcept { return head_ ? &*head_ : nullptr; }
    [[nodiscard]] const StyleTables* style() const noexcept { return style_ ? &*style_ : nullptr; }

    /// A chunk's stored (compressed) bytes: in place for a mapped pack, else copied into `scratch`.
    [[nodiscard]] Result<std::span<const std::byte>> stored(const ChunkRef& chunk,
                                                            std::vector<std::byte>& scratch) const;

    /// A chunk's decompressed bytes, checked against its SHA-256, into `out` (resized to its raw length).
    [[nodiscard]] Result<void> unpack(const ChunkRef& chunk, Zstd& zstd, std::vector<std::byte>& out,
                                      std::vector<std::byte>& scratch, bool verify = true) const;

    /// A chunk's tile: unpacked into `raw`, decoded into `arena` and checked against the chunk (check_against). The
    /// tile views `raw` and `arena`.
    [[nodiscard]] Result<Tile> decode(const ChunkRef& chunk, Zstd& zstd, TileDecoder& decoder, Arena& arena,
                                      std::vector<std::byte>& raw, std::vector<std::byte>& scratch) const;

    /// Reads every section whole and checks its CRC-32, and every chunk's SHA-256 (a render pack's).
    [[nodiscard]] Result<void> verify() const;

private:
    Pack() = default;

    std::unique_ptr<ByteSource> source_;
    ContainerHeader header_;
    std::vector<Section> sections_;
    ChunkTable chunks_;
    std::optional<RenderHead> head_;
    std::optional<StyleTables> style_;
};

/// A section's bytes as stored, checked against its CRC-32, then decompressed by its method (stored or zstd) into an
/// owned buffer of its raw length (at most `max_raw`).
[[nodiscard]] Result<std::vector<std::byte>> read_section(const ByteSource& source, const Section& section, Site site,
                                                          std::uint64_t max_raw, Zstd& zstd);

} // namespace atlas::tiles
