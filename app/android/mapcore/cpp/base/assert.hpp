// Invariants. The core has no exceptions (plan-vector T3): a fallible operation returns std::expected, and a broken
// invariant ends the process through one handler, which logs where and why first. On Android the reason also becomes
// the tombstone's abort message, so CI's ndk-stack trace shows it.
#pragma once

#include "base/log.hpp"
#include "base/std_check.hpp"

#include <cstdlib>
#include <source_location>
#include <string_view>

#if defined(__ANDROID__)
#include <android/set_abort_message.h>
#endif

namespace atlas {

/// Ends the process after a broken invariant: logs the expression, the reason and the place, then aborts.
[[noreturn, gnu::cold, gnu::noinline]] inline void
fail(std::string_view expression, std::string_view reason,
     std::source_location where = std::source_location::current()) noexcept {
    const log::Line line("{}:{}: {}{}{}", where.file_name(), where.line(), expression, reason.empty() ? "" : ": ",
                         reason);
    log::write(log::Level::fatal, line);
#if defined(__ANDROID__)
    android_set_abort_message(line.c_str());
#endif
    std::abort();
}

} // namespace atlas

/// Checks an invariant in every build; the optional reason must be a string literal.
#define ATLAS_ASSERT(condition, ...)                                                                                   \
    (static_cast<bool>(condition) ? static_cast<void>(0) : ::atlas::fail(#condition, "" __VA_ARGS__))

/// Checks an invariant in debug builds only (NDEBUG off), where checking it costs too much elsewhere.
#if defined(NDEBUG)
#define ATLAS_DEBUG_ASSERT(condition, ...) static_cast<void>(sizeof(static_cast<bool>(condition)))
#else
#define ATLAS_DEBUG_ASSERT(condition, ...) ATLAS_ASSERT(condition, __VA_ARGS__)
#endif

/// Marks a path that cannot be reached; reaching it ends the process with the reason.
#define ATLAS_UNREACHABLE(reason) ::atlas::fail("unreachable", reason)
