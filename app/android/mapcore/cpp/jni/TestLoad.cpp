// Stage P's cut's test entry point in the host build (load/Cut.hpp), for :mapcorecheck's CutValidityTest on the JVM,
// which holds the core's cut of the canal ring to nothing that cannot be there (load/Clean.hpp's validity counts) and
// to every tile of the bundled render pack of it. It binds to the Kotlin object app.atlas.mapcorecheck.load.LoadHost by
// JNI's names; nothing of it is in the phone's library.
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
#include <string>
#include <unordered_map>
#include <vector>

namespace {

namespace load = atlas::load;
namespace osm = atlas::osm;

/// The layout version of these entry points; LoadHost refuses another.
constexpr jint test_load_abi = 6;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

/// `<layer>:<lines that differ>/<lines>,…`, or `none`.
std::string layers_text(const std::vector<atlas::tiles::LayerLines>& layers) {
    std::string out;
    for (const atlas::tiles::LayerLines& l : layers) {
        out += std::format("{}{}:{}/{}", out.empty() ? "" : ",", l.layer, l.differ, l.lines);
    }
    return out.empty() ? "none" : out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_load_LoadHost_abi(JNIEnv*, jclass) { return test_load_abi; }

/// A region's tiles cut by the core from the raw.json at `raw` (its box from the region.json at `region_json`, its
/// frame and its variants' tolerances from the render pack at `pack`, loaded whole and at each of them), beside that
/// pack's tiles, which the port cut (`missing`: the pack's tiles the core cut none of; `differ`: those whose bytes the
/// core's cut changed, for the record), and what the loading counted of its buildings (Scene::counts: the validity
/// counts `pairs`, `uncovered` and `flat`, then `underground`, `doubles`, `bounded`, `courtyards`, `covered`, `hidden`,
/// `clipped`, `raised`, `vanished`, `seamed`, `unsettled`, `unmeasured`): `tiles=<n> cut=<n> missing=<n> differ=<n>
/// bytes=<n> variants=<n> pairs=<n> uncovered=<n> flat=<n> … load_ms=<ms> cut_ms=<ms>`; or `error=<what>`.
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
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "cut");
    std::vector<std::byte> bytes;
    std::vector<std::byte> scratch;
    std::size_t tiles = 0;
    std::size_t missing = 0;
    std::size_t differ = 0;
    std::size_t written_bytes = 0;
    std::string first;
    std::vector<tl::LayerLines> layers;
    for (const tl::ChunkRef& chunk : opened->chunks().chunks()) {
        ++tiles;
        arena.reset();
        auto theirs = opened->decode(chunk, zstd, decoder, arena, bytes, scratch);
        if (!theirs) return env->NewStringUTF(std::format("error={}", tl::describe(theirs.error())).c_str());
        const auto it = mine.find(chunk.tile.packed());
        if (it == mine.end()) {
            if (missing++ == 0) first = std::format("{}/{}/{}", int{chunk.tile.z}, chunk.tile.x, chunk.tile.y);
            continue;
        }
        const auto written = tl::encode_tile(*it->second);
        if (!written) return env->NewStringUTF(std::format("error={}", tl::describe(written.error())).c_str());
        written_bytes += written->size();
        if (*written == bytes) {
            tl::compare_layers(*theirs, *theirs, layers);
            continue;
        }
        ++differ;
        tl::TileDecoder again;
        atlas::Arena arena2(8U << 20U, atlas::Growth::grows, "cut, the core's tile");
        const auto ours = again.decode(*written, arena2);
        if (!ours) return env->NewStringUTF(std::format("error={}", tl::describe(ours.error())).c_str());
        tl::compare_layers(*ours, *theirs, layers);
    }
    const auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    const load::BuildingCounts& c = base->counts;
    std::string summary = std::format(
        "tiles={} cut={} missing={} differ={} bytes={} variants={} pairs={} uncovered={} flat={} flat_by_seam={} "
        "underground={} underground_layer={} doubles={} doubles_disagree={} bounded={} courtyards={} covered={} "
        "hidden={} clipped={} raised={} vanished={} seamed={} unsettled={} unmeasured={} uncovered_m2={:.1f} "
        "sliver_m2={:.1f} dropped_m2={:.1f} load_ms={:.1f} cut_ms={:.1f}",
        tiles, cut->size(), missing, differ, written_bytes, variant_features, c.overlapping_pairs, c.uncovered_outlines,
        c.flat_roofs, c.flat_by_seam, c.underground, c.underground_layer, c.doubles, c.doubles_disagree, c.bounded,
        c.courtyards, c.covered, c.hidden, c.clipped, c.raised, c.vanished, c.seamed, c.unsettled, c.unmeasured,
        c.uncovered_m2, c.sliver_m2, c.dropped_m2, ms(start, loaded), ms(loaded, done));
    summary += " layers=" + layers_text(layers);
    if (!first.empty()) summary += " first_missing=" + first;
    return env->NewStringUTF(summary.c_str());
}

/// The tiles of the render pack at `a` beside those of the pack at `b`, layer by layer of their canonical texts
/// (tiles/Dump.hpp's compare_layers): `tiles=<b's> missing=<those a lacks> extra=<those b lacks> layers=<layer>:<lines
/// that differ>/<lines>,…`; or `error=<what>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_load_LoadHost_comparePacks(JNIEnv* env, jclass, jstring a,
                                                                                 jstring b) {
    namespace tl = atlas::tiles;
    const auto open = [&](jstring path) -> tl::Result<tl::Pack> {
        auto source = tl::MappedSource::open(string_of(env, path));
        if (!source) return std::unexpected(source.error());
        return tl::Pack::open(std::move(*source));
    };
    auto mine = open(a);
    auto theirs = open(b);
    if (!mine || !theirs) return env->NewStringUTF("error=a pack does not open");
    std::unordered_map<std::uint64_t, const tl::ChunkRef*> held;
    for (const tl::ChunkRef& chunk : mine->chunks().chunks()) held[chunk.tile.packed()] = &chunk;
    tl::Zstd zstd;
    tl::TileDecoder decoder_a;
    tl::TileDecoder decoder_b;
    atlas::Arena arena_a(8U << 20U, atlas::Growth::grows, "packs compared, a");
    atlas::Arena arena_b(8U << 20U, atlas::Growth::grows, "packs compared, b");
    std::vector<std::byte> bytes_a;
    std::vector<std::byte> bytes_b;
    std::vector<std::byte> scratch;
    std::vector<tl::LayerLines> layers;
    std::size_t tiles = 0;
    std::size_t missing = 0;
    for (const tl::ChunkRef& chunk : theirs->chunks().chunks()) {
        ++tiles;
        const auto it = held.find(chunk.tile.packed());
        if (it == held.end()) {
            ++missing;
            continue;
        }
        arena_a.reset();
        arena_b.reset();
        const auto x = mine->decode(*it->second, zstd, decoder_a, arena_a, bytes_a, scratch);
        const auto y = theirs->decode(chunk, zstd, decoder_b, arena_b, bytes_b, scratch);
        if (!x || !y) return env->NewStringUTF("error=a tile does not decode");
        tl::compare_layers(*x, *y, layers);
    }
    const std::size_t extra = mine->chunks().chunks().size() - (tiles - missing);
    return env->NewStringUTF(
        std::format("tiles={} missing={} extra={} layers={}", tiles, missing, extra, layers_text(layers)).c_str());
}

} // extern "C"
