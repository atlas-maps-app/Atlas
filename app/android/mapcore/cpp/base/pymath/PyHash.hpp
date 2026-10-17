// CPython's hash() on 64-bit builds with PYTHONHASHSEED=0, as the port computes it (R/py/PyHash.kt): ints, floats,
// str, bytes and tuples. The renders seed their random streams from hashes of tuples of floats (and of a str with
// floats), so these must be CPython's to the bit.
#pragma once

#include "base/pymath/PyMath.hpp"

#include <concepts>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>
#include <type_traits>

namespace atlas::pymath {

/// `hash(n)` for an int.
[[nodiscard]] std::int64_t hash_int(std::int64_t n) noexcept;

/// `hash(n)` for a non-negative int up to 2^64 - 1.
[[nodiscard]] std::int64_t hash_unsigned(std::uint64_t n) noexcept;

/// `hash(x)` for a float: its value reduced modulo 2^61 - 1, so integral floats hash like ints; NaN has no
/// reproducible hash (since 3.10 it is the object's address), as in the port.
[[nodiscard]] Result<std::int64_t> hash_float(double x) noexcept;

/// `hash(b)` for bytes: SipHash with the all-zero key of PYTHONHASHSEED=0 (SipHash-2-4 up to 3.10, -1-3 from 3.11).
[[nodiscard]] std::int64_t hash_bytes(std::span<const std::byte> bytes, Compat compat = renders) noexcept;

/// `hash(s)` for a str given as UTF-8: SipHash over CPython's own representation of its code points (one, two or four
/// bytes each, by the largest). Text that is not UTF-8 was never a Python str.
[[nodiscard]] Result<std::int64_t> hash_str(std::string_view utf8, Compat compat = renders) noexcept;

/// `hash(t)` for a tuple, built item by item from the items' own hashes (CPython 3.8 and later, xxHash's mixing).
class TupleHash {
public:
    /// Adds an item by its hash.
    constexpr void add_hash(std::int64_t lane) noexcept {
        acc_ += static_cast<std::uint64_t>(lane) * prime_2;
        acc_ = (acc_ << 31U) | (acc_ >> 33U);
        acc_ *= prime_1;
        ++length_;
    }

    /// The tuple's hash so far.
    [[nodiscard]] constexpr std::int64_t value() const noexcept {
        const std::uint64_t acc = acc_ + (static_cast<std::uint64_t>(length_) ^ (prime_5 ^ 3527539U));
        return acc == ~std::uint64_t{0} ? 1546275796 : static_cast<std::int64_t>(acc);
    }

private:
    static constexpr std::uint64_t prime_1 = 11400714785074694791U;
    static constexpr std::uint64_t prime_2 = 14029467366897019727U;
    static constexpr std::uint64_t prime_5 = 2870177450012600261U;

    std::uint64_t acc_ = prime_5;
    std::size_t length_ = 0;
};

/// A Python value a tuple of the renders holds: an int, a float or a str.
template <class T>
concept TupleItem = std::same_as<std::remove_cvref_t<T>, double> || std::same_as<std::remove_cvref_t<T>, bool> ||
                    std::signed_integral<std::remove_cvref_t<T>> || std::convertible_to<T, std::string_view>;

/// `hash((a, b, ...))` of ints, floats and strs, as Python sees each C++ value: an integer type is an int, a double a
/// float, text a str. Fails as hash() would: on a NaN, or on text that is not UTF-8.
template <TupleItem... Items>
[[nodiscard]] Result<std::int64_t> hash_tuple(Compat compat, const Items&... items) noexcept {
    TupleHash h;
    Result<std::int64_t> lane = 0;
    const auto add = [&]<class Item>(const Item& item) {
        if (!lane.has_value()) {
            return; // an earlier item could not be hashed
        }
        using T = std::remove_cvref_t<Item>;
        if constexpr (std::same_as<T, double>) {
            lane = hash_float(item);
        } else if constexpr (std::same_as<T, bool>) {
            lane = item ? 1 : 0;
        } else if constexpr (std::signed_integral<T>) {
            lane = hash_int(static_cast<std::int64_t>(item));
        } else {
            lane = hash_str(std::string_view(item), compat);
        }
        if (lane.has_value()) {
            h.add_hash(*lane);
        }
    };
    (add(items), ...);
    if (!lane.has_value()) {
        return lane;
    }
    return h.value();
}

/// `hash((a, b, ...))` with the renders' Python; where hash() would raise (a NaN, text that is not UTF-8) the process
/// ends, as the render would have.
template <TupleItem... Items>
[[nodiscard]] std::int64_t hash_tuple(const Items&... items) noexcept {
    return value_or_fail(hash_tuple(renders, items...), std::source_location::current());
}

} // namespace atlas::pymath
