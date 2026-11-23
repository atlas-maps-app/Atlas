// The Westerkerk's living pack as the core's stage P makes the app's own (its tiles/amsterdam.atlas, :app's
// generateFactsTiles through atlas_stagep, which VK facts' hash lines run over on the phones' Bionic): the repository's
// data/ams/raw.json loaded and cut by the core (load/Amsterdam.hpp, load/Cut.hpp), with the core's head and style for
// the living map (load/PackHead.hpp), written once a process into the tests' scratch folder: under a name of the
// process's own, then renamed over the last one, since ctest runs the tests that read it side by side and one that has
// the pack mapped reads past its end where another truncates the file it maps (SIGBUS); a rename leaves it the file it
// opened, the same bytes. The host's checks the emulator is held to (ground_check, the sun's bundled_check, twin_check;
// ci/hashes.env) run over it, since the core's buildings are its own (core-p14) and the bundled packs (ATLAS_TILES_DIR)
// stay the port's until cut 2.
#pragma once

#include "load/Amsterdam.hpp"
#include "load/Cut.hpp"
#include "load/PackHead.hpp"
#include "osm/Overpass.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/TileWriter.hpp"

#include <array>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <span>
#include <string>
#include <system_error>
#include <unistd.h>
#include <vector>

#ifndef ATLAS_TESTS_DIR
#error "the Westerkerk's pack needs ATLAS_TESTS_DIR, the tests' folder (CMakeLists.txt)"
#endif

namespace atlas::test {

/// The pack's path, or "" where it cannot be made (the reason on stderr).
[[nodiscard]] inline std::string westerkerk_core_pack() {
    static const std::string path = [] {
        namespace tl = atlas::tiles;
        std::ifstream in(std::string(ATLAS_TESTS_DIR) + "/../../../../../data/ams/raw.json", std::ios::binary);
        const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        const auto doc = osm::read_overpass(std::as_bytes(std::span(text.data(), text.size())));
        if (text.empty() || !doc) return std::string();
        const tl::Frame frame = load::westerkerk_frame();
        const auto scene = load::load_amsterdam(*doc, frame, load::amsterdam_palette());
        if (!scene) return std::string();
        const auto cut = load::cut_living(*scene, frame);
        if (!cut) return std::string();
        constexpr std::array<std::uint8_t, 1> levels = {14};
        const load::PackFacts facts{.profile = tl::Profile::living,
                                    .region = "amsterdam",
                                    .levels = levels,
                                    .frame = frame,
                                    .engine_version = "core",
                                    .source = "00"};
        const auto views = load::pack_views(facts);
        if (!views) return std::string();
        const auto sections = load::pack_sections(facts, *views);
        if (!sections) return std::string();
        std::vector<tl::PackTile> tiles;
        for (const tl::TileFill& t : *cut) {
            auto raw = tl::encode_tile(t);
            const auto reach = tl::reach_of(t);
            if (!raw || !reach) return std::string();
            std::uint32_t count = 0;
            for (const tl::LayerFill& l : t.layers()) count += l.count();
            tiles.push_back(
                {.key = tl::TileKey(t.level(), t.x(), t.y()), .raw = std::move(*raw), .count = count, .reach = *reach});
        }
        const auto bytes = tl::write_pack(tiles, {.levels = levels,
                                                  .manifest = R"({"layout":"render-tiles","payload":1})",
                                                  .head = sections->head,
                                                  .style = sections->style});
        if (!bytes) return std::string();
        const std::filesystem::path out = std::filesystem::path(ATLAS_TEST_SCRATCH) / "westerkerk-core.atlas";
        std::filesystem::create_directories(out.parent_path());
        std::filesystem::path own = out;
        own += "." + std::to_string(::getpid());
        {
            std::ofstream file(own, std::ios::binary | std::ios::trunc);
            file.write(
                reinterpret_cast<const char*>(bytes->data()), // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
                static_cast<std::streamsize>(bytes->size()));
            file.close();
            if (!file) return std::string();
        }
        std::error_code renamed;
        std::filesystem::rename(own, out, renamed);
        if (renamed) {
            std::error_code ignored;
            std::filesystem::remove(own, ignored);
            return std::string();
        }
        return out.string();
    }();
    return path;
}

} // namespace atlas::test
