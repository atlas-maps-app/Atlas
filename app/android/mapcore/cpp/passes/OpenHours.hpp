// The opening hours' answers at the frame's moment (render/amsterdam.py's open_at, style/OpeningHours.hpp), for the
// passes that light what is open: the shopfronts (a board's glass, a terrace, an open place's glow) and the walls (a
// shop floor's glazing at night, amsterdam.py's open_here). A tile's build that keeps opening hours takes a serial of
// its own (next_serial), the cache's key: each build's distinct hours are answered once a minute, not a frame for each
// place.
#pragma once

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

namespace atlas::style {
struct ThemeBlock;
} // namespace atlas::style

namespace atlas::passes {

/// A new build's serial (OpenHours' key), never 0.
[[nodiscard]] std::uint64_t next_serial() noexcept;

/// Each build's opening hours' answers at one moment (open 1, closed 0, none -1: open_at answers nothing), by its
/// serial.
class OpenHours {
public:
    /// The answers of `hours`, the build's with serial `serial`, at the theme's moment: `open_at(oh,
    /// DAYS[datetime.date(*th['date']).weekday()], th['hhmm'], date=th['date'])` for each.
    [[nodiscard]] const std::vector<std::int8_t>& of(std::uint64_t serial, std::span<const std::string> hours,
                                                     const style::ThemeBlock& th);

private:
    std::array<int, 5> moment_{};
    std::unordered_map<std::uint64_t, std::vector<std::int8_t>> answers_;
};

} // namespace atlas::passes
