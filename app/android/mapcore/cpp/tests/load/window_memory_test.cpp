// What building a window takes in memory (ENGINE.md step 3's budget: about 50 MB on a phone). Each window's data is
// Overpass's JSON of its query, cut from a region's raw.json element by element (the canal ring around the Westerkerk,
// the Louvre's window in Paris: ATLAS_REGIONS_DIR), and built as the phone builds it, anywhere's way (its country's
// tradition, its frame cell's frame). The heap's growth over the build is counted from the allocator's own hooks,
// which the AddressSanitizer builds have (host-debug): the most the build ever held at once beyond what was held
// before it, its data's bytes apart.
#include "load/Cut.hpp"
#include "load/Regions.hpp"
#include "load/Scene.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "tests/base/require.hpp"
#include "tests/load/query.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Grid.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <atomic>
#include <filesystem>
#include <format>
#include <fstream>
#include <iterator>
#include <string>
#include <tuple>
#include <vector>

#if defined(__has_feature)
#if __has_feature(address_sanitizer)
#define ATLAS_HEAP_HOOKS 1
#endif
#endif
#if defined(__SANITIZE_ADDRESS__) && !defined(ATLAS_HEAP_HOOKS)
#define ATLAS_HEAP_HOOKS 1
#endif

#if defined(ATLAS_HEAP_HOOKS)
#include <sanitizer/allocator_interface.h>
#endif

namespace {

#if defined(ATLAS_HEAP_HOOKS)
namespace osm = atlas::osm;
namespace tl = atlas::tiles;
namespace load = atlas::load;

// The heap's growth while counting: every allocation adds its size, every free takes its block's size away.
struct Heap {
    std::atomic<bool> counting{false};
    std::atomic<std::int64_t> live{0};
    std::atomic<std::int64_t> peak{0};
};

/// The process's counters, made on first use.
Heap& heap() {
    static Heap counters;
    return counters;
}

void on_malloc(const volatile void* /*block*/, std::size_t size) {
    if (!heap().counting.load(std::memory_order_relaxed)) return;
    const std::int64_t now = heap().live.fetch_add(static_cast<std::int64_t>(size)) + static_cast<std::int64_t>(size);
    std::int64_t was = heap().peak.load();
    while (now > was && !heap().peak.compare_exchange_weak(was, now)) {
    }
}

void on_free(const volatile void* block) {
    if (block == nullptr || !heap().counting.load(std::memory_order_relaxed)) return;
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-const-cast): the hook's block, as the allocator's own query takes it
    const auto size = __sanitizer_get_allocated_size(const_cast<const void*>(block));
    heap().live.fetch_sub(static_cast<std::int64_t>(size));
}

/// Whether the hooks are in, installed once for the process (each install adds a pair, and each pair counts).
bool hooked() {
    static const bool in = __sanitizer_install_malloc_and_free_hooks(on_malloc, on_free) != 0;
    return in;
}

/// The most the heap grew by at once while `f` ran.
template <typename F>
std::int64_t peak_growth(F&& f) {
    if (!hooked()) return -1;
    heap().live = 0;
    heap().peak = 0;
    heap().counting = true;
    std::forward<F>(f)();
    heap().counting = false;
    return heap().peak.load();
}

std::string read_text(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

/// The JSON Overpass answers window `tile`'s query with: the elements the query returns from `region` (its text
/// `text`), each as the region's answer printed it, in their order.
std::string window_json(const std::string& text, const osm::Document& region, const tl::TileKey& tile) {
    const osm::Document mine = atlas::test::query(region, load::window_box(tile));
    std::string out =
        std::format("{{\n  \"version\": 0.6,\n  \"osm3s\": {{\n    \"timestamp_osm_base\": \"{}\"\n  }},\n"
                    "  \"elements\": [\n\n",
                    region.osm_base());
    bool first = true;
    for (const osm::Element& e : mine.elements()) {
        if (!first) out += ",\n";
        first = false;
        out.append(text, e.from, e.to - e.from);
    }
    return out + "\n\n  ]\n}\n";
}

struct Measured {
    std::string name;
    std::size_t json = 0;
    std::uint32_t features = 0;
    std::int64_t peak = 0;
    double ms = 0;
};
#endif

} // namespace

TEST_CASE("load: what building a window takes in memory (ATLAS_REGIONS_DIR, AddressSanitizer builds)") {
    const std::string regions = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (regions.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: no window's memory is measured here");
        return;
    }
#if !defined(ATLAS_HEAP_HOOKS)
    MESSAGE("not an AddressSanitizer build: the heap's hooks are not there, no window's memory is measured here");
#else
    // the windows' data first, the regions' documents gone before anything is measured
    std::vector<std::pair<std::string, tl::TileKey>> names;
    std::vector<std::string> jsons;
    {
        const std::string text = read_text(std::filesystem::path(regions) / "grachten" / "raw.json");
        ATLAS_REQUIRE(!text.empty());
        const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
        ATLAS_REQUIRE(doc.has_value());
        const tl::TileKey a = tl::tile_of(14, 523'746'000, 48'840'000); // the Westerkerk's
        for (const int dy : {-1, 0, 1}) {
            for (const int dx : {-1, 0, 1}) {
                const tl::TileKey k(14, static_cast<std::uint32_t>(static_cast<int>(a.x) + dx),
                                    static_cast<std::uint32_t>(static_cast<int>(a.y) + dy));
                names.emplace_back(std::format("canal ring 14/{}/{}", k.x, k.y), k);
                jsons.push_back(window_json(text, *doc, k));
            }
        }
    }
    {
        const std::string text = read_text(std::filesystem::path(regions) / "paris" / "raw.json");
        if (!text.empty()) {
            const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
            ATLAS_REQUIRE(doc.has_value());
            const tl::TileKey louvre = tl::tile_of(14, 488'610'000, 23'360'000);
            names.emplace_back(std::format("Paris 14/{}/{}", louvre.x, louvre.y), louvre);
            jsons.push_back(window_json(text, *doc, louvre));
        }
    }
    std::vector<Measured> measured;
    for (std::size_t i = 0; i < jsons.size(); ++i) {
        const std::string& json = jsons[i];
        const tl::TileKey tile = names[i].second;
        const load::WindowPlace place = load::place_of(tile);
        std::uint32_t features = 0;
        double ms = 0;
        const std::int64_t peak = peak_growth([&] {
            const auto built = load::build_window(std::as_bytes(std::span(json.data(), json.size())), tile, place);
            if (built) {
                features = built->features;
                ms = built->load_ms + built->cut_ms + built->write_ms;
            }
        });
        ATLAS_REQUIRE(peak >= 0, "the allocator's hooks could not be installed");
        CHECK_MESSAGE(features > 0, names[i].first, " built nothing");
        measured.push_back({.name = names[i].first, .json = json.size(), .features = features, .peak = peak, .ms = ms});
    }
    std::int64_t most = 0;
    for (const Measured& m : measured) {
        MESSAGE(m.name, ": its data ", m.json / 1000, " KB, ", m.features, " features; the build held at most ",
                m.peak / 1'000'000, " MB more (", m.ms, " ms under ASan)");
        most = std::max(most, m.peak);
    }
    MESSAGE("the most a window's build held: ", most / 1'000'000, " MB (ENGINE.md's budget: about 50 MB)");
#endif
}

TEST_CASE("load: where a dense window's memory goes, step by step (ATLAS_REGIONS_DIR, AddressSanitizer builds)") {
    const std::string regions = atlas::test::environment("ATLAS_REGIONS_DIR");
    if (regions.empty()) {
        MESSAGE("ATLAS_REGIONS_DIR is not set: no window's memory is measured here");
        return;
    }
#if !defined(ATLAS_HEAP_HOOKS)
    MESSAGE("not an AddressSanitizer build: the heap's hooks are not there, no window's memory is measured here");
#else
    std::string json;
    const tl::TileKey tile(14, 8414, 5384); // the canal ring's densest
    {
        const std::string text = read_text(std::filesystem::path(regions) / "grachten" / "raw.json");
        ATLAS_REQUIRE(!text.empty());
        const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
        ATLAS_REQUIRE(doc.has_value());
        json = window_json(text, *doc, tile);
    }
    const load::WindowPlace place = load::place_of(tile);
    // each step: what is held after it, and the most held during it
    std::vector<std::tuple<std::string, std::int64_t, std::int64_t>> steps;
    std::int64_t before = 0;
    const std::int64_t peak = peak_growth([&] {
        const auto mark = [&](std::string name) {
            steps.emplace_back(std::move(name), heap().live.load(), heap().peak.load());
            heap().peak = heap().live.load();
        };
        before = heap().live.load();
        const auto doc = osm::read_overpass(std::as_bytes(std::span(json.data(), json.size())));
        if (!doc) return;
        mark("the document read");
        const load::RegionConfig config{.name = place.region,
                                        .frame = place.frame,
                                        .bbox = load::window_box(tile),
                                        .tradition =
                                            place.tradition ? load::tradition_named(*place.tradition) : nullptr,
                                        .keep_homes = place.new_looks,
                                        .close_up = place.close_up,
                                        .palette = load::region_palette(place.region)};
        load::WindowCut cut(place.frame, tile);
        {
            auto base = load::load_region(*doc, config);
            if (!base) return;
            mark("the scene at 0 m loaded");
            if (!cut.base(*base)) return;
            mark("its attributes taken");
        }
        mark("the scene at 0 m gone");
        for (const double t : {0.15, 0.3, 0.6, 0.8}) {
            auto other = load::load_region(*doc, config, t);
            if (!other) return;
            mark(std::format("the scene at {} m loaded", t));
            if (!cut.compare(*other, t)) return;
        }
        auto base = load::load_region(*doc, config);
        if (!base) return;
        mark("the scene at 0 m loaded again");
        const auto tiles = cut.cut(*base);
        if (!tiles) return;
        mark("the tile cut");
    });
    for (const auto& [name, held, most] : steps) {
        MESSAGE(name, ": ", held / 1'000'000, " MB held after, ", most / 1'000'000, " MB at most during");
    }
    MESSAGE("at most ", peak / 1'000'000, " MB; the data ", json.size() / 1'000'000, " MB");
#endif
}
