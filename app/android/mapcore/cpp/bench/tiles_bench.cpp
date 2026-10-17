// The tile reader's timings (plan-vector §3, K3: a dense L14 tile decodes in at most 15 ms on one Mac core): the
// densest tile of the bundled packs (ATLAS_TILES_DIR) decompressed, checked against its SHA-256 and decoded whole, then
// each step alone, and the canonical hash and simplification over every ring of the tile at one level.
#include "base/arena.hpp"
#include "tiles/Container.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/layers/Buildings.hpp"

#include <nanobench.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <memory>
#include <string>

using namespace atlas::tiles;

int main() {
    const char* dir = std::getenv("ATLAS_TILES_DIR"); // NOLINT(concurrency-mt-unsafe): read once, before any thread
    if (dir == nullptr) {
        (void)std::printf("tiles_bench: set ATLAS_TILES_DIR to the bundled packs' folder\n");
        return 0;
    }
    const std::string path = (std::filesystem::path(dir) / "grachten.atlas").string();
    auto source = MappedSource::open(path);
    if (!source) return 1;
    auto pack = Pack::open(std::move(*source));
    if (!pack) return 1;
    const ChunkRef* densest = nullptr;
    for (const ChunkRef& c : pack->chunks().chunks()) {
        if (densest == nullptr || c.raw > densest->raw) densest = &c;
    }
    Zstd zstd;
    TileDecoder decoder;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "bench");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    (void)std::printf("the densest tile: %u/%u/%u, %u bytes stored, %u raw, %u features\n", densest->tile.z,
                      densest->tile.x, densest->tile.y, densest->stored, densest->raw, densest->count);
    ankerl::nanobench::Bench bench;
    bench.title("tile reader").unit("tile").warmup(3).minEpochIterations(50).relative(false);
    bench.run("unpack (zstd and SHA-256) and decode", [&] {
        arena.reset();
        if (!pack->unpack(*densest, zstd, raw, scratch)) std::abort();
        auto tile = decoder.decode(raw, arena);
        if (!tile) std::abort();
        ankerl::nanobench::doNotOptimizeAway(tile->feature_count());
    });
    bench.run("unpack: zstd and SHA-256", [&] {
        if (!pack->unpack(*densest, zstd, raw, scratch)) std::abort();
    });
    bench.run("unpack: zstd alone", [&] {
        if (!pack->unpack(*densest, zstd, raw, scratch, false)) std::abort();
    });
    bench.run("decode", [&] {
        arena.reset();
        auto tile = decoder.decode(raw, arena);
        if (!tile) std::abort();
        ankerl::nanobench::doNotOptimizeAway(tile->feature_count());
    });
    arena.reset();
    auto tile = decoder.decode(raw, arena);
    if (!tile) return 1;
    bench.run("canonical hash", [&] { ankerl::nanobench::doNotOptimizeAway(canonical_hash(*tile)); });
    const Layer* buildings = tile->layer(LayerId::building);
    std::vector<std::uint32_t> kept;
    bench.run("simplify every building at 0.7 m", [&] {
        std::size_t n = 0;
        for (std::uint32_t i = 0; i < buildings->count; ++i) {
            const RingView ring = buildings->ring(building::ring, i);
            kept.resize(ring.size());
            n += simplify(ring, 7, pack->head()->frame, kept).count;
        }
        ankerl::nanobench::doNotOptimizeAway(n);
    });
    (void)std::printf("arena: %zu bytes for the decoded tile (%u raw)\n", arena.used(), densest->raw);

    // every tile of both bundled packs, once each, the best of five passes
    double best = 0.0;
    std::uint64_t raw_bytes = 0;
    std::uint64_t decoded_bytes = 0;
    std::size_t tiles = 0;
    for (int pass = 0; pass < 5; ++pass) {
        raw_bytes = decoded_bytes = 0;
        tiles = 0;
        const auto t0 = std::chrono::steady_clock::now();
        for (const char* name : {"amsterdam.atlas", "grachten.atlas"}) {
            auto s = MappedSource::open((std::filesystem::path(dir) / name).string());
            if (!s) return 1;
            auto p = Pack::open(std::move(*s));
            if (!p) return 1;
            for (const ChunkRef& c : p->chunks().chunks()) {
                arena.reset();
                if (!p->unpack(c, zstd, raw, scratch) || !decoder.decode(raw, arena)) return 1;
                raw_bytes += c.raw;
                decoded_bytes += arena.used();
                ++tiles;
            }
        }
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (pass == 0 || ms < best) best = ms;
    }
    (void)std::printf(
        "all %zu bundled tiles: %.2f ms to open both packs, unpack and decode every tile; %llu raw bytes, "
        "%llu bytes decoded\n",
        tiles, best, static_cast<unsigned long long>(raw_bytes), static_cast<unsigned long long>(decoded_bytes));
    return 0;
}
