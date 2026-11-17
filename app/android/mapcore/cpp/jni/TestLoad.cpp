// Stage P's cut's test entry point in the host build (load/Cut.hpp), for :mapcorecheck's CutParityTest on the JVM,
// which holds the core's cut of the canal ring to the packer's render pack of it, tile for tile and byte for byte. It
// binds to the Kotlin object app.atlas.mapcorecheck.load.LoadHost by JNI's names; nothing of it is in the phone's
// library.
#include "base/arena.hpp"
#include "load/Cut.hpp"
#include "load/Regions.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Container.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Json.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileWriter.hpp"

#include <jni.h>

#include <chrono>
#include <cstdint>
#include <format>
#include <fstream>
#include <iterator>
#include <set>
#include <span>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

namespace load = atlas::load;
namespace osm = atlas::osm;

/// The layout version of these entry points; LoadHost refuses another.
constexpr jint test_load_abi = 5;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

/// The first line where two canonical dumps differ, both sides, cut to 300 characters each.
std::string first_difference(const std::string& core, const std::string& packer) {
    std::istringstream a(core);
    std::istringstream b(packer);
    std::string la;
    std::string lb;
    for (std::size_t line = 1;; ++line) {
        const bool ha = static_cast<bool>(std::getline(a, la));
        const bool hb = static_cast<bool>(std::getline(b, lb));
        if (!ha && !hb) return "no line differs";
        if (!ha || !hb || la != lb) {
            return std::format("line {}: core {} | packer {}", line, ha ? la.substr(0, 300) : "(none)",
                               hb ? lb.substr(0, 300) : "(none)");
        }
    }
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_load_LoadHost_abi(JNIEnv*, jclass) { return test_load_abi; }

/// A region's tiles cut by the core from the raw.json at `raw` (its box from the region.json at `region_json`, its
/// frame and its variants' tolerances from the render pack at `pack`, loaded whole and at each of them), held to that
/// pack's tiles byte for byte: `tiles=<n> wrong=<n> bytes=<n> variants=<n> load_ms=<ms> cut_ms=<ms>`, and where a tile
/// differs ` first=<z/x/y> <the first line of their canonical dumps that does>`; or `error=<what>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_load_LoadHost_cutRegion(JNIEnv* env, jclass, jstring raw,
                                                                              jstring region_json, jstring pack,
                                                                              jstring name) {
    namespace tl = atlas::tiles;
    const auto read_file = [](const std::string& path) {
        std::ifstream in(path, std::ios::binary);
        return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    };
    const std::string text = read_file(string_of(env, raw));
    const std::string json = read_file(string_of(env, region_json));
    if (text.empty() || json.empty()) return env->NewStringUTF("error=io at=raw or region.json");
    const auto start = std::chrono::steady_clock::now();
    const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
    if (!doc) return env->NewStringUTF(std::format("error={}", osm::name_of(doc.error().fault)).c_str());
    const auto region = tl::read_json(std::as_bytes(std::span(json.data(), json.size())));
    if (!region) return env->NewStringUTF("error=region.json");
    const tl::Value bbox = region->root().find("bbox").value_or(tl::Value());
    if (bbox.size() != 4) return env->NewStringUTF("error=region.json has no bbox");
    auto source = tl::MappedSource::open(string_of(env, pack));
    if (!source) return env->NewStringUTF(std::format("error={}", tl::describe(source.error())).c_str());
    auto opened = tl::Pack::open(std::move(*source));
    if (!opened || opened->head() == nullptr) return env->NewStringUTF("error=the render pack does not open");
    const tl::RenderHead& head = *opened->head();
    const std::string label = string_of(env, name);
    const load::RegionConfig config{.name = label,
                                    .frame = head.frame,
                                    .bbox = {bbox.child(0).number().value_or(0), bbox.child(1).number().value_or(0),
                                             bbox.child(2).number().value_or(0), bbox.child(3).number().value_or(0)},
                                    .tradition = nullptr,
                                    .palette = load::region_palette(label)};
    const auto base = load::load_region(*doc, config);
    if (!base) return env->NewStringUTF("error=the region does not load");
    std::set<std::uint8_t> levels;
    for (const tl::HeadView& v : head.views()) levels.insert(v.tolerance);
    for (const tl::HeadBand& b : head.bands()) levels.insert(b.tolerance);
    levels.erase(0);
    std::vector<load::Scene> others;
    std::vector<load::CutVariant> variants;
    others.reserve(levels.size());
    variants.reserve(levels.size());
    for (const std::uint8_t l : levels) {
        auto other = load::load_region(*doc, config, tl::keep::tolerances.at(l));
        if (!other) return env->NewStringUTF("error=the region does not load at a variant's tolerance");
        others.push_back(std::move(*other));
    }
    std::size_t k = 0;
    for (const std::uint8_t l : levels)
        variants.push_back({.tolerance = tl::keep::tolerances.at(l), .scene = &others[k++]});
    const auto loaded = std::chrono::steady_clock::now();
    const auto cut = load::cut_region(*base, head.frame, variants);
    const auto done = std::chrono::steady_clock::now();
    if (!cut) return env->NewStringUTF(std::format("error={}", cut.error().what).c_str());
    std::unordered_map<std::uint64_t, const tl::TileFill*> mine;
    std::size_t variant_features = 0;
    for (const tl::TileFill& t : *cut) {
        mine[tl::TileKey(t.level(), t.x(), t.y()).packed()] = &t;
        for (const tl::LayerFill& lf : t.layers()) {
            if (lf.id() == tl::LayerId::variant) variant_features += lf.count();
        }
    }
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "cut parity");
    std::vector<std::byte> bytes;
    std::vector<std::byte> scratch;
    std::size_t tiles = 0;
    std::size_t wrong = 0;
    std::size_t written_bytes = 0;
    std::string first;
    for (const tl::ChunkRef& chunk : opened->chunks().chunks()) {
        ++tiles;
        arena.reset();
        auto theirs = opened->decode(chunk, zstd, decoder, arena, bytes, scratch);
        if (!theirs) return env->NewStringUTF(std::format("error={}", tl::describe(theirs.error())).c_str());
        const std::string where = std::format("{}/{}/{}", int{chunk.tile.z}, chunk.tile.x, chunk.tile.y);
        const auto it = mine.find(chunk.tile.packed());
        if (it == mine.end()) {
            if (wrong++ == 0) first = where + " the core cut no such tile";
            continue;
        }
        const auto written = tl::encode_tile(*it->second);
        if (!written) return env->NewStringUTF(std::format("error={}", tl::describe(written.error())).c_str());
        written_bytes += written->size();
        if (*written == bytes) continue;
        if (wrong++ == 0) {
            tl::TileDecoder again;
            atlas::Arena arena2(8U << 20U, atlas::Growth::grows, "cut parity, the core's tile");
            const auto ours = again.decode(*written, arena2);
            first = ours ? where + " " + first_difference(tl::canonical_dump(*ours), tl::canonical_dump(*theirs))
                         : where + " the core's tile does not decode";
        }
    }
    const auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    std::string summary =
        std::format("tiles={} cut={} wrong={} bytes={} variants={} load_ms={:.1f} cut_ms={:.1f}", tiles, cut->size(),
                    wrong, written_bytes, variant_features, ms(start, loaded), ms(loaded, done));
    if (!first.empty()) summary += " first=" + first;
    return env->NewStringUTF(summary.c_str());
}

} // extern "C"
