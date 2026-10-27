// What changed in OpenStreetMap's data since a window's data was fetched, as Overpass answers it, and the window's kept
// data brought up to that date (ENGINE.md step 3: a window built again after a week asks Overpass only for what
// changed).
//
// The diff is the XML of an augmented diff of the window's own query (`[adiff:"<since>"]` ... `out geom`). Overpass
// runs the query at both dates and prints each element whose output differs: its tags, its nodes or members and, under
// `out geom`, its points, so a way whose node moved is among them. A created element comes as it is now, a modified one
// as it was and as it is, one deleted or gone from the query's result as it was.
//
// The merge takes the kept data (Overpass's JSON of the same query, or an earlier merge: its elements in type and id
// order, as Overpass lists an answer), leaves out what is gone, puts each changed element's new form in its place and
// each new one in its order. The result is what the query answers at the diff's date, and a window built from it is
// the window a fresh answer builds.
//
// Each new form is written as Overpass's JSON prints that element (output_json.cc: its keys, its layout, its escapes;
// the XML printer's escapes agree with them, both printing a control character other than a tab or a line end as `?`).
// So the merged document reads as a fresh answer reads (osm/Overpass.hpp), with the same bytes for each element.
//
// The reader takes what Overpass's XML printer writes (output_xml.cc) and refuses the rest, naming it; the app then
// fetches the window whole:
//   - a remark: the query failed or ran out of time;
//   - an action, element or child it does not know, an element outside an action (an answer that is no diff);
//   - a point without a position, a way without nodes, a way member without points, a relation without members: for
//     these Overpass's JSON prints a null, an empty list or no key at all, and its XML does not say which;
//   - an element twice, a tag twice in one element (Overpass's JSON would print the key twice), no `meta osm_base`.
#pragma once

#include "osm/Overpass.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::osm {

/// Why a diff cannot be read or merged, and the byte where that was found.
struct DiffError {
    enum class Fault : std::uint8_t {
        truncated,   ///< the text ends inside a tag
        syntax,      ///< not the XML Overpass writes
        bad_utf8,    ///< text that is not UTF-8, or a reference to no XML character
        bad_number,  ///< an id, a reference or a coordinate that is no number as Overpass prints them
        remark,      ///< the answer's remark: the query failed
        unsupported, ///< what the merge cannot give as a fresh answer would hold it
        duplicate,   ///< an element twice, an attribute twice in a tag, a key twice in an element's tags
        no_base,     ///< no `meta osm_base`, or one that is no date
        kept,        ///< the kept data does not read, has no date, or is not one answer in type and id order
    };
    Fault fault = Fault::syntax;
    std::size_t at = 0; ///< in the diff's bytes; in the kept data's for `kept`
    std::string what;
};

/// The fault's name, for logs and tests.
[[nodiscard]] std::string_view name_of(DiffError::Fault fault) noexcept;

/// One element the diff names: its new form, or none where it is gone from the query's result.
struct Change {
    Kind kind = Kind::node;
    std::int64_t id = 0;
    bool gone = false;
    std::string json; ///< its new form, one element of Overpass's JSON (empty when gone)
};

/// A diff: the date it brings the data to (`meta osm_base`), its generator, and its changes in type and id order.
struct Diff {
    std::string osm_base;
    std::string generator;
    std::vector<Change> changes;
};

/// The diff in Overpass's XML (an augmented diff, or a plain one), or why it cannot be merged exactly.
[[nodiscard]] std::expected<Diff, DiffError> read_diff(std::span<const std::byte> xml);

/// The kept data with a diff's changes.
struct Merged {
    std::string json;         ///< Overpass's JSON of the query at the diff's date, its head saying when it was whole
    std::size_t kept = 0;     ///< elements as they were
    std::size_t replaced = 0; ///< elements in their new form
    std::size_t added = 0;    ///< elements new to the data
    std::size_t removed = 0;  ///< elements gone
    std::size_t absent = 0;   ///< elements the diff says are gone that the data did not hold
    [[nodiscard]] bool changed() const noexcept { return replaced + added + removed > 0; }
};

/// Merges `diff` into `kept`: Overpass's JSON of the query (its elements in type and id order, its date in
/// `osm3s.timestamp_osm_base`), or an earlier merge. Element by element the result is the kept data's bytes where
/// nothing changed and the diff's new forms where something did. Its date is the diff's, also where the server that
/// answered the diff was behind the one the data came from: an element it gives in a form older than the kept one is
/// then brought forward again by the next diff, asked from that date. Its `atlas.whole` says when the data was last
/// fetched whole (the kept data's, else the kept data's date).
[[nodiscard]] std::expected<Merged, DiffError> merge_diff(std::span<const std::byte> kept, const Diff& diff);

/// Whether `s` is a date as Overpass writes its data's: `YYYY-MM-DDTHH:MM:SSZ`.
[[nodiscard]] bool is_osm_date(std::string_view s) noexcept;

/// `s` appended to `out` as Overpass's JSON writes a string's characters (escape_cstr): a quote, a backslash, a line
/// feed, a tab and a carriage return escaped, another control character printed `?`, every other byte as it is.
void append_escaped(std::string& out, std::string_view s);

} // namespace atlas::osm
