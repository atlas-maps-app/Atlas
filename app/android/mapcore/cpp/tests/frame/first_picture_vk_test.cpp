// The first picture (ENGINE.md gains 8 and 9): the app's world at the Westerkerk home over the bundled packs
// (ATLAS_TILES_DIR), the canal ring around the Westerkerk as the app's map has it, its frame loop woken by each tile
// published; how long until every tile the first frame wants in view is built, on 2 workers (CI's emulator) and on 8;
// each pass's share of those tiles' stage T; the densest tile alone, as the camera reaches it while moving, on 1 to 8
// workers; every tile the workers built, each pass's bytes as one thread builds them alone; a tile's passes shared by
// the idle workers, its items joined in the passes' order, and a pass's work in pieces on them; and the workers' hints,
// the frame waiting on them while a tile in view is queued or under way, not for the prefetch.
#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "scene/Heaps.hpp"
#include "scene/World.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Store.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <filesystem>
#include <format>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <thread>
#include <vector>

using namespace atlas;

namespace {

/// The Westerkerk home (amsterdam.py's main camera, scene_vk_test's): 10 m west and 30 m north of the Westerkerk, yaw
/// 52°, 38° down, 430 m, on S1 (1080 × 2400 px, 412 × 916 dp).
[[nodiscard]] style::CameraState home() {
    style::CameraState c{};
    c.version = style::camera_state_version;
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    c.latitude = 52.3746 - (-30.0) / 110540.0;
    c.longitude = 4.8840 + (-10.0) / kx;
    c.bearing = 52.0;
    c.pitch = 52.0;
    c.distance = 430.0;
    c.principal_x = 206.0;
    c.principal_y = 440.0;
    c.density = 1080.0 / 412.0;
    c.width_px = 1080;
    c.height_px = 2400;
    c.width_dp = 412;
    c.height_dp = 916;
    c.flags = style::camera_flag::rest;
    return c;
}

[[nodiscard]] double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

/// The bundled packs the app lists, in its order: the Westerkerk, then the canal ring.
[[nodiscard]] bool add_bundled(scene::World& w, const std::string& dir) {
    for (const char* id : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(id) + ".atlas");
        auto added = w.store().add({.id = id, .kind = tiles::PackKind::bundled, .path = path.string()});
        if (!added) {
            FAIL(tiles::describe(added.error()));
            return false;
        }
    }
    return true;
}

/// One first picture: from the first frame, how long until the Westerkerk's tiles in view are built (the app's
/// complete picture but the fades) and until every tile in view is (the canal ring's around it too).
struct Picture {
    double active_ms = -1;
    double all_ms = -1;
    std::size_t visible = 0; ///< the tiles the first frame wants in view
    std::size_t frames = 0;
    scene::ResidencyStats stats;
};

/// The app's world at the home on `workers` workers, sharing each tile's work or not (WorldOptions::share: gain 9's
/// before and after), with the sun or without, its frame loop woken by each tile published (as the render thread's),
/// until every tile in view is built; a frame builder of every pass of its own.
[[nodiscard]] Picture first_picture(const gpu::Device& device, const gpu::PipelineCache& cache, const std::string& dir,
                                    unsigned workers, bool share, bool sun) {
    Picture out;
    auto made = frame::FrameBuilder::create(device, cache, frame::make_passes());
    ATLAS_REQUIRE_OR_RETURN(out, made.has_value(), made.error().text());
    frame::FrameBuilder& builder = **made;
    std::mutex mutex;
    std::condition_variable woken;
    bool wake = false;
    const auto epoch = std::chrono::steady_clock::now();
    auto world = scene::World::create(
        device, builder.passes(),
        {.workers = workers,
         .sun = {.enabled = sun},
         .clock = [epoch] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - epoch).count(); },
         .share = share});
    ATLAS_REQUIRE_OR_RETURN(out, world.has_value(), world.error().text());
    scene::World& w = **world;
    w.set_wake([&] {
        {
            const std::scoped_lock lock(mutex);
            wake = true;
        }
        woken.notify_one();
    });
    if (!add_bundled(w, dir)) return out;
    w.set_coarser_enclosing(true); // the app's map: the canal ring around the Westerkerk
    builder.set_inputs({.camera = home(), .scene = &w});
    const auto started = std::chrono::steady_clock::now();
    for (std::uint64_t n = 1; ms_since(started) < 300'000.0; ++n) {
        ATLAS_REQUIRE_OR_RETURN(out, builder.derive(n, 0, nullptr));
        w.update(builder.state());
        ++out.frames;
        const scene::Cover& cover = *w.cover();
        const std::size_t visible = static_cast<std::size_t>(std::ranges::count_if(
            cover.requests(), [](const scene::Request& r) { return r.lane == scene::Lane::visible; }));
        if (n == 1) out.visible = visible;
        if (out.active_ms < 0 && w.active() != nullptr && !cover.tiles().empty() && cover.missing() == 0) {
            out.active_ms = ms_since(started);
        }
        if (visible == 0 && !cover.tiles().empty()) {
            out.all_ms = ms_since(started);
            break;
        }
        // Asleep until a tile is published, as the frame loop at rest; at most a frame.
        std::unique_lock lock(mutex);
        woken.wait_for(lock, std::chrono::milliseconds(16), [&wake] { return wake; });
        wake = false;
    }
    out.stats = w.residency().stats();
    w.residency().wait_idle();
    if (w.sun() != nullptr) w.sun()->wait_idle();
    return out;
}

/// What one pass's stage T made of a tile, as bytes: its ranges' bytes, its cells, its numbers and its CPU-side bytes.
struct PassBytes {
    std::vector<std::vector<std::byte>> ranges;
    std::vector<std::uint32_t> cell_first;
    std::array<std::uint32_t, 8> values{};
    std::size_t cpu_bytes = 0;
    bool kept = false;
    friend bool operator==(const PassBytes&, const PassBytes&) = default;
};

[[nodiscard]] PassBytes bytes_of(scene::Heaps& heaps, const scene::PassTile& p) {
    PassBytes b;
    for (const scene::HeapRange& r : p.ranges) {
        if (!r) {
            b.ranges.emplace_back();
            continue;
        }
        const scene::Heaps::Writer w = heaps.writer(r);
        b.ranges.emplace_back(w.bytes().begin(), w.bytes().end());
    }
    b.cell_first = p.cell_first;
    b.values = p.values;
    b.cpu_bytes = p.cpu_bytes;
    b.kept = p.cpu != nullptr;
    return b;
}

/// Two items alike in everything but their tile's handle.
[[nodiscard]] bool same_item(const scene::ItemSpec& a, const scene::ItemSpec& b) {
    scene::ItemSpec x = a;
    scene::ItemSpec y = b;
    x.tile = 0;
    y.tile = 0;
    return x.x == y.x && x.y == y.y && x.z == y.z && x.offset == y.offset && x.cut_x == y.cut_x && x.cut_z == y.cut_z &&
           x.order == y.order && x.kind == y.kind && x.flags == y.flags && x.slots == y.slots &&
           x.min_depth == y.min_depth && x.radius == y.radius && x.owner == y.owner;
}

/// A tile built by one thread, pass after pass in the registry's order: each pass's bytes and time, and the items.
struct Serial {
    std::vector<PassBytes> passes;
    std::vector<double> ms;
    double decode_ms = 0;
    std::vector<scene::ItemSpec> items;
};

[[nodiscard]] std::optional<Serial> build_alone(const tiles::Store& store, std::string_view pack_id, tiles::TileKey key,
                                                std::uint8_t level,
                                                std::span<const std::unique_ptr<frame::Pass>> passes,
                                                scene::Heaps& heaps, std::uint64_t frame) {
    std::shared_ptr<const tiles::StorePack> pack;
    for (const std::shared_ptr<const tiles::StorePack>& p : store.packs()) {
        if (p->id == pack_id) pack = p;
    }
    if (!pack || pack->pack.head() == nullptr || pack->pack.style() == nullptr) return std::nullopt;
    const tiles::ChunkRef* chunk = pack->pack.chunks().find(key);
    if (chunk == nullptr) return std::nullopt;
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    Arena arena(std::size_t{4} << 20U, Growth::grows, "a serial build's tile");
    std::vector<std::byte> raw;
    const auto t0 = std::chrono::steady_clock::now();
    auto decoded = store.decode({.pack = pack, .chunk = chunk}, zstd, decoder, arena, raw);
    if (!decoded) return std::nullopt;
    Serial out;
    out.decode_ms = ms_since(t0);
    const tiles::Tile& tile = *decoded;
    const tiles::RenderHead& head = *pack->pack.head();
    const view::Vec3 origin{head.frame.x(tile.header.west), 0.0, head.frame.z(tile.header.south)};
    std::vector<scene::PassTile> made(passes.size());
    for (std::uint32_t p = 0; p < passes.size(); ++p) {
        scene::TileBuild b(tile, head, *pack->pack.style(), level, origin, heaps, made[p], out.items, p);
        const auto t = std::chrono::steady_clock::now();
        if (auto built = passes[p]->build(b); !built) {
            FAIL(std::format("the {} pass: {}", passes[p]->name(), built.error().text()));
            return std::nullopt;
        }
        out.ms.push_back(ms_since(t));
    }
    for (const scene::PassTile& p : made) {
        out.passes.push_back(bytes_of(heaps, p));
        for (const scene::HeapRange& r : p.ranges) heaps.retire(r, frame);
    }
    heaps.collect(frame);
    return out;
}

} // namespace

TEST_CASE("the first picture at the Westerkerk home: every tile in view built on 2 workers and on 8, each tile on one "
          "worker as before gain 9 and shared, with the sun and without; and each pass's share of their stage T") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty() || !std::filesystem::exists(std::filesystem::path(dir) / "grachten.atlas")) {
        MESSAGE("ATLAS_TILES_DIR has no canal ring's pack: the first picture is not timed here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto builder = frame::FrameBuilder::create(*session->device, *cache, frame::make_passes());
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    const bool timed = test::environment("ATLAS_TIMED") == "1";
    const int rounds = timed ? 3 : 1;
    // The two ways one after the other at each count, so they meet the same load (a host's sun competes with the
    // workers for the cores; the phone's runs behind them, gain 8).
    for (int round = 0; round < rounds; ++round) {
        for (const unsigned workers : {2U, 8U}) {
            for (const bool sun : {true, false}) {
                for (const bool share : {false, true}) {
                    const Picture p = first_picture(*session->device, *cache, dir, workers, share, sun);
                    CHECK(p.all_ms >= 0);
                    CHECK(p.active_ms >= 0);
                    CHECK(p.visible > 0);
                    MESSAGE(std::format("{} workers, {}, {}: the Westerkerk's tiles in view built after {:.0f} ms, all "
                                        "{} in view after {:.0f} ms ({} frames)",
                                        workers, sun ? "the sun" : "no sun", share ? "shared" : "a tile a worker",
                                        p.active_ms, p.visible, p.all_ms, p.frames));
                }
            }
        }
    }

    // Each pass's share: the tiles the home wants in view, each built by one thread alone, pass after pass.
    auto world =
        scene::World::create(*session->device, (*builder)->passes(), {.workers = 1, .sun = {.enabled = false}});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    if (!add_bundled(w, dir)) return;
    w.set_coarser_enclosing(true);
    (*builder)->set_inputs({.camera = home(), .scene = &w});
    ATLAS_REQUIRE((*builder)->derive(1, 0, nullptr));
    w.update((*builder)->state());
    std::vector<scene::Request> visible;
    for (const scene::Request& r : w.cover()->requests()) {
        if (r.lane == scene::Lane::visible) visible.push_back(r);
    }
    std::vector<std::string> packs; // the requests' views of the packs' ids, kept past the residency's next wants
    packs.reserve(visible.size());
    for (const scene::Request& r : visible) packs.emplace_back(r.pack);
    w.residency().want({});
    auto heaps = scene::Heaps::create(*session->device);
    ATLAS_REQUIRE(heaps.has_value(), heaps.error().text());
    const std::span<const std::unique_ptr<frame::Pass>> passes = (*builder)->passes();
    std::vector<double> total(passes.size(), 0.0);
    double all = 0;
    std::uint64_t frame = 1;
    for (std::size_t i = 0; i < visible.size(); ++i) {
        const scene::Request& r = visible[i];
        const auto s = build_alone(w.store(), packs[i], r.key, r.level, passes, **heaps, frame++);
        ATLAS_REQUIRE(s.has_value(), "a tile of the home's could not be built alone");
        std::string line =
            std::format("{}/{}/{} of {}: decode {:.1f} ms", r.key.z, r.key.x, r.key.y, packs[i], s->decode_ms);
        double sum = s->decode_ms;
        for (std::size_t p = 0; p < passes.size(); ++p) {
            if (s->ms[p] >= 0.05) line += std::format(", {} {:.1f}", passes[p]->name(), s->ms[p]);
            total[p] += s->ms[p];
            sum += s->ms[p];
        }
        all += sum;
        MESSAGE(line << std::format("; {:.1f} ms in all", sum));
    }
    std::string shares = std::format("the home's {} tiles in view, one thread: {:.0f} ms;", visible.size(), all);
    for (std::size_t p = 0; p < passes.size(); ++p) {
        if (total[p] >= 0.05) shares += std::format(" {} {:.0f} ms", passes[p]->name(), total[p]);
    }
    MESSAGE(shares);
    (void)session->take_messages();
}

TEST_CASE("a dense tile in view while moving: built on 1 worker, as every tile was before gain 9, and on 2, 3 and 8") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty() || !std::filesystem::exists(std::filesystem::path(dir) / "grachten.atlas")) {
        MESSAGE("ATLAS_TILES_DIR has no canal ring's pack: the dense tile is not timed here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto builder = frame::FrameBuilder::create(*session->device, *cache, frame::make_passes());
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    // The canal ring's densest tile, the one the camera reaches alone while it moves: before gain 9 one worker built
    // it whatever how many waited idle, so 1 worker times the old way.
    const tiles::TileKey dense(14, 8414, 5383);
    const bool timed = test::environment("ATLAS_TIMED") == "1";
    const int rounds = timed ? 3 : 1;
    std::string line = "the canal ring's 14/8414/5383 alone, in view:";
    for (const unsigned workers : {1U, 2U, 3U, 8U}) {
        line += std::format(" {} worker{}", workers, workers == 1 ? "" : "s");
        for (int round = 0; round < rounds; ++round) {
            auto world = scene::World::create(*session->device, (*builder)->passes(),
                                              {.workers = workers, .sun = {.enabled = false}});
            ATLAS_REQUIRE(world.has_value(), world.error().text());
            scene::World& w = **world;
            if (!add_bundled(w, dir)) return;
            const std::array<scene::Request, 1> one{
                scene::Request{.key = dense, .pack = "grachten", .lane = scene::Lane::visible}};
            const auto started = std::chrono::steady_clock::now();
            w.residency().want(one);
            w.residency().wait_idle();
            const double ms = ms_since(started);
            CHECK(w.residency().stats().built == 1);
            line += std::format("{}{:.0f}", round == 0 ? " " : " / ", ms);
        }
        line += " ms;";
    }
    MESSAGE(line);
    (void)session->take_messages();
}

TEST_CASE("every tile the workers build is each pass's build by one thread alone, byte for byte (gain 9)") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty() || !std::filesystem::exists(std::filesystem::path(dir) / "grachten.atlas")) {
        MESSAGE("ATLAS_TILES_DIR has no canal ring's pack: the workers' tiles are not held to one thread's here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto builder = frame::FrameBuilder::create(*session->device, *cache, frame::make_passes());
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    auto world =
        scene::World::create(*session->device, (*builder)->passes(), {.workers = 4, .sun = {.enabled = false}});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    if (!add_bundled(w, dir)) return;
    w.set_coarser_enclosing(true);
    (*builder)->set_inputs({.camera = home(), .scene = &w});
    for (std::uint64_t n = 1; n <= 30; ++n) {
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
        w.residency().wait_idle();
        if (n > 1 && w.cover()->requests().empty()) break;
    }
    REQUIRE(w.cover()->requests().empty());
    const std::span<const std::unique_ptr<frame::Pass>> passes = (*builder)->passes();
    auto heaps = scene::Heaps::create(*session->device);
    ATLAS_REQUIRE(heaps.has_value(), heaps.error().text());
    std::size_t tiles = 0;
    std::size_t items = 0;
    std::size_t bytes = 0;
    std::uint64_t frame = 1;
    for (const std::shared_ptr<const scene::ResidentTile>& t : w.residency().resident()) {
        const auto alone = build_alone(w.store(), t->pack, t->key, t->level, passes, **heaps, frame++);
        ATLAS_REQUIRE(alone.has_value(), "a resident tile could not be built alone");
        REQUIRE(t->passes.size() == passes.size());
        for (std::size_t p = 0; p < passes.size(); ++p) {
            const PassBytes workers = bytes_of(*w.heaps(), t->passes[p]);
            CHECK_MESSAGE(workers == alone->passes[p], std::format("{}/{}/{} of {}: the {} pass", t->key.z, t->key.x,
                                                                   t->key.y, t->pack, passes[p]->name()));
            for (const std::vector<std::byte>& r : workers.ranges) bytes += r.size();
        }
        REQUIRE(t->items.size() == alone->items.size());
        for (std::size_t i = 0; i < t->items.size(); ++i) {
            if (!same_item(t->items[i], alone->items[i])) {
                FAIL(std::format("{}/{}/{} of {}: item {} differs", t->key.z, t->key.x, t->key.y, t->pack, i));
                break;
            }
        }
        items += t->items.size();
        ++tiles;
    }
    CHECK(tiles >= 10);
    // the passes and the buildings' blocks shared out as the workers fell idle
    const scene::ResidencyStats stats = w.residency().stats();
    MESSAGE(std::format("{} tiles, {} items and {} heap bytes as one thread builds them; {} passes and {} of the "
                        "buildings' blocks built on another worker than their tile's or their pass's",
                        tiles, items, bytes, stats.shared, stats.pieces));
    (void)session->take_messages();
}

namespace {

/// A pass whose stage T adds `items` items of kind `kind` to each tile, writes them into the points heap and keeps
/// their count, after `sleep_ms` (a dense tile's pass), noting the thread it ran on.
class Sleeper final : public frame::Pass {
public:
    Sleeper(std::string name, std::uint16_t kind, std::uint32_t items, int sleep_ms)
        : name_(std::move(name)), kind_(kind), items_(items), sleep_ms_(sleep_ms) {}
    [[nodiscard]] std::string_view name() const noexcept override { return name_; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return {}; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        (void)c;
        return {};
    }
    void release() noexcept override {}
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)r;
        (void)phase;
    }
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& b) const override {
        if (sleep_ms_ > 0) std::this_thread::sleep_for(std::chrono::milliseconds(sleep_ms_));
        ATLAS_GPU_LET(range, b.allocate(scene::HeapKind::points, 16 * items_, 16));
        std::vector<std::uint32_t> words(std::size_t{4} * items_);
        for (std::uint32_t i = 0; i < items_; ++i) {
            words[std::size_t{4} * i] = kind_;
            words[(std::size_t{4} * i) + 1] = i;
            b.add_item({.x = static_cast<double>(i),
                        .y = 1.0,
                        .z = 0.0,
                        .offset = 0,
                        .cut_x = static_cast<double>(i),
                        .cut_z = 0.0,
                        .order = scene::order_of(kind_, i),
                        .kind = kind_,
                        .flags = 0,
                        .slots = {1, 1, 1, 1},
                        .min_depth = 2.0F,
                        .radius = 1.0F,
                        .tile = 0});
        }
        b.heaps().write(range, std::as_bytes(std::span(words)));
        b.out().ranges[0] = range;
        b.out().values[0] = items_;
        const std::scoped_lock lock(mutex_);
        threads_.push_back(std::this_thread::get_id());
        return {};
    }
    [[nodiscard]] std::vector<std::thread::id> threads() const {
        const std::scoped_lock lock(mutex_);
        return threads_;
    }

private:
    std::string name_;
    std::uint16_t kind_;
    std::uint32_t items_;
    int sleep_ms_;
    mutable std::mutex mutex_;
    mutable std::vector<std::thread::id> threads_;
};

/// A pass whose stage T splits its work in a piece for its worker and one for each worker waiting for work
/// (TileBuild::helpers, share), each piece a while long and noting its thread, and writes how many pieces it ran.
class Pieces final : public frame::Pass {
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "pieces"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return {}; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        (void)c;
        return {};
    }
    void release() noexcept override {}
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)r;
        (void)phase;
    }
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& b) const override {
        const std::size_t n = std::size_t{b.helpers()} + 1;
        std::vector<std::thread::id> ran(n);
        b.share(n, [&ran](std::size_t i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(40));
            ran[i] = std::this_thread::get_id();
        });
        ATLAS_GPU_LET(range, b.allocate(scene::HeapKind::points, 16, 16));
        const std::array<std::uint32_t, 4> words{static_cast<std::uint32_t>(n), 0, 0, 0};
        b.heaps().write(range, std::as_bytes(std::span(words)));
        b.out().ranges[0] = range;
        const std::scoped_lock lock(mutex_);
        threads_ = std::move(ran);
        return {};
    }
    /// The threads the last build's pieces ran on, in the pieces' order.
    [[nodiscard]] std::vector<std::thread::id> threads() const {
        const std::scoped_lock lock(mutex_);
        return threads_;
    }

private:
    mutable std::mutex mutex_;
    mutable std::vector<std::thread::id> threads_;
};

/// What the workers told their hints: how many joined, each change of the frame's wait, the reports and whether any
/// came while the frame waited on nothing.
class Told final : public scene::WorkerHints {
public:
    void joined() override {
        const std::scoped_lock lock(mutex_);
        ++joined_;
    }
    void urgent(bool on) override {
        const std::scoped_lock lock(mutex_);
        changes_.push_back(on);
        now_ = on;
    }
    void report(std::int64_t start_ns, std::int64_t ns) override {
        const std::scoped_lock lock(mutex_);
        ++reports_;
        if (!now_ || start_ns <= 0 || ns < 0) ++stray_;
    }
    [[nodiscard]] int joins() const {
        const std::scoped_lock lock(mutex_);
        return joined_;
    }
    [[nodiscard]] std::vector<bool> changes() const {
        const std::scoped_lock lock(mutex_);
        return changes_;
    }
    [[nodiscard]] int reports() const {
        const std::scoped_lock lock(mutex_);
        return reports_;
    }
    [[nodiscard]] int stray() const {
        const std::scoped_lock lock(mutex_);
        return stray_;
    }

private:
    mutable std::mutex mutex_;
    int joined_ = 0;
    std::vector<bool> changes_;
    bool now_ = false;
    int reports_ = 0;
    int stray_ = 0;
};

/// The keys of the finest level's first `n` tiles of `pack` in `store`.
[[nodiscard]] std::vector<tiles::TileKey> first_tiles(const tiles::Store& store, std::string_view pack, std::size_t n) {
    std::vector<tiles::TileKey> out;
    for (const std::shared_ptr<const tiles::StorePack>& p : store.packs()) {
        if (p->id != pack || p->pack.head() == nullptr || p->pack.head()->levels.empty()) continue;
        for (const tiles::ChunkRef& c : p->pack.chunks().level(p->pack.head()->levels[0])) {
            if (out.size() == n) break;
            out.push_back(c.tile);
        }
    }
    return out;
}

} // namespace

TEST_CASE("a tile's passes are shared by the idle workers, its items joined in the passes' order (gain 9)") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the workers' sharing is not checked over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    // The first pass the longest: its worker sleeps in it while the idle ones take the others.
    std::vector<std::unique_ptr<frame::Pass>> passes;
    passes.push_back(std::make_unique<Sleeper>("slow", 1, 5, 150));
    passes.push_back(std::make_unique<Sleeper>("quick", 2, 3, 0));
    passes.push_back(std::make_unique<Sleeper>("quicker", 3, 2, 0));
    const auto* slow =
        static_cast<const Sleeper*>(passes[0].get()); // NOLINT(cppcoreguidelines-pro-type-static-cast-downcast)
    const auto* quick =
        static_cast<const Sleeper*>(passes[1].get()); // NOLINT(cppcoreguidelines-pro-type-static-cast-downcast)
    const auto* quicker =
        static_cast<const Sleeper*>(passes[2].get()); // NOLINT(cppcoreguidelines-pro-type-static-cast-downcast)
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    auto world =
        scene::World::create(*session->device, (*builder)->passes(), {.workers = 4, .sun = {.enabled = false}});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    if (!add_bundled(w, dir)) return;
    const std::vector<tiles::TileKey> keys = first_tiles(w.store(), "amsterdam", 1);
    ATLAS_REQUIRE(keys.size() == 1);
    const std::array<scene::Request, 1> one{
        scene::Request{.key = keys[0], .pack = "amsterdam", .lane = scene::Lane::visible}};
    w.residency().want(one);
    w.residency().wait_idle();
    scene::ItemTable items;
    std::vector<std::shared_ptr<const scene::ResidentTile>> taken;
    w.residency().take_published(items, &taken);
    ATLAS_REQUIRE(taken.size() == 1);
    const scene::ResidentTile& t = *taken[0];
    // Each pass ran once, the quick ones on other workers than the slow one, which its decoder took first.
    REQUIRE(slow->threads().size() == 1);
    REQUIRE(quick->threads().size() == 1);
    REQUIRE(quicker->threads().size() == 1);
    CHECK(quick->threads()[0] != slow->threads()[0]);
    CHECK(quicker->threads()[0] != slow->threads()[0]);
    CHECK(w.residency().stats().shared >= 2);
    // The items in the passes' order, though the slow pass finished last; each pass's record its own.
    REQUIRE(t.items.size() == 10);
    for (std::size_t i = 0; i < t.items.size(); ++i) {
        const std::uint16_t kind = i < 5 ? 1 : i < 8 ? 2 : 3;
        CHECK(t.items[i].kind == kind);
        CHECK(t.items[i].tile == t.handle);
    }
    REQUIRE(t.passes.size() == 3);
    CHECK(t.passes[0].values[0] == 5);
    CHECK(t.passes[1].values[0] == 3);
    CHECK(t.passes[2].values[0] == 2);
    CHECK(t.rows.size() == t.items.size());
    w.residency().release(t.key, t.pack, items, 0);
    (void)session->take_messages();
}

TEST_CASE("a pass's work in pieces: one for its worker and one for each waiting, run on them (gain 9)") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: a pass's pieces are not checked over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes;
    passes.push_back(std::make_unique<Pieces>());
    const auto* pieces =
        static_cast<const Pieces*>(passes[0].get()); // NOLINT(cppcoreguidelines-pro-type-static-cast-downcast)
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    auto world =
        scene::World::create(*session->device, (*builder)->passes(), {.workers = 4, .sun = {.enabled = false}});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    if (!add_bundled(w, dir)) return;
    const std::vector<tiles::TileKey> keys = first_tiles(w.store(), "amsterdam", 1);
    ATLAS_REQUIRE(keys.size() == 1);
    // A tile alone: its worker builds it, the three others wait, so its pass splits in four.
    const std::array<scene::Request, 1> one{
        scene::Request{.key = keys[0], .pack = "amsterdam", .lane = scene::Lane::visible}};
    w.residency().want(one);
    w.residency().wait_idle();
    const std::vector<std::thread::id> ran = pieces->threads();
    CHECK(ran.size() == 4);
    std::vector<std::thread::id> distinct = ran;
    std::ranges::sort(distinct);
    distinct.erase(std::ranges::unique(distinct).begin(), distinct.end());
    CHECK(distinct.size() >= 2);
    CHECK(w.residency().stats().pieces >= 1);
    CHECK(w.residency().stats().built == 1);
    MESSAGE(std::format("{} pieces on {} threads", ran.size(), distinct.size()));
    (void)session->take_messages();
}

TEST_CASE("the workers' hints: the frame waits on them while a tile in view is queued or under way, not for the "
          "prefetch (gain 8)") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the workers' hints are not checked over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes;
    passes.push_back(std::make_unique<Sleeper>("one", 1, 2, 20));
    passes.push_back(std::make_unique<Sleeper>("two", 2, 2, 5));
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    auto told = std::make_shared<Told>();
    auto world = scene::World::create(*session->device, (*builder)->passes(),
                                      {.workers = 2, .sun = {.enabled = false}, .hints = told});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    if (!add_bundled(w, dir)) return;
    // Both workers joined, at background priority: nothing waits on them yet.
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (told->joins() < 2 && std::chrono::steady_clock::now() < until) std::this_thread::yield();
    CHECK(told->joins() == 2);
    CHECK(told->changes().empty());
    const std::vector<tiles::TileKey> keys = first_tiles(w.store(), "amsterdam", 3);
    ATLAS_REQUIRE(keys.size() == 3);
    scene::ItemTable items;
    // The prefetch alone: the frame waits on nothing, nothing is reported.
    const std::array<scene::Request, 1> ahead{
        scene::Request{.key = keys[0], .pack = "amsterdam", .lane = scene::Lane::prefetch}};
    w.residency().want(ahead);
    w.residency().wait_idle();
    w.residency().take_published(items);
    CHECK(w.residency().stats().built == 1);
    CHECK(told->changes().empty());
    CHECK(told->reports() == 0);
    // Two tiles in view and one ahead: the frame waits from the wants on, until the last in view is published; each
    // piece of theirs reported while it waits, none after.
    const std::array<scene::Request, 3> mixed{
        scene::Request{.key = keys[1], .pack = "amsterdam", .lane = scene::Lane::visible, .priority = 1.0},
        scene::Request{.key = keys[2], .pack = "amsterdam", .lane = scene::Lane::visible, .priority = 0.5},
        scene::Request{.key = keys[0], .pack = "amsterdam", .lane = scene::Lane::prefetch}};
    w.residency().want(mixed);
    w.residency().wait_idle();
    w.residency().take_published(items);
    CHECK(w.residency().stats().built == 3);
    CHECK(told->changes() == std::vector<bool>{true, false});
    // Each tile's decoding and its two passes at least.
    CHECK(told->reports() >= 6);
    CHECK(told->stray() == 0);
    // Wanted in view and then no longer before it was taken: the frame stops waiting with the cancelled want.
    w.residency().want({});
    CHECK(told->changes() == std::vector<bool>{true, false});
    MESSAGE(std::format("{} workers joined; {} reports while the frame waited", told->joins(), told->reports()));
    (void)session->take_messages();
}
