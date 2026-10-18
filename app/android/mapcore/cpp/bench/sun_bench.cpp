// The sun worker's timings (plan-vector K8: one bucket for the Main board in at most 80 ms on four Mac cores; the
// phone's reported at A1): the canal ring's render pack (ATLAS_TILES_DIR) at its grachten_home view's level, the
// bucket of its sunny moment over the tiles within the view's radius, nearest the eye first, windows within
// win_d · 1.35, on one and on four threads; then the scene's build (samples and grid) and one tile's hulls. With
// ATLAS_TIMED=1 (a quiet machine) a bucket over 80 ms on four threads fails the run; otherwise it prints.
#include "base/pymath/PyMath.hpp"
#include "sun/Buckets.hpp"
#include "sun/Hulls.hpp"
#include "sun/Samples.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileBox.hpp"

#include <nanobench.h>

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

using namespace atlas::sun;
namespace tl = atlas::tiles;
namespace pm = atlas::pymath;

namespace {

/// The Main board's sunny sun, as the port's bits (MT/sun/SunParityTest.kt's sun-bits.txt, grachten_home-sunny).
constexpr Sun board_sun{.x = std::bit_cast<double>(0xBFE3'70F7'8052'1105ULL),
                        .y = std::bit_cast<double>(0x3FDC'0166'AC03'0321ULL),
                        .z = std::bit_cast<double>(0x3FE5'3656'EC50'C046ULL)};

double number(const tl::Value& map, std::string_view key, double otherwise) {
    const auto v = map.find(key);
    return v ? v->number().value_or(otherwise) : otherwise;
}

} // namespace

int main() {
    const char* dir = std::getenv("ATLAS_TILES_DIR"); // NOLINT(concurrency-mt-unsafe): read once, before any thread
    if (dir == nullptr) {
        (void)std::printf("sun_bench: set ATLAS_TILES_DIR to the bundled packs' folder\n");
        return 0;
    }
    const char* timed = std::getenv("ATLAS_TIMED"); // NOLINT(concurrency-mt-unsafe)
    auto source = tl::MappedSource::open((std::filesystem::path(dir) / "grachten.atlas").string());
    if (!source) return 1;
    auto pack = tl::Pack::open(std::move(*source));
    if (!pack || pack->head() == nullptr || pack->style() == nullptr) return 1;
    const tl::RenderHead& head = *pack->head();
    const auto view = head.view("grachten_home");
    if (!view) return 1;
    const std::uint8_t level = view->tolerance;
    const tl::Value cam = view->camera;
    const auto target = cam.find("target");
    if (!target || target->size() < 3) return 1;
    const double tx = target->child(0).number().value_or(0.0);
    const double tz = target->child(2).number().value_or(0.0);
    const double radius = number(cam, "radius", 1e9);
    const double win_d = number(cam, "win_d", 390.0);
    // the eye: City.Camera's C = target - d · dist, d = (sin yaw · cos pitch, -sin pitch, -cos yaw · cos pitch)
    const double yr = pm::radians(number(cam, "yaw", 0.0));
    const double pr = pm::radians(number(cam, "pitch", 90.0));
    const double dist = number(cam, "dist", 0.0);
    const double cx = tx - ((pm::sin(yr) * pm::cos(pr)) * dist);
    const double cz = tz - ((-pm::cos(yr) * pm::cos(pr)) * dist);

    const Region region = region_of(*pack->style());
    const std::uint8_t z = head.levels.front();
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    atlas::Arena arena(8U << 20U, atlas::Growth::grows, "sun bench");
    const auto build = [&]() -> std::shared_ptr<const Scene> {
        std::vector<Deck> decks;
        for (const tl::ChunkRef& c : pack->chunks().level(z)) {
            arena.reset();
            auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
            if (!tile) return nullptr;
            collect_decks(*tile, head, level, decks);
        }
        std::vector<SunTile> tiles;
        for (const tl::ChunkRef& c : pack->chunks().level(z)) {
            arena.reset();
            auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
            if (!tile) return nullptr;
            tiles.push_back(make_tile(*tile, head, region, level, decks));
        }
        const auto maxh = scene_maxh(*pack, level);
        if (!maxh) return nullptr;
        return std::make_shared<const Scene>(std::move(tiles), *maxh);
    };
    const auto t0 = std::chrono::steady_clock::now();
    const std::shared_ptr<const Scene> scene = build();
    if (!scene) return 1;
    const double build_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();

    // the Main board's bucket: the tiles within the view's radius of its target, nearest the eye first
    const tl::Frame& frame = head.frame;
    std::vector<std::uint8_t> visible(scene->tiles().size());
    for (std::size_t t = 0; t < scene->tiles().size(); ++t) {
        const tl::TileKey k = scene->tiles()[t].key;
        const double x0 = frame.x(tl::column_edge(k.z, k.x));
        const double x1 = frame.x(tl::column_edge(k.z, k.x + 1));
        const double z0 = frame.z(tl::row_edge(k.z, k.y));
        const double z1 = frame.z(tl::row_edge(k.z, k.y + 1));
        const double dx = pm::max({0.0, x0 - tx, tx - x1});
        const double dz = pm::max({0.0, z0 - tz, tz - z1});
        visible[t] = (dx * dx) + (dz * dz) <= radius * radius ? 1 : 0;
    }
    std::vector<TileJob> jobs;
    for (const TileJob& j : nearest_first(*scene, frame, cx, cz, visible, win_d * 1.35)) {
        if (visible[j.tile] != 0) jobs.push_back(j);
    }
    std::uint64_t samples = 0;
    for (const TileJob& j : jobs) {
        const TileSamples& s = scene->tiles()[j.tile].samples;
        samples += j.windows ? s.size() : s.windows_first;
    }
    const Bucket bucket = bucket_of(board_sun, true);
    std::vector<TileBits> got(scene->tiles().size());
    const auto bucket_run = [&](unsigned threads) {
        std::mutex m;
        run(*scene, bucket, jobs, threads, [&](std::uint32_t t, TileBits bits) {
            const std::lock_guard lock(m);
            got[t] = std::move(bits);
        });
    };
    ankerl::nanobench::Bench bench;
    bench.title("sun worker").unit("bucket").warmup(1).minEpochIterations(3).relative(false);
    bench.run("Main board bucket, one thread", [&] { bucket_run(1); });
    const double one = bench.results().back().median(ankerl::nanobench::Result::Measure::elapsed);
    bench.run("Main board bucket, four threads", [&] { bucket_run(4); });
    const double four = bench.results().back().median(ankerl::nanobench::Result::Measure::elapsed);
    bench.run("the scene: samples, solids and grid", [&] { ankerl::nanobench::doNotOptimizeAway(build().get()); });
    const SunTile& densest =
        *std::ranges::max_element(scene->tiles(), {}, [](const SunTile& t) { return t.solids.size(); });
    bench.run("one tile's ground shadows",
              [&] { ankerl::nanobench::doNotOptimizeAway(shadows_of(densest, board_sun, region).size()); });
    // the line the phone logs per bucket (plan-vector Appendix C): its sun's elevation, its samples, its time on four
    // threads and its bits' hash (the tiles in the pack's order); then the acceptance
    tl::Sha256 sha;
    std::uint64_t lit = 0;
    for (const TileJob& j : jobs) {
        const std::array<std::uint64_t, 2> key{scene->tiles()[j.tile].key.packed(), got[j.tile].count};
        sha.update(std::as_bytes(std::span(key)));
        sha.update(std::as_bytes(std::span(got[j.tile].words)));
        for (const std::uint64_t w : got[j.tile].words) lit += static_cast<std::uint64_t>(std::popcount(w));
    }
    const double el = pm::degrees(pm::asin(board_sun.y));
    (void)std::fputs(std::format("sun bucket {:.2f} samples {} {:.1f} hash {} (the Main board: {} tiles, {} lit)\n", el,
                                 samples, four * 1e3, tl::hex(sha.finish()).substr(0, 16), jobs.size(), lit)
                         .c_str(),
                     stdout);
    (void)std::fputs(
        std::format("sun: the scene built in {:.0f} ms ({} tiles, {} solids); a Main board bucket {:.1f} ms on "
                    "one thread, {:.1f} ms on four (budget 80 ms): {}\n",
                    build_ms, scene->tiles().size(), scene->grid().size(), one * 1e3, four * 1e3,
                    four * 1e3 <= 80.0 ? "ok" : "OVER")
            .c_str(),
        stdout);
    if (timed != nullptr && std::string_view(timed) == "1" && four * 1e3 > 80.0) return 1;
    return 0;
}
