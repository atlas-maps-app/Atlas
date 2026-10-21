// The ground's shadows (passes/Ground.hpp; plan-vector D8 "Ground shadows": in A1 a stencil union composited once at
// shadow_op in th['shadow'], sharp; render/amsterdam.py:742–771). By day with the sun up (and where the region draws
// them), every solid casts its shadow on the ground: a prism the hull of its footprint and its top moved by
// −L.xz / L.y · h, a raised part its top's outline moved, a tree its crown's 16-gon moved by the shadow of 0.7 of its
// height; the renders' radius cut drops a caster whose centroid (a tree's trunk) lies beyond it.
//
// The sun moves them on the GPU, so nothing is made per sun bucket: stage T keeps each prism's footprint hull (hull2,
// sun-free), each raised outline and each crown disc (geometry/Ground.cpp's build_shadows), and the vertex stage moves
// them by the moment's sun. The hull of a footprint and its moved copy is the footprint's hull swept along the offset:
// each hull point comes twice, before and after its turn, moved where the edge on that side faces the offset (its
// outward normal · offset > 0), so the polygon over the doubled points is the swept hull, convex, a fan. Each frame
// decides the radius cut per caster (kept at rest with the other decisions); the drawn shadows' triangles set the
// stencil's shadow bit at the shadows' painter depth (not where a nearer object was drawn), and one triangle over the
// frame composites th['shadow'] at shadow_op where the bit is set, clearing it.
//
// The twin draws the port's own polygons: sun::shadows_of over the tile's casters (K8's hull2 of the footprint and its
// moved top, rounded to millimetres), each kept where Camera.poly keeps it (900 px of margin for a solid's, 40 for a
// tree's), in `<g opacity fill filter>` at the shadows' slot.
//
// The soft shadows. Where the moment blurs them (th['shadow_blur']: 2.6 overcast, 0.6 with the sun between clouds, so
// every daytime moment the app shows), the renders draw the group through `sblur`, and the port's raster blurs it on
// a grid of its own (fx/BlurGrid.hpp): the filter region is the shadows' box grown by a tenth, WebKit draws a filter
// at most 4096² pixels large, so at the boards' views the grid is coarser than the device's (the home's 0.43 of the
// density, the Main board's 0.70), and the result is drawn back over the frame, filtered. Each such frame, before the
// main pass (phase_shadow_mask), on the GPU alone, so the CPU does nothing per shadow:
//   1. shadow_box_cs measures the box of the drawn shadows as Camera.poly keeps them and the SVG prints them;
//   2. shadow_grid_cs works out the grid from it as fx::blur_grid does: the clamp, the scale, the rectangles drawn and
//      read, vImage's box;
//   3. the shadows' union is drawn on that grid into a 4x mask (shadow_mask_vs), resolved (252 of 255 a sample, so a
//      pixel's coverage n/4 resolves to 63n whatever the driver's rounding);
//   4. shaders/ground/shadow_blur.slang runs vImage's three square boxes over it, exactly (fx::square_blur_cpu), the
//      last faded by the opacity as the port fades its bitmap;
// and at the shadows' slot one triangle over the frame (soft_fs) draws the result back as the port draws its bitmap:
// each pixel's centre on the grid, the mask there, bilinear where the grid is coarser. What differs from the port is
// the coverage of the union's edges (4x samples, the port's analytic coverage of each path, composited) and the
// bilinear filter's rounding; the grid, the boxes and the fade are the port's.
#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "fx/Blur.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/ground/shadow_blur.hpp"
#include "gpu/Descriptors.hpp"
#include "passes/Ground.hpp"
#include "scene/Residency.hpp"
#include "sun/Hulls.hpp"
#include "sun/Samples.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

namespace {

/// render's `far`: math.hypot(x - TX, z - TZ) > RAD, the squares deciding where they tell.
[[nodiscard]] bool far(double x, double z, double tx, double tz, double radius) noexcept {
    const double dx = x - tx;
    const double dz = z - tz;
    const double d2 = (dx * dx) + (dz * dz);
    const double r2 = radius * radius;
    if (d2 < r2 * (1 - 1e-9)) return false;
    if (d2 > r2 * (1 + 1e-9)) return true;
    return pm::hypot(dx, dz) > radius;
}

[[nodiscard]] std::uint32_t argb(const style::Rgb8& c) noexcept {
    return 0xFF00'0000U | (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | c.b;
}

using Push = layouts::ground_ground::PushConstants;
using BlurPush = layouts::ground_shadow_blur::BlurPush;

/// The widest blur the mask leaves room for: render's overcast deviation (2.6 CSS px) at the likeness's highest
/// density (8 device px a CSS px).
constexpr float widest_sigma = 2.6F * 8.0F;
/// The bytes of one ShadowGrid (shared/shadow_grid.slang).
constexpr vk::DeviceSize grid_bytes = 96;
/// The texels a workgroup of shadow_blur.slang writes along each side.
constexpr std::uint32_t blur_tile = 32;

/// Whether a surface transform turns the view a quarter or three quarters (the output's size is the view's turned).
[[nodiscard]] bool turned(vk::SurfaceTransformFlagBitsKHR transform) noexcept {
    using T = vk::SurfaceTransformFlagBitsKHR;
    return transform == T::eRotate90 || transform == T::eRotate270 || transform == T::eHorizontalMirrorRotate90 ||
           transform == T::eHorizontalMirrorRotate270;
}

/// A tile's shadows the shadows' draw draws.
[[nodiscard]] bool casts(const DrawnTile& t) noexcept {
    return t.ground->layout.shadow_draw.count != 0 && static_cast<bool>(t.pass->ranges[0]) && !t.ground->no_shadows;
}

} // namespace

const SoftShadows* Ground::soft_shadows() const noexcept {
    return pipelines_ != nullptr ? pipelines_->soft.get() : nullptr;
}

bool Ground::soft(const frame::FrameState& state) const noexcept {
    return frame_.shadows && state.theme.shadow_blur > 0 && pipelines_ != nullptr && pipelines_->soft != nullptr;
}

void Ground::decide_shadows(const DrawnTile& t, TileDecisions& d) const {
    const GroundFrame& f = frame_;
    const geo::GroundTile& g = *t.ground;
    d.shadow_drawn.clear();
    if (!f.casting || g.no_shadows) return;
    d.shadow_drawn.assign(g.shadows.size(), 0);
    if (f.engine && g.shadow_at[1] >= g.shadow_at[0]) {
        // the tile's casters wholly within the radius or wholly beyond it, by the box of their points
        const std::array<double, 4>& b = g.shadow_at;
        const double nx = pm::max(0.0, pm::max(b[0] - f.target_x, f.target_x - b[1]));
        const double nz = pm::max(0.0, pm::max(b[2] - f.target_z, f.target_z - b[3]));
        const double fx = pm::max(std::abs(b[0] - f.target_x), std::abs(b[1] - f.target_x));
        const double fz = pm::max(std::abs(b[2] - f.target_z), std::abs(b[3] - f.target_z));
        const double r2 = f.radius * f.radius;
        if ((fx * fx) + (fz * fz) < r2 * (1 - 1e-9)) {
            std::ranges::fill(d.shadow_drawn, std::uint8_t{1});
            return;
        }
        if ((nx * nx) + (nz * nz) > r2 * (1 + 1e-9)) return;
    }
    for (std::uint32_t i = 0; i < g.shadows.size(); ++i) {
        const geo::Shadow& sh = g.shadows[i];
        // the radius cut, in engine mode (a solid's centroid, a tree's trunk); every caster counts, in a visible cell
        // or not: its shadow may reach into one
        if (f.engine && far(sh.at.x, sh.at.z, f.target_x, f.target_z, f.radius)) continue;
        d.shadow_drawn[i] = 1;
    }
}

gpu::Result<void> Ground::make_shadow_pipelines(const frame::PassContext& context, Pipelines& p) const {
    // the union: every drawn shadow's triangles set the shadow bit at the shadows' depth (not under a nearer object)
    gpu::GraphicsDesc un = ground_desc(context, p, "ground shadow union", "shadow_vs", "colour_fs");
    translucent(un);
    colour_off(un);
    un.stencil_test = true;
    un.stencil = {.failOp = vk::StencilOp::eKeep,
                  .passOp = vk::StencilOp::eReplace,
                  .depthFailOp = vk::StencilOp::eKeep,
                  .compareOp = vk::CompareOp::eAlways,
                  .compareMask = 0,
                  .writeMask = ground_stencil::shadows,
                  .reference = ground_stencil::shadows};
    ATLAS_GPU_LET(u, frame::make_pipeline(context, un, frame::Overdraw::none));
    p.shadow_union = std::move(u);
    // the composite: th['shadow'] at shadow_op where the bit is set, clearing it
    gpu::GraphicsDesc co = ground_desc(context, p, "ground shadow composite", "screen_vs", "flat_fs");
    translucent(co);
    co.stencil_test = true;
    co.stencil = {.failOp = vk::StencilOp::eKeep,
                  .passOp = vk::StencilOp::eZero,
                  .depthFailOp = vk::StencilOp::eZero,
                  .compareOp = vk::CompareOp::eEqual,
                  .compareMask = ground_stencil::shadows,
                  .writeMask = ground_stencil::shadows,
                  .reference = ground_stencil::shadows};
    ATLAS_GPU_LET(c, frame::make_pipeline(context, co, frame::Overdraw::translucent));
    p.shadow_composite = std::move(c);
    // the soft shadows, but in the owner mode (its map takes the shadows' slot where the union's stencil is set); where
    // the device cannot make them, the shadows stay sharp and the rest of the ground draws
    if (!context.owners) {
        if (auto made = make_soft_shadows(context, p); !made) {
            log::warn("vk ground: the soft shadows: {}; the shadows are drawn sharp", made.error().text());
            p.soft.reset();
        }
    }
    return {};
}

gpu::Result<void> Ground::make_soft_shadows(const frame::PassContext& context, Pipelines& p) const {
    const gpu::Device& device = context.device;
    const vk::raii::Device& d = device.handle();
    auto s = std::make_unique<SoftShadows>();
    s->kept = keep_soft_;
    // the mask: the view's device px (the output's, turned back) with the widest reach and a pixel around them
    vk::Extent2D view = context.output.extent();
    if (turned(context.output.transform())) std::swap(view.width, view.height);
    const std::uint32_t reach = 3 * ((fx::box_size(widest_sigma) / 2) + 1);
    const std::uint32_t margin = (2 * reach) + 4;
    s->extent = {.width = view.width + margin, .height = view.height + margin};
    s->stride = (s->extent.width + 3) / 4;
    using U = vk::ImageUsageFlagBits;
    auto image = [&](vk::SampleCountFlagBits samples, vk::ImageUsageFlags usage, gpu::MemoryUse use) {
        return device.memory().image({.imageType = vk::ImageType::e2D,
                                      .format = vk::Format::eR8Unorm,
                                      .extent = {.width = s->extent.width, .height = s->extent.height, .depth = 1},
                                      .mipLevels = 1,
                                      .arrayLayers = 1,
                                      .samples = samples,
                                      .tiling = vk::ImageTiling::eOptimal,
                                      .usage = usage,
                                      .initialLayout = vk::ImageLayout::eUndefined},
                                     use);
    };
    // the samples in lazily allocated memory where the frame's are (no pages on a tiler), else, or where that fails
    // for one channel, in ordinary memory
    const bool lazy = context.pass.spec().config.lazy();
    const vk::ImageUsageFlags transient = U::eColorAttachment | U::eTransientAttachment;
    auto samples = image(vk::SampleCountFlagBits::e4, transient, lazy ? gpu::MemoryUse::lazy : gpu::MemoryUse::gpu);
    if (!samples && lazy) samples = image(vk::SampleCountFlagBits::e4, transient, gpu::MemoryUse::gpu);
    if (!samples) return std::unexpected(samples.error());
    s->samples = std::move(*samples);
    ATLAS_GPU_LET(mask, image(vk::SampleCountFlagBits::e1,
                              U::eColorAttachment | U::eSampled | (s->kept ? U::eTransferSrc : vk::ImageUsageFlags{}),
                              gpu::MemoryUse::gpu));
    s->mask = std::move(mask);
    auto view_of = [&](const gpu::Image& i) -> gpu::Result<vk::raii::ImageView> {
        ATLAS_VK_LET(v,
                     d.createImageView({.image = i.handle(),
                                        .viewType = vk::ImageViewType::e2D,
                                        .format = vk::Format::eR8Unorm,
                                        .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                             .levelCount = 1,
                                                             .layerCount = 1}}),
                     "the soft shadows' mask's view");
        return std::move(v);
    };
    ATLAS_GPU_LET(samples_view, view_of(s->samples));
    s->samples_view = std::move(samples_view);
    ATLAS_GPU_LET(mask_view, view_of(s->mask));
    s->mask_view = std::move(mask_view);
    // the mask's pass: the 4x samples cleared and drawn, resolved into the mask; record moves both into the colour
    // layout before it and the mask out after it
    const std::array<vk::AttachmentDescription, 2> attachments{{
        {.format = vk::Format::eR8Unorm,
         .samples = vk::SampleCountFlagBits::e4,
         .loadOp = vk::AttachmentLoadOp::eClear,
         .storeOp = vk::AttachmentStoreOp::eDontCare,
         .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
         .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
         .initialLayout = vk::ImageLayout::eColorAttachmentOptimal,
         .finalLayout = vk::ImageLayout::eColorAttachmentOptimal},
        {.format = vk::Format::eR8Unorm,
         .samples = vk::SampleCountFlagBits::e1,
         .loadOp = vk::AttachmentLoadOp::eDontCare,
         .storeOp = vk::AttachmentStoreOp::eStore,
         .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
         .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
         .initialLayout = vk::ImageLayout::eColorAttachmentOptimal,
         .finalLayout = vk::ImageLayout::eColorAttachmentOptimal},
    }};
    const vk::AttachmentReference colour{.attachment = 0, .layout = vk::ImageLayout::eColorAttachmentOptimal};
    const vk::AttachmentReference resolve{.attachment = 1, .layout = vk::ImageLayout::eColorAttachmentOptimal};
    const vk::SubpassDescription subpass{.pipelineBindPoint = vk::PipelineBindPoint::eGraphics,
                                         .colorAttachmentCount = 1,
                                         .pColorAttachments = &colour,
                                         .pResolveAttachments = &resolve};
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
                 d.createRenderPass({.attachmentCount = static_cast<std::uint32_t>(attachments.size()),
                                     .pAttachments = attachments.data(),
                                     .subpassCount = 1,
                                     .pSubpasses = &subpass,
                                     .dependencyCount = static_cast<std::uint32_t>(dependencies.size()),
                                     .pDependencies = dependencies.data()}),
                 "the soft shadows' mask's render pass");
    s->pass = std::move(pass);
    const std::array<vk::ImageView, 2> views{*s->samples_view, *s->mask_view};
    ATLAS_VK_LET(framebuffer,
                 d.createFramebuffer({.renderPass = *s->pass,
                                      .attachmentCount = static_cast<std::uint32_t>(views.size()),
                                      .pAttachments = views.data(),
                                      .width = s->extent.width,
                                      .height = s->extent.height,
                                      .layers = 1}),
                 "the soft shadows' mask's framebuffer");
    s->framebuffer = std::move(framebuffer);
    // the grid and the blur's texels (readable where the tests keep them), and the kept mask's copy
    using B = vk::BufferUsageFlagBits;
    const gpu::MemoryUse look = s->kept ? gpu::MemoryUse::readback : gpu::MemoryUse::gpu;
    ATLAS_GPU_LET(grid, device.memory().buffer(grid_bytes, B::eStorageBuffer | B::eTransferDst, look));
    s->grid = std::move(grid);
    const vk::DeviceSize texels = vk::DeviceSize{s->stride} * 4 * s->extent.height;
    ATLAS_GPU_LET(first, device.memory().buffer(texels, B::eStorageBuffer, look));
    s->blurred[0] = std::move(first);
    ATLAS_GPU_LET(second, device.memory().buffer(texels, B::eStorageBuffer, gpu::MemoryUse::gpu));
    s->blurred[1] = std::move(second);
    if (s->kept) {
        ATLAS_GPU_LET(copy, device.memory().buffer(vk::DeviceSize{s->extent.width} * s->extent.height, B::eTransferDst,
                                                   gpu::MemoryUse::readback));
        s->mask_copy = std::move(copy);
    }
    gpu::SetWriter(*p.soft_set)
        .buffer(0, vk::DescriptorType::eStorageBuffer, s->grid.handle())
        .buffer(1, vk::DescriptorType::eStorageBuffer, s->blurred[0].handle())
        .buffer(2, vk::DescriptorType::eStorageBuffer, s->grid.handle())
        .apply(d);

    // the compute stages of the ground's module: its sets, its push constants for them
    const std::array<vk::DescriptorSetLayout, 4> sets{
        context.layout.frame_set_layout(), context.layout.global_set_layout(), *p.set_layout, *p.soft_set_layout};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, frame::push_bytes, vk::ShaderStageFlagBits::eCompute));
    p.compute_layout = std::move(compute_layout);
    auto compute = [&](std::string_view name, std::string_view module, vk::ShaderModule code, const char* entry,
                       vk::PipelineLayout layout) -> gpu::Result<vk::raii::Pipeline> {
        const gpu::PipelineLabel label{.name = name, .module = module, .configuration = context.configuration};
        context.cache.note_made();
        const auto started = std::chrono::steady_clock::now();
        auto made = [&] {
            const gpu::Announced named = gpu::announce(label, context.watch);
            return gpu::make_compute(d, context.cache.handle(), label, code, entry, layout);
        }();
        if (context.stats != nullptr) {
            context.stats->ms +=
                std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
            ++context.stats->count;
        }
        return made;
    };
    ATLAS_GPU_LET(box, compute("ground shadow box", "ground_ground", *p.module, "shadow_box_cs", *p.compute_layout));
    p.shadow_box = std::move(box);
    ATLAS_GPU_LET(made_grid,
                  compute("ground shadow grid", "ground_ground", *p.module, "shadow_grid_cs", *p.compute_layout));
    p.shadow_grid = std::move(made_grid);
    // the union on the grid, into the mask's samples
    gpu::GraphicsDesc un{
        .label = {.name = "ground shadow mask", .module = "ground_ground", .configuration = context.configuration},
        .module = *p.module,
        .vertex = "shadow_mask_vs",
        .fragment = "mask_fs",
        .layout = *p.layout};
    un.target = {.render_pass = *s->pass, .subpass = 0, .samples = vk::SampleCountFlagBits::e4, .colour_count = 1};
    un.blends[0] = gpu::Blend::opaque();
    ATLAS_GPU_LET(made_mask, frame::make_pipeline(context, un, frame::Overdraw::none));
    p.shadow_mask = std::move(made_mask);
    // the result drawn back at the shadows' painter depth, over what lies under them
    gpu::GraphicsDesc co = ground_desc(context, p, "ground shadow soft", "screen_vs", "soft_fs");
    translucent(co);
    ATLAS_GPU_LET(made_soft, frame::make_pipeline(context, co, frame::Overdraw::translucent));
    p.shadow_soft = std::move(made_soft);

    // the blur: its module, the grid, the mask and a pass's two buffers, three passes back and forth
    ATLAS_GPU_LET(blur_module, frame::make_module(context, "ground_shadow_blur", spirv::ground_shadow_blur));
    s->blur_module = std::move(blur_module);
    constexpr auto in_compute = vk::ShaderStageFlagBits::eCompute;
    const std::array blur_bindings{
        gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = in_compute},
        gpu::Binding{.slot = 1, .type = vk::DescriptorType::eSampledImage, .stages = in_compute},
        gpu::Binding{.slot = 2, .type = vk::DescriptorType::eStorageBuffer, .stages = in_compute},
        gpu::Binding{.slot = 3, .type = vk::DescriptorType::eStorageBuffer, .stages = in_compute}};
    ATLAS_GPU_LET(blur_set_layout, gpu::make_set_layout(d, blur_bindings));
    s->blur_set_layout = std::move(blur_set_layout);
    const std::array<std::span<const gpu::Binding>, 1> blur_kinds{blur_bindings};
    ATLAS_GPU_LET(blur_pool, gpu::make_pool(d, blur_kinds, 3));
    s->blur_pool = std::move(blur_pool);
    for (std::size_t i = 0; i < s->blur_sets.size(); ++i) {
        ATLAS_GPU_LET(set, gpu::allocate_set(d, s->blur_pool, s->blur_set_layout));
        s->blur_sets[i] = std::move(set);
        // the first pass writes the first buffer, the second the other, the third the first again
        const std::size_t target = i % 2 == 0 ? 0 : 1;
        gpu::SetWriter(*s->blur_sets[i])
            .buffer(0, vk::DescriptorType::eStorageBuffer, s->grid.handle())
            .image(1, vk::DescriptorType::eSampledImage, *s->mask_view, vk::ImageLayout::eShaderReadOnlyOptimal)
            .buffer(2, vk::DescriptorType::eStorageBuffer, s->blurred[1 - target].handle())
            .buffer(3, vk::DescriptorType::eStorageBuffer, s->blurred[target].handle())
            .apply(d);
    }
    const std::array<vk::DescriptorSetLayout, 1> blur_sets{*s->blur_set_layout};
    ATLAS_GPU_LET(blur_layout, gpu::make_layout(d, blur_sets, sizeof(BlurPush), in_compute));
    s->blur_layout = std::move(blur_layout);
    ATLAS_GPU_LET(blur,
                  compute("ground shadow blur", "ground_shadow_blur", *s->blur_module, "blur_cs", *s->blur_layout));
    s->blur = std::move(blur);
    p.soft = std::move(s);
    return {};
}

void Ground::record_shadow_mask(const frame::Recording& r) {
    drew_soft_ = false;
    if (!soft(r.state)) return;
    Pipelines& p = *pipelines_;
    SoftShadows& s = *p.soft;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const gpu::Sync& sync = r.frame.sync;
    const Words& w = *words_;
    const style::ThemeBlock& th = r.state.theme;
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    using L = vk::ImageLayout;
    constexpr vk::PipelineStageFlags2 shaders = S::eVertexShader | S::eFragmentShader | S::eComputeShader;

    // the box anew, once the last frame has read the grid
    sync.barrier(
        cmd, gpu::MemoryDependency{
                 .src_stage = shaders, .src_access = {}, .dst_stage = S::eTransfer, .dst_access = A::eTransferWrite});
    cmd.fillBuffer(s.grid.handle(), 0, 8, 0xFFFF'FFFFU);
    cmd.fillBuffer(s.grid.handle(), 8, 8, 0U);
    sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eTransfer,
                                            .src_access = A::eTransferWrite,
                                            .dst_stage = S::eComputeShader,
                                            .dst_access = A::eShaderRead | A::eShaderWrite});
    const auto frame_sets = r.layout.sets();
    const auto offsets = r.layout.offsets(r.frame.slot.slot);
    const std::array<vk::DescriptorSet, 2> own{*p.sets[w.slot], *p.soft_set};
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *p.compute_layout, 0, frame_sets, offsets);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *p.compute_layout, 2, own, {});

    // 1. the drawn shadows' box: a thread a shadow, a row of workgroups a tile
    Push pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.rows = {static_cast<float>(frame_.sun_x), static_cast<float>(frame_.sun_y), static_cast<float>(frame_.sun_z),
               0.0F};
    pc.colour = {s.extent.width, s.extent.height, 0U, s.stride};
    std::size_t most = 0;
    for (const DrawnTile& t : frame_.tiles) {
        if (casts(t)) most = std::max(most, t.ground->shadows.size());
    }
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *p.shadow_box);
    cmd.pushConstants<Push>(*p.compute_layout, vk::ShaderStageFlagBits::eCompute, 0, pc);
    if (most > 0) {
        cmd.dispatch(static_cast<std::uint32_t>((most + 63) / 64), static_cast<std::uint32_t>(frame_.tiles.size()), 1);
        r.counters.dispatch("shadow box");
    }
    sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                            .src_access = A::eShaderWrite,
                                            .dst_stage = S::eComputeShader,
                                            .dst_access = A::eShaderRead | A::eShaderWrite});
    // 2. the grid, from the box: the deviation and the opacity as the SVG prints them, WebKit's factor as the CPU's
    pc.rows = {static_cast<float>(printed(th.shadow_blur, 1)), static_cast<float>(printed(th.shadow_opacity, 2)),
               fx::webkit_factor(), 0.0F};
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *p.shadow_grid);
    cmd.pushConstants<Push>(*p.compute_layout, vk::ShaderStageFlagBits::eCompute, 0, pc);
    cmd.dispatch(1, 1, 1);
    r.counters.dispatch("shadow grid");
    sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                            .src_access = A::eShaderWrite,
                                            .dst_stage = shaders,
                                            .dst_access = A::eShaderRead});

    // 3. the shadows' union on the grid, its samples resolved into the mask (which the last frame's blur read)
    const std::array<gpu::ImageDependency, 2> to_draw{{
        {.image = s.samples.handle(),
         .src_stage = S::eColorAttachmentOutput,
         .src_access = {},
         .dst_stage = S::eColorAttachmentOutput,
         .dst_access = A::eColorAttachmentWrite,
         .old_layout = L::eUndefined,
         .new_layout = L::eColorAttachmentOptimal},
        {.image = s.mask.handle(),
         .src_stage = S::eComputeShader | S::eTransfer,
         .src_access = {},
         .dst_stage = S::eColorAttachmentOutput,
         .dst_access = A::eColorAttachmentWrite,
         .old_layout = L::eUndefined,
         .new_layout = L::eColorAttachmentOptimal},
    }};
    sync.barrier(cmd, {}, to_draw);
    const vk::ClearValue clear{.color = vk::ClearColorValue{std::array<float, 4>{0.0F, 0.0F, 0.0F, 0.0F}}};
    cmd.beginRenderPass({.renderPass = *s.pass,
                         .framebuffer = *s.framebuffer,
                         .renderArea = {.offset = {.x = 0, .y = 0}, .extent = s.extent},
                         .clearValueCount = 1,
                         .pClearValues = &clear},
                        vk::SubpassContents::eInline);
    cmd.setViewport(0, vk::Viewport{.x = 0,
                                    .y = 0,
                                    .width = static_cast<float>(s.extent.width),
                                    .height = static_cast<float>(s.extent.height),
                                    .minDepth = 0,
                                    .maxDepth = 1});
    cmd.setScissor(0, vk::Rect2D{.offset = {.x = 0, .y = 0}, .extent = s.extent});
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *p.shadow_mask);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *p.layout, 0, frame_sets, offsets);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *p.layout, 2, own, {});
    pc.rows = {static_cast<float>(frame_.sun_x), static_cast<float>(frame_.sun_y), static_cast<float>(frame_.sun_z),
               0.0F};
    cmd.pushConstants<Push>(*p.layout, frame::push_stages, 0, pc);
    for (std::size_t i = 0; i < frame_.tiles.size(); ++i) {
        const DrawnTile& t = frame_.tiles[i];
        if (!casts(t)) continue;
        const geo::Words draw = t.ground->layout.shadow_draw;
        cmd.draw(draw.count, 1, (t.pass->ranges[0].offset / 4) + draw.first, static_cast<std::uint32_t>(i));
        r.counters.draw("shadow mask", draw.count / 3);
    }
    cmd.endRenderPass();
    sync.barrier(cmd, gpu::ImageDependency{.image = s.mask.handle(),
                                           .src_stage = S::eColorAttachmentOutput,
                                           .src_access = A::eColorAttachmentWrite,
                                           .dst_stage = S::eComputeShader,
                                           .dst_access = A::eShaderRead,
                                           .old_layout = L::eColorAttachmentOptimal,
                                           .new_layout = L::eShaderReadOnlyOptimal});

    // 4. vImage's three boxes over the grid's input, the last faded; the composite of the last frame read the first
    // buffer, which the first pass writes
    sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eFragmentShader | S::eComputeShader,
                                            .src_access = {},
                                            .dst_stage = S::eComputeShader,
                                            .dst_access = A::eShaderWrite});
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *s.blur);
    const std::uint32_t groups_x = (s.extent.width + blur_tile - 1) / blur_tile;
    const std::uint32_t groups_y = (s.extent.height + blur_tile - 1) / blur_tile;
    for (std::uint32_t pass = 0; pass < 3; ++pass) {
        if (pass > 0) {
            sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                    .src_access = A::eShaderWrite,
                                                    .dst_stage = S::eComputeShader,
                                                    .dst_access = A::eShaderRead | A::eShaderWrite});
        }
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.blur_layout, 0, *s.blur_sets[pass], {});
        const BlurPush bp{.first = pass == 0 ? 1U : 0U, .last = pass == 2 ? 1U : 0U, .stride = s.stride, .pad = 0U};
        cmd.pushConstants<BlurPush>(*s.blur_layout, vk::ShaderStageFlagBits::eCompute, 0, bp);
        cmd.dispatch(groups_x, groups_y, 1);
        r.counters.dispatch("shadow blur");
    }
    sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                            .src_access = A::eShaderWrite,
                                            .dst_stage = S::eFragmentShader,
                                            .dst_access = A::eShaderRead});
    if (s.kept) {
        // the tests' look: the resolved mask, a row after another, and the grid and the blur readable by the host
        const gpu::MemoryDependency copied{.src_stage = S::eTransfer,
                                           .src_access = A::eTransferWrite,
                                           .dst_stage = S::eTransfer,
                                           .dst_access = A::eTransferWrite};
        const gpu::ImageDependency from{.image = s.mask.handle(),
                                        .src_stage = S::eComputeShader,
                                        .src_access = {},
                                        .dst_stage = S::eTransfer,
                                        .dst_access = A::eTransferRead,
                                        .old_layout = L::eShaderReadOnlyOptimal,
                                        .new_layout = L::eTransferSrcOptimal};
        sync.barrier(cmd, std::span(&copied, 1), std::span(&from, 1));
        const vk::BufferImageCopy region{
            .bufferOffset = 0,
            .bufferRowLength = 0,
            .bufferImageHeight = 0,
            .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
            .imageOffset = {.x = 0, .y = 0, .z = 0},
            .imageExtent = {.width = s.extent.width, .height = s.extent.height, .depth = 1}};
        cmd.copyImageToBuffer(s.mask.handle(), L::eTransferSrcOptimal, s.mask_copy.handle(), region);
        sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eTransfer | S::eComputeShader,
                                                .src_access = A::eTransferWrite | A::eShaderWrite,
                                                .dst_stage = S::eHost,
                                                .dst_access = A::eHostRead});
    }
    drew_soft_ = true;
}

void Ground::record_shadows(const frame::Recording& r) {
    if (!frame_.shadows) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const Words& w = *words_;
    if (drew_soft_) {
        // the blurred mask drawn back over the frame at the shadows' slot
        const SoftShadows& s = *pipelines_->soft;
        const style::ThemeBlock& th = r.state.theme;
        Push pc{};
        pc.at = {w.tiles, w.slots, w.theme, w.runs};
        pc.mode = {0U, slot(geo::group::shadow, 0, 0), 0U, 0U};
        pc.colour = {0U, 0U, geo::Rgba{.r = th.shadow.r, .g = th.shadow.g, .b = th.shadow.b, .a = 255}.packed(),
                     s.stride};
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->shadow_soft);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *pipelines_->layout, 3, *pipelines_->soft_set, {});
        cmd.pushConstants<Push>(*pipelines_->layout, frame::push_stages, 0, pc);
        cmd.draw(3, 1, 0, 0);
        r.counters.draw("ground shadows", 1);
        return;
    }
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::shadow, 0, 0), 0U, 0U};
    pc.rows = {static_cast<float>(frame_.sun_x), static_cast<float>(frame_.sun_y), static_cast<float>(frame_.sun_z),
               0.0F};
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->shadow_union);
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    for (std::size_t i = 0; i < frame_.tiles.size(); ++i) {
        const DrawnTile& t = frame_.tiles[i];
        const geo::Words draw = t.ground->layout.shadow_draw;
        if (draw.count == 0 || !t.pass->ranges[0] || t.ground->no_shadows) continue;
        cmd.draw(draw.count, 1, (t.pass->ranges[0].offset / 4) + draw.first, static_cast<std::uint32_t>(i));
        r.counters.draw("ground shadows", draw.count / 3);
    }
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->shadow_composite);
    const style::ThemeBlock& th = r.state.theme;
    pc.rows = {0.0F, 0.0F, static_cast<float>(th.shadow_opacity), 0.0F};
    pc.colour = {0U, 0U, geo::Rgba{.r = th.shadow.r, .g = th.shadow.g, .b = th.shadow.b, .a = 255}.packed(), 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground shadows", 1);
}

void Ground::emit_shadows(const frame::FrameState& state, twin::Stream& out) const {
    if (!frame_.shadows || !state.view) return;
    const style::ThemeBlock& th = state.theme;
    const view::Camera& cam = state.view->camera();
    TwinPlacer pr(state, cam);
    const sun::Sun light{.x = frame_.sun_x, .y = frame_.sun_y, .z = frame_.sun_z};
    // the port's polygons, per tile
    struct Cast {
        std::uint32_t tile;
        sun::TileShadows shadows;
    };
    std::vector<Cast> all;
    for (std::uint32_t t = 0; t < frame_.tiles.size(); ++t) {
        const geo::GroundTile& g = *frame_.tiles[t].ground;
        if (g.casters == nullptr || g.no_shadows) continue;
        all.push_back({.tile = t, .shadows = sun::shadows_of(g.casters->solids, g.casters->trees, light, {})});
    }
    // the polygons in the renders' loops' order over every tile: S['buildings'], S['parts'], then S['trees']
    struct Polygon {
        std::uint64_t order;
        std::uint32_t cast;
        std::uint32_t index;
    };
    std::vector<Polygon> polygons;
    for (std::uint32_t c = 0; c < all.size(); ++c) {
        const geo::GroundTile& g = *frame_.tiles[all[c].tile].ground;
        const sun::TileShadows& s = all[c].shadows;
        for (std::uint32_t p = 0; p < s.size(); ++p) {
            const bool tree = s.kind[p] == sun::ShadowKind::tree;
            const std::vector<std::uint64_t>& order = tree ? g.tree_order : g.solid_order;
            const std::uint32_t owner = s.owner[p];
            polygons.push_back(
                {.order = owner < order.size() ? order[owner] : ~std::uint64_t{0}, .cast = c, .index = p});
        }
    }
    std::ranges::stable_sort(polygons, {}, &Polygon::order);
    std::vector<geo::P2> ring;
    std::vector<twin::Point> points;
    std::vector<std::uint32_t> ends;
    std::vector<view::Vec3> cs;
    std::vector<view::Vec2> sp;
    for (const Polygon& q : polygons) {
        const DrawnTile& t = frame_.tiles[all[q.cast].tile];
        const geo::GroundTile& g = *t.ground;
        const sun::TileShadows& s = all[q.cast].shadows;
        const std::uint32_t p = q.index;
        const bool tree = s.kind[p] == sun::ShadowKind::tree;
        // the radius cut by the caster's centroid or trunk
        if (frame_.engine) {
            geo::P2 at;
            if (tree) {
                at = {.x = g.casters->trees.x[s.owner[p]], .z = g.casters->trees.z[s.owner[p]]};
            } else {
                const std::span<const double> xs = g.casters->solids.xs(s.owner[p]);
                const std::span<const double> zs = g.casters->solids.zs(s.owner[p]);
                ring.clear();
                for (std::size_t k = 0; k < xs.size(); ++k) ring.push_back({xs[k], zs[k]});
                at = geo::centroid(ring);
            }
            if (far(at.x, at.z, frame_.target_x, frame_.target_z, frame_.radius)) continue;
        }
        // cam.poly(..., margin): kept where its near-clipped projection reaches within the margin
        ring.clear();
        const std::span<const double> xs = s.xs(p);
        const std::span<const double> zs = s.zs(p);
        for (std::size_t k = 0; k < xs.size(); ++k) ring.push_back({xs[k], zs[k]});
        const ProjectedPoly pp = project_poly(cam, ring, s.y[p], tree ? 40.0 : 900.0, cs, sp);
        if (pp.points < 3 || !pp.inside) continue;
        if (pr.ring(t, ring, s.y[p], points) >= 3) {
            ends.push_back(static_cast<std::uint32_t>(points.size()));
        }
    }
    // `<g opacity="%.2f" fill="..." filter="url(#sblur)">` around one path each (the blur where the moment has one)
    if (ends.empty()) return;
    const std::int32_t blur = th.shadow_blur > 0 ? out.filter("sblur") : twin::none;
    const std::int32_t group = out.group(
        {.node = {.opacity = static_cast<float>(printed(th.shadow_opacity, 2)), .filter = blur}, .parent = out.root()});
    out.at(slot(geo::group::shadow, 0, 0), group);
    twin::Style style;
    style.fill = twin::Paint::colour(argb(th.shadow));
    std::uint32_t start = 0;
    for (const std::uint32_t e : ends) {
        out.path(style, std::span<const twin::Point>(points).subspan(start, e - start), true);
        start = e;
    }
}

} // namespace atlas::passes
