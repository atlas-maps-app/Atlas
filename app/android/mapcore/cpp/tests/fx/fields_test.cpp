// The screen-wide fields (fx/Fields.hpp) held to the port at fields.inc's sizes (D16's S1–S4 and the owner's
// phone's family): each field's noise, by the lanes and (at S1) by the scalar reference, and the bytes the core keeps
// of it, against the hashes mapview's ByteTablesExportTest took with the port's Turbulence and SvgFilterRaster; the
// kept bytes against keep(), SvgFilterRaster's steps one by one; the disk cache's round trip and its refusals; the
// worker; the VK facts line; and the time of the five at S1 on one core (ATLAS_TIMED=1: at most 300 ms, plan-vector
// §3, on a quiet machine; else printed).
#include "fx/Fields.hpp"
#include "fx/Noise.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <span>
#include <string>
#include <thread>
#include <vector>

namespace {

using namespace atlas::fx;

/// ThreadSanitizer slows the noise's arithmetic tenfold: under it the fields are held to the port at S1 alone, the
/// threads being what it checks; the other builds take every reference size.
#if defined(__has_feature)
#if __has_feature(thread_sanitizer)
constexpr bool thread_sanitizer = true;
#else
constexpr bool thread_sanitizer = false;
#endif
#else
constexpr bool thread_sanitizer = false;
#endif

/// The noise of field `f` over a whole frame of `size`, by the lanes or by the scalar reference, in four bands at once.
std::vector<Argb> noise_of(Field f, FieldSize size, bool lanes) {
    const FieldSpec& s = spec_of(f);
    const Lattice l(s.noise.seed);
    std::vector<Argb> out(static_cast<std::size_t>(size.width) * size.height);
    const Grid grid = grid_of(size.scale, size.scale);
    auto band = [&](std::uint32_t k) {
        const std::uint32_t y0 = size.height * k / 4;
        const std::uint32_t y1 = size.height * (k + 1) / 4;
        const Block block{.left = 0,
                          .top = static_cast<std::int32_t>(y0),
                          .width = static_cast<std::int32_t>(size.width),
                          .height = static_cast<std::int32_t>(y1 - y0)};
        const std::span<Argb> rows = std::span<Argb>(out).subspan(static_cast<std::size_t>(y0) * size.width);
        if (lanes) {
            fill_lanes(l, s.noise, grid, block, s.need, rows, size.width);
        } else {
            fill(l, s.noise, grid, block, s.need, rows, size.width);
        }
    };
    {
        std::vector<std::jthread> threads;
        for (std::uint32_t k = 1; k < 4; ++k) threads.emplace_back(band, k);
        band(0);
    }
    return out;
}

std::vector<std::uint8_t> little_endian(const std::vector<Argb>& v) {
    std::vector<std::uint8_t> out;
    out.reserve(v.size() * 4);
    for (const Argb p : v) {
        for (std::uint32_t b = 0; b < 4; ++b) out.push_back(static_cast<std::uint8_t>(p >> (8U * b)));
    }
    return out;
}

const FieldReference& reference(std::string_view name) {
    for (const FieldReference& r : field_references()) {
        if (r.name == name) return r;
    }
    FAIL("fields.inc has no size " << std::string(name));
    return field_references().front();
}

bool timed() {
    const char* t = std::getenv("ATLAS_TIMED"); // NOLINT(concurrency-mt-unsafe): read before any thread
    return t != nullptr && std::string(t) == "1";
}

} // namespace

TEST_CASE("fields.inc holds D16's sizes and the owner's phone's family") {
    const auto refs = field_references();
    REQUIRE(refs.size() >= 4);
    CHECK(reference("S1").size == size_of(1080, 2400, 1080.0 / 412.0));
    CHECK(reference("S2").size == size_of(1080, 2340, 1080.0 / 412.0));
    CHECK(reference("S3").size == size_of(1440, 3120, 1440.0 / 412.0));
    CHECK(reference("S4").size == size_of(1344, 2992, 1344.0 / 412.0));
    CHECK(reference_of(size_of(1080, 2400, 1080.0 / 412.0)) == &reference("S1"));
    CHECK(reference_of(size_of(1080, 2400, 2.5)) == nullptr);
}

TEST_CASE("every screen-wide field at every reference size is the port's, noise and kept bytes") {
    for (const FieldReference& ref : field_references()) {
        if (thread_sanitizer && ref.name != "S1") continue;
        CAPTURE(std::string(ref.name));
        const auto fields = make_fields(ref.size, MakeOptions{.threads = 4});
        for (std::size_t f = 0; f < field_count; ++f) {
            CAPTURE(std::string(screen_fields[f].name));
            CHECK(hash_of(little_endian(noise_of(static_cast<Field>(f), ref.size, true))) == ref.noise[f]);
            CHECK(hash_of(fields->bytes_of(static_cast<Field>(f))) == ref.kept[f]);
        }
    }
}

TEST_CASE("the scalar reference makes S1's fields too") {
    if (thread_sanitizer) return; // the lanes' S1 above, and the lanes against the reference in noise_test.cpp
    const FieldReference& s1 = reference("S1");
    const auto fields = make_fields(s1.size, MakeOptions{.threads = 4, .lanes = false});
    for (std::size_t f = 0; f < field_count; ++f) {
        CAPTURE(std::string(screen_fields[f].name));
        CHECK(hash_of(little_endian(noise_of(static_cast<Field>(f), s1.size, false))) == s1.noise[f]);
        CHECK(hash_of(fields->bytes_of(static_cast<Field>(f))) == s1.kept[f]);
    }
}

TEST_CASE("the kept bytes are SvgFilterRaster's steps one by one") {
    // make_fields reads tables of the steps; keep() takes them one by one (premultiply, unpremultiply, the matrices'
    // rows, the output's conversion): every pixel of a frame that is no reference size, odd in both directions.
    const FieldSize size = size_of(331, 517, 1.7);
    const auto fields = make_fields(size, MakeOptions{.threads = 2, .band_rows = 7});
    for (std::size_t f = 0; f < field_count; ++f) {
        const auto field = static_cast<Field>(f);
        const std::vector<Argb> noise = noise_of(field, size, false);
        std::size_t differ = 0;
        for (std::uint32_t y = 0; y < size.height; ++y) {
            for (std::uint32_t x = 0; x < size.width; ++x) {
                std::array<std::uint8_t, 2> expected{};
                keep(field, noise[(static_cast<std::size_t>(y) * size.width) + x], expected);
                const auto got = fields->at(field, x, y);
                if (got[0] != expected[0] || (spec_of(field).bytes == 2 && got[1] != expected[1])) ++differ;
            }
        }
        CAPTURE(std::string(spec_of(field).name));
        CHECK(differ == 0);
    }
}

TEST_CASE("the five at S1 on one core") {
    const FieldSize s1 = reference("S1").size;
    (void)make_fields(size_of(64, 64, 1.0), MakeOptions{}); // the tables and generators made once
    double best = 1e9;
    for (int run = 0; run < (timed() ? 3 : 1); ++run) {
        const auto started = std::chrono::steady_clock::now();
        const auto fields = make_fields(s1, MakeOptions{.threads = 1});
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
        best = ms < best ? ms : best;
        CHECK(fields->size() == s1);
    }
    (void)std::printf("fields: the five at S1 (1080 × 2400) on one core: %.1f ms (budget 300 ms%s)\n", best,
                      timed() ? ", ATLAS_TIMED=1" : ", not gated: ATLAS_TIMED=1 gates it");
    if (timed()) CHECK(best <= 300.0);
}

TEST_CASE("fields kept on disk come back mapped, the same bytes; a damaged or foreign file is made again") {
    namespace fs = std::filesystem;
    const fs::path dir = fs::path(ATLAS_TEST_SCRATCH) / "fields-disk";
    std::error_code ec;
    fs::remove_all(dir, ec);
    const FieldDisk disk(dir, std::uint64_t{4} << 20U);
    const FieldSize size = size_of(200, 300, 2.0);
    CHECK(disk.read(size) == nullptr);
    const auto made = make_fields(size, MakeOptions{.threads = 2});
    REQUIRE(disk.write(*made));
    const auto read = disk.read(size);
    ATLAS_REQUIRE(read != nullptr);
    CHECK(read->mapped());
    CHECK(std::ranges::equal(read->rgba(), made->rgba()));
    CHECK(std::ranges::equal(read->rg(), made->rg()));
    CHECK(std::ranges::equal(read->r(), made->r()));
    // another scale, another file
    CHECK(disk.read(size_of(200, 300, 2.5)) == nullptr);
    // one byte of the images flipped: refused (its CRC), so the worker makes the fields again
    {
        std::fstream f(disk.path(size), std::ios::in | std::ios::out | std::ios::binary);
        f.seekp(64 + 1000);
        f.put('\x5A');
    }
    CHECK(disk.read(size) == nullptr);
    // a cut file: refused
    REQUIRE(disk.write(*made));
    fs::resize_file(disk.path(size), 64 + 100, ec);
    CHECK(disk.read(size) == nullptr);
    // the budget: the least recently written go first, the last one stays
    for (std::uint32_t k = 0; k < 6; ++k) REQUIRE(disk.write(*make_fields(size_of(400, 400 + k, 1.0))));
    std::uintmax_t total = 0;
    std::size_t files = 0;
    for (const auto& e : fs::directory_iterator(dir)) {
        total += e.file_size();
        ++files;
    }
    CHECK(total <= (std::uint64_t{4} << 20U));
    CHECK(files >= 1);
    CHECK(disk.read(size_of(400, 405, 1.0)) != nullptr);
    fs::remove_all(dir, ec);
}

TEST_CASE("the worker publishes the latest size asked for, from disk the second time") {
    namespace fs = std::filesystem;
    const fs::path dir = fs::path(ATLAS_TEST_SCRATCH) / "fields-worker";
    std::error_code ec;
    fs::remove_all(dir, ec);
    auto disk = std::make_shared<const FieldDisk>(dir);
    const FieldSize a = size_of(160, 240, 1.5);
    const FieldSize b = size_of(240, 160, 1.5);
    {
        FieldWorker worker(disk, 3);
        CHECK(worker.latest() == nullptr);
        worker.request(a);
        worker.request(b); // replaces a when a has not begun
        worker.wait_idle();
        ATLAS_REQUIRE(worker.latest() != nullptr);
        CHECK(worker.latest()->size() == b);
        CHECK_FALSE(worker.last_from_disk());
        const auto v = worker.version();
        worker.request(b); // published: nothing to do
        worker.wait_idle();
        CHECK(worker.version() == v);
        worker.request(a);
        worker.wait_idle();
        CHECK(worker.latest()->size() == a);
    }
    FieldWorker again(disk, 2);
    again.request(b);
    again.wait_idle();
    ATLAS_REQUIRE(again.latest() != nullptr);
    CHECK(again.last_from_disk());
    CHECK(again.latest()->mapped());
    const auto fresh = make_fields(b);
    CHECK(std::ranges::equal(again.latest()->rgba(), fresh->rgba()));
    fs::remove_all(dir, ec);
}

TEST_CASE("VK facts' fields line") {
    const std::string s1 = fields_line(reference("S1").size, 4);
    CHECK(s1.starts_with("fields 1080x2400 cpu "));
    CHECK(s1.ends_with(" ok"));
    const std::string odd = fields_line(size_of(123, 45, 1.25), 2);
    CHECK(odd.ends_with(" unchecked"));
    (void)std::printf("%s\n", s1.c_str());
}
