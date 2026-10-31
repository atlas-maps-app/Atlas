#include "sun/Buckets.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "sun/Hulls.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileBox.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <sstream>
#include <system_error>
#include <utility>

#if defined(__ANDROID__)
#include <sys/resource.h>
#endif

namespace atlas::sun {

namespace {

namespace pm = atlas::pymath;
namespace fs = std::filesystem;

/// Samples a worker takes at once: large enough that taking one costs nothing, small enough that four threads share
/// a tile.
constexpr std::uint32_t piece = 4096;

[[nodiscard]] std::uint64_t bits_of(double v) noexcept { return std::bit_cast<std::uint64_t>(v); }

/// The disk file's magic ("ATSB", little-endian).
constexpr std::uint32_t file_magic = 0x4253'5441U;

void put_u16(std::string& out, std::uint16_t v) {
    for (unsigned k = 0; k < 2; ++k) out.push_back(static_cast<char>((v >> (8U * k)) & 0xFFU));
}
void put_u32(std::string& out, std::uint32_t v) {
    for (unsigned k = 0; k < 4; ++k) out.push_back(static_cast<char>((v >> (8U * k)) & 0xFFU));
}
void put_u64(std::string& out, std::uint64_t v) {
    for (unsigned k = 0; k < 8; ++k) out.push_back(static_cast<char>((v >> (8U * k)) & 0xFFU));
}
[[nodiscard]] std::uint64_t get(std::string_view in, std::size_t at, unsigned bytes) noexcept {
    std::uint64_t v = 0;
    for (unsigned k = 0; k < bytes; ++k) v |= std::uint64_t{static_cast<unsigned char>(in[at + k])} << (8U * k);
    return v;
}

/// What a bucket's file is about, written first in it and hashed for its name.
[[nodiscard]] std::string head_of(const tiles::Sha256Digest& chunk, std::uint8_t level, const Sun& sun) {
    std::string h;
    put_u32(h, file_magic);
    put_u16(h, sample_format);
    h.push_back(static_cast<char>(level));
    for (const std::byte b : chunk) h.push_back(static_cast<char>(b));
    put_u64(h, bits_of(sun.x));
    put_u64(h, bits_of(sun.y));
    put_u64(h, bits_of(sun.z));
    return h;
}

} // namespace

Sun sun_vector(double azimuth, double elevation) noexcept {
    const double a = pm::radians(azimuth);
    const double e = pm::radians(elevation);
    // vnorm(V3(sin(a) * cos(e), sin(e), -cos(a) * cos(e)))
    const double vx = pm::sin(a) * pm::cos(e);
    const double vy = pm::sin(e);
    const double vz = -pm::cos(a) * pm::cos(e);
    const double l = pm::sqrt((vx * vx) + (vy * vy) + (vz * vz));
    return {.x = vx / l, .y = vy / l, .z = vz / l};
}

Bucket bucket_of(const Sun& sun, bool named) noexcept {
    std::uint64_t k = 0xCBF2'9CE4'8422'2325ULL; // FNV-1a over the three doubles' bits
    for (const double v : {sun.x, sun.y, sun.z}) {
        std::uint64_t b = bits_of(v);
        for (int i = 0; i < 8; ++i) {
            k = (k ^ (b & 0xFFU)) * 0x100'0000'01B3ULL;
            b >>= 8U;
        }
    }
    return {.sun = sun, .key = k, .named = named};
}

Bucket bucket_of(const style::ThemeBlock& block) noexcept {
    const Sun exact{.x = block.sun_vector[0], .y = block.sun_vector[1], .z = block.sun_vector[2]};
    if (block.moment != style::MomentName::other) return bucket_of(exact, true);
    if (!(exact.y > 0)) return bucket_of(Sun{.x = 0.0, .y = -1.0, .z = 0.0}, false);
    const double az = static_cast<double>(pm::round(block.sun_azimuth / bucket_degrees)) * bucket_degrees;
    const double el = static_cast<double>(pm::round(block.sun_elevation / bucket_degrees)) * bucket_degrees;
    return bucket_of(sun_vector(az, pm::max(bucket_degrees, el)), false);
}

Scene::Scene(std::vector<SunTile> tiles, double scene_maxh, std::vector<tiles::Sha256Digest> chunks)
    : tiles_(std::move(tiles)), chunks_(std::move(chunks)) {
    Grid::Builder b;
    base_.reserve(tiles_.size());
    for (const SunTile& t : tiles_) {
        base_.push_back(static_cast<PrismId>(b.size()));
        for (std::uint32_t s = 0; s < t.solids.size(); ++s) b.add(t.solids.xs(s), t.solids.zs(s), t.solids.h[s]);
    }
    grid_ = std::move(b).build(scene_maxh);
}

std::uint64_t Scene::context(std::size_t t) const noexcept {
    if (t >= chunks_.size()) return 0;
    std::uint64_t c = 0;
    for (std::size_t i = 0; i < 8; ++i) c |= std::uint64_t{std::to_integer<std::uint8_t>(chunks_[t][i])} << (8 * i);
    return c;
}

std::optional<std::uint32_t> Scene::find(tiles::TileKey key) const noexcept {
    for (std::size_t t = 0; t < tiles_.size(); ++t) {
        if (tiles_[t].key == key) return static_cast<std::uint32_t>(t);
    }
    return std::nullopt;
}

std::vector<tiles::TileKey> halo_of(const tiles::Pack& pack, std::span<const tiles::TileKey> resident, double metres) {
    std::vector<tiles::TileKey> out;
    const tiles::RenderHead* head = pack.head();
    if (head == nullptr) return out;
    // the reach in grid units (1e-7°), widened by a unit for the rounding: metres = degrees · k, so a grid unit is
    // k / 1e7 metres
    const auto units = [&](double k) { return static_cast<std::int32_t>(std::ceil((metres / k) * 1e7)) + 1; };
    const std::int32_t dlat = units(head->frame.kz);
    const std::int32_t dlon = units(head->frame.kx);
    std::vector<tiles::GridBox> boxes;
    for (const tiles::TileKey& k : resident) {
        const tiles::ChunkRef* c = pack.chunks().find(k);
        if (c == nullptr) continue;
        const tiles::GridBox& b = c->reach.box;
        boxes.push_back({b.south - dlat, b.west - dlon, b.north + dlat, b.east + dlon});
    }
    if (boxes.empty()) return out;
    for (const tiles::ChunkRef& c : pack.chunks().level(resident.front().z)) {
        if (std::ranges::any_of(boxes, [&](const tiles::GridBox& b) { return c.reach.meets(b); })) {
            out.push_back(c.tile);
        }
    }
    return out;
}

bool sample_lit(const Grid& grid, PrismId base, const TileSamples& s, std::uint32_t i, const Sun& sun) noexcept {
    const std::uint32_t f = s.face[i];
    if (f != no_face) {
        // the port's `vdot(n, L) > 0`: a face turned from the sun is unlit, and no march is made
        const double d = (s.nx[f] * sun.x) + (s.ny[f] * sun.y) + (s.nz[f] * sun.z);
        if (!(d > 0)) return false;
    }
    if (s.skip[i] == no_march) return true; // another tile's place: lit as it faces the sun
    double px = s.x[i];
    double py = s.y[i];
    double pz = s.z[i];
    if (s.kind[i] == Kind::crown) { // V3(nx + L[0] * r, h * 0.75 + L[1] * r, nz + L[2] * r)
        const double r = s.crown_r[i - s.crowns_first];
        px = px + (sun.x * r);
        py = py + (sun.y * r);
        pz = pz + (sun.z * r);
    }
    const PrismId skip = s.skip[i] == no_solid ? no_prism : base + s.skip[i];
    return lit(grid, sun, px, py, pz, skip);
}

void evaluate(const Grid& grid, PrismId base, const TileSamples& s, const Sun& sun, std::uint32_t from,
              std::uint32_t to, std::span<std::uint64_t> words) noexcept {
    ATLAS_DEBUG_ASSERT(from % 64 == 0 && to <= s.size() && (to + 63) / 64 <= words.size());
    for (std::uint32_t w = from / 64; w < (to + 63) / 64; ++w) words[w] = 0;
    for (std::uint32_t i = from; i < to; ++i) {
        if (sample_lit(grid, base, s, i, sun)) words[i / 64] |= std::uint64_t{1} << (i % 64);
    }
}

std::vector<TileJob> nearest_first(const Scene& scene, const tiles::Frame& frame, double cx, double cz,
                                   std::span<const std::uint8_t> visible, double window_reach) {
    struct Ranked {
        std::uint32_t tile;
        bool visible;
        double distance;
    };
    std::vector<Ranked> ranked;
    const auto tiles = scene.tiles();
    for (std::size_t t = 0; t < tiles.size(); ++t) {
        const tiles::TileKey k = tiles[t].key;
        const double x0 = frame.x(tiles::column_edge(k.z, k.x));
        const double x1 = frame.x(tiles::column_edge(k.z, k.x + 1));
        const double z0 = frame.z(tiles::row_edge(k.z, k.y));
        const double z1 = frame.z(tiles::row_edge(k.z, k.y + 1));
        const double dx = pm::max(0.0, pm::max(x0 - cx, cx - x1));
        const double dz = pm::max(0.0, pm::max(z0 - cz, cz - z1));
        ranked.push_back({static_cast<std::uint32_t>(t), t < visible.size() && visible[t] != 0, pm::hypot(dx, dz)});
    }
    std::ranges::stable_sort(ranked, [](const Ranked& a, const Ranked& b) {
        if (a.visible != b.visible) return a.visible;
        return a.distance < b.distance;
    });
    std::vector<TileJob> jobs;
    jobs.reserve(ranked.size());
    for (const Ranked& r : ranked) jobs.push_back({r.tile, r.distance <= window_reach});
    return jobs;
}

void run(const Scene& scene, const Bucket& bucket, std::span<const TileJob> jobs, unsigned threads,
         const std::function<void(std::uint32_t tile, TileBits bits)>& done) {
    struct Piece {
        std::uint32_t job;
        std::uint32_t from;
        std::uint32_t to;
    };
    std::vector<Piece> pieces;
    std::vector<TileBits> bits(jobs.size());
    const auto left = std::make_unique<std::atomic<std::uint32_t>[]>(jobs.size());
    for (std::size_t j = 0; j < jobs.size(); ++j) {
        const TileSamples& s = scene.tiles()[jobs[j].tile].samples;
        TileBits& b = bits[j];
        b.words.assign((std::size_t{s.size()} + 63) / 64, 0);
        b.count = s.size();
        b.windows_from = s.windows_first;
        b.crowns_from = s.crowns_first;
        b.windows = jobs[j].windows;
        b.bucket = bucket.key;
        b.sun = bucket.sun;
        const std::uint32_t end = jobs[j].windows ? s.size() : s.windows_first;
        std::uint32_t n = 0;
        for (std::uint32_t from = 0; from < end; from += piece) {
            pieces.push_back({static_cast<std::uint32_t>(j), from, std::min(end, from + piece)});
            ++n;
        }
        left[j].store(n, std::memory_order_relaxed);
        if (n == 0) done(jobs[j].tile, std::move(b));
    }
    std::atomic<std::size_t> next{0};
    const auto work = [&] {
        for (;;) {
            const std::size_t p = next.fetch_add(1, std::memory_order_relaxed);
            if (p >= pieces.size()) return;
            const Piece& pc = pieces[p];
            const std::uint32_t t = jobs[pc.job].tile;
            evaluate(scene.grid(), scene.base(t), scene.tiles()[t].samples, bucket.sun, pc.from, pc.to,
                     bits[pc.job].words);
            // the piece that completes a tile hands it on: every other piece's words were written before their
            // own decrement, which this one's acquires
            if (left[pc.job].fetch_sub(1, std::memory_order_acq_rel) == 1) done(t, std::move(bits[pc.job]));
        }
    };
    {
        std::vector<std::jthread> helpers;
        const std::size_t extra = std::min<std::size_t>(threads > 1 ? threads - 1 : 0, pieces.size());
        helpers.reserve(extra);
        for (std::size_t k = 0; k < extra; ++k) helpers.emplace_back(work);
        work();
    } // the helpers join here
}

DiskCache::DiskCache(std::string dir, std::uint64_t budget) : dir_(std::move(dir)), budget_(budget) {
    std::error_code ec;
    fs::create_directories(dir_, ec);
}

std::string DiskCache::path(const tiles::Sha256Digest& chunk, std::uint8_t level, const Bucket& bucket) const {
    const std::string h = head_of(chunk, level, bucket.sun);
    const tiles::Sha256Digest name = tiles::Sha256::of(std::as_bytes(std::span(h)));
    return (fs::path(dir_) / (tiles::hex(name).substr(0, 32) + ".sun")).string();
}

std::optional<TileBits> DiskCache::load(const tiles::Sha256Digest& chunk, std::uint8_t level, const Bucket& bucket,
                                        std::uint32_t count, bool windows) const {
    const std::string p = path(chunk, level, bucket);
    const std::lock_guard lock(mutex_);
    std::ifstream in(p, std::ios::binary);
    if (!in) return std::nullopt;
    std::ostringstream all;
    all << in.rdbuf();
    const std::string data = all.str();
    const std::string head = head_of(chunk, level, bucket.sun);
    const std::size_t words = (std::size_t{count} + 63) / 64;
    const std::size_t want = head.size() + 1 + 4 + 4 + 4 + (8 * words);
    if (data.size() != want || !data.starts_with(head)) return std::nullopt;
    TileBits b;
    std::size_t at = head.size();
    b.windows = data[at] != 0;
    at += 1;
    if (get(data, at, 4) != count) return std::nullopt;
    b.count = count;
    at += 4;
    b.windows_from = static_cast<std::uint32_t>(get(data, at, 4));
    at += 4;
    b.crowns_from = static_cast<std::uint32_t>(get(data, at, 4));
    at += 4;
    if (windows && !b.windows) return std::nullopt;
    b.words.resize(words);
    for (std::size_t w = 0; w < words; ++w) b.words[w] = get(data, at + (8 * w), 8);
    b.bucket = bucket.key;
    b.sun = bucket.sun;
    return b;
}

bool DiskCache::store(const tiles::Sha256Digest& chunk, std::uint8_t level, const TileBits& bits) {
    const Bucket bucket = bucket_of(bits.sun, true);
    std::string data = head_of(chunk, level, bits.sun);
    data.push_back(static_cast<char>(bits.windows ? 1 : 0));
    put_u32(data, bits.count);
    put_u32(data, bits.windows_from);
    put_u32(data, bits.crowns_from);
    for (const std::uint64_t w : bits.words) put_u64(data, w);
    const std::string p = path(chunk, level, bucket);
    const std::lock_guard lock(mutex_);
    const std::string tmp = p + ".part";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) return false;
        out.write(data.data(), static_cast<std::streamsize>(data.size()));
        if (!out) return false;
    }
    std::error_code ec;
    fs::rename(tmp, p, ec); // a reader sees the whole file or none
    if (ec) {
        fs::remove(tmp, ec);
        return false;
    }
    trim();
    return true;
}

void DiskCache::trim() {
    std::error_code ec;
    struct File {
        fs::path path;
        fs::file_time_type when;
        std::uintmax_t size;
    };
    std::vector<File> files;
    std::uint64_t total = 0;
    for (const auto& entry : fs::directory_iterator(dir_, ec)) {
        if (entry.path().extension() != ".sun") continue;
        std::error_code e2;
        const auto size = entry.file_size(e2);
        const auto when = entry.last_write_time(e2);
        if (e2) continue;
        files.push_back({entry.path(), when, size});
        total += size;
    }
    if (total <= budget_) return;
    std::ranges::sort(files, [](const File& a, const File& b) { return a.when < b.when; });
    for (const File& f : files) {
        if (total <= budget_) break;
        std::error_code e3;
        if (fs::remove(f.path, e3)) total -= f.size;
    }
}

std::uint64_t march_check() {
    pm::PyRandom rnd(20261018);
    std::uint64_t hash = 0xCBF2'9CE4'8422'2325ULL;
    const auto feed = [&hash](std::uint64_t v, int bytes) {
        for (int i = 0; i < bytes; ++i) {
            hash = (hash ^ (v & 0xFFU)) * 0x100'0000'01B3ULL;
            v >>= 8U;
        }
    };
    constexpr int prisms = 600;
    Grid::Builder b;
    std::vector<double> xs;
    std::vector<double> zs;
    for (int k = 0; k < prisms; ++k) {
        const double cx = rnd.uniform(-300.0, 300.0);
        const double cz = rnd.uniform(-300.0, 300.0);
        const int nv = 3 + static_cast<int>(rnd.random() * 6);
        const double r = rnd.uniform(3.0, 22.0);
        const double h = rnd.random() < 0.05 ? rnd.uniform(40.0, 90.0) : rnd.uniform(3.0, 30.0);
        xs.clear();
        zs.clear();
        for (int i = 0; i < nv; ++i) {
            const double a = (((2 * pm::pi) * i) / nv) + rnd.uniform(-0.25, 0.25);
            const double rr = r * rnd.uniform(0.55, 1.0);
            xs.push_back(cx + (rr * pm::cos(a)));
            zs.push_back(cz + (rr * pm::sin(a)));
        }
        b.add(xs, zs, h);
    }
    const double maxh = b.maxh();
    const Grid grid = std::move(b).build(maxh);
    struct Point {
        double x;
        double y;
        double z;
        PrismId skip;
    };
    std::vector<Point> points;
    for (int i = 0; i < 5000; ++i) {
        const double x = rnd.uniform(-320.0, 320.0);
        const double y = rnd.uniform(0.0, 45.0);
        const double z = rnd.uniform(-320.0, 320.0);
        const PrismId skip = rnd.random() < 0.4 ? static_cast<PrismId>(rnd.random() * prisms) : no_prism;
        points.push_back({x, y, z, skip});
    }
    constexpr std::array<std::array<double, 2>, 5> suns = {
        {{135.0, 35.0}, {250.0, 12.0}, {190.0, 55.0}, {80.0, 3.0}, {300.0, 70.0}}};
    for (const auto& [az, el] : suns) {
        const Sun sun = sun_vector(az, el);
        feed(bits_of(sun.x), 8);
        feed(bits_of(sun.y), 8);
        feed(bits_of(sun.z), 8);
        for (const Point& p : points) feed(lit(grid, sun, p.x, p.y, p.z, p.skip) ? 1 : 0, 1);
    }
    std::vector<double> hx;
    std::vector<double> hz;
    std::vector<double> ox;
    std::vector<double> oz;
    for (int s = 0; s < 300; ++s) {
        const int n = 3 + static_cast<int>(rnd.random() * 30);
        hx.clear();
        hz.clear();
        for (int i = 0; i < n; ++i) {
            if (i > 0 && rnd.random() < 0.2) { // a point again, as a footprint and its shadow share corners
                const auto j = static_cast<std::size_t>(rnd.random() * i);
                hx.push_back(hx[j]);
                hz.push_back(hz[j]);
                continue;
            }
            double x = rnd.uniform(-50.0, 50.0);
            const double z = rnd.uniform(-50.0, 50.0);
            if (rnd.random() < 0.05) x = -0.0; // the set's key is 0.0 for it
            hx.push_back(x);
            hz.push_back(z);
        }
        ox.clear();
        oz.clear();
        hull2(hx, hz, ox, oz);
        feed(ox.size(), 4);
        for (std::size_t i = 0; i < ox.size(); ++i) {
            feed(bits_of(ox[i]), 8);
            feed(bits_of(oz[i]), 8);
        }
    }
    return hash;
}

std::string march_check_line() {
    const std::uint64_t h = march_check();
    const auto hex = [](std::uint64_t v) {
        std::string s(16, '0');
        for (int i = 15; i >= 0; --i) {
            s[static_cast<std::size_t>(i)] = "0123456789abcdef"[v & 0xFU];
            v >>= 4U;
        }
        return s;
    };
    if (h == march_check_expected) return "sun " + hex(h) + " ok";
    return "sun " + hex(h) + " FAIL expected " + hex(march_check_expected);
}

tiles::Result<std::uint64_t> bundled_check(const tiles::Pack& pack) {
    const tiles::RenderHead* head = pack.head();
    if (head == nullptr || pack.style() == nullptr || head->levels.empty()) {
        return std::unexpected(tiles::error(tiles::Fault::missing, tiles::Site::none));
    }
    const Region region = region_of(*pack.style());
    constexpr std::uint8_t level = 0;
    const std::uint8_t z = head->levels.front();
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(4U << 20U, Growth::grows, "sun check");
    std::vector<Deck> decks;
    for (const tiles::ChunkRef& c : pack.chunks().level(z)) {
        arena.reset();
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) return std::unexpected(tile.error());
        collect_decks(*tile, *head, level, decks);
    }
    std::vector<SunTile> tiles;
    for (const tiles::ChunkRef& c : pack.chunks().level(z)) {
        arena.reset();
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) return std::unexpected(tile.error());
        tiles.push_back(make_tile(*tile, *head, region, level, decks));
    }
    const auto maxh = scene_maxh(pack, level);
    if (!maxh) return std::unexpected(maxh.error());
    const Scene scene(std::move(tiles), *maxh);
    std::vector<TileJob> jobs;
    for (std::uint32_t t = 0; t < scene.tiles().size(); ++t) jobs.push_back({t, true});
    std::vector<TileBits> got(scene.tiles().size());
    std::mutex m;
    const unsigned threads = std::clamp(std::thread::hardware_concurrency(), 1U, 4U);
    run(scene, bucket_of(bundled_sun, true), jobs, threads, [&](std::uint32_t t, TileBits bits) {
        const std::lock_guard lock(m);
        got[t] = std::move(bits);
    });
    tiles::Sha256 sha;
    const auto put = [&sha](std::uint64_t v) {
        std::array<std::byte, 8> b{};
        for (unsigned k = 0; k < 8; ++k) b[k] = static_cast<std::byte>((v >> (8U * k)) & 0xFFU);
        sha.update(b);
    };
    put(march_check());
    for (std::size_t t = 0; t < scene.tiles().size(); ++t) {
        put(scene.tiles()[t].key.packed());
        put(got[t].count);
        for (const std::uint64_t w : got[t].words) put(w);
    }
    for (const SunTile& t : scene.tiles()) {
        const TileShadows sh = shadows_of(t, bundled_sun, region);
        put(sh.size());
        for (std::uint32_t p = 0; p < sh.size(); ++p) {
            put((std::uint64_t{static_cast<std::uint8_t>(sh.kind[p])} << 32U) | sh.owner[p]);
            const auto xs = sh.xs(p);
            const auto zs = sh.zs(p);
            put(xs.size());
            for (std::size_t i = 0; i < xs.size(); ++i) {
                put(bits_of(xs[i]));
                put(bits_of(zs[i]));
            }
        }
    }
    const tiles::Sha256Digest d = sha.finish();
    std::uint64_t h = 0;
    for (std::size_t k = 0; k < 8; ++k) h = (h << 8U) | static_cast<std::uint64_t>(d[k]);
    return h;
}

namespace {

[[nodiscard]] std::string hex16(std::uint64_t v) {
    std::string s(16, '0');
    for (int i = 15; i >= 0; --i) {
        s[static_cast<std::size_t>(i)] = "0123456789abcdef"[v & 0xFU];
        v >>= 4U;
    }
    return s;
}

} // namespace

std::string sun_line(const tiles::Pack& pack) {
    const auto h = bundled_check(pack);
    if (!h) return "sun unavailable: " + tiles::describe(h.error());
    if (*h == bundled_check_expected) return "sun " + hex16(*h) + " ok";
    std::string line = "sun " + hex16(*h) + " FAIL expected " + hex16(bundled_check_expected);
    if (const std::uint64_t m = march_check(); m != march_check_expected) line += " march " + hex16(m);
    return line;
}

std::string sun_line(int fd, std::uint64_t offset, std::uint64_t length) {
    auto source = tiles::MappedSource::open(fd, offset, length);
    if (!source) return "sun unavailable: " + tiles::describe(source.error());
    auto pack = tiles::Pack::open(std::move(*source));
    if (!pack) return "sun unavailable: " + tiles::describe(pack.error());
    return sun_line(*pack);
}

Worker::Worker(BitTiles& bits, unsigned threads, DiskCache* cache, std::function<double()> clock)
    : bits_(bits), threads_(std::max(1U, threads)), cache_(cache), clock_(std::move(clock)),
      thread_([this](const std::stop_token& stop) { loop(stop); }) {}

Worker::~Worker() {
    thread_.request_stop();
    // the jthread's destruction (first of the members, being the last) joins it before anything it uses goes
}

void Worker::request(std::shared_ptr<const Scene> scene, const Bucket& bucket, std::vector<TileJob> jobs,
                     unsigned threads) {
    {
        const std::lock_guard lock(mutex_);
        next_ = Request{.scene = std::move(scene),
                        .bucket = bucket,
                        .jobs = std::move(jobs),
                        .threads = threads == 0 ? threads_ : std::min(threads, threads_)};
    }
    wake_.notify_one();
}

void Worker::wait_idle() {
    std::unique_lock lock(mutex_);
    idle_.wait(lock, [&] { return !busy_ && !next_; });
}

std::uint64_t Worker::runs() const {
    const std::lock_guard lock(mutex_);
    return runs_;
}

std::uint64_t Worker::cached() const {
    const std::lock_guard lock(mutex_);
    return cached_;
}

void Worker::loop(const std::stop_token& stop) {
#if defined(__ANDROID__)
    // Background priority (ENGINE.md gain 8), its helpers' too: made on the render thread, this thread would keep its
    // display priority, and the helpers would take it from this one.
    (void)setpriority(PRIO_PROCESS, 0, 10);
#endif
    std::unique_lock lock(mutex_);
    for (;;) {
        if (!wake_.wait(lock, stop, [&] { return next_.has_value(); })) return; // stop asked
        std::optional<Request> taken = std::exchange(next_, std::nullopt);
        if (!taken) continue;
        Request r = std::move(*taken);
        busy_ = true;
        lock.unlock();
        std::uint64_t from_disk = 0;
        std::vector<TileJob> jobs;
        jobs.reserve(r.jobs.size());
        // The run's bits, published together once it is done: one wake of the frame loop a run, so at rest its tiles
        // come in one frame. Published a tile at a time, each woke a frame of its own wherever the GPU's frames starve
        // this thread between them (CI's emulator: a frame a tile, each 2 s on lavapipe with the water's light on).
        std::vector<std::pair<tiles::TileKey, TileBits>> made;
        made.reserve(r.jobs.size());
        std::mutex made_mutex;
        for (const TileJob& j : r.jobs) {
            const tiles::Sha256Digest* chunk = r.scene->chunk(j.tile);
            if (r.bucket.named && cache_ != nullptr && chunk != nullptr) {
                const SunTile& t = r.scene->tiles()[j.tile];
                if (auto b = cache_->load(*chunk, t.level, r.bucket, t.samples.size(), j.windows)) {
                    b->quays = t.quays;
                    b->reflections = t.reflections;
                    b->context = r.scene->context(j.tile);
                    made.emplace_back(t.key, std::move(*b));
                    ++from_disk;
                    continue;
                }
            }
            jobs.push_back(j);
        }
        run(*r.scene, r.bucket, jobs, r.threads, [&](std::uint32_t tile, TileBits bits) {
            const SunTile& t = r.scene->tiles()[tile];
            if (r.bucket.named && cache_ != nullptr) {
                if (const tiles::Sha256Digest* chunk = r.scene->chunk(tile)) (void)cache_->store(*chunk, t.level, bits);
            }
            bits.quays = t.quays;
            bits.reflections = t.reflections;
            bits.context = r.scene->context(tile);
            const std::scoped_lock held(made_mutex);
            made.emplace_back(t.key, std::move(bits));
        });
        bits_.publish(std::move(made), clock_());
        lock.lock();
        busy_ = false;
        ++runs_;
        cached_ += from_disk;
        idle_.notify_all();
    }
}

} // namespace atlas::sun
