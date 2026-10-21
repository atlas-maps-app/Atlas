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

    /// The set of `sprites` at `revision` (the camera state's labels revision), drawn no smaller than `least` of their
    /// own size (the readable floor, from Android's text size: placement's Frame::least), or why not.
    [[nodiscard]] static std::expected<std::shared_ptr<const LabelSet>, SpriteError>
    make(std::vector<LabelSprite> sprites, std::uint64_t revision, float least = 0.0F);

    [[nodiscard]] std::span<const LabelSprite> sprites() const noexcept { return sprites_; }
    /// Each sprite's first texel in texels().
    [[nodiscard]] std::span<const std::uint32_t> offsets() const noexcept { return offsets_; }
    /// Every sprite's pixels, one after another.
    [[nodiscard]] std::span<const std::uint32_t> texels() const noexcept { return texels_; }
    [[nodiscard]] std::uint64_t revision() const noexcept { return revision_; }
    [[nodiscard]] float least() const noexcept { return least_; }

private:
    LabelSet() = default;
    std::vector<LabelSprite> sprites_;
    std::vector<std::uint32_t> offsets_;
    std::vector<std::uint32_t> texels_;
    std::uint64_t revision_ = 0;
    float least_ = 0.0F;
};

/// Where a placed label's sprite goes on the frame, device px: its top-left corner and its size, its anchor landing on
/// the stand's x and baseline. At its own size (Drawn::scale 1) on whole pixels, those rounded as Kotlin's roundToInt
/// rounds them (LabelPainter draws its text there, so the two match pixel for pixel); scaled, the sprite scaled about
/// its anchor, where it falls.
struct SpriteAt {
    float x = 0.0F;
    float y = 0.0F;
    float width = 0.0F;
    float height = 0.0F;
    float scale = 1.0F;
};
[[nodiscard]] SpriteAt sprite_at(const LabelSprite& sprite, const Drawn& drawn) noexcept;

/// The magic number that opens a block of label sprites ('LBL1', little-endian).
inline constexpr std::uint32_t label_block_magic = 0x314C'424CU;

/// The sprites of a block as the app writes it (MK/state/LabelBlock.kt; Native.labels), little-endian: the magic and
/// the count (u32 each); per sprite its id and priority (i32), its measures (f32: half its text's width plus the pad,
/// the pad, its text's height, its baseline's offset), its width and height (u32), its anchor (i32 x, y), its points'
/// count (u32), its points (f64 latitude, longitude each), its pixels (width x height u32). Or why it was refused.
[[nodiscard]] std::expected<std::vector<LabelSprite>, std::string> read_label_block(std::span<const std::byte> block);

} // namespace atlas::labels
