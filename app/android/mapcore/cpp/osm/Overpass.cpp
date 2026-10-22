// OpenStreetMap's data as Overpass returns it (osm/Overpass.hpp).
#include "osm/Overpass.hpp"

#include "base/pymath/PyMath.hpp"
#include "tiles/Bytes.hpp"

#include <algorithm>
#include <array>
#include <limits>
#include <optional>

namespace atlas::osm {

namespace {

using Fault = ReadError::Fault;

/// A run of one of the document's flat arrays, by index while they still grow.
struct Range {
    std::size_t first = 0;
    std::size_t count = 0;
};

struct RawMember {
    Kind kind = Kind::node;
    std::int64_t ref = 0;
    std::string_view role;
    Range geometry;
    bool has_role = false;
    bool has_geometry = false;
};

struct RawElement {
    Kind kind = Kind::node;
    std::string_view type;
    std::int64_t id = 0;
    LatLon at;
    Range tags;
    Range nodes;
    Range geometry;
    Range members;
    bool has_type = false;
    bool has_id = false;
    bool has_lat = false;
    bool has_lon = false;
    bool has_tags = false;
    bool has_nodes = false;
    bool has_geometry = false;
    bool has_members = false;
    std::size_t from = 0;
    std::size_t to = 0;
};

/// A JSON number's parts, as written.
struct Number {
    bool negative = false;
    std::string_view whole;    ///< the integer part's digits
    std::string_view fraction; ///< the digits after the point
    std::int64_t exponent = 0; ///< saturated at ±10^15
    bool integer = true;       ///< neither a point nor an exponent: Python reads an int
};

[[nodiscard]] Kind kind_of(std::string_view type) noexcept {
    if (type == "node") return Kind::node;
    if (type == "way") return Kind::way;
    if (type == "relation") return Kind::relation;
    return Kind::other;
}

/// Whether the keys of `tags` from `first` on are all different.
[[nodiscard]] bool unique_keys(std::span<const Tag> tags, std::vector<std::string_view>& scratch) {
    if (tags.size() <= 16) {
        for (std::size_t i = 1; i < tags.size(); ++i) {
            for (std::size_t j = 0; j < i; ++j) {
                if (tags[i].key == tags[j].key) return false;
            }
        }
        return true;
    }
    scratch.clear();
    for (const Tag& t : tags) scratch.push_back(t.key);
    std::ranges::sort(scratch);
    return std::ranges::adjacent_find(scratch) == scratch.end();
}

/// A recursive-descent reader over the document's bytes. The first failure sticks: after it every read fails at once.
class Parser {
public:
    Parser(std::span<const std::byte> json, std::deque<std::string>& decoded)
        : begin_(reinterpret_cast<const char*>(json.data())), // NOLINT: the bytes read as the characters they are
          at_(begin_), end_(begin_ + json.size()), decoded_(&decoded) {}

    /// What the document holds, in its order: its elements and members, their tags, references and points.
    std::vector<RawElement> elements;
    std::vector<RawMember> members;
    std::vector<Tag> tags;
    std::vector<std::int64_t> refs;
    std::vector<LatLon> points;
    std::string_view head_base;  ///< `osm3s.timestamp_osm_base`
    std::string_view head_whole; ///< `atlas.whole`

    [[nodiscard]] const std::optional<ReadError>& error() const noexcept { return error_; }

    /// The document: an object whose `elements` is an array of elements, its date read from its head; its other keys
    /// stepped over.
    bool document() {
        if (!expect('{')) return false;
        bool seen = false;
        bool seen_osm3s = false;
        bool seen_atlas = false;
        if (!peek('}')) {
            do {
                std::string_view key;
                if (!string(key) || !expect(':')) return false;
                if (key == "elements") {
                    if (seen) return fail(Fault::duplicate);
                    seen = true;
                    if (!element_list()) return false;
                } else if (key == "osm3s" || key == "atlas") {
                    const bool osm3s = key == "osm3s";
                    bool& once = osm3s ? seen_osm3s : seen_atlas;
                    if (once) return fail(Fault::duplicate);
                    once = true;
                    if (!head(osm3s ? "timestamp_osm_base" : "whole", osm3s ? head_base : head_whole)) return false;
                } else if (!skip(1)) {
                    return false;
                }
            } while (more('}'));
        }
        if (!expect('}')) return false;
        space();
        if (at_ != end_) return fail(Fault::syntax); // nothing after the document but white space
        if (!seen) return fail(Fault::no_elements);
        return true;
    }

private:
    bool fail(Fault f) {
        if (!error_) error_ = ReadError{.fault = f, .at = static_cast<std::size_t>(at_ - begin_)};
        at_ = end_;
        return false;
    }

    void space() noexcept {
        while (at_ < end_ && (*at_ == ' ' || *at_ == '\t' || *at_ == '\n' || *at_ == '\r')) ++at_;
    }

    bool expect(char c) {
        space();
        if (at_ == end_) return fail(Fault::truncated);
        if (*at_ != c) return fail(Fault::syntax);
        ++at_;
        return true;
    }

    bool peek(char c) {
        space();
        return at_ < end_ && *at_ == c;
    }

    /// After a value of a list or object: true at a comma (taken), false at its end `close` (left for expect).
    bool more(char close) {
        space();
        if (at_ < end_ && *at_ == ',') {
            ++at_;
            return true;
        }
        if (at_ == end_)
            fail(Fault::truncated);
        else if (*at_ != close)
            fail(Fault::syntax);
        return false;
    }

    /// Four hexadecimal digits of a \u escape, before `stop` (the string's closing quote).
    bool hex4(std::uint32_t& out, const char* stop) {
        if (stop - at_ < 4) return fail(Fault::syntax);
        out = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = *at_++;
            std::uint32_t d = 0;
            if (c >= '0' && c <= '9')
                d = static_cast<std::uint32_t>(c - '0');
            else if (c >= 'a' && c <= 'f')
                d = static_cast<std::uint32_t>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F')
                d = static_cast<std::uint32_t>(c - 'A' + 10);
            else
                return fail(Fault::syntax);
            out = (out << 4U) | d;
        }
        return true;
    }

    static void append_utf8(std::string& s, std::uint32_t cp) {
        if (cp < 0x80U) {
            s += static_cast<char>(cp);
        } else if (cp < 0x800U) {
            s += static_cast<char>(0xC0U | (cp >> 6U));
            s += static_cast<char>(0x80U | (cp & 0x3FU));
        } else if (cp < 0x10000U) {
            s += static_cast<char>(0xE0U | (cp >> 12U));
            s += static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU));
            s += static_cast<char>(0x80U | (cp & 0x3FU));
        } else {
            s += static_cast<char>(0xF0U | (cp >> 18U));
            s += static_cast<char>(0x80U | ((cp >> 12U) & 0x3FU));
            s += static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU));
            s += static_cast<char>(0x80U | (cp & 0x3FU));
        }
    }

    /// A string: viewed in place when it has no escapes, else decoded into the document's own strings.
    bool string(std::string_view& out) {
        if (!expect('"')) return false;
        const char* start = at_;
        bool escaped = false;
        while (true) {
            if (at_ == end_) return fail(Fault::truncated);
            const auto c = static_cast<unsigned char>(*at_);
            if (c == '"') break;
            if (c < 0x20U) return fail(Fault::syntax); // a control character must be escaped
            if (c == '\\') {
                escaped = true;
                ++at_;
                if (at_ == end_) return fail(Fault::truncated);
            }
            ++at_;
        }
        const std::string_view raw(start, static_cast<std::size_t>(at_ - start));
        ++at_; // the closing quote
        if (!tiles::valid_utf8(std::as_bytes(std::span(raw.data(), raw.size())))) {
            at_ = start;
            return fail(Fault::bad_utf8);
        }
        if (!escaped) {
            out = raw;
            return true;
        }
        std::string& s = decoded_->emplace_back();
        s.reserve(raw.size());
        const char* const after = at_;
        const char* const stop = after - 1; // the closing quote
        at_ = start;
        while (at_ < stop) {
            const char c = *at_++;
            if (c != '\\') {
                s += c;
                continue;
            }
            const char e = *at_++;
            switch (e) {
            case '"': s += '"'; break;
            case '\\': s += '\\'; break;
            case '/': s += '/'; break;
            case 'b': s += '\b'; break;
            case 'f': s += '\f'; break;
            case 'n': s += '\n'; break;
            case 'r': s += '\r'; break;
            case 't': s += '\t'; break;
            case 'u': {
                std::uint32_t cp = 0;
                if (!hex4(cp, stop)) return false;
                if (cp >= 0xD800U && cp <= 0xDBFFU) {
                    // a high surrogate: its low one must follow
                    std::uint32_t low = 0;
                    if (stop - at_ < 6 || at_[0] != '\\' || at_[1] != 'u') return fail(Fault::bad_utf8);
                    at_ += 2;
                    if (!hex4(low, stop)) return false;
                    if (low < 0xDC00U || low > 0xDFFFU) return fail(Fault::bad_utf8);
                    cp = 0x10000U + ((cp - 0xD800U) << 10U) + (low - 0xDC00U);
                } else if (cp >= 0xDC00U && cp <= 0xDFFFU) {
                    return fail(Fault::bad_utf8); // a lone low surrogate
                }
                append_utf8(s, cp);
                break;
            }
            default: return fail(Fault::syntax);
            }
        }
        at_ = after;
        out = s;
        return true;
    }

    /// A number's parts (RFC 8259's grammar: no leading zeros, no lone point, no NaN or Infinity).
    bool number(Number& n) {
        space();
        if (at_ == end_) return fail(Fault::truncated);
        n = {};
        if (*at_ == '-') {
            n.negative = true;
            ++at_;
        }
        const char* whole = at_;
        if (at_ == end_ || *at_ < '0' || *at_ > '9') return fail(at_ == end_ ? Fault::truncated : Fault::syntax);
        if (*at_ == '0') {
            ++at_;
        } else {
            while (at_ < end_ && *at_ >= '0' && *at_ <= '9') ++at_;
        }
        n.whole = std::string_view(whole, static_cast<std::size_t>(at_ - whole));
        if (at_ < end_ && *at_ == '.') {
            n.integer = false;
            const char* fraction = ++at_;
            while (at_ < end_ && *at_ >= '0' && *at_ <= '9') ++at_;
            if (at_ == fraction) return fail(at_ == end_ ? Fault::truncated : Fault::syntax);
            n.fraction = std::string_view(fraction, static_cast<std::size_t>(at_ - fraction));
        }
        if (at_ < end_ && (*at_ == 'e' || *at_ == 'E')) {
            n.integer = false;
            ++at_;
            bool minus = false;
            if (at_ < end_ && (*at_ == '+' || *at_ == '-')) minus = *at_++ == '-';
            const char* digits = at_;
            constexpr std::int64_t cap = 1'000'000'000'000'000; // decimal_to_double's limit
            std::int64_t e = 0;
            while (at_ < end_ && *at_ >= '0' && *at_ <= '9') {
                e = std::min(cap, (e * 10) + (*at_ - '0'));
                ++at_;
            }
            if (at_ == digits) return fail(at_ == end_ ? Fault::truncated : Fault::syntax);
            n.exponent = minus ? -e : e;
        }
        return true;
    }

    /// An id or a reference: an integer literal of 64 bits.
    bool integer(std::int64_t& out) {
        Number n;
        if (!number(n)) return false;
        if (!n.integer || n.whole.size() > 19) return fail(Fault::bad_number);
        std::uint64_t v = 0;
        for (const char c : n.whole) v = (v * 10) + static_cast<std::uint64_t>(c - '0'); // 19 digits fit in 64 bits
        const std::uint64_t limit = n.negative ? std::uint64_t{1} << 63U : (std::uint64_t{1} << 63U) - 1;
        if (v > limit) return fail(Fault::bad_number);
        out = n.negative ? static_cast<std::int64_t>(~v + 1) : static_cast<std::int64_t>(v);
        return true;
    }

    /// A coordinate: what Python's json reads a number as, made a float: float() of a decimal's digits (its sign kept,
    /// -0.0 included), or int() of an integer's, then float() of that (an integer has no -0).
    bool coordinate(double& out) {
        Number n;
        if (!number(n)) return false;
        double v = 0.0;
        if (n.fraction.empty()) {
            v = pymath::decimal_to_double(n.whole, n.exponent);
        } else {
            digits_.assign(n.whole);
            digits_.append(n.fraction);
            v = pymath::decimal_to_double(digits_, n.exponent - static_cast<std::int64_t>(n.fraction.size()));
        }
        out = n.negative && (!n.integer || v != 0.0) ? -v : v;
        return true;
    }

    /// One of true, false, null.
    bool literal(std::string_view word) {
        if (static_cast<std::size_t>(end_ - at_) < word.size()) return fail(Fault::truncated);
        if (std::string_view(at_, word.size()) != word) return fail(Fault::syntax);
        at_ += word.size();
        return true;
    }

    /// Whether the next value is null (taken).
    bool null() {
        space();
        if (at_ < end_ && *at_ == 'n') return literal("null");
        return false;
    }

    /// Any value, read and dropped.
    bool skip(std::size_t depth) {
        if (depth > max_depth) return fail(Fault::too_deep);
        space();
        if (at_ == end_) return fail(Fault::truncated);
        switch (*at_) {
        case '"': {
            std::string_view s;
            return string(s);
        }
        case '{':
            ++at_;
            if (!peek('}')) {
                do {
                    std::string_view key;
                    if (!string(key) || !expect(':') || !skip(depth + 1)) return false;
                } while (more('}'));
            }
            return expect('}');
        case '[':
            ++at_;
            if (!peek(']')) {
                do {
                    if (!skip(depth + 1)) return false;
                } while (more(']'));
            }
            return expect(']');
        case 't': return literal("true");
        case 'f': return literal("false");
        case 'n': return literal("null");
        default: {
            Number n;
            return number(n);
        }
        }
    }

    /// A geometry: a list of {"lat": .., "lon": ..} (other keys stepped over), appended to the points.
    bool geometry(Range& out) {
        if (!expect('[')) return false;
        out.first = points.size();
        if (!peek(']')) {
            do {
                space();
                if (at_ < end_ && *at_ == 'n') return fail(Fault::unsupported); // a point Overpass could not give
                if (!expect('{')) return false;
                LatLon p;
                bool lat = false;
                bool lon = false;
                if (!peek('}')) {
                    do {
                        std::string_view key;
                        if (!string(key) || !expect(':')) return false;
                        if (key == "lat" || key == "lon") {
                            bool& seen = key == "lat" ? lat : lon;
                            if (seen) return fail(Fault::duplicate);
                            seen = true;
                            if (!coordinate(key == "lat" ? p.lat : p.lon)) return false;
                        } else if (!skip(2)) {
                            return false;
                        }
                    } while (more('}'));
                }
                if (!expect('}')) return false;
                if (!lat || !lon) return fail(Fault::bad_type);
                points.push_back(p);
            } while (more(']'));
        }
        out.count = points.size() - out.first;
        return expect(']');
    }

    /// The tags: an object of strings, in their order.
    bool tag_object(Range& out) {
        if (!expect('{')) return false;
        out.first = tags.size();
        if (!peek('}')) {
            do {
                Tag t;
                if (!string(t.key) || !expect(':')) return false;
                space();
                if (at_ < end_ && *at_ != '"') return fail(Fault::bad_type);
                if (!string(t.value)) return false;
                tags.push_back(t);
            } while (more('}'));
        }
        out.count = tags.size() - out.first;
        if (!unique_keys(std::span(tags).subspan(out.first, out.count), keys_)) return fail(Fault::duplicate);
        return expect('}');
    }

    /// A way's node references.
    bool node_list(Range& out) {
        if (!expect('[')) return false;
        out.first = refs.size();
        if (!peek(']')) {
            do {
                std::int64_t r = 0;
                if (!integer(r)) return false;
                refs.push_back(r);
            } while (more(']'));
        }
        out.count = refs.size() - out.first;
        return expect(']');
    }

    /// A relation's members.
    bool member_list(Range& out) {
        if (!expect('[')) return false;
        // the members are kept apart while their geometries go to the points, then moved into place together
        std::vector<RawMember> mine;
        if (!peek(']')) {
            do {
                if (!expect('{')) return false;
                RawMember m;
                bool type = false;
                bool ref = false;
                if (!peek('}')) {
                    do {
                        std::string_view key;
                        if (!string(key) || !expect(':')) return false;
                        if (key == "type") {
                            if (type) return fail(Fault::duplicate);
                            type = true;
                            std::string_view t;
                            if (!typed_string(t)) return false;
                            m.kind = kind_of(t);
                        } else if (key == "ref") {
                            if (ref) return fail(Fault::duplicate);
                            ref = true;
                            if (!integer(m.ref)) return false;
                        } else if (key == "role") {
                            if (m.has_role) return fail(Fault::duplicate);
                            m.has_role = true;
                            if (!typed_string(m.role)) return false;
                        } else if (key == "geometry") {
                            if (m.has_geometry) return fail(Fault::duplicate);
                            m.has_geometry = true;
                            if (!geometry(m.geometry)) return false;
                        } else if (!skip(3)) {
                            return false;
                        }
                    } while (more('}'));
                }
                if (!expect('}')) return false;
                mine.push_back(m);
            } while (more(']'));
        }
        out.first = members.size();
        out.count = mine.size();
        members.insert(members.end(), mine.begin(), mine.end());
        return expect(']');
    }

    /// A string where only a string may stand.
    bool typed_string(std::string_view& out) {
        space();
        if (at_ < end_ && *at_ != '"') return fail(Fault::bad_type);
        return string(out);
    }

    /// An object of the document's head (`osm3s`, `atlas`): its string `name` read into `out`, its other keys stepped
    /// over; a value of another kind stepped over whole.
    bool head(std::string_view name, std::string_view& out) {
        space();
        if (at_ == end_ || *at_ != '{') return skip(1);
        ++at_;
        bool seen = false;
        if (!peek('}')) {
            do {
                std::string_view key;
                if (!string(key) || !expect(':')) return false;
                if (key == name) {
                    if (seen) return fail(Fault::duplicate);
                    seen = true;
                    if (!typed_string(out)) return false;
                } else if (!skip(2)) {
                    return false;
                }
            } while (more('}'));
        }
        return expect('}');
    }

    bool element() {
        if (!expect('{')) return false;
        RawElement e;
        e.from = static_cast<std::size_t>(at_ - begin_) - 1;
        if (!peek('}')) {
            do {
                std::string_view key;
                if (!string(key) || !expect(':')) return false;
                const auto once = [&](bool& seen) {
                    if (seen) return fail(Fault::duplicate);
                    seen = true;
                    return true;
                };
                if (key == "type") {
                    if (!once(e.has_type) || !typed_string(e.type)) return false;
                    e.kind = kind_of(e.type);
                } else if (key == "id") {
                    if (!once(e.has_id) || !integer(e.id)) return false;
                } else if (key == "lat") {
                    if (!once(e.has_lat) || !coordinate(e.at.lat)) return false;
                } else if (key == "lon") {
                    if (!once(e.has_lon) || !coordinate(e.at.lon)) return false;
                } else if (key == "tags") {
                    if (!once(e.has_tags)) return false;
                    if (null()) {
                        e.has_tags = false; // e.get('tags') is None
                    } else if (!tag_object(e.tags)) {
                        return false;
                    }
                } else if (key == "nodes") {
                    if (!once(e.has_nodes) || !node_list(e.nodes)) return false;
                } else if (key == "geometry") {
                    space();
                    if (at_ < end_ && *at_ == 'n') return fail(Fault::unsupported); // `'geometry' in e`, and None
                    if (!once(e.has_geometry) || !geometry(e.geometry)) return false;
                } else if (key == "members") {
                    if (!once(e.has_members) || !member_list(e.members)) return false;
                } else if (!skip(2)) {
                    return false;
                }
            } while (more('}'));
        }
        if (!expect('}')) return false;
        e.to = static_cast<std::size_t>(at_ - begin_);
        if (!e.has_type) return fail(Fault::bad_type);
        elements.push_back(e);
        return true;
    }

    bool element_list() {
        if (!expect('[')) return false;
        if (!peek(']')) {
            do {
                if (!element()) return false;
            } while (more(']'));
        }
        return expect(']');
    }

    const char* begin_;
    const char* at_;
    const char* end_;
    std::deque<std::string>* decoded_;
    std::optional<ReadError> error_;
    std::string digits_;
    std::vector<std::string_view> keys_;
};

} // namespace

std::string_view name_of(ReadError::Fault fault) noexcept {
    switch (fault) {
    case Fault::truncated: return "truncated";
    case Fault::syntax: return "syntax";
    case Fault::bad_utf8: return "bad_utf8";
    case Fault::bad_number: return "bad_number";
    case Fault::bad_type: return "bad_type";
    case Fault::duplicate: return "duplicate";
    case Fault::too_deep: return "too_deep";
    case Fault::unsupported: return "unsupported";
    case Fault::no_elements: return "no_elements";
    }
    return "?";
}

std::expected<Document, ReadError> read_overpass(std::span<const std::byte> json) {
    Document doc;
    Parser p(json, doc.decoded_);
    if (!p.document()) return std::unexpected(p.error().value_or(ReadError{})); // a failed read has its error
    const std::vector<RawElement>& elements = p.elements;
    const std::vector<RawMember>& members = p.members;
    doc.tags_ = std::move(p.tags);
    doc.refs_ = std::move(p.refs);
    doc.points_ = std::move(p.points);
    // the arrays are whole: the ranges become views
    const auto view = [](const auto& v, Range r) { return std::span(v).subspan(r.first, r.count); };
    doc.members_.reserve(members.size());
    for (const RawMember& m : members) {
        doc.members_.push_back({.kind = m.kind,
                                .ref = m.ref,
                                .role = m.role,
                                .geometry = view(doc.points_, m.geometry),
                                .has_role = m.has_role,
                                .has_geometry = m.has_geometry});
    }
    doc.elements_.reserve(elements.size());
    for (const RawElement& e : elements) {
        doc.elements_.push_back({.kind = e.kind,
                                 .type = e.type,
                                 .id = e.id,
                                 .at = e.at,
                                 .tags = view(doc.tags_, e.tags),
                                 .nodes = view(doc.refs_, e.nodes),
                                 .geometry = view(doc.points_, e.geometry),
                                 .members = view(doc.members_, e.members),
                                 .has_id = e.has_id,
                                 .has_at = e.has_lat && e.has_lon,
                                 .has_tags = e.has_tags,
                                 .has_nodes = e.has_nodes,
                                 .has_geometry = e.has_geometry,
                                 .has_members = e.has_members,
                                 .from = e.from,
                                 .to = e.to});
    }
    doc.osm_base_ = p.head_base;
    doc.whole_ = p.head_whole;
    return doc;
}

Document select(const Document& from, const std::function<bool(const Element&)>& keep) {
    Document out;
    for (const Element& e : from.elements_) {
        if (keep(e)) out.elements_.push_back(e);
    }
    out.osm_base_ = from.osm_base_;
    out.whole_ = from.whole_;
    return out;
}

} // namespace atlas::osm
