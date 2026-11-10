// The packs' own stage T (scene/Packs.hpp; frame::Pass::build_pack): when a pack becomes the one drawn, a thread of the
// world's runs every pass's build_pack over it once, reading its tiles, and publishes what each pass kept as the pack's
// ResidentPack; the frame's state carries it (FrameState::active_pack) from the frame that takes it on; a pack drawn
// again is not built again, a pack added to the store again is another pack, built again, and what was built of a pack
// goes with it from the store. A pass whose parts are all left out builds nothing of it, and is built onto it as one
// of its parts comes back (ENGINE.md's parts). On a synthetic render pack (tests/building/fixture.hpp's tile), so CI
// runs it. A frame cell's compositions as its windows join: one waiting is not built once a newer is asked for, and
// each is built with what its passes kept of the last one built (scene::PackBuild::before), through the world too.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "gpu/PipelineCache.hpp"
#include "load/Window.hpp"
#include "scene/Packs.hpp"
#include "scene/World.hpp"
#include "tests/building/fixture.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/load/corner.hpp"
#include "tiles/Container.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <condition_variable>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// What the pack probe keeps of a pack: its id, the tiles of its finest level it read and their features.
struct PackSeen {
    std::string id;
    std::uint32_t tiles = 0;
    std::uint64_t features = 0;
};

/// A pass whose pack-level stage T reads every tile of a pack's finest level once and keeps what it saw; of the camera
/// parts `parts` (every one when none is given: frame::Pass's).
class PackProbe final : public frame::Pass {
public:
    explicit PackProbe(std::uint64_t parts = ~std::uint64_t{0}) : parts_(parts) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "pack probe"; }
    [[nodiscard]] std::uint64_t camera_parts() const noexcept override { return parts_; }
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
    [[nodiscard]] gpu::Result<void> build_pack(scene::PackBuild& b) const override {
        auto seen = std::make_shared<PackSeen>();
        seen->id = b.pack().id;
        ATLAS_GPU_CHECK(b.tiles([&seen](const tiles::Tile& t, const tiles::ChunkRef& c) {
            (void)t;
            ++seen->tiles;
            seen->features += c.count;
        }));
        b.keep(std::shared_ptr<const PackSeen>(std::move(seen)), sizeof(PackSeen));
        builds.fetch_add(1);
        return {};
    }
    mutable std::atomic<int> builds{0};

private:
    std::uint64_t parts_;
};

/// A pass whose pack-level stage T notes each build (its pack, and what it was given of an earlier composition of the
/// pack's id), keeps what it saw as the pack probe does, and for a pack of id `hold`'s waits until let go: a pack
/// under way while others are asked for.
class GateProbe final : public frame::Pass {
public:
    struct Built {
        std::string id;
        const tiles::StorePack* pack = nullptr;
        const void* before = nullptr; ///< PackBuild::before's data, or null
        const void* kept = nullptr;
        std::uint32_t tiles = 0;
    };
    [[nodiscard]] std::string_view name() const noexcept override { return "gate probe"; }
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
    [[nodiscard]] gpu::Result<void> build_pack(scene::PackBuild& b) const override {
        {
            std::unique_lock lock(mutex_);
            if (b.pack().id == held_) {
                entered_ = true;
                changed_.notify_all();
                changed_.wait(lock, [this] { return held_.empty(); });
            }
        }
        auto seen = std::make_shared<PackSeen>();
        seen->id = b.pack().id;
        ATLAS_GPU_CHECK(b.tiles([&seen](const tiles::Tile& t, const tiles::ChunkRef& c) {
            (void)t;
            ++seen->tiles;
            seen->features += c.count;
        }));
        const std::scoped_lock lock(mutex_);
        built_.push_back({.id = seen->id,
                          .pack = &b.pack(),
                          .before = b.before() != nullptr ? b.before()->cpu.get() : nullptr,
                          .kept = seen.get(),
                          .tiles = seen->tiles});
        b.keep(std::shared_ptr<const PackSeen>(std::move(seen)), sizeof(PackSeen));
        return {};
    }
    /// From now on a build of pack `id` waits for open().
    void hold(std::string id) {
        const std::scoped_lock lock(mutex_);
        held_ = std::move(id);
        entered_ = false;
    }
    /// Waits until a held build waits.
    void wait_entered() {
        std::unique_lock lock(mutex_);
        changed_.wait(lock, [this] { return entered_; });
    }
    void open() {
        {
            const std::scoped_lock lock(mutex_);
            held_.clear();
        }
        changed_.notify_all();
    }
    [[nodiscard]] std::vector<Built> built() const {
        const std::scoped_lock lock(mutex_);
        return built_;
    }

private:
    mutable std::mutex mutex_;
    mutable std::condition_variable changed_;
    mutable std::string held_;
    mutable bool entered_ = false;
    mutable std::vector<Built> built_;
};

/// The windows of frame cell L10 526/337 as the app builds them (load::build_window), each with a house `east` and
/// `north` metres of the cell's north-west corner, written under `folder`; their ids the app's.
class CellWindows {
public:
    explicit CellWindows(std::filesystem::path folder) : folder_(std::move(folder)) {
        at_.corner = tiles::TileKey(14, 526U << 4U, 337U << 4U);
        const std::array<std::int32_t, 4> box = tiles::tile_box(at_.corner);
        at_.lat0 = static_cast<double>(box[2]) / 1e7;
        at_.lon0 = static_cast<double>(box[1]) / 1e7;
        at_.kx = 111320.0 * pymath::cos(pymath::radians(at_.lat0));
        std::filesystem::create_directories(folder_);
    }
    /// Window (dx, dy) of the cell, its house at (`east`, `north`); empty where it was not built.
    [[nodiscard]] tiles::PackRef window(std::uint32_t dx, std::uint32_t dy, double east, double north) {
        const tiles::TileKey k(14, at_.corner.x + dx, at_.corner.y + dy);
        const std::string json = std::format(R"({{"version":0.6,"elements":[{}]}})",
                                             test::made_building(way_, R"("building":"yes","building:levels":"4")",
                                                                 east, north - 30.0, east + 30.0, north, at_));
        const auto built = load::build_window(std::as_bytes(std::span(json.data(), json.size())), k, load::place_of(k));
        ATLAS_REQUIRE_OR_RETURN(tiles::PackRef{}, built.has_value(), built.error().what);
        const std::filesystem::path file = folder_ / std::format("{}-{}-{}.atlas", k.x, k.y, way_);
        std::ofstream out(file, std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                  static_cast<std::streamsize>(built->pack.size()));
        return {.id = "window/" + load::place_of(k).name, .kind = tiles::PackKind::window, .path = file.string()};
    }
    /// The point `east` and `north` metres of the corner: {lat, lon}.
    [[nodiscard]] std::array<double, 2> at(double east, double north) const noexcept { return at_.at(east, north); }
    [[nodiscard]] static std::string cell() { return tiles::Store::cell_id(tiles::TileKey(10, 526, 337)); }

private:
    std::filesystem::path folder_;
    test::CornerWindows at_;
    std::int64_t way_ = 1;
};

/// The store's pack of id `id`, or null.
[[nodiscard]] std::shared_ptr<const tiles::StorePack> pack_of(const tiles::Store& store, std::string_view id) {
    for (const std::shared_ptr<const tiles::StorePack>& p : store.packs()) {
        if (p->id == id) return p;
    }
    return nullptr;
}

/// A camera over the synthetic tile's buildings, at rest, on a 1080 × 2400 px frame.
[[nodiscard]] style::CameraState over_the_tile() {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = 52.37422;
    c.longitude = 4.88486;
    c.bearing = 20.0;
    c.pitch = 50.0;
    c.distance = 260.0;
    c.density = 1080.0 / 412.0;
    c.width_px = 1080;
    c.height_px = 2400;
    c.width_dp = 412;
    c.height_dp = 916;
    c.principal_x = 206.0;
    c.principal_y = 440.0;
    c.flags = style::camera_flag::rest;
    return c;
}

} // namespace

TEST_CASE("a pack's own stage T: built once when it is drawn, published to the frame, built again when added again, "
          "and let go of with it") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes;
    auto owned = std::make_unique<PackProbe>();
    const PackProbe* probe = owned.get();
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    frame::FrameBuilder& fb = **builder;
    CHECK(fb.passes()[0]->index() == 0);
    std::atomic<double> now{0}; // the world's clock, read on its workers' threads too
    std::atomic<int> wakes{0};  // before the world: its threads call the wake until it is gone
    auto world =
        scene::World::create(*session->device, fb.passes(), {.workers = 2, .clock = [&now] { return now.load(); }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    w.set_wake([&wakes] { wakes.fetch_add(1); });
    const std::string path =
        test::building::write_pack(test::building::tile(), test::building::tile_features, "packs-test");
    const auto add = [&w, &path] {
        auto added = w.store().add({.id = "synthetic", .kind = tiles::PackKind::bundled, .path = path});
        ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    };
    add();
    fb.set_inputs({.camera = over_the_tile(), .scene = &w});
    // The frames until the pack is drawn and its stage T arrives: the first asks, the thread builds, a later one takes.
    const auto frames = [&](std::uint64_t first, std::uint64_t last) {
        for (std::uint64_t n = first; n <= last; ++n) {
            now = static_cast<double>(n) * 0.016;
            ATLAS_REQUIRE(fb.derive(n, 0, nullptr));
            w.update(fb.state());
            w.residency().wait_idle();
            w.pack_stage().wait_idle();
        }
    };
    frames(1, 4);
    ATLAS_REQUIRE(w.active() != nullptr, "the synthetic pack holds the camera's target");
    const scene::ResidentPack* built = w.active_pack();
    ATLAS_REQUIRE(built != nullptr, "the pack's stage T was published and taken");
    CHECK(built->id == "synthetic");
    CHECK(built->source.get() == w.active());
    REQUIRE(built->passes.size() == 1);
    const auto* seen = built->passes[0].cpu_as<PackSeen>();
    ATLAS_REQUIRE(seen != nullptr, "the probe kept what it saw of the pack");
    CHECK(seen->id == "synthetic");
    CHECK(seen->tiles == 1);
    CHECK(seen->features == test::building::tile_features);
    CHECK(built->passes[0].cpu_bytes == sizeof(PackSeen));
    CHECK(built->build_ms >= 0.0);
    CHECK(probe->builds.load() == 1);
    CHECK(wakes.load() >= 1); // its publication woke the frame loop
    MESSAGE("the synthetic pack's stage T: " << seen->tiles << " tile, " << seen->features << " features, in "
                                             << built->build_ms << " ms");

    // The frame builder's own stage F hands it to the passes (FrameState::active_pack), as it records or emits a frame.
    const std::weak_ptr<const void> kept = built->passes[0].cpu;
    twin::Stream stream;
    fb.emit(stream, 5);
    CHECK(fb.state().active_pack == built);
    CHECK(fb.state().coarser_pack == nullptr);
    // More frames of the same pack: built once.
    frames(6, 12);
    CHECK(probe->builds.load() == 1);
    CHECK(w.active_pack() == built);

    // The pack added again is another pack: the old one's stage T goes, the new one is built.
    CHECK(w.store().remove("synthetic"));
    add();
    frames(13, 20);
    CHECK(probe->builds.load() == 2);
    const scene::ResidentPack* again = w.active_pack();
    ATLAS_REQUIRE(again != nullptr);
    CHECK(again->source.get() == w.active());
    CHECK(kept.expired()); // the first pack's data went with it

    // Gone from the store: nothing of it is kept.
    const std::weak_ptr<const void> kept_again = again->passes[0].cpu;
    CHECK(w.store().remove("synthetic"));
    frames(21, 24);
    CHECK(w.active() == nullptr);
    CHECK(w.active_pack() == nullptr);
    CHECK(kept_again.expired());
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("a pack's own stage T builds no pass whose parts are all left out, and builds it onto the pack as one of its "
          "parts comes back, the others' as they were (ENGINE.md's parts)") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    // the probe of every part, then one of the names on the ground's alone (the canal names' pass, GroundText's)
    std::vector<std::unique_ptr<frame::Pass>> passes;
    auto every = std::make_unique<PackProbe>();
    auto names = std::make_unique<PackProbe>(style::camera_part::ground_text);
    const PackProbe* all = every.get();
    const PackProbe* text = names.get();
    passes.push_back(std::move(every));
    passes.push_back(std::move(names));
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    frame::FrameBuilder& fb = **builder;
    std::atomic<double> now{0};
    auto world =
        scene::World::create(*session->device, fb.passes(),
                             {.workers = 2, .sun = {.enabled = false}, .clock = [&now] { return now.load(); }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    const std::string path =
        test::building::write_pack(test::building::tile(), test::building::tile_features, "packs-parts-test");
    auto added = w.store().add({.id = "synthetic", .kind = tiles::PackKind::bundled, .path = path});
    ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    style::CameraState camera = over_the_tile();
    std::uint64_t n = 0;
    const auto frames = [&](std::uint64_t parts, int count) {
        camera.parts = parts;
        fb.set_inputs({.camera = camera, .scene = &w});
        for (int k = 0; k < count; ++k) {
            ++n;
            now = static_cast<double>(n) * 0.016;
            ATLAS_REQUIRE(fb.derive(n, 0, nullptr));
            w.update(fb.state());
            w.residency().wait_idle();
            w.pack_stage().wait_idle();
        }
    };
    // The names left out: the pack's stage T is the first probe's alone, the second's never run.
    frames(style::camera_part::ground_text, 4);
    const scene::ResidentPack* built = w.active_pack();
    ATLAS_REQUIRE(built != nullptr, "the pack's stage T was published and taken");
    REQUIRE(built->passes.size() == 2);
    CHECK(built->built == 0b01U);
    CHECK(all->builds.load() == 1);
    CHECK(text->builds.load() == 0);
    CHECK(built->passes[0].cpu_as<PackSeen>() != nullptr);
    CHECK(built->passes[1].cpu == nullptr);
    CHECK(built->passes[1].cpu_bytes == 0);
    const std::shared_ptr<const void> first = built->passes[0].cpu;
    // Drawn again: the second probe is built onto the pack alone, and published with the first's data as it was (the
    // same object, not built again) in the pack's one ResidentPack.
    frames(0, 4);
    const scene::ResidentPack* joined = w.active_pack();
    ATLAS_REQUIRE(joined != nullptr);
    CHECK(joined->built == 0b11U);
    CHECK(all->builds.load() == 1);
    CHECK(text->builds.load() == 1);
    CHECK(joined->passes[0].cpu == first);
    const auto* seen = joined->passes[1].cpu_as<PackSeen>();
    ATLAS_REQUIRE(seen != nullptr, "the names' probe kept what it saw of the pack");
    CHECK(seen->id == "synthetic");
    CHECK(seen->tiles == 1);
    CHECK(seen->features == test::building::tile_features);
    CHECK(joined->source.get() == w.active());
    // the frame builder's own stage F hands the joined pack to the passes, as it emits a frame
    twin::Stream stream;
    fb.emit(stream, ++n);
    CHECK(fb.state().active_pack == joined);
    // Left out and drawn again: nothing is built again; what was built stays while the pack does.
    frames(style::camera_part::ground_text, 3);
    frames(0, 3);
    CHECK(all->builds.load() == 1);
    CHECK(text->builds.load() == 1);
    CHECK(w.active_pack() == joined);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("the packs' stage builds a frame cell's newest composition alone: one waiting goes as a newer is asked for, "
          "its earlier build handed on, and one waiting as another is under way takes what that one built") {
    std::vector<std::unique_ptr<frame::Pass>> passes;
    auto owned = std::make_unique<GateProbe>();
    GateProbe& probe = *owned;
    passes.push_back(std::move(owned));
    tiles::Store store;
    const std::string path =
        test::building::write_pack(test::building::tile(), test::building::tile_features, "packs-burst-test");
    ATLAS_REQUIRE(store.add({.id = "synthetic", .kind = tiles::PackKind::bundled, .path = path}).has_value());
    CellWindows cell(std::filesystem::path(ATLAS_TEST_SCRATCH) / "packs-burst");
    const std::array<tiles::PackRef, 6> windows{cell.window(0, 0, 300, -300),  cell.window(1, 0, 1790, -300),
                                                cell.window(0, 1, 300, -1790), cell.window(1, 1, 1790, -1790),
                                                cell.window(2, 0, 3280, -300), cell.window(2, 1, 3280, -1790)};
    for (const tiles::PackRef& r : windows) ATLAS_REQUIRE(!r.path.empty());
    scene::PackStage stage(store, passes);
    std::vector<std::shared_ptr<const scene::ResidentPack>> published;
    const auto join = [&](const tiles::PackRef& r) {
        const auto added = store.add(r);
        ATLAS_REQUIRE_OR_RETURN(std::shared_ptr<const tiles::StorePack>{}, added.has_value(),
                                tiles::describe(added.error()));
        return pack_of(store, CellWindows::cell());
    };
    // The cell of one window, built.
    const auto first = join(windows[0]);
    ATLAS_REQUIRE(first != nullptr);
    stage.want(first);
    stage.wait_idle();
    stage.take(published);
    ATLAS_REQUIRE(published.size() == 1);
    const std::shared_ptr<const scene::ResidentPack> built = published[0];
    published.clear();

    // While the stage builds another pack, three more windows join, each composition asked for as the world asks: the
    // first with the cell's build before (the world's), the others with none (the world let it go with its source).
    // The newest alone is built, given that build.
    probe.hold("synthetic");
    stage.want(pack_of(store, "synthetic"));
    probe.wait_entered();
    const auto second = join(windows[1]);
    stage.want(second, ~std::uint64_t{0}, built);
    const auto third = join(windows[2]);
    stage.want(third);
    const auto fourth = join(windows[3]);
    stage.want(fourth);
    CHECK(stage.dropped() == 2);
    probe.open();
    stage.wait_idle();
    stage.take(published);
    ATLAS_REQUIRE(published.size() == 2);
    CHECK(published[0]->id == "synthetic");
    CHECK(published[1]->source == fourth);
    std::vector<GateProbe::Built> seen = probe.built();
    ATLAS_REQUIRE(seen.size() == 3);
    CHECK(seen[0].before == nullptr);
    CHECK(seen[2].pack == fourth.get());
    CHECK(seen[2].before == built->passes[0].cpu.get());
    CHECK(seen[2].tiles == 4);
    const std::shared_ptr<const scene::ResidentPack> newest = published[1];
    published.clear();

    // A composition under way when a newer is asked for is built, and the newer, waiting, takes what it built.
    probe.hold(CellWindows::cell());
    const auto fifth = join(windows[4]);
    stage.want(fifth, ~std::uint64_t{0}, newest);
    probe.wait_entered();
    const auto sixth = join(windows[5]);
    stage.want(sixth);
    probe.open();
    stage.wait_idle();
    stage.take(published);
    ATLAS_REQUIRE(published.size() == 2);
    CHECK(published[0]->source == fifth);
    CHECK(published[1]->source == sixth);
    seen = probe.built();
    ATLAS_REQUIRE(seen.size() == 5);
    CHECK(seen[3].before == newest->passes[0].cpu.get());
    CHECK(seen[4].before == published[0]->passes[0].cpu.get());
    CHECK(seen[4].tiles == 6);
    CHECK(stage.dropped() == 2);
    stage.stop();
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("the world asks for a frame cell's newer composition with what its passes kept of the one drawn before") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes;
    auto owned = std::make_unique<GateProbe>();
    const GateProbe& probe = *owned;
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    frame::FrameBuilder& fb = **builder;
    std::atomic<double> now{0};
    auto world =
        scene::World::create(*session->device, fb.passes(),
                             {.workers = 2, .sun = {.enabled = false}, .clock = [&now] { return now.load(); }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    CellWindows cell(std::filesystem::path(ATLAS_TEST_SCRATCH) / "packs-world-cell");
    const tiles::PackRef a = cell.window(0, 0, 300, -300);
    const tiles::PackRef b = cell.window(1, 0, 1790, -300);
    ATLAS_REQUIRE(!a.path.empty() && !b.path.empty());
    ATLAS_REQUIRE(w.store().add(a).has_value());
    // over the first window's house
    style::CameraState camera = over_the_tile();
    const std::array<double, 2> house = cell.at(315, -315);
    camera.latitude = house[0];
    camera.longitude = house[1];
    fb.set_inputs({.camera = camera, .scene = &w});
    std::uint64_t n = 0;
    const auto frames = [&](int count) {
        for (int k = 0; k < count; ++k) {
            ++n;
            now = static_cast<double>(n) * 0.016;
            ATLAS_REQUIRE(fb.derive(n, 0, nullptr));
            w.update(fb.state());
            w.residency().wait_idle();
            w.pack_stage().wait_idle();
        }
    };
    frames(5);
    ATLAS_REQUIRE(w.active_pack() != nullptr, "the cell's stage T was published and taken");
    CHECK(w.active_pack()->id == CellWindows::cell());
    const std::shared_ptr<const void> kept = w.active_pack()->passes[0].cpu;
    ATLAS_REQUIRE(kept != nullptr);
    // the second window joins: the cell composed again, built with what the first composition's build kept
    ATLAS_REQUIRE(w.store().add(b).has_value());
    frames(6);
    const scene::ResidentPack* again = w.active_pack();
    ATLAS_REQUIRE(again != nullptr);
    CHECK(again->source.get() == w.active());
    CHECK(again->passes[0].cpu != kept);
    const std::vector<GateProbe::Built> seen = probe.built();
    ATLAS_REQUIRE(seen.size() == 2);
    CHECK(seen[0].before == nullptr);
    CHECK(seen[1].before == kept.get());
    CHECK(seen[1].tiles == 2);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
}
