// A pack's `style` and its style version as stage P derives them (load/PackStyle.hpp).
#include "load/PackStyle.hpp"

#include "gen/data/engine_style.hpp"
#include "load/Tables.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Json.hpp"
#include "tiles/Schema.hpp"
#include "tiles/SectionWriter.hpp"
#include "tiles/Sections.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Ground.hpp"

#include <array>
#include <cstdio>
#include <cstdlib>
#include <optional>
#include <utility>

namespace atlas::load {

namespace {

namespace tl = atlas::tiles;

/// StyleVersion.DERIVATION: bumped when stage P derives a value differently while the format stays the same.
constexpr int derivation = 1;

/// The layers whose tag keys `render_tags` lists (Schema.RENDER_TAGS), in its order.
constexpr std::array<tl::LayerId, 8> tagged = {tl::LayerId::building, tl::LayerId::part, tl::LayerId::ground,
                                               tl::LayerId::road,     tl::LayerId::line, tl::LayerId::tree,
                                               tl::LayerId::thing,    tl::LayerId::label};

/// The tag keys of a layer's `tag:` columns, in the schema's order.
std::vector<std::string_view> tag_keys(const tl::LayerSchema& schema) {
    std::vector<std::string_view> keys;
    for (const tl::ColumnSpec& c : schema.columns) {
        if (c.name.starts_with("tag:")) keys.push_back(c.name.substr(4));
    }
    return keys;
}

/// Java's Double.toString of a value between 10^-3 and 10^7 (the ladder's): the shortest decimal that reads back to
/// it, with ".0" where it has no fraction.
std::string java_double(double v) {
    std::array<char, 40> buf{};
    for (int digits = 1; digits <= 17; ++digits) {
        if (std::snprintf(buf.data(), buf.size(), "%.*g", digits, v) <= 0) return {};
        if (std::strtod(buf.data(), nullptr) == v) break;
    }
    std::string s(buf.data());
    if (s.find_first_of(".e") == std::string::npos) s += ".0";
    return s;
}

/// The style file read once, kept for the process (the bytes are the core's own, so it never changes).
struct EngineStyleFile {
    std::optional<tl::JsonValue> json;

    EngineStyleFile() {
        const auto bytes = std::as_bytes(std::span(data::engine_style));
        auto read = tl::read_json(bytes, {.comments = true});
        if (read && read->root().type() == tl::ValueType::map) json = std::move(*read);
    }
};

const EngineStyleFile& style_file() {
    static const EngineStyleFile file;
    return file;
}

/// The style file's tables written as `style` writes them.
void write_file_tables(tl::StyleOut& out, const tl::Value& file) {
    for (std::size_t i = 0; i < file.size(); ++i) out.table(file.key(i)).value(file.child(i));
}

} // namespace

tl::Value engine_style() {
    const EngineStyleFile& f = style_file();
    return f.json ? f.json->root() : tl::Value();
}

std::string style_version() {
    const tl::Value file = engine_style();
    if (file.is_null()) return {};
    tl::Sha256 md;
    auto put = [&](std::string_view s) {
        md.update(std::as_bytes(std::span(s.data(), s.size())));
        md.update(std::array{std::byte{0}});
    };
    put("payload " + std::to_string(tl::payload_version));
    for (const tl::LayerSchema* layer : tl::schemas()) {
        put("layer " + std::to_string(static_cast<int>(layer->id)) + " " + std::string(layer->label));
        for (const tl::ColumnSpec& c : layer->columns) {
            put(std::string(c.name) + " " + std::to_string(static_cast<int>(c.codec)));
        }
    }
    for (const tl::LayerId id : tagged) {
        const tl::LayerSchema* layer = tl::schema_of(id);
        std::string line = "tags " + std::string(layer->label) + " ";
        const std::vector<std::string_view> keys = tag_keys(*layer);
        for (std::size_t i = 0; i < keys.size(); ++i) {
            if (i > 0) line += ",";
            line += keys[i];
        }
        put(line);
    }
    std::string keep = "keep ";
    for (std::size_t i = 0; i < tl::keep::tolerances.size(); ++i) {
        if (i > 0) keep += ",";
        keep += java_double(tl::keep::tolerances[i]);
    }
    put(keep);
    put("style");
    tl::StyleOut tables;
    write_file_tables(tables, file);
    md.update(tables.bytes());
    const std::string digest = tl::hex(md.finish());
    return "s" + std::to_string(tl::payload_version) + "." + std::to_string(derivation) + "-" + digest.substr(0, 16);
}

ModuleState region_state(std::string_view name, std::int32_t tz_h) noexcept {
    ModuleState s;
    s.tz_h = tz_h;
    if (name == "bergen") {
        s.quay_col = "#7F796E";
        s.quay_course = 0.45;
    }
    if (name == "paris") {
        s.quay_col = "#B8AD96";
        s.quay_course = 0.45;
        s.win_style = "paris";
    } else if (name == "port" || name == "schiphol") {
        s.quay_col = "#8C8A85";
        s.quay_course = std::nullopt;
    }
    s.tone = true;
    return s;
}

std::optional<std::vector<std::byte>> style_section(const StyleFacts& facts) {
    const tl::Value file = engine_style();
    if (file.is_null()) return std::nullopt;
    tl::StyleOut out;
    out.table("keep").reals(tl::keep::tolerances);
    {
        tl::ValueSink v = out.table("render_tags");
        v.map(tagged.size());
        for (const tl::LayerId id : tagged) {
            const tl::LayerSchema* layer = tl::schema_of(id);
            v.key(layer->label);
            v.strings(tag_keys(*layer));
        }
    }
    write_file_tables(out, file);
    {
        tl::ValueSink v = out.table("material");
        v.map(materials.size());
        for (const Material& m : materials) {
            v.key(m.name);
            v.string(m.col);
        }
    }
    out.table("clay").string(clay);
    out.table("road_order").strings(road_order);
    {
        tl::ValueSink v = out.table("aero_order");
        v.map(aero_order.size());
        for (const AeroOrder& a : aero_order) {
            v.key(a.kind);
            v.integer(a.order);
        }
    }
    {
        namespace gk = tl::ground_key;
        tl::ValueSink v = out.table("ground_stages");
        const std::array<std::pair<std::string_view, std::uint32_t>, 6> stages = {{{"land", gk::land},
                                                                                   {"water", gk::water},
                                                                                   {"area", gk::area},
                                                                                   {"road", gk::road},
                                                                                   {"over", gk::over},
                                                                                   {"marks", gk::marks}}};
        v.map(stages.size());
        for (const auto& [name, stage] : stages) {
            v.key(name);
            v.integer(stage);
        }
        tl::ValueSink m = out.table("marks_classes");
        const std::array<std::pair<std::string_view, std::uint32_t>, 3> classes = {
            {{"road", gk::marks_road}, {"tram", gk::marks_tram}, {"canal", gk::marks_canal}}};
        m.map(classes.size());
        for (const auto& [name, klass] : classes) {
            m.key(name);
            m.integer(klass);
        }
    }
    {
        namespace b = tl::building;
        tl::ValueSink v = out.table("building_flags");
        const std::array<std::pair<std::string_view, std::uint64_t>, 8> flags = {
            {{"church", b::flag_church},
             {"warehouse", b::flag_warehouse},
             {"shop_floor", b::flag_shop_floor},
             {"construction", b::flag_construction},
             {"near_water", b::flag_near_water},
             {"houseboat", b::flag_houseboat},
             {"modern", b::flag_modern},
             {"wall_drawn", b::flag_wall_drawn}}};
        v.map(flags.size());
        for (const auto& [name, flag] : flags) {
            v.key(name);
            v.integer(static_cast<std::int64_t>(flag));
        }
    }
    {
        tl::ValueSink v = out.table("palette");
        v.map(3);
        v.key("brick_old");
        v.strings(facts.palette.brick_old);
        v.key("plaster");
        v.strings(facts.palette.plaster);
        v.key("brick_new");
        v.strings(facts.palette.brick_new);
    }
    {
        const ModuleState& s = facts.state;
        tl::ValueSink v = out.table("state");
        v.map(8);
        v.key("water_y");
        v.real(s.water_y);
        v.key("quay_col");
        v.string(s.quay_col);
        v.key("quay_course");
        if (s.quay_course) {
            v.real(*s.quay_course);
        } else {
            v.null();
        }
        v.key("tone");
        v.boolean(s.tone);
        v.key("win_style");
        if (s.win_style) {
            v.string(*s.win_style);
        } else {
            v.null();
        }
        v.key("tz_h");
        v.integer(s.tz_h);
        v.key("air_haze");
        v.boolean(s.air_haze);
        v.key("clouds_lit");
        v.boolean(s.clouds_lit);
    }
    {
        tl::ValueSink v = out.table("scene");
        if (facts.scene == nullptr) {
            v.map(0);
        } else {
            const SceneScalars& sc = *facts.scene;
            v.map(5);
            v.key("region");
            v.string(sc.region);
            v.key("view");
            v.string(sc.view);
            v.key("tradition");
            if (sc.tradition) {
                v.string(*sc.tradition);
            } else {
                v.null();
            }
            v.key("no_facades");
            v.boolean(true);
            v.key("R");
            v.value(sc.r);
        }
    }
    if (facts.tradition != nullptr) out.table("tradition").value(facts.tradition->root());
    return out.bytes();
}

} // namespace atlas::load
