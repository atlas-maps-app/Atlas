// The lit streets at night (passes/Ground.hpp; ENGINE.md step 4, the night; render/amsterdam.py:821's lit=yes streets,
// R/Amsterdam.kt nightLight): each lit road's runs of points ahead of the near plane, within the radius cut, stroked
// at the road's width at their mean depth, in S['roads']'s order across the drawn tiles, as render's night light
// appends them to `pools` after the quay walls, before the lamps' pools:
//   `<g fill="none" stroke="#FFB866" stroke-linecap="round" stroke-linejoin="round" opacity=".3" (.4 without weather)
//   filter="url(#litglow)" style="mix-blend-mode:screen">`, each run `<path d="M…" stroke-width="%.1f"/>`, the width
//   max(0.8, (w + 1, or 3 without weather) · focal / the run's mean depth);
//   on a wet night their cores, `<g fill="none" stroke="#FFE2B0" … opacity=".22" style="mix-blend-mode:screen">`, the
//   same paths at '%.1f' of their printed widths times 0.3, unfiltered.
// At eye level the port strokes no runs (it fills a quad a segment, each road's path blurred on its own): not drawn,
// as the app's camera never reaches eye level.
//
// On the GPU, before the main pass (phase_lit), the runs are drawn as the group's layer holds them: each segment of a
// run an instance (ground.slang's lit_vs and lit_fs: the ground's lines' coverage, round-capped and round-joined, in
// the group's colour, opaque) into 4x samples of the view's upright device px and a margin about them, resolved into
// the glow's layer; then the filter runs as SvgFilterRaster runs it (fx/LitGlow.hpp): litglow.slang's three square
// boxes over its input, the first holding the layer in linear RGB; on a wet night the cores are drawn alike into a
// layer of their own. In the main pass, at the pools group's first two slots, one triangle over the frame each
// (lit_composite_fs) screens the glow's result, written back as sRGB, at its group's opacity, and the cores' layer at
// .22. Where the filters are off (structural mode, before the byte tables come) the glow's layer is screened as it is,
// as the port's raster draws a filtered group with its filters off. What differs from the port: the layers' edges (4x
// samples against the port's coverage of each path), the composite's rounding (the blend unit's against Skia's 8-bit
// arithmetic), and a renderer's issue fixed: the port's raster, as WebKit's, lowers a filter's resolution where its
// region would pass 4096² px, and the runs' box reaches far beyond the frame (a road's points just ahead of the near
// plane project thousands of px off it: at the home at night its 158 runs reach from -1,315 to 1,701 CSS px across
// and down to 2,242, against the frame's 412 × 916, the region 9.0 · 10⁷ px), so the renders blur the glow at 0.43 of
// the density there and draw it back in blocks, as far as the off-screen points happen to reach; the engine blurs it
// at the frame's density.
#include "fx/LitGlow.hpp"

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "fx/Blur.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/ground/litglow.hpp"
#include "gpu/Descriptors.hpp"
#include "passes/Ground.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>
#include <limits>
#include <optional>
#include <span>
#include <utility>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;

namespace {

using Push = layouts::ground_ground::PushConstants;
using GlowPush = layouts::ground_litglow::GlowPush;

/// The layers' margin about the view, device px: the filter's input reaches its blur's reach and a pixel beyond the
/// frame (3 · (size / 2 + 1) + 1), within the margin up to a density of about 4.9 (size 24). At a greater density the
/// input stops at the layer's edge, repeated as the filter's input edge is; no pixel of the frame changes while the
/// three boxes' halves reach no further than the margin (size 27: a density of about 5.5).
constexpr std::int32_t glow_margin = 40;
/// The box passes' tile and the most half a box may be (litglow.slang's GLOW_TILE and GLOW_HALF_MOST); a box beyond
/// it (a density over about 6.7) draws the glow unfiltered.
constexpr std::uint32_t glow_tile = 16;
constexpr std::int32_t glow_half_most = 16;
/// The segments of a run an instance word holds (run << 14 | segment).
constexpr std::uint32_t lit_segments_most = 0x4000;
/// render's colours: the glow's and the cores' strokes, opaque in their layers; the cores' group's opacity.
constexpr std::uint32_t glow_rgb = 0xFFB866U;
constexpr std::uint32_t core_rgb = 0xFFE2B0U;
constexpr float core_opacity = 0.22F;

[[nodiscard]] std::uint32_t bits_of(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

/// An RGB literal opaque, RGBA8 packed (r in the low byte).
[[nodiscard]] std::uint32_t opaque(std::uint32_t rgb) noexcept {
    return geo::Rgba{.r = static_cast<std::uint8_t>((rgb >> 16U) & 0xFFU),
                     .g = static_cast<std::uint8_t>((rgb >> 8U) & 0xFFU),
                     .b = static_cast<std::uint8_t>(rgb & 0xFFU),
                     .a = 255}
        .packed();
}

[[nodiscard]] PixelBox box_of(const fx::GlowRect& r) noexcept {
    return {.x0 = r.x0, .y0 = r.y0, .x1 = r.x1, .y1 = r.y1};
}
[[nodiscard]] fx::GlowRect rect_of(const PixelBox& b) noexcept {
    return {.x0 = b.x0, .y0 = b.y0, .x1 = b.x1, .y1 = b.y1};
}

[[nodiscard]] bool turned(vk::SurfaceTransformFlagBitsKHR t) noexcept {
    using T = vk::SurfaceTransformFlagBitsKHR;
    return t == T::eRotate90 || t == T::eRotate270 || t == T::eHorizontalMirrorRotate90 ||
           t == T::eHorizontalMirrorRotate270;
}

/// A box's area as a render pass takes it.
[[nodiscard]] vk::Rect2D area_of(const PixelBox& b) noexcept {
    return {.offset = {.x = b.x0, .y = b.y0},
            .extent = {.width = static_cast<std::uint32_t>(b.x1 - b.x0),
                       .height = static_cast<std::uint32_t>(b.y1 - b.y0)}};
}

} // namespace

void Ground::decide_lit(const view::Camera& cam) {
    GroundFrame& f = frame_;
    f.lit_runs.clear();
    f.lit_run_points.clear();
    f.lit_opacity = f.weather ? 0.3 : 0.4;
    if (!f.night || no_lit_ || only_setts_ || f.tiles.empty()) return;
    // `eye = th.get('wx') and cfg.get('pitch', 90) < 10`
    if (f.weather && cam.spec().pitch < 10) return;
    // the lit roads in S['roads']'s order across the drawn tiles
    struct Source {
        std::uint32_t order;
        std::uint32_t tile;
        std::uint32_t k;
    };
    std::vector<Source> roads;
    for (std::uint32_t ti = 0; ti < f.tiles.size(); ++ti) {
        const geo::GroundTile& g = *f.tiles[ti].ground;
        for (std::uint32_t k = 0; k < g.lit_roads.size(); ++k)
            roads.push_back({.order = g.lit_roads[k].index, .tile = ti, .k = k});
    }
    std::ranges::stable_sort(roads, {}, &Source::order);
    const double widen = f.weather ? 1.0 : 3.0;
    for (const Source& s : roads) {
        const geo::GroundTile& g = *f.tiles[s.tile].ground;
        const geo::LitRoad& road = g.lit_roads[s.k];
        // for pt in r['pts'] + [None]: a point ahead (c[2] > 2) within the radius joins the run; anything else ends it,
        // and a run of two points or more is a path
        auto first = static_cast<std::uint32_t>(f.lit_run_points.size());
        pm::Summer depths;
        const auto end_run = [&] {
            const auto count = static_cast<std::uint32_t>(f.lit_run_points.size()) - first;
            if (count > 1) {
                const double mean = depths.value() / static_cast<double>(count);
                const double width = pm::max(0.8, ((road.w + widen) * cam.focal()) / mean);
                const double printed_width = f.exact ? printed(width, 1) : width;
                const double core = f.exact ? printed(printed_width * 0.3, 1) : printed_width * 0.3;
                f.lit_runs.push_back({.first = first, .count = count, .width = printed_width, .core = core});
            } else {
                f.lit_run_points.resize(first);
            }
            first = static_cast<std::uint32_t>(f.lit_run_points.size());
            depths = pm::Summer();
        };
        for (std::uint32_t k = 0; k < road.line_count; ++k) {
            const geo::P2& q = g.lit_lines[road.line + k];
            if (!(f.engine && beyond_radius(q.x, q.z, f.target_x, f.target_z, f.radius))) {
                const view::Vec3 c = cam.cam({q.x, 0.06, q.z});
                if (c.z > 2) {
                    const view::Vec2 p = cam.pc(c);
                    f.lit_run_points.push_back(f.exact ? view::Vec2{printed(p.x, 1), printed(p.y, 1)} : p);
                    depths.add(c.z);
                    continue;
                }
            }
            end_run();
        }
        end_run();
    }
}

const GlowLayer* Ground::glow_layer() const noexcept {
    return pipelines_ != nullptr ? pipelines_->glow.get() : nullptr;
}

bool Ground::glow_filtered(const frame::FrameState& state) const noexcept {
    // a filter of the renders: in full mode, where the byte tables are bound (with the fields)
    return state.mode == frame::Mode::full && (state.block.info.x & shaders::FRAME_FIELDS) != 0U &&
           pipelines_ != nullptr && pipelines_->glow != nullptr;
}

void Ground::write_lit(std::vector<std::uint32_t>& words, const frame::FrameState& state) {
    Words& w = *words_;
    w.lit_runs = 0;
    w.lit_segments = 0;
    w.lit_segment_count = 0;
    GlowPlan& plan = glow_plan_;
    plan = {};
    const GroundFrame& f = frame_;
    const GlowLayer* s = pipelines_->glow.get();
    if (f.lit_runs.empty() || s == nullptr) {
        glow_key_.clear();
        return;
    }
    // the runs' points (CSS px, as the SVG's parser reads them), a point that repeats the last dropped as the strokes'
    // raster drops it (anchor's Strokes: a run left with one point is a dot, its round caps); their records
    // (ground.slang's LIT_RUN_WORDS); then each segment's instance word, a dot's one
    std::vector<std::array<std::uint32_t, 2>> kept; // each run's first word and its points kept
    kept.reserve(f.lit_runs.size());
    for (const LitRun& r : f.lit_runs) {
        const auto first = static_cast<std::uint32_t>(words.size());
        for (std::uint32_t k = 0; k < r.count; ++k) {
            const view::Vec2& q = f.lit_run_points[r.first + k];
            const std::uint32_t x = bits_of(static_cast<float>(q.x));
            const std::uint32_t y = bits_of(static_cast<float>(q.y));
            if (words.size() > first && std::bit_cast<float>(words[words.size() - 2]) == std::bit_cast<float>(x) &&
                std::bit_cast<float>(words.back()) == std::bit_cast<float>(y)) {
                continue;
            }
            words.push_back(x);
            words.push_back(y);
        }
        kept.push_back({first, static_cast<std::uint32_t>(words.size() - first) / 2});
    }
    w.lit_runs = static_cast<std::uint32_t>(words.size());
    for (std::size_t i = 0; i < f.lit_runs.size(); ++i) {
        words.push_back(kept[i][0]);
        words.push_back(kept[i][1]);
        words.push_back(bits_of(static_cast<float>(f.lit_runs[i].width)));
        words.push_back(bits_of(static_cast<float>(f.lit_runs[i].core)));
    }
    w.lit_segments = static_cast<std::uint32_t>(words.size());
    for (std::uint32_t i = 0; i < kept.size(); ++i) {
        // (a road of more points than an instance word holds draws its first segments)
        const std::uint32_t n = std::min(std::max(kept[i][1], 2U) - 1, lit_segments_most);
        for (std::uint32_t k = 0; k < n; ++k) words.push_back((i << 14U) | k);
    }
    w.lit_segment_count = static_cast<std::uint32_t>(words.size()) - w.lit_segments;
    // what the layers and the filter cover, in the layers' px
    const std::int32_t m = s->margin;
    const auto fw = static_cast<std::int32_t>(s->view.width);
    const auto fh = static_cast<std::int32_t>(s->view.height);
    const PixelBox frame_box{.x0 = m, .y0 = m, .x1 = m + fw, .y1 = m + fh};
    const PixelBox layer_box{.x0 = 0,
                             .y0 = 0,
                             .x1 = static_cast<std::int32_t>(s->extent.width),
                             .y1 = static_cast<std::int32_t>(s->extent.height)};
    plan.cores = f.wet ? frame_box : PixelBox{};
    const auto density = static_cast<float>(state.camera.density);
    const fx::GlowBlur blur = fx::litglow_blur(density);
    plan.filtered = glow_filtered(state) && blur.side > 0 && blur.half() <= glow_half_most;
    if (!plan.filtered) {
        // the filters off: the group screened as it is drawn, over the frame
        plan.glow = true;
        plan.layer = frame_box;
        plan.out = frame_box;
    } else {
        // the group's box as the SVG's parser reads its points (floats), the filter's region about it
        const auto inf = std::numeric_limits<float>::infinity();
        std::array<float, 4> box{inf, inf, -inf, -inf};
        for (const view::Vec2& p : f.lit_run_points) {
            const auto x = static_cast<float>(p.x);
            const auto y = static_cast<float>(p.y);
            box = {std::min(box[0], x), std::min(box[1], y), std::max(box[2], x), std::max(box[3], y)};
        }
        // where the box has no width or height, or the filter's output misses the frame, the port draws no glow
        if (const std::optional<fx::GlowRect> region = fx::litglow_region(box, density)) {
            const fx::GlowPlan p = fx::litglow_plan(*region, s->view.width, s->view.height, blur);
            const fx::GlowRect out = p.out.moved(m, m).within(rect_of(frame_box));
            if (!out.empty()) {
                const fx::GlowRect input = p.input.moved(m, m).within(rect_of(layer_box));
                plan.glow = true;
                plan.side = blur.side;
                plan.input = box_of(input);
                plan.out = box_of(out);
                // each pass over what the next reads of it, within the input; the layer over what the first reads
                fx::GlowRect r = out;
                for (std::size_t k = plan.passes.size(); k-- > 0;) {
                    plan.passes[k] = box_of(r);
                    r = r.grown(blur.half()).within(input);
                }
                plan.layer = box_of(r);
            }
        }
    }
    // The layers and the result are a function of the runs, the plan and the density: where they are the last
    // frame's, they stand (a map at rest draws frames for other passes' sake).
    std::vector<std::uint32_t> key{bits_of(density)};
    for (const view::Vec2& p : f.lit_run_points) {
        key.push_back(bits_of(static_cast<float>(p.x)));
        key.push_back(bits_of(static_cast<float>(p.y)));
    }
    for (const LitRun& r : f.lit_runs) {
        key.insert(key.end(), {r.count, bits_of(static_cast<float>(r.width)), bits_of(static_cast<float>(r.core))});
    }
    key.insert(key.end(), {plan.glow ? 1U : 0U, plan.filtered ? 1U : 0U, plan.side});
    for (const PixelBox& b :
         {plan.layer, plan.input, plan.passes[0], plan.passes[1], plan.passes[2], plan.out, plan.cores}) {
        for (const std::int32_t v : {b.x0, b.y0, b.x1, b.y1}) key.push_back(static_cast<std::uint32_t>(v));
    }
    glow_stale_ = key != glow_key_;
    glow_key_ = std::move(key);
}

gpu::Result<void> Ground::make_glow(const frame::PassContext& context, Pipelines& p) const {
    const gpu::Device& device = context.device;
    const vk::raii::Device& d = device.handle();
    auto s = std::make_unique<GlowLayer>();
    s->kept = keep_glow_;
    // the view's device px, the output's turned back, and the margin about them
    vk::Extent2D view = context.output.extent();
    if (turned(context.output.transform())) std::swap(view.width, view.height);
    s->view = view;
    s->margin = glow_margin;
    const auto wide = static_cast<std::uint32_t>(2 * glow_margin);
    s->extent = {.width = view.width + wide, .height = view.height + wide};
    using U = vk::ImageUsageFlagBits;
    const vk::Format colour = vk::Format::eR8G8B8A8Unorm;
    auto image = [&](vk::SampleCountFlagBits samples, vk::ImageUsageFlags usage, gpu::MemoryUse use) {
        return device.memory().image({.imageType = vk::ImageType::e2D,
                                      .format = colour,
                                      .extent = {.width = s->extent.width, .height = s->extent.height, .depth = 1},
                                      .mipLevels = 1,
                                      .arrayLayers = 1,
                                      .samples = samples,
                                      .tiling = vk::ImageTiling::eOptimal,
                                      .usage = usage,
                                      .initialLayout = vk::ImageLayout::eUndefined},
                                     use);
    };
    // the samples in lazily allocated memory where the frame's are (no pages on a tiler), else, or where that fails, in
    // ordinary memory
    const bool lazy = context.pass.spec().config.lazy();
    auto transient = [&]() -> gpu::Result<gpu::Image> {
        auto made = image(vk::SampleCountFlagBits::e4, U::eColorAttachment | U::eTransientAttachment,
                          lazy ? gpu::MemoryUse::lazy : gpu::MemoryUse::gpu);
        if (!made && lazy) made = image(vk::SampleCountFlagBits::e4, U::eColorAttachment, gpu::MemoryUse::gpu);
        return made;
    };
    ATLAS_GPU_LET(samples, transient());
    s->samples = std::move(samples);
    const vk::ImageUsageFlags layer_usage =
        U::eColorAttachment | U::eSampled | (s->kept ? U::eTransferSrc : vk::ImageUsageFlags{});
    ATLAS_GPU_LET(glow, image(vk::SampleCountFlagBits::e1, layer_usage, gpu::MemoryUse::gpu));
    s->glow = std::move(glow);
    ATLAS_GPU_LET(cores, image(vk::SampleCountFlagBits::e1, layer_usage, gpu::MemoryUse::gpu));
    s->cores = std::move(cores);
    auto view_of = [&](const gpu::Image& i) -> gpu::Result<vk::raii::ImageView> {
        ATLAS_VK_LET(v,
                     d.createImageView({.image = i.handle(),
                                        .viewType = vk::ImageViewType::e2D,
                                        .format = colour,
                                        .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                             .levelCount = 1,
                                                             .layerCount = 1}}),
                     "the lit streets' layer's view");
        return std::move(v);
    };
    ATLAS_GPU_LET(samples_view, view_of(s->samples));
    s->samples_view = std::move(samples_view);
    ATLAS_GPU_LET(glow_view, view_of(s->glow));
    s->glow_view = std::move(glow_view);
    ATLAS_GPU_LET(cores_view, view_of(s->cores));
    s->cores_view = std::move(cores_view);
    // a layer's pass: the samples cleared and drawn, resolved into the layer; record moves the layer into the colour
    // layout before it and out after it
    using L = vk::ImageLayout;
    const std::array<vk::AttachmentDescription, 2> attachments{{
        {.format = colour,
         .samples = vk::SampleCountFlagBits::e4,
         .loadOp = vk::AttachmentLoadOp::eClear,
         .storeOp = vk::AttachmentStoreOp::eDontCare,
         .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
         .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
         .initialLayout = L::eUndefined,
         .finalLayout = L::eColorAttachmentOptimal},
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
    const vk::AttachmentReference resolve_ref{.attachment = 1, .layout = L::eColorAttachmentOptimal};
    const vk::SubpassDescription subpass{.pipelineBindPoint = vk::PipelineBindPoint::eGraphics,
                                         .colorAttachmentCount = 1,
                                         .pColorAttachments = &colour_ref,
                                         .pResolveAttachments = &resolve_ref};
    using PS = vk::PipelineStageFlagBits;
    using AF = vk::AccessFlagBits;
    const std::array<vk::SubpassDependency, 2> dependencies{{
        {.srcSubpass = vk::SubpassExternal,
         .dstSubpass = 0,
         .srcStageMask = PS::eColorAttachmentOutput,
         .dstStageMask = PS::eColorAttachmentOutput,
         .srcAccessMask = AF::eColorAttachmentWrite,
         .dstAccessMask = AF::eColorAttachmentRead | AF::eColorAttachmentWrite},
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
                 "the lit streets' layers' render pass");
    s->pass = std::move(pass);
    auto framebuffer_of = [&](const vk::raii::ImageView& resolve) -> gpu::Result<vk::raii::Framebuffer> {
        const std::array<vk::ImageView, 2> views{*s->samples_view, *resolve};
        ATLAS_VK_LET(f,
                     d.createFramebuffer({.renderPass = *s->pass,
                                          .attachmentCount = static_cast<std::uint32_t>(views.size()),
                                          .pAttachments = views.data(),
                                          .width = s->extent.width,
                                          .height = s->extent.height,
                                          .layers = 1}),
                     "the lit streets' layers' framebuffer");
        return std::move(f);
    };
    ATLAS_GPU_LET(glow_framebuffer, framebuffer_of(s->glow_view));
    s->glow_framebuffer = std::move(glow_framebuffer);
    ATLAS_GPU_LET(cores_framebuffer, framebuffer_of(s->cores_view));
    s->cores_framebuffer = std::move(cores_framebuffer);
    // the filter's two buffers (readable where the tests keep them), and the kept layers' copies
    using B = vk::BufferUsageFlagBits;
    const vk::DeviceSize texels = vk::DeviceSize{s->extent.width} * s->extent.height * 4;
    const gpu::MemoryUse look = s->kept ? gpu::MemoryUse::readback : gpu::MemoryUse::gpu;
    for (gpu::Buffer& b : s->buffers) {
        ATLAS_GPU_LET(made, device.memory().buffer(texels, B::eStorageBuffer, look));
        b = std::move(made);
    }
    if (s->kept) {
        ATLAS_GPU_LET(glow_copy, device.memory().buffer(texels, B::eTransferDst, gpu::MemoryUse::readback));
        s->glow_copy = std::move(glow_copy);
        ATLAS_GPU_LET(core_copy, device.memory().buffer(texels, B::eTransferDst, gpu::MemoryUse::readback));
        s->core_copy = std::move(core_copy);
    }
    // the composite reads the result and the layers from the ground's set 3 (the same three in each frame slot's: the
    // layers' and the boxes' barriers wait for the last frame's composite before they are written again)
    for (const vk::raii::DescriptorSet& set : p.soft_sets) {
        gpu::SetWriter(*set)
            .buffer(5, vk::DescriptorType::eStorageBuffer, s->buffers[0].handle())
            .image(6, vk::DescriptorType::eSampledImage, *s->glow_view, L::eShaderReadOnlyOptimal)
            .image(7, vk::DescriptorType::eSampledImage, *s->cores_view, L::eShaderReadOnlyOptimal)
            .apply(d);
    }
    // the passes' set: the glow's layer, a buffer to read and one to write, both ways
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
            .image(0, vk::DescriptorType::eSampledImage, *s->glow_view, L::eShaderReadOnlyOptimal)
            .buffer(1, vk::DescriptorType::eStorageBuffer, s->buffers[i].handle())
            .buffer(2, vk::DescriptorType::eStorageBuffer, s->buffers[1 - i].handle())
            .apply(d);
    }
    const std::array<vk::DescriptorSetLayout, 4> sets{
        context.layout.frame_set_layout(), context.layout.global_set_layout(), *p.set_layout, *s->set_layout};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, sizeof(GlowPush), in_compute));
    s->compute_layout = std::move(compute_layout);
    ATLAS_GPU_LET(module, frame::make_module(context, "ground_litglow", spirv::ground_litglow));
    s->module = std::move(module);
    ATLAS_GPU_LET(box,
                  frame::make_compute(
                      context,
                      {.name = "ground lit box", .module = "ground_litglow", .configuration = context.configuration},
                      *s->module, "glow_box_cs", *s->compute_layout));
    s->box = std::move(box);
    // the runs into the samples: their coverage in the group's colour, source over (a colour over itself is its union)
    gpu::GraphicsDesc layer_draw = ground_desc(context, p, "ground lit layer", "lit_vs", "lit_fs");
    layer_draw.target = {
        .render_pass = *s->pass, .subpass = 0, .samples = vk::SampleCountFlagBits::e4, .colour_count = 1};
    layer_draw.blends[0] = gpu::Blend::over();
    ATLAS_GPU_LET(draw, frame::make_pipeline(context, layer_draw, frame::Overdraw::none));
    s->draw = std::move(draw);
    // the groups screened over the ground at their slots, translucent
    gpu::GraphicsDesc lit = ground_desc(context, p, "ground lit streets", "screen_vs", "lit_composite_fs");
    translucent(lit);
    lit.blends[0] = gpu::Blend::screen();
    ATLAS_GPU_LET(composite, frame::make_pipeline(context, lit, frame::Overdraw::translucent));
    s->composite = std::move(composite);
    p.glow = std::move(s);
    return {};
}

void Ground::record_lit_layer(const frame::Recording& r) {
    const bool drew = drew_glow_;
    drew_glow_ = false;
    const Words& w = *words_;
    const GlowPlan& plan = glow_plan_;
    Pipelines& p = *pipelines_;
    if (p.glow == nullptr || w.lit_segment_count == 0 || (!plan.glow && plan.cores.empty())) return;
    GlowLayer& s = *p.glow;
    if (drew && !glow_stale_) {
        // the last frame's layers and result stand
        drew_glow_ = true;
        return;
    }
    s.drew = plan;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    const gpu::Sync& sync = r.frame.sync;
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    using L = vk::ImageLayout;
    const auto frame_sets = r.layout.sets();
    const auto offsets = r.layout.offsets(r.frame.slot.slot);
    // the runs into a layer, over `area` alone (nothing reads the layer beyond it), which the last frame's passes and
    // composite read
    const auto layer = [&](const gpu::Image& image, const vk::raii::Framebuffer& framebuffer, const PixelBox& area,
                           std::uint32_t colour, std::uint32_t width, const char* counter) {
        sync.barrier(cmd, gpu::ImageDependency{.image = image.handle(),
                                               .src_stage = S::eComputeShader | S::eFragmentShader | S::eTransfer,
                                               .src_access = {},
                                               .dst_stage = S::eColorAttachmentOutput,
                                               .dst_access = A::eColorAttachmentWrite,
                                               .old_layout = L::eUndefined,
                                               .new_layout = L::eColorAttachmentOptimal});
        std::array<vk::ClearValue, 1> clear{};
        clear[0].color = vk::ClearColorValue{std::array<float, 4>{0.0F, 0.0F, 0.0F, 0.0F}};
        cmd.beginRenderPass({.renderPass = *s.pass,
                             .framebuffer = *framebuffer,
                             .renderArea = area_of(area),
                             .clearValueCount = static_cast<std::uint32_t>(clear.size()),
                             .pClearValues = clear.data()},
                            vk::SubpassContents::eInline);
        cmd.setViewport(0, vk::Viewport{.x = 0,
                                        .y = 0,
                                        .width = static_cast<float>(s.extent.width),
                                        .height = static_cast<float>(s.extent.height),
                                        .minDepth = 0,
                                        .maxDepth = 1});
        cmd.setScissor(0, area_of(area));
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.draw);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *p.layout, 0, frame_sets, offsets);
        const std::array<vk::DescriptorSet, 2> own{*p.sets[w.slot], *p.soft_sets[w.slot]};
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *p.layout, 2, own, {});
        Push pc{};
        pc.at = {w.tiles, w.slots, w.theme, w.lit_runs};
        pc.mode = {0U, 0U, w.lit_segments, 4U};
        pc.rows = {static_cast<float>(s.margin), static_cast<float>(s.extent.width),
                   static_cast<float>(s.extent.height), 0.0F};
        pc.colour = {colour, width, 0U, 0U};
        cmd.pushConstants<Push>(*p.layout, frame::push_stages, 0, pc);
        cmd.draw(6, w.lit_segment_count, 0, 0);
        r.counters.draw(counter, std::uint64_t{w.lit_segment_count} * 2, w.lit_segment_count);
        cmd.endRenderPass();
        sync.barrier(cmd, gpu::ImageDependency{.image = image.handle(),
                                               .src_stage = S::eColorAttachmentOutput,
                                               .src_access = A::eColorAttachmentWrite,
                                               .dst_stage = S::eComputeShader | S::eFragmentShader,
                                               .dst_access = A::eShaderRead,
                                               .old_layout = L::eColorAttachmentOptimal,
                                               .new_layout = L::eShaderReadOnlyOptimal});
        s.laid_out[width] = true;
    };
    if (plan.glow) layer(s.glow, s.glow_framebuffer, plan.layer, opaque(glow_rgb), 0, "lit glow layer");
    if (plan.glow && plan.filtered) {
        // the three boxes, the layer into a, a into b, b into a, over the buffers the last frame's composite read
        sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eFragmentShader | S::eComputeShader,
                                                .src_access = {},
                                                .dst_stage = S::eComputeShader,
                                                .dst_access = A::eShaderWrite});
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *s.box);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 0, frame_sets, offsets);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 2, *p.sets[w.slot], {});
        GlowPush gp{};
        gp.input = {plan.input.x0, plan.input.y0, plan.input.x1, plan.input.y1};
        for (std::size_t k = 0; k < plan.passes.size(); ++k) {
            const PixelBox& region = plan.passes[k];
            // set [1] reads b and writes a, set [0] reads a and writes b
            cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *s.compute_layout, 3, *s.sets[k == 1 ? 0 : 1], {});
            gp.size = {s.extent.width, plan.side, k == 0 ? 1U : 0U, 0U};
            gp.region = {region.x0, region.y0, region.x1, region.y1};
            cmd.pushConstants<GlowPush>(*s.compute_layout, vk::ShaderStageFlagBits::eCompute, 0, gp);
            const auto tiles = [](std::int32_t n) {
                return (static_cast<std::uint32_t>(n) + glow_tile - 1) / glow_tile;
            };
            cmd.dispatch(tiles(region.x1 - region.x0), tiles(region.y1 - region.y0), 1);
            r.counters.dispatch("lit glow box");
            sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                    .src_access = A::eShaderWrite,
                                                    .dst_stage = S::eComputeShader | S::eFragmentShader,
                                                    .dst_access = A::eShaderRead | A::eShaderWrite});
        }
    }
    if (!plan.cores.empty()) layer(s.cores, s.cores_framebuffer, plan.cores, opaque(core_rgb), 1, "lit cores layer");
    // the composite names both layers (its descriptors), so a layer not drawn yet is laid out to be read, once
    for (std::size_t i = 0; i < s.laid_out.size(); ++i) {
        if (s.laid_out[i]) continue;
        sync.barrier(cmd, gpu::ImageDependency{.image = (i == 0 ? s.glow : s.cores).handle(),
                                               .src_stage = S::eTopOfPipe,
                                               .src_access = {},
                                               .dst_stage = S::eComputeShader | S::eFragmentShader,
                                               .dst_access = A::eShaderRead,
                                               .old_layout = L::eUndefined,
                                               .new_layout = L::eShaderReadOnlyOptimal});
        s.laid_out[i] = true;
    }
    if (s.kept) {
        // the tests' look: the layers, a row after another, and the buffers readable by the host
        const auto copy = [&](const gpu::Image& image, const gpu::Buffer& to) {
            sync.barrier(cmd, gpu::ImageDependency{.image = image.handle(),
                                                   .src_stage = S::eComputeShader | S::eColorAttachmentOutput,
                                                   .src_access = {},
                                                   .dst_stage = S::eTransfer,
                                                   .dst_access = A::eTransferRead,
                                                   .old_layout = L::eShaderReadOnlyOptimal,
                                                   .new_layout = L::eTransferSrcOptimal});
            const vk::BufferImageCopy region{
                .bufferOffset = 0,
                .bufferRowLength = 0,
                .bufferImageHeight = 0,
                .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
                .imageOffset = {.x = 0, .y = 0, .z = 0},
                .imageExtent = {.width = s.extent.width, .height = s.extent.height, .depth = 1}};
            cmd.copyImageToBuffer(image.handle(), L::eTransferSrcOptimal, to.handle(), region);
            sync.barrier(cmd, gpu::ImageDependency{.image = image.handle(),
                                                   .src_stage = S::eTransfer,
                                                   .src_access = {},
                                                   .dst_stage = S::eFragmentShader,
                                                   .dst_access = A::eShaderRead,
                                                   .old_layout = L::eTransferSrcOptimal,
                                                   .new_layout = L::eShaderReadOnlyOptimal});
        };
        if (plan.glow) copy(s.glow, s.glow_copy);
        if (!plan.cores.empty()) copy(s.cores, s.core_copy);
        sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eTransfer | S::eComputeShader,
                                                .src_access = A::eTransferWrite | A::eShaderWrite,
                                                .dst_stage = S::eHost,
                                                .dst_access = A::eHostRead});
    }
    drew_glow_ = true;
}

void Ground::record_lit(const frame::Recording& r) {
    if (!drew_glow_ || pipelines_->glow == nullptr) return;
    const GlowLayer& s = *pipelines_->glow;
    const GlowPlan& plan = s.drew;
    const Words& w = *words_;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *s.composite);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *pipelines_->layout, 3, *pipelines_->soft_sets[w.slot],
                           {});
    // the composite's region in the frame's px (the layers' less the margin)
    const auto pack = [m = s.margin](std::int32_t x, std::int32_t y) {
        return static_cast<std::uint32_t>(x - m) | (static_cast<std::uint32_t>(y - m) << 16U);
    };
    const auto draw = [&](std::uint8_t sub, std::uint32_t mode, float opacity, const char* counter) {
        Push pc{};
        pc.at = {w.tiles, w.slots, w.theme, w.runs};
        pc.mode = {0U, slot(geo::group::pools, 0, sub), mode, 0U};
        pc.rows = {0.0F, 0.0F, opacity, 0.0F};
        pc.colour = {s.extent.width, pack(plan.out.x0, plan.out.y0), pack(plan.out.x1, plan.out.y1),
                     static_cast<std::uint32_t>(s.margin)};
        cmd.pushConstants<Push>(*pipelines_->layout, frame::push_stages, 0, pc);
        cmd.draw(3, 1, 0, 0);
        r.counters.draw(counter, 1);
    };
    if (plan.glow) {
        draw(geo::pools_sub::glow, plan.filtered ? 0U : 1U, static_cast<float>(frame_.lit_opacity), "ground lit glow");
    }
    if (!plan.cores.empty()) draw(geo::pools_sub::cores, 2U, core_opacity, "ground lit cores");
}

void Ground::emit_lit(twin::Stream& out) const {
    const GroundFrame& f = frame_;
    if (f.lit_runs.empty()) return;
    twin::Style s;
    s.cap = twin::Cap::round;
    s.join = twin::Join::round;
    std::vector<twin::Point> points;
    const auto runs = [&](bool core) {
        for (const LitRun& r : f.lit_runs) {
            points.clear();
            for (std::uint32_t k = 0; k < r.count; ++k) {
                const view::Vec2& q = f.lit_run_points[r.first + k];
                points.push_back({.x = static_cast<float>(q.x), .y = static_cast<float>(q.y)});
            }
            s.stroke_width = static_cast<float>(core ? r.core : r.width);
            out.path(s, points, false);
        }
    };
    const std::int32_t glow = out.group({.node = {.opacity = static_cast<float>(f.lit_opacity),
                                                  .blend = twin::Blend::screen,
                                                  .filter = out.filter("litglow")},
                                         .parent = out.root()});
    out.at(slot(geo::group::pools, 0, geo::pools_sub::glow), glow);
    s.stroke = twin::Paint::colour(0xFF00'0000U | glow_rgb);
    runs(false);
    if (!f.wet) return;
    const std::int32_t cores =
        out.group({.node = {.opacity = core_opacity, .blend = twin::Blend::screen}, .parent = out.root()});
    out.at(slot(geo::group::pools, 0, geo::pools_sub::cores), cores);
    s.stroke = twin::Paint::colour(0xFF00'0000U | core_rgb);
    runs(true);
}

} // namespace atlas::passes
