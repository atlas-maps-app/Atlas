// The core's log: one line per call, formatted into a fixed buffer (nothing allocates), written to Android's log under
// the tag smoke.sh reads (plan-vector Appendix C) or, on hosts, to stderr.
#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdio>
#include <format>
#include <string_view>
#include <utility>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace atlas::log {

/// How much a line matters; fatal is reserved for the failure handler in base/assert.hpp.
enum class Level : unsigned char { debug, info, warn, error, fatal };

/// The log tag of every core line: `adb logcat -s AtlasMap`. A C string, since Android's log reads it up to its
/// terminator.
inline constexpr const char* tag = "AtlasMap";

/// The longest line, terminator included; a longer line is cut and ends with "...".
inline constexpr std::size_t line_capacity = 1024;

/// A formatted line in its own buffer, always terminated.
class Line {
public:
    template <class... Args>
    explicit Line(std::format_string<Args...> format, Args&&... args) noexcept {
        constexpr std::size_t room = line_capacity - 1;
        const auto result =
            std::format_to_n(text_.data(), static_cast<std::ptrdiff_t>(room), format, std::forward<Args>(args)...);
        const auto wanted = static_cast<std::size_t>(result.size);
        size_ = std::min(wanted, room);
        if (wanted > room) {
            constexpr std::string_view cut = "...";
            std::ranges::copy(cut, text_.begin() + static_cast<std::ptrdiff_t>(room - cut.size()));
        }
        text_[size_] = '\0';
    }

    [[nodiscard]] const char* c_str() const noexcept { return text_.data(); }
    [[nodiscard]] std::string_view view() const noexcept { return {text_.data(), size_}; }

private:
    std::array<char, line_capacity> text_{};
    std::size_t size_ = 0;
};

/// Writes one finished line at a level.
inline void write(Level level, const Line& line) noexcept {
#if defined(__ANDROID__)
    constexpr std::array priorities{ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR,
                                    ANDROID_LOG_FATAL};
    __android_log_write(priorities[static_cast<std::size_t>(level)], tag, line.c_str());
#else
    constexpr std::array<std::string_view, 5> names{"D", "I", "W", "E", "F"};
    const std::string_view name = names[static_cast<std::size_t>(level)];
    // One call per line, so lines from several threads never interleave within a line.
    (void)std::fprintf(stderr, "%s %.*s %s\n", tag, static_cast<int>(name.size()), name.data(), line.c_str());
#endif
}

template <class... Args>
void debug(std::format_string<Args...> format, Args&&... args) noexcept {
    write(Level::debug, Line(format, std::forward<Args>(args)...));
}

template <class... Args>
void info(std::format_string<Args...> format, Args&&... args) noexcept {
    write(Level::info, Line(format, std::forward<Args>(args)...));
}

template <class... Args>
void warn(std::format_string<Args...> format, Args&&... args) noexcept {
    write(Level::warn, Line(format, std::forward<Args>(args)...));
}

template <class... Args>
void error(std::format_string<Args...> format, Args&&... args) noexcept {
    write(Level::error, Line(format, std::forward<Args>(args)...));
}

} // namespace atlas::log
