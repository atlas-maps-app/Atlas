#include "passes/OpenHours.hpp"

#include "style/OpeningHours.hpp"
#include "style/Theme.hpp"

#include <atomic>
#include <optional>

namespace atlas::passes {

std::uint64_t next_serial() noexcept {
    static std::atomic<std::uint64_t> next{1};
    return next.fetch_add(1, std::memory_order_relaxed);
}

const std::vector<std::int8_t>& OpenHours::of(std::uint64_t serial, std::span<const std::string> hours,
                                              const style::ThemeBlock& th) {
    const std::array<int, 5> moment{th.year, th.month, th.day, th.hour, th.minute};
    if (moment != moment_ || answers_.size() > 4096) {
        answers_.clear();
        moment_ = moment;
    }
    const auto [it, made] = answers_.try_emplace(serial);
    if (made) {
        // `open_at(oh, DAYS[datetime.date(*th['date']).weekday()], th['hhmm'], date=th['date'])`
        const style::Date date{.year = th.year, .month = th.month, .day = th.day};
        const std::optional<style::Weekday> day = style::weekday_of(date);
        it->second.reserve(hours.size());
        for (const std::string& oh : hours) {
            std::optional<bool> now;
            if (day) now = style::open_at(oh, *day, {.hour = th.hour, .minute = th.minute}, date);
            it->second.push_back(!now ? std::int8_t{-1} : *now ? std::int8_t{1} : std::int8_t{0});
        }
    }
    return it->second;
}

} // namespace atlas::passes
