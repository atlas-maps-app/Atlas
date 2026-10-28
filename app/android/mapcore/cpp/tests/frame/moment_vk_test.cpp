// A new moment's frame (frame/FrameBuilder.hpp's moment, Pass::ready_for; ENGINE.md gain 5): a frame drawn into an
// output shows a moment the inputs bring once every pass is ready for it, the frames before it showing the moment they
// showed; every pass is asked about it each frame until then, with the frame before's state and the moment's shaders'
// Theme; a moment asked about stays asked about until it is shown, though the inputs move on, and the frame that shows
// it after they have calls the wake; back at the moment shown, nothing is asked about; the first frame and the twin's,
// which draws nothing, take the inputs' moment at once.
#if !defined(__ANDROID__)

#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "standin/View.hpp"
#include "style/Theme.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

using namespace atlas;

namespace {

/// A pass ready for a new moment only once told so: it notes each moment it is asked about, the frame before's number
/// and the wake it is given.
class Waiting final : public frame::Pass {
public:
    struct Asked {
        std::uint64_t last = 0;
        shaders::Theme next{};
    };

    [[nodiscard]] std::string_view name() const noexcept override { return "waiting"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        (void)c;
        return {};
    }
    void release() noexcept override {}
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)r;
        (void)phase;
    }
    [[nodiscard]] bool ready_for(const frame::FrameState& last, const shaders::Theme& next,
                                 const std::function<void()>& wake) override {
        asked.push_back({.last = last.number, .next = next});
        given = wake;
        return ready;
    }

    std::vector<Asked> asked;
    std::function<void()> given;
    bool ready = false;

private:
    static constexpr std::array<frame::Phase, 1> phase_list{{{.slot = frame::Slot::offscreen, .order = 0, .id = 0}}};
};

/// A moment of its own light: the sun's strength `sun_k`.
[[nodiscard]] style::ThemeBlock moment(double sun_k) {
    style::ThemeBlock b{};
    b.version = style::theme_block_version;
    b.flags = style::theme_flag::weather;
    b.sun_k = sun_k;
    return b;
}

[[nodiscard]] bool same(const shaders::Theme& a, const shaders::Theme& b) {
    return std::ranges::equal(std::as_bytes(std::span(&a, 1)), std::as_bytes(std::span(&b, 1)));
}

} // namespace

TEST_CASE("frame: a new moment is shown once every pass is ready for it, the frames before it showing the one shown") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    constexpr std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<Waiting>();
    Waiting& waiting = *owned;
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    int woken = 0;
    (*builder)->set_wake([&woken] { ++woken; });
    auto output = gpu::Offscreen::create(*session->device, {.width = 64, .height = 48}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    auto renderer = gpu::Renderer::create(*session->device, **output, **builder, session->device->facts().choice);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    const style::CameraState camera = standin::board_view(64, 48, 1.0);
    const auto frame_at = [&](const style::ThemeBlock& th) {
        (*builder)->set_inputs({.camera = camera, .theme = th});
        auto report = (*renderer)->draw(0);
        ATLAS_REQUIRE(report.has_value(), report.error().text());
        (*renderer)->drain();
    };
    // what the last frame showed: its theme block and the shaders' Theme of it in its frame block
    const auto showing = [&](const style::ThemeBlock& th) {
        const frame::FrameState& s = (*builder)->state();
        return std::ranges::equal(std::as_bytes(std::span(&s.theme, 1)), std::as_bytes(std::span(&th, 1))) &&
               same(s.block.theme, style::shader_theme(th));
    };
    const style::ThemeBlock a = moment(0.5);
    const style::ThemeBlock b = moment(0.6);
    const style::ThemeBlock c = moment(0.7);

    // the first frame takes the inputs' moment at once, and so does nothing ask about the moment shown
    frame_at(a);
    CHECK(showing(a));
    frame_at(a);
    CHECK(showing(a));
    CHECK(waiting.asked.empty());
    // a new moment: the frames show the one shown while the pass is not ready, asking it each frame, with the frame
    // before and the new moment's shaders' Theme
    frame_at(b);
    CHECK(showing(a));
    REQUIRE(waiting.asked.size() == 1);
    CHECK(waiting.asked[0].last == 2);
    CHECK(same(waiting.asked[0].next, style::shader_theme(b)));
    frame_at(b);
    CHECK(showing(a));
    REQUIRE(waiting.asked.size() == 2);
    CHECK(waiting.asked[1].last == 3);
    // the wake the pass is given is the builder's
    REQUIRE(static_cast<bool>(waiting.given));
    waiting.given();
    CHECK(woken == 1);
    // the inputs move on: the moment asked about stays asked about until it is shown
    frame_at(c);
    CHECK(showing(a));
    REQUIRE(waiting.asked.size() == 3);
    CHECK(same(waiting.asked[2].next, style::shader_theme(b)));
    // ready: shown, and the inputs having moved on, the wake called so that the next frame asks about theirs at rest
    waiting.ready = true;
    frame_at(c);
    CHECK(showing(b));
    CHECK(waiting.asked.size() == 4);
    CHECK(woken == 2);
    frame_at(c);
    CHECK(showing(c));
    REQUIRE(waiting.asked.size() == 5);
    CHECK(same(waiting.asked[4].next, style::shader_theme(c)));
    CHECK(woken == 2);
    // asked about, not ready, and the inputs back at the moment shown: it is not shown, nor asked about again
    waiting.ready = false;
    frame_at(b);
    CHECK(showing(c));
    CHECK(waiting.asked.size() == 6);
    frame_at(c);
    CHECK(showing(c));
    CHECK(waiting.asked.size() == 6);
    // the twin, which has no frames to keep even, takes the inputs' moment at once; a frame at it asks nothing
    (*builder)->set_inputs({.camera = camera, .theme = a});
    twin::Stream out;
    (*builder)->emit(out, 99);
    CHECK(showing(a));
    frame_at(a);
    CHECK(showing(a));
    CHECK(waiting.asked.size() == 6);
    CHECK(woken == 2);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
}

#endif
