// The engine's fonts (plan-vector D13, K13): the bundled variable fonts the renders name, loaded into HarfBuzz once,
// and each face at each weight the core draws it at, as the oracle's SvgFonts picks them. The renders name two CSS
// families: `Geist, Helvetica, sans-serif` (Geist, the canal names, runway designators and shop signs) and `Georgia,
// serif` (Gelasio, metric-compatible with Georgia: house numbers); both are variable fonts with a weight axis, and
// Gelasio has an italic file. They ship once, in mapview's assets (svgfonts/), which the APK carries.
//
// A face is a font file's bytes (owned) under HarfBuzz's face; an instance is a face at one weight: its variation
// coordinates and a HarfBuzz font at the face's own units, from which shaping fonts are made per run and outlines are
// drawn. Instances are made on first use and never change, so any thread may use one at once.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

struct hb_blob_t;
struct hb_face_t;
struct hb_font_t;

namespace atlas::text {

/// The engine's typefaces.
enum class Face : std::uint8_t {
    sans = 0,         ///< Geist, `Geist, Helvetica, sans-serif`
    serif = 1,        ///< Gelasio, `Georgia, serif`
    serif_italic = 2, ///< Gelasio Italic
};
inline constexpr std::size_t face_count = 3;

/// The folder of the bundled fonts among the APK's assets (mapview's SvgFonts.DIR).
inline constexpr std::string_view asset_folder = "svgfonts";

/// A face's font file, as the assets name it.
[[nodiscard]] std::string_view file_of(Face face) noexcept;

/// A face at a weight on its `wght` axis.
struct FontSpec {
    Face face = Face::sans;
    std::uint16_t weight = 400;

    friend constexpr bool operator==(const FontSpec&, const FontSpec&) = default;
};

/// What SvgFonts draws a CSS font-family list at `weight` with: the first family it knows, Gelasio's weights clamped
/// to 400-700 and Geist's to 100-900, italic only for Gelasio. Nothing for the system families (Helvetica, Arial,
/// sans-serif, serif, Times): the core carries no system font, and the renders always name a bundled one first.
[[nodiscard]] std::optional<FontSpec> resolve(std::string_view family, int weight, bool italic) noexcept;

/// Why a font could not be loaded.
enum class FontError : std::uint8_t {
    empty,        ///< no bytes, or no glyphs in them
    not_variable, ///< the file has no `wght` axis
    unknown_face, ///< the face was never loaded
    weight,       ///< the weight is outside the face's axis
};

[[nodiscard]] std::string_view describe(FontError error) noexcept;

/// One font file in HarfBuzz.
class FontFile {
public:
    /// The file of `bytes` (copied), or why not.
    [[nodiscard]] static std::expected<std::unique_ptr<FontFile>, FontError> load(std::span<const std::byte> bytes);

    FontFile(const FontFile&) = delete;
    FontFile& operator=(const FontFile&) = delete;
    FontFile(FontFile&&) = delete;
    FontFile& operator=(FontFile&&) = delete;
    ~FontFile();

    [[nodiscard]] hb_face_t* face() const noexcept { return face_; }
    [[nodiscard]] unsigned units_per_em() const noexcept { return upem_; }
    [[nodiscard]] unsigned glyph_count() const noexcept { return glyphs_; }
    /// The `wght` axis: minimum, default, maximum.
    [[nodiscard]] std::array<float, 3> weight_axis() const noexcept { return axis_; }

private:
    FontFile() = default;
    std::vector<std::byte> bytes_;
    hb_blob_t* blob_ = nullptr;
    hb_face_t* face_ = nullptr;
    unsigned upem_ = 0;
    unsigned glyphs_ = 0;
    std::array<float, 3> axis_{};
};

/// A face at one weight: a HarfBuzz font at the face's units (scale = units per em) with its variations set, the
/// parent of every shaping font of that weight and the font outlines are drawn from.
class Instance {
public:
    Instance(const FontFile& file, FontSpec spec);
    Instance(const Instance&) = delete;
    Instance& operator=(const Instance&) = delete;
    Instance(Instance&&) = delete;
    Instance& operator=(Instance&&) = delete;
    ~Instance();

    [[nodiscard]] FontSpec spec() const noexcept { return spec_; }
    [[nodiscard]] const FontFile& file() const noexcept { return *file_; }
    [[nodiscard]] hb_font_t* font() const noexcept { return font_; }
    [[nodiscard]] unsigned units_per_em() const noexcept { return file_->units_per_em(); }

    /// A glyph's advance at this weight in the font's units, its variation (HVAR) applied.
    [[nodiscard]] std::int32_t advance_units(std::uint32_t glyph) const noexcept;
    /// The glyph of a code point, or 0 (the font's .notdef).
    [[nodiscard]] std::uint32_t glyph_of(char32_t code_point) const noexcept;

private:
    const FontFile* file_;
    FontSpec spec_;
    hb_font_t* font_ = nullptr;
};

/// The engine's fonts: each face's file once, and instances by face and weight, made on first use. Thread-safe.
class Fonts {
public:
    Fonts() = default;
    Fonts(const Fonts&) = delete;
    Fonts& operator=(const Fonts&) = delete;
    Fonts(Fonts&&) = delete;
    Fonts& operator=(Fonts&&) = delete;
    ~Fonts() = default;

    /// Loads `bytes` as `face`'s file (copied), replacing nothing: a face loads once.
    [[nodiscard]] std::expected<void, FontError> load(Face face, std::span<const std::byte> bytes);

    /// Loads every face from the files in `folder` (a host's copy of the assets' svgfonts/).
    [[nodiscard]] std::expected<void, FontError> load_folder(const std::string& folder);

    [[nodiscard]] bool loaded(Face face) const noexcept;

    /// `spec`'s instance, made on first use; nothing when its face is not loaded or the weight is off its axis.
    [[nodiscard]] std::expected<const Instance*, FontError> instance(FontSpec spec) const;

private:
    mutable std::mutex mutex_;
    std::array<std::unique_ptr<FontFile>, face_count> files_;
    mutable std::vector<std::unique_ptr<Instance>> instances_;
};

/// The fonts the frame's text draws with (passes/GroundText.cpp): loaded once, by the app from the APK's svgfonts
/// (Native.textFonts) or by a host's tests (load_folder); a face not loaded yet draws nothing.
[[nodiscard]] Fonts& shared_fonts() noexcept;

} // namespace atlas::text
