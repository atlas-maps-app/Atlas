// Text shaped as the oracle shapes it (plan-vector D13, K13). The oracle draws the renders' `<text>` with SvgCanvas,
// that is Android's text stack: Minikin lays the run out with HarfBuzz, taking each glyph's advance from Skia, whose
// FreeType scaler reads it from the variable font at the paint's weight with linear metrics (SvgCanvas's text paint has
// LINEAR_TEXT_FLAG), and adds the letter spacing half before and half after each cluster. The core runs the same
// HarfBuzz on the same font files with Minikin's settings and Skia's advances computed as FreeType computes them, so
// a glyph's id and its position along the baseline are Android's bit for bit (tests/text/android_shaping.txt, written
// by mapview's TextShapingTest from Robolectric's Android 14, holds them).
//
// What Minikin does here, and the core with it: the text split into words after each space and before each CJK
// ideograph (its layout cache's pieces), each shaped alone in the context of its neighbours' word breaks and placed
// after the ones before it; one HarfBuzz sub-font at scale ⌊256 · size⌋ (24.8 fixed point) and ppem ⌊size⌋, advances
// from the font's callback (Skia's, in 1/256 px, cut to an integer); a word split where its script changes (common and
// inherited characters join the run around them), the buffer in en-US (the oracle's default locale), `liga` and `clig`
// off when the spacing exceeds 0.03 em; then x accumulated in float: half the spacing before a run's first cluster,
// each glyph at x plus its offset, x plus its advance, the whole spacing between clusters and half after the last.
// SvgCanvas centres or ends a run (text-anchor) by Minikin's advance.
//
// One thing differs between Android releases: how HarfBuzz scales a font's units (GPOS values, kerning among them) to
// the run's fixed point. HarfBuzz used to take the floor (Robolectric's text stack runs HarfBuzz 2.6.4, the oracle's);
// 14.6 rounds. Atlas's one change to HarfBuzz (third_party/VERSIONS.md) makes that a font's flag, and the platform's
// rule, which the app measures with Android's own Paint (MV/TextRounding.kt), is the run's.
//
// Not reproduced: Minikin's font fallback (a character the bundled face lacks is drawn as its .notdef, where Android
// would take a system font: the shaped run reports it) and right-to-left runs (no render names one).
#pragma once

#include "text/Fonts.hpp"

#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::text {

/// How a run stands about its anchor (SVG's text-anchor).
enum class Anchor : std::uint8_t { start, middle, end };

/// How HarfBuzz scales a font's units to the run's 24.8 fixed point: the floor of HarfBuzz before it rounded (the
/// oracle's, Robolectric's HarfBuzz 2.6.4), or rounded (HarfBuzz 14.6, and Android releases with a newer HarfBuzz).
enum class Rounding : std::uint8_t { floor, round };

/// The platform's rule, as the app measured it (floor until it says otherwise: the oracle's). Thread-safe.
[[nodiscard]] Rounding platform_rounding() noexcept;
void set_platform_rounding(Rounding rounding) noexcept;

[[nodiscard]] std::string_view name_of(Rounding rounding) noexcept;

/// A run's font and spacing, as SvgCanvas sets up its paint for a `<text>`: the face at its weight, the font size and
/// the letter spacing in the text's units (as parsed from the SVG: floats).
struct ShapeStyle {
    FontSpec font;
    float size = 0.0F;
    float letter_spacing = 0.0F;
    Anchor anchor = Anchor::middle;
    Rounding rounding = Rounding::floor;

    friend constexpr bool operator==(const ShapeStyle&, const ShapeStyle&) = default;
};

/// A shaped run: its glyphs and their x positions along the baseline from the run's start (Minikin's), in the text's
/// units, and the run's advance.
struct ShapedRun {
    std::vector<std::uint32_t> glyphs;
    std::vector<float> x;
    float advance = 0.0F;
    /// How many characters the face has no glyph for (drawn as .notdef; Android would fall back to another font).
    std::uint32_t missing = 0;

    /// Where the run starts relative to its anchor: 0, half its advance back, or its whole advance back.
    [[nodiscard]] float start_offset(Anchor anchor) const noexcept;
    /// Glyph i's position relative to the anchor, as hwui adds it: the start offset plus Minikin's x, in float.
    [[nodiscard]] float anchored_x(std::size_t i, Anchor anchor) const noexcept;
};

/// Why a run could not be shaped.
enum class ShapeError : std::uint8_t {
    font,     ///< the style's face or weight is not available
    encoding, ///< the text is not UTF-8
    size,     ///< the size is not positive and finite, or too large for HarfBuzz's 24.8 fixed point
};

[[nodiscard]] std::string_view describe(ShapeError error) noexcept;

/// Shapes `utf8` in `style` as Android's text stack does. Allocates (HarfBuzz's buffer and sub-font): shape once per
/// string and style and keep the run.
[[nodiscard]] std::expected<ShapedRun, ShapeError> shape(const Fonts& fonts, const ShapeStyle& style,
                                                         std::string_view utf8);

/// Skia's advance of `glyph` at `size` px with linear metrics, as FreeType's scaler computes it from the font's
/// (varied) advance: the size in 26.6 fixed point, FreeType's 16.16 scale and linear advance, then a float.
[[nodiscard]] float skia_advance(const Instance& instance, std::uint32_t glyph, float size) noexcept;

/// One run as the platform shaped it: a row of tests/text/android_shaping.txt (mapview's TextShapingTest, Robolectric)
/// or of a device's glyph check (VK facts), `<style> <weight> <size> <spacing> | <text> | <glyph>:<x> ... | <advance>
/// <measureText>`, the size, the spacing, each x and the advances as floats' bits in hex.
struct PlatformRun {
    std::string style;
    int weight = 0;
    float size = 0.0F;
    float spacing = 0.0F;
    std::string text;
    std::vector<std::uint32_t> glyphs;
    std::vector<float> x;
    float advance = 0.0F;
    float measured = 0.0F;

    /// The face its style draws with: Gelasio for the `gelasio` styles, else Geist.
    [[nodiscard]] Face face() const noexcept;
    [[nodiscard]] ShapeStyle shape_style(Rounding rounding) const noexcept;
};

/// A row, or nothing when the line is not one.
[[nodiscard]] std::optional<PlatformRun> parse_platform_run(std::string_view line);

/// A run of the device's glyph check: its row's head (style, weight, size, spacing) and its text.
struct CheckRun {
    std::string_view head;
    std::string_view text;
};

/// The runs the device's glyph check shapes with Android's Paint: the rows of tests/text/android_shaping.txt.
[[nodiscard]] std::span<const CheckRun> check_runs() noexcept;

/// The device's glyph check (VK facts): each of `platform`'s rows (Android's shaping of check_runs(), a row a line)
/// shaped by the core under `rounding` and compared with it glyph by glyph, bit for bit. One line: `text.glyphs: <n>
/// runs, <m> glyphs equal Android's (rule <rule>)`, or `text.glyphs: FAIL <k> of <n> runs differ (rule <rule>), first
/// <text> glyph <i>`.
[[nodiscard]] std::string glyph_check(const Fonts& fonts, std::string_view platform, Rounding rounding);

/// Python's `str.upper()` (Java's `uppercase()`, Locale.ROOT) of UTF-8 text: full case mapping (ß → SS); invalid
/// UTF-8 bytes become U+FFFD.
[[nodiscard]] std::string upper(std::string_view utf8);

/// The UTF-8 text's code points; invalid bytes become U+FFFD, as HarfBuzz reads them. Nothing on overlong input.
[[nodiscard]] std::u32string code_points(std::string_view utf8);

} // namespace atlas::text
