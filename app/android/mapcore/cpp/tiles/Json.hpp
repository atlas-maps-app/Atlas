// A JSON document as typed values (tiles/Values.hpp), each as Python's json module reads it and the port's PyJson gives
// it: an integer as an int (an Int where it fits 32 bits, else a Long), a number with a point or an exponent as a float
// (correctly rounded, pymath::decimal_to_double), the keys of an object in their order. The style section of a pack
// carries such a tree (rendertiles' Style: the region's building tradition, `plain(PyJson.loads(text))`), and stage P
// reads a tradition's own file into one (load/Tradition.hpp).
//
// It reads strict JSON (RFC 8259: no NaN or Infinity, no control characters in strings, well-formed UTF-8, nothing
// after the value but white space) and refuses, by name, what Python reads but the core keeps out: a key twice in one
// object (Python keeps the last value in the first place), a lone surrogate, an integer past 64 bits (Python's are
// unbounded), values nested deeper than the values' coding allows (max_value_depth). Asked to, it also takes comments
// where white space may stand, as the packer reads the engine's style file (kotlinx's allowComments: `//` to the line's
// end, `/* */`).
#pragma once

#include "tiles/Values.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::tiles {

struct JsonOptions;

/// Why a document cannot be read, and the byte where that was found.
struct JsonError {
    enum class Fault : std::uint8_t {
        truncated,  ///< the document ends inside a value
        syntax,     ///< not JSON, or something after the value
        bad_utf8,   ///< a string that is not UTF-8, or a lone surrogate
        bad_number, ///< an integer past 64 bits
        duplicate,  ///< a key twice in one object
        too_deep,   ///< values nested past max_value_depth
    };
    Fault fault = Fault::syntax;
    std::size_t at = 0;
};

/// The fault's name, for logs and tests.
[[nodiscard]] std::string_view name_of(JsonError::Fault fault) noexcept;

/// A document's value tree, owning its nodes and its strings (decoded). It moves without copying what its values view;
/// it does not copy.
class JsonValue {
public:
    JsonValue() = default;
    JsonValue(const JsonValue&) = delete;
    JsonValue& operator=(const JsonValue&) = delete;
    JsonValue(JsonValue&&) noexcept = default;
    JsonValue& operator=(JsonValue&&) noexcept = default;
    ~JsonValue() = default;

    /// The document's value (null for an empty tree).
    [[nodiscard]] Value root() const noexcept { return nodes_.empty() ? Value() : Value(nodes_, strings_, root_); }

private:
    friend std::expected<JsonValue, JsonError> read_json(std::span<const std::byte> json, JsonOptions options);

    std::vector<ValueNode> nodes_;
    std::vector<char> text_;                ///< every string's bytes, one after another
    std::vector<std::string_view> strings_; ///< views into text_, made once it stops growing
    std::uint32_t root_ = 0;
};

/// What a document may hold besides strict JSON.
struct JsonOptions {
    bool comments = false; ///< `//` and `/* */` comments where white space may stand (JSON with comments)
};

/// The document's value, or why it cannot be read.
[[nodiscard]] std::expected<JsonValue, JsonError> read_json(std::span<const std::byte> json, JsonOptions options = {});

} // namespace atlas::tiles
