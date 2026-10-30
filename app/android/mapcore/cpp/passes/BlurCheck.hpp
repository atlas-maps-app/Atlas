// VK facts' blur check (ENGINE.md step 8: squares every 32 texels of the soft shadows' blur on the user's PowerVR,
// which lavapipe draws right): whether the device makes the soft shadows' blurred mask as the CPU's twin does
// (fx::square_blur_cpu, which the host's tests hold the GPU's to bit for bit), block by block of the blur's workgroups.
//
// It takes a known pattern, rectangles in whole texels of a 1,000-texel square (no whole number of the blur's 32-texel
// blocks), that leave some blocks empty, fill others whole and cut the rest at and about their edges, and blurs it with
// the frame's own pipeline (shadow_blur.slang's three passes) two ways: from the mask uploaded as the blur reads it
// (the blur's compute alone), and from the mask drawn through the frame's own mask pass (4x samples cleared, the
// rectangles drawn by mask_fs, resolved: in lazily allocated memory where the frame's are), each at a narrow box and at
// the widest the grid allows; and drawn once more with the mask cleared as the pass begins (its resolve then writes
// every texel over a clear one, should a driver leave some tile unresolved). The drawn masks are read back too. Each is
// held to the CPU's: the drawn masks to the pattern (252 a texel the rectangles cover, else 0), each blur to
// square_blur_cpu of the pattern. What differs is named by the blocks it falls in: how many, how many of them hold none
// of the pattern within the box's reach, how many of the differing texels lie on a block's edge, and the largest
// difference. VK facts runs it behind the crash guard's checks stage (standin/Session.hpp's check), as the composite
// check.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/PipelineCache.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace atlas::passes {

/// The pattern's side, texels: no whole number of the blur's blocks.
inline constexpr std::uint32_t blur_check_side = 1000;

/// The boxes the check blurs at: a narrow one, and the widest the soft shadows' grid allows (ground.slang's
/// SHADOW_HALF_MOST, shadow_blur.slang's HALF_MOST).
inline constexpr std::array<std::uint32_t, 2> blur_check_boxes{9, 49};

/// How one image the check read back compares with the CPU's.
struct BlurCompare {
    std::uint32_t texels = 0; ///< texels that differ
    std::uint32_t most = 0;   ///< the largest difference, levels
    std::uint32_t blocks = 0; ///< 32-texel blocks with a differing texel
    std::uint32_t empty = 0;  ///< of them, blocks whose CPU result holds nothing (no pattern within the box's reach)
    std::uint32_t edge = 0;   ///< differing texels on a block's edge (its first or last row or column)
    std::uint32_t first_x = 0;
    std::uint32_t first_y = 0;

    [[nodiscard]] bool exact() const noexcept { return texels == 0; }
};

/// `got` against `want`, `side` × `side` texels a byte each, block by block.
[[nodiscard]] BlurCompare compare_blocks(std::span<const std::uint8_t> got, std::span<const std::uint8_t> want,
                                         std::uint32_t side) noexcept;

/// One of the check's blurs.
struct BlurRun {
    bool drawn = false;   ///< the mask drawn through the mask pass, else uploaded
    bool cleared = false; ///< drawn with the mask cleared as the pass begins
    std::uint32_t box = 0;
    BlurCompare blur;
};

/// What the check found.
struct BlurCheck {
    bool lazy = false;   ///< the mask's samples in lazily allocated memory, as the frame's
    BlurCompare mask;    ///< the mask drawn by the frame's pass against the pattern
    BlurCompare cleared; ///< the mask drawn with it cleared first
    std::vector<BlurRun> runs;

    [[nodiscard]] bool exact() const noexcept;
};

/// The pattern: its rectangles, left, top, right and bottom in whole texels.
[[nodiscard]] std::span<const std::array<std::int32_t, 4>> blur_check_pattern() noexcept;

/// The check on `device`, its pipelines through `cache`. `poisoned`: the blur's module that fills its group memory
/// before each tile (shadow_blur_poison.slang), the tests' check that it reads no word a tile has not written.
[[nodiscard]] gpu::Result<BlurCheck> blur_check(const gpu::Device& device, const gpu::PipelineCache& cache,
                                                bool poisoned = false);

/// VK facts' line: `shadow.blur: …`, exact (what it blurred), what differs (by mask and run), or unavailable (why).
[[nodiscard]] std::string blur_line(const BlurCheck& check);
[[nodiscard]] std::string blur_line(const gpu::Device& device, const gpu::PipelineCache& cache);

} // namespace atlas::passes
