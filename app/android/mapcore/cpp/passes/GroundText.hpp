// The ground's text (plan-vector D13, K13): the canal names on the water, drawn from their glyphs' outlines by Slug's
// method (shaders/text/slug.slang) in the scene subpass's translucent slot as part of the ground, so the composite
// hazes them as the renders' are hazed.
//   - Stage T (build, on the workers): each tile's canal name candidates (its label layer's, in source order) and the
//     region's water level (the pack's style), kept with the tile (PassTile::cpu).
//   - Stage F (update, on the render thread): the cover's tiles' candidates, each once; the names (text/CanalNames: the
//     renders' rule at rest, kept on their ways while moving, fading, turned for reading); each name shaped once as
//     Android shapes it (text/Shaper, the platform's kerning rule) in Geist 700, its glyphs' outlines added to the
//     shared curve and band data on first use (text/SlugBands' GlyphAtlas); its glyphs laid out under its matrix, at
//     rest the matrix as the SVG prints it (which the oracle draws), into the frame slot's draws.
//   - Record: one instanced draw, six vertices a glyph, at the pass's ground slot (one: the canal names are the last of
//     the ground's marks), after the ground's translucent phases up to the tram tracks (K9's ground_order:
//     canal_names), depth test on and writes off.
// The glyphs' curves and bands are set 1's, filled by TextGlobals (below), which also gives set 1 the board's label
// sprites; the frame slot's glyphs and the coverage tables are the pass's own set (2). The fonts are
// text::shared_fonts() (a face not loaded draws nothing).
#pragma once

#include "frame/Globals.hpp"
#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "gpu/Memory.hpp"
#include "text/CanalNames.hpp"
#include "text/SlugBands.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::labels {
struct LabelSet; // labels/Sprites.hpp
} // namespace atlas::labels

namespace atlas::passes {

/// The canal names' place among the translucent phases (K9's ground_order::canal_names: after the tram tracks, before
/// the shadows, as amsterdam.py appends its marks).
inline constexpr std::int32_t canal_names_order = 650;

/// What stage T keeps of a tile for the canal names.
struct CanalTile {
    std::vector<text::CanalCandidate> candidates;
    /// The region's canal water level (amsterdam.py's WATER_Y, the pack's style state.water_y).
    double water_level = -1.5;
};

/// The ground's text pass.
class GroundText final : public frame::Pass {
public:
    /// Glyphs a frame draws at most (the Main board's 67 names take about 800, twice that while they cross-fade).
    static constexpr std::uint32_t max_draws = 2048;

    GroundText() = default;
    GroundText(const GroundText&) = delete;
    GroundText& operator=(const GroundText&) = delete;
    GroundText(GroundText&&) = delete;
    GroundText& operator=(GroundText&&) = delete;
    ~GroundText() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "ground_text"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    [[nodiscard]] std::uint32_t ground_slots(const frame::FrameState& state) const override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& build) const override;
    /// The twin (L1): the names the last update placed, each a `<text>` as the port writes it (its matrix as the
    /// vertex stage draws it: printed at rest; its opacity times its fade), in the ground layer at the pass's slot.
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;

    // ---- For tests and logs.

    /// The names of the last frame, as text/CanalNames placed them.
    [[nodiscard]] std::span<const text::PlacedName> names() const noexcept { return placed_; }
    /// The glyphs the last frame drew, as the shader reads them.
    [[nodiscard]] std::span<const layouts::text_slug::GlyphDraw> draws() const noexcept { return draws_; }
    /// The candidates of the cover's tiles, as the names read them.
    [[nodiscard]] std::span<const text::CanalCandidate> candidates() const noexcept { return names_.candidates(); }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{
        frame::Phase{.slot = frame::Slot::translucent, .order = canal_names_order, .id = 0}};

    /// A run shaped once, and its glyphs' places in the data (null for a glyph without contours).
    struct Shaped {
        text::ShapedRun run;
        std::vector<const text::slug::Glyph*> places;
        bool ok = false;
    };

    [[nodiscard]] const Shaped& shaped(const text::TextRun& run);
    void gather(const frame::FrameState& state);

    mutable std::atomic<std::uint32_t> index_{0xFFFF'FFFFU}; // the pass's number in the registry (from build)
    text::CanalNames names_;
    std::vector<text::PlacedName> placed_;
    std::vector<std::uint32_t> tiles_; // the cover's tiles the candidates came from (keys and handles), sorted
    double water_level_ = -1.5;
    std::unordered_map<std::string, Shaped> shaped_;
    std::vector<layouts::text_slug::GlyphDraw> draws_;
    std::vector<text::GlyphInstance> laid_;
    std::array<std::uint32_t, 2> drawn_{}; // the draws of each frame slot

    // ---- What prepare makes.
    gpu::Buffer tables_;
    gpu::Buffer draw_buffer_; // two frame slots' draws
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets_{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

/// Set 1's text globals (frame/Globals.hpp; plan-vector D13): the glyphs' curves and bands (text::slug::shared_glyphs,
/// copied to the GPU as glyphs are added, before the frame draws: record) and the board's label sprites' texels (the
/// frame's LabelSet, copied once a set). The world holds it (World::add_globals); the frame's text passes read it.
class TextGlobals final : public frame::GlobalProvider {
public:
    explicit TextGlobals(const gpu::Device& device) noexcept : device_(device) {}

    [[nodiscard]] std::string_view name() const noexcept override { return "text"; }
    [[nodiscard]] std::span<const frame::Global> bindings() const noexcept override { return owned; }
    void update(const frame::FrameState& state, frame::Globals& globals) override;
    void record(const gpu::FrameContext& frame) override;

    /// The glyphs' texels copied so far, and the sprites' (for tests).
    [[nodiscard]] std::pair<std::size_t, std::size_t> glyph_texels() const noexcept {
        return {curves_copied_, bands_copied_};
    }
    [[nodiscard]] std::size_t sprite_texels() const noexcept { return sprite_texels_; }

private:
    static constexpr std::array<frame::Global, 3> owned{frame::Global::glyph_curves, frame::Global::glyph_bands,
                                                        frame::Global::sprites};

    const gpu::Device& device_;
    gpu::Buffer curves_;
    gpu::Buffer bands_;
    std::size_t curves_copied_ = 0;
    std::size_t bands_copied_ = 0;
    // The sprites: the set copied last (its address and revision), its buffer, and the buffers of sets before it,
    // kept until the frames that read them are done.
    const labels::LabelSet* labels_ = nullptr;
    std::uint64_t labels_revision_ = 0;
    gpu::Buffer sprites_;
    std::size_t sprite_texels_ = 0;
    std::vector<std::pair<gpu::Buffer, std::uint64_t>> retired_; // buffer, the last frame that may read it
};

} // namespace atlas::passes
