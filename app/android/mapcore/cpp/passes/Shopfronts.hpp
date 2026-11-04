// The shopfronts, their terraces and the open places' glows (ENGINE.md step 4, the facades and the night;
// render/amsterdam.py's facade_details and shopfront, and the night's `open` glows at :854): with weather, each mapped
// shop, café or hotel that opens onto a street wall near the eye gets its front as the port draws it, its terrace
// where the user's rule sets it out, and at night every open place its glow.
//   - Stage T (build, on a worker): geometry/Shopfronts.hpp's shopfronts and open places of the tile, their items (a
//     shopfront's board, its blade and its tables; a place's glow); the pass keeps them, the tile's water and its
//     distinct opening hours (PassTile::cpu).
//   - Stage F (update): shop_shapes, facade_details' and shopfront's decisions in double at the frame's camera and
//     moment: the cover's shopfronts in S['nodes'] order, each where the radius cut keeps its node, its node lies 2 to
//     230 m deep and its wall faces the eye; open_at of its opening hours at the moment (style/OpeningHours.hpp, each
//     tile's hours once a minute); its board (the frame, the glass, the mullions, the awning with its stripes, the sign
//     with its text, the night's spill), its blade (its bracket, the blade, its text along or down it) and, the user's
//     rule (4 November), its terrace only while it is open and by day where its hours say nothing: its tables, each
//     its leg, two chairs, its top and by day a parasol, none on the water. Every face as face() keeps it (Camera.poly
//     within 300 px, from 0.12 px²; the stripes within 200 px, unchecked), every line as the port strokes it. And at
//     night each open place's glow: a circle max(3, 5 f / z) px about its node 1.5 m up, from 2 m deep.
//   - Records, the CPU's (shaders/item/items.slangh, drawn through the items' stages): the faces (the glass by day its
//     two stops down its box, ITEM_VERTICAL; a polygon of more than five corners as fan pieces of five), their
//     outlines as round-capped segments on their planes, the lines butt-capped; the stripes and the spill translucent
//     faces (items_tints_fs); the texts' glyphs, Slug's (wall_text_vs and wall_text_fs) in `Geist, Helvetica,
//     sans-serif` 800 at the size the SVG prints, screen-upright at their board's nearest depth (the blade's turned by
//     90° down it); the glows, radial records (items_radials_fs). At rest every corner, end, anchor and size is as the
//     SVG prints it.
//   - Record: the faces' fans and the lines in the objects slot (the fronts', then the terraces'); the texts in the
//     translucent slot with the doors' numbers, before every object's translucent part; the tints after the items'
//     translucent parts; the glows after every object, the depth test off. None in the owner mode.
//   - Depth: every corner and end at its own reciprocal depth; a front's parts lead 21 to 24 steps of 2^-16 over their
//     wall (its windows take 20 at most), so a front covers its wall's windows, plinth and glazing. Under the painter
//     switch every part at its item's slots (a board ten, a blade four, a table twenty-two).
//   - The twin (emit): each element as the port prints it, at its item's slots (twin/shop/ShopTwin.hpp); the glows
//     after every object, outside any item.
#pragma once

#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "geometry/Shopfronts.hpp"
#include "gpu/Memory.hpp"
#include "passes/OpenHours.hpp"
#include "text/GroundText.hpp"
#include "text/Shaper.hpp"
#include "text/SlugBands.hpp"
#include "tiles/Style.hpp"
#include "view/Camera.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

namespace atlas::shaders {
struct FrameCamera;
struct ItemRecord;
} // namespace atlas::shaders

namespace atlas::passes {

/// A tile's shopfronts and open places as the pass keeps them (PassTile::cpu).
struct ShopTile {
    std::uint64_t serial = 0; ///< this build's own, for the open hours' cache
    std::vector<geometry::Shopfront> shops;
    std::vector<std::uint32_t> firsts; ///< each shopfront's board among the pass's items of the tile
    std::vector<geometry::OpenPlace> places;
    std::uint32_t places_first = 0; ///< the first glow among them
    std::vector<std::string> hours;
    std::vector<geometry::WaterPiece> water;
    /// The boxes of the shopfronts' nodes and of the places' (frame metres: x0, z0, x1, z1; empty where x0 > x1).
    std::array<double, 4> shops_box{1e300, 1e300, -1e300, -1e300};
    std::array<double, 4> places_box{1e300, 1e300, -1e300, -1e300};
};

/// A polygon as Camera.poly keeps it: its corners (cut at the near plane) in CSS px as the port computes them
/// (unsnapped) and their eye depths (k.z); up to thirteen (a table's top cut), none where it is dropped.
struct ShopPoly {
    std::array<view::Vec2, 13> points{};
    std::array<double, 13> depths{};
    std::uint32_t count = 0;
};

/// A line as the port strokes it where both ends project: its ends (CSS px, unsnapped), their eye depths and its
/// width as printed ('%.2f').
struct ShopLine {
    bool drawn = false;
    view::Vec2 a;
    view::Vec2 b;
    double za = 0;
    double zb = 0;
    double width = 0;
};

/// A plane's reciprocal depth over CSS px (affine there: passes/Doors.cpp's Plane), r = r0 + gx x + gy y.
struct ShopPlane {
    double r0 = 0;
    double gx = 0;
    double gy = 0;
};

/// A sign's text as the port prints it: the text, its anchor (x, y) and size, its letter spacing, its colour, and on
/// a blade seen edge-on its turn, rotate(90) about (cx, cy); the reciprocal depth it draws at (its board's nearest).
struct ShopText {
    bool drawn = false;
    std::string text;
    double x = 0;
    double y = 0;
    double size = 0;
    double spacing = 0;
    std::uint32_t fill = 0; ///< 0xRRGGBB
    bool turned = false;
    double cx = 0;
    double cy = 0;
    double r = 0;
};

/// A shopfront's board, as shopfront draws it: its faces, its mullions, its awning's stripes, its sign's text and the
/// night's spill, each with its colour (0xRRGGBB), the awning's and the sign's planes (their outlines keep to them).
struct BoardShape {
    std::uint32_t rank = 0;
    ShopPoly frame;
    ShopPoly glass;
    std::array<ShopLine, 2> mullions;
    ShopPoly awning;
    std::vector<ShopPoly> stripes;
    ShopPoly sign;
    ShopPoly spill;
    ShopPlane awning_plane;
    ShopPlane sign_plane;
    std::uint32_t frame_colour = 0;
    std::uint32_t glass_colour = 0; ///< at night (by day the glass's gradient, `glass_lit`'s where it is open)
    bool glass_lit = false;
    std::uint32_t awning_colour = 0;
    std::uint32_t awning_outline = 0;
    std::uint32_t stripe_colour = 0;
    std::uint32_t sign_colour = 0;
    ShopText label;
};

/// Its blade: the bracket, the blade (in its board's sign's colour) on its plane, its text.
struct BladeShape {
    std::uint32_t rank = 0;
    ShopLine bracket;
    ShopPoly blade;
    ShopPlane plane;
    std::uint32_t colour = 0;
    ShopText text;
};

/// A chair of a table: its leg, its seat and its back, each its colour.
struct ChairShape {
    ShopLine leg;
    ShopPoly seat;
    ShopPoly back;
    std::uint32_t leg_colour = 0;
    std::uint32_t seat_colour = 0;
    std::uint32_t back_colour = 0;
};

/// A table of a terrace: its leg, its chairs, its top on its plane, and by day its parasol (its pole and twelve
/// panels, each lit or in the shade).
struct TableShape {
    std::uint32_t rank = 0;
    ShopLine leg;
    std::uint32_t wood = 0;
    std::array<ChairShape, 2> chairs;
    ShopPoly top;
    ShopPlane top_plane;
    std::uint32_t top_colour = 0;
    ShopLine pole;
    std::array<ShopPoly, 12> panels;
    std::array<std::uint32_t, 12> panel_colours{};
    bool parasol = false;
};

/// One shopfront the frame draws, as facade_details and shopfront decide it at the frame's camera and moment, in
/// double: its node, whether it is open, its board, its blade and its tables where each is drawn (its item's rank
/// within the frame), and while a tile fades the standing fade of the cover's entry it is drawn for (0 at rest).
struct ShopShape {
    std::uint32_t node = 0;
    std::int64_t id = 0;
    bool open = false;
    std::optional<BoardShape> board;
    std::optional<BladeShape> blade;
    std::vector<TableShape> tables;
    std::uint32_t fade = 0;
};

/// An open place's glow at night: its node, its circle (CSS px, unsnapped) and its standing fade.
struct GlowShape {
    std::uint32_t node = 0;
    std::int64_t id = 0;
    double x = 0;
    double y = 0;
    double r = 0;
    std::uint32_t fade = 0;
};

/// What the frame draws of the shops: the shopfronts and the glows, each in S['nodes'] order.
struct ShopFrame {
    std::vector<ShopShape> shops;
    std::vector<GlowShape> glows;
};

/// The pass's item kinds.
struct ShopKinds {
    std::uint16_t board = 0;
    std::uint16_t blade = 0;
    std::uint16_t table = 0;
    std::uint16_t glow = 0;
};

/// What the frame draws of the shops (shopfronts' order, then the glows'): those of the cover's tiles (Cover::upright)
/// that pass `pass` keeps, of kinds `kinds`, at the frame's camera and moment, their colours from `style`. A shopfront
/// whose board, blade or table the ranks do not draw (beyond the frame) draws the rest all the same.
[[nodiscard]] ShopFrame shop_shapes(const frame::FrameState& state, std::uint32_t pass, const ShopKinds& kinds,
                                    const tiles::ShopfrontsStyle& style, OpenHours& hours);

/// The shopfronts' pass.
class Shopfronts final : public frame::Pass {
public:
    /// The most glyphs a frame draws.
    static constexpr std::uint32_t max_glyphs = 8192;

    Shopfronts();
    Shopfronts(const Shopfronts&) = delete;
    Shopfronts& operator=(const Shopfronts&) = delete;
    Shopfronts(Shopfronts&&) = delete;
    Shopfronts& operator=(Shopfronts&&) = delete;
    ~Shopfronts() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "shopfronts"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void kinds(frame::Selectors& selectors) override;
    void select(const frame::FrameState& state, frame::Selectors& selectors) override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& build) const override;
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;

    /// The phases: the fronts and terraces (objects); their texts (translucent, with the doors' numbers); the stripes
    /// and spill (translucent, after the items' translucent parts); the glows (translucent, after every object).
    static constexpr std::uint32_t draw_phase = 0;
    static constexpr std::uint32_t texts_phase = 1;
    static constexpr std::uint32_t tints_phase = 2;
    static constexpr std::uint32_t glows_phase = 3;
    static constexpr std::int32_t texts_order = 999;
    static constexpr std::int32_t tints_order = 1013;
    static constexpr std::int32_t glows_order = 1100;

    /// What the last frame drew: its shopfronts, tables and glows, and its records and glyphs (the faces the fronts'
    /// and the terraces' opaque ones, then the tints).
    struct Counts {
        std::uint32_t shops = 0;
        std::uint32_t tables = 0;
        std::uint32_t glows = 0;
        std::uint32_t front_faces = 0;
        std::uint32_t terrace_faces = 0;
        std::uint32_t tints = 0;
        std::uint32_t front_parts = 0;
        std::uint32_t terrace_parts = 0;
        std::uint32_t glyphs = 0;
    };
    [[nodiscard]] Counts counts() const noexcept { return counts_; }
    [[nodiscard]] const ShopKinds& shop_kinds() const noexcept { return kinds_; }
    /// The last frame's records and glyphs, as the GPU reads them (the tests').
    [[nodiscard]] std::span<const shaders::ItemRecord> faces() const noexcept;
    [[nodiscard]] std::span<const shaders::ItemRecord> parts() const noexcept;
    [[nodiscard]] std::span<const shaders::ItemRecord> glows() const noexcept;
    [[nodiscard]] std::span<const layouts::text_slug::GlyphDraw> glyphs() const noexcept { return glyphs_; }

private:
    static constexpr std::array<frame::Phase, 4> phase_list{{
        {.slot = frame::Slot::objects, .order = 0, .id = draw_phase},
        {.slot = frame::Slot::translucent, .order = texts_order, .id = texts_phase},
        {.slot = frame::Slot::translucent, .order = tints_order, .id = tints_phase},
        {.slot = frame::Slot::translucent, .order = glows_order, .id = glows_phase},
    }};

    /// A run shaped once, and its glyphs' places in the shared data (null for a glyph without contours).
    struct Shaped {
        text::ShapedRun run;
        std::vector<const text::slug::Glyph*> places;
        bool ok = false;
    };
    [[nodiscard]] const Shaped& shaped(const text::TextRun& run);
    void add_text(const text::TextRun& run, std::uint32_t slot, float depth, std::uint32_t fade);
    void record_draw(const frame::Recording& recording);
    void record_texts(const frame::Recording& recording);
    void record_tints(const frame::Recording& recording);
    void record_glows(const frame::Recording& recording);

    ShopKinds kinds_;
    Counts counts_;
    mutable OpenHours hours_; // the render thread's, update's and emit's

    // made in prepare
    const gpu::Device* device_ = nullptr;
    vk::raii::ShaderModule items_module_{nullptr};
    vk::raii::ShaderModule text_module_{nullptr};
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets_{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    std::array<gpu::Buffer, 2> faces_buffer_;
    std::array<gpu::Buffer, 2> parts_buffer_;
    std::array<gpu::Buffer, 2> glows_buffer_;
    std::array<gpu::Buffer, 2> glyphs_buffer_;
    /// While a tile fades, each face's standing fade, then each part's, then each glow's (bound always)
    std::array<gpu::Buffer, 2> fades_buffer_;
    gpu::Buffer tables_;
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::Pipeline fills_{nullptr};
    vk::raii::Pipeline fills_fading_{nullptr}; ///< the same while a tile fades (items_fills_fading_fs)
    vk::raii::Pipeline parts_pipeline_{nullptr};
    vk::raii::Pipeline tints_{nullptr};
    vk::raii::Pipeline glows_pipeline_{nullptr};
    vk::raii::Pipeline texts_{nullptr};
    vk::raii::Pipeline texts_fading_{nullptr}; ///< the same while a tile fades (wall_text_fading_fs)

    // the render thread's, per frame
    std::vector<shaders::ItemRecord> faces_;
    std::vector<shaders::ItemRecord> parts_;
    std::vector<shaders::ItemRecord> glows_;
    std::vector<layouts::text_slug::GlyphDraw> glyphs_;
    std::vector<std::uint32_t> fade_words_;
    bool dithers_ = false; ///< what stands up draws dithered this frame (scene::Cover::dithers)
    std::vector<text::GlyphInstance> laid_;
    std::unordered_map<std::string, Shaped> shaped_;
    std::uint32_t slot_ = 0;
};

} // namespace atlas::passes
