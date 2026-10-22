// Stage P's loaders' test entry points in the host build (load/Amsterdam.hpp, text/Case.hpp), for :mapcorecheck's
// AmsterdamParityTest on the JVM, which holds every record and field the core loads from a raw.json to the port's own
// Amsterdam.load, and CaseParityTest, which holds the core's capitalize() to the port's over every code point. The
// scene is written as canonical lines, one a record, which the test writes the same way from the port's dicts:
//   - a double as the 16 hex digits of its bits; a missing value as `-`; a string as `'` and its UTF-8 bytes, each
//     byte other than a letter, a digit or one of `_.:#-` as `%` and two upper-case hex digits; a field the dict lacks
//     as `~`;
//   - points as their count, then `,x,z` each; tags as their count, then `|key=value` each in the document's order;
//   - the lists in the scene's order, each record `<list> <index> ` and its fields as name=value, in the order below.
// It binds to the Kotlin object app.atlas.mapcorecheck.load.LoadHost by JNI's names; nothing of it is in the phone's
// library.
#include "load/Amsterdam.hpp"
#include "load/Regions.hpp"
#include "osm/Overpass.hpp"
#include "text/Case.hpp"

#include <jni.h>

#include <bit>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <format>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

namespace load = atlas::load;
namespace osm = atlas::osm;

/// The layout version of these entry points; LoadHost refuses another.
constexpr jint test_load_abi = 2;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

/// One record's canonical line.
class Line {
public:
    Line(std::string_view list, std::size_t index) { out_ = std::format("{} {}", list, index); }

    Line& field(std::string_view name) {
        out_ += ' ';
        out_ += name;
        out_ += '=';
        return *this;
    }
    Line& real(double v) {
        out_ += std::format("{:016x}", std::bit_cast<std::uint64_t>(v));
        return *this;
    }
    Line& real(const std::optional<double>& v) { return v ? real(*v) : raw("-"); }
    Line& integer(std::int64_t v) {
        out_ += std::to_string(v);
        return *this;
    }
    Line& integer(const std::optional<std::int64_t>& v) { return v ? integer(*v) : raw("-"); }
    Line& flag(bool v) { return raw(v ? "1" : "0"); }
    Line& text(std::string_view s) {
        out_ += '\'';
        for (const char c : s) {
            const auto b = static_cast<unsigned char>(c);
            const bool plain = (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || b == '_' ||
                               b == '.' || b == ':' || b == '#' || b == '-';
            if (plain) {
                out_ += c;
            } else {
                out_ += std::format("%{:02X}", b);
            }
        }
        return *this;
    }
    Line& maybe(const std::optional<std::string_view>& s) { return s ? text(*s) : raw("-"); }
    Line& points(std::span<const load::V2> p) {
        out_ += std::to_string(p.size());
        for (const load::V2& q : p) {
            out_ += ',';
            real(q.x);
            out_ += ',';
            real(q.z);
        }
        return *this;
    }
    Line& tags(const load::Tags& t) {
        out_ += std::to_string(t.all().size());
        for (const osm::Tag& tag : t.all()) {
            out_ += '|';
            text(tag.key);
            out_ += '=';
            text(tag.value);
        }
        return *this;
    }
    Line& raw(std::string_view s) {
        out_ += s;
        return *this;
    }
    [[nodiscard]] const std::string& str() const noexcept { return out_; }

private:
    std::string out_;
};

std::string_view source_of(load::HeightSource s) {
    switch (s) {
    case load::HeightSource::height: return "height";
    case load::HeightSource::levels: return "levels";
    case load::HeightSource::none: break;
    }
    return "-";
}

/// A building's line; for a region, without what generic.py's looks set or change (they join when they are ported).
void building(std::ofstream& out, std::string_view list, std::size_t i, const load::Building& b, bool part,
              bool region) {
    Line l(list, i);
    l.field("id").integer(b.id);
    l.field("src").raw(source_of(b.src));
    if (region) {
        l.field("min").real(b.min);
        l.field("year").integer(b.year);
        l.field("otype").raw(b.relation ? "relation" : "~");
        l.field("parent_id");
        if (b.parent_id) {
            l.integer(*b.parent_id);
        } else {
            l.raw("~");
        }
        l.field("in_church").raw(b.in_church ? "1" : "~");
        l.field("bb").real(b.bb.x0).raw(",").real(b.bb.x1).raw(",").real(b.bb.z0).raw(",").real(b.bb.z1);
        l.field("places");
        if (b.places.empty()) {
            l.raw("~");
        } else {
            for (std::size_t k = 0; k < b.places.size(); ++k) {
                if (k > 0) l.raw(",");
                l.integer(b.places[k]);
            }
        }
        l.field("hidden");
        if (part) {
            l.raw("~");
        } else {
            l.flag(b.hidden);
        }
        l.field("poly").points(b.poly);
        l.field("tags").tags(b.tags);
        out << l.str() << '\n';
        return;
    }

    l.field("h").real(b.h);
    l.field("min").real(b.min);
    l.field("year").integer(b.year);
    l.field("roof").maybe(b.roof);
    l.field("roof_h").real(b.roof_h);
    l.field("orient").maybe(b.orient);
    l.field("mat").maybe(b.mat);
    l.field("rmat").maybe(b.rmat);
    l.field("col").maybe(b.col);
    l.field("rcol").maybe(b.rcol);
    l.field("mat_draw");
    if (b.mat_draw) {
        l.text(*b.mat_draw);
    } else {
        l.raw("~");
    }
    l.field("levels").integer(b.levels);
    l.field("guessed");
    for (std::size_t k = 0; k < b.guessed.keys().size(); ++k) {
        if (k > 0) l.raw(",");
        l.raw(load::key_of(b.guessed.keys()[k]));
    }
    l.field("bb").real(b.bb.x0).raw(",").real(b.bb.x1).raw(",").real(b.bb.z0).raw(",").real(b.bb.z1);
    l.field("places");
    if (b.places.empty()) {
        l.raw("~");
    } else {
        for (std::size_t k = 0; k < b.places.size(); ++k) {
            if (k > 0) l.raw(",");
            l.integer(b.places[k]);
        }
    }
    l.field("hidden");
    if (part) {
        l.raw("~");
    } else {
        l.flag(b.hidden);
    }
    l.field("poly").points(b.poly);
    l.field("tags").tags(b.tags);
    out << l.str() << '\n';
}

/// An item's line (a crane, mast, tower or turbine).
void item(std::ofstream& out, std::string_view list, std::size_t i, const load::Item& it) {
    Line l(list, i);
    l.field("id").integer(it.id);
    l.field("x").real(it.x);
    l.field("z").real(it.z);
    l.field("h").real(it.h);
    l.field("rotor").real(it.rotor);
    l.field("poly");
    if (it.poly) {
        l.points(*it.poly);
    } else {
        l.raw("~");
    }
    l.field("tags").tags(it.tags);
    out << l.str() << '\n';
}

/// The lists regions.py's load adds, after the living map's; the number of lines.
std::size_t write_region(std::ofstream& out, const load::Scene& s) {
    std::size_t lines = 0;
    for (std::size_t i = 0; i < s.land.size(); ++i, ++lines) {
        const load::Land& a = s.land[i];
        Line l("land", i);
        l.field("kind").text(a.kind).field("id").integer(a.id).field("poly").points(a.poly).field("tags").tags(a.tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.aero.size(); ++i, ++lines) {
        const load::Aero& a = s.aero[i];
        Line l("aero", i);
        l.field("kind").text(a.kind).field("id").integer(a.id).field("pts").points(a.pts).field("tags").tags(a.tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.big.size(); ++i, ++lines) {
        const load::BigRoad& r = s.big[i];
        Line l("big", i);
        l.field("hw").text(r.hw).field("layer").real(r.layer).field("id").integer(r.id);
        l.field("pts").points(r.pts).field("tags").tags(r.tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.rail.size(); ++i, ++lines) {
        const load::Railway& r = s.rail[i];
        Line l("railway", i);
        l.field("crane").flag(r.crane).field("pts").points(r.pts).field("tags").tags(r.tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.power.size(); ++i, ++lines) {
        const load::PowerLine& r = s.power[i];
        Line l("power", i);
        l.field("pts").points(r.pts).field("tags").tags(r.tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.marine.size(); ++i, ++lines) {
        const load::Marine& m = s.marine[i];
        Line l("marine", i);
        l.field("kind").text(m.kind).field("closed").flag(m.closed).field("pts").points(m.pts);
        l.field("tags").tags(m.tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.cranes.size(); ++i, ++lines) item(out, "crane", i, s.cranes[i]);
    for (std::size_t i = 0; i < s.masts.size(); ++i, ++lines) item(out, "mast", i, s.masts[i]);
    for (const auto& [list, gantries] : {std::pair{"gantry", &s.gantries}, std::pair{"conveyor", &s.conveyors}}) {
        for (std::size_t i = 0; i < gantries->size(); ++i, ++lines) {
            const load::Gantry& g = (*gantries)[i];
            Line l(list, i);
            l.field("id").integer(g.id).field("h").real(g.h).field("pts").points(g.pts).field("tags").tags(g.tags);
            out << l.str() << '\n';
        }
    }
    for (std::size_t i = 0; i < s.turbines.size(); ++i, ++lines) item(out, "turbine", i, s.turbines[i]);
    for (std::size_t i = 0; i < s.mtowers.size(); ++i, ++lines) item(out, "mtower", i, s.mtowers[i]);
    for (std::size_t i = 0; i < s.towers.size(); ++i, ++lines) {
        const load::Tower& t = s.towers[i];
        Line l("tower", i);
        l.field("id").integer(t.id).field("x").real(t.at.x).field("z").real(t.at.z).field("h").real(t.h);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.park.size(); ++i, ++lines) {
        Line l("park", i);
        l.field("ring").points(s.park[i].ring).field("tags").tags(s.park[i].tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.water_holes.size(); ++i, ++lines) {
        out << Line("hole", i).field("ring").points(s.water_holes[i]).str() << '\n';
    }
    for (std::size_t i = 0; i < s.natural_shore.size(); ++i) {
        if (!s.natural_shore[i]) continue;
        out << Line("shore", i).str() << '\n';
        ++lines;
    }
    return lines;
}

/// The scene's canonical lines, written to `out` (a region's with its own lists); the number of lines.
std::size_t write_scene(std::ofstream& out, const load::Scene& s, bool region = false) {
    std::size_t lines = 0;
    for (std::size_t i = 0; i < s.buildings.size(); ++i, ++lines) {
        building(out, "building", i, s.buildings[i], false, region);
    }
    for (std::size_t i = 0; i < s.parts.size(); ++i, ++lines) building(out, "part", i, s.parts[i], true, region);

    for (std::size_t i = 0; i < s.water.size(); ++i, ++lines) {
        out << Line("water", i).field("ring").points(s.water[i]).str() << '\n';
    }
    for (std::size_t i = 0; i < s.roads.size(); ++i, ++lines) {
        const load::Road& r = s.roads[i];
        Line l("road", i);
        l.field("id").integer(r.id);
        l.field("hw").text(r.hw);
        l.field("w").real(r.w);
        l.field("layer").real(r.layer);
        l.field("bridge").flag(r.bridge);
        l.field("wguess").flag(r.wguess);
        l.field("pts").points(r.pts);
        l.field("tags").tags(r.tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.rails.size(); ++i, ++lines) {
        out << Line("rail", i).field("pts").points(s.rails[i]).str() << '\n';
    }
    for (std::size_t i = 0; i < s.areas.size(); ++i, ++lines) {
        const load::Area& a = s.areas[i];
        out << Line("area", i).field("kind").text(a.kind).field("poly").points(a.poly).field("tags").tags(a.tags).str()
            << '\n';
    }
    for (std::size_t i = 0; i < s.trees.size(); ++i, ++lines) {
        const load::Tree& t = s.trees[i];
        Line l("tree", i);
        l.field("node").integer(t.node);
        l.field("h").real(t.h);
        l.field("crown").real(t.crown);
        std::vector<std::string_view> keys;
        if (t.guessed.height) keys.emplace_back("height");
        if (t.guessed.diameter_crown) keys.emplace_back("diameter_crown");
        if (t.guessed.leaf_type) keys.emplace_back("leaf_type");
        l.field("guessed");
        for (std::size_t k = 0; k < keys.size(); ++k) {
            if (k > 0) l.raw(",");
            l.raw(keys[k]);
        }
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.nodes.size(); ++i, ++lines) {
        const load::Node& n = s.nodes[i];
        Line l("node", i);
        l.field("id").integer(n.id);
        l.field("x").real(n.x);
        l.field("z").real(n.z);
        l.field("style");
        if (n.style) {
            const load::PlaceStyle& st = *n.style;
            l.text(st.cat).raw(",").text(st.col).raw(",").text(st.glyph).raw(",").text(st.kind).raw(",");
            l.maybe(st.brand).raw(",").maybe(st.bg).raw(",").maybe(st.fg);
        } else {
            l.raw("~");
        }
        l.field("building");
        if (n.building) {
            l.integer(*n.building);
        } else {
            l.raw("~");
        }
        l.field("tags").tags(n.tags);
        out << l.str() << '\n';
    }
    for (std::size_t i = 0; i < s.pois.size(); ++i, ++lines) {
        out << Line("poi", i).field("node").integer(s.pois[i]).str() << '\n';
    }
    for (std::size_t i = 0; i < s.labels.size(); ++i, ++lines) {
        const load::Label& lb = s.labels[i];
        out << Line("label", i)
                   .field("kind")
                   .text(lb.kind)
                   .field("name")
                   .text(lb.name)
                   .field("pts")
                   .points(lb.pts)
                   .str()
            << '\n';
    }
    if (region) lines += write_region(out, s);
    return lines;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_load_LoadHost_abi(JNIEnv*, jclass) { return test_load_abi; }

/// The living map's load of the raw.json at `raw`, in the Westerkerk's frame, written as canonical lines to `out`:
/// `lines=<n> buildings=<n> parts=<n> nodes=<n> read_ms=<ms> load_ms=<ms>`, or `error=<fault> at=<where>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_load_LoadHost_dump(JNIEnv* env, jclass, jstring raw,
                                                                         jstring out) {
    std::ifstream in(string_of(env, raw), std::ios::binary);
    if (!in) return env->NewStringUTF("error=io at=raw");
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto t0 = std::chrono::steady_clock::now();
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    const auto t1 = std::chrono::steady_clock::now();
    if (!doc) {
        return env->NewStringUTF(
            std::format("error={} at={}", osm::name_of(doc.error().fault), doc.error().at).c_str());
    }
    const auto scene = load::load_amsterdam(*doc, load::westerkerk_frame(), load::amsterdam_palette());
    const auto t2 = std::chrono::steady_clock::now();
    if (!scene) {
        return env->NewStringUTF(
            std::format("error={} at={}", load::name_of(scene.error().fault), scene.error().element).c_str());
    }
    std::ofstream file(string_of(env, out), std::ios::binary);
    if (!file) return env->NewStringUTF("error=io at=out");
    const std::size_t lines = write_scene(file, *scene);
    file.close();
    if (!file) return env->NewStringUTF("error=io at=write");
    const auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    const std::string summary =
        std::format("lines={} buildings={} parts={} nodes={} read_ms={:.1f} load_ms={:.1f}", lines,
                    scene->buildings.size(), scene->parts.size(), scene->nodes.size(), ms(t0, t1), ms(t1, t2));
    return env->NewStringUTF(summary.c_str());
}

/// A region's load (regions.py's, in the frame and box given: the port's after its configure) of the raw.json at `raw`,
/// at `tolerance`, written as canonical lines to `out`: as dump, its buildings without what generic.py's looks set, and
/// its own lists.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_load_LoadHost_dumpRegion(
    JNIEnv* env, jclass, jstring raw, jstring out, jstring name, jdouble lat0, jdouble lon0, jdouble kx, jdouble kz,
    jdouble south, jdouble west, jdouble north, jdouble east, jstring tradition, jdouble tolerance) {
    std::ifstream in(string_of(env, raw), std::ios::binary);
    if (!in) return env->NewStringUTF("error=io at=raw");
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto t0 = std::chrono::steady_clock::now();
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    const auto t1 = std::chrono::steady_clock::now();
    if (!doc) {
        return env->NewStringUTF(
            std::format("error={} at={}", osm::name_of(doc.error().fault), doc.error().at).c_str());
    }
    const std::string region = string_of(env, name);
    const std::string trad = tradition != nullptr ? string_of(env, tradition) : std::string();
    const load::RegionConfig config{
        .name = region,
        .frame = {.lat0 = lat0, .lon0 = lon0, .kx = kx, .kz = kz},
        .bbox = {south, west, north, east},
        .tradition = tradition != nullptr ? std::optional<std::string_view>(trad) : std::nullopt,
        .palette = load::region_palette(region),
    };
    const auto scene = load::load_region(*doc, config, tolerance);
    const auto t2 = std::chrono::steady_clock::now();
    if (!scene) {
        return env->NewStringUTF(
            std::format("error={} at={}", load::name_of(scene.error().fault), scene.error().element).c_str());
    }
    std::ofstream file(string_of(env, out), std::ios::binary);
    if (!file) return env->NewStringUTF("error=io at=out");
    const std::size_t lines = write_scene(file, *scene, true);
    file.close();
    if (!file) return env->NewStringUTF("error=io at=write");
    const auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    const std::string summary = std::format(
        "lines={} buildings={} parts={} land={} water={} read_ms={:.1f} load_ms={:.1f}", lines, scene->buildings.size(),
        scene->parts.size(), scene->land.size(), scene->water.size(), ms(t0, t1), ms(t1, t2));
    return env->NewStringUTF(summary.c_str());
}

/// The core's capitalize() of each UTF-8 text of `bytes` (`lengths` bytes each, in order), each result as a u32 length
/// (little-endian) and its UTF-8.
JNIEXPORT jbyteArray JNICALL Java_app_atlas_mapcorecheck_load_LoadHost_capitalize(JNIEnv* env, jclass, jbyteArray bytes,
                                                                                  jintArray lengths) {
    const jsize n = env->GetArrayLength(lengths);
    std::vector<jint> length(static_cast<std::size_t>(n));
    if (n > 0) env->GetIntArrayRegion(lengths, 0, n, length.data());
    const jsize total = env->GetArrayLength(bytes);
    std::string all(static_cast<std::size_t>(total), '\0');
    if (total > 0) env->GetByteArrayRegion(bytes, 0, total, reinterpret_cast<jbyte*>(all.data()));
    std::string out;
    std::size_t at = 0;
    for (const jint len : length) {
        const std::string c = atlas::text::capitalize(std::string_view(all).substr(at, static_cast<std::size_t>(len)));
        at += static_cast<std::size_t>(len);
        const auto size = static_cast<std::uint32_t>(c.size());
        for (unsigned k = 0; k < 4; ++k) out += static_cast<char>((size >> (8U * k)) & 0xFFU);
        out += c;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (result != nullptr && !out.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    }
    return result;
}

} // extern "C"
