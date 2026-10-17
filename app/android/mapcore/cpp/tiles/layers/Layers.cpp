// The registry of the layers payload version 1 defines, with what the reader derives from each schema at compile time.
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Labels.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"
#include "tiles/layers/Water.hpp"

#include <array>

namespace atlas::tiles {
namespace {

template <const auto& Columns>
struct Derived {
    static constexpr auto dump = detail::dump_order(Columns);
    static constexpr auto keep = detail::keep_of(Columns);
    static constexpr auto keep_for = detail::keep_for(Columns);
    static_assert(Columns.size() <= max_columns);
};

template <const auto& Columns>
constexpr LayerSchema make(LayerId id, std::string_view label) {
    return {id,
            label,
            Columns,
            std::span(Derived<Columns>::dump).first(detail::fields(Columns)),
            Derived<Columns>::keep,
            Derived<Columns>::keep_for};
}

constexpr LayerSchema building_schema = make<building::columns>(LayerId::building, "building");
constexpr LayerSchema part_schema = make<building::columns>(LayerId::part, "part");
constexpr LayerSchema ground_schema = make<ground::columns>(LayerId::ground, "ground");
constexpr LayerSchema water_schema = make<water::columns>(LayerId::water, "water");
constexpr LayerSchema quay_schema = make<quay::columns>(LayerId::quay, "quay");
constexpr LayerSchema road_schema = make<road::columns>(LayerId::road, "road");
constexpr LayerSchema line_schema = make<line::columns>(LayerId::line, "line");
constexpr LayerSchema tree_schema = make<tree::columns>(LayerId::tree, "tree");
constexpr LayerSchema thing_schema = make<thing::columns>(LayerId::thing, "thing");
constexpr LayerSchema item_schema = make<item::columns>(LayerId::item, "item");
constexpr LayerSchema label_schema = make<label::columns>(LayerId::label, "label");
constexpr LayerSchema variant_schema = make<variant::columns>(LayerId::variant, "variant");

constexpr std::array<const LayerSchema*, 12> all = {
    &building_schema, &part_schema, &ground_schema, &water_schema, &quay_schema,  &road_schema,
    &line_schema,     &tree_schema, &thing_schema,  &item_schema,  &label_schema, &variant_schema,
};

} // namespace

const LayerSchema* schema_of(LayerId id) noexcept {
    for (const LayerSchema* s : all) {
        if (s->id == id) return s;
    }
    return nullptr;
}

std::span<const LayerSchema* const> schemas() noexcept { return all; }

} // namespace atlas::tiles
