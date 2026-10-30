// VK facts' blur check (passes/BlurCheck.hpp).
#include "passes/BlurCheck.hpp"

#include "fx/Blur.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/ground/blur_check.hpp"
#include "gen/spirv/ground/ground.hpp"
#include "gen/spirv/ground/shadow_blur.hpp"
#include "gen/spirv/ground/shadow_blur_poison.hpp"
#include "gpu/Descriptors.hpp"
#include "gpu/Pipelines.hpp"
#include "gpu/Sync.hpp"
#include "passes/Ground.hpp"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <format>
#include <utility>

namespace atlas::passes {

namespace {

using CheckPush = layouts::ground_blur_check::CheckPush;
using BlurPush = layouts::ground_shadow_blur::BlurPush;

/// The blur's workgroups' blocks, texels a side (shadow_blur.slang's TILE).
constexpr std::uint32_t block_side = 32;
/// The blurred texels' words a row: four texels a word (shadow_blur.slang's push.stride).
constexpr std::uint32_t stride = (blur_check_side + 3) / 4;
/// What a texel the rectangles cover holds once its four samples are resolved (mask_fs's 252 of 255 each).
constexpr std::uint8_t resolved = 252;
/// The blur's passes (vImage's three boxes).
constexpr std::uint32_t passes = 3;

/// The pattern: rectangles that leave the blocks at the top left empty, cover whole blocks with edges on theirs, cut
/// blocks at odd places, run along a single texel, and touch the mask's right and bottom edges, whose blocks are cut
/// short (1,000 is no whole number of 32).
constexpr std::array<std::array<std::int32_t, 4>, 14> pattern{{
    {320, 64, 640, 384},    // whole blocks, edges on theirs
    {700, 100, 757, 333},   // edges inside blocks
    {101, 500, 389, 517},   // a band through a row of blocks
    {450, 450, 451, 900},   // a single texel's column
    {600, 600, 1000, 1000}, // to the right and bottom edges
    {37, 700, 40, 703},     // three texels square
    {990, 10, 1000, 20},    // at the right edge
    {0, 990, 10, 1000},     // at the bottom left
    {256, 256, 288, 288},   // one block whole
    {511, 31, 513, 33},     // across four blocks' corner
    {160, 800, 330, 805},
    {800, 420, 806, 560},
    {12, 400, 90, 470},
    {880, 880, 960, 960},
}};

[[nodiscard]] std::size_t texel(std::uint32_t x, std::uint32_t y) noexcept {
    return (static_cast<std::size_t>(y) * blur_check_side) + x;
}

/// The pattern's mask, `covered` a texel the rectangles cover and 0 elsewhere.
[[nodiscard]] std::vector<std::uint8_t> mask_of(std::uint8_t covered) {
    std::vector<std::uint8_t> m(static_cast<std::size_t>(blur_check_side) * blur_check_side, 0);
    for (const auto& r : pattern) {
        for (std::int32_t y = r[1]; y < r[3]; ++y) {
            for (std::int32_t x = r[0]; x < r[2]; ++x) {
                m[texel(static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y))] = covered;
            }
        }
    }
    return m;
}

} // namespace

BlurCompare compare_blocks(std::span<const std::uint8_t> got, std::span<const std::uint8_t> want,
                           std::uint32_t side) noexcept {
    BlurCompare c;
    const std::uint32_t blocks = (side + block_side - 1) / block_side;
    for (std::uint32_t by = 0; by < blocks; ++by) {
        for (std::uint32_t bx = 0; bx < blocks; ++bx) {
            std::uint32_t differ = 0;
            bool empty = true;
            for (std::uint32_t y = by * block_side; y < std::min(side, (by + 1) * block_side); ++y) {
                for (std::uint32_t x = bx * block_side; x < std::min(side, (bx + 1) * block_side); ++x) {
                    const std::size_t i = (static_cast<std::size_t>(y) * side) + x;
                    empty = empty && want[i] == 0;
                    if (got[i] == want[i]) continue;
                    const auto d = static_cast<std::uint32_t>(std::abs(int{got[i]} - int{want[i]}));
                    if (c.texels == 0) {
                        c.first_x = x;
                        c.first_y = y;
                    }
                    ++c.texels;
                    ++differ;
                    c.most = std::max(c.most, d);
                    const std::uint32_t u = x % block_side;
                    const std::uint32_t v = y % block_side;
                    if (u == 0 || v == 0 || u == block_side - 1 || v == block_side - 1) ++c.edge;
                }
            }
            if (differ == 0) continue;
            ++c.blocks;
            if (empty) ++c.empty;
        }
    }
    return c;
}

bool BlurCheck::exact() const noexcept {
    return mask.exact() && cleared.exact() &&
           std::ranges::all_of(runs, [](const BlurRun& r) { return r.blur.exact(); });
}

std::span<const std::array<std::int32_t, 4>> blur_check_pattern() noexcept { return pattern; }

gpu::Result<BlurCheck> blur_check(const gpu::Device& device, const gpu::PipelineCache& cache, bool poisoned) {
    const vk::raii::Device& d = device.handle();
    const vk::raii::PipelineCache& pc = cache.handle();
    using U = vk::ImageUsageFlagBits;
    using B = vk::BufferUsageFlagBits;
    BlurCheck out{.lazy = device.facts().choice.lazy()};
    const vk::Extent2D extent{.width = blur_check_side, .height = blur_check_side};
    auto image = [&](vk::SampleCountFlagBits samples, vk::ImageUsageFlags usage, gpu::MemoryUse use) {
        return device.memory().image({.imageType = vk::ImageType::e2D,
                                      .format = vk::Format::eR8Unorm,
                                      .extent = {.width = extent.width, .height = extent.height, .depth = 1},
                                      .mipLevels = 1,
                                      .arrayLayers = 1,
                                      .samples = samples,
                                      .tiling = vk::ImageTiling::eOptimal,
                                      .usage = usage,
                                      .initialLayout = vk::ImageLayout::eUndefined},
                                     use);
    };
    auto view_of = [&](const gpu::Image& i) -> gpu::Result<vk::raii::ImageView> {
        ATLAS_VK_LET(v,
                     d.createImageView({.image = i.handle(),
                                        .viewType = vk::ImageViewType::e2D,
                                        .format = vk::Format::eR8Unorm,
                                        .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                             .levelCount = 1,
                                                             .layerCount = 1}}),
                     "the blur check's view");
        return std::move(v);
    };
    // the mask's samples as the frame keeps them: lazily allocated where its are
    const vk::ImageUsageFlags transient = U::eColorAttachment | U::eTransientAttachment;
    auto samples = image(vk::SampleCountFlagBits::e4, transient, out.lazy ? gpu::MemoryUse::lazy : gpu::MemoryUse::gpu);
    if (!samples && out.lazy) samples = image(vk::SampleCountFlagBits::e4, transient, gpu::MemoryUse::gpu);
    if (!samples) return std::unexpected(samples.error());
    ATLAS_GPU_LET(samples_view, view_of(*samples));
    ATLAS_GPU_LET(mask,
                  image(vk::SampleCountFlagBits::e1,
                        U::eColorAttachment | U::eSampled | U::eTransferSrc | U::eTransferDst, gpu::MemoryUse::gpu));
    ATLAS_GPU_LET(mask_view, view_of(mask));
    ATLAS_GPU_LET(pass, soft_mask_pass(d, false));
    ATLAS_GPU_LET(pass_cleared, soft_mask_pass(d, true));
    const std::array<vk::ImageView, 2> views{*samples_view, *mask_view};
    ATLAS_VK_LET(framebuffer,
                 d.createFramebuffer({.renderPass = *pass,
                                      .attachmentCount = static_cast<std::uint32_t>(views.size()),
                                      .pAttachments = views.data(),
                                      .width = extent.width,
                                      .height = extent.height,
                                      .layers = 1}),
                 "the blur check's framebuffer");

    // the check's own module (the rectangles, the grid) and the frame's (mask_fs, the blur)
    ATLAS_GPU_LET(check_module, gpu::make_module(d, spirv::ground_blur_check));
    ATLAS_GPU_LET(ground_module, gpu::make_module(d, spirv::ground_ground));
    ATLAS_GPU_LET(blur_module,
                  gpu::make_module(d, poisoned ? std::span<const std::uint32_t>(spirv::ground_shadow_blur_poison)
                                               : std::span<const std::uint32_t>(spirv::ground_shadow_blur)));
    constexpr auto in_vertex = vk::ShaderStageFlagBits::eVertex;
    constexpr auto in_compute = vk::ShaderStageFlagBits::eCompute;
    const std::array check_bindings{
        gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = in_vertex},
        gpu::Binding{.slot = 1, .type = vk::DescriptorType::eStorageBuffer, .stages = in_compute}};
    ATLAS_GPU_LET(check_set_layout, gpu::make_set_layout(d, check_bindings));
    const std::array<vk::DescriptorSetLayout, 1> check_sets{*check_set_layout};
    ATLAS_GPU_LET(check_layout, gpu::make_layout(d, check_sets, sizeof(CheckPush), in_vertex | in_compute));
    const std::array<gpu::Binding, 4> blur_bindings = soft_blur_bindings();
    ATLAS_GPU_LET(blur_set_layout, gpu::make_set_layout(d, blur_bindings));
    const std::array<vk::DescriptorSetLayout, 1> blur_sets{*blur_set_layout};
    ATLAS_GPU_LET(blur_layout, gpu::make_layout(d, blur_sets, sizeof(BlurPush), in_compute));
    gpu::GraphicsDesc rects{.label = {.name = "blur check rectangles", .module = "ground_blur_check"},
                            .module = *check_module,
                            .vertex = "rect_vs",
                            .fragment = "mask_fs",
                            .fragment_module = *ground_module,
                            .layout = *check_layout};
    rects.target = {.render_pass = *pass, .subpass = 0, .samples = vk::SampleCountFlagBits::e4, .colour_count = 1};
    rects.blends[0] = gpu::Blend::opaque();
    ATLAS_GPU_LET(rects_pipeline, gpu::make_graphics(d, pc, rects));
    ATLAS_GPU_LET(grid_pipeline, gpu::make_compute(d, pc, {.name = "blur check grid", .module = "ground_blur_check"},
                                                   *check_module, "check_grid_cs", *check_layout));
    ATLAS_GPU_LET(blur_pipeline, gpu::make_compute(d, pc, {.name = "blur check blur", .module = "ground_shadow_blur"},
                                                   *blur_module, "blur_cs", *blur_layout));

    // the buffers: the rectangles, the uploaded mask, and each run's grid and texels
    const std::size_t texels = static_cast<std::size_t>(blur_check_side) * blur_check_side;
    const vk::DeviceSize words_bytes = vk::DeviceSize{stride} * 4 * blur_check_side;
    ATLAS_GPU_LET(rect_buffer, device.memory().buffer(sizeof pattern, B::eStorageBuffer, gpu::MemoryUse::upload));
    std::memcpy(rect_buffer.bytes().data(), pattern.data(), sizeof pattern);
    rect_buffer.flush();
    ATLAS_GPU_LET(upload, device.memory().buffer(texels, B::eTransferSrc, gpu::MemoryUse::upload));
    const std::vector<std::uint8_t> drawn = mask_of(resolved);
    std::memcpy(upload.bytes().data(), drawn.data(), texels);
    upload.flush();
    struct Run {
        bool drawn = false;
        bool cleared = false;
        std::uint32_t box = 0;
        gpu::Buffer grid;
        std::array<gpu::Buffer, 2> blurred;
        gpu::Buffer mask_copy;
        vk::raii::DescriptorSet check_set{nullptr};
        std::array<vk::raii::DescriptorSet, passes> blur_set{
            vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    };
    // uploaded and drawn at each box, then drawn cleared first at the widest; the pools before the runs' sets, which
    // go back to them first
    constexpr std::uint32_t run_count = (2 * blur_check_boxes.size()) + 1;
    const std::array<std::span<const gpu::Binding>, 1> check_kinds{check_bindings};
    ATLAS_GPU_LET(check_pool, gpu::make_pool(d, check_kinds, run_count));
    const std::array<std::span<const gpu::Binding>, 1> blur_kinds{blur_bindings};
    ATLAS_GPU_LET(blur_pool, gpu::make_pool(d, blur_kinds, run_count * passes));
    std::vector<Run> runs;
    for (const bool draw : {false, true}) {
        for (const std::uint32_t box : blur_check_boxes) runs.push_back({.drawn = draw, .box = box});
    }
    runs.push_back({.drawn = true, .cleared = true, .box = blur_check_boxes.back()});
    for (Run& r : runs) {
        ATLAS_GPU_LET(grid, device.memory().buffer(96, B::eStorageBuffer, gpu::MemoryUse::gpu));
        r.grid = std::move(grid);
        ATLAS_GPU_LET(first, device.memory().buffer(words_bytes, B::eStorageBuffer, gpu::MemoryUse::readback));
        r.blurred[0] = std::move(first);
        ATLAS_GPU_LET(second, device.memory().buffer(words_bytes, B::eStorageBuffer, gpu::MemoryUse::gpu));
        r.blurred[1] = std::move(second);
        if (r.drawn) {
            ATLAS_GPU_LET(copy, device.memory().buffer(texels, B::eTransferDst, gpu::MemoryUse::readback));
            r.mask_copy = std::move(copy);
        }
        ATLAS_GPU_LET(set, gpu::allocate_set(d, check_pool, check_set_layout));
        gpu::SetWriter(*set)
            .buffer(0, vk::DescriptorType::eStorageBuffer, rect_buffer.handle())
            .buffer(1, vk::DescriptorType::eStorageBuffer, r.grid.handle())
            .apply(d);
        r.check_set = std::move(set);
        for (std::uint32_t i = 0; i < passes; ++i) {
            // the first pass writes the first buffer, the second the other, the third the first again (Shadows.cpp)
            const std::size_t target = i % 2 == 0 ? 0 : 1;
            ATLAS_GPU_LET(blur_set, gpu::allocate_set(d, blur_pool, blur_set_layout));
            gpu::SetWriter(*blur_set)
                .buffer(0, vk::DescriptorType::eStorageBuffer, r.grid.handle())
                .image(1, vk::DescriptorType::eSampledImage, *mask_view, vk::ImageLayout::eShaderReadOnlyOptimal)
                .buffer(2, vk::DescriptorType::eStorageBuffer, r.blurred.at(1 - target).handle())
                .buffer(3, vk::DescriptorType::eStorageBuffer, r.blurred.at(target).handle())
                .apply(d);
            r.blur_set.at(i) = std::move(blur_set);
        }
    }

    // one submission: each run the grid, its mask (uploaded, or drawn and resolved), the three passes
    ATLAS_VK_LET(pool,
                 d.createCommandPool(
                     {.flags = vk::CommandPoolCreateFlagBits::eTransient, .queueFamilyIndex = device.queue_family()}),
                 "the blur check's command pool");
    ATLAS_VK_LET(commands,
                 d.allocateCommandBuffers(
                     {.commandPool = *pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1}),
                 "the blur check's command buffer");
    const vk::raii::CommandBuffer& cmd = commands.front();
    const gpu::Sync sync(device.enabled().synchronization2);
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    using L = vk::ImageLayout;
    ATLAS_VK_CHECK(cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit}), "the blur check's commands");
    const auto all_before = gpu::MemoryDependency{.src_stage = S::eAllCommands,
                                                  .src_access = A::eMemoryWrite,
                                                  .dst_stage = S::eAllCommands,
                                                  .dst_access = A::eMemoryRead | A::eMemoryWrite};
    for (Run& r : runs) {
        // the grid
        const CheckPush push{.side = blur_check_side, .box = r.box, .pad0 = 0, .pad1 = 0};
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *grid_pipeline);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *check_layout, 0, *r.check_set, {});
        cmd.pushConstants<CheckPush>(*check_layout, in_vertex | in_compute, 0, push);
        cmd.dispatch(1, 1, 1);
        // the mask: the last run's reads of it done first, what it held not kept
        sync.barrier(cmd, all_before);
        if (!r.drawn) {
            sync.barrier(cmd, gpu::ImageDependency{.image = mask.handle(),
                                                   .src_stage = S::eComputeShader | S::eTransfer,
                                                   .src_access = {},
                                                   .dst_stage = S::eTransfer,
                                                   .dst_access = A::eTransferWrite,
                                                   .old_layout = L::eUndefined,
                                                   .new_layout = L::eTransferDstOptimal});
            cmd.copyBufferToImage(
                upload.handle(), mask.handle(), L::eTransferDstOptimal,
                vk::BufferImageCopy{
                    .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
                    .imageExtent = {.width = extent.width, .height = extent.height, .depth = 1}});
            sync.barrier(cmd, gpu::ImageDependency{.image = mask.handle(),
                                                   .src_stage = S::eTransfer,
                                                   .src_access = A::eTransferWrite,
                                                   .dst_stage = S::eComputeShader,
                                                   .dst_access = A::eShaderRead,
                                                   .old_layout = L::eTransferDstOptimal,
                                                   .new_layout = L::eShaderReadOnlyOptimal});
        } else {
            // as Shadows.cpp draws the frame's: the samples and the mask into the colour layout, the pass, the mask out
            const std::array<gpu::ImageDependency, 2> to_draw{{
                {.image = samples->handle(),
                 .src_stage = S::eColorAttachmentOutput,
                 .src_access = {},
                 .dst_stage = S::eColorAttachmentOutput,
                 .dst_access = A::eColorAttachmentWrite,
                 .old_layout = L::eUndefined,
                 .new_layout = L::eColorAttachmentOptimal},
                {.image = mask.handle(),
                 .src_stage = S::eTopOfPipe,
                 .src_access = {},
                 .dst_stage = S::eColorAttachmentOutput,
                 .dst_access = A::eColorAttachmentWrite,
                 .old_layout = L::eUndefined,
                 .new_layout = L::eColorAttachmentOptimal},
            }};
            sync.barrier(cmd, {}, to_draw);
            const vk::ClearValue zero{.color = vk::ClearColorValue{std::array<float, 4>{0.0F, 0.0F, 0.0F, 0.0F}}};
            const std::array<vk::ClearValue, 2> clear{zero, zero};
            cmd.beginRenderPass({.renderPass = r.cleared ? *pass_cleared : *pass,
                                 .framebuffer = *framebuffer,
                                 .renderArea = {.offset = {.x = 0, .y = 0}, .extent = extent},
                                 .clearValueCount = r.cleared ? 2U : 1U,
                                 .pClearValues = clear.data()},
                                vk::SubpassContents::eInline);
            cmd.setViewport(0, vk::Viewport{.x = 0,
                                            .y = 0,
                                            .width = static_cast<float>(extent.width),
                                            .height = static_cast<float>(extent.height),
                                            .minDepth = 0,
                                            .maxDepth = 1});
            cmd.setScissor(0, vk::Rect2D{.offset = {.x = 0, .y = 0}, .extent = extent});
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *rects_pipeline);
            cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *check_layout, 0, *r.check_set, {});
            cmd.pushConstants<CheckPush>(*check_layout, in_vertex | in_compute, 0, push);
            cmd.draw(6, static_cast<std::uint32_t>(pattern.size()), 0, 0);
            cmd.endRenderPass();
            sync.barrier(cmd, gpu::ImageDependency{.image = mask.handle(),
                                                   .src_stage = S::eColorAttachmentOutput,
                                                   .src_access = A::eColorAttachmentWrite,
                                                   .dst_stage = S::eComputeShader,
                                                   .dst_access = A::eShaderRead,
                                                   .old_layout = L::eColorAttachmentOptimal,
                                                   .new_layout = L::eShaderReadOnlyOptimal});
        }
        // the blur's three passes, as Shadows.cpp records them
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *blur_pipeline);
        const std::uint32_t groups = (blur_check_side + block_side - 1) / block_side;
        for (std::uint32_t i = 0; i < passes; ++i) {
            if (i > 0) {
                sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                        .src_access = A::eShaderWrite,
                                                        .dst_stage = S::eComputeShader,
                                                        .dst_access = A::eShaderRead | A::eShaderWrite});
            }
            cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *blur_layout, 0, *r.blur_set.at(i), {});
            const BlurPush bp{
                .first = i == 0 ? 1U : 0U, .last = i + 1 == passes ? 1U : 0U, .stride = stride, .pad = 0U};
            cmd.pushConstants<BlurPush>(*blur_layout, in_compute, 0, bp);
            cmd.dispatch(groups, groups, 1);
        }
        if (r.drawn) {
            sync.barrier(cmd, gpu::ImageDependency{.image = mask.handle(),
                                                   .src_stage = S::eComputeShader,
                                                   .src_access = {},
                                                   .dst_stage = S::eTransfer,
                                                   .dst_access = A::eTransferRead,
                                                   .old_layout = L::eShaderReadOnlyOptimal,
                                                   .new_layout = L::eTransferSrcOptimal});
            cmd.copyImageToBuffer(
                mask.handle(), L::eTransferSrcOptimal, r.mask_copy.handle(),
                vk::BufferImageCopy{
                    .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
                    .imageExtent = {.width = extent.width, .height = extent.height, .depth = 1}});
        }
        sync.barrier(cmd, all_before);
    }
    sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader | S::eTransfer,
                                            .src_access = A::eShaderWrite | A::eTransferWrite,
                                            .dst_stage = S::eHost,
                                            .dst_access = A::eHostRead});
    ATLAS_VK_CHECK(cmd.end(), "the blur check's commands");
    ATLAS_VK_LET(fence, d.createFence({}), "the blur check's fence");
    const vk::CommandBuffer raw = *cmd;
    ATLAS_VK_CHECK(device.queue().submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, *fence),
                   "the blur check");
    if (const vk::Result w = d.waitForFences(*fence, vk::True, UINT64_MAX); w != vk::Result::eSuccess) {
        return gpu::fail(w, "the blur check");
    }

    // each against the CPU's
    std::vector<std::uint8_t> decoded = mask_of(255); // the first pass's reading of 252: four samples of four
    std::vector<std::uint8_t> got(texels);
    bool compared = false;
    for (Run& r : runs) {
        if (r.drawn && (r.cleared || !compared)) {
            // the first mask the frame's pass drew, and the one drawn cleared
            r.mask_copy.invalidate();
            std::memcpy(got.data(), r.mask_copy.bytes().data(), texels);
            (r.cleared ? out.cleared : out.mask) = compare_blocks(got, drawn, blur_check_side);
            compared = compared || !r.cleared;
        }
        std::vector<std::uint8_t> want = decoded;
        fx::square_blur_cpu(want, blur_check_side, blur_check_side, r.box, passes);
        r.blurred[0].invalidate();
        const std::span<const std::byte> words = r.blurred[0].bytes();
        for (std::uint32_t y = 0; y < blur_check_side; ++y) {
            for (std::uint32_t x = 0; x < blur_check_side; ++x) {
                got[texel(x, y)] = static_cast<std::uint8_t>(words[(static_cast<std::size_t>(y) * stride * 4) + x]);
            }
        }
        out.runs.push_back(
            {.drawn = r.drawn, .cleared = r.cleared, .box = r.box, .blur = compare_blocks(got, want, blur_check_side)});
    }
    return out;
}

std::string blur_line(const BlurCheck& c) {
    const std::string what = std::format("{} × {} texels, boxes {} and {}, the mask uploaded, drawn through the "
                                         "frame's 4x pass{} and drawn cleared first",
                                         blur_check_side, blur_check_side, blur_check_boxes[0], blur_check_boxes[1],
                                         c.lazy ? " in lazily allocated memory" : "");
    if (c.exact()) return std::format("shadow.blur: exact ({})", what);
    const auto named = [](const BlurCompare& d) {
        return std::format("{} texels in {} blocks ({} with none of the pattern in reach), {} on a block's edge, up to "
                           "{} levels, the first at {},{}",
                           d.texels, d.blocks, d.empty, d.edge, d.most, d.first_x, d.first_y);
    };
    std::string line = "shadow.blur: differs (" + what + ")";
    line += c.mask.exact() ? "; the drawn mask as drawn" : "; the drawn mask: " + named(c.mask);
    line += c.cleared.exact() ? "; cleared first, as drawn" : "; cleared first: " + named(c.cleared);
    for (const BlurRun& r : c.runs) {
        line += std::format("; box {} {}: ", r.box, r.cleared ? "drawn cleared" : (r.drawn ? "drawn" : "uploaded"));
        line += r.blur.exact() ? "exact" : named(r.blur);
    }
    return line;
}

std::string blur_line(const gpu::Device& device, const gpu::PipelineCache& cache) {
    const auto check = blur_check(device, cache);
    if (!check) return std::format("shadow.blur: unavailable ({})", check.error().text());
    return blur_line(*check);
}

} // namespace atlas::passes
