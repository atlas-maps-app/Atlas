// The terrain's relief tiles (scene/Relief.hpp, ENGINE.md part 6, terrain, step 2), without a GPU: the walk ends at
// the level the eye's distance asks for and no finer than the packs hold rasters; a tile is lit by the store's raster
// for it, its part of a coarser one where the store holds no finer; a terrain pack the app brings by ranges (its head
// in the world's cache, as the phone's are) has its raster wanted of the app, the tile lit by the coarser raster
// meanwhile, and baked once its chunk is kept; the blocks and the sources the relief names; a pack that leaves takes
// its rasters with it.
#include "base/pymath/PyMath.hpp"
#include "scene/Relief.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/terrain_support.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Container.hpp"
#include "tiles/Directory.hpp"
#include "tiles/ExpandedCache.hpp"
#include "tiles/Source.hpp"
#include "tiles/Store.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <numbers>
#include <span>
#include <string>
#include <unistd.h>
#include <vector>

using namespace atlas;

namespace {

namespace fs = std::filesystem;
namespace tl = atlas::tiles;
namespace terrain = atlas::test::terrain;

/// A folder of its own for one test, deleted when it ends.
class TempDir {
public:
    TempDir() {
        static std::atomic<int> serial{0};
        path_ = fs::temp_directory_path() / std::format("atlas-relief-tiles-test-{}-{}", ::getpid(), serial++);
        fs::create_directories(path_);
    }
    TempDir(const TempDir&) = delete;
    TempDir& operator=(const TempDir&) = delete;
    TempDir(TempDir&&) = delete;
    TempDir& operator=(TempDir&&) = delete;
    ~TempDir() {
        std::error_code ec;
        fs::remove_all(path_, ec);
    }
    [[nodiscard]] std::string file(std::string_view name) const { return (path_ / name).string(); }

private:
    fs::path path_;
};

void write_file(const std::string& path, std::span<const std::byte> bytes) {
    std::ofstream out(path, std::ios::binary);
    for (const std::byte b : bytes) out.put(static_cast<char>(b));
}

/// A gentle surface in steps, over level 13's lattice.
[[nodiscard]] std::int64_t surface(std::int64_t row13, std::int64_t column13) {
    return 400 + (((row13 * 3) + (column13 * 5)) % 64) + (row13 / 4);
}

/// A view looking north at the frame's origin from `distance` metres, 45 degrees down, on a phone's screen.
[[nodiscard]] view::View view_from(double distance) {
    const view::CameraSpec spec{
        .target = {0, 0, 0}, .yaw = 0.0, .pitch = 45.0, .dist = distance, .width = 411.0, .height = 914.0};
    return {spec, view::Device{.density = 2.625, .width_px = 1079, .height_px = 2399, .quarter_turns = 0}};
}

} // namespace

TEST_CASE("the relief's tiles: lit by the store's rasters to the level the packs hold, a pack fetched by range "
          "wanted of the app and baked once its chunk is kept") {
    const TempDir dir;
    // the dunes' level 12 tile, a raster of its own; its north-west quarter's level 13 raster in a pack of its own,
    // which the app brings by ranges
    constexpr std::uint32_t x12 = 2100;
    constexpr std::uint32_t y12 = 1344;
    terrain::Raster coarse =
        terrain::raster_of(257, 8, [](std::int64_t r, std::int64_t c) { return surface(2 * r, 2 * c); });
    terrain::Raster fine = terrain::raster_of(257, 8, [](std::int64_t r, std::int64_t c) { return surface(r, c); });
    fine.sources = {"ahn4"};
    fine.shares = {1000};
    constexpr std::array<std::uint8_t, 1> twelve{12};
    constexpr std::array<std::uint8_t, 1> thirteen{13};
    write_file(dir.file("coarse.atlas"), terrain::terrain_pack({terrain::terrain_tile(12, x12, y12, coarse)}, twelve));
    const std::vector<std::byte> remote =
        terrain::terrain_pack({terrain::terrain_tile(13, x12 * 2, y12 * 2, fine)}, thirteen);

    auto cache = tl::ExpandedCache::open(dir.file("cache"), std::uint64_t{1} << 24U);
    ATLAS_REQUIRE(cache.has_value(), "the cache does not open");
    tl::Store store(cache->get());
    ATLAS_REQUIRE(
        store
            .add({.id = "terrain/test/coarse", .kind = tl::PackKind::terrain_country, .path = dir.file("coarse.atlas")})
            .has_value(),
        "the level 12 pack does not open");
    // the remote pack's head as the app fetches it: its bytes before its chunks and after them
    const tl::PackRef ref{.id = "terrain/test/9/262/168",
                          .kind = tl::PackKind::terrain_country,
                          .length = remote.size(),
                          .fetch = tl::Store::absent(),
                          .identity = tl::Sha256::of(remote)};
    {
        auto whole = tl::Pack::open(std::make_unique<tl::MemorySource>(std::span<const std::byte>(remote)));
        ATLAS_REQUIRE(whole.has_value(), "the level 13 pack does not open");
        const tl::Section* section = tl::find_section(whole->sections(), "chunks");
        ATLAS_REQUIRE(section != nullptr, "the pack has no chunks");
        const std::uint64_t start = section->offset;
        const std::uint64_t end = section->offset + section->stored;
        const std::span<const std::byte> all(remote);
        const std::array<tl::PackBytes, 2> head = {
            {{.offset = 0, .bytes = all.first(start)}, {.offset = end, .bytes = all.subspan(end)}}};
        ATLAS_REQUIRE(tl::Store::keep_head(**cache, ref, head).has_value(), "the head is not kept");
    }
    ATLAS_REQUIRE(store.add(ref).has_value(), "the remote pack does not open from its head");
    REQUIRE(store.terrain_packs().size() == 2);

    // the frame centred where the level 12 tile's four quarters meet
    const double lon = (((x12 + 0.5) / 4096.0) * 360.0) - 180.0;
    const double lat = pymath::atan(pymath::sinh(std::numbers::pi * (1.0 - ((2.0 * (y12 + 0.5)) / 4096.0)))) *
                       (180.0 / std::numbers::pi);
    const view::GroundFrame frame{
        .lat0 = lat, .lon0 = lon, .kx = 111'320.0 * pymath::cos(pymath::radians(lat)), .kz = 110'540.0};
    const view::View view = view_from(2'500.0);
    const scene::ReliefTiles::Reaches reaches{};
    const tl::TileKey own13(13, x12 * 2, y12 * 2);

    scene::ReliefTiles relief(store);
    std::uint64_t number = 0;
    // a frame, then every raster it asked for baked, then the frame that draws them; twice, so a raster found absent
    // while the cache was written to is found so again
    const auto settle = [&] {
        for (int round = 0; round < 2; ++round) {
            relief.update(view, frame, reaches, ++number);
            relief.wait_idle();
            relief.update(view, frame, reaches, ++number);
        }
    };

    // the first frame: nothing baked, nothing drawn, both rasters wanted
    relief.update(view, frame, reaches, ++number);
    CHECK(relief.frame().draws.empty());
    CHECK(relief.frame().wanted == 2);
    const std::vector<tl::TileKey> blocks = relief.blocks();
    REQUIRE(!blocks.empty());
    CHECK(blocks.front() == tl::TileKey(9, x12 >> 3U, y12 >> 3U));

    // the level 12 raster baked; the level 13 raster's chunk is not on the phone: wanted of the app, its tile lit by
    // the coarser raster's quarter meanwhile
    settle();
    {
        const scene::ReliefFrame& f = relief.frame();
        CHECK(f.wanted == 1);
        std::size_t quarters = 0;
        std::size_t waiting = 0;
        for (const scene::ReliefDraw& d : f.draws) {
            REQUIRE(d.part.tile != nullptr);
            CHECK(d.part.tile->key == tl::TileKey(12, x12, y12));
            if (d.key.z != 13) continue;
            ++quarters;
            CHECK(std::abs(d.part.extent - 0.5F) < 1e-6F);
            CHECK(d.under.tile == nullptr);
            if (d.key == own13) {
                ++waiting;
                CHECK(!d.own);
                CHECK(std::abs(d.part.x) < 1e-6F);
                CHECK(std::abs(d.part.y) < 1e-6F);
            } else {
                CHECK(d.own);
            }
        }
        CHECK(quarters == 4);
        CHECK(waiting == 1);
        const std::vector<scene::WorldWant> wants = relief.wants();
        REQUIRE(wants.size() == 1);
        CHECK(wants.front().tile == own13);
        CHECK(wants.front().pack == ref.id);
        CHECK(wants.front().identity == ref.identity);
        CHECK(relief.sources() == "ahn4,glo30");
        // a quad's box: the quarter's, 3 km a side at this latitude, its corner north-west of the middle
        const auto north_west =
            std::ranges::find_if(f.draws, [&own13](const scene::ReliefDraw& d) { return d.key == own13; });
        REQUIRE(north_west != f.draws.end());
        CHECK(north_west->east == doctest::Approx(2'990.0).epsilon(0.02));
        CHECK(north_west->south == doctest::Approx(2'990.0).epsilon(0.02));
        CHECK(north_west->corner.x == doctest::Approx(-north_west->east).epsilon(1e-6));
        CHECK(north_west->corner.z == doctest::Approx(-north_west->south).epsilon(2e-3));
    }

    // the app keeps the chunk: the raster is baked, its tile lit by its own, the coarser one under it
    {
        const std::vector<scene::WorldWant> wants = relief.wants();
        REQUIRE(wants.size() == 1);
        const scene::WorldWant& want = wants.front();
        const std::shared_ptr<const tl::StorePack> pack = store.terrain_packs().back();
        REQUIRE(pack->remote);
        tl::Zstd zstd;
        const std::span<const std::byte> all(remote);
        const tl::KeptChunks kept = tl::Store::keep(
            **cache, *pack, {.offset = want.offset, .bytes = all.subspan(want.offset, want.length)}, zstd);
        CHECK(kept.kept.size() == 1);
        CHECK(kept.refused.empty());
    }
    settle();
    {
        const scene::ReliefFrame& f = relief.frame();
        CHECK(f.wanted == 0);
        CHECK(relief.wants().empty());
        const auto north_west =
            std::ranges::find_if(f.draws, [&own13](const scene::ReliefDraw& d) { return d.key == own13; });
        REQUIRE(north_west != f.draws.end());
        CHECK(north_west->own);
        REQUIRE(north_west->part.tile != nullptr);
        CHECK(north_west->part.tile->key == own13);
        CHECK(std::abs(north_west->part.extent - 1.0F) < 1e-6F);
        REQUIRE(north_west->under.tile != nullptr);
        CHECK(north_west->under.tile->key == tl::TileKey(12, x12, y12));
        CHECK(relief.stats().baked == 2);
        CHECK(relief.stats().failed == 0);
    }

    // a frame without relief: nothing drawn or asked for, the rasters kept for the next
    relief.rest();
    CHECK(relief.frame().draws.empty());
    CHECK(relief.blocks().empty());
    CHECK(relief.sources().empty());
    relief.update(view, frame, reaches, ++number);
    CHECK(relief.frame().draws.size() >= 4);
    CHECK(relief.stats().baked == 2);

    // the remote pack leaves: its raster with it, and the level 12 tile is one tile again, lit by its own raster
    CHECK(store.remove(ref.id));
    relief.update(view, frame, reaches, ++number);
    {
        const scene::ReliefFrame& f = relief.frame();
        const auto whole = std::ranges::find_if(
            f.draws, [](const scene::ReliefDraw& d) { return d.key == tl::TileKey(12, x12, y12); });
        REQUIRE(whole != f.draws.end());
        CHECK(whole->own);
        CHECK(std::abs(whole->part.extent - 1.0F) < 1e-6F);
        CHECK(std::ranges::none_of(f.draws, [](const scene::ReliefDraw& d) { return d.key.z == 13; }));
        CHECK(relief.stats().resident == 1);
        CHECK(relief.sources() == "ahn4,glo30");
    }

    // from 30 km the walk ends at level 12, whatever the packs hold finer
    const view::View far = view_from(30'000.0);
    relief.update(far, frame, {.far = 60'000.0}, ++number);
    CHECK(!relief.frame().draws.empty());
    CHECK(std::ranges::all_of(relief.frame().draws, [](const scene::ReliefDraw& d) { return d.key.z == 12; }));
}
