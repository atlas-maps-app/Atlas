#include "text/Shaper.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <charconv>
#include <cmath>
#include <cstring>
#include <format>
#include <hb-ot.h>
#include <hb.h>
#include <iterator>
#include <limits>
#include <memory>
#include <optional>
#include <span>
#include <utility>
#include <vector>

namespace atlas::text {

namespace {

// ---------------------------------------------------------------- Python's str.upper()

struct UpperRun {
    char32_t start;
    std::uint32_t count;
    std::uint32_t stride;
    std::int32_t delta;
};

struct UpperSpecial {
    char32_t from;
    std::array<char32_t, 3> to;
};

#include "text/CheckRuns.inc"
#include "text/Uppercase.inc"

/// A float from its bits in hex, or nothing.
std::optional<float> float_bits(std::string_view hex) noexcept {
    std::uint32_t bits = 0;
    const auto [end, error] = std::from_chars(hex.data(), hex.data() + hex.size(), bits, 16);
    if (error != std::errc{} || end != hex.data() + hex.size()) return std::nullopt;
    return std::bit_cast<float>(bits);
}

/// The words of `text` split at single spaces.
std::vector<std::string_view> words(std::string_view text) {
    std::vector<std::string_view> out;
    while (!text.empty()) {
        const std::size_t space = text.find(' ');
        if (space != 0) out.push_back(text.substr(0, space));
        if (space == std::string_view::npos) break;
        text.remove_prefix(space + 1);
    }
    return out;
}

/// The code points `c` uppercases to: one, or a special case's two or three.
std::span<const char32_t> upper_of(char32_t c, char32_t& single) noexcept {
    const auto* const special = std::ranges::lower_bound(upper_special, c, {}, &UpperSpecial::from);
    if (special != upper_special.end() && special->from == c) {
        const auto& to = special->to;
        return {to.data(), to[2] != 0 ? 3U : 2U};
    }
    // The last run starting at or before c.
    const auto* const after = std::ranges::upper_bound(upper_runs, c, {}, &UpperRun::start);
    single = c;
    if (after != upper_runs.begin()) {
        const UpperRun& run = *std::prev(after);
        const char32_t offset = c - run.start;
        if (offset % run.stride == 0 && offset / run.stride < run.count) {
            single = static_cast<char32_t>(static_cast<std::int64_t>(c) + run.delta);
        }
    }
    return {&single, 1};
}

void append_utf8(std::string& out, char32_t c) {
    if (c < 0x80) {
        out.push_back(static_cast<char>(c));
    } else if (c < 0x800) {
        out.push_back(static_cast<char>(0xC0U | (c >> 6U)));
        out.push_back(static_cast<char>(0x80U | (c & 0x3FU)));
    } else if (c < 0x10000) {
        out.push_back(static_cast<char>(0xE0U | (c >> 12U)));
        out.push_back(static_cast<char>(0x80U | ((c >> 6U) & 0x3FU)));
        out.push_back(static_cast<char>(0x80U | (c & 0x3FU)));
    } else {
        out.push_back(static_cast<char>(0xF0U | (c >> 18U)));
        out.push_back(static_cast<char>(0x80U | ((c >> 12U) & 0x3FU)));
        out.push_back(static_cast<char>(0x80U | ((c >> 6U) & 0x3FU)));
        out.push_back(static_cast<char>(0x80U | (c & 0x3FU)));
    }
}

// ---------------------------------------------------------------- Minikin's runs and words

/// Minikin's isWordBreakAfter: the spaces.
[[nodiscard]] constexpr bool break_after(char32_t c) noexcept {
    return c == U' ' || (c >= 0x2000 && c <= 0x200A) || c == 0x3000;
}

/// Minikin's isWordBreakBefore: the spaces and the CJK ideographs.
[[nodiscard]] constexpr bool break_before(char32_t c) noexcept {
    return break_after(c) || (c >= 0x3400 && c <= 0x9FFF);
}

/// Minikin's getPrevWordBreakForCache: the word break before `offset` (< offset, or 0).
[[nodiscard]] std::size_t previous_break(std::u32string_view text, std::size_t offset) noexcept {
    if (offset == 0) return 0;
    offset = std::min(offset, text.size());
    if (break_before(text[offset - 1])) return offset - 1;
    for (std::size_t i = offset - 1; i > 0; --i) {
        if (break_before(text[i]) || break_after(text[i - 1])) return i;
    }
    return 0;
}

/// Minikin's getNextWordBreakForCache: the word break after `offset` (> offset, or the text's end).
[[nodiscard]] std::size_t next_break(std::u32string_view text, std::size_t offset) noexcept {
    if (offset >= text.size()) return text.size();
    if (break_after(text[offset])) return offset + 1;
    for (std::size_t i = offset + 1; i < text.size(); ++i) {
        if (break_before(text[i])) return i;
    }
    return text.size();
}

/// Minikin's getScriptRun: the end of the script run starting at `start` within [start, end), and its script.
[[nodiscard]] std::pair<std::size_t, hb_script_t> script_run(hb_unicode_funcs_t* unicode, std::u32string_view text,
                                                             std::size_t start, std::size_t end) noexcept {
    std::size_t at = start;
    hb_script_t current = hb_unicode_script(unicode, text[at++]);
    while (at < end) {
        const hb_script_t script = hb_unicode_script(unicode, text[at]);
        if (script != current) {
            if (current == HB_SCRIPT_INHERITED || current == HB_SCRIPT_COMMON) {
                current = script;
            } else if (script != HB_SCRIPT_INHERITED && script != HB_SCRIPT_COMMON) {
                break;
            }
        }
        ++at;
    }
    if (current == HB_SCRIPT_INHERITED) current = HB_SCRIPT_COMMON;
    return {at, current};
}

/// Minikin's isScriptOkForLetterspacing.
[[nodiscard]] constexpr bool spaced(hb_script_t script) noexcept {
    switch (script) {
    case HB_SCRIPT_ARABIC:
    case HB_SCRIPT_NKO:
    case HB_SCRIPT_PSALTER_PAHLAVI:
    case HB_SCRIPT_MANDAIC:
    case HB_SCRIPT_MONGOLIAN:
    case HB_SCRIPT_PHAGS_PA:
    case HB_SCRIPT_DEVANAGARI:
    case HB_SCRIPT_BENGALI:
    case HB_SCRIPT_GURMUKHI:
    case HB_SCRIPT_MODI:
    case HB_SCRIPT_SHARADA:
    case HB_SCRIPT_SYLOTI_NAGRI:
    case HB_SCRIPT_TIRHUTA:
    case HB_SCRIPT_OGHAM: return false;
    default: return true;
    }
}

// ---------------------------------------------------------------- Skia's advances as HarfBuzz's font functions

/// What a shaping font's callbacks read: the instance and the run's size.
struct AdvanceSource {
    const Instance* instance;
    float size;
};

/// Minikin's batch callback: HBFloatToFixed, Skia's advance times 256 cut to an integer.
void advances(hb_font_t* /*font*/, void* font_data, unsigned count, const hb_codepoint_t* first_glyph,
              unsigned glyph_stride, hb_position_t* first_advance, unsigned advance_stride, void* /*user*/) {
    const auto* source = static_cast<const AdvanceSource*>(font_data);
    const auto* glyph = reinterpret_cast<const std::byte*>(first_glyph);
    auto* advance = reinterpret_cast<std::byte*>(first_advance);
    for (unsigned i = 0; i < count; ++i) {
        hb_codepoint_t id = 0;
        std::memcpy(&id, glyph, sizeof id);
        const auto fixed =
            static_cast<hb_position_t>(std::scalbn(skia_advance(*source->instance, id, source->size), 8));
        std::memcpy(advance, &fixed, sizeof fixed);
        glyph += glyph_stride;
        advance += advance_stride;
    }
}

/// Minikin's single-glyph callback: 256 times Skia's advance, plus a half, cut to an integer.
hb_position_t advance(hb_font_t* /*font*/, void* font_data, hb_codepoint_t glyph, void* /*user*/) {
    const auto* source = static_cast<const AdvanceSource*>(font_data);
    // Minikin's own rounding, a cast of the value plus a half, kept for its results.
    const double half_up = static_cast<double>(256.0F * skia_advance(*source->instance, glyph, source->size)) + 0.5;
    return static_cast<hb_position_t>(half_up);
}

/// Minikin's origin callback: every glyph's horizontal origin at (0, 0).
hb_bool_t origin(hb_font_t* /*font*/, void* /*font_data*/, hb_codepoint_t /*glyph*/, hb_position_t* x, hb_position_t* y,
                 void* /*user*/) {
    *x = 0;
    *y = 0;
    return 1;
}

hb_font_funcs_t* skia_funcs() {
    static hb_font_funcs_t* const funcs = [] {
        hb_font_funcs_t* made = hb_font_funcs_create();
        hb_font_funcs_set_glyph_h_advance_func(made, advance, nullptr, nullptr);
        hb_font_funcs_set_glyph_h_advances_func(made, advances, nullptr, nullptr);
        hb_font_funcs_set_glyph_h_origin_func(made, origin, nullptr, nullptr);
        hb_font_funcs_make_immutable(made);
        return made;
    }();
    return funcs;
}

struct FontDeleter {
    void operator()(hb_font_t* f) const noexcept { hb_font_destroy(f); }
};
struct BufferDeleter {
    void operator()(hb_buffer_t* b) const noexcept { hb_buffer_destroy(b); }
};

/// The em-relative spacing as SvgCanvas sets it (Paint.letterSpacing = spacing / size, in float).
[[nodiscard]] float spacing_em(const ShapeStyle& style) noexcept {
    return style.size > 0.0F ? style.letter_spacing / style.size : 0.0F;
}

} // namespace

// ---------------------------------------------------------------- public

namespace {
std::atomic<Rounding>& platform_rule() noexcept {
    static std::atomic<Rounding> rule{Rounding::floor};
    return rule;
}
} // namespace

Rounding platform_rounding() noexcept { return platform_rule().load(std::memory_order_relaxed); }

void set_platform_rounding(Rounding rounding) noexcept { platform_rule().store(rounding, std::memory_order_relaxed); }

std::string_view name_of(Rounding rounding) noexcept { return rounding == Rounding::floor ? "floor" : "round"; }

std::string_view describe(ShapeError error) noexcept {
    switch (error) {
    case ShapeError::font: return "the face or weight is not available";
    case ShapeError::encoding: return "the text is not UTF-8";
    case ShapeError::size: return "the size is not a usable positive number";
    }
    return "unknown shaping error";
}

float ShapedRun::start_offset(Anchor anchor) const noexcept {
    switch (anchor) {
    case Anchor::start: return 0.0F;
    case Anchor::middle: return -advance * 0.5F;
    case Anchor::end: return -advance;
    }
    return 0.0F;
}

float ShapedRun::anchored_x(std::size_t i, Anchor anchor) const noexcept { return start_offset(anchor) + x[i]; }

float skia_advance(const Instance& instance, std::uint32_t glyph, float size) noexcept {
    // FreeType's FT_Set_Char_Size from Skia's SkScalarToFDot6: the size in 26.6, cut.
    const auto size26 = static_cast<std::int64_t>(size * 64.0F);
    const auto upem = static_cast<std::int64_t>(instance.units_per_em());
    // FT_Request_Metrics: x_scale = FT_DivFix(size26, upem), rounded.
    const std::int64_t x_scale = ((size26 << 16) + (upem >> 1)) / upem;
    // FT_Load_Glyph: linearHoriAdvance = FT_MulDiv(advance, x_scale, 64), rounded, in 16.16 pixels.
    const std::int64_t units = std::max<std::int64_t>(instance.advance_units(glyph), 0);
    const std::int64_t linear = (units * x_scale + 32) / 64;
    // Skia: SkFixedToScalar.
    return static_cast<float>(linear) * 1.52587890625e-5F;
}

std::expected<ShapedRun, ShapeError> shape(const Fonts& fonts, const ShapeStyle& style, std::string_view utf8) {
    const auto instance = fonts.instance(style.font);
    if (!instance) return std::unexpected(ShapeError::font);
    // HarfBuzz's scale is 24.8 fixed point in an int: a size of 2^22 px overflows it long before.
    if (!(style.size > 0.0F) || !std::isfinite(style.size) || style.size > 65536.0F) {
        return std::unexpected(ShapeError::size);
    }
    const std::u32string text = code_points(utf8);
    ShapedRun out;
    if (text.empty()) return out;

    // One sub-font per run, as Minikin makes one per layout piece: Skia's advances, the size as 24.8 fixed point.
    AdvanceSource source{.instance = *instance, .size = style.size};
    const std::unique_ptr<hb_font_t, FontDeleter> font(hb_font_create_sub_font((*instance)->font()));
    hb_font_set_funcs(font.get(), skia_funcs(), &source, nullptr);
    hb_font_set_em_mult_floor(font.get(), style.rounding == Rounding::floor ? 1 : 0);
    const auto size = static_cast<double>(style.size);
    hb_font_set_ppem(font.get(), static_cast<unsigned>(size), static_cast<unsigned>(size));
    const auto scale = static_cast<int>(std::scalbn(static_cast<float>(size), 8));
    hb_font_set_scale(font.get(), scale, scale);

    // cleanAndAddDefaultFontFeatures: no optional ligatures under letter spacing (CSS Text 3).
    const float em = spacing_em(style);
    std::array<hb_feature_t, 2> features{{{.tag = HB_TAG('l', 'i', 'g', 'a'), .value = 0, .start = 0, .end = ~0U},
                                          {.tag = HB_TAG('c', 'l', 'i', 'g'), .value = 0, .start = 0, .end = ~0U}}};
    const unsigned feature_count = std::fabs(em) > 0.03F ? 2U : 0U;

    hb_unicode_funcs_t* unicode = hb_unicode_funcs_get_default();
    const hb_language_t language = hb_language_from_string("en-US", -1);
    const std::unique_ptr<hb_buffer_t, BufferDeleter> buffer(hb_buffer_create());
    const std::u32string_view all(text);
    float total = 0.0F; // Layout::mAdvance
    for (std::size_t piece = 0; piece < text.size();) {
        // LayoutSplitter: a word, shaped in the context from the word break before it to the one after it.
        const std::size_t piece_end = next_break(all, piece);
        const std::size_t context = previous_break(all, piece);
        const std::u32string_view context_text = all.substr(context, piece_end - context);
        // LayoutPiece: x from the piece's start, the spacing per script run.
        float x = 0.0F;
        for (std::size_t run = piece; run < piece_end;) {
            const auto [run_end, script] = script_run(unicode, all, run, piece_end);
            double letter = 0.0;
            double half_left = 0.0;
            double half_right = 0.0;
            if (em != 0.0F && spaced(script)) {
                letter = static_cast<double>(em) * size * 1.0;
                half_left = letter * 0.5;
                half_right = letter - half_left;
            }
            hb_buffer_clear_contents(buffer.get());
            hb_buffer_set_script(buffer.get(), script);
            hb_buffer_set_direction(buffer.get(), HB_DIRECTION_LTR);
            hb_buffer_set_language(buffer.get(), language);
            hb_buffer_add_utf32(buffer.get(), reinterpret_cast<const std::uint32_t*>(context_text.data()),
                                static_cast<int>(context_text.size()), static_cast<unsigned>(run - context),
                                static_cast<int>(run_end - run));
            hb_shape(font.get(), buffer.get(), feature_count == 0 ? nullptr : features.data(), feature_count);
            unsigned count = 0;
            const hb_glyph_info_t* info = hb_buffer_get_glyph_infos(buffer.get(), &count);
            const hb_glyph_position_t* positions = hb_buffer_get_glyph_positions(buffer.get(), nullptr);
            // Minikin's x is a float, its spacing a double: each sum in double, kept as a float.
            if (count != 0) x = static_cast<float>(static_cast<double>(x) + half_left);
            for (unsigned i = 0; i < count; ++i) {
                if (i > 0 && info[i - 1].cluster != info[i].cluster) {
                    x = static_cast<float>(static_cast<double>(x) + letter);
                }
                const float x_offset = std::scalbn(static_cast<float>(positions[i].x_offset), -8);
                out.glyphs.push_back(info[i].codepoint);
                // appendLayout: the layout's advance so far plus the glyph's place in its piece.
                out.x.push_back(total + (x + x_offset));
                if (info[i].codepoint == 0) ++out.missing;
                x += std::scalbn(static_cast<float>(positions[i].x_advance), -8);
            }
            if (count != 0) x = static_cast<float>(static_cast<double>(x) + half_right);
            run = run_end;
        }
        total += x;
        piece = piece_end;
    }
    out.advance = total;
    return out;
}

Face PlatformRun::face() const noexcept { return style.starts_with("gelasio") ? Face::serif : Face::sans; }

ShapeStyle PlatformRun::shape_style(Rounding rounding) const noexcept {
    return {.font = {.face = face(), .weight = static_cast<std::uint16_t>(weight)},
            .size = size,
            .letter_spacing = spacing,
            .anchor = Anchor::middle,
            .rounding = rounding};
}

std::optional<PlatformRun> parse_platform_run(std::string_view line) {
    if (line.empty() || line.front() == '#') return std::nullopt;
    const std::size_t a = line.find(" | ");
    const std::size_t c = line.rfind(" | ");
    if (a == std::string_view::npos || c == a) return std::nullopt;
    const std::size_t b = line.rfind(" | ", c - 1);
    if (b == std::string_view::npos || b < a) return std::nullopt;
    PlatformRun run;
    const auto head = words(line.substr(0, a));
    if (head.size() != 4) return std::nullopt;
    run.style = std::string(head[0]);
    const auto [weight_end, weight_error] =
        std::from_chars(head[1].data(), head[1].data() + head[1].size(), run.weight);
    const auto size = float_bits(head[2]);
    const auto spacing = float_bits(head[3]);
    if (weight_error != std::errc{} || !size || !spacing) return std::nullopt;
    run.size = *size;
    run.spacing = *spacing;
    // The text lies between the first separator and the glyphs; a run without glyphs has none between them.
    run.text = b == a ? std::string() : std::string(line.substr(a + 3, b - a - 3));
    if (b != a && c >= b + 3) {
        for (const std::string_view token : words(line.substr(b + 3, c - b - 3))) {
            const std::size_t colon = token.find(':');
            if (colon == std::string_view::npos) return std::nullopt;
            std::uint32_t glyph = 0;
            const std::string_view digits = token.substr(0, colon);
            const auto [glyph_end, glyph_error] = std::from_chars(digits.begin(), digits.end(), glyph);
            const auto x = float_bits(token.substr(colon + 1));
            if (glyph_error != std::errc{} || glyph_end != digits.end() || !x) return std::nullopt;
            run.glyphs.push_back(glyph);
            run.x.push_back(*x);
        }
    }
    const auto tail = words(line.substr(c + 3));
    if (tail.size() != 2) return std::nullopt;
    const auto advance = float_bits(tail[0]);
    const auto measured = float_bits(tail[1]);
    if (!advance || !measured) return std::nullopt;
    run.advance = *advance;
    run.measured = *measured;
    return run;
}

std::span<const CheckRun> check_runs() noexcept { return check_run_table; }

std::string glyph_check(const Fonts& fonts, std::string_view platform, Rounding rounding) {
    std::size_t runs = 0;
    std::size_t glyphs = 0;
    std::size_t differ = 0;
    std::string first;
    while (!platform.empty()) {
        const std::size_t end = platform.find('\n');
        const std::string_view line = platform.substr(0, end);
        platform.remove_prefix(end == std::string_view::npos ? platform.size() : end + 1);
        const auto run = parse_platform_run(line);
        if (!run) continue;
        ++runs;
        const auto core = shape(fonts, run->shape_style(rounding), run->text);
        std::size_t at = 0;
        bool same = core.has_value() && core->glyphs.size() == run->glyphs.size() &&
                    std::bit_cast<std::uint32_t>(core->advance) == std::bit_cast<std::uint32_t>(run->advance);
        if (core.has_value()) {
            for (; at < core->glyphs.size() && at < run->glyphs.size(); ++at) {
                if (core->glyphs[at] != run->glyphs[at] ||
                    std::bit_cast<std::uint32_t>(core->x[at]) != std::bit_cast<std::uint32_t>(run->x[at])) {
                    same = false;
                    break;
                }
            }
        }
        glyphs += run->glyphs.size();
        if (!same) {
            if (differ == 0) first = std::format("{} glyph {}", run->text, at);
            ++differ;
        }
    }
    if (differ == 0) {
        return std::format("text.glyphs: {} runs, {} glyphs equal Android's (rule {})", runs, glyphs,
                           name_of(rounding));
    }
    return std::format("text.glyphs: FAIL {} of {} runs differ (rule {}), first {}", differ, runs, name_of(rounding),
                       first);
}

std::string upper(std::string_view utf8) {
    std::string out;
    out.reserve(utf8.size());
    for (const char32_t c : code_points(utf8)) {
        char32_t single = 0;
        for (const char32_t u : upper_of(c, single)) append_utf8(out, u);
    }
    return out;
}

} // namespace atlas::text
