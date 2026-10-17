#include "tiles/Values.hpp"

#include <algorithm>
#include <bit>
#include <limits>
#include <utility>

namespace atlas::tiles {

std::optional<bool> Value::boolean() const noexcept {
    switch (type()) {
    case ValueType::boolean_false: return false;
    case ValueType::boolean_true: return true;
    default: return std::nullopt;
    }
}

std::optional<std::int64_t> Value::integer() const noexcept {
    const ValueType t = type();
    if (t != ValueType::int32 && t != ValueType::int64) return std::nullopt;
    return static_cast<std::int64_t>(at().bits);
}

std::optional<double> Value::real() const noexcept {
    if (type() != ValueType::real) return std::nullopt;
    return std::bit_cast<double>(at().bits);
}

std::optional<double> Value::number() const noexcept {
    if (const auto i = integer()) return static_cast<double>(*i);
    return real();
}

std::optional<std::string_view> Value::string() const noexcept {
    if (type() != ValueType::string) return std::nullopt;
    return strings_[at().first];
}

std::size_t Value::size() const noexcept {
    const ValueType t = type();
    return t == ValueType::list || t == ValueType::map ? at().count : 0;
}

Value Value::child(std::size_t i) const noexcept {
    return {nodes_, strings_, at().first + static_cast<std::uint32_t>(i)};
}

std::string_view Value::key(std::size_t i) const noexcept { return strings_[nodes_[at().first + i].key]; }

std::optional<Value> Value::find(std::string_view name) const noexcept {
    if (type() != ValueType::map) return std::nullopt;
    for (std::size_t i = 0; i < at().count; ++i) {
        if (key(i) == name) return child(i);
    }
    return std::nullopt;
}

Result<std::uint32_t> ValueReader::read(Reader& r) {
    const auto slot = static_cast<std::uint32_t>(nodes_.size());
    nodes_.emplace_back();
    if (!read_into(r, slot, 0)) return std::unexpected(error(r.ok() ? Fault::bad_value : r.fault(), Site::value));
    return slot;
}

std::vector<ValueNode> ValueReader::take_nodes() noexcept { return std::exchange(nodes_, {}); }

std::vector<std::string_view> ValueReader::take_strings() noexcept { return std::exchange(inline_table_, {}); }

void ValueReader::clear() noexcept {
    nodes_.clear();
    inline_table_.clear();
}

void ValueReader::reset(std::span<const std::string_view> table, bool inline_strings) noexcept {
    clear();
    table_ = table;
    inline_ = inline_strings;
}

bool ValueReader::read_string(Reader& r, std::uint32_t& index) {
    if (inline_) {
        const std::string_view s = r.utf8();
        if (!r.ok()) return false;
        index = static_cast<std::uint32_t>(inline_table_.size());
        inline_table_.push_back(s);
        return true;
    }
    const std::uint32_t i = r.count();
    if (!r.ok()) return false;
    if (i >= table_.size()) {
        r.fail(Fault::bad_value);
        return false;
    }
    index = i;
    return true;
}

bool ValueReader::unique_keys(std::uint32_t first, std::uint32_t count) {
    const std::span<const std::string_view> strings = this->strings();
    auto key = [&](std::uint32_t i) { return strings[nodes_[first + i].key]; };
    if (count <= 16) {
        for (std::uint32_t i = 1; i < count; ++i) {
            for (std::uint32_t j = 0; j < i; ++j) {
                if (key(i) == key(j)) return false;
            }
        }
        return true;
    }
    keys_scratch_.clear();
    for (std::uint32_t i = 0; i < count; ++i) keys_scratch_.push_back(key(i));
    std::ranges::sort(keys_scratch_);
    return std::ranges::adjacent_find(keys_scratch_) == keys_scratch_.end();
}

bool ValueReader::read_into(Reader& r, std::uint32_t slot, unsigned depth) {
    if (depth > max_value_depth) {
        r.fail(Fault::too_big);
        return false;
    }
    const std::uint8_t t = r.u8();
    if (!r.ok()) return false;
    ValueNode node;
    switch (t) {
    case 0: node.type = ValueType::null; break;
    case 1: node.type = ValueType::boolean_false; break;
    case 2: node.type = ValueType::boolean_true; break;
    case 3: {
        const std::int64_t v = r.varz();
        if (!r.ok()) return false;
        if (v < std::numeric_limits<std::int32_t>::min() || v > std::numeric_limits<std::int32_t>::max()) {
            r.fail(Fault::overflow);
            return false;
        }
        node.type = ValueType::int32;
        node.bits = static_cast<std::uint64_t>(v);
        break;
    }
    case 4:
        node.type = ValueType::int64;
        node.bits = static_cast<std::uint64_t>(r.varz());
        break;
    case 5:
        node.type = ValueType::real;
        node.bits = r.u64le(); // the double's exact bits
        break;
    case 6:
        node.type = ValueType::string;
        if (!read_string(r, node.first)) return false;
        break;
    case 7:
    case 8: {
        const std::uint32_t n = r.count();
        if (!r.ok()) return false;
        if (n > r.remaining()) { // every child takes a byte at least
            r.fail(Fault::bad_count);
            return false;
        }
        const auto first = static_cast<std::uint32_t>(nodes_.size());
        nodes_.resize(nodes_.size() + n);
        node.type = t == 7 ? ValueType::list : ValueType::map;
        node.first = first;
        node.count = n;
        node.key = nodes_[slot].key;
        nodes_[slot] = node;
        for (std::uint32_t i = 0; i < n; ++i) {
            std::uint32_t key = 0;
            if (t == 8 && !read_string(r, key)) return false;
            if (!read_into(r, first + i, depth + 1)) return false;
            nodes_[first + i].key = key;
        }
        if (t == 8 && !unique_keys(first, n)) {
            r.fail(Fault::duplicate);
            return false;
        }
        return true;
    }
    default: r.fail(Fault::bad_value); return false;
    }
    if (!r.ok()) return false;
    node.key = nodes_[slot].key;
    nodes_[slot] = node;
    return true;
}

} // namespace atlas::tiles
