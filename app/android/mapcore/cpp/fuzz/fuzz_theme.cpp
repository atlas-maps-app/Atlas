// Fuzzes the theme block's reader (style/Theme.hpp, K5) with arbitrary bytes (plan-vector T10): it must never read
// past the input or hit undefined behaviour, a refusal must name its reason, and a block it accepts must read back
// the same from its own bytes and go through the shaders' Theme and the dump. The seeds in corpus/fuzz_theme/ are
// blocks the reader accepts, made from K5's tests' moments (style tests' sunny and night moments of the port, and the
// edge block they accept: 29 February, an azimuth of 360, overcast, dusk, wet, tone and golden).
#include "base/assert.hpp"
#include "style/Theme.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>
#include <string>

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    const auto block = atlas::style::read_theme_block(bytes);
    if (!block) {
        const char* reason = atlas::style::name_of(block.error());
        ATLAS_ASSERT(reason != nullptr && *reason != '\0', "a refusal without a name");
        return 0;
    }
    std::array<std::byte, sizeof(atlas::style::ThemeBlock)> again{};
    std::memcpy(again.data(), &*block, again.size());
    const auto reread = atlas::style::read_theme_block(again);
    ATLAS_ASSERT(reread.has_value(), "an accepted block is refused from its own bytes");
    decltype(again) twice{};
    std::memcpy(twice.data(), &*reread, twice.size());
    ATLAS_ASSERT(twice == again, "an accepted block reads back differently"); // byte for byte
    (void)atlas::style::shader_theme(*block);
    ATLAS_ASSERT(!atlas::style::dump(*block).empty(), "an accepted block dumps to nothing");
    return 0;
}
