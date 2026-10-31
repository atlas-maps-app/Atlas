#include "load/Tags.hpp"

#include "load/Tables.hpp"

#include <format>
#include <span>
#include <string_view>

namespace atlas::load {

namespace {

/// A table's entries as a JSON array, each from `entry`, a line each.
template <class R, class F>
void table(std::string& out, std::string_view key, const R& rows, F entry, bool last = false) {
    out += std::format("  \"{}\": [\n", key);
    for (std::size_t i = 0; i < rows.size(); ++i) {
        out += "    " + entry(rows[i]) + (i + 1 < rows.size() ? ",\n" : "\n");
    }
    out += last ? "  ]\n" : "  ],\n";
}

/// A string from the tables as JSON (they hold no character JSON escapes).
[[nodiscard]] std::string quoted(std::string_view s) { return std::format("\"{}\"", s); }

void names(std::string& out, std::string_view key, std::span<const std::string_view> rows, bool last = false) {
    table(out, key, rows, quoted, last);
}

} // namespace

std::string tag_tables_json() {
    std::string out = "{\n";
    out += "  \"about\": \"Stage P's tag tables (mapcore/cpp/load/Tables.hpp), as `atlas_stagep tags` prints them: the "
           "world's profile reads them (ENGINE.md step 5). Not to be edited: tags_test holds this file to the "
           "tables.\",\n";
    table(out, "land", land_kinds, [](const LandKind& k) { return std::format("[\"{}\", {}]", k.kind, k.order); });
    names(out, "land_leisure", land_leisure);
    table(out, "roads", road_classes, [](const RoadClass& r) { return std::format("[\"{}\", {:.1f}]", r.hw, r.w); });
    names(out, "big_roads", big_roads);
    names(out, "rail", rail_kinds);
    names(out, "aero", aero_kinds);
    names(out, "water_ways", water_ways);
    names(out, "tunnels", tunnels, true);
    out += "}\n";
    return out;
}

} // namespace atlas::load
