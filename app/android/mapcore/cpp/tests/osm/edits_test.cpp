// The user's own edits in a window's data (osm/Edits.hpp): each applied as the app's sender applies it (set, carried
// already, left out where the element changed, or absent), the edits of one element in their order, each edited element
// written as Overpass's JSON prints it and every other byte the kept data's, and the refusals named. On the
// Westerkerk's raw.json, a building whose colour is a guess edited as FillIn edits it (a brown brick front) builds a
// window whose tile draws the colour as a mapped one: byte for byte the window of an answer that maps it.
#include "base/arena.hpp"
#include "load/Window.hpp"
#include "osm/Edits.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Buildings.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cstdint>
#include <format>
#include <fstream>
#include <iterator>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

namespace osm = atlas::osm;
namespace tl = atlas::tiles;
namespace load = atlas::load;
using Fault = osm::DiffError::Fault;

std::span<const std::byte> bytes_of(std::string_view s) { return std::as_bytes(std::span(s.data(), s.size())); }

std::string read_file(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

osm::TagChange change(std::string key, std::optional<std::string> value, std::optional<std::string> seen) {
    return {.key = std::move(key), .value = std::move(value), .seen = std::move(seen)};
}

/// An element's tags as the document reads them, in their order.
std::vector<std::pair<std::string, std::string>> tags_of(const osm::Document& doc, const osm::Element& e) {
    std::vector<std::pair<std::string, std::string>> out;
    for (const osm::Tag t : doc.tags(e)) out.emplace_back(t.key, t.value);
    return out;
}

const osm::Element* element(const osm::Document& doc, osm::Kind kind, std::int64_t id) {
    const auto all = doc.elements();
    const auto it = std::ranges::find_if(all, [&](const osm::Element& e) { return e.kind == kind && e.id == id; });
    return it == all.end() ? nullptr : &*it;
}

std::string_view slice(std::string_view json, const osm::Element& e) { return json.substr(e.from, e.to - e.from); }

/// Overpass's JSON of a query: its head, then its elements.
const std::string answer = R"({
  "version": 0.6,
  "generator": "Overpass API 0.7.62.11 87bfad18",
  "osm3s": {
    "timestamp_osm_base": "2026-10-26T20:00:00Z",
    "copyright": "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."
  },
  "elements": [

{
  "type": "node",
  "id": 1,
  "lat": 52.3745000,
  "lon": 4.8838000,
  "tags": {
    "amenity": "bench"
  }
},
{
  "type": "node",
  "id": 2,
  "lat": 52.3746000,
  "lon": 4.8839000
},
{
  "type": "way",
  "id": 10,
  "bounds": {
    "minlat": 52.3745000,
    "minlon": 4.8838000,
    "maxlat": 52.3746000,
    "maxlon": 4.8839000
  },
  "nodes": [
    1,
    2,
    1
  ],
  "geometry": [
    { "lat": 52.3745000, "lon": 4.8838000 },
    { "lat": 52.3746000, "lon": 4.8839000 },
    { "lat": 52.3745000, "lon": 4.8838000 }
  ],
  "tags": {
    "building": "yes",
    "height": "12"
  }
},
{
  "type": "way",
  "id": 11,
  "nodes": [
    1,
    2
  ],
  "tags": {
    "building": "house",
    "building:colour": "#ffffff"
  }
},
{
  "type": "relation",
  "id": 20,
  "members": [
    {
      "type": "way",
      "ref": 10,
      "role": "outer"
    }
  ],
  "tags": {
    "type": "multipolygon",
    "building": "yes"
  }
}

  ]
}
)";

/// The buildings of a window's one tile: each one's id, wall colour and guessed tags.
struct Drawn {
    std::int64_t id = 0;
    std::int64_t wall = 0;
    bool relation = false;
    std::vector<std::string> guessed;
};

std::vector<Drawn> buildings_of(const std::vector<std::byte>& pack) {
    auto opened = tl::Pack::open(std::make_unique<tl::MemorySource>(pack));
    if (!opened || opened->chunks().chunks().size() != 1) return {};
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(1U << 22U, atlas::Growth::grows, "edits test, a tile's buildings");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    const auto tile = opened->decode(opened->chunks().chunks()[0], zstd, decoder, arena, raw, scratch);
    if (!tile) return {};
    std::vector<Drawn> out;
    if (const tl::Layer* l = tile->layer(tl::LayerId::building)) {
        for (std::uint32_t i = 0; i < l->count; ++i) {
            Drawn d{.id = l->integer(tl::building::id, i),
                    .wall = l->integer(tl::building::wall_rgb, i),
                    .relation = l->string(tl::building::otype, i) == "relation"};
            if (const auto list = l->string_list(tl::slot_of(tl::building::columns, "guessed"), i)) {
                for (const std::uint32_t s : *list) d.guessed.emplace_back(l->strings[s]);
            }
            out.push_back(std::move(d));
        }
    }
    return out;
}

bool guessed(const Drawn& d, std::string_view key) { return std::ranges::find(d.guessed, key) != d.guessed.end(); }

} // namespace

TEST_CASE("osm: the user's edits apply as the sender applies them, each edited element as Overpass prints it") {
    const std::vector<osm::TagEdit> edits{
        // FillIn's answers for a building whose colour is a guess
        {.kind = osm::Kind::way,
         .id = 10,
         .tags = {change("building:colour", "#5c3a30", std::nullopt),
                  change("building:material", "brick", std::nullopt)}},
        // mapped by someone else since the user saw it: left out
        {.kind = osm::Kind::way, .id = 11, .tags = {change("building:colour", "#5c3a30", std::nullopt)}},
        // the first tags of an element that had none, and the last taken away from another
        {.kind = osm::Kind::node, .id = 2, .tags = {change("amenity", "bench", std::nullopt)}},
        {.kind = osm::Kind::node, .id = 1, .tags = {change("amenity", std::nullopt, "bench")}},
        // what the data holds already
        {.kind = osm::Kind::relation, .id = 20, .tags = {change("building", "yes", std::nullopt)}},
        // an element the data does not hold
        {.kind = osm::Kind::way, .id = 99, .tags = {change("building:colour", "#5c3a30", std::nullopt)}},
        // the same building again, on what the first edit made
        {.kind = osm::Kind::way, .id = 10, .tags = {change("building:colour", "#94503c", "#5c3a30")}},
    };
    const auto edited = osm::apply_edits(bytes_of(answer), edits);
    ATLAS_REQUIRE(edited.has_value(), edited.error().what);
    CHECK(edited->applied == 4);
    CHECK(edited->carried == 1);
    CHECK(edited->stale == 1);
    CHECK(edited->absent == 1);
    CHECK(edited->changed());
    const auto before = osm::read_overpass(bytes_of(answer));
    ATLAS_REQUIRE(before.has_value());
    const auto after = osm::read_overpass(bytes_of(edited->json));
    ATLAS_REQUIRE(after.has_value(), osm::name_of(after.error().fault), " at ", after.error().at);
    CHECK(after->osm_base() == "2026-10-26T20:00:00Z");
    REQUIRE(after->elements().size() == before->elements().size());
    // the head and the elements not edited: the kept bytes
    CHECK(std::string_view(edited->json).substr(0, before->elements()[0].from) ==
          std::string_view(answer).substr(0, before->elements()[0].from));
    for (const auto& [kind, id] : {std::pair{osm::Kind::way, 11}, std::pair{osm::Kind::relation, 20}}) {
        CHECK(slice(edited->json, *element(*after, kind, id)) == slice(answer, *element(*before, kind, id)));
    }
    // the building: its tags in their places, the new ones after them, as Overpass prints them
    const osm::Element* way = element(*after, osm::Kind::way, 10);
    REQUIRE(way != nullptr);
    CHECK(slice(edited->json, *way).ends_with(R"(  "tags": {
    "building": "yes",
    "height": "12",
    "building:colour": "#94503c",
    "building:material": "brick"
  }
})"));
    const std::string_view old = slice(answer, *element(*before, osm::Kind::way, 10));
    CHECK(slice(edited->json, *way).starts_with(old.substr(0, old.find(R"("tags")"))));
    // a node's first tags after its other keys; another's last one gone with its key
    const osm::Element* two = element(*after, osm::Kind::node, 2);
    REQUIRE(two != nullptr);
    CHECK(two->has_tags);
    CHECK(tags_of(*after, *two) == std::vector<std::pair<std::string, std::string>>{{"amenity", "bench"}});
    CHECK(slice(edited->json, *two) == R"({
  "type": "node",
  "id": 2,
  "lat": 52.3746000,
  "lon": 4.8839000,
  "tags": {
    "amenity": "bench"
  }
})");
    const osm::Element* one = element(*after, osm::Kind::node, 1);
    REQUIRE(one != nullptr);
    CHECK_FALSE(one->has_tags);
    CHECK(slice(edited->json, *one) == R"({
  "type": "node",
  "id": 1,
  "lat": 52.3745000,
  "lon": 4.8838000
})");
}

TEST_CASE("osm: an edit's values are written with Overpass's escapes and read back as they were") {
    const std::string name = "A \"quoted\" café \\ back\nslash\ttab";
    const std::vector<osm::TagEdit> edits{
        {.kind = osm::Kind::way, .id = 10, .tags = {change("name", name, std::nullopt), change("height", "13", "12")}}};
    const auto edited = osm::apply_edits(bytes_of(answer), edits);
    ATLAS_REQUIRE(edited.has_value(), edited.error().what);
    CHECK(edited->applied == 1);
    const auto after = osm::read_overpass(bytes_of(edited->json));
    ATLAS_REQUIRE(after.has_value(), osm::name_of(after.error().fault), " at ", after.error().at);
    const osm::Element* way = element(*after, osm::Kind::way, 10);
    REQUIRE(way != nullptr);
    CHECK(tags_of(*after, *way) ==
          std::vector<std::pair<std::string, std::string>>{{"building", "yes"}, {"height", "13"}, {"name", name}});
    CHECK(slice(edited->json, *way).find(R"("name": "A \"quoted\" café \\ back\nslash\ttab")") != std::string::npos);
}

TEST_CASE("osm: where no edit applies, nothing is written") {
    const std::vector<osm::TagEdit> edits{
        {.kind = osm::Kind::way, .id = 11, .tags = {change("building:colour", "#5c3a30", std::nullopt)}},
        {.kind = osm::Kind::way, .id = 11, .tags = {change("building:colour", "#ffffff", std::nullopt)}},
        {.kind = osm::Kind::node, .id = 3, .tags = {change("amenity", "bench", std::nullopt)}},
    };
    const auto edited = osm::apply_edits(bytes_of(answer), edits);
    ATLAS_REQUIRE(edited.has_value(), edited.error().what);
    CHECK(edited->json.empty());
    CHECK_FALSE(edited->changed());
    CHECK(edited->stale == 1);
    CHECK(edited->carried == 1);
    CHECK(edited->absent == 1);
    const auto none = osm::apply_edits(bytes_of(answer), {});
    ATLAS_REQUIRE(none.has_value());
    CHECK(none->json.empty());
}

TEST_CASE(
    "osm: an edit that changes no tag, a tag without a key or one twice, and data that does not read are refused") {
    const auto refused = [](const std::string& data, const osm::TagEdit& e) {
        const auto r = osm::apply_edits(bytes_of(data), std::span(&e, 1));
        return r.has_value() ? std::optional<Fault>() : std::optional(r.error().fault);
    };
    CHECK(refused(answer, {.kind = osm::Kind::way, .id = 10, .tags = {}}) == Fault::unsupported);
    CHECK(refused(answer, {.kind = osm::Kind::way, .id = 10, .tags = {change("", "x", std::nullopt)}}) ==
          Fault::unsupported);
    CHECK(refused(answer, {.kind = osm::Kind::way,
                           .id = 10,
                           .tags = {change("name", "a", std::nullopt), change("name", "b", std::nullopt)}}) ==
          Fault::unsupported);
    CHECK(refused("{\"elements\": [",
                  {.kind = osm::Kind::way, .id = 10, .tags = {change("name", "a", std::nullopt)}}) == Fault::kept);
}

TEST_CASE("osm: the edits as the app hands them over read, and what does not read so is refused") {
    const std::string json = R"([
  {"type": "way", "id": 266616341, "tags": [
    {"key": "building:colour", "value": "#5c3a30", "seen": null},
    {"key": "roof:shape", "value": "gabled"},
    {"key": "fixme", "value": null, "seen": "colour?"}
  ]},
  {"type": "node", "id": 7, "tags": [{"key": "name", "value": "Café 😀", "seen": "Cafe"}]}
])";
    const auto edits = osm::read_edits(bytes_of(json));
    ATLAS_REQUIRE(edits.has_value(), edits.error().what);
    REQUIRE(edits->size() == 2);
    const osm::TagEdit& way = (*edits)[0];
    CHECK(way.kind == osm::Kind::way);
    CHECK(way.id == 266616341);
    REQUIRE(way.tags.size() == 3);
    CHECK(way.tags[0].key == "building:colour");
    CHECK(way.tags[0].value == "#5c3a30");
    CHECK_FALSE(way.tags[0].seen.has_value());
    CHECK(way.tags[1].value == "gabled");
    CHECK_FALSE(way.tags[1].seen.has_value());
    CHECK_FALSE(way.tags[2].value.has_value());
    CHECK(way.tags[2].seen == "colour?");
    const osm::TagEdit& node = (*edits)[1];
    CHECK(node.kind == osm::Kind::node);
    REQUIRE(node.tags.size() == 1);
    CHECK(node.tags[0].value == "Caf\xC3\xA9 \xF0\x9F\x98\x80"); // UTF-8, the emoji whole
    CHECK(node.tags[0].seen == "Cafe");
    const auto refused = [](std::string_view s) {
        const auto r = osm::read_edits(bytes_of(s));
        return !r.has_value() && r.error().fault == Fault::unsupported;
    };
    CHECK(refused("["));
    CHECK(refused("{}"));
    CHECK(refused(R"([{"type": "area", "id": 1, "tags": []}])"));
    CHECK(refused(R"([{"type": "way", "tags": []}])"));
    CHECK(refused(R"([{"type": "way", "id": 1}])"));
    CHECK(refused(R"([{"type": "way", "id": 1, "tags": [{"value": "x"}]}])"));
    CHECK(refused(R"([{"type": "way", "id": 1, "tags": [{"key": "k", "value": 3}]}])"));
    const auto none = osm::read_edits(bytes_of("[]"));
    ATLAS_REQUIRE(none.has_value());
    CHECK(none->empty());
}

TEST_CASE("osm: a building whose colour is a guess, edited as FillIn edits it, draws its colour as a mapped one") {
    const std::string kept = read_file(std::string(ATLAS_TESTS_DIR) + "/../../../../../data/ams/raw.json");
    ATLAS_REQUIRE(!kept.empty(), "data/ams/raw.json is not in the repository");
    const auto doc = osm::read_overpass(bytes_of(kept));
    ATLAS_REQUIRE(doc.has_value());
    const tl::TileKey tile = tl::tile_of(14, 523'746'000, 48'840'000); // the Westerkerk's
    const load::WindowPlace place = load::place_of(tile);
    const auto from_kept = load::build_window(*doc, tl::Sha256Digest{}, tile, place);
    ATLAS_REQUIRE(from_kept.has_value(), from_kept.error().what);
    // the first way of the tile whose colour and material are not mapped, so its colour is a guess
    const std::vector<Drawn> was = buildings_of(from_kept->pack);
    const auto asked = std::ranges::find_if(was, [&](const Drawn& d) {
        const osm::Element* e = d.relation ? nullptr : element(*doc, osm::Kind::way, d.id);
        return e != nullptr && !doc->tags(*e).empty() && guessed(d, "building:colour") &&
               !doc->tags(*e).find("building:colour") && !doc->tags(*e).find("building:material") &&
               doc->tags(*e).back().value.find('"') == std::string::npos;
    });
    ATLAS_REQUIRE(asked != was.end(), "no building of the tile has its colour guessed");
    CHECK(asked->wall != 0x5C3A30);
    const std::vector<osm::TagEdit> edits{{.kind = osm::Kind::way,
                                           .id = asked->id,
                                           .tags = {change("building:colour", "#5c3a30", std::nullopt),
                                                    change("building:material", "brick", std::nullopt)}}};
    const auto edited = osm::apply_edits(bytes_of(kept), edits);
    ATLAS_REQUIRE(edited.has_value(), edited.error().what);
    CHECK(edited->applied == 1);
    const auto ours = osm::read_overpass(bytes_of(edited->json));
    ATLAS_REQUIRE(ours.has_value(), osm::name_of(ours.error().fault), " at ", ours.error().at);
    const auto from_edited = load::build_window(*ours, tl::Sha256Digest{}, tile, place);
    ATLAS_REQUIRE(from_edited.has_value(), from_edited.error().what);
    const std::vector<Drawn> now = buildings_of(from_edited->pack);
    const auto drawn = std::ranges::find_if(now, [&](const Drawn& d) { return !d.relation && d.id == asked->id; });
    ATLAS_REQUIRE(drawn != now.end());
    CHECK(drawn->wall == 0x5C3A30);
    CHECK_FALSE(guessed(*drawn, "building:colour"));
    CHECK_FALSE(guessed(*drawn, "building:material"));
    // byte for byte the window of an answer that maps it: the element's tags as Overpass prints two more
    const osm::Element* e = element(*doc, osm::Kind::way, asked->id);
    REQUIRE(e != nullptr);
    std::string mapped(slice(kept, *e));
    const std::string end = "\n  }\n}";
    ATLAS_REQUIRE(mapped.ends_with(end), "the element's tags are not its last member");
    mapped.insert(mapped.size() - end.size(),
                  ",\n    \"building:colour\": \"#5c3a30\",\n    \"building:material\": \"brick\"");
    const std::string fresh = kept.substr(0, e->from) + mapped + kept.substr(e->to);
    const auto theirs = osm::read_overpass(bytes_of(fresh));
    ATLAS_REQUIRE(theirs.has_value());
    const auto from_fresh = load::build_window(*theirs, tl::Sha256Digest{}, tile, place);
    ATLAS_REQUIRE(from_fresh.has_value(), from_fresh.error().what);
    CHECK(edited->json == fresh);
    CHECK(from_edited->pack == from_fresh->pack);
    CHECK(from_edited->pack != from_kept->pack);
    MESSAGE("way ", asked->id, ": wall ", std::format("#{:06X}", asked->wall), " guessed, then #5C3A30 mapped; ",
            was.size(), " buildings in the tile");
}
