// The sun worker's test entry points in the host build (plan-vector K8), for :mapcorecheck's MT/sun tests on the JVM:
// a render pack's sun scene at a level (every tile of its finest level, their samples and the grid over their
// solids), the core's answers for the port's own samples, matched by point and building, every bit's hash, the hulls,
// a bucket's time and the sun's check. They bind to the Kotlin object app.atlas.mapcorecheck.sun.SunHost by JNI's
// names; nothing of them is in the phone's library.
#include "base/pymath/PyMath.hpp"
#include "sun/Buckets.hpp"
#include "sun/Hulls.hpp"
#include "sun/Samples.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileBox.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Points.hpp"

#include <jni.h>

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <format>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

using namespace atlas::sun;
namespace tl = atlas::tiles;

/// The layout version of these entry points; SunHost refuses another.
constexpr jint test_sun_abi = 1;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring text(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

std::uint64_t bits(double v) { return std::bit_cast<std::uint64_t>(v); }

/// Who a building's sample belongs to, as the port's dict tells it: its OSM id and its footprint's first vertex.
struct Owner {
    std::int64_t id = 0;
    std::uint64_t x0 = 0;
    std::uint64_t z0 = 0;
};

/// A pack's sun scene at one level, built once per JVM.
struct Built {
    std::unique_ptr<tl::Pack> pack;
    Region region;
    std::shared_ptr<const Scene> scene;
    std::vector<std::vector<Owner>> buildings; ///< per tile, per building record
    std::vector<std::vector<Owner>> parts;     ///< per tile, per part record
    double maxh = 0.0;
    double millis = 0.0;
};

/// The scenes built so far, the most recently used last: at most three are kept (a canal ring's takes some 150 MB).
struct KeptScenes {
    std::mutex mutex;
    std::vector<std::pair<std::pair<std::string, int>, std::shared_ptr<Built>>> scenes;
};

constexpr std::size_t kept_scenes = 3;

KeptScenes& built_scenes() {
    static KeptScenes kept;
    return kept;
}

/// The owners of a layer's records at `level`: each record's id and its ring's first vertex there.
std::vector<Owner> owners_of(const tl::Tile& tile, tl::LayerId id, std::uint8_t level, const tl::Frame& frame) {
    std::vector<Owner> out;
    const tl::Layer* l = tile.layer(id);
    if (l == nullptr) return out;
    std::vector<std::uint32_t> kept;
    for (std::uint32_t i = 0; i < l->count; ++i) {
        const tl::RingView ring = l->ring(tl::building::ring, i);
        kept.resize(ring.size());
        const tl::Simplified s = tl::simplify(ring, level, frame, kept);
        Owner o;
        o.id = l->integer(tl::building::id, i);
        if (s.count > 0) {
            o.x0 = bits(frame.x(ring.lon[kept[0]]));
            o.z0 = bits(frame.z(ring.lat[kept[0]]));
        }
        out.push_back(o);
    }
    return out;
}

std::shared_ptr<Built> build(const std::string& path, int level, std::string& error) {
    KeptScenes& kept = built_scenes();
    const std::lock_guard lock(kept.mutex);
    const auto key = std::pair{path, level};
    if (const auto it = std::ranges::find(kept.scenes, key, [](const auto& e) { return e.first; });
        it != kept.scenes.end()) {
        auto found = *it;
        kept.scenes.erase(it);
        kept.scenes.push_back(found);
        return found.second;
    }
    const auto t0 = std::chrono::steady_clock::now();
    auto source = tl::MappedSource::open(path);
    if (!source) {
        error = "error=" + describe(source.error());
        return nullptr;
    }
    auto pack = tl::Pack::open(std::move(*source));
    if (!pack || pack->head() == nullptr || pack->style() == nullptr) {
        error = pack ? "error=not a render pack" : "error=" + describe(pack.error());
        return nullptr;
    }
    auto b = std::make_shared<Built>();
    b->pack = std::make_unique<tl::Pack>(std::move(*pack));
    const tl::Pack& p = *b->pack;
    const tl::RenderHead& head = *p.head();
    b->region = region_of(*p.style());
    const std::uint8_t z = head.levels.front();
    const auto lv = static_cast<std::uint8_t>(level);
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "sun test");
    // every bridge of the pack first (the quays' decks), then each tile's samples
    std::vector<Deck> decks;
    for (const tl::ChunkRef& c : p.chunks().level(z)) {
        arena.reset();
        auto tile = p.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) {
            error = "error=" + describe(tile.error());
            return nullptr;
        }
        collect_decks(*tile, head, lv, decks);
    }
    std::vector<SunTile> tiles;
    std::vector<tl::Sha256Digest> chunks;
    for (const tl::ChunkRef& c : p.chunks().level(z)) {
        arena.reset();
        auto tile = p.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) {
            error = "error=" + describe(tile.error());
            return nullptr;
        }
        tiles.push_back(make_tile(*tile, head, b->region, lv, decks));
        chunks.push_back(c.sha256);
        b->buildings.push_back(owners_of(*tile, tl::LayerId::building, lv, head.frame));
        b->parts.push_back(owners_of(*tile, tl::LayerId::part, lv, head.frame));
    }
    auto maxh = scene_maxh(p, lv);
    if (!maxh) {
        error = "error=" + describe(maxh.error());
        return nullptr;
    }
    b->maxh = *maxh;
    b->scene = std::make_shared<const Scene>(std::move(tiles), *maxh, std::move(chunks));
    b->millis = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (kept.scenes.size() >= kept_scenes) kept.scenes.erase(kept.scenes.begin());
    kept.scenes.emplace_back(key, b);
    return b;
}

Sun sun_of(jdouble x, jdouble y, jdouble z) { return {.x = x, .y = y, .z = z}; }

/// A sample's point for `sun` (a crown's moved toward it) and its owner.
struct Located {
    double x;
    double y;
    double z;
    const Owner* owner;
};

Located locate(const Built& b, std::size_t t, std::uint32_t i, const Sun& sun) {
    const TileSamples& s = b.scene->tiles()[t].samples;
    Located l{.x = s.x[i], .y = s.y[i], .z = s.z[i], .owner = nullptr};
    if (s.kind[i] == Kind::crown) {
        const double r = s.crown_r[i - s.crowns_first];
        l.x = l.x + (sun.x * r);
        l.y = l.y + (sun.y * r);
        l.z = l.z + (sun.z * r);
    }
    if (s.source[i] == Source::building) l.owner = &b.buildings[t][s.owner[i]];
    if (s.source[i] == Source::part) l.owner = &b.parts[t][s.owner[i]];
    return l;
}

/// The key a sample is matched by: its point's bits and its building (none for crowns and quays).
struct Key {
    std::array<std::uint64_t, 6> v;
    friend bool operator==(const Key&, const Key&) = default;
};

struct KeyHash {
    std::size_t operator()(const Key& k) const noexcept {
        std::uint64_t h = 0x9E37'79B9'7F4A'7C15ULL;
        for (const std::uint64_t x : k.v) h = (h ^ x) * 0xFF51'AFD7'ED55'8CCDULL;
        return static_cast<std::size_t>(h ^ (h >> 29U));
    }
};

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_abi(JNIEnv*, jclass) { return test_sun_abi; }

/// The scene of the pack at `path` at ladder `level`: `tiles <n> solids <n> samples <n> windows <n> maxh <bits> ms
/// <ms>`, then a line per kind with its count, or `error=...`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_scene(JNIEnv* env, jclass, jstring path, jint level) {
    std::string error;
    const auto b = build(string_of(env, path), level, error);
    if (!b) return text(env, error);
    std::array<std::uint64_t, kinds> by_kind{};
    std::uint64_t samples = 0;
    std::uint64_t windows = 0;
    for (const SunTile& t : b->scene->tiles()) {
        samples += t.samples.size();
        windows += t.samples.size() - t.samples.windows_first;
        for (const Kind k : t.samples.kind) ++by_kind[static_cast<std::size_t>(k)];
    }
    std::string out =
        std::format("tiles {} solids {} samples {} windows {} maxh {:016x} ms {:.1f}\n", b->scene->tiles().size(),
                    b->scene->grid().size(), samples, windows, bits(b->maxh), b->millis);
    for (std::size_t k = 0; k < kinds; ++k) {
        out += std::format("kind {} {}\n", name_of(static_cast<Kind>(k)), by_kind[k]);
    }
    return text(env, out);
}

/// The core's answers for the port's samples, for `sun`: `port` holds 7 longs a sample (the point's x, y, z bits; the
/// building's OSM id, or Long.MIN_VALUE for none; its first vertex's x and z bits; the port's answer, 0 or 1). Per
/// sample: -1 when the core has no such sample, else its kind + 32 · the core's march's answer + 64 · (the core's
/// sample faces the sun, as the port's must have for it to march) + 128 · (the core has such a sample more than once).
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_match(JNIEnv* env, jclass, jstring path, jint level,
                                                                          jdouble sx, jdouble sy, jdouble sz,
                                                                          jlongArray port) {
    std::string error;
    const auto b = build(string_of(env, path), level, error);
    if (!b) return nullptr;
    const Sun sun = sun_of(sx, sy, sz);
    struct Found {
        std::uint32_t tile;
        std::uint32_t sample;
        bool faces;
        bool repeated;
    };
    std::unordered_map<Key, Found, KeyHash> index;
    const auto tiles = b->scene->tiles();
    for (std::size_t t = 0; t < tiles.size(); ++t) {
        const TileSamples& s = tiles[t].samples;
        for (std::uint32_t i = 0; i < s.size(); ++i) {
            const Located l = locate(*b, t, i, sun);
            const Key k{{bits(l.x), bits(l.y), bits(l.z),
                         l.owner != nullptr ? static_cast<std::uint64_t>(l.owner->id) : 0x8000'0000'0000'0000ULL,
                         l.owner != nullptr ? l.owner->x0 : 0, l.owner != nullptr ? l.owner->z0 : 0}};
            const std::uint32_t f = s.face[i];
            const bool faces = f == no_face || (s.nx[f] * sun.x) + (s.ny[f] * sun.y) + (s.nz[f] * sun.z) > 0;
            const auto [it, fresh] = index.try_emplace(
                k, Found{.tile = static_cast<std::uint32_t>(t), .sample = i, .faces = faces, .repeated = false});
            if (!fresh) {
                it->second.repeated = true;
                it->second.faces = it->second.faces || faces;
            }
        }
    }
    const jsize n = env->GetArrayLength(port) / 7;
    std::vector<jlong> in(static_cast<std::size_t>(n) * 7);
    env->GetLongArrayRegion(port, 0, n * 7, in.data());
    std::vector<jint> out(static_cast<std::size_t>(n));
    for (jsize s = 0; s < n; ++s) {
        const jlong* q = &in[static_cast<std::size_t>(s) * 7];
        const bool none = q[3] == std::numeric_limits<jlong>::min();
        const Key k{{static_cast<std::uint64_t>(q[0]), static_cast<std::uint64_t>(q[1]),
                     static_cast<std::uint64_t>(q[2]), static_cast<std::uint64_t>(q[3]),
                     none ? 0 : static_cast<std::uint64_t>(q[4]), none ? 0 : static_cast<std::uint64_t>(q[5])}};
        const auto it = index.find(k);
        if (it == index.end()) {
            out[static_cast<std::size_t>(s)] = -1;
            continue;
        }
        const Found& f = it->second;
        const TileSamples& ts = tiles[f.tile].samples;
        const PrismId skip = ts.skip[f.sample] == no_solid ? no_prism : b->scene->base(f.tile) + ts.skip[f.sample];
        const bool lit = atlas::sun::lit(b->scene->grid(), sun, std::bit_cast<double>(q[0]),
                                         std::bit_cast<double>(q[1]), std::bit_cast<double>(q[2]), skip);
        out[static_cast<std::size_t>(s)] =
            static_cast<jint>(ts.kind[f.sample]) + (lit ? 32 : 0) + (f.faces ? 64 : 0) + (f.repeated ? 128 : 0);
    }
    jintArray array = env->NewIntArray(n);
    env->SetIntArrayRegion(array, 0, n, out.data());
    return array;
}

/// Every bit of the scene for `sun` on `threads` threads (windows of every tile): `bits <sha256> lit <n> of <n>`, the
/// hash over each tile's key, sample count and bits in the pack's order, then `kind <name> <lit> <of>` lines.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_bits(JNIEnv* env, jclass, jstring path, jint level,
                                                                       jdouble sx, jdouble sy, jdouble sz,
                                                                       jint threads) {
    std::string error;
    const auto b = build(string_of(env, path), level, error);
    if (!b) return text(env, error);
    const Bucket bucket = bucket_of(sun_of(sx, sy, sz), true);
    const auto tiles = b->scene->tiles();
    std::vector<TileJob> jobs;
    jobs.reserve(tiles.size());
    for (std::size_t t = 0; t < tiles.size(); ++t) jobs.push_back({static_cast<std::uint32_t>(t), true});
    std::vector<TileBits> got(tiles.size());
    std::mutex m;
    run(*b->scene, bucket, jobs, static_cast<unsigned>(threads), [&](std::uint32_t t, TileBits bits) {
        const std::lock_guard lock(m);
        got[t] = std::move(bits);
    });
    tl::Sha256 sha;
    std::array<std::uint64_t, kinds> lit{};
    std::array<std::uint64_t, kinds> of{};
    std::uint64_t all = 0;
    std::uint64_t on = 0;
    for (std::size_t t = 0; t < tiles.size(); ++t) {
        const TileBits& g = got[t];
        const std::array<std::uint64_t, 4> head{tiles[t].key.packed(), g.count, 0, 0};
        sha.update(std::as_bytes(std::span(head)));
        sha.update(std::as_bytes(std::span(g.words)));
        for (std::uint32_t i = 0; i < g.count; ++i) {
            const auto k = static_cast<std::size_t>(tiles[t].samples.kind[i]);
            ++of[k];
            ++all;
            if (g.bit(i)) {
                ++lit[k];
                ++on;
            }
        }
    }
    std::string out = std::format("bits {} lit {} of {}\n", tl::hex(sha.finish()), on, all);
    for (std::size_t k = 0; k < kinds; ++k) {
        if (of[k] > 0) out += std::format("kind {} {} {}\n", name_of(static_cast<Kind>(k)), lit[k], of[k]);
    }
    return text(env, out);
}

/// Every solid's ground shadow for `sun`: per solid its OSM id, its first vertex's x and z bits, the shadow's kind
/// (0 a hull, 1 a raised outline), its point count n and n pairs of x, z bits; then per tree its id (as the trunk's x
/// and z bits), kind 2 and its 16 points.
JNIEXPORT jlongArray JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_hulls(JNIEnv* env, jclass, jstring path,
                                                                           jint level, jdouble sx, jdouble sy,
                                                                           jdouble sz) {
    std::string error;
    const auto b = build(string_of(env, path), level, error);
    if (!b) return nullptr;
    const Sun sun = sun_of(sx, sy, sz);
    std::vector<jlong> out;
    const auto tiles = b->scene->tiles();
    for (std::size_t t = 0; t < tiles.size(); ++t) {
        const TileShadows sh = shadows_of(tiles[t], sun, b->region);
        for (std::uint32_t p = 0; p < sh.size(); ++p) {
            if (sh.kind[p] == ShadowKind::tree) {
                out.push_back(static_cast<jlong>(bits(tiles[t].trees.x[sh.owner[p]])));
                out.push_back(static_cast<jlong>(bits(tiles[t].trees.z[sh.owner[p]])));
                out.push_back(0);
            } else {
                const TileSolids& s = tiles[t].solids;
                const std::uint32_t r = s.record[sh.owner[p]];
                const Owner& o = s.source[sh.owner[p]] == Source::building ? b->buildings[t][r] : b->parts[t][r];
                out.push_back(o.id);
                out.push_back(static_cast<jlong>(o.x0));
                out.push_back(static_cast<jlong>(o.z0));
            }
            out.push_back(static_cast<jlong>(sh.kind[p]));
            const auto xs = sh.xs(p);
            const auto zs = sh.zs(p);
            out.push_back(static_cast<jlong>(xs.size()));
            for (std::size_t i = 0; i < xs.size(); ++i) {
                out.push_back(static_cast<jlong>(bits(xs[i])));
                out.push_back(static_cast<jlong>(bits(zs[i])));
            }
        }
    }
    jlongArray array = env->NewLongArray(static_cast<jsize>(out.size()));
    env->SetLongArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

/// One bucket's time for a view, the best of `repeats`: its tiles are those whose box comes within `radius` metres of
/// the target (tx, tz), nearest the camera's ground point (cx, cz) first, windows for those within `window_reach`.
/// `bucket <ms> threads <n> tiles <n> samples <n> windows <n> lit <n>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_bucket(JNIEnv* env, jclass, jstring path, jint level,
                                                                         jdouble sx, jdouble sy, jdouble sz, jdouble tx,
                                                                         jdouble tz, jdouble radius, jdouble cx,
                                                                         jdouble cz, jdouble window_reach, jint threads,
                                                                         jint repeats) {
    std::string error;
    const auto b = build(string_of(env, path), level, error);
    if (!b) return text(env, error);
    const tl::Frame& frame = b->pack->head()->frame;
    const auto tiles = b->scene->tiles();
    std::vector<bool> near(tiles.size());
    for (std::size_t t = 0; t < tiles.size(); ++t) {
        const tl::TileKey k = tiles[t].key;
        const double x0 = frame.x(tl::column_edge(k.z, k.x));
        const double x1 = frame.x(tl::column_edge(k.z, k.x + 1));
        const double z0 = frame.z(tl::row_edge(k.z, k.y));
        const double z1 = frame.z(tl::row_edge(k.z, k.y + 1));
        const double dx = atlas::pymath::max({0.0, x0 - tx, tx - x1});
        const double dz = atlas::pymath::max({0.0, z0 - tz, tz - z1});
        near[t] = (dx * dx) + (dz * dz) <= radius * radius;
    }
    std::vector<std::uint8_t> visible(tiles.size());
    for (std::size_t t = 0; t < tiles.size(); ++t) visible[t] = near[t] ? 1 : 0;
    std::vector<TileJob> jobs;
    for (const TileJob& j : nearest_first(*b->scene, frame, cx, cz, visible, window_reach)) {
        if (near[j.tile]) jobs.push_back(j);
    }
    std::uint64_t samples = 0;
    std::uint64_t windows = 0;
    for (const TileJob& j : jobs) {
        const TileSamples& s = tiles[j.tile].samples;
        samples += j.windows ? s.size() : s.windows_first;
        if (j.windows) windows += s.size() - s.windows_first;
    }
    const Bucket bucket = bucket_of(sun_of(sx, sy, sz), true);
    double best = 1e30;
    std::uint64_t lit = 0;
    for (jint r = 0; r < std::max(1, repeats); ++r) {
        std::mutex m;
        std::uint64_t on = 0;
        const auto t0 = std::chrono::steady_clock::now();
        run(*b->scene, bucket, jobs, static_cast<unsigned>(threads), [&](std::uint32_t, const TileBits& bits) {
            std::uint64_t n = 0;
            for (const std::uint64_t w : bits.words) n += static_cast<std::uint64_t>(std::popcount(w));
            const std::lock_guard lock(m);
            on += n;
        });
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        best = std::min(best, ms);
        lit = on;
    }
    return text(env, std::format("bucket {:.2f} threads {} tiles {} samples {} windows {} lit {}", best, threads,
                                 jobs.size(), samples, windows, lit));
}

/// The sun's check: `sun <hash> ok` or `sun <hash> FAIL expected <hash>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_check(JNIEnv* env, jclass) {
    return text(env, march_check_line());
}

/// The bundled check over the pack at `path`, as the app's VK facts runs it on the APK's tiles/amsterdam.atlas: its
/// line, then `hash <16 hex digits>` and `ms <time>`.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_sun_SunHost_bundled(JNIEnv* env, jclass, jstring path) {
    auto source = tl::MappedSource::open(string_of(env, path));
    if (!source) return text(env, "error=" + describe(source.error()));
    auto pack = tl::Pack::open(std::move(*source));
    if (!pack) return text(env, "error=" + describe(pack.error()));
    const auto t0 = std::chrono::steady_clock::now();
    const std::string line = sun_line(*pack);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    const auto h = bundled_check(*pack);
    return text(env, std::format("{}\nhash {:016x}\nms {:.1f}", line, h ? *h : 0, ms));
}

} // extern "C"
