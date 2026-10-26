// render/real.py's opening hours in the core (style/OpeningHours.hpp) held to the Python itself:
// - hand cases for each rule of what real.py reads (no rule, 24/7, hours and their ends, day selectors and their
//   ranges past Su, several hours, rules anywhere in the text, off and closed, the last rule winning, hours past
//   midnight, dated parts, PH, Python's white space, digits and word characters, the date), every answer real.py's;
// - opening_hours.json, which tests/style/opening_hours.py writes from real.py's own open_at: every distinct
//   opening_hours of the bundled packs and the hand cases' texts, on each weekday at the renders' dates (30 September,
//   1 and 2 October 2026) at the minutes either side of every hour the texts name and of the renders' own;
// - with ATLAS_TILES_DIR, every opening_hours in amsterdam.atlas and grachten.atlas (the things' tag and the
//   buildings' places) is a case of the fixture. Their list goes to opening_hours_strings.json in ATLAS_TEST_OUT, else
//   the build folder's scratch, for the generator.
#include "base/arena.hpp"
#include "style/OpeningHours.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Container.hpp"
#include "tiles/Json.hpp"
#include "tiles/Payload.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iterator>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

namespace tl = atlas::tiles;
using atlas::style::Date;
using atlas::style::HourMinute;
using atlas::style::open_at;
using atlas::style::Weekday;
using atlas::style::weekday_of;
using enum atlas::style::Weekday;

[[nodiscard]] constexpr Date on(int year, int month, int day) { return {.year = year, .month = month, .day = day}; }

/// The renders' Wednesday (amsterdam.py's THEMES).
constexpr Date renders = on(2026, 9, 30);

/// open_at as the Python's answer: "open" (True), "closed" (False) or "none" (None, and where the Python raises).
[[nodiscard]] std::string at(std::string_view oh, Weekday day, int hour, int minute, Date date = renders) {
    const auto open = open_at(oh, day, {.hour = hour, .minute = minute}, date);
    if (!open) return "none";
    return *open ? "open" : "closed";
}

/// The fixture's letter for an answer: 1 True, 0 False, - None.
[[nodiscard]] char letter(std::optional<bool> open) {
    if (!open) return '-';
    return *open ? '1' : '0';
}

/// tests/style/opening_hours.json's text.
[[nodiscard]] std::string fixture_text() {
    std::ifstream in(std::string(ATLAS_TESTS_DIR) + "/style/opening_hours.json", std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

/// [hour, minute] pairs of the fixture.
[[nodiscard]] std::vector<HourMinute> times_of(const tl::Value& list) {
    std::vector<HourMinute> out;
    out.reserve(list.size());
    for (std::size_t i = 0; i < list.size(); ++i) {
        out.push_back({.hour = static_cast<int>(list.child(i).child(0).integer().value_or(-1)),
                       .minute = static_cast<int>(list.child(i).child(1).integer().value_or(-1))});
    }
    return out;
}

/// [year, month, day] triples of the fixture.
[[nodiscard]] std::vector<Date> dates_of(const tl::Value& list) {
    std::vector<Date> out;
    out.reserve(list.size());
    for (std::size_t i = 0; i < list.size(); ++i) {
        const tl::Value d = list.child(i);
        out.push_back(on(static_cast<int>(d.child(0).integer().value_or(0)),
                         static_cast<int>(d.child(1).integer().value_or(0)),
                         static_cast<int>(d.child(2).integer().value_or(0))));
    }
    return out;
}

/// The value of `name` in an object of the fixture (null where it has none).
[[nodiscard]] tl::Value field(const tl::Value& object, std::string_view name) {
    return object.find(name).value_or(tl::Value());
}

/// Every opening_hours of the pack at `path` into `out`: the things' tag and each building's (and part's, and their
/// variants') places; false (the test case failed) where the pack cannot be read.
bool pack_hours(const std::string& path, std::set<std::string>& out) {
    auto pack = atlas::test::open_pack(path);
    ATLAS_REQUIRE_OR_RETURN(false, pack.has_value(), path << ": " << tl::describe(pack.error()));
    constexpr std::uint8_t thing_hours = tl::slot_of(tl::thing::columns, "tag:opening_hours");
    constexpr std::uint8_t variant_hours = tl::slot_of(tl::variant::columns, "place_hours");
    constexpr std::array<std::pair<tl::LayerId, std::uint8_t>, 3> places = {{
        {tl::LayerId::building, tl::building::place_hours},
        {tl::LayerId::part, tl::building::place_hours},
        {tl::LayerId::variant, variant_hours},
    }};
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "opening hours test");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    for (const tl::ChunkRef& chunk : pack->chunks().chunks()) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE_OR_RETURN(false, tile.has_value(), path << ": " << tl::describe(tile.error()));
        if (const tl::Layer* things = tile->layer(tl::LayerId::thing)) {
            for (std::size_t i = 0; i < things->count; ++i) {
                if (const auto oh = things->string(thing_hours, i)) out.emplace(*oh);
            }
        }
        for (const auto& [id, slot] : places) {
            const tl::Layer* layer = tile->layer(id);
            if (layer == nullptr) continue;
            for (std::size_t i = 0; i < layer->count; ++i) {
                const auto list = layer->string_list(slot, i);
                if (!list) continue;
                for (const std::uint32_t s : *list) out.emplace(layer->strings[s]);
            }
        }
    }
    return true;
}

/// A JSON string of UTF-8 text (the packs' strings are UTF-8, their reader checks).
[[nodiscard]] std::string json_string(std::string_view text) {
    constexpr std::string_view hex = "0123456789abcdef";
    std::string out = "\"";
    for (const char c : text) {
        const auto b = static_cast<unsigned char>(c);
        if (c == '"' || c == '\\') {
            out += '\\';
            out += c;
        } else if (b < 0x20U) {
            out += "\\u00";
            out += hex[b >> 4U];
            out += hex[b & 0xFU];
        } else {
            out += c;
        }
    }
    return out + "\"";
}

} // namespace

TEST_CASE("opening hours: a text holding no rule real.py reads is not understood, and 24/7 is open") {
    CHECK(at("", we, 12, 0) == "none");
    CHECK(at("   ", we, 12, 0) == "none");
    CHECK(at("by appointment", we, 12, 0) == "none");
    CHECK(at("Mo-Fr 9-18", we, 12, 0) == "none");
    CHECK(at("Mo-Fr 109:00-118:00", we, 12, 0) == "none");
    CHECK(at("mo-fr", we, 12, 0) == "none");
    CHECK(at("24/7", we, 3, 0) == "open");
    CHECK(at(" 24/7\n", su, 23, 59) == "open");
    // not 24/7 alone: the PH part leaves " off", a rule for every day
    CHECK(at("24/7; PH off", we, 12, 0) == "closed");
    // 24/7 is answered before the date is read
    CHECK(at("24/7 ", mo, 0, 0, on(2026, 2, 30)) == "open");
}

TEST_CASE("opening hours: hours hold from their first minute to before their last, on the days the selector names") {
    CHECK(at("Mo-Fr 09:00-18:00", we, 8, 59) == "closed");
    CHECK(at("Mo-Fr 09:00-18:00", we, 9, 0) == "open");
    CHECK(at("Mo-Fr 09:00-18:00", we, 17, 59) == "open");
    CHECK(at("Mo-Fr 09:00-18:00", we, 18, 0) == "closed");
    CHECK(at("Mo-Fr 09:00-18:00", sa, 12, 0) == "closed");
    CHECK(at("Mo-Fr 9:00-18:00", we, 9, 0) == "open");
    CHECK(at("Mo-Fr 10:00 - 18:00", we, 12, 0) == "open");
    CHECK(at("Mo-Fr10:00-18:00", we, 12, 0) == "open");
    CHECK(at("Mo-Fr 10:00-18:000", we, 12, 0) == "open");
    // no selector: every day (a lowercase one is none)
    CHECK(at("10:00-18:00", su, 12, 0) == "open");
    CHECK(at("mo-fr 10:00-18:00", su, 12, 0) == "open");
    // three digits are no hour: the selector is lost, and the rule found inside is 23:00-18:00 every day
    CHECK(at("Mo-Fr 123:00-18:00", we, 22, 0) == "closed");
    CHECK(at("Mo-Fr 123:00-18:00", we, 23, 30) == "open");
}

TEST_CASE("opening hours: day ranges run past Su, lists take commas, and a selector no hours follow is lost") {
    CHECK(at("Fr-Mo 10:00-12:00", su, 11, 0) == "open");
    CHECK(at("Fr-Mo 10:00-12:00", mo, 11, 0) == "open");
    CHECK(at("Fr-Mo 10:00-12:00", tu, 11, 0) == "closed");
    CHECK(at("Mo-Mo 10:00-12:00", mo, 11, 0) == "open");
    CHECK(at("Mo-Mo 10:00-12:00", tu, 11, 0) == "closed");
    CHECK(at("Sa,Su 10:00-12:00", su, 11, 0) == "open");
    CHECK(at("Sa,Su 10:00-12:00", fr, 11, 0) == "closed");
    CHECK(at("Mo-We , Fr 10:00-12:00", fr, 11, 0) == "open");
    CHECK(at("Mo-We , Fr 10:00-12:00", th, 11, 0) == "closed");
    CHECK(at("Mo-Fr,10:00-12:00", sa, 11, 0) == "open");
    CHECK(at("Mo, Xx 10:00-12:00", sa, 11, 0) == "open");
    CHECK(at("Mo-Xx 10:00-12:00", sa, 11, 0) == "open");
    CHECK(at("Monday 10:00-12:00", sa, 11, 0) == "open");
}

TEST_CASE("opening hours: several hours by commas, and rules wherever they stand in the text") {
    CHECK(at("Mo-Fr 09:00-12:00,13:00-17:30", tu, 12, 30) == "closed");
    CHECK(at("Mo-Fr 09:00-12:00,13:00-17:30", tu, 13, 0) == "open");
    CHECK(at("Mo-Fr 09:00-12:00,13:00-17:30", tu, 17, 29) == "open");
    CHECK(at("Mo-Fr 09:00-12:00,13:00-17:30", tu, 17, 30) == "closed");
    CHECK(at("Mo-Fr 09:00 - 12:00 , 13:00 - 17:30", tu, 13, 0) == "open");
    CHECK(at("Mo-Fr 09:00-12:00, Sa 13:00-17:30", sa, 13, 0) == "open");
    CHECK(at("Mo-Fr 09:00-12:00, Sa 13:00-17:30", mo, 13, 0) == "closed");
    CHECK(at("Mo-Fr 10:00-18:00 Sa 10:00-14:00", sa, 12, 0) == "open");
    CHECK(at("open Mo-Fr 10:00-18:00 except holidays", mo, 12, 0) == "open");
}

TEST_CASE("opening hours: off and closed, and the last rule naming the day wins") {
    CHECK(at("Mo-Sa 10:00-18:00; Su off", su, 12, 0) == "closed");
    CHECK(at("Mo-Sa 10:00-18:00; Su closed", su, 12, 0) == "closed");
    CHECK(at("Mo-Sa 10:00-18:00; Su closed", sa, 12, 0) == "open");
    CHECK(at("off", we, 12, 0) == "closed");
    CHECK(at("closed", we, 12, 0) == "closed");
    CHECK(at("Mo-Fr 10:00-18:00 off", we, 12, 0) == "closed");
    CHECK(at("Mooff", mo, 12, 0) == "closed");
    CHECK(at("Mo-Fr 09:00-18:00; We 12:00-13:00", we, 10, 0) == "closed");
    CHECK(at("Mo-Fr 09:00-18:00; We 12:00-13:00", we, 12, 30) == "open");
    CHECK(at("Mo-Fr 09:00-18:00; We 12:00-13:00", th, 10, 0) == "open");
    CHECK(at("Mo-Su 09:00-18:00; We off", we, 10, 0) == "closed");
    CHECK(at("We off; Mo-Fr 10:00-18:00", we, 12, 0) == "open");
}

TEST_CASE("opening hours: hours past midnight count on the next day, by the last rule of the day they start on") {
    CHECK(at("Th 10:00-01:00", th, 23, 0) == "open");
    CHECK(at("Th 10:00-01:00", fr, 0, 30, on(2026, 10, 2)) == "open");
    CHECK(at("Th 10:00-01:00", fr, 1, 0, on(2026, 10, 2)) == "closed");
    CHECK(at("Th 10:00-01:00", th, 0, 30, on(2026, 10, 1)) == "closed");
    CHECK(at("Fr-Sa 18:00-02:00", su, 1, 0) == "open");
    CHECK(at("Fr-Sa 18:00-02:00", mo, 1, 0) == "closed");
    CHECK(at("Su 20:00-03:00", mo, 2, 0) == "open");
    CHECK(at("22:00-22:00", we, 21, 59) == "open");
    CHECK(at("00:00-00:00", we, 23, 59) == "open");
    CHECK(at("Mo 10:00-12:00", tu, 11, 0) == "closed");
    CHECK(at("Mo 20:00-26:00", tu, 1, 0) == "open");
    CHECK(at("Mo 20:00-26:00", tu, 2, 0) == "closed");
    CHECK(at("Fr 18:00-02:00; Fr 10:00-12:00", sa, 1, 0) == "closed");
    CHECK(at("Fr 18:00-02:00; Sa off", sa, 1, 0) == "open");
    // minutes past 59 count as written (25:99 is 26:39)
    CHECK(at("Mo 25:99-26:00", tu, 2, 39) == "open");
    CHECK(at("Mo 25:99-26:00", tu, 2, 38) == "closed");
    // the time is hour * 60 + minute, unchecked: one before midnight reads the day before's hours only where they run
    // past midnight
    CHECK(at("Mo 10:00-02:00", mo, 25, 0) == "open");
    CHECK(at("Mo 10:00-23:30", tu, -1, 0) == "closed");
    CHECK(at("Mo 23:00-01:00", tu, -1, 30) == "open");
}

TEST_CASE("opening hours: a dated part counts on its date alone, as that day's own rule") {
    CHECK(at("Mo-Su 10:00-18:00; Dec 25 off", fr, 12, 0, on(2026, 12, 25)) == "closed");
    CHECK(at("Mo-Su 10:00-18:00; Dec 25 off", th, 12, 0, on(2026, 12, 24)) == "open");
    CHECK(at("Mo-Su 10:00-18:00; 2026 Dec 25 off", fr, 12, 0, on(2026, 12, 25)) == "closed");
    CHECK(at("Mo-Su 10:00-18:00; 2027 Dec 25 off", fr, 12, 0, on(2026, 12, 25)) == "open");
    CHECK(at("Dec 24 10:00-14:00", th, 12, 0, on(2026, 12, 24)) == "open");
    CHECK(at("Dec 24 10:00-14:00", we, 12, 0, on(2026, 12, 23)) == "none");
    CHECK(at("Sep 30 10:00-12:00", mo, 11, 0) == "open");
    CHECK(at("Sep 30 Mo 10:00-12:00", we, 11, 0) == "closed");
    CHECK(at("Sep  30: off", we, 11, 0) == "closed");
    // no word character may follow the day: these are no dates, and their "off" closes every day
    CHECK(at("Mo-Su 10:00-18:00; Oct 1st off", we, 12, 0) == "closed");
    CHECK(at("Mo-Su 10:00-18:00; Oct 100 off", we, 12, 0) == "closed");
    CHECK(at("Mo-Su 10:00-18:00; sep 30 off", th, 12, 0, on(2026, 10, 1)) == "closed");
    CHECK(at("Mo-Su 10:00-18:00; Sep 30 off", th, 12, 0, on(2026, 10, 1)) == "open");
    // dated hours past midnight: the next day reads only its own parts for a rule
    CHECK(at("Sep 30 22:00-02:00", we, 23, 0) == "open");
    CHECK(at("Sep 30 22:00-02:00", th, 1, 0, on(2026, 10, 1)) == "none");
    CHECK(at("Mo-Su 08:00-09:00; Sep 30 22:00-02:00", th, 1, 0, on(2026, 10, 1)) == "open");
    CHECK(at("Mo-Su 08:00-09:00; Dec 31 22:00-02:00", fr, 1, 0, on(2027, 1, 1)) == "open");
    CHECK(at("Mo-Su 08:00-09:00; Feb 29 22:00-02:00", we, 1, 0, on(2028, 3, 1)) == "open");
    CHECK(at("Mo-Su 08:00-09:00; Feb 29 22:00-02:00", mo, 1, 0, on(2027, 3, 1)) == "closed");
    // the rest of a dated part ends at a newline
    CHECK(at("Dec 25 off\nMo 10:00-12:00", fr, 11, 0, on(2026, 12, 25)) == "closed");
    CHECK(at("Dec 25 off\nMo 10:00-12:00", mo, 11, 0, on(2026, 12, 21)) == "none");
    CHECK(at("20261 Dec 25 off", fr, 11, 0, on(2026, 12, 25)) == "closed");
}

TEST_CASE("opening hours: PH is dropped where it stands as a word, with a comma before it") {
    CHECK(at("Mo-Fr,PH 10:00-18:00", mo, 12, 0) == "open");
    CHECK(at("Mo-Fr,PH 10:00-18:00", sa, 12, 0) == "closed");
    CHECK(at("PH 10:00-12:00", su, 11, 0) == "open");
    CHECK(at("Mo-Su 10:00-18:00; PH off", mo, 12, 0) == "closed");
    CHECK(at("Sa,PH 10:00-12:00", mo, 11, 0) == "closed");
    CHECK(at("Sa,PHX 10:00-12:00", mo, 11, 0) == "open");
    CHECK(at("Sa,PH2 10:00-12:00", mo, 11, 0) == "open");
    CHECK(at("Sa,PHé 10:00-12:00", mo, 11, 0) == "open");
    CHECK(at("Sa,PH 10:00-12:00", mo, 11, 0) == "closed");
    CHECK(at("PH,Sa 10:00-12:00", mo, 11, 0) == "closed");
    CHECK(at("Sa,PHPH 10:00-12:00", mo, 11, 0) == "open");
}

TEST_CASE("opening hours: Python's white space, decimal digits of every plane and word characters") {
    CHECK(at("Mo-Fr 09:00 - 18:00", we, 12, 0) == "open");
    CHECK(at("Mo-Fr ٠٩:٣٠-١٨:٠٠", we, 9, 30) == "open");
    CHECK(at("Mo-Fr ٠٩:٣٠-١٨:٠٠", we, 9, 29) == "closed");
    // beyond the Basic Multilingual Plane (R/Real.kt reads a UTF-16 unit there)
    CHECK(at("Mo-Fr \U0001D7CE\U0001D7D7:\U0001D7D1\U0001D7CE-\U0001D7CF\U0001D7D6:\U0001D7CE\U0001D7CE", we, 9, 30) ==
          "open");
    CHECK(at("Mo-Fr \U0001D7CE\U0001D7D7:\U0001D7D1\U0001D7CE-\U0001D7CF\U0001D7D6:\U0001D7CE\U0001D7CE", we, 9, 29) ==
          "closed");
    // ² is a word character to Python (str.isalnum()), so no date ends at the 1 and "off" closes every day (Java's \w
    // and \b, R/Real.kt's, read the date)
    CHECK(at("Mo-Su 10:00-18:00; Oct 1² off", th, 12, 0, on(2026, 10, 1)) == "closed");
    CHECK(at("Mo-Su 10:00-18:00; Oct 1² off", we, 12, 0) == "closed");
    CHECK(at("Mo-Su 10:00-18:00; Sep 3٠ off", we, 12, 0) == "closed");
    CHECK(at("Mo-Su 10:00-18:00; ٢٠٢٦ Sep 30 off", we, 12, 0) == "closed");
    CHECK(at("Mo-Fr 09:00-18:00\nSa 10:00-12:00", sa, 11, 0) == "open");
    CHECK(at("Mo-Fr 09:00-18:00 Sa 10:00-12:00", sa, 11, 0) == "open");
    CHECK(at("Mo-Fr 09:00-18:00​", we, 12, 0) == "open");
    CHECK(at("​Mo-Fr 09:00-18:00", sa, 12, 0) == "closed");
    CHECK(at("Mo－Fr 09:00-18:00", sa, 12, 0) == "closed");
    // an invalid byte reads as U+FFFD, a character of no class
    CHECK(at("Mo-Fr\xFF 09:00-18:00", we, 12, 0) == "open");
}

TEST_CASE("opening hours: the date's weekday, and nothing where the Python raises on the date") {
    CHECK(weekday_of(on(2026, 9, 30)) == we);
    CHECK(weekday_of(on(2026, 10, 1)) == th);
    CHECK(weekday_of(on(2026, 10, 2)) == fr);
    CHECK(weekday_of(on(2000, 2, 29)) == tu);
    CHECK(weekday_of(on(1, 1, 1)) == mo);
    CHECK(weekday_of(on(9999, 12, 31)) == fr);
    CHECK_FALSE(weekday_of(on(2026, 2, 29)).has_value());
    CHECK_FALSE(weekday_of(on(2026, 13, 1)).has_value());
    CHECK_FALSE(weekday_of(on(0, 1, 1)).has_value());
    CHECK_FALSE(weekday_of(on(10000, 1, 1)).has_value());
    // ValueError (no 30 February) and OverflowError (no day before 1-01-01), once a rule is read
    CHECK(at("Mo 10:00-12:00", mo, 11, 0, on(2026, 2, 30)) == "none");
    CHECK(at("Mo 10:00-12:00", mo, 11, 0, on(1, 1, 1)) == "none");
    CHECK(at("Mo 10:00-12:00", mo, 11, 0, on(1, 1, 2)) == "open");
    CHECK(at("by appointment", mo, 11, 0, on(2026, 2, 30)) == "none");
}

TEST_CASE("opening hours: real.py's own answers at every moment of the fixture (opening_hours.json)") {
    const std::string text = fixture_text();
    auto json = tl::read_json(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(json.has_value(), "tests/style/opening_hours.json cannot be read");
    const tl::Value root = json->root();
    const std::vector<Date> common_dates = dates_of(field(root, "dates"));
    const std::vector<HourMinute> common_times = times_of(field(root, "times"));
    const tl::Value cases = field(root, "cases");
    ATLAS_REQUIRE(!common_dates.empty() && !common_times.empty() && cases.size() > 0);
    std::size_t moments = 0;
    std::size_t wrong = 0;
    for (std::size_t c = 0; c < cases.size(); ++c) {
        const tl::Value one = cases.child(c);
        const std::string_view oh = field(one, "oh").string().value_or("");
        std::vector<Date> dates = common_dates;
        for (const Date& d : dates_of(field(one, "dates"))) dates.push_back(d);
        std::vector<HourMinute> times = common_times;
        for (const HourMinute& t : times_of(field(one, "times"))) times.push_back(t);
        // one string a date: seven groups of the times' answers, Mo to Su, a space between them
        const tl::Value open = field(one, "open");
        ATLAS_REQUIRE(open.size() == dates.size(), "case " << c << ": " << open.size() << " dates answered");
        for (std::size_t d = 0; d < dates.size(); ++d) {
            const std::string_view want = open.child(d).string().value_or("");
            ATLAS_REQUIRE(want.size() == (7 * (times.size() + 1)) - 1,
                          "case " << c << ": " << want.size() << " answers");
            for (std::uint8_t day = 0; day < 7; ++day) {
                for (std::size_t i = 0; i < times.size(); ++i) {
                    const char python = want[(day * (times.size() + 1)) + i];
                    const char got = letter(open_at(oh, static_cast<Weekday>(day), times[i], dates[d]));
                    if (got != python && wrong++ < 20) {
                        FAIL_CHECK("case ", c, " ", json_string(oh), " on day ", int{day}, " of ", dates[d].year, "-",
                                   dates[d].month, "-", dates[d].day, " at ", times[i].hour, ":", times[i].minute, ": ",
                                   got, " for the Python's ", python);
                    }
                    ++moments;
                }
            }
        }
    }
    MESSAGE(cases.size(), " texts at ", moments, " moments, ", wrong, " answers not the Python's");
    CHECK(wrong == 0);
}

TEST_CASE("opening hours: every opening_hours of the bundled packs is a case of the fixture (ATLAS_TILES_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled packs' opening hours are not read here");
        return;
    }
    std::set<std::string> values;
    for (const char* name : {"amsterdam.atlas", "grachten.atlas"}) {
        ATLAS_REQUIRE(pack_hours((std::filesystem::path(dir) / name).string(), values));
    }
    // the list for tests/style/opening_hours.py
    const std::string out_dir = atlas::test::environment("ATLAS_TEST_OUT");
    const std::filesystem::path list =
        (out_dir.empty() ? std::filesystem::path(ATLAS_TEST_SCRATCH) : std::filesystem::path(out_dir)) /
        "opening_hours_strings.json";
    std::error_code ec;
    std::filesystem::create_directories(list.parent_path(), ec);
    {
        std::ofstream out(list, std::ios::binary);
        out << "[\n";
        std::size_t n = 0;
        for (const std::string& v : values) out << "  " << json_string(v) << (++n < values.size() ? ",\n" : "\n");
        out << "]\n";
        CHECK(out.good());
    }
    // every value is a case
    const std::string text = fixture_text();
    auto json = tl::read_json(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE(json.has_value(), "tests/style/opening_hours.json cannot be read");
    std::set<std::string, std::less<>> cases;
    const tl::Value list_of_cases = field(json->root(), "cases");
    for (std::size_t c = 0; c < list_of_cases.size(); ++c) {
        cases.emplace(field(list_of_cases.child(c), "oh").string().value_or(""));
    }
    std::size_t missing = 0;
    for (const std::string& v : values) {
        if (cases.contains(v)) continue;
        if (missing++ < 20) FAIL_CHECK("not in the fixture: ", json_string(v));
    }
    MESSAGE(values.size(), " distinct opening_hours in the packs (listed in ", list.string(), "), ", missing,
            " not in the fixture");
    CHECK(missing == 0);
}
