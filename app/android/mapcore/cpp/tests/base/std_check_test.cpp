// The feature probe (plan-vector T3): base/std_check.hpp fails the build on a toolchain without a feature the core
// relies on; this file uses each of them once at run time, and prints the whole table for the record
// (`atlas_tests --probe`), the C++26 features the core adopts as the NDK gains them included.
#include "base/std_check.hpp"
#include "tests/base/probe.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <concepts>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <expected>
#include <flat_map>
#include <format>
#include <mdspan>
#include <memory_resource>
#include <numeric>
#include <ranges>
#include <span>
#include <stop_token>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>
#include <version>

namespace atlas::test {

namespace {

struct Feature {
    const char* name;
    long value; // 0 when the toolchain does not define it
};

} // namespace

// Every macro the core relies on (std_check.hpp) and the ones it watches for (plan-vector T3: C++26 as it arrives).
void print_features(std::FILE* out) {
    std::vector<Feature> features;
#define ATLAS_ADD(macro) features.push_back(Feature{#macro, macro})
#define ATLAS_MISSING(macro) features.push_back(Feature{#macro, 0})
    // Language.
    ATLAS_ADD(__cplusplus);
#ifdef __cpp_concepts
    ATLAS_ADD(__cpp_concepts);
#else
    ATLAS_MISSING(__cpp_concepts);
#endif
#ifdef __cpp_consteval
    ATLAS_ADD(__cpp_consteval);
#else
    ATLAS_MISSING(__cpp_consteval);
#endif
#ifdef __cpp_if_consteval
    ATLAS_ADD(__cpp_if_consteval);
#else
    ATLAS_MISSING(__cpp_if_consteval);
#endif
#ifdef __cpp_explicit_this_parameter
    ATLAS_ADD(__cpp_explicit_this_parameter);
#else
    ATLAS_MISSING(__cpp_explicit_this_parameter);
#endif
#ifdef __cpp_multidimensional_subscript
    ATLAS_ADD(__cpp_multidimensional_subscript);
#else
    ATLAS_MISSING(__cpp_multidimensional_subscript);
#endif
#ifdef __cpp_static_call_operator
    ATLAS_ADD(__cpp_static_call_operator);
#else
    ATLAS_MISSING(__cpp_static_call_operator);
#endif
#ifdef __cpp_pack_indexing
    ATLAS_ADD(__cpp_pack_indexing);
#else
    ATLAS_MISSING(__cpp_pack_indexing);
#endif
#ifdef __cpp_deleted_function
    ATLAS_ADD(__cpp_deleted_function);
#else
    ATLAS_MISSING(__cpp_deleted_function);
#endif
#ifdef __cpp_placeholder_variables
    ATLAS_ADD(__cpp_placeholder_variables);
#else
    ATLAS_MISSING(__cpp_placeholder_variables);
#endif
#ifdef __cpp_contracts
    ATLAS_ADD(__cpp_contracts);
#else
    ATLAS_MISSING(__cpp_contracts);
#endif
#ifdef __cpp_impl_reflection
    ATLAS_ADD(__cpp_impl_reflection);
#else
    ATLAS_MISSING(__cpp_impl_reflection);
#endif
#ifdef __cpp_modules
    ATLAS_ADD(__cpp_modules);
#else
    ATLAS_MISSING(__cpp_modules);
#endif
    // Library.
#ifdef _LIBCPP_VERSION
    ATLAS_ADD(_LIBCPP_VERSION);
#else
    ATLAS_MISSING(_LIBCPP_VERSION);
#endif
#ifdef __cpp_lib_expected
    ATLAS_ADD(__cpp_lib_expected);
#else
    ATLAS_MISSING(__cpp_lib_expected);
#endif
#ifdef __cpp_lib_mdspan
    ATLAS_ADD(__cpp_lib_mdspan);
#else
    ATLAS_MISSING(__cpp_lib_mdspan);
#endif
#ifdef __cpp_lib_flat_map
    ATLAS_ADD(__cpp_lib_flat_map);
#else
    ATLAS_MISSING(__cpp_lib_flat_map);
#endif
#ifdef __cpp_lib_flat_set
    ATLAS_ADD(__cpp_lib_flat_set);
#else
    ATLAS_MISSING(__cpp_lib_flat_set);
#endif
#ifdef __cpp_lib_ranges_to_container
    ATLAS_ADD(__cpp_lib_ranges_to_container);
#else
    ATLAS_MISSING(__cpp_lib_ranges_to_container);
#endif
#ifdef __cpp_lib_ranges_zip
    ATLAS_ADD(__cpp_lib_ranges_zip);
#else
    ATLAS_MISSING(__cpp_lib_ranges_zip);
#endif
#ifdef __cpp_lib_ranges_enumerate
    ATLAS_ADD(__cpp_lib_ranges_enumerate);
#else
    ATLAS_MISSING(__cpp_lib_ranges_enumerate);
#endif
#ifdef __cpp_lib_ranges_chunk
    ATLAS_ADD(__cpp_lib_ranges_chunk);
#else
    ATLAS_MISSING(__cpp_lib_ranges_chunk);
#endif
#ifdef __cpp_lib_print
    ATLAS_ADD(__cpp_lib_print);
#else
    ATLAS_MISSING(__cpp_lib_print);
#endif
#ifdef __cpp_lib_format
    ATLAS_ADD(__cpp_lib_format);
#else
    ATLAS_MISSING(__cpp_lib_format);
#endif
#ifdef __cpp_lib_jthread
    ATLAS_ADD(__cpp_lib_jthread);
#else
    ATLAS_MISSING(__cpp_lib_jthread);
#endif
#ifdef __cpp_lib_atomic_wait
    ATLAS_ADD(__cpp_lib_atomic_wait);
#else
    ATLAS_MISSING(__cpp_lib_atomic_wait);
#endif
#ifdef __cpp_lib_byteswap
    ATLAS_ADD(__cpp_lib_byteswap);
#else
    ATLAS_MISSING(__cpp_lib_byteswap);
#endif
#ifdef __cpp_lib_to_underlying
    ATLAS_ADD(__cpp_lib_to_underlying);
#else
    ATLAS_MISSING(__cpp_lib_to_underlying);
#endif
#ifdef __cpp_lib_unreachable
    ATLAS_ADD(__cpp_lib_unreachable);
#else
    ATLAS_MISSING(__cpp_lib_unreachable);
#endif
#ifdef __cpp_lib_memory_resource
    ATLAS_ADD(__cpp_lib_memory_resource);
#else
    ATLAS_MISSING(__cpp_lib_memory_resource);
#endif
#ifdef __cpp_lib_out_ptr
    ATLAS_ADD(__cpp_lib_out_ptr);
#else
    ATLAS_MISSING(__cpp_lib_out_ptr);
#endif
#ifdef __cpp_lib_saturation_arithmetic
    ATLAS_ADD(__cpp_lib_saturation_arithmetic);
#else
    ATLAS_MISSING(__cpp_lib_saturation_arithmetic);
#endif
#ifdef __cpp_lib_span_initializer_list
    ATLAS_ADD(__cpp_lib_span_initializer_list);
#else
    ATLAS_MISSING(__cpp_lib_span_initializer_list);
#endif
#ifdef __cpp_lib_inplace_vector
    ATLAS_ADD(__cpp_lib_inplace_vector);
#else
    ATLAS_MISSING(__cpp_lib_inplace_vector);
#endif
#ifdef __cpp_lib_simd
    ATLAS_ADD(__cpp_lib_simd);
#else
    ATLAS_MISSING(__cpp_lib_simd);
#endif
#ifdef __cpp_lib_generator
    ATLAS_ADD(__cpp_lib_generator);
#else
    ATLAS_MISSING(__cpp_lib_generator);
#endif
#ifdef __cpp_lib_stacktrace
    ATLAS_ADD(__cpp_lib_stacktrace);
#else
    ATLAS_MISSING(__cpp_lib_stacktrace);
#endif
#ifdef __cpp_lib_move_only_function
    ATLAS_ADD(__cpp_lib_move_only_function);
#else
    ATLAS_MISSING(__cpp_lib_move_only_function);
#endif
#ifdef __cpp_lib_start_lifetime_as
    ATLAS_ADD(__cpp_lib_start_lifetime_as);
#else
    ATLAS_MISSING(__cpp_lib_start_lifetime_as);
#endif
#ifdef __cpp_lib_is_implicit_lifetime
    ATLAS_ADD(__cpp_lib_is_implicit_lifetime);
#else
    ATLAS_MISSING(__cpp_lib_is_implicit_lifetime);
#endif
#ifdef __cpp_lib_modules
    ATLAS_ADD(__cpp_lib_modules);
#else
    ATLAS_MISSING(__cpp_lib_modules);
#endif
#undef ATLAS_ADD
#undef ATLAS_MISSING
    for (const Feature& f : features) {
        if (f.value != 0) {
            (void)std::fprintf(out, "%-36s %ld\n", f.name, f.value);
        } else {
            (void)std::fprintf(out, "%-36s -\n", f.name);
        }
    }
}

} // namespace atlas::test

namespace {

enum class ParseError : unsigned char { empty, not_digit };

// std::expected and its monadic operations.
std::expected<int, ParseError> digit(std::string_view s) {
    if (s.empty()) return std::unexpected(ParseError::empty);
    if (s[0] < '0' || s[0] > '9') return std::unexpected(ParseError::not_digit);
    return s[0] - '0';
}

// Deducing this.
struct Counter {
    int n = 0;
    template <class Self>
    auto&& value(this Self&& self) {
        return std::forward<Self>(self).n;
    }
};

// Static operator().
struct Twice {
    static int operator()(int x) { return 2 * x; }
};

} // namespace

TEST_CASE("the C++23 features the core relies on work at run time") {
    CHECK((digit("7").and_then([](int d) { return std::expected<int, ParseError>(d + 1); }) == 8));
    CHECK((digit("x").transform([](int d) { return d * 2; }).error() == ParseError::not_digit));
    CHECK((digit("").or_else([](ParseError) { return std::expected<int, ParseError>(-1); }) == -1));

    std::flat_map<int, int> map{{3, 30}, {1, 10}, {2, 20}};
    CHECK(map.begin()->first == 1);
    CHECK(map.at(2) == 20);

    std::array<float, 12> grid{};
    std::mdspan<float, std::extents<std::size_t, 3, 4>> view(grid.data());
    view[1, 2] = 5.0F;
    CHECK(grid[(1 * 4) + 2] == 5.0F);

    const auto evens =
        std::views::iota(0, 10) | std::views::filter([](int i) { return i % 2 == 0; }) | std::ranges::to<std::vector>();
    CHECK(evens.size() == 5);

    Counter counter{.n = 4};
    CHECK(counter.value() == 4);
    CHECK(Twice{}(21) == 42);
    CHECK(Twice::operator()(4) == 8);

    std::atomic<int> flag{0};
    {
        std::jthread worker([&flag](const std::stop_token& stop) {
            if (!stop.stop_requested()) {
                flag.store(1);
                flag.notify_one();
            }
        });
        flag.wait(0);
    }
    CHECK(flag.load() == 1);

    CHECK(std::byteswap(std::uint32_t{0x01020304}) == 0x04030201U);
    CHECK(std::bit_cast<std::uint32_t>(1.0F) == 0x3F800000U);
    CHECK(std::to_underlying(ParseError::not_digit) == 1);
    CHECK(std::format("{:.3f} {}", 1.5, 42) == "1.500 42");

    std::array<std::byte, 256> buffer{};
    std::pmr::monotonic_buffer_resource resource(buffer.data(), buffer.size(), std::pmr::null_memory_resource());
    std::pmr::vector<int> values({1, 2, 3}, &resource);
    CHECK(values.size() == 3);
}

#if __cplusplus > 202302L
// The C++26 features the NDK already has, adopted with -std=c++2c (plan-vector T3).
template <class... T>
using first_of = T...[0];

int removed(int) = delete ("the core's reason for deleting a function shows in the error");

TEST_CASE("the C++26 features of -std=c++2c work") {
    static_assert(std::same_as<first_of<int, float>, int>);
    auto [a, _] = std::pair{1, 2};
    auto [b, _] = std::pair{3, 4}; // placeholders may repeat
    CHECK(a + b == 4);
#if defined(__cpp_lib_saturation_arithmetic)
    CHECK(std::add_sat<std::uint8_t>(200, 100) == 255);
    CHECK(std::sub_sat<std::uint8_t>(10, 20) == 0);
#endif
}
#endif
