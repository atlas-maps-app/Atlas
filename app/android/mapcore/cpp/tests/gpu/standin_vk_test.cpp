// The main pass and VK bench's stand-in on the host's Vulkan (plan-vector K1's host acceptance): every configuration,
// form, route and composite target the device has clears and draws under the validation layer with synchronization
// validation silent, at 8 bits a channel and at 10 (the frame's colour, gpu/Facts.hpp: lavapipe draws it); the
// stand-in's S1 frame reads back identically across two runs and identically through both frame routes at each depth,
// and its 10-bit frame, at 8 bits, within a level of its 8-bit one; the frosted pills draw over both; its steady frame
// stays within half a second; the pipeline cache is written, read back as a hit, and refused for other shaders or a
// damaged file (pipeline creation with and without it is reported: lavapipe's cache is empty). The frames go to the
// host build's offscreen images (platform/host), so these tests are the hosts' only: the Android build has no such
// output, and on the emulator and the phone VK bench draws the stand-in into the window.
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

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <map>
#include <memory>
#include <string>
#include <string_view>
#include <unistd.h>
#include <utility>
#include <vector>

using namespace atlas;

namespace {

/// A content that only clears: the main pass's begin, (next,) end.
class ClearOnly final : public gpu::Content {
public:
    gpu::Result<void> prepare(const gpu::MainPass& /*pass*/, const gpu::Output& /*output*/) override { return {}; }
    void release() override {}
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

/// The depths a test draws in: 8 bits, and 10 where the device draws the frame in them (lavapipe does).
std::vector<gpu::Depth> depths(const gpu::Device& device) {
    if (gpu::colour_offscreen(device.facts(), gpu::Depth::deepest).ten_bit())
        return {gpu::Depth::eight, gpu::Depth::deepest};
    MESSAGE("no 10-bit frames: " << gpu::colour_offscreen(device.facts(), gpu::Depth::deepest).reason);
    return {gpu::Depth::eight};
}

/// The frame's colour at `depth` on `device`, as its name ("rgba8", "rgb10a2").
std::string colour_at(const gpu::Device& device, gpu::Depth depth) {
    return gpu::colour_name(gpu::colour_offscreen(device.facts(), depth).format);
}

/// The stand-in's frame of `camera` at `extent` in `config`, after `frames` frames (the last one read back).
struct Drawn {
    platform::Pixels pixels; // at 8 bits a channel (a 10-bit frame converted exactly)
    platform::Packed packed; // as the image holds it
    gpu::GpuTimes gpu;
    double frame_ms = 0; // the last frame, submit to finish, as the CPU saw it
    double pipelines_ms = 0;
    std::uint32_t pipelines = 0;
    standin::Counters counters;
};

/// Each step that fails fails the test case and returns its error, so the caller can go on with the next frame.
gpu::Result<Drawn> draw_standin(test::Session& s, const gpu::Configuration& config, vk::Extent2D extent,
                                const style::CameraState& camera, int frames, const gpu::PipelineCache& cache,
                                std::shared_ptr<const standin::City> city, gpu::Depth depth = gpu::Depth::eight) {
    auto output = platform::Offscreen::create(*s.device, extent, 2, depth);
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(output.error()), output.has_value(), output.error().text());
    auto filled = (*output)->fill({1.0F, 0.0F, 1.0F, 1.0F});
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(filled.error()), filled.has_value(), filled.error().text());
    auto content = standin::StandIn::create(*s.device, cache, std::move(city));
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(content.error()), content.has_value(), content.error().text());
    (*content)->set_camera(camera);
    auto renderer = gpu::Renderer::create(*s.device, **output, **content, config);
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(renderer.error()), renderer.has_value(),
                            describe(config) << ": " << renderer.error().text());
    Drawn d;
    d.pipelines_ms = (*content)->pipelines_ms();
    d.pipelines = (*content)->pipeline_count();
    for (int i = 0; i < frames; ++i) {
        (*content)->set_time(0.5);
        const auto started = std::chrono::steady_clock::now();
        auto report = (*renderer)->draw(0);
        ATLAS_REQUIRE_OR_RETURN(std::unexpected(report.error()), report.has_value(),
                                describe(config) << ": " << report.error().text());
        (*renderer)->drain();
        d.frame_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    }
    d.gpu = (*renderer)->frames().last_times();
    d.counters = (*content)->counters();
    auto packed = (*output)->read_packed((*output)->latest());
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(packed.error()), packed.has_value(), packed.error().text());
    d.packed = std::move(*packed);
    d.pixels = platform::to_rgba8(d.packed);
    ATLAS_REQUIRE_OR_RETURN(gpu::fail("no pixels"), !d.pixels.rgba.empty(),
                            "no 8-bit pixels of " << vk::to_string(d.packed.format));
    renderer->reset();
    return d;
}

/// The stand-in's frame drawn as draw_standin draws it, at 16 bits a channel (R16G16B16A16_UNORM: output and
/// attachments): the frame's colour as nearly exact as its own arithmetic, against which the 8- and 10-bit frames are
/// held.
gpu::Result<Drawn> draw_reference(test::Session& s, const gpu::Configuration& config, vk::Extent2D extent,
                                  const style::CameraState& camera, const gpu::PipelineCache& cache,
                                  std::shared_ptr<const standin::City> city) {
    auto output = platform::Offscreen::create(*s.device, extent, 2, vk::Format::eR16G16B16A16Unorm);
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(output.error()), output.has_value(), output.error().text());
    auto content = standin::StandIn::create(*s.device, cache, std::move(city));
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(content.error()), content.has_value(), content.error().text());
    (*content)->set_camera(camera);
    auto renderer = gpu::Renderer::create(*s.device, **output, **content, config);
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(renderer.error()), renderer.has_value(),
                            describe(config) << " at 16 bits: " << renderer.error().text());
    for (int i = 0; i < 2; ++i) {
        (*content)->set_time(0.5);
        auto report = (*renderer)->draw(0);
        ATLAS_REQUIRE_OR_RETURN(std::unexpected(report.error()), report.has_value(), report.error().text());
        (*renderer)->drain();
    }
    Drawn d;
    auto packed = (*output)->read_packed((*output)->latest());
    ATLAS_REQUIRE_OR_RETURN(std::unexpected(packed.error()), packed.has_value(), packed.error().text());
    d.packed = std::move(*packed);
    d.pixels = platform::to_rgba8(d.packed);
    const auto messages = s.take_messages();
    CHECK_MESSAGE(messages.empty(), describe(config)
                                        << " at 16 bits: " << (messages.empty() ? std::string() : messages.front()));
    renderer->reset();
    return d;
}

/// The two frosted pills of the home's chrome (CameraWriter's keep-out shapes, as VK bench writes them).
style::CameraState with_pills(style::CameraState c) {
    const auto w = static_cast<float>(c.width_dp);
    const auto h = static_cast<float>(c.height_dp);
    c.keep_out_count = 2;
    c.keep_out[0] = style::KeepOut{.left = 16.0F,
                                   .top = 40.0F,
                                   .right = w - 16.0F,
                                   .bottom = 92.0F,
                                   .radius = 26.0F,
                                   .blur16 = 20 * 16,
                                   .saturation = 180,
                                   .flags = 1};
    c.keep_out[1] = style::KeepOut{.left = 16.0F,
                                   .top = h - 100.0F,
                                   .right = w - 16.0F,
                                   .bottom = h - 48.0F,
                                   .radius = 26.0F,
                                   .blur16 = 20 * 16,
                                   .saturation = 180,
                                   .flags = 1};
    return c;
}

/// How a 10-bit frame at 8 bits compares with the 8-bit frame of the same configuration.
struct Likeness {
    platform::Difference any;   // pixels whose colour differs at all, and the largest difference
    platform::Difference over1; // pixels whose colour differs by more than a level
    platform::Difference alpha; // pixels whose alpha differs at all (rgb10a2 keeps 2 bits of it)
    double between = 0;         // the 10-bit frame's colour channels between two 8-bit levels
};

Likeness likeness(const Drawn& eight, const Drawn& ten) {
    Likeness l{.any = platform::compare(eight.pixels, ten.pixels, 0, false),
               .over1 = platform::compare(eight.pixels, ten.pixels, 1, false),
               .alpha = {},
               .between = platform::share_between_levels(ten.packed)};
    platform::Pixels a = eight.pixels;
    platform::Pixels b = ten.pixels;
    for (std::size_t i = 0; i < a.rgba.size(); i += 4) {
        a.rgba[i] = a.rgba[i + 1] = a.rgba[i + 2] = 0;
        b.rgba[i] = b.rgba[i + 1] = b.rgba[i + 2] = 0;
    }
    l.alpha = platform::compare(a, b, 0, true);
    return l;
}

std::string text_of(const Likeness& l) {
    return std::format(
        "{:.4f} % of pixels differ in colour (by up to {}), {:.4f} % by more than a level; alpha differs "
        "on {:.4f} % (by up to {}); {:.2f} % of the 10-bit channels lie between 8-bit levels",
        l.any.share * 100, l.any.largest, l.over1.share * 100, l.alpha.share * 100, l.alpha.largest, l.between * 100);
}

/// Channel `c` (0 R, 1 G, 2 B) of pixel `i` in 8-bit levels, unrounded: an 8-bit frame's own level, a 10- or 16-bit
/// frame's value at that scale.
double level_of(const platform::Packed& p, std::size_t i, std::size_t c) {
    switch (p.format) {
    case vk::Format::eR8G8B8A8Unorm: return static_cast<double>((p.words[i] >> (8 * c)) & 0xFFU);
    case vk::Format::eA2B10G10R10UnormPack32:
        return static_cast<double>((p.words[i] >> (10 * c)) & 0x3FFU) * 255.0 / 1023.0;
    case vk::Format::eA2R10G10B10UnormPack32:
        return static_cast<double>((p.words[i] >> (10 * (2 - c))) & 0x3FFU) * 255.0 / 1023.0;
    case vk::Format::eR16G16B16A16Unorm: {
        const std::uint32_t w = p.words[2 * i + c / 2];
        return static_cast<double>(c % 2 == 0 ? (w & 0xFFFFU) : (w >> 16)) * 255.0 / 65535.0;
    }
    default: return -1.0;
    }
}

/// A frame against the 16-bit frame of its configuration: the largest difference of a colour channel, unrounded, in
/// 8-bit levels.
double worst_against(const Drawn& frame, const Drawn& reference) {
    double worst = 0.0;
    for (std::size_t i = 0; i < frame.pixels.rgba.size() / 4; ++i) {
        for (std::size_t c = 0; c < 3; ++c)
            worst = std::max(worst, std::abs(level_of(frame.packed, i, c) - level_of(reference.packed, i, c)));
    }
    return worst;
}

/// Of the colour channels where the 10-bit frame at 8 bits is more than a level from the 8-bit frame, how many have
/// the 10-bit frame (unrounded) nearer the 16-bit frame than the 8-bit one; and how many such channels there are.
std::pair<std::size_t, std::size_t> nearer_where_apart(const Drawn& eight, const Drawn& ten, const Drawn& reference) {
    std::size_t nearer = 0;
    std::size_t apart = 0;
    for (std::size_t i = 0; i < eight.pixels.rgba.size() / 4; ++i) {
        for (std::size_t c = 0; c < 3; ++c) {
            if (std::abs(int{eight.pixels.rgba[4 * i + c]} - int{ten.pixels.rgba[4 * i + c]}) <= 1) continue;
            ++apart;
            const double r = level_of(reference.packed, i, c);
            nearer += std::abs(level_of(ten.packed, i, c) - r) < std::abs(level_of(eight.packed, i, c) - r) ? 1 : 0;
        }
    }
    return {nearer, apart};
}

/// A file of the stand-in's tests: in ATLAS_TEST_OUT where it is set, else in the build folder's scratch (its frames
/// as PPMs stay there to look at, overwritten by the next run).
std::filesystem::path scratch(const char* name) {
    const char* dir = std::getenv("ATLAS_TEST_OUT"); // NOLINT(concurrency-mt-unsafe)
    std::filesystem::path base =
        dir != nullptr ? std::filesystem::path(dir) : std::filesystem::path(ATLAS_TEST_SCRATCH) / "standin";
    std::error_code ec;
    std::filesystem::create_directories(base, ec);
    return base / name;
}

} // namespace

TEST_CASE("the main pass clears in every configuration, form, route and target the device has, at 8 bits and at 10") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const vk::Extent2D extent{.width = 96, .height = 64};
    const auto all = configurations(session->device->facts());
    CHECK(all.size() >= 6);
    // lavapipe draws the frame in 10 bits, so the hosts test that path (the phone's where its surface offers it).
    if (session->device->facts().driver_id == vk::DriverId::eMesaLlvmpipe) CHECK(depths(*session->device).size() == 2);
    for (const gpu::Depth depth : depths(*session->device)) {
        auto output = platform::Offscreen::create(*session->device, extent, 2, depth);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        const bool ten = (*output)->colour().ten_bit();
        MESSAGE("the outputs: " << gpu::colour_name((*output)->format()) << " (" << (*output)->colour().reason << ")");
        for (const gpu::Configuration& config : all) {
            // A sentinel first: a configuration that never writes its output would read back as it.
            auto filled = (*output)->fill({1.0F, 0.0F, 1.0F, 1.0F});
            ATLAS_REQUIRE(filled.has_value(), filled.error().text());
            ClearOnly content;
            auto renderer = gpu::Renderer::create(*session->device, **output, content, config);
            ATLAS_REQUIRE(renderer.has_value(), describe(config) << ": " << renderer.error().text());
            for (int i = 0; i < 3; ++i) {
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE(report.has_value(), describe(config) << ": " << report.error().text());
            }
            (*renderer)->drain();
            auto pixels = (*output)->read((*output)->latest());
            ATLAS_REQUIRE(pixels.has_value(), describe(config) << ": " << pixels.error().text());
            // The clear reaches the output through the resolve, or as the output itself; where only the composite
            // writes the output (the direct target, one sample), this content draws no composite and the output is
            // undefined.
            const bool reaches =
                config.form == gpu::FrameForm::onepass || config.target == gpu::CompositeTarget::resolve;
            if (reaches) {
                // 0.25, 0.5, 0.75 in UNORM: 64, 128, 191 (round to nearest of 63.75, 127.5, 191.25); in 10 bits 256,
                // 511 or 512 (half way), 767, which read back at 8 bits as the same.
                bool all_clear = true;
                for (std::size_t i = 0; i < pixels->rgba.size(); i += 4) {
                    all_clear = all_clear && pixels->rgba[i] == 64 && pixels->rgba[i + 1] == 128 &&
                                pixels->rgba[i + 2] == 191 && pixels->rgba[i + 3] == 255;
                }
                CHECK_MESSAGE(all_clear, describe(config) << " did not clear to (64, 128, 191, 255): first pixel "
                                                          << int{pixels->rgba[0]} << ", " << int{pixels->rgba[1]}
                                                          << ", " << int{pixels->rgba[2]});
                if (ten) {
                    // The image holds 10 bits: 8-bit levels would be 257, 514, 766 (64, 128, 191 in 10 bits).
                    auto packed = (*output)->read_packed((*output)->latest());
                    ATLAS_REQUIRE(packed.has_value(), packed.error().text());
                    const std::uint32_t w = packed->words.front();
                    const bool red_first = packed->format == vk::Format::eA2B10G10R10UnormPack32;
                    const std::uint32_t r = red_first ? (w & 0x3FFU) : ((w >> 20) & 0x3FFU);
                    const std::uint32_t g = (w >> 10) & 0x3FFU;
                    const std::uint32_t b = red_first ? ((w >> 20) & 0x3FFU) : (w & 0x3FFU);
                    CHECK_MESSAGE((r == 256 && (g == 511 || g == 512) && b == 767 && (w >> 30) == 3),
                                  describe(config)
                                      << " in 10 bits cleared to " << r << ", " << g << ", " << b << ", " << (w >> 30));
                }
            }
            const auto messages = session->take_messages();
            CHECK_MESSAGE(messages.empty(), describe(config) << " in " << gpu::colour_name((*output)->format()) << ": "
                                                             << (messages.empty() ? std::string() : messages.front()));
        }
    }
}

TEST_CASE("the stand-in's S1 frame is the same across runs and through both frame routes, and at 10 bits within a "
          "level of its 8 bits") {
    auto city = std::make_shared<const standin::City>(standin::City::generate());
    CHECK(city->box_triangles() == 501'760);
    // S1: 1080 × 2400 at the density 2.625 (412 × 915 dp), the Main board's view.
    const vk::Extent2D s1{.width = 1080, .height = 2400};
    const style::CameraState board = standin::board_view(s1.width, s1.height, 2.625);
    // Frames that must be identical: the same configuration, form, target and colour, across both routes and both
    // runs. An 8-bit frame's key is "<config> <form> <target>"; a 10-bit one's adds its colour. Each key has a frame at
    // 16 bits a channel too, the colour as nearly exact as the frame's own arithmetic (its textures are 8-bit in all).
    std::map<std::string, std::vector<std::pair<std::string, Drawn>>> buckets;
    std::map<std::string, Drawn> references;
    std::string ten_name;
    for (int run = 0; run < 2; ++run) {
        auto session = test::open_session();
        if (!session) return;
        auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
        ATLAS_REQUIRE(cache.has_value(), cache.error().text());
        const gpu::Facts& facts = session->device->facts();
        for (const gpu::Configuration& config : configurations(facts)) {
            if (config.config == gpu::FrameConfig::a || config.config == gpu::FrameConfig::b)
                continue; // not on lavapipe
            if (run == 1 && config.route == gpu::Route::dynamic && config.form == gpu::FrameForm::onepass)
                continue; // enough
            for (const gpu::Depth depth : depths(*session->device)) {
                auto drawn = draw_standin(*session, config, s1, board, 2, *cache, city, depth);
                if (!drawn) continue;
                const auto messages = session->take_messages();
                CHECK_MESSAGE(messages.empty(), describe(config)
                                                    << " in " << colour_at(*session->device, depth) << ": "
                                                    << (messages.empty() ? std::string() : messages.front()));
                const bool ten = gpu::ten_bit(drawn->packed.format);
                if (ten) ten_name = gpu::colour_name(drawn->packed.format);
                // The 8-bit frames' lines as before this colour depth (their hashes are compared with earlier builds').
                const std::string name = std::format("run {} {}{}", run, describe(config),
                                                     ten ? " in " + gpu::colour_name(drawn->packed.format) : "");
                MESSAGE(name << ": hash " << std::format("{:016x}", platform::hash_of(drawn->pixels)) << ", GPU "
                             << drawn->gpu.total_ns / 1e6 << " ms (offscreen " << drawn->gpu.offscreen_ns / 1e6
                             << ", main " << drawn->gpu.main_ns / 1e6 << "), fragments " << drawn->gpu.fragments << " ("
                             << static_cast<double>(drawn->gpu.fragments) / (s1.width * s1.height) << " FSE), draws "
                             << drawn->counters.draws << ", triangles " << drawn->counters.triangles);
                if (run == 0) {
                    (void)platform::write_ppm(
                        drawn->pixels,
                        scratch(std::format("standin-{}-{}-{}-{}-{}.ppm", gpu::name_of(config.config),
                                            gpu::name_of(config.route), gpu::name_of(config.form),
                                            gpu::name_of(config.target), gpu::colour_name(drawn->packed.format))
                                    .c_str())
                            .string());
                }
                buckets[std::format("{} {} {}{}", gpu::name_of(config.config), gpu::name_of(config.form),
                                    gpu::name_of(config.target), ten ? " " + ten_name : "")]
                    .emplace_back(name, std::move(*drawn));
            }
            const std::string key = std::format("{} {} {}", gpu::name_of(config.config), gpu::name_of(config.form),
                                                gpu::name_of(config.target));
            if (references.contains(key) || depths(*session->device).size() < 2) continue;
            auto reference = draw_reference(*session, config, s1, board, *cache, city);
            if (reference) references.emplace(key, std::move(*reference));
        }
    }
    CHECK(buckets.size() >= 5);
    ATLAS_REQUIRE(buckets.contains("C composite direct") && buckets.contains("C onepass resolve") &&
                      buckets.contains("C composite resolve"),
                  "configuration C was not drawn in every form");
    for (const auto& [key, frames] : buckets) {
        CHECK_MESSAGE(frames.size() >= 2, key << " was drawn once only");
        for (std::size_t i = 1; i < frames.size(); ++i) {
            const platform::Difference d = platform::compare(frames[0].second.pixels, frames[i].second.pixels, 0);
            CHECK_MESSAGE(d.share == 0.0, frames[i].first << " differs from " << frames[0].first << " on "
                                                          << d.share * 100 << " % of pixels, by up to " << d.largest);
            const bool same = frames[0].second.packed.words == frames[i].second.packed.words;
            CHECK_MESSAGE(same, frames[i].first << " differs from " << frames[0].first << " as the image holds it");
        }
        // Nothing still shows the sentinel (magenta): every configuration wrote its whole output.
        const auto& p = frames[0].second.pixels.rgba;
        std::size_t sentinel = 0;
        for (std::size_t i = 0; i < p.size(); i += 4)
            sentinel += (p[i] == 255 && p[i + 1] == 0 && p[i + 2] == 255) ? 1 : 0;
        CHECK_MESSAGE(sentinel == 0, key << " left " << sentinel << " pixels unwritten");
    }
    // The 10-bit frame, read back at 8 bits (exactly: platform::level8), against the 8-bit frame of the same
    // configuration, form and target: within a level in colour. Their alpha may differ where the one-subpass form's
    // crowns write their coverage into it (2 bits hold 0, 1/3, 2/3 and 1): the surface is opaque and nothing reads it.
    //
    // Where they are more than a level apart (a few pixels in 100,000), the 8-bit frame is the one off: each 8-bit
    // write rounds (the ground, each screen-blended glow over it, the samples' resolve, the composite's effects), so it
    // drifts up to two levels from the 16-bit frame, where the 10-bit one stays within a level of it, unrounded: there
    // the 10-bit frame is the nearer the 16-bit one in every such channel.
    if (!ten_name.empty()) {
        for (const auto& [key, frames] : buckets) {
            if (key.ends_with(ten_name)) continue;
            const auto ten = buckets.find(std::format("{} {}", key, ten_name));
            ATLAS_REQUIRE(ten != buckets.end(), key << " was not drawn in " << ten_name);
            const Drawn& eight = frames[0].second;
            const Drawn& deep = ten->second[0].second;
            const Likeness l = likeness(eight, deep);
            MESSAGE(key << " in " << ten_name << " against rgba8: " << text_of(l));
            CHECK_MESSAGE(l.over1.share < 1e-4, key << " in " << ten_name
                                                    << " is more than a level from its 8-bit frame on "
                                                    << l.over1.share * 100 << " % of pixels");
            CHECK(l.any.largest <= 2);
            CHECK_MESSAGE(l.between > 0.0, key << " in " << ten_name << " holds nothing an 8-bit frame would not");
            if (key.contains("composite")) CHECK(l.alpha.share == 0.0); // the composite writes alpha 1
            const auto reference = references.find(key);
            ATLAS_REQUIRE(reference != references.end(), key << " has no 16-bit frame");
            const double off8 = worst_against(eight, reference->second);
            const double off10 = worst_against(deep, reference->second);
            const platform::Difference at8 = platform::compare(deep.pixels, reference->second.pixels, 1, false);
            const auto [nearer, apart] = nearer_where_apart(eight, deep, reference->second);
            MESSAGE(key << " against its 16-bit frame: rgba8 up to " << off8 << " levels off, " << ten_name << " up to "
                        << off10 << " (at 8 bits more than a level off on " << at8.share * 100 << " %); where the two "
                        << "are more than a level apart (" << apart << " channels), " << ten_name << " is the nearer "
                        << "in " << nearer);
            CHECK(off10 < 1.0);
            CHECK(at8.share == 0.0);
            CHECK(nearer == apart);
        }
    }
    // The composite against one subpass: they differ where the coverage rule thins the silhouettes.
    const auto& composite = buckets.at("C composite direct").front().second.pixels;
    const platform::Difference forms =
        platform::compare(composite, buckets.at("C onepass resolve").front().second.pixels, 2);
    MESSAGE("composite against one subpass: "
            << forms.share * 100 << " % of pixels differ by more than 2 levels, by up to " << forms.largest);
    CHECK(forms.share > 0.0);
    // The composite written into the samples and resolved, against the 1x image written straight from them: equal in
    // exact arithmetic; lavapipe runs the in-place composite so that later samples of a pixel see earlier samples'
    // results (a feedback loop's race), which moves some pixels where the effects change the colour.
    const platform::Difference targets =
        platform::compare(composite, buckets.at("C composite resolve").front().second.pixels, 0);
    MESSAGE("composite resolved against written straight: " << targets.share * 100 << " % of pixels differ, by up to "
                                                            << targets.largest);
}

namespace {

/// A watch double (gpu/Pipelines.hpp): it hears each announcement and its end, with what the allocator holds at each,
/// and notes every break of the rule that a name lasts its call alone: none announced while another is open (none
/// nest in the stand-in's and the renderer's work), nothing allocated or freed while a module or a pipeline is made (an
/// image made under a pipeline's name), nothing allocated while something goes, and each announcement ended.
class Watch final : public gpu::PipelineWatch {
public:
    explicit Watch(const gpu::Device& device) : device_(device) {}

    std::vector<std::string> released;
    std::uint32_t made = 0;
    std::uint32_t announced = 0;
    std::uint32_t ended = 0;
    std::vector<std::string> faults;

    void before(const gpu::PipelineLabel& label) override {
        ++made;
        begin("pipeline " + gpu::describe(label), true);
    }
    void before_module(std::string_view module) override { begin("module " + std::string(module), true); }
    void before_release(const gpu::Release& release) override {
        released.push_back(gpu::describe(release));
        begin("release " + gpu::describe(release), false);
    }
    void after() override {
        ++ended;
        if (open_.empty()) {
            faults.emplace_back("an end with nothing announced");
            return;
        }
        const std::uint32_t now = allocations();
        if (making_ && now != at_open_) {
            faults.push_back(std::format("{}: {} allocations made or freed under its name", open_,
                                         static_cast<std::int64_t>(now) - static_cast<std::int64_t>(at_open_)));
        }
        if (!making_ && now > at_open_)
            faults.push_back(std::format("{}: {} allocations made under its name", open_, now - at_open_));
        open_.clear();
    }

private:
    void begin(std::string what, bool making) {
        ++announced;
        if (!open_.empty()) faults.push_back(std::format("{} announced while {} was open", what, open_));
        open_ = std::move(what);
        making_ = making;
        at_open_ = allocations();
    }
    [[nodiscard]] std::uint32_t allocations() const {
        VmaTotalStatistics total{};
        vmaCalculateStatistics(device_.memory().handle(), &total);
        return total.total.statistics.allocationCount;
    }

    const gpu::Device& device_;
    std::string open_;
    bool making_ = false;
    std::uint32_t at_open_ = 0;
};

} // namespace

TEST_CASE("a renderer's end releases everything it prepared in the stand-in, announced to its watch") {
    // What VK bench's colour stage relies on (jni/Bridge.cpp): the 10-bit frame's pipelines, passes, framebuffers and
    // images go with the renderer that prepared them, so the core can let go of them all before the crash guard's
    // colour stage closes. The watch hears each frame go, named by its configuration and colour, and each name lasts
    // its call alone (Watch's rule): the next frame's pass and images are made under none.
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto city = std::make_shared<const standin::City>(standin::City::generate(32, 13.0));
    Watch watch(*session->device);
    auto standin = standin::StandIn::create(*session->device, *cache, city, &watch);
    ATLAS_REQUIRE(standin.has_value(), standin.error().text());
    CHECK_FALSE((*standin)->holds_prepared()); // only what every configuration shares
    const gpu::Configuration config = session->device->facts().choice;
    for (const gpu::Depth depth : depths(*session->device)) {
        auto output = platform::Offscreen::create(*session->device, {.width = 64, .height = 64}, 2, depth);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        const std::string frame =
            std::format("frame (configuration {} {})", describe(config), gpu::colour_name((*output)->format()));
        watch.released.clear();
        {
            auto renderer = gpu::Renderer::create(*session->device, **output, **standin, config, &watch);
            ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
            CHECK((*standin)->holds_prepared());
            for (int i = 0; i < 2; ++i) {
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE(report.has_value(), report.error().text());
            }
            // A reconfiguration lets go of the frame drawn so far before it makes the next.
            auto again = (*renderer)->reconfigure(config);
            ATLAS_REQUIRE(again.has_value(), again.error().text());
            CHECK(watch.released == std::vector<std::string>{frame});
            CHECK((*standin)->holds_prepared());
        }
        // The renderer gone: nothing of its frame is left in the stand-in, and its watch heard it go.
        CHECK_FALSE((*standin)->holds_prepared());
        CHECK(watch.released == std::vector<std::string>{frame, frame});
        const auto messages = session->take_messages();
        CHECK_MESSAGE(messages.empty(), frame << ": " << (messages.empty() ? std::string() : messages.front()));
    }
    // The stand-in made once draws again after its frames went: the next renderer prepares it afresh.
    auto output = platform::Offscreen::create(*session->device, {.width = 64, .height = 64}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    auto renderer = gpu::Renderer::create(*session->device, **output, **standin, config, &watch);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    auto report = (*renderer)->draw(0);
    CHECK(report.has_value());
    renderer->reset();
    CHECK_FALSE((*standin)->holds_prepared());
    CHECK(session->take_messages().empty());
    // Every name lasted its call alone: each announcement ended, none while another was open, and no image or buffer
    // was made under a module's or a pipeline's name, nor under that of something going.
    MESSAGE(watch.announced << " announcements (" << watch.made << " pipelines), " << watch.ended << " ends");
    CHECK(watch.made > 0);
    CHECK(watch.announced == watch.ended);
    CHECK_MESSAGE(watch.faults.empty(), (watch.faults.empty() ? std::string() : watch.faults.front()));
}

TEST_CASE("the frosted pills draw over a 10-bit output and an 8-bit one") {
    // What the SurfaceView host draws after the main pass (D5.1): the frame copied at a quarter of its size (a blit
    // from the output's format into RGBA8), blurred, and drawn back inside the pills, blended over the output.
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto city = std::make_shared<const standin::City>(standin::City::generate(64, 13.0));
    const vk::Extent2D extent{.width = 540, .height = 1200};
    const style::CameraState camera = with_pills(standin::board_view(extent.width, extent.height, 1.3125));
    const gpu::Configuration config = session->device->facts().choice;
    auto bare = draw_standin(*session, config, extent, standin::board_view(extent.width, extent.height, 1.3125), 3,
                             *cache, city, gpu::Depth::eight);
    ATLAS_REQUIRE(bare.has_value(), bare.error().text());
    std::vector<Drawn> drawn;
    for (const gpu::Depth depth : depths(*session->device)) {
        auto d = draw_standin(*session, config, extent, camera, 3, *cache, city, depth);
        ATLAS_REQUIRE(d.has_value(), d.error().text());
        const auto messages = session->take_messages();
        CHECK_MESSAGE(messages.empty(), colour_at(*session->device, depth)
                                            << ": " << (messages.empty() ? std::string() : messages.front()));
        drawn.push_back(std::move(*d));
    }
    // The pills cover about a tenth of the frame.
    const platform::Difference pills = platform::compare(bare->pixels, drawn[0].pixels, 0, false);
    MESSAGE("the pills change " << pills.share * 100 << " % of the frame");
    CHECK(pills.share > 0.05);
    // The frosted glass is drawn from an 8-bit copy of the frame, blurred six times at 8 bits and saturated by 1.8,
    // which spreads the copy's rounding: so under the pills the depths differ by up to a few levels (a channel swap
    // would differ by tens).
    if (drawn.size() == 2) {
        const Likeness l = likeness(drawn[0], drawn[1]);
        MESSAGE("with the pills, " << gpu::colour_name(drawn[1].packed.format) << " against rgba8: " << text_of(l));
        CHECK(l.any.largest <= 4);
    }
}

TEST_CASE("the stand-in's steady S1 frame takes at most half a second, and its pipeline cache is kept and checked") {
    // No validation layer here: the frame's own cost, as the bench measures it on the phone.
    test::Session session;
    session.instance = test::open_instance({.validation = false});
    if (!session.instance) return;
    auto device = gpu::Device::open(*session.instance, {});
    ATLAS_REQUIRE(device.has_value(), device.error().text());
    session.device = std::move(*device);
    session.device->check_ten_bit(); // it draws at 10 bits too, as VK bench's colour stage does after asking
    auto city = std::make_shared<const standin::City>(standin::City::generate());
    const vk::Extent2D s1{.width = 1080, .height = 2400};
    const style::CameraState board = standin::board_view(s1.width, s1.height, 2.625);
    const std::filesystem::path file = scratch(std::format("pipelines-{}.bin", ::getpid()).c_str());
    gpu::PipelineCache::remove(file);
    const gpu::Configuration config = session.device->facts().choice;

    auto cold = gpu::PipelineCache::open(session.device->handle(), session.device->facts(), file, 7, true);
    ATLAS_REQUIRE(cold.has_value(), cold.error().text());
    CHECK_FALSE(cold->hit());
    // In the frame's colour, as the phone draws it where its surface allows (10 bits on lavapipe).
    const gpu::Depth deepest = gpu::Depth::deepest;
    auto first = draw_standin(session, config, s1, board, 4, *cold, city, deepest);
    ATLAS_REQUIRE(first.has_value(), first.error().text());
    auto saved = cold->save();
    ATLAS_REQUIRE(saved.has_value(), saved.error().text());
    CHECK(*saved >= sizeof(gpu::CacheHeader));
    CHECK_FALSE(cold->changed());
    // Nothing made through it since: the next write writes nothing.
    auto again = cold->save();
    ATLAS_REQUIRE(again.has_value(), again.error().text());
    CHECK(*again == 0);
    cold = gpu::fail("closed"); // the cache goes before its device

    // A second device, as the next start opens it.
    session.device.reset();
    auto device2 = gpu::Device::open(*session.instance, {});
    ATLAS_REQUIRE(device2.has_value(), device2.error().text());
    session.device = std::move(*device2);
    session.device->check_ten_bit();
    auto warm = gpu::PipelineCache::open(session.device->handle(), session.device->facts(), file, 7, true);
    ATLAS_REQUIRE(warm.has_value(), warm.error().text());
    CHECK(warm->hit());
    CHECK(warm->status() == "hit");
    auto second = draw_standin(session, config, s1, board, 4, *warm, city, deepest);
    ATLAS_REQUIRE(second.has_value(), second.error().text());
    // The same frame at 8 bits, for its time against the frame's colour's (VK bench's colour line, on the phone).
    auto eight = draw_standin(session, config, s1, board, 4, *warm, city, gpu::Depth::eight);
    ATLAS_REQUIRE(eight.has_value(), eight.error().text());
    // Another shader hash discards the file.
    auto other = gpu::PipelineCache::open(session.device->handle(), session.device->facts(), file, 8, true);
    ATLAS_REQUIRE(other.has_value(), other.error().text());
    CHECK_FALSE(other->hit());
    CHECK(other->status() == "miss: other shaders");
    // A damaged file too.
    {
        std::FILE* f = std::fopen(file.c_str(), "r+b");
        ATLAS_REQUIRE(f != nullptr, "cannot open " << file.string());
        (void)std::fseek(f, static_cast<long>(offsetof(gpu::CacheHeader, checksum)), SEEK_SET);
        (void)std::fputc(0x5A, f);
        (void)std::fclose(f);
    }
    auto damaged = gpu::PipelineCache::open(session.device->handle(), session.device->facts(), file, 7, true);
    ATLAS_REQUIRE(damaged.has_value(), damaged.error().text());
    CHECK(damaged->status() == "miss: damaged");

    MESSAGE("pipelines: " << first->pipelines << " made in " << first->pipelines_ms << " ms without the cache, "
                          << second->pipelines_ms << " ms from it (" << *saved << " bytes on disk)");
    MESSAGE("steady S1 frame (" << describe(config) << " in " << gpu::colour_name(second->packed.format)
                                << "): " << second->frame_ms << " ms on the CPU's clock, GPU "
                                << second->gpu.total_ns / 1e6 << " ms, ranks " << second->counters.ranks_ms
                                << " ms, recording " << second->counters.record_ms << " ms; in rgba8 "
                                << eight->frame_ms << " ms, GPU " << eight->gpu.total_ns / 1e6 << " ms");
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
