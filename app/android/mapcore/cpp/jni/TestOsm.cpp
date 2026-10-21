// The Overpass reader's test entry point in the host build (osm/Overpass.hpp), for :mapcorecheck's OverpassParityTest
// on the JVM, which holds every value the core reads of a region's raw.json to the port's own json.load (PyJson): the
// elements' canonical bytes, hashed, beside their counts and the time the read took. The canonical bytes, per element
// in the document's order, all little-endian: the type (a u32 length and its UTF-8), a flag byte (id 1, position 2,
// tags 4, nodes 8, geometry 16, members 32), then where flagged the id (i64), the position (each coordinate's bits,
// u64), the tags (u32 count; each key and value as a u32 length and UTF-8), the nodes (u32 count, i64 each), the
// geometry (u32 count, each point's bits), the members (u32 count; each its type's code, 0 node, 1 way, 2 relation, 3
// another, its ref (i64), a flag byte (role 1, geometry 2), its role (u32 length and UTF-8) and its geometry (u32
// count, bits)). It binds to the Kotlin object app.atlas.mapcorecheck.osm.OsmHost by JNI's names; nothing of it is in
// the phone's library.
#include "osm/Overpass.hpp"
#include "tiles/Checksums.hpp"

#include <jni.h>

#include <bit>
#include <chrono>
#include <cstdint>
#include <format>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

namespace osm = atlas::osm;

/// The layout version of these entry points; OsmHost refuses another.
constexpr jint test_osm_abi = 1;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

/// The canonical bytes, fed to the hash as they are made.
class Canonical {
public:
    void u8(std::uint64_t v) { put(v, 1); }
    void u32(std::uint64_t v) { put(v, 4); }
    void u64(std::uint64_t v) { put(v, 8); }
    void i64(std::int64_t v) { u64(static_cast<std::uint64_t>(v)); }
    void real(double v) { u64(std::bit_cast<std::uint64_t>(v)); }
    void text(std::string_view s) {
        u32(s.size());
        hash_.update(std::as_bytes(std::span(s.data(), s.size())));
    }
    void points(std::span<const osm::LatLon> p) {
        u32(p.size());
        for (const osm::LatLon& q : p) {
            real(q.lat);
            real(q.lon);
        }
    }
    [[nodiscard]] atlas::tiles::Sha256Digest finish() { return hash_.finish(); }

private:
    void put(std::uint64_t v, unsigned n) {
        std::array<std::byte, 8> b{};
        for (unsigned k = 0; k < n; ++k) b[k] = static_cast<std::byte>(v >> (8U * k));
        hash_.update(std::span(b).first(n));
    }

    atlas::tiles::Sha256 hash_;
};

[[nodiscard]] std::uint8_t code_of(osm::Kind k) noexcept { return static_cast<std::uint8_t>(k); }

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_osm_OsmHost_abi(JNIEnv*, jclass) { return test_osm_abi; }

/// The document at `path` read: `sha256=<hex> elements=<n> nodes=<n> ways=<n> relations=<n> ms=<read>`, or
/// `error=<fault> at=<byte>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_osm_OsmHost_hash(JNIEnv* env, jclass, jstring path) {
    std::ifstream in(string_of(env, path), std::ios::binary);
    if (!in) return env->NewStringUTF("error=io at=0");
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    const auto t0 = std::chrono::steady_clock::now();
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (!doc) {
        return env->NewStringUTF(
            std::format("error={} at={}", osm::name_of(doc.error().fault), doc.error().at).c_str());
    }
    Canonical c;
    std::array<std::size_t, 4> kinds{};
    for (const osm::Element& e : doc->elements()) {
        ++kinds[code_of(e.kind)];
        c.text(e.type);
        c.u8((e.has_id ? 1U : 0U) | (e.has_at ? 2U : 0U) | (e.has_tags ? 4U : 0U) | (e.has_nodes ? 8U : 0U) |
             (e.has_geometry ? 16U : 0U) | (e.has_members ? 32U : 0U));
        if (e.has_id) c.i64(e.id);
        if (e.has_at) {
            c.real(e.at.lat);
            c.real(e.at.lon);
        }
        if (e.has_tags) {
            c.u32(e.tags.size());
            for (const osm::Tag& t : e.tags) {
                c.text(t.key);
                c.text(t.value);
            }
        }
        if (e.has_nodes) {
            c.u32(e.nodes.size());
            for (const std::int64_t n : e.nodes) c.i64(n);
        }
        if (e.has_geometry) c.points(e.geometry);
        if (e.has_members) {
            c.u32(e.members.size());
            for (const osm::Member& m : e.members) {
                c.u8(code_of(m.kind));
                c.i64(m.ref);
                c.u8((m.has_role ? 1U : 0U) | (m.has_geometry ? 2U : 0U));
                c.text(m.role);
                c.points(m.geometry);
            }
        }
    }
    const std::string out =
        std::format("sha256={} elements={} nodes={} ways={} relations={} ms={:.1f}", atlas::tiles::hex(c.finish()),
                    doc->elements().size(), kinds[0], kinds[1], kinds[2], ms);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
