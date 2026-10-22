// Typed values written out (tiles/ValueWriter.hpp).
#include "tiles/ValueWriter.hpp"

#include <limits>

namespace atlas::tiles {

void ValueSink::integer(std::int64_t v) {
    if (v >= std::numeric_limits<std::int32_t>::min() && v <= std::numeric_limits<std::int32_t>::max()) {
        int32(static_cast<std::int32_t>(v));
    } else {
        int64(v);
    }
}

void ValueSink::int32(std::int32_t v) {
    out_.u8(static_cast<std::uint8_t>(ValueType::int32));
    out_.varz(v);
}

void ValueSink::int64(std::int64_t v) {
    out_.u8(static_cast<std::uint8_t>(ValueType::int64));
    out_.varz(v);
}

void ValueSink::real(double v) {
    out_.u8(static_cast<std::uint8_t>(ValueType::real));
    out_.f64(v);
}

void ValueSink::string(std::string_view v) {
    out_.u8(static_cast<std::uint8_t>(ValueType::string));
    out_.utf8(v);
}

void ValueSink::list(std::size_t count) {
    out_.u8(static_cast<std::uint8_t>(ValueType::list));
    out_.varu(count);
}

void ValueSink::map(std::size_t count) {
    out_.u8(static_cast<std::uint8_t>(ValueType::map));
    out_.varu(count);
}

void ValueSink::strings(std::span<const std::string_view> v) {
    list(v.size());
    for (const std::string_view s : v) string(s);
}

void ValueSink::reals(std::span<const double> v) {
    list(v.size());
    for (const double d : v) real(d);
}

void ValueSink::value(const Value& v) {
    switch (v.type()) {
    case ValueType::null: null(); return;
    case ValueType::boolean_false: boolean(false); return;
    case ValueType::boolean_true: boolean(true); return;
    case ValueType::int32: int32(static_cast<std::int32_t>(v.integer().value_or(0))); return;
    case ValueType::int64: int64(v.integer().value_or(0)); return;
    case ValueType::real: real(v.real().value_or(0.0)); return;
    case ValueType::string: string(v.string().value_or(std::string_view{})); return;
    case ValueType::list:
        list(v.size());
        for (std::size_t i = 0; i < v.size(); ++i) value(v.child(i));
        return;
    case ValueType::map:
        map(v.size());
        for (std::size_t i = 0; i < v.size(); ++i) {
            key(v.key(i));
            value(v.child(i));
        }
        return;
    }
}

} // namespace atlas::tiles
