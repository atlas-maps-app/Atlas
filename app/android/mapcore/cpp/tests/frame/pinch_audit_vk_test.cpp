// The app's pinch as the phone draws it (a tool, run only with ATLAS_PINCH_OUT set): the map of a place at the phone's
// own frame (ATLAS_PINCH_SIZE, S1's 1080 x 2400 at 2.625 by default), the camera pinched from ATLAS_PINCH_FROM in to
// ATLAS_PINCH_TO metres over ATLAS_PINCH_FRAMES frames without rest, then at rest, each frame's pack named as the app
// names it (PackList.choose with MapController's hold: the pack drawn kept while the camera moves, HOLD_FIT at rest);
// each frame's CPU (the session's draw, stage F, the recording), counts, GPU work and tiles logged. Then, at each of
// ATLAS_PINCH_REST's distances, the frame complete at rest and each pass's draws, dispatches, triangles and instances.
//
// The map: the windows of an N x N block of L14 places around the target (ATLAS_PINCH_BLOCK, 5), built anywhere's way
// from a region's raw.json (ATLAS_REGIONS_DIR, ATLAS_PINCH_REGION: grachten) into <out>/windows (kept there and used
// again), and, with ATLAS_TILES_DIR, the bundled Westerkerk and canal ring before them, as the APK lists them (a window
// only where no bundled pack listed holds its place whole, as the phone builds them).
// ATLAS_PINCH_PLACE: "lat,lon,bearing,pitch" (52.3735,4.8755,22,48: west of the Westerkerk); ATLAS_PINCH_HEAPS: the
// heaps' budget in MB instead of the device's; ATLAS_PINCH_OUTWARD: the pinch back out to the start after.
//
// To compare the packs' cuts (each alone at the same cameras): ATLAS_PINCH_PACKS names the bundled packs listed
// ("amsterdam,grachten" by default, "none" for none), ATLAS_PINCH_BLOCK=0 lists no windows, ATLAS_PINCH_KIT builds the
// windows in a tradition's kit (nl_amsterdam: the Dutch windows' look until 27 October) instead of the New looks, and
// ATLAS_PINCH_WINDOWS names the folder they are kept in (<out>/windows, or <out>/windows-<kit>). ATLAS_PINCH_COST: at
// each rest, what the frame drew kind by kind (the ranks' rows: buildings and parts, trees, things, region items,
// doors), and what each pass costs the GPU, measured as the phone's switches do, by leaving it out (FrameOptions::
// passes): the median of three frames' GPU time and fragments with every pass, then with each pass that drew alone
// (beside the composite: what it shades with nothing hiding it) and without it (what the frame saves, the fragments it
// hid from the others counted against it), and without the facades' detail (style::camera_part::facade_detail).
// ATLAS_PINCH_OFF: the parts every frame leaves out, as the app's switches do ("cloud_shadows,water": alpha 19's
// defaults; also trees, shadows, facades). ATLAS_PINCH_MOMENT: "night" (the live map's night with the hour's weather:
// its lit streets and their glow, the lamps' pools, the lit windows) or "wet-night" (the same in rain: the rain's
// streaks, the streets' wet cores, the drizzle's rings on the water) instead of the sunny moment.
#include "base/pymath/PyMath.hpp"
#include "frame/MapFrame.hpp"
#include "gpu/Guard.hpp"
#include "gpu/Offscreen.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "scene/ItemTable.hpp"
#include "standin/Session.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/gpu/session_fixture.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/load/query.hpp"
#include "tiles/Grid.hpp"
#include "twin/building/BuildingShader.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <map>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

#if !defined(__ANDROID__)

using namespace atlas;

namespace {

namespace fs = std::filesystem;

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before the test's threads
    return v == nullptr ? std::string() : std::string(v);
}

[[nodiscard]] std::vector<double> numbers(const std::string& text) {
    std::vector<double> out;
    std::stringstream s(text);
    std::string item;
    while (std::getline(s, item, ',')) {
        if (!item.empty()) out.push_back(std::stod(item));
    }
    return out;
}

/// An offscreen window that keeps its images, to read the frames back.
class KeptWindow final : public standin::SessionWindow {
public:
    [[nodiscard]] gpu::Result<vk::raii::SurfaceKHR> surface(const gpu::Instance& /*instance*/) override {
        return vk::raii::SurfaceKHR{nullptr};
    }
    [[nodiscard]] gpu::Result<std::unique_ptr<gpu::Output>> output(const gpu::Device& device,
                                                                   vk::SurfaceKHR /*surface*/, vk::Extent2D wanted,
                                                                   const gpu::Output* /*old*/,
                                                                   gpu::Depth depth) override {
        auto images = gpu::Offscreen::create(device, wanted, 3, depth);
        if (!images) return std::unexpected(images.error());
        kept = images->get();
        return std::unique_ptr<gpu::Output>(std::move(*images));
    }
    gpu::Offscreen* kept = nullptr;
};

/// amsterdam.py's sunny moment of 1 October 2026, 16:00, as Kotlin writes it with the hour's weather
/// (tests/frame/map_frame_vk_test.cpp's): the live map's moment wherever the forecast came, whose weather turns on the
/// facades' flat detail within 760 m, the material faces, the deep windows and the church windows.
[[nodiscard]] style::ThemeBlock sunny() {
    namespace flag = style::theme_flag;
    style::ThemeBlock b{};
    b.version = 1;
    b.flags = environment("ATLAS_PINCH_DRY").empty()
                  ? flag::weather | flag::shadow_blur | flag::cloud_shadows | flag::water_gradient | flag::wet_set
                  : 0U;
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

/// The phone's frame: its pixels and density.
struct Screen {
    vk::Extent2D size{.width = 1080, .height = 2400};
    double density = 2.625;
};

[[nodiscard]] Screen screen_of(const std::string& text) {
    Screen s;
    if (text.empty()) return s;
    const auto x = text.find('x');
    const auto at = text.find('@');
    s.size.width = static_cast<std::uint32_t>(std::stoul(text.substr(0, x)));
    s.size.height = static_cast<std::uint32_t>(std::stoul(text.substr(x + 1, at - x - 1)));
    s.density = std::stod(text.substr(at + 1));
    return s;
}

/// The camera MapController publishes over (`lat`, `lon`): the map's bearing and pitch (degrees from straight down),
/// `distance` metres, the frame in whole dp as MapController.liveView rounds it.
[[nodiscard]] style::CameraState camera(const Screen& s, double lat, double lon, double bearing, double pitch,
                                        double distance, bool rest) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    const double w_dp = std::max(1.0, std::round(s.size.width / s.density));
    const double h_dp = std::max(1.0, std::round(s.size.height / s.density));
    c.principal_x = w_dp / 2.0;
    c.principal_y = h_dp * 0.5;
    c.density = s.density;
    c.width_px = s.size.width;
    c.height_px = s.size.height;
    c.width_dp = static_cast<std::uint32_t>(w_dp);
    c.height_dp = static_cast<std::uint32_t>(h_dp);
    c.flags = rest ? style::camera_flag::rest : 0U;
    c.pack_id = 0;
    return c;
}

/// One pack as the app's PackList lists it: its box (south, west, north, east) and its views' distances.
struct Listed {
    frame::MapPack pack;
    std::string id;
    std::array<double, 4> box{};
    std::vector<double> distances;
};

/// PackList.choose: of the packs whose box holds the target, the one whose views fit the distance best (the smaller
/// box on a tie); the pack drawn now (`held`) stays while no other fits better by more than `hold_fit`.
[[nodiscard]] const Listed* choose(const std::vector<Listed>& listed, double lat, double lon, double distance,
                                   const Listed* held, double hold_fit) {
    const Listed* best = nullptr;
    double best_fit = 1e300;
    const Listed* kept = nullptr;
    double kept_fit = 1e300;
    const auto area = [](const Listed& a) { return (a.box[2] - a.box[0]) * (a.box[3] - a.box[1]); };
    for (const Listed& l : listed) {
        if (lat < l.box[0] || lat > l.box[2] || lon < l.box[1] || lon > l.box[3]) continue;
        double fit = 1e300;
        for (const double v : l.distances)
            fit = std::min(fit, std::fabs(pymath::strict::log(std::max(distance, 1.0) / v)));
        if (held == &l) {
            kept = &l;
            kept_fit = fit;
        }
        const bool tie = std::fabs(fit - best_fit) <= 1e-9;
        if (best == nullptr || (!tie && fit < best_fit) || (tie && area(l) < area(*best))) {
            best = &l;
            best_fit = std::min(fit, best_fit);
        }
    }
    return kept != nullptr && kept_fit <= best_fit + hold_fit ? kept : best;
}

/// Each pass's counts of the last frame: `pass <name> draws <n> dispatches <n> triangles <n> instances <n>` and its
/// kinds' triangles.
[[nodiscard]] std::string pass_counts(const frame::FrameBuilder& builder) {
    std::string out;
    for (const frame::PassCounters& p : builder.counters()) {
        const frame::Count& c = p.total();
        out += std::format("  pass {:<12} draws {:>4} dispatches {:>3} triangles {:>8} instances {:>7}", p.pass(),
                           c.draws, c.dispatches, c.triangles, c.instances);
        for (const frame::KindCount& k : p.kinds()) {
            out += std::format(" | {} {}d {}t {}i", k.kind, k.count.draws + k.count.dispatches, k.count.triangles,
                               k.count.instances);
        }
        out += '\n';
    }
    return out;
}

/// The windows' decide stages the last frame ran (their dispatches' kinds: passes/Windows.cpp), its facades' records
/// and its window records (a shop's glazing among them), as the draws count them.
[[nodiscard]] std::string decided(const frame::FrameBuilder& builder) {
    std::string stages;
    std::uint64_t facades = 0;
    std::uint64_t records = 0;
    for (const frame::PassCounters& p : builder.counters()) {
        for (const frame::KindCount& k : p.kinds()) {
            if (k.count.dispatches > 0 && (k.kind == "windows" || k.kind.starts_with("window "))) {
                stages += stages.empty() ? std::string(k.kind) : std::format(", {}", k.kind);
            }
            if (k.kind == "facades") facades += k.count.instances;
            if (k.kind == "window frames") records += k.count.triangles / (shaders::BUILDING_WINDOW_FRAME_VERTICES / 3);
        }
    }
    return std::format("decided [{}], {} facades, {} window records", stages.empty() ? "none" : stages, facades,
                       records);
}

/// The rows the last frame drew, kind by kind (the ranks' order: buildings and parts, trees, things, region items,
/// doors): `drew building 812, tree 140, thing 2311`.
[[nodiscard]] std::string drawn_kinds(const frame::FrameBuilder& builder, const scene::ItemTable& items) {
    std::map<std::string, std::uint32_t> by;
    const std::span<const std::uint16_t> kind = items.kind();
    for (const std::uint32_t row : builder.ranks().order()) {
        if (row < kind.size()) ++by[std::string(builder.selectors().name(kind[row]))];
    }
    std::string out = "drew";
    for (const auto& [name, n] : by) out += std::format("{} {} {}", out.size() > 4 ? "," : "", name, n);
    return by.empty() ? "drew nothing" : out;
}

/// The last frame's stage F part by part, the parts of 0.1 ms or more: `stage F: scene 1.2, ranks 3.4, doors 2.1`.
[[nodiscard]] std::string stage_f_parts(const frame::FrameBuilder& builder) {
    const frame::FrameBuilder::StageFParts& p = builder.stage_f_parts();
    std::string out =
        std::format("stage F: scene {:.1f}, globals {:.1f}, ranks {:.1f}", p.scene_ms, p.globals_ms, p.ranks_ms);
    for (std::size_t i = 0; i < p.pass_ms.size() && i < builder.passes().size(); ++i) {
        if (p.pass_ms[i] >= 0.1) out += std::format(", {} {:.1f}", builder.passes()[i]->name(), p.pass_ms[i]);
    }
    return out;
}

/// The middle of `v` (its upper middle for an even count).
[[nodiscard]] double median(std::vector<double> v) {
    if (v.empty()) return 0.0;
    std::ranges::sort(v);
    return v[v.size() / 2];
}

} // namespace

TEST_CASE("pinch audit: the app's pinch at the phone's frame, each frame's CPU and each pass's counts") {
    const std::string out = environment("ATLAS_PINCH_OUT");
    if (out.empty()) {
        MESSAGE("ATLAS_PINCH_OUT is not set: no pinch drawn here");
        return;
    }
    if (!test::open_session()) return;
    const Screen screen = screen_of(environment("ATLAS_PINCH_SIZE"));
    const std::vector<double> place = environment("ATLAS_PINCH_PLACE").empty()
                                          ? std::vector<double>{52.3735, 4.8755, 22.0, 48.0}
                                          : numbers(environment("ATLAS_PINCH_PLACE"));
    REQUIRE(place.size() == 4);
    const double lat = place[0];
    const double lon = place[1];
    const double bearing = place[2];
    const double pitch = place[3];
    const double from = environment("ATLAS_PINCH_FROM").empty() ? 3000.0 : std::stod(environment("ATLAS_PINCH_FROM"));
    const double to = environment("ATLAS_PINCH_TO").empty() ? 300.0 : std::stod(environment("ATLAS_PINCH_TO"));
    const int steps = environment("ATLAS_PINCH_FRAMES").empty() ? 90 : std::stoi(environment("ATLAS_PINCH_FRAMES"));
    const auto block = static_cast<std::uint32_t>(
        environment("ATLAS_PINCH_BLOCK").empty() ? 5UL : std::stoul(environment("ATLAS_PINCH_BLOCK")));
    const std::vector<double> rests = numbers(environment("ATLAS_PINCH_REST"));
    fs::create_directories(out);

    // The packs as the APK and the phone list them: the bundled ones first, then the windows by name.
    std::vector<Listed> listed;
    std::uint64_t number = 0;
    const std::string bundled =
        environment("ATLAS_PINCH_PACKS").empty() ? "amsterdam,grachten" : environment("ATLAS_PINCH_PACKS");
    if (const std::string tiles_dir = environment("ATLAS_TILES_DIR"); !tiles_dir.empty()) {
        const auto add = [&](const char* id, std::array<double, 4> box, std::vector<double> distances) {
            if (std::format(",{},", bundled).find(std::format(",{},", id)) == std::string::npos) return;
            Listed l;
            l.pack.number = ++number;
            l.pack.ref.id = id;
            l.pack.ref.kind = tiles::PackKind::bundled;
            l.pack.ref.path = (fs::path(tiles_dir) / (std::string(id) + ".atlas")).string();
            l.id = id;
            l.box = box;
            l.distances = std::move(distances);
            listed.push_back(std::move(l));
        };
        add("amsterdam", {52.371, 4.878, 52.378, 4.891}, {430.0});
        add("grachten", {52.36, 4.872, 52.393, 4.922}, {2700.0, 2300.0, 1600.0});
    }
    if (block > 0) {
        const std::string regions = environment("ATLAS_REGIONS_DIR");
        const std::string region =
            environment("ATLAS_PINCH_REGION").empty() ? "grachten" : environment("ATLAS_PINCH_REGION");
        // the windows in a tradition's kit (ATLAS_PINCH_KIT) instead of the New looks, kept apart
        const std::string kit = environment("ATLAS_PINCH_KIT");
        const fs::path folder = !environment("ATLAS_PINCH_WINDOWS").empty()
                                    ? fs::path(environment("ATLAS_PINCH_WINDOWS"))
                                : kit.empty() ? fs::path(out) / "windows"
                                              : fs::path(out) / ("windows-" + kit);
        fs::create_directories(folder);
        const tiles::TileKey centre = tiles::tile_of(14, static_cast<std::int32_t>(std::lround(lat * 1e7)),
                                                     static_cast<std::int32_t>(std::lround(lon * 1e7)));
        std::optional<osm::Document> doc;
        std::string text;
        const std::uint32_t half = block / 2;
        const std::size_t bundled_count = listed.size();
        for (std::uint32_t dy = 0; dy < block; ++dy) {
            for (std::uint32_t dx = 0; dx < block; ++dx) {
                const tiles::TileKey k(14, centre.x - half + dx, centre.y - half + dy);
                // as the phone builds them (World::wanted_windows): none where a bundled pack's box holds the place
                // whole
                const std::array<std::int32_t, 4> place_box = tiles::tile_box(k);
                const bool held_whole = std::any_of(
                    listed.begin(), listed.begin() + static_cast<std::ptrdiff_t>(bundled_count), [&](const Listed& p) {
                        return p.box[0] <= place_box[0] / 1e7 && p.box[1] <= place_box[1] / 1e7 &&
                               place_box[2] / 1e7 <= p.box[2] && place_box[3] / 1e7 <= p.box[3];
                    });
                if (held_whole) continue;
                const fs::path file = folder / std::format("14-{}-{}.atlas", k.x, k.y);
                if (!fs::exists(file)) {
                    if (!doc) {
                        REQUIRE(!regions.empty());
                        std::ifstream in(fs::path(regions) / region / "raw.json", std::ios::binary);
                        text.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
                        REQUIRE(!text.empty());
                        auto read = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
                        REQUIRE(read.has_value());
                        doc.emplace(std::move(*read));
                    }
                    const auto started = std::chrono::steady_clock::now();
                    load::WindowPlace where = load::place_of(k);
                    if (!kit.empty()) {
                        where.new_looks = false;
                        where.tradition = kit;
                    }
                    const auto built =
                        load::build_window(test::query(*doc, load::window_box(k)), tiles::Sha256Digest{}, k, where);
                    const double ms =
                        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
                    REQUIRE_MESSAGE(built.has_value(), built.error().what);
                    std::ofstream o(file, std::ios::binary | std::ios::trunc);
                    o.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                            static_cast<std::streamsize>(built->pack.size()));
                    MESSAGE(
                        std::format("window 14/{}/{} built in {:.0f} ms, {} bytes", k.x, k.y, ms, built->pack.size()));
                }
                const std::array<std::int32_t, 4> b = tiles::tile_box(k);
                Listed l;
                l.pack.number = ++number;
                l.pack.ref.id = std::format("window/14/{}/{}", k.x, k.y);
                l.pack.ref.kind = tiles::PackKind::window;
                l.pack.ref.path = file.string();
                l.id = l.pack.ref.id;
                l.box = {b[0] / 1e7, b[1] / 1e7, b[2] / 1e7, b[3] / 1e7};
                l.distances = {430.0};
                listed.push_back(std::move(l));
            }
        }
    }
    REQUIRE(!listed.empty());
    std::vector<frame::MapPack> packs;
    packs.reserve(listed.size());
    for (const Listed& l : listed) packs.push_back(l.pack);

    const fs::path guard = fs::temp_directory_path() / std::format("atlas-pinch-{}", ::getpid());
    fs::create_directories(guard);
    // ATLAS_PINCH_VALIDATE: under the validation layer (with VK_LAYER_GPUAV_ENABLE=1, its GPU-assisted checks of each
    // shader's buffer accesses, which lavapipe would otherwise let pass where a phone's GPU faults)
    auto started = gpu::start_guarded({.guard_dir = guard,
                                       .cache_file = guard / "pipelines.bin",
                                       .system = "host-test-system",
                                       .build = "pinch-audit",
                                       .validation = !environment("ATLAS_PINCH_VALIDATE").empty(),
                                       .present = true,
                                       // ATLAS_PINCH_DYNAMIC: the dynamic route with local reads, the PowerVR's, which
                                       // lavapipe's properties forbid (the GPU tests' fixture forces it alike)
                                       .force_local_read = !environment("ATLAS_PINCH_DYNAMIC").empty(),
                                       .spirv = 42});
    REQUIRE_MESSAGE(started.has_value(), started.error().reason);
    {
        const std::string heaps_mb = environment("ATLAS_PINCH_HEAPS");
        frame::MapFrame map(2, [] {},
                            {.cache_dir = guard, .heaps_total = heaps_mb.empty() ? 0U : std::stoull(heaps_mb) << 20U});
        standin::Session session(*started, {.cache_file = guard / "pipelines.bin", .spirv = 42});
        MESSAGE(map.set_packs(packs));
        CHECK(session.map(&map).empty());
        auto window = std::make_unique<KeptWindow>();
        KeptWindow* kept = window.get();
        std::string why;
        REQUIRE_MESSAGE(session.attach(std::move(window), screen.size, why), why);
        // the moment (ATLAS_PINCH_MOMENT): the sunny one, the live night, or the live night in rain
        {
            const std::string moment = environment("ATLAS_PINCH_MOMENT");
            style::ThemeBlock theme = sunny();
            if (moment == "night" || moment == "wet-night") theme = test::night_weather();
            if (moment == "wet-night") {
                theme.flags |= style::theme_flag::wet | style::theme_flag::wet_set;
                theme.weather_code = 61; // rain
                theme.rain = 1.2;
                theme.wind = 22.0;
                theme.gust = 38.0;
                theme.wind_direction = 225.0;
                theme.cloud = 1.0;
                theme.humidity = 94.0;
            }
            REQUIRE((moment.empty() || moment == "sunny" || moment == "night" || moment == "wet-night"));
            map.set_theme(theme);
        }
        // the validation layer's messages since the last call (ATLAS_PINCH_VALIDATE), each kind once with its count
        const auto said = [&](const std::string& when) {
            if (!started->instance) return;
            std::vector<std::pair<std::string, int>> kinds;
            for (const std::string& m : started->instance->messages().take()) {
                const std::string key = m.substr(0, 400);
                const auto it = std::ranges::find_if(kinds, [&key](const auto& k) { return k.first == key; });
                if (it != kinds.end()) {
                    ++it->second;
                } else {
                    kinds.emplace_back(key, 1);
                }
            }
            for (const auto& [m, n] : kinds) MESSAGE(std::format("validation {} ({}x): {}", when, n, m));
        };
        // the parts every frame leaves out (ATLAS_PINCH_OFF), as the app's switches name them
        std::uint64_t always_off = 0U;
        {
            namespace part = style::camera_part;
            const std::string named = std::format(",{},", environment("ATLAS_PINCH_OFF"));
            for (const auto& [name, bits] :
                 {std::pair{"trees", part::trees}, std::pair{"shadows", part::building_shadows | part::park_shadows},
                  std::pair{"cloud_shadows", part::cloud_shadows}, std::pair{"water", part::water_light},
                  std::pair{"facades", part::facade_detail}}) {
                if (named.find(std::format(",{},", name)) != std::string::npos) always_off |= bits;
            }
        }
        const Listed* held = nullptr;
        // a frame at `d` with the parts `off` leaves out (style::camera_part), the pack named as MapController names
        // it; its line in the log
        const auto draw = [&](const std::string& tag, double d, bool rest, bool log, std::uint64_t off = 0U) {
            style::CameraState c = camera(screen, lat, lon, bearing, pitch, d, rest);
            c.parts |= off | always_off;
            held = choose(listed, lat, lon, d, held, rest ? 0.3 : 1e300);
            c.pack_id = held != nullptr ? held->pack.number : 0;
            const auto at = std::chrono::steady_clock::now();
            const standin::Drawn s = session.draw(c, {});
            if (s.stale) session.remake();
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - at).count();
            const frame::FrameBuilder* b = map.builder();
            if (log) {
                MESSAGE(std::format("{} {:6.0f} m: named {} drew {}, complete {}, frame {:.0f} ms (stage F {:.1f}, "
                                    "record {:.1f}), {} draws {}k triangles, gpu {:.0f} ms {}k fragments; {}; {}; {}",
                                    tag, d, held != nullptr ? held->id : "-", map.drawn_pack(), map.complete(), ms,
                                    b != nullptr ? b->stage_f_ms() : 0.0, b != nullptr ? b->record_ms() : 0.0,
                                    s.record.draws, s.record.triangles_k, s.record.gpu_ms, s.record.fragments_k,
                                    b != nullptr ? decided(*b) : std::string("no frame"), map.tiles_line(),
                                    b != nullptr ? stage_f_parts(*b) : std::string()));
            }
            return s;
        };
        // at rest until complete (at most four minutes), then three frames more, the last one's counts logged
        const auto settle = [&](double d) {
            const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(240);
            int n = 0;
            while (std::chrono::steady_clock::now() < until) {
                n += draw("settle", d, true, false).drawn ? 1 : 0;
                if (map.complete() && n > 4) break;
                if (!map.animating() && map.world() != nullptr) map.world()->residency().wait_idle();
            }
            for (int k = 0; k < 3; ++k) (void)draw("rest", d, true, k == 2);
        };
        settle(from);
        said(std::format("at {:.0f} m", from));
        for (int i = 0; i <= steps; ++i) {
            const double t = static_cast<double>(i) / steps;
            const double d = from * pymath::strict::pow(to / from, t);
            (void)draw(std::format("in {:3d}", i), d, i == steps, true);
        }
        said("in the pinch");
        for (int i = 0; i < 20; ++i) (void)draw(std::format("held {:2d}", i), to, true, true);
        said(std::format("held at {:.0f} m", to));
        if (!environment("ATLAS_PINCH_OUTWARD").empty()) {
            for (int i = 0; i <= steps; ++i) {
                const double t = static_cast<double>(i) / steps;
                const double d = to * pymath::strict::pow(from / to, t);
                (void)draw(std::format("out {:3d}", i), d, i == steps, true);
            }
        }
        for (const double d : rests) {
            settle(d);
            said(std::format("at rest at {:.0f} m", d));
            if (const frame::FrameBuilder* b = map.builder(); b != nullptr) {
                MESSAGE(std::format("passes at {:.0f} m (rest, complete {}):\n{}", d, map.complete(), pass_counts(*b)));
            }
            if (!environment("ATLAS_PINCH_COST").empty()) {
                const frame::FrameBuilder* b = map.builder();
                REQUIRE(b != nullptr);
                REQUIRE(map.world() != nullptr);
                const std::string kinds = drawn_kinds(*b, *map.world()->items());
                // the passes that drew this frame (the composite stays: it makes the picture)
                std::vector<std::pair<std::string, std::size_t>> drew;
                for (std::size_t i = 0; i < b->counters().size(); ++i) {
                    const frame::Count& c = b->counters()[i].total();
                    const std::string_view name = b->counters()[i].pass();
                    if (name != "composite" && c.draws + c.dispatches > 0) drew.emplace_back(name, i);
                }
                // the median of three frames' GPU time and fragments, after three (the GPU's are an earlier frame's)
                const auto cost = [&](std::uint64_t passes, std::uint64_t off) {
                    map.set_passes(passes);
                    std::vector<double> ms;
                    std::vector<double> fragments;
                    for (int k = 0; k < 6; ++k) {
                        const standin::Drawn f = draw("cost", d, true, false, off);
                        if (k < 3) continue;
                        ms.push_back(static_cast<double>(f.record.gpu_ms));
                        fragments.push_back(f.record.fragments_k);
                    }
                    map.set_passes(~std::uint64_t{0});
                    return std::pair{median(ms), median(fragments)};
                };
                const auto [all_ms, all_fragments] = cost(~std::uint64_t{0}, 0U);
                std::string lines =
                    std::format("  every pass: gpu {:.0f} ms, {:.0f}k fragments\n", all_ms, all_fragments);
                std::uint64_t composite = 0;
                for (std::size_t i = 0; i < b->counters().size(); ++i) {
                    if (b->counters()[i].pass() == "composite") composite |= std::uint64_t{1} << i;
                }
                for (const auto& [name, i] : drew) {
                    const auto [alone_ms, alone_fragments] = cost(composite | (std::uint64_t{1} << i), 0U);
                    const auto [ms, fragments] = cost(~(std::uint64_t{1} << i), 0U);
                    lines += std::format("  {:<12} alone gpu {:6.0f} ms {:7.0f}k fragments; saves {:6.0f} ms {:7.0f}k "
                                         "(without it: {:.0f} ms, {:.0f}k)\n",
                                         name, alone_ms, alone_fragments, all_ms - ms, all_fragments - fragments, ms,
                                         fragments);
                }
                const auto [nothing_ms, nothing_fragments] = cost(composite, 0U);
                lines += std::format("  {:<12} alone gpu {:6.0f} ms {:7.0f}k fragments\n", "composite", nothing_ms,
                                     nothing_fragments);
                const auto [no_facades_ms, no_facades_fragments] =
                    cost(~std::uint64_t{0}, style::camera_part::facade_detail);
                lines += std::format("  {:<12} saves {:6.0f} ms {:7.0f}k (without it: {:.0f} ms, {:.0f}k)\n", "facades",
                                     all_ms - no_facades_ms, all_fragments - no_facades_fragments, no_facades_ms,
                                     no_facades_fragments);
                for (int k = 0; k < 4; ++k) (void)draw("rest", d, true, k == 3);
                MESSAGE(std::format("cost at {:.0f} m: {}\n{}", d, kinds, lines));
            }
            auto pixels = kept->kept->read(kept->kept->latest());
            REQUIRE_MESSAGE(pixels.has_value(), pixels.error().text());
            CHECK(gpu::write_ppm(*pixels, (fs::path(out) / std::format("rest_{:.0f}.ppm", d)).string()));
            // ATLAS_PINCH_PARTS: each part the phone's switches leave out, alone, its frame's draws, triangles and
            // fragments (the GPU's counts are an earlier frame's: the fourth frame's line is the part's)
            if (!environment("ATLAS_PINCH_PARTS").empty()) {
                namespace part = style::camera_part;
                for (const auto& [name, off] :
                     {std::pair{"trees", part::trees}, std::pair{"shadows", part::building_shadows},
                      std::pair{"cloud shadows", part::cloud_shadows}, std::pair{"water light", part::water_light},
                      std::pair{"facade detail", part::facade_detail}}) {
                    for (int k = 0; k < 4; ++k) (void)draw(std::format("without {}", name), d, true, k == 3, off);
                }
                for (int k = 0; k < 4; ++k) (void)draw("with every part", d, true, k == 3);
            }
        }
    }
    std::error_code ec;
    fs::remove_all(guard, ec);
}

#endif
