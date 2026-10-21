// Text laid on the ground and on walls (plan-vector D13, K13): the renders' ground_text (living.py:309), a run's exact
// perspective at its anchor and the affine map around it, printed as the port prints its `<text>` (the oracle draws
// the printed values: at rest the core draws them too, so "the matrices after snapping" are the SVG's own); runway
// designators (regions.py:628, runway_ends); and a run laid out as Slug glyph instances under its matrix.
//
// The camera is the frame's (view::Camera, K7's port of the renders' Camera): its proj takes a point in the region's
// metres (x east, y up, z south) to CSS px, and nothing nearer than the near plane.
#pragma once

#include "text/Shaper.hpp"
#include "text/SlugBands.hpp"
#include "view/Camera.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::text {

/// A point or a direction in the region's metres (x east, y up, z south), and a point on the frame in CSS px.
using World = view::Vec3;
using Screen = view::Vec2;

/// The `Geist, Helvetica, sans-serif` family every ground text of the renders names.
inline constexpr std::string_view geist_family = "Geist, Helvetica, sans-serif";

/// One text run as the renders draw it: the port's `<text transform="matrix(a b c d e f)" font-family font-size
/// font-weight letter-spacing text-anchor fill opacity>`.
struct TextRun {
    std::string text;
    std::array<double, 6> matrix{}; ///< a b c d e f: text space to CSS px, unsnapped
    std::string_view family = geist_family;
    double size = 0.0;
    int weight = 400;
    double spacing = 0.0;
    Anchor anchor = Anchor::middle;
    std::uint32_t fill = 0; ///< 0xRRGGBB
    double opacity = 1.0;

    /// The element as the port prints it (PyFmt's `%.3f`, `%.1f`, `%.2f`, `%d`, `%s`).
    [[nodiscard]] std::string svg() const;
    /// The matrix as the oracle reads it back from the SVG: each printed value parsed as a float.
    [[nodiscard]] std::array<float, 6> snapped_matrix() const noexcept;
    /// The size, spacing and opacity as printed (`%.2f`) and parsed back as floats.
    [[nodiscard]] float printed_size() const noexcept;
    [[nodiscard]] float printed_spacing() const noexcept;
    [[nodiscard]] float printed_opacity() const noexcept;
    /// The style SvgCanvas shapes the printed run with.
    [[nodiscard]] std::optional<ShapeStyle> shape_style(Rounding rounding) const noexcept;
    /// Turned by 180° about its anchor (the canal names' readability flip): u and v reversed.
    [[nodiscard]] TextRun turned() const;
};

/// A value as Python's `'%.<digits>f' %` prints it (correctly rounded; a negative value that rounds to zero keeps its
/// sign, "-0.000").
[[nodiscard]] std::string printed(double value, int digits);
/// The float SvgParser reads from a printed value.
[[nodiscard]] float printed_float(double value, int digits) noexcept;

/// living.py:309 ground_text: the run of `text` painted on the plane through `p0` spanned by `u` (the baseline) and
/// `v` (down the letters): the anchor's projection and the projections of p0 + u and p0 + v give the affine map, or
/// nothing when one of the three is behind the camera or the map nearly degenerates (|det| < 0.02).
[[nodiscard]] std::optional<TextRun> ground_text(const view::Camera& camera, std::string_view text, const World& p0,
                                                 const World& u, const World& v, double size, std::uint32_t fill,
                                                 double opacity = 1.0, int weight = 800, double spacing = 0.18,
                                                 Anchor anchor = Anchor::middle);

/// A runway's line and its designators' source (regions.py's S['aero'] of kind runway: its points in metres, x and z;
/// its ref, "18C/36C").
struct Runway {
    std::vector<std::array<double, 2>> points;
    std::string ref;
};

/// regions.py:628 runway_ends' designations: at each end, the last of the ref's names whose number times ten lies
/// within 30° of the heading from that end, 60 m in, 18 m tall in Geist 800 with spacing 0.05, in the marking colour
/// `mark` (0xRRGGBB: the style's runways' marks by day or at night). The names of a ref without '/' are none. A name's
/// number is its leading decimal digits as the port reads them: the Basic Multilingual Plane's (Character.isDigit on
/// each UTF-16 unit; Python's \d would take the other planes' too, which no ref holds).
[[nodiscard]] std::vector<TextRun> runway_designators(const view::Camera& camera, const Runway& runway,
                                                      std::uint32_t mark);

/// A shaped run laid out as Slug glyph instances: each glyph's em-to-device-pixel map under the run's (snapped)
/// matrix at `density` device px a CSS px, its place in the textures, its premultiplied colour.
struct GlyphInstance {
    std::array<float, 3> row0{};
    std::array<float, 3> row1{};
    const slug::Glyph* glyph = nullptr;
    std::array<float, 4> colour{};
};

/// `run`'s glyphs, shaped as `shaped`, appended to `out` as instances: `places[i]` is shaped glyph i's place in the
/// textures (nullptr or not drawn for a glyph without contours, which is left out). `matrix` is the run's own or the
/// snapped one; glyph i stands at its anchored x on the baseline, the em's y up the letters.
void lay_out(const TextRun& run, const std::array<double, 6>& matrix, const ShapedRun& shaped,
             std::span<const slug::Glyph* const> places, double density, std::vector<GlyphInstance>& out);

} // namespace atlas::text
