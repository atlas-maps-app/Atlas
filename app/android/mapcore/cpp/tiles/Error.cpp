#include "tiles/Error.hpp"

#include <algorithm>
#include <format>

namespace atlas::tiles {

std::string_view TileError::column_name() const noexcept {
    const auto* const end = std::ranges::find(column, '\0');
    return {column.data(), static_cast<std::size_t>(end - column.begin())};
}

TileError error(Fault fault, Site site, std::uint64_t detail) noexcept {
    TileError e;
    e.fault = fault;
    e.site = site;
    e.detail = detail;
    return e;
}

TileError column_error(Fault fault, LayerId layer, std::string_view column, std::uint64_t detail) noexcept {
    TileError e = error(fault, Site::column, detail);
    e.layer = layer;
    const std::size_t n = std::min(column.size(), e.column.size() - 1);
    std::ranges::copy(column.substr(0, n), e.column.begin());
    return e;
}

std::string_view name_of(Fault fault) noexcept {
    switch (fault) {
    case Fault::truncated: return "the bytes end early";
    case Fault::overlong: return "an overlong varint";
    case Fault::overflow: return "a number out of range";
    case Fault::left_over: return "bytes left over";
    case Fault::bad_utf8: return "a string that is not UTF-8";
    case Fault::bad_magic: return "the wrong magic";
    case Fault::bad_version: return "a version this reader does not know";
    case Fault::bad_value: return "a value the field does not allow";
    case Fault::bad_count: return "counts that disagree";
    case Fault::duplicate: return "a duplicate";
    case Fault::missing: return "missing";
    case Fault::damaged: return "damaged (checksum)";
    case Fault::too_big: return "too big";
    case Fault::unsupported: return "not read by the core";
    case Fault::io: return "an I/O error";
    }
    return "an unknown fault";
}

std::string_view name_of(Site site) noexcept {
    switch (site) {
    case Site::none: return "pack";
    case Site::header: return "header";
    case Site::directory: return "directory";
    case Site::section: return "section";
    case Site::chunk_table: return "chunk table";
    case Site::chunk: return "chunk";
    case Site::head: return "rhead";
    case Site::style: return "style";
    case Site::reach: return "reach";
    case Site::tile: return "tile";
    case Site::strings: return "strings";
    case Site::cells: return "cells";
    case Site::layers: return "layers";
    case Site::column: return "column";
    case Site::value: return "value";
    case Site::cache: return "cache";
    case Site::file: return "file";
    }
    return "somewhere";
}

std::string_view name_of(LayerId layer) noexcept {
    switch (layer) {
    case LayerId::none: return "none";
    case LayerId::building: return "building";
    case LayerId::part: return "part";
    case LayerId::ground: return "ground";
    case LayerId::water: return "water";
    case LayerId::quay: return "quay";
    case LayerId::road: return "road";
    case LayerId::line: return "line";
    case LayerId::tree: return "tree";
    case LayerId::thing: return "thing";
    case LayerId::item: return "item";
    case LayerId::label: return "label";
    case LayerId::marking: return "marking";
    case LayerId::built: return "built";
    case LayerId::dem: return "dem";
    case LayerId::variant: return "variant";
    }
    return "unknown";
}

std::string describe(const TileError& e) {
    std::string out(name_of(e.site));
    if (e.site == Site::column || e.layer != LayerId::none) {
        out += std::format(" {}", name_of(e.layer));
        if (!e.column_name().empty()) out += std::format(".{}", e.column_name());
    }
    out += std::format(": {}", name_of(e.fault));
    if (e.detail != 0) out += std::format(" ({})", e.detail);
    return out;
}

} // namespace atlas::tiles
