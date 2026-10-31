// Stage P's tag tables (load/Tables.hpp) as data, for the world's profile (the packer's `world`, ENGINE.md step 5):
// `atlas_stagep tags` prints them, mapcore/style/tags.json holds them, and tags_test holds that file to the tables, so
// the profile chooses a feature's kind and class as stage P does.
#pragma once

#include <string>

namespace atlas::load {

/// The tag tables as JSON, an object of one key a table, each as Tables.hpp orders it: `land` ([kind, draw order]),
/// `land_leisure`, `roads` ([class, width in metres]), `big_roads`, `rail`, `aero`, `water_ways` and `tunnels`; and
/// `about`, what the file is. Two spaces to an indent, a table's entry a line, a newline at the end.
[[nodiscard]] std::string tag_tables_json();

} // namespace atlas::load
