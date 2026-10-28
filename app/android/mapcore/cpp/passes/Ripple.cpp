// The water's ripple (passes/Ground.hpp; ENGINE.md step 4, the effects; render/amsterdam.py:479 water_layer's
// `ripple`, R/Amsterdam.kt waterLayer): the reflections' group `<g filter="url(#ripple)" opacity=".92">` (.95 at
// night) inside the water's clip, its filter as SvgFilterRaster runs it (fx/Ripple.hpp): the group drawn, held in
// linear RGB, displaced by the ripple's field (the CPU's screen field, fx/Fields.hpp) by '%.1f' of 0.8 + wind · 0.17,
// blurred by WebKit's boxes for .1 and .45, written back as sRGB.
//
// Where the fields are bound in full mode (FRAME_FIELDS: the filters on), each frame with reflections, before the main
// pass (phase_ripple):
//   1. the reflections are drawn as the group's layer holds them (each its own colour at its own opacity, in the
//      renders' order, at its slot's depth so the faces of a path blend once) into 4x samples of the view's upright
//      device px, resolved into the layer: premultiplied sRGB, as the port's raster draws the group;
//   2. ripple_displace_cs moves each pixel by the field (its 256 offsets this frame are a table in the words, made with
//      an exact fma: fx::ripple_displacement), in linear RGB;
//   3. ripple_square_cs (vImage's three square boxes, where both of WebKit's sizes are equal: the likeness's density)
//      or ripple_line_cs (WebKit's separable passes) blur the result, back and forth between the two buffers;
// and in the main pass, at the group's first slot inside the water's clip, one triangle over the frame (ripple_fs)
// writes the result back as sRGB at the group's opacity, premultiplied. What differs from the port: the layer's
// edges (4x samples against the port's coverage of each path), the composite's rounding (the blend unit's against
// Skia's 8-bit arithmetic), and the reflections' trees' own `leaf` filter (still to come). Elsewhere (structural mode,
// before the fields come, the owner mode) the reflections are drawn unrippled as before.
#include "fx/Ripple.hpp"

#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "fx/Blur.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/ground/ripple.hpp"
#include "gen/spirv/ground/ripple_water.hpp"
#include "gpu/Descriptors.hpp"
#include "passes/Ground.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <limits>

namespace atlas::passes {

namespace geo = atlas::geometry;

namespace {

using Push = layouts::ground_ground::PushConstants;
using RipplePush = layouts::ground_ripple::RipplePush;

/// The stages' workgroups: 8 × 8 pixels (ripple.slang's numthreads).
constexpr std::uint32_t group_side = 8;

/// A reflection's record in the words (ground.slang's QUAY_WORDS).
constexpr std::size_t reflection_words = 16;

/// The fused stages' tile (ripple_fused_cs: a workgroup's result pixels a side) and the most the blur passes' reaches
/// may sum to along an axis for them (ripple.slang's RIPPLE_TILE and RIPPLE_REACH).
constexpr std::uint32_t ripple_tile = 16;
constexpr std::int32_t ripple_reach = 8;

/// `b` grown by `dx` and `dy` either side, within the `w` × `h` frame.
[[nodiscard]] PixelBox grown(PixelBox b, std::int32_t dx, std::int32_t dy, std::int32_t w, std::int32_t h) noexcept {
    return {.x0 = std::max(0, b.x0 - dx),
            .y0 = std::max(0, b.y0 - dy),
            .x1 = std::min(w, b.x1 + dx),
            .y1 = std::min(h, b.y1 + dy)};
}

/// A box as the shaders take it: x0, y0, x1, y1.
[[nodiscard]] std::array<std::int32_t, 4> corners(const PixelBox& b) noexcept { return {b.x0, b.y0, b.x1, b.y1}; }

[[nodiscard]] bool turned(vk::SurfaceTransformFlagBitsKHR t) noexcept {
    using T = vk::SurfaceTransformFlagBitsKHR;
    return t == T::eRotate90 || t == T::eRotate270 || t == T::eHorizontalMirrorRotate90 ||
           t == T::eHorizontalMirrorRotate270;
}

} // namespace

const RippleLayer* Ground::ripple_layer() const noexcept {
    return pipelines_ != nullptr ? pipelines_->ripple.get() : nullptr;
}

bool Ground::rippled(const frame::FrameState& state) const noexcept {
    // a filter of the renders: in full mode alone, where the fields are bound
    return !frame_.reflections.empty() && !no_ripple_ && state.mode == frame::Mode::full &&
           (state.block.info.x & shaders::FRAME_FIELDS) != 0U && pipelines_ != nullptr && pipelines_->ripple != nullptr;
}

void Ground::write_ripple(std::vector<std::uint32_t>& words, const frame::FrameState& state) {
    Words& w = *words_;
    w.ripple_offsets = static_cast<std::uint32_t>(words.size());
    w.ripple_plan = 0;
    w.leaf_offsets = 0;
    if (!rippled(state)) {
        ripple_key_.clear();
        return;
    }
    // DisplacementNode's srcX = x + fma(scaleForColor, channel, offset).toInt(), for each of the map's 256 levels.
    // Where the ripple lies on the water (the app's map), its scale goes with the zoom as its field does: the
    // Westerkerk home's 430 m over the camera's distance, within .25 and 2.5, so nearer water wobbles more
    const float zoom = state.grounded && state.camera.distance > 0
                           ? static_cast<float>(std::clamp(430.0 / state.camera.distance, 0.25, 2.5))
                           : 1.0F;
    const fx::RippleDisplacement d =
        fx::ripple_displacement(fx::ripple_scale(state.theme.wind) * zoom, static_cast<float>(state.camera.density));
    for (std::uint32_t c = 0; c < 256; ++c) {
        const auto moved = static_cast<std::int32_t>(std::fma(d.factor, static_cast<float>(c), d.offset));
        words.push_back(std::bit_cast<std::uint32_t>(moved));
    }
    // the reflected trees' leaf filter (amsterdam.py's `leaf`, scale 2.6) at the same filter scale: its 256 offsets,
    // then its reach (reflection_vs and reflection_fs, into the layer)
    w.leaf_offsets = static_cast<std::uint32_t>(words.size());
    const fx::RippleDisplacement leaf = fx::ripple_displacement(2.6F, static_cast<float>(state.camera.density));
    for (std::uint32_t c = 0; c < 256; ++c) {
        const auto moved = static_cast<std::int32_t>(std::fma(leaf.factor, static_cast<float>(c), leaf.offset));
        words.push_back(std::bit_cast<std::uint32_t>(moved));
    }
    words.push_back(static_cast<std::uint32_t>(leaf.reach));
    // on the water (the app's map): the water's map the ripple reads its field by, as the glints read theirs
    // (passes::water_map), so the ripple keeps its place on the water while the map moves
    w.ripple_map = 0;
    if (state.grounded && state.view) {
        w.ripple_map = static_cast<std::uint32_t>(words.size());
        const std::array<float, 9> map = water_map(state.view->camera(), water_level(frame_), state.camera.density,
                                                   state.camera.width_px, state.camera.height_px);
        for (const float v : map) words.push_back(std::bit_cast<std::uint32_t>(v));
    }
    // The result is a function of what the layer's draw and the stages read: the camera (the frame's size and density
    // with it), the tiles' places, the reflections' records and the offsets (the fields and the byte tables are the
    // size's). Where they are the last result's, it stands (a map at rest draws frames for other passes' sake).
    std::vector<std::uint32_t> key(sizeof(shaders::FrameCamera) / sizeof(std::uint32_t));
    std::memcpy(key.data(), &state.block.camera, sizeof(shaders::FrameCamera));
    const auto add = [&](std::uint32_t first, std::size_t n) {
        key.insert(key.end(), words.begin() + first, words.begin() + static_cast<std::ptrdiff_t>(first + n));
    };
    add(w.tiles, w.slots - w.tiles);
    add(w.reflections, std::size_t{w.reflection_count} * reflection_words);
    add(w.ripple_offsets, 256);
    add(w.leaf_offsets, 257);
    if (w.ripple_map != 0) add(w.ripple_map, 9);
    ripple_stale_ = key != ripple_key_;
    ripple_key_ = std::move(key);
    // what the stages cover: the layer's content in the frame (each face as the vertex stage clips and projects it,
    // the trees' circles, the streaks' ellipses, grown by the faces' strokes and two pixels of slack), the result's
    // region (that content moved by the displacement's reach and spread by the boxes'), and backwards from it each
    // pass's and the displacement's region, each what the next one reads
    RipplePlan& plan = ripple_plan_;
    plan = {};
    if (!state.view) return;
    const view::Camera& cam = state.view->camera();
    const double density = state.camera.density;
    double min_x = std::numeric_limits<double>::infinity();
    double min_y = min_x;
    double max_x = -min_x;
    double max_y = -min_x;
    const auto take = [&](double x, double y) {
        min_x = std::min(min_x, x);
        min_y = std::min(min_y, y);
        max_x = std::max(max_x, x);
        max_y = std::max(max_y, y);
    };
    std::vector<view::Vec3> cs;
    for (const Reflection& e : frame_.reflections) {
        if (e.kind == Reflection::Kind::streak) {
            const ReflectionStreak& k = frame_.reflection_streaks[e.first];
            take(k.cx - k.rx, k.cy - k.ry);
            take(k.cx + k.rx, k.cy + k.ry);
            continue;
        }
        if (e.kind == Reflection::Kind::tree) {
            for (std::uint32_t k = 0; k < e.count; ++k) {
                const ReflectionCircle& c = frame_.reflection_circles[e.first + k];
                take(c.cx - c.r, c.cy - c.r);
                take(c.cx + c.r, c.cy + c.r);
            }
            continue;
        }
        for (std::uint32_t k = 0; k < e.count; ++k) {
            const ReflectionFace& face = frame_.reflection_faces[e.first + k];
            cs.clear();
            const view::Vec3 first = cam.cam(face.q[0]);
            view::Vec3 a = first;
            for (std::uint32_t i = 1; i < face.n; ++i) {
                const view::Vec3 b = cam.cam(face.q[i]);
                ProjectedPoly::clip_edge(a, b, cam.near(), cs);
                a = b;
            }
            ProjectedPoly::clip_edge(a, first, cam.near(), cs);
            for (const view::Vec3& c : cs) {
                const view::Vec2 q = cam.pc(c);
                take(q.x, q.y);
            }
        }
    }
    const vk::Extent2D extent = pipelines_->ripple->extent;
    const auto fw = static_cast<std::int32_t>(extent.width);
    const auto fh = static_cast<std::int32_t>(extent.height);
    if (min_x > max_x || min_y > max_y) return; // no content
    const double pad = (0.15 * density) + 2;
    const auto device = [&](double v, bool up) {
        const double d = up ? std::ceil((v * density) + pad) : std::floor((v * density) - pad);
        return static_cast<std::int32_t>(std::clamp(d, -1e9, 1e9));
    };
    plan.layer = grown(
        {.x0 = device(min_x, false), .y0 = device(min_y, false), .x1 = device(max_x, true), .y1 = device(max_y, true)},
        0, 0, fw, fh);
    if (plan.layer.empty()) return;
    const fx::RippleBlur blur = fx::ripple_blur(static_cast<float>(density));
    std::vector<std::array<std::int32_t, 2>> reach;
    if (blur.square()) {
        const auto half = static_cast<std::int32_t>(blur.side() / 2);
        for (int k = 0; k < 3; ++k) {
            plan.passes.push_back({.square = true, .side = blur.side()});
            reach.push_back({half, half});
        }
    } else {
        const std::array<fx::Run, 2> runs{fx::webkit_boxes(blur.x), fx::webkit_boxes(blur.y)};
        for (std::uint32_t k = 0; k < 3; ++k) {
            for (std::uint32_t axis = 0; axis < 2; ++axis) {
                if (k >= runs[axis].count) continue;
                const fx::Box b = runs[axis].boxes[k];
                plan.passes.push_back({.square = false, .axis = axis, .left = b.left, .right = b.right});
                const auto r = static_cast<std::int32_t>(std::max(b.left, b.right - 1));
                reach.push_back(axis == 0 ? std::array<std::int32_t, 2>{r, 0} : std::array<std::int32_t, 2>{0, r});
            }
        }
    }
    std::int32_t spread_x = d.reach;
    std::int32_t spread_y = d.reach;
    for (const auto& r : reach) {
        spread_x += r[0];
        spread_y += r[1];
        plan.rx += r[0];
        plan.ry += r[1];
    }
    plan.out = grown(plan.layer, spread_x, spread_y, fw, fh);
    PixelBox region = plan.out;
    for (std::size_t k = plan.passes.size(); k-- > 0;) {
        plan.passes[k].region = region;
        region = grown(region, reach[k][0], reach[k][1], fw, fh);
    }
    plan.displace = region;
    // The plan as ripple_fused_cs reads it: the passes' count and their reaches summed, the displacement's and the
    // result's regions, then each pass's kind (0 square, 1 line), side or axis, left, right, 1 / (left + right) as the
    // stages apart take it, and region.
    w.ripple_plan = static_cast<std::uint32_t>(words.size());
    const auto box = [&](const PixelBox& b) {
        for (const std::int32_t v : corners(b)) words.push_back(std::bit_cast<std::uint32_t>(v));
    };
    words.push_back(static_cast<std::uint32_t>(plan.passes.size()));
    words.push_back(static_cast<std::uint32_t>(plan.rx));
    words.push_back(static_cast<std::uint32_t>(plan.ry));
    box(plan.displace);
    box(plan.out);
    for (const RipplePass& pass : plan.passes) {
        words.push_back(pass.square ? 0U : 1U);
        words.push_back(pass.square ? pass.side : pass.axis);
        words.push_back(pass.left);
        words.push_back(pass.right);
        words.push_back(pass.square ? 0U
                                    : std::bit_cast<std::uint32_t>(1.0F / static_cast<float>(pass.left + pass.right)));
        box(pass.region);
    }
}

gpu::Result<void> Ground::make_ripple(const frame::PassContext& context, Pipelines& p) const {
    const gpu::Device& device = context.device;
    const vk::raii::Device& d = device.handle();
    auto s = std::make_unique<RippleLayer>();
    s->kept = keep_ripple_;
    // the view's device px, the output's turned back: the fields' pixels
    vk::Extent2D view = context.output.extent();
    if (turned(context.output.transform())) std::swap(view.width, view.height);
    s->extent = view;
    using U = vk::ImageUsageFlagBits;
    auto image = [&](vk::Format format, vk::SampleCountFlagBits samples, vk::ImageUsageFlags usage,
                     gpu::MemoryUse use) {
        return device.memory().image({.imageType = vk::ImageType::e2D,
                                      .format = format,
                                      .extent = {.width = view.width, .height = view.height, .depth = 1},
                                      .mipLevels = 1,
                                      .arrayLayers = 1,
                                      .samples = samples,
                                      .tiling = vk::ImageTiling::eOptimal,
                                      .usage = usage,
                                      .initialLayout = vk::ImageLayout::eUndefined},
                                     use);
    };
    // the samples and their depths in lazily allocated memory where the frame's are (no pages on a tiler), else, or
    // where that fails, in ordinary memory
    const bool lazy = context.pass.spec().config.lazy();
    const vk::Format colour = vk::Format::eR8G8B8A8Unorm;
    const vk::Format depth_format = context.pass.spec().depth_stencil;
    auto transient = [&](vk::Format format, vk::ImageUsageFlags usage) -> gpu::Result<gpu::Image> {
        auto made = image(format, vk::SampleCountFlagBits::e4, usage | U::eTransientAttachment,
                          lazy ? gpu::MemoryUse::lazy : gpu::MemoryUse::gpu);
        if (!made && lazy) made = image(format, vk::SampleCountFlagBits::e4, usage, gpu::MemoryUse::gpu);
        return made;
    };
    ATLAS_GPU_LET(samples, transient(colour, U::eColorAttachment));
    s->samples = std::move(samples);
    ATLAS_GPU_LET(depth, transient(depth_format, U::eDepthStencilAttachment));
    s->depth = std::move(depth);
    ATLAS_GPU_LET(layer, image(colour, vk::SampleCountFlagBits::e1,
                               U::eColorAttachment | U::eSampled | (s->kept ? U::eTransferSrc : vk::ImageUsageFlags{}),
                               gpu::MemoryUse::gpu));
    s->layer = std::move(layer);
    auto view_of = [&](const gpu::Image& i, vk::Format format,
                       vk::ImageAspectFlags aspect) -> gpu::Result<vk::raii::ImageView> {
        ATLAS_VK_LET(v,
                     d.createImageView({.image = i.handle(),
                                        .viewType = vk::ImageViewType::e2D,
                                        .format = format,
                                        .subresourceRange = {.aspectMask = aspect, .levelCount = 1, .layerCount = 1}}),
                     "the ripple's layer's view");
        return std::move(v);
    };
    using Aspect = vk::ImageAspectFlagBits;
    ATLAS_GPU_LET(samples_view, view_of(s->samples, colour, Aspect::eColor));
    s->samples_view = std::move(samples_view);
    ATLAS_GPU_LET(depth_view, view_of(s->depth, depth_format, Aspect::eDepth));
    s->depth_view = std::move(depth_view);
    ATLAS_GPU_LET(layer_view, view_of(s->layer, colour, Aspect::eColor));
    s->layer_view = std::move(layer_view);
    // the layer's pass: the samples and their depths cleared and drawn, the samples resolved into the layer; record
    // moves the layer into the colour layout before it and out after it
    using L = vk::ImageLayout;
    const std::array<vk::AttachmentDescription, 3> attachments{{
        {.format = colour,
         .samples = vk::SampleCountFlagBits::e4,
         .loadOp = vk::AttachmentLoadOp::eClear,
         .storeOp = vk::AttachmentStoreOp::eDontCare,
         .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
         .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
         .initialLayout = L::eUndefined,
         .finalLayout = L::eColorAttachmentOptimal},
        {.format = depth_format,
         .samples = vk::SampleCountFlagBits::e4,
         .loadOp = vk::AttachmentLoadOp::eClear,
         .storeOp = vk::AttachmentStoreOp::eDontCare,
         .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
         .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
         .initialLayout = L::eUndefined,
         .finalLayout = L::eDepthStencilAttachmentOptimal},
        {.format = colour,
         .samples = vk::SampleCountFlagBits::e1,
         .loadOp = vk::AttachmentLoadOp::eDontCare,
         .storeOp = vk::AttachmentStoreOp::eStore,
         .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
         .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
         .initialLayout = L::eColorAttachmentOptimal,
         .finalLayout = L::eColorAttachmentOptimal},
    }};
    const vk::AttachmentReference colour_ref{.attachment = 0, .layout = L::eColorAttachmentOptimal};
    const vk::AttachmentReference depth_ref{.attachment = 1, .layout = L::eDepthStencilAttachmentOptimal};
    const vk::AttachmentReference resolve_ref{.attachment = 2, .layout = L::eColorAttachmentOptimal};
    const vk::SubpassDescription subpass{.pipelineBindPoint = vk::PipelineBindPoint::eGraphics,
                                         .colorAttachmentCount = 1,
                                         .pColorAttachments = &colour_ref,
                                         .pResolveAttachments = &resolve_ref,
                                         .pDepthStencilAttachment = &depth_ref};
    using PS = vk::PipelineStageFlagBits;
    using AF = vk::AccessFlagBits;
    const std::array<vk::SubpassDependency, 2> dependencies{{
        {.srcSubpass = vk::SubpassExternal,
         .dstSubpass = 0,
         .srcStageMask = PS::eColorAttachmentOutput | PS::eEarlyFragmentTests | PS::eLateFragmentTests,
         .dstStageMask = PS::eColorAttachmentOutput | PS::eEarlyFragmentTests | PS::eLateFragmentTests,
         .srcAccessMask = AF::eColorAttachmentWrite | AF::eDepthStencilAttachmentWrite,
         .dstAccessMask = AF::eColorAttachmentRead | AF::eColorAttachmentWrite | AF::eDepthStencilAttachmentRead |
                          AF::eDepthStencilAttachmentWrite},
        {.srcSubpass = 0,
         .dstSubpass = vk::SubpassExternal,
         .srcStageMask = PS::eColorAttachmentOutput,
         .dstStageMask = PS::eColorAttachmentOutput,
         .srcAccessMask = AF::eColorAttachmentWrite,
         .dstAccessMask = AF::eColorAttachmentRead | AF::eColorAttachmentWrite},
    }};
    ATLAS_VK_LET(pass,
                 d.createRenderPass({.attachmentCount = static_cast<std::uint32_t>(attachments.size()),
                                     .pAttachments = attachments.data(),
                                     .subpassCount = 1,
                                     .pSubpasses = &subpass,
                                     .dependencyCount = static_cast<std::uint32_t>(dependencies.size()),
                                     .pDependencies = dependencies.data()}),
                 "the ripple's layer's render pass");
    s->pass = std::move(pass);
    const std::array<vk::ImageView, 3> views{*s->samples_view, *s->depth_view, *s->layer_view};
    ATLAS_VK_LET(framebuffer,
                 d.createFramebuffer({.renderPass = *s->pass,
                                      .attachmentCount = static_cast<std::uint32_t>(views.size()),
                                      .pAttachments = views.data(),
                                      .width = view.width,
                                      .height = view.height,
                                      .layers = 1}),
                 "the ripple's layer's framebuffer");
    s->framebuffer = std::move(framebuffer);
    // the filter's two buffers (readable where the tests keep them), and the kept layer's copy
    using B = vk::BufferUsageFlagBits;
    const vk::DeviceSize texels = vk::DeviceSize{view.width} * view.height * 4;
    const gpu::MemoryUse look = s->kept ? gpu::MemoryUse::readback : gpu::MemoryUse::gpu;
    for (gpu::Buffer& b : s->buffers) {
        ATLAS_GPU_LET(made, device.memory().buffer(texels, B::eStorageBuffer, look));
        b = std::move(made);
    }
    if (s->kept) {
        ATLAS_GPU_LET(copy, device.memory().buffer(texels, B::eTransferDst, gpu::MemoryUse::readback));
        s->layer_copy = std::move(copy);
    }
    // the composite reads both from the soft shadows' sets (the ground's set 3, the same two in each frame slot's)
    for (const vk::raii::DescriptorSet& set : p.soft_sets) {
        gpu::SetWriter(*set)
            .buffer(3, vk::DescriptorType::eStorageBuffer, s->buffers[0].handle())
            .buffer(4, vk::DescriptorType::eStorageBuffer, s->buffers[1].handle())
            .apply(d);
    }
    // the stages' set: the layer, a buffer to read and one to write, both ways
    constexpr auto in_compute = vk::ShaderStageFlagBits::eCompute;
    const std::array bindings{
        gpu::Binding{.slot = 0, .type = vk::DescriptorType::eSampledImage, .stages = in_compute},
        gpu::Binding{.slot = 1, .type = vk::DescriptorType::eStorageBuffer, .stages = in_compute},
        gpu::Binding{.slot = 2, .type = vk::DescriptorType::eStorageBuffer, .stages = in_compute}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    s->set_layout = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, static_cast<std::uint32_t>(s->sets.size())));
    s->pool = std::move(pool);
    for (std::size_t i = 0; i < s->sets.size(); ++i) {
        ATLAS_GPU_LET(set, gpu::allocate_set(d, s->pool, s->set_layout));
        s->sets[i] = std::move(set);
        gpu::SetWriter(*s->sets[i])
            .image(0, vk::DescriptorType::eSampledImage, *s->layer_view, L::eShaderReadOnlyOptimal)
            .buffer(1, vk::DescriptorType::eStorageBuffer, s->buffers[i].handle())
            .buffer(2, vk::DescriptorType::eStorageBuffer, s->buffers[1 - i].handle())
            .apply(d);
    }
    const std::array<vk::DescriptorSetLayout, 4> sets{
        context.layout.frame_set_layout(), context.layout.global_set_layout(), *p.set_layout, *s->set_layout};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, sizeof(RipplePush), in_compute));
    s->compute_layout = std::move(compute_layout);
    ATLAS_GPU_LET(module, frame::make_module(context, "ground_ripple", spirv::ground_ripple));
    s->module = std::move(module);
    auto compute = [&](std::string_view name, const char* entry) {
        return frame::make_compute(context,
                                   {.name = name, .module = "ground_ripple", .configuration = context.configuration},
                                   *s->module, entry, *s->compute_layout);
    };
    ATLAS_GPU_LET(displace, compute("ground ripple displace", "ripple_displace_cs"));
    s->displace = std::move(displace);
    ATLAS_GPU_LET(square, compute("ground ripple square", "ripple_square_cs"));
    s->square = std::move(square);
    ATLAS_GPU_LET(line, compute("ground ripple line", "ripple_line_cs"));
    s->line = std::move(line);
    ATLAS_GPU_LET(fused, compute("ground ripple fused", "ripple_fused_cs"));
    s->fused = std::move(fused);
    // the displacement on the water (the app's map), apart and fused
    ATLAS_GPU_LET(water_module, frame::make_module(context, "ground_ripple_water", spirv::ground_ripple_water));
    s->water_module = std::move(water_module);
    auto water = [&](std::string_view name, const char* entry) {
        return frame::make_compute(
            context, {.name = name, .module = "ground_ripple_water", .configuration = context.configuration},
            *s->water_module, entry, *s->compute_layout);
    };
    ATLAS_GPU_LET(water_displace, water("ground ripple water displace", "ripple_displace_cs"));
    s->water_displace = std::move(water_displace);
    ATLAS_GPU_LET(water_fused, water("ground ripple water fused", "ripple_fused_cs"));
    s->water_fused = std::move(water_fused);
    // the reflections into the samples: each its own colour, source over, at its slot's depth
    gpu::GraphicsDesc layer_draw = ground_desc(context, p, "ground ripple layer", "reflection_vs", "reflection_fs");
    layer_draw.target = {
        .render_pass = *s->pass, .subpass = 0, .samples = vk::SampleCountFlagBits::e4, .colour_count = 1};
    translucent(layer_draw);
    layer_draw.depth_write = true;
    ATLAS_GPU_LET(draw, frame::make_pipeline(context, layer_draw, frame::Overdraw::none));
    s->draw = std::move(draw);
    p.ripple = std::move(s);
    return {};
}

void Ground::record_ripple_layer(const frame::Recording& r) {
    const bool drew = drew_ripple_;
    drew_ripple_ = false;
    const Words& w = *words_;
    if (!rippled(r.state) || w.reflection_count == 0) return;
    if (drew && !ripple_stale_) {
        // the last frame's result stands: the buffers hold it, and the composite reads them again
        drew_ripple_ = true;
        return;
    }
    Pipelines& p = *pipelines_;
    RippleLayer& s = *p.ripple;
    const RipplePlan& plan = ripple_plan_;
    s.out = plan.out;
    s.drawn = plan.layer;
    if (plan.out.empty()) {
        // nothing of the layer in the frame: its result is transparent, and the composite draws nothing
        drew_ripple_ = true;
        return;
    }
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const gpu::Sync& sync = r.frame.sync;
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    using L = vk::ImageLayout;

    // 1. the reflections into the layer, which the last frame's stages read
    sync.barrier(cmd, gpu::ImageDependency{.image = s.layer.handle(),
                                           .src_stage = S::eComputeShader | S::eTransfer,
                                           .src_access = {},
                                           .dst_stage = S::eColorAttachmentOutput,
                                           .dst_access = A::eColorAttachmentWrite,
                                           .old_layout = L::eUndefined,
                                           .new_layout = L::eColorAttachmentOptimal});
    std::array<vk::ClearValue, 3> clear{};
    clear[0].color = vk::ClearColorValue{std::array<float, 4>{0.0F, 0.0F, 0.0F, 0.0F}};
    clear[1].depthStencil = vk::ClearDepthStencilValue{.depth = 0.0F, .stencil = 0};
    // over the layer's content alone: the stages read nothing of it beyond
    const vk::Rect2D area{.offset = {.x = plan.layer.x0, .y = plan.layer.y0},
                          .extent = {.width = static_cast<std::uint32_t>(plan.layer.x1 - plan.layer.x0),
                                     .height = static_cast<std::uint32_t>(plan.layer.y1 - plan.layer.y0)}};
    cmd.beginRenderPass({.renderPass = *s.pass,
                         .framebuffer = *s.framebuffer,
                         .renderArea = area,
                         .clearValueCount = static_cast<std::uint32_t>(clear.size()),
                         .pClearValues = clear.data()},
                        vk::SubpassContents::eInline);
    cmd.setViewport(0, vk::Viewport{.x = 0,
                                    .y = 0,
                                    .width = static_cast<float>(s.extent.width),
                                    .height = static_cast<float>(s.extent.height),
                                    .minDepth = 0,
                                    .maxDepth = 1});
    cmd.setScissor(0, area);
    const auto frame_sets = r.layout.sets();
    const auto offsets = r.layout.offsets(r.frame.slot.slot);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.draw);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *p.layout, 0, frame_sets, offsets);
    const std::array<vk::DescriptorSet, 2> own{*p.sets[w.slot], *p.soft_sets[w.slot]};
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *p.layout, 2, own, {});
    const tiles::ReflectionsStyle& rs = frame_.tiles[frame_.reflections.front().tile].ground->reflection_style;
    Push pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {ground_flag::layer | (frame_.night ? ground_flag::night : 0U), 0U, w.reflections, 4U};
    // the window rows' opacity (their path's, in the group)
    pc.rows = {0.0F, 0.0F, 1.0F, static_cast<float>(rs.windows_opacity)};
    // the leaf filter's offsets and reach, for the trees' circles
    pc.colour = {w.leaf_offsets, 0U, 0U, 0U};
    cmd.pushConstants<Push>(*p.layout, frame::push_stages, 0, pc);
    cmd.draw(6, w.reflection_count, 0, 0);
    r.counters.draw("ripple layer", std::uint64_t{w.reflection_count} * 2, w.reflection_count);
    cmd.endRenderPass();
    sync.barrier(cmd, gpu::ImageDependency{.image = s.layer.handle(),
                                           .src_stage = S::eColorAttachmentOutput,
                                           .src_access = A::eColorAttachmentWrite,
                                           .dst_stage = S::eComputeShader,
                                           .dst_access = A::eShaderRead,
                                           .old_layout = L::eColorAttachmentOptimal,
                                           .new_layout = L::eShaderReadOnlyOptimal});

    if (fuse_ripple_ && plan.rx <= ripple_reach && plan.ry <= ripple_reach) {
        // 2. the displacement and the boxes in one dispatch, a tile of the result a workgroup (ripple_fused_cs), into
        // the buffer the last frame's composite did not read: the frame before it, which may have, has finished
        const std::uint32_t t = 1U - s.result;
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 0, frame_sets, offsets);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 2, *p.sets[w.slot], {});
        // set [i] reads buffer i and writes the other: set [1 - t] writes buffer t
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 3, *s.sets[1U - t], {});
        RipplePush rp{};
        rp.size = {s.extent.width, s.extent.height, w.ripple_offsets, w.ripple_plan};
        rp.box = {0U, 0U, 0U, w.ripple_map};
        const std::array<std::int32_t, 4> layer_box = corners(plan.layer);
        rp.layer = {layer_box[0], layer_box[1], layer_box[2], layer_box[3]};
        const std::array<std::int32_t, 4> out_box = corners(plan.out);
        rp.region = {out_box[0], out_box[1], out_box[2], out_box[3]};
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, w.ripple_map != 0 ? *s.water_fused : *s.fused);
        cmd.pushConstants<RipplePush>(*s.compute_layout, vk::ShaderStageFlagBits::eCompute, 0, rp);
        const auto tiles = [](std::int32_t n) {
            return (static_cast<std::uint32_t>(n) + ripple_tile - 1) / ripple_tile;
        };
        cmd.dispatch(tiles(plan.out.x1 - plan.out.x0), tiles(plan.out.y1 - plan.out.y0), 1);
        r.counters.dispatch("ripple fused");
        sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                .src_access = A::eShaderWrite,
                                                .dst_stage = S::eComputeShader | S::eFragmentShader,
                                                .dst_access = A::eShaderRead | A::eShaderWrite});
        s.result = t;
        s.ran_fused = true;
    } else {
        s.ran_fused = false;
        // the stages apart, where the boxes reach too far for the fused stages' shared memory
        // 2. the displacement into the first buffer, which the last frame's composite read
        sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eFragmentShader | S::eComputeShader,
                                                .src_access = {},
                                                .dst_stage = S::eComputeShader,
                                                .dst_access = A::eShaderWrite});
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 0, frame_sets, offsets);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 2, *p.sets[w.slot], {});
        RipplePush rp{};
        rp.size = {s.extent.width, s.extent.height, w.ripple_offsets, 0U};
        rp.box = {0U, 0U, 0U, w.ripple_map};
        const std::array<std::int32_t, 4> layer_box = corners(plan.layer);
        rp.layer = {layer_box[0], layer_box[1], layer_box[2], layer_box[3]};
        // a stage over `region` reading buffer `from`, writing the other (set [from] reads it)
        auto stage = [&](const vk::raii::Pipeline& pipeline, const PixelBox& region, std::uint32_t from,
                         const char* counter) {
            const std::array<std::int32_t, 4> c = corners(region);
            rp.region = {c[0], c[1], c[2], c[3]};
            cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *pipeline);
            cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 3, *s.sets[from], {});
            cmd.pushConstants<RipplePush>(*s.compute_layout, vk::ShaderStageFlagBits::eCompute, 0, rp);
            const auto groups = [](std::int32_t n) {
                return (static_cast<std::uint32_t>(n) + group_side - 1) / group_side;
            };
            cmd.dispatch(groups(region.x1 - region.x0), groups(region.y1 - region.y0), 1);
            r.counters.dispatch(counter);
            sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                    .src_access = A::eShaderWrite,
                                                    .dst_stage = S::eComputeShader | S::eFragmentShader,
                                                    .dst_access = A::eShaderRead | A::eShaderWrite});
            return 1U - from; // the buffer it wrote
        };
        std::uint32_t result =
            stage(w.ripple_map != 0 ? s.water_displace : s.displace, plan.displace, 1, "ripple displace");
        // 3. the blur: vImage's square boxes where WebKit's two sizes are equal, else its separable passes, x then y
        for (const RipplePass& pass : plan.passes) {
            if (pass.square) {
                rp.box = {pass.side, 0U, 0U, 0U};
                result = stage(s.square, pass.region, result, "ripple square");
            } else {
                rp.box = {pass.axis, pass.left, pass.right, 0U};
                rp.inv = {1.0F / static_cast<float>(pass.left + pass.right), 0.0F, 0.0F, 0.0F};
                result = stage(s.line, pass.region, result, "ripple line");
            }
        }
        s.result = result;
    }
    if (s.kept) {
        // the tests' look: the layer, a row after another (after the last frame's copy), and the buffers readable by
        // the host
        const gpu::MemoryDependency copied{.src_stage = S::eTransfer,
                                           .src_access = A::eTransferWrite,
                                           .dst_stage = S::eTransfer,
                                           .dst_access = A::eTransferWrite};
        const gpu::ImageDependency from{.image = s.layer.handle(),
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
        cmd.copyImageToBuffer(s.layer.handle(), L::eTransferSrcOptimal, s.layer_copy.handle(), region);
        sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eTransfer | S::eComputeShader,
                                                .src_access = A::eTransferWrite | A::eShaderWrite,
                                                .dst_stage = S::eHost,
                                                .dst_access = A::eHostRead});
    }
    drew_ripple_ = true;
}

void Ground::record_ripple(const frame::Recording& r) {
    const Words& w = *words_;
    const RippleLayer& s = *pipelines_->ripple;
    if (s.out.empty()) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const tiles::ReflectionsStyle& rs = frame_.tiles[frame_.reflections.front().tile].ground->reflection_style;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->ripple_composite);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *pipelines_->layout, 3, *pipelines_->soft_sets[w.slot],
                           {});
    Push pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::reflection, 0, 0), s.result, 0U};
    pc.rows = {0.0F, 0.0F, static_cast<float>(frame_.night ? rs.opacity[1] : rs.opacity[0]), 0.0F};
    // the result's region: nothing beyond it
    const auto pack = [](std::int32_t x, std::int32_t y) {
        return static_cast<std::uint32_t>(x) | (static_cast<std::uint32_t>(y) << 16U);
    };
    pc.colour = {s.extent.width, pack(s.out.x0, s.out.y0), pack(s.out.x1, s.out.y1), 0U};
    cmd.pushConstants<Push>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground ripple", 1);
}

} // namespace atlas::passes
