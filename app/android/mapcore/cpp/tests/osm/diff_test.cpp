// A window's kept data brought up to date by Overpass's diff (osm/Diff.hpp). The reader on a diff of each action and
// element, each new form held to the JSON Overpass prints for that element, and its refusals named. The merge on a
// real answer: the Westerkerk's raw.json (Overpass's JSON of the renders' query) edited element by element (a tag, a
// way's point, a relation's member and tag; elements deleted, gone from the query, named again unchanged, created
// between and after the others), with the diff of those edits in Overpass's XML: the merged document holds the edited
// answer's bytes element for element, reads as it reads, and builds the same window. Where ATLAS_DIFF_KEPT,
// ATLAS_DIFF_XML and ATLAS_DIFF_FRESH name an answer of Overpass, its augmented diff since that answer's date and an
// answer of the diff's date (the same query), the merged answer reads as the fresh one.
#include "load/Window.hpp"
#include "osm/Diff.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Json.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdio>
#include <format>
#include <fstream>
#include <iterator>
#include <string>
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

/// XML's escapes as Overpass's escape_xml writes them: & " < > escaped, a line feed, a tab and a carriage return as
/// character references, another control character as `?`.
std::string xml_escaped(std::string_view s) {
    std::string out;
    for (const char ch : s) {
        switch (ch) {
        case '&': out += "&amp;"; break;
        case '"': out += "&quot;"; break;
        case '<': out += "&lt;"; break;
        case '>': out += "&gt;"; break;
        case '\n': out += "&#x0a;"; break;
        case '\t': out += "&#x09;"; break;
        case '\r': out += "&#x0d;"; break;
        default: out += static_cast<unsigned char>(ch) < 0x20U ? '?' : ch;
        }
    }
    return out;
}

/// A coordinate as Overpass prints it: fixed, seven decimals.
std::string coordinate(double v) {
    std::array<char, 32> buf{};
    const int n = std::snprintf(buf.data(), buf.size(), "%.7f", v);
    return {buf.data(), static_cast<std::size_t>(n)};
}

std::string number_of(const tl::Value& v) { return coordinate(v.number().value_or(0.0)); }

/// An object's value for `key` (null where it has none).
tl::Value field(const tl::Value& v, std::string_view key) { return v.find(key).value_or(tl::Value()); }

std::string text_of(const std::optional<tl::Value>& v) {
    return v ? std::string(v->string().value_or("")) : std::string();
}

/// One element of Overpass's JSON printed as Overpass's XML prints it (output_xml.cc); `gone` as an augmented diff
/// prints a deleted element's new form.
std::string xml_of(std::string_view json, bool gone = false) {
    const auto read = tl::read_json(bytes_of(json));
    REQUIRE_MESSAGE(read.has_value(), "the element does not read: ", json.substr(0, 80));
    if (!read) return {};
    const tl::Value e = read->root();
    const std::string type = text_of(e.find("type"));
    std::string out = std::format("  <{} id=\"{}\"", type, field(e, "id").integer().value_or(0));
    if (gone) {
        out += R"( visible="false" version="7" timestamp="2026-10-12T10:00:00Z" changeset="1" uid="2" user="x"/>)";
        out += '\n';
        return out;
    }
    if (type == "node")
        out += std::format(R"( lat="{}" lon="{}")", number_of(field(e, "lat")), number_of(field(e, "lon")));
    std::string children;
    if (const auto b = e.find("bounds")) {
        children += std::format(R"(    <bounds minlat="{}" minlon="{}" maxlat="{}" maxlon="{}"/>)",
                                number_of(field(*b, "minlat")), number_of(field(*b, "minlon")),
                                number_of(field(*b, "maxlat")), number_of(field(*b, "maxlon")));
        children += '\n';
    }
    if (const auto nodes = e.find("nodes")) {
        const tl::Value g = field(e, "geometry");
        for (std::size_t i = 0; i < nodes->size(); ++i) {
            children += std::format(R"(    <nd ref="{}" lat="{}" lon="{}"/>)", nodes->child(i).integer().value_or(0),
                                    number_of(field(g.child(i), "lat")), number_of(field(g.child(i), "lon")));
            children += '\n';
        }
    }
    if (const auto members = e.find("members")) {
        for (std::size_t i = 0; i < members->size(); ++i) {
            const tl::Value m = members->child(i);
            const std::string kind = text_of(m.find("type"));
            children += std::format(R"(    <member type="{}" ref="{}" role="{}")", kind,
                                    field(m, "ref").integer().value_or(0), xml_escaped(text_of(m.find("role"))));
            if (const auto lat = m.find("lat")) {
                children += std::format(R"( lat="{}" lon="{}")", number_of(*lat), number_of(field(m, "lon")));
            }
            if (const auto g = m.find("geometry")) {
                children += ">\n";
                for (std::size_t j = 0; j < g->size(); ++j) {
                    children += std::format(R"(      <nd lat="{}" lon="{}"/>)", number_of(field(g->child(j), "lat")),
                                            number_of(field(g->child(j), "lon")));
                    children += '\n';
                }
                children += "    </member>\n";
            } else {
                children += "/>\n";
            }
        }
    }
    if (const auto tags = e.find("tags")) {
        for (std::size_t i = 0; i < tags->size(); ++i) {
            children += std::format(R"(    <tag k="{}" v="{}"/>)", xml_escaped(tags->key(i)),
                                    xml_escaped(tags->child(i).string().value_or("")));
            children += '\n';
        }
    }
    if (children.empty()) return out + "/>\n";
    return out + ">\n" + children + "  </" + type + ">\n";
}

constexpr std::string_view xml_head = R"(<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="Overpass API 0.7.62.11 87bfad18">
<note>The data included in this document is from www.openstreetmap.org. The data is made available under ODbL.</note>
<meta osm_base="2026-10-09T08:46:19Z" areas="2026-10-09T08:00:00Z"/>

)";

std::string diff_xml(std::string_view actions) { return std::string(xml_head) + std::string(actions) + "\n</osm>\n"; }

std::string modify(std::string_view was, std::string_view now) {
    return "<action type=\"modify\">\n<old>\n" + xml_of(was) + "</old>\n<new>\n" + xml_of(now) + "</new>\n</action>\n";
}

std::string create(std::string_view now) { return "<action type=\"create\">\n" + xml_of(now) + "</action>\n"; }

/// A delete as an augmented diff prints it (its new form gone, or `away`: still there, out of the query's result), or
/// as a plain diff (no new form).
std::string remove(std::string_view was, int form) {
    std::string a = "<action type=\"delete\">\n<old>\n" + xml_of(was) + "</old>\n";
    if (form == 1) a += "<new>\n" + xml_of(was, true) + "</new>\n";
    if (form == 2) a += "<new>\n" + xml_of(was) + "</new>\n";
    return a + "</action>\n";
}

/// A document's elements, one canonical line each: every value the reader gives, the coordinates to the bit.
std::string dump(const osm::Document& doc) {
    std::string out;
    const auto bits = [](double v) { return std::bit_cast<std::uint64_t>(v); };
    for (const osm::Element& e : doc.elements()) {
        out += std::format("{} {} {} {} {:x} {:x} {}{}{}{}{}{}", static_cast<int>(e.kind), doc.text(e.type), e.id,
                           e.has_id ? 1 : 0, bits(e.at.lat), bits(e.at.lon), e.has_at ? 1 : 0, e.has_tags ? 1 : 0,
                           e.has_nodes ? 1 : 0, e.has_geometry ? 1 : 0, e.has_members ? 1 : 0, '|');
        for (const osm::Tag t : doc.tags(e)) out += std::format("{}={};", t.key, t.value);
        out += '|';
        for (const std::int64_t n : doc.nodes(e)) out += std::format("{},", n);
        out += '|';
        for (const osm::LatLon& p : doc.geometry(e)) out += std::format("{:x}/{:x},", bits(p.lat), bits(p.lon));
        out += '|';
        for (const osm::Member& m : doc.members(e)) {
            out += std::format("{} {} {} {}{}:", static_cast<int>(m.kind), m.ref, doc.text(m.role), m.has_role ? 1 : 0,
                               m.has_geometry ? 1 : 0);
            for (const osm::LatLon& p : doc.geometry(m)) out += std::format("{:x}/{:x},", bits(p.lat), bits(p.lon));
            out += ';';
        }
        out += '\n';
    }
    return out;
}

/// The elements of a document of Overpass's JSON, from their array's opening on: what two answers of one query share.
std::string_view elements_of(std::string_view json) {
    const std::size_t at = json.find("\"elements\": [");
    return at == std::string_view::npos ? std::string_view() : json.substr(at);
}

std::string answer(const std::vector<std::string>& elements, std::string_view base) {
    std::string out = std::format(
        "{{\n  \"version\": 0.6,\n  \"generator\": \"Overpass API 0.7.62.11 87bfad18\",\n  \"osm3s\": {{\n    "
        "\"timestamp_osm_base\": \"{}\",\n    \"copyright\": \"The data included in this document is from "
        "www.openstreetmap.org. The data is made available under ODbL.\"\n  }},\n  \"elements\": [\n\n",
        base);
    for (std::size_t i = 0; i < elements.size(); ++i) {
        if (i > 0) out += ",\n";
        out += elements[i];
    }
    return out + "\n\n  ]\n}\n";
}

/// Replaces the first `{ "lat": ` number at or after `from` in `text` by the next value 0.00001° north.
bool move_point(std::string& text, std::size_t from) {
    const std::string_view lead = "{ \"lat\": ";
    const std::size_t at = text.find(lead, from);
    if (at == std::string::npos) return false;
    const std::size_t first = at + lead.size();
    const std::size_t comma = text.find(',', first);
    const double lat = std::stod(text.substr(first, comma - first));
    text.replace(first, comma - first, coordinate(lat + 0.00001));
    return true;
}

osm::DiffError::Fault fault_of(std::string_view xml) {
    const auto d = osm::read_diff(bytes_of(xml));
    REQUIRE_MESSAGE(!d.has_value(), "read without a fault: ", xml);
    return d ? Fault::syntax : d.error().fault;
}

} // namespace

TEST_CASE("osm: a diff in Overpass's XML gives each element's new form as Overpass's JSON prints it") {
    const std::string xml = std::string(xml_head) + R"(<action type="delete">
<old>
  <relation id="9">
    <member type="way" ref="5" role="outer">
      <nd lat="52.0000000" lon="4.0000000"/>
    </member>
  </relation>
</old>
<new>
  <relation id="9" visible="false" version="3" timestamp="2026-10-12T10:00:00Z" changeset="1" uid="2" user="x"/>
</new>
</action>
<action type="create">
  <relation id="10">
    <bounds minlat="52.0000000" minlon="4.0000000" maxlat="52.1000000" maxlon="4.1000000"/>
    <member type="node" ref="1" role="label" lat="52.0500000" lon="4.0500000"/>
    <member type="way" ref="5" role="out&quot;er">
      <nd lat="52.0000000" lon="4.0000000"/>
      <nd lat="52.1000000" lon="4.1000000"/>
    </member>
    <member type="relation" ref="9" role=""/>
    <tag k="type" v="multipolygon"/>
  </relation>
</action>
<action type="modify">
<old>
  <way id="5">
    <nd ref="1" lat="52.0000000" lon="4.0000000"/>
    <nd ref="2" lat="52.1000000" lon="4.1000000"/>
  </way>
</old>
<new>
  <way id="5">
    <bounds minlat="52.0000000" minlon="4.0000000" maxlat="52.1000000" maxlon="4.1000000"/>
    <nd ref="1" lat="52.0000000" lon="4.0000000"/>
    <nd ref="2" lat="52.1000000" lon="4.1000000"/>
    <tag k="highway" v="footway"/>
  </way>
</new>
</action>
<action type="create">
  <node id="1" lat="52.0500000" lon="-0.0000001">
    <tag k="name" v="A &quot;quoted&quot; bench &amp; &lt;its&gt; \ back&#x0a;slash&#x09;tab&#x0d;caf&#233; ' &#x1F600;"/>
    <tag k="amenity" v="bench"/>
  </node>
</action>
<action type="delete">
<old>
  <node id="3" lat="1.0000000" lon="2.0000000"/>
</old>
</action>
<action type="create">
  <node id="4" lat="1.0000000" lon="2.0000000"/>
</action>

</osm>
)";
    const auto d = osm::read_diff(bytes_of(xml));
    ATLAS_REQUIRE(d.has_value(), osm::name_of(d.error().fault), " at ", d.error().at, ": ", d.error().what);
    CHECK(d->osm_base == "2026-10-09T08:46:19Z");
    CHECK(d->generator == "Overpass API 0.7.62.11 87bfad18");
    ATLAS_REQUIRE(d->changes.size() == 6);
    // in type and id order, as an answer lists them
    const std::array<std::pair<osm::Kind, std::int64_t>, 6> order{{{osm::Kind::node, 1},
                                                                   {osm::Kind::node, 3},
                                                                   {osm::Kind::node, 4},
                                                                   {osm::Kind::way, 5},
                                                                   {osm::Kind::relation, 9},
                                                                   {osm::Kind::relation, 10}}};
    for (std::size_t i = 0; i < order.size(); ++i) {
        CHECK(d->changes[i].kind == order.at(i).first);
        CHECK(d->changes[i].id == order.at(i).second);
    }
    CHECK(d->changes[1].gone);
    CHECK(d->changes[1].json.empty());
    CHECK(d->changes[4].gone);
    CHECK(d->changes[0].json == R"({
  "type": "node",
  "id": 1,
  "lat": 52.0500000,
  "lon": -0.0000001,
  "tags": {
    "name": "A \"quoted\" bench & <its> \\ back\nslash\ttab\rcafé ' 😀",
    "amenity": "bench"
  }
})");
    CHECK(d->changes[2].json == R"({
  "type": "node",
  "id": 4,
  "lat": 1.0000000,
  "lon": 2.0000000
})");
    CHECK(d->changes[3].json == R"({
  "type": "way",
  "id": 5,
  "bounds": {
    "minlat": 52.0000000,
    "minlon": 4.0000000,
    "maxlat": 52.1000000,
    "maxlon": 4.1000000
  },
  "nodes": [
    1,
    2
  ],
  "geometry": [
    { "lat": 52.0000000, "lon": 4.0000000 },
    { "lat": 52.1000000, "lon": 4.1000000 }
  ],
  "tags": {
    "highway": "footway"
  }
})");
    CHECK(d->changes[5].json == R"({
  "type": "relation",
  "id": 10,
  "bounds": {
    "minlat": 52.0000000,
    "minlon": 4.0000000,
    "maxlat": 52.1000000,
    "maxlon": 4.1000000
  },
  "members": [
    {
      "type": "node",
      "ref": 1,
      "role": "label",
      "lat": 52.0500000,
      "lon": 4.0500000
    },
    {
      "type": "way",
      "ref": 5,
      "role": "out\"er",
      "geometry": [
         { "lat": 52.0000000, "lon": 4.0000000 },
         { "lat": 52.1000000, "lon": 4.1000000 }
      ]
    },
    {
      "type": "relation",
      "ref": 9,
      "role": ""
    }
  ],
  "tags": {
    "type": "multipolygon"
  }
})");
    // the new forms read as Overpass's JSON reads: the decoded name, the sign of -0.0000001
    std::vector<std::string> forms;
    for (const osm::Change& c : d->changes) {
        if (!c.gone) forms.push_back(c.json);
    }
    const std::string doc_text = answer(forms, "2026-10-09T08:46:19Z");
    const auto doc = osm::read_overpass(bytes_of(doc_text));
    ATLAS_REQUIRE(doc.has_value(), osm::name_of(doc.error().fault), " at ", doc.error().at);
    ATLAS_REQUIRE(doc->elements().size() == 4);
    CHECK(doc->tags(doc->elements()[0])[0].value == "A \"quoted\" bench & <its> \\ back\nslash\ttab\rcafé ' 😀");
    CHECK(std::signbit(doc->elements()[0].at.lon));
    CHECK(doc->osm_base() == "2026-10-09T08:46:19Z");
}

TEST_CASE("osm: what a diff cannot give as a fresh answer holds is refused, named") {
    const std::string meta = R"(<?xml version="1.0" encoding="UTF-8"?><osm><meta osm_base="2026-10-09T08:46:19Z"/>)";
    const auto with = [&](std::string_view body) { return meta + std::string(body) + "</osm>"; };
    const std::vector<std::pair<std::string, Fault>> cases{
        {with("<remark> runtime error: Query timed out in \"query\" at line 1 after 240 seconds. </remark>"),
         Fault::remark},
        {with(R"(<action type="show_initial"><node id="1" lat="1" lon="2"/></action>)"), Fault::unsupported},
        {with(R"(<node id="1" lat="1" lon="2"/>)"), Fault::unsupported},                // no diff
        {with(R"(<action type="create"><node id="1"/></action>)"), Fault::unsupported}, // no position
        {with(R"(<action type="create"><way id="1"><nd ref="1"/></way></action>)"), Fault::unsupported},
        {with(R"(<action type="create"><way id="1"><tag k="a" v="b"/></way></action>)"), Fault::unsupported},
        {with(R"(<action type="create"><relation id="1"><member type="way" ref="2" role=""/></relation></action>)"),
         Fault::unsupported},
        {with(R"(<action type="create"><relation id="1"><member type="way" ref="2" role=""><nd/></member>)"
              R"(</relation></action>)"),
         Fault::unsupported},
        {with(R"(<action type="create"><relation id="1"><tag k="a" v="b"/></relation></action>)"), Fault::unsupported},
        {with(R"(<action type="create"><area id="1"/></action>)"), Fault::unsupported},
        {with(R"(<action type="create"><node id="1" lat="1" lon="2"><center lat="1" lon="2"/></node></action>)"),
         Fault::unsupported},
        {with(R"(<action type="create"><node id="1" lat="1" lon="2" visible="false"/></action>)"), Fault::unsupported},
        {with(R"(<action type="create"><node id="1" lat="1" lon="2"/></action>)"
              R"(<action type="create"><node id="1" lat="1" lon="2"/></action>)"),
         Fault::duplicate},
        {with(R"(<action type="create"><node id="1" lat="1" lon="2"><tag k="a" v="1"/><tag k="a" v="2"/>)"
              R"(</node></action>)"),
         Fault::duplicate},
        {with(R"(<action type="create"><node id="1" id="2" lat="1" lon="2"/></action>)"), Fault::duplicate},
        {with(R"(<action type="create"><node id="012" lat="1" lon="2"/></action>)"), Fault::bad_number},
        {with(R"(<action type="create"><node id="1" lat="1.2.3" lon="2"/></action>)"), Fault::bad_number},
        {with(R"(<action type="create"><node id="99999999999999999999" lat="1" lon="2"/></action>)"),
         Fault::bad_number},
        {with(R"(<action type="create"><node id="1" lat="1"/></action>)"), Fault::syntax}, // one coordinate
        {with(R"(<action type="modify"><old><node id="1" lat="1" lon="2"/></old>)"
              R"(<new><node id="2" lat="1" lon="2"/></new></action>)"),
         Fault::syntax},
        {with(R"(<action type="create"><node id="1" lat="1" lon="2"><tag k="a" v="&foo;"/></node></action>)"),
         Fault::syntax},
        {with(R"(<action type="create"><node id="1" lat="1" lon="2"><tag k="a" v="&#x1;"/></node></action>)"),
         Fault::bad_utf8},
        {with(R"(<action type="create"><node id="1" lat='1' lon="2"/></action>)"), Fault::syntax},
        {with("<action type=\"create\"><node id=\"1\" lat=\"1\" lon=\"2\"><tag k=\"a\" v=\"x\ny\"/></node></action>"),
         Fault::unsupported},
        {with("<!-- a comment -->"), Fault::unsupported},
        {with("hello"), Fault::syntax},
        {with(R"(<action type="create"><node id="1" lat="1" lon="2"/></actionx>)"), Fault::syntax},
        {with("<action type=\"create\"><node id=\"1\" lat=\"1\" lon=\"2\"/></action>\x01"), Fault::syntax},
        {with("<action type=\"create\"><node id=\"1\" lat=\"1\" lon=\"2\"><tag k=\"a\" v=\"\xff\"/></node></action>"),
         Fault::bad_utf8},
        {R"(<osm><action type="create"><node id="1" lat="1" lon="2"/></action></osm>)", Fault::no_base},
        {R"(<osm><meta osm_base="yesterday"/></osm>)", Fault::no_base},
        {meta + R"(<action type="create"><node id="1" lat="1)", Fault::truncated},
        {meta + R"(<action type="create"><node id="1" lat="1" lon="2"/></action>)", Fault::truncated},
        {"\xEF\xBB\xBF" + with(""), Fault::syntax},
    };
    for (const auto& [xml, fault] : cases) {
        const Fault got = fault_of(xml);
        CHECK_MESSAGE(got == fault, xml, ": ", osm::name_of(got), ", not ", osm::name_of(fault));
    }
    const auto remark = osm::read_diff(bytes_of(cases[0].first));
    ATLAS_REQUIRE(!remark.has_value());
    CHECK(remark.error().what == "runtime error: Query timed out in \"query\" at line 1 after 240 seconds.");
    // a diff with nothing in it: the data's date moves on
    const auto empty = osm::read_diff(bytes_of(with("")));
    ATLAS_REQUIRE(empty.has_value(), empty.error().what);
    CHECK(empty->changes.empty());
}

TEST_CASE("osm: the Westerkerk's answer merged with a diff of its edits is the edited answer and builds its window") {
    const std::string kept = read_file(std::string(ATLAS_TESTS_DIR) + "/../../../../../data/ams/raw.json");
    ATLAS_REQUIRE(!kept.empty(), "data/ams/raw.json is not in the repository");
    const auto doc = osm::read_overpass(bytes_of(kept));
    ATLAS_REQUIRE(doc.has_value());
    const std::span<const osm::Element> all = doc->elements();
    const auto slice = [&](std::size_t i) {
        return std::string_view(kept).substr(all[i].from, all[i].to - all[i].from);
    };
    // the edits, each by its element's place
    std::size_t tag_node = all.size();   // a node's last tag
    std::size_t gone_node = all.size();  // deleted (a plain diff's form)
    std::size_t moved_way = all.size();  // a street's point moved
    std::size_t same_way = all.size();   // named again, unchanged
    std::size_t away_way = all.size();   // out of the query's result
    std::size_t edited_rel = all.size(); // a member's point and a tag
    std::size_t gone_rel = all.size();   // deleted (an augmented diff's form)
    std::size_t gap = all.size();        // a node id free after this node
    std::size_t last_node = 0;
    std::size_t last_way = 0;
    std::size_t smallest_rel = 0;
    for (std::size_t i = 0; i < all.size(); ++i) {
        const osm::Element& e = all[i];
        const auto plain = [](std::string_view v) {
            return std::ranges::all_of(v, [](char c) { return c != '"' && c != '\\'; });
        };
        if (e.kind == osm::Kind::node) {
            last_node = i;
            if (tag_node == all.size() && e.tags.count >= 2 && plain(doc->tags(e).back().value)) {
                tag_node = i;
            } else if (tag_node != all.size() && gone_node == all.size() && e.has_tags) {
                gone_node = i;
            }
            if (gap == all.size() && i + 1 < all.size() && all[i + 1].kind == osm::Kind::node &&
                all[i + 1].id > e.id + 1 && i > tag_node) {
                gap = i;
            }
        } else if (e.kind == osm::Kind::way) {
            last_way = i;
            const bool street = std::ranges::any_of(doc->tags(e), [](const osm::Tag& t) { return t.key == "highway"; });
            if (moved_way == all.size() && street && e.geometry.count >= 3) {
                moved_way = i;
            } else if (moved_way != all.size() && same_way == all.size()) {
                same_way = i;
            } else if (same_way != all.size() && away_way == all.size()) {
                away_way = i;
            }
        } else if (e.kind == osm::Kind::relation) {
            const bool way_member = std::ranges::any_of(
                doc->members(e), [](const osm::Member& m) { return m.has_geometry && m.geometry.count >= 2; });
            if (way_member && plain(e.tags.count == 0 ? "" : doc->tags(e).back().value) && e.tags.count != 0 &&
                (edited_rel == all.size() || slice(i).size() < slice(edited_rel).size())) {
                edited_rel = i;
            }
            if (smallest_rel == 0 || slice(i).size() < slice(smallest_rel).size()) smallest_rel = i;
        }
    }
    for (std::size_t i = 0; i < all.size(); ++i) {
        if (all[i].kind == osm::Kind::relation && i != edited_rel) {
            gone_rel = i;
            break;
        }
    }
    ATLAS_REQUIRE(tag_node < all.size() && gone_node < all.size() && moved_way < all.size() && same_way < all.size() &&
                  away_way < all.size() && edited_rel < all.size() && gone_rel < all.size() && gap < all.size());
    // the edited elements, as Overpass's JSON would print them
    std::string tagged(slice(tag_node));
    {
        const osm::Tag t = doc->tags(all[tag_node]).back();
        const std::string was = std::format(R"("{}": "{}")", t.key, t.value);
        const std::size_t at = tagged.rfind(was);
        ATLAS_REQUIRE(at != std::string::npos);
        tagged.replace(at, was.size(), std::format("\"{}\": \"{} (2026)\"", t.key, t.value));
    }
    std::string moved(slice(moved_way));
    ATLAS_REQUIRE(move_point(moved, moved.find("{ \"lat\": ", moved.find("\"geometry\": [")) + 1));
    std::string edited(slice(edited_rel));
    {
        ATLAS_REQUIRE(move_point(edited, edited.find("\"geometry\": [")));
        const osm::Tag t = doc->tags(all[edited_rel]).back();
        const std::string was = std::format(R"("{}": "{}")", t.key, t.value);
        const std::size_t at = edited.rfind(was);
        ATLAS_REQUIRE(at != std::string::npos);
        edited.replace(at, was.size(), std::format(R"("{}": "{}, again")", t.key, t.value));
    }
    // the created elements, as Overpass's JSON prints them
    const std::string between = std::format(R"({{
  "type": "node",
  "id": {},
  "lat": 52.3745000,
  "lon": 4.8838000,
  "tags": {{
    "amenity": "bench",
    "name": "A \"quoted\" bench & <its> \\ back\nslash\ttab\rcafé"
  }}
}})",
                                            all[gap].id + 1);
    const std::string after_nodes = std::format(R"({{
  "type": "node",
  "id": {},
  "lat": 52.3746000,
  "lon": 4.8839000,
  "tags": {{
    "natural": "tree"
  }}
}})",
                                                all[last_node].id + 1);
    const std::string after_ways = std::format(R"({{
  "type": "way",
  "id": {},
  "bounds": {{
    "minlat": 52.3745000,
    "minlon": 4.8838000,
    "maxlat": 52.3746000,
    "maxlon": 4.8839000
  }},
  "nodes": [
    {},
    {}
  ],
  "geometry": [
    {{ "lat": 52.3745000, "lon": 4.8838000 }},
    {{ "lat": 52.3746000, "lon": 4.8839000 }}
  ],
  "tags": {{
    "highway": "footway"
  }}
}})",
                                               all[last_way].id + 1, all[gap].id + 1, all[last_node].id + 1);
    const std::string after_rels =
        std::format(R"({{
  "type": "relation",
  "id": {},
  "bounds": {{
    "minlat": 52.3745000,
    "minlon": 4.8838000,
    "maxlat": 52.3746000,
    "maxlon": 4.8839000
  }},
  "members": [
    {{
      "type": "node",
      "ref": {},
      "role": "label",
      "lat": 52.3745000,
      "lon": 4.8838000
    }},
    {{
      "type": "way",
      "ref": {},
      "role": "outer",
      "geometry": [
         {{ "lat": 52.3745000, "lon": 4.8838000 }},
         {{ "lat": 52.3746000, "lon": 4.8839000 }}
      ]
    }},
    {{
      "type": "relation",
      "ref": {},
      "role": "sub\"area"
    }}
  ],
  "tags": {{
    "type": "multipolygon",
    "landuse": "grass"
  }}
}})",
                    all.back().id + 1, all[gap].id + 1, all[last_way].id + 1, all[smallest_rel].id);
    // the edited answer, element by element, and the diff of the edits: relations first, as no order is assumed
    std::vector<std::string> fresh;
    std::string actions = remove(slice(gone_rel), 1) + create(after_rels) + modify(slice(edited_rel), edited);
    for (std::size_t i = 0; i < all.size(); ++i) {
        const std::string_view was = slice(i);
        if (i == tag_node) {
            fresh.push_back(tagged);
            actions += modify(was, tagged);
        } else if (i == moved_way) {
            fresh.push_back(moved);
            actions += modify(was, moved);
        } else if (i == edited_rel) {
            fresh.push_back(edited);
        } else if (i == same_way) {
            fresh.emplace_back(was);
            actions += modify(was, was);
        } else if (i == gone_node) {
            actions += remove(was, 0);
        } else if (i == away_way) {
            actions += remove(was, 2);
        } else if (i != gone_rel) {
            fresh.emplace_back(was);
        }
        if (i == gap) {
            fresh.push_back(between);
            actions += create(between);
        }
        if (i == last_node) {
            fresh.push_back(after_nodes);
            actions += create(after_nodes);
        }
        if (i == last_way) fresh.push_back(after_ways);
        if (i + 1 == all.size()) fresh.push_back(after_rels);
    }
    actions += create(after_ways);
    const std::string xml = diff_xml(actions);
    const auto diff = osm::read_diff(bytes_of(xml));
    ATLAS_REQUIRE(diff.has_value(), osm::name_of(diff.error().fault), " at ", diff.error().at, ": ", diff.error().what);
    const auto merged = osm::merge_diff(bytes_of(kept), *diff);
    ATLAS_REQUIRE(merged.has_value(), osm::name_of(merged.error().fault), ": ", merged.error().what);
    CHECK(merged->replaced == 3);
    CHECK(merged->added == 4);
    CHECK(merged->removed == 3);
    CHECK(merged->absent == 0);
    CHECK(merged->kept == all.size() - 6);
    CHECK(merged->changed());
    // element for element the edited answer's bytes
    const std::string want = answer(fresh, "2026-10-09T08:46:19Z");
    CHECK(elements_of(merged->json) == elements_of(want));
    // and read as it reads
    const auto ours = osm::read_overpass(bytes_of(merged->json));
    ATLAS_REQUIRE(ours.has_value(), osm::name_of(ours.error().fault), " at ", ours.error().at);
    const auto theirs = osm::read_overpass(bytes_of(want));
    ATLAS_REQUIRE(theirs.has_value());
    CHECK(dump(*ours) == dump(*theirs));
    CHECK(ours->osm_base() == "2026-10-09T08:46:19Z"); // the diff's date
    CHECK(ours->whole() == doc->osm_base());           // fetched whole at the answer's
    // the window built from the merged data is the window the edited answer builds (the same source named for both)
    const tl::TileKey tile = tl::tile_of(14, 523'746'000, 48'840'000); // the Westerkerk's
    const load::WindowPlace place = load::place_of(tile);
    const auto from_merged = load::build_window(*ours, tl::Sha256Digest{}, tile, place);
    ATLAS_REQUIRE(from_merged.has_value(), from_merged.error().what);
    const auto from_fresh = load::build_window(*theirs, tl::Sha256Digest{}, tile, place);
    ATLAS_REQUIRE(from_fresh.has_value(), from_fresh.error().what);
    CHECK(from_merged->pack == from_fresh->pack);
    const auto from_kept = load::build_window(*doc, tl::Sha256Digest{}, tile, place);
    ATLAS_REQUIRE(from_kept.has_value(), from_kept.error().what);
    CHECK_MESSAGE(from_kept->pack != from_fresh->pack, "the edits change the window"); // the test sees a difference
    MESSAGE("merged ", all.size(), " elements: ", merged->kept, " kept, ", merged->replaced, " replaced, ",
            merged->added, " added, ", merged->removed, " removed; the window ", from_merged->features, " features");
    // merged again with a diff that names nothing: the elements stay, the date moves, the whole date stays
    const std::string later = R"(<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="Overpass API 0.7.62.11 87bfad18">
<meta osm_base="2026-10-16T08:46:19Z"/>
</osm>
)";
    const auto none = osm::read_diff(bytes_of(later));
    ATLAS_REQUIRE(none.has_value());
    const auto again = osm::merge_diff(bytes_of(merged->json), *none);
    ATLAS_REQUIRE(again.has_value(), again.error().what);
    CHECK(!again->changed());
    CHECK(elements_of(again->json) == elements_of(merged->json));
    const auto reread = osm::read_overpass(bytes_of(again->json));
    ATLAS_REQUIRE(reread.has_value());
    CHECK(reread->osm_base() == "2026-10-16T08:46:19Z");
    CHECK(reread->whole() == doc->osm_base());
}

TEST_CASE("osm: kept data that is not one answer in type and id order is refused") {
    const std::string later = R"(<osm><meta osm_base="2026-10-16T08:46:19Z"/></osm>)";
    const auto none = osm::read_diff(bytes_of(later));
    ATLAS_REQUIRE(none.has_value());
    const std::vector<std::string> refused{
        // out of order, a node after a way, an element twice, another type, no date, no elements
        R"({"osm3s": {"timestamp_osm_base": "2026-10-09T08:46:19Z"}, "elements": [
            {"type": "node", "id": 2, "lat": 1, "lon": 2}, {"type": "node", "id": 1, "lat": 1, "lon": 2}]})",
        R"({"osm3s": {"timestamp_osm_base": "2026-10-09T08:46:19Z"}, "elements": [
            {"type": "way", "id": 1, "nodes": [1]}, {"type": "node", "id": 2, "lat": 1, "lon": 2}]})",
        R"({"osm3s": {"timestamp_osm_base": "2026-10-09T08:46:19Z"}, "elements": [
            {"type": "node", "id": 1, "lat": 1, "lon": 2}, {"type": "node", "id": 1, "lat": 1, "lon": 2}]})",
        R"({"osm3s": {"timestamp_osm_base": "2026-10-09T08:46:19Z"}, "elements": [{"type": "area", "id": 1}]})",
        R"({"elements": [{"type": "node", "id": 1, "lat": 1, "lon": 2}]})",
        R"({"osm3s": {"timestamp_osm_base": "2026-10-09T08:46:19Z"}})",
        R"({"osm3s": {"timestamp_osm_base": "2026-10-09T08:46:19Z"}, "atlas": {"whole": "then"}, "elements": []})",
    };
    for (const std::string& kept : refused) {
        const auto m = osm::merge_diff(bytes_of(kept), *none);
        CHECK_MESSAGE(!m.has_value(), kept);
        if (!m) CHECK(m.error().fault == Fault::kept);
    }
    // the reader takes the head's dates, and nothing else of it changes
    const std::string ok =
        R"({"osm3s": {"copyright": "x", "timestamp_osm_base": "2026-10-09T08:46:19Z"}, "atlas": {"whole": "2026-10-01T00:00:00Z"}, "elements": []})";
    const auto m = osm::merge_diff(bytes_of(ok), *none);
    ATLAS_REQUIRE(m.has_value(), m.error().what);
    const auto d = osm::read_overpass(bytes_of(m->json));
    ATLAS_REQUIRE(d.has_value());
    CHECK(d->whole() == "2026-10-01T00:00:00Z");
    CHECK(d->osm_base() == "2026-10-16T08:46:19Z");
    CHECK(d->elements().empty());
}

TEST_CASE("osm: a real answer merged with Overpass's own diff since its date reads as a fresh answer "
          "(ATLAS_DIFF_KEPT, ATLAS_DIFF_XML, ATLAS_DIFF_FRESH)") {
    const std::string kept_path = atlas::test::environment("ATLAS_DIFF_KEPT");
    const std::string xml_path = atlas::test::environment("ATLAS_DIFF_XML");
    const std::string fresh_path = atlas::test::environment("ATLAS_DIFF_FRESH");
    if (kept_path.empty() || xml_path.empty() || fresh_path.empty()) {
        MESSAGE("ATLAS_DIFF_KEPT, ATLAS_DIFF_XML and ATLAS_DIFF_FRESH are not set: no real diff is merged here");
        return;
    }
    const std::string kept = read_file(kept_path);
    const std::string xml = read_file(xml_path);
    const std::string fresh = read_file(fresh_path);
    ATLAS_REQUIRE(!kept.empty() && !xml.empty() && !fresh.empty());
    const auto diff = osm::read_diff(bytes_of(xml));
    ATLAS_REQUIRE(diff.has_value(), osm::name_of(diff.error().fault), " at ", diff.error().at, ": ", diff.error().what);
    const auto merged = osm::merge_diff(bytes_of(kept), *diff);
    ATLAS_REQUIRE(merged.has_value(), osm::name_of(merged.error().fault), ": ", merged.error().what);
    const auto ours = osm::read_overpass(bytes_of(merged->json));
    ATLAS_REQUIRE(ours.has_value());
    const auto theirs = osm::read_overpass(bytes_of(fresh));
    ATLAS_REQUIRE(theirs.has_value());
    const std::string a = dump(*ours);
    const std::string b = dump(*theirs);
    if (a != b) {
        // the first element that differs, to name it
        std::size_t line = 0;
        std::size_t i = 0;
        while (i < a.size() && i < b.size() && a[i] == b[i]) {
            if (a[i] == '\n') ++line;
            ++i;
        }
        FAIL_CHECK("the merged answer differs from the fresh one at element ", line, ": ",
                   a.substr(a.rfind('\n', i) + 1, 160), " | ", b.substr(b.rfind('\n', i) + 1, 160));
    }
    CHECK(elements_of(merged->json) == elements_of(fresh));
    MESSAGE("a real diff since ", ours->whole(), " to ", diff->osm_base, ": ", diff->changes.size(), " changes, ",
            merged->kept, " kept, ", merged->replaced, " replaced, ", merged->added, " added, ", merged->removed,
            " removed, ", merged->absent, " absent; ", theirs->elements().size(), " elements fresh");
}
