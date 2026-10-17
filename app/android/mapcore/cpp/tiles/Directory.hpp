// The pack container's header and section directory (D1's container, plan-vector D2 and Appendix A; render/data's
// PackWriter and TiledPackWriter, rendertiles' Format3Writer).
//
// The header is 20 bytes: "ATLASPCK", a zero byte, the format byte, two zero bytes, then the JSON manifest's and the
// directory's lengths as big-endian u32. The manifest follows (for Kotlin and the tools; the core never parses it),
// then the directory: per section its name (a varint length and UTF-8), then big-endian u64 offset, u64 stored length,
// u64 raw length, u32 CRC-32 of the stored bytes, and a method byte. Formats 2 (element packs, D1) and 3 (render packs)
// share it; format 1 has 32-bit lengths and no chunk table, and the core refuses it. A reader finds sections by name
// and skips one it does not know, whatever its method.
#pragma once

#include "tiles/Error.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::tiles {

/// The container's fixed header size.
inline constexpr std::size_t container_header_bytes = 20;

/// The formats with a chunk table: D1's element packs and the render packs.
inline constexpr std::uint8_t format_elements = 2;
inline constexpr std::uint8_t format_render = 3;

/// The section methods: stored, deflated (raw DEFLATE, element packs), zstd (render packs). A section the reader does
/// not know may carry another, which it keeps as read.
enum class Method : std::uint8_t { stored = 0, deflate = 1, zstd = 2 };

/// The sections a pack has (a render pack's in the order it writes them).
inline constexpr std::string_view section_tiles = "tiles";
inline constexpr std::string_view section_reach = "reach";
inline constexpr std::string_view section_chunks = "chunks";
inline constexpr std::string_view section_head = "rhead";
inline constexpr std::string_view section_style = "style";

/// What the header says.
struct ContainerHeader {
    std::uint8_t format = 0;
    std::uint32_t manifest_bytes = 0;
    std::uint32_t directory_bytes = 0;

    /// Where the directory starts and where the sections may start.
    [[nodiscard]] std::uint64_t directory_offset() const noexcept {
        return container_header_bytes + std::uint64_t{manifest_bytes};
    }
    [[nodiscard]] std::uint64_t body_offset() const noexcept { return directory_offset() + directory_bytes; }
};

/// One entry of the directory.
struct Section {
    std::string name;
    std::uint64_t offset = 0;
    std::uint64_t stored = 0;
    std::uint64_t raw = 0;
    std::uint32_t crc = 0;
    Method method = Method::stored;
};

/// Reads the header from the pack's first 20 bytes (or more); refuses anything but formats 2 and 3, and a header whose
/// manifest and directory run past `pack_size`.
[[nodiscard]] Result<ContainerHeader> read_container_header(std::span<const std::byte> bytes, std::uint64_t pack_size);

/// Reads the directory's bytes: every section inside the pack's `pack_size` bytes, each name once, the method of each
/// section the core reads known.
[[nodiscard]] Result<std::vector<Section>> read_directory(std::span<const std::byte> bytes, std::uint64_t pack_size);

/// The section named `name`, or null.
[[nodiscard]] const Section* find_section(std::span<const Section> sections, std::string_view name) noexcept;

} // namespace atlas::tiles
