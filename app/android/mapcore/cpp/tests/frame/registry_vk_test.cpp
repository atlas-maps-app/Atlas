// The pass registry (frame/PassRegistry.hpp) and the frame's order of phases (frame/FrameBuilder.hpp): every registered
// pass is made by make_passes in the registry's order, the frame lays the passes' ground slots out in that order before
// the items' (D6), and it records phases by slot, then order, then the registry's order (D5.12).
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <format>
#include <string>
#include <vector>

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"
#endif

using namespace atlas;

TEST_CASE("the registry makes every registered pass in its order, the composite among them") {
    const auto r = frame::registrations();
    REQUIRE(!r.empty());
    CHECK(std::ranges::any_of(r, [](const frame::Registration& x) { return x.name == "composite"; }));
    const auto passes = frame::make_passes();
    REQUIRE(passes.size() == r.size());
    for (std::size_t i = 0; i < r.size(); ++i) CHECK(passes[i]->name() == r[i].name);
    const std::array<std::string_view, 1> only{"composite"};
    const auto some = frame::make_passes(only);
    REQUIRE(some.size() == 1);
    CHECK(some[0]->name() == "composite");
    // Every name is unique, and the composite has the composite slot alone.
    std::vector<std::string_view> names;
    for (const frame::Registration& x : r) names.push_back(x.name);
    std::ranges::sort(names);
    CHECK(std::ranges::adjacent_find(names) == names.end());
    for (const auto& p : passes) {
        for (const frame::Phase& ph : p->phases()) {
            if (ph.slot == frame::Slot::composite) CHECK(p->name() == "composite");
        }
    }
    CHECK(frame::name_of(frame::Slot::translucent) == "translucent");
}

namespace {

/// A pass that only notes, in a shared log, each phase the frame records, and takes `ground` ground slots.
class Noting final : public frame::Pass {
public:
    Noting(std::string name, std::vector<frame::Phase> phases, std::vector<std::string>& log, std::uint32_t ground)
        : name_(std::move(name)), phases_(std::move(phases)), log_(log), ground_(ground) {}
    [[nodiscard]] std::string_view name() const noexcept override { return name_; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phases_; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        (void)c;
        return {};
    }
    void release() noexcept override {}
    void select(const frame::FrameState& s, frame::Selectors& selectors) override {
        (void)s;
        (void)selectors;
        log_.push_back(name_ + " select");
    }
    [[nodiscard]] std::uint32_t ground_slots(const frame::FrameState& s) const override {
        (void)s;
        log_.push_back(name_ + " ground");
        return ground_;
    }
    void update(const frame::FrameState& s) override {
        log_.push_back(std::format("{} update from ground slot {} of {}", name_, first_ground_slot(), s.ground_slots));
    }
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)r;
        log_.push_back(std::format("{} {}", name_, phase));
    }

private:
    std::string name_;
    std::vector<frame::Phase> phases_;
    std::vector<std::string>& log_;
    std::uint32_t ground_;
};

} // namespace

#if !defined(__ANDROID__)
TEST_CASE("the frame records its passes' phases by slot, then order, then the registry's order") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    std::vector<std::string> log;
    using S = frame::Slot;
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    passes.push_back(std::make_unique<Noting>("a",
                                              std::vector<frame::Phase>{{.slot = S::translucent, .order = 5, .id = 1},
                                                                        {.slot = S::objects, .order = 0, .id = 2},
                                                                        {.slot = S::after, .order = 0, .id = 3}},
                                              log, 3));
    passes.push_back(std::make_unique<Noting>("b",
                                              std::vector<frame::Phase>{{.slot = S::translucent, .order = 1, .id = 4},
                                                                        {.slot = S::ground, .order = 0, .id = 5},
                                                                        {.slot = S::offscreen, .order = 0, .id = 6},
                                                                        {.slot = S::labels, .order = 0, .id = 7},
                                                                        {.slot = S::translucent, .order = 5, .id = 8}},
                                              log, 5));
    auto builder = frame::FrameBuilder::create(*session->device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    (*builder)->set_inputs({.camera = standin::board_view(64, 48, 1.0)});
    auto output = platform::Offscreen::create(*session->device, {.width = 64, .height = 48}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    gpu::Configuration config = session->device->facts().choice;
    auto renderer = gpu::Renderer::create(*session->device, **output, **builder, config);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    auto report = (*renderer)->draw(0);
    ATLAS_REQUIRE(report.has_value(), report.error().text());
    (*renderer)->drain();
    // The selections, the ground slots (a's 3 from slot 0, then b's 5 from 3), then the updates, each in the
    // registry's order; then the phases in D5.12's order, the translucent ones by order (b's 1 before a's 5 and b's 5,
    // which tie and keep the registry's order).
    const std::vector<std::string> want{"a select",
                                        "b select",
                                        "a ground",
                                        "b ground",
                                        "a update from ground slot 0 of 8",
                                        "b update from ground slot 3 of 8",
                                        "b 6",
                                        "a 2",
                                        "b 5",
                                        "b 4",
                                        "a 1",
                                        "b 8",
                                        "b 7",
                                        "a 3"};
    CHECK(log == want);
    // Without items the frame's painter slots are the ground's alone.
    CHECK((*builder)->state().ground_slots == 8);
    CHECK((*builder)->state().block.info.y == 8);
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
    renderer->reset();
}
#endif
