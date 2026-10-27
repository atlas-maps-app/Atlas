// The user's own edits in a window's data (osm/Edits.hpp).
#include "osm/Edits.hpp"

#include "tiles/Json.hpp"

#include <algorithm>
#include <format>
#include <map>
#include <string_view>
#include <utility>

namespace atlas::osm {

namespace {

using Fault = DiffError::Fault;

/// An element's tags as its edits change them, in their order.
using TagRows = std::vector<std::pair<std::string, std::string>>;

/// Whether `tags` give `key` the value `want` (none: no such tag).
[[nodiscard]] bool holds(const TagRows& tags, std::string_view key, const std::optional<std::string>& want) {
    const auto it = std::ranges::find(tags, key, &TagRows::value_type::first);
    if (it == tags.end()) return !want.has_value();
    return want.has_value() && it->second == *want;
}

/// `change` made to `tags`: the value in the tag's place, a new tag after the others, or the tag gone.
void make(TagRows& tags, const TagChange& change) {
    const auto it = std::ranges::find(tags, change.key, &TagRows::value_type::first);
    if (!change.value) {
        if (it != tags.end()) tags.erase(it);
    } else if (it != tags.end()) {
        it->second = *change.value;
    } else {
        tags.emplace_back(change.key, *change.value);
    }
}

[[nodiscard]] bool space(char c) noexcept { return c == ' ' || c == '\n' || c == '\r' || c == '\t'; }

[[nodiscard]] std::size_t past_space(std::string_view s, std::size_t i) noexcept {
    while (i < s.size() && space(s[i])) ++i;
    return i;
}

/// Just past the string whose quote is at `i`.
[[nodiscard]] std::size_t past_string(std::string_view s, std::size_t i) noexcept {
    for (++i; i < s.size(); ++i) {
        if (s[i] == '\\') {
            ++i;
        } else if (s[i] == '"') {
            return i + 1;
        }
    }
    return s.size();
}

/// Just past the value that begins at `i`: a string, an object or an array (the strings in it stepped over), else a
/// number or a literal.
[[nodiscard]] std::size_t past_value(std::string_view s, std::size_t i) noexcept {
    if (i >= s.size()) return s.size();
    if (s[i] == '"') return past_string(s, i);
    if (s[i] == '{' || s[i] == '[') {
        std::size_t depth = 0;
        while (i < s.size()) {
            const char c = s[i];
            if (c == '"') {
                i = past_string(s, i);
                continue;
            }
            if (c == '{' || c == '[') ++depth;
            if ((c == '}' || c == ']') && --depth == 0) return i + 1;
            ++i;
        }
        return s.size();
    }
    while (i < s.size() && s[i] != ',' && s[i] != '}' && s[i] != ']' && !space(s[i])) ++i;
    return i;
}

/// Where an element's object holds its tags: the bytes of its `"tags"` member and of its value, and where its last
/// value ends.
struct TagsPlace {
    bool found = false;
    std::size_t cut_from = 0; ///< the member with the comma that joins it to the others
    std::size_t cut_to = 0;
    std::size_t value_from = 0;
    std::size_t value_to = 0;
    std::size_t members = 0;
    std::size_t last_to = 0; ///< just past the last member's value
};

/// The places in `s`, one element's object as the data holds it (read as JSON before); none where its members do not
/// read as an object's.
[[nodiscard]] std::optional<TagsPlace> tags_place(std::string_view s) noexcept {
    TagsPlace p;
    std::size_t i = past_space(s, 0);
    if (i >= s.size() || s[i] != '{') return std::nullopt;
    p.last_to = i + 1;
    i = past_space(s, i + 1);
    if (i < s.size() && s[i] == '}') return p;
    std::size_t before = p.last_to; // just past the value before this member
    while (i < s.size() && s[i] == '"') {
        const std::size_t key_from = i;
        const std::size_t key_to = past_string(s, i);
        i = past_space(s, key_to);
        if (i >= s.size() || s[i] != ':') return std::nullopt;
        const std::size_t from = past_space(s, i + 1);
        const std::size_t to = past_value(s, from);
        const std::size_t next = past_space(s, to);
        const bool more = next < s.size() && s[next] == ',';
        if (s.substr(key_from, key_to - key_from) == R"("tags")") {
            p.found = true;
            p.value_from = from;
            p.value_to = to;
            // taken away with the comma before it, or after it where it is the first member
            p.cut_from = p.members > 0 ? before : key_from;
            p.cut_to = p.members > 0 || !more ? to : past_space(s, next + 1);
        }
        ++p.members;
        p.last_to = to;
        before = to;
        if (!more) return next < s.size() && s[next] == '}' ? std::optional(p) : std::nullopt;
        i = past_space(s, next + 1);
    }
    return std::nullopt;
}

/// The element's object `was` with `tags`, as Overpass's JSON prints them; none where its members do not read.
[[nodiscard]] std::optional<std::string> with_tags(std::string_view was, const TagRows& tags) {
    const auto place = tags_place(was);
    if (!place) return std::nullopt;
    std::string object = "{";
    for (std::size_t i = 0; i < tags.size(); ++i) {
        object += i == 0 ? "\n    \"" : ",\n    \"";
        append_escaped(object, tags[i].first);
        object += "\": \"";
        append_escaped(object, tags[i].second);
        object += '"';
    }
    object += "\n  }";
    std::string out;
    out.reserve(was.size() + object.size() + 16);
    if (place->found && tags.empty()) {
        out += was.substr(0, place->cut_from);
        out += was.substr(place->cut_to);
    } else if (place->found) {
        out += was.substr(0, place->value_from);
        out += object;
        out += was.substr(place->value_to);
    } else if (!tags.empty()) {
        out += was.substr(0, place->last_to);
        out += place->members > 0 ? ",\n  \"tags\": " : "\n  \"tags\": ";
        out += object;
        out += was.substr(place->last_to);
    } else {
        out += was;
    }
    return out;
}

/// An edited element's new form, in place of its object's bytes in the data.
struct Rewrite {
    std::size_t from = 0;
    std::size_t to = 0;
    std::string json;
};

} // namespace

std::expected<std::vector<TagEdit>, DiffError> read_edits(std::span<const std::byte> json) {
    const auto refuse = [](std::size_t at, std::string what) {
        return std::unexpected(DiffError{.fault = Fault::unsupported, .at = at, .what = std::move(what)});
    };
    const auto read = tiles::read_json(json);
    if (!read)
        return refuse(read.error().at, std::format("the edits do not read: {}", tiles::name_of(read.error().fault)));
    const tiles::Value all = read->root();
    if (all.type() != tiles::ValueType::list) return refuse(0, "the edits are no array");
    // a string, or none for null or missing; false where it is another value
    const auto text = [](const std::optional<tiles::Value>& v, std::optional<std::string>& out) {
        out.reset();
        if (!v || v->is_null()) return true;
        const auto s = v->string();
        if (s) out = std::string(*s);
        return s.has_value();
    };
    std::vector<TagEdit> edits;
    edits.reserve(all.size());
    for (std::size_t i = 0; i < all.size(); ++i) {
        const tiles::Value e = all.child(i);
        const auto type = e.find("type");
        const auto id = e.find("id");
        const auto tags = e.find("tags");
        const std::optional<std::string_view> name = type ? type->string() : std::nullopt;
        const std::optional<std::int64_t> number = id ? id->integer() : std::nullopt;
        if (!name || !number || !tags || tags->type() != tiles::ValueType::list) {
            return refuse(0, std::format("edit {} has no type, id or tags", i));
        }
        TagEdit edit{.id = *number};
        if (*name == "node") {
            edit.kind = Kind::node;
        } else if (*name == "way") {
            edit.kind = Kind::way;
        } else if (*name == "relation") {
            edit.kind = Kind::relation;
        } else {
            return refuse(0, std::format("edit {} is of a {}", i, *name));
        }
        for (std::size_t k = 0; k < tags->size(); ++k) {
            const tiles::Value t = tags->child(k);
            std::optional<std::string> key;
            TagChange c;
            if (!text(t.find("key"), key) || !key || !text(t.find("value"), c.value) || !text(t.find("seen"), c.seen)) {
                return refuse(0, std::format("edit {}'s tag {} does not read", i, k));
            }
            c.key = std::move(*key);
            edit.tags.push_back(std::move(c));
        }
        edits.push_back(std::move(edit));
    }
    return edits;
}

std::expected<Edited, DiffError> apply_edits(std::span<const std::byte> data, std::span<const TagEdit> edits) {
    const auto refuse = [](std::string what) {
        return std::unexpected(DiffError{.fault = Fault::unsupported, .at = 0, .what = std::move(what)});
    };
    for (const TagEdit& e : edits) {
        if (e.tags.empty()) return refuse(std::format("an edit of {} changes no tag", e.id));
        for (std::size_t i = 0; i < e.tags.size(); ++i) {
            if (e.tags[i].key.empty()) return refuse(std::format("an edit of {} changes a tag without a key", e.id));
            for (std::size_t j = 0; j < i; ++j) {
                if (e.tags[j].key == e.tags[i].key) {
                    return refuse(std::format("an edit of {} changes {} twice", e.id, e.tags[i].key));
                }
            }
        }
    }
    const auto doc = read_overpass(data);
    if (!doc) {
        return std::unexpected(
            DiffError{.fault = Fault::kept,
                      .at = doc.error().at,
                      .what = std::format("the data does not read: {}", name_of(doc.error().fault))});
    }
    // each edited element's edits, in their order
    std::map<std::pair<Kind, std::int64_t>, std::vector<const TagEdit*>> waiting;
    for (const TagEdit& e : edits) waiting[{e.kind, e.id}].push_back(&e);
    Edited out;
    const std::string_view text(reinterpret_cast<const char*>(data.data()), data.size()); // NOLINT: bytes as characters
    std::vector<Rewrite> rewrites;
    for (const Element& el : doc->elements()) {
        if (!el.has_id || waiting.empty()) continue;
        const auto it = waiting.find({el.kind, el.id});
        if (it == waiting.end()) continue;
        TagRows tags;
        for (const Tag t : doc->tags(el)) tags.emplace_back(t.key, t.value);
        bool changed = false;
        for (const TagEdit* e : it->second) {
            const auto all_hold = [&](std::optional<std::string> TagChange::* which) {
                return std::ranges::all_of(e->tags, [&](const TagChange& c) { return holds(tags, c.key, c.*which); });
            };
            if (all_hold(&TagChange::value)) {
                ++out.carried;
            } else if (!all_hold(&TagChange::seen)) {
                ++out.stale;
            } else {
                for (const TagChange& c : e->tags) make(tags, c);
                ++out.applied;
                changed = true;
            }
        }
        waiting.erase(it); // an answer holds each element once
        if (!changed) continue;
        auto json = with_tags(text.substr(el.from, el.to - el.from), tags);
        if (!json) {
            return std::unexpected(DiffError{
                .fault = Fault::kept, .at = el.from, .what = std::format("element {}'s object does not read", el.id)});
        }
        rewrites.push_back({.from = el.from, .to = el.to, .json = std::move(*json)});
    }
    for (const auto& [element, left] : waiting) out.absent += left.size();
    if (rewrites.empty()) return out;
    // the elements in the document's order, their objects one after another in its bytes
    std::size_t grown = 0;
    for (const Rewrite& r : rewrites) grown += r.json.size();
    out.json.reserve(text.size() + grown);
    std::size_t at = 0;
    for (const Rewrite& r : rewrites) {
        out.json += text.substr(at, r.from - at);
        out.json += r.json;
        at = r.to;
    }
    out.json += text.substr(at);
    return out;
}

} // namespace atlas::osm
