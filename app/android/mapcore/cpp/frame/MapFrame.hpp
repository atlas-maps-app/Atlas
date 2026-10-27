// The app's map in the core (plan-vector K11; D1, D3, D4): what the session draws in VK bench's stand-in's place while
// a map screen is on (standin/SessionMap.hpp). It holds the frame builder over the world (frame/FrameBuilder.hpp,
// scene/World.hpp), made on the session's device and made again after the device's loss; the packs the app lists
// (MapCore.packs: the render packs the APK carries, mapped where they lie in it, and those downloaded), kept across a
// loss and given to every world made; and the moment's theme (MapCore.moment, about once a minute).
//
// Per frame it takes the camera Kotlin wrote for the vsync: the pack the camera state names (pack_id: PackList's
// choice, the finest pack holding the target as the live map has always chosen it) is the pack the frame draws, and its
// profile's rules light it (the theme's TONE on for the regions' rules, off for the living map's, as the port's
// Regions.load and the living map's load set Amsterdam.TONE). A number it does not know leaves the choice to the world.
//
// After a frame it tells whether the picture was complete (the pack drawn, every tile the cover wants resident and
// drawn, every fade-in and the frame's blend done: the app's transitional rule and its first-frame line wait for one)
// and whether more frames are due at the same camera (World::animating: a fade or the frame's blend under way); a tile
// published wakes the frame loop at rest (World::set_wake, the wake given here), so the loop draws until the picture is
// final and then rests (D1: at rest the map draws nothing). Render thread only, but the world's clock and the wake,
// which its workers call.
//
// Every world made holds the passes' own providers of set 1's globals (frame::Pass::providers, made with the map's
// resources: their fonts) and K17a's noise fields and byte tables (fx::FieldCache), kept under the app's cache.
#pragma once

#include "frame/FrameBuilder.hpp"
#include "labels/Sprites.hpp"
#include "markers/Markers.hpp"
#include "routes/Routes.hpp"
#include "scene/Pick.hpp"
#include "scene/World.hpp"
#include "standin/SessionMap.hpp"
#include "style/Theme.hpp"
#include "tiles/Store.hpp"

#include <chrono>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::frame {

/// One pack the app lists: its number (the camera state's pack_id), and where its bytes are: a file, or a range of an
/// open descriptor (an APK asset) that the map owns from then on and closes when the pack goes.
struct MapPack {
    std::uint64_t number = 0;
    tiles::PackRef ref;
};

/// What a map is made with besides its workers and its wake.
struct MapOptions {
    /// The app's cache folder: the noise fields are kept under cache_dir/fields (fx::FieldCache); empty: none kept,
    /// each world made makes them again.
    std::filesystem::path cache_dir;
    /// Where the passes' resources come from (their fonts: frame::files_under on a host, assets_of on Android); none
    /// by default.
    Resources resources;
};

/// The app's map.
class MapFrame final : public standin::SessionMap {
public:
    /// A map whose world builds tiles on `workers` threads (T8: 2-3 by core count); `wake` is called on a worker as
    /// each tile, a tile's sun bits or the noise fields are published (cheap and never blocking: the frame loop's frame
    /// request), for every world made.
    MapFrame(unsigned workers, std::function<void()> wake, MapOptions options = {});
    /// The session that draws it must have let go of it first (Session::map(nullptr), or the session's close).
    ~MapFrame() override;
    MapFrame(const MapFrame&) = delete;
    MapFrame& operator=(const MapFrame&) = delete;
    MapFrame(MapFrame&&) = delete;
    MapFrame& operator=(MapFrame&&) = delete;

    /// The packs the app lists from now on, replacing the last list: those gone leave the world (their descriptors
    /// closed), new and changed ones open in it (now if the world is made, else when it is), the same ones stay as
    /// they are, and one that does not open is not kept (the next listing tries it again). A line per pack listed:
    /// "<id> opened", "<id> listed" (no world yet), "<id> kept" or "<id>: <why not>".
    [[nodiscard]] std::string set_packs(std::vector<MapPack> packs);
    /// The moment's theme from now on (checked by style::read_theme_block).
    void set_theme(const style::ThemeBlock& theme) noexcept { theme_ = theme; }
    /// The app's labels from now on (MapCore.labels; null: none), drawn by the labels pass.
    void set_labels(std::shared_ptr<const labels::LabelSet> labels) noexcept { labels_ = std::move(labels); }
    /// The app's markers from now on (MapCore.markers; null: none), drawn by the markers pass.
    void set_markers(std::shared_ptr<const markers::MarkerSet> markers) noexcept { markers_ = std::move(markers); }
    /// The app's routes and areas from now on (MapCore.routes; null: none), drawn by the routes pass.
    void set_routes(std::shared_ptr<const routes::RouteSet> routes) noexcept { routes_ = std::move(routes); }
    /// The building the app asks about from now on (MapCore.ask; none: no question open), drawn as asked.
    void set_asked(std::optional<AskedBuilding> asked) noexcept { asked_ = asked; }
    /// The painter switch from now on (FrameOptions::painter), kept for every frame builder made.
    void set_painter(bool on) noexcept {
        painter_ = on;
        if (builder_) builder_->set_painter(on);
    }
    /// The screen's haze, vignette and grain on or off from now on (the app's Look settings: FrameOptions::steps
    /// without their composite steps when off; the grain off takes the materials' grain off the fills too,
    /// FrameOptions::grain), kept for every frame builder made.
    void set_screen(bool haze, bool vignette, bool grain) noexcept;
    /// Where the fling under way lands (scene::World::set_landing, ENGINE.md gain 26), or none; kept for a world made
    /// later.
    void set_landing(std::optional<scene::World::Landing> landing) noexcept {
        landing_ = landing;
        if (world_) world_->set_landing(landing);
    }

    /// Whether the last frame's picture was complete.
    [[nodiscard]] bool complete() const noexcept;
    /// Whether the last frame left the picture mid-transition (a fade or the frame's blend): another is due at the
    /// same camera.
    [[nodiscard]] bool animating() const noexcept { return made() && world_->animating(); }
    /// The pack the last frame drew ("" for none).
    [[nodiscard]] std::string_view drawn_pack() const noexcept;
    /// Its world's `vk tiles <n> resident <MB> build <ms>` line (Appendix C).
    [[nodiscard]] std::string tiles_line() const;
    /// The windows its world wants built, nearest first (scene::World::wanted_windows; none before it is made).
    [[nodiscard]] std::vector<tiles::TileKey> wanted_windows() const;
    /// The places in view its world draws from windows, nearest first (scene::World::windows_in_view).
    [[nodiscard]] std::vector<tiles::TileKey> windows_in_view() const;
    /// The building under the view's device point (`x`, `y`) as the last frame drew it (scene/Pick.hpp: the ray from
    /// the last frame's eye through that point, among the buildings of the packs its resident tiles came from), or
    /// none.
    [[nodiscard]] std::optional<scene::Picked> pick(double x, double y);
    /// The world, while made (tests).
    [[nodiscard]] scene::World* world() noexcept { return world_.get(); }
    /// The frame builder, while made (tests: the last frame's state).
    [[nodiscard]] const FrameBuilder* builder() const noexcept { return builder_.get(); }

    // standin::SessionMap
    [[nodiscard]] std::string make(const gpu::Device& device, const gpu::PipelineCache& cache,
                                   gpu::PipelineWatch* watch) override;
    [[nodiscard]] bool made() const noexcept override { return builder_ != nullptr; }
    void release(gpu::PipelineWatch* watch) noexcept override;
    [[nodiscard]] gpu::Content& content() noexcept override { return *builder_; }
    [[nodiscard]] bool ready() const noexcept override { return made() && theme_.has_value(); }
    void next(const style::CameraState& camera, double seconds) override;
    [[nodiscard]] standin::MapCounts counts() const noexcept override;

private:
    /// Seconds on the world's clock (its workers read it too).
    [[nodiscard]] double now() const noexcept;
    /// Opens `pack` in the world; "" or why not.
    [[nodiscard]] std::string open(const MapPack& pack);

    unsigned workers_;
    std::function<void()> wake_;
    MapOptions options_;
    std::chrono::steady_clock::time_point epoch_;
    std::vector<MapPack> packs_;
    std::optional<style::ThemeBlock> theme_;
    std::shared_ptr<const labels::LabelSet> labels_;
    std::shared_ptr<const markers::MarkerSet> markers_;
    std::shared_ptr<const routes::RouteSet> routes_;
    std::optional<AskedBuilding> asked_;
    bool painter_ = false;
    std::uint32_t steps_ = ~0U;
    bool grain_ = true;
    std::optional<scene::World::Landing> landing_;
    std::uint64_t active_number_ = 0;
    std::string active_id_;
    std::unique_ptr<FrameBuilder> builder_;
    std::unique_ptr<scene::World> world_; // after the builder: it goes first (its workers run the builder's passes)
};

/// The theme `theme` with `profile`'s rules: TONE on for every profile but the living map's.
[[nodiscard]] style::ThemeBlock with_profile(style::ThemeBlock theme, tiles::Profile profile) noexcept;

/// The theme for `pack` drawn as the active pack: its profile's (with_profile), but the living map's light (no TONE)
/// for a cell of windows that draws the renders' New looks (load::draws_new_looks: the Netherlands), so the map does
/// not turn warmer as the camera leaves the bundled Westerkerk for the windows around it.
[[nodiscard]] style::ThemeBlock with_pack(style::ThemeBlock theme, const tiles::StorePack& pack) noexcept;

} // namespace atlas::frame
