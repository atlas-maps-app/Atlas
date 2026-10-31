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
// Tiles leave as the budgets ask (the heaps', 64 MB unless the options say more: the app's map takes the device's,
// scene::heap_budget; and as many bytes of decoded tiles in native memory): a
// resident tile the cover has not drawn for `idle_seconds` is let go of once the heaps or the resident tiles' native
// bytes are over `keep_fraction` of their budget, and after `forget_seconds` whatever they hold; its ranges are freed
// two frames later, after the fences, its native memory at once.
#pragma once

#include "frame/FrameBuilder.hpp"
#include "frame/Globals.hpp"
#include "scene/Cover.hpp"
#include "scene/Heaps.hpp"
#include "scene/ItemTable.hpp"
#include "scene/Packs.hpp"
#include "scene/Residency.hpp"
#include "scene/SunGlobals.hpp"
#include "tiles/Store.hpp"
#include "view/Frames.hpp"
#include "view/Ladder.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::scene {

/// How far ahead the windows the core wants look while the camera pans (World::wanted_windows): the places its target's
/// motion brings into view within this many seconds are asked for after those in view, so they are built before the
/// camera reaches them.
inline constexpr double window_lead = 3.0;
/// The least motion of the camera's target that counts as panning, metres a second.
inline constexpr double window_motion_min = 10.0;
/// How many places ahead are asked for at once (each is a query of Overpass).
inline constexpr std::size_t window_ahead_max = 4;

/// The world's choices.
struct WorldOptions {
    unsigned workers = 2;       ///< T8: 2-3 by core count
    double fine_reach = 4700.0; ///< how far the finest level serves (D3)
    /// How far a frame cell's coarse level serves (L13, every building), its far level (L12) beyond (ENGINE.md one pack
    /// per country, step 7): 12 km, as far as alpha 23's windows drew every building, where a house of 60 m² is still
    /// 2.4 px of the user's phone across and the L12 keeps only those of 1,000 m² or 30 m.
    double coarse_reach = 12'000.0;
    double far = 12'000.0; ///< how far the cover reaches at least
    /// How far it reaches at most: twice the camera's distance between the two (step 7: a frame cell's levels drawn out
    /// to 30 km of the eye, where the engine's reach ends).
    double levels_far = 30'000.0;
    double idle_seconds = 2.0;    ///< a tile not drawn this long may go when the heaps fill
    double forget_seconds = 30.0; ///< and goes after this long whatever they hold
    double keep_fraction = 0.75;  ///< of the heaps' budget, and of the native one
    /// Of the heaps' budget, what the tiles the cover asks for may take (Cover's plan): below keep_fraction, so a tile
    /// planned has room while the ones no longer drawn wait to go, and the heaps' free ranges need not be one.
    double plan_fraction = 0.7;
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
    /// Whether the workers share a tile's passes and their pieces (ENGINE.md gain 9: WorkerOptions::share).
    bool share = true;
    /// What the residency's workers tell the platform (ENGINE.md gain 8: platform::WorkerSession on Android, their
    /// priority and their hint session), or none: they stay at background priority.
    std::shared_ptr<WorkerHints> hints;
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
    /// The pack the frame draws from now on (its store id, or a window's, whose frame cell's pack is drawn), or the
    /// automatic choice (empty). Where the pack drawn still holds the camera's target, it stays drawn while the named
    /// one's tiles in view come in (incoming()), so a switch never draws a frame of nothing in between.
    void set_active(std::string id) { forced_ = std::move(id); }
    /// The pack named next, drawn once its tiles in view are resident (its own stage T built) or after
    /// `incoming_hold` seconds, whichever comes first; null when no switch is pending.
    [[nodiscard]] const tiles::StorePack* incoming() const noexcept { return incoming_.get(); }
    /// How long a switch waits at most for the named pack's tiles, seconds.
    static constexpr double incoming_hold = 3.0;
    /// Where a fling lands (ENGINE.md gain 26): the camera's target, bearing, pitch and distance at its end, which the
    /// app knows as the fling begins. From then on the landing's tiles are wanted after the frame's own (its visible
    /// ones in the prefetch lane, ahead of any other prefetch), so the map lands whole. None: no fling under way.
    struct Landing {
        double latitude = 0;
        double longitude = 0;
        double bearing = 0;
        double pitch = 0;
        double distance = 0;
    };
    void set_landing(std::optional<Landing> landing) noexcept { landing_ = landing; }
    /// The tiles last asked of the residency: the frame's and the landing's.
    [[nodiscard]] std::span<const Request> asked() const noexcept { return asked_; }
    /// A coarser pack drawn around the active one (its store id: the canal ring around the Westerkerk; empty: none),
    /// and the box masking it (D3: the coarser pack's items whose reference points lie inside it are skipped, so a
    /// building is drawn once and whole, and its tiles wholly inside it are left out); without a box, the active pack's
    /// own, its finest level's reach. K11 chooses both per frame.
    void set_coarser(std::string id, std::optional<tiles::GridBox> mask = std::nullopt);
    /// Without set_coarser's id, the coarser pack the world chooses itself (the app's map): the smallest other render
    /// pack whose box (its finest level's reach) holds the active one's, so the canal ring is drawn around the
    /// Westerkerk; none where no pack holds it.
    void set_coarser_enclosing(bool on) noexcept { coarser_enclosing_ = on; }
    /// The coarser pack drawn now (null: none) and its mask.
    [[nodiscard]] const tiles::StorePack* coarser() const noexcept { return coarser_.get(); }
    [[nodiscard]] const std::optional<tiles::GridBox>& mask() const noexcept { return mask_; }
    /// The packs drawn beside the active one now (max_neighbours at most, null past the last), across kinds alone:
    /// around a render pack, cells' windows (tiles::Store's cell packs), the frame cell's holding the camera's target
    /// first, then the other cells' within the cover's reach of the target, nearest first; around a cell's windows, the
    /// render packs within that reach, then the other cells' windows, each nearest first. Never a render pack beside a
    /// render pack. So every cell in view is drawn (ENGINE.md one pack per country, step 4: where four cells meet, the
    /// three around the active one). Each one's tiles are drawn where neither the active nor the coarser pack nor a
    /// neighbour before it has one (scene/Cover.hpp).
    [[nodiscard]] std::array<const tiles::StorePack*, max_neighbours> neighbours() const noexcept;
    /// The nearest of them (null: none).
    [[nodiscard]] const tiles::StorePack* neighbour() const noexcept { return neighbours_[0].get(); }
    /// The L14 places in view the phone may build a window for (ENGINE.md step 3: the core asks, the app fetches):
    /// each seen by the frustum within the finest level's reach of the eye, where no pack of the store has a tile and
    /// no bundled or render pack's box holds the place whole (an empty place of that pack's data), nearest first; then,
    /// while the camera pans, the few places its motion brings into view within window_lead seconds (prefetched),
    /// nearest to where the eye will be first. Made on the render thread at most every half second; read from any
    /// thread.
    [[nodiscard]] std::vector<tiles::TileKey> wanted_windows() const;
    /// The L14 places in view drawn from windows, nearest first: each seen by the frustum within the finest level's
    /// reach of the eye, where only windows hold its tile (the app refreshes the ones whose data has aged: ENGINE.md
    /// step 3, a window built again after a week). Made with wanted_windows; read from any thread.
    [[nodiscard]] std::vector<tiles::TileKey> windows_in_view() const;
    /// How many of wanted_windows' last places are the ones ahead of the camera's motion.
    [[nodiscard]] std::size_t windows_ahead() const;
    /// The frame cells' levels' tiles in view the phone may take from the country's tiles (ENGINE.md one pack per
    /// country, step 7) where no pack of the store has them: each L12 tile the frustum sees within the cover's reach,
    /// then each L13 tile within the coarse level's reach that holds a place beyond the finest level's, each level's by
    /// its share of the screen, then nearest first (the app asks for those its country lists). Made with
    /// wanted_windows; read from any thread.
    [[nodiscard]] std::vector<tiles::TileKey> wanted_levels() const;
    /// The frame cells' levels' tiles the cover draws now, nearest first, then those it keeps under the places in view
    /// (each place's coarsest): tiles the store holds, whose files the app keeps while they are in use (the windows'
    /// folder's budget, as windows_in_view for the places' windows). Made with wanted_windows; read from any thread.
    [[nodiscard]] std::vector<tiles::TileKey> levels_in_view() const;
    /// How far the cover reaches for a camera `distance` metres from its target: twice that, within the options' far
    /// and levels_far.
    [[nodiscard]] double far_of(double distance) const noexcept {
        return std::clamp(2.0 * distance, options_.far, std::max(options_.far, options_.levels_far));
    }

    // frame::FrameScene
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override;
    void update(const frame::FrameState& state) override;
    [[nodiscard]] const Cover* cover() const noexcept override { return &cover_; }
    [[nodiscard]] const ItemTable* items() const noexcept override { return &items_; }
    [[nodiscard]] Heaps* heaps() noexcept override { return heaps_.get(); }
    [[nodiscard]] std::span<const std::string_view> pass_names() const noexcept override { return pass_names_; }
    [[nodiscard]] std::span<frame::GlobalProvider* const> globals() noexcept override { return globals_; }
    [[nodiscard]] std::optional<view::GroundFrame> ground_frame() const noexcept override { return frames_.current(); }
    [[nodiscard]] const ResidentPack* active_pack() const noexcept override { return built(active_); }
    [[nodiscard]] const ResidentPack* coarser_pack() const noexcept override { return built(coarser_); }
    [[nodiscard]] std::span<const ResidentPack* const> neighbour_packs() const noexcept override {
        return neighbour_packs_;
    }
    /// The packs' stage T (tests: wait_idle).
    [[nodiscard]] PackStage& pack_stage() noexcept { return *pack_stage_; }

    /// Adds a provider of set 1's globals that no pass owns (K17a's fields and byte tables, K13's glyphs and sprites),
    /// asked each frame after the world's own (the heaps', the sun's); refused when it fills a binding another fills.
    [[nodiscard]] gpu::Result<void> add_globals(std::shared_ptr<frame::GlobalProvider> provider);
    /// The sun (null when the options turned it off).
    [[nodiscard]] SunGlobals* sun() noexcept { return sun_.get(); }

    /// Wakes the frame loop at rest: called on a worker thread as each tile is published (cheap and never blocking:
    /// post a frame request), so the next frame takes the tile and fades it in.
    /// The sun's workers call it too, once a run's bits are published. It must stay valid until the world is gone.
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
    /// whose finest level has a tile reaching over the target (its chunks' reach, what their features draw: a sparse
    /// window may reach over part of its place alone, and the app names the window whose box holds the target).
    void choose(const style::CameraState& camera);
    void release_idle(double seconds, std::uint64_t frame);
    /// The coarser pack and its mask for the frame (set_coarser's, else the enclosing pack's when chosen, else none).
    void choose_coarser();
    /// The smallest other render pack whose box holds the active one's (set_coarser_enclosing), or "": a frame cell's
    /// windows are never one.
    [[nodiscard]] std::string enclosing_pack();
    /// The neighbours for the frame (neighbours()), for the camera's target.
    void choose_neighbours(const style::CameraState& camera);
    /// The windows wanted in `view` around the camera's target (wanted_windows), at most every half second; `frame`
    /// the view's ground frame (the frame drawn, or without a pack one centred on the target, as the view then is).
    void want_windows(const view::View& view, const style::CameraState& camera, const view::GroundFrame& frame,
                      double seconds);
    /// The levels' tiles wanted in `view` (wanted_levels), with the windows.
    [[nodiscard]] std::vector<tiles::TileKey> want_levels(const view::View& view, const style::CameraState& camera,
                                                          const view::GroundFrame& frame) const;
    /// Room for the tiles the frame asks for, where tiles that only stand in hold it (ENGINE.md one pack per country,
    /// step 7: the heaps full of windows zooming out, of levels zooming in). In the order
    /// the residency builds them, each is asked for while the heaps' free bytes hold what the cover expects of it and
    /// of those before it (a resident one, nothing more); once one does not fit, or waits for a range to go back to the
    /// heaps (Residency::Wait::stuck: their bytes free hold no piece for it), the coarsest of those left that is not
    /// resident takes the room first (zooming out, a frame cell's L12, which then stands in at sixteen places): the
    /// tiles of the places in view that nothing draws, stands in or would go (the cover's spare), then, while the room
    /// coming is short of it (the heaps' free and retiring bytes, and the stand-ins that go once the tiles asked for
    /// before it come; for a tile that waits for a range, those alone but the free), stand-ins one by one, those whose
    /// places keep a backup first, then those at its own places, the fewest holes first. It is asked for once the room
    /// is there, the rest held back until then (Request::held). Where what can go is short of it, of the stand-ins only
    /// those at its places that keep a backup go, and the tiles asked for before it come first where their coming lets
    /// another go, else every tile is asked for as before. What goes is let go of as the next frame begins (going_),
    /// before its covers.
    void make_room();
    /// make_room's piece of `bytes` for a tile whose largest range (of kind `kind`) finds none though the heaps hold
    /// its bytes free: whether the tiles going already, those nothing draws, keeps or asks for, and `stand_ins` (those
    /// whose places all keep another resident level) can make one where a range of its kind may lie, joined to the
    /// pieces beside them (Heaps::joined_with); those it takes are let go of as the next frame begins (going_), none
    /// where they cannot.
    [[nodiscard]] bool piece_for(std::uint32_t bytes, HeapKind kind,
                                 std::span<const std::pair<tiles::TileKey, std::string_view>> stand_ins);
    /// A resident tile's items in the frame drawn (converted from their pack's frame) and masked where they are the
    /// coarser pack's inside the mask.
    void place_items(const ResidentTile& tile);
    /// The packs' stage T: asks for the packs drawn, takes what was built, drops what the store no longer holds.
    void take_packs();
    /// The neighbours' stage T, as built so far (neighbour_packs).
    void take_neighbour_packs() noexcept;
    /// What was built of `pack`, or null.
    [[nodiscard]] const ResidentPack* built(const std::shared_ptr<const tiles::StorePack>& pack) const noexcept;

    WorldOptions options_;
    std::vector<std::string_view> pass_names_; ///< the passes that build its tiles and packs, in their order
    tiles::Store store_;
    std::unique_ptr<Heaps> heaps_;
    std::unique_ptr<Residency> residency_;
    std::unique_ptr<PackStage> pack_stage_;
    std::vector<std::shared_ptr<const ResidentPack>> packs_;   ///< the packs' stage T, built so far
    std::vector<std::shared_ptr<const ResidentPack>> arrived_; ///< taken this frame (kept for its capacity)
    std::shared_ptr<const tiles::StorePack> last_active_;      ///< the packs drawn when packs_ was last pruned
    std::shared_ptr<const tiles::StorePack> last_coarser_;
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
    bool coarser_enclosing_ = false;
    /// The enclosing pack's choice (set_coarser_enclosing), kept until the active pack or the store's packs change.
    std::shared_ptr<const tiles::StorePack> enclosing_for_;
    std::vector<std::shared_ptr<const tiles::StorePack>> enclosing_among_;
    std::string enclosing_id_;
    std::optional<tiles::GridBox> mask_wanted_;
    std::shared_ptr<const tiles::StorePack> coarser_;
    std::optional<tiles::GridBox> mask_;
    std::array<std::shared_ptr<const tiles::StorePack>, max_neighbours> neighbours_;
    std::array<std::shared_ptr<const tiles::StorePack>, max_neighbours> last_neighbours_;
    std::array<const ResidentPack*, max_neighbours> neighbour_packs_{}; ///< their stage T, once built (update)
    mutable std::mutex wanted_mutex_;
    std::vector<tiles::TileKey> wanted_;                     ///< under wanted_mutex_
    std::optional<std::array<std::int32_t, 2>> last_target_; ///< the camera's target at the last look (render thread)
    std::vector<tiles::TileKey> in_view_;                    ///< under wanted_mutex_
    std::vector<tiles::TileKey> levels_;                     ///< under wanted_mutex_
    std::vector<tiles::TileKey> levels_drawn_;               ///< under wanted_mutex_ (levels_in_view)
    double far_ = 12'000.0;                                  ///< the cover's reach this frame (far_of)
    /// The tiles make_room lets go of, at the next frame's start (this frame's covers may draw them).
    std::vector<std::pair<tiles::TileKey, std::string>> going_;
    std::size_t ahead_ = 0; ///< under wanted_mutex_
    double wanted_at_ = -1.0;
    /// What the items were last placed for: the frame drawn, the coarser pack and the mask.
    std::optional<view::GroundFrame> placed_frame_;
    std::string placed_coarser_;
    std::optional<tiles::GridBox> placed_mask_;
    std::vector<std::shared_ptr<const ResidentTile>> taken_;
    std::optional<view::Ladder> ladder_;
    std::optional<Landing> landing_;
    Cover landing_cover_; ///< the landing's cover, for its requests alone
    /// The pack named next while the pack drawn stays (incoming()), when it was named (-1: this frame's update sets
    /// it), whether its tiles in view are in, and its cover, for its requests alone.
    std::shared_ptr<const tiles::StorePack> incoming_;
    double incoming_since_ = -1.0;
    bool incoming_ready_ = false;
    Cover incoming_cover_;
    std::vector<Request> asked_; ///< the frame's requests, then the incoming pack's, then the landing's
    /// Per resident tile (its handle): when the cover last drew it.
    std::vector<std::pair<std::uint32_t, double>> last_drawn_;
    bool animating_ = false;
};

} // namespace atlas::scene
