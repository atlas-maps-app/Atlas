// The board's labels as the app gives them (plan-vector D13, K13; MapCore.labels, Appendix D): each label's sprite,
// which the app's LabelSprites draws with the device's own Skia exactly as LabelPainter draws the label (its text and
// its halos, premultiplied RGBA8 at the density), and what placement needs (its id, priority, measures and points). A
// sprite holds two images of its label: at its own size, which the Labels pass copies texel for texel onto whole pixels
// where the label stands at its own size, and drawn again at the band's top (Placement::band_high, the set's
// large_scale), which a grown label is drawn from, so it stays sharp as it grows (ENGINE.md design point 8). A labels()
// call makes a LabelSet: immutable, shared by the frames that draw it, its images' texels one after another in one
// buffer (no packing in two dimensions).
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

/// One image of a label: width x height px, premultiplied RGBA8 (R in the low byte), rows top to bottom; and where the
/// text's anchor lies in it, whole px: LabelPainter's drawText x (the text's centre) and its baseline. 0 x 0: none.
struct SpriteImage {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::vector<std::uint32_t> pixels;
    std::int32_t anchor_x = 0;
    std::int32_t anchor_y = 0;

    [[nodiscard]] bool empty() const noexcept { return width == 0 || height == 0; }
};

/// One label's sprite and its placement's inputs.
struct LabelSprite {
    /// Stable across labels() calls: the label's fades and kept place follow it.
    std::uint32_t id = 0;
    std::int32_t priority = 0;
    Measures measures;
    /// Its points, the likeliest first: latitude and longitude, degrees.
    std::vector<std::array<double, 2>> points;
    /// The label at its own size: LabelPainter's pixels.
    SpriteImage own;
    /// The label drawn at the set's large_scale times its own size (the band's top), or none: a grown label is drawn
    /// from it, else from its own image.
    SpriteImage large;
};

/// Why a set of labels was refused.
enum class SpriteError : std::uint8_t {
    size,   ///< an image's pixels are not its width x height, or a label's own image is empty
    large,  ///< an image is larger than max_sprite_side on a side
    budget, ///< the own images' texels exceed max_texels
    points, ///< a label has no points, or more than max_points
    scale,  ///< the large images' scale is neither 0 (none) nor from 1 to max_large_scale, or 0 with a large image
};

[[nodiscard]] std::string_view describe(SpriteError error) noexcept;

/// The labels of one labels() call (a struct: the frame's FrameInputs and FrameState name it so).
struct LabelSet {
public:
    /// The largest image side, px (a long name at 21 dp at density 4 with its glow, at the band's top: well under it).
    static constexpr std::uint32_t max_sprite_side = 2048;
    /// The texels of the own images at most: 16 MB (plan-vector §3 budgets 8 MB of sprites at S1, 14 MB at S3).
    static constexpr std::uint32_t max_texels = 4U << 20U;
    /// The texels of all the images at most: 32 MB. The large images are kept by priority, highest first, while the
    /// set stays within it; a label whose large image would not fit is drawn grown from its own image.
    static constexpr std::uint32_t max_all_texels = 8U << 20U;
    /// The largest scale the large images may be drawn at.
    static constexpr float max_large_scale = 4.0F;
    /// A label's points at most.
    static constexpr std::size_t max_points = 1024;

    /// The set of `sprites` at `revision` (the camera state's labels revision), drawn no smaller than `least` of their
    /// own size (the readable floor, from Android's text size: placement's Frame::least), their large images drawn at
    /// `large_scale` times their own size (0: none); or why not.
    [[nodiscard]] static std::expected<std::shared_ptr<const LabelSet>, SpriteError>
    make(std::vector<LabelSprite> sprites, std::uint64_t revision, float least = 0.0F, float large_scale = 0.0F);

    /// The sprites; their images' pixels are in texels(), not in them.
    [[nodiscard]] std::span<const LabelSprite> sprites() const noexcept { return sprites_; }
    /// Each sprite's images' first texels in texels(): its own image's, then its large one's (none: 0).
    [[nodiscard]] std::span<const std::array<std::uint32_t, 2>> offsets() const noexcept { return offsets_; }
    /// Every image's pixels, one after another.
    [[nodiscard]] std::span<const std::uint32_t> texels() const noexcept { return texels_; }
    [[nodiscard]] std::uint64_t revision() const noexcept { return revision_; }
    [[nodiscard]] float least() const noexcept { return least_; }
    /// The scale the large images are drawn at, times a label's own size (0: none).
    [[nodiscard]] float large_scale() const noexcept { return large_scale_; }

private:
    LabelSet() = default;
    std::vector<LabelSprite> sprites_;
    std::vector<std::array<std::uint32_t, 2>> offsets_;
    std::vector<std::uint32_t> texels_;
    std::uint64_t revision_ = 0;
    float least_ = 0.0F;
    float large_scale_ = 0.0F;
};

/// Where an image of a placed label goes on the frame, device px: its top-left corner and its size, its anchor landing
/// on the stand's x and baseline; and its scale, device px a texel. At its own size from its own image (both scales 1)
/// on whole pixels, those rounded as Kotlin's roundToInt rounds them (LabelPainter draws its text there, so the two
/// match pixel for pixel); otherwise the image scaled about its anchor, where it falls.
struct SpriteAt {
    float x = 0.0F;
    float y = 0.0F;
    float width = 0.0F;
    float height = 0.0F;
    float scale = 1.0F;
};
/// Where `image`, drawn at `image_scale` times the label's own size, goes for a label drawn as `drawn`.
[[nodiscard]] SpriteAt sprite_at(const SpriteImage& image, const Drawn& drawn, float image_scale = 1.0F) noexcept;

/// What a placed label is drawn from and where: its large image while it is grown (Drawn::scale above 1) and has one,
/// so it stays sharp; else its own image.
struct ImageAt {
    std::uint32_t image = 0; ///< 0: its own image; 1: its large one (LabelSet::offsets' order)
    SpriteAt at;
};
[[nodiscard]] ImageAt image_at(const LabelSprite& sprite, float large_scale, const Drawn& drawn) noexcept;

/// The magic number that opens a block of label sprites ('LBL2', little-endian).
inline constexpr std::uint32_t label_block_magic = 0x324C'424CU;

/// The sprites of a block as the app writes it (MK/state/LabelBlock.kt; Native.labels), little-endian: the magic and
/// the count (u32 each) and the large images' scale (f32, 0 for none); per sprite its id and priority (i32), its
/// measures (f32: half its text's width plus the pad, the pad, its text's height, its baseline's offset), its own
/// image's width and height (u32) and anchor (i32 x, y), its points' count (u32), its points (f64 latitude, longitude
/// each), its own image's pixels (width x height u32), then its large image: width and height (u32), anchor (i32 x, y)
/// and pixels (0 x 0 and none for none). Or why it was refused; the scale is LabelSet::make's to judge.
struct LabelBlock {
    std::vector<LabelSprite> sprites;
    float large_scale = 0.0F;
};
[[nodiscard]] std::expected<LabelBlock, std::string> read_label_block(std::span<const std::byte> block);

} // namespace atlas::labels
