// Slug's curve and band textures (plan-vector D13, K13; E. Lengyel, "GPU-Centered Font Rendering Directly from Glyph
// Outlines", JCGT 6(2), 2017, the Slug algorithm, whose patent is dedicated to the public domain): what the glyph
// shader (shaders/text/slug.slang) reads to compute a glyph's coverage at any pixel from its outline, exactly under any
// scale, rotation and shear, with no atlas of rasterised glyphs.
//
// The curve texture (RGBA32F, rows of `texture_width` texels) holds each contour's points two to a texel, in em units
// (the font's units over its units per em), y up: texel k of a contour is (point 2k, point 2k + 1), so curve k reads
// its first two control points from texel k and its third from texel k + 1's first two channels. A contour stays in
// one row.
//
// The band texture (RG16UI, the same width) holds per glyph its horizontal bands' headers, then its vertical bands',
// all in one row, then the bands' curve lists, which may wrap into the next rows. A header is (curve count, offset of
// its list from the glyph's first texel); a list entry is a curve's texel in the curve texture. The glyph's box is cut
// into h horizontal and v vertical bands of equal size; a curve is listed in every band its control points' range
// reaches (with a margin of 1/64 of a band, so a sample's band index computed in the GPU's own rounding finds it);
// horizontal bands list their curves by decreasing largest x, vertical ones by decreasing largest y, so a sample's
// ray stops at the first curve wholly behind it.
//
// coverage() is the shader's arithmetic on the CPU, reading the same textures: the reference the GPU is held to
// (tests/text/slug_vk_test.cpp) and the fuzz target's oracle (fuzz/fuzz_outline_bands.cpp).
#pragma once

#include "text/Fonts.hpp"
#include "text/Outlines.hpp"

#include <cstdint>
#include <expected>
#include <map>
#include <mutex>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::text::slug {

/// The textures' width, texels, and its log2 (the shader's kLogBandTextureWidth).
inline constexpr std::uint32_t log_texture_width = 12;
inline constexpr std::uint32_t texture_width = 1U << log_texture_width;

/// The most bands a glyph is cut into, each way.
inline constexpr std::uint32_t max_bands = 16;

/// A curve texel: two points, em units (RGBA32F).
struct CurveTexel {
    float x0 = 0.0F;
    float y0 = 0.0F;
    float x1 = 0.0F;
    float y1 = 0.0F;
};

/// A band texel (RG16UI): a header's count and offset, or a list entry's curve texel (x, y).
struct BandTexel {
    std::uint16_t a = 0;
    std::uint16_t b = 0;
};

/// What a glyph's instances carry to the shader: where its bands are, how many, and the em box's map onto them.
struct Glyph {
    std::uint32_t glyph = 0;
    /// The box around every control point, em units; empty for a glyph without contours (nothing is drawn).
    OutlineBox box;
    /// The glyph's first texel in the band texture.
    std::uint32_t band_x = 0;
    std::uint32_t band_y = 0;
    std::uint32_t hbands = 0;
    std::uint32_t vbands = 0;
    /// A sample's band indices: (int(x · scale_x + offset_x), int(y · scale_y + offset_y)), clamped to the counts.
    float scale_x = 0.0F;
    float scale_y = 0.0F;
    float offset_x = 0.0F;
    float offset_y = 0.0F;
    std::uint32_t curves = 0;

    [[nodiscard]] bool drawn() const noexcept { return hbands != 0; }
};

/// Why a glyph could not join the textures.
enum class AtlasError : std::uint8_t {
    full,      ///< the textures would pass 65,536 rows (16-bit texel coordinates)
    too_large, ///< a contour longer than a row, or a glyph's band block longer than 16 bits can offset
    not_finite ///< a point is not finite
};

[[nodiscard]] std::string_view describe(AtlasError error) noexcept;

/// The textures of a set of glyphs, built glyph by glyph. Rows are only appended to, so the texels a GPU copy needs
/// after adding glyphs are the rows from the last copy's end.
class Atlas {
public:
    /// Adds `outline` (font units) of a font with `units_per_em`; the glyph's place in the textures.
    [[nodiscard]] std::expected<Glyph, AtlasError> add(const GlyphOutline& outline, unsigned units_per_em);

    [[nodiscard]] std::span<const CurveTexel> curves() const noexcept { return curves_; }
    [[nodiscard]] std::span<const BandTexel> bands() const noexcept { return bands_; }
    /// Rows in use (the last one partly).
    [[nodiscard]] std::uint32_t curve_rows() const noexcept;
    [[nodiscard]] std::uint32_t band_rows() const noexcept;
    /// The texels written so far: every texel before these indices may have been, none after them (a copy to the GPU
    /// takes the texels from its last end to these).
    [[nodiscard]] std::size_t curve_texels() const noexcept {
        return (static_cast<std::size_t>(curve_y_) << log_texture_width) + curve_x_;
    }
    [[nodiscard]] std::size_t band_texels() const noexcept {
        return (static_cast<std::size_t>(band_y_) << log_texture_width) + band_x_;
    }
    /// Bytes the two textures take at their rows (RGBA32F and RG16UI).
    [[nodiscard]] std::size_t bytes() const noexcept;

    [[nodiscard]] const CurveTexel& curve_texel(std::uint32_t x, std::uint32_t y) const noexcept {
        return curves_[(static_cast<std::size_t>(y) << log_texture_width) + x];
    }
    [[nodiscard]] const BandTexel& band_texel(std::uint32_t x, std::uint32_t y) const noexcept {
        return bands_[(static_cast<std::size_t>(y) << log_texture_width) + x];
    }

private:
    /// Room for `count` texels starting at the cursor, in one row: the cursor moves to the next row if they do not fit.
    [[nodiscard]] static bool reserve_in_row(std::uint32_t& x, std::uint32_t& y, std::uint32_t count) noexcept;
    void grow_curves(std::uint32_t rows);
    void grow_bands(std::uint32_t rows);

    std::vector<CurveTexel> curves_;
    std::vector<BandTexel> bands_;
    std::uint32_t curve_x_ = 0;
    std::uint32_t curve_y_ = 0;
    std::uint32_t band_x_ = 0;
    std::uint32_t band_y_ = 0;
};

/// The glyphs the frame's text draws, shared by its passes (the ground's text, then the house numbers and shop signs)
/// and set 1's provider of their data (passes/GroundText.hpp's TextGlobals, which copies what was added since its last
/// copy to the GPU before the frame draws): each glyph's outline added on first use, by face, weight and glyph id, up
/// to max_rows rows of each texture (the provider's buffers). Locked, so frames on two render threads may share it.
class GlyphAtlas {
public:
    /// The rows of each texture at most: 32,768 texels each, some 400 glyphs (640 KB of buffers in all).
    static constexpr std::uint32_t max_rows = 8;

    /// Glyph `id` of `instance`'s place, added on first use; null when it cannot be (the data are full, or the
    /// outline is refused). The place stays put once made.
    [[nodiscard]] const Glyph* glyph(const Instance& instance, std::uint32_t id);
    /// Calls `read` with the atlas under the lock (the provider's copy).
    template <class F>
    void read(F&& read) const {
        const std::lock_guard lock(mutex_);
        std::forward<F>(read)(atlas_);
    }
    /// How many glyphs it holds, and whether it refused one for want of room.
    [[nodiscard]] std::size_t size() const;
    [[nodiscard]] bool full() const;

private:
    mutable std::mutex mutex_;
    Atlas atlas_;
    std::map<std::uint64_t, Glyph> glyphs_;
    bool full_ = false;
};

/// The process's glyphs (every frame's).
[[nodiscard]] GlyphAtlas& shared_glyphs() noexcept;

/// How many bands a glyph of `curves` curves is cut into each way: about one per two curves, 1 to max_bands.
[[nodiscard]] std::uint32_t band_count(std::size_t curves) noexcept;

/// The texel a band list's offset reaches from a glyph's first texel, wrapping into the next rows (the shader's
/// CalcBandLoc).
struct TexelLocation {
    std::uint32_t x = 0;
    std::uint32_t y = 0;
};
[[nodiscard]] constexpr TexelLocation band_location(std::uint32_t glyph_x, std::uint32_t glyph_y,
                                                    std::uint32_t offset) noexcept {
    const std::uint32_t x = glyph_x + offset;
    return {.x = x & (texture_width - 1), .y = glyph_y + (x >> log_texture_width)};
}

/// Slug's root code: which of a quadratic's two roots (bit 0: the first, bit 8: the second) cross the sample's ray,
/// from the signs of the three control points' coordinates across it (the shader's CalcRootCode).
[[nodiscard]] std::uint32_t root_code(float c1, float c2, float c3) noexcept;

/// The coverage of `glyph` at `sample` (em units), a pixel being `pixels_per_em` along x and y (1 / fwidth of the
/// sample), by Slug's method as the shader computes it: the horizontal and the vertical ray's coverage, each crossing
/// counted by how far it lies from the sample within half a pixel, combined by how near a crossing is; nonzero fill.
[[nodiscard]] float coverage(const Atlas& atlas, const Glyph& glyph, float sample_x, float sample_y,
                             float pixels_per_em_x, float pixels_per_em_y) noexcept;

/// A quadratic curve, em units.
struct Curve {
    float x1 = 0.0F;
    float y1 = 0.0F;
    float x2 = 0.0F;
    float y2 = 0.0F;
    float x3 = 0.0F;
    float y3 = 0.0F;
};

/// An outline's curves in em units, divided as the atlas divides them.
[[nodiscard]] std::vector<Curve> em_curves(const GlyphOutline& outline, unsigned units_per_em);

/// Slug's coverage over every one of `curves`, without bands and without stopping early: what coverage() equals up to
/// the order its sums are taken in (the fuzz target's and the tests' check of the bands).
[[nodiscard]] float coverage_unbanded(std::span<const Curve> curves, float sample_x, float sample_y,
                                      float pixels_per_em_x, float pixels_per_em_y) noexcept;

} // namespace atlas::text::slug
