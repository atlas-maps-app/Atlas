// A value hidden from the optimiser, so a select written in Python's order stays that select. clang (Apple's 21 and the
// NDK r30's, from -O1 up) folds `b < a ? b : a` and its mirror images into arm64's fminnm and fmaxnm and x86_64's minss
// and maxss, which order -0.0 and +0.0, and NaN, by their own rules (third_party/VERSIONS.md, "Compiler facts"); an
// opaque condition keeps the comparison and the select apart. The barrier is an empty asm: no instruction is emitted.
#pragma once

#include <type_traits>

namespace atlas {

namespace detail {

/// Whether a SIMD register holds a clang vector of this type: arm64's take 64 and 128 bits, x86_64's SSE ones 128;
/// none takes three lanes.
template <class T>
inline constexpr bool fits_vector_register =
#if defined(__aarch64__)
    __builtin_vectorelements(T) != 3 && (sizeof(T) == 8 || sizeof(T) == 16);
#elif defined(__x86_64__) || defined(__i386__)
    __builtin_vectorelements(T) != 3 && sizeof(T) == 16;
#else
    false;
#endif

template <class T>
inline void hide_in_general_register(T& v) noexcept {
    asm("" : "+r"(v));
}

template <class T>
inline void hide_in_vector_register(T& v) noexcept {
#if defined(__aarch64__)
    asm("" : "+w"(v));
#elif defined(__x86_64__) || defined(__i386__)
    asm("" : "+x"(v));
#endif
}

template <class T>
inline void hide_in_memory(T& v) noexcept {
    asm("" : "+m"(v));
}

} // namespace detail

/// `v` unchanged, but unknown to the optimiser: a bool, an integer, or a clang vector (a comparison's lane mask).
template <class T>
    requires std::is_trivially_copyable_v<T>
[[nodiscard]] inline T opaque(T v) noexcept {
    if constexpr (std::is_scalar_v<T>) {
        detail::hide_in_general_register(v);
    } else if constexpr (detail::fits_vector_register<T>) {
        detail::hide_in_vector_register(v);
    } else {
        detail::hide_in_memory(v); // through memory: slower, but no register class takes it
    }
    return v;
}

} // namespace atlas
