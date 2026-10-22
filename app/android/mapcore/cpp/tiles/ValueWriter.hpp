// Typed values written out (rendertiles' Values.write with inline strings; tiles/Values.hpp reads them): the cameras of
// `rhead`'s views and the tables of `style`, which stage P writes for the packs and windows the core builds
// (tiles/SectionWriter.hpp). A list or a map is announced with its size and its children follow, a map's each after its
// key; an integer is an Int where it fits 32 bits and a Long past them, as the port's Python ints are typed, unless a
// value read from a pack or a document says which it is (value() copies its type).
#pragma once

#include "tiles/Sink.hpp"
#include "tiles/Values.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::tiles {

/// Writes typed values into a sink, strings written out.
class ValueSink {
public:
    explicit ValueSink(Sink& out) noexcept : out_(out) {}

    void null() { out_.u8(static_cast<std::uint8_t>(ValueType::null)); }
    void boolean(bool v) { out_.u8(static_cast<std::uint8_t>(v ? ValueType::boolean_true : ValueType::boolean_false)); }
    /// An Int where `v` fits 32 bits, else a Long (Python's int, typed as PyJson types it).
    void integer(std::int64_t v);
    void int32(std::int32_t v);
    void int64(std::int64_t v);
    void real(double v);
    void string(std::string_view v);
    /// A list of `count` values: the values follow.
    void list(std::size_t count);
    /// A map of `count` entries: each key() and its value follow.
    void map(std::size_t count);
    /// A map entry's key.
    void key(std::string_view k) { out_.utf8(k); }

    /// A list of strings; a list of doubles.
    void strings(std::span<const std::string_view> v);
    void reals(std::span<const double> v);

    /// A value as it was read (a pack's, or a JSON document's), each node of its type.
    void value(const Value& v);

private:
    Sink& out_;
};

} // namespace atlas::tiles
