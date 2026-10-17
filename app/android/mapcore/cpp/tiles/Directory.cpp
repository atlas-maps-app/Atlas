#include "tiles/Directory.hpp"

#include "tiles/Bytes.hpp"

#include <algorithm>

namespace atlas::tiles {

Result<ContainerHeader> read_container_header(std::span<const std::byte> bytes, std::uint64_t pack_size) {
    if (bytes.size() < container_header_bytes || pack_size < container_header_bytes) {
        return std::unexpected(error(Fault::truncated, Site::header));
    }
    constexpr std::string_view magic = "ATLASPCK";
    if (as_text(bytes.first(magic.size())) != magic) return std::unexpected(error(Fault::bad_magic, Site::header));
    Reader r(bytes.subspan(magic.size(), container_header_bytes - magic.size()));
    ContainerHeader h;
    (void)r.u8(); // zero, as the readers have always taken it
    h.format = r.u8();
    r.skip(2);
    h.manifest_bytes = r.u32be();
    h.directory_bytes = r.u32be();
    if (h.format == 1) return std::unexpected(error(Fault::unsupported, Site::header, h.format));
    if (h.format != format_elements && h.format != format_render) {
        return std::unexpected(error(Fault::bad_version, Site::header, h.format));
    }
    if (h.body_offset() > pack_size) return std::unexpected(error(Fault::truncated, Site::header, h.body_offset()));
    return h;
}

Result<std::vector<Section>> read_directory(std::span<const std::byte> bytes, std::uint64_t pack_size) {
    Reader r(bytes);
    std::vector<Section> out;
    while (r.ok() && r.remaining() > 0) {
        Section s;
        const std::string_view name = r.utf8();
        s.offset = r.u64be();
        s.stored = r.u64be();
        s.raw = r.u64be();
        s.crc = r.u32be();
        const std::uint8_t method = r.u8();
        if (!r.ok()) return std::unexpected(error(r.fault(), Site::directory, out.size()));
        const bool read = name == section_tiles || name == section_reach || name == section_chunks ||
                          name == section_head || name == section_style;
        if (read && method > static_cast<std::uint8_t>(Method::zstd)) {
            return std::unexpected(error(Fault::bad_value, Site::directory, method));
        }
        if (s.offset > pack_size || s.stored > pack_size - s.offset) {
            return std::unexpected(error(Fault::truncated, Site::section, out.size()));
        }
        if (find_section(out, name) != nullptr)
            return std::unexpected(error(Fault::duplicate, Site::directory, out.size()));
        s.name = std::string(name);
        s.method = static_cast<Method>(method);
        out.push_back(std::move(s));
    }
    return out;
}

const Section* find_section(std::span<const Section> sections, std::string_view name) noexcept {
    const auto it = std::ranges::find(sections, name, &Section::name);
    return it == sections.end() ? nullptr : &*it;
}

} // namespace atlas::tiles
