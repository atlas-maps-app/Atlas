// A frame cell's levels (load/Levels.hpp).
#include "load/Levels.hpp"

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "load/PackHead.hpp"
#include "tiles/Container.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Json.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/Payload.hpp"
#include "tiles/SectionWriter.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/Source.hpp"
#include "tiles/Store.hpp"
#include "tiles/TileWriter.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"
#include "tiles/layers/Water.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <format>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>

namespace atlas::load {

namespace {

namespace tl = atlas::tiles;

/// A grid integer's degrees.
[[nodiscard]] double degrees(std::int32_t grid) noexcept { return static_cast<double>(grid) / 1e7; }

/// A double written so that it reads back to itself.
[[nodiscard]] std::string exact(double v) {
    std::array<char, 32> buf{};
    const int n = std::snprintf(buf.data(), buf.size(), "%.17g", v);
    return n > 0 ? std::string(buf.data(), static_cast<std::size_t>(n)) : std::string("0");
}

[[nodiscard]] std::string quoted(std::string_view s) { return std::format("\"{}\"", s); }

/// What a level keeps: its rings at its code, and what the renders leave out at the depth it is first drawn at (a face
/// under 0.12 px², a ground polygon under 0.2 px², the frame's dp there), m²; the far level only its large buildings.
struct LevelRule {
    std::uint8_t code = 0;
    double least_face = 0;
    double least_ground = 0;
    double least_area = 0;   ///< a building's footprint, m² (0: every building)
    double least_height = 0; ///< or its height, m
};

[[nodiscard]] constexpr LevelRule rule_at(std::uint8_t code, double depth, double least_area,
                                          double least_height) noexcept {
    const double px = depth / frame_focal;
    return {.code = code,
            .least_face = 0.12 * px * px,
            .least_ground = 0.2 * px * px,
            .least_area = least_area,
            .least_height = least_height};
}

constexpr LevelRule coarse_rule = rule_at(coarse_code, coarse_depth, 0, 0);
constexpr LevelRule far_rule = rule_at(far_code, far_depth, far_least_area, far_least_height);

/// A window of the cell: its pack, its tile decoded.
struct Place {
    tl::TileKey key;
    std::optional<tl::Pack> pack;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena{std::size_t{4} << 20U, Growth::grows, "levels"};
    tl::Tile tile{};
};

/// A place's share of a level's cell: its tile, and the L14 cells (bit r · 8 + c) whose features the cell holds.
struct Part {
    const Place* place = nullptr;
    std::uint64_t cells = 0;
};

/// Calls `f(i)` for each feature of `l` in `cells` (bit c: the layer's cell c), in the layer's order.
template <class F>
void each_feature(const tl::Layer& l, std::uint64_t cells, const F& f) {
    const std::size_t n = l.cell_first.empty() ? 0 : l.cell_first.size() - 1;
    for (std::size_t c = 0; c < n && c < 64; ++c) {
        if (((cells >> c) & 1U) == 0U) continue;
        for (std::uint32_t i = l.cell_first[c]; i < l.cell_first[c + 1]; ++i) f(i);
    }
}

// ---------------------------------------------------------------- copying a feature

/// Copies feature `i` of `in` into feature `f` of `out` (the same layer), every column but its geometry and keep
/// columns (the caller's) and the slots in `skip`, which keep their defaults.
void copy_columns(const tl::Layer& in, std::uint32_t i, tl::LayerFill& out, std::uint32_t f,
                  std::span<const std::uint8_t> skip = {}) {
    std::vector<std::pair<std::string_view, std::string_view>> pairs;
    std::vector<std::string_view> list;
    const std::span<const tl::ColumnSpec> columns = in.schema->columns;
    for (std::size_t s = 0; s < columns.size(); ++s) {
        const auto slot = static_cast<std::uint8_t>(s);
        if (!in.column(slot).present || std::ranges::find(skip, slot) != skip.end()) continue;
        switch (columns[s].codec) {
        case tl::Codec::u8:
        case tl::Codec::varu:
        case tl::Codec::varz:
        case tl::Codec::delta: out.integer(slot, f, in.integer(slot, i)); break;
        case tl::Codec::nvarz:
        case tl::Codec::ndelta: out.nullable_integer(slot, f, in.nullable_integer(slot, i)); break;
        case tl::Codec::centi:
        case tl::Codec::f64: out.decimal(slot, f, in.decimal(slot, i)); break;
        case tl::Codec::str: out.string(slot, f, in.string(slot, i)); break;
        case tl::Codec::strn: {
            const tl::StringOrNumber v = in.string_or_number(slot, i);
            if (v.kind == tl::StringOrNumber::Kind::null) {
                out.string_or_number(slot, f, std::nullopt);
            } else if (v.kind == tl::StringOrNumber::Kind::string) {
                out.string_or_number(slot, f, v.text);
            } else {
                out.string_or_number(slot, f, std::to_string(v.number));
            }
            break;
        }
        case tl::Codec::boolean: out.boolean(slot, f, in.boolean(slot, i)); break;
        case tl::Codec::point: out.point(slot, f, in.point(slot, i)); break;
        case tl::Codec::tags:
            pairs.clear();
            for (std::size_t k = 0; k < in.tag_count(slot, i); ++k) pairs.push_back(in.tag(slot, i, k));
            out.tags(slot, f, pairs);
            break;
        case tl::Codec::ids: out.ids(slot, f, in.ids(slot, i)); break;
        case tl::Codec::strs: {
            const auto indexes = in.string_list(slot, i);
            if (!indexes) {
                out.string_list(slot, f, std::nullopt);
                break;
            }
            list.clear();
            for (const std::uint32_t k : *indexes) list.push_back(in.strings[k]);
            out.string_list(slot, f, list);
            break;
        }
        case tl::Codec::geom:
        case tl::Codec::keep:
        case tl::Codec::value: break; // the caller's (no layer has a value column)
        }
    }
}

/// A ring's area, m², in `frame` (the shoelace over its vertices' metres).
[[nodiscard]] double area_of(std::span<const std::int32_t> lat, std::span<const std::int32_t> lon,
                             const tl::Frame& frame) noexcept {
    const std::size_t n = lat.size();
    if (n < 3) return 0.0;
    double a = 0.0;
    for (std::size_t k = 0; k < n; ++k) {
        const std::size_t j = (k + 1) % n;
        a += (frame.x(lon[k]) * frame.z(lat[j])) - (frame.x(lon[j]) * frame.z(lat[k]));
    }
    return std::abs(a) / 2.0;
}

/// A ring at the level's code: its vertices kept there (tiles::simplify's, with its fallbacks), with their codes (a
/// ring without codes keeps every vertex, coded keep::all).
struct LevelRing {
    std::vector<std::int32_t> lat;
    std::vector<std::int32_t> lon;
    std::vector<std::uint8_t> keep;
};

[[nodiscard]] LevelRing ring_at(const tl::RingView& r, std::uint8_t code, const tl::Frame& frame,
                                std::vector<std::uint32_t>& idx) {
    LevelRing out;
    idx.resize(r.size());
    const tl::Simplified s = tl::simplify(r, code, frame, idx);
    const std::size_t n = s.whole ? r.size() : s.count;
    out.lat.reserve(n);
    out.lon.reserve(n);
    out.keep.reserve(n);
    for (std::size_t k = 0; k < n; ++k) {
        const std::size_t v = s.whole ? k : idx[k];
        out.lat.push_back(r.lat[v]);
        out.lon.push_back(r.lon[v]);
        out.keep.push_back(r.has_keep ? r.keep[v] : tl::keep::all);
    }
    // a ring left smaller than simplify touches keeps every vertex at every tolerance, as the format has it
    if (n < tl::keep::min_points) std::ranges::fill(out.keep, tl::keep::all);
    return out;
}

/// Douglas-Peucker on a line's vertices in `frame`'s metres at `tolerance`: the kept vertices' indexes (its ends
/// always), in order.
void douglas_peucker(const tl::RingView& r, const tl::Frame& frame, double tolerance, std::vector<std::uint32_t>& out) {
    out.clear();
    const std::size_t n = r.size();
    if (n <= 2) {
        for (std::size_t k = 0; k < n; ++k) out.push_back(static_cast<std::uint32_t>(k));
        return;
    }
    std::vector<bool> kept(n, false);
    kept.front() = true;
    kept.back() = true;
    std::vector<std::pair<std::size_t, std::size_t>> stack{{0, n - 1}};
    while (!stack.empty()) {
        const auto [a, b] = stack.back();
        stack.pop_back();
        if (b <= a + 1) continue;
        const double ax = frame.x(r.lon[a]);
        const double az = frame.z(r.lat[a]);
        const double bx = frame.x(r.lon[b]);
        const double bz = frame.z(r.lat[b]);
        const double dx = bx - ax;
        const double dz = bz - az;
        const double len = pymath::hypot(dx, dz);
        double far = -1.0;
        std::size_t at = a;
        for (std::size_t k = a + 1; k < b; ++k) {
            const double px = frame.x(r.lon[k]) - ax;
            const double pz = frame.z(r.lat[k]) - az;
            const double d = len > 0 ? std::abs((px * dz) - (pz * dx)) / len : pymath::hypot(px, pz);
            if (d > far) {
                far = d;
                at = k;
            }
        }
        if (far > tolerance) {
            kept[at] = true;
            stack.emplace_back(a, at);
            stack.emplace_back(at, b);
        }
    }
    for (std::size_t k = 0; k < n; ++k) {
        if (kept[k]) out.push_back(static_cast<std::uint32_t>(k));
    }
}

// ---------------------------------------------------------------- a level's tile

/// Builds one tile of a level, cell by cell, from its places' tiles.
class LevelWriter {
public:
    LevelWriter(tl::TileKey key, const tl::Frame& frame, const LevelRule& rule)
        : frame_(frame), rule_(rule), fill_(key.z, key.x, key.y) {}

    /// Cell `cell`: its parts' features at the level, in their order.
    void cell(std::uint16_t cell, std::span<const Part> parts) {
        tl::CellFill box{.south = INT32_MAX, .west = INT32_MAX, .north = INT32_MIN, .east = INT32_MIN, .top_cm = 0};
        for (const Part& p : parts) {
            const std::span<const tl::CellInfo> cells = p.place->tile.cells;
            for (std::size_t c = 0; c < cells.size() && c < 64; ++c) {
                if (((p.cells >> c) & 1U) == 0U || cells[c].total == 0) continue;
                box.south = std::min(box.south, cells[c].south);
                box.west = std::min(box.west, cells[c].west);
                box.north = std::max(box.north, cells[c].north);
                box.east = std::max(box.east, cells[c].east);
                box.top_cm = std::max(box.top_cm, cells[c].top_cm);
            }
        }
        if (box.south <= box.north) fill_.cells()[cell] = box;
        buildings(cell, parts, tl::LayerId::building);
        buildings(cell, parts, tl::LayerId::part);
        rings(cell, parts, tl::LayerId::ground, tl::ground::ring);
        rings(cell, parts, tl::LayerId::water, tl::water::ring);
        lines(cell, parts, tl::LayerId::road, tl::road::line, {});
        lines(cell, parts, tl::LayerId::line, tl::line::line, {4, 7, 8});
        copy_all(cell, parts, tl::LayerId::tree);
        items(cell, parts);
    }

    [[nodiscard]] tl::TileFill& fill() noexcept { return fill_; }
    [[nodiscard]] std::uint32_t buildings_written() const noexcept { return buildings_; }
    [[nodiscard]] std::uint32_t trees() const noexcept { return trees_; }

private:
    /// The columns the level leaves out of a building: a shop's places and their hours, a kit's shutters and party
    /// walls (corners of the ring at L14), which only a close view draws.
    static constexpr std::array<std::uint8_t, 4> building_skip{tl::building::places, tl::building::place_hours,
                                                               tl::building::shutter, tl::building::party};

    void write_ring(tl::LayerFill& out, std::uint8_t slot, std::uint32_t f, const LevelRing& ring, bool keep) {
        out.ring(slot, f, ring.lat, ring.lon,
                 keep ? std::optional(std::span<const std::uint8_t>(ring.keep)) : std::nullopt);
    }

    void buildings(std::uint16_t cell, std::span<const Part> parts, tl::LayerId id) {
        for (const Part& p : parts) {
            const tl::Layer* l = p.place->tile.layer(id);
            if (l == nullptr) continue;
            const bool keep = l->schema->keep_for[tl::building::ring] != 0xFF;
            const std::vector<std::int64_t>* outlines = rule_.least_area > 0 ? &large_of(*p.place) : nullptr;
            each_feature(*l, p.cells, [&](std::uint32_t i) {
                if (outlines != nullptr && !kept_large(*l, id, i, *outlines)) return;
                const LevelRing ring = ring_at(l->ring(tl::building::ring, i), rule_.code, frame_, idx_);
                if (ring.lat.size() < 3) return;
                if (area_of(ring.lat, ring.lon, frame_) < rule_.least_face) return;
                tl::LayerFill& out = fill_.layer(id);
                const std::uint32_t f = out.add(cell);
                copy_columns(*l, i, out, f, building_skip);
                write_ring(out, tl::building::ring, f, ring, keep);
                ++buildings_;
            });
        }
    }

    /// Whether building or part `i` of `l` is large: a footprint of the rule's least area or more, or its height.
    [[nodiscard]] bool large(const tl::Layer& l, std::uint32_t i) const {
        if (l.decimal(tl::building::h, i).value_or(0.0) >= rule_.least_height) return true;
        const tl::RingView r = l.ring(tl::building::ring, i);
        return area_of(r.lat, r.lon, frame_) >= rule_.least_area;
    }

    /// The outlines of place `p` a level of large buildings keeps, by id, sorted (once a place): each large one, and
    /// each with a large part (a church's tower), so an outline drawn by its parts (hidden) keeps them all.
    const std::vector<std::int64_t>& large_of(const Place& p) {
        auto [it, fresh] = large_.try_emplace(&p);
        std::vector<std::int64_t>& out = it->second;
        if (!fresh) return out;
        if (const tl::Layer* b = p.tile.layer(tl::LayerId::building)) {
            for (std::uint32_t i = 0; i < b->count; ++i) {
                if (large(*b, i)) out.push_back(b->integer(tl::building::id, i));
            }
        }
        if (const tl::Layer* parts = p.tile.layer(tl::LayerId::part)) {
            for (std::uint32_t i = 0; i < parts->count; ++i) {
                if (!large(*parts, i)) continue;
                if (const auto parent = parts->nullable_integer(tl::building::parent_id, i)) out.push_back(*parent);
            }
        }
        std::ranges::sort(out);
        return out;
    }

    /// Whether a level of large buildings keeps building or part `i` of `l`: a kept outline (`large`, large_of's), or
    /// a part of one; a part whose outline lies in another place, where it is large itself.
    [[nodiscard]] bool kept_large(const tl::Layer& l, tl::LayerId id, std::uint32_t i,
                                  const std::vector<std::int64_t>& outlines) const {
        if (id == tl::LayerId::building) return std::ranges::binary_search(outlines, l.integer(tl::building::id, i));
        const auto parent = l.nullable_integer(tl::building::parent_id, i);
        return (parent && std::ranges::binary_search(outlines, *parent)) || large(l, i);
    }

    void rings(std::uint16_t cell, std::span<const Part> parts, tl::LayerId id, std::uint8_t slot) {
        for (const Part& p : parts) {
            const tl::Layer* l = p.place->tile.layer(id);
            if (l == nullptr) continue;
            const bool keep = l->schema->keep_for[slot] != 0xFF;
            each_feature(*l, p.cells, [&](std::uint32_t i) {
                const LevelRing ring = ring_at(l->ring(slot, i), rule_.code, frame_, idx_);
                if (ring.lat.size() < 3 || area_of(ring.lat, ring.lon, frame_) < rule_.least_ground) return;
                tl::LayerFill& out = fill_.layer(id);
                const std::uint32_t f = out.add(cell);
                copy_columns(*l, i, out, f);
                write_ring(out, slot, f, ring, keep);
            });
        }
    }

    void lines(std::uint16_t cell, std::span<const Part> parts, tl::LayerId id, std::uint8_t slot,
               std::initializer_list<std::uint8_t> left_out) {
        const double tolerance = tl::keep::tolerances.at(rule_.code);
        for (const Part& p : parts) {
            const tl::Layer* l = p.place->tile.layer(id);
            if (l == nullptr) continue;
            each_feature(*l, p.cells, [&](std::uint32_t i) {
                if (id == tl::LayerId::line &&
                    std::ranges::find(left_out, static_cast<std::uint8_t>(l->integer(tl::line::source, i))) !=
                        left_out.end()) {
                    return;
                }
                const tl::RingView r = l->ring(slot, i);
                // a line whose vertices carry heights of their own (a coaster's) stays whole
                if (id == tl::LayerId::line && !l->ids(tl::line::ys, i).empty()) {
                    idx_.resize(r.size());
                    for (std::size_t k = 0; k < r.size(); ++k) idx_[k] = static_cast<std::uint32_t>(k);
                } else {
                    douglas_peucker(r, frame_, tolerance, idx_);
                }
                lat_.clear();
                lon_.clear();
                for (const std::uint32_t k : idx_) {
                    lat_.push_back(r.lat[k]);
                    lon_.push_back(r.lon[k]);
                }
                tl::LayerFill& out = fill_.layer(id);
                const std::uint32_t f = out.add(cell);
                copy_columns(*l, i, out, f);
                out.ring(slot, f, lat_, lon_);
            });
        }
    }

    void copy_all(std::uint16_t cell, std::span<const Part> parts, tl::LayerId id) {
        for (const Part& p : parts) {
            const tl::Layer* l = p.place->tile.layer(id);
            if (l == nullptr) continue;
            each_feature(*l, p.cells, [&](std::uint32_t i) {
                tl::LayerFill& out = fill_.layer(id);
                copy_columns(*l, i, out, out.add(cell));
                if (id == tl::LayerId::tree) ++trees_;
            });
        }
    }

    /// The region items the frame draws beyond 4.7 km: towers, pylons, turbines, the woods' trees and the masts (drawn
    /// to focal / 0.25, 5.7 km); never the cranes (focal / 0.6, 2.4 km). The far level keeps them as well.
    void items(std::uint16_t cell, std::span<const Part> parts) {
        for (const Part& p : parts) {
            const tl::Layer* l = p.place->tile.layer(tl::LayerId::item);
            if (l == nullptr) continue;
            each_feature(*l, p.cells, [&](std::uint32_t i) {
                if (l->integer(tl::item::source, i) == 0) return;
                tl::LayerFill& out = fill_.layer(tl::LayerId::item);
                const std::uint32_t f = out.add(cell);
                copy_columns(*l, i, out, f);
                const tl::RingView poly = l->ring(tl::item::poly, i);
                if (poly.size() > 0) {
                    lat_.assign(poly.lat.begin(), poly.lat.end());
                    lon_.assign(poly.lon.begin(), poly.lon.end());
                    out.ring(tl::item::poly, f, lat_, lon_);
                }
            });
        }
    }

    const tl::Frame& frame_;
    LevelRule rule_;
    tl::TileFill fill_;
    std::vector<std::uint32_t> idx_;
    std::vector<std::int32_t> lat_;
    std::vector<std::int32_t> lon_;
    std::uint32_t buildings_ = 0;
    std::uint32_t trees_ = 0;
    std::map<const Place*, std::vector<std::int64_t>> large_;
};

/// What every coarse pack of the cell takes from its first window: its head's frame and versions, its style, its time
/// zone.
struct CellHead {
    tl::Frame frame;
    std::string engine_version;
    std::string style_version;
    std::string tz;
    std::vector<std::byte> style;
};

[[nodiscard]] std::expected<CellHead, WindowError> head_of(const tl::Pack& pack) {
    CellHead out;
    const tl::RenderHead& head = *pack.head();
    out.frame = head.frame;
    out.engine_version = std::string(head.engine_version);
    out.style_version = std::string(head.style_version);
    tl::Zstd zstd;
    for (const tl::Section& s : pack.sections()) {
        if (s.name != tl::section_style) continue;
        auto bytes = tl::read_section(pack.source(), s, tl::Site::style, tl::max_section_raw, zstd);
        if (!bytes) {
            return std::unexpected(WindowError{"a window's style does not read: " + tl::describe(bytes.error())});
        }
        out.style = std::move(*bytes);
    }
    std::vector<std::byte> scratch;
    const auto manifest = pack.source().bytes(tl::container_header_bytes, pack.header().manifest_bytes, scratch);
    if (!manifest) return std::unexpected(WindowError{"a window's manifest does not read"});
    const auto json = tl::read_json(*manifest);
    if (!json) return std::unexpected(WindowError{"a window's manifest is not JSON"});
    if (const auto tz = json->root().find("tz")) out.tz = std::string(tz->string().value_or("UTC"));
    return out;
}

/// A level's tile's pack, as a window's is written: its tile alone at its level, the cell's frame, versions, bands and
/// style, a manifest naming it.
[[nodiscard]] std::expected<BuiltLevel, WindowError> pack_of(LevelWriter& w, const CellHead& cell) {
    const tl::TileFill& t = w.fill();
    BuiltLevel out{.key = tl::TileKey(t.level(), t.x(), t.y())};
    auto raw = tl::encode_tile(t);
    if (!raw) {
        return std::unexpected(WindowError{
            std::format("tile {}/{}/{} does not encode: {}", t.level(), t.x(), t.y(), tl::describe(raw.error()))});
    }
    const auto reach = tl::reach_of(t);
    if (!reach) return std::unexpected(WindowError{"a level's tile has no reach: " + tl::describe(reach.error())});
    std::uint32_t count = 0;
    for (const tl::LayerFill& l : t.layers()) count += l.count();
    out.features = count;
    out.raw = raw->size();
    out.buildings = w.buildings_written();
    out.trees = w.trees();
    std::vector<tl::PackTile> tiles;
    tiles.push_back({.key = out.key, .raw = std::move(*raw), .count = count, .reach = *reach});
    const std::array<std::uint8_t, 1> levels{t.level()};
    const std::string name = std::format("{}/{}/{}", t.level(), t.x(), t.y());
    const auto head = tl::encode_head({.profile = tl::Profile::region,
                                       .levels = levels,
                                       .frame = cell.frame,
                                       .region = name,
                                       .engine_version = cell.engine_version,
                                       .style_version = cell.style_version,
                                       .source = {},
                                       .views = {},
                                       .bands = region_bands()});
    if (!head) return std::unexpected(WindowError{"the head does not encode: " + tl::describe(head.error())});
    const std::array<std::int32_t, 4> tb = tl::tile_box(out.key);
    const std::string manifest = std::format(
        R"({{"layout":"render-tiles","payload":{},"profile":"region","bbox":{{"west":{},"south":{},"east":{},"north":{}}},)"
        R"("origin":{{"lat":{},"lon":{}}},"tz":{},"config":{{"views":{{}}}},"chunks":1,"engine_version":{},)"
        R"("style_version":{},"builder":"core levels","window":{}}})",
        tl::payload_version, exact(degrees(tb[1])), exact(degrees(tb[0])), exact(degrees(tb[3])), exact(degrees(tb[2])),
        exact(cell.frame.lat0), exact(cell.frame.lon0), quoted(cell.tz), quoted(cell.engine_version),
        quoted(cell.style_version), quoted(name));
    auto pack = tl::write_pack(tiles, {.levels = levels, .manifest = manifest, .head = *head, .style = cell.style});
    if (!pack) return std::unexpected(WindowError{"the pack does not write: " + tl::describe(pack.error())});
    out.pack = std::move(*pack);
    return out;
}

/// The L14 cells (bit r · 8 + c) of the quarter (qy, qx) of a place: the L14 cells an L13 cell holds.
[[nodiscard]] constexpr std::uint64_t quarter(std::uint32_t qy, std::uint32_t qx) noexcept {
    std::uint64_t bits = 0;
    for (std::uint32_t r = qy * 4; r < (qy * 4) + 4; ++r) {
        for (std::uint32_t c = qx * 4; c < (qx * 4) + 4; ++c) bits |= std::uint64_t{1} << ((r * 8) + c);
    }
    return bits;
}

} // namespace

std::expected<std::vector<BuiltLevel>, WindowError> build_levels(std::span<const std::span<const std::byte>> windows) {
    static_assert(tl::cells_per_side(coarse_level) == 4 && tl::cells_per_side(far_level) == 4 &&
                  tl::cells_per_side(window_level) == 8);
    static_assert(tl::Store::coarse_level == coarse_level && tl::Store::far_level == far_level &&
                  tl::Store::window_level == window_level && tl::Store::cell_level == frame_cell_level);
    // the windows, by place, each opened over its bytes (the caller's)
    std::map<std::uint64_t, std::span<const std::byte>> by_place;
    std::optional<tl::TileKey> cell_key;
    std::optional<CellHead> head;
    for (const std::span<const std::byte> bytes : windows) {
        auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(bytes));
        if (!pack) return std::unexpected(WindowError{"a window does not open: " + tl::describe(pack.error())});
        if (!pack->is_render() || pack->head() == nullptr || pack->head()->levels.size() != 1 ||
            pack->head()->levels[0] != window_level || pack->chunks().chunks().size() > 1) {
            return std::unexpected(WindowError{"not a window's pack"});
        }
        if (pack->chunks().chunks().empty()) continue;
        const tl::TileKey key = pack->chunks().chunks()[0].tile;
        const tl::TileKey cell(frame_cell_level, key.x >> 4U, key.y >> 4U);
        if (cell_key && cell_key->packed() != cell.packed()) {
            return std::unexpected(WindowError{"windows of two frame cells"});
        }
        cell_key = cell;
        if (!head) {
            auto h = head_of(*pack);
            if (!h) return std::unexpected(h.error());
            head = std::move(*h);
        } else if (pack->head()->engine_version != head->engine_version ||
                   pack->head()->style_version != head->style_version) {
            return std::unexpected(WindowError{"windows of two versions"});
        }
        by_place[key.packed()] = bytes;
    }
    std::vector<BuiltLevel> out;
    if (!cell_key) return out;
    // the cell's head and style: its first window's in the places' order, whatever order the windows came in
    if (auto first = tl::Pack::open(std::make_unique<tl::MemorySource>(by_place.begin()->second)); first) {
        auto h = head_of(*first);
        if (!h) return std::unexpected(h.error());
        head = std::move(*h);
    } else {
        return std::unexpected(WindowError{"a window does not open"});
    }
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    // place `at`'s window opened and its tile decoded; none where the cell has no window there
    const auto open = [&](const tl::TileKey& at) -> std::expected<std::unique_ptr<Place>, WindowError> {
        const auto it = by_place.find(at.packed());
        if (it == by_place.end()) return std::unique_ptr<Place>{};
        auto p = std::make_unique<Place>();
        p->key = at;
        auto pack = tl::Pack::open(std::make_unique<tl::MemorySource>(it->second));
        if (!pack) return std::unexpected(WindowError{"a window does not open"});
        p->pack.emplace(std::move(*pack));
        auto tile = p->pack->decode(p->pack->chunks().chunks()[0], zstd, decoder, p->arena, p->raw, p->scratch);
        if (!tile) {
            return std::unexpected(WindowError{
                std::format("window 14/{}/{} does not decode: {}", at.x, at.y, tl::describe(tile.error()))});
        }
        p->tile = *tile;
        return p;
    };
    // Each level's tiles of the cell, row by row from the north-west: an L13 tile's 4 x 4 cells each a quarter of one
    // of its four places, an L12 tile's each one of its sixteen places whole.
    const auto level = [&](std::uint8_t z, const LevelRule& rule) -> std::expected<void, WindowError> {
        const std::uint32_t side = 1U << static_cast<std::uint32_t>(z - frame_cell_level); // its tiles a cell's side
        const std::uint32_t span = 1U << static_cast<std::uint32_t>(window_level - z);     // its places a tile's side
        for (std::uint32_t row = 0; row < side; ++row) {
            for (std::uint32_t col = 0; col < side; ++col) {
                const tl::TileKey key(z, (cell_key->x * side) + col, (cell_key->y * side) + row);
                std::vector<std::unique_ptr<Place>> places(static_cast<std::size_t>(span) * span);
                bool any = false;
                for (std::uint32_t dy = 0; dy < span; ++dy) {
                    for (std::uint32_t dx = 0; dx < span; ++dx) {
                        auto p = open(tl::TileKey(window_level, (key.x * span) + dx, (key.y * span) + dy));
                        if (!p) return std::unexpected(p.error());
                        any = any || *p != nullptr;
                        places.at((static_cast<std::size_t>(dy) * span) + dx) = std::move(*p);
                    }
                }
                if (!any) continue;
                LevelWriter writer(key, head->frame, rule);
                for (std::uint32_t cy = 0; cy < 4; ++cy) {
                    for (std::uint32_t cx = 0; cx < 4; ++cx) {
                        // the place the cell lies in, and its L14 cells there
                        const std::uint32_t py = cy * span / 4;
                        const std::uint32_t px = cx * span / 4;
                        const Place* p = places.at((static_cast<std::size_t>(py) * span) + px).get();
                        if (p == nullptr) continue;
                        const std::uint64_t cells = span == 2 ? quarter(cy % 2, cx % 2) : ~std::uint64_t{0};
                        const std::array<Part, 1> parts{Part{.place = p, .cells = cells}};
                        writer.cell(static_cast<std::uint16_t>((cy * 4) + cx), parts);
                    }
                }
                // a tile whose every feature is too small for its level is not built, as a place without a window
                std::uint32_t held = 0;
                for (const tl::LayerFill& l : writer.fill().layers()) held += l.count();
                if (held == 0) continue;
                auto built = pack_of(writer, *head);
                if (!built) return std::unexpected(built.error());
                out.push_back(std::move(*built));
            }
        }
        return {};
    };
    if (auto done = level(coarse_level, coarse_rule); !done) return std::unexpected(done.error());
    if (auto done = level(far_level, far_rule); !done) return std::unexpected(done.error());
    return out;
}

} // namespace atlas::load
