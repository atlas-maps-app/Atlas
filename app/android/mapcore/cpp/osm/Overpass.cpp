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

/// Whether the keys of `tags` are all different.
[[nodiscard]] bool unique_keys(std::span<const HeldTag> tags, const Values& values,
                               std::vector<std::string_view>& scratch) {
    if (tags.size() <= 16) {
        for (std::size_t i = 1; i < tags.size(); ++i) {
            for (std::size_t j = 0; j < i; ++j) {
                if (tags[i].key.size == tags[j].key.size && values.text(tags[i].key) == values.text(tags[j].key)) {
                    return false;
                }
            }
        }
        return true;
    }
    scratch.clear();
    for (const HeldTag& t : tags) scratch.push_back(values.text(t.key));
    std::ranges::sort(scratch);
    return std::ranges::adjacent_find(scratch) == scratch.end();
}

/// A place in the document's text, its arrays or its bytes: within 32 bits, as max_bytes keeps them.
[[nodiscard]] std::uint32_t place(std::size_t at) noexcept { return static_cast<std::uint32_t>(at); }

/// A recursive-descent reader over the document's bytes. The first failure sticks: after it every read fails at once.
class Parser {
public:
    /// A reader of `values.bytes`, which fills the rest of `values`.
    explicit Parser(Values& values)
        : begin_(values.bytes.data()), at_(begin_), end_(begin_ + values.bytes.size()), values_(&values) {}

    /// The document's elements, in its order (what they hold goes to the values).
    std::vector<Element> elements;

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
                    if (!head(osm3s ? "timestamp_osm_base" : "whole", osm3s ? values_->osm_base : values_->whole)) {
                        return false;
                    }
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

    static void append_utf8(std::vector<char>& s, std::uint32_t cp) {
        if (cp < 0x80U) {
            s.push_back(static_cast<char>(cp));
        } else if (cp < 0x800U) {
            s.push_back(static_cast<char>(0xC0U | (cp >> 6U)));
            s.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        } else if (cp < 0x10000U) {
            s.push_back(static_cast<char>(0xE0U | (cp >> 12U)));
            s.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU)));
            s.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        } else {
            s.push_back(static_cast<char>(0xF0U | (cp >> 18U)));
            s.push_back(static_cast<char>(0x80U | ((cp >> 12U) & 0x3FU)));
            s.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU)));
            s.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        }
    }

    /// A string: viewed in place when it has no escapes, else decoded onto the end of `into` (`decoded` says which).
    bool string_in(std::string_view& out, std::vector<char>& into, bool& decoded) {
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
        decoded = escaped;
        if (!escaped) {
            out = raw;
            return true;
        }
        const std::size_t first = into.size();
        const char* const after = at_;
        const char* const stop = after - 1; // the closing quote
        at_ = start;
        while (at_ < stop) {
            const char c = *at_++;
            if (c != '\\') {
                into.push_back(c);
                continue;
            }
            const char e = *at_++;
            switch (e) {
            case '"': into.push_back('"'); break;
            case '\\': into.push_back('\\'); break;
            case '/': into.push_back('/'); break;
            case 'b': into.push_back('\b'); break;
            case 'f': into.push_back('\f'); break;
            case 'n': into.push_back('\n'); break;
            case 'r': into.push_back('\r'); break;
            case 't': into.push_back('\t'); break;
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
                append_utf8(into, cp);
                break;
            }
            default: return fail(Fault::syntax);
            }
        }
        at_ = after;
        out = std::string_view(std::span(into).subspan(first).data(), into.size() - first);
        return true;
    }

    /// A string the document keeps: its place in the document's text, its bytes or its decoded strings.
    bool string(Text& out) {
        const std::size_t first = values_->decoded.size();
        std::string_view s;
        bool decoded = false;
        if (!string_in(s, values_->decoded, decoded)) return false;
        out = decoded ? Text{.at = place(values_->bytes.size() + first), .size = place(values_->decoded.size() - first)}
                      : Text{.at = place(static_cast<std::size_t>(s.data() - begin_)), .size = place(s.size())};
        return true;
    }

    /// A string read and used at once (a key, a member's type, a string stepped over): valid until the next such.
    bool string(std::string_view& out) {
        scratch_.clear();
        bool decoded = false;
        return string_in(out, scratch_, decoded);
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
    bool geometry(Run& out) {
        if (!expect('[')) return false;
        std::vector<LatLon>& points = values_->points;
        out.first = place(points.size());
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
        out.count = place(points.size() - out.first);
        return expect(']');
    }

    /// The tags: an object of strings, in their order.
    bool tag_object(Run& out) {
        if (!expect('{')) return false;
        std::vector<HeldTag>& tags = values_->tags;
        out.first = place(tags.size());
        if (!peek('}')) {
            do {
                HeldTag t;
                if (!string(t.key) || !expect(':')) return false;
                space();
                if (at_ < end_ && *at_ != '"') return fail(Fault::bad_type);
                if (!string(t.value)) return false;
                tags.push_back(t);
            } while (more('}'));
        }
        out.count = place(tags.size() - out.first);
        if (!unique_keys(std::span(tags).subspan(out.first, out.count), *values_, keys_)) {
            return fail(Fault::duplicate);
        }
        return expect('}');
    }

    /// A way's node references.
    bool node_list(Run& out) {
        if (!expect('[')) return false;
        std::vector<std::int64_t>& refs = values_->refs;
        out.first = place(refs.size());
        if (!peek(']')) {
            do {
                std::int64_t r = 0;
                if (!integer(r)) return false;
                refs.push_back(r);
            } while (more(']'));
        }
        out.count = place(refs.size() - out.first);
        return expect(']');
    }

    /// A relation's members.
    bool member_list(Run& out) {
        if (!expect('[')) return false;
        std::vector<Member>& members = values_->members;
        out.first = place(members.size());
        if (!peek(']')) {
            do {
                if (!expect('{')) return false;
                Member m;
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
                members.push_back(m);
            } while (more(']'));
        }
        out.count = place(members.size() - out.first);
        return expect(']');
    }

    /// A string where only a string may stand, read and used at once, or kept.
    template <typename Out>
    bool typed_string(Out& out) {
        space();
        if (at_ < end_ && *at_ != '"') return fail(Fault::bad_type);
        return string(out);
    }

    /// An object of the document's head (`osm3s`, `atlas`): its string `name` read into `out`, its other keys stepped
    /// over; a value of another kind stepped over whole.
    bool head(std::string_view name, std::optional<Text>& out) {
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
                    Text t;
                    if (!typed_string(t)) return false;
                    out = t;
                } else if (!skip(2)) {
                    return false;
                }
            } while (more('}'));
        }
        return expect('}');
    }

    bool element() {
        if (!expect('{')) return false;
        Element e;
        e.from = place(static_cast<std::size_t>(at_ - begin_) - 1);
        bool has_type = false;
        bool has_lat = false;
        bool has_lon = false;
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
                    if (!once(has_type) || !typed_string(e.type)) return false;
                    e.kind = kind_of(values_->text(e.type));
                } else if (key == "id") {
                    if (!once(e.has_id) || !integer(e.id)) return false;
                } else if (key == "lat") {
                    if (!once(has_lat) || !coordinate(e.at.lat)) return false;
                } else if (key == "lon") {
                    if (!once(has_lon) || !coordinate(e.at.lon)) return false;
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
        e.to = place(static_cast<std::size_t>(at_ - begin_));
        if (!has_type) return fail(Fault::bad_type);
        e.has_at = has_lat && has_lon;
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
    Values* values_;
    std::optional<ReadError> error_;
    std::string digits_;
    std::vector<char> scratch_; ///< the strings read and used at once that needed decoding
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
    case Fault::too_large: return "too_large";
    }
    return "?";
}

std::expected<Document, ReadError> read_overpass(std::span<const std::byte> json) {
    if (json.size() > max_bytes) return std::unexpected(ReadError{.fault = Fault::too_large, .at = max_bytes});
    auto values = std::make_shared<Values>();
    values->bytes = std::span(reinterpret_cast<const char*>(json.data()), json.size()); // NOLINT: bytes as characters
    Parser p(*values);
    if (!p.document()) return std::unexpected(p.error().value_or(ReadError{})); // a failed read has its error
    Document doc;
    doc.elements_ = std::move(p.elements);
    // grown by doubling as the document was read: no more room than they hold
    doc.elements_.shrink_to_fit();
    values->tags.shrink_to_fit();
    values->refs.shrink_to_fit();
    values->points.shrink_to_fit();
    values->members.shrink_to_fit();
    values->decoded.shrink_to_fit();
    doc.values_ = std::move(values);
    return doc;
}

Document select(const Document& from, const std::function<bool(const Element&)>& keep) {
    Document out;
    for (const Element& e : from.elements_) {
        if (keep(e)) out.elements_.push_back(e);
    }
    out.values_ = from.values_;
    return out;
}

} // namespace atlas::osm
