// The frosted backdrops (passes/Frosted.hpp).
#include "passes/Frosted.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/frosted/glass.hpp"
#include "gpu/Descriptors.hpp"
#include "view/Camera.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <format>
#include <string>

namespace atlas::passes {

namespace {

using GlassPush = layouts::frosted_glass::GlassPush;
using clock = std::chrono::steady_clock;

/// Texels kept past a run's reach around a shape, so that the glass's linear reads at its edge stay in its region.
constexpr std::int64_t region_pad = 2;
/// How much larger than the boxes of the shapes it draws a render pass's box may be.
constexpr double group_waste = 1.25;
/// The coarsest block of the copy (px).
constexpr std::uint32_t coarsest_block = 64;

/// A rectangle [x0, x1) × [y0, y1) of blocks or pixels.
struct Rect {
    std::int64_t x0 = 0;
    std::int64_t y0 = 0;
    std::int64_t x1 = 0;
    std::int64_t y1 = 0;

    [[nodiscard]] std::int64_t width() const noexcept { return x1 - x0; }
    [[nodiscard]] std::int64_t height() const noexcept { return y1 - y0; }
    [[nodiscard]] std::int64_t area() const noexcept { return width() * height(); }
    [[nodiscard]] bool empty() const noexcept { return x1 <= x0 || y1 <= y0; }
    /// Whether the two overlap or share an edge.
    [[nodiscard]] bool touches(const Rect& o) const noexcept {
        return x0 <= o.x1 && o.x0 <= x1 && y0 <= o.y1 && o.y0 <= y1;
    }
    [[nodiscard]] Rect united(const Rect& o) const noexcept {
        return {.x0 = std::min(x0, o.x0), .y0 = std::min(y0, o.y0), .x1 = std::max(x1, o.x1), .y1 = std::max(y1, o.y1)};
    }
};

/// A region being planned: its blocks, its block size and run, and whether it was merged into another.
struct Candidate {
    Rect blocks;
    std::uint32_t down = 4;
    fx::Run run;
    std::uint32_t shapes = 0;
    bool merged = false;
};

[[nodiscard]] std::int64_t floor_of(double v) noexcept { return static_cast<std::int64_t>(std::floor(v)); }
[[nodiscard]] std::int64_t ceil_of(double v) noexcept { return static_cast<std::int64_t>(std::ceil(v)); }

/// How many quarter turns a surface transform is (the frame's pre-rotation).
[[nodiscard]] std::uint32_t quarter_turns_of(vk::SurfaceTransformFlagBitsKHR transform) noexcept {
    using T = vk::SurfaceTransformFlagBitsKHR;
    switch (transform) {
    case T::eRotate90:
    case T::eHorizontalMirrorRotate90: return 1;
    case T::eRotate180:
    case T::eHorizontalMirrorRotate180: return 2;
    case T::eRotate270:
    case T::eHorizontalMirrorRotate270: return 3;
    default: return 0;
    }
}

/// The regions of the shapes at their blocks, merged where one σ's regions overlap with no waste: each shape's region
/// index in `region`. Shapes whose region would be empty (smaller than a block of the frame) get none (~0).
std::uint32_t build_regions(FrostPlan& plan, vk::Extent2D output, std::array<Candidate, style::max_keep_outs>& cand,
                            std::array<std::uint32_t, style::max_keep_outs>& region) {
    std::uint32_t n = 0;
    const auto w = static_cast<double>(output.width);
    const auto h = static_cast<double>(output.height);
    for (std::size_t i = 0; i < plan.shapes.size(); ++i) {
        const FrostShape& s = plan.shapes[i];
        region[i] = ~0U;
        const std::uint32_t down = s.down;
        const auto blocks_x = static_cast<std::int64_t>(output.width / down);
        const auto blocks_y = static_cast<std::int64_t>(output.height / down);
        const fx::Run run = fx::webkit_run(static_cast<float>(s.sigma / static_cast<double>(down)));
        const std::int64_t reach = static_cast<std::int64_t>(run.reach()) + region_pad;
        const auto d = static_cast<double>(down);
        Rect r{.x0 = floor_of(pymath::max(s.left, 0.0) / d) - reach,
               .y0 = floor_of(pymath::max(s.top, 0.0) / d) - reach,
               .x1 = ceil_of(pymath::min(s.right, w) / d) + reach,
               .y1 = ceil_of(pymath::min(s.bottom, h) / d) + reach};
        r.x0 = std::clamp<std::int64_t>(r.x0, 0, blocks_x);
        r.x1 = std::clamp<std::int64_t>(r.x1, 0, blocks_x);
        r.y0 = std::clamp<std::int64_t>(r.y0, 0, blocks_y);
        r.y1 = std::clamp<std::int64_t>(r.y1, 0, blocks_y);
        if (r.empty()) continue;
        cand[n] = {.blocks = r, .down = down, .run = run, .shapes = 1, .merged = false};
        region[i] = n++;
    }
    // Merge one block size's and one run's regions that overlap while their union wastes nothing.
    bool changed = true;
    while (changed) {
        changed = false;
        for (std::uint32_t a = 0; a < n; ++a) {
            if (cand[a].merged) continue;
            for (std::uint32_t b = a + 1; b < n; ++b) {
                if (cand[b].merged || cand[b].down != cand[a].down || !(cand[b].run == cand[a].run) ||
                    !cand[a].blocks.touches(cand[b].blocks)) {
                    continue;
                }
                const Rect u = cand[a].blocks.united(cand[b].blocks);
                if (u.area() > cand[a].blocks.area() + cand[b].blocks.area()) continue;
                cand[a].blocks = u;
                cand[a].shapes += cand[b].shapes;
                cand[b].merged = true;
                for (std::size_t i = 0; i < plan.shapes.size(); ++i) {
                    if (region[i] == b) region[i] = a;
                }
                changed = true;
            }
        }
    }
    return n;
}

/// Packs the regions into the copy in shelves, tallest first; the index of a region that did not fit, or ~0.
std::uint32_t pack(std::span<FrostRegion> regions, vk::Extent2D copy) {
    // Tallest first, ties in order: an insertion sort of at most sixteen, which allocates nothing.
    std::array<std::uint32_t, style::max_keep_outs> order{};
    for (std::uint32_t i = 0; i < regions.size(); ++i) {
        std::uint32_t j = i;
        while (j > 0 && regions[order[j - 1]].height < regions[i].height) {
            order[j] = order[j - 1];
            --j;
        }
        order[j] = i;
    }
    std::uint32_t x = 0;
    std::uint32_t y = 0;
    std::uint32_t shelf = 0;
    for (std::size_t k = 0; k < regions.size(); ++k) {
        FrostRegion& r = regions[order[k]];
        if (r.width > copy.width || r.height > copy.height) return order[k];
        if (x + r.width > copy.width) {
            y += shelf;
            x = 0;
            shelf = 0;
        }
        if (y + r.height > copy.height) return order[k];
        r.cx = x;
        r.cy = y;
        x += r.width;
        shelf = std::max(shelf, r.height);
    }
    return ~0U;
}

/// The output's pixels a shape's glass may touch: its box grown by a pixel for the edge's coverage, on the frame.
[[nodiscard]] Rect pixels_of(const FrostShape& s, vk::Extent2D output) noexcept {
    return {.x0 = std::max<std::int64_t>(floor_of(s.left) - 1, 0),
            .y0 = std::max<std::int64_t>(floor_of(s.top) - 1, 0),
            .x1 = std::min<std::int64_t>(ceil_of(s.right) + 1, output.width),
            .y1 = std::min<std::int64_t>(ceil_of(s.bottom) + 1, output.height)};
}

/// The glass's render passes: shapes near one another share one while its box wastes little.
void build_groups(FrostPlan& plan, vk::Extent2D output) {
    struct Group {
        Rect box;
        std::int64_t covered = 0;
        bool merged = false;
    };
    std::array<Group, style::max_keep_outs> groups{};
    std::array<std::uint32_t, style::max_keep_outs> of{};
    std::uint32_t n = 0;
    auto fits = [](const Group& g, const Rect& box, std::int64_t covered) {
        return static_cast<double>(g.box.united(box).area()) <= group_waste * static_cast<double>(g.covered + covered);
    };
    for (std::size_t i = 0; i < plan.shapes.size(); ++i) {
        const Rect box = pixels_of(plan.shapes[i], output);
        std::uint32_t into = n;
        for (std::uint32_t g = 0; g < n; ++g) {
            if (fits(groups[g], box, box.area())) {
                into = g;
                break;
            }
        }
        if (into == n) {
            groups[n] = {.box = box, .covered = box.area(), .merged = false};
            ++n;
        } else {
            groups[into].box = groups[into].box.united(box);
            groups[into].covered += box.area();
        }
        of[i] = into;
    }
    bool changed = true;
    while (changed) {
        changed = false;
        for (std::uint32_t a = 0; a < n; ++a) {
            if (groups[a].merged) continue;
            for (std::uint32_t b = a + 1; b < n; ++b) {
                if (groups[b].merged || !fits(groups[a], groups[b].box, groups[b].covered)) continue;
                groups[a].box = groups[a].box.united(groups[b].box);
                groups[a].covered += groups[b].covered;
                groups[b].merged = true;
                for (std::size_t i = 0; i < plan.shapes.size(); ++i) {
                    if (of[i] == b) of[i] = a;
                }
                changed = true;
            }
        }
    }
    std::array<std::uint32_t, style::max_keep_outs> index{};
    for (std::uint32_t g = 0; g < n; ++g) {
        if (groups[g].merged) continue;
        index[g] = static_cast<std::uint32_t>(plan.groups.size());
        const Rect& b = groups[g].box;
        plan.groups.push_back({.x0 = static_cast<std::int32_t>(b.x0),
                               .y0 = static_cast<std::int32_t>(b.y0),
                               .x1 = static_cast<std::int32_t>(b.x1),
                               .y1 = static_cast<std::int32_t>(b.y1)});
    }
    for (std::size_t i = 0; i < plan.shapes.size(); ++i) plan.shapes[i].group = index[of[i]];
}

} // namespace

std::uint32_t frost_block(double sigma) noexcept {
    if (!(sigma >= 8.0)) return 1;
    std::uint32_t down = 4;
    while (sigma / static_cast<double>(down) > 16.0 && down < coarsest_block) down *= 2;
    return down;
}

vk::Extent2D frost_copy_extent(vk::Extent2D output) noexcept {
    const std::uint32_t w = (output.width + 3) / 4;
    const std::uint32_t h = (output.height + 3) / 4;
    return {.width = w + 8, .height = h + (h / 2) + 8};
}

void plan_frost(const style::CameraState& camera, std::uint32_t quarter_turns, vk::Extent2D output, vk::Extent2D copy,
                FrostPlan& plan) noexcept {
    plan.shapes.clear();
    plan.regions.clear();
    plan.groups.clear();
    if (output.width == 0 || output.height == 0 || camera.width_px == 0 || camera.height_px == 0 ||
        !(camera.density > 0.0)) {
        return;
    }
    const auto w = static_cast<double>(output.width);
    const auto h = static_cast<double>(output.height);
    const auto vw = static_cast<double>(camera.width_px);
    const auto vh = static_cast<double>(camera.height_px);
    const double density = camera.density;
    // The view's pixels to the output's, through the pre-rotation of the frame's own projection (view::turn_of).
    const hlsl::float4 t = view::turn_of(quarter_turns);
    auto to_output = [&](double x, double y) {
        const double nx = (2.0 * x / vw) - 1.0;
        const double ny = (2.0 * y / vh) - 1.0;
        const double rx = (nx * static_cast<double>(t.x)) + (ny * static_cast<double>(t.y));
        const double ry = (nx * static_cast<double>(t.z)) + (ny * static_cast<double>(t.w));
        return std::array<double, 2>{(rx + 1.0) * 0.5 * w, (ry + 1.0) * 0.5 * h};
    };
    for (const style::KeepOut& k : camera.keep_outs()) {
        if (!k.frosted() || !(k.alpha() > 0.0F)) continue;
        const auto a = to_output(static_cast<double>(k.left) * density, static_cast<double>(k.top) * density);
        const auto b = to_output(static_cast<double>(k.right) * density, static_cast<double>(k.bottom) * density);
        FrostShape s;
        s.left = pymath::min(a[0], b[0]);
        s.top = pymath::min(a[1], b[1]);
        s.right = pymath::max(a[0], b[0]);
        s.bottom = pymath::max(a[1], b[1]);
        if (!(s.right > s.left) || !(s.bottom > s.top) || !(s.right > 0.0) || !(s.left < w) || !(s.bottom > 0.0) ||
            !(s.top < h)) {
            continue;
        }
        s.radius = pymath::max(static_cast<double>(k.radius) * density, 0.0);
        s.sigma = static_cast<double>(k.blur_dp()) * density;
        s.saturation = static_cast<double>(k.saturation) / 100.0;
        s.alpha = static_cast<double>(k.alpha());
        s.down = frost_block(s.sigma);
        plan.shapes.push_back(s);
    }
    if (plan.shapes.empty()) return;

    // The regions, packed into the copy; where one does not fit, its shapes take a coarser block and all are planned
    // again (a block four times the area: the copy always holds the frame at the coarsest).
    std::array<Candidate, style::max_keep_outs> cand{};
    std::array<std::uint32_t, style::max_keep_outs> region{};
    for (;;) {
        const std::uint32_t n = build_regions(plan, output, cand, region);
        plan.regions.clear();
        std::array<std::uint32_t, style::max_keep_outs> index{};
        for (std::uint32_t c = 0; c < n; ++c) {
            if (cand[c].merged) continue;
            index[c] = static_cast<std::uint32_t>(plan.regions.size());
            const Rect& r = cand[c].blocks;
            plan.regions.push_back({.down = cand[c].down,
                                    .gx = static_cast<std::int32_t>(r.x0),
                                    .gy = static_cast<std::int32_t>(r.y0),
                                    .width = static_cast<std::uint32_t>(r.width()),
                                    .height = static_cast<std::uint32_t>(r.height()),
                                    .cx = 0,
                                    .cy = 0,
                                    .run = cand[c].run,
                                    .shapes = cand[c].shapes});
        }
        const std::uint32_t failed = pack(plan.regions.span(), copy);
        if (failed == ~0U) {
            // Shapes too small for a block of the frame have no region and are left out.
            std::size_t kept = 0;
            for (std::size_t i = 0; i < plan.shapes.size(); ++i) {
                if (region[i] == ~0U) continue;
                FrostShape s = plan.shapes[i];
                s.region = index[region[i]];
                plan.shapes[kept++] = s;
            }
            while (plan.shapes.size() > kept) plan.shapes.pop_back();
            break;
        }
        // The region that did not fit: its shapes a block coarser, or, at the coarsest, left out.
        bool coarser = false;
        std::size_t kept = 0;
        for (std::size_t i = 0; i < plan.shapes.size(); ++i) {
            FrostShape s = plan.shapes[i];
            if (region[i] != ~0U && index[region[i]] == failed) {
                if (s.down >= coarsest_block) continue;
                s.down *= 2;
                coarser = true;
            }
            plan.shapes[kept++] = s;
        }
        while (plan.shapes.size() > kept) plan.shapes.pop_back();
        if (!coarser && plan.shapes.empty()) {
            plan.regions.clear();
            return;
        }
    }
    build_groups(plan, output);
}

// ---- The backdrops of one output.

gpu::Result<void> FrostedBackdrops::prepare(const Target& target) {
    release();
    device_ = &target.device;
    const gpu::Output& output = target.output;
    extent_ = output.extent();
    final_layout_ = output.final_layout();
    quarter_turns_ = quarter_turns_of(output.transform());
    images_.assign(output.images().begin(), output.images().end());
    using F = vk::FormatFeatureFlagBits;
    const vk::FormatFeatureFlags need =
        F::eBlitSrc | F::eSampledImageFilterLinear | F::eColorAttachment | F::eColorAttachmentBlend;
    const vk::FormatFeatureFlags have =
        target.device.physical().getFormatProperties(output.format()).optimalTilingFeatures;
    if (!output.transfer_source() || (have & need) != need) {
        log::warn("vk frosted: the output ({}) cannot be {}: the core draws no frosted backdrops on it",
                  vk::to_string(output.format()),
                  output.transfer_source() ? "blitted from and blended into" : "copied from (no TRANSFER_SRC)");
        return {};
    }
    const vk::raii::Device& d = target.device.handle();
    copy_extent_ = frost_copy_extent(extent_);
    using U = vk::ImageUsageFlagBits;
    auto image = [&](vk::ImageUsageFlags usage) {
        return target.device.memory().image(
            {.imageType = vk::ImageType::e2D,
             .format = vk::Format::eR8G8B8A8Unorm,
             .extent = {.width = copy_extent_.width, .height = copy_extent_.height, .depth = 1},
             .mipLevels = 1,
             .arrayLayers = 1,
             .samples = vk::SampleCountFlagBits::e1,
             .tiling = vk::ImageTiling::eOptimal,
             .usage = usage,
             .initialLayout = vk::ImageLayout::eUndefined},
            gpu::MemoryUse::gpu);
    };
    ATLAS_GPU_LET(copy, image(U::eTransferDst | U::eStorage | U::eSampled));
    copy_ = std::move(copy);
    ATLAS_GPU_LET(scratch, image(U::eStorage));
    scratch_ = std::move(scratch);
    auto view_of = [&](const gpu::Image& i) -> gpu::Result<vk::raii::ImageView> {
        ATLAS_VK_LET(view,
                     d.createImageView({.image = i.handle(),
                                        .viewType = vk::ImageViewType::e2D,
                                        .format = i.format(),
                                        .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                             .levelCount = 1,
                                                             .layerCount = 1}}),
                     "the frosted copy's view");
        return std::move(view);
    };
    ATLAS_GPU_LET(copy_view, view_of(copy_));
    copy_view_ = std::move(copy_view);
    ATLAS_GPU_LET(scratch_view, view_of(scratch_));
    scratch_view_ = std::move(scratch_view);
    ATLAS_VK_LET(sampler,
                 d.createSampler({.magFilter = vk::Filter::eLinear,
                                  .minFilter = vk::Filter::eLinear,
                                  .mipmapMode = vk::SamplerMipmapMode::eNearest,
                                  .addressModeU = vk::SamplerAddressMode::eClampToEdge,
                                  .addressModeV = vk::SamplerAddressMode::eClampToEdge,
                                  .addressModeW = vk::SamplerAddressMode::eClampToEdge,
                                  .maxLod = 0.0F}),
                 "the frosted copy's sampler");
    sampler_ = std::move(sampler);
    // The boxes' jobs: per frame slot, a dispatch along x and one along y.
    const vk::DeviceSize job_room =
        static_cast<vk::DeviceSize>(gpu::Frames::in_flight) * 2 * fx::BoxBlur::max_rects * fx::BoxBlur::job_bytes;
    ATLAS_GPU_LET(
        jobs, target.device.memory().buffer(job_room, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload));
    jobs_ = std::move(jobs);
    double ms = 0;
    ATLAS_GPU_LET(boxes, fx::BoxBlur::create(target.device, target.cache, target.watch, fx::BlurFormat::rgba8, 2, &ms));
    boxes_ = std::move(boxes);
    ATLAS_GPU_LET(along_x, boxes_->bind(*copy_view_, *scratch_view_, jobs_.handle()));
    along_x_ = std::move(along_x);
    ATLAS_GPU_LET(along_y, boxes_->bind(*scratch_view_, *copy_view_, jobs_.handle()));
    along_y_ = std::move(along_y);

    // The glass: its copy as a combined image sampler, its push constants, its module.
    const std::array bindings{gpu::Binding{
        .slot = 0, .type = vk::DescriptorType::eCombinedImageSampler, .stages = vk::ShaderStageFlagBits::eFragment}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    glass_set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 1));
    glass_pool_ = std::move(pool);
    ATLAS_GPU_LET(set, gpu::allocate_set(d, glass_pool_, glass_set_layout_));
    glass_set_ = std::move(set);
    gpu::SetWriter(*glass_set_)
        .image(0, vk::DescriptorType::eCombinedImageSampler, *copy_view_, vk::ImageLayout::eShaderReadOnlyOptimal,
               *sampler_)
        .apply(d);
    const std::array<vk::DescriptorSetLayout, 1> sets{*glass_set_layout_};
    ATLAS_GPU_LET(layout, gpu::make_layout(d, sets, sizeof(GlassPush),
                                           vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment));
    glass_layout_ = std::move(layout);
    auto module = [&] {
        const gpu::Announced named = gpu::announce_module("frosted_glass", target.watch);
        return gpu::make_module(d, spirv::frosted_glass);
    }();
    ATLAS_GPU_LET(made_module, std::move(module));
    glass_module_ = std::move(made_module);

    // One render pass over the output, which is kept (its load and its store), in the colour layout throughout:
    // record moves the output into it and back. Between two of its instances the colour's writes come first.
    const vk::AttachmentDescription attachment{.format = output.format(),
                                               .samples = vk::SampleCountFlagBits::e1,
                                               .loadOp = vk::AttachmentLoadOp::eLoad,
                                               .storeOp = vk::AttachmentStoreOp::eStore,
                                               .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
                                               .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
                                               .initialLayout = vk::ImageLayout::eColorAttachmentOptimal,
                                               .finalLayout = vk::ImageLayout::eColorAttachmentOptimal};
    const vk::AttachmentReference colour{.attachment = 0, .layout = vk::ImageLayout::eColorAttachmentOptimal};
    const vk::SubpassDescription subpass{
        .pipelineBindPoint = vk::PipelineBindPoint::eGraphics, .colorAttachmentCount = 1, .pColorAttachments = &colour};
    const std::array<vk::SubpassDependency, 2> dependencies{{
        {.srcSubpass = vk::SubpassExternal,
         .dstSubpass = 0,
         .srcStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput,
         .dstStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput,
         .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
         .dstAccessMask = vk::AccessFlagBits::eColorAttachmentRead | vk::AccessFlagBits::eColorAttachmentWrite},
        {.srcSubpass = 0,
         .dstSubpass = vk::SubpassExternal,
         .srcStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput,
         .dstStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput,
         .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
         .dstAccessMask = vk::AccessFlagBits::eColorAttachmentRead | vk::AccessFlagBits::eColorAttachmentWrite},
    }};
    ATLAS_VK_LET(pass,
                 d.createRenderPass({.attachmentCount = 1,
                                     .pAttachments = &attachment,
                                     .subpassCount = 1,
                                     .pSubpasses = &subpass,
                                     .dependencyCount = static_cast<std::uint32_t>(dependencies.size()),
                                     .pDependencies = dependencies.data()}),
                 "the frosted glass's render pass");
    glass_pass_ = std::move(pass);
    for (const vk::ImageView view : output.views()) {
        ATLAS_VK_LET(framebuffer,
                     d.createFramebuffer({.renderPass = *glass_pass_,
                                          .attachmentCount = 1,
                                          .pAttachments = &view,
                                          .width = extent_.width,
                                          .height = extent_.height,
                                          .layers = 1}),
                     "the frosted glass's framebuffer");
        framebuffers_.push_back(std::move(framebuffer));
    }
    gpu::GraphicsDesc desc{.label = {.name = "glass", .module = "frosted_glass", .configuration = target.configuration},
                           .module = *glass_module_,
                           .vertex = "glass_vs",
                           .fragment = "glass_fs",
                           .layout = *glass_layout_};
    desc.target = {.render_pass = *glass_pass_, .subpass = 0, .colour_count = 1};
    desc.blends[0] = gpu::Blend::over();
    target.cache.note_made();
    const auto started = clock::now();
    auto pipeline = [&] {
        const gpu::Announced named = gpu::announce(desc.label, target.watch);
        return gpu::make_graphics(d, target.cache.handle(), desc);
    }();
    ms += std::chrono::duration<double, std::milli>(clock::now() - started).count();
    ATLAS_GPU_LET(made_pipeline, std::move(pipeline));
    glass_pipeline_ = std::move(made_pipeline);
    if (target.stats != nullptr) {
        target.stats->ms += ms;
        target.stats->count += 2;
    }
    ready_ = true;
    return {};
}

void FrostedBackdrops::release() noexcept {
    ready_ = false;
    glass_pipeline_.clear();
    framebuffers_.clear();
    glass_pass_.clear();
    glass_module_.clear();
    glass_layout_.clear();
    glass_set_.clear();
    glass_pool_.clear();
    glass_set_layout_.clear();
    along_y_.clear();
    along_x_.clear();
    boxes_.reset();
    jobs_ = {};
    sampler_.clear();
    scratch_view_.clear();
    copy_view_.clear();
    scratch_ = {};
    copy_ = {};
    images_.clear();
    plan_.shapes.clear();
    plan_.regions.clear();
    plan_.groups.clear();
}

bool FrostedBackdrops::holds_prepared() const noexcept {
    return ready_ || *glass_pipeline_ || !framebuffers_.empty() || *glass_pass_ || *glass_module_ || *glass_layout_ ||
           *glass_set_ || *glass_pool_ || *glass_set_layout_ || *along_x_ || *along_y_ || boxes_ != nullptr ||
           static_cast<bool>(jobs_) || *sampler_ || *copy_view_ || *scratch_view_ || static_cast<bool>(copy_) ||
           static_cast<bool>(scratch_);
}

vk::DeviceSize FrostedBackdrops::bytes() const noexcept { return copy_.bytes() + scratch_.bytes() + jobs_.size(); }

void FrostedBackdrops::record(const vk::raii::CommandBuffer& cmd, const gpu::Sync& sync, std::uint32_t image,
                              std::uint32_t slot, const style::CameraState& camera, frame::PassCounters& counters) {
    if (!ready_ || image >= images_.size() || slot >= gpu::Frames::in_flight) return;
    plan_frost(camera, quarter_turns_, extent_, copy_extent_, plan_);
    log_plan(camera);
    if (plan_.empty()) return;
    const vk::Image output = images_[image];
    const vk::Image copy = copy_.handle();
    const vk::Image scratch = scratch_.handle();
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    using L = vk::ImageLayout;

    // The frame to copy from, after the main pass's writes; the copy written anew, after the last frame's reads.
    const std::array<gpu::ImageDependency, 2> to_copy{{
        {.image = output,
         .src_stage = S::eColorAttachmentOutput | S::eTransfer,
         .src_access = A::eColorAttachmentWrite,
         .dst_stage = S::eTransfer,
         .dst_access = A::eTransferRead,
         .old_layout = final_layout_,
         .new_layout = L::eTransferSrcOptimal},
        {.image = copy,
         .src_stage = S::eFragmentShader | S::eComputeShader,
         .src_access = {},
         .dst_stage = S::eTransfer,
         .dst_access = A::eTransferWrite,
         .old_layout = L::eUndefined,
         .new_layout = L::eTransferDstOptimal},
    }};
    sync.barrier(cmd, {}, to_copy);
    // Each region's blocks, each the linear blit's mean of its middle pixels.
    std::array<vk::ImageBlit, style::max_keep_outs> blits{};
    for (std::size_t i = 0; i < plan_.regions.size(); ++i) {
        const FrostRegion& r = plan_.regions[i];
        const auto down = static_cast<std::int32_t>(r.down);
        const auto w = static_cast<std::int32_t>(r.width);
        const auto h = static_cast<std::int32_t>(r.height);
        const auto cx = static_cast<std::int32_t>(r.cx);
        const auto cy = static_cast<std::int32_t>(r.cy);
        blits[i] = vk::ImageBlit{
            .srcSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
            .srcOffsets =
                std::array<vk::Offset3D, 2>{vk::Offset3D{.x = r.gx * down, .y = r.gy * down, .z = 0},
                                            vk::Offset3D{.x = (r.gx + w) * down, .y = (r.gy + h) * down, .z = 1}},
            .dstSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
            .dstOffsets = std::array<vk::Offset3D, 2>{vk::Offset3D{.x = cx, .y = cy, .z = 0},
                                                      vk::Offset3D{.x = cx + w, .y = cy + h, .z = 1}}};
    }
    cmd.blitImage(output, L::eTransferSrcOptimal, copy, L::eTransferDstOptimal,
                  std::span<const vk::ImageBlit>(blits.data(), plan_.regions.size()), vk::Filter::eLinear);
    const std::array<gpu::ImageDependency, 3> to_blur{{
        {.image = copy,
         .src_stage = S::eTransfer,
         .src_access = A::eTransferWrite,
         .dst_stage = S::eComputeShader,
         .dst_access = A::eShaderRead,
         .old_layout = L::eTransferDstOptimal,
         .new_layout = L::eGeneral},
        {.image = scratch,
         .src_stage = S::eComputeShader,
         .src_access = {},
         .dst_stage = S::eComputeShader,
         .dst_access = A::eShaderWrite,
         .old_layout = L::eUndefined,
         .new_layout = L::eGeneral},
        {.image = output,
         .src_stage = S::eTransfer,
         .src_access = {},
         .dst_stage = S::eColorAttachmentOutput,
         .dst_access = A::eColorAttachmentRead | A::eColorAttachmentWrite,
         .old_layout = L::eTransferSrcOptimal,
         .new_layout = L::eColorAttachmentOptimal},
    }};
    sync.barrier(cmd, {}, to_blur);

    // The boxes along x into the scratch image (every texel read as opaque: the frame's alpha means nothing), then
    // along y back into the copy; past a region's ends its end texels go on, so past the frame's edge the frame's edge
    // goes on (inside the frame a region reaches past what its shapes read, which no end changes).
    for (std::size_t i = 0; i < plan_.regions.size(); ++i) {
        const FrostRegion& r = plan_.regions[i];
        rects_[i] = {.x = static_cast<std::int32_t>(r.cx),
                     .y = static_cast<std::int32_t>(r.cy),
                     .width = r.width,
                     .height = r.height,
                     .run = r.run};
    }
    const std::span<const fx::BlurRect> rects(rects_.data(), plan_.regions.size());
    const std::uint32_t first_x = ((slot * 2) + 0) * fx::BoxBlur::max_rects;
    const std::uint32_t first_y = ((slot * 2) + 1) * fx::BoxBlur::max_rects;
    const std::span<std::byte> jobs = jobs_.bytes();
    const std::size_t room = fx::BoxBlur::max_rects * fx::BoxBlur::job_bytes;
    const bool written =
        fx::BoxBlur::write_jobs(jobs.subspan(first_x * fx::BoxBlur::job_bytes, room), rects, fx::Rounding::nearest) &&
        fx::BoxBlur::write_jobs(jobs.subspan(first_y * fx::BoxBlur::job_bytes, room), rects, fx::Rounding::nearest);
    jobs_.flush();
    if (written) {
        boxes_->record(
            cmd, *along_x_, first_x, rects,
            {.axis = fx::Axis::x, .rounding = fx::Rounding::nearest, .edge = fx::Edge::extend, .opaque = true});
        counters.dispatch("boxes");
        const std::array<gpu::ImageDependency, 2> between{{
            {.image = scratch,
             .src_stage = S::eComputeShader,
             .src_access = A::eShaderWrite,
             .dst_stage = S::eComputeShader,
             .dst_access = A::eShaderRead,
             .old_layout = L::eGeneral,
             .new_layout = L::eGeneral},
            {.image = copy,
             .src_stage = S::eComputeShader,
             .src_access = {},
             .dst_stage = S::eComputeShader,
             .dst_access = A::eShaderWrite,
             .old_layout = L::eGeneral,
             .new_layout = L::eGeneral},
        }};
        sync.barrier(cmd, {}, between);
        boxes_->record(
            cmd, *along_y_, first_y, rects,
            {.axis = fx::Axis::y, .rounding = fx::Rounding::nearest, .edge = fx::Edge::extend, .opaque = false});
        counters.dispatch("boxes");
    }
    sync.barrier(cmd, gpu::ImageDependency{.image = copy,
                                           .src_stage = S::eComputeShader,
                                           .src_access = A::eShaderWrite,
                                           .dst_stage = S::eFragmentShader,
                                           .dst_access = A::eShaderRead,
                                           .old_layout = L::eGeneral,
                                           .new_layout = L::eShaderReadOnlyOptimal});

    // The glass, a render pass per group of shapes over its box.
    const auto w = static_cast<float>(extent_.width);
    const auto h = static_cast<float>(extent_.height);
    const auto cw = static_cast<double>(copy_extent_.width);
    const auto ch = static_cast<double>(copy_extent_.height);
    for (std::size_t g = 0; g < plan_.groups.size(); ++g) {
        const FrostGroup& group = plan_.groups[g];
        cmd.beginRenderPass({.renderPass = *glass_pass_,
                             .framebuffer = *framebuffers_[image],
                             .renderArea = {.offset = {.x = group.x0, .y = group.y0},
                                            .extent = {.width = static_cast<std::uint32_t>(group.x1 - group.x0),
                                                       .height = static_cast<std::uint32_t>(group.y1 - group.y0)}}},
                            vk::SubpassContents::eInline);
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *glass_pipeline_);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *glass_layout_, 0, *glass_set_, {});
        cmd.setViewport(0, vk::Viewport{.x = 0, .y = 0, .width = w, .height = h, .minDepth = 0, .maxDepth = 1});
        for (const FrostShape& s : plan_.shapes) {
            if (s.group != g) continue;
            const FrostRegion& r = plan_.regions[s.region];
            const Rect px = pixels_of(s, extent_);
            const std::int64_t x0 = std::max<std::int64_t>(px.x0, group.x0);
            const std::int64_t y0 = std::max<std::int64_t>(px.y0, group.y0);
            const std::int64_t x1 = std::min<std::int64_t>(px.x1, group.x1);
            const std::int64_t y1 = std::min<std::int64_t>(px.y1, group.y1);
            if (x1 <= x0 || y1 <= y0) continue;
            cmd.setScissor(
                0, vk::Rect2D{.offset = {.x = static_cast<std::int32_t>(x0), .y = static_cast<std::int32_t>(y0)},
                              .extent = {.width = static_cast<std::uint32_t>(x1 - x0),
                                         .height = static_cast<std::uint32_t>(y1 - y0)}});
            const auto down = static_cast<double>(r.down);
            auto f = [](double v) { return static_cast<float>(v); };
            GlassPush p{};
            p.box = {f(s.left), f(s.top), f(s.right), f(s.bottom)};
            p.copy = {f(1.0 / (down * cw)), f(1.0 / (down * ch)), f((static_cast<double>(r.cx) - r.gx) / cw),
                      f((static_cast<double>(r.cy) - r.gy) / ch)};
            p.bounds = {f((static_cast<double>(r.cx) + 0.5) / cw), f((static_cast<double>(r.cy) + 0.5) / ch),
                        f((static_cast<double>(r.cx + r.width) - 0.5) / cw),
                        f((static_cast<double>(r.cy + r.height) - 0.5) / ch)};
            p.screen = {w, h, 1.0F / w, 1.0F / h};
            p.shape = {f(s.radius), f(s.saturation), f(s.alpha), 0.0F};
            cmd.pushConstants(
                *glass_layout_, vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment, 0,
                vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
            cmd.draw(6, 1, 0, 0);
            counters.draw("glass", 2);
        }
        cmd.endRenderPass();
    }
    sync.barrier(cmd, gpu::ImageDependency{.image = output,
                                           .src_stage = S::eColorAttachmentOutput,
                                           .src_access = A::eColorAttachmentWrite,
                                           .dst_stage = S::eTransfer | S::eColorAttachmentOutput,
                                           .dst_access = A::eTransferRead,
                                           .old_layout = L::eColorAttachmentOptimal,
                                           .new_layout = final_layout_});
}

void FrostedBackdrops::log_plan(const style::CameraState& camera) {
    // A line when the shapes change: at once when their number does, at most once a second while they move.
    const auto now = clock::now();
    bool same = logged_any_ && logged_.shapes.size() == plan_.shapes.size();
    for (std::size_t i = 0; same && i < plan_.shapes.size(); ++i) {
        const FrostShape& a = plan_.shapes[i];
        const FrostShape& b = logged_.shapes[i];
        same = std::abs(a.left - b.left) < 0.5 && std::abs(a.top - b.top) < 0.5 && std::abs(a.right - b.right) < 0.5 &&
               std::abs(a.bottom - b.bottom) < 0.5 && a.down == b.down;
    }
    if (same) return;
    const bool count_changed = !logged_any_ || logged_.shapes.size() != plan_.shapes.size();
    if (!count_changed && now - logged_at_ < std::chrono::seconds(1)) return;
    logged_any_ = true;
    logged_at_ = now;
    logged_.shapes.clear();
    for (const FrostShape& s : plan_.shapes) logged_.shapes.push_back(s);
    if (plan_.empty()) {
        log::info("vk frosted 0 shapes (of {} keep-outs)", camera.keep_out_count);
        return;
    }
    // "vk frosted <n> shapes: <left> <top> <right> <bottom> r <radius> blur <σ> sat <s>[ alpha <a>] block <px>; ...
    // (copy …)", the alpha while a fade holds the chrome under 1
    std::array<char, 1400> text{}; // sixteen shapes of up to 85 characters
    std::size_t used = 0;
    for (const FrostShape& s : plan_.shapes) {
        char* const at = text.data() + used;
        const auto room = static_cast<std::ptrdiff_t>(text.size() - used);
        const char* const gap = used == 0 ? "" : "; ";
        const auto out =
            s.alpha < 1.0
                ? std::format_to_n(
                      at, room, "{}{:.1f} {:.1f} {:.1f} {:.1f} r {:.1f} blur {:.1f} sat {:.2f} alpha {:.2f} block {}",
                      gap, s.left, s.top, s.right, s.bottom, s.radius, s.sigma, s.saturation, s.alpha, s.down)
                : std::format_to_n(at, room, "{}{:.1f} {:.1f} {:.1f} {:.1f} r {:.1f} blur {:.1f} sat {:.2f} block {}",
                                   gap, s.left, s.top, s.right, s.bottom, s.radius, s.sigma, s.saturation, s.down);
        used = std::min(text.size(), used + static_cast<std::size_t>(out.size));
    }
    log::info("vk frosted {} shapes: {} (copy {}x{}, {} regions, {} passes)", plan_.shapes.size(),
              std::string_view(text.data(), used), copy_extent_.width, copy_extent_.height, plan_.regions.size(),
              plan_.groups.size());
}

// ---- The pass.

gpu::Result<void> Frosted::prepare(const frame::PassContext& context) {
    return backdrops_.prepare({.device = context.device,
                               .cache = context.cache,
                               .watch = context.watch,
                               .output = context.output,
                               .configuration = context.configuration,
                               .stats = context.stats});
}

void Frosted::release() noexcept { backdrops_.release(); }

void Frosted::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
    if (recording.state.camera.leaves_out(style::camera_part::frosted)) return; // the map leaves the glass out
    const gpu::FrameContext& f = recording.frame;
    backdrops_.record(f.cmd, f.sync, f.image, f.slot.slot, recording.state.camera, recording.counters);
}

} // namespace atlas::passes
