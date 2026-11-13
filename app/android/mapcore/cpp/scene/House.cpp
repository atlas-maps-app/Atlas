// The house the app asks about, with the roof it asks about (scene/House.hpp).
#include "scene/House.hpp"

#include "base/arena.hpp"
#include "geometry/Walls.hpp"
#include "scene/ItemTable.hpp"
#include "scene/Residency.hpp"
#include "tiles/Store.hpp"

#include <algorithm>
#include <cstddef>
#include <optional>

namespace atlas::scene {

namespace {

namespace gm = atlas::geometry;

/// The part of the house a primitive of `kind` is; none for what a plain house does not draw.
[[nodiscard]] std::optional<HousePart> part_of(gm::PrimKind kind) noexcept {
    using K = gm::PrimKind;
    switch (kind) {
    case K::cell:
    case K::skillion_wall:
    case K::gable: return HousePart::wall;
    case K::slope:
    case K::hip:
    case K::pyramid:
    case K::skillion:
    case K::mansard: return HousePart::slope;
    case K::top:
    case K::mansard_top:
    case K::plain_top: return HousePart::top;
    default: return std::nullopt;
    }
}

} // namespace

std::string_view house_roof(std::uint32_t code) {
    return code >= 1 && code <= gm::house_roofs.size() ? gm::house_roofs.at(code - 1) : std::string_view{};
}

const ResidentTile* house_tile(std::span<const std::shared_ptr<const ResidentTile>> resident,
                               std::int64_t id) noexcept {
    const std::uint64_t key = owner_key(owner_kind_area, static_cast<std::uint64_t>(id));
    const ResidentTile* from = nullptr;
    for (const auto& tile : resident) {
        if (tile == nullptr || (from != nullptr && from->level <= tile->level)) continue;
        if (std::ranges::any_of(tile->items, [key](const ItemSpec& item) { return item.building == key; })) {
            from = tile.get();
        }
    }
    return from;
}

std::shared_ptr<const House> house_of(const tiles::Store& store, const ResidentTile& from, std::int64_t id,
                                      bool relation, std::string_view roof) {
    std::shared_ptr<const tiles::StorePack> pack;
    for (std::shared_ptr<const tiles::StorePack>& p : store.packs()) {
        if (p->id == from.pack) pack = std::move(p);
    }
    if (!pack || !pack->pack.is_render() || pack->pack.head() == nullptr) return nullptr;
    const tiles::ChunkRef* chunk = pack->pack.chunks().find(from.key);
    if (chunk == nullptr) return nullptr;
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    Arena arena(std::size_t{4} << 20U, Growth::grows, "a house's tile");
    std::vector<std::byte> raw;
    const auto tile = store.decode({.pack = pack, .chunk = chunk}, zstd, decoder, arena, raw);
    if (!tile) return nullptr;
    const gm::BuildingInput in{.tile = &*tile, .head = pack->pack.head(), .level = from.level};
    const std::optional<gm::TileBuildings> built = gm::build_house(in, id, relation, roof);
    if (!built) return nullptr;
    auto house = std::make_shared<House>();
    house->id = id;
    house->relation = relation;
    house->roof = *std::ranges::find(gm::house_roofs, roof); // the list's own, which outlives the house
    house->key = from.key;
    house->pack = from.pack;
    const view::Vec3 origin = from.origin;
    const auto local = [&origin](const gm::V3& p) {
        return std::array<float, 3>{static_cast<float>(p.x - origin.x), static_cast<float>(p.y - origin.y),
                                    static_cast<float>(p.z - origin.z)};
    };
    for (const std::vector<gm::Prim>* list : {&built->edged, &built->plain}) {
        for (const gm::Prim& q : *list) {
            // a house is drawn at every distance and in any weather the same: what is drawn far off, without weather
            if ((q.flags & gm::prim_flag::far) == 0 || (q.flags & gm::prim_flag::weather) != 0) continue;
            const std::optional<HousePart> part = part_of(q.kind);
            if (!part || q.face >= built->faces.size()) continue;
            const gm::V3& n = built->faces[q.face].normal;
            // the walls' colour as the tile has it: its first wall's face's base (every wall's is the building's)
            if (*part == HousePart::wall && q.kind == gm::PrimKind::cell && house->wall == 0) {
                house->wall = built->faces[q.face].base & 0xFF'FFFFU;
            }
            HouseFace face{.part = *part,
                           .normal = {n.x, n.y, n.z},
                           .first = static_cast<std::uint32_t>(house->points.size()),
                           .count = 3};
            house->points.push_back(local(q.p[0]));
            house->points.push_back(local(q.p[1]));
            house->points.push_back(local(q.p[2]));
            if ((q.flags & gm::prim_flag::triangle) == 0) {
                house->points.push_back(local(q.p[0]));
                house->points.push_back(local(q.p[2]));
                house->points.push_back(local(q.p[3]));
                face.count = 6;
            }
            house->faces.push_back(face);
        }
    }
    if (house->points.empty()) return nullptr;
    // the flat top's outline, which the board strokes
    for (const gm::Polygon& g : built->polygons) {
        if (g.kind != gm::PrimKind::top || g.ring.size() < 3) continue;
        for (const gm::V3& p : g.ring) house->rim.push_back(local(p));
        house->rim.push_back(local(g.ring.front()));
        break;
    }
    return house;
}

} // namespace atlas::scene
