// What changed in OpenStreetMap's data, and the kept data brought up to date (osm/Diff.hpp).
#include "osm/Diff.hpp"

#include "tiles/Bytes.hpp"

#include <algorithm>
#include <array>
#include <deque>
#include <format>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace atlas::osm {

void append_escaped(std::string& out, std::string_view s) {
    for (const char ch : s) {
        const auto c = static_cast<unsigned char>(ch);
        if (c == '"') {
            out += "\\\"";
        } else if (c == '\\') {
            out += "\\\\";
        } else if (c == '\n') {
            out += "\\n";
        } else if (c == '\t') {
            out += "\\t";
        } else if (c == '\r') {
            out += "\\r";
        } else if (c < 0x20U) {
            out += '?';
        } else {
            out += ch;
        }
    }
}

namespace {

using Fault = DiffError::Fault;

/// The copyright line of Overpass's JSON head (output_json.cc).
constexpr std::string_view copyright = "The data included in this document is from www.openstreetmap.org. The data is "
                                       "made available under ODbL.";

/// An element's place in an answer: its type (nodes, then ways, then relations), then its id.
using Key = std::pair<int, std::int64_t>;

[[nodiscard]] int rank_of(Kind kind) noexcept {
    switch (kind) {
    case Kind::node: return 0;
    case Kind::way: return 1;
    case Kind::relation: return 2;
    case Kind::other: return 3;
    }
    return 3;
}

[[nodiscard]] Key key_of(const Change& c) noexcept { return {rank_of(c.kind), c.id}; }

[[nodiscard]] std::string_view type_name(Kind kind) noexcept {
    switch (kind) {
    case Kind::node: return "node";
    case Kind::way: return "way";
    case Kind::relation: return "relation";
    case Kind::other: return "other";
    }
    return "other";
}

[[nodiscard]] bool digit(char c) noexcept { return c >= '0' && c <= '9'; }

/// An integer as Overpass prints ids and references: an optional minus, no leading zero, within 64 bits.
[[nodiscard]] bool integer_text(std::string_view s, std::int64_t& out) noexcept {
    const bool negative = !s.empty() && s.front() == '-';
    const std::string_view digits = negative ? s.substr(1) : s;
    if (digits.empty() || digits.size() > 19 || (digits.size() > 1 && digits.front() == '0')) return false;
    std::uint64_t v = 0;
    for (const char c : digits) {
        if (!digit(c)) return false;
        v = (v * 10) + static_cast<std::uint64_t>(c - '0'); // 19 digits fit in 64 bits
    }
    const std::uint64_t limit = negative ? std::uint64_t{1} << 63U : (std::uint64_t{1} << 63U) - 1;
    if (v > limit) return false;
    out = negative ? static_cast<std::int64_t>(~v + 1) : static_cast<std::int64_t>(v);
    return true;
}

/// A JSON number (RFC 8259), as Overpass prints a coordinate: copied as it is into the JSON.
[[nodiscard]] bool number_text(std::string_view s) noexcept {
    std::size_t i = 0;
    if (i < s.size() && s[i] == '-') ++i;
    if (i == s.size() || !digit(s[i])) return false;
    if (s[i] == '0') {
        ++i;
    } else {
        while (i < s.size() && digit(s[i])) ++i;
    }
    if (i < s.size() && s[i] == '.') {
        const std::size_t first = ++i;
        while (i < s.size() && digit(s[i])) ++i;
        if (i == first) return false;
    }
    if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
        ++i;
        if (i < s.size() && (s[i] == '+' || s[i] == '-')) ++i;
        const std::size_t first = i;
        while (i < s.size() && digit(s[i])) ++i;
        if (i == first) return false;
    }
    return i == s.size();
}

/// Whether `cp` is a character XML 1.0 lets a document hold.
[[nodiscard]] bool xml_char(std::uint32_t cp) noexcept {
    return cp == 0x9U || cp == 0xAU || cp == 0xDU || (cp >= 0x20U && cp <= 0xD7FFU) ||
           (cp >= 0xE000U && cp <= 0xFFFDU) || (cp >= 0x10000U && cp <= 0x10FFFFU);
}

void append_utf8(std::string& s, std::uint32_t cp) {
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

/// An attribute of a start tag, its value decoded: a view of the text where it holds no reference, else of the
/// reader's own strings.
struct Attribute {
    std::string_view name;
    std::string_view value;
};

/// A start tag.
struct Start {
    std::string_view name;
    std::vector<Attribute> attributes;
    bool empty = false; ///< `<name ... />`
    std::size_t at = 0; ///< its `<`

    [[nodiscard]] const Attribute* find(std::string_view n) const noexcept {
        for (const Attribute& a : attributes) {
            if (a.name == n) return &a;
        }
        return nullptr;
    }
};

/// What an element read: which it is, and where.
struct Found {
    Kind kind = Kind::node;
    std::int64_t id = 0;
    std::size_t at = 0;
};

/// A reader of the XML Overpass prints (output_xml.cc), over the diff's bytes. The first failure sticks.
class Reader {
public:
    explicit Reader(std::span<const std::byte> xml)
        : bytes_(xml), begin_(reinterpret_cast<const char*>(xml.data())), // NOLINT: the bytes read as the characters
          at_(begin_), end_(begin_ + xml.size()) {}

    std::expected<Diff, DiffError> read() {
        if (!document()) return std::unexpected(error_.value_or(DiffError{}));
        // in type and id order, each element once
        std::vector<std::size_t> order(diff_.changes.size());
        for (std::size_t i = 0; i < order.size(); ++i) order[i] = i;
        std::ranges::stable_sort(order, [this](std::size_t a, std::size_t b) {
            return key_of(diff_.changes[a]) < key_of(diff_.changes[b]);
        });
        std::vector<Change> sorted;
        sorted.reserve(order.size());
        for (std::size_t k = 0; k < order.size(); ++k) {
            const Change& c = diff_.changes[order[k]];
            if (k > 0 && key_of(c) == key_of(sorted.back())) {
                return std::unexpected(DiffError{.fault = Fault::duplicate,
                                                 .at = places_[order[k]],
                                                 .what = std::format("{} {} twice", type_name(c.kind), c.id)});
            }
            sorted.push_back(std::move(diff_.changes[order[k]]));
        }
        diff_.changes = std::move(sorted);
        return std::move(diff_);
    }

private:
    bool fail_at(std::size_t at, Fault f, std::string what) {
        if (!error_) error_ = DiffError{.fault = f, .at = at, .what = std::move(what)};
        at_ = end_;
        return false;
    }

    bool fail(Fault f, std::string what) { return fail_at(static_cast<std::size_t>(at_ - begin_), f, std::move(what)); }

    bool truncated() { return fail(Fault::truncated, "the text ends inside a tag"); }

    void space() noexcept {
        while (at_ < end_ && (*at_ == ' ' || *at_ == '\t' || *at_ == '\n' || *at_ == '\r')) ++at_;
    }

    [[nodiscard]] bool starts(std::string_view s) const noexcept {
        return static_cast<std::size_t>(end_ - at_) >= s.size() && std::string_view(at_, s.size()) == s;
    }

    [[nodiscard]] static bool name_start(char c) noexcept {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':';
    }

    [[nodiscard]] static bool name_char(char c) noexcept { return name_start(c) || digit(c) || c == '-' || c == '.'; }

    bool name(std::string_view& out) {
        if (at_ == end_) return truncated();
        if (!name_start(*at_)) return fail(Fault::syntax, "a tag or an attribute without a name");
        const char* first = at_;
        while (at_ < end_ && name_char(*at_)) ++at_;
        out = std::string_view(first, static_cast<std::size_t>(at_ - first));
        return true;
    }

    /// A reference at `&` (one of XML's five, or a character's number), before `limit`, decoded onto `out`.
    bool reference(std::string& out, const char* limit) {
        ++at_; // the &
        const char* semi = at_;
        while (semi < limit && semi - at_ < 10 && *semi != ';') ++semi;
        if (semi == limit || *semi != ';') {
            return semi == end_ ? truncated() : fail(Fault::syntax, "an & that starts no reference");
        }
        const std::string_view ref(at_, static_cast<std::size_t>(semi - at_));
        at_ = semi + 1;
        if (ref == "amp") {
            out += '&';
        } else if (ref == "lt") {
            out += '<';
        } else if (ref == "gt") {
            out += '>';
        } else if (ref == "quot") {
            out += '"';
        } else if (ref == "apos") {
            out += '\'';
        } else if (ref.size() > 1 && ref.front() == '#') {
            const bool hex = ref[1] == 'x';
            const std::string_view digits = ref.substr(hex ? 2 : 1);
            if (digits.empty() || digits.size() > (hex ? 6U : 7U)) {
                return fail(Fault::syntax, "a character reference out of range");
            }
            std::uint32_t cp = 0;
            for (const char c : digits) {
                std::uint32_t d = 0;
                if (digit(c)) {
                    d = static_cast<std::uint32_t>(c - '0');
                } else if (hex && c >= 'a' && c <= 'f') {
                    d = static_cast<std::uint32_t>(c - 'a' + 10);
                } else if (hex && c >= 'A' && c <= 'F') {
                    d = static_cast<std::uint32_t>(c - 'A' + 10);
                } else {
                    return fail(Fault::syntax, "a character reference that is no number");
                }
                cp = (cp * (hex ? 16U : 10U)) + d;
            }
            if (!xml_char(cp))
                return fail(Fault::bad_utf8, std::format("a reference to U+{:04X}, no XML character", cp));
            append_utf8(out, cp);
        } else {
            return fail(Fault::syntax, std::format("an unknown reference &{};", ref));
        }
        return true;
    }

    /// An attribute's value in double quotes (as Overpass writes them), its references decoded.
    bool value(std::string_view& out) {
        if (at_ == end_) return truncated();
        if (*at_ != '"') return fail(Fault::syntax, "an attribute's value not in double quotes");
        ++at_;
        const char* first = at_;
        bool references = false;
        while (at_ < end_ && *at_ != '"') {
            const char c = *at_;
            if (c == '<') return fail(Fault::syntax, "a < in an attribute's value");
            // XML reads them as spaces; Overpass escapes every one, so one written raw is not Overpass's
            if (c == '\t' || c == '\n' || c == '\r') {
                return fail(Fault::unsupported, "a tab or a line end written raw in an attribute's value");
            }
            references = references || c == '&';
            ++at_;
        }
        if (at_ == end_) return truncated();
        const char* const quote = at_;
        ++at_;
        if (!references) {
            out = std::string_view(first, static_cast<std::size_t>(quote - first));
            return true;
        }
        std::string& s = strings_.emplace_back();
        const char* const after = at_;
        at_ = first;
        while (at_ < quote) {
            if (*at_ == '&') {
                if (!reference(s, quote)) return false;
            } else {
                s += *at_++;
            }
        }
        at_ = after;
        out = s;
        return true;
    }

    /// A start tag at `<`: its name and attributes, and whether it closes itself.
    bool start(Start& s) {
        s.at = static_cast<std::size_t>(at_ - begin_);
        ++at_; // the <
        if (!name(s.name)) return false;
        s.attributes.clear();
        while (true) {
            const char* before = at_;
            space();
            if (at_ == end_) return truncated();
            if (*at_ == '/') {
                ++at_;
                if (at_ == end_) return truncated();
                if (*at_ != '>') return fail(Fault::syntax, "a / in a tag");
                ++at_;
                s.empty = true;
                return true;
            }
            if (*at_ == '>') {
                ++at_;
                s.empty = false;
                return true;
            }
            if (at_ == before) return fail(Fault::syntax, "attributes without a space between them");
            Attribute a;
            if (!name(a.name)) return false;
            space();
            if (at_ == end_) return truncated();
            if (*at_ != '=') return fail(Fault::syntax, "an attribute without a value");
            ++at_;
            space();
            if (!value(a.value)) return false;
            if (s.find(a.name) != nullptr) {
                return fail_at(s.at, Fault::duplicate, std::format("the attribute {} twice in <{}>", a.name, s.name));
            }
            s.attributes.push_back(a);
        }
    }

    /// After white space: a child's start tag (read into `s`, `found`), or its parent's end tag (left for end()).
    bool child(Start& s, bool& found) {
        found = false;
        space();
        if (at_ == end_) return truncated();
        if (*at_ != '<') return fail(Fault::syntax, "text where Overpass writes none");
        if (starts("</")) return true;
        if (starts("<!"))
            return fail(Fault::unsupported, "a comment, CDATA or a document type, which Overpass never writes");
        if (starts("<?")) return fail(Fault::syntax, "a processing instruction after the declaration");
        found = true;
        return start(s);
    }

    /// `</n>`, after white space.
    bool end(std::string_view n) {
        space();
        if (at_ == end_) return truncated();
        if (!starts("</")) return fail(Fault::syntax, std::format("<{}> holds what Overpass never puts in it", n));
        at_ += 2;
        std::string_view got;
        if (!name(got)) return false;
        space();
        if (at_ == end_) return truncated();
        if (*at_ != '>') return fail(Fault::syntax, "an end tag with more than its name");
        ++at_;
        if (got != n) return fail(Fault::syntax, std::format("</{}> closes <{}>", got, n));
        return true;
    }

    /// The text up to the next tag, its references decoded.
    bool text(std::string& out) {
        while (at_ < end_ && *at_ != '<') {
            if (*at_ == '&') {
                if (!reference(out, end_)) return false;
            } else {
                out += *at_++;
            }
        }
        if (at_ == end_) return truncated();
        return true;
    }

    /// A child that holds nothing: `<x .../>`, or `<x ...></x>`.
    bool closed(const Start& s) { return s.empty || end(s.name); }

    /// An attribute that must be there.
    bool required(const Start& s, std::string_view n, std::string_view& out) {
        const Attribute* a = s.find(n);
        if (a == nullptr) return fail_at(s.at, Fault::syntax, std::format("<{}> without {}", s.name, n));
        out = a->value;
        return true;
    }

    /// A position (`lat` and `lon`, both or neither), each a number as Overpass prints it.
    bool position(const Start& s, std::string_view& lat, std::string_view& lon, bool& has) {
        const Attribute* a = s.find("lat");
        const Attribute* o = s.find("lon");
        has = a != nullptr && o != nullptr;
        if ((a == nullptr) != (o == nullptr)) return fail_at(s.at, Fault::syntax, "a position with one coordinate");
        if (!has) return true;
        if (!number_text(a->value) || !number_text(o->value)) {
            return fail_at(s.at, Fault::bad_number, std::format("a coordinate that is no number in <{}>", s.name));
        }
        lat = a->value;
        lon = o->value;
        return true;
    }

    /// An integer attribute that must be there: an id, a reference.
    bool integer(const Start& s, std::string_view n, std::string_view& text, std::int64_t& out) {
        if (!required(s, n, text)) return false;
        if (!integer_text(text, out)) {
            return fail_at(s.at, Fault::bad_number, std::format("<{}>'s {} is no integer of 64 bits", s.name, n));
        }
        return true;
    }

    /// A way member's point, or a way's node.
    struct Point {
        std::string_view ref;
        std::string_view lat;
        std::string_view lon;
    };

    /// An element (node, way or relation) at its start tag, read whole; with `full`, its new form written as
    /// Overpass's JSON prints it into `json`.
    bool element(const Start& s, bool full, Found& found, std::string& json) {
        found.at = s.at;
        if (s.name == "node") {
            found.kind = Kind::node;
        } else if (s.name == "way") {
            found.kind = Kind::way;
        } else if (s.name == "relation") {
            found.kind = Kind::relation;
        } else {
            return fail_at(s.at, Fault::unsupported, std::format("an element <{}>", s.name));
        }
        std::string_view id;
        if (!integer(s, "id", id, found.id)) return false;
        const Attribute* visible = s.find("visible");
        if (full && visible != nullptr && visible->value == "false") {
            return fail_at(s.at, Fault::unsupported, "a deleted element given as a new form");
        }
        json.clear();
        if (full) {
            json += "{\n  \"type\": \"";
            json += s.name;
            json += "\",\n  \"id\": ";
            json += id;
        }
        if (found.kind == Kind::node) {
            std::string_view lat;
            std::string_view lon;
            bool has = false;
            if (!position(s, lat, lon, has)) return false;
            if (full && !has) return fail_at(s.at, Fault::unsupported, "a node without a position");
            if (full) {
                json += ",\n  \"lat\": ";
                json += lat;
                json += ",\n  \"lon\": ";
                json += lon;
            }
        }
        tags_.clear();
        points_.clear();
        members_.clear();
        std::size_t members = 0;
        if (!s.empty) {
            int phase = 0; // the bounds, then the nodes or the members, then the tags, as Overpass prints them
            Start c;
            while (true) {
                bool more = false;
                if (!child(c, more)) return false;
                if (!more) break;
                if (c.name == "tag") {
                    phase = 2;
                    std::string_view k;
                    std::string_view v;
                    if (!required(c, "k", k) || !required(c, "v", v)) return false;
                    for (const auto& [key, ignored] : tags_) {
                        if (key == k) return fail_at(c.at, Fault::duplicate, std::format("the tag {} twice", k));
                    }
                    tags_.emplace_back(k, v);
                } else if (c.name == "bounds" && found.kind != Kind::node && phase == 0) {
                    phase = 1;
                    std::array<std::string_view, 4> b;
                    constexpr std::array<std::string_view, 4> names{"minlat", "minlon", "maxlat", "maxlon"};
                    for (std::size_t i = 0; i < 4; ++i) {
                        if (!required(c, names.at(i), b.at(i))) return false;
                        if (!number_text(b.at(i))) return fail_at(c.at, Fault::bad_number, "bounds that are no number");
                    }
                    if (full) {
                        json += ",\n  \"bounds\": {";
                        for (std::size_t i = 0; i < 4; ++i) {
                            json += i == 0 ? "\n    \"" : ",\n    \"";
                            json += names.at(i);
                            json += "\": ";
                            json += b.at(i);
                        }
                        json += "\n  }";
                    }
                } else if (c.name == "nd" && found.kind == Kind::way && phase <= 1) {
                    phase = 1;
                    Point p;
                    std::int64_t ignored = 0;
                    bool has = false;
                    if (!integer(c, "ref", p.ref, ignored) || !position(c, p.lat, p.lon, has)) return false;
                    if (full && !has) return fail_at(c.at, Fault::unsupported, "a way's node without a position");
                    points_.push_back(p);
                } else if (c.name == "member" && found.kind == Kind::relation && phase <= 1) {
                    phase = 1;
                    if (!member(c, full, members++)) return false;
                    continue; // member() read its end
                } else {
                    return fail_at(c.at, Fault::unsupported, std::format("<{}> in <{}>", c.name, s.name));
                }
                if (!closed(c)) return false;
            }
            if (!end(s.name)) return false;
        }
        if (!full) return true;
        if (found.kind == Kind::way) {
            if (points_.empty()) return fail_at(s.at, Fault::unsupported, "a way without nodes");
            json += ",\n  \"nodes\": [";
            for (std::size_t i = 0; i < points_.size(); ++i) {
                json += i == 0 ? "\n    " : ",\n    ";
                json += points_[i].ref;
            }
            json += "\n  ],\n  \"geometry\": [";
            for (std::size_t i = 0; i < points_.size(); ++i) {
                json += i == 0 ? "\n    { \"lat\": " : ",\n    { \"lat\": ";
                json += points_[i].lat;
                json += ", \"lon\": ";
                json += points_[i].lon;
                json += " }";
            }
            json += "\n  ]";
        } else if (found.kind == Kind::relation) {
            if (members == 0) return fail_at(s.at, Fault::unsupported, "a relation without members");
            json += ",\n  \"members\": [";
            json += members_;
            json += "\n  ]";
        }
        if (!tags_.empty()) {
            json += ",\n  \"tags\": {";
            for (std::size_t i = 0; i < tags_.size(); ++i) {
                json += i == 0 ? "\n    \"" : ",\n    \"";
                append_escaped(json, tags_[i].first);
                json += "\": \"";
                append_escaped(json, tags_[i].second);
                json += '"';
            }
            json += "\n  }";
        }
        json += "\n}";
        return true;
    }

    /// A relation's member at its start tag, read whole (a way member's points inside it); with `full`, written as
    /// Overpass's JSON prints it onto members_ (`index` its place).
    bool member(const Start& c, bool full, std::size_t index) {
        std::string_view type;
        std::string_view ref;
        std::string_view role;
        std::int64_t ignored = 0;
        if (!required(c, "type", type) || !integer(c, "ref", ref, ignored) || !required(c, "role", role)) return false;
        if (type != "node" && type != "way" && type != "relation") {
            return fail_at(c.at, Fault::unsupported, std::format("a member of type {}", type));
        }
        std::string_view lat;
        std::string_view lon;
        bool has = false;
        if (!position(c, lat, lon, has)) return false;
        if (has && type != "node") return fail_at(c.at, Fault::syntax, "a position on a member that is no node");
        std::vector<Point>& points = member_points_;
        points.clear();
        if (!c.empty) {
            Start p;
            while (true) {
                bool more = false;
                if (!child(p, more)) return false;
                if (!more) break;
                if (p.name != "nd" || type != "way") {
                    return fail_at(p.at, Fault::unsupported, std::format("<{}> in a {} member", p.name, type));
                }
                Point point;
                bool at = false;
                if (!position(p, point.lat, point.lon, at)) return false;
                if (full && !at) return fail_at(p.at, Fault::unsupported, "a way member's point without a position");
                points.push_back(point);
                if (!closed(p)) return false;
            }
            if (!end("member")) return false;
        }
        if (!full) return true;
        if (type == "way" && points.empty()) return fail_at(c.at, Fault::unsupported, "a way member without points");
        members_ += index == 0 ? "\n    {\n      \"type\": \"" : ",\n    {\n      \"type\": \"";
        members_ += type;
        members_ += "\",\n      \"ref\": ";
        members_ += ref;
        members_ += ",\n      \"role\": \"";
        append_escaped(members_, role);
        members_ += '"';
        if (has) {
            members_ += ",\n      \"lat\": ";
            members_ += lat;
            members_ += ",\n      \"lon\": ";
            members_ += lon;
        }
        if (type == "way") {
            members_ += ",\n      \"geometry\": [";
            for (std::size_t i = 0; i < points.size(); ++i) {
                members_ += i == 0 ? "\n         { \"lat\": " : ",\n         { \"lat\": ";
                members_ += points[i].lat;
                members_ += ", \"lon\": ";
                members_ += points[i].lon;
                members_ += " }";
            }
            members_ += "\n      ]";
        }
        members_ += "\n    }";
        return true;
    }

    /// `<n>` holding one element, `</n>`: the element read (written as JSON with `full`).
    bool wrapped(std::string_view n, bool full, Found& found, std::string& json) {
        Start w;
        bool more = false;
        if (!child(w, more)) return false;
        if (!more || w.name != n || w.empty)
            return fail(Fault::syntax, std::format("no <{}> where the action holds one", n));
        Start s;
        if (!child(s, more)) return false;
        if (!more) return fail(Fault::syntax, std::format("<{}> without an element", n));
        if (!element(s, full, found, json)) return false;
        return end(n);
    }

    /// An action at its start tag: a created element, a modified one's old and new forms, a deleted one's old form
    /// (and, in an augmented diff, what became of it).
    bool action(const Start& a) {
        std::string_view type;
        if (!required(a, "type", type)) return false;
        if (a.empty) return fail_at(a.at, Fault::syntax, "an action without its element");
        Found now;
        Found was;
        std::string json;
        bool gone = false;
        if (type == "create") {
            Start s;
            bool more = false;
            if (!child(s, more)) return false;
            if (!more) return fail(Fault::syntax, "a create without its element");
            if (!element(s, true, now, json)) return false;
        } else if (type == "modify") {
            if (!wrapped("old", false, was, json) || !wrapped("new", true, now, json)) return false;
            if (was.kind != now.kind || was.id != now.id) {
                return fail_at(a.at, Fault::syntax, "a modify whose old and new forms are different elements");
            }
        } else if (type == "delete") {
            gone = true; // `now` is the element as it was
            if (!wrapped("old", false, now, json)) return false;
            space();
            if (!starts("</")) {
                if (!wrapped("new", false, was, json)) return false;
                if (was.kind != now.kind || was.id != now.id) {
                    return fail_at(a.at, Fault::syntax, "a delete whose old and new forms are different elements");
                }
            }
            json.clear();
        } else {
            return fail_at(a.at, Fault::unsupported, std::format("an action of type {}", type));
        }
        if (!end("action")) return false;
        diff_.changes.push_back({.kind = now.kind, .id = now.id, .gone = gone, .json = std::move(json)});
        places_.push_back(now.at);
        return true;
    }

    bool document() {
        if (!tiles::valid_utf8(bytes_)) return fail_at(0, Fault::bad_utf8, "the text is not UTF-8");
        for (const char* p = begin_; p < end_; ++p) {
            const auto c = static_cast<unsigned char>(*p);
            if (c < 0x20U && c != '\t' && c != '\n' && c != '\r') {
                return fail_at(static_cast<std::size_t>(p - begin_), Fault::syntax, "a control character");
            }
        }
        if (starts("\xEF\xBB\xBF")) return fail(Fault::syntax, "a byte order mark, which Overpass never writes");
        if (starts("<?xml")) {
            const std::string_view rest(at_, static_cast<std::size_t>(end_ - at_));
            const std::size_t close = rest.find("?>");
            if (close == std::string_view::npos) return truncated();
            at_ += close + 2;
        }
        space();
        if (at_ == end_) return truncated();
        Start root;
        bool more = false;
        if (!child(root, more)) return false;
        if (!more || root.name != "osm") return fail(Fault::syntax, "the document is no <osm>");
        if (const Attribute* g = root.find("generator")) diff_.generator = g->value;
        std::optional<std::string_view> base;
        if (!root.empty) {
            Start c;
            while (true) {
                if (!child(c, more)) return false;
                if (!more) break;
                if (c.name == "note") {
                    std::string ignored;
                    if (!c.empty && (!text(ignored) || !end("note"))) return false;
                } else if (c.name == "meta") {
                    if (base) return fail_at(c.at, Fault::syntax, "<meta> twice");
                    const Attribute* b = c.find("osm_base");
                    if (b == nullptr) return fail_at(c.at, Fault::no_base, "<meta> without osm_base");
                    base = b->value;
                    if (!closed(c)) return false;
                } else if (c.name == "remark") {
                    std::string said;
                    if (!c.empty && !text(said)) return false;
                    const std::size_t first = said.find_first_not_of(" \t\n\r");
                    const std::size_t last = said.find_last_not_of(" \t\n\r");
                    return fail_at(c.at, Fault::remark,
                                   first == std::string::npos ? std::string() : said.substr(first, last - first + 1));
                } else if (c.name == "action") {
                    if (!action(c)) return false;
                } else if (c.name == "node" || c.name == "way" || c.name == "relation") {
                    return fail_at(c.at, Fault::unsupported, "an element outside an action: the answer is no diff");
                } else {
                    return fail_at(c.at, Fault::unsupported, std::format("<{}> in <osm>", c.name));
                }
            }
            if (!end("osm")) return false;
        }
        space();
        if (at_ != end_) return fail(Fault::syntax, "more after the document");
        if (!base) return fail_at(0, Fault::no_base, "no <meta osm_base>");
        if (!is_osm_date(*base)) return fail_at(0, Fault::no_base, std::format("osm_base {} is no date", *base));
        diff_.osm_base = *base;
        return true;
    }

    std::span<const std::byte> bytes_;
    const char* begin_;
    const char* at_;
    const char* end_;
    std::optional<DiffError> error_;
    std::deque<std::string> strings_; ///< decoded values, never moved
    Diff diff_;
    std::vector<std::size_t> places_; ///< where each change's element starts
    std::vector<std::pair<std::string_view, std::string_view>> tags_;
    std::vector<Point> points_;
    std::vector<Point> member_points_;
    std::string members_;
};

} // namespace

std::string_view name_of(DiffError::Fault fault) noexcept {
    switch (fault) {
    case Fault::truncated: return "truncated";
    case Fault::syntax: return "syntax";
    case Fault::bad_utf8: return "bad_utf8";
    case Fault::bad_number: return "bad_number";
    case Fault::remark: return "remark";
    case Fault::unsupported: return "unsupported";
    case Fault::duplicate: return "duplicate";
    case Fault::no_base: return "no_base";
    case Fault::kept: return "kept";
    }
    return "?";
}

bool is_osm_date(std::string_view s) noexcept {
    // YYYY-MM-DDTHH:MM:SSZ
    constexpr std::string_view shape = "dddd-dd-ddTdd:dd:ddZ";
    if (s.size() != shape.size()) return false;
    for (std::size_t i = 0; i < s.size(); ++i) {
        if (shape[i] == 'd' ? !digit(s[i]) : s[i] != shape[i]) return false;
    }
    return true;
}

std::expected<Diff, DiffError> read_diff(std::span<const std::byte> xml) { return Reader(xml).read(); }

std::expected<Merged, DiffError> merge_diff(std::span<const std::byte> kept, const Diff& diff) {
    const auto kept_error = [](std::size_t at, std::string what) {
        return std::unexpected(DiffError{.fault = Fault::kept, .at = at, .what = std::move(what)});
    };
    if (!is_osm_date(diff.osm_base)) {
        return std::unexpected(
            DiffError{.fault = Fault::no_base, .at = 0, .what = std::format("osm_base {} is no date", diff.osm_base)});
    }
    for (std::size_t i = 1; i < diff.changes.size(); ++i) {
        if (!(key_of(diff.changes[i - 1]) < key_of(diff.changes[i]))) {
            return std::unexpected(DiffError{.fault = Fault::unsupported,
                                             .at = 0,
                                             .what = "the diff's changes are not in type and id order, once each"});
        }
    }
    const auto doc = read_overpass(kept);
    if (!doc) {
        return kept_error(doc.error().at, std::format("the kept data does not read: {}", name_of(doc.error().fault)));
    }
    const std::string_view base = doc->osm_base();
    if (!is_osm_date(base)) return kept_error(0, "the kept data has no date");
    const std::string_view whole = doc->whole().empty() ? base : doc->whole();
    if (!is_osm_date(whole)) return kept_error(0, std::format("the kept data's whole date {} is no date", whole));
    const std::span<const Element> elements = doc->elements();
    for (std::size_t i = 0; i < elements.size(); ++i) {
        const Element& e = elements[i];
        if (e.kind == Kind::other || !e.has_id) {
            return kept_error(e.from, "an element of the kept data that is no node, way or relation with an id");
        }
        if (i > 0 && !(Key{rank_of(elements[i - 1].kind), elements[i - 1].id} < Key{rank_of(e.kind), e.id})) {
            return kept_error(e.from, "the kept data is not one answer in type and id order");
        }
    }
    const std::string_view text(reinterpret_cast<const char*>(kept.data()), kept.size()); // NOLINT: bytes as characters
    Merged m;
    std::size_t grown = 0;
    for (const Change& c : diff.changes) grown += c.json.size() + 2;
    m.json.reserve(kept.size() + grown + 512);
    m.json += "{\n  \"version\": 0.6,\n  \"generator\": \"";
    append_escaped(m.json, diff.generator.empty() ? std::string_view("Overpass API") : diff.generator);
    m.json += "\",\n  \"osm3s\": {\n    \"timestamp_osm_base\": \"";
    m.json += diff.osm_base;
    m.json += "\",\n    \"copyright\": \"";
    m.json += copyright;
    m.json += "\"\n  },\n  \"atlas\": {\n    \"whole\": \"";
    m.json += whole;
    m.json += "\"\n  },\n  \"elements\": [\n\n";
    bool first = true;
    const auto put = [&](std::string_view s) {
        if (!first) m.json += ",\n";
        first = false;
        m.json += s;
    };
    const auto before = [&](const Change& c) {
        if (c.gone) {
            ++m.absent;
        } else {
            put(c.json);
            ++m.added;
        }
    };
    std::size_t next = 0;
    const std::vector<Change>& changes = diff.changes;
    for (const Element& e : elements) {
        const Key key{rank_of(e.kind), e.id};
        while (next < changes.size() && key_of(changes[next]) < key) before(changes[next++]);
        const std::string_view was = text.substr(e.from, e.to - e.from);
        if (next < changes.size() && key_of(changes[next]) == key) {
            const Change& c = changes[next++];
            if (c.gone) {
                ++m.removed;
            } else if (c.json == was) {
                put(was); // named again with the same form: a change undone, or one the kept data already holds
                ++m.kept;
            } else {
                put(c.json);
                ++m.replaced;
            }
            continue;
        }
        put(was);
        ++m.kept;
    }
    while (next < changes.size()) before(changes[next++]);
    m.json += "\n\n  ]\n}\n";
    return m;
}

} // namespace atlas::osm
