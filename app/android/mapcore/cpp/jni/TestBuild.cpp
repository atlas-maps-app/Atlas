// Stage T of the buildings' test entry points in the host build (plan-vector K10), for :mapcorecheck's
// MT/buildings/StageTParityTest on the JVM: every primitive and polygon a render pack's tiles build at one level of the
// ladder (geometry/Walls.hpp), with its building's OSM id, its kind and flags, its face's light inputs and its corners'
// bits, for the test to hold to the points, fills and strokes the port's renderer gives face(). They bind to the
// Kotlin object app.atlas.mapcorecheck.buildings.BuildHost by JNI's names; nothing of them is in the phone's library.
#include "base/arena.hpp"
#include "geometry/Walls.hpp"
#include "sun/Samples.hpp"
#include "tiles/Container.hpp"
#include "tiles/Source.hpp"

#include <jni.h>

#include <bit>
#include <cstdint>
#include <limits>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace {

namespace tl = atlas::tiles;
namespace gm = atlas::geometry;

/// The layout version of these entry points; BuildHost refuses another.
constexpr jint test_build_abi = 1;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jlong bits(double v) { return static_cast<jlong>(std::bit_cast<std::int64_t>(v)); }

/// A record's first word: a primitive or a polygon.
constexpr jlong record_prim = 0;
constexpr jlong record_polygon = 1;

/// Appends one record: what it is, the building's OSM id, its info word (bit 0 a part, bit 1 a relation's id, bits 8-15
/// the primitive's kind, bits 16-31 its flags), its face's base colour (bit 24: lit by day, not by a sample) and
/// normal, its corners' count and their x, y, z bits.
void append(std::vector<jlong>& out, jlong what, const gm::BuildingItem& item, jlong kind, jlong flags,
            const gm::Face& f, std::span<const gm::V3> corners) {
    out.push_back(what);
    out.push_back(static_cast<jlong>(item.id));
    out.push_back((item.source == gm::Source::part ? 1 : 0) | (item.relation ? 2 : 0) | (kind << 8) | (flags << 16));
    out.push_back(static_cast<jlong>(f.base) | (f.lit == gm::Lit::day ? (jlong{1} << 24) : 0));
    out.push_back(bits(f.normal.x));
    out.push_back(bits(f.normal.y));
    out.push_back(bits(f.normal.z));
    out.push_back(static_cast<jlong>(corners.size()));
    for (const gm::V3& p : corners) {
        out.push_back(bits(p.x));
        out.push_back(bits(p.y));
        out.push_back(bits(p.z));
    }
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_buildings_BuildHost_abi(JNIEnv*, jclass) { return test_build_abi; }

/// Stage T of every tile of the pack at `path` (its finest zoom) at ladder `level`: per primitive (but an n-gon's
/// triangles) and per n-gon a record (append's), or null when the pack cannot be read.
JNIEXPORT jlongArray JNICALL Java_app_atlas_mapcorecheck_buildings_BuildHost_prims(JNIEnv* env, jclass, jstring path,
                                                                                   jint level) {
    auto source = tl::MappedSource::open(string_of(env, path));
    if (!source) return nullptr;
    auto pack = tl::Pack::open(std::move(*source));
    if (!pack || pack->head() == nullptr || level < 0 ||
        std::cmp_greater(level, std::numeric_limits<std::uint8_t>::max())) {
        return nullptr;
    }
    const tl::RenderHead& head = *pack->head();
    const atlas::sun::Region region =
        pack->style() != nullptr ? atlas::sun::region_of(*pack->style()) : atlas::sun::Region{};
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "stage T parity");
    std::vector<jlong> out;
    for (const tl::ChunkRef& chunk : pack->chunks().level(head.levels.front())) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        if (!tile) return nullptr;
        const gm::TileBuildings g = gm::build_buildings({.tile = &*tile,
                                                         .head = &head,
                                                         .level = static_cast<std::uint8_t>(level),
                                                         .region = {.water_y = region.water_y},
                                                         .kind = 0});
        for (const std::vector<gm::Prim>* list : {&g.edged, &g.plain}) {
            for (const gm::Prim& p : *list) {
                if ((p.flags & gm::prim_flag::polygon) != 0) continue; // its n-gon's record stands for it
                const std::size_t n = (p.flags & gm::prim_flag::triangle) != 0 ? 3 : 4;
                append(out, record_prim, g.items[p.item], static_cast<jlong>(p.kind), p.flags, g.faces[p.face],
                       std::span(p.p.data(), n));
            }
        }
        for (const gm::Polygon& poly : g.polygons) {
            append(out, record_polygon, g.items[poly.item], static_cast<jlong>(poly.kind), poly.flags,
                   g.faces[poly.face], poly.ring);
        }
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(out.size()));
    if (result != nullptr) env->SetLongArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

} // extern "C"
