// The packs' own stage T (scene/Packs.hpp; frame::Pass::build_pack): when a pack becomes the one drawn, a thread of the
// world's runs every pass's build_pack over it once, reading its tiles, and publishes what each pass kept as the pack's
// ResidentPack; the frame's state carries it (FrameState::active_pack) from the frame that takes it on; a pack drawn
// again is not built again, a pack added to the store again is another pack, built again, and what was built of a pack
// goes with it from the store. On a synthetic render pack (tests/building/fixture.hpp's tile), so CI runs it.
#include "frame/FrameBuilder.hpp"
#include "gpu/PipelineCache.hpp"
#include "scene/Packs.hpp"
#include "scene/World.hpp"
#include "tests/building/fixture.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tiles/Container.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <filesystem>
#include <memory>
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

/// A pass whose pack-level stage T reads every tile of a pack's finest level once and keeps what it saw.
class PackProbe final : public frame::Pass {
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "pack probe"; }
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
};

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
    double now = 0;
    std::atomic<int> wakes{0}; // before the world: its threads call the wake until it is gone
    auto world = scene::World::create(*session->device, fb.passes(), {.workers = 2, .clock = [&now] { return now; }});
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
