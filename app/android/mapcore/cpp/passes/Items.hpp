// A region's items (ENGINE.md step 4, "Trees and things"; render/regions.py's draw_items): one pass draws every tower
// with a height (the app's map one standing on a roof as its roof mast), wind turbine, crane, mast, gantry, goods
// conveyor and power line of a region as the port does, in the objects slot at true depth (shaders/item/: a stage a
// kind writes their records, towers_cs to woods_cs and roof_masts_cs; items_faces_vs and _fs draw the faces,
// items_parts_vs and _fs the opaque parts, items_sides_vs and _fs the translucent ones, far to near).
//   - Stage T (build, on a worker): geometry/Items.hpp's items of the tile, ITEM_VECTORS uint4 each in the points heap;
//     an item each, of the kind "region item" (its kind's painter slots); the pass keeps each item as stage T made it.
//   - Stage F (update): the moment's tones (item_tones), and a job for each item the ranks draw, the nearest first,
//   with
//     what its item's depth decides in double (item_choice: a segment's radius cut at either end in engine mode, the
//     port's least sizes, the widths as printed, a power line's fade), its boxes' colours by their faces' normals and
//     its parts in draw_items' order (item_extra).
//   - Before the main pass each kind's stage writes its jobs' records: the jobs are put together kind by kind (the
//     roof masts' last), each job keeping its records' places, and each kind with jobs this frame is dispatched over
//     them alone (the audit of 17 November, change 7: one stage over every kind had grown to 418,600 bytes of SPIR-V,
//     twice shaders/spirv.budget's line; each kind's is within it). The objects phase draws the faces and the opaque
//     parts, the translucent phase the rest, far to near. None in the owner mode.
//   - The twin (emit): every item's elements as the port's SVG has them (twin/item/ItemTwin.hpp).
#pragma once

#include "frame/FrameBlock.hpp"
#include "frame/Pass.hpp"
#include "geometry/Items.hpp"
#include "gpu/Memory.hpp"
#include "style/Palette.hpp"
#include "style/Theme.hpp"
#include "tiles/Style.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <utility>
#include <vector>

namespace atlas::scene {
struct ResidentTile;
} // namespace atlas::scene

namespace atlas::view {
class Camera;
} // namespace atlas::view

namespace atlas::shaders {
struct ItemRecord;
} // namespace atlas::shaders

namespace atlas::passes {

/// A tile's region items as the pass keeps them (PassTile::cpu): each as stage T made it, in double.
struct ItemTile {
    std::vector<geometry::RegionItem> items;
    std::vector<geometry::CoasterTrackSpan> tracks; ///< its coaster tracks' pieces among the items
};

/// The points heap's words of a tile's items (items.slangh's ITEM_VECTORS each: its foot tile-local and its kind and
/// flags; a segment's second end, its height and a kind's own value; a power line's conductors' heights).
[[nodiscard]] std::vector<hlsl::uint4> item_words(const geometry::TileRegionItems& items, double origin_x,
                                                  double origin_y, double origin_z);

/// What the render thread decides for a drawn item from its item's depth `depth` and the camera's focal length, in
/// double as the port compares: whether it is drawn (a crane's, a mast's and a segment's least sizes, a power line's
/// fade above 0), its job's flags (items.slangh's ITEM_JOB_) and values: a turbine's s, its light's offset, its hub's
/// and its light's radii (as printed); a crane's stays' width (as printed); a mast's glow's and its core's radii (as
/// printed); a power line's tower's width (as printed) and its fade (as printed, where under 1).
struct ItemChoice {
    bool drawn = false;
    std::uint32_t flags = 0;
    float a = 0;
    float b = 0;
    float c = 0;
    float d = 0;
    float e = 0; ///< a coaster's top's width (as printed)
};
[[nodiscard]] ItemChoice item_choice(const geometry::RegionItem& item, double depth, double focal, bool night);

/// The moment's tones (items.slangh's table, ITEM_TONES words): the port's colours by day or at night, the towers'
/// gradients' stops mixed toward the moment's shadow, the turbines' blades' angles and the hour's wind. The user's
/// `palette` (null: today's) colours the turbines, the pylons and their conductors, and the woods' greens.
inline constexpr std::size_t item_tone_count = 69;
using ItemTones = std::array<std::uint32_t, item_tone_count>;
[[nodiscard]] ItemTones item_tones(const style::ThemeBlock& theme, const shaders::Theme& th,
                                   const tiles::ItemsStyle& style, const style::PaletteBlock* palette = nullptr);

/// Whether the towers' gradients' middle stop lies at .65: the sun on the camera's right (L[0] · sx + L[2] · sz > 0).
[[nodiscard]] bool item_high(const style::ThemeBlock& theme, const view::Camera& camera) noexcept;

/// A job's own words (items.slangh's ITEM_EXTRA_COLOURS, then its parts in draw_items' order): a crane's, a mast's, a
/// gantry's or a conveyor's boxes' colours by their faces' normals (light(th, col, n3, not night and vdot(n3, L) > 0),
/// the steel first, a crane's machinery, a mast's floodlights or a conveyor's gallery second), and a crane's, a
/// gantry's or a conveyor's parts sorted by their middles' depths at H / 2, far first (a stable sort), in double; a
/// roof mast's panels' faces' colours, the towers' shaft lit so. `a` and `b` are a crane's foot or a segment's ends in
/// the drawn frame.
void item_extra(const geometry::RegionItem& item, const view::Camera& camera, const style::ThemeBlock& theme,
                const shaders::Theme& th, const tiles::ItemsStyle& style, double ax, double az, double bx, double bz,
                std::vector<std::uint32_t>& out);

/// The kinds with a decide stage of their own but the roof masts (items.slangh's ITEM_TOWER to ITEM_WOOD, in that
/// order; ITEM_ROOF_MAST's stage comes after them).
inline constexpr std::size_t item_kind_stages = 9;

/// The region items' pass.
class Items final : public frame::Pass {
public:
    Items() = default;

    [[nodiscard]] std::string_view name() const noexcept override { return "items"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void kinds(frame::Selectors& selectors) override;
    void select(const frame::FrameState& state, frame::Selectors& selectors) override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    [[nodiscard]] gpu::Result<void> build(scene::TileBuild& build) const override;
    /// Its stage T serves a region's items (style::camera_part::region_items).
    [[nodiscard]] std::uint64_t camera_parts() const noexcept override;
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;
    /// emit's elements, keeping the records the GPU writes for them in its lists' order: the faces, the opaque parts
    /// and the translucent ones (the jobs' blocks far to near, each in its own order) (the tests').
    void emit_records(const frame::FrameState& state, twin::Stream& out, std::vector<shaders::ItemRecord>* faces,
                      std::vector<shaders::ItemRecord>* parts, std::vector<shaders::ItemRecord>* sides) const;

    /// The phases: the faces and the opaque parts (objects), decided before the main pass (offscreen); the translucent
    /// ones (after the furniture's).
    static constexpr std::uint32_t draw_phase = 0;
    static constexpr std::uint32_t decide_phase = 1;
    static constexpr std::uint32_t sides_phase = 2;
    static constexpr std::int32_t sides_order = 1012;

    /// What the last frame drew: its jobs (the items drawn; the roof masts', the last, their own stage's), their faces,
    /// opaque parts and translucent ones, and the jobs of each other kind (in the kinds' order: each kind with any is
    /// a dispatch of its stage).
    struct Counts {
        std::uint32_t jobs = 0;
        std::uint32_t masts = 0;
        std::uint32_t faces = 0;
        std::uint32_t parts = 0;
        std::uint32_t sides = 0;
        std::array<std::uint32_t, item_kind_stages> kinds{};
    };
    [[nodiscard]] Counts counts() const noexcept { return counts_; }

#if defined(ATLAS_TESTS)
    /// The tests' (items_vk_test; only where the tests are built): `whole`, a module whose items_cs decides every
    /// kind's jobs in one stage (tests/item/items_whole.slang, the stage before it was split by kind), made at prepare
    /// beside the kinds' stages, which a frame runs over every job but the roof masts' in their place while use_whole
    /// is on; and `readable`, the records' lists in memory the CPU reads (records()). Set before prepare.
    struct Testing {
        std::vector<std::uint32_t> whole;
        bool readable = false;
    };
    void set_testing(Testing testing) { testing_ = std::move(testing); }
    void use_whole(bool on) noexcept { use_whole_ = on; }
    /// While `sentinel` is set (Testing::readable), each frame fills its slot's records' lists with that byte before
    /// its stages write them, so records() shows a record no stage wrote as the sentinel, not as what the slot's frame
    /// before left there.
    void fill_records(std::optional<std::byte> sentinel) noexcept { sentinel_ = sentinel; }
    /// The tests': the records the last frame's stages wrote, the faces', the opaque parts' and the translucent ones'
    /// as their lists hold them (Testing::readable; read once that frame has finished).
    struct Records {
        std::vector<std::byte> faces;
        std::vector<std::byte> parts;
        std::vector<std::byte> sides;
        bool operator==(const Records&) const = default;
    };
    [[nodiscard]] Records records() const;
#endif
    [[nodiscard]] std::uint16_t kind() const noexcept { return kind_; }

private:
    static constexpr std::array<frame::Phase, 3> phase_list{{
        {.slot = frame::Slot::objects, .order = 0, .id = draw_phase},
        {.slot = frame::Slot::offscreen, .order = 0, .id = decide_phase},
        {.slot = frame::Slot::translucent, .order = sides_order, .id = sides_phase},
    }};

    /// A drawn item: its tile's place in the cover, its index in the tile, its rank entry and depth, its choice and
    /// its segment's ends in the drawn frame.
    struct Drawn {
        std::uint32_t cover = 0;
        std::uint32_t item = 0;
        std::uint32_t rank = 0;
        double depth = 0;
        ItemChoice choice;
        double ax = 0;
        double az = 0;
        double bx = 0;
        double bz = 0;
        /// A wooden coaster's piece with braces: the tile's item whose post they lead back to.
        std::uint32_t prev_post = 0;
    };
    /// The items the ranks draw this frame and the port draws, the nearest first.
    void drawn(const frame::FrameState& state, std::vector<Drawn>& out) const;
    void record_decide(const frame::Recording& recording);
    void record_draw(const frame::Recording& recording);
    void record_sides(const frame::Recording& recording);

    std::uint16_t kind_ = 0;
    Counts counts_;

    // made in prepare
    const gpu::Device* device_ = nullptr;
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets_{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    std::array<gpu::Buffer, 2> jobs_;
    std::array<gpu::Buffer, 2> tones_;
    std::array<gpu::Buffer, 2> extra_;
    std::array<gpu::Buffer, 2> faces_;
    std::array<gpu::Buffer, 2> parts_;
    std::array<gpu::Buffer, 2> sides_;
    /// While a tile fades, each face's standing fade, then each opaque part's and each translucent one's (ENGINE.md's
    /// standing fades; bound always)
    std::array<gpu::Buffer, 2> fades_;
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::PipelineLayout compute_layout_{nullptr};
    std::vector<vk::raii::Pipeline> stages_; ///< each kind's decide stage, in the kinds' order (kind_stages)
    vk::raii::Pipeline roof_masts_{nullptr}; ///< roof_masts_cs, over the roof masts' jobs
    vk::raii::Pipeline faces_pipeline_{nullptr};
    vk::raii::Pipeline fills_pipeline_{nullptr}; ///< the faces' fans alone at true depth (ENGINE.md gain 18)
    vk::raii::Pipeline fills_fading_{nullptr};   ///< the same while a tile fades (items_fills_fading_fs)
    vk::raii::Pipeline parts_pipeline_{nullptr};
    vk::raii::Pipeline sides_pipeline_{nullptr};

    // the render thread's, per frame
    std::vector<Drawn> drawn_;
    std::vector<std::uint32_t> words_;
    std::vector<std::uint32_t> extra_words_;
    /// each kind's jobs, the roof masts' last, before they join words_ in that order; and each other kind's jobs'
    /// translucent records' counts
    std::array<std::vector<std::uint32_t>, item_kind_stages + 1> kind_words_;
    std::array<std::vector<std::uint32_t>, item_kind_stages> kind_sides_;
    std::vector<std::uint32_t> fade_words_;
    bool dithers_ = false; ///< what stands up draws dithered this frame (scene::Cover::dithers)
    std::uint32_t slot_ = 0;
#if defined(ATLAS_TESTS)
    vk::raii::ShaderModule whole_module_{nullptr}; ///< the tests' (Testing::whole)
    vk::raii::Pipeline whole_{nullptr};
    Testing testing_;
    bool use_whole_ = false;
    std::optional<std::byte> sentinel_;
#endif
};

} // namespace atlas::passes
