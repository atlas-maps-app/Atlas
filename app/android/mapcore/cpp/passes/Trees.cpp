#include "passes/Trees.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "fx/Ripple.hpp"
#include "gen/spirv/tree/draw.hpp"
#include "gpu/Descriptors.hpp"
#include "scene/Cover.hpp"
#include "scene/Packs.hpp"
#include "scene/Residency.hpp"
#include "style/Light.hpp"
#include "twin/Twin.hpp"
#include "twin/tree/TreeShader.hpp"
#include "twin/tree/TreeTwin.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <span>

namespace atlas::passes {

namespace {

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }
[[nodiscard]] std::uint32_t f32_bits(double v) noexcept { return bits(static_cast<float>(v)); }

/// The jobs a row of workgroups covers (trees_cs's TREE_JOB_ROW workgroups of 64).
constexpr std::uint32_t job_row = 65535;

/// `b`, or a buffer for `use` of at least `bytes` with room to grow by half again where `b` is smaller; whether it was
/// made anew.
[[nodiscard]] gpu::Result<bool> fit(const gpu::Device& device, gpu::Buffer& b, vk::DeviceSize bytes,
                                    vk::BufferUsageFlags usage, gpu::MemoryUse use) {
    if (b && b.size() >= bytes) return false;
    ATLAS_GPU_LET(made, device.memory().buffer(std::max<vk::DeviceSize>(bytes + (bytes / 2), 1024), usage, use));
    b = std::move(made);
    return true;
}

/// A colour of the style (0xRRGGBB) as the shaders take it, 0xBBGGRR.
[[nodiscard]] std::uint32_t bgr(std::uint32_t rgb) noexcept {
    return ((rgb >> 16U) & 0xFFU) | (rgb & 0xFF00U) | ((rgb & 0xFFU) << 16U);
}

/// light.slang's light through tohex, packed 0xBBGGRR.
[[nodiscard]] std::uint32_t lit_bgr(const shaders::Theme& th, shaders::double3 base, shaders::double3 n, bool lit) {
    const hlsl::uint3 c = shaders::light_bytes(th, base, n, lit);
    return c.x | (c.y << 8U) | (c.z << 16U);
}

/// A style colour as the port's hx reads it, channel by channel.
[[nodiscard]] shaders::double3 hx(std::uint32_t rgb) noexcept {
    return {static_cast<double>((rgb >> 16U) & 0xFFU), static_cast<double>((rgb >> 8U) & 0xFFU),
            static_cast<double>(rgb & 0xFFU)};
}

[[nodiscard]] shaders::double3 mulc(shaders::double3 a, double s) noexcept { return {a.x * s, a.y * s, a.z * s}; }

/// city.py's mix: a + (b - a) · t, channel by channel.
[[nodiscard]] shaders::double3 mix(shaders::double3 a, shaders::double3 b, double t) noexcept {
    return {a.x + ((b.x - a.x) * t), a.y + ((b.y - a.y) * t), a.z + ((b.z - a.z) * t)};
}

/// city.py's vnorm: each lane over sqrt(vdot(a, a)), summed left to right.
[[nodiscard]] shaders::double3 vnorm(double x, double y, double z) noexcept {
    const double l = pymath::sqrt((x * x) + (y * y) + (z * z));
    return {x / l, y / l, z / l};
}

/// A 0xBBGGRR colour's channels as doubles (hx of what tohex printed).
[[nodiscard]] shaders::double3 channels(std::uint32_t c) noexcept {
    return {static_cast<double>(c & 0xFFU), static_cast<double>((c >> 8U) & 0xFFU),
            static_cast<double>((c >> 16U) & 0xFFU)};
}

} // namespace

std::vector<hlsl::uint4> tree_words(const geometry::TileTrees& trees, double origin_x, double origin_y, double origin_z,
                                    bool lean) {
    std::vector<hlsl::uint4> out;
    out.reserve(trees.trees.size() * (lean ? shaders::TREE_LEAN_VECTORS : shaders::TREE_VECTORS));
    for (const geometry::Tree& t : trees.trees) {
        // tree_close: cb = h * (0.38 if form != 'weep' else 0.3), the trunk's top cb + (h - cb) * 0.3, the crown's
        // centre cy = cb + (h - cb) * 0.5, in double
        const double cb = t.h * (t.form != tiles::TreeForm::weep ? 0.38 : 0.3);
        const double top = cb + ((t.h - cb) * 0.3);
        const double cy = cb + ((t.h - cb) * 0.5);
        out.push_back(hlsl::uint4{f32_bits(t.x - origin_x), f32_bits(t.gy - origin_y), f32_bits(t.z - origin_z),
                                  t.evergreen ? shaders::TREE_EVERGREEN : 0U});
        out.push_back(hlsl::uint4{f32_bits(top), f32_bits(cy), f32_bits(t.h), f32_bits(t.r)});
        // a level's tree has no clumps (tree.slangh's TREE_LEAN_VECTORS)
        for (const geometry::TreeClump& c :
             lean ? std::span<const geometry::TreeClump>() : std::span<const geometry::TreeClump>(t.clumps)) {
            out.push_back(hlsl::uint4{f32_bits(c.dx), f32_bits(c.y), f32_bits(c.dz),
                                      f32_bits(c.br) | (c.under ? 0x8000'0000U : 0U)});
        }
        for (const geometry::TreeClump& b : t.blobs) {
            out.push_back(hlsl::uint4{f32_bits(b.dx), f32_bits(b.y), f32_bits(b.dz), f32_bits(b.br)});
        }
    }
    return out;
}

TreeChoice tree_choice(double depth, double focal, double r, bool weather, bool night, bool right, bool lean) {
    TreeChoice c;
    const auto nb = [](std::uint32_t n) { return n << shaders::TREE_JOB_NB_SHIFT; };
    if (night) c.flags |= shaders::TREE_JOB_NIGHT;
    if (lean) c.flags |= shaders::TREE_JOB_LEAN;
    if (!weather) {
        // draw_tree3d: nb = 9 if d < 400 else 5 (26 needs weather, which takes tree_close), the trunk's stroke-width
        // '%.2f' % max(0.6, 0.3 * cam.focal / d)
        c.flags |= shaders::TREE_JOB_GENERIC | nb(depth < 400 ? 9U : 5U);
        c.w0 = static_cast<float>(pymath::round(pymath::max(0.6, 0.3 * focal / depth), 2));
        return c;
    }
    // tree_close: r * cam.focal / max(d, 1) < 6.5 is a small crown, rr = max(0.8, that), one circle where rr < 4.2
    const double d1 = pymath::max(depth, 1.0);
    const double rpx = r * focal / d1;
    if (rpx < 6.5 || lean) {
        const double rr = pymath::max(0.8, rpx);
        c.flags |= shaders::TREE_JOB_SMALL | (rr < 4.2 ? shaders::TREE_JOB_ONE : 0U);
        c.w0 = static_cast<float>(rr);
        return c;
    }
    // s = cam.focal / max(d, 1); nb = 36 if rpx > 70 else (26 if rpx > 34 else (16 if rpx > 16 else 9)); the trunk's
    // w0 = max(0.5, 0.22 * s), w1 = max(0.35, 0.12 * s); its sunlit side by day where s > 4; the limbs where s > 2.5,
    // '%.2f' % max(0.4, 0.07 * s) wide
    const double s = focal / d1;
    c.flags |= nb(rpx > 70 ? 36U : (rpx > 34 ? 26U : (rpx > 16 ? 16U : 9U)));
    if (!night && s > 4) c.flags |= shaders::TREE_JOB_SIDE | (right ? shaders::TREE_JOB_RIGHT : 0U);
    if (s > 2.5) c.flags |= shaders::TREE_JOB_LIMBS;
    c.w0 = static_cast<float>(pymath::max(0.5, 0.22 * s));
    c.w1 = static_cast<float>(pymath::max(0.35, 0.12 * s));
    c.limb = static_cast<float>(pymath::round(pymath::max(0.4, 0.07 * s), 2));
    return c;
}

std::uint32_t first_tree(const scene::ResidentTile& tile, std::uint16_t kind) noexcept {
    for (std::size_t i = 0; i < tile.items.size(); ++i) {
        if (tile.items[i].kind == kind) return static_cast<std::uint32_t>(i);
    }
    return static_cast<std::uint32_t>(tile.items.size());
}

bool tree_side_right(const shaders::Theme& theme, const view::Camera& camera) noexcept {
    const shaders::double3 l = shaders::theme_sun_vector(theme);
    return (l.x * camera.right().x) + (l.z * camera.right().z) > 0;
}

double tree_tint(const style::ThemeBlock& block) noexcept {
    const double doy = ((static_cast<double>(block.month) - 1) * 30.5) + static_cast<double>(block.day);
    return pymath::max(0.0, pymath::min(0.7, (doy - 258) / 75));
}

std::array<std::uint32_t, 25> tree_tones(const shaders::Theme& theme, const style::ThemeBlock& block,
                                         const tiles::TreesStyle& style) {
    static_assert(shaders::TREE_TONES == 25);
    std::array<std::uint32_t, 25> out{};
    const bool night = (theme.flags.x & shaders::THEME_NIGHT) != 0U;
    const shaders::double3 l = shaders::theme_sun_vector(theme);
    out[shaders::TREE_TONE_BARK] = bgr(night ? style.bark.night : style.bark.day);
    out[shaders::TREE_TONE_SIDE] = bgr(style.bark_lit);
    // each tint: leaf = mix(hx('#4E6B35'), hx('#9C8A36'), autumn(th['date'], tags))
    const std::array<double, 2> tints{tree_tint(block), 0.0};
    const shaders::double3 up{0.0, 0.3, 0.0};
    const shaders::double3 down{0.0, -1.0, 0.0};
    const shaders::double3 side8 = vnorm(l.x, 0.8, l.z);
    const shaders::double3 side5 = vnorm(l.x, 0.5, l.z);
    for (std::size_t k = 0; k < tints.size(); ++k) {
        const std::uint32_t at = shaders::TREE_TONE_TINTS + (static_cast<std::uint32_t>(k) * shaders::TREE_TINT_TONES);
        const shaders::double3 leaf = mix(hx(style.leaf), hx(style.autumn), tints[k]);
        // a small crown: c0 = light(mulc(leaf, 0.7), (0, 0.3, 0), False), c1 = light(leaf, vnorm((L0, 0.8, L2)), not
        // night), one circle mix(hx(c0), hx(c1), 0.55)
        const std::uint32_t c0 = lit_bgr(theme, mulc(leaf, 0.7), up, false);
        const std::uint32_t c1 = lit_bgr(theme, leaf, side8, !night);
        out[at + shaders::TREE_TONE_SMALL_SHADE] = c0;
        out[at + shaders::TREE_TONE_SMALL_LIT] = c1;
        const hlsl::uint3 m = shaders::tohex3(mix(channels(c0), channels(c1), 0.55));
        out[at + shaders::TREE_TONE_SMALL_MIX] = m.x | (m.y << 8U) | (m.z << 16U);
        // the crown's tones: (0.55, (0, -1, 0), False), (0.8, (0, 0.3, 0), False), (0.86, vnorm((L0, 0.8, L2)), lit),
        // (0.93, vnorm((L0, 0.5, L2)), lit)
        out[at + shaders::TREE_TONE_UNDER] = lit_bgr(theme, mulc(leaf, 0.55), down, false);
        out[at + shaders::TREE_TONE_FLANK] = lit_bgr(theme, mulc(leaf, 0.8), up, false);
        out[at + shaders::TREE_TONE_LIT] = lit_bgr(theme, mulc(leaf, 0.86), side8, false);
        out[at + shaders::TREE_TONE_LIT + 1] = lit_bgr(theme, mulc(leaf, 0.86), side8, !night);
        out[at + shaders::TREE_TONE_HIGHLIGHT] = lit_bgr(theme, mulc(leaf, 0.93), side5, false);
        out[at + shaders::TREE_TONE_HIGHLIGHT + 1] = lit_bgr(theme, mulc(leaf, 0.93), side5, !night);
    }
    // the generic tree: dark = light(mulc(base, 0.6), (0, 1, 0), False), mid = light(base, vnorm((L0, 0.7, L2)), lit)
    const shaders::double3 base = hx(style.crown);
    out[shaders::TREE_TONE_GENERIC_DARK] = lit_bgr(theme, mulc(base, 0.6), {0.0, 1.0, 0.0}, false);
    const shaders::double3 side7 = vnorm(l.x, 0.7, l.z);
    out[shaders::TREE_TONE_GENERIC_MID] = lit_bgr(theme, base, side7, false);
    out[shaders::TREE_TONE_GENERIC_MID + 1] = lit_bgr(theme, base, side7, !night);
    out[shaders::TREE_TONE_GENERIC_TRUNK] = bgr(night ? style.trunk.night : style.trunk.day);
    out[shaders::TREE_TONE_SIDE_OPACITY] = bits(style.bark_lit_opacity);
    return out;
}

gpu::Result<void> Trees::prepare(const frame::PassContext& context) {
    release();
    // no trees in the owner mode: the likeness harness's owner maps draw none yet
    if (context.owners) return {};
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(module, frame::make_module(context, "tree_draw", spirv::tree_draw));
    module_ = std::move(module);
    using Stage = vk::ShaderStageFlagBits;
    const std::array bindings{
        gpu::Binding{.slot = 0, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 1,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = Stage::eCompute | Stage::eVertex | Stage::eFragment}, // the crowns' reach too
        gpu::Binding{.slot = 2, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 3, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        gpu::Binding{.slot = 4, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eCompute},
        gpu::Binding{.slot = 5, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex},
        // the fills' list (ENGINE.md gain 18)
        gpu::Binding{.slot = 6, .type = vk::DescriptorType::eStorageBuffer, .stages = Stage::eVertex}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, static_cast<std::uint32_t>(sets_.size())));
    pool_ = std::move(pool);
    for (vk::raii::DescriptorSet& set : sets_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        set = std::move(made);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    const std::array<vk::DescriptorSetLayout, 3> sets{context.layout.frame_set_layout(),
                                                      context.layout.global_set_layout(), *set_layout_};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, frame::push_bytes, Stage::eCompute));
    compute_layout_ = std::move(compute_layout);
    ATLAS_GPU_LET(decide, frame::make_compute(
                              context, {.name = "trees", .module = "tree_draw", .configuration = context.configuration},
                              *module_, "trees_cs", *compute_layout_));
    decide_ = std::move(decide);
    // the parts: opaque, at their tree's plane's depth and leads, writing the depth and the objects bit
    gpu::GraphicsDesc desc{.label = {.name = "trees", .module = "tree_draw", .configuration = context.configuration},
                           .module = *module_,
                           .vertex = "trees_vs",
                           .fragment = "trees_fs",
                           .layout = *layout_,
                           .target = context.pass.scene_target(),
                           .depth_test = true,
                           .depth_write = true,
                           .depth_op = vk::CompareOp::eGreater,
                           .stencil_test = true,
                           .stencil = {.failOp = vk::StencilOp::eKeep,
                                       .passOp = vk::StencilOp::eReplace,
                                       .depthFailOp = vk::StencilOp::eKeep,
                                       .compareOp = vk::CompareOp::eAlways,
                                       .compareMask = 0,
                                       .writeMask = shaders::OBJECTS_BIT,
                                       .reference = shaders::OBJECTS_BIT}};
    if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(draw, frame::make_pipeline(context, desc, frame::Overdraw::opaque));
    draw_ = std::move(draw);
    // the trunks alone (ENGINE.md gain 18): the same tests and writes, a fragment stage that writes no mask and
    // discards nothing
    gpu::GraphicsDesc fill = desc;
    fill.label.name = "tree fills";
    fill.vertex = "trees_fills_vs";
    fill.fragment = "trees_fills_fs";
    ATLAS_GPU_LET(fill_pipeline, frame::make_pipeline(context, fill, frame::Overdraw::opaque));
    fill_ = std::move(fill_pipeline);
    // the trunks' sunlit sides: translucent, in painter's order, the test on and writes off
    gpu::GraphicsDesc side{
        .label = {.name = "tree sides", .module = "tree_draw", .configuration = context.configuration},
        .module = *module_,
        .vertex = "tree_sides_vs",
        .fragment = "tree_sides_fs",
        .layout = *layout_,
        .target = context.pass.scene_target(),
        .depth_test = true,
        .depth_write = false,
        .depth_op = vk::CompareOp::eGreater};
    side.blends[0] = gpu::Blend::over();
    if (side.target.colour_count == 2) side.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(side_pipeline, frame::make_pipeline(context, side, frame::Overdraw::translucent));
    side_ = std::move(side_pipeline);
    return {};
}

void Trees::release() noexcept {
    side_.clear();
    fill_.clear();
    draw_.clear();
    decide_.clear();
    compute_layout_.clear();
    layout_.clear();
    for (vk::raii::DescriptorSet& s : sets_) s.clear();
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    jobs_ = {};
    tones_ = {};
    records_ = {};
    sides_ = {};
    fills_ = {};
    counts_ = {};
}

void Trees::kinds(frame::Selectors& selectors) { kind_ = selectors.kind("tree"); }

void Trees::select(const frame::FrameState& state, frame::Selectors& selectors) {
    // the map leaves its trees out: their items take no rank
    selectors.leave_out(kind_, state.camera.leaves_out(style::camera_part::trees));
}

gpu::Result<void> Trees::build(scene::TileBuild& b) const {
    const geometry::TileTrees g =
        geometry::build_trees({.tile = &b.tile(), .head = &b.head(), .style = &b.style(), .kind = kind_});
    if (g.trees.empty()) return {};
    // a frame cell's level's tile (L13, L12, drawn beyond the finest level's reach): lean records, small crowns
    const bool lean = b.tile().header.level < 14;
    const std::vector<hlsl::uint4> words = tree_words(g, b.origin().x, b.origin().y, b.origin().z, lean);
    ATLAS_GPU_LET(
        range, b.allocate(scene::HeapKind::points, static_cast<std::uint32_t>(words.size() * sizeof(hlsl::uint4)), 16));
    b.heaps().write(range, std::as_bytes(std::span(words)));
    b.out().ranges[0] = range;
    auto layout = std::make_shared<TreeTile>();
    layout->trees = static_cast<std::uint32_t>(g.trees.size());
    layout->lean = lean;
    layout->r.reserve(g.trees.size());
    layout->evergreen.reserve(g.trees.size());
    layout->ids.reserve(g.trees.size());
    for (const geometry::Tree& t : g.trees) {
        layout->r.push_back(t.r);
        layout->evergreen.push_back(t.evergreen ? 1U : 0U);
        layout->ids.push_back(t.id);
    }
    for (const scene::ItemSpec& item : g.items) b.add_item(item);
    const std::size_t bytes = sizeof(TreeTile) + (g.trees.size() * (sizeof(double) + 1 + sizeof(std::int64_t)));
    b.keep(std::shared_ptr<const TreeTile>(std::move(layout)), bytes);
    return {};
}

void Trees::drawn(const frame::FrameState& state, std::vector<Drawn>& out) const {
    out.clear();
    if (state.cover == nullptr || state.ranks == nullptr) return;
    const std::uint32_t me = index();
    const std::span<const std::uint32_t> table = state.ranks->table();
    const std::span<const double> depth = state.ranks->depth();
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    for (std::size_t c = 0; c < tiles.size(); ++c) {
        const scene::CoverTile& t = tiles[c];
        if (t.drawn == nullptr || me >= t.drawn->passes.size()) continue;
        const scene::PassTile& mine = t.drawn->passes[me];
        const auto* trees = mine.cpu_as<TreeTile>();
        if (trees == nullptr || !mine.ranges[0]) continue;
        // a tile's trees once, though it draws for several tiles (a stand-in ancestor, its cells over each)
        bool seen = false;
        for (std::size_t e = 0; e < c && !seen; ++e) seen = tiles[e].drawn == t.drawn;
        if (seen) continue;
        const std::uint32_t first = first_tree(*t.drawn, kind_);
        const std::span<const std::uint32_t> rows(t.drawn->rows);
        for (std::uint32_t i = 0; i < trees->trees && first + i < rows.size(); ++i) {
            const std::uint32_t row = rows[first + i];
            const std::uint32_t rank = row < table.size() ? table[row] : shaders::RANK_NONE;
            if (rank == shaders::RANK_NONE) continue;
            out.push_back({.cover = static_cast<std::uint32_t>(c), .tree = i, .rank = rank, .depth = depth[row]});
        }
    }
    // the nearest first (the latest slots), so early depth testing rejects what they hide
    std::ranges::stable_sort(out, std::ranges::greater{}, [](const Drawn& d) { return shaders::rank_slot(d.rank); });
}

void Trees::update(const frame::FrameState& state) {
    counts_ = {};
    if (decide_ == nullptr || state.heaps == nullptr || !state.view ||
        state.camera.leaves_out(style::camera_part::trees)) {
        return;
    }
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    drawn(state, drawn_);
    if (drawn_.empty()) return;
    const std::uint32_t me = index();
    const bool weather = (state.block.theme.flags.x & shaders::THEME_WEATHER) != 0U;
    const bool night = (state.block.theme.flags.x & shaders::THEME_NIGHT) != 0U;
    const view::Camera& camera = state.view->camera();
    const bool right = tree_side_right(state.block.theme, camera);
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    words_.clear();
    fill_words_.clear();
    std::uint32_t records = 0;
    std::uint32_t sides = 0;
    for (const Drawn& d : drawn_) {
        const scene::CoverTile& t = tiles[d.cover];
        const scene::PassTile& mine = t.drawn->passes[me];
        const TreeTile& trees = *mine.cpu_as<TreeTile>();
        const TreeChoice choice =
            tree_choice(d.depth, camera.focal(), trees.r[d.tree], weather, night, right, trees.lean);
        const std::uint32_t n = shaders::tree_record_count(choice.flags);
        if (records + n > max_records) {
            if (!capped_) {
                log::warn("vk trees: a frame's records pass {}: the farther trees are left out", max_records);
                capped_ = true;
            }
            continue;
        }
        // a close tree's trunk for the plain fills (ENGINE.md gain 18)
        if ((choice.flags & (shaders::TREE_JOB_SMALL | shaders::TREE_JOB_GENERIC)) == 0U) {
            fill_words_.push_back(records + shaders::tree_trunk_record(choice.flags));
        }
        const bool side = (choice.flags & shaders::TREE_JOB_SIDE) != 0U;
        const std::uint32_t vectors = trees.lean ? shaders::TREE_LEAN_VECTORS : shaders::TREE_VECTORS;
        const std::uint32_t crowns = t.sun && t.sun->now ? t.sun->now->crowns_from : 0U;
        // a level's tile without bits: its crowns lit by day (TREE_DAY_BITS), never drawn as if in the shade
        const std::uint32_t sun = trees.lean && t.sun_base == scene::no_sun ? shaders::TREE_DAY_BITS : t.sun_base;
        const std::uint32_t words[] = {bits(t.place.offset.x),
                                       bits(t.place.offset.y),
                                       bits(t.place.offset.z),
                                       bits(t.place.offset.w),
                                       bits(t.place.scale.x),
                                       bits(t.place.scale.y),
                                       bits(t.place.scale.z),
                                       bits(t.place.scale.w),
                                       (mine.ranges[0].offset / 16) + (d.tree * vectors),
                                       choice.flags,
                                       shaders::rank_slot(d.rank),
                                       records,
                                       bits(choice.w0),
                                       bits(choice.w1),
                                       bits(choice.limb),
                                       side ? sides : 0U,
                                       sun,
                                       bits(t.sun_weight),
                                       crowns + d.tree,
                                       shaders::TREE_TONE_TINTS +
                                           (trees.evergreen[d.tree] != 0 ? shaders::TREE_TINT_TONES : 0U)};
        static_assert(std::size(words) == std::size_t{4} * shaders::TREE_JOB_VECTORS);
        static_assert(scene::no_sun == shaders::TREE_NO_BITS);
        words_.insert(words_.end(), std::begin(words), std::end(words));
        records += n;
        if (side) ++sides;
    }
    // the sunlit sides far to near (painter's order, translucent): the jobs went nearest first
    for (std::size_t at = 0; at < words_.size(); at += std::size_t{4} * shaders::TREE_JOB_VECTORS) {
        if ((words_[at + 9] & shaders::TREE_JOB_SIDE) != 0U) words_[at + 15] = sides - 1 - words_[at + 15];
    }
    const auto jobs = static_cast<std::uint32_t>(words_.size() / (std::size_t{4} * shaders::TREE_JOB_VECTORS));
    if (jobs == 0) return;
    // the tones, then the crown filter's displacement at the frame's filter scale (render/closeup.py's crown_filter,
    // scale 10; DisplacementNode's srcX = x + fma(scaleForColor, channel, offset).toInt() for each of the 256 levels)
    std::array<std::uint32_t, shaders::TREE_TONE_WORDS> tones{};
    std::ranges::copy(tree_tones(state.block.theme, state.theme, style->trees), tones.begin());
    const fx::RippleDisplacement crown = fx::ripple_displacement(10.0F, static_cast<float>(state.camera.density));
    tones[shaders::TREE_TONE_CROWN_REACH] = static_cast<std::uint32_t>(crown.reach);
    for (std::uint32_t c = 0; c < 256; ++c) {
        const auto moved = static_cast<std::int32_t>(std::fma(crown.factor, static_cast<float>(c), crown.offset));
        tones[shaders::TREE_TONE_CROWN_OFFSETS + c] = std::bit_cast<std::uint32_t>(moved);
    }
    // into this frame slot's buffers (its previous frame has finished: its buffers and set are free)
    const std::uint32_t slot = state.slot % 2;
    using B = vk::BufferUsageFlagBits;
    const auto vectors = [](std::uint32_t n) {
        return vk::DeviceSize{std::max(n, 1U)} * shaders::TREE_RECORD_VECTORS * 16;
    };
    auto jobs_made =
        fit(*device_, jobs_[slot], words_.size() * sizeof(std::uint32_t), B::eStorageBuffer, gpu::MemoryUse::upload);
    auto tones_made = fit(*device_, tones_[slot], sizeof tones, B::eStorageBuffer, gpu::MemoryUse::upload);
    auto records_made = fit(*device_, records_[slot], vectors(records), B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto sides_made = fit(*device_, sides_[slot], vectors(sides), B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto fills_made = fit(*device_, fills_[slot], std::max<std::size_t>(fill_words_.size(), 1) * sizeof(std::uint32_t),
                          B::eStorageBuffer, gpu::MemoryUse::upload);
    if (!jobs_made || !tones_made || !records_made || !sides_made || !fills_made) {
        const gpu::Error& e = !jobs_made      ? jobs_made.error()
                              : !tones_made   ? tones_made.error()
                              : !records_made ? records_made.error()
                              : !sides_made   ? sides_made.error()
                                              : fills_made.error();
        log::error("vk trees: {} jobs, {} records, {} sides and {} fills: {}", jobs, records, sides, fill_words_.size(),
                   e.text());
        jobs_[slot] = {};
        tones_[slot] = {};
        records_[slot] = {};
        sides_[slot] = {};
        fills_[slot] = {};
        return;
    }
    if (*jobs_made || *tones_made || *records_made || *sides_made || *fills_made) {
        gpu::SetWriter(*sets_[slot])
            .buffer(0, vk::DescriptorType::eStorageBuffer, jobs_[slot].handle())
            .buffer(1, vk::DescriptorType::eStorageBuffer, tones_[slot].handle())
            .buffer(2, vk::DescriptorType::eStorageBuffer, records_[slot].handle())
            .buffer(3, vk::DescriptorType::eStorageBuffer, records_[slot].handle())
            .buffer(4, vk::DescriptorType::eStorageBuffer, sides_[slot].handle())
            .buffer(5, vk::DescriptorType::eStorageBuffer, sides_[slot].handle())
            .buffer(6, vk::DescriptorType::eStorageBuffer, fills_[slot].handle())
            .apply(device_->handle());
    }
    std::memcpy(jobs_[slot].bytes().data(), words_.data(), words_.size() * sizeof(std::uint32_t));
    jobs_[slot].flush();
    std::memcpy(tones_[slot].bytes().data(), tones.data(), sizeof tones);
    tones_[slot].flush();
    if (!fill_words_.empty()) {
        std::memcpy(fills_[slot].bytes().data(), fill_words_.data(), fill_words_.size() * sizeof(std::uint32_t));
        fills_[slot].flush();
    }
    slot_ = slot;
    counts_ = {
        .jobs = jobs, .records = records, .sides = sides, .fills = static_cast<std::uint32_t>(fill_words_.size())};
}

void Trees::record(const frame::Recording& recording, std::uint32_t phase) {
    switch (phase) {
    case draw_phase: record_draw(recording); break;
    case decide_phase: record_decide(recording); break;
    case sides_phase: record_sides(recording); break;
    default: break;
    }
}

void Trees::record_decide(const frame::Recording& recording) {
    if (decide_ == nullptr || counts_.jobs == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *decide_);
    const auto frame_sets = recording.layout.sets();
    const auto offsets = recording.layout.offsets(recording.frame.slot.slot);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *compute_layout_, 0, frame_sets, offsets);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *compute_layout_, 2, *sets_[slot_], {});
    const hlsl::uint4 push{counts_.jobs, 0U, 0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*compute_layout_, vk::ShaderStageFlagBits::eCompute, 0, push);
    const std::uint32_t groups = (counts_.jobs + 63) / 64;
    cmd.dispatch(std::min(groups, job_row), (groups + job_row - 1) / job_row, 1);
    recording.counters.dispatch("trees");
    // the records, for the trees' vertex stages
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    recording.frame.sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                            .src_access = A::eShaderWrite,
                                                            .dst_stage = S::eVertexShader,
                                                            .dst_access = A::eShaderRead});
}

void Trees::record_draw(const frame::Recording& recording) {
    if (draw_ == nullptr || counts_.records == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    // set 2 under the frame's sets, which the scene subpass keeps bound; the fragments' samples a pixel
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_[slot_], {});
    // At true depth with float depth the close trees' trunks below depth 1 (tree_fill) draw after the rest, alone,
    // through a pipeline that writes no mask and discards nothing, so a tiler keeps its early depth test and
    // hidden-surface removal for them (ENGINE.md gain 18); behind their crowns and limbs (slot 0), they are shaded only
    // where those leave them. A trunk is its tree's last record, so it passes none of its own tree's; another tree's
    // record would tie it only with their planes' depths equal to the float's last bit (in a ratio of two leads). On
    // 24-bit depth, whose step holds trees a few centimetres apart a few hundred metres out at one depth, and under the
    // painter switch, one draw as before.
    const bool fills = counts_.fills > 0 && (recording.state.block.camera.flags.x &
                                             (shaders::CAMERA_PAINTER | shaders::CAMERA_DEPTH24)) == 0U;
    const hlsl::uint4 push{static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), fills ? 1U : 0U,
                           0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *draw_);
    cmd.draw(counts_.records * shaders::TREE_RECORD_VERTICES, 1, 0, 0);
    recording.counters.draw("trees", std::uint64_t{counts_.records} * 2);
    if (!fills) return;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *fill_);
    cmd.draw(counts_.fills * shaders::TREE_RECORD_VERTICES, 1, 0, 0);
    recording.counters.draw("tree fills", std::uint64_t{counts_.fills} * 2);
}

void Trees::record_sides(const frame::Recording& recording) {
    if (side_ == nullptr || counts_.sides == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_[slot_], {});
    const hlsl::uint4 push{static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), 0U, 0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *side_);
    cmd.draw(counts_.sides * shaders::TREE_RECORD_VERTICES, 1, 0, 0);
    recording.counters.draw("tree sides", std::uint64_t{counts_.sides} * 2);
}

void Trees::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    if (phase != draw_phase) return;
    emit_records(state, out, nullptr, nullptr);
}

void Trees::emit_records(const frame::FrameState& state, twin::Stream& out, std::vector<shaders::TreeRecord>* records,
                         std::vector<shaders::TreeRecord>* sides) const {
    emitted_ = 0;
    if (state.cover == nullptr || state.ranks == nullptr || state.heaps == nullptr || !state.view) return;
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    std::vector<Drawn> trees;
    drawn(state, trees);
    if (trees.empty()) return;
    const std::size_t before = out.elements();
    out.layer(twin::Layer::objects); // the port's objects image (`o`)
    twin::TreeTwin twin(state, *style, out);
    twin.keep_records(records, sides);
    const std::uint32_t me = index();
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    for (const Drawn& d : trees) {
        const scene::CoverTile& t = tiles[d.cover];
        const scene::PassTile& mine = t.drawn->passes[me];
        twin.tree(t, *mine.cpu_as<TreeTile>(), state.heaps->writer(mine.ranges[0]).bytes(), d.tree, d.rank, d.depth);
    }
    emitted_ = out.elements() - before;
}

} // namespace atlas::passes
