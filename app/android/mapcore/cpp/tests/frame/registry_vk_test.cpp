// The pass registry (frame/PassRegistry.hpp) and the frame's order of phases (frame/FrameBuilder.hpp): every registered
// pass is made by make_passes in the registry's order, registers its item kinds as the builder is made (their rows
// fixed from then on), the frame lays the passes' ground slots out in the registry's order before the items' (D6), and
// it records phases by slot, then order, then the registry's order (D5.12).
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <format>
#include <map>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#if !defined(__ANDROID__)
#include "gpu/Offscreen.hpp"
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
    void kinds(frame::Selectors& selectors) override {
        log_.push_back(std::format("{} kind {}", name_, selectors.kind(name_ + " kind")));
    }
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
    auto output = gpu::Offscreen::create(*session->device, {.width = 64, .height = 48}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    gpu::Configuration config = session->device->facts().choice;
    auto renderer = gpu::Renderer::create(*session->device, **output, **builder, config);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    auto report = (*renderer)->draw(0);
    ATLAS_REQUIRE(report.has_value(), report.error().text());
    (*renderer)->drain();
    // The kinds as the builder is made, their rows in the registry's order (the registered buildings' row 0, the trees'
    // 1, the things' 2, the region items' 3 and the doors' 4 first); each frame the selections, the ground slots (a's 3
    // from slot 0, then b's 5 from 3), then the updates, each in the registry's order; then the phases in D5.12's
    // order, the translucent ones by order (b's 1 before a's 5 and b's 5, which tie and keep the registry's order).
    const std::vector<std::string> want{"a kind 5",
                                        "b kind 6",
                                        "a select",
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

TEST_CASE("the frame's twin hangs the passes from its root, the objects' shared group and the ground's") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto builder = frame::FrameBuilder::create(*session->device, *cache, frame::make_passes());
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    for (const bool weather : {true, false}) {
        style::ThemeBlock th{};
        th.version = style::theme_block_version;
        th.flags = weather ? style::theme_flag::weather : 0U;
        (*builder)->set_inputs({.camera = standin::board_view(64, 48, 1.0), .theme = th});
        twin::Stream out;
        (*builder)->emit(out, 1);
        const twin::Decoded d = twin::decode(out.bytes());
        REQUIRE_MESSAGE(d.error.empty(), d.error);
        const twin::Document& doc = d.document;
        const std::int32_t root = out.root();
        REQUIRE(root >= 0);
        REQUIRE(out.objects() >= 0);
        CHECK(doc.groups[static_cast<std::size_t>(root)].node.clip >= 0); // the clip to the frame, vb
        CHECK(doc.clips.front().id == "vb");
        const twin::Group& objects = doc.groups[static_cast<std::size_t>(out.objects())];
        CHECK(objects.parent == root);
        if (weather) {
            REQUIRE(objects.node.filter >= 0);
            CHECK(doc.filters[static_cast<std::size_t>(objects.node.filter)] == "mat");
            REQUIRE(out.ground() != root);
            const twin::Group& ground = doc.groups[static_cast<std::size_t>(out.ground())];
            CHECK(ground.parent == root);
            CHECK(ground.node.filter == objects.node.filter);
        } else {
            CHECK(objects.node.filter == twin::none); // the port's plain <g stroke-linejoin="round">
            CHECK(out.ground() == root);              // and no ground group
        }
    }
    (void)session->take_messages();
}

#if !defined(__ANDROID__)
namespace {

/// A watch double for a frame's passes (gpu/Pipelines.hpp), which announce from several threads at once: every name
/// announced, the most calls open at once over the threads, and every end on a thread with nothing open on it.
class Threads final : public gpu::PipelineWatch {
public:
    std::vector<std::string> names;
    std::size_t most = 0;
    std::size_t ended = 0;
    std::vector<std::string> faults;

    void before(const gpu::PipelineLabel& label) override { begin("pipeline " + gpu::describe(label)); }
    void before_module(std::string_view module) override { begin("module " + std::string(module)); }
    void before_release(const gpu::Release& release) override { begin("release " + gpu::describe(release)); }
    void after() override {
        const std::scoped_lock lock(mutex_);
        std::vector<std::string>& mine = open_[std::this_thread::get_id()];
        if (mine.empty()) {
            faults.emplace_back("an end on a thread with nothing announced");
            return;
        }
        mine.pop_back();
        --now_;
        ++ended;
    }

private:
    void begin(std::string what) {
        const std::scoped_lock lock(mutex_);
        names.push_back(what);
        open_[std::this_thread::get_id()].push_back(std::move(what));
        most = std::max(most, ++now_);
    }

    std::mutex mutex_;
    std::map<std::thread::id, std::vector<std::string>> open_;
    std::size_t now_ = 0;
};

} // namespace

TEST_CASE("the passes prepare on several threads at once: the pipelines and the frame they make one after another") {
    // ENGINE.md gain 20: FrameBuilder::prepare runs every pass's prepare on a thread of its own, so an empty pipeline
    // cache (an alpha's first launch, its shaders new) costs the longest pass, not the sum of them. The registry's
    // passes, each prepared through an empty cache on one thread and then on one a core: the same modules and
    // pipelines, each announced on its own thread and ended there, several under way at once, and the same frame.
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    const gpu::Configuration config = device.facts().choice;
    const vk::Extent2D extent{.width = 540, .height = 1200};
    struct Run {
        std::vector<std::string> names;
        std::size_t most = 0;
        std::uint32_t pipelines = 0;
        std::uint32_t threads = 0;
        double ms = 0;
        double driver_ms = 0;
        std::vector<std::uint32_t> frame;
    };
    const auto run = [&](std::uint32_t threads) {
        Run r;
        auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
        ATLAS_REQUIRE_OR_RETURN(r, cache.has_value(), cache.error().text());
        Threads watch;
        auto builder = frame::FrameBuilder::create(device, *cache, frame::make_passes(), {.threads = threads}, &watch);
        ATLAS_REQUIRE_OR_RETURN(r, builder.has_value(), builder.error().text());
        (*builder)->set_inputs({.camera = standin::board_view(extent.width, extent.height, 1.0)});
        auto output = gpu::Offscreen::create(device, extent, 2);
        ATLAS_REQUIRE_OR_RETURN(r, output.has_value(), output.error().text());
        {
            auto renderer = gpu::Renderer::create(device, **output, **builder, config, &watch);
            ATLAS_REQUIRE_OR_RETURN(r, renderer.has_value(), renderer.error().text());
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE_OR_RETURN(r, report.has_value(), report.error().text());
            (*renderer)->drain();
            auto packed = (*output)->read_packed((*output)->latest());
            ATLAS_REQUIRE_OR_RETURN(r, packed.has_value(), packed.error().text());
            r.frame = std::move(packed->words);
        }
        CHECK(watch.ended == watch.names.size());
        CHECK_MESSAGE(watch.faults.empty(), (watch.faults.empty() ? std::string() : watch.faults.front()));
        r.names = std::move(watch.names);
        std::ranges::sort(r.names);
        r.most = watch.most;
        r.pipelines = (*builder)->pipeline_count();
        r.threads = (*builder)->prepare_threads();
        r.ms = (*builder)->prepare_ms();
        r.driver_ms = (*builder)->pipelines_ms();
        return r;
    };
    const Run one = run(1);
    const Run all = run(0);
    MESSAGE("prepare through an empty pipeline cache: "
            << one.pipelines << " pipelines in " << one.ms << " ms on one thread, " << all.ms << " ms on "
            << all.threads << " (" << one.driver_ms << " and " << all.driver_ms << " ms in the driver)");
    CHECK(one.threads == 1);
    CHECK(one.most == 1); // one after another: never two calls under way at once
    CHECK(all.threads ==
          std::clamp<std::size_t>(std::thread::hardware_concurrency(), 1, frame::registrations().size()));
    if (all.threads > 1) CHECK(all.most > 1);
    CHECK(all.pipelines == one.pipelines);
    CHECK(all.names == one.names);
    CHECK(!all.frame.empty());
    CHECK(all.frame == one.frame);
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
}
#endif
