// OpenStreetMap's data as Overpass returns it (ENGINE.md step 3: stage P builds the map on the phone from live data):
// the JSON of an `[out:json]` query ending in `out geom` (the renderer's data/regions/<region>/raw.json is one), read
// into its elements in the document's order, each value as Python's json module reads it and the port's loaders take
// it (amsterdam.py's load, regions.py's):
//   - an id or a node reference as an integer (it must fit 64 bits);
//   - a coordinate as float() parses its digits (pymath::decimal_to_double), one written as an integer as int() and
//     then a float (so "-0" is +0.0 and "-0.0" is -0.0);
//   - the tags in their order, as a dict keeps them, and whether an element has any (`e.get('tags')`) or a geometry
//     (`'geometry' in e`).
// The reader takes what stage P reads (each element's type, id, position, tags, nodes, geometry and members) and steps
// over the rest (bounds, centres, timestamps, versions, users, keys it does not know, whatever they hold). It reads
// strict JSON (RFC 8259, no NaN or Infinity, no control characters in strings, well-formed UTF-8) and refuses what
// Overpass never writes and the port could not read alike: a key twice in one object, a null in a geometry, a lone
// surrogate, a number where a string belongs or the other way round. A string without escapes is viewed in place, so
// the document's bytes must outlive what is read from them.
#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <expected>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::osm {

/// What an element is: a node, a way, a relation, or another type Overpass may list (an area, a count), kept by name.
enum class Kind : std::uint8_t { node, way, relation, other };

/// A position as Overpass prints it, degrees.
struct LatLon {
    double lat = 0.0;
    double lon = 0.0;
};

/// One tag.
struct Tag {
    std::string_view key;
    std::string_view value;
};

/// A relation's member.
struct Member {
    Kind kind = Kind::node;
    std::int64_t ref = 0;
    std::string_view role;
    std::span<const LatLon> geometry; ///< a way member's points (`out geom`)
    bool has_role = false;
    bool has_geometry = false;
};

/// One element of the document.
struct Element {
    Kind kind = Kind::node;
    std::string_view type; ///< the type as written (another kind's name)
    std::int64_t id = 0;
    LatLon at; ///< a node's position
    std::span<const Tag> tags;
    std::span<const std::int64_t> nodes;
    std::span<const LatLon> geometry;
    std::span<const Member> members;
    bool has_id = false;
    bool has_at = false;   ///< both `lat` and `lon`
    bool has_tags = false; ///< `tags` is an object (perhaps empty): `e.get('tags')` is a dict
    bool has_nodes = false;
    bool has_geometry = false; ///< `'geometry' in e`
    bool has_members = false;
};

/// Why a document cannot be read, and the byte where that was found.
struct ReadError {
    enum class Fault : std::uint8_t {
        truncated,   ///< the document ends inside a value
        syntax,      ///< not JSON
        bad_utf8,    ///< a string that is not UTF-8, or a lone surrogate
        bad_number,  ///< an id or a reference that is not an integer of 64 bits
        bad_type,    ///< a value of another type than its key takes
        duplicate,   ///< a key twice in one object
        too_deep,    ///< values nested past max_depth
        unsupported, ///< valid JSON the port could not read alike (a null in a geometry)
        no_elements, ///< no `elements` array
    };
    Fault fault = Fault::syntax;
    std::size_t at = 0;
};

/// The fault's name, for logs and tests.
[[nodiscard]] std::string_view name_of(ReadError::Fault fault) noexcept;

/// How deep values may nest, in what the reader steps over.
inline constexpr std::size_t max_depth = 64;

/// A document's elements, and what they view: the document's own bytes, and the strings that needed decoding.
class Document {
public:
    [[nodiscard]] std::span<const Element> elements() const noexcept { return elements_; }

private:
    friend std::expected<Document, ReadError> read_overpass(std::span<const std::byte> json);

    std::vector<Element> elements_;
    std::vector<Tag> tags_;
    std::vector<std::int64_t> refs_;
    std::vector<LatLon> points_;
    std::vector<Member> members_;
    std::deque<std::string> decoded_; ///< never moved, so the views into them stay valid
};

/// The document's elements, or why it cannot be read. The document's bytes must outlive the result.
[[nodiscard]] std::expected<Document, ReadError> read_overpass(std::span<const std::byte> json);

} // namespace atlas::osm
