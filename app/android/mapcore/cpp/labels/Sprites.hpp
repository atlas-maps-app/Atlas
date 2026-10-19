// The board's labels as the app gives them (plan-vector D13, K13; MapCore.labels, Appendix D): each label's sprite,
// which the app's LabelSprites draws with the device's own Skia exactly as LabelPainter draws the label (its text and
// its halos, premultiplied RGBA8 at the density), and what placement needs (its id, priority, measures and points). A
// labels() call makes a LabelSet: immutable, shared by the frames that draw it, its sprites' texels one after another
// in one buffer (the Labels pass copies them texel for texel onto whole pixels, so no packing in two dimensions).
#pragma once

#include "labels/Placement.hpp"

#include <array>
#include <cstdint>
#include <expected>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace atlas::labels {

/// One label's sprite and its placement's inputs.
struct LabelSprite {
    /// Stable across labels() calls: the label's fades and kept place follow it.
    std::uint32_t id = 0;
    std::int32_t priority = 0;
    Measures measures;
    /// Its points, the likeliest first: latitude and longitude, degrees.
    std::vector<std::array<double, 2>> points;
    /// The sprite: width x height px, premultiplied RGBA8 (R in the low byte), rows top to bottom.
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::vector<std::uint32_t> pixels;
    /// Where the text's anchor lies in the sprite, whole px: LabelPainter's drawText x (the text's centre) and its
    /// baseline.
    std::int32_t anchor_x = 0;
    std::int32_t anchor_y = 0;
};

/// Why a set of labels was refused.
enum class SpriteError : std::uint8_t {
    size,   ///< a sprite's pixels are not width x height, or it is empty
    large,  ///< a sprite is larger than max_sprite_side on a side
    budget, ///< the sprites' texels exceed max_texels
    points, ///< a label has no points, or more than max_points
};

[[nodiscard]] std::string_view describe(SpriteError error) noexcept;

/// The labels of one labels() call (a struct: the frame's FrameInputs and FrameState name it so).
struct LabelSet {
public:
    /// The largest sprite side, px (a long name at 21 dp at density 4 with its glow: well under it).
    static constexpr std::uint32_t max_sprite_side = 2048;
    /// The texels of all the sprites at most: 16 MB (plan-vector §3 budgets 8 MB of sprites at S1, 14 MB at S3).
    static constexpr std::uint32_t max_texels = 4U << 20U;
    /// A label's points at most.
    static constexpr std::size_t max_points = 1024;

    /// The set of `sprites` at `revision` (the camera state's labels revision), or why not.
    [[nodiscard]] static std::expected<std::shared_ptr<const LabelSet>, SpriteError>
    make(std::vector<LabelSprite> sprites, std::uint64_t revision);

    [[nodiscard]] std::span<const LabelSprite> sprites() const noexcept { return sprites_; }
    /// Each sprite's first texel in texels().
    [[nodiscard]] std::span<const std::uint32_t> offsets() const noexcept { return offsets_; }
    /// Every sprite's pixels, one after another.
    [[nodiscard]] std::span<const std::uint32_t> texels() const noexcept { return texels_; }
    [[nodiscard]] std::uint64_t revision() const noexcept { return revision_; }

private:
    LabelSet() = default;
    std::vector<LabelSprite> sprites_;
    std::vector<std::uint32_t> offsets_;
    std::vector<std::uint32_t> texels_;
    std::uint64_t revision_ = 0;
};

/// Where a placed label's sprite goes on the frame: its top-left pixel, so that the sprite's anchor lands on the
/// stand's x and baseline rounded as Kotlin's roundToInt rounds them (LabelPainter draws its text there, so the two
/// match pixel for pixel).
struct SpriteAt {
    std::int32_t x = 0;
    std::int32_t y = 0;
};
[[nodiscard]] SpriteAt sprite_at(const LabelSprite& sprite, const Drawn& drawn) noexcept;

} // namespace atlas::labels
