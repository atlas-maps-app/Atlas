// A national park's edge longer than an instance word's 16,384 segments on the host's Vulkan (passes/Lines.cpp's
// write_lines, passes/LinePieces.hpp): stage P files the edge whole in the window of its middle point, and the ground
// draws it in pieces, a run each, where it left it out before (the twin drew it whole). A park whose edge is a circle
// of 20,001 points (300 m about the window's middle, a point every 9.4 cm) draws close up on both sides of the cut at
// its 16,384th segment in the app's map, the validation layer silent; drawn for the renders, the frame's dashes there
// are the twin's path's, sample for sample away from their ends, and the frame draws nothing beyond the twin's stroke;
// and a park of 1,001 points drawn in runs of 97 segments (Ground::cut_lines_at) gives the same bytes as drawn whole.
#if !defined(__ANDROID__)

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "geometry/Ground.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "load/Window.hpp"
#include "passes/Ground.hpp"
#include "passes/LinePieces.hpp"
#include "scene/Cover.hpp"
#include "scene/World.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

using namespace atlas;
namespace pm = atlas::pymath;

namespace {

const tiles::TileKey window_tile = tiles::tile_of(14, 523'746'000, 48'840'000);
constexpr double kz = 110540.0; // the frame's metres a degree of latitude, as the tests' cameras take them

/// The window's tile's middle (lat, lon).
[[nodiscard]] std::array<double, 2> middle() {
    const std::array<std::int32_t, 4> b = tiles::tile_box(window_tile);
    return {(b[0] + b[2]) / 2e7, (b[1] + b[3]) / 2e7};
}

[[nodiscard]] double kx() { return 111320.0 * pm::cos(pm::radians(middle()[0])); }

/// A park's edge: a circle of `points` points `radius` m about the tile's middle, point i at i / points of a turn
/// counterclockwise from the east, on the data's grid (seven decimals); the way closes on its first point.
struct Ring {
    std::uint32_t points = 0;
    double radius = 0;

    [[nodiscard]] std::array<double, 2> at(std::uint32_t i) const {
        const double a = 2.0 * pm::pi * static_cast<double>(i) / static_cast<double>(points);
        const std::array<double, 2> m = middle();
        return {m[0] + (radius * pm::sin(a) / kz), m[1] + (radius * pm::cos(a) / kx())};
    }
};

/// The window of a park with edge `ring`, its pack written to the tests' scratch folder (`name`'s folder).
[[nodiscard]] std::string park_window(const Ring& ring, const std::string& name) {
    std::string data =
        R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [)"
        R"({"type": "way", "id": 70, "tags": {"boundary": "national_park", "name": "Ring"}, "geometry": [)";
    for (std::uint32_t i = 0; i <= ring.points; ++i) {
        const std::array<double, 2> p = ring.at(i % ring.points);
        data += std::format(R"({}{{"lat": {:.7f}, "lon": {:.7f}}})", i == 0 ? "" : ", ", p[0], p[1]);
    }
    data += "]}]}";
    const auto built = load::build_window(std::as_bytes(std::span(data.data(), data.size())), window_tile,
                                          load::place_of(window_tile));
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / name;
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// The points of the window's park's edge, on the CPU as stage T builds it: its one polyline's (0 where there is
/// none, or more than one).
[[nodiscard]] std::uint32_t edge_points(const std::string& path) {
    auto pack = test::open_pack(path);
    ATLAS_REQUIRE_OR_RETURN(0, pack.has_value(), tiles::describe(pack.error()));
    const tiles::RenderHead& head = *pack->head();
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(8U << 20U, Growth::grows, "park edge test");
    const auto chunks = pack->chunks().level(head.levels.front());
    ATLAS_REQUIRE_OR_RETURN(0, chunks.size() == 1);
    auto tile = pack->decode(chunks.front(), zstd, decoder, arena, raw, scratch);
    ATLAS_REQUIRE_OR_RETURN(0, tile.has_value(), tiles::describe(tile.error()));
    const geometry::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
    const geometry::GroundTile g = geometry::build_ground(*tile, head, *pack->style(), 0, origin);
    const geometry::Stroke* edge = nullptr;
    for (const geometry::Stroke& s : g.strokes) {
        if (s.kind != geometry::StrokeKind::park) continue;
        if (edge != nullptr || s.count != 1) return 0;
        edge = &s;
    }
    return edge != nullptr ? g.polylines[edge->first].count : 0;
}

/// A frame of the park drawn by the ground alone: its pixels as the image holds them and in RGBA8, the runs of its
/// lines, the pair polylines left out, and the twin's edge (its subpaths in device px, the stroke's width there).
struct EdgeDraw {
    gpu::Packed packed;
    gpu::Pixels pixels;
    std::uint32_t runs = 0;
    std::uint32_t left_out = 0;
    std::vector<std::vector<std::array<double, 2>>> twin;
    double width = 0;
    std::vector<std::string> messages;
};

/// The park's window drawn at `camera` (in the app's map where `grounded`), its lines in runs of at most `most`
/// segments, until its tile is built and faded in, then once more.
std::optional<EdgeDraw> draw_edge(const test::Session& session, const std::string& path,
                                  const style::CameraState& camera, bool grounded, std::uint32_t most) {
    const gpu::Device& device = *session.device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    if (!cache) return std::nullopt;
    constexpr std::array<std::string_view, 3> ground_only{"composite", "frosted", "ground"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(ground_only);
    passes::Ground* g = test::ground_of(passes);
    if (g == nullptr) return std::nullopt;
    g->cut_lines_at(most);
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes),
                                               {.steps = 0, .grain = false, .grounded = grounded});
    if (!builder) return std::nullopt;
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(),
                                      {.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }});
    if (!world) return std::nullopt;
    const std::string id = "window/" + load::place_of(window_tile).name;
    if (!(*world)->store().add({.id = id, .kind = tiles::PackKind::window, .path = path})) return std::nullopt;
    auto output = gpu::Offscreen::create(device, {.width = camera.width_px, .height = camera.height_px}, 2);
    if (!output) return std::nullopt;
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **output, **builder, config);
    if (!renderer) return std::nullopt;
    bool settled = false;
    for (int f = 0; f < 61 && !settled; ++f) {
        now = static_cast<double>(f) * 0.25;
        (*builder)->set_inputs({.camera = camera,
                                .theme = test::sunny(),
                                .seconds = now,
                                .mode = frame::Mode::structural,
                                .scene = world->get()});
        if (!(*renderer)->draw(0)) return std::nullopt;
        (*renderer)->drain();
        (*world)->residency().wait_idle();
        const scene::Cover& cover = *(*world)->cover();
        settled = f > 4 && cover.missing() == 0 && !cover.tiles().empty() &&
                  std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) { return t.fade >= 1.0F; });
    }
    if (!settled) return std::nullopt;
    if (!(*renderer)->draw(0)) return std::nullopt;
    (*renderer)->drain();
    auto packed = (*output)->read_packed((*output)->latest());
    if (!packed) return std::nullopt;
    EdgeDraw d;
    d.pixels = gpu::to_rgba8(*packed);
    d.packed = std::move(*packed);
    d.runs = g->line_runs();
    d.left_out = g->lines_left_out();
    const frame::FrameState& state = (*builder)->state();
    twin::Stream s;
    g->emit(state, s, passes::Ground::phase_opaque);
    const twin::Decoded decoded = twin::decode(s.bytes());
    REQUIRE(decoded.error.empty());
    const auto density = static_cast<double>(state.block.camera.frame.z);
    for (const twin::Element& e : decoded.document.elements) {
        if (e.layer != twin::Layer::ground || e.kind != twin::Op::path || e.dashes.empty()) continue;
        std::size_t start = 0;
        for (const std::uint32_t end : e.subpath_ends) {
            std::vector<std::array<double, 2>> p;
            for (std::size_t k = start; k < end; ++k) {
                p.push_back(
                    {static_cast<double>(e.points[k].x) * density, static_cast<double>(e.points[k].y) * density});
            }
            d.twin.push_back(std::move(p));
            start = end;
        }
        d.width = static_cast<double>(e.style.stroke_width) * density;
    }
    d.messages = session.take_messages();
    renderer->reset();
    return d;
}

/// Whether pixel (x, y) of `a` differs from `b`'s by more than `by` levels in a channel.
[[nodiscard]] bool differs(const gpu::Pixels& a, const gpu::Pixels& b, std::uint32_t x, std::uint32_t y, int by) {
    const std::size_t i = 4 * ((static_cast<std::size_t>(y) * a.width) + x);
    for (std::size_t c = 0; c < 4; ++c) {
        if (std::abs(static_cast<int>(a.rgba[i + c]) - static_cast<int>(b.rgba[i + c])) > by) return true;
    }
    return false;
}

/// A sample along the twin's edge: its device px, the polyline's segment it lies on, and whether the twin's dashes (7
/// on 5 off in CSS px, from the subpath's start) draw it (in a dash) or leave it (in a gap), 1.5 CSS px or more from
/// a dash's ends; on the frame with a margin of 4 px.
struct Sample {
    std::array<double, 2> at{};
    std::uint32_t segment = 0;
    bool dash = false;
};

/// The twin's samples along its one subpath, every 0.5 CSS px, in its dashes' middles and its gaps' (`density` device
/// px a CSS px).
[[nodiscard]] std::vector<Sample> samples_of(const std::vector<std::array<double, 2>>& path, double density,
                                             std::uint32_t width, std::uint32_t height) {
    std::vector<Sample> out;
    double along = 0; // CSS px
    for (std::size_t k = 0; k + 1 < path.size(); ++k) {
        const std::array<double, 2>& a = path[k];
        const std::array<double, 2>& b = path[k + 1];
        const double len = pm::hypot(b[0] - a[0], b[1] - a[1]) / density;
        // the samples at every 0.5 CSS px of the path that lie along this segment
        for (auto i = static_cast<std::int64_t>(std::ceil(along / 0.5)); (0.5 * static_cast<double>(i)) - along < len;
             ++i) {
            const double t = (0.5 * static_cast<double>(i)) - along;
            const double phase = std::fmod(along + t, 12.0);
            const bool dash = phase >= 1.5 && phase <= 5.5;
            const bool gap = phase >= 8.5 && phase <= 10.5;
            if (!dash && !gap) continue;
            const double f = len > 0 ? t / len : 0;
            const std::array<double, 2> p{a[0] + ((b[0] - a[0]) * f), a[1] + ((b[1] - a[1]) * f)};
            if (p[0] < 4 || p[1] < 4 || p[0] > width - 4.0 || p[1] > height - 4.0) continue;
            out.push_back({.at = p, .segment = static_cast<std::uint32_t>(k), .dash = dash});
        }
        along += len;
    }
    return out;
}

/// The distance from (x, y) to the twin's subpaths, at most `reach` (beyond it: `reach`).
[[nodiscard]] double distance_to(const std::vector<std::vector<std::array<double, 2>>>& paths, double x, double y,
                                 double reach) {
    double least = reach;
    for (const auto& p : paths) {
        for (std::size_t k = 0; k + 1 < p.size(); ++k) {
            const std::array<double, 2>& a = p[k];
            const std::array<double, 2>& b = p[k + 1];
            if (std::max(a[0], b[0]) < x - least || std::min(a[0], b[0]) > x + least ||
                std::max(a[1], b[1]) < y - least || std::min(a[1], b[1]) > y + least) {
                continue;
            }
            const double dx = b[0] - a[0];
            const double dy = b[1] - a[1];
            const double l2 = (dx * dx) + (dy * dy);
            const double t = l2 == 0 ? 0 : std::clamp((((x - a[0]) * dx) + ((y - a[1]) * dy)) / l2, 0.0, 1.0);
            least = std::min(least, pm::hypot(a[0] + (t * dx) - x, a[1] + (t * dy) - y));
        }
    }
    return least;
}

/// The camera straight down from `distance` m over point `i` of `ring`, at rest; in engine mode where `engine`.
[[nodiscard]] style::CameraState over(const Ring& ring, std::uint32_t i, double distance, bool engine) {
    const std::array<double, 2> p = ring.at(i);
    style::CameraState c = test::s1_camera(p[0], p[1], 0.0, 0.0, distance);
    if (!engine) c.flags &= static_cast<std::uint32_t>(~style::camera_flag::engine_mode);
    return c;
}

/// `c` with the ground's lines left out (camera_part::ground_lines): the frame the edge is drawn over.
[[nodiscard]] style::CameraState without_lines(style::CameraState c) {
    c.parts |= style::camera_part::ground_lines;
    return c;
}

const Ring long_ring{.points = 20'000, .radius = 300.0};

} // namespace

TEST_CASE("ground: a park's edge of 20,001 points is filed whole in one window, more segments than a run holds") {
    const std::string path = park_window(long_ring, "park-edge-long");
    ATLAS_REQUIRE(!path.empty());
    const std::uint32_t points = edge_points(path);
    // every point of the way, its first again at its end: 20,000 segments, two runs' pieces
    CHECK(points == long_ring.points + 1);
    CHECK(points - 1 > passes::run_segments_most);
    CHECK(passes::line_pieces(points - 1).size() == 2);
}

TEST_CASE("ground: in the app's map a park's edge past a run's segments draws close up on both sides of its cut") {
    const std::string path = park_window(long_ring, "park-edge-app");
    ATLAS_REQUIRE(!path.empty());
    ATLAS_REQUIRE(edge_points(path) == long_ring.points + 1);
    auto session = test::open_session();
    if (!session) return;
    // 60 m straight down over the cut, the edge's 16,384th point: some 400 of its segments on the frame
    const style::CameraState camera = over(long_ring, passes::run_segments_most, 60.0, false);
    const auto drawn = draw_edge(*session, path, camera, true, passes::run_segments_most);
    ATLAS_REQUIRE(drawn.has_value(), "the frame draws");
    const auto ground = draw_edge(*session, path, without_lines(camera), true, passes::run_segments_most);
    ATLAS_REQUIRE(ground.has_value(), "the frame without the lines draws");
    for (const std::string& m : drawn->messages) CHECK_MESSAGE(false, m);
    for (const std::string& m : ground->messages) CHECK_MESSAGE(false, m);
    // the edge in two pieces (before: none, left out whole), no pair polyline left out
    CHECK(drawn->runs == 2);
    CHECK(ground->runs == 0);
    CHECK(drawn->left_out == 0);
    // the twin's edge: one subpath of all its points, all of them ahead of the near plane
    ATLAS_REQUIRE(drawn->twin.size() == 1);
    ATLAS_REQUIRE(drawn->twin.front().size() == long_ring.points + 1);
    const double density = camera.density;
    const std::vector<Sample> samples =
        samples_of(drawn->twin.front(), density, drawn->pixels.width, drawn->pixels.height);
    // its dashes' middles drawn on either side of the cut
    std::array<std::size_t, 2> dashes{};
    std::array<std::size_t, 2> drawn_dashes{};
    for (const Sample& s : samples) {
        if (!s.dash) continue;
        const std::size_t side = s.segment < passes::run_segments_most ? 0 : 1;
        ++dashes.at(side);
        const auto x = static_cast<std::uint32_t>(s.at[0]);
        const auto y = static_cast<std::uint32_t>(s.at[1]);
        if (differs(drawn->pixels, ground->pixels, x, y, 8)) ++drawn_dashes.at(side);
    }
    MESSAGE(std::format("dashes' samples before the cut {} ({} drawn), after it {} ({} drawn)", dashes[0],
                        drawn_dashes[0], dashes[1], drawn_dashes[1]));
    for (std::size_t side = 0; side < 2; ++side) {
        CHECK(dashes.at(side) > 100);
        CHECK(drawn_dashes.at(side) == dashes.at(side));
    }
}

TEST_CASE(
    "ground: a park's edge past a run's segments, drawn for the renders, keeps the twin's dashes across its cut") {
    const std::string path = park_window(long_ring, "park-edge-twin");
    ATLAS_REQUIRE(!path.empty());
    ATLAS_REQUIRE(edge_points(path) == long_ring.points + 1);
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = over(long_ring, passes::run_segments_most, 60.0, true);
    const auto drawn = draw_edge(*session, path, camera, false, passes::run_segments_most);
    ATLAS_REQUIRE(drawn.has_value(), "the frame draws");
    const auto ground = draw_edge(*session, path, without_lines(camera), false, passes::run_segments_most);
    ATLAS_REQUIRE(ground.has_value(), "the frame without the lines draws");
    for (const std::string& m : drawn->messages) CHECK_MESSAGE(false, m);
    CHECK(drawn->runs == 2);
    ATLAS_REQUIRE(drawn->twin.size() == 1);
    ATLAS_REQUIRE(drawn->twin.front().size() == long_ring.points + 1);
    const gpu::Pixels& px = drawn->pixels;
    // every dash's middle of the twin's drawn, every gap's middle left as the ground is, on both sides of the cut
    std::array<std::size_t, 2> seen{};
    std::size_t wrong = 0;
    for (const Sample& s : samples_of(drawn->twin.front(), camera.density, px.width, px.height)) {
        const auto x = static_cast<std::uint32_t>(s.at[0]);
        const auto y = static_cast<std::uint32_t>(s.at[1]);
        const bool changed = differs(px, ground->pixels, x, y, s.dash ? 8 : 2);
        ++seen.at(s.segment < passes::run_segments_most ? 0 : 1);
        if (changed != s.dash) {
            if (++wrong <= 10) {
                MESSAGE(std::format("at ({:.1f}, {:.1f}), segment {}: the twin {}, the frame {}", s.at[0], s.at[1],
                                    s.segment, s.dash ? "draws" : "leaves it", changed ? "draws" : "leaves it"));
            }
        }
    }
    CHECK(seen[0] > 150);
    CHECK(seen[1] > 150);
    CHECK(wrong == 0);
    // and nothing beyond the twin's stroke: every pixel drawn within its half width and a pixel's reach of the path
    const double reach = (drawn->width / 2) + 1.5;
    std::size_t beyond = 0;
    std::size_t changed = 0;
    for (std::uint32_t y = 0; y < px.height; ++y) {
        for (std::uint32_t x = 0; x < px.width; ++x) {
            if (!differs(px, ground->pixels, x, y, 0)) continue;
            ++changed;
            if (distance_to(drawn->twin, x + 0.5, y + 0.5, reach + 1) > reach) ++beyond;
        }
    }
    MESSAGE(std::format("{} pixels drawn, {} beyond the twin's stroke", changed, beyond));
    CHECK(changed > 0);
    CHECK(beyond == 0);
}

TEST_CASE("ground: a park's edge of 1,001 points drawn in runs of 97 segments gives the same bytes as drawn whole") {
    const Ring ring{.points = 1'000, .radius = 40.0};
    const std::string path = park_window(ring, "park-edge-cut");
    ATLAS_REQUIRE(!path.empty());
    ATLAS_REQUIRE(edge_points(path) == ring.points + 1);
    auto session = test::open_session();
    if (!session) return;
    // the app's map, 350 m straight down over the middle: the whole ring on the frame (a CSS px a segment), its ten
    // cuts with it
    style::CameraState camera = test::s1_camera(middle()[0], middle()[1], 0.0, 0.0, 350.0);
    camera.flags &= static_cast<std::uint32_t>(~style::camera_flag::engine_mode);
    const auto whole = draw_edge(*session, path, camera, true, passes::run_segments_most);
    ATLAS_REQUIRE(whole.has_value(), "the frame draws");
    const auto cut = draw_edge(*session, path, camera, true, 97);
    ATLAS_REQUIRE(cut.has_value(), "the frame in pieces draws");
    for (const std::string& m : cut->messages) CHECK_MESSAGE(false, m);
    CHECK(whole->runs == 1);
    CHECK(cut->runs == passes::line_pieces(ring.points, 97).size());
    CHECK(cut->runs == 11);
    CHECK(whole->packed.format == cut->packed.format);
    CHECK(whole->packed.words == cut->packed.words);
    // the edge is on the frame
    const auto ground = draw_edge(*session, path, without_lines(camera), true, passes::run_segments_most);
    ATLAS_REQUIRE(ground.has_value(), "the frame without the lines draws");
    CHECK(whole->pixels != ground->pixels);
}

#endif
