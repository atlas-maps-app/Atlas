// The pack container (tiles/Directory.hpp, Chunks.hpp, Sections.hpp, Container.hpp) on packs written to D1's layout by
// the tests' writer: a pack opens, finds and unpacks its chunks, knows their reach, and verifies; each part refuses
// what it must, and skips what it does not know.
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstring>
#include <functional>
#include <memory>

namespace {

using namespace atlas::tiles;
using atlas::test::ChunkBytes;
using atlas::test::Sink;

constexpr std::array<std::uint8_t, 2> levels = {14, 12};

/// Two L14 chunks and one L12, raw bytes of any content (the container does not decode them), each with a reach.
std::vector<ChunkBytes> chunks() {
    std::vector<ChunkBytes> out;
    for (std::uint32_t i = 0; i < 2; ++i) {
        ChunkBytes c;
        c.tile = {14, 8414 + i, 5384};
        c.raw.assign(1000 + (i * 700), static_cast<std::byte>(i + 1));
        c.raw[7] = std::byte{0x42};
        c.count = 10 + i;
        const auto w = static_cast<std::int32_t>(48'800'000 + (i * 22'000));
        c.reach = {.box = {.south = 523'700'000, .west = w, .north = 523'720'000, .east = w + 25'000},
                   .top_cm = 2'400 + static_cast<std::int32_t>(i)};
        out.push_back(c);
    }
    ChunkBytes c;
    c.tile = {12, 2103, 1346};
    c.raw.assign(200'000, std::byte{9}); // two zstd blocks
    c.count = 3;
    c.reach = {.box = {.south = 523'500'000, .west = 48'700'000, .north = 523'800'000, .east = 49'100'000},
               .top_cm = 0};
    out.push_back(c);
    return out;
}

Result<Pack> open(std::vector<std::byte> bytes) { return Pack::open(std::make_unique<MemorySource>(std::move(bytes))); }

TEST_CASE("tiles: a render pack opens, finds and unpacks its chunks, and verifies") {
    const auto cs = chunks();
    auto pack = open(atlas::test::pack_bytes(cs, levels));
    ATLAS_REQUIRE(pack, describe(pack.error()));
    CHECK(pack->format() == format_render);
    CHECK(pack->is_render());
    CHECK(pack->sections().size() == 5);
    ATLAS_REQUIRE(pack->chunks().chunks().size() == 3);
    CHECK(pack->chunks().zooms().size() == 2);
    CHECK(pack->chunks().level(14).size() == 2);
    CHECK(pack->chunks().level(12).size() == 1);
    CHECK(pack->chunks().level(12)[0].tile == TileKey{12, 2103, 1346});
    CHECK(pack->chunks().level(13).empty());
    for (std::size_t i = 0; i < cs.size(); ++i) CHECK(pack->chunks().chunks()[i].reach == cs[i].reach);
    ATLAS_REQUIRE(pack->head() != nullptr);
    ATLAS_REQUIRE(!pack->head()->views().empty());
    CHECK(pack->head()->views()[0].tolerance == 0);
    ATLAS_REQUIRE(pack->head()->bands().size() == 1);
    CHECK(pack->head()->never_simplified());
    CHECK(pack->head()->profile == Profile::living);
    CHECK(pack->head()->levels == std::vector<std::uint8_t>{14, 12});
    CHECK(pack->head()->region == "amsterdam");
    CHECK(pack->head()->frame.kz == 110540.0);
    CHECK(atlas::test::held(atlas::test::held(pack->head()->view("home")).camera.find("dist")).real() == 430.0);
    CHECK(!pack->head()->view("away"));
    ATLAS_REQUIRE(pack->style() != nullptr);
    CHECK(atlas::test::held(atlas::test::held(pack->style()->table("LAND")).find("park")).string() == "#a8c890");
    CHECK(!pack->style()->table("SURF"));
    Zstd zstd;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    for (const auto& c : cs) {
        const ChunkRef* ref = pack->chunks().find(c.tile);
        ATLAS_REQUIRE(ref != nullptr);
        CHECK(ref->count == c.count);
        ATLAS_REQUIRE(pack->unpack(*ref, zstd, raw, scratch));
        CHECK(raw == c.raw);
    }
    CHECK(pack->chunks().find({14, 1, 1}) == nullptr);
    CHECK(pack->verify());
}

TEST_CASE("tiles: a damaged chunk, section or table is caught") {
    const auto cs = chunks();
    const auto bytes = atlas::test::pack_bytes(cs, levels);
    auto pack = open(bytes);
    ATLAS_REQUIRE(pack);
    const ChunkRef first = pack->chunks().chunks()[0];
    const Section chunk_section = *find_section(pack->sections(), section_chunks);

    SUBCASE("a byte of a chunk's data: its SHA-256 differs, and verify() names the chunks section") {
        auto b = bytes;
        b[static_cast<std::size_t>(first.offset) + 20] ^= std::byte{1}; // inside the first raw block's data
        auto damaged = open(b);
        ATLAS_REQUIRE(damaged); // the chunks section's CRC-32 is not read at open
        Zstd zstd;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        auto u = damaged->unpack(first, zstd, raw, scratch);
        ATLAS_REQUIRE(!u);
        CHECK(u.error().fault == Fault::damaged);
        CHECK(u.error().site == Site::chunk);
        CHECK(u.error().detail == first.tile.packed());
        auto v = damaged->verify();
        ATLAS_REQUIRE(!v);
        CHECK(v.error().fault == Fault::damaged);
        CHECK(damaged->unpack(first, zstd, raw, scratch, false)); // unchecked, it decompresses
    }
    SUBCASE("a byte of the chunk table: its CRC-32 differs") {
        auto b = bytes;
        const Section t = *find_section(pack->sections(), section_tiles);
        b[static_cast<std::size_t>(t.offset) + 3] ^= std::byte{0x10};
        auto damaged = open(b);
        ATLAS_REQUIRE(!damaged);
        CHECK(damaged.error().fault == Fault::damaged);
        CHECK(damaged.error().site == Site::chunk_table);
    }
    SUBCASE("a byte of rhead") {
        auto b = bytes;
        const Section h = *find_section(pack->sections(), section_head);
        b[static_cast<std::size_t>(h.offset) + 10] ^= std::byte{0x10};
        auto damaged = open(b);
        ATLAS_REQUIRE(!damaged);
        CHECK(damaged.error().site == Site::head);
        CHECK(damaged.error().fault == Fault::damaged);
    }
    SUBCASE("a pack cut short") {
        auto b = bytes;
        b.resize(static_cast<std::size_t>(chunk_section.offset) + 10);
        auto damaged = open(b);
        ATLAS_REQUIRE(!damaged);
        CHECK(damaged.error().fault == Fault::truncated);
    }
}

TEST_CASE("tiles: the container's header refuses what is not a render or element pack") {
    auto bytes = atlas::test::pack_bytes(chunks(), levels);
    SUBCASE("magic") {
        bytes[0] = std::byte{'B'};
        CHECK(open(bytes).error().fault == Fault::bad_magic);
    }
    SUBCASE("format 1, the renders' layout without a chunk table") {
        bytes[9] = std::byte{1};
        CHECK(open(bytes).error().fault == Fault::unsupported);
    }
    SUBCASE("format 4") {
        bytes[9] = std::byte{4};
        CHECK(open(bytes).error().fault == Fault::bad_version);
    }
    SUBCASE("a manifest past the pack") {
        bytes[12] = std::byte{0x7F};
        CHECK(open(bytes).error().site == Site::header);
    }
    SUBCASE("less than a header") {
        bytes.resize(12);
        CHECK(open(bytes).error().fault == Fault::truncated);
    }
}

/// A pack of `cs` whose chunk table is `table` instead of its own (the section's CRC-32 made to match). Where the pack
/// cannot be edited so, the test case has failed and gets the pack unedited.
std::vector<std::byte> with_table(const std::vector<ChunkBytes>& cs, const Sink& table,
                                  std::uint8_t format = format_render) {
    auto bytes = atlas::test::pack_bytes(cs, levels, format);
    auto pack = open(bytes);
    ATLAS_REQUIRE_OR_RETURN(bytes, pack, describe(pack.error()));
    const Section* tiles = find_section(pack->sections(), section_tiles);
    ATLAS_REQUIRE_OR_RETURN(bytes, tiles != nullptr);
    const Section t = *tiles;
    ATLAS_REQUIRE_OR_RETURN(bytes, table.bytes.size() == t.stored); // the same length, so every offset stays
    std::ranges::copy(table.bytes, bytes.begin() + static_cast<std::ptrdiff_t>(t.offset));
    // the directory's CRC-32 of the table: the first entry is `tiles`, its CRC four bytes before its method byte
    const auto header = read_container_header(bytes, bytes.size());
    ATLAS_REQUIRE_OR_RETURN(bytes, header, describe(header.error()));
    const std::size_t crc_at = static_cast<std::size_t>(header->directory_offset()) + 1 + section_tiles.size() + 24;
    const std::uint32_t crc = crc32(table.bytes);
    for (std::size_t k = 0; k < 4; ++k) bytes[crc_at + k] = static_cast<std::byte>(crc >> (24U - (8U * k)));
    return bytes;
}

/// The table of `cs`, with `edit` applied to entry `i` before it is written.
Sink table(const std::vector<ChunkBytes>& cs, std::size_t i, const std::function<void(ChunkRef&)>& edit) {
    Sink t;
    t.varu(cs.size());
    for (std::size_t k = 0; k < cs.size(); ++k) {
        ChunkRef r;
        r.tile = cs[k].tile;
        r.stored = static_cast<std::uint32_t>(atlas::test::zstd_frame(cs[k].raw).size());
        r.raw = static_cast<std::uint32_t>(cs[k].raw.size());
        r.count = cs[k].count;
        r.sha256 = Sha256::of(cs[k].raw);
        if (k == i) edit(r);
        t.u8(r.tile.z);
        t.varu(r.tile.x);
        t.varu(r.tile.y);
        t.varu(r.stored);
        t.varu(r.raw);
        t.varu(r.count);
        t.raw(r.sha256);
    }
    return t;
}

TEST_CASE("tiles: the chunk table refuses tiles off the grid, levels out of order, duplicates and wrong sums") {
    const auto cs = chunks();
    auto refused = [&](const Sink& t, Fault fault) {
        auto p = open(with_table(cs, t));
        ATLAS_REQUIRE(!p);
        CHECK(p.error().site == Site::chunk_table);
        CHECK(p.error().fault == fault);
    };
    SUBCASE("unchanged, it opens") {
        CHECK(open(with_table(cs, table(cs, 0, [](ChunkRef&) {}))));
    }
    SUBCASE("a column past its zoom") {
        refused(table(cs, 2, [](ChunkRef& r) { r.tile.x = 4100; }), Fault::bad_value);
    } // 2^12 = 4096
    SUBCASE("a zoom rhead does not list") {
        refused(table(cs, 2, [](ChunkRef& r) { r.tile.z = 13; }), Fault::bad_value);
    }
    SUBCASE("a tile twice") {
        refused(table(cs, 1, [](ChunkRef& r) { r.tile.x = 8414; }), Fault::duplicate);
    }
    SUBCASE("stored lengths that do not fill the section") {
        refused(table(cs, 0, [](ChunkRef& r) { r.stored -= 1; }), Fault::bad_count);
    }
    SUBCASE("raw lengths that do not add up to the section's") {
        refused(table(cs, 0, [](ChunkRef& r) { r.raw += 1; }), Fault::bad_count);
    }
    SUBCASE("a zoom past the finest") {
        refused(table(cs, 2, [](ChunkRef& r) { r.tile.z = 25; }), Fault::bad_value);
    }
}

TEST_CASE("tiles: levels must come in rhead's order, each in one run") {
    auto cs = chunks();
    std::swap(cs[0], cs[2]); // L12 first, then L14: against rhead's [14, 12]
    auto p = open(atlas::test::pack_bytes(cs, levels));
    ATLAS_REQUIRE(!p);
    CHECK(p.error().site == Site::chunk_table);
    CHECK(p.error().fault == Fault::bad_value);
}

TEST_CASE("tiles: an element pack (format 2) opens as a container, and the core does not unpack its chunks") {
    const auto cs = chunks();
    auto pack = open(atlas::test::pack_bytes(cs, levels, format_elements));
    ATLAS_REQUIRE(pack, describe(pack.error()));
    CHECK(pack->format() == format_elements);
    CHECK(!pack->is_render());
    CHECK(pack->head() == nullptr);
    CHECK(pack->chunks().chunks().size() == 3);
    Zstd zstd;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    auto u = pack->unpack(pack->chunks().chunks()[0], zstd, raw, scratch);
    ATLAS_REQUIRE(!u);
    CHECK(u.error().fault == Fault::unsupported);
}

TEST_CASE("tiles: reach gives every chunk its box and top, in the table's order, and refuses what it must") {
    const auto cs = chunks();
    const Reach& near = cs[0].reach;
    // a view that meets the first L14 chunk's box at its north-east corner, and one just past it
    const GridBox corner{
        .south = near.box.north, .west = near.box.east, .north = near.box.north + 10, .east = near.box.east + 10};
    CHECK(near.meets(corner));
    CHECK(!near.meets(
        {.south = near.box.north + 1, .west = near.box.west, .north = near.box.north + 9, .east = near.box.east}));
    CHECK(!near.meets(
        {.south = near.box.south, .west = near.box.east + 1, .north = near.box.north, .east = near.box.east + 9}));

    SUBCASE("entries longer than this payload's are read for their first twenty bytes") {
        auto p = open(atlas::test::pack_bytes(cs, levels, format_render, {.reach = atlas::test::reach_bytes(cs, 28)}));
        ATLAS_REQUIRE(p, describe(p.error()));
        for (std::size_t i = 0; i < cs.size(); ++i) CHECK(p->chunks().chunks()[i].reach == cs[i].reach);
    }
    auto refused = [&](const std::optional<std::vector<std::byte>>& reach, Fault fault, bool omit = false) {
        auto p = open(atlas::test::pack_bytes(cs, levels, format_render, {.reach = reach, .omit_reach = omit}));
        ATLAS_REQUIRE(!p);
        CHECK(p.error().site == Site::reach);
        CHECK(p.error().fault == fault);
    };
    SUBCASE("a render pack without it") { refused(std::nullopt, Fault::missing, true); }
    SUBCASE("another magic") {
        auto r = atlas::test::reach_bytes(cs);
        r[3] = std::byte{'S'};
        refused(r, Fault::bad_magic);
    }
    SUBCASE("a payload version past this reader") {
        auto r = atlas::test::reach_bytes(cs);
        r[4] = std::byte{2};
        refused(r, Fault::bad_version);
    }
    SUBCASE("entries shorter than twenty bytes") {
        auto r = atlas::test::reach_bytes(cs);
        r[6] = std::byte{19};
        refused(r, Fault::bad_value);
    }
    SUBCASE("an entry count other than the table's") {
        auto two = cs;
        two.pop_back();
        refused(atlas::test::reach_bytes(two), Fault::bad_count);
    }
    SUBCASE("a byte more or less than its entries") {
        auto r = atlas::test::reach_bytes(cs);
        r.push_back(std::byte{0});
        refused(r, Fault::bad_count);
        r.resize(r.size() - 2);
        refused(r, Fault::bad_count);
    }
    SUBCASE("less than its header") { refused(std::vector<std::byte>(11, std::byte{0}), Fault::truncated); }
    SUBCASE("an empty box: its south past its north, or its west past its east") {
        auto bad = cs;
        std::swap(bad[1].reach.box.south, bad[1].reach.box.north);
        refused(atlas::test::reach_bytes(bad), Fault::bad_value);
        bad = cs;
        std::swap(bad[2].reach.box.west, bad[2].reach.box.east);
        refused(atlas::test::reach_bytes(bad), Fault::bad_value);
    }
    SUBCASE("a damaged byte: its CRC-32 differs") {
        auto bytes = atlas::test::pack_bytes(cs, levels);
        auto p = open(bytes);
        ATLAS_REQUIRE(p);
        const Section r = *find_section(p->sections(), section_reach);
        bytes[static_cast<std::size_t>(r.offset) + 13] ^= std::byte{0x01};
        auto damaged = open(bytes);
        ATLAS_REQUIRE(!damaged);
        CHECK(damaged.error().site == Site::reach);
        CHECK(damaged.error().fault == Fault::damaged);
    }
}

TEST_CASE("tiles: a render pack's chunk is a tile of at most 4 MiB; an element pack's chunks may be larger") {
    auto big = chunks();
    big[2].raw.assign(std::size_t{max_tile_bytes} + 1, std::byte{9});
    auto p = open(atlas::test::pack_bytes(big, levels));
    ATLAS_REQUIRE(!p);
    CHECK(p.error().site == Site::chunk_table);
    CHECK(p.error().fault == Fault::too_big);
    auto e = open(atlas::test::pack_bytes(big, levels, format_elements));
    ATLAS_REQUIRE(e, describe(e.error()));
    CHECK(e->chunks().chunks()[2].raw == max_tile_bytes + 1);
}

TEST_CASE(
    "tiles: a section the reader does not know is skipped, whatever its method; a known one's method is checked") {
    const auto cs = chunks();
    atlas::test::SectionBytes aux{
        .name = "aux", .stored = std::vector<std::byte>(9, std::byte{7}), .raw = 9, .method = Method::zstd};
    aux.method = static_cast<Method>(7); // a method of a later format
    auto p = open(atlas::test::pack_bytes(cs, levels, format_render, {.extra = {aux}}));
    ATLAS_REQUIRE(p, describe(p.error()));
    CHECK(p->sections().size() == 6);
    CHECK(p->verify()); // its CRC-32 holds all the same
}

TEST_CASE("tiles: the directory refuses unknown methods, sections past the pack and a section twice") {
    Sink d;
    auto entry = [&](std::string_view name, std::uint64_t offset, std::uint64_t stored, std::uint8_t method) {
        d.utf8(name);
        d.u64be(offset);
        d.u64be(stored);
        d.u64be(stored);
        d.u32be(0);
        d.u8(method);
    };
    entry("tiles", 100, 10, 0);
    entry("chunks", 110, 50, 2);
    auto ok = read_directory(d.bytes, 1000);
    ATLAS_REQUIRE(ok);
    CHECK(ok->size() == 2);
    CHECK(find_section(*ok, "chunks")->method == Method::zstd);
    CHECK(read_directory(d.bytes, 159).error().fault == Fault::truncated); // the chunks run past a 159-byte pack
    entry("tiles", 160, 1, 0);
    CHECK(read_directory(d.bytes, 1000).error().fault == Fault::duplicate);
    Sink m;
    m.utf8("aux");
    m.u64be(0);
    m.u64be(0);
    m.u64be(0);
    m.u32be(0);
    m.u8(3);
    CHECK(read_directory(m.bytes, 1000)); // a section the core does not read keeps its method
    for (const std::string_view known : {section_tiles, section_reach, section_chunks, section_head, section_style}) {
        Sink k;
        k.utf8(known);
        k.u64be(0);
        k.u64be(0);
        k.u64be(0);
        k.u32be(0);
        k.u8(3);
        CHECK_MESSAGE(read_directory(k.bytes, 1000).error().fault == Fault::bad_value, known);
    }
    Sink cut = m;
    cut.bytes.resize(10);
    CHECK(read_directory(cut.bytes, 1000).error().fault == Fault::truncated);
}

TEST_CASE("tiles: rhead gives each view its tolerance and the live map its bands, and a frame its ladder index") {
    const std::vector<HeadBand> bands = {
        {.tolerance = 3, .up_to = 300.0}, {.tolerance = 7, .up_to = 900.0}, {.tolerance = 12, .up_to = std::nullopt}};
    auto h = read_head(atlas::test::head_bytes(levels, Profile::region, 5, bands));
    ATLAS_REQUIRE(h, describe(h.error()));
    ATLAS_REQUIRE(h->views().size() == 1);
    CHECK(h->views()[0].tolerance == 5);
    ATLAS_REQUIRE(h->bands().size() == 3);
    CHECK(h->bands()[0].tolerance == 3);
    CHECK(h->bands()[1].up_to == 900.0);
    CHECK(!h->bands()[2].up_to);
    CHECK(!h->never_simplified());
    // a camera snapped onto a view takes its index; any other the first band whose end its distance is below
    CHECK(h->tolerance_of("home", 5000.0) == 5);
    CHECK(!h->tolerance_of("away", 10.0));
    CHECK(h->tolerance_of(std::nullopt, 10.0) == 3);
    CHECK(h->tolerance_of(std::nullopt, 299.99) == 3);
    CHECK(h->tolerance_of(std::nullopt, 300.0) == 7);
    CHECK(h->tolerance_of(std::nullopt, 900.0) == 12);
    CHECK(h->tolerance_of(std::nullopt, 1e9) == 12);
    CHECK(h->tolerance_of(std::nullopt, std::nan("")) == 12);
    // the living map: one band at index 0, never simplified
    auto living = read_head(atlas::test::head_bytes(levels));
    ATLAS_REQUIRE(living);
    CHECK(living->never_simplified());
    CHECK(living->tolerance_of(std::nullopt, 50.0) == 0);

    SUBCASE("no band") {
        CHECK(read_head(atlas::test::head_bytes(levels, Profile::region, 0, {})).error().fault == Fault::missing);
    }
    SUBCASE("a view or a band past the ladder") {
        CHECK(read_head(atlas::test::head_bytes(levels, Profile::region, 14)).error().fault == Fault::bad_value);
        CHECK(read_head(atlas::test::head_bytes(levels, Profile::region, 0, {{.tolerance = 14, .up_to = std::nullopt}}))
                  .error()
                  .fault == Fault::bad_value);
    }
    SUBCASE("a band that ends at NaN") {
        const std::vector<HeadBand> nan = {{.tolerance = 1, .up_to = std::nan("")},
                                           {.tolerance = 2, .up_to = std::nullopt}};
        CHECK(read_head(atlas::test::head_bytes(levels, Profile::region, 0, nan)).error().fault == Fault::bad_value);
    }
    SUBCASE("a band's end cut off") {
        auto b = atlas::test::head_bytes(levels, Profile::region, 0, bands);
        b.resize(b.size() - 3);
        CHECK(read_head(b).error().fault == Fault::truncated);
    }
}

TEST_CASE("tiles: rhead and style refuse what they must") {
    auto head = atlas::test::head_bytes(levels);
    CHECK(read_head(head));
    // the living map's head ends: the camera's map (type byte, count, "dist", its double), the view's tolerance, one
    // band at index 0
    constexpr std::size_t tail = 1 + 1 + 1 + 4 + 1 + 8 + 1 + 1 + 1;
    SUBCASE("magic") {
        head[0] = std::byte{0};
        CHECK(read_head(head).error().fault == Fault::bad_magic);
    }
    SUBCASE("a payload version past this reader") {
        head[4] = std::byte{2};
        CHECK(read_head(head).error().fault == Fault::bad_version);
    }
    SUBCASE("an unknown profile") {
        head[6] = std::byte{4};
        CHECK(read_head(head).error().fault == Fault::bad_value);
    }
    SUBCASE("levels from coarse to fine") {
        head[8] = std::byte{12};
        head[9] = std::byte{14};
        CHECK(read_head(head).error().fault == Fault::bad_value);
    }
    SUBCASE("a byte left over") {
        head.push_back(std::byte{0});
        CHECK(read_head(head).error().fault == Fault::left_over);
    }
    SUBCASE("a view's camera that is not a map") {
        ATLAS_REQUIRE(head[head.size() - tail] == std::byte{8});
        head[head.size() - tail] = std::byte{7}; // the camera's type byte: a list, not a map
        CHECK(!read_head(head));
    }
    auto style = atlas::test::style_bytes();
    CHECK(read_style(style));
    style[4] = std::byte{9};
    CHECK(read_style(style).error().fault == Fault::bad_version);
}

TEST_CASE("tiles: a zstd frame must be one frame that fills its chunk exactly") {
    Zstd zstd;
    std::vector<std::byte> raw(100, std::byte{3});
    const auto frame = atlas::test::zstd_frame(raw);
    std::vector<std::byte> out(100);
    CHECK(zstd.frame(frame, out, Site::chunk));
    CHECK(out == raw);
    std::vector<std::byte> small(99);
    CHECK(zstd.frame(frame, small, Site::chunk).error().fault == Fault::bad_count);
    auto two = frame;
    two.insert(two.end(), frame.begin(), frame.end());
    CHECK(zstd.frame(two, out, Site::chunk).error().fault == Fault::damaged);
    auto cut = frame;
    cut.resize(frame.size() - 1);
    CHECK(zstd.frame(cut, out, Site::chunk).error().fault == Fault::damaged);
    std::vector<std::byte> empty;
    const auto none = atlas::test::zstd_frame(empty);
    CHECK(zstd.frame(none, empty, Site::chunk));
}

} // namespace
