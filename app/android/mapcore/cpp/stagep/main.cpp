// The packer's tool (ENGINE.md step 3: the packer runs the core's stage P): a region's or the living map's render
// tiles from Overpass's JSON, loaded and cut by the core as the phone builds a window, and its pack's head and style,
// for `packer render-tiles` to write into its pack (rendertiles' CoreTiles).
//
//   atlas_stagep tiles <raw.json> <job.json> <out>
//
// The job says what the packer knows of the pack: its profile (`living` or `region`), its name (its palette and
// rules), the levels it holds, its frame's four doubles (lat0, lon0, kx, kz) as their IEEE-754 bits in hex (the port's
// own doubles: kx from CrMath.cos), the port's engine version, the element pack's SHA-256, and for a region its
// region.json as the packer unpacked it into the region's folder (its box, tradition, time zone and views, read as
// Python's json reads them). The core takes the rest from there (load/PackHead.hpp): the views and the tolerances the
// variants load at (the views' and the live map's bands'), the head and the style.
//
// `out` gets, by a file beside it renamed once whole, little-endian: `ATSP` and the format (2), the tiles' count, then
// per tile in the cut's order its level (u8), column and row (u32), its features (u32), its reach (south, west, north,
// east and top in cm, i32), its layers that hold features (u16), each its label (u8 length, its bytes) and its features
// (u32), and its encoded bytes (u32 length, the bytes); then the head's bytes and the style's before compression, each
// a u32 length and the bytes. One line on stdout says what was cut and how long it took. The exit code: 0 done; 2 a
// usage or input error; 3 a region the tiles cannot carry yet (the packer's render_gap, why on stderr: a theme park's
// coaster tracks or a park's or zoo's planted woods (R's park or zoo), a survey's heights (a dem.json in the region's
// folder), gantries or conveyors, a tradition's details); 1 any other failure.
#include "load/Amsterdam.hpp"
#include "load/Cut.hpp"
#include "load/PackHead.hpp"
#include "load/Regions.hpp"
#include "load/Scene.hpp"
#include "load/Window.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Error.hpp"
#include "tiles/Json.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileWriter.hpp"
#include "tiles/Values.hpp"

#include <array>
#include <bit>
#include <charconv>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <expected>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <system_error>
#include <utility>
#include <vector>

namespace {

namespace load = atlas::load;
namespace osm = atlas::osm;
namespace tl = atlas::tiles;

/// Why the tool stops: its exit code and the reason it gives.
struct Failure {
    int code = 1;
    std::string why;
};

constexpr int usage_error = 2;
constexpr int not_carried = 3;
constexpr std::uint32_t format_version = 2;

[[nodiscard]] Failure input(std::string why) { return {.code = usage_error, .why = std::move(why)}; }

/// A file's bytes, mapped.
[[nodiscard]] std::expected<std::unique_ptr<tl::MappedSource>, Failure> mapped(const std::string& path) {
    auto source = tl::MappedSource::open(path);
    if (!source) return std::unexpected(input(std::format("{} does not open: {}", path, tl::describe(source.error()))));
    return std::move(*source);
}

/// A double from the hex digits of its IEEE-754 bits.
[[nodiscard]] std::optional<double> from_bits(std::string_view hex) {
    std::uint64_t bits = 0;
    const auto [at, error] = std::from_chars(hex.data(), hex.data() + hex.size(), bits, 16);
    if (hex.empty() || error != std::errc{} || at != hex.data() + hex.size()) return std::nullopt;
    return std::bit_cast<double>(bits);
}

/// The ints of a JSON list, or nothing where it is not one of ints within [low, high].
[[nodiscard]] std::optional<std::vector<std::int64_t>> ints(const tl::Value& list, std::int64_t low,
                                                            std::int64_t high) {
    if (list.type() != tl::ValueType::list) return std::nullopt;
    std::vector<std::int64_t> out;
    for (std::size_t i = 0; i < list.size(); ++i) {
        const std::optional<std::int64_t> v = list.child(i).integer();
        if (!v || *v < low || *v > high) return std::nullopt;
        out.push_back(*v);
    }
    return out;
}

/// Python's truth of a JSON value: false for null, false, 0, and an empty string, list or map.
[[nodiscard]] bool truthy(const tl::Value& v) noexcept {
    switch (v.type()) {
    case tl::ValueType::null:
    case tl::ValueType::boolean_false: return false;
    case tl::ValueType::boolean_true: return true;
    case tl::ValueType::int32:
    case tl::ValueType::int64: return v.integer().value_or(0) != 0;
    case tl::ValueType::real: return v.real().value_or(0.0) != 0.0;
    case tl::ValueType::string: return !v.string().value_or("").empty();
    case tl::ValueType::list:
    case tl::ValueType::map: return v.size() > 0;
    }
    return false;
}

/// What a region holds that render tiles cannot carry yet, refused as the packer's SceneToTiles refuses its scene:
/// the coaster tracks a theme park (R's park) loads and the woods it and a zoo (R's zoo) plant, and a survey's heights,
/// which regions.py reads from a dem.json in the region's folder. Nothing where it holds none of them.
[[nodiscard]] std::optional<Failure> uncarried(std::string_view name, const tl::Value& r,
                                               const std::filesystem::path& folder) {
    const auto has = [&](std::string_view key) {
        const std::optional<tl::Value> v = r.find(key);
        return v && truthy(*v);
    };
    std::vector<std::string_view> found;
    if (has("park")) found.emplace_back("a theme park's coaster tracks");
    if (has("park") || has("zoo")) found.emplace_back("a park's planted woods");
    std::error_code e;
    if (std::filesystem::exists(folder / "dem.json", e)) found.emplace_back("a survey's heights");
    if (found.empty()) return std::nullopt;
    std::string list;
    for (const std::string_view f : found) list += (list.empty() ? "" : ", ") + std::string(f);
    return Failure{.code = not_carried, .why = std::format("{}: render tiles cannot carry {} yet", name, list)};
}

/// Little-endian bytes, as the tiles file holds them.
class Out {
public:
    void u8(std::uint8_t v) { bytes_.push_back(static_cast<char>(v)); }
    void u16(std::uint16_t v) {
        for (unsigned i = 0; i < 2; ++i) u8(static_cast<std::uint8_t>(v >> (8U * i)));
    }
    void u32(std::uint32_t v) {
        for (unsigned i = 0; i < 4; ++i) u8(static_cast<std::uint8_t>(v >> (8U * i)));
    }
    void i32(std::int32_t v) { u32(static_cast<std::uint32_t>(v)); }
    void text(std::string_view s) { bytes_.append(s); }
    void raw(std::span<const std::byte> b) {
        for (const std::byte c : b) u8(std::to_integer<std::uint8_t>(c));
    }
    [[nodiscard]] const std::string& bytes() const noexcept { return bytes_; }

private:
    std::string bytes_;
};

/// The tiles file of `tiles` and the pack's `sections`, as the header says.
[[nodiscard]] std::expected<std::string, Failure> tiles_file(const std::vector<tl::TileFill>& tiles,
                                                             const load::PackSections& sections, std::size_t& features,
                                                             std::size_t& bytes) {
    Out out;
    out.text("ATSP");
    out.u32(format_version);
    out.u32(static_cast<std::uint32_t>(tiles.size()));
    for (const tl::TileFill& t : tiles) {
        const auto raw = tl::encode_tile(t);
        if (!raw) return std::unexpected(Failure{.why = "a tile does not encode: " + tl::describe(raw.error())});
        const auto reach = tl::reach_of(t);
        if (!reach) return std::unexpected(Failure{.why = "a tile has no reach: " + tl::describe(reach.error())});
        std::uint32_t count = 0;
        std::uint16_t layers = 0;
        for (const tl::LayerFill& l : t.layers()) {
            count += l.count();
            layers += l.count() > 0 ? 1U : 0U;
        }
        out.u8(t.level());
        out.u32(t.x());
        out.u32(t.y());
        out.u32(count);
        out.i32(reach->box.south);
        out.i32(reach->box.west);
        out.i32(reach->box.north);
        out.i32(reach->box.east);
        out.i32(reach->top_cm);
        out.u16(layers);
        for (const tl::LayerFill& l : t.layers()) {
            if (l.count() == 0) continue;
            const std::string_view label = l.schema().label;
            out.u8(static_cast<std::uint8_t>(label.size()));
            out.text(label);
            out.u32(l.count());
        }
        out.u32(static_cast<std::uint32_t>(raw->size()));
        out.raw(*raw);
        features += count;
        bytes += raw->size();
    }
    out.u32(static_cast<std::uint32_t>(sections.head.size()));
    out.raw(sections.head);
    out.u32(static_cast<std::uint32_t>(sections.style.size()));
    out.raw(sections.style);
    return out.bytes();
}

/// Writes `bytes` to `path` by a file beside it renamed once whole.
[[nodiscard]] std::expected<void, Failure> write_whole(const std::string& path, const std::string& bytes) {
    const std::string part = path + ".part";
    {
        std::ofstream file(part, std::ios::binary | std::ios::trunc);
        file.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
        if (!file.good()) return std::unexpected(Failure{.why = part + " could not be written"});
    }
    std::error_code e;
    std::filesystem::rename(part, path, e);
    if (e) return std::unexpected(Failure{.why = std::format("{} could not be renamed: {}", part, e.message())});
    return {};
}

/// The region of `json` (region.json): its box and its tradition, with `name`, `frame` and its palette.
[[nodiscard]] std::expected<load::RegionConfig, Failure> region_of(std::string_view name, const tl::Frame& frame,
                                                                   const tl::Value& json) {
    const std::optional<tl::Value> bbox = json.find("bbox");
    if (!bbox || bbox->size() != 4) return std::unexpected(input("region.json has no bbox of four numbers"));
    std::array<double, 4> box{};
    for (std::size_t i = 0; i < 4; ++i) {
        const std::optional<double> v = bbox->child(i).number();
        if (!v) return std::unexpected(input("region.json's bbox holds something other than numbers"));
        box.at(i) = *v;
    }
    std::shared_ptr<const load::Tradition> tradition;
    if (const std::optional<tl::Value> t = json.find("tradition"); t && !t->is_null()) {
        const std::optional<std::string_view> named = t->string();
        if (!named) return std::unexpected(input("region.json's tradition is not a name"));
        tradition = load::tradition_named(*named);
        if (!tradition) return std::unexpected(input(std::format("the core has no tradition {}", *named)));
    }
    return load::RegionConfig{.name = name,
                              .frame = frame,
                              .bbox = box,
                              .tradition = std::move(tradition),
                              .palette = load::region_palette(name)};
}

/// The tiles of the job, read, loaded and cut, and the pack's head and style. Returns the line it prints.
[[nodiscard]] std::expected<std::string, Failure> tiles(const std::string& raw_path, const std::string& job_path,
                                                        const std::string& out_path) {
    using clock = std::chrono::steady_clock;
    const auto ms = [](clock::time_point a, clock::time_point b) {
        return std::chrono::duration<double, std::milli>(b - a).count();
    };
    const auto t0 = clock::now();
    auto job_file = mapped(job_path);
    if (!job_file) return std::unexpected(job_file.error());
    const auto job = tl::read_json((*job_file)->view(0, (*job_file)->size()));
    if (!job) return std::unexpected(input(std::format("{} is not JSON", job_path)));
    const tl::Value j = job->root();
    const std::optional<std::string_view> profile = j.find("profile").value_or(tl::Value()).string();
    const std::optional<std::string_view> name = j.find("region").value_or(tl::Value()).string();
    if (!profile || (*profile != "living" && *profile != "region")) {
        return std::unexpected(input("the job's profile is neither living nor region"));
    }
    if (!name || name->empty()) return std::unexpected(input("the job names no region"));
    const std::optional<tl::Value> frame_bits = j.find("frame");
    if (!frame_bits || frame_bits->size() != 4) return std::unexpected(input("the job's frame is not four doubles"));
    std::array<double, 4> f{};
    for (std::size_t i = 0; i < 4; ++i) {
        const std::optional<double> v = from_bits(frame_bits->child(i).string().value_or(""));
        if (!v) return std::unexpected(input("the job's frame holds something other than a double's bits"));
        f.at(i) = *v;
    }
    const tl::Frame frame{.lat0 = f[0], .lon0 = f[1], .kx = f[2], .kz = f[3]};
    const auto levels = ints(j.find("levels").value_or(tl::Value()), 0, 30);
    if (!levels) return std::unexpected(input("the job's levels are not a list of ints"));
    const bool living = *profile == "living";
    if (*levels != (living ? std::vector<std::int64_t>{14} : std::vector<std::int64_t>{14, 12})) {
        return std::unexpected(
            input(std::format("the core cuts {} at {}, not the job's levels", living ? "the living map" : "a region",
                              living ? "L14" : "L14 and L12")));
    }
    const std::optional<std::string_view> engine_version = j.find("engine_version").value_or(tl::Value()).string();
    const std::optional<std::string_view> source = j.find("source").value_or(tl::Value()).string();
    if (!engine_version || engine_version->empty() || !source || source->empty()) {
        return std::unexpected(input("the job names no engine version or no source"));
    }

    // a region's R, read before its data, so what its tiles cannot carry yet refuses it at once
    std::unique_ptr<tl::MappedSource> region_file;
    std::optional<tl::JsonValue> region_json;
    std::optional<load::RegionConfig> config;
    if (!living) {
        const std::optional<std::string_view> region_path = j.find("region_json").value_or(tl::Value()).string();
        if (!region_path) return std::unexpected(input("the job names no region.json for a region"));
        auto opened = mapped(std::string(*region_path));
        if (!opened) return std::unexpected(opened.error());
        region_file = std::move(*opened);
        auto read = tl::read_json(region_file->view(0, region_file->size()));
        if (!read) return std::unexpected(input(std::format("{} is not JSON", *region_path)));
        region_json = std::move(*read);
        if (auto refused = uncarried(*name, region_json->root(), std::filesystem::path(*region_path).parent_path())) {
            return std::unexpected(*refused);
        }
        auto made = region_of(*name, frame, region_json->root());
        if (!made) return std::unexpected(made.error());
        config = std::move(*made);
    }
    std::vector<std::uint8_t> level_list;
    for (const std::int64_t z : *levels) level_list.push_back(static_cast<std::uint8_t>(z));
    const load::PackFacts facts{.profile = living ? tl::Profile::living : tl::Profile::region,
                                .region = *name,
                                .levels = level_list,
                                .frame = frame,
                                .engine_version = *engine_version,
                                .source = *source,
                                .r = region_json ? region_json->root() : tl::Value(),
                                .tradition = config ? config->tradition.get() : nullptr};
    const auto views = load::pack_views(facts);
    if (!views) return std::unexpected(input(views.error()));
    const std::vector<std::uint8_t> ladder = living ? std::vector<std::uint8_t>() : load::variant_ladder(*views);

    auto data = mapped(raw_path);
    if (!data) return std::unexpected(data.error());
    const auto doc = osm::read_overpass((*data)->view(0, (*data)->size()));
    if (!doc) {
        return std::unexpected(input(
            std::format("{} does not read: {} at byte {}", raw_path, osm::name_of(doc.error().fault), doc.error().at)));
    }
    const auto t1 = clock::now();
    const auto loaded = [&](const std::expected<load::Scene, load::LoadError>& scene) -> std::optional<Failure> {
        if (scene) return std::nullopt;
        return Failure{.why = std::format("the data does not load: {} at element {}",
                                          load::name_of(scene.error().fault), scene.error().element)};
    };
    std::expected<std::vector<tl::TileFill>, load::CutError> cut;
    clock::time_point t2;
    if (living) {
        const auto scene = load::load_amsterdam(*doc, frame, load::amsterdam_palette());
        if (auto failed = loaded(scene)) return std::unexpected(*failed);
        t2 = clock::now();
        cut = load::cut_living(*scene, frame);
    } else {
        const auto base = load::load_region(*doc, *config);
        if (auto failed = loaded(base)) return std::unexpected(*failed);
        // the region at each tolerance its views and the live map's bands load it at, for the variants
        std::vector<load::Scene> others;
        others.reserve(ladder.size());
        for (const std::uint8_t l : ladder) {
            auto other = load::load_region(*doc, *config, tl::keep::tolerances.at(l));
            if (auto failed = loaded(other)) return std::unexpected(*failed);
            others.push_back(std::move(*other));
        }
        std::vector<load::CutVariant> cut_variants;
        cut_variants.reserve(others.size());
        for (std::size_t k = 0; k < others.size(); ++k) {
            cut_variants.push_back({.tolerance = tl::keep::tolerances.at(ladder[k]), .scene = &others[k]});
        }
        t2 = clock::now();
        cut = load::cut_region(*base, frame, cut_variants);
    }
    if (!cut) {
        return std::unexpected(Failure{.code = cut.error().uncarried ? not_carried : 1,
                                       .why = std::string(*name) + ": " + cut.error().what});
    }
    const auto sections = load::pack_sections(facts, *views);
    if (!sections) return std::unexpected(Failure{.why = std::string(*name) + ": " + sections.error()});
    const auto t3 = clock::now();
    std::size_t features = 0;
    std::size_t bytes = 0;
    const auto file = tiles_file(*cut, *sections, features, bytes);
    if (!file) return std::unexpected(file.error());
    if (auto written = write_whole(out_path, *file); !written) return std::unexpected(written.error());
    return std::format("{} ({}): {} tiles, {} features, {} bytes, a head of {} views and a style of {} bytes; read in "
                       "{:.0f} ms, loaded in {:.0f} ms ({} variants' loads), cut in {:.0f} ms",
                       *name, *profile, cut->size(), features, bytes, views->size(), sections->style.size(), ms(t0, t1),
                       ms(t1, t2), ladder.size(), ms(t2, t3));
}

} // namespace

int main(int argc, char** argv) {
    const std::span<char*> args(argv, static_cast<std::size_t>(argc));
    if (args.size() != 5 || std::string_view(args[1]) != "tiles") {
        (void)std::fputs("usage: atlas_stagep tiles <raw.json> <job.json> <out>\n", stderr);
        return usage_error;
    }
    const auto line = tiles(args[2], args[3], args[4]);
    if (!line) {
        (void)std::fprintf(stderr, "atlas_stagep: %s\n", line.error().why.c_str());
        return line.error().code;
    }
    (void)std::printf("%s\n", line->c_str());
    return 0;
}
