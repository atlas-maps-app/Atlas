// A frame whose descriptors do not begin (frame/FrameLayout.hpp's begin_frame: here its ring cannot grow to the frame's
// items, as when the device's memory refuses a larger one) is drawn as the clear alone: nothing of set 0 is written,
// least of all its rank table past the old ring's slice, and no pass records into it. The frame still goes out, and the
// next one, its ring grown, draws.
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "scene/ItemTable.hpp"
#include "standin/View.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <span>
#include <string_view>
#include <vector>

#if !defined(__ANDROID__)
#include "gpu/Offscreen.hpp"
#endif

using namespace atlas;

#if !defined(__ANDROID__)
namespace {

/// Draws nothing; counts the frames it records into.
class Counted final : public frame::Pass {
public:
    std::uint32_t records = 0;

    [[nodiscard]] std::string_view name() const noexcept override { return "counted"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return one_phase; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override {
        (void)context;
        return {};
    }
    void release() noexcept override {}
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)r;
        (void)phase;
        ++records;
    }

private:
    static constexpr std::array<frame::Phase, 1> one_phase{
        frame::Phase{.slot = frame::Slot::objects, .order = 0, .id = 1}};
};

/// A scene of an item table alone.
class Rows final : public frame::FrameScene {
public:
    explicit Rows(const scene::ItemTable& items) noexcept : items_(items) {}
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override {
        (void)camera;
        return nullptr;
    }
    void update(const frame::FrameState& state) override { (void)state; }
    [[nodiscard]] const scene::Cover* cover() const noexcept override { return nullptr; }
    [[nodiscard]] const scene::ItemTable* items() const noexcept override { return &items_; }
    [[nodiscard]] scene::Heaps* heaps() noexcept override { return nullptr; }

private:
    const scene::ItemTable& items_;
};

/// Adds items to `items` until it has `rows`, each on the camera's axis.
void fill(scene::ItemTable& items, std::size_t rows) {
    for (std::size_t i = items.rows(); i < rows; ++i) {
        (void)items.add({.x = 0,
                         .y = 0,
                         .z = 0,
                         .offset = 0,
                         .cut_x = 0,
                         .cut_z = 0,
                         .order = scene::order_of(0, i),
                         .kind = 0,
                         .flags = 0,
                         .slots = {1, 1, 1, 1},
                         .min_depth = 2.0F,
                         .radius = 1.0F,
                         .tile = 1,
                         .owner = scene::owner_key(2, 1000 + i)});
    }
}

/// Every slot's bytes of the ring.
[[nodiscard]] std::vector<std::vector<std::byte>> ring_bytes(const frame::FrameLayout& layout) {
    std::vector<std::vector<std::byte>> out;
    for (std::uint32_t s = 0; s < gpu::Frames::in_flight; ++s) {
        const std::span<const std::byte> b = layout.ring(s);
        out.emplace_back(b.begin(), b.end());
    }
    return out;
}

} // namespace

TEST_CASE("frame: a frame whose ring cannot grow to its items is the clear alone, writing nothing of set 0") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const vk::Extent2D extent{.width = 64, .height = 48};
    auto output = gpu::Offscreen::create(device, extent, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    auto counted = std::make_unique<Counted>();
    Counted& pass = *counted;
    std::vector<std::unique_ptr<frame::Pass>> passes;
    passes.push_back(std::move(counted));
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    scene::ItemTable items;
    fill(items, 1000);
    Rows scene(items);
    (*builder)->set_inputs(
        {.camera = standin::board_view(extent.width, extent.height, 2.0, 0.0, 0.0, 1000.0), .scene = &scene});
    auto renderer = gpu::Renderer::create(device, **output, **builder,
                                          {.config = gpu::FrameConfig::x1, .target = gpu::CompositeTarget::direct});
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    frame::FrameLayout& layout = (*builder)->layout();

    // a frame within the ring draws
    auto first = (*renderer)->draw(0);
    ATLAS_REQUIRE(first.has_value(), first.error().text());
    CHECK(pass.records == 1);
    const std::uint32_t capacity = layout.item_capacity();
    REQUIRE(capacity >= 1000);

    // the ring may not grow, and the frame's items pass it: the frame goes out, the clear alone
    layout.limit_items(capacity);
    fill(items, std::size_t{capacity} + 1000);
    const auto before = ring_bytes(layout);
    auto refused = (*renderer)->draw(0);
    ATLAS_REQUIRE(refused.has_value(), refused.error().text());
    CHECK(pass.records == 1);
    CHECK(layout.item_capacity() == capacity);
    const auto after = ring_bytes(layout);
    for (std::uint32_t s = 0; s < gpu::Frames::in_flight; ++s) {
        INFO("slot " << s);
        CHECK(after[s] == before[s]);
    }

    // the ring may grow again: it does, and the frame draws
    layout.limit_items(std::numeric_limits<std::uint32_t>::max());
    auto grown = (*renderer)->draw(0);
    ATLAS_REQUIRE(grown.has_value(), grown.error().text());
    CHECK(pass.records == 2);
    CHECK(layout.item_capacity() >= capacity + 1000);
    (*renderer)->drain();
    CHECK(session->take_messages().empty());
    renderer->reset();
}
#endif
