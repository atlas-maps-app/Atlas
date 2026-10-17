// Typed values (rendertiles' `Values`, plan-vector Appendix A): what the port's dicts hold, read without losing a type.
// They carry the VALUE columns of a tile (`extra`: the loader's other fields), the cameras of `rhead`'s views and the
// tables of the `style` section.
//
// The coding: a type byte, then 0 null; 1 false; 2 true; 3 an Int (a zig-zag varint within 32 bits); 4 a Long (a
// zig-zag varint); 5 a Double (8 bytes, its bits, little-endian); 6 a string; 7 a list (a varint count, the values); 8
// a map with string keys (a varint count, then each key and its value). Inside a tile a string is a varint index into
// the tile's string table; in `rhead` and `style` it is written out (a varint length and UTF-8). Nesting stops at 33
// levels, as the Kotlin reader's does.
//
// A decoded value is a tree of nodes in one array: a list's or a map's children are consecutive, so a value is read
// without pointers and copied as plain data.
#pragma once

#include "tiles/Bytes.hpp"

#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::tiles {

/// A value's type, as its type byte says it.
enum class ValueType : std::uint8_t {
    null = 0,
    boolean_false = 1,
    boolean_true = 2,
    int32 = 3,
    int64 = 4,
    real = 5,
    string = 6,
    list = 7,
    map = 8,
};

/// One node of a value tree.
struct ValueNode {
    ValueType type = ValueType::null;
    /// A map entry: its key's index in the pool's strings.
    std::uint32_t key = 0;
    /// A list or map: its first child's node. A string: its index in the pool's strings.
    std::uint32_t first = 0;
    /// A list or map: how many children.
    std::uint32_t count = 0;
    /// An integer as two's complement, or a double's bits.
    std::uint64_t bits = 0;
};

/// How deep a value may nest: the root is at depth 0, and a value deeper than this is refused.
inline constexpr unsigned max_value_depth = 32;

/// A value inside a pool of nodes and strings.
class Value {
public:
    Value() noexcept = default;
    Value(std::span<const ValueNode> nodes, std::span<const std::string_view> strings, std::uint32_t node) noexcept
        : nodes_(nodes), strings_(strings), node_(node) {}

    [[nodiscard]] ValueType type() const noexcept { return nodes_.empty() ? ValueType::null : at().type; }
    [[nodiscard]] bool is_null() const noexcept { return type() == ValueType::null; }

    /// A boolean, or nothing for another type.
    [[nodiscard]] std::optional<bool> boolean() const noexcept;
    /// An Int or a Long, or nothing for another type.
    [[nodiscard]] std::optional<std::int64_t> integer() const noexcept;
    /// A Double, or nothing for another type (an integer is not converted).
    [[nodiscard]] std::optional<double> real() const noexcept;
    /// A number of any type as a double (an integer converted as Python would), or nothing.
    [[nodiscard]] std::optional<double> number() const noexcept;
    /// A string, or nothing for another type.
    [[nodiscard]] std::optional<std::string_view> string() const noexcept;

    /// A list's or a map's number of children (0 for anything else).
    [[nodiscard]] std::size_t size() const noexcept;
    /// A list's element or a map's value, by position.
    [[nodiscard]] Value child(std::size_t i) const noexcept;
    /// A map's key, by position.
    [[nodiscard]] std::string_view key(std::size_t i) const noexcept;
    /// A map's value for `key`, or nothing (maps are small: a scan).
    [[nodiscard]] std::optional<Value> find(std::string_view name) const noexcept;

    [[nodiscard]] std::span<const ValueNode> nodes() const noexcept { return nodes_; }
    [[nodiscard]] std::span<const std::string_view> strings() const noexcept { return strings_; }
    [[nodiscard]] std::uint32_t node() const noexcept { return node_; }

private:
    [[nodiscard]] const ValueNode& at() const noexcept { return nodes_[node_]; }

    std::span<const ValueNode> nodes_;
    std::span<const std::string_view> strings_;
    std::uint32_t node_ = 0;
};

/// Reads values into a pool: nodes, and for written-out strings the strings themselves.
class ValueReader {
public:
    /// Values whose strings index `table` (a tile's), or, when it is empty and `inline_strings` is true, are written
    /// out.
    ValueReader(std::span<const std::string_view> table, bool inline_strings) noexcept
        : table_(table), inline_(inline_strings) {}

    /// Reads one value from `r` into the pool and returns its node, or the fault (also recorded in `r`).
    [[nodiscard]] Result<std::uint32_t> read(Reader& r);

    [[nodiscard]] std::span<const ValueNode> nodes() const noexcept { return nodes_; }
    /// The strings the pool's nodes index: the table, or the written-out strings read so far.
    [[nodiscard]] std::span<const std::string_view> strings() const noexcept {
        return inline_ ? std::span<const std::string_view>(inline_table_) : table_;
    }

    /// Takes the nodes (and written-out strings) read so far; the reader starts empty again.
    [[nodiscard]] std::vector<ValueNode> take_nodes() noexcept;
    [[nodiscard]] std::vector<std::string_view> take_strings() noexcept;

    /// Forgets every value read (keeps the memory).
    void clear() noexcept;

    /// Forgets every value read and reads strings from `table` (or written out) from now on.
    void reset(std::span<const std::string_view> table, bool inline_strings) noexcept;

private:
    [[nodiscard]] bool read_into(Reader& r, std::uint32_t slot, unsigned depth);
    [[nodiscard]] bool read_string(Reader& r, std::uint32_t& index);
    [[nodiscard]] bool unique_keys(std::uint32_t first, std::uint32_t count);

    std::span<const std::string_view> table_;
    bool inline_ = false;
    std::vector<ValueNode> nodes_;
    std::vector<std::string_view> inline_table_;
    std::vector<std::string_view> keys_scratch_;
};

} // namespace atlas::tiles
