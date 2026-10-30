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
#include "tiles/Store.hpp"
#include "tiles/layers/Labels.hpp"
#include "twin/Twin.hpp"
#include "view/Frames.hpp"
#include "view/Ladder.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <format>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace atlas::passes {

namespace {

namespace sl = layouts::text_slug;

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

double canal_names_radius(std::optional<double> view_radius, double dist, bool engine_mode) noexcept {
    if (view_radius && *view_radius > 0) return *view_radius;
    return engine_mode ? 1e9 : view::live_names_radius(dist);
}

GroundText::~GroundText() { GroundText::release(); }

gpu::Result<void> GroundText::build(scene::TileBuild& b) const {
    auto kept = std::make_shared<CanalTile>();
    if (const tiles::Layer* labels = b.tile().layer(tiles::LayerId::label)) {
        text::canal_candidates(*labels, b.head().frame, kept->candidates);
    }
    kept->water_level = b.style().water_y;
    kept->look = b.style().canal_names;
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
    auto canals = text::pack_canals(b.pack().pack, b.pack().kind == tiles::PackKind::window);
    if (!canals) return gpu::fail(std::format("the canal names: {}", tiles::describe(canals.error())));
    auto kept = std::make_shared<text::PackCanals>(std::move(*canals));
    std::size_t bytes = sizeof(text::PackCanals) + (kept->candidates.capacity() * sizeof(text::CanalCandidate));
    for (const text::CanalCandidate& c : kept->candidates) bytes += c.name.capacity();
    b.keep(std::shared_ptr<const text::PackCanals>(std::move(kept)), bytes);
    return {};
}

void GroundText::gather(const frame::FrameState& state) {
    const std::uint32_t me = index(); // this pass's place in a tile's and a pack's stage T
    // The active pack's names whole, once its own stage T has given them; until then, or with none drawn, its cover's
    // tiles' (a pack the tests hand over with use_pack stays).
    const std::shared_ptr<const void>* built = state.active_pack != nullptr && me < state.active_pack->passes.size()
                                                   ? &state.active_pack->passes[me].cpu
                                                   : nullptr;
    if (built != nullptr && *built) {
        pack_ = std::static_pointer_cast<const text::PackCanals>(*built);
        pack_from_frame_ = true;
    } else if (pack_from_frame_) {
        pack_.reset();
        pack_from_frame_ = false;
    }
    // Every pack drawn but the coarser one, which lies under the active pack: the active one and the neighbours (frame
    // cells' windows where four cells meet), each its names whole where its stage T is built, else its cover's tiles'.
    struct Whole {
        std::shared_ptr<const text::PackCanals> canals;
        std::string_view id;
        std::optional<view::GroundFrame> frame; ///< its points' (none: the frame drawn's, the active pack's)
    };
    std::vector<Whole> whole;
    const std::string_view active = state.cover != nullptr ? state.cover->active() : std::string_view();
    if (pack_) whole.push_back({.canals = pack_, .id = active, .frame = std::nullopt});
    for (const scene::ResidentPack* n : state.neighbour_packs) {
        if (n == nullptr || n->source == nullptr || me >= n->passes.size() || !n->passes[me].cpu) continue;
        whole.push_back({.canals = std::static_pointer_cast<const text::PackCanals>(n->passes[me].cpu),
                         .id = n->id,
                         .frame = view::frame_of(n->source->pack.head()->frame)});
    }
    const std::string_view coarser = state.cover != nullptr ? state.cover->coarser() : std::string_view();
    std::vector<const scene::CoverTile*> loose; // the cover's tiles of the packs not read whole
    std::vector<std::uint64_t> tiles;
    if (state.cover != nullptr) {
        for (const scene::CoverTile& c : state.cover->tiles()) {
            if (c.drawn == nullptr || me >= c.drawn->passes.size()) continue;
            if (c.drawn->passes[me].cpu_as<CanalTile>() == nullptr || c.drawn->pack == coarser) continue;
            if (std::ranges::any_of(whole, [&c](const Whole& w) { return w.id == c.drawn->pack; })) continue;
            loose.push_back(&c);
            tiles.push_back(c.drawn->key.packed());
            tiles.push_back(c.drawn->handle);
        }
    }
    std::ranges::sort(tiles);
    // Read again only when what they come from changes: a pack's names, the cover's tiles, the frame drawn.
    const std::optional<view::GroundFrame> drawn = state.ground_frame;
    const bool same_packs = std::ranges::equal(
        whole, read_packs_, [](const Whole& w, const auto& r) { return w.canals == r.first && w.id == r.second; });
    if (same_packs && tiles == read_tiles_ && drawn == read_frame_) return;
    read_packs_.clear();
    for (const Whole& w : whole) read_packs_.emplace_back(w.canals, std::string(w.id));
    read_tiles_ = std::move(tiles);
    read_frame_ = drawn;
    // Each candidate in the frame drawn (a neighbour's points are its frame cell's metres), known by its source's group
    // and index: a pack's own source order is its group (canal_group), a frame cell's windows' each window's.
    const auto taken = [&drawn](text::CanalCandidate k, const std::optional<view::GroundFrame>& from,
                                std::uint64_t group) {
        if (k.group == 0) k.group = group;
        if (from && drawn && *from != *drawn) {
            const view::Vec3 mid = view::convert(*from, *drawn, {k.mid_x, 0.0, k.mid_z});
            const view::Vec3 prev = view::convert(*from, *drawn, {k.prev_x, 0.0, k.prev_z});
            k.mid_x = mid.x;
            k.mid_z = mid.z;
            k.prev_x = prev.x;
            k.prev_z = prev.z;
        }
        return k;
    };
    std::vector<std::pair<std::uint8_t, text::CanalCandidate>> all; // its tile's zoom, the candidate
    bool styled = false;
    for (const Whole& w : whole) {
        const std::uint64_t group = text::canal_group(w.id);
        for (const text::CanalCandidate& k : w.canals->candidates) all.emplace_back(0xFF, taken(k, w.frame, group));
        if (!styled) {
            water_level_ = w.canals->water_level;
            look_ = w.canals->look;
            styled = true;
        }
    }
    for (const scene::CoverTile* c : loose) {
        const auto* kept = c->drawn->passes[me].cpu_as<CanalTile>();
        const std::uint64_t group =
            c->drawn->kind == tiles::PackKind::window ? c->drawn->key.packed() : text::canal_group(c->drawn->pack);
        for (const text::CanalCandidate& k : kept->candidates) {
            all.emplace_back(c->drawn->key.z, taken(k, c->drawn->frame, group));
        }
        if (!styled) {
            water_level_ = kept->water_level;
            look_ = kept->look;
            styled = true;
        }
    }
    // each once, from the finest tile that holds it (the cover may hold a tile and its ancestor)
    std::ranges::sort(all, [](const auto& a, const auto& b) {
        const auto ka = std::pair{a.second.group, a.second.index};
        const auto kb = std::pair{b.second.group, b.second.index};
        return ka != kb ? ka < kb : a.first > b.first;
    });
    std::vector<text::CanalCandidate> merged;
    merged.reserve(all.size());
    for (auto& [z, k] : all) {
        if (merged.empty() || merged.back().group != k.group || merged.back().index != k.index) {
            merged.push_back(std::move(k));
        }
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

void GroundText::draw_run(const frame::FrameState& state, const text::TextRun& run, std::uint32_t slot, float alpha) {
    const Shaped& s = shaped(run);
    if (!s.ok) return;
    // At rest the matrix the SVG prints, which the oracle draws; moving, the camera's own.
    std::array<double, 6> m = run.matrix;
    if (state.rest) {
        const std::array<float, 6> snapped = run.snapped_matrix();
        for (std::size_t i = 0; i < m.size(); ++i) m.at(i) = static_cast<double>(snapped.at(i));
    }
    laid_.clear();
    text::lay_out(run, m, s.run, s.places, 1.0, laid_);
    const auto face = text::resolve(run.family, run.weight, false);
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
             .colour = {g.colour[0] * alpha, g.colour[1] * alpha, g.colour[2] * alpha, g.colour[3] * alpha},
             .info = {table, slot, 0, 0}});
    }
}

void GroundText::update(const frame::FrameState& state) {
    const std::uint32_t slot = state.slot & 1U;
    drawn_.at(slot) = {0, 0};
    draws_.clear();
    placed_.clear();
    designators_.clear();
    fading_ = false; // a frame that places no names leaves none fading
    // (the names on the ground: unless the map leaves them out)
    if (!state.view.has_value() || state.camera.leaves_out(style::camera_part::ground_text)) return;
    gather(state);
    const view::Camera& cam = state.view->camera();
    const double radius = state.placed ? canal_names_radius(state.placed->number("radius"), state.placed->dist(),
                                                            state.camera.has(style::camera_flag::engine_mode))
                                       : 1e9;
    const text::CanalView view{.camera = &cam,
                               .target_x = cam.spec().target.x,
                               .target_z = cam.spec().target.z,
                               .radius = radius,
                               .pitch = cam.spec().pitch,
                               .night = state.theme.has(style::theme_flag::night),
                               .weather = state.theme.has(style::theme_flag::weather),
                               .water_level = water_level_,
                               .look = look_,
                               .rest = state.rest,
                               .seconds = state.seconds};
    const auto names = names_.update(view);
    fading_ = names_.fading();
    placed_.assign(names.begin(), names.end());
    for (const text::PlacedName& p : placed_) draw_run(state, p.drawn, first_ground_slot(), p.alpha);
    const auto named = static_cast<std::uint32_t>(draws_.size());
    // the runways' designators the ground laid, each at its runway's slot
    if (state.ground_runs != nullptr) {
        designators_ = state.ground_runs->runs;
        for (const frame::GroundRun& r : designators_) draw_run(state, r.run, r.slot, 1.0F);
    }
    if (draw_buffer_ && !draws_.empty()) {
        std::memcpy(draw_buffer_.bytes().data() + (slot * slot_bytes), draws_.data(), draws_.size() * draw_bytes);
        draw_buffer_.flush();
        drawn_.at(slot) = {named, static_cast<std::uint32_t>(draws_.size()) - named};
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
    const std::uint32_t slot = recording.state.slot & 1U;
    const std::array<std::uint32_t, 2>& drawn = drawn_.at(slot);
    const bool names = phase == phase_names;
    const std::uint32_t count = names ? drawn[0] : drawn[1];
    if (!*pipeline_ || count == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot), {});
    cmd.draw(6, count, 0, names ? 0 : drawn[0]);
    recording.counters.draw(names ? "canal names" : "runway designators", 2ULL * count, count);
}

void GroundText::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    if (phase == phase_designators) {
        if (designators_.empty()) return;
        out.layer(twin::Layer::ground);
        // draw_over's group: render opens its `mat` group around the areas, the roads and draw_over with weather
        const bool weather = state.theme.has(style::theme_flag::weather);
        const std::int32_t parent =
            weather ? out.group({.node = {.filter = out.filter("mat")}, .parent = out.root()}) : out.root();
        for (const frame::GroundRun& r : designators_) {
            const text::TextRun& run = r.run;
            twin::Node node{.opacity = run.printed_opacity(), .transformed = true};
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
            out.at(r.slot, parent);
            out.text({.fill = twin::Paint::colour(0xFF00'0000U | run.fill)}, run.text, 0.0F, 0.0F, font, node);
        }
        return;
    }
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
