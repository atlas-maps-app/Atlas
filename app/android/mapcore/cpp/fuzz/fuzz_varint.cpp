// Fuzzes the varint reader (base/varint.hpp) with arbitrary bytes: it must never read past the input, and every value
// it accepts must be the one shortest encoding of itself, so writing it again gives back the very bytes it consumed.
#include "base/assert.hpp"
#include "base/varint.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    atlas::VarintReader reader(bytes);
    while (!reader.done()) {
        const std::size_t start = reader.position();
        const auto value = reader.next();
        ATLAS_ASSERT(reader.position() <= size, "read past the input");
        if (!value) break;
        std::array<std::byte, atlas::varint_max_bytes> again{};
        const std::size_t n = atlas::write_varint(*value, again);
        ATLAS_ASSERT(n == reader.position() - start, "an accepted varint is not the shortest encoding");
        ATLAS_ASSERT(std::memcmp(again.data(), bytes.data() + start, n) == 0, "re-encoding differs from the input");
        ATLAS_ASSERT(atlas::unzigzag(atlas::zigzag(atlas::unzigzag(*value))) == atlas::unzigzag(*value), "zigzag");
    }
    return 0;
}
