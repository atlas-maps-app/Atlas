// The user's palette through the app's map (frame/MapFrame.hpp's set_palette; ENGINE.md, "The user's palette"), on the
// host's Vulkan: each slot of the Map look changed alone moves a frame that shows its kind, inside a country's pack
// (the canal ring by day near and from its Main board, at a wall of shops, over the Prinsengracht by day and at
// night) and beyond every pack (the globe, by day and night across its terminator), so a setting holds wherever the
// core draws it; a change draws at rest with no tile built again, and today's palette again draws the first frame byte
// for byte. The slots the moment's theme carries (the bare ground, the streets without a surface, the water and its
// gradient, the night's ground and water, the sky, the sun, the shade's light, the shadows, the haze) move as the app
// moves them before the theme reaches the core (mapview's PaletteTheme, whose rule is style::palette_move's), the
// others by the palette's block. And the reflections under a palette: the walls' and the trees' changes move pixels the
// reflections alone draw.
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/MapFrame.hpp"
#include "gpu/Guard.hpp"
#include "gpu/Offscreen.hpp"
#include "scene/Cover.hpp"
#include "scene/Residency.hpp"
#include "scene/World.hpp"
#include "scene/WorldTiles.hpp"
#include "standin/Session.hpp"
#include "style/Palette.hpp"
#include "style/Theme.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "tests/tiles/world_support.hpp"
#include "tiles/Container.hpp"
#include "tiles/WorldIndex.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <string_view>
#include <unistd.h>
#include <utility>
#include <vector>

using namespace atlas;

#if !defined(__ANDROID__)

namespace {

namespace fs = std::filesystem;
namespace flag = style::theme_flag;

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before the test's threads
    return v == nullptr ? std::string() : std::string(v);
}

/// A slot of the Map look (mapview's Slot): its code, its name and its colour today.
struct Slot {
    std::uint32_t code;
    std::string_view name;
    std::uint32_t today;
};

/// Every slot the Map look edits (mapview's Slot.entries, in their order).
constexpr std::array<Slot, 63> slots{{
    {1, "paper", 0xD6CFC2U},
    {2, "lawn", 0xA9C38AU},
    {3, "woods", 0x6F8661U},
    {4, "squares", 0xDCD3C4U},
    {5, "parking", 0xC9C4BAU},
    {6, "playground", 0xD9B48AU},
    {7, "memorial", 0xE3B3C0U},
    {8, "bridge_deck", 0xCFC6B6U},
    {16, "water", 0x4F7590U},
    {17, "water_sky", 0xA6BFD4U},
    {18, "water_deep", 0x2D506CU},
    {19, "quay_walls", 0x6E5546U},
    {32, "street", 0xC9C1B4U},
    {33, "asphalt", 0x9B9A99U},
    {34, "paving_stones", 0xC9B9A6U},
    {35, "setts", 0xA89A88U},
    {36, "concrete_paving", 0xC4C2BCU},
    {37, "brick_paving", 0xB98870U},
    {38, "boardwalk", 0xA88A62U},
    {48, "old_brick_1", 0x6E3B2EU},
    {49, "old_brick_2", 0x7A4434U},
    {50, "old_brick_3", 0x5C3A30U},
    {51, "old_brick_4", 0x8A5040U},
    {52, "old_brick_5", 0x6A4A3CU},
    {53, "old_brick_6", 0x7E5242U},
    {54, "plaster_1", 0xE8E2D4U},
    {55, "plaster_2", 0xEFE9DCU},
    {56, "plaster_3", 0xD9D2C2U},
    {57, "new_brick_1", 0x9C6A52U},
    {58, "new_brick_2", 0x8F7F74U},
    {59, "new_brick_3", 0xB08A6AU},
    {60, "new_brick_4", 0x8C8A86U},
    {61, "new_brick_5", 0xA9876EU},
    {62, "limestone", 0xD8CCB2U},
    {63, "concrete", 0xBEBAB1U},
    {80, "red_tiles", 0xAE573FU},
    {81, "dark_tiles_1", 0x4A3E3AU},
    {82, "dark_tiles_2", 0x56504CU},
    {83, "dark_tiles_3", 0x3E4248U},
    {84, "flat_grey_1", 0x7E7A74U},
    {85, "flat_grey_2", 0x8C8882U},
    {86, "flat_grey_3", 0x6E6A66U},
    {87, "bitumen", 0x56585CU},
    {88, "gravel", 0x9C988FU},
    {89, "slate", 0x535C66U},
    {90, "zinc", 0x737E88U},
    {91, "lead", 0x7A8187U},
    {112, "leaves", 0x4E6B35U},
    {113, "evergreen", 0x4E6B35U},
    {114, "bark", 0x4B4038U},
    {115, "benches", 0x8A6A4CU},
    {116, "lamp_posts", 0x2A2E33U},
    {117, "bollards", 0x4A2620U},
    {118, "turbines", 0xE9ECEEU},
    {119, "pylons", 0x6E747CU},
    {128, "night_ground", 0x12151FU},
    {129, "night_water", 0x070C14U},
    {130, "lit_windows", 0xFFD08AU},
    {131, "sky", 0x7FA6DAU},
    {132, "sun", 0xFFEBCBU},
    {133, "shade", 0x93A6CCU},
    {134, "shadows", 0x27305AU},
    {135, "haze", 0xDFE6EEU},
}};

/// The colour a test sets a slot to: magenta, which no slot is near, or green for one near it.
[[nodiscard]] constexpr std::uint32_t far_from(std::uint32_t today) noexcept {
    const auto r = static_cast<int>((today >> 16U) & 0xFFU);
    const auto g = static_cast<int>((today >> 8U) & 0xFFU);
    return r > 200 && g < 80 ? 0x00FF00U : 0xFF00FFU;
}

[[nodiscard]] const Slot& slot_of(std::uint32_t code) {
    return *std::ranges::find_if(slots, [code](const Slot& s) { return s.code == code; });
}

/// `c` as 0xRRGGBB.
[[nodiscard]] std::uint32_t rgb_of(const style::Rgb8& c) noexcept {
    return (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | std::uint32_t{c.b};
}

/// `theme`'s colours as the app moves them by `palette` (mapview's PaletteTheme.recolour: each theme colour with the
/// slot it goes with, by day or at night, moved by style::palette_move from the slot's colour today to the user's;
/// with grass on bare ground the day's ground goes to the lawns').
[[nodiscard]] style::ThemeBlock recoloured(style::ThemeBlock theme, const style::PaletteBlock& palette) {
    const bool night = theme.has(flag::night);
    const auto move = [&palette](style::Rgb8& c, std::uint32_t code) {
        if (c.a == 0) return;
        const Slot& s = slot_of(code);
        std::uint32_t to = palette.colour(code).value_or(s.today);
        if (code == style::palette_slot::paper && palette.has(style::palette_flag::grass)) {
            to = palette.colour(style::palette_slot::lawn).value_or(slot_of(style::palette_slot::lawn).today);
        }
        if (to == s.today) return;
        const std::uint32_t moved = style::palette_move(rgb_of(c), s.today, to);
        c = {.r = static_cast<std::uint8_t>(moved >> 16U),
             .g = static_cast<std::uint8_t>(moved >> 8U),
             .b = static_cast<std::uint8_t>(moved),
             .a = c.a};
    };
    move(theme.ground, night ? style::palette_slot::night_ground : style::palette_slot::paper);
    move(theme.road, style::palette_slot::street);
    move(theme.water, night ? style::palette_slot::night_water : style::palette_slot::water);
    move(theme.water_top, style::palette_slot::water_sky);
    move(theme.water_bottom, style::palette_slot::water_deep);
    move(theme.sky_top, 131);
    move(theme.sky_horizon, 131);
    move(theme.sun, 132);
    move(theme.ambient, 133);
    move(theme.shadow, 134);
    move(theme.haze, 135);
    return theme;
}

/// The palette of slot `code` alone at `rgb`.
[[nodiscard]] style::PaletteBlock only(std::uint32_t code, std::uint32_t rgb) {
    style::PaletteBlock p{};
    p.colours.at(code) = 0x0100'0000U | rgb;
    return p;
}

/// amsterdam.py's sunny moment of 1 October 2026, 16:00, with the hour's weather (tests/frame/map_frame_vk_test.cpp's).
[[nodiscard]] style::ThemeBlock sunny() {
    style::ThemeBlock b{};
    b.version = style::theme_block_version;
    b.place_latitude = 52.3746F;
    b.place_longitude = 4.8840F;
    b.flags = flag::weather | flag::shadow_blur | flag::cloud_shadows | flag::water_gradient | flag::wet_set;
    b.year = 2026;
    b.month = 10;
    b.day = 1;
    b.hour = 16;
    b.minute = 0;
    b.moment = style::MomentName::sunny;
    b.weather_code = 2;
    b.sky_top = {.r = 0x7F, .g = 0xA6, .b = 0xDA, .a = 255};
    b.sky_horizon = {.r = 0xDC, .g = 0xE7, .b = 0xF2, .a = 255};
    b.haze = {.r = 0xDF, .g = 0xE6, .b = 0xEE, .a = 255};
    b.sun = {.r = 0xFF, .g = 0xEB, .b = 0xCB, .a = 255};
    b.ambient = {.r = 0x93, .g = 0xA6, .b = 0xCC, .a = 255};
    b.ground = {.r = 0xD6, .g = 0xCF, .b = 0xC2, .a = 255};
    b.road = {.r = 0xC9, .g = 0xC1, .b = 0xB4, .a = 255};
    b.water = {.r = 0x4F, .g = 0x75, .b = 0x90, .a = 255};
    b.water_top = {.r = 0xA6, .g = 0xBF, .b = 0xD4, .a = 255};
    b.water_bottom = {.r = 0x2D, .g = 0x50, .b = 0x6C, .a = 255};
    b.shadow = {.r = 0x27, .g = 0x30, .b = 0x5A, .a = 255};
    b.haze_max = 0.22 * 0.85;
    b.sun_k = 1.0;
    b.amb_k = 0.6;
    b.sky_k = 1.0;
    b.shadow_opacity = 0.32;
    b.shadow_blur = 0.6;
    b.cloud_shadows = 0.42;
    const double len = std::sqrt((0.5307 * 0.5307) + (0.2779 * 0.2779) + (0.8006 * 0.8006));
    b.sun_vector = {0.5307 / len, 0.2779 / len, 0.8006 / len};
    b.sun_azimuth = 213.5;
    b.sun_elevation = 16.1;
    b.cloud = 0.42;
    b.cloud_low = 0.1;
    b.wind = 14.0;
    b.gust = 27.0;
    b.wind_direction = 225.0;
    b.temperature = 14.2;
    b.pressure = 1012.5;
    b.visibility = 24000.0;
    b.humidity = 72.0;
    return b;
}

/// The same hour at night (map_frame_vk_test.cpp's wet night, dry, so no rain's rings move between two frames): the
/// lit windows, the lamps, the night's ground and water.
[[nodiscard]] style::ThemeBlock night() {
    style::ThemeBlock b = sunny();
    b.flags = (b.flags | flag::night) & ~(flag::shadow_blur | flag::cloud_shadows);
    b.moment = style::MomentName::night;
    b.weather_code = 0;
    b.sky_top = {.r = 0x05, .g = 0x08, .b = 0x14, .a = 255};
    b.sky_horizon = {.r = 0x1C, .g = 0x23, .b = 0x40, .a = 255};
    b.haze = {.r = 0x16, .g = 0x1D, .b = 0x36, .a = 255};
    b.ground = {.r = 0x12, .g = 0x15, .b = 0x1F, .a = 255};
    b.road = {.r = 0x1B, .g = 0x1F, .b = 0x2C, .a = 255};
    b.water = {.r = 0x0B, .g = 0x14, .b = 0x22, .a = 255};
    b.water_top = {.r = 0x13, .g = 0x1B, .b = 0x2A, .a = 255};
    b.water_bottom = {.r = 0x03, .g = 0x06, .b = 0x0B, .a = 255};
    b.shadow_opacity = 0.0;
    b.shadow_blur = 0.0;
    b.cloud_shadows = 0.0;
    b.sun_k = 0.0;
    b.sun_vector = {0.0, -1.0, 0.0};
    b.sun_elevation = -21.4;
    return b;
}

constexpr vk::Extent2D size{.width = 180, .height = 400};

/// A camera over (`lat`, `lon`) on the 180 × 400 px window at density 1, naming pack `pack` (0: none).
[[nodiscard]] style::CameraState camera_at(double lat, double lon, double bearing, double pitch, double distance,
                                           std::uint64_t pack) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    c.principal_x = size.width / 2.0;
    c.principal_y = size.height * 0.48;
    c.density = 1.0;
    c.width_px = size.width;
    c.height_px = size.height;
    c.width_dp = size.width;
    c.height_dp = size.height;
    c.flags = style::camera_flag::rest;
    c.pack_id = pack;
    return c;
}

/// An offscreen window that keeps its images, to read the frames back.
class KeptWindow final : public standin::SessionWindow {
public:
    [[nodiscard]] gpu::Result<vk::raii::SurfaceKHR> surface(const gpu::Instance& /*instance*/) override {
        return vk::raii::SurfaceKHR{nullptr};
    }
    [[nodiscard]] gpu::Result<std::unique_ptr<gpu::Output>> output(const gpu::Device& device,
                                                                   vk::SurfaceKHR /*surface*/, vk::Extent2D wanted,
                                                                   const gpu::Output* /*old*/, gpu::Depth depth,
                                                                   gpu::Compression /*compression*/) override {
        auto images = gpu::Offscreen::create(device, wanted, 3, depth);
        if (!images) return std::unexpected(images.error());
        kept = images->get();
        return std::unique_ptr<gpu::Output>(std::move(*images));
    }
    gpu::Offscreen* kept = nullptr;
};

/// A fresh guard folder, removed when the test case ends.
class Folder {
public:
    explicit Folder(std::string_view name)
        : path_(fs::temp_directory_path() / std::format("atlas-palette-{}-{}", name, ::getpid())) {
        std::error_code ec;
        fs::remove_all(path_, ec);
        fs::create_directories(path_);
    }
    Folder(const Folder&) = delete;
    Folder& operator=(const Folder&) = delete;
    Folder(Folder&&) = delete;
    Folder& operator=(Folder&&) = delete;
    ~Folder() {
        std::error_code ec;
        fs::remove_all(path_, ec);
    }
    [[nodiscard]] const fs::path& path() const noexcept { return path_; }

private:
    fs::path path_;
};

/// A guarded start in `dir`, as the façade starts (it presents).
[[nodiscard]] gpu::StartOptions start_in(const fs::path& dir) {
    return {.guard_dir = dir,
            .cache_file = dir / "pipelines.bin",
            .system = "host-test-system",
            .build = "palette-test",
            .present = true,
            .spirv = 42};
}

/// The map's session on the host's Vulkan in `dir`, or none (the test skips, saying why).
[[nodiscard]] std::optional<gpu::Started> start(const fs::path& dir) {
    auto started = gpu::start_guarded(start_in(dir));
    if (started) return std::move(*started);
    if (auto instance = test::open_instance({}); instance) test::cannot_present(*instance, started.error().reason);
    return std::nullopt;
}

/// The tiles the map's last frame drew: each one's handle, its cells and whether its fade is done.
[[nodiscard]] std::vector<std::uint64_t> drawn_tiles(frame::MapFrame& map) {
    std::vector<std::uint64_t> out;
    if (map.world() == nullptr || map.world()->cover() == nullptr) return out;
    for (const scene::CoverTile& t : map.world()->cover()->tiles()) {
        out.push_back(t.drawn != nullptr ? t.drawn->handle : 0U);
        out.push_back(t.cells);
        out.push_back(t.fade >= 1.0F ? 1U : 0U);
    }
    return out;
}

/// Draws `camera` until the map's picture is complete (its tiles resident and drawn, every fade done) twice over the
/// same tiles and the world wants nothing more (at most four minutes); false where it never is.
[[nodiscard]] bool draw_still(standin::Session& session, frame::MapFrame& map, const style::CameraState& camera) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
    std::optional<std::vector<std::uint64_t>> complete;
    while (std::chrono::steady_clock::now() < until) {
        const standin::Drawn d = session.draw(camera, {});
        if (d.stale) session.remake();
        scene::WorldTiles* tiles = map.world() != nullptr ? map.world()->world_tiles() : nullptr;
        const scene::WorldFrame* f = tiles != nullptr ? tiles->frame() : nullptr;
        const bool world_done = f == nullptr || f->wanted == 0;
        if (!map.complete() || !world_done) {
            complete.reset();
            if (map.world() != nullptr) map.world()->residency().wait_idle();
            if (tiles != nullptr) tiles->wait_idle();
            continue;
        }
        std::vector<std::uint64_t> drawn = drawn_tiles(map);
        if (complete == drawn) return true;
        complete = std::move(drawn);
    }
    return false;
}

/// The frame of `camera` drawn now (two frames, the second read back).
[[nodiscard]] gpu::Pixels frame_of(standin::Session& session, KeptWindow& window, const style::CameraState& camera) {
    for (int i = 0; i < 2; ++i) {
        if (session.draw(camera, {}).stale) session.remake();
    }
    auto pixels = window.kept->read(window.kept->latest());
    if (!pixels) {
        FAIL_CHECK(pixels.error().text());
        return {};
    }
    return std::move(*pixels);
}

/// The pixels of `a` and `b` (the same size) any of whose channels differ by more than 8 levels.
[[nodiscard]] std::vector<bool> changed(const gpu::Pixels& a, const gpu::Pixels& b) {
    std::vector<bool> out(a.rgba.size() / 4, false);
    if (a.rgba.size() != b.rgba.size()) return std::vector<bool>(out.size(), true);
    for (std::size_t i = 0; i < out.size(); ++i) {
        for (std::size_t c = 0; c < 3; ++c) {
            if (std::abs(int{a.rgba[(4 * i) + c]} - int{b.rgba[(4 * i) + c]}) > 8) out[i] = true;
        }
    }
    return out;
}

[[nodiscard]] std::size_t count(const std::vector<bool>& v) {
    return static_cast<std::size_t>(std::ranges::count(v, true));
}

/// A view of the test: its name, its camera and its moment.
struct View {
    std::string_view name;
    style::CameraState camera;
    style::ThemeBlock theme;
};

/// Each slot's changed pixels in each view, and the views' noise (today drawn twice).
struct Moves {
    std::map<std::uint32_t, std::vector<std::pair<std::string_view, std::size_t>>> by_slot;
    std::vector<std::pair<std::string_view, std::size_t>> noise;
};

/// Draws each view of `views` with today's palette, then with each slot alone set far from today's colour (its theme
/// as the app recolours it), and records how many pixels moved beyond the view's noise; today's palette again draws
/// the first frame within that noise (none at rest).
void measure(standin::Session& session, frame::MapFrame& map, KeptWindow& window, std::span<const View> views,
             Moves& out) {
    const style::PaletteBlock today{};
    for (const View& v : views) {
        map.set_palette(today);
        map.set_theme(v.theme);
        ATLAS_REQUIRE(draw_still(session, map, v.camera), std::string(v.name) + " never complete");
        const gpu::Pixels base = frame_of(session, window, v.camera);
        const std::size_t noise = count(changed(base, frame_of(session, window, v.camera)));
        out.noise.emplace_back(v.name, noise);
        const std::vector<std::uint64_t> tiles = drawn_tiles(map);
        for (const Slot& s : slots) {
            const style::PaletteBlock p = only(s.code, far_from(s.today));
            map.set_palette(p);
            map.set_theme(recoloured(v.theme, p));
            const std::size_t n = count(changed(base, frame_of(session, window, v.camera)));
            out.by_slot[s.code].emplace_back(v.name, n > noise ? n - noise : 0);
        }
        // nothing built again for a palette: the same tiles drawn
        CHECK_MESSAGE(drawn_tiles(map) == tiles, v.name);
        map.set_palette(today);
        map.set_theme(v.theme);
        const gpu::Pixels again = frame_of(session, window, v.camera);
        CHECK_MESSAGE(count(changed(base, again)) <= noise, v.name);
        if (noise == 0) CHECK_MESSAGE(again.rgba == base.rgba, v.name);
    }
}

/// The globe's L0 tile as test::world_tile cuts it (cell c land west of (c % 3) / 2 of its width, sea east of it),
/// with two cities of twelve million in cell 6, at (25° N, 70° E) and (20° N, 10° E) (world_vk_test.cpp's).
[[nodiscard]] test::TileBytes lit_globe_tile() {
    using namespace atlas::tiles;
    namespace col = test::columns;
    test::TileBytes tile = test::world_tile(0, 0, 0);
    test::LayerBytes label{.id = static_cast<std::uint8_t>(LayerId::label)};
    label.cells = {6, 6};
    const std::initializer_list<std::pair<std::int32_t, std::int32_t>> at = {{250'000'000, 700'000'000},
                                                                             {200'000'000, 100'000'000}};
    label.columns = {{"source", Codec::u8, col::u8({1, 1})},
                     {"index", Codec::delta, col::delta({0, 1})},
                     {"name", Codec::str, col::str(tile, {"East", "Middle"})},
                     {"pos", Codec::point, col::point(at)},
                     {"prev", Codec::point, col::point(at)},
                     {"count", Codec::varu, col::varu({1, 1})},
                     {"kind", Codec::str, col::str(tile, {"city", "city"})},
                     {"tag:population", Codec::str, col::str(tile, {"12000000", "12000000"})}};
    tile.layers.back() = label;
    return tile;
}

/// The pixels each slot moved in each view, and each view's noise, a line a slot.
[[nodiscard]] std::string table_of(const Moves& m) {
    std::ostringstream out;
    for (const auto& [code, views] : m.by_slot) {
        out << slot_of(code).name << ":";
        for (const auto& [name, n] : views) out << " " << name << "=" << n;
        out << "\n";
    }
    for (const auto& [name, n] : m.noise) out << "noise " << name << "=" << n << "\n";
    return out.str();
}

} // namespace

TEST_CASE("the map's palette: each slot changed alone moves a frame that shows its kind, in the canal ring's pack near "
          "and from its board, by day and at night, and on the globe beyond every pack, by day and night; "
          "nothing is built again, and today's palette again draws the frame it drew") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the map is not drawn over the bundled packs here");
        return;
    }
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("slots");
    const fs::path& dir = folder.path();
    std::optional<gpu::Started> started = start(dir);
    if (!started) return;
    // the globe of world_vk_test.cpp's look, for the frames beyond every pack
    constexpr std::array<std::uint8_t, 6> coarse = {5, 4, 3, 2, 1, 0};
    const std::vector<std::byte> globe = test::world_pack({lit_globe_tile()}, coarse);
    {
        std::ofstream file(dir / tiles::world_globe_file, std::ios::binary);
        file.write(reinterpret_cast<const char*>(globe.data()), static_cast<std::streamsize>(globe.size())); // NOLINT
    }
    Moves moves;
    {
        frame::MapFrame map(2, [] {}, {.cache_dir = dir});
        standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
        std::vector<frame::MapPack> packs;
        std::uint64_t number = 0;
        for (const char* id : {"amsterdam", "grachten"}) {
            frame::MapPack p;
            p.number = ++number;
            p.ref.id = id;
            p.ref.kind = tiles::PackKind::bundled;
            p.ref.path = (fs::path(tiles_dir) / (std::string(id) + ".atlas")).string();
            packs.push_back(std::move(p));
        }
        frame::MapPack base;
        base.number = 102;
        base.ref.id = std::string(tiles::world_globe_id);
        base.ref.kind = tiles::PackKind::world_base;
        base.ref.path = (dir / tiles::world_globe_file).string();
        packs.push_back(std::move(base));
        (void)map.set_packs(std::move(packs));
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
        map.set_theme(sunny());
        map.set_screen(false, false, false);
        const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
        // a plain day over the globe, the sun low over the cities' place, the terminator across the disc (the cities
        // in the night beyond it light)
        style::ThemeBlock plain = sunny();
        plain.flags = 0;
        plain.weather_code = 255;
        plain.cloud = plain.cloud_low = plain.wind = plain.gust = plain.wind_direction = 0.0;
        plain.temperature = plain.pressure = plain.visibility = plain.humidity = 0.0;
        plain.shadow_blur = plain.cloud_shadows = 0.0;
        const std::array<View, 6> views{{
            {.name = "home",
             .camera = camera_at(52.3746 + (30.0 / 110540.0), 4.8840 - (10.0 / kx), 52.0, 52.0, 430.0, 2),
             .theme = sunny()},
            {.name = "canal",
             .camera = camera_at(52.3756, 4.8846 + (40.0 / kx), 213.5, 70.0, 260.0, 2),
             .theme = sunny()},
            {.name = "shops",
             .camera = camera_at(52.3746 - (297.49641 / 110540.0), 4.8840 + (425.44906 / kx), 269.23868878579214, 60.0,
                                 70.0, 2),
             .theme = sunny()},
            {.name = "board", .camera = camera_at(52.3746, 4.884, 52.0, 46.0, 2300.0, 2), .theme = sunny()},
            {.name = "night",
             .camera = camera_at(52.3756, 4.8846 + (40.0 / kx), 213.5, 70.0, 260.0, 2),
             .theme = night()},
            {.name = "globe", .camera = camera_at(20.0, 10.0, 0.0, 0.0, 54'000'000.0, 0), .theme = plain},
        }};
        measure(session, map, *kept, views, moves);
    }
    const std::string table = table_of(moves);
    MESSAGE("the pixels each slot moved in each view, beyond the view's noise:\n" << table);
    // every slot moves a frame that shows its kind
    for (const Slot& s : slots) {
        const auto& views = moves.by_slot[s.code];
        const bool moved = std::ranges::any_of(views, [](const auto& v) { return v.second > 0; });
        CHECK_MESSAGE(moved, s.name);
    }
    // the globe beyond every pack: its land with the lawns, its sea with the water, its night with the night's
    // ground, its cities' lights with the lit windows
    const auto in = [&](std::uint32_t code, std::string_view view) {
        for (const auto& [name, n] : moves.by_slot[code]) {
            if (name == view) return n;
        }
        return std::size_t{0};
    };
    CHECK(in(style::palette_slot::lawn, "globe") > 0);
    CHECK(in(style::palette_slot::water, "globe") > 0);
    CHECK(in(style::palette_slot::night_ground, "globe") > 0);
    CHECK(in(style::palette_slot::lit_windows, "globe") > 0);
    // and the canal ring's lit shops at night with the lit windows
    CHECK(in(style::palette_slot::lit_windows, "night") > 0);
}

TEST_CASE("the reflections under the map's palette: the walls' and the leaves' changes move pixels that the "
          "reflections alone draw, and today's palette mirrors them as before") {
    const std::string tiles_dir = environment("ATLAS_TILES_DIR");
    if (tiles_dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the map is not drawn over the bundled packs here");
        return;
    }
    if (!test::open_session()) return; // no Vulkan here
    const Folder folder("mirror");
    const fs::path& dir = folder.path();
    std::optional<gpu::Started> started = start(dir);
    if (!started) return;
    frame::MapFrame map(2, [] {}, {.cache_dir = dir});
    standin::Session session(*started, {.cache_file = dir / "pipelines.bin", .spirv = 42});
    frame::MapPack ring;
    ring.number = 2;
    ring.ref.id = "grachten";
    ring.ref.kind = tiles::PackKind::bundled;
    ring.ref.path = (fs::path(tiles_dir) / "grachten.atlas").string();
    std::vector<frame::MapPack> packs;
    packs.push_back(std::move(ring));
    (void)map.set_packs(std::move(packs));
    CHECK(session.map(&map).empty());
    auto window = std::make_unique<KeptWindow>();
    KeptWindow* kept = window.get();
    std::string why;
    ATLAS_REQUIRE(session.attach(std::move(window), size, why), why);
    map.set_theme(sunny());
    map.set_screen(false, false, false);
    // over the Prinsengracht toward the sun: its houses and trees mirrored in it
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    const style::CameraState on = camera_at(52.3756, 4.8846 + (40.0 / kx), 213.5, 70.0, 260.0, 2);
    style::CameraState off = on;
    off.parts = style::camera_part::reflections;
    ATLAS_REQUIRE(draw_still(session, map, on));
    const style::PaletteBlock today{};
    const gpu::Pixels today_on = frame_of(session, *kept, on);
    const gpu::Pixels today_off = frame_of(session, *kept, off);
    // where the reflections draw
    const std::vector<bool> mirrored = changed(today_on, today_off);
    REQUIRE(count(mirrored) > 100);
    style::PaletteBlock walls{};
    for (std::uint32_t code = 48; code <= 63; ++code) walls.colours.at(code) = 0x01FF00FFU;
    style::PaletteBlock leaves = only(style::palette_slot::leaves, 0xFF00FFU);
    leaves.colours.at(style::palette_slot::evergreen) = 0x01FF00FFU;
    for (const auto& [name, palette] : {std::pair{"walls", walls}, std::pair{"leaves", leaves}}) {
        map.set_palette(palette);
        const std::vector<bool> with = changed(today_on, frame_of(session, *kept, on));
        const std::vector<bool> without = changed(today_off, frame_of(session, *kept, off));
        // the pixels the palette moved with the reflections drawn and not without them, where they draw
        std::size_t theirs = 0;
        for (std::size_t i = 0; i < with.size(); ++i) theirs += with[i] && !without[i] && mirrored[i] ? 1U : 0U;
        MESSAGE(name << ": " << theirs << " of the reflections' " << count(mirrored) << " pixels moved");
        CHECK_MESSAGE(theirs > 20, name);
    }
    map.set_palette(today);
    CHECK(count(changed(today_on, frame_of(session, *kept, on))) == 0);
}

#endif
