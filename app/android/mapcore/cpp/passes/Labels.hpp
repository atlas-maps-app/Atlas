// The board's labels (plan-vector D13, K13; ENGINE.md design point 8): the app's sprites (the frame's LabelSet:
// MapCore.labels, each label drawn by LabelSprites as LabelPainter draws it, at its own size and at the band's top),
// placed in the core where LabelPainter would place them and kept there, growing and shrinking with the map within
// their band, fading where they meet another or leave it (labels/Placement, the map's scale the reciprocal of the
// camera's distance), and drawn in the composite subpass after the composite, so they never trail the map and are not
// hazed. A label's points are projected by the frame's camera onto the view's device pixels; the chrome's keep-out
// shapes (the camera state's) and the markers keep labels off them. At its own size each sprite stands on whole device
// pixels where LabelPainter would draw its text (labels/Sprites.hpp's sprite_at) and is copied texel for texel; grown,
// it is drawn from the label drawn at the band's top, so it stays sharp, and shrunk from its own image, each pixel the
// area it covers of the image (shaders/text/sprite.slang). Its texels are set 1's (SpriteGlobals, below); the frame
// slot's sprites to draw are the pass's own set (2). The log's line, `label place <ms> placed <n>`, comes when the
// labels placed change, at most once a second.
//
// The world's names (ENGINE.md step 5, "Names"; its step 9) are labels too: the pass picks the frame's names from the
// world's tiles (scene/WorldNames.hpp), each standing where the world pass draws its ground and nowhere behind the
// horizon, says which it has no sprite of (the frame's NameStore, which the app fills: MapCore.namesWanted and names),
// and places those it has among the boards' labels, under them by priority, a name on its way out fading where it
// stands. Their texels are the pass's own: a buffer beside the frame slot's sprites (set 2), each sprite copied into
// it once, when it is first drawn; when it is full a larger one, or one with only this frame's, takes its place.
#pragma once

#include "frame/Globals.hpp"
#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "gpu/Memory.hpp"
#include "labels/Placement.hpp"
#include "scene/WorldNames.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::labels {
struct LabelSet;   // labels/Sprites.hpp
struct NameSprite; // labels/Names.hpp
} // namespace atlas::labels

namespace atlas::passes {

/// The board's labels pass.
class Labels final : public frame::Pass {
public:
    /// Sprites a frame draws at most (with the fading places of labels that moved).
    static constexpr std::uint32_t max_draws = 512;
    /// The names' texels' buffer: its first size and its largest, texels (256 kB and 16 MB).
    static constexpr std::uint32_t first_name_texels = 1U << 16U;
    static constexpr std::uint32_t max_name_texels = 4U << 20U;
    /// The least cosine between the bent ground's normal and the ray to the eye a name is shown from: nearer the
    /// limb than about 84° of it, none.
    static constexpr double name_least_facing = 0.1;

    Labels() = default;
    Labels(const Labels&) = delete;
    Labels& operator=(const Labels&) = delete;
    Labels(Labels&&) = delete;
    Labels& operator=(Labels&&) = delete;
    ~Labels() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "labels"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void update(const frame::FrameState& state) override;
    /// Whether the last frame placed the labels mid-fade (labels::Placement::fading); a frame without them ends it.
    [[nodiscard]] bool fading() const noexcept override { return fading_; }
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    /// Set 1's sprites (SpriteGlobals, below).
    [[nodiscard]] std::vector<std::shared_ptr<frame::GlobalProvider>>
    providers(const gpu::Device& device, const frame::Resources& resources) const override;

    // ---- For tests and logs.

    /// The labels the last frame drew, as the placement left them, and their sprites' places.
    [[nodiscard]] std::span<const labels::Drawn> drawn() const noexcept { return drawn_; }
    [[nodiscard]] std::span<const layouts::text_sprite::SpriteDraw> draws() const noexcept { return draws_; }
    [[nodiscard]] const labels::Placement& placement() const noexcept { return placement_; }
    /// The names the last frame picked (those leaving too), how many of them it drew, and the names' buffer: its
    /// texels, those taken, and how many buffers there have been.
    [[nodiscard]] std::span<const scene::PickedName> names() const noexcept { return picked_; }
    [[nodiscard]] std::uint32_t names_drawn() const noexcept { return names_drawn_; }
    [[nodiscard]] std::uint32_t name_texels() const noexcept { return arena_capacity_; }
    [[nodiscard]] std::uint32_t name_texels_used() const noexcept { return arena_used_; }
    [[nodiscard]] std::uint64_t name_buffers() const noexcept { return arena_generation_; }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{
        frame::Phase{.slot = frame::Slot::labels, .order = 0, .id = 0}};

    /// The frame's names among its candidates: picked, projected, those with a sprite candidates after the boards'.
    void pick_names(const frame::FrameState& state, const view::View& view, const view::GroundFrame& frame,
                    std::size_t first);
    /// The place in the names' buffer of `sprite`'s images' texels (its own, then its large one), copied there now
    /// if they were not; nothing where the buffer has no room left.
    [[nodiscard]] const std::array<std::uint32_t, 2>* name_offsets(const labels::NameSprite& sprite);
    /// A names' buffer of `texels` in place of the one there is (which is kept while frames may read it).
    [[nodiscard]] bool fresh_names_buffer(std::uint32_t texels);

    labels::Placement placement_; // by the labels' ids, which hold across the app's sets
    scene::WorldNames world_names_;
    std::vector<scene::PickedName> picked_;
    std::vector<const labels::NameSprite*> name_sprites_; // per name candidate, its sprite
    std::vector<labels::Point> name_points_;              // per name candidate, its point
    std::array<double, 7> picked_camera_{};               // the camera the last frame saw
    std::uint32_t names_drawn_ = 0;
    float names_least_ = 0.0F; // the least size the frame's names are drawn at, a fraction of their own (0: none)
    std::array<std::uint32_t, 2> logged_names_{}; // the names picked and drawn the log last said
    double names_logged_at_ = -1e9;
    bool fading_ = false; // the last frame's placement left a fade under way
    std::vector<labels::Candidate> candidates_;
    std::vector<std::vector<labels::Point>> points_;
    std::vector<labels::Box> keep_out_;
    std::vector<labels::Drawn> drawn_;
    std::vector<layouts::text_sprite::SpriteDraw> draws_;
    std::array<std::uint32_t, 2> drawn_count_{};
    std::uint32_t logged_placed_ = 0xFFFF'FFFFU;
    double logged_at_ = -1e9;

    // ---- What prepare makes.
    const gpu::Device* device_ = nullptr;
    gpu::Buffer draw_buffer_; // two frame slots' sprites
    // The names' texels: the buffer, the place of each sprite copied into it (by its serial), how much is taken, which
    // buffer it is (1 the first) and which each frame slot's set names; the buffers let go, each with the update it
    // was let go at, kept while frames in flight may read them.
    gpu::Buffer arena_;
    std::unordered_map<std::uint64_t, std::array<std::uint32_t, 2>> arena_places_;
    std::uint32_t arena_capacity_ = 0;
    std::uint32_t arena_used_ = 0;
    bool arena_dirty_ = false; // texels copied since the last flush
    std::uint64_t arena_generation_ = 0;
    std::array<std::uint64_t, 2> set_generation_{};
    std::vector<std::pair<gpu::Buffer, std::uint64_t>> retired_;
    std::uint64_t updates_ = 0;
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets_{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

/// Set 1's sprites (frame/Globals.hpp; plan-vector D13): the texels of the frame's LabelSet, copied once a set into a
/// buffer of its own; the buffer of the set before kept for a few updates more than the frames in flight of the frame
/// builders that may read it. The world holds it (World::add_globals; the labels pass brings it).
class SpriteGlobals final : public frame::GlobalProvider {
public:
    explicit SpriteGlobals(const gpu::Device& device) noexcept : device_(device) {}

    [[nodiscard]] std::string_view name() const noexcept override { return "sprites"; }
    [[nodiscard]] std::span<const frame::Global> bindings() const noexcept override { return owned; }
    void update(const frame::FrameState& state, frame::Globals& globals) override;

    /// The texels of the set copied last (for tests).
    [[nodiscard]] std::size_t texels() const noexcept { return texels_; }

private:
    static constexpr std::array<frame::Global, 1> owned{frame::Global::sprites};
    /// Updates a let-go buffer is kept: two frame builders' frames in flight, and as many again.
    static constexpr std::uint64_t retire_after = 4ULL * gpu::Frames::in_flight;

    const gpu::Device& device_;
    const labels::LabelSet* set_ = nullptr; // the set copied last: its address and revision
    std::uint64_t revision_ = 0;
    gpu::Buffer buffer_;
    std::size_t texels_ = 0;
    std::vector<std::pair<gpu::Buffer, std::uint64_t>> retired_; // buffer, the update it was let go of
    std::uint64_t updates_ = 0;
};

} // namespace atlas::passes
