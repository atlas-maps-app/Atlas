#include "style/OpeningHours.hpp"

#include "load/Real.hpp"
#include "text/Utf8.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <iterator>
#include <string>
#include <utility>

namespace atlas::style {

namespace {

struct WordRange {
    char32_t first;
    char32_t last;
};

#include "text/PyRe.inc"

using Text = std::u32string;
using View = std::u32string_view;

constexpr std::array<View, 7> day_names = {U"Mo", U"Tu", U"We", U"Th", U"Fr", U"Sa", U"Su"};
constexpr std::array<View, 12> month_names = {U"Jan", U"Feb", U"Mar", U"Apr", U"May", U"Jun",
                                              U"Jul", U"Aug", U"Sep", U"Oct", U"Nov", U"Dec"};
constexpr std::int64_t day_minutes = std::int64_t{24} * 60;

// ---- Python's text

/// `\s`, the white space str.strip() takes off.
[[nodiscard]] bool space(char32_t c) noexcept { return load::is_space(c); }

/// `\d`: the value of a decimal digit of any plane, as int() reads it.
[[nodiscard]] std::optional<int> digit(char32_t c) noexcept {
    if (c < 0x10000) return load::digit_of(c);
    const auto* const after = std::ranges::upper_bound(astral_digit_zeros, c);
    if (after == astral_digit_zeros.begin()) return std::nullopt;
    const char32_t value = c - *std::prev(after);
    if (value >= 10) return std::nullopt;
    return static_cast<int>(value);
}

/// `\w`: str.isalnum() or "_".
[[nodiscard]] bool word(char32_t c) noexcept {
    const auto* const after = std::ranges::upper_bound(word_ranges, c, {}, &WordRange::first);
    return after != word_ranges.begin() && c <= std::prev(after)->last;
}

/// `s.strip()`.
[[nodiscard]] View strip(View s) noexcept {
    while (!s.empty() && space(s.front())) s.remove_prefix(1);
    while (!s.empty() && space(s.back())) s.remove_suffix(1);
    return s;
}

/// Where `\s*` from `i` ends.
[[nodiscard]] std::size_t skip_space(View s, std::size_t i) noexcept {
    while (i < s.size() && space(s[i])) ++i;
    return i;
}

/// Whether `what` stands in `s` at `i`.
[[nodiscard]] bool holds(View s, std::size_t i, View what) noexcept {
    return i <= s.size() && s.substr(i).starts_with(what);
}

/// `\b` at `i`: a word character on one side of it and none on the other (the text's ends have none).
[[nodiscard]] bool boundary(View s, std::size_t i) noexcept {
    const bool before = i > 0 && i <= s.size() && word(s[i - 1]);
    const bool after = i < s.size() && word(s[i]);
    return before != after;
}

/// How many digits stand in a row from `i`.
[[nodiscard]] std::size_t digits_at(View s, std::size_t i) noexcept {
    std::size_t n = 0;
    while (i + n < s.size() && digit(s[i + n])) ++n;
    return n;
}

/// int() of the digits in [first, last): a group of a pattern's \d, four of them at most.
[[nodiscard]] int number(View s, std::size_t first, std::size_t last) noexcept {
    int n = 0;
    for (std::size_t i = first; i < last; ++i) n = n * 10 + digit(s[i]).value_or(0);
    return n;
}

/// The day of DAYS that stands at `i`, as its index.
[[nodiscard]] std::optional<std::size_t> day_at(View s, std::size_t i) noexcept {
    for (std::size_t k = 0; k < day_names.size(); ++k) {
        if (holds(s, i, day_names[k])) return k;
    }
    return std::nullopt;
}

// ---- a dated part and PH

/// A part dated by `re.match(r'(?:(\d{4})\s+)?(Jan|...|Dec)\s+(\d{1,2})\b(.*)', p)`.
struct Dated {
    std::optional<int> year;
    int month = 0; ///< 1 to 12
    int day = 0;
    View rest; ///< the part after the day to its first newline (`.` takes no newline)
};

[[nodiscard]] std::optional<Dated> dated(View p) noexcept {
    Dated out;
    std::size_t i = 0;
    // (?:(\d{4})\s+)?: four digits and white space; failing that the month must stand first, which a digit cannot
    if (digits_at(p, 0) == 4 && p.size() > 4 && space(p[4])) {
        out.year = number(p, 0, 4);
        i = skip_space(p, 4);
    }
    const auto* const month = std::ranges::find_if(month_names, [&](View m) { return holds(p, i, m); });
    if (month == month_names.end()) return std::nullopt;
    out.month = static_cast<int>(std::distance(month_names.begin(), month)) + 1;
    i += month->size();
    if (i >= p.size() || !space(p[i])) return std::nullopt;
    i = skip_space(p, i);
    // (\d{1,2})\b: two digits, else one, with no word character after them (so not three digits, nor 5th)
    const std::size_t n = digits_at(p, i);
    if (n == 0 || n > 2 || !boundary(p, i + n)) return std::nullopt;
    out.day = number(p, i, i + n);
    i += n;
    const std::size_t newline = p.find(U'\n', i);
    out.rest = p.substr(i, newline == View::npos ? View::npos : newline - i);
    return out;
}

/// `re.sub(r',?\bPH\b', '', p)`.
[[nodiscard]] Text without_ph(View p) {
    Text out;
    std::size_t i = 0;
    while (i < p.size()) {
        // ,? takes a comma where one stands (and \b holds between it and the P); where none does, \b before the P
        const std::size_t at = p[i] == U',' ? i + 1 : i;
        if (holds(p, at, U"PH") && (at > i || boundary(p, at)) && boundary(p, at + 2)) {
            i = at + 2;
            continue;
        }
        out.push_back(p[i]);
        ++i;
    }
    return out;
}

// ---- RULE

/// One `\d{1,2}:\d{2}` as real.py reads it: `int(a[:a.index(':')]) * 60 + int(a[-2:])`.
struct Clock {
    std::int64_t minutes = 0;
    std::size_t end = 0;
};

[[nodiscard]] std::optional<Clock> clock_at(View s, std::size_t i) noexcept {
    // \d{1,2} takes two digits where two stand, and a third is not the colon (backing off to one finds a digit there)
    const std::size_t n = std::min<std::size_t>(digits_at(s, i), 2);
    if (n == 0 || !holds(s, i + n, U":") || digits_at(s, i + n + 1) < 2) return std::nullopt;
    return Clock{.minutes = (std::int64_t{number(s, i, i + n)} * 60) + number(s, i + n + 1, i + n + 3),
                 .end = i + n + 3};
}

/// One `\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2}`: from, to and where it ends.
struct Hours {
    std::int64_t from = 0;
    std::int64_t to = 0;
    std::size_t end = 0;
};

[[nodiscard]] std::optional<Hours> hours_at(View s, std::size_t i) noexcept {
    const auto from = clock_at(s, i);
    if (!from) return std::nullopt;
    const std::size_t dash = skip_space(s, from->end);
    if (!holds(s, dash, U"-")) return std::nullopt;
    const auto to = clock_at(s, skip_space(s, dash + 1));
    if (!to) return std::nullopt;
    return Hours{.from = from->minutes, .to = to->minutes, .end = to->end};
}

/// Where the next hours after a comma (`\s*,\s*`) start, if a comma follows `end`.
[[nodiscard]] std::optional<std::size_t> after_comma(View s, std::size_t end) noexcept {
    const std::size_t comma = skip_space(s, end);
    if (!holds(s, comma, U",")) return std::nullopt;
    return skip_space(s, comma + 1);
}

/// Where RULE's second group from `i` ends: hours and as many more after commas as follow whole, else off, else closed.
[[nodiscard]] std::optional<std::size_t> times_end(View s, std::size_t i) noexcept {
    if (const auto first = hours_at(s, i)) {
        std::size_t end = first->end;
        while (const auto next = after_comma(s, end)) {
            const auto more = hours_at(s, *next);
            if (!more) break;
            end = more->end;
        }
        return end;
    }
    if (holds(s, i, U"off")) return i + 3;
    if (holds(s, i, U"closed")) return i + 6;
    return std::nullopt;
}

/// Where a day with its range from `i` ends: D(?:-D)?.
[[nodiscard]] std::size_t day_range_end(View s, std::size_t i) noexcept {
    return holds(s, i + 2, U"-") && day_at(s, i + 3) ? i + 5 : i + 2;
}

/// Where RULE's first group from a day at `i` ends, taken whole. The regex backs off from the whole selector only to
/// shorter takes that leave a dash or a comma (after white space) before the hours, which then cannot match there: so
/// the selector is the whole one or none.
[[nodiscard]] std::size_t selector_end(View s, std::size_t i) noexcept {
    std::size_t end = day_range_end(s, i);
    while (const auto next = after_comma(s, end)) {
        if (!day_at(s, *next)) break;
        end = day_range_end(s, *next);
    }
    return end;
}

/// One match of RULE.
struct Rule {
    std::optional<View> days; ///< the first group, where it took part
    View times;               ///< the second
    std::size_t end = 0;
};

/// RULE matched at `i`: from a day only with its selector (the hours, off and closed start with none of its letters),
/// elsewhere without one, past white space.
[[nodiscard]] std::optional<Rule> rule_at(View s, std::size_t i) noexcept {
    std::optional<View> days;
    std::size_t at = skip_space(s, i);
    if (day_at(s, i)) {
        const std::size_t end = selector_end(s, i);
        days = s.substr(i, end - i);
        at = skip_space(s, end);
    }
    const auto end = times_end(s, at);
    if (!end) return std::nullopt;
    return Rule{.days = days, .times = s.substr(at, *end - at), .end = *end};
}

/// real.py's `_days(sel)` of RULE's first group, as bits (1 << a day's index): its parts after `\s*,\s*` each a day,
/// or a range of two, which runs on past Su (Fr-Mo is Fr, Sa, Su and Mo).
[[nodiscard]] unsigned days_of(View sel) noexcept {
    unsigned out = 0;
    std::optional<std::size_t> i = 0;
    while (i) {
        const std::size_t a = day_at(sel, *i).value_or(0);
        std::size_t b = a;
        if (holds(sel, *i + 2, U"-")) b = day_at(sel, *i + 3).value_or(a);
        for (std::size_t k = a;; k = (k + 1) % day_names.size()) {
            out |= 1U << k;
            if (k == b) break;
        }
        i = after_comma(sel, day_range_end(sel, *i));
    }
    return out;
}

// ---- the day

/// real.py's `_day_state(oh, day, t, date, spill)`: the state of the last rule naming the day, at minute `t` of it
/// (past 24:00 with spill, where only hours that run past midnight count), none without one; and whether the text
/// holds any rule at all.
struct DayState {
    std::optional<bool> state;
    bool any_rule = false;
};

[[nodiscard]] DayState day_state(View oh, std::size_t day, std::int64_t t, Date date, bool spill) {
    // the parts (oh.split(';'), stripped): a dated one on its date as the day's own rule, PH dropped from the rest;
    // joined by "; "
    Text joined;
    bool first = true;
    for (std::size_t from = 0;;) {
        const std::size_t semicolon = oh.find(U';', from);
        const View p = strip(oh.substr(from, semicolon == View::npos ? View::npos : semicolon - from));
        std::optional<Text> kept;
        if (const auto d = dated(p)) {
            if ((!d->year || *d->year == date.year) && d->month == date.month && d->day == date.day) {
                Text own(day_names[day]);
                own += U' ';
                own += strip(d->rest);
                kept = std::move(own);
            }
        } else {
            kept = without_ph(p);
        }
        if (kept) {
            if (!first) joined += U"; ";
            joined += *kept;
            first = false;
        }
        if (semicolon == View::npos) break;
        from = semicolon + 1;
    }
    // RULE.finditer(oh)
    DayState out;
    const View s = joined;
    for (std::size_t i = 0; i < s.size();) {
        const auto rule = rule_at(s, i);
        if (!rule) {
            ++i;
            continue;
        }
        i = rule->end;
        out.any_rule = true;
        if (rule->days && (days_of(*rule->days) & (1U << day)) == 0) continue;
        if (rule->times == U"off" || rule->times == U"closed") {
            out.state = false;
            continue;
        }
        // each hours of re.split(r'\s*,\s*', times), split at its '-'
        bool st = false;
        for (std::optional<std::size_t> at = 0; at;) {
            const auto h = hours_at(rule->times, *at);
            if (!h) break;
            const std::int64_t ta = h->from;
            const std::int64_t tb = h->to <= ta ? h->to + day_minutes : h->to;
            if (ta <= t && t < tb && (!spill || tb > day_minutes)) st = true;
            at = after_comma(rule->times, h->end);
        }
        out.state = st;
    }
    return out;
}

/// The date as std::chrono's (a day past 255 it cannot hold: read it after real()).
[[nodiscard]] std::chrono::year_month_day calendar(Date date) noexcept {
    return {std::chrono::year{date.year}, std::chrono::month{static_cast<unsigned>(date.month)},
            std::chrono::day{static_cast<unsigned>(date.day)}};
}

/// Whether Python's datetime.date takes the date.
[[nodiscard]] bool real(Date date) noexcept {
    if (date.year < 1 || date.year > 9999 || date.month < 1 || date.month > 12 || date.day < 1 || date.day > 31) {
        return false;
    }
    return calendar(date).ok();
}

/// `datetime.date(*date) - datetime.timedelta(days=1)`, or nothing where Python raises.
[[nodiscard]] std::optional<Date> previous_day(Date date) noexcept {
    if (!real(date) || (date.year == 1 && date.month == 1 && date.day == 1)) return std::nullopt;
    const std::chrono::year_month_day before{std::chrono::sys_days{calendar(date)} - std::chrono::days{1}};
    return Date{.year = static_cast<int>(before.year()),
                .month = static_cast<int>(static_cast<unsigned>(before.month())),
                .day = static_cast<int>(static_cast<unsigned>(before.day()))};
}

} // namespace

std::optional<Weekday> weekday_of(Date date) noexcept {
    if (!real(date)) return std::nullopt;
    return static_cast<Weekday>(std::chrono::weekday{std::chrono::sys_days{calendar(date)}}.iso_encoding() - 1);
}

std::optional<bool> open_at(std::string_view oh, Weekday day, HourMinute hhmm, Date date) noexcept {
    const auto d = static_cast<std::size_t>(day);
    if (oh.empty() || d >= day_names.size()) return std::nullopt;
    const Text text = text::code_points(oh);
    const View stripped = strip(text);
    if (stripped == U"24/7") return true;
    const std::int64_t t = (std::int64_t{hhmm.hour} * 60) + hhmm.minute;
    const DayState today = day_state(stripped, d, t, date, false);
    if (!today.any_rule) return std::nullopt;
    // hours that ran past midnight the day before
    const auto before = previous_day(date);
    if (!before) return std::nullopt;
    const DayState spill =
        day_state(stripped, (d + day_names.size() - 1) % day_names.size(), t + day_minutes, *before, true);
    return today.state.value_or(false) || spill.state.value_or(false);
}

} // namespace atlas::style
