// A window of the map the phone builds itself (load/Window.hpp).
#include "load/Window.hpp"

#include "base/pymath/PyMath.hpp"
#include "gen/data/tradition_fr_paris.hpp"
#include "gen/data/tradition_gb_terrace.hpp"
#include "gen/data/tradition_it_tuscan.hpp"
#include "gen/data/tradition_nl_amsterdam.hpp"
#include "gen/data/tradition_no_timber.hpp"
#include "load/Countries.hpp"
#include "load/Cut.hpp"
#include "load/PackHead.hpp"
#include "load/PackStyle.hpp"
#include "load/Regions.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Json.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/SectionWriter.hpp"
#include "tiles/TileWriter.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <format>
#include <utility>

namespace atlas::load {

namespace {

namespace tl = atlas::tiles;

/// regions.py's metres per degree of latitude, and of longitude at the equator.
constexpr double kz = 110540.0;
constexpr double kx_equator = 111320.0;

constexpr std::array<std::uint8_t, 1> window_levels{window_level};

/// A grid integer's degrees.
[[nodiscard]] double degrees(std::int32_t grid) noexcept { return static_cast<double>(grid) / 1e7; }

/// Java's Math.round(v · 10^4) / 10^4: four decimals, a half rounded up (RegionConfig's round).
[[nodiscard]] double round4(double v) noexcept { return std::floor((v * 1e4) + 0.5) / 1e4; }

/// A double written so that it reads back to itself.
[[nodiscard]] std::string exact(double v) {
    std::array<char, 32> buf{};
    const int n = std::snprintf(buf.data(), buf.size(), "%.17g", v);
    return n > 0 ? std::string(buf.data(), static_cast<std::size_t>(n)) : std::string("0");
}

/// A JSON string's text (the names and zones a window writes are plain ASCII).
[[nodiscard]] std::string quoted(std::string_view s) { return std::format("\"{}\"", s); }

/// The traditions compiled into the core, each read once.
struct Traditions {
    struct Entry {
        std::string_view name;
        std::span<const std::uint8_t> bytes;
        std::shared_ptr<const Tradition> read;
    };
    std::array<Entry, 5> entries = {{
        {.name = "fr_paris", .bytes = data::tradition_fr_paris, .read = nullptr},
        {.name = "gb_terrace", .bytes = data::tradition_gb_terrace, .read = nullptr},
        {.name = "it_tuscan", .bytes = data::tradition_it_tuscan, .read = nullptr},
        {.name = "nl_amsterdam", .bytes = data::tradition_nl_amsterdam, .read = nullptr},
        {.name = "no_timber", .bytes = data::tradition_no_timber, .read = nullptr},
    }};

    Traditions() {
        for (Entry& e : entries) {
            auto t = read_tradition(std::as_bytes(e.bytes));
            if (t) e.read = std::move(*t);
        }
    }
};

} // namespace

std::string window_version() { return std::format("{} {}", stage_p_version, style_version()); }

std::array<double, 4> window_box(const tiles::TileKey& tile, double margin) noexcept {
    const std::array<std::int32_t, 4> b = tl::tile_box(tile);
    const double south = degrees(b[0]);
    const double west = degrees(b[1]);
    const double north = degrees(b[2]);
    const double east = degrees(b[3]);
    // the margin along the parallel farthest from the equator, where a degree of longitude is shortest (in pymath, so
    // every phone asks for the same box)
    const double widest = std::min(89.0, std::max(std::abs(south), std::abs(north)));
    const double dlat = margin / kz;
    const double dlon = margin / (kx_equator * pymath::cos(pymath::radians(widest)));
    return {std::max(-90.0, south - dlat), std::max(-180.0, west - dlon), std::min(90.0, north + dlat),
            std::min(180.0, east + dlon)};
}

tiles::Frame frame_of(const tiles::TileKey& tile) noexcept {
    const auto shift = static_cast<std::uint32_t>(tile.z > frame_cell_level ? tile.z - frame_cell_level : 0);
    const tl::TileKey cell(std::min(tile.z, frame_cell_level), tile.x >> shift, tile.y >> shift);
    const tl::GridPoint c = tl::centre_of(tl::tile_box(cell));
    const double lat0 = round4(degrees(c.lat));
    const double lon0 = round4(degrees(c.lon));
    return {.lat0 = lat0, .lon0 = lon0, .kx = kx_equator * pymath::cos(pymath::radians(lat0)), .kz = kz};
}

WindowPlace place_of(const tiles::TileKey& tile) {
    const tl::GridPoint c = tl::centre_of(tl::tile_box(tile));
    const double lat = degrees(c.lat);
    const double lon = degrees(c.lon);
    WindowPlace p;
    p.name = std::format("{}/{}/{}", tile.z, tile.x, tile.y);
    p.frame = frame_of(tile);
    p.region = "window";
    p.country = country_of(lat, lon);
    // The Netherlands follow the renders' New looks, as the bundled Westerkerk does (the user, 27 October: "the new
    // look is more subtle and more advanced", "We cant drop the tradition completely?"): the Dutch kit no more.
    // Elsewhere the country's tradition still gives a city its materials, which the New looks would paint as
    // Amsterdam's brick.
    p.new_looks = draws_new_looks(p.country);
    if (!p.new_looks) p.tradition = tradition_of(p.country);
    p.zone = timezone_of(p.country, lat, lon);
    p.tz_h = tz_hours(p.zone);
    return p;
}

std::shared_ptr<const Tradition> tradition_named(std::string_view name) {
    static const Traditions traditions;
    for (const Traditions::Entry& e : traditions.entries) {
        if (e.name == name) return e.read;
    }
    return nullptr;
}

std::expected<BuiltWindow, WindowError> build_window(const osm::Document& doc, const tiles::Sha256Digest& source,
                                                     const tiles::TileKey& tile, const WindowPlace& place) {
    using clock = std::chrono::steady_clock;
    const auto ms = [](clock::time_point a, clock::time_point b) {
        return std::chrono::duration<double, std::milli>(b - a).count();
    };
    if (tile.z != window_level) {
        return std::unexpected(WindowError{std::format("a window is a tile of L{}, not of L{}", window_level, tile.z)});
    }
    std::shared_ptr<const Tradition> tradition;
    if (place.tradition) {
        tradition = tradition_named(*place.tradition);
        if (!tradition) return std::unexpected(WindowError{std::format("no tradition {}", *place.tradition)});
    }
    const std::array<double, 4> box = window_box(tile);
    const RegionConfig config{.name = place.region,
                              .frame = place.frame,
                              .bbox = box,
                              .tradition = tradition,
                              .keep_homes = place.new_looks,
                              .palette = region_palette(place.region),
                              .park = place.park,
                              .woods = place.woods};
    BuiltWindow out;
    const auto t0 = clock::now();
    // One scene at a time (ENGINE.md step 3's budget for a window's memory): the attributes of the scene at tolerance
    // 0, then each tolerance of the live map's bands compared with them as its scene comes, then the scene at tolerance
    // 0 again for the cut. The same tiles as cut_window over every scene at once.
    WindowCut steps(place.frame, tile);
    const auto loaded = [&](double tolerance) -> std::expected<Scene, WindowError> {
        auto scene = load_region(doc, config, tolerance);
        if (scene) return std::move(*scene);
        if (tolerance == 0.0) {
            return std::unexpected(WindowError{std::format("the data does not load: {} at element {}",
                                                           name_of(scene.error().fault), scene.error().element)});
        }
        return std::unexpected(WindowError{std::format("the data does not load at {} m", tolerance)});
    };
    {
        auto base = loaded(0.0);
        if (!base) return std::unexpected(base.error());
        if (auto taken = steps.base(*base); !taken) {
            return std::unexpected(WindowError{"the scene does not cut: " + taken.error().what});
        }
    }
    for (const tl::HeadBand& b : region_bands()) {
        if (b.tolerance == 0) continue;
        const double tolerance = tl::keep::tolerances.at(b.tolerance);
        auto other = loaded(tolerance);
        if (!other) return std::unexpected(other.error());
        if (auto compared = steps.compare(*other, tolerance); !compared) {
            return std::unexpected(WindowError{"the scene does not cut: " + compared.error().what});
        }
    }
    auto base = loaded(0.0);
    if (!base) return std::unexpected(base.error());
    const auto t1 = clock::now();
    const auto cut = steps.cut(*base);
    if (!cut) return std::unexpected(WindowError{"the scene does not cut: " + cut.error().what});
    const auto t2 = clock::now();
    std::vector<tl::PackTile> tiles;
    for (const tl::TileFill& t : *cut) {
        auto raw = tl::encode_tile(t);
        if (!raw) return std::unexpected(WindowError{"the tile does not encode: " + tl::describe(raw.error())});
        const auto reach = tl::reach_of(t);
        if (!reach) return std::unexpected(WindowError{"the tile has no reach: " + tl::describe(reach.error())});
        std::uint32_t count = 0;
        for (const tl::LayerFill& l : t.layers()) count += l.count();
        out.features += count;
        tiles.push_back(
            {.key = tl::TileKey(t.level(), t.x(), t.y()), .raw = std::move(*raw), .count = count, .reach = *reach});
    }
    const std::string style_version = load::style_version();
    const std::string data = tl::hex(source);
    const auto head = tl::encode_head({.profile = tl::Profile::region,
                                       .levels = window_levels,
                                       .frame = place.frame,
                                       .region = place.name,
                                       .engine_version = stage_p_version,
                                       .style_version = style_version,
                                       .source = data,
                                       .views = {},
                                       .bands = region_bands()});
    if (!head) return std::unexpected(WindowError{"the head does not encode: " + tl::describe(head.error())});
    // R, as the port's RegionConfig.forBox writes a built region's (without views: a window has none)
    const std::array<std::int32_t, 4> tb = tl::tile_box(tile);
    std::string r =
        std::format(R"({{"bbox": [{}, {}, {}, {}], "origin": [{}, {}], "title": {}, "tz": {})", exact(degrees(tb[0])),
                    exact(degrees(tb[1])), exact(degrees(tb[2])), exact(degrees(tb[3])), exact(place.frame.lat0),
                    exact(place.frame.lon0), quoted(place.name), quoted(place.zone));
    if (place.tradition) r += ", \"tradition\": " + quoted(*place.tradition);
    if (place.country) r += ", \"country\": " + quoted(*place.country);
    r += "}";
    const auto r_value = tl::read_json(std::as_bytes(std::span(r.data(), r.size())));
    if (!r_value) return std::unexpected(WindowError{"the region's R does not read"});
    const SceneScalars scalars{
        .region = place.name, .view = place.name, .tradition = place.tradition, .r = r_value->root()};
    const auto style = style_section({.palette = region_palette(place.region),
                                      .state = region_state(place.region, place.tz_h),
                                      .scene = &scalars,
                                      .tradition = tradition.get()});
    if (!style) return std::unexpected(WindowError{"the engine's style file does not read"});
    // the manifest the app reads a render pack by (PackList's readHead: its box, origin, profile, time zone, views),
    // with its tiles' count (a window without features holds none: the app does not list it) and the versions it was
    // built with, under the packer's names for them
    const std::string manifest = std::format(
        R"({{"layout":"render-tiles","payload":{},"profile":"region","bbox":{{"west":{},"south":{},"east":{},"north":{}}},)"
        R"("origin":{{"lat":{},"lon":{}}},"tz":{},"config":{{"views":{{}}}},"chunks":{},"engine_version":{},)"
        R"("style_version":{},"builder":"core window","window":{},"source":"{}"}})",
        tl::payload_version, exact(degrees(tb[1])), exact(degrees(tb[0])), exact(degrees(tb[3])), exact(degrees(tb[2])),
        exact(place.frame.lat0), exact(place.frame.lon0), quoted(place.zone), tiles.size(), quoted(stage_p_version),
        quoted(style_version), quoted(place.name), data);
    auto pack = tl::write_pack(tiles, {.levels = window_levels, .manifest = manifest, .head = *head, .style = *style});
    if (!pack) return std::unexpected(WindowError{"the pack does not write: " + tl::describe(pack.error())});
    out.pack = std::move(*pack);
    const auto t3 = clock::now();
    out.load_ms = ms(t0, t1);
    out.cut_ms = ms(t1, t2);
    out.write_ms = ms(t2, t3);
    return out;
}

std::expected<BuiltWindow, WindowError> build_window(std::span<const std::byte> json, const tiles::TileKey& tile,
                                                     const WindowPlace& place) {
    const auto doc = osm::read_overpass(json);
    if (!doc) {
        return std::unexpected(WindowError{
            std::format("the data does not read: {} at byte {}", osm::name_of(doc.error().fault), doc.error().at)});
    }
    return build_window(*doc, tl::Sha256::of(json), tile, place);
}

} // namespace atlas::load
