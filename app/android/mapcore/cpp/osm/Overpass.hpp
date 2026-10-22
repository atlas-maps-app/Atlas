// OpenStreetMap's data as Overpass returns it (ENGINE.md step 3: stage P builds the map on the phone from live data):
// the JSON of an `[out:json]` query ending in `out geom` (the renderer's data/regions/<region>/raw.json is one), read
// into its elements in the document's order, each value as Python's json module reads it and the port's loaders take
// it (amsterdam.py's load, regions.py's):
//   - an id or a node reference as an integer (it must fit 64 bits);
//   - a coordinate as float() parses its digits (pymath::decimal_to_double), one written as an integer as int() and
//     then a float (so "-0" is +0.0 and "-0.0" is -0.0);
//   - the tags in their order, as a dict keeps them, and whether an element has any (`e.get('tags')`) or a geometry
//     (`'geometry' in e`).
// The reader takes what stage P reads (each element's type, id, position, tags, nodes, geometry and members), where
// each element lies in the bytes and the data's date, and steps over the rest (bounds, centres, timestamps, versions,
// users, keys it does not know, whatever they hold). It reads
// strict JSON (RFC 8259, no NaN or Infinity, no control characters in strings, well-formed UTF-8) and refuses what
// Overpass never writes and the port could not read alike: a key twice in one object, a null in a geometry, a lone
// surrogate, a number where a string belongs or the other way round. A string without escapes is viewed in place, so
// the document's bytes must outlive what is read from them.
//
// An element holds what it has of variable size (its tags, nodes, points, members and strings) as places in the
// document's arrays and text, which the document gives (Document::tags, nodes, geometry, members, text): ENGINE.md
// step 3's budget for a window's memory. A dense window's data has 62,000 elements and 393,000 tags; as views they
// took 136 bytes an element and 32 a tag, as places 80 and 16.
#pragma once

#include <cstddef>
#include <cstdint>
#include <expected>
#include <functional>
#include <iterator>
#include <memory>
#include <optional>
#include <span>
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

/// A string of the document: where it lies in the document's text, which is its bytes (a string without escapes,
/// viewed in place) followed by the strings that needed decoding.
struct Text {
    std::uint32_t at = 0;
    std::uint32_t size = 0;
};

/// A run of one of the document's arrays (its tags, node references, points or members): the first and how many.
struct Run {
    std::uint32_t first = 0;
    std::uint32_t count = 0;
};

/// One tag.
struct Tag {
    std::string_view key;
    std::string_view value;
};

/// One tag as the document holds it: its key and value in the document's text.
struct HeldTag {
    Text key;
    Text value;
};

/// A relation's member. Its role and points lie in the document.
struct Member {
    std::int64_t ref = 0;
    Text role;
    Run geometry; ///< a way member's points (`out geom`)
    Kind kind = Kind::node;
    bool has_role = false;
    bool has_geometry = false;
};

/// One element of the document. Its tags, nodes, points, members and type lie in the document.
struct Element {
    std::int64_t id = 0;
    LatLon at; ///< a node's position
    Run tags;
    Run nodes;
    Run geometry;
    Run members;
    Text type;              ///< the type as written (another kind's name)
    std::uint32_t from = 0; ///< where its object starts in the document's bytes (its `{`)
    std::uint32_t to = 0;   ///< just past its object's `}`
    Kind kind = Kind::node;
    bool has_id = false;
    bool has_at = false;   ///< both `lat` and `lon`
    bool has_tags = false; ///< `tags` is an object (perhaps empty): `e.get('tags')` is a dict
    bool has_nodes = false;
    bool has_geometry = false; ///< `'geometry' in e`
    bool has_members = false;
};

/// What a document's elements hold, in the document's order: its text (the bytes it was read from and the strings that
/// needed decoding), the tags, node references, points and members, and the data's dates. A document holds it on the
/// heap, so what views it stays valid wherever the document moves.
struct Values {
    std::span<const char> bytes; ///< the document's own, which must outlive it
    std::vector<char> decoded;   ///< the strings that needed decoding, one after another
    std::vector<HeldTag> tags;
    std::vector<std::int64_t> refs;
    std::vector<LatLon> points;
    std::vector<Member> members;
    std::optional<Text> osm_base;
    std::optional<Text> whole;

    [[nodiscard]] std::string_view text(Text t) const noexcept {
        const std::span<const char> s =
            t.at < bytes.size() ? bytes.subspan(t.at, t.size) : std::span(decoded).subspan(t.at - bytes.size(), t.size);
        return {s.data(), s.size()};
    }
    [[nodiscard]] Tag tag(const HeldTag& t) const noexcept { return {.key = text(t.key), .value = text(t.value)}; }
};

/// An element's tags in their order, as a dict keeps them, each read from the document as it is asked for.
class TagList {
public:
    class Iterator {
    public:
        using value_type = Tag;
        using difference_type = std::ptrdiff_t;
        using iterator_concept = std::forward_iterator_tag;

        Iterator() = default;
        Iterator(const Values* values, std::span<const HeldTag>::iterator at) noexcept : values_(values), at_(at) {}

        [[nodiscard]] Tag operator*() const noexcept { return values_->tag(*at_); }
        Iterator& operator++() noexcept {
            ++at_;
            return *this;
        }
        Iterator operator++(int) noexcept {
            const Iterator was = *this;
            ++at_;
            return was;
        }
        [[nodiscard]] bool operator==(const Iterator& o) const noexcept { return at_ == o.at_; }

    private:
        const Values* values_ = nullptr;
        std::span<const HeldTag>::iterator at_;
    };

    TagList() = default;
    TagList(const Values* values, Run run) noexcept : values_(values), run_(run) {}

    [[nodiscard]] Iterator begin() const noexcept { return {values_, held().begin()}; }
    [[nodiscard]] Iterator end() const noexcept { return {values_, held().end()}; }
    [[nodiscard]] std::size_t size() const noexcept { return run_.count; }
    [[nodiscard]] bool empty() const noexcept { return run_.count == 0; }
    [[nodiscard]] Tag operator[](std::size_t i) const noexcept { return values_->tag(held()[i]); }
    [[nodiscard]] Tag back() const noexcept { return values_->tag(held().back()); }
    /// The value of the tag `key` (a document holds each key once), or none.
    [[nodiscard]] std::optional<std::string_view> find(std::string_view key) const noexcept {
        for (const HeldTag& t : held()) {
            if (t.key.size == key.size() && values_->text(t.key) == key) return values_->text(t.value);
        }
        return std::nullopt;
    }

private:
    [[nodiscard]] std::span<const HeldTag> held() const noexcept {
        if (values_ == nullptr) return {};
        return std::span(values_->tags).subspan(run_.first, run_.count);
    }

    const Values* values_ = nullptr;
    Run run_;
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
        too_large,   ///< larger than max_bytes: places in the document's text have 32 bits
    };
    Fault fault = Fault::syntax;
    std::size_t at = 0;
};

/// The fault's name, for logs and tests.
[[nodiscard]] std::string_view name_of(ReadError::Fault fault) noexcept;

/// How deep values may nest, in what the reader steps over.
inline constexpr std::size_t max_depth = 64;

/// The largest document read: its text (its bytes, then at most as many decoded) stays within 32 bits.
inline constexpr std::size_t max_bytes = 0x7FFF'FFFF;

/// A document's elements, and what they hold: views of the document's own bytes, and of its values.
class Document {
public:
    [[nodiscard]] std::span<const Element> elements() const noexcept { return elements_; }
    [[nodiscard]] TagList tags(const Element& e) const noexcept { return {values_.get(), e.tags}; }
    [[nodiscard]] std::span<const std::int64_t> nodes(const Element& e) const noexcept {
        return run(&Values::refs, e.nodes);
    }
    [[nodiscard]] std::span<const LatLon> geometry(const Element& e) const noexcept {
        return run(&Values::points, e.geometry);
    }
    [[nodiscard]] std::span<const Member> members(const Element& e) const noexcept {
        return run(&Values::members, e.members);
    }
    [[nodiscard]] std::span<const LatLon> geometry(const Member& m) const noexcept {
        return run(&Values::points, m.geometry);
    }
    [[nodiscard]] std::string_view text(Text t) const noexcept {
        return values_ ? values_->text(t) : std::string_view();
    }
    /// The data's date, `osm3s.timestamp_osm_base` as Overpass writes it (empty where the document has none).
    [[nodiscard]] std::string_view osm_base() const noexcept {
        return values_ && values_->osm_base ? values_->text(*values_->osm_base) : std::string_view();
    }
    /// For the data a window keeps (osm/Diff.hpp), the date it was last fetched whole: `atlas.whole` (else empty).
    [[nodiscard]] std::string_view whole() const noexcept {
        return values_ && values_->whole ? values_->text(*values_->whole) : std::string_view();
    }

private:
    friend std::expected<Document, ReadError> read_overpass(std::span<const std::byte> json);
    friend Document select(const Document& from, const std::function<bool(const Element&)>& keep);

    template <typename T>
    [[nodiscard]] std::span<const T> run(std::vector<T> Values::* list, Run r) const noexcept {
        if (!values_) return {};
        return std::span((*values_).*list).subspan(r.first, r.count);
    }

    std::vector<Element> elements_;
    std::shared_ptr<const Values> values_; ///< a selection shares its document's
};

/// The document's elements, or why it cannot be read. The document's bytes must outlive the result.
[[nodiscard]] std::expected<Document, ReadError> read_overpass(std::span<const std::byte> json);

/// The elements of `from` that `keep` keeps, in their order: what a query of a smaller box returns from a region's data
/// (a window's, load/Window.hpp). They share `from`'s values, so only `from`'s bytes must outlive the result.
[[nodiscard]] Document select(const Document& from, const std::function<bool(const Element&)>& keep);

} // namespace atlas::osm
