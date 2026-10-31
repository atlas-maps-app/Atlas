// The sun worker (sun/Buckets.hpp, sun/BitTiles.hpp): buckets from the theme block, a bucket's run on several threads
// giving one thread's bits, each tile handed on once when its last piece is done, windows tested only where asked;
// the worker publishing the latest request, a run's tiles together at one time with one wake of the frame loop, a
// named moment's tiles kept on disk and read back; the bits a frame draws, fading 300 ms over a swap. The host-tsan
// build runs these with ThreadSanitizer; the disk cache's files go to the build folder's scratch (ATLAS_TEST_SCRATCH)
// and are removed.
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "style/Theme.hpp"
#include "sun/BitTiles.hpp"
#include "sun/Buckets.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <bit>
#include <chrono>
#include <filesystem>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

using namespace atlas::sun;
namespace pm = atlas::pymath;
namespace tl = atlas::tiles;

/// A tile of `solids` square solids and `samples` samples drawn from `rnd` (every tenth a crown, the last fifth
/// windows), at column x of the Westerkerk's L14 row.
SunTile drawn_tile(pm::PyRandom& rnd, std::uint32_t x, int solids, int samples) {
    SunTile t;
    t.key = {14, x, 5384};
    for (int k = 0; k < solids; ++k) {
        const double cx = rnd.uniform(-200.0, 200.0);
        const double cz = rnd.uniform(-200.0, 200.0);
        const double r = rnd.uniform(3.0, 12.0);
        for (const auto& [dx, dz] : {std::pair{-r, -r}, std::pair{r, -r}, std::pair{r, r}, std::pair{-r, r}}) {
            t.solids.x.push_back(cx + dx);
            t.solids.z.push_back(cz + dz);
        }
        t.solids.first.push_back(static_cast<std::uint32_t>(t.solids.x.size()));
        t.solids.h.push_back(rnd.uniform(3.0, 40.0));
        t.solids.min.push_back(0.0);
        t.solids.source.push_back(Source::building);
        t.solids.record.push_back(static_cast<std::uint32_t>(k));
    }
    TileSamples& s = t.samples;
    const int windows = samples / 5;
    for (int i = 0; i < samples - windows; ++i) {
        if (i % 10 == 9) continue; // crowns go after the buildings' samples
        const double a = rnd.uniform(0.0, 2 * pm::pi);
        const std::uint32_t f = s.add_face(pm::cos(a), 0.0, pm::sin(a));
        const auto skip = static_cast<std::uint32_t>(rnd.random() * solids);
        s.add(Kind::wall, Source::building, skip, 0, 0, rnd.uniform(-200.0, 200.0), rnd.uniform(0.0, 30.0),
              rnd.uniform(-200.0, 200.0), f, skip);
    }
    s.crowns_first = s.size();
    for (int i = 0; i < (samples - windows) / 10; ++i) {
        s.add(Kind::crown, Source::tree, static_cast<std::uint32_t>(i), 0, 0, rnd.uniform(-200.0, 200.0), 7.5,
              rnd.uniform(-200.0, 200.0), no_face, no_solid);
        s.crown_r.push_back(rnd.uniform(1.0, 5.0));
    }
    s.crowns_end = s.windows_first = s.size();
    for (int i = 0; i < windows; ++i) {
        const std::uint32_t f = s.add_face(0.0, 0.0, 1.0);
        s.add(Kind::window, Source::building, 0, 0, static_cast<std::uint32_t>(i), rnd.uniform(-200.0, 200.0),
              rnd.uniform(1.0, 20.0), rnd.uniform(-200.0, 200.0), f, no_solid);
    }
    return t;
}

std::shared_ptr<const Scene> drawn_scene(int tiles, int samples) {
    pm::PyRandom rnd(99);
    std::vector<SunTile> ts;
    std::vector<tl::Sha256Digest> chunks;
    double maxh = 0.0;
    for (int k = 0; k < tiles; ++k) {
        ts.push_back(drawn_tile(rnd, 8400 + static_cast<std::uint32_t>(k), 60, samples + (k * 37)));
        for (const double h : ts.back().solids.h) maxh = pm::max(maxh, h);
        tl::Sha256Digest d{};
        d[0] = static_cast<std::byte>(k + 1);
        chunks.push_back(d);
    }
    return std::make_shared<const Scene>(std::move(ts), maxh, std::move(chunks));
}

/// Every tile's bits, by its place in the scene, from one run.
std::vector<TileBits> run_all(const Scene& scene, const Bucket& b, unsigned threads, bool windows) {
    std::vector<TileJob> jobs;
    for (std::uint32_t t = 0; t < scene.tiles().size(); ++t) jobs.push_back({t, windows});
    std::vector<TileBits> out(scene.tiles().size());
    std::vector<int> calls(scene.tiles().size());
    std::mutex m;
    run(scene, b, jobs, threads, [&](std::uint32_t t, TileBits bits) {
        const std::lock_guard lock(m);
        out[t] = std::move(bits);
        ++calls[t];
    });
    for (const int c : calls) CHECK(c == 1);
    return out;
}

} // namespace

TEST_CASE("sun: buckets of the theme block, named and live") {
    atlas::style::ThemeBlock b{};
    b.moment = atlas::style::MomentName::sunny;
    b.sun_vector = {0.1, 0.8, std::sqrt(1.0 - 0.65)};
    b.sun_azimuth = 171.3;
    b.sun_elevation = 53.1;
    const Bucket named = bucket_of(b);
    CHECK(named.named);
    CHECK(named.sun == Sun{.x = 0.1, .y = 0.8, .z = std::sqrt(1.0 - 0.65)}); // the port's bits, exactly
    b.moment = atlas::style::MomentName::other;
    const Bucket live = bucket_of(b);
    CHECK(!live.named);
    CHECK(live.sun == sun_vector(171.5, 53.0)); // 0.5° steps
    b.sun_azimuth = 171.6;
    b.sun_elevation = 53.2;
    CHECK(bucket_of(b).key == live.key);
    b.sun_elevation = 0.1; // just up: half a degree, never the set sun
    CHECK(bucket_of(b).sun.y > 0);
    b.sun_vector = {0.0, -1.0, 0.0};
    b.sun_elevation = -3.0;
    CHECK(bucket_of(b).sun == Sun{.x = 0.0, .y = -1.0, .z = 0.0});
    CHECK(bucket_of(Sun{.x = 1.0, .y = 0.0, .z = 0.0}, true).key !=
          bucket_of(Sun{.x = 0.0, .y = 1.0, .z = 0.0}, true).key);
}

TEST_CASE("sun: a bucket's bits on four threads are one thread's, each tile handed on once") {
    const auto scene = drawn_scene(6, 9000);
    const Bucket b = bucket_of(sun_vector(150.0, 25.0), true);
    const std::vector<TileBits> one = run_all(*scene, b, 1, true);
    const std::vector<TileBits> four = run_all(*scene, b, 4, true);
    std::uint64_t lit = 0;
    for (std::size_t t = 0; t < one.size(); ++t) {
        CHECK(one[t].words == four[t].words);
        CHECK(one[t].count == scene->tiles()[t].samples.size());
        CHECK(one[t].bucket == b.key);
        for (std::uint32_t i = 0; i < one[t].count; ++i) {
            CHECK(one[t].bit(i) == sample_lit(scene->grid(), scene->base(t), scene->tiles()[t].samples, i, b.sun));
            lit += one[t].bit(i) ? 1 : 0;
        }
    }
    CHECK(lit > 0);
    // without windows their bits stay 0
    const std::vector<TileBits> bare = run_all(*scene, b, 4, false);
    for (std::size_t t = 0; t < bare.size(); ++t) {
        const TileSamples& s = scene->tiles()[t].samples;
        for (std::uint32_t i = 0; i < s.size(); ++i) {
            CHECK(bare[t].bit(i) == (i < s.windows_first && one[t].bit(i)));
        }
    }
}

TEST_CASE("sun: a sample whose place another tile holds is lit as its face turns to the sun, with no march") {
    // A tower 50 m tall and 10 m square; three samples 20 m north of it, 1 m up: one marched (in its shadow, the sun
    // in the south 45° up), one whose place another tile of the scene holds (no_march: lit, as it faces the sun), and
    // one such turned from the sun (unlit).
    SunTile t;
    t.key = {13, 4207, 2692};
    for (const auto& [x, z] :
         {std::pair{-5.0, -5.0}, std::pair{5.0, -5.0}, std::pair{5.0, 5.0}, std::pair{-5.0, 5.0}}) {
        t.solids.x.push_back(x);
        t.solids.z.push_back(z);
    }
    t.solids.first.push_back(4);
    t.solids.h.push_back(50.0);
    t.solids.min.push_back(0.0);
    t.solids.source.push_back(Source::building);
    t.solids.record.push_back(0);
    TileSamples& s = t.samples;
    const std::uint32_t toward = s.add_face(0.0, 0.0, 1.0);
    const std::uint32_t away = s.add_face(0.0, 0.0, -1.0);
    s.add(Kind::wall, Source::building, 1, 0, 0, 0.0, 1.0, -20.0, toward, no_solid);
    s.add(Kind::wall, Source::building, 1, 0, 0, 0.0, 1.0, -20.0, toward, no_march);
    s.add(Kind::wall, Source::building, 1, 0, 0, 0.0, 1.0, -20.0, away, no_march);
    s.crowns_first = s.crowns_end = s.windows_first = s.size();
    std::vector<SunTile> tiles;
    tiles.push_back(std::move(t));
    const Scene scene(std::move(tiles), 50.0);
    const double r = 0.7071067811865476; // 45° up, toward the south
    const Sun sun{.x = 0.0, .y = r, .z = r};
    const TileSamples& in = scene.tiles()[0].samples;
    CHECK_FALSE(sample_lit(scene.grid(), scene.base(0), in, 0, sun));
    CHECK(sample_lit(scene.grid(), scene.base(0), in, 1, sun));
    CHECK_FALSE(sample_lit(scene.grid(), scene.base(0), in, 2, sun));
}

TEST_CASE("sun: the jobs go nearest first, the visible before the rest, windows within reach") {
    const auto scene = drawn_scene(5, 100);
    tl::Frame frame;
    frame.lat0 = 52.374;
    frame.lon0 = 4.884;
    frame.kx = 67900.0;
    frame.kz = 111300.0;
    const std::vector<std::uint8_t> visible{0, 0, 1, 0, 1};
    const std::vector<TileJob> jobs = nearest_first(*scene, frame, 0.0, 0.0, visible, 1000.0);
    ATLAS_REQUIRE(jobs.size() == 5);
    CHECK((jobs[0].tile == 2 || jobs[0].tile == 4));
    CHECK((jobs[1].tile == 2 || jobs[1].tile == 4));
    CHECK(jobs[0].tile != jobs[1].tile);
}

TEST_CASE("sun: the bits a frame draws: the first at once, a swap fading over 300 ms") {
    BitTiles tiles;
    const tl::TileKey k{14, 8414, 5384};
    CHECK(!tiles.view(k, 0.0).has_value());
    TileBits a;
    a.words = {0b1010U};
    a.count = 4;
    tiles.publish(k, a, 10.0);
    auto v = tiles.view(k, 10.0);
    ATLAS_REQUIRE(v.has_value());
    CHECK(v->weight == 1.0);
    CHECK(v->now->words == a.words);
    TileBits b = a;
    b.words = {0b0110U};
    tiles.publish(k, b, 20.0);
    v = tiles.view(k, 20.0);
    ATLAS_REQUIRE(v.has_value());
    CHECK(v->weight == 0.0);
    CHECK(v->before->words == a.words);
    CHECK(v->now->words == b.words);
    const auto half = tiles.view(k, 20.15);
    const auto done = tiles.view(k, 20.3);
    const auto later = tiles.view(k, 25.0);
    ATLAS_REQUIRE(half.has_value() && done.has_value() && later.has_value());
    CHECK(half->weight == doctest::Approx(0.5));
    CHECK(done->weight == 1.0);
    CHECK(later->before->words == b.words); // the fade over
    CHECK(lit_amount(true, true, 0.3) == 1.0);
    CHECK(lit_amount(false, true, 0.25) == 0.25);
    CHECK(lit_amount(true, false, 0.25) == 0.75);
    std::vector<std::uint32_t> texels;
    const auto w = tiles.texels(k, 20.0, texels);
    ATLAS_REQUIRE(w.has_value());
    CHECK(texels == std::vector<std::uint32_t>{0b0110U, 0b1010U}); // R the new bits, G the old
    tiles.forget(k);
    CHECK(tiles.size() == 0);
}

TEST_CASE("sun: a named moment's tiles are kept on disk and read back") {
    const std::string dir = (std::filesystem::path(ATLAS_TEST_SCRATCH) / "sun-cache").string();
    std::filesystem::remove_all(dir);
    DiskCache cache(dir, 1U << 20U);
    const auto scene = drawn_scene(2, 700);
    const Bucket b = bucket_of(sun_vector(200.0, 40.0), true);
    const std::vector<TileBits> bits = run_all(*scene, b, 2, true);
    const tl::Sha256Digest chunk = *scene->chunk(0);
    CHECK(!cache.load(chunk, 0, b, bits[0].count, true).has_value());
    CHECK(cache.store(chunk, 0, bits[0]));
    const auto back = cache.load(chunk, 0, b, bits[0].count, true);
    ATLAS_REQUIRE(back.has_value());
    CHECK(back->words == bits[0].words);
    CHECK(back->windows);
    CHECK(!cache.load(chunk, 1, b, bits[0].count, true).has_value()); // another level
    CHECK(!cache.load(chunk, 0, bucket_of(sun_vector(201.0, 40.0), true), bits[0].count, true).has_value()); // sun
    CHECK(!cache.load(chunk, 0, b, bits[0].count + 1, true).has_value());                                    // samples
    // a budget of a few files drops the oldest
    const std::uint64_t file = 72 + (8 * bits[0].words.size()); // the head, then the words
    DiskCache small(dir, (3 * file) + 1);
    for (int k = 0; k < 6; ++k) {
        TileBits more = bits[0];
        more.sun = sun_vector(100.0 + k, 30.0);
        CHECK(small.store(chunk, 0, more));
    }
    std::size_t files = 0;
    for (const auto& e : std::filesystem::directory_iterator(dir)) files += e.path().extension() == ".sun" ? 1 : 0;
    CHECK(files <= 3);
    std::filesystem::remove_all(dir);
}

TEST_CASE("sun: the worker publishes the latest request, and a named moment from disk the second time") {
    const std::string dir = (std::filesystem::path(ATLAS_TEST_SCRATCH) / "sun-worker").string();
    std::filesystem::remove_all(dir);
    DiskCache cache(dir);
    BitTiles bits;
    std::atomic<int> ticks{0};
    const auto clock = [&ticks] { return 100.0 + ticks.fetch_add(1); };
    const auto scene = drawn_scene(4, 3000);
    const Bucket b = bucket_of(sun_vector(120.0, 33.0), true);
    std::vector<TileJob> jobs;
    jobs.reserve(4);
    for (std::uint32_t t = 0; t < 4; ++t) jobs.push_back({t, true});
    const std::vector<TileBits> want = run_all(*scene, b, 1, true);
    {
        Worker w(bits, 3, &cache, clock);
        w.request(scene, bucket_of(sun_vector(10.0, 10.0), false), jobs); // replaced by the next if not begun
        w.request(scene, b, jobs);
        w.wait_idle();
        for (std::uint32_t t = 0; t < 4; ++t) {
            const auto v = bits.view(scene->tiles()[t].key, 1e9);
            ATLAS_REQUIRE(v.has_value());
            CHECK(v->now->bucket == b.key);
            CHECK(v->now->words == want[t].words);
        }
        CHECK(w.cached() == 0);
        w.request(scene, b, jobs);
        w.wait_idle();
        CHECK(w.cached() == 4); // every tile from disk
        CHECK(w.runs() >= 2);
    }
    {
        Worker idle(bits, 2, nullptr, clock); // made and stopped with nothing asked
        idle.wait_idle();
    }
    std::filesystem::remove_all(dir);
}

TEST_CASE("sun: a run's tiles are published together, at one time, and tell the frame loop once") {
    BitTiles bits;
    const auto scene = drawn_scene(4, 3000);
    std::atomic<int> told{0};
    std::atomic<int> there{0}; // the tiles with bits when last told
    bits.on_publish([&] {
        int n = 0;
        for (std::uint32_t t = 0; t < 4; ++t) n += bits.view(scene->tiles()[t].key, 0.0).has_value() ? 1 : 0;
        there.store(n);
        told.fetch_add(1);
    });
    std::atomic<int> ticks{0};
    const auto clock = [&ticks] { return 100.0 + ticks.fetch_add(1); };
    std::vector<TileJob> jobs;
    jobs.reserve(4);
    for (std::uint32_t t = 0; t < 4; ++t) jobs.push_back({t, true});
    Worker w(bits, 3, nullptr, clock);
    // The first bits: four tiles, one wake, every tile's there by then.
    w.request(scene, bucket_of(sun_vector(120.0, 33.0), false), jobs);
    w.wait_idle();
    CHECK(told.load() == 1);
    CHECK(there.load() == 4);
    // A new bucket: the four swap at the run's one time, so their fades go together, and one wake more.
    w.request(scene, bucket_of(sun_vector(121.0, 33.0), false), jobs);
    w.wait_idle();
    CHECK(told.load() == 2);
    const double swapped = 100.0 + (ticks.load() - 1); // the clock's last time: the run's
    for (std::uint32_t t = 0; t < 4; ++t) {
        const auto at = bits.view(scene->tiles()[t].key, swapped);
        const auto half = bits.view(scene->tiles()[t].key, swapped + (fade_seconds / 2));
        ATLAS_REQUIRE(at.has_value() && half.has_value());
        CHECK(at->weight == 0.0);
        CHECK(half->weight == doctest::Approx(0.5));
    }
}
