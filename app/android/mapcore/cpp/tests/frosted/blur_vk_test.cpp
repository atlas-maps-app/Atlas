// The box blurs (fx/Blur.hpp, K11b): WebKit's box sizes in single precision as SvgFilterRaster.kernel computes them,
// its three boxes as kernelPosition places them, the CPU blur held to WebKit's separable passes (SvgFilterRaster's
// boxNone, ported here as the reference), and the GPU's dispatches held to the CPU blur bit for bit on the host's
// Vulkan, along both axes, in every mode, for RGBA8 and R8, with the validation layer silent.
#include "fx/Blur.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Sync.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <format>
#include <limits>
#include <random>
#include <span>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// A generator of a seed given (the tests' sequences are fixed, so every run checks the same values).
[[nodiscard]] std::mt19937_64 seeded(std::uint64_t seed) { return std::mt19937_64(seed); }

/// σ (its single-precision bits) and WebKit's box size for it: around every size's lower edge (the float below, at
/// and above (k - 1/2) / f), and some spot values. Computed apart from the core, by emulating Kotlin's float steps
/// (3f / 4f * sqrt(2 * Math.PI.toFloat()), then floor(σ · f + 0.5f)) one rounding at a time.
using SizeCase = std::array<std::uint32_t, 2>;
constexpr std::array size_cases{
    SizeCase{0x3e882c1aU, 2},   SizeCase{0x3f4c422aU, 2},   SizeCase{0x3faa3721U, 2},   SizeCase{0x3faa3722U, 3},
    SizeCase{0x3fee4d2fU, 3},   SizeCase{0x3fee4d30U, 4},   SizeCase{0x4019319eU, 4},   SizeCase{0x4019319fU, 5},
    SizeCase{0x403b3ca5U, 5},   SizeCase{0x403b3ca6U, 6},   SizeCase{0x405d47acU, 6},   SizeCase{0x405d47adU, 7},
    SizeCase{0x407f52b2U, 7},   SizeCase{0x407f52b3U, 8},   SizeCase{0x4090aedcU, 8},   SizeCase{0x4090aeddU, 9},
    SizeCase{0x40a1b460U, 9},   SizeCase{0x40a1b461U, 10},  SizeCase{0x40b2b9e3U, 10},  SizeCase{0x40b2b9e4U, 11},
    SizeCase{0x40c3bf66U, 11},  SizeCase{0x40c3bf67U, 12},  SizeCase{0x40d4c4eaU, 12},  SizeCase{0x40d4c4ebU, 13},
    SizeCase{0x40e5ca6dU, 13},  SizeCase{0x40e5ca6eU, 14},  SizeCase{0x40f6cff1U, 14},  SizeCase{0x40f6cff2U, 15},
    SizeCase{0x4103eabaU, 15},  SizeCase{0x4103eabbU, 16},  SizeCase{0x410c6d7bU, 16},  SizeCase{0x410c6d7cU, 17},
    SizeCase{0x4114f03dU, 17},  SizeCase{0x4114f03eU, 18},  SizeCase{0x411d72ffU, 18},  SizeCase{0x411d7300U, 19},
    SizeCase{0x4125f5c0U, 19},  SizeCase{0x4125f5c1U, 20},  SizeCase{0x412e7882U, 20},  SizeCase{0x412e7883U, 21},
    SizeCase{0x4136fb44U, 21},  SizeCase{0x4136fb45U, 22},  SizeCase{0x413f7e06U, 22},  SizeCase{0x413f7e07U, 23},
    SizeCase{0x414800c7U, 23},  SizeCase{0x414800c8U, 24},  SizeCase{0x41508389U, 24},  SizeCase{0x4150838aU, 25},
    SizeCase{0x4159064bU, 25},  SizeCase{0x4159064cU, 26},  SizeCase{0x4161890cU, 26},  SizeCase{0x4161890dU, 27},
    SizeCase{0x416a0bceU, 27},  SizeCase{0x416a0bcfU, 28},  SizeCase{0x41728e90U, 28},  SizeCase{0x41728e91U, 29},
    SizeCase{0x417b1152U, 29},  SizeCase{0x417b1153U, 30},  SizeCase{0x4181ca09U, 30},  SizeCase{0x4181ca0aU, 31},
    SizeCase{0x419f93afU, 37},  SizeCase{0x419f93b0U, 38},  SizeCase{0x41a3d510U, 38},  SizeCase{0x41a3d511U, 39},
    SizeCase{0x3dcccccdU, 2},   SizeCase{0x3f800000U, 2},   SizeCase{0x40000000U, 4},   SizeCase{0x40666666U, 7},
    SizeCase{0x41000000U, 15},  SizeCase{0x41666666U, 27},  SizeCase{0x41a00000U, 38},  SizeCase{0x41d20000U, 49},
    SizeCase{0x42670000U, 109}, SizeCase{0x42c80000U, 188}, SizeCase{0x43960000U, 500}, SizeCase{0x447a0000U, 500},
};

/// SvgFilterRaster's boxNone, ported: one box of size `d` reaching `left` and `right` along rows (`horizontal`) or
/// columns of premultiplied ARGB, transparent past the ends, each channel's sum times 1/d in single precision and
/// truncated, as WebKit's NEON code computes it. The test's independent reference for the CPU blur.
void box_none(const std::vector<std::uint32_t>& src, std::vector<std::uint32_t>& dst, std::uint32_t w, std::uint32_t h,
              std::uint32_t d, std::uint32_t left, std::uint32_t right, bool horizontal) {
    const std::uint32_t lines = horizontal ? h : w;
    const std::uint32_t len = horizontal ? w : h;
    const std::uint32_t step = horizontal ? 1 : w;
    const std::uint32_t max_kernel = std::min(right, len);
    const float inv = 1.0F / static_cast<float>(d);
    for (std::uint32_t line = 0; line < lines; ++line) {
        const std::uint32_t base = horizontal ? line * w : line;
        std::array<std::uint32_t, 4> sum{};
        auto add = [&](std::uint32_t p, bool plus) {
            for (std::uint32_t k = 0; k < 4; ++k) {
                const std::uint32_t c = (p >> (8U * k)) & 0xFFU;
                sum[k] = plus ? sum[k] + c : sum[k] - c;
            }
        };
        for (std::uint32_t i = 0; i < max_kernel; ++i) add(src[base + (i * step)], true);
        for (std::uint32_t x = 0; x < len; ++x) {
            const std::uint32_t at = base + (x * step);
            std::uint32_t out = 0;
            for (std::uint32_t k = 0; k < 4; ++k) {
                out |= static_cast<std::uint32_t>(static_cast<float>(sum[k]) * inv) << (8U * k);
            }
            dst[at] = out;
            if (x >= left) add(src[at - (left * step)], false);
            if (x + right < len) add(src[at + (right * step)], true);
        }
    }
}

[[nodiscard]] std::vector<std::uint32_t> random_texels(std::size_t n, std::uint64_t seed, bool r8) {
    std::mt19937_64 rng = seeded(seed);
    std::vector<std::uint32_t> out(n);
    for (std::uint32_t& t : out) {
        const auto v = static_cast<std::uint32_t>(rng());
        t = r8 ? (v & 0xFFU) : v;
    }
    return out;
}

} // namespace

TEST_CASE("WebKit's box sizes, in single precision as SvgFilterRaster.kernel computes them") {
    for (const auto& [bits, size] : size_cases) {
        const auto sigma = std::bit_cast<float>(bits);
        CHECK_MESSAGE(fx::box_size(sigma) == size, std::format("σ {:.9g} ({:#010x})", sigma, bits));
    }
    CHECK(fx::box_size(0.0F) == 0);
    CHECK(fx::box_size(-1.0F) == 0);
    CHECK(fx::box_size(std::numeric_limits<float>::quiet_NaN()) == 0);
    CHECK(fx::box_size(std::numeric_limits<float>::infinity()) == 500);
    CHECK(fx::box_size(1e-30F) == 2);
}

TEST_CASE("WebKit's three boxes for each size, as kernelPosition places them") {
    using B = fx::Box;
    CHECK(fx::webkit_boxes(0).count == 0);
    const fx::Run two = fx::webkit_boxes(2);
    CHECK(two.count == 3);
    CHECK(two.boxes[0] == B{.left = 0, .right = 2});
    CHECK(two.boxes[1] == B{.left = 1, .right = 1});
    CHECK(two.boxes[2] == B{.left = 1, .right = 2});
    const fx::Run four = fx::webkit_boxes(4);
    CHECK(four.boxes[0] == B{.left = 1, .right = 3});
    CHECK(four.boxes[1] == B{.left = 2, .right = 2});
    CHECK(four.boxes[2] == B{.left = 2, .right = 3});
    const fx::Run odd = fx::webkit_boxes(27);
    for (const B& b : odd.boxes) CHECK(b == B{.left = 13, .right = 14});
    for (std::uint32_t d = 2; d <= 64; ++d) {
        const fx::Run run = fx::webkit_boxes(d);
        REQUIRE(run.count == 3);
        CHECK(run.boxes[0].size() == d);
        CHECK(run.boxes[1].size() == d);
        CHECK(run.boxes[2].size() == (d % 2 == 0 ? d + 1 : d));
        // The three together are centred: as far to the left as to the right.
        CHECK(run.reach_left() == run.reach_right());
        CHECK(run.reach() == (d % 2 == 0 ? (3 * d / 2) - 1 : 3 * (d - 1) / 2));
    }
}

TEST_CASE("the CPU blur is WebKit's separable boxes: transparent past the ends, truncating, box by box") {
    constexpr std::uint32_t w = 37;
    constexpr std::uint32_t h = 23;
    const std::vector<std::uint32_t> picture = random_texels(std::size_t{w} * h, 11, false);
    for (const std::uint32_t d : {2U, 3U, 6U, 9U, 26U, 27U, 41U}) {
        const fx::Run run = fx::webkit_boxes(d);
        for (const fx::Axis axis : {fx::Axis::x, fx::Axis::y}) {
            const bool along_x = axis == fx::Axis::x;
            // The run as WebKit's passes along one axis, box after box.
            std::vector<std::uint32_t> want = picture;
            std::vector<std::uint32_t> next(want.size());
            for (std::uint32_t i = 0; i < run.count; ++i) {
                box_none(want, next, w, h, run.boxes[i].size(), run.boxes[i].left, run.boxes[i].right, along_x);
                want.swap(next);
            }
            std::vector<std::uint32_t> got(picture.size(), 0xDEADBEEFU);
            fx::blur_cpu(picture, got, w, {.x = 0, .y = 0, .width = w, .height = h, .run = run},
                         {.axis = axis, .rounding = fx::Rounding::truncate, .edge = fx::Edge::transparent},
                         fx::BlurFormat::rgba8);
            CHECK_MESSAGE(got == want, std::format("d {} along {}", d, along_x ? "x" : "y"));
        }
    }
}

TEST_CASE("a run with its ends extended keeps a flat picture flat; transparent ends fade it within the run's reach") {
    constexpr std::uint32_t w = 50;
    constexpr std::uint32_t h = 4;
    const std::vector<std::uint32_t> flat(std::size_t{w} * h, 0xFF4080C0U);
    const fx::Run run = fx::webkit_run(3.6F); // boxes of 7: a reach of 9
    std::vector<std::uint32_t> extended(flat.size());
    fx::blur_cpu(flat, extended, w, {.x = 0, .y = 0, .width = w, .height = h, .run = run},
                 {.axis = fx::Axis::x, .rounding = fx::Rounding::nearest, .edge = fx::Edge::extend},
                 fx::BlurFormat::rgba8);
    CHECK(extended == flat);
    std::vector<std::uint32_t> faded(flat.size());
    fx::blur_cpu(flat, faded, w, {.x = 0, .y = 0, .width = w, .height = h, .run = run},
                 {.axis = fx::Axis::x, .rounding = fx::Rounding::nearest, .edge = fx::Edge::transparent},
                 fx::BlurFormat::rgba8);
    const std::uint32_t reach = run.reach();
    for (std::uint32_t x = 0; x < w; ++x) {
        const bool inside = x >= reach && x + reach < w;
        CHECK_MESSAGE((faded[x] == flat[x]) == inside, std::format("x {}: {:#010x}", x, faded[x]));
        if (!inside) CHECK((faded[x] >> 24U) < 0xFFU); // the transparent past the end lowers the alpha too
    }
    // An odd run is symmetric: the mirrored picture blurs into the mirrored blur.
    const std::vector<std::uint32_t> picture = random_texels(w, 5, false);
    std::vector<std::uint32_t> mirrored(picture.rbegin(), picture.rend());
    std::vector<std::uint32_t> a(w);
    std::vector<std::uint32_t> b(w);
    const fx::Run odd = fx::webkit_boxes(7);
    fx::blur_cpu(picture, a, w, {.x = 0, .y = 0, .width = w, .height = 1, .run = odd},
                 {.axis = fx::Axis::x, .rounding = fx::Rounding::nearest}, fx::BlurFormat::rgba8);
    fx::blur_cpu(mirrored, b, w, {.x = 0, .y = 0, .width = w, .height = 1, .run = odd},
                 {.axis = fx::Axis::x, .rounding = fx::Rounding::nearest}, fx::BlurFormat::rgba8);
    std::ranges::reverse(b);
    CHECK(a == b);
}

#if !defined(__ANDROID__)

namespace {

/// One dispatch of the GPU test: its rectangles (apart from one another) and its settings.
struct Dispatch {
    std::vector<fx::BlurRect> rects;
    fx::BlurPass pass;
    std::string name;
};

/// The GPU blur of `dispatches` over a w × h image of `format`: each from the same random source into a target first
/// filled with a sentinel; the targets read back, packed as the CPU blur reads them.
struct GpuRun {
    std::vector<std::vector<std::uint32_t>> targets;
    std::vector<std::string> messages;
};

[[nodiscard]] std::optional<GpuRun> run_on_gpu(test::Session& session, fx::BlurFormat format, std::uint32_t w,
                                               std::uint32_t h, std::span<const std::uint32_t> source,
                                               std::uint32_t sentinel, std::span<const Dispatch> dispatches) {
    const gpu::Device& device = *session.device;
    const vk::raii::Device& d = device.handle();
    auto cache = gpu::PipelineCache::open(d, device.facts(), {}, 0, false);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, cache.has_value(), cache.error().text());
    auto blur = fx::BoxBlur::create(device, *cache, nullptr, format, 2);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, blur.has_value(), blur.error().text());
    const bool r8 = format == fx::BlurFormat::r8;
    const vk::Format vk_format = r8 ? vk::Format::eR8Unorm : vk::Format::eR8G8B8A8Unorm;
    const std::uint32_t texel = r8 ? 1 : 4;
    using U = vk::ImageUsageFlagBits;
    auto image = [&]() {
        return device.memory().image({.imageType = vk::ImageType::e2D,
                                      .format = vk_format,
                                      .extent = {.width = w, .height = h, .depth = 1},
                                      .mipLevels = 1,
                                      .arrayLayers = 1,
                                      .samples = vk::SampleCountFlagBits::e1,
                                      .tiling = vk::ImageTiling::eOptimal,
                                      .usage = U::eStorage | U::eTransferSrc | U::eTransferDst,
                                      .initialLayout = vk::ImageLayout::eUndefined},
                                     gpu::MemoryUse::gpu);
    };
    auto src = image();
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, src.has_value(), src.error().text());
    auto dst = image();
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, dst.has_value(), dst.error().text());
    auto view_of = [&](const gpu::Image& i) {
        return d.createImageView(
            {.image = i.handle(),
             .viewType = vk::ImageViewType::e2D,
             .format = vk_format,
             .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1}});
    };
    auto src_view = view_of(*src);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, src_view.has_value(), vk::to_string(src_view.error()));
    auto dst_view = view_of(*dst);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, dst_view.has_value(), vk::to_string(dst_view.error()));
    const vk::DeviceSize bytes = vk::DeviceSize{w} * h * texel;
    auto upload = device.memory().buffer(bytes, vk::BufferUsageFlagBits::eTransferSrc, gpu::MemoryUse::upload);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, upload.has_value(), upload.error().text());
    auto readback = device.memory().buffer(bytes, vk::BufferUsageFlagBits::eTransferDst, gpu::MemoryUse::readback);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, readback.has_value(), readback.error().text());
    auto jobs = device.memory().buffer(fx::BoxBlur::max_rects * fx::BoxBlur::job_bytes,
                                       vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, jobs.has_value(), jobs.error().text());
    auto set = (*blur)->bind(**src_view, **dst_view, jobs->handle());
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, set.has_value(), set.error().text());
    // The source, packed as the image holds it.
    for (std::size_t i = 0; i < source.size(); ++i) {
        if (r8) {
            upload->bytes()[i] = static_cast<std::byte>(source[i] & 0xFFU);
        } else {
            std::memcpy(upload->bytes().data() + (i * 4), &source[i], 4);
        }
    }
    upload->flush();
    const std::array<float, 4> fill{
        static_cast<float>(sentinel & 0xFFU) / 255.0F, static_cast<float>((sentinel >> 8U) & 0xFFU) / 255.0F,
        static_cast<float>((sentinel >> 16U) & 0xFFU) / 255.0F, static_cast<float>(sentinel >> 24U) / 255.0F};
    auto pool = d.createCommandPool(
        {.flags = vk::CommandPoolCreateFlagBits::eTransient, .queueFamilyIndex = device.queue_family()});
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, pool.has_value(), vk::to_string(pool.error()));
    const gpu::Sync sync(device.enabled().synchronization2);
    GpuRun out;
    const vk::ImageSubresourceRange range{
        .aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1};
    const vk::BufferImageCopy whole{
        .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
        .imageExtent = {.width = w, .height = h, .depth = 1}};
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    using L = vk::ImageLayout;
    for (const Dispatch& dispatch : dispatches) {
        ATLAS_REQUIRE_OR_RETURN(std::nullopt,
                                fx::BoxBlur::write_jobs(jobs->bytes(), dispatch.rects, dispatch.pass.rounding),
                                dispatch.name << ": the jobs were refused");
        jobs->flush();
        auto commands = d.allocateCommandBuffers(
            {.commandPool = **pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, commands.has_value(), vk::to_string(commands.error()));
        const vk::raii::CommandBuffer& cmd = commands->front();
        ATLAS_REQUIRE_OR_RETURN(std::nullopt,
                                cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}).has_value(),
                                "the commands' begin");
        const std::array<gpu::ImageDependency, 2> to_fill{{
            {.image = src->handle(),
             .src_stage = S::eComputeShader | S::eTransfer,
             .src_access = {},
             .dst_stage = S::eTransfer,
             .dst_access = A::eTransferWrite,
             .old_layout = L::eUndefined,
             .new_layout = L::eTransferDstOptimal},
            {.image = dst->handle(),
             .src_stage = S::eComputeShader | S::eTransfer,
             .src_access = {},
             .dst_stage = S::eTransfer,
             .dst_access = A::eTransferWrite,
             .old_layout = L::eUndefined,
             .new_layout = L::eTransferDstOptimal},
        }};
        sync.barrier(cmd, {}, to_fill);
        cmd.copyBufferToImage(upload->handle(), src->handle(), L::eTransferDstOptimal, whole);
        cmd.clearColorImage(dst->handle(), L::eTransferDstOptimal, vk::ClearColorValue{fill}, range);
        const std::array<gpu::ImageDependency, 2> to_blur{{
            {.image = src->handle(),
             .src_stage = S::eTransfer,
             .src_access = A::eTransferWrite,
             .dst_stage = S::eComputeShader,
             .dst_access = A::eShaderRead,
             .old_layout = L::eTransferDstOptimal,
             .new_layout = L::eGeneral},
            {.image = dst->handle(),
             .src_stage = S::eTransfer,
             .src_access = A::eTransferWrite,
             .dst_stage = S::eComputeShader,
             .dst_access = A::eShaderWrite,
             .old_layout = L::eTransferDstOptimal,
             .new_layout = L::eGeneral},
        }};
        sync.barrier(cmd, {}, to_blur);
        (*blur)->record(cmd, **set, 0, dispatch.rects, dispatch.pass);
        sync.barrier(cmd, gpu::ImageDependency{.image = dst->handle(),
                                               .src_stage = S::eComputeShader,
                                               .src_access = A::eShaderWrite,
                                               .dst_stage = S::eTransfer,
                                               .dst_access = A::eTransferRead,
                                               .old_layout = L::eGeneral,
                                               .new_layout = L::eTransferSrcOptimal});
        cmd.copyImageToBuffer(dst->handle(), L::eTransferSrcOptimal, readback->handle(), whole);
        sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eTransfer,
                                                .src_access = A::eTransferWrite,
                                                .dst_stage = S::eHost,
                                                .dst_access = A::eHostRead});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, cmd.end().has_value(), "the commands' end");
        auto fence = d.createFence({});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, fence.has_value(), vk::to_string(fence.error()));
        const vk::CommandBuffer raw = *cmd;
        ATLAS_REQUIRE_OR_RETURN(std::nullopt,
                                device.queue()
                                    .submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, **fence)
                                    .has_value(),
                                "the submit");
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, d.waitForFences(**fence, vk::True, UINT64_MAX) == vk::Result::eSuccess,
                                "the fence");
        readback->invalidate();
        std::vector<std::uint32_t> target(std::size_t{w} * h);
        for (std::size_t i = 0; i < target.size(); ++i) {
            if (r8) {
                target[i] = static_cast<std::uint32_t>(readback->bytes()[i]);
            } else {
                std::memcpy(&target[i], readback->bytes().data() + (i * 4), 4);
            }
        }
        out.targets.push_back(std::move(target));
        for (std::string& m : session.take_messages()) out.messages.push_back(dispatch.name + ": " + m);
    }
    device.wait_idle();
    return out;
}

} // namespace

TEST_CASE("the GPU's box blurs equal the CPU's bit for bit, along both axes, in every mode, for RGBA8 and R8") {
    auto session = test::open_session();
    if (!session) return;
    constexpr std::uint32_t w = 300;
    constexpr std::uint32_t h = 210;
    using B = fx::Box;
    // Runs: WebKit's for a few σ (odd and even sizes), one plain box, none (a copy), lopsided boxes, the longest
    // reach a dispatch takes on each side.
    const std::array<fx::Run, 7> runs{
        fx::webkit_run(1.5F),
        fx::webkit_run(14.4F),
        fx::webkit_run(13.6F),
        fx::Run{.boxes = {B{.left = 0, .right = 1}}, .count = 1},
        fx::Run{},
        fx::Run{.boxes = {B{.left = 5, .right = 2}, B{.left = 0, .right = 9}}, .count = 2},
        fx::Run{.boxes = {B{.left = 42, .right = 43}, B{.left = 43, .right = 43}, B{.left = 43, .right = 43}},
                .count = 3},
    };
    // Rectangles apart from one another: longer than a workgroup's 256 texels, short, single texels, at the edges.
    auto rects_of = [&](const fx::Run& run) {
        return std::vector<fx::BlurRect>{
            {.x = 0, .y = 0, .width = w, .height = 9, .run = run},
            {.x = 3, .y = 12, .width = 290, .height = 31, .run = run},
            {.x = 10, .y = 50, .width = 17, .height = 120, .run = run},
            {.x = 40, .y = 60, .width = 1, .height = 1, .run = run},
            {.x = 60, .y = 180, .width = 240, .height = 30, .run = run},
        };
    };
    for (const fx::BlurFormat format : {fx::BlurFormat::rgba8, fx::BlurFormat::r8}) {
        const bool r8 = format == fx::BlurFormat::r8;
        const std::vector<std::uint32_t> source = random_texels(std::size_t{w} * h, r8 ? 3 : 2, r8);
        const std::uint32_t sentinel = r8 ? 0x5AU : 0x5A3C7E11U;
        std::vector<Dispatch> dispatches;
        for (std::size_t r = 0; r < runs.size(); ++r) {
            for (const fx::Axis axis : {fx::Axis::x, fx::Axis::y}) {
                for (const fx::Rounding rounding : {fx::Rounding::nearest, fx::Rounding::truncate}) {
                    for (const fx::Edge edge : {fx::Edge::transparent, fx::Edge::extend}) {
                        const bool opaque = !r8 && rounding == fx::Rounding::nearest && edge == fx::Edge::transparent;
                        dispatches.push_back(
                            {.rects = rects_of(runs[r]),
                             .pass = {.axis = axis, .rounding = rounding, .edge = edge, .opaque = opaque},
                             .name = std::format("{} run {} along {}, {}, {}{}", r8 ? "r8" : "rgba8", r,
                                                 axis == fx::Axis::x ? "x" : "y",
                                                 rounding == fx::Rounding::nearest ? "nearest" : "truncated",
                                                 edge == fx::Edge::extend ? "extended" : "transparent",
                                                 opaque ? ", opaque" : "")});
                    }
                }
            }
        }
        const auto gpu_run = run_on_gpu(*session, format, w, h, source, sentinel, dispatches);
        ATLAS_REQUIRE(gpu_run.has_value());
        for (const std::string& m : gpu_run->messages) CHECK_MESSAGE(false, m);
        std::size_t checked = 0;
        for (std::size_t i = 0; i < dispatches.size(); ++i) {
            std::vector<std::uint32_t> want(source.size(), sentinel);
            for (const fx::BlurRect& rect : dispatches[i].rects) {
                fx::blur_cpu(source, want, w, rect, dispatches[i].pass, format);
            }
            const std::vector<std::uint32_t>& got = gpu_run->targets[i];
            std::size_t wrong = 0;
            std::string first;
            for (std::size_t t = 0; t < want.size(); ++t) {
                if (got[t] == want[t]) continue;
                if (wrong++ == 0) {
                    first = std::format("texel ({}, {}): {:#010x} where the CPU has {:#010x}", t % w, t / w, got[t],
                                        want[t]);
                }
            }
            CHECK_MESSAGE(wrong == 0, dispatches[i].name << ": " << wrong << " texels differ; the first: " << first);
            checked += want.size();
        }
        MESSAGE(std::string(r8 ? "r8" : "rgba8")
                << ": " << dispatches.size() << " dispatches, " << checked << " texels as the CPU computes them");
    }
}

#endif
