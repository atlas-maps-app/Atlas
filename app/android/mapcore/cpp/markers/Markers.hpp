// The app's markers (ENGINE.md step 6; MapCore.markers): what the map's markers look like and where they stand, drawn
// by the core in the map's own frame (passes/Markers.cpp) so they move with the map in the same frame instead of being
// drawn by the canvas over it a frame apart.
//
// The app draws each marker's look as a sprite with the device's own Skia exactly as MarkerPainter draws the marker as
// itself (its disc, tile or pill, shadow, symbol, ring, badge, value chip and a pin's name, premultiplied RGBA8 at the
// screen's density; mapview's MarkerSprites), with the marker's place on a whole pixel of it: once a look, which the
// markers that look alike share, and a named pin's twice (its name right of the pin and left of it, which MarkerPainter
// picks by where the pin stands on the view). A cluster's look is the core's own: its bubble (drawn as MarkerPainter
// draws it, shaders/overlay/marker.slang) with its count, the count's text a sprite the app draws for each count the
// markers can make, in each ink a cluster's count is written in. The core places the markers each frame as MarkerLayout
// places them (markers/Layout.hpp), from what the set carries of each marker beside its look: its place, priority,
// style, whether it is the chosen one, its count and the widths of its texts.
//
// A markers() call makes a MarkerSet: immutable, shared by the frames that draw it, its images' texels one after
// another in one buffer.
#pragma once

#include "labels/Sprites.hpp"

#include <algorithm>
#include <cstdint>
#include <expected>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::markers {

/// How a marker is drawn: MarkerStyle, in its order.
enum class Style : std::uint8_t { pin = 0, dot = 1, brand = 2, cluster = 3, chip = 4 };

/// One image: width x height px, premultiplied RGBA8 (R in the low byte), rows top to bottom, and where the marker's
/// place lies in it, whole px.
using Image = labels::SpriteImage;

/// A marker without an image of its own: a cluster, which the core draws as its bubble and count.
inline constexpr std::uint32_t no_look = 0xFFFF'FFFFU;

/// One marker of the app's list, in the list's order.
struct Marker {
    double latitude = 0.0;
    double longitude = 0.0;
    std::int32_t priority = 0;
    Style style = Style::pin;
    bool selected = false;
    /// Whether it carries a value (Marker.value): a pin's box then reaches down over its value chip.
    bool value = false;
    /// Marker.count, 1 where it has none: what it adds to the marker it folds into, and a cluster's own count.
    std::uint32_t count = 1;
    /// The width of its chip's text, px (MarkerPainter.chipTextWidth: a chip's, or a pin's value): 0 for none.
    float chip_text = 0.0F;
    /// The width of a pin's name, px (MarkerPainter's name paint's measureText); negative: no name.
    float name = -1.0F;
    /// Its colour (ARGB, opaque, as MarkerPainter takes it): a cluster's bubble; and the ink of a cluster's count
    /// (MarkerPainter.onColour of it).
    std::uint32_t colour = 0xFF14'161BU;
    std::uint32_t ink = 0xFFFF'FFFFU;
    /// Its look as itself, and a named pin's with its name left of the pin (the same otherwise): indices into the set's
    /// looks; no_look for a cluster.
    std::uint32_t look = no_look;
    std::uint32_t look_left = no_look;
};

/// A cluster's count as the app draws it: its text (MarkerPainter.countLabel's, which count_key decides) in an ink,
/// its anchor the bubble's centre.
struct CountImage {
    std::uint32_t ink = 0;
    std::uint32_t key = 0;
    Image image;
};

/// What MarkerPainter.countLabel's text depends on: the count itself below 1,000; its hundreds below 10,000 ("1.2k");
/// its thousands, at most 999, above ("15k", "999k").
[[nodiscard]] constexpr std::uint32_t count_key(std::uint64_t n) noexcept {
    if (n < 1'000U) return static_cast<std::uint32_t>(n);
    if (n < 10'000U) return static_cast<std::uint32_t>(n / 100U * 100U);
    return static_cast<std::uint32_t>(std::min<std::uint64_t>(n / 1'000U, 999U) * 1'000U);
}

/// Why a set of markers was refused.
enum class MarkerError : std::uint8_t {
    size,    ///< an image's pixels are not its width x height, or an image is empty
    large,   ///< an image is larger than max_side on a side
    budget,  ///< the images' texels exceed max_texels
    look,    ///< a marker's look is none of the set's (or a cluster has one, or another style none)
    markers, ///< more markers than max_markers, or a marker's place or count is not one
    density, ///< the density is not a finite number from 0.5 to 8
};

[[nodiscard]] std::string_view describe(MarkerError error) noexcept;

/// The markers of one markers() call.
class MarkerSet {
public:
    /// The largest image side, px (a chosen pin's name and value at density 4, with its shadow: well under it).
    static constexpr std::uint32_t max_side = 2048;
    /// The images' texels at most: 32 MB.
    static constexpr std::uint32_t max_texels = 8U << 20U;
    /// The markers at most (MarkerLayout's sort key keeps a marker's index in 24 bits).
    static constexpr std::size_t max_markers = std::size_t{1} << 16U;

    /// The set of `markers` with their `looks` and the clusters' `counts`, drawn at `density` (the app's, which its
    /// sizes are in), at `revision` (the caller's count of hand-overs); or why not.
    [[nodiscard]] static std::expected<std::shared_ptr<const MarkerSet>, MarkerError>
    make(std::vector<Marker> markers, std::vector<Image> looks, std::vector<CountImage> counts, float density,
         std::uint64_t revision);

    [[nodiscard]] std::span<const Marker> markers() const noexcept { return markers_; }
    /// The looks; their pixels are in texels(), each from its offset in look_offsets().
    [[nodiscard]] std::span<const Image> looks() const noexcept { return looks_; }
    [[nodiscard]] std::span<const std::uint32_t> look_offsets() const noexcept { return look_offsets_; }
    /// The counts by ink, then key; their pixels are in texels(), each from its offset in count_offsets().
    [[nodiscard]] std::span<const CountImage> counts() const noexcept { return counts_; }
    [[nodiscard]] std::span<const std::uint32_t> count_offsets() const noexcept { return count_offsets_; }
    /// The index of count `n`'s image in `ink` in counts(), or none.
    [[nodiscard]] std::optional<std::uint32_t> count_of(std::uint32_t ink, std::uint64_t n) const noexcept;
    /// Every image's pixels, one after another.
    [[nodiscard]] std::span<const std::uint32_t> texels() const noexcept { return texels_; }
    [[nodiscard]] float density() const noexcept { return density_; }
    [[nodiscard]] std::uint64_t revision() const noexcept { return revision_; }

private:
    MarkerSet() = default;
    std::vector<Marker> markers_;
    std::vector<Image> looks_;
    std::vector<std::uint32_t> look_offsets_;
    std::vector<CountImage> counts_;
    std::vector<std::uint32_t> count_offsets_;
    std::vector<std::uint32_t> texels_;
    float density_ = 1.0F;
    std::uint64_t revision_ = 0;
};

/// The magic number that opens a block of markers ('MRK1', little-endian).
inline constexpr std::uint32_t marker_block_magic = 0x314B'524DU;

/// The markers of a block as the app writes it (MK/state/MarkerBlock.kt; Native.markers), little-endian: the magic,
/// the density (f32) and the counts of looks, markers and count images (u32 each); each look: its width and height
/// (u32), anchor (i32 x, y) and pixels (width x height u32); each marker: its latitude and longitude (f64), priority
/// (i32), style, chosen and value flags (u8 each) and a zero byte, its count (u32), its chip's text's and its name's
/// widths (f32), its colour and its count's ink (u32 ARGB), its looks (u32, no_look for none); each count image: its
/// ink and key (u32), then an image as a look's. Or why it was refused; the set's own checks are MarkerSet::make's.
struct MarkerBlock {
    float density = 0.0F;
    std::vector<Image> looks;
    std::vector<Marker> markers;
    std::vector<CountImage> counts;
};
[[nodiscard]] std::expected<MarkerBlock, std::string> read_marker_block(std::span<const std::byte> block);

} // namespace atlas::markers
