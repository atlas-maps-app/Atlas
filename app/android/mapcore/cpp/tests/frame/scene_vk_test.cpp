// The scene on the host's Vulkan (plan-vector D3, D5.7, K7): the geometry heaps (VMA's virtual blocks: ranges
// allocated, written in place, grown with their bytes kept at their offsets, retired and freed after their frames), and
// the world over the bundled render packs (ATLAS_TILES_DIR, as the tile reader's and the sun's tests take them): the
// active pack for the camera, its ladder snapping the Westerkerk home onto the living map's view, the cover's requests
// built on the workers by every pass's stage T into the heaps, published, taken with their items, drawn with their
// visible cells, fading in, and let go of when the camera leaves, the passes' CPU-side data with them (counted in the
// tiles' native bytes); the sun's scene the resident tiles and their 220 m halo.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "gpu/PipelineCache.hpp"
#include "scene/Heaps.hpp"
#include "scene/World.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tiles/layers/Buildings.hpp"
#include "view/Frames.hpp"
#include "view/Ladder.hpp"

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"
#endif

#include <doctest/doctest.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

using namespace atlas;

namespace {

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before threads start
    return v == nullptr ? std::string() : std::string(v);
}

/// What the probe keeps of a tile on the CPU: each building's first vertex in the frame's metres, in double.
struct FirstVertices {
    std::vector<double> xz;
};

/// A pass whose stage T writes each tile's key and cell counts into the points heap, adds an item per building and
/// keeps the buildings' first vertices on the CPU (PassTile::cpu).
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
        auto kept = std::make_shared<FirstVertices>();
        if (const tiles::Layer* layer = t.layer(tiles::LayerId::building)) {
            for (std::uint32_t i = 0; i < layer->count; ++i) {
                const tiles::RingView ring = layer->ring(tiles::building::ring, i);
                if (ring.size() == 0) continue;
                const tiles::Frame& f = b.head().frame;
                const double x = f.x(ring.lon[0]);
                const double z = f.z(ring.lat[0]);
                kept->xz.push_back(x);
                kept->xz.push_back(z);
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
        const std::size_t bytes = sizeof(FirstVertices) + (kept->xz.capacity() * sizeof(double));
        b.keep(std::shared_ptr<const FirstVertices>(std::move(kept)), bytes);
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
    std::atomic<int> wakes{0}; // before the world: its threads call the wake until it is gone
    auto world =
        scene::World::create(*session->device, (*builder)->passes(), {.workers = 3, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    w.set_wake([&wakes] { wakes.fetch_add(1); });
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
    // Each tile published woke the frame loop (and each tile's sun bits), and the frame that first draws them fades
    // them in.
    CHECK(wakes.load() >= probe->built.load());
    CHECK(w.animating());
    CHECK(w.items()->live() > 0);
    for (const scene::CoverTile& t : w.cover()->tiles()) {
        CHECK(t.drawn != nullptr);
        CHECK(t.cells != 0);
        CHECK(t.drawn->passes.size() == 1);
        CHECK(t.drawn->passes[0].ranges[0].size > 0);
        CHECK(t.fade >= 0.0F);
    }
    // The pass's CPU-side data, as its stage T kept it: a first vertex per item, counted in the tile's native bytes.
    std::size_t native = 0;
    std::weak_ptr<const void> one_kept;
    for (const std::shared_ptr<const scene::ResidentTile>& t : w.residency().resident()) {
        const scene::PassTile& p = t->passes[0];
        const auto* kept = p.cpu_as<FirstVertices>();
        ATLAS_REQUIRE(kept != nullptr, "the probe kept its tile's first vertices");
        CHECK(kept->xz.size() == 2 * t->items.size());
        for (std::size_t i = 0; i < t->items.size(); ++i) CHECK(kept->xz[2 * i] == t->items[i].x);
        CHECK(p.cpu_bytes >= kept->xz.size() * sizeof(double));
        CHECK(t->native_bytes >= p.cpu_bytes + (t->items.size() * sizeof(scene::ItemSpec)));
        native += t->native_bytes;
        one_kept = p.cpu;
    }
    CHECK(w.residency().stats().native_bytes == native);
    MESSAGE(w.residency().resident().size() << " resident tiles hold " << native << " native bytes");

    // The sun: its scene the resident tiles and their halo, its bits published, every drawn tile's texels in the frame
    // slot's half of its buffer, as BitTiles gives them.
    REQUIRE(w.sun() != nullptr);
    w.sun()->wait_idle();
    now += 1.0; // past every fade
    ATLAS_REQUIRE((*builder)->derive(61, 1, nullptr));
    w.update((*builder)->state());
    const scene::SunStats sun_stats = w.sun()->stats();
    CHECK(sun_stats.scenes >= 1);
    CHECK(sun_stats.runs >= 1);
    CHECK(sun_stats.tiles >= w.residency().resident().size());
    std::size_t lit_tiles = 0;
    for (const scene::CoverTile& t : w.cover()->tiles()) {
        if (t.drawn->key.z != t.key.z) continue; // a stand-in ancestor has no bits
        if (!t.sun) {
            FAIL("a drawn tile without its sun bits after the sun went idle");
            continue;
        }
        REQUIRE(t.sun_base != scene::no_sun);
        CHECK(t.sun_weight == 1.0F);
        std::vector<std::uint32_t> want;
        REQUIRE(w.sun()->bits().texels(t.drawn->key, now, want).has_value());
        const std::span<const std::byte> bytes = w.sun()->buffer().bytes();
        REQUIRE(bytes.size() >= (std::size_t{t.sun_base} * 8) + (want.size() * 4));
        // Frame slot 1: the second half.
        CHECK(t.sun_base >= w.sun()->capacity());
        CHECK(std::memcmp(bytes.data() + (std::size_t{t.sun_base} * 8), want.data(), want.size() * 4) == 0);
        CHECK(want.size() == 2 * ((std::size_t{t.sun->now->count} + 31) / 32)); // a texel per 32 samples
        ++lit_tiles;
    }
    CHECK(lit_tiles > 0);
    MESSAGE("the sun: " << sun_stats.scenes << " scene(s) of " << sun_stats.tiles << " tiles (" << sun_stats.resident
                        << " resident) in " << sun_stats.scene_ms << " ms, " << sun_stats.runs << " run(s), "
                        << w.sun()->stats().texels << " texels this frame for " << lit_tiles << " drawn tiles");
    // Later frames: the fade completes, nothing more is asked for the same view.
    for (std::uint64_t n = 41; n <= 60; ++n) {
        now = static_cast<double>(n) * 0.016;
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
        w.residency().wait_idle();
    }
    for (const scene::CoverTile& t : w.cover()->tiles()) CHECK(t.fade == 1.0F);
    CHECK(w.cover()->missing() == 0);
    CHECK_FALSE(w.animating()); // the fades are over: the frame loop may rest
    const auto sun = w.residency().sun_tiles("amsterdam");
    CHECK(sun.size() >= w.residency().resident().size() / 2);
    MESSAGE(w.tiles_line() << "; " << w.cover()->tiles().size() << " drawn, " << w.items()->live()
                           << " items, the sun's scene " << sun.size() << " tiles");

    // The camera leaves for nowhere (mid-Atlantic): no pack holds it, and after the forget time every tile goes.
    style::CameraState away = home();
    away.latitude = 40.0;
    away.longitude = -30.0;
    (*builder)->set_inputs({.camera = away, .scene = &w});
    for (std::uint64_t n = 62; n <= 65; ++n) {
        now = 100.0 + static_cast<double>(n);
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
    }
    CHECK(w.active() == nullptr);
    CHECK(w.cover()->tiles().empty());
    // Every tile went, and its CPU-side data with it.
    CHECK(w.residency().resident().empty());
    CHECK(w.residency().stats().native_bytes == 0);
    CHECK(one_kept.expired());
    (void)session->take_messages();
}

TEST_CASE("a coarser pack is drawn around the active one, its items in the frame drawn and masked inside its box") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    const std::filesystem::path westerkerk = std::filesystem::path(dir) / "amsterdam.atlas";
    const std::filesystem::path canals = std::filesystem::path(dir) / "grachten.atlas";
    if (dir.empty() || !std::filesystem::exists(canals)) {
        MESSAGE("ATLAS_TILES_DIR has no canal ring's pack: the coarser pack is not checked here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes;
    passes.push_back(std::make_unique<BuildProbe>());
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world =
        scene::World::create(*session->device, (*builder)->passes(),
                             {.workers = 3, .clock = [&now] { return now; }}); // the sun on: the canal ring's lane too
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    for (const auto& [id, path] : {std::pair{"amsterdam", westerkerk}, std::pair{"grachten", canals}}) {
        auto added = w.store().add({.id = id, .kind = tiles::PackKind::bundled, .path = path.string()});
        ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    }
    // The Westerkerk active, the canal ring around it the world's own choice (the smallest pack whose box holds the
    // Westerkerk's, as the app's map has it), from higher up so both packs' tiles are in view.
    w.set_active("amsterdam");
    w.set_coarser_enclosing(true);
    style::CameraState c = home();
    c.distance = 1600.0;
    c.flags = 0;
    (*builder)->set_inputs({.camera = c, .scene = &w});
    for (std::uint64_t n = 1; n <= 60; ++n) {
        now = static_cast<double>(n) * 0.016;
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
        w.residency().wait_idle();
    }
    ATLAS_REQUIRE(w.active() != nullptr);
    ATLAS_REQUIRE(w.coarser() != nullptr);
    CHECK(w.coarser()->id == "grachten");
    // A pack named comes first: the active one itself is none, the canal ring named is the canal ring.
    const auto one_frame = [&](std::uint64_t n) {
        ATLAS_REQUIRE((*builder)->derive(n, 0, nullptr));
        w.update((*builder)->state());
    };
    w.set_coarser("amsterdam");
    one_frame(61);
    CHECK(w.coarser() == nullptr);
    w.set_coarser("grachten");
    one_frame(62);
    ATLAS_REQUIRE(w.coarser() != nullptr);
    CHECK(w.coarser()->id == "grachten");
    ATLAS_REQUIRE(w.mask().has_value());
    const tiles::GridBox mask = *w.mask();
    // Both packs' tiles are drawn; none of the canal ring's lies wholly inside the Westerkerk's box.
    std::size_t active_tiles = 0;
    std::size_t coarser_tiles = 0;
    for (const scene::CoverTile& t : w.cover()->tiles()) {
        if (t.drawn->pack == "amsterdam") {
            ++active_tiles;
            continue;
        }
        ++coarser_tiles;
        const tiles::GridBox& b = t.drawn->reach.box;
        CHECK_FALSE((b.south >= mask.south && b.north <= mask.north && b.west >= mask.west && b.east <= mask.east));
    }
    CHECK(active_tiles > 0);
    CHECK(coarser_tiles > 0);
    // The canal ring's items: in the frame drawn (the Westerkerk's), masked where their reference point is inside.
    const view::GroundFrame drawn = w.frames().current().value_or(view::GroundFrame{});
    const tiles::Frame& cf = w.coarser()->pack.head()->frame;
    std::size_t masked = 0;
    std::size_t shown = 0;
    std::size_t converted = 0;
    for (const std::shared_ptr<const scene::ResidentTile>& t : w.residency().resident()) {
        for (std::size_t i = 0; i < t->rows.size(); ++i) {
            const scene::ItemSpec& spec = t->items[i];
            const std::uint32_t row = t->rows[i];
            const bool is_masked = (w.items()->flags()[row] & scene::item_flag::masked) != 0;
            if (t->pack == "amsterdam") {
                CHECK_FALSE(is_masked);
                CHECK(w.items()->x()[row] == spec.x);
                continue;
            }
            const bool inside = spec.cut_x >= cf.x(mask.west) && spec.cut_x <= cf.x(mask.east) &&
                                spec.cut_z >= cf.z(mask.north) && spec.cut_z <= cf.z(mask.south);
            CHECK(is_masked == inside);
            (inside ? masked : shown) += 1;
            const view::Vec3 p = view::convert(t->frame, drawn, {spec.x, spec.y, spec.z});
            CHECK(w.items()->x()[row] == p.x);
            CHECK(w.items()->z()[row] == p.z);
            converted += (w.items()->x()[row] != spec.x) ? 1U : 0U;
        }
    }
    CHECK(masked > 0);
    CHECK(shown > 0);
    CHECK(converted > 0); // the two packs' frames differ
    // The ranks never draw a masked item.
    const std::optional<view::View>& frame_view = (*builder)->state().view;
    if (!frame_view) {
        FAIL("the frame has no view");
        return;
    }
    frame::Selectors selectors;
    frame::Ranks ranks;
    ranks.update(*w.items(), frame_view->camera(), selectors);
    std::size_t drawn_masked = 0;
    for (std::uint32_t row = 0; row < w.items()->rows(); ++row) {
        if (w.items()->alive(row) && (w.items()->flags()[row] & scene::item_flag::masked) != 0 &&
            ranks.first_slot()[row] != frame::Ranks::none) {
            ++drawn_masked;
        }
    }
    CHECK(drawn_masked == 0);
    MESSAGE(active_tiles << " of the Westerkerk's tiles and " << coarser_tiles << " of the canal ring's drawn; of the "
                         << "canal ring's items " << masked << " masked inside the Westerkerk's box, " << shown
                         << " drawn, every one in the Westerkerk's frame");
    // The canal ring has its own sun lane: once its scene is built and its bucket run, every tile of it the cover draws
    // at its finest zoom has its bits, as the Westerkerk's do.
    REQUIRE(w.sun() != nullptr);
    w.sun()->wait_idle();
    one_frame(63);
    std::size_t ring_lit = 0;
    std::size_t ring_drawn = 0;
    for (const scene::CoverTile& t : w.cover()->tiles()) {
        if (t.drawn->pack != "grachten" || t.drawn->key.z != w.coarser()->pack.head()->levels[0]) continue;
        ++ring_drawn;
        if (t.sun && t.sun_base != scene::no_sun) ++ring_lit;
    }
    MESSAGE(ring_lit << " of the canal ring's " << ring_drawn << " drawn tiles have their sun bits");
    CHECK(ring_drawn > 0);
    CHECK(ring_lit == ring_drawn);
    // Without the coarser pack (none named, none chosen), its tiles go from the cover and its items' masks with them.
    w.set_coarser({});
    w.set_coarser_enclosing(false);
    ATLAS_REQUIRE((*builder)->derive(61, 0, nullptr));
    w.update((*builder)->state());
    for (const scene::CoverTile& t : w.cover()->tiles()) CHECK(t.drawn->pack == "amsterdam");
    for (std::uint32_t row = 0; row < w.items()->rows(); ++row) {
        if (w.items()->alive(row)) CHECK((w.items()->flags()[row] & scene::item_flag::masked) == 0);
    }
    (void)session->take_messages();
}

namespace {

/// A provider of set 1's noise fields (K17a's bindings) that only counts what the frame asks of it.
class CountingGlobals final : public frame::GlobalProvider {
public:
    explicit CountingGlobals(std::array<frame::Global, 1> bindings) noexcept : bindings_(bindings) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "counting"; }
    [[nodiscard]] std::span<const frame::Global> bindings() const noexcept override { return bindings_; }
    void update(const frame::FrameState& state, frame::Globals& globals) override {
        (void)globals;
        last = state.number;
        ++updates;
    }
    void record(const gpu::FrameContext& frame) override {
        (void)frame;
        ++records;
    }
    /// It says its images are bound once `bound` is set.
    [[nodiscard]] std::uint32_t frame_flags(const frame::FrameState& state) const noexcept override {
        (void)state;
        return bound ? shaders::FRAME_FIELDS : 0U;
    }
    bool bound = false;
    std::uint64_t last = 0;
    int updates = 0;
    int records = 0;

private:
    std::array<frame::Global, 1> bindings_;
};

} // namespace

#if !defined(__ANDROID__)
TEST_CASE("the world's providers fill set 1's globals: the heaps and the sun bound once, a provider per binding") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the world's globals are not checked over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    passes.push_back(std::make_unique<BuildProbe>());
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world =
        scene::World::create(*session->device, (*builder)->passes(), {.workers = 2, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    scene::World& w = **world;
    const std::filesystem::path path = std::filesystem::path(dir) / "amsterdam.atlas";
    auto added = w.store().add({.id = "amsterdam", .kind = tiles::PackKind::bundled, .path = path.string()});
    ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));

    // A binding has one provider: the sun's is refused to another, a free one taken.
    auto stealing = w.add_globals(std::make_shared<CountingGlobals>(std::array{frame::Global::sun_bits}));
    REQUIRE_FALSE(stealing.has_value());
    MESSAGE(stealing.error().text());
    CHECK(stealing.error().text().find("sun_bits") != std::string::npos);
    auto fields = std::make_shared<CountingGlobals>(std::array{frame::Global::noise_rgba});
    REQUIRE(w.add_globals(fields).has_value());
    CHECK(w.globals().size() == 5); // the heaps', the sun's, the registered text passes' (glyphs, sprites), the fields'

    (*builder)->set_inputs({.camera = home(), .scene = &w});
    auto output = platform::Offscreen::create(*session->device, {.width = 412, .height = 916}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    auto renderer = gpu::Renderer::create(*session->device, **output, **builder, session->device->facts().choice);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    auto frame_at = [&](double t) {
        now = t;
        auto report = (*renderer)->draw(0);
        ATLAS_REQUIRE(report.has_value(), report.error().text());
    };
    // Frames until the tiles are drawn and the sun's bits are in, then frames at rest.
    for (int n = 0; n < 40 && w.cover()->tiles().empty(); ++n) {
        frame_at(0.016 * n);
        w.residency().wait_idle();
    }
    REQUIRE_FALSE(w.cover()->tiles().empty());
    w.sun()->wait_idle();
    frame_at(2.0);
    frame_at(2.1);
    const frame::FrameLayout& layout = (*builder)->layout();
    CHECK(layout.bound_buffer(frame::Global::points) == w.heaps()->buffer(scene::HeapKind::points));
    CHECK(layout.bound_buffer(frame::Global::labels) == w.heaps()->buffer(scene::HeapKind::labels));
    REQUIRE(w.sun()->buffer());
    CHECK(layout.bound_buffer(frame::Global::sun_bits) == w.sun()->buffer().handle());
    // The frame drawn, as the passes read it: the Westerkerk's.
    REQUIRE((*builder)->state().ground_frame.has_value());
    CHECK((*builder)->state().ground_frame == w.frames().current());
    // Set 1's sprites are a storage buffer (K13 copies its texels whole): its placeholder's until K13 binds its own.
    CHECK(frame::is_buffer(frame::Global::sprites));
    CHECK(layout.bound_buffer(frame::Global::sprites));
    // At rest nothing in set 1 changes: no new version of it, frame after frame.
    const std::uint64_t versions = layout.global_versions();
    for (int n = 0; n < 6; ++n) frame_at(2.2 + 0.016 * n);
    CHECK(layout.global_versions() == versions);
    // The added provider was asked every frame, before its record, after the scene's update.
    CHECK(fields->updates == fields->records);
    CHECK(fields->updates >= 8);
    CHECK(fields->last == (*builder)->state().number);
    // FRAME_FIELDS: once its images are bound, and in full mode alone.
    CHECK(((*builder)->state().block.info.x & shaders::FRAME_FIELDS) == 0U);
    fields->bound = true;
    frame_at(2.5);
    CHECK(((*builder)->state().block.info.x & shaders::FRAME_FIELDS) == 0U); // structural mode
    (*builder)->set_inputs({.camera = home(), .mode = frame::Mode::full, .scene = &w});
    frame_at(2.6);
    CHECK(((*builder)->state().block.info.x & shaders::FRAME_FIELDS) != 0U);
    CHECK(((*builder)->state().block.info.x & shaders::FRAME_FULL) != 0U);
    fields->bound = false;
    frame_at(2.7);
    CHECK(((*builder)->state().block.info.x & shaders::FRAME_FIELDS) == 0U);
    (*renderer)->drain();
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
    MESSAGE(fields->updates << " frames; set 1 made " << versions << " times; the sun's buffer "
                            << w.sun()->buffer().size() << " bytes");
    renderer->reset();
}
#endif

namespace {

/// A pass that contributes a provider of set 1's glyph curves, made with the resources it is given (it reads
/// probe.txt), or (`clash`) one of the sun's bits, which the world's sun fills.
class ProviderProbe final : public frame::Pass {
public:
    explicit ProviderProbe(bool clash) noexcept : clash_(clash) {}
    [[nodiscard]] std::string_view name() const noexcept override { return clash_ ? "clash probe" : "provider probe"; }
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
    [[nodiscard]] std::vector<std::shared_ptr<frame::GlobalProvider>>
    providers(const gpu::Device& device, const frame::Resources& resources) const override {
        (void)device;
        if (resources.read) {
            if (auto bytes = resources.read("probe.txt")) {
                read.assign(reinterpret_cast<const char*>(bytes->data()), bytes->size()); // NOLINT
            }
            refused = !resources.read("../probe.txt").has_value() && !resources.read("/probe.txt").has_value();
        }
        made = std::make_shared<CountingGlobals>(
            std::array{clash_ ? frame::Global::sun_bits : frame::Global::glyph_curves});
        return {made};
    }
    mutable std::string read;
    mutable bool refused = false;
    mutable std::shared_ptr<CountingGlobals> made;

private:
    bool clash_;
};

} // namespace

TEST_CASE("a pass's providers come with the world, made with the world's resources; a clash refuses the world") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "resources";
    std::filesystem::create_directories(folder);
    std::ofstream(folder / "probe.txt") << "the fonts' folder";
    {
        std::vector<std::unique_ptr<frame::Pass>> passes;
        auto owned = std::make_unique<ProviderProbe>(false);
        const ProviderProbe* probe = owned.get();
        passes.push_back(std::move(owned));
        auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
        ATLAS_REQUIRE(builder.has_value(), builder.error().text());
        auto world = scene::World::create(*session->device, (*builder)->passes(),
                                          {.workers = 1, .resources = frame::files_under(folder.string())});
        ATLAS_REQUIRE(world.has_value(), world.error().text());
        CHECK(probe->read == "the fonts' folder");
        CHECK(probe->refused); // nothing outside the folder
        REQUIRE(probe->made != nullptr);
        const std::span<frame::GlobalProvider* const> globals = (*world)->globals();
        CHECK(std::ranges::find(globals, probe->made.get()) != globals.end());
        CHECK(globals.size() == 3); // the heaps', the sun's, the pass's
    }
    {
        std::vector<std::unique_ptr<frame::Pass>> passes;
        passes.push_back(std::make_unique<ProviderProbe>(true));
        auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
        ATLAS_REQUIRE(builder.has_value(), builder.error().text());
        auto world = scene::World::create(*session->device, (*builder)->passes(), {.workers = 1});
        REQUIRE_FALSE(world.has_value());
        MESSAGE(world.error().text());
        CHECK(world.error().text().find("clash probe") != std::string::npos);
        CHECK(world.error().text().find("sun_bits") != std::string::npos);
    }
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
}
