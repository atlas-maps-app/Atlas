// The frame's colour depth (plan-vector D5, "Colour depth"; gpu/Facts.hpp): the choice of 10 bits a channel where the
// surface offers them in sRGB and the device can draw the frame in them, else 8 bits as before, with its reasons; the
// exact conversion of a 10-bit frame to the 8 bits a channel the tests, the hashes and the likeness gates read
// (platform/host); and the rule that keeps rgb10a2's 2 bits of alpha harmless: no blend reads the destination's alpha.
// None of these needs a device: the facts are written by hand, as a phone's would read.
#include "gpu/Facts.hpp"
#include "gpu/Pipelines.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <string>
#include <vector>

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"
#endif

using namespace atlas;

namespace {

using F = vk::Format;
using S = vk::ColorSpaceKHR;
constexpr vk::SampleCountFlags one_four = vk::SampleCountFlagBits::e1 | vk::SampleCountFlagBits::e4;

/// A phone's facts as far as the colour reads them: 1x and 4x (and 2x when `two`), each of the four formats drawable
/// unless `unfit` names it.
gpu::Facts phone(bool two = false, F unfit = F::eUndefined) {
    gpu::Facts f;
    const vk::SampleCountFlags counts = two ? one_four | vk::SampleCountFlagBits::e2 : one_four;
    f.limits.framebufferColorSampleCounts = counts;
    f.limits.framebufferDepthSampleCounts = counts;
    f.limits.framebufferStencilSampleCounts = counts;
    for (const F format :
         {F::eA2B10G10R10UnormPack32, F::eA2R10G10B10UnormPack32, F::eR8G8B8A8Unorm, F::eB8G8R8A8Unorm}) {
        gpu::ColourFormat c{.format = format, .output = true, .attachment = true, .samples = counts};
        if (format == unfit) {
            c.output = false;
            c.lacks = "blending";
        }
        f.colour_formats.push_back(c);
    }
    return f;
}

std::vector<vk::SurfaceFormatKHR> offering(std::initializer_list<std::pair<F, S>> formats) {
    std::vector<vk::SurfaceFormatKHR> out;
    for (const auto& [format, space] : formats) out.push_back({.format = format, .colorSpace = space});
    return out;
}

} // namespace

TEST_CASE("the frame takes 10 bits where the surface offers them in sRGB and the device draws the frame in them") {
    const gpu::Facts f = phone();
    // A phone's usual surface: rgba8 and rgb10a2 in sRGB, and rgb10a2 in HDR10.
    const auto usual = offering({{F::eR8G8B8A8Unorm, S::eSrgbNonlinear},
                                 {F::eA2B10G10R10UnormPack32, S::eSrgbNonlinear},
                                 {F::eA2B10G10R10UnormPack32, S::eHdr10St2084EXT}});
    const gpu::Colour deep = gpu::colour_for_surface(f, usual, gpu::Depth::deepest);
    CHECK(deep.format == F::eA2B10G10R10UnormPack32);
    CHECK(deep.space == S::eSrgbNonlinear);
    CHECK(deep.ten_bit());
    CHECK(deep.reason == "the surface offers it in sRGB and the frame's attachments follow it");
    // VK bench's 8-bit frame on the same surface.
    const gpu::Colour eight = gpu::colour_for_surface(f, usual, gpu::Depth::eight);
    CHECK(eight.format == F::eR8G8B8A8Unorm);
    CHECK(eight.space == S::eSrgbNonlinear);
    CHECK_FALSE(eight.ten_bit());
    CHECK(eight.reason == "8 bits asked for");
    // bgr10a2 where rgb10a2 is not offered.
    const gpu::Colour bgr = gpu::colour_for_surface(
        f, offering({{F::eB8G8R8A8Unorm, S::eSrgbNonlinear}, {F::eA2R10G10B10UnormPack32, S::eSrgbNonlinear}}),
        gpu::Depth::deepest);
    CHECK(bgr.format == F::eA2R10G10B10UnormPack32);
    // 10 bits outside sRGB only: the gamut stays sRGB, so 8 bits.
    const gpu::Colour wide = gpu::colour_for_surface(f,
                                                     offering({{F::eA2B10G10R10UnormPack32, S::eHdr10St2084EXT},
                                                               {F::eA2B10G10R10UnormPack32, S::eDisplayP3NonlinearEXT},
                                                               {F::eR8G8B8A8Unorm, S::eSrgbNonlinear}}),
                                                     gpu::Depth::deepest);
    CHECK(wide.format == F::eR8G8B8A8Unorm);
    CHECK(wide.reason == "the surface offers 10 bits only outside sRGB (the gamut stays sRGB)");
    // No 10-bit format at all; and a surface of bgra8 alone.
    const gpu::Colour plain =
        gpu::colour_for_surface(f, offering({{F::eR8G8B8A8Unorm, S::eSrgbNonlinear}}), gpu::Depth::deepest);
    CHECK(plain.format == F::eR8G8B8A8Unorm);
    CHECK(plain.reason == "the surface offers no 10-bit format");
    CHECK(gpu::colour_for_surface(f, offering({{F::eB8G8R8A8Unorm, S::eSrgbNonlinear}}), gpu::Depth::deepest).format ==
          F::eB8G8R8A8Unorm);
    // A surface of neither 8-bit sRGB format: its first, as before.
    const gpu::Colour odd =
        gpu::colour_for_surface(f, offering({{F::eR8G8B8A8Srgb, S::eSrgbNonlinear}}), gpu::Depth::deepest);
    CHECK(odd.format == F::eR8G8B8A8Srgb);
    CHECK(odd.reason.ends_with("the surface offers neither rgba8 nor bgra8 in sRGB: its first format"));
}

TEST_CASE("the frame stays at 8 bits where the device cannot draw it in the surface's 10") {
    const auto usual =
        offering({{F::eR8G8B8A8Unorm, S::eSrgbNonlinear}, {F::eA2B10G10R10UnormPack32, S::eSrgbNonlinear}});
    // rgb10a2 without blending.
    const gpu::Colour unblended =
        gpu::colour_for_surface(phone(false, F::eA2B10G10R10UnormPack32), usual, gpu::Depth::deepest);
    CHECK(unblended.format == F::eR8G8B8A8Unorm);
    CHECK(unblended.reason == "the surface's 10-bit sRGB formats are of no use to the frame: rgb10a2 (no blending)");
    // A device with 2x samples (configuration B) whose rgb10a2 input attachments have none.
    gpu::Facts two = phone(true);
    two.colour_formats.front().samples = one_four;
    const gpu::Colour no_2x = gpu::colour_for_surface(two, usual, gpu::Depth::deepest);
    CHECK(no_2x.format == F::eR8G8B8A8Unorm);
    CHECK(no_2x.reason.ends_with("rgb10a2 (no 2x samples as a transient input attachment)"));
    // Lazily allocated memory that cannot hold rgb10a2's samples: configuration A needs it.
    gpu::Facts tiler = phone();
    tiler.lazy_memory = true;
    tiler.colour_formats.front().lazy = false;
    tiler.colour_formats.front().lazy_checked = true;
    const gpu::Colour no_lazy = gpu::colour_for_surface(tiler, usual, gpu::Depth::deepest);
    CHECK(no_lazy.format == F::eR8G8B8A8Unorm);
    CHECK(no_lazy.reason.ends_with("(lazily allocated memory cannot hold its transient samples (configuration A))"));
    // Without such memory (configuration C) the probe's answer does not matter.
    tiler.lazy_memory = false;
    CHECK(gpu::colour_for_surface(tiler, usual, gpu::Depth::deepest).format == F::eA2B10G10R10UnormPack32);
}

TEST_CASE("offscreen the frame takes 10 bits where the device draws it in them, and VkFacts says why") {
    gpu::Facts f = phone();
    const gpu::Colour deep = gpu::colour_offscreen(f, gpu::Depth::deepest);
    CHECK(deep.format == F::eA2B10G10R10UnormPack32);
    CHECK(deep.reason == "offscreen: the device draws the frame in 10 bits");
    CHECK(gpu::colour_offscreen(f, gpu::Depth::eight).format == F::eR8G8B8A8Unorm);
    f.colour_formats[0].attachment = false;
    f.colour_formats[0].lacks = "input attachment";
    CHECK(gpu::colour_offscreen(f, gpu::Depth::deepest).format == F::eA2R10G10B10UnormPack32);
    f.colour_formats[1].lacks = "linearly filtered blits";
    const gpu::Colour none = gpu::colour_offscreen(f, gpu::Depth::deepest);
    CHECK(none.format == F::eR8G8B8A8Unorm);
    CHECK(none.reason == "offscreen: the device cannot draw the frame in 10 bits: rgb10a2 (no input attachment), "
                         "bgr10a2 (no linearly filtered blits)");
    // The lines VkFacts prints for it.
    f.colour = none;
    const std::vector<std::string> lines = gpu::facts_lines(f);
    CHECK(std::ranges::find(lines, "colour: rgba8 (R8G8B8A8Unorm in SrgbNonlinear: " + none.reason + ")") !=
          lines.end());
    CHECK(std::ranges::find(lines, "colour.formats: rgb10a2 no (no input attachment), bgr10a2 no (no linearly filtered "
                                   "blits), rgba8 yes, bgra8 yes (the frame drawn in each)") != lines.end());
    CHECK(gpu::colour_name(F::eA2B10G10R10UnormPack32) == "rgb10a2");
    CHECK(gpu::colour_name(F::eA2R10G10B10UnormPack32) == "bgr10a2");
    CHECK(gpu::colour_name(F::eR16G16B16A16Sfloat) == "R16G16B16A16Sfloat");
}

TEST_CASE("no blend of the core reads the destination's alpha, and a pipeline that would is refused") {
    for (const gpu::Blend& b :
         {gpu::Blend::opaque(), gpu::Blend::over(), gpu::Blend::screen(), gpu::Blend::untouched()})
        CHECK_FALSE(b.reads_destination_alpha());
    // The destination's alpha in the alpha factors leaves the colour alone; in the colour factors it does not.
    gpu::Blend alpha_only = gpu::Blend::over();
    alpha_only.dst_alpha = vk::BlendFactor::eDstAlpha;
    CHECK_FALSE(alpha_only.reads_destination_alpha());
    for (const vk::BlendFactor f :
         {vk::BlendFactor::eDstAlpha, vk::BlendFactor::eOneMinusDstAlpha, vk::BlendFactor::eSrcAlphaSaturate}) {
        gpu::Blend b = gpu::Blend::over();
        b.src_colour = f;
        CHECK(b.reads_destination_alpha());
        b.enable = false;
        CHECK_FALSE(b.reads_destination_alpha()); // no blending, no read
    }
    // Refused before any driver sees it: no device is needed to be told so.
    const vk::raii::Device none{nullptr};
    const vk::raii::PipelineCache no_cache{nullptr};
    gpu::GraphicsDesc desc{.label = {.name = "planted", .module = "none"}};
    desc.blends[0] = gpu::Blend::over();
    desc.blends[0].dst_colour = vk::BlendFactor::eOneMinusDstAlpha;
    auto made = gpu::make_graphics(none, no_cache, desc);
    ATLAS_REQUIRE(!made.has_value(), "a blend by the destination's alpha was made");
    CHECK(made.error().text() ==
          "the graphics pipeline planted (module none, configuration any) blends attachment 0 by "
          "the destination's alpha, which the frame's colour does not keep (rgb10a2 holds 2 "
          "bits of it)");
}

#if !defined(__ANDROID__)
TEST_CASE("a 10-bit frame reads back at 8 bits exactly: each channel at its nearest level, and every level kept") {
    using platform::level10;
    using platform::level8;
    // Every 10-bit value to the nearest 8-bit level, against the rational arithmetic: |255 v - 1023 q| is least, and
    // never a tie.
    for (std::uint32_t v = 0; v < 1024; ++v) {
        const std::uint32_t q = level8(v);
        const auto miss = [&](std::uint32_t k) {
            const std::int64_t a = std::int64_t{255} * v;
            const std::int64_t b = std::int64_t{1023} * k;
            return a > b ? a - b : b - a;
        };
        CHECK(miss(q) * 2 < 1023);
        if (q > 0) CHECK(miss(q) < miss(q - 1));
        if (q < 255) CHECK(miss(q) < miss(q + 1));
    }
    // Every 8-bit level drawn at 10 bits (its nearest 10-bit value) comes back as itself, and is the 10-bit value the
    // GPU stores for that level's float.
    for (std::uint32_t k = 0; k < 256; ++k) {
        const auto k8 = static_cast<std::uint8_t>(k);
        CHECK(level8(level10(k8)) == k8);
        const double exact = static_cast<double>(k) * 1023.0 / 255.0;
        CHECK(static_cast<double>(level10(k8)) - exact <= 0.5);
        CHECK(exact - static_cast<double>(level10(k8)) < 0.5);
    }
    // The packings: rgb10a2 holds R in bits 0-9, bgr10a2 B; alpha in 30-31 at 0, 85, 170, 255; 8-bit B8G8R8A8 swapped.
    const std::uint32_t word = 1023U | (512U << 10) | (4U << 20) | (2U << 30);
    platform::Packed packed{.width = 1, .height = 1, .format = F::eA2B10G10R10UnormPack32, .words = {word}};
    CHECK(platform::to_rgba8(packed).rgba == std::vector<std::uint8_t>{255, 128, 1, 170});
    packed.format = F::eA2R10G10B10UnormPack32;
    CHECK(platform::to_rgba8(packed).rgba == std::vector<std::uint8_t>{1, 128, 255, 170});
    packed.format = F::eB8G8R8A8Unorm;
    packed.words = {0x44332211U};
    CHECK(platform::to_rgba8(packed).rgba == std::vector<std::uint8_t>{0x33, 0x22, 0x11, 0x44});
    packed.format = F::eR8G8B8A8Unorm;
    CHECK(platform::to_rgba8(packed).rgba == std::vector<std::uint8_t>{0x11, 0x22, 0x33, 0x44});
    CHECK(
        platform::to_rgba8({.width = 1, .height = 1, .format = F::eR16G16B16A16Sfloat, .words = {0, 0}}).rgba.empty());
    // Between levels: 512 is not an 8-bit level's 10-bit value (128 is 514), 1023 and 4 (level 1) are.
    packed = {.width = 1, .height = 1, .format = F::eA2B10G10R10UnormPack32, .words = {word}};
    CHECK(platform::share_between_levels(packed) == doctest::Approx(1.0 / 3.0));
    packed.format = F::eR8G8B8A8Unorm;
    CHECK(platform::share_between_levels(packed) == 0.0);
}
#endif
