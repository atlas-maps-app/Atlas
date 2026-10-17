// The byte layouts the core shares with Kotlin and with the shaders, as tables: the theme block and the camera state
// (written by MK/state/ThemeBlock.kt and CameraWriter.kt) and theme.slang's Theme (the uniforms the shaders read), as
// offset tables; and every number of those layouts that is not an offset (versions, sizes, flag bits, moment names,
// profiles), as a constants table. MT/style/LayoutTest.kt asks the host build for these tables and holds Kotlin's own
// offsets and constants to them, so neither side copies the other's by hand.
#pragma once

#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::style {

/// One field of a shared layout: its name, its offset and its size in bytes.
struct Field {
    std::string_view name;
    std::uint32_t offset;
    std::uint32_t size;
};

/// One number of a shared layout, named as Kotlin names it (ThemeBlock.NIGHT, CameraWriter.PROFILE_REGION, the
/// moments as ThemeBlock.MOMENTS[name]).
struct Constant {
    std::string_view name;
    std::uint32_t value;
};

/// The theme block's fields (style/Theme.hpp), in order.
[[nodiscard]] std::span<const Field> theme_block_fields() noexcept;

/// The camera state's fields (style/CameraState.hpp), in order; the keep-out shapes as the first one's fields and the
/// array's stride (`keep_out` with the size of all sixteen).
[[nodiscard]] std::span<const Field> camera_state_fields() noexcept;

/// theme.slang's Theme as the C++ twin lays it out, which Slang's reflection of the shaders must equal.
[[nodiscard]] std::span<const Field> shader_theme_fields() noexcept;

/// The theme block's and the camera state's versions, sizes, flag bits, moment names and profiles.
[[nodiscard]] std::span<const Constant> layout_constants() noexcept;

} // namespace atlas::style
