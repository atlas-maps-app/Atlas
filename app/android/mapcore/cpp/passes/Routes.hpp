// The app's routes and areas drawn by the core (ENGINE.md step 6): the frame's RouteSet (MapCore.routes,
// routes/Routes.hpp) on the street the frame's camera sees, as mapview's OverlayPainter.drawGround draws it, so it
// moves with the map in the same frame instead of being drawn by the canvas over it a frame apart. Each frame, as the
// canvas's camera measures it (MapCamera: the metres a dp spans at the target, its distance over the focal length; the
// near clip, 1.5 times 2 % of the distance within 0.5 and 20 m; the haze at 6 times the distance):
//   - the areas whose circles reach the view, each a quad over its circle's box;
//   - each route, the alternatives first as the app lists them: its segments cut to the part between the near clip
//     and the haze (RouteRibbons.draw), the pieces of a run meeting each other; its casing's pieces (1.6 dp wider
//     either side, at least 0.96 dp more than the fill's hairline), then its fill's (half its width, at least 1.25 dp),
//     each a quad over its capsule's box on the view;
//   - over every ribbon, each route's chip halfway along the part of its fill's runs on the view, 24 dp in from its
//     edges (RouteRibbons.chipPoint), on the pixel that lands on.
// Drawn in the composite subpass after the composite and before the labels (shaders/overlay/route.slang).
//
// The pass's own set (2) holds a frame slot's draws and the slot's copy of the set's chips, copied when the slot first
// draws a new set (its previous frame has finished with the old one).
#pragma once

#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "gpu/Memory.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::routes {
class RouteSet; // routes/Routes.hpp
} // namespace atlas::routes

namespace atlas::passes {

/// The routes pass.
class Routes final : public frame::Pass {
public:
    /// What a frame draws at most: the areas, the routes' pieces (twice: casing and fill) and their chips.
    static constexpr std::uint32_t max_draws = 16384;

    /// The kinds of draw (route.slang's ROUTE_PIECE, ROUTE_AREA and ROUTE_CHIP) and a piece's joins.
    static constexpr std::uint32_t piece = 0;
    static constexpr std::uint32_t area = 1;
    static constexpr std::uint32_t chip = 2;
    static constexpr std::uint32_t joins_prev = 1;
    static constexpr std::uint32_t joins_next = 2;

    Routes() = default;
    Routes(const Routes&) = delete;
    Routes& operator=(const Routes&) = delete;
    Routes(Routes&&) = delete;
    Routes& operator=(Routes&&) = delete;
    ~Routes() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "routes"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    // ---- For tests.

    /// What the last frame drew, in order.
    [[nodiscard]] std::span<const layouts::overlay_route::RouteDraw> draws() const noexcept { return draws_; }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{
        frame::Phase{.slot = frame::Slot::labels, .order = -1, .id = 0}};

    /// A piece of a route's segment in front of the eye and short of the haze: its ends (frame metres, on the street)
    /// and their distances along the route, whether it meets the piece before it, and whether its run ends with it
    /// (cut there, or the route's last segment: RouteRibbons.draw keeps that end however near the point before it).
    struct Piece {
        double ax = 0.0;
        double az = 0.0;
        double sa = 0.0;
        double bx = 0.0;
        double bz = 0.0;
        double sb = 0.0;
        bool joined = false;
        bool ends = false;
    };
    std::vector<Piece> pieces_;
    std::vector<std::array<float, 4>> quads_; // per piece, its quad on the view and whether it reaches the view
    std::vector<std::uint8_t> shown_;
    std::vector<std::array<float, 2>> run_; // the chip's run points on the view, and where a run begins
    std::vector<std::uint8_t> run_breaks_;
    std::vector<layouts::overlay_route::RouteDraw> draws_;
    std::vector<layouts::overlay_route::RouteDraw> chips_;

    /// A frame slot's own: its set, its copy of the set's chips and which set they are, and its draws' count.
    struct Slot {
        vk::raii::DescriptorSet set{nullptr};
        gpu::Buffer texels;
        const routes::RouteSet* of = nullptr;
        std::uint64_t revision = 0;
        std::uint32_t count = 0;
    };
    std::array<Slot, 2> slots_{};

    // ---- What prepare makes.
    const gpu::Device* device_ = nullptr;
    gpu::Buffer draw_buffer_; // two frame slots' draws
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

} // namespace atlas::passes
