#include "text/Fonts.hpp"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <hb-ot.h>
#include <hb.h>
#include <iterator>
#include <limits>

namespace atlas::text {

namespace {

constexpr hb_tag_t wght_tag = HB_TAG('w', 'g', 'h', 't');

/// One CSS family name, trimmed of white space and quotes, lowercased (ASCII: the families the renders name).
std::string family_key(std::string_view raw) {
    auto is_trimmed = [](char c) { return c == ' ' || c == '\t' || c == '"' || c == '\''; };
    while (!raw.empty() && is_trimmed(raw.front())) raw.remove_prefix(1);
    while (!raw.empty() && is_trimmed(raw.back())) raw.remove_suffix(1);
    std::string key(raw);
    std::ranges::transform(key, key.begin(),
                           [](char c) { return static_cast<char>(std::tolower(static_cast<unsigned char>(c))); });
    return key;
}

} // namespace

std::string_view file_of(Face face) noexcept {
    switch (face) {
    case Face::sans: return "Geist-wght.ttf";
    case Face::serif: return "Gelasio-wght.ttf";
    case Face::serif_italic: return "Gelasio-Italic-wght.ttf";
    }
    return {};
}

std::optional<FontSpec> resolve(std::string_view family, int weight, bool italic) noexcept {
    // SvgFonts.face: the first family of the list that it knows decides; anything else is the system's serif.
    std::size_t start = 0;
    while (start <= family.size()) {
        const std::size_t comma = family.find(',', start);
        const std::string_view part =
            family.substr(start, comma == std::string_view::npos ? std::string_view::npos : comma - start);
        const std::string key = family_key(part);
        if (key == "georgia" || key == "gelasio") {
            return FontSpec{.face = italic ? Face::serif_italic : Face::serif,
                            .weight = static_cast<std::uint16_t>(std::clamp(weight, 400, 700))};
        }
        if (key == "geist") {
            return FontSpec{.face = Face::sans, .weight = static_cast<std::uint16_t>(std::clamp(weight, 100, 900))};
        }
        if (key == "helvetica" || key == "arial" || key == "sans-serif" || key == "serif" || key == "times" ||
            key == "times new roman") {
            return std::nullopt;
        }
        if (comma == std::string_view::npos) break;
        start = comma + 1;
    }
    return std::nullopt;
}

std::string_view describe(FontError error) noexcept {
    switch (error) {
    case FontError::empty: return "the font holds no glyphs";
    case FontError::not_variable: return "the font has no wght axis";
    case FontError::unknown_face: return "the face is not loaded";
    case FontError::weight: return "the weight is outside the face's wght axis";
    }
    return "unknown font error";
}

std::expected<std::unique_ptr<FontFile>, FontError> FontFile::load(std::span<const std::byte> bytes) {
    if (bytes.empty() || bytes.size() > std::numeric_limits<unsigned>::max()) return std::unexpected(FontError::empty);
    std::unique_ptr<FontFile> file(new FontFile()); // NOLINT(cppcoreguidelines-owning-memory): private constructor
    file->bytes_.assign(bytes.begin(), bytes.end());
    // The blob views the file's own copy, which outlives it.
    file->blob_ = hb_blob_create(reinterpret_cast<const char*>(file->bytes_.data()),
                                 static_cast<unsigned>(file->bytes_.size()), HB_MEMORY_MODE_READONLY, nullptr, nullptr);
    file->face_ = hb_face_create(file->blob_, 0);
    hb_face_make_immutable(file->face_);
    file->upem_ = hb_face_get_upem(file->face_);
    file->glyphs_ = hb_face_get_glyph_count(file->face_);
    if (file->glyphs_ == 0 || file->upem_ == 0) return std::unexpected(FontError::empty);
    hb_ot_var_axis_info_t axis{};
    if (hb_ot_var_find_axis_info(file->face_, wght_tag, &axis) == 0) return std::unexpected(FontError::not_variable);
    file->axis_ = {axis.min_value, axis.default_value, axis.max_value};
    return file;
}

FontFile::~FontFile() {
    hb_face_destroy(face_);
    hb_blob_destroy(blob_);
}

Instance::Instance(const FontFile& file, FontSpec spec)
    : file_(&file), spec_(spec), font_(hb_font_create(file.face())) {
    const auto upem = static_cast<int>(file.units_per_em());
    hb_font_set_scale(font_, upem, upem);
    const hb_variation_t weight{.tag = wght_tag, .value = static_cast<float>(spec.weight)};
    hb_font_set_variations(font_, &weight, 1);
    hb_font_make_immutable(font_);
}

Instance::~Instance() { hb_font_destroy(font_); }

std::int32_t Instance::advance_units(std::uint32_t glyph) const noexcept {
    return hb_font_get_glyph_h_advance(font_, glyph);
}

std::uint32_t Instance::glyph_of(char32_t code_point) const noexcept {
    hb_codepoint_t glyph = 0;
    return hb_font_get_nominal_glyph(font_, code_point, &glyph) != 0 ? glyph : 0;
}

std::expected<void, FontError> Fonts::load(Face face, std::span<const std::byte> bytes) {
    auto file = FontFile::load(bytes);
    if (!file) return std::unexpected(file.error());
    const std::scoped_lock lock(mutex_);
    auto& slot = files_.at(static_cast<std::size_t>(face));
    if (!slot) slot = std::move(*file);
    return {};
}

std::expected<void, FontError> Fonts::load_folder(const std::string& folder) {
    for (std::size_t i = 0; i < face_count; ++i) {
        const auto face = static_cast<Face>(i);
        std::ifstream in(folder + "/" + std::string(file_of(face)), std::ios::binary);
        if (!in) return std::unexpected(FontError::empty);
        const std::vector<char> chars{std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
        const auto loaded = load(face, std::as_bytes(std::span(chars)));
        if (!loaded) return loaded;
    }
    return {};
}

bool Fonts::loaded(Face face) const noexcept {
    const std::scoped_lock lock(mutex_);
    return files_.at(static_cast<std::size_t>(face)) != nullptr;
}

std::expected<const Instance*, FontError> Fonts::instance(FontSpec spec) const {
    const std::scoped_lock lock(mutex_);
    const auto& file = files_.at(static_cast<std::size_t>(spec.face));
    if (!file) return std::unexpected(FontError::unknown_face);
    const auto axis = file->weight_axis();
    const auto weight = static_cast<float>(spec.weight);
    if (weight < axis[0] || weight > axis[2]) return std::unexpected(FontError::weight);
    for (const auto& made : instances_) {
        if (made->spec() == spec) return made.get();
    }
    return instances_.emplace_back(std::make_unique<Instance>(*file, spec)).get();
}

} // namespace atlas::text
