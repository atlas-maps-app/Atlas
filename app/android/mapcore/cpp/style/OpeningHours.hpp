// render/real.py's opening hours, its common subset (ENGINE.md step 4, the night: the open places' glows and the
// shopfronts' lit glazing, render/amsterdam.py:1142 and :1771 ask `open_at(...) is True`): open_at with _days and
// _day_state, as Python computes them. The Python is the truth: where R/Real.kt reads text otherwise (Java's \w and
// \b, digits beyond the Basic Multilingual Plane), this follows real.py.
//
// What real.py reads: a value is parts between `;`. A part `[YYYY ]Mon D rest` counts on that date alone, as the day's
// own rule `Dd rest`; every other part loses its `PH` (with a comma before it). Then each match of RULE, anywhere in
// the parts joined by "; " (an optional day selector, as Mo, Mo-Fr, Fr-Mo or Sa,Su, then hours hh:mm-hh:mm, several
// by commas, or off or closed), sets the state of the days it names: the last one for the day wins, closed where none
// of its hours hold the minute. Hours that end at or before they start run past midnight and belong to the day they
// start on: at 00:30 on a Friday, Th 10:00-01:00 is open. Text that holds no rule is not understood; 24/7 is open.
//
// Text is Python's str: UTF-8 read as code points (an invalid byte as U+FFFD, text/Utf8.hpp), `\s` and strip()
// Python's white space (load::is_space), `\d` and int() a decimal digit of any plane, `\b` the edge between Python's
// word characters (str.isalnum() or "_") and the rest, `.` anything but a newline: CPython 3.9.6's Unicode 13.0
// (text/Digits.inc, text/PyRe.inc). tests/style/opening_hours_test.cpp holds it to the Python's own answers.
#pragma once

#include <cstdint>
#include <optional>
#include <string_view>

namespace atlas::style {

/// A day of the week, as real.py's DAYS: Monday first, Python's date.weekday().
enum class Weekday : std::uint8_t { mo, tu, we, th, fr, sa, su };

/// A date as real.py's (year, month, day): a real one is a day of the proleptic Gregorian calendar in the years
/// 1-9999, as Python's datetime.date takes it.
struct Date {
    int year;
    int month;
    int day;
};

/// A time of day as real.py's hhmm, (hour, minute): the minute hour * 60 + minute, unchecked, as Python reads it.
struct HourMinute {
    int hour;
    int minute;
};

/// Python's `datetime.date(*date).weekday()` as a day of DAYS; nothing for a date that is not real (Python raises).
[[nodiscard]] std::optional<Weekday> weekday_of(Date date) noexcept;

/// real.py's `open_at(oh, day, hhmm, date)`: open or closed on `day` (the day `date` falls on, where the renders ask)
/// at `hhmm`, or nothing when the text holds no rule real.py reads (empty text too). Where the Python raises, after
/// reading a rule, on a date that is not real or has no day before it (1-01-01), this answers nothing as well.
[[nodiscard]] std::optional<bool> open_at(std::string_view oh, Weekday day, HourMinute hhmm, Date date) noexcept;

} // namespace atlas::style
