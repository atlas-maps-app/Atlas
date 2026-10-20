// The world the map draws (plan-vector D1 "The scene", D3): the packs the app lists (tiles::Store: bundled tiles,
// render packs, windows, the world bases), the active pack for the camera (the finest render pack holding its target,
// D3 "Packs on the phone"), that pack's ladder (view::Ladder, from its `rhead`) and frame (view::Frames, a change of
// frame blended over 300 ms), the tiles resident in the geometry heaps (scene/Residency.hpp, built by every pass's
// stage T on the workers), the cover over them (scene/Cover.hpp) and the item table their items join
// (scene/ItemTable.hpp). The frame builder asks it once a frame, on the render thread (frame::FrameScene); nothing in
// it waits on I/O, decoding or the sun. At rest the frame loop sleeps: a tile or a tile's sun bits published wakes it
// (set_wake), and it draws while a transition runs (animating). Set 1's globals that no pass owns come from the
// world's providers (frame::FrameScene::globals): its heaps', its sun's (scene/SunGlobals.hpp), those the passes
// contribute (frame::Pass::providers, made with WorldOptions::resources) and those added to it.
//
// Tiles leave as the budgets ask (D3: 40 MB of heaps; §3: 64 MB of decoded tiles in native memory): a resident tile the
// cover has not drawn for `idle_seconds` is let go of once the heaps or the resident tiles' native bytes are over
// `keep_fraction` of their budget, and after `forget_seconds` whatever they hold; its ranges are freed two frames
// later, after the fences, its native memory at once.
#pragma once

#include "frame/FrameBuilder.hpp"
#include "frame/Globals.hpp"
#include "scene/Cover.hpp"
#include "scene/Heaps.hpp"
#include "scene/ItemTable.hpp"
#include "scene/Residency.hpp"
#include "scene/SunGlobals.hpp"
#include "tiles/Store.hpp"
#include "view/Frames.hpp"
#include "view/Ladder.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace atlas::scene {

/// The world's choices.
struct WorldOptions {
    unsigned workers = 2;         ///< T8: 2-3 by core count
    double fine_reach = 4700.0;   ///< how far the finest level serves (D3)
    double far = 12'000.0;        ///< how far the cover reaches
    double idle_seconds = 2.0;    ///< a tile not drawn this long may go when the heaps fill
    double forget_seconds = 30.0; ///< and goes after this long whatever they hold
    double keep_fraction = 0.75;  ///< of the heaps' budget, and of the native one
    HeapBudget heaps;
    /// The resident tiles' native bytes (ResidencyStats::native_bytes: their records, items and the passes' CPU-side
    /// data), §3's decoded tiles.
    std::size_t native_budget = std::size_t{64} << 20U;
    /// The sun (scene/SunGlobals.hpp): its threads and disk cache, or none.
    SunOptions sun;
    /// Where the passes' resources come from (their fonts: frame::files_under on a host, the APK's assets on Android),
    /// for the providers they contribute (frame::Pass::providers).
    frame::Resources resources;
    /// The frame's clock (seconds), from the workers' threads too; the frame state's time when absent.
    std::function<double()> clock;
};

/// The world.
class World final : public frame::FrameScene {
public:
    /// A world on `device`, its tiles built by `passes` (the frame builder's: they outlive the world).
    [[nodiscard]] static gpu::Result<std::unique_ptr<World>>
    create(const gpu::Device& device, std::span<const std::unique_ptr<frame::Pass>> passes, WorldOptions options = {});
    ~World() override;
    World(const World&) = delete;
    World& operator=(const World&) = delete;
    World(World&&) = delete;
    World& operator=(World&&) = delete;

    /// The packs: add and remove them as the app lists them (MapCore.packs).
    [[nodiscard]] tiles::Store& store() noexcept { return store_; }
    /// The pack the frame draws from now on (its store id), or the automatic choice (empty).
    void set_active(std::string id) { forced_ = std::move(id); }
    /// A coarser pack drawn around the active one (its store id: the canal ring around the Westerkerk; empty: none),
    /// and the box masking it (D3: the coarser pack's items whose reference points lie inside it are skipped, so a
    /// building is drawn once and whole, and its tiles wholly inside it are left out); without a box, the active pack's
    /// own, its finest level's reach. K11 chooses both per frame.
    void set_coarser(std::string id, std::optional<tiles::GridBox> mask = std::nullopt);
    /// The coarser pack drawn now (null: none) and its mask.
    [[nodiscard]] const tiles::StorePack* coarser() const noexcept { return coarser_.get(); }
    [[nodiscard]] const std::optional<tiles::GridBox>& mask() const noexcept { return mask_; }

    // frame::FrameScene
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override;
    void update(const frame::FrameState& state) override;
    [[nodiscard]] const Cover* cover() const noexcept override { return &cover_; }
    [[nodiscard]] const ItemTable* items() const noexcept override { return &items_; }
    [[nodiscard]] Heaps* heaps() noexcept override { return heaps_.get(); }
    [[nodiscard]] std::span<frame::GlobalProvider* const> globals() noexcept override { return globals_; }
    [[nodiscard]] std::optional<view::GroundFrame> ground_frame() const noexcept override { return frames_.current(); }

    /// Adds a provider of set 1's globals that no pass owns (K17a's fields and byte tables, K13's glyphs and sprites),
    /// asked each frame after the world's own (the heaps', the sun's); refused when it fills a binding another fills.
    [[nodiscard]] gpu::Result<void> add_globals(std::shared_ptr<frame::GlobalProvider> provider);
    /// The sun (null when the options turned it off).
    [[nodiscard]] SunGlobals* sun() noexcept { return sun_.get(); }

    /// Wakes the frame loop at rest: called on a worker thread as each tile is published (cheap and never blocking:
    /// post a frame request), so the next frame takes the tile and fades it in.
    /// The sun's workers call it too, as each tile's bits are published. It must stay valid until the world is gone.
    void set_wake(std::function<void()> wake);
    /// Whether the last update left the frame mid-transition: a tile drawn still fading in, a change of the frame
    /// drawn still blending, or a tile's sun bits still cross-fading. The frame loop draws another frame while it is
    /// true; once a frame leaves it false, the loop may rest until the camera moves or the wake.
    [[nodiscard]] bool animating() const noexcept { return animating_; }

    /// The pack drawn now (null: none holds the camera's target).
    [[nodiscard]] const tiles::StorePack* active() const noexcept { return active_.get(); }
    [[nodiscard]] Residency& residency() noexcept { return *residency_; }
    [[nodiscard]] const view::Frames& frames() const noexcept { return frames_; }
    /// The resident tiles and the heaps' use, for the `vk tiles <n> resident <MB> build <ms>` line (Appendix C).
    [[nodiscard]] std::string tiles_line() const;

private:
    explicit World(WorldOptions options) noexcept : options_(std::move(options)) {}
    /// The active pack for the camera's target: the forced one, else the first render pack (in the store's order)
    /// whose finest level has a tile under the target.
    void choose(const style::CameraState& camera);
    void release_idle(double seconds, std::uint64_t frame);
    /// The coarser pack and its mask for the frame (set_coarser's, else none).
    void choose_coarser();
    /// A resident tile's items in the frame drawn (converted from their pack's frame) and masked where they are the
    /// coarser pack's inside the mask.
    void place_items(const ResidentTile& tile);

    WorldOptions options_;
    tiles::Store store_;
    std::unique_ptr<Heaps> heaps_;
    std::unique_ptr<Residency> residency_;
    std::unique_ptr<frame::GlobalProvider> heap_globals_;
    std::unique_ptr<SunGlobals> sun_;
    std::vector<std::shared_ptr<frame::GlobalProvider>> added_;
    std::vector<frame::GlobalProvider*> globals_; ///< every provider, in the order the frame asks them
    Cover cover_;
    ItemTable items_;
    view::Frames frames_;
    std::string forced_;
    std::shared_ptr<const tiles::StorePack> active_;
    std::string coarser_wanted_;
    std::optional<tiles::GridBox> mask_wanted_;
    std::shared_ptr<const tiles::StorePack> coarser_;
    std::optional<tiles::GridBox> mask_;
    /// What the items were last placed for: the frame drawn, the coarser pack and the mask.
    std::optional<view::GroundFrame> placed_frame_;
    std::string placed_coarser_;
    std::optional<tiles::GridBox> placed_mask_;
    std::vector<std::shared_ptr<const ResidentTile>> taken_;
    std::optional<view::Ladder> ladder_;
    /// Per resident tile (its handle): when the cover last drew it.
    std::vector<std::pair<std::uint32_t, double>> last_drawn_;
    bool animating_ = false;
};

} // namespace atlas::scene
