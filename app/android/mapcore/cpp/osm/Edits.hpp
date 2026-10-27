// The user's own edits drawn before OpenStreetMap carries them (ENGINE.md step 6): a window's data with the tags of
// the edits waiting on the phone (the app's EditQueue, pending or sent), so the window built from it draws an edited
// colour or roof exactly as a mapped one. An edit is data: the window's kept data stays Overpass's answer and the
// edited data is only built from, so the next diff is asked from the kept data's date, and once the data carries an
// edit, the edit changes nothing.
//
// An edit applies as the app's sender applies it (OsmEdits.applied): where every key it changes still holds what the
// user saw, its values are set (none takes the tag away); where they all hold its values already, the data carries it;
// otherwise the element changed since the user saw it, and the edit is left out, as the sender leaves it. The edits of
// one element apply in their order, each to what the ones before it made.
//
// An edited element is written as Overpass's JSON prints it: its bytes as they were but for its tags, each in its place
// with its value, a new one after them, one taken away gone; `"tags"` after its other keys where it had none, and left
// out where none are left. Every other byte of the data, its head among them, is the kept data's.
#pragma once

#include "osm/Diff.hpp"
#include "osm/Overpass.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace atlas::osm {

/// One tag an edit changes: its key, the value it gives (none: the tag is taken away) and the value the user saw (none:
/// the element had no such tag).
struct TagChange {
    std::string key;
    std::optional<std::string> value;
    std::optional<std::string> seen;
};

/// One of the user's edits (the app's TagEdit): the element it changes, and how.
struct TagEdit {
    Kind kind = Kind::node;
    std::int64_t id = 0;
    std::vector<TagChange> tags;
};

/// A window's data with the user's edits, and what became of each edit.
struct Edited {
    std::string json;        ///< the data with the edits applied; empty where none applies
    std::size_t applied = 0; ///< edits whose keys held what the user saw: drawn
    std::size_t carried = 0; ///< edits whose values the data holds already
    std::size_t stale = 0;   ///< edits whose element changed since the user saw it: left out
    std::size_t absent = 0;  ///< edits of elements the data does not hold
    [[nodiscard]] bool changed() const noexcept { return applied > 0; }
};

/// The edits as the app hands them over (mapview's Windows): a JSON array, each edit `{"type": "way", "id": 1,
/// "tags": [{"key": "building:colour", "value": "#5c3a30", "seen": null}, ...]}`, a null or missing value or seen
/// for none. Refused where they do not read so (unsupported).
[[nodiscard]] std::expected<std::vector<TagEdit>, DiffError> read_edits(std::span<const std::byte> json);

/// `data`, Overpass's JSON of a window's query as kept (osm/Diff.hpp), with `edits` applied in their order. Refused
/// where the data does not read (DiffError::Fault::kept), and where an edit changes no tag, a tag without a key or one
/// tag twice (unsupported).
[[nodiscard]] std::expected<Edited, DiffError> apply_edits(std::span<const std::byte> data,
                                                           std::span<const TagEdit> edits);

} // namespace atlas::osm
