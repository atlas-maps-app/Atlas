// The scene on the host's Vulkan (plan-vector D3, D5.7, K7): the geometry heaps (VMA's virtual blocks: ranges
// allocated, written in place, grown with their bytes kept at their offsets, retired and freed after their frames), and
// the world over the bundled render packs (ATLAS_TILES_DIR, as the tile reader's and the sun's tests take them): the
// active pack for the camera, its ladder snapping the Westerkerk home onto the living map's view, the cover's requests
// built on the workers by every pass's stage T into the heaps, published, taken with their items, drawn with their
// visible cells, fading in, and let go of when the camera leaves; the sun's scene the resident tiles and their 220 m
// halo.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "scene/Heaps.hpp"
#include "scene/World.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tiles/layers/Buildings.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

using namespace atlas;

namespace {

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before threads start
    return v == nullptr ? std::string() : std::string(v);
}

/// A pass whose stage T writes each tile's key and cell counts into the points heap and adds an item per building.
class BuildProbe final : public frame::Pass {
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "build probe"; }
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
        const tiles::Tile& t = b.tile();
        const auto cells = static_cast<std::uint32_t>(t.cells.size());
        ATLAS_GPU_LET(range, b.allocate(scene::HeapKind::points, 16 + 4 * cells, 16));
        {
            const scene::Heaps::Writer w = b.heaps().writer(range);
            const std::uint32_t head[4] = {t.header.level, t.header.x, t.header.y, cells};
            std::memcpy(w.bytes().data(), head, sizeof head);
            for (std::uint32_t c = 0; c < cells; ++c) {
                std::memcpy(w.bytes().data() + std::size_t{16} + (std::size_t{4} * c), &t.cells[c].total, 4);
            }
        }
        b.out().ranges[0] = range;
        b.out().cell_first.assign(cells + 1, 0);
        for (std::uint32_t c = 0; c < cells; ++c) b.out().cell_first[c + 1] = b.out().cell_first[c] + t.cells[c].total;
        if (const tiles::Layer* layer = t.layer(tiles::LayerId::building)) {
            for (std::uint32_t i = 0; i < layer->count; ++i) {
                const tiles::RingView ring = layer->ring(tiles::building::ring, i);
                if (ring.size() == 0) continue;
                const tiles::Frame& f = b.head().frame;
                const double x = f.x(ring.lon[0]);
                const double z = f.z(ring.lat[0]);
                b.add_item(
                    {.x = x,
                     .y = 5.0,
                     .z = z,
                     .offset = 0,
                     .cut_x = x,
                     .cut_z = z,
                     .order = scene::order_of(0, static_cast<std::uint64_t>(layer->integer(tiles::building::index, i))),
                     .kind = 0,
                     .flags = scene::item_flag::radius,
                     .slots = {8, 4, 2, 1},
                     .min_depth = 2.0F,
                     .radius = 30.0F,
                     .tile = 0});
            }
        }
        built.fetch_add(1);
        return {};
    }
    mutable std::atomic<int> built{0};
};

/// The Westerkerk home (amsterdam.py's main camera): 10 m west and 30 m north of the Westerkerk, yaw 52°, 38° down,
/// 430 m, on S1 (1080 × 2400 px, 412 × 916 dp).
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

} // namespace

TEST_CASE("the geometry heaps allocate, write in place, grow keeping every range's bytes, and free after the frames") {
    auto session = test::open_session();
    if (!session) return;
    scene::HeapBudget budget;
    budget.initial.fill(vk::DeviceSize{64} << 10U);
    budget.total = vk::DeviceSize{2} << 20U;
    auto heaps = scene::Heaps::create(*session->device, budget);
    ATLAS_REQUIRE(heaps.has_value(), heaps.error().text());
    scene::Heaps& h = **heaps;
    std::vector<scene::HeapRange> ranges;
    std::vector<std::byte> pattern(1000);
    for (std::size_t i = 0; i < 200; ++i) { // 200 KB in a 64 KB heap: it grows twice
        auto r = h.allocate(scene::HeapKind::walls, 1000, 64);
        ATLAS_REQUIRE(r.has_value(), r.error().text());
        CHECK(r->offset % 64 == 0);
        for (std::size_t k = 0; k < pattern.size(); ++k) pattern[k] = static_cast<std::byte>((i * 31 + k) & 0xFF);
        h.write(*r, pattern);
        ranges.push_back(*r);
    }
    CHECK(h.capacity(scene::HeapKind::walls) >= 200'000);
    CHECK(h.stats().growths >= 2);
    CHECK(h.version() >= 2);
    // Every range kept its bytes at its offset through the growths.
    bool kept = true;
    for (std::size_t i = 0; i < ranges.size(); ++i) {
        const scene::Heaps::Writer w = h.writer(ranges[i]);
        for (std::size_t k = 0; k < 1000; ++k) kept &= w.bytes()[k] == static_cast<std::byte>((i * 31 + k) & 0xFF);
    }
    CHECK(kept);
    // Retired after frame 7, freed once frame 7 has finished.
    const vk::DeviceSize used = h.stats().used[static_cast<std::size_t>(scene::HeapKind::walls)];
    for (std::size_t i = 0; i < 100; ++i) h.retire(ranges[i], 7);
    h.bound(8);
    h.collect(6);
    CHECK(h.stats().used[static_cast<std::size_t>(scene::HeapKind::walls)] == used);
    h.collect(7);
    CHECK(h.stats().used[static_cast<std::size_t>(scene::HeapKind::walls)] == used - (vk::DeviceSize{100} * 1000));
    CHECK(h.stats().retired == 0);
    // The budget holds: a range the heaps cannot grow to is refused, saying why.
    auto huge = h.allocate(scene::HeapKind::points, std::uint32_t{4} << 20U, 16);
    CHECK_FALSE(huge.has_value());
    if (!huge) CHECK(huge.error().what.find("cannot grow") != std::string::npos);
    (void)session->take_messages();
}

TEST_CASE("the world builds the cover's tiles on its workers, draws their visible cells and lets them go") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the world is not checked over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes;
    auto owned = std::make_unique<BuildProbe>();
    const BuildProbe* probe = owned.get();
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world =
        scene::World::create(*session->device, (*builder)->passes(), {.workers = 3, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        if (!std::filesystem::exists(path)) continue;
        auto added = w.store().add({.id = name, .kind = tiles::PackKind::bundled, .path = path.string()});
        ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    }
    (*builder)->set_inputs({.camera = home(), .scene = &w});

    // A few frames: the first asks, the workers build, the next ones take and draw.
    std::size_t drawn = 0;
    for (std::uint64_t n = 1; n <= 40 && drawn == 0; ++n) {
        now = static_cast<double>(n) * 0.016;
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
        w.residency().wait_idle();
        drawn = w.cover()->tiles().size();
    }
    ATLAS_REQUIRE(w.active() != nullptr, "no pack holds the Westerkerk home's target");
    CHECK(w.active()->id == "amsterdam"); // the store's order: the bundled Westerkerk before the canal ring
    const frame::FrameState& state = (*builder)->state();
    ATLAS_REQUIRE(state.placed.has_value());
    CHECK_MESSAGE(state.placed->view().has_value(), "the home view snaps onto the living map's view");
    if (state.placed->view()) MESSAGE("snapped onto " << std::string(*state.placed->view()));
    CHECK(drawn > 0);
    CHECK(probe->built.load() > 0);
    CHECK(w.items()->live() > 0);
    for (const scene::CoverTile& t : w.cover()->tiles()) {
        CHECK(t.drawn != nullptr);
        CHECK(t.cells != 0);
        CHECK(t.drawn->passes.size() == 1);
        CHECK(t.drawn->passes[0].ranges[0].size > 0);
        CHECK(t.fade >= 0.0F);
    }
    // Later frames: the fade completes, nothing more is asked for the same view.
    for (std::uint64_t n = 41; n <= 60; ++n) {
        now = static_cast<double>(n) * 0.016;
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
        w.residency().wait_idle();
    }
    for (const scene::CoverTile& t : w.cover()->tiles()) CHECK(t.fade == 1.0F);
    CHECK(w.cover()->missing() == 0);
    const auto sun = w.residency().sun_tiles("amsterdam");
    CHECK(sun.size() >= w.residency().resident().size() / 2);
    MESSAGE(w.tiles_line() << "; " << w.cover()->tiles().size() << " drawn, " << w.items()->live()
                           << " items, the sun's scene " << sun.size() << " tiles");

    // The camera leaves for nowhere (mid-Atlantic): no pack holds it, and after the forget time every tile goes.
    style::CameraState away = home();
    away.latitude = 40.0;
    away.longitude = -30.0;
    (*builder)->set_inputs({.camera = away, .scene = &w});
    for (std::uint64_t n = 61; n <= 64; ++n) {
        now = 100.0 + static_cast<double>(n);
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
    }
    CHECK(w.active() == nullptr);
    CHECK(w.cover()->tiles().empty());
    (void)session->take_messages();
}
