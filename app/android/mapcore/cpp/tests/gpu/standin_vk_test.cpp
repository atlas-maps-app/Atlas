// The main pass and VK bench's stand-in on the host's Vulkan (plan-vector K1's host acceptance): every configuration,
// form, route and composite target the device has clears and draws under the validation layer with synchronization
// validation silent; the stand-in's S1 frame reads back identically across two runs and identically through both frame
// routes; its steady frame stays within half a second; the pipeline cache is written, read back as a hit, and refused
// for other shaders or a damaged file (pipeline creation with and without it is reported: lavapipe's cache is empty).
// The frames go to the host build's offscreen images (platform/host), so these tests are the hosts' only: the Android
// build has no such output, and on the emulator and the phone VK bench draws the stand-in into the window.
#if !defined(__ANDROID__)

#include "base/build_info.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Passes.hpp"
#include "gpu/PipelineCache.hpp"
#include "platform/host/Offscreen.hpp"
#include "standin/StandIn.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <chrono>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <map>
#include <memory>
#include <string>
#include <string_view>
#include <unistd.h>
#include <vector>

using namespace atlas;

namespace {

/// A content that only clears: the main pass's begin, (next,) end.
class ClearOnly final : public gpu::Content {
public:
    gpu::Result<void> prepare(const gpu::MainPass& /*pass*/, const gpu::Output& /*output*/) override { return {}; }
    void record(const gpu::FrameContext& f) override {
        f.frames.mark(f.slot, gpu::Mark::offscreen);
        f.pass.begin(f.cmd, f.sync, f.image, {0.25F, 0.5F, 0.75F, 1.0F});
        if (f.pass.composite()) f.pass.next(f.cmd, f.sync);
        f.pass.end(f.cmd, f.sync, f.image);
        f.frames.mark(f.slot, gpu::Mark::main);
        f.frames.mark(f.slot, gpu::Mark::after);
    }
};

/// Every way of drawing the frame the device has (the bench's matrix): per configuration, both forms through each
/// route; the composite's target where it is a choice (the samples resolved, or the 1x output written straight from
/// them in the render-pass route), the 1x output itself at one sample.
std::vector<gpu::Configuration> configurations(const gpu::Facts& facts) {
    std::vector<gpu::Configuration> out;
    const bool dynamic = facts.features.dynamic_rendering && facts.features.local_read;
    for (const gpu::FrameConfig c :
         {gpu::FrameConfig::a, gpu::FrameConfig::b, gpu::FrameConfig::c, gpu::FrameConfig::x1}) {
        if (!facts.supports(c)) continue;
        const bool one = gpu::Configuration{.config = c}.samples() == vk::SampleCountFlagBits::e1;
        for (const gpu::Route route : {gpu::Route::renderpass, gpu::Route::dynamic}) {
            if (route == gpu::Route::dynamic && !dynamic) continue;
            out.push_back({.config = c,
                           .route = route,
                           .form = gpu::FrameForm::onepass,
                           .target = gpu::CompositeTarget::resolve});
            if (one) {
                out.push_back({.config = c,
                               .route = route,
                               .form = gpu::FrameForm::composite,
                               .target = gpu::CompositeTarget::direct});
                continue;
            }
            out.push_back({.config = c,
                           .route = route,
                           .form = gpu::FrameForm::composite,
                           .target = gpu::CompositeTarget::resolve});
            if (route == gpu::Route::renderpass) {
                out.push_back({.config = c,
                               .route = route,
                               .form = gpu::FrameForm::composite,
                               .target = gpu::CompositeTarget::direct});
            }
        }
    }
    return out;
}

std::string describe(const gpu::Configuration& c) {
    return std::format("{} {} {} {}", gpu::name_of(c.config), gpu::name_of(c.route), gpu::name_of(c.form),
                       gpu::name_of(c.target));
}

/// The stand-in's frame of `camera` at `extent` in `config`, after `frames` frames (the last one read back).
struct Drawn {
    platform::Pixels pixels;
    gpu::GpuTimes gpu;
    double frame_ms = 0; // the last frame, submit to finish, as the CPU saw it
    double pipelines_ms = 0;
    std::uint32_t pipelines = 0;
    standin::Counters counters;
};

gpu::Result<Drawn> draw_standin(test::Session& s, const gpu::Configuration& config, vk::Extent2D extent,
                                const style::CameraState& camera, int frames, const gpu::PipelineCache& cache,
                                std::shared_ptr<const standin::City> city) {
    auto output = platform::Offscreen::create(*s.device, extent, 2);
    REQUIRE_MESSAGE(output.has_value(), output.error().text());
    REQUIRE((*output)->fill({1.0F, 0.0F, 1.0F, 1.0F}).has_value());
    auto content = standin::StandIn::create(*s.device, cache, std::move(city));
    REQUIRE_MESSAGE(content.has_value(), content.error().text());
    (*content)->set_camera(camera);
    auto renderer = gpu::Renderer::create(*s.device, **output, **content, config);
    if (!renderer) {
        FAIL_CHECK(describe(config) << ": " << renderer.error().text());
        return std::unexpected(renderer.error());
    }
    Drawn d;
    d.pipelines_ms = (*content)->pipelines_ms();
    d.pipelines = (*content)->pipeline_count();
    for (int i = 0; i < frames; ++i) {
        (*content)->set_time(0.5);
        const auto started = std::chrono::steady_clock::now();
        auto report = (*renderer)->draw(0);
        REQUIRE_MESSAGE(report.has_value(), report.error().text());
        (*renderer)->drain();
        d.frame_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    }
    d.gpu = (*renderer)->frames().last_times();
    d.counters = (*content)->counters();
    auto pixels = (*output)->read((*output)->latest());
    REQUIRE_MESSAGE(pixels.has_value(), pixels.error().text());
    d.pixels = std::move(*pixels);
    renderer->reset();
    return d;
}

std::filesystem::path scratch(const char* name) {
    const char* dir = std::getenv("ATLAS_TEST_OUT"); // NOLINT(concurrency-mt-unsafe)
    std::filesystem::path base =
        dir != nullptr ? std::filesystem::path(dir) : std::filesystem::temp_directory_path() / "atlas-k1";
    std::error_code ec;
    std::filesystem::create_directories(base, ec);
    return base / name;
}

} // namespace

TEST_CASE("the main pass clears in every configuration, form, route and target the device has") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    REQUIRE(cache.has_value());
    const vk::Extent2D extent{.width = 96, .height = 64};
    auto output = platform::Offscreen::create(*session->device, extent, 2);
    REQUIRE(output.has_value());
    const auto all = configurations(session->device->facts());
    CHECK(all.size() >= 6);
    for (const gpu::Configuration& config : all) {
        // A sentinel first: a configuration that never writes its output would read back as it.
        REQUIRE((*output)->fill({1.0F, 0.0F, 1.0F, 1.0F}).has_value());
        ClearOnly content;
        auto renderer = gpu::Renderer::create(*session->device, **output, content, config);
        REQUIRE_MESSAGE(renderer.has_value(), describe(config) << ": " << renderer.error().text());
        for (int i = 0; i < 3; ++i) {
            auto report = (*renderer)->draw(0);
            REQUIRE_MESSAGE(report.has_value(), describe(config));
        }
        (*renderer)->drain();
        auto pixels = (*output)->read((*output)->latest());
        REQUIRE(pixels.has_value());
        // The clear reaches the output through the resolve, or as the output itself; where only the composite writes
        // the output (the direct target, one sample), this content draws no composite and the output is undefined.
        const bool reaches = config.form == gpu::FrameForm::onepass || config.target == gpu::CompositeTarget::resolve;
        if (reaches) {
            // 0.25, 0.5, 0.75 in UNORM: 64, 128, 191 (round to nearest of 63.75, 127.5, 191.25).
            bool all_clear = true;
            for (std::size_t i = 0; i < pixels->rgba.size(); i += 4) {
                all_clear = all_clear && pixels->rgba[i] == 64 && pixels->rgba[i + 1] == 128 &&
                            pixels->rgba[i + 2] == 191 && pixels->rgba[i + 3] == 255;
            }
            CHECK_MESSAGE(all_clear, describe(config) << " did not clear to (64, 128, 191, 255): first pixel "
                                                      << int{pixels->rgba[0]} << ", " << int{pixels->rgba[1]} << ", "
                                                      << int{pixels->rgba[2]});
        }
        const auto messages = session->take_messages();
        CHECK_MESSAGE(messages.empty(), describe(config)
                                            << ": " << (messages.empty() ? std::string() : messages.front()));
    }
}

TEST_CASE("the stand-in's S1 frame is the same across runs and through both frame routes") {
    auto city = std::make_shared<const standin::City>(standin::City::generate());
    CHECK(city->box_triangles() == 501'760);
    // S1: 1080 × 2400 at the density 2.625 (412 × 915 dp), the Main board's view.
    const vk::Extent2D s1{.width = 1080, .height = 2400};
    const style::CameraState board = standin::board_view(s1.width, s1.height, 2.625);
    // Frames that must be identical: the same configuration, form and target, across both routes and both runs.
    std::map<std::string, std::vector<std::pair<std::string, platform::Pixels>>> buckets;
    for (int run = 0; run < 2; ++run) {
        auto session = test::open_session();
        if (!session) return;
        auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
        REQUIRE(cache.has_value());
        const gpu::Facts& facts = session->device->facts();
        for (const gpu::Configuration& config : configurations(facts)) {
            if (config.config == gpu::FrameConfig::a || config.config == gpu::FrameConfig::b)
                continue; // not on lavapipe
            if (run == 1 && config.route == gpu::Route::dynamic && config.form == gpu::FrameForm::onepass)
                continue; // enough
            auto drawn = draw_standin(*session, config, s1, board, 2, *cache, city);
            if (!drawn) continue;
            const auto messages = session->take_messages();
            CHECK_MESSAGE(messages.empty(), describe(config)
                                                << ": " << (messages.empty() ? std::string() : messages.front()));
            const std::string name = std::format("run {} {}", run, describe(config));
            MESSAGE(name << ": hash " << std::format("{:016x}", platform::hash_of(drawn->pixels)) << ", GPU "
                         << drawn->gpu.total_ns / 1e6 << " ms (offscreen " << drawn->gpu.offscreen_ns / 1e6 << ", main "
                         << drawn->gpu.main_ns / 1e6 << "), fragments " << drawn->gpu.fragments << " ("
                         << static_cast<double>(drawn->gpu.fragments) / (s1.width * s1.height) << " FSE), draws "
                         << drawn->counters.draws << ", triangles " << drawn->counters.triangles);
            if (run == 0) {
                (void)platform::write_ppm(drawn->pixels,
                                          scratch(std::format("standin-{}-{}-{}-{}.ppm", gpu::name_of(config.config),
                                                              gpu::name_of(config.route), gpu::name_of(config.form),
                                                              gpu::name_of(config.target))
                                                      .c_str())
                                              .string());
            }
            buckets[std::format("{} {} {}", gpu::name_of(config.config), gpu::name_of(config.form),
                                gpu::name_of(config.target))]
                .emplace_back(name, std::move(drawn->pixels));
        }
    }
    CHECK(buckets.size() >= 5);
    for (const auto& [key, frames] : buckets) {
        CHECK_MESSAGE(frames.size() >= 2, key << " was drawn once only");
        for (std::size_t i = 1; i < frames.size(); ++i) {
            const platform::Difference d = platform::compare(frames[0].second, frames[i].second, 0);
            CHECK_MESSAGE(d.share == 0.0, frames[i].first << " differs from " << frames[0].first << " on "
                                                          << d.share * 100 << " % of pixels, by up to " << d.largest);
        }
        // Nothing still shows the sentinel (magenta): every configuration wrote its whole output.
        const auto& p = frames[0].second.rgba;
        std::size_t sentinel = 0;
        for (std::size_t i = 0; i < p.size(); i += 4)
            sentinel += (p[i] == 255 && p[i + 1] == 0 && p[i + 2] == 255) ? 1 : 0;
        CHECK_MESSAGE(sentinel == 0, key << " left " << sentinel << " pixels unwritten");
    }
    // The composite against one subpass: they differ where the coverage rule thins the silhouettes.
    const auto& composite = buckets.at("C composite direct").front().second;
    const platform::Difference forms = platform::compare(composite, buckets.at("C onepass resolve").front().second, 2);
    MESSAGE("composite against one subpass: "
            << forms.share * 100 << " % of pixels differ by more than 2 levels, by up to " << forms.largest);
    CHECK(forms.share > 0.0);
    // The composite written into the samples and resolved, against the 1x image written straight from them: equal in
    // exact arithmetic; lavapipe runs the in-place composite so that later samples of a pixel see earlier samples'
    // results (a feedback loop's race), which moves some pixels where the effects change the colour.
    const platform::Difference targets =
        platform::compare(composite, buckets.at("C composite resolve").front().second, 0);
    MESSAGE("composite resolved against written straight: " << targets.share * 100 << " % of pixels differ, by up to "
                                                            << targets.largest);
}

TEST_CASE("the stand-in's steady S1 frame takes at most half a second, and its pipeline cache is kept and checked") {
    // No validation layer here: the frame's own cost, as the bench measures it on the phone.
    auto instance = gpu::Instance::open({.validation = false});
    if (!instance) {
        if (test::vulkan_required()) FAIL(instance.error().text());
        return;
    }
    test::Session session;
    session.instance = std::move(*instance);
    auto device = gpu::Device::open(*session.instance, {});
    REQUIRE(device.has_value());
    session.device = std::move(*device);
    auto city = std::make_shared<const standin::City>(standin::City::generate());
    const vk::Extent2D s1{.width = 1080, .height = 2400};
    const style::CameraState board = standin::board_view(s1.width, s1.height, 2.625);
    const std::filesystem::path file = scratch(std::format("pipelines-{}.bin", ::getpid()).c_str());
    gpu::PipelineCache::remove(file);
    const gpu::Configuration config = session.device->facts().choice;

    auto cold = gpu::PipelineCache::open(session.device->handle(), session.device->facts(), file, 7, true);
    REQUIRE(cold.has_value());
    CHECK_FALSE(cold->hit());
    auto first = draw_standin(session, config, s1, board, 4, *cold, city);
    REQUIRE(first.has_value());
    auto saved = cold->save();
    REQUIRE_MESSAGE(saved.has_value(), saved.error().text());
    CHECK(*saved >= sizeof(gpu::CacheHeader));
    cold = gpu::fail("closed"); // the cache goes before its device

    // A second device, as the next start opens it.
    session.device.reset();
    auto device2 = gpu::Device::open(*session.instance, {});
    REQUIRE(device2.has_value());
    session.device = std::move(*device2);
    auto warm = gpu::PipelineCache::open(session.device->handle(), session.device->facts(), file, 7, true);
    REQUIRE(warm.has_value());
    CHECK(warm->hit());
    CHECK(warm->status() == "hit");
    auto second = draw_standin(session, config, s1, board, 4, *warm, city);
    REQUIRE(second.has_value());
    // Another shader hash discards the file.
    auto other = gpu::PipelineCache::open(session.device->handle(), session.device->facts(), file, 8, true);
    REQUIRE(other.has_value());
    CHECK_FALSE(other->hit());
    CHECK(other->status() == "miss: other shaders");
    // A damaged file too.
    {
        std::FILE* f = std::fopen(file.c_str(), "r+b");
        REQUIRE(f != nullptr);
        (void)std::fseek(f, static_cast<long>(offsetof(gpu::CacheHeader, checksum)), SEEK_SET);
        (void)std::fputc(0x5A, f);
        (void)std::fclose(f);
    }
    auto damaged = gpu::PipelineCache::open(session.device->handle(), session.device->facts(), file, 7, true);
    REQUIRE(damaged.has_value());
    CHECK(damaged->status() == "miss: damaged");

    MESSAGE("pipelines: " << first->pipelines << " made in " << first->pipelines_ms << " ms without the cache, "
                          << second->pipelines_ms << " ms from it (" << *saved << " bytes on disk)");
    MESSAGE("steady S1 frame (" << describe(config) << "): " << second->frame_ms << " ms on the CPU's clock, GPU "
                                << second->gpu.total_ns / 1e6 << " ms, ranks " << second->counters.ranks_ms
                                << " ms, recording " << second->counters.record_ms << " ms");
    // K1's host acceptance: at most 0.5 s on the x86_64 runner (N8 measured 0.11 s for a heavier frame on this Mac),
    // with the shipped flags. Under ASan and TSan lavapipe's own copies and locks go through the sanitizers'
    // interceptors (about twice the time), so those builds report the frame and leave the budget to host-release,
    // unless ATLAS_FRAME_BUDGET_MS names one (also for a machine busy with other work).
    const std::string_view build = build_info();
    const bool sanitized =
        build.find(" asan") != std::string_view::npos || build.find(" tsan") != std::string_view::npos;
    double budget = 500.0;
    bool budget_given = false;
    // NOLINTNEXTLINE(concurrency-mt-unsafe): the test's own thread, before any other
    if (const char* b = std::getenv("ATLAS_FRAME_BUDGET_MS"); b != nullptr) {
        budget = std::strtod(b, nullptr);
        budget_given = true;
    }
    if (sanitized && !budget_given) {
        MESSAGE("a sanitized build: the frame's time is reported; host-release holds it to " << budget << " ms");
    } else {
        CHECK(second->frame_ms <= budget);
    }
    CHECK(platform::compare(first->pixels, second->pixels, 0).share == 0.0);
    other = gpu::fail("closed");
    damaged = gpu::fail("closed");
    warm = gpu::fail("closed");
    gpu::PipelineCache::remove(file);
}

#endif
