// The ground's text and set 1's text globals (passes/GroundText.hpp).
#include "passes/GroundText.hpp"

#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/spirv/text/slug.hpp"
#include "gpu/Descriptors.hpp"
#include "scene/Cover.hpp"
#include "scene/Packs.hpp"
#include "scene/Residency.hpp"
#include "style/CameraState.hpp"
#include "style/Theme.hpp"
#include "text/Fonts.hpp"
#include "text/Outlines.hpp"
#include "tiles/Error.hpp"
#include "tiles/Sections.hpp"
#include "tiles/layers/Labels.hpp"
#include "twin/Twin.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <format>

namespace atlas::passes {

namespace {

namespace sl = layouts::text_slug;

constexpr std::uint32_t none = 0xFFFF'FFFFU;
constexpr std::size_t draw_bytes = sizeof(sl::GlyphDraw);
constexpr std::size_t slot_bytes = GroundText::max_draws * draw_bytes;
constexpr std::size_t glyph_capacity = std::size_t{text::slug::GlyphAtlas::max_rows} << text::slug::log_texture_width;

/// The coverage tables, a face each (sans, serif, serif italic): the identity until calibrated against Skia's text on
/// the emulator (D13).
constexpr std::size_t table_entries = 256 * text::face_count;

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

static_assert(static_cast<int>(text::Anchor::start) == static_cast<int>(twin::TextAnchor::start) &&
              static_cast<int>(text::Anchor::middle) == static_cast<int>(twin::TextAnchor::middle) &&
              static_cast<int>(text::Anchor::end) == static_cast<int>(twin::TextAnchor::end));

} // namespace

GroundText::~GroundText() { GroundText::release(); }

gpu::Result<void> GroundText::build(scene::TileBuild& b) const {
    index_.store(b.pass(), std::memory_order_relaxed);
    auto kept = std::make_shared<CanalTile>();
    if (const tiles::Layer* labels = b.tile().layer(tiles::LayerId::label)) {
        text::canal_candidates(*labels, b.head().frame, kept->candidates);
    }
    if (const tiles::StyleTables* style = b.style()) {
        if (const auto state = style->table("state")) {
            if (const auto level = state->find("water_y")) kept->water_level = level->number().value_or(-1.5);
        }
    }
    std::size_t bytes = sizeof(CanalTile) + (kept->candidates.capacity() * sizeof(text::CanalCandidate));
    for (const text::CanalCandidate& c : kept->candidates) bytes += c.name.capacity();
    b.keep(std::shared_ptr<const CanalTile>(std::move(kept)), bytes);
    return {};
}

std::uint32_t GroundText::ground_slots(const frame::FrameState& state) const {
    // One slot for the names, the last of the ground's marks, wherever the world has tiles to bring them.
    return state.view.has_value() && state.cover != nullptr ? 1 : 0;
}

std::vector<std::shared_ptr<frame::GlobalProvider>> GroundText::providers(const gpu::Device& device,
                                                                          const frame::Resources& resources) const {
    if (resources.read) {
        text::Fonts& fonts = text::shared_fonts();
        for (std::size_t f = 0; f < text::face_count; ++f) {
            const auto face = static_cast<text::Face>(f);
            if (fonts.loaded(face)) continue;
            const std::string path = std::format("{}/{}", text::asset_folder, text::file_of(face));
            if (const auto bytes = resources.read(path); bytes) {
                if (auto loaded = fonts.load(face, *bytes); !loaded) {
                    log::warn("vk text: {} from {}: not loaded", path, resources.origin);
                }
            }
        }
    }
    return {std::make_shared<GlyphGlobals>(device)};
}

gpu::Result<void> GroundText::build_pack(scene::PackBuild& b) const {
    auto canals = text::pack_canals(b.pack().pack);
    if (!canals) return gpu::fail(std::format("the canal names: {}", tiles::describe(canals.error())));
    auto kept = std::make_shared<text::PackCanals>(std::move(*canals));
    std::size_t bytes = sizeof(text::PackCanals) + (kept->candidates.capacity() * sizeof(text::CanalCandidate));
    for (const text::CanalCandidate& c : kept->candidates) bytes += c.name.capacity();
    b.keep(std::shared_ptr<const text::PackCanals>(std::move(kept)), bytes);
    return {};
}

void GroundText::gather(const frame::FrameState& state) {
    // The drawn pack's names whole, once its own stage T has given them; until then, or with none drawn, the cover's
    // tiles' (a pack the tests hand over with use_pack stays).
    const std::shared_ptr<const void>* built =
        state.active_pack != nullptr && index() < state.active_pack->passes.size()
            ? &state.active_pack->passes[index()].cpu
            : nullptr;
    if (built != nullptr && *built) {
        pack_ = std::static_pointer_cast<const text::PackCanals>(*built);
        pack_from_frame_ = true;
    } else if (pack_from_frame_) {
        pack_.reset();
        pack_from_frame_ = false;
    }
    if (pack_) {
        // The pack's names whole: read again only when the pack's data change.
        if (pack_ != pack_read_) {
            pack_read_ = pack_;
            tiles_.clear();
            water_level_ = pack_->water_level;
            names_.set_candidates(pack_->candidates);
        }
        return;
    }
    pack_read_.reset();
    const std::uint32_t me = index_.load(std::memory_order_relaxed);
    std::vector<std::pair<std::uint64_t, std::uint32_t>> tiles; // the tiles holding candidates: key, handle
    if (state.cover != nullptr && me != none) {
        for (const scene::CoverTile& c : state.cover->tiles()) {
            if (c.drawn == nullptr || me >= c.drawn->passes.size()) continue;
            if (c.drawn->passes[me].cpu_as<CanalTile>() == nullptr) continue;
            tiles.emplace_back(c.drawn->key.packed(), c.drawn->handle);
        }
    }
    std::ranges::sort(tiles);
    std::vector<std::uint32_t> flat;
    flat.reserve(tiles.size() * 3);
    for (const auto& [key, handle] : tiles) {
        flat.push_back(static_cast<std::uint32_t>(key >> 32U));
        flat.push_back(static_cast<std::uint32_t>(key));
        flat.push_back(handle);
    }
    if (flat == tiles_) return;
    tiles_ = std::move(flat);
    // The candidates of the cover's tiles, each once, from the finest tile that holds it (the cover may hold a tile and
    // its ancestor): every tile the cover draws is the active pack's, so all are in the frame drawn's metres.
    std::vector<std::pair<std::uint8_t, const text::CanalCandidate*>> all; // its tile's zoom, the candidate
    if (state.cover != nullptr && me != none) {
        for (const scene::CoverTile& c : state.cover->tiles()) {
            if (c.drawn == nullptr || me >= c.drawn->passes.size()) continue;
            const auto* kept = c.drawn->passes[me].cpu_as<CanalTile>();
            if (kept == nullptr) continue;
            for (const text::CanalCandidate& k : kept->candidates) all.emplace_back(c.drawn->key.z, &k);
            water_level_ = kept->water_level;
        }
    }
    std::ranges::sort(all, [](const auto& a, const auto& b) {
        return a.second->index != b.second->index ? a.second->index < b.second->index : a.first > b.first;
    });
    std::vector<text::CanalCandidate> merged;
    merged.reserve(all.size());
    for (const auto& [z, k] : all) {
        if (merged.empty() || merged.back().index != k->index) merged.push_back(*k);
    }
    names_.set_candidates(std::move(merged));
}

const GroundText::Shaped& GroundText::shaped(const text::TextRun& run) {
    static const Shaped nothing;
    const text::Rounding rounding = text::platform_rounding();
    const auto style = run.shape_style(rounding);
    if (!style) return nothing;
    const std::string key =
        std::format("{}\x1f{}\x1f{}\x1f{:08x}\x1f{:08x}\x1f{}\x1f{}", run.text, static_cast<int>(style->font.face),
                    style->font.weight, bits(style->size), bits(style->letter_spacing), static_cast<int>(style->anchor),
                    static_cast<int>(rounding));
    if (const auto it = shaped_.find(key); it != shaped_.end()) return it->second;
    const text::Fonts& fonts = text::shared_fonts();
    const auto instance = fonts.instance(style->font);
    if (!instance) return nothing; // the face is not loaded yet: asked again next frame
    Shaped s;
    if (auto run_shaped = text::shape(fonts, *style, run.text)) {
        s.run = std::move(*run_shaped);
        s.places.reserve(s.run.glyphs.size());
        text::slug::GlyphAtlas& glyphs = text::slug::shared_glyphs();
        for (const std::uint32_t id : s.run.glyphs) s.places.push_back(glyphs.glyph(**instance, id));
        s.ok = true;
    }
    return shaped_.emplace(key, std::move(s)).first->second;
}

void GroundText::update(const frame::FrameState& state) {
    const std::uint32_t slot = state.slot & 1U;
    drawn_.at(slot) = 0;
    draws_.clear();
    placed_.clear();
    if (!state.view.has_value()) return;
    gather(state);
    const view::Camera& cam = state.view->camera();
    double radius = 1e9;
    if (state.placed) {
        if (const auto r = state.placed->number("radius"); r && *r > 0) radius = *r;
    }
    const text::CanalView view{.camera = &cam,
                               .target_x = cam.spec().target.x,
                               .target_z = cam.spec().target.z,
                               .radius = radius,
                               .pitch = cam.spec().pitch,
                               .night = state.theme.has(style::theme_flag::night),
                               .weather = state.theme.has(style::theme_flag::weather),
                               .water_level = water_level_,
                               .rest = state.rest,
                               .seconds = state.seconds};
    const auto names = names_.update(view);
    placed_.assign(names.begin(), names.end());
    for (const text::PlacedName& p : placed_) {
        const Shaped& s = shaped(p.drawn);
        if (!s.ok) continue;
        // At rest the matrix the SVG prints, which the oracle draws; moving, the camera's own.
        std::array<double, 6> m = p.drawn.matrix;
        if (state.rest) {
            const std::array<float, 6> snapped = p.drawn.snapped_matrix();
            for (std::size_t i = 0; i < m.size(); ++i) m.at(i) = static_cast<double>(snapped.at(i));
        }
        laid_.clear();
        text::lay_out(p.drawn, m, s.run, s.places, 1.0, laid_);
        const auto face = text::resolve(p.drawn.family, p.drawn.weight, false);
        const std::uint32_t table = face ? static_cast<std::uint32_t>(face->face) : 0U;
        for (const text::GlyphInstance& g : laid_) {
            if (draws_.size() >= max_draws) break;
            const text::slug::Glyph& at = *g.glyph;
            draws_.push_back(
                {.row0 = {g.row0[0], g.row0[1], g.row0[2], 0.0F},
                 .row1 = {g.row1[0], g.row1[1], g.row1[2], 0.0F},
                 .box = {at.box.x0, at.box.y0, at.box.x1, at.box.y1},
                 .bands = {at.scale_x, at.scale_y, at.offset_x, at.offset_y},
                 .location = {at.band_x, at.band_y, at.vbands - 1, at.hbands - 1},
                 .colour = {g.colour[0] * p.alpha, g.colour[1] * p.alpha, g.colour[2] * p.alpha, g.colour[3] * p.alpha},
                 .info = {table, first_ground_slot(), 0, 0}});
        }
    }
    if (draw_buffer_ && !draws_.empty()) {
        std::memcpy(draw_buffer_.bytes().data() + (slot * slot_bytes), draws_.data(), draws_.size() * draw_bytes);
        draw_buffer_.flush();
        drawn_.at(slot) = static_cast<std::uint32_t>(draws_.size());
    }
}

gpu::Result<void> GroundText::prepare(const frame::PassContext& context) {
    release();
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(module, frame::make_module(context, "text_slug", spirv::text_slug));
    module_ = std::move(module);

    // Set 2: the frame slot's glyphs and the coverage tables.
    const std::array bindings{
        gpu::Binding{.slot = sl::glyph_draws.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{.slot = sl::coverage_tables.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eFragment}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 2));
    pool_ = std::move(pool);
    for (vk::raii::DescriptorSet& set : sets_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        set = std::move(made);
    }
    const gpu::Memory& memory = context.device.memory();
    const vk::BufferUsageFlags storage = vk::BufferUsageFlagBits::eStorageBuffer;
    ATLAS_GPU_LET(tables, memory.buffer(table_entries * sizeof(float), storage, gpu::MemoryUse::upload));
    tables_ = std::move(tables);
    std::vector<float> identity(table_entries);
    for (std::size_t i = 0; i < table_entries; ++i) identity[i] = static_cast<float>(i % 256) / 255.0F;
    std::memcpy(tables_.bytes().data(), identity.data(), identity.size() * sizeof(float));
    tables_.flush();
    ATLAS_GPU_LET(draws, memory.buffer(2 * slot_bytes, storage, gpu::MemoryUse::upload));
    draw_buffer_ = std::move(draws);
    for (std::size_t s = 0; s < sets_.size(); ++s) {
        gpu::SetWriter(*sets_.at(s))
            .buffer(sl::glyph_draws.binding, vk::DescriptorType::eStorageBuffer, draw_buffer_.handle(), s * slot_bytes,
                    slot_bytes)
            .buffer(sl::coverage_tables.binding, vk::DescriptorType::eStorageBuffer, tables_.handle())
            .apply(d);
    }

    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    gpu::GraphicsDesc desc{
        .label = {.name = "ground text", .module = "text_slug", .configuration = context.configuration},
        .module = *module_,
        .vertex = "ground_text_vs",
        .fragment = "ground_text_fs",
        .layout = *layout_,
        .target = context.pass.scene_target(),
        .depth_test = true,
        .depth_write = false,
        .depth_op = vk::CompareOp::eGreater};
    desc.blends[0] = gpu::Blend::over();
    ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::translucent));
    pipeline_ = std::move(pipeline);
    return {};
}

void GroundText::release() noexcept {
    pipeline_.clear();
    layout_.clear();
    for (vk::raii::DescriptorSet& set : sets_) set.clear();
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    draw_buffer_ = gpu::Buffer{};
    tables_ = gpu::Buffer{};
    drawn_ = {};
}

void GroundText::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
    const std::uint32_t slot = recording.state.slot & 1U;
    const std::uint32_t count = drawn_.at(slot);
    if (!*pipeline_ || count == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot), {});
    cmd.draw(6, count, 0, 0);
    recording.counters.draw("canal names", 2ULL * count, count);
}

void GroundText::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    (void)phase;
    if (placed_.empty()) return;
    out.layer(twin::Layer::ground);
    out.at(first_ground_slot(), out.root());
    std::size_t turned = 0;
    for (const text::PlacedName& p : placed_) {
        // The renders' own run: a name the frame turns for reading is D13's intended deviation, which L1 compares
        // unturned and counts (the log's line below); a way a name left, fading out, is not the renders'.
        if (p.leaving) continue;
        turned += p.turned ? 1U : 0U;
        const text::TextRun& run = p.rule;
        twin::Node node{.opacity = run.printed_opacity() * p.alpha, .transformed = true};
        if (state.rest) {
            node.transform = run.snapped_matrix();
        } else {
            for (std::size_t i = 0; i < node.transform.size(); ++i) {
                node.transform.at(i) = static_cast<float>(run.matrix.at(i));
            }
        }
        const twin::Font font{.family = run.family,
                              .size = run.printed_size(),
                              .weight = run.weight,
                              .italic = false,
                              .letter_spacing = run.printed_spacing(),
                              .anchor = static_cast<twin::TextAnchor>(run.anchor)};
        out.text({.fill = twin::Paint::colour(0xFF00'0000U | run.fill)}, run.text, 0.0F, 0.0F, font, node);
    }
    log::info("ground text twin: {} names, {} of them drawn turned for reading (D13), emitted as the renders draw them",
              placed_.size(), turned);
}

// ---- Set 1's glyphs.

void GlyphGlobals::update(const frame::FrameState& state, frame::Globals& globals) {
    (void)state;
    if (!curves_) {
        // The buffers, once, at the shared atlas's rows (record copies what is added before each frame draws).
        const vk::BufferUsageFlags storage = vk::BufferUsageFlagBits::eStorageBuffer;
        auto curves =
            device_.memory().buffer(glyph_capacity * sizeof(text::slug::CurveTexel), storage, gpu::MemoryUse::upload);
        auto bands = device_.memory().buffer(glyph_capacity * sizeof(std::uint32_t), storage, gpu::MemoryUse::upload);
        if (!curves || !bands) {
            log::error("text: the glyphs' buffers: {}", (!curves ? curves.error() : bands.error()).text());
            return;
        }
        curves_ = std::move(*curves);
        bands_ = std::move(*bands);
        curves_copied_ = 0;
        bands_copied_ = 0;
    }
    // Every frame (each frame builder drawing the world has its own set 1): a binding that did not change costs a
    // comparison there.
    globals.buffer(frame::Global::glyph_curves, curves_.handle());
    globals.buffer(frame::Global::glyph_bands, bands_.handle());
}

void GlyphGlobals::record(const gpu::FrameContext& frame) {
    (void)frame;
    if (!curves_ || !bands_) return;
    // The texels the frame's passes added since the last copy (the atlas only grows): into memory no frame in flight
    // reads yet, before this frame's commands go to the GPU.
    text::slug::shared_glyphs().read([&](const text::slug::Atlas& atlas) {
        const std::size_t curves = std::min(atlas.curve_texels(), glyph_capacity);
        if (curves > curves_copied_) {
            const auto all = atlas.curves();
            std::memcpy(curves_.bytes().data() + (curves_copied_ * sizeof(text::slug::CurveTexel)),
                        all.data() + curves_copied_, (curves - curves_copied_) * sizeof(text::slug::CurveTexel));
            curves_.flush();
            curves_copied_ = curves;
        }
        const std::size_t bands = std::min(atlas.band_texels(), glyph_capacity);
        if (bands > bands_copied_) {
            const auto all = atlas.bands();
            std::vector<std::uint32_t> packed(bands - bands_copied_);
            for (std::size_t i = 0; i < packed.size(); ++i) {
                const text::slug::BandTexel& t = all[bands_copied_ + i];
                packed[i] = std::uint32_t{t.a} | (std::uint32_t{t.b} << 16U);
            }
            std::memcpy(bands_.bytes().data() + (bands_copied_ * sizeof(std::uint32_t)), packed.data(),
                        packed.size() * sizeof(std::uint32_t));
            bands_.flush();
            bands_copied_ = bands;
        }
    });
}

} // namespace atlas::passes
