// The doors and their house numbers (ENGINE.md step 4, the facades; render/amsterdam.py's facade_details, its doors):
// with weather, where facades are drawn (the living map's tiles alone carry doors: regions set no_facades), each
// address that opens onto a street wall near the eye gets a door with its outline and a stoop, and nearer a plate with
// its numbers, as the port draws them, in the objects slot at true depth.
//   - Stage T (build, on a worker): geometry/Doors.hpp's doors of the tile, an item each, of the kind "door" (its five
//     painter slots); the pass keeps the doors (PassTile::cpu).
//   - Stage F (update): door_shapes, facade_details' decisions in double at the frame's camera: the cover's doors in
//     S['nodes'] order, each where the renders' radius cut keeps its node, its node lies 2 to 75 m deep and its wall
//     faces the eye; one door a spot on a wall (its building, and its point there rounded), drawn from its spot's
//     first address, the later ones adding their numbers. Then the door's records, the CPU's: its face and its
//     stoop's as face() keeps them (Camera.poly within 300 px, from 0.12 px² on) and, where its node lies nearer than
//     45 m and it has a number, its plate (a face stroked in its own colour about the rect's inner box: SVG's rounded
//     rect), as the region items' records (shaders/item/items.slangh, drawn through the items' own stages); its
//     outline, a round-capped segment an edge of its face; its number's glyphs, Slug's (shaders/text/slug.slang's
//     wall_text_vs and wall_text_fs), shaped as Android shapes `Georgia, serif` (the core's Gelasio) at the size the
//     SVG prints. At rest every corner, the plate's rect and the number's anchor and size are as the SVG prints them.
//   - Record: the faces' fans, their edges and the outlines in the objects slot, the nearest door first; the numbers'
//     glyphs in the translucent slot before every object's translucent part (so what stands nearer still covers them,
//     and what lies behind the plate fails its depth), depth test on and writes off. None in the owner mode.
//   - Depth: the door's face and outline on their plane 0.06 m off the wall, the stoop on its own 0.6 m off, each
//   corner
//     at its own reciprocal depth; the plate and its number screen-upright at one depth, the nearest the door's plane
//     has under the plate, so its own wall never cuts it. They lead their plane by 21 to 24 steps of 2^-16 (a wall's
//     windows by at most 20), so a door always covers the windows and the plinth of its own wall. Under the painter
//     switch: the door, its outline, the stoop, the plate and its number at the item's five slots.
//   - The twin (emit): each door's path (filled in its colour, stroked .5 in the outline's), its stoop's, its plate's
//     rect and its number's text, as the port prints them, at the item's slots (twin/door/DoorTwin.hpp).
// At eye level the renders wrap each facade item in the air's haze (`<g opacity>`): the map's camera never reaches eye
// level (ENGINE.md D9), so the doors leave it out.
#pragma once

#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "geometry/Doors.hpp"
#include "gpu/Memory.hpp"
#include "text/GroundText.hpp"
#include "text/Shaper.hpp"
#include "text/SlugBands.hpp"
#include "tiles/Style.hpp"
#include "view/Camera.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

namespace atlas::scene {
struct ResidentTile;
} // namespace atlas::scene

namespace atlas::shaders {
struct FrameCamera;
struct ItemRecord;
} // namespace atlas::shaders

namespace atlas::passes {

/// A tile's doors as the pass keeps them (PassTile::cpu).
struct DoorTile {
    std::vector<geometry::Door> doors;
    /// The box of the doors' nodes (frame metres: x0, z0, x1, z1): a frame none of whose nodes can lie 2 to 75 m deep
    /// draws none of them.
    std::array<double, 4> box{};
};

/// A face as facade_details' face() keeps it: Camera.poly's corners (cut at the near plane, the face dropped where it
/// lies wholly 300 px or more beyond the frame) in CSS px as the port computes them (unsnapped) and their eye depths
/// (k.z), where its area is 0.12 px² or more; no corners otherwise.
struct DoorFace {
    std::array<view::Vec2, 5> points{};
    std::array<double, 5> depths{};
    std::uint32_t count = 0;
};

/// One door the frame draws, as facade_details decides it at the frame's camera, in double: its item's rank entry and
/// depth, its first address's node, its colour, its face and its stoop's, its numbers, and where it has one its plate
/// (the rect's x, y, width, height and rx, the number's anchor and size, unsnapped: the SVG prints them) and the
/// plate's reciprocal depth (the nearest the door's plane, 0.06 m off the wall, has under the plate's rect); and the
/// plane's reciprocal depth's gradient over the screen, which keeps the door's outline on it across its width.
struct DoorShape {
    std::uint32_t rank = 0;
    double depth = 0;
    std::int64_t id = 0;
    std::uint32_t colour = 0; ///< 0xRRGGBB
    DoorFace door;
    DoorFace stoop;
    std::string number; ///< '%d' of its one number, '%d–%d' of its least and most, or empty
    bool plate = false;
    double x = 0;
    double y = 0;
    double width = 0;
    double height = 0;
    double rx = 0;
    double text_x = 0;
    double text_y = 0;
    double size = 0;
    double plate_r = 0;
    double plane_gx = 0; ///< the reciprocal depth's gradient over CSS px on the door's plane (affine there), x and y
    double plane_gy = 0;
    std::int64_t building = 0; ///< its building, and its wall's direction: the doors of one wall lie on one plane
    double ux = 0;
    double uz = 0;
    /// Its place, in painter's order, among the doors of its wall it overlaps (the port draws the later over the
    /// earlier): its parts lead four steps more for each one before it, and their plates share one depth.
    std::uint32_t order = 0;
};

/// The doors the frame draws (door_shapes' order: each spot's first address's in S['nodes']), as facade_details decides
/// them: the doors of the cover's tiles that pass `pass` keeps (its PassTile in each), whose items are of kind `kind`,
/// at the frame's camera and moment, the door's colours from `style`. Doors whose items the ranks do not draw (beyond
/// the frame) take their spot all the same and are left out.
[[nodiscard]] std::vector<DoorShape> door_shapes(const frame::FrameState& state, std::uint32_t pass, std::uint16_t kind,
                                                 const tiles::DoorsStyle& style);

/// The records the GPU draws for door `d` at camera `camera` (its flags: at rest, the painter switch) with the
/// moment's colours: its face and its stoop's, its plate (a face stroked about the rect's inner box) into `faces`; its
/// outline, a segment an edge of its face, into `parts`; the night's colours where `night`.
void door_records(const DoorShape& d, const shaders::FrameCamera& camera, const tiles::DoorsStyle& style, bool night,
                  std::vector<shaders::ItemRecord>& faces, std::vector<shaders::ItemRecord>& parts);

/// A door's number as the SVG prints it at rest (its anchor and size at '%.1f'), its colour by day or at night: the
/// text run its glyphs are laid out from.
[[nodiscard]] text::TextRun door_number_run(const DoorShape& d, const tiles::DoorsStyle& style, bool night, bool rest);

/// The depth a door's number's glyphs draw at (slug.slang's wall_text_vs): its plate's with the number's lead at true
/// depth, its slot's under the painter switch.
[[nodiscard]] float door_number_depth(const DoorShape& d, const shaders::FrameCamera& camera);

/// The doors' and their numbers' pass.
class Doors final : public frame::Pass {
public:
    /// The most glyphs a frame draws.
    static constexpr std::uint32_t max_glyphs = 4096;

    Doors();
    Doors(const Doors&) = delete;
    Doors& operator=(const Doors&) = delete;
    Doors(Doors&&) = delete;
    Doors& operator=(Doors&&) = delete;
    ~Doors() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "doors"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void kinds(frame::Selectors& selectors) override;
    void select(const frame::FrameState& state, frame::Selectors& selectors) override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& build) const override;
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;

    /// The phases: the doors (objects), and their numbers (translucent, before every object's translucent part).
    static constexpr std::uint32_t draw_phase = 0;
    static constexpr std::uint32_t numbers_phase = 1;
    static constexpr std::int32_t numbers_order = 999;

    /// What the last frame drew: its doors, their faces and outlines' records, and the numbers' glyphs.
    struct Counts {
        std::uint32_t doors = 0;
        std::uint32_t faces = 0;
        std::uint32_t parts = 0;
        std::uint32_t glyphs = 0;
    };
    [[nodiscard]] Counts counts() const noexcept { return counts_; }
    [[nodiscard]] std::uint16_t kind() const noexcept { return kind_; }
    /// The last frame's records and glyphs, as the GPU reads them (the tests').
    [[nodiscard]] std::span<const shaders::ItemRecord> faces() const noexcept;
    [[nodiscard]] std::span<const shaders::ItemRecord> parts() const noexcept;
    [[nodiscard]] std::span<const layouts::text_slug::GlyphDraw> glyphs() const noexcept { return glyphs_; }

private:
    static constexpr std::array<frame::Phase, 2> phase_list{{
        {.slot = frame::Slot::objects, .order = 0, .id = draw_phase},
        {.slot = frame::Slot::translucent, .order = numbers_order, .id = numbers_phase},
    }};

    /// A run shaped once, and its glyphs' places in the shared data (null for a glyph without contours).
    struct Shaped {
        text::ShapedRun run;
        std::vector<const text::slug::Glyph*> places;
        bool ok = false;
    };
    [[nodiscard]] const Shaped& shaped(const text::TextRun& run);
    void record_draw(const frame::Recording& recording);
    void record_numbers(const frame::Recording& recording);

    std::uint16_t kind_ = 0;
    Counts counts_;

    // made in prepare
    const gpu::Device* device_ = nullptr;
    vk::raii::ShaderModule items_module_{nullptr};
    vk::raii::ShaderModule text_module_{nullptr};
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets_{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    std::array<gpu::Buffer, 2> faces_buffer_;
    std::array<gpu::Buffer, 2> parts_buffer_;
    std::array<gpu::Buffer, 2> glyphs_buffer_;
    gpu::Buffer tables_;
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::Pipeline fills_{nullptr};
    vk::raii::Pipeline faces_pipeline_{nullptr};
    vk::raii::Pipeline parts_pipeline_{nullptr};
    vk::raii::Pipeline numbers_{nullptr};

    // the render thread's, per frame
    std::vector<shaders::ItemRecord> faces_;
    std::vector<shaders::ItemRecord> parts_;
    std::vector<layouts::text_slug::GlyphDraw> glyphs_;
    std::vector<text::GlyphInstance> laid_;
    std::unordered_map<std::string, Shaped> shaped_;
    std::uint32_t slot_ = 0;
};

} // namespace atlas::passes
