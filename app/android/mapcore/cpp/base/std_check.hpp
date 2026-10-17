// The C++ the core is written in (plan-vector T3), checked when it compiles: every language and library feature the
// core relies on, and the floating point it assumes. A toolchain without one of them fails to build here, with the
// reason, instead of failing at run time. base/assert.hpp includes it, so every core translation unit checks it.
#pragma once

#include <cfloat>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <version>

#if !defined(__clang__)
#error "the core is built with clang only: the NDK's on Android, Apple's on macOS, LLVM's on Linux (plan-vector T4)"
#endif

#if __cplusplus < 202302L
#error "the core is C++23 or newer (plan-vector T3); build it with -std=c++23 or -std=c++2c"
#endif

#if defined(__cpp_exceptions) || defined(__EXCEPTIONS)
#error "the core is built without exceptions (-fno-exceptions): errors are values, invariants abort (plan-vector T3)"
#endif

#if defined(__GXX_RTTI) || __has_feature(cxx_rtti)
#error "the core is built without RTTI (-fno-rtti) (plan-vector T3)"
#endif

#if defined(__FAST_MATH__) || (defined(__FINITE_MATH_ONLY__) && __FINITE_MATH_ONLY__)
#error "fast math changes the port's bits; the core never uses it (plan-vector T3)"
#endif

// Language features.
#if !defined(__cpp_concepts) || __cpp_concepts < 202002L
#error "concepts (C++20) are required"
#endif
#if !defined(__cpp_consteval) || __cpp_consteval < 201811L
#error "consteval (C++20) is required"
#endif
#if !defined(__cpp_if_consteval) || __cpp_if_consteval < 202106L
#error "if consteval (C++23) is required"
#endif
#if !defined(__cpp_explicit_this_parameter) || __cpp_explicit_this_parameter < 202110L
#error "deducing this (C++23) is required"
#endif
#if !defined(__cpp_multidimensional_subscript) || __cpp_multidimensional_subscript < 202110L
#error "the multidimensional subscript operator (C++23) is required"
#endif
#if !defined(__cpp_static_call_operator) || __cpp_static_call_operator < 202207L
#error "static operator() (C++23) is required"
#endif
#if !defined(__cpp_designated_initializers) || __cpp_designated_initializers < 201707L
#error "designated initializers (C++20) are required"
#endif
#if !defined(__cpp_size_t_suffix) || __cpp_size_t_suffix < 202011L
#error "the uz literal suffix (C++23) is required"
#endif

// Library features, with the revision the core needs.
#if !defined(__cpp_lib_expected) || __cpp_lib_expected < 202211L
#error "std::expected with and_then, transform and or_else (C++23) is required"
#endif
#if !defined(__cpp_lib_span) || __cpp_lib_span < 202002L
#error "std::span (C++20) is required"
#endif
#if !defined(__cpp_lib_mdspan) || __cpp_lib_mdspan < 202207L
#error "std::mdspan (C++23) is required"
#endif
#if !defined(__cpp_lib_flat_map) || __cpp_lib_flat_map < 202207L
#error "std::flat_map (C++23) is required"
#endif
#if !defined(__cpp_lib_ranges_to_container) || __cpp_lib_ranges_to_container < 202202L
#error "std::ranges::to (C++23) is required"
#endif
#if !defined(__cpp_lib_jthread) || __cpp_lib_jthread < 201911L
#error "std::jthread and std::stop_token (C++20) are required"
#endif
#if !defined(__cpp_lib_atomic_wait) || __cpp_lib_atomic_wait < 201907L
#error "std::atomic::wait and notify (C++20) are required"
#endif
#if !defined(__cpp_lib_byteswap) || __cpp_lib_byteswap < 202110L
#error "std::byteswap (C++23) is required"
#endif
#if !defined(__cpp_lib_bit_cast) || __cpp_lib_bit_cast < 201806L
#error "std::bit_cast (C++20) is required"
#endif
#if !defined(__cpp_lib_bitops) || __cpp_lib_bitops < 201907L
#error "<bit>'s bit operations (C++20) are required"
#endif
#if !defined(__cpp_lib_to_underlying) || __cpp_lib_to_underlying < 202102L
#error "std::to_underlying (C++23) is required"
#endif
#if !defined(__cpp_lib_unreachable) || __cpp_lib_unreachable < 202202L
#error "std::unreachable (C++23) is required"
#endif
#if !defined(__cpp_lib_memory_resource) || __cpp_lib_memory_resource < 201603L
#error "std::pmr memory resources (C++17) are required for the arenas"
#endif
#if !defined(__cpp_lib_source_location) || __cpp_lib_source_location < 201907L
#error "std::source_location (C++20) is required"
#endif
#if !defined(__cpp_lib_format)
#error "std::format (C++20) is required for the log"
#endif
#if !defined(__cpp_lib_concepts) || __cpp_lib_concepts < 202002L
#error "the standard concepts (C++20) are required"
#endif
#if !defined(__cpp_lib_make_unique) || !defined(__cpp_lib_smart_ptr_for_overwrite)
#error "std::make_unique_for_overwrite (C++20) is required"
#endif

// The vector extensions the SIMD lanes and the shaders' C++ build stand on (base/simd.hpp, base/hlsl.hpp).
#if !__has_attribute(ext_vector_type)
#error "clang's ext_vector_type is required"
#endif
#if !__has_builtin(__builtin_elementwise_fma) || !__has_builtin(__builtin_elementwise_max) ||                          \
    !__has_builtin(__builtin_elementwise_floor) || !__has_builtin(__builtin_convertvector) ||                          \
    !__has_builtin(__builtin_shufflevector)
#error "clang's elementwise vector builtins are required"
#endif

namespace atlas::std_check {

// The machine the core assumes: 64-bit, 8-bit bytes, little-endian, IEEE 754 binary32 and binary64 evaluated in their
// own precision (no x87 excess precision), rounding to nearest. The port's bits depend on all of it.
static_assert(CHAR_BIT == 8);
static_assert(sizeof(void*) == 8, "64-bit ABIs only: arm64-v8a and x86_64 (plan-vector T3)");
static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "the packs and the GPU buffers are little-endian");
static_assert(std::numeric_limits<float>::is_iec559 && std::numeric_limits<double>::is_iec559);
static_assert(std::numeric_limits<float>::digits == 24 && std::numeric_limits<double>::digits == 53);
static_assert(FLT_EVAL_METHOD == 0, "every operation must round to its own type");
static_assert(std::numeric_limits<float>::round_style == std::round_to_nearest);
static_assert(sizeof(long long) == 8 && sizeof(int) == 4 && sizeof(std::size_t) == 8);

} // namespace atlas::std_check
