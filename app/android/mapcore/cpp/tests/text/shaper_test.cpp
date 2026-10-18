// The shaper held to Android's text stack (plan-vector D13, K13): every row of android_shaping.txt (each canal name of
// the Westerkerk's and the canal ring's scenes, Schiphol's runway designators, kerning pairs and every capital and
// digit, in Geist and Gelasio at the renders' weights, sizes and letter spacings), its glyphs and their x positions bit
// for bit under the oracle's rule; the rounding rule's probe pair; the fonts and SvgFonts' choice of them; Python's
// upper().
#include "tests/base/require.hpp"
#include "tests/text/text_support.hpp"
#include "text/Fonts.hpp"
#include "text/Shaper.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cstdint>
#include <format>
#include <limits>
#include <string>

namespace {

using namespace atlas;
using atlas::test::bundled_fonts;

/// How many of `run`'s glyphs the core places where Android does (ids and x bit for bit), and whether its advance is
/// Android's.
struct Agreement {
    std::size_t glyphs = 0;
    bool same_count = false;
    bool advance = false;
};

Agreement agree(const text::PlatformRun& android, const text::ShapedRun& core) {
    Agreement a;
    a.same_count = core.glyphs.size() == android.glyphs.size();
    for (std::size_t i = 0; i < core.glyphs.size() && i < android.glyphs.size(); ++i) {
        if (core.glyphs[i] == android.glyphs[i] &&
            std::bit_cast<std::uint32_t>(core.x[i]) == std::bit_cast<std::uint32_t>(android.x[i])) {
            ++a.glyphs;
        }
    }
    a.advance = std::bit_cast<std::uint32_t>(core.advance) == std::bit_cast<std::uint32_t>(android.advance);
    return a;
}

} // namespace

TEST_CASE("the bundled fonts load with their weight axes, and resolve as SvgFonts picks them") {
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr, "the fonts in " ATLAS_FONTS_DIR " cannot be read");
    for (const auto face : {text::Face::sans, text::Face::serif, text::Face::serif_italic}) CHECK(fonts->loaded(face));
    const auto geist = fonts->instance({.face = text::Face::sans, .weight = 700});
    ATLAS_REQUIRE(geist.has_value());
    CHECK((*geist)->units_per_em() == 1000);
    CHECK((*geist)->file().weight_axis() == std::array<float, 3>{100.0F, 400.0F, 900.0F});
    const auto gelasio = fonts->instance({.face = text::Face::serif, .weight = 400});
    ATLAS_REQUIRE(gelasio.has_value());
    CHECK((*gelasio)->units_per_em() == 2048);
    CHECK((*gelasio)->file().weight_axis() == std::array<float, 3>{400.0F, 400.0F, 700.0F});
    // The same instance on every call; a weight off the axis refused.
    CHECK(*fonts->instance({.face = text::Face::sans, .weight = 700}) == *geist);
    CHECK(fonts->instance({.face = text::Face::serif, .weight = 800}).error() == text::FontError::weight);
    // A face is a file of glyphs with a weight axis.
    CHECK(text::FontFile::load({}).error() == text::FontError::empty);

    using text::Face;
    using text::FontSpec;
    CHECK(text::resolve("Geist, Helvetica, sans-serif", 700, false) == FontSpec{Face::sans, 700});
    CHECK(text::resolve("Geist, Helvetica, sans-serif", 950, false) == FontSpec{Face::sans, 900});
    CHECK(text::resolve("Georgia, serif", 800, false) == FontSpec{Face::serif, 700});
    CHECK(text::resolve("'Georgia', serif", 300, true) == FontSpec{Face::serif_italic, 400});
    CHECK(text::resolve(" gelasio ", 500, false) == FontSpec{Face::serif, 500});
    CHECK(!text::resolve("Helvetica, Geist", 400, false).has_value()); // the system's face comes first
    CHECK(!text::resolve("Comic Sans", 400, false).has_value());
}

TEST_CASE("every glyph of Android's ground text lands where Android puts it, bit for bit, under the oracle's floor") {
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    const auto runs = test::android_runs();
    ATLAS_REQUIRE(runs.size() >= 180, runs.size() << " rows in android_shaping.txt");
    std::size_t glyphs = 0;
    std::size_t exact = 0;
    std::size_t advances = 0;
    std::size_t rounded_off = 0; // glyphs the round rule would move
    std::size_t canal = 0;
    for (const auto& android : runs) {
        const auto core = text::shape(*fonts, android.shape_style(text::Rounding::floor), android.text);
        ATLAS_REQUIRE(core.has_value(), android.text << ": " << text::describe(core.error()));
        const Agreement a = agree(android, *core);
        CHECK_MESSAGE(a.same_count, android.text);
        CHECK_MESSAGE(a.glyphs == android.glyphs.size(),
                      android.style << " " << android.text << ": " << a.glyphs << " of " << android.glyphs.size());
        CHECK_MESSAGE(a.advance, android.text);
        CHECK(core->missing == 0);
        glyphs += android.glyphs.size();
        exact += a.glyphs;
        advances += a.advance ? 1U : 0U;
        canal += android.style == "canal" ? 1U : 0U;
        const auto round = text::shape(*fonts, android.shape_style(text::Rounding::round), android.text);
        ATLAS_REQUIRE(round.has_value());
        rounded_off += android.glyphs.size() - agree(android, *round).glyphs;
    }
    MESSAGE(exact << " of " << glyphs << " glyphs and " << advances << " of " << runs.size() << " advances equal "
                  << "Android's (" << canal << " canal-style runs); HarfBuzz 14.6's rounding would move " << rounded_off
                  << " glyphs");
    CHECK(exact == glyphs);
    CHECK(advances == runs.size());
    CHECK(rounded_off > 0); // the rule matters on these runs
}

TEST_CASE("the device's glyph check shapes the fixture's runs and holds the core to the platform's rows") {
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    // check_runs() (CheckRuns.inc, from text/tables.py) is the fixture's styles and texts, in its order.
    const auto runs = test::android_runs();
    const auto check = text::check_runs();
    ATLAS_REQUIRE(check.size() == runs.size(), check.size() << " check runs, " << runs.size() << " rows");
    for (std::size_t i = 0; i < runs.size(); ++i) {
        const auto& r = runs[i];
        const auto head =
            r.style + " " + std::to_string(r.weight) + " " +
            std::format("{:08x} {:08x}", std::bit_cast<std::uint32_t>(r.size), std::bit_cast<std::uint32_t>(r.spacing));
        CHECK_MESSAGE(check[i].head == head, i);
        CHECK_MESSAGE(check[i].text == r.text, i);
    }
    // Robolectric's rows under the floor: every glyph equal; under the rounding: the check fails, naming the first.
    const std::string android = test::android_shaping();
    const std::string floor = text::glyph_check(*fonts, android, text::Rounding::floor);
    CHECK(floor == "text.glyphs: 186 runs, 1836 glyphs equal Android's (rule floor)");
    const std::string round = text::glyph_check(*fonts, android, text::Rounding::round);
    MESSAGE(round);
    CHECK(round.starts_with("text.glyphs: FAIL "));
    CHECK(round.contains("of 186 runs differ (rule round), first BLOEMGRACHT glyph 3"));
    // Lines that are not rows are passed over; a row's damage is refused.
    CHECK(!text::parse_platform_run("# a comment").has_value());
    CHECK(!text::parse_platform_run("canal 700 404ccccd | X | 1:0 | 0 0").has_value());
    CHECK(!text::parse_platform_run("canal 700 404ccccd 3e99999a | X | 1:zz | 3f800000 3f800000").has_value());
    const auto empty = text::parse_platform_run("canal 700 404ccccd 3e99999a | | 3f800000 3f800000");
    ATLAS_REQUIRE(empty.has_value());
    CHECK(empty->glyphs.empty());
}

TEST_CASE("the rounding probe: O and E kerned at 3.2 px in Geist 700, five or six 256ths of a pixel") {
    // MV/TextRounding.kt measures this pair with Android's own Paint and tells the core which rule its platform takes:
    // GPOS's O-E value is 12 units plus a variation delta of -4.8 at wght 700, at the scale of 819/1000: the floor
    // gives 9 - 4 = 5, the rounding 10 - 4 = 6.
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    auto kern = [&](text::Rounding rounding) {
        const text::ShapeStyle style{
            .font = {.face = text::Face::sans, .weight = 700}, .size = 3.2F, .rounding = rounding};
        const auto pair = text::shape(*fonts, style, "OE");
        const auto alone = text::shape(*fonts, style, "O");
        ATLAS_REQUIRE_OR_RETURN(0, pair.has_value() && alone.has_value() && pair->x.size() == 2);
        return static_cast<int>((pair->x[1] - pair->x[0] - alone->advance) * 256.0F);
    };
    CHECK(kern(text::Rounding::floor) == 5);
    CHECK(kern(text::Rounding::round) == 6);
    // The platform's rule starts at the oracle's, and the app may change it.
    CHECK(text::platform_rounding() == text::Rounding::floor);
    text::set_platform_rounding(text::Rounding::round);
    CHECK(text::platform_rounding() == text::Rounding::round);
    text::set_platform_rounding(text::Rounding::floor);
    CHECK(text::name_of(text::Rounding::round) == "round");
}

TEST_CASE("a run without glyphs, a missing face and bad sizes") {
    const text::Fonts* fonts = bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    const text::ShapeStyle style{
        .font = {.face = text::Face::sans, .weight = 700}, .size = 3.2F, .letter_spacing = 0.3F};
    const auto empty = text::shape(*fonts, style, "");
    ATLAS_REQUIRE(empty.has_value());
    CHECK(empty->glyphs.empty());
    CHECK(empty->advance == 0.0F);
    text::ShapeStyle bad = style;
    bad.size = 0.0F;
    CHECK(text::shape(*fonts, bad, "A").error() == text::ShapeError::size);
    bad.size = std::numeric_limits<float>::infinity();
    CHECK(text::shape(*fonts, bad, "A").error() == text::ShapeError::size);
    const text::Fonts none;
    CHECK(text::shape(none, style, "A").error() == text::ShapeError::font);
    // A character Geist lacks is its .notdef, counted.
    const auto cjk = text::shape(*fonts, style, "A水");
    ATLAS_REQUIRE(cjk.has_value());
    CHECK(cjk->missing == 1);
    // Anchors: the start, half the advance back, the whole advance back.
    const auto run = text::shape(*fonts, style, "SINGEL");
    ATLAS_REQUIRE(run.has_value());
    CHECK(run->start_offset(text::Anchor::start) == 0.0F);
    CHECK(run->start_offset(text::Anchor::middle) == -run->advance * 0.5F);
    CHECK(run->anchored_x(0, text::Anchor::end) == -run->advance + run->x[0]);
}

TEST_CASE("upper() is Python's str.upper(): full case mapping, and invalid bytes as U+FFFD") {
    CHECK(text::upper("Prinsengracht") == "PRINSENGRACHT");
    CHECK(text::upper("straße") == "STRASSE");
    CHECK(text::upper("ijssel ĳ") == "IJSSEL Ĳ");
    CHECK(text::upper("ŉ") == "ʼN");
    CHECK(text::upper("ᾳ") == "ΑΙ");
    CHECK(text::upper("ﬀ") == "FF");
    CHECK(text::upper("ǆ") == "Ǆ");
    CHECK(text::upper("µ") == "Μ");
    CHECK(text::upper("ı") == "I");
    CHECK(text::upper("Écluse 18r") == "ÉCLUSE 18R");
    CHECK(text::upper("a\xFF"
                      "b") == "A\xEF\xBF\xBD"
                              "B");
    CHECK(text::code_points("\xE2\x82") == std::u32string{0xFFFD, 0xFFFD});             // a truncated sequence
    CHECK(text::code_points("\xC0\x80") == std::u32string{0xFFFD, 0xFFFD});             // an overlong one
    CHECK(text::code_points("\xED\xA0\x80") == std::u32string{0xFFFD, 0xFFFD, 0xFFFD}); // a surrogate
    CHECK(text::code_points("\xF0\x9F\x98\x80") == std::u32string{0x1F600});
}
