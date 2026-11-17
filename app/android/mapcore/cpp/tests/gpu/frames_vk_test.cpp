// The frame loop's waits on the GPU are bounded (gpu/Frames.hpp's wait_ns): a frame whose slot's earlier frame the GPU
// never finishes (a hang the driver does not report as a lost device) fails within the bound, the render thread free,
// and once the GPU goes on the next frame waits for the same slot and draws. The hang is a slot held by the test
// (Frames::hold_next: its fence unsignalled, nothing submitted to signal it), not a frame held on the GPU: lavapipe
// runs a submission in the thread that submits it, where a frame held on a host event held the thread to set it.
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <cstdint>
#include <memory>
#include <string>
#include <tuple>

using namespace atlas;

namespace {

/// Draws nothing.
class Blank final : public gpu::Content {
public:
    [[nodiscard]] gpu::Result<void> prepare(const gpu::MainPass& /*pass*/, const gpu::Output& /*output*/) override {
        return {};
    }
    void release() override {}
    void record(const gpu::FrameContext& /*frame*/) override {}
};

} // namespace

#if !defined(__ANDROID__)
TEST_CASE("frames: a slot the GPU does not finish fails its next frame within the bound, then draws once it does") {
    auto s = test::open_session();
    if (!s) return;
    auto output = gpu::Offscreen::create(*s->device, {.width = 64, .height = 64}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    Blank content;
    auto renderer = gpu::Renderer::create(*s->device, **output, content, s->device->facts().choice);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    gpu::Frames& frames = (*renderer)->frames();
    // a phone GPU's bound, though the device be CI's lavapipe (whose bound is longer)
    frames.limit_slot_wait(2'000'000'000);
    const std::uint64_t bound = frames.slot_wait_ns();
    // whatever the checks find, the held slot is let go and the GPU drained before the renderer goes
    class Release {
    public:
        explicit Release(gpu::Renderer& renderer) noexcept : renderer_(renderer) {}
        Release(const Release&) = delete;
        Release& operator=(const Release&) = delete;
        Release(Release&&) = delete;
        Release& operator=(Release&&) = delete;
        ~Release() {
            std::ignore = renderer_.frames().let_go(); // a lost device has nothing left to wait for
            renderer_.drain();
        }

    private:
        gpu::Renderer& renderer_;
    };
    const Release release(**renderer);

    for (std::uint32_t i = 0; i < gpu::Frames::in_flight; ++i) {
        auto drawn = (*renderer)->draw(0);
        ATLAS_REQUIRE(drawn.has_value(), drawn.error().text());
    }
    // the next frame's slot holds a frame the GPU never finishes: its wait ends at the bound, the frame failing
    ATLAS_REQUIRE(frames.hold_next().has_value());
    const auto waiting = std::chrono::steady_clock::now();
    auto timed_out = (*renderer)->draw(0);
    const auto waited = std::chrono::steady_clock::now() - waiting;
    REQUIRE_FALSE(timed_out.has_value());
    CHECK(timed_out.error().code == vk::Result::eTimeout);
    CHECK(waited >= std::chrono::nanoseconds(bound));
    CHECK(waited < std::chrono::nanoseconds(4 * bound));
    // once the GPU goes on, the same slot is waited for again, and the frames draw on
    ATLAS_REQUIRE(frames.let_go().has_value());
    for (int i = 0; i < 3; ++i) {
        auto drawn = (*renderer)->draw(0);
        ATLAS_REQUIRE(drawn.has_value(), drawn.error().text());
    }
    for (const std::string& m : s->take_messages()) MESSAGE(m);
}
#endif
