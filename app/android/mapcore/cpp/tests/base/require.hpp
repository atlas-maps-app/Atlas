// What a test cannot go on without. The core builds doctest without exceptions
// (DOCTEST_CONFIG_NO_EXCEPTIONS_BUT_WITH_ALL_ASSERTS, CMakeLists.txt), and there a failed REQUIRE, REQUIRE_MESSAGE or
// FAIL reports and fails the test case, then lets it run on, past the instance, device, offscreen target, buffer or
// decoded value it did not get: core.yml's third run went on into a null gpu::Device. So whatever a test or a fixture
// needs before it can go on, it requires with ATLAS_REQUIRE, which fails the test case at that line and returns; a
// helper that returns a value uses ATLAS_REQUIRE_OR_RETURN with the value to return. CHECK stays for what a test can
// go on without.
//
// The message, streamed as FAIL's, is built only when the requirement fails, so it may name the error:
// `ATLAS_REQUIRE(device.has_value(), device.error().text());`. REQUIRE_MESSAGE builds its message for a passing
// assertion too (doctest's --success, and the contexts its reporters print), where error() would read a value as an
// error.
#pragma once

#include <doctest/doctest.h>

/// Fails the test case and returns from the enclosing function unless `cond` holds. The message (optional, streamed as
/// FAIL's) is built only when `cond` fails.
#define ATLAS_REQUIRE(cond, ...)                                                                                       \
    do {                                                                                                               \
        if (!static_cast<bool>(cond)) {                                                                                \
            DOCTEST_FAIL(#cond " failed" __VA_OPT__(<< ": " << __VA_ARGS__));                                          \
            return;                                                                                                    \
        }                                                                                                              \
    } while (false)

/// ATLAS_REQUIRE in a function that returns a value: returns `value` when `cond` fails.
#define ATLAS_REQUIRE_OR_RETURN(value, cond, ...)                                                                      \
    do {                                                                                                               \
        if (!static_cast<bool>(cond)) {                                                                                \
            DOCTEST_FAIL(#cond " failed" __VA_OPT__(<< ": " << __VA_ARGS__));                                          \
            return value;                                                                                              \
        }                                                                                                              \
    } while (false)
