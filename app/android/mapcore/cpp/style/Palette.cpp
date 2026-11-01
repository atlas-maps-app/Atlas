#include "style/Palette.hpp"

#include <algorithm>
#include <cstring>

namespace atlas::style {

bool PaletteBlock::today() const noexcept {
    return flags == 0U && std::ranges::all_of(colours, [](std::uint32_t c) { return c == 0U; });
}

std::string_view name_of(PaletteError error) noexcept {
    switch (error) {
    case PaletteError::size: return "size";
    case PaletteError::version: return "version";
    case PaletteError::flags: return "flags";
    case PaletteError::reserved: return "reserved";
    case PaletteError::colour: return "colour";
    }
    return "?";
}

std::expected<PaletteBlock, PaletteError> read_palette_block(std::span<const std::byte> bytes) noexcept {
    if (bytes.size() != palette_block_size) return std::unexpected(PaletteError::size);
    PaletteBlock b{};
    std::memcpy(&b, bytes.data(), sizeof b);
    if (b.version != palette_block_version) return std::unexpected(PaletteError::version);
    if ((b.flags & ~palette_flag::known) != 0U) return std::unexpected(PaletteError::flags);
    if (b.reserved != 0U) return std::unexpected(PaletteError::reserved);
    if (!std::ranges::all_of(b.colours, [](std::uint32_t c) { return c == 0U || (c >> 24U) == 1U; })) {
        return std::unexpected(PaletteError::colour);
    }
    return b;
}

} // namespace atlas::style
