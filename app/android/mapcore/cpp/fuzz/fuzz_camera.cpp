// Fuzzes the camera state's reader (style/CameraState.hpp, K5) with arbitrary bytes (plan-vector T10): it must never
// read past the input or hit undefined behaviour, a refusal must name its reason, and a state it accepts must read
// back the same from its own bytes. The seeds in corpus/fuzz_camera/ are states the reader accepts: K1's stand-in
// board views (standin::board_view) at the sizes its tests and swapchain bench use, at map pitch and the steepest.
#include "base/assert.hpp"
#include "style/CameraState.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    const auto state = atlas::style::read_camera_state(bytes);
    if (!state) {
        const char* reason = atlas::style::name_of(state.error());
        ATLAS_ASSERT(reason != nullptr && *reason != '\0', "a refusal without a name");
        return 0;
    }
    std::array<std::byte, sizeof(atlas::style::CameraState)> again{};
    std::memcpy(again.data(), &*state, again.size());
    const auto reread = atlas::style::read_camera_state(again);
    ATLAS_ASSERT(reread.has_value(), "an accepted state is refused from its own bytes");
    decltype(again) twice{};
    std::memcpy(twice.data(), &*reread, twice.size());
    ATLAS_ASSERT(twice == again, "an accepted state reads back differently"); // byte for byte
    return 0;
}
