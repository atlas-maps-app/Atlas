// The frame loop's waits on the GPU are bounded (gpu/Frames.hpp's wait_ns): a frame whose slot's earlier frame the GPU
// never finishes (a hang the driver does not report as a lost device) fails within the bound, the render thread free,
// and once the GPU goes on the next frame waits for the same slot and draws.
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <chrono>
#include <memory>
#include <string>
#include <tuple>

using namespace atlas;

namespace {

/// Draws nothing; while `hold` is set, each frame's commands wait for `event`, which the host sets.
class Held final : public gpu::Content {
public:
    explicit Held(const vk::raii::Event& event) noexcept : event_(event) {}

    std::atomic<bool> hold{false};

    [[nodiscard]] gpu::Result<void> prepare(const gpu::MainPass& /*pass*/, const gpu::Output& /*output*/) override {
        return {};
    }
    void release() override {}
    void record(const gpu::FrameContext& frame) override {
        if (!hold) return;
        frame.cmd.waitEvents({*event_}, vk::PipelineStageFlagBits::eHost, vk::PipelineStageFlagBits::eAllCommands, {},
                             {}, {});
    }

private:
    const vk::raii::Event& event_;
};

} // namespace

#if !defined(__ANDROID__)
TEST_CASE("frames: a slot the GPU does not finish fails its next frame within the bound, then draws once it does") {
    auto s = test::open_session();
    if (!s) return;
    auto output = gpu::Offscreen::create(*s->device, {.width = 64, .height = 64}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    auto event = s->device->handle().createEvent({});
    ATLAS_REQUIRE(event.has_value(), vk::to_string(event.error()));
    Held content(*event);
    auto renderer = gpu::Renderer::create(*s->device, **output, content, s->device->facts().choice);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    // whatever the checks find, the GPU is let go and drained before the event and the renderer go
    class Release {
    public:
        Release(const vk::raii::Event& event, gpu::Renderer& renderer) noexcept : event_(event), renderer_(renderer) {}
        Release(const Release&) = delete;
        Release& operator=(const Release&) = delete;
        Release(Release&&) = delete;
        Release& operator=(Release&&) = delete;
        ~Release() {
            std::ignore = event_.set(); // a lost device has nothing left to wait on it
            renderer_.drain();
        }

    private:
        const vk::raii::Event& event_;
        gpu::Renderer& renderer_;
    };
    const Release release(*event, **renderer);

    auto first = (*renderer)->draw(0);
    ATLAS_REQUIRE(first.has_value(), first.error().text());
    // the second frame's commands wait for the host; the third's, on the first's slot, behind them in the queue
    content.hold = true;
    auto held = (*renderer)->draw(0);
    ATLAS_REQUIRE(held.has_value(), held.error().text());
    content.hold = false;
    auto queued = (*renderer)->draw(0);
    ATLAS_REQUIRE(queued.has_value(), queued.error().text());
    // the next frame's slot is the held frame's: its wait ends at the bound, the frame failing
    const auto waiting = std::chrono::steady_clock::now();
    auto timed_out = (*renderer)->draw(0);
    const auto waited = std::chrono::steady_clock::now() - waiting;
    REQUIRE_FALSE(timed_out.has_value());
    CHECK(timed_out.error().code == vk::Result::eTimeout);
    CHECK(waited >= std::chrono::nanoseconds(gpu::Frames::wait_ns));
    CHECK(waited < std::chrono::nanoseconds(4 * gpu::Frames::wait_ns));
    // let go: the same slot is waited for again, and the frames draw on
    ATLAS_REQUIRE(event->set().has_value());
    for (int i = 0; i < 3; ++i) {
        auto drawn = (*renderer)->draw(0);
        ATLAS_REQUIRE(drawn.has_value(), drawn.error().text());
    }
    // what the validation layer says of a host's event held so (said, not checked: its event tracking is the layer's)
    for (const std::string& m : s->take_messages()) MESSAGE(m);
}
#endif
