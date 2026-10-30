// The screen-wide noise fields (fx/Fields.hpp): what a pixel keeps, the making on worker threads, the disk cache,
// the worker and the reference hashes of fields.inc; and on the GPU (fx/FieldCache.hpp): the images, the byte tables
// and their provider of set 1.
#include "fx/FieldCache.hpp"

#include "base/assert.hpp"
#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/trace.hpp"
#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "fx/ByteTables.hpp"
#include "fx/Canopy.hpp"
#include "fx/Fields.hpp"
#include "fx/Mat.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Source.hpp"

#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstring>
#include <format>
#include <fstream>
#include <system_error>

namespace atlas::fx {

namespace fs = std::filesystem;

namespace {

using clock = std::chrono::steady_clock;

/// `mat`'s colour matrix (amsterdam.py: .33 .33 .33 0 0 three times, then 0 0 0 0 1): its float rows.
[[nodiscard]] const bytes::ColourMatrix& mat_matrix() noexcept {
    static const bytes::ColourMatrix m({0.33F, 0.33F, 0.33F, 0.0F, 0.0F, 0.33F, 0.33F, 0.33F, 0.0F, 0.0F,
                                        0.33F, 0.33F, 0.33F, 0.0F, 0.0F, 0.0F,  0.0F,  0.0F,  0.0F, 1.0F});
    return m;
}

/// The grain's `saturate` 0 (SvgParser's expansion: 0.213, 0.715, 0.072 in each colour row): vImage's integer path.
[[nodiscard]] const bytes::ColourMatrix& grain_matrix() noexcept {
    static const bytes::ColourMatrix m({0.213F, 0.715F, 0.072F, 0.0F, 0.0F, 0.213F, 0.715F, 0.072F, 0.0F, 0.0F,
                                        0.213F, 0.715F, 0.072F, 0.0F, 0.0F, 0.0F,   0.0F,   0.0F,   1.0F, 0.0F});
    return m;
}

/// The tables make_fields reads instead of keep's steps (the same bytes, held so by the tests): a channel premultiplied
/// and unpremultiplied again at each alpha, and the grain's grey from its matrix to its premultiplied sRGB at each
/// alpha.
struct KeepTables {
    std::array<std::uint8_t, bytes::table_size> round_trip{}; ///< a · 256 + c
    std::array<std::uint8_t, bytes::table_size> grain_srgb{}; ///< a · 256 + the matrix's grey
};

[[nodiscard]] const KeepTables& keep_tables() noexcept {
    static const KeepTables t = [] {
        KeepTables k;
        for (std::uint32_t a = 0; a < 256; ++a) {
            for (std::uint32_t c = 0; c < 256; ++c) {
                const std::size_t i = (static_cast<std::size_t>(a) << 8U) | c;
                k.round_trip[i] = static_cast<std::uint8_t>(
                    bytes::channel(bytes::unpremultiply(bytes::premultiply(bytes::pack(a, c, c, c))), 16));
                const Argb m = bytes::premultiply(bytes::pack(a, c, c, c));
                k.grain_srgb[i] = static_cast<std::uint8_t>(
                    bytes::channel(bytes::premultiply(bytes::unpremultiply_exact(m, false)), 16));
            }
        }
        return k;
    }();
    return t;
}

/// The generators of the nine fields, made once.
[[nodiscard]] const std::array<Lattice, field_count>& lattices() noexcept {
    static const std::array<Lattice, field_count> l{
        Lattice(screen_fields[0].noise.seed), Lattice(screen_fields[1].noise.seed),
        Lattice(screen_fields[2].noise.seed), Lattice(screen_fields[3].noise.seed),
        Lattice(screen_fields[4].noise.seed), Lattice(screen_fields[5].noise.seed),
        Lattice(screen_fields[6].noise.seed), Lattice(screen_fields[7].noise.seed),
        Lattice(screen_fields[8].noise.seed)};
    return l;
}

/// Float round half to even of 0 ≤ t < 2^22 (Math.rint as `clamped` takes it), exact with contraction off.
[[nodiscard]] float rint_small(float t) noexcept {
    constexpr float big = 8388608.0F; // 2^23: adding it leaves no fraction, rounded to even
    return (t + big) - big;
}

/// make_fields' kept bytes of one band: the noise of each field in `scratch`, `rows` rows from `y0`.
void keep_band(Field f, std::span<const Argb> noise, std::uint32_t width, std::uint32_t y0, std::uint32_t rows,
               ScreenFields& out) {
    const KeepTables& k = keep_tables();
    const std::size_t first = static_cast<std::size_t>(y0) * width;
    const std::size_t n = static_cast<std::size_t>(rows) * width;
    switch (f) {
    case Field::mat: {
        std::uint8_t* dst = out.rgba_mut().data() + (first * rgba_bytes);
        for (std::size_t i = 0; i < n; ++i) {
            const Argb p = noise[i];
            const std::size_t row = static_cast<std::size_t>(p >> 24U) << 8U;
            const auto r = static_cast<float>(k.round_trip[row | bytes::channel(p, 16)]);
            const auto g = static_cast<float>(k.round_trip[row | bytes::channel(p, 8)]);
            const auto b = static_cast<float>(k.round_trip[row | bytes::channel(p, 0)]);
            // the matrix's row: fma(.33, r, .33 · g), then fma(.33, b, t); its zero terms leave t as it is
            const float t = std::fma(0.33F, b, std::fma(0.33F, r, 0.33F * g));
            const float v =
                !(t > 0.0F) ? 0.0F : (t >= 255.0F ? 255.0F : rint_small(t)); // `clamped`: NaN and below 0 give 0
            dst[i * rgba_bytes] = static_cast<std::uint8_t>(v);
        }
        break;
    }
    case Field::grain: {
        std::uint8_t* dst = out.rgba_mut().data() + (first * rgba_bytes);
        for (std::size_t i = 0; i < n; ++i) {
            const Argb p = noise[i];
            const std::uint32_t a = p >> 24U;
            const std::size_t row = static_cast<std::size_t>(a) << 8U;
            const std::uint32_t r = k.round_trip[row | bytes::channel(p, 16)];
            const std::uint32_t g = k.round_trip[row | bytes::channel(p, 8)];
            const std::uint32_t b = k.round_trip[row | bytes::channel(p, 0)];
            // vImage: 55 r + 183 g + 18 b (each coefficient · 256, rounded), + 128, >> 8; the alpha row keeps a
            const std::uint32_t grey = std::min<std::uint32_t>(((55U * r) + (183U * g) + (18U * b) + 128U) >> 8U, 255U);
            dst[(i * rgba_bytes) + 1] = k.grain_srgb[row | grey];
            dst[(i * rgba_bytes) + 2] = static_cast<std::uint8_t>(a);
        }
        break;
    }
    case Field::cloud_shadows: {
        std::uint8_t* dst = out.rgba_mut().data() + (first * rgba_bytes);
        for (std::size_t i = 0; i < n; ++i) {
            const Argb p = noise[i];
            dst[(i * rgba_bytes) + 3] =
                k.round_trip[(static_cast<std::size_t>(p >> 24U) << 8U) | bytes::channel(p, 16)];
        }
        break;
    }
    case Field::ripple:
    case Field::crown:
    case Field::leaf: {
        // the displacements' red and green as they read them: the noise unpremultiplied, unconverted
        const bool rg = placement_of(f).image == 1;
        const std::size_t stride = rg ? rg_bytes : r_bytes;
        std::uint8_t* dst = (rg ? out.rg_mut() : out.r_mut()).data() + (first * stride) + placement_of(f).channel;
        for (std::size_t i = 0; i < n; ++i) {
            dst[i * stride] = static_cast<std::uint8_t>(bytes::channel(noise[i], 16));
            dst[(i * stride) + 1] = static_cast<std::uint8_t>(bytes::channel(noise[i], 8));
        }
        break;
    }
    case Field::glints:
    case Field::crown_tone: {
        std::uint8_t* dst = out.r_mut().data() + (first * r_bytes) + placement_of(f).channel;
        for (std::size_t i = 0; i < n; ++i) {
            const Argb p = noise[i];
            dst[i * r_bytes] = k.round_trip[(static_cast<std::size_t>(p >> 24U) << 8U) | bytes::channel(p, 16)];
        }
        break;
    }
    case Field::canopy: {
        // the matrix's alpha of the red it reads
        const std::array<std::uint8_t, 256>& alpha = canopy_alpha();
        std::uint8_t* dst = out.canopy_mut().data() + first;
        for (std::size_t i = 0; i < n; ++i) {
            const Argb p = noise[i];
            dst[i] = alpha[k.round_trip[(static_cast<std::size_t>(p >> 24U) << 8U) | bytes::channel(p, 16)]];
        }
        break;
    }
    }
}

// ---- the disk cache's file: a 64-byte head, then the four images.

constexpr std::uint32_t file_magic = 0x44465441; // "ATFD"
constexpr std::uint32_t file_format = 1;
constexpr std::size_t head_bytes = 64;

/// The eight specs, so a file of other parameters is never read as these (FNV-1a 64 over their numbers' bits).
[[nodiscard]] std::uint64_t specs_hash() noexcept {
    std::uint64_t h = 0xCBF29CE484222325ULL;
    auto add = [&h](std::uint32_t v) {
        for (std::uint32_t b = 0; b < 4; ++b) {
            h ^= (v >> (8U * b)) & 0xFFU;
            h *= 0x100000001B3ULL;
        }
    };
    for (const FieldSpec& s : screen_fields) {
        add(std::bit_cast<std::uint32_t>(s.noise.seed));
        add(std::bit_cast<std::uint32_t>(s.noise.base_x));
        add(std::bit_cast<std::uint32_t>(s.noise.base_y));
        add(static_cast<std::uint32_t>(s.noise.octaves));
        add(s.noise.fractal ? 1U : 0U);
        add(s.need);
        add(s.bytes);
    }
    return h;
}

void put_u32(std::span<std::uint8_t> b, std::size_t at, std::uint32_t v) noexcept {
    for (std::size_t i = 0; i < 4; ++i) b[at + i] = static_cast<std::uint8_t>(v >> (8U * i));
}

void put_u64(std::span<std::uint8_t> b, std::size_t at, std::uint64_t v) noexcept {
    for (std::size_t i = 0; i < 8; ++i) b[at + i] = static_cast<std::uint8_t>(v >> (8U * i));
}

[[nodiscard]] std::uint32_t get_u32(std::span<const std::byte> b, std::size_t at) noexcept {
    std::uint32_t v = 0;
    for (std::size_t i = 0; i < 4; ++i) v |= static_cast<std::uint32_t>(b[at + i]) << (8U * i);
    return v;
}

[[nodiscard]] std::uint64_t get_u64(std::span<const std::byte> b, std::size_t at) noexcept {
    std::uint64_t v = 0;
    for (std::size_t i = 0; i < 8; ++i) v |= static_cast<std::uint64_t>(b[at + i]) << (8U * i);
    return v;
}

[[nodiscard]] std::uint64_t pixels_of(FieldSize s) noexcept { return static_cast<std::uint64_t>(s.width) * s.height; }

/// The head of a size's file: magic, format, algorithm, width, height, scale bits, the specs' hash, the images'
/// bytes, their CRC-32, and the head's own CRC-32 over the bytes before it.
[[nodiscard]] std::array<std::uint8_t, head_bytes> head_of(FieldSize s, std::uint32_t crc) noexcept {
    std::array<std::uint8_t, head_bytes> h{};
    put_u32(h, 0, file_magic);
    put_u32(h, 4, file_format);
    put_u32(h, 8, fields_algorithm);
    put_u32(h, 12, s.width);
    put_u32(h, 16, s.height);
    put_u32(h, 20, std::bit_cast<std::uint32_t>(s.scale));
    put_u64(h, 24, specs_hash());
    put_u64(h, 32, pixels_of(s) * pixel_bytes);
    put_u32(h, 40, crc);
    put_u32(h, 60, tiles::crc32(std::as_bytes(std::span<const std::uint8_t>(h.data(), 60))));
    return h;
}

} // namespace

void keep(Field f, Argb p, std::span<std::uint8_t> out) noexcept {
    switch (f) {
    case Field::mat: {
        // the matrix reads its input unpremultiplied after the raster made it premultiplied, as WebKit does
        const Argb g = mat_matrix().apply(bytes::unpremultiply(bytes::premultiply(p)));
        out[0] = static_cast<std::uint8_t>(bytes::channel(g, 16));
        break;
    }
    case Field::grain: {
        const Argb m = grain_matrix().apply(bytes::unpremultiply(bytes::premultiply(p)));
        // the filter's result, written as premultiplied sRGB (SvgFilterRaster's output conversion, never corrected)
        const Argb s = bytes::premultiply(bytes::unpremultiply_exact(m, false));
        out[0] = static_cast<std::uint8_t>(bytes::channel(s, 16));
        out[1] = static_cast<std::uint8_t>(s >> 24U);
        break;
    }
    case Field::ripple:
    case Field::crown:
    case Field::leaf:
        out[0] = static_cast<std::uint8_t>(bytes::channel(p, 16));
        out[1] = static_cast<std::uint8_t>(bytes::channel(p, 8));
        break;
    case Field::glints:
    case Field::cloud_shadows:
    case Field::crown_tone:
        out[0] = static_cast<std::uint8_t>(bytes::channel(bytes::unpremultiply(bytes::premultiply(p)), 16));
        break;
    case Field::canopy: out[0] = canopy_alpha()[bytes::channel(bytes::unpremultiply(bytes::premultiply(p)), 16)]; break;
    }
}

ScreenFields::ScreenFields(FieldSize size)
    : size_(size), owned_rgba_(pixels_of(size) * rgba_bytes), owned_rg_(pixels_of(size) * rg_bytes),
      owned_r_(pixels_of(size) * r_bytes), owned_canopy_(pixels_of(size) * canopy_bytes), rgba_(owned_rgba_),
      rg_(owned_rg_), r_(owned_r_), canopy_(owned_canopy_) {}

ScreenFields::ScreenFields(FieldSize size, std::shared_ptr<const void> owner, bool mapped,
                           std::span<const std::uint8_t> rgba, std::span<const std::uint8_t> rg,
                           std::span<const std::uint8_t> r, std::span<const std::uint8_t> canopy)
    : size_(size), owner_(std::move(owner)), mapped_(mapped), rgba_(rgba), rg_(rg), r_(r), canopy_(canopy) {}

std::array<std::uint8_t, 2> ScreenFields::at(Field f, std::uint32_t x, std::uint32_t y) const noexcept {
    const std::size_t i = (static_cast<std::size_t>(y) * size_.width) + x;
    const Placement p = placement_of(f);
    std::span<const std::uint8_t> image = canopy_;
    std::size_t stride = canopy_bytes;
    if (p.image == 0) {
        image = rgba_;
        stride = rgba_bytes;
    } else if (p.image == 1) {
        image = rg_;
        stride = rg_bytes;
    } else if (p.image == 2) {
        image = r_;
        stride = r_bytes;
    }
    const std::size_t at = (i * stride) + p.channel;
    return {image[at], spec_of(f).bytes == 2 ? image[at + 1] : std::uint8_t{0}};
}

std::vector<std::uint8_t> ScreenFields::bytes_of(Field f) const {
    const std::uint32_t n = spec_of(f).bytes;
    std::vector<std::uint8_t> out(pixels_of(size_) * n);
    std::size_t k = 0;
    for (std::uint32_t y = 0; y < size_.height; ++y) {
        for (std::uint32_t x = 0; x < size_.width; ++x) {
            const auto b = at(f, x, y);
            out[k++] = b[0];
            if (n == 2) out[k++] = b[1];
        }
    }
    return out;
}

std::shared_ptr<const ScreenFields> make_fields(FieldSize size, const MakeOptions& options) {
    ATLAS_ASSERT(size.width > 0 && size.height > 0 && size.width <= 16384 && size.height <= 16384,
                 "fields of a frame of 1 to 16384 px a side");
    ATLAS_ASSERT(size.scale > 0.0F && std::isfinite(size.scale), "a positive filter scale");
    auto fields = std::make_shared<ScreenFields>(size);
    const Grid grid = grid_of(size.scale, size.scale);
    const std::array<Lattice, field_count>& l = lattices();
    (void)keep_tables(); // made before the threads start
    const std::uint32_t band = std::max<std::uint32_t>(options.band_rows, 1);
    const std::uint32_t bands = (size.height + band - 1) / band;
    std::atomic<std::uint32_t> next{0};
    auto work = [&] {
        std::vector<Argb> scratch(static_cast<std::size_t>(size.width) * band);
        for (std::uint32_t b = next.fetch_add(1, std::memory_order_relaxed); b < bands;
             b = next.fetch_add(1, std::memory_order_relaxed)) {
            const std::uint32_t y0 = b * band;
            const std::uint32_t rows = std::min(band, size.height - y0);
            const Block block{.left = 0,
                              .top = static_cast<std::int32_t>(y0),
                              .width = static_cast<std::int32_t>(size.width),
                              .height = static_cast<std::int32_t>(rows)};
            for (std::size_t f = 0; f < field_count; ++f) {
                const FieldSpec& s = screen_fields[f];
                if (options.lanes) {
                    fill_lanes(l[f], s.noise, grid, block, s.need, scratch, size.width);
                } else {
                    fill(l[f], s.noise, grid, block, s.need, scratch, size.width);
                }
                keep_band(static_cast<Field>(f), scratch, size.width, y0, rows, *fields);
            }
        }
    };
    const unsigned helpers = std::min(std::max(options.threads, 1U), bands) - 1;
    {
        std::vector<std::jthread> threads;
        threads.reserve(helpers);
        for (unsigned i = 0; i < helpers; ++i) threads.emplace_back(work);
        work();
    } // the helpers join here; their writes happen before the fields are handed on
    return fields;
}

std::shared_ptr<const ScreenFields> make_block(Block block, float scale) {
    ATLAS_ASSERT(block.width > 0 && block.height > 0 && block.width <= 16384 && block.height <= 16384,
                 "a block of 1 to 16384 px a side");
    const FieldSize size{.width = static_cast<std::uint32_t>(block.width),
                         .height = static_cast<std::uint32_t>(block.height),
                         .scale = scale};
    auto fields = std::make_shared<ScreenFields>(size);
    const Grid grid = grid_of(scale, scale);
    std::vector<Argb> noise(static_cast<std::size_t>(size.width) * size.height);
    for (std::size_t f = 0; f < field_count; ++f) {
        const FieldSpec& s = screen_fields[f];
        fill(lattices()[f], s.noise, grid, block, s.need, noise, size.width);
        keep_band(static_cast<Field>(f), noise, size.width, 0, size.height, *fields);
    }
    return fields;
}

std::vector<std::uint8_t> keep_tables_bytes() {
    const KeepTables& k = keep_tables();
    std::vector<std::uint8_t> out(k.round_trip.begin(), k.round_trip.end());
    out.insert(out.end(), k.grain_srgb.begin(), k.grain_srgb.end());
    out.insert(out.end(), canopy_alpha().begin(), canopy_alpha().end());
    return out;
}

// ---- FieldDisk

FieldDisk::FieldDisk(fs::path dir, std::uint64_t budget) : dir_(std::move(dir)), budget_(budget) {}

fs::path FieldDisk::path(FieldSize size) const {
    return dir_ / std::format("{}x{}-{:08x}.fields", size.width, size.height, std::bit_cast<std::uint32_t>(size.scale));
}

std::shared_ptr<const ScreenFields> FieldDisk::read(FieldSize size) const {
    const fs::path p = path(size);
    std::error_code ec;
    if (!fs::is_regular_file(p, ec)) return nullptr;
    auto opened = tiles::MappedSource::open(p.string());
    if (!opened) return nullptr;
    std::shared_ptr<const tiles::MappedSource> mapping = std::move(*opened);
    const std::uint64_t data = pixels_of(size) * pixel_bytes;
    if (mapping->size() != head_bytes + data) return nullptr;
    const std::span<const std::byte> all = mapping->view(0, head_bytes + data);
    if (all.size() != head_bytes + data) return nullptr;
    const std::span<const std::byte> head = all.first(head_bytes);
    if (get_u32(head, 60) != tiles::crc32(head.first(60))) return nullptr;
    if (get_u32(head, 0) != file_magic || get_u32(head, 4) != file_format || get_u32(head, 8) != fields_algorithm ||
        get_u32(head, 12) != size.width || get_u32(head, 16) != size.height ||
        get_u32(head, 20) != std::bit_cast<std::uint32_t>(size.scale) || get_u64(head, 24) != specs_hash() ||
        get_u64(head, 32) != data) {
        return nullptr;
    }
    const std::span<const std::byte> body = all.subspan(head_bytes);
    if (tiles::crc32(body) != get_u32(head, 40)) {
        log::warn("fields {}x{}: the kept file is damaged; made again", size.width, size.height);
        return nullptr;
    }
    const auto* bytes = reinterpret_cast<const std::uint8_t*>(body.data());
    const std::size_t n = pixels_of(size);
    const std::span<const std::uint8_t> rgba(bytes, n * rgba_bytes);
    const std::span<const std::uint8_t> rg(bytes + (n * rgba_bytes), n * rg_bytes);
    const std::span<const std::uint8_t> r(bytes + (n * (rgba_bytes + rg_bytes)), n * r_bytes);
    const std::span<const std::uint8_t> canopy(bytes + (n * (rgba_bytes + rg_bytes + r_bytes)), n * canopy_bytes);
    // touch the file, so the budget's trim drops the least recently used
    fs::last_write_time(p, fs::file_time_type::clock::now(), ec);
    return std::make_shared<const ScreenFields>(size, std::move(mapping), true, rgba, rg, r, canopy);
}

bool FieldDisk::holds(FieldSize size) const {
    std::error_code ec;
    return fs::is_regular_file(path(size), ec);
}

bool FieldDisk::write(const ScreenFields& fields) const {
    const FieldSize s = fields.size();
    const std::lock_guard lock(mutex_);
    std::error_code ec;
    fs::create_directories(dir_, ec);
    if (ec) return false;
    std::uint32_t crc = tiles::crc32(std::as_bytes(fields.rgba()));
    crc = tiles::crc32(std::as_bytes(fields.rg()), crc);
    crc = tiles::crc32(std::as_bytes(fields.r()), crc);
    crc = tiles::crc32(std::as_bytes(fields.canopy()), crc);
    const auto head = head_of(s, crc);
    const fs::path p = path(s);
    fs::path tmp = p;
    tmp += ".part";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) return false;
        auto put = [&out](std::span<const std::uint8_t> b) {
            out.write(reinterpret_cast<const char*>(b.data()), static_cast<std::streamsize>(b.size()));
        };
        put(head);
        put(fields.rgba());
        put(fields.rg());
        put(fields.r());
        put(fields.canopy());
        if (!out) {
            out.close();
            fs::remove(tmp, ec);
            return false;
        }
    }
    fs::rename(tmp, p, ec); // a reader sees the whole file or none
    if (ec) {
        fs::remove(tmp, ec);
        return false;
    }
    trim();
    return true;
}

void FieldDisk::trim() const {
    struct File {
        fs::path path;
        fs::file_time_type when;
        std::uintmax_t size;
    };
    std::vector<File> files;
    std::uint64_t total = 0;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator(dir_, ec)) {
        if (entry.path().extension() != ".fields") continue;
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
        if (total <= budget_ || files.size() < 2) break;
        std::error_code e3;
        if (fs::remove(f.path, e3)) total -= f.size;
    }
}

// ---- FieldWorker

FieldWorker::FieldWorker(std::shared_ptr<const FieldDisk> disk, unsigned threads)
    : disk_(std::move(disk)), threads_(std::max(threads, 1U)),
      thread_([this](const std::stop_token& stop) { loop(stop); }) {}

FieldWorker::~FieldWorker() {
    thread_.request_stop();
    wake_.notify_all();
}

void FieldWorker::request(FieldSize size) {
    {
        const std::lock_guard lock(mutex_);
        if (current_ == size && !next_) return;
        next_ = size;
    }
    wake_.notify_all();
}

void FieldWorker::keep(std::shared_ptr<const ScreenFields> fields, double ms) {
    {
        const std::lock_guard lock(mutex_);
        current_ = fields->size();
        last_ms_ = ms;
        last_from_disk_ = false;
        latest_ = fields;
        version_.fetch_add(1, std::memory_order_acq_rel);
        if (disk_ != nullptr) keeping_ = std::move(fields);
    }
    wake_.notify_all();
}

std::shared_ptr<const ScreenFields> FieldWorker::latest() const {
    const std::lock_guard lock(mutex_);
    return latest_;
}

void FieldWorker::on_publish(std::function<void()> wake) {
    const std::lock_guard lock(mutex_);
    wake_on_publish_ = std::move(wake);
}

void FieldWorker::wait_idle() {
    std::unique_lock lock(mutex_);
    idle_.wait(lock, [this] { return !busy_ && !next_ && keeping_ == nullptr; });
}

double FieldWorker::last_ms() const {
    const std::lock_guard lock(mutex_);
    return last_ms_;
}

bool FieldWorker::last_from_disk() const {
    const std::lock_guard lock(mutex_);
    return last_from_disk_;
}

void FieldWorker::loop(const std::stop_token& stop) {
    while (!stop.stop_requested()) {
        FieldSize size;
        std::shared_ptr<const ScreenFields> keeping;
        {
            std::unique_lock lock(mutex_);
            wake_.wait(lock, stop, [this] { return next_.has_value() || keeping_ != nullptr; });
            if (stop.stop_requested()) return;
            if (keeping_ != nullptr) {
                keeping = std::move(keeping_);
                keeping_.reset();
            } else if (next_) {
                size = *next_;
                next_.reset();
                current_ = size;
            } else {
                continue;
            }
            busy_ = true;
        }
        if (keeping != nullptr) {
            // Made elsewhere and published already: kept on disk, then let go of (the GPU's buffer with them).
            if (!disk_->write(*keeping)) {
                log::warn("fields {}x{}: not kept on disk", keeping->size().width, keeping->size().height);
            }
            keeping.reset();
            {
                const std::lock_guard lock(mutex_);
                busy_ = false;
            }
            idle_.notify_all();
            continue;
        }
        const auto started = clock::now();
        std::shared_ptr<const ScreenFields> fields = disk_ != nullptr ? disk_->read(size) : nullptr;
        const bool from_disk = fields != nullptr;
        if (!from_disk) {
            const trace::Section traced("atlas fields");
            fields = make_fields(size, MakeOptions{.threads = threads_});
        }
        const double ms = std::chrono::duration<double, std::milli>(clock::now() - started).count();
        // Published first, so the frame takes them while they are written to disk.
        std::function<void()> wake;
        {
            const std::lock_guard lock(mutex_);
            latest_ = fields;
            last_ms_ = ms;
            last_from_disk_ = from_disk;
            version_.fetch_add(1, std::memory_order_acq_rel);
            wake = wake_on_publish_;
        }
        if (wake) wake();
        if (!from_disk && disk_ != nullptr && !disk_->write(*fields)) {
            log::warn("fields {}x{}: not kept on disk", size.width, size.height);
        }
        {
            const std::lock_guard lock(mutex_);
            busy_ = false;
        }
        idle_.notify_all();
    }
}

// ---- the reference hashes

std::uint64_t hash_of(std::span<const std::uint8_t> bytes) noexcept {
    const tiles::Sha256Digest d = tiles::Sha256::of(std::as_bytes(bytes));
    std::uint64_t v = 0;
    for (std::size_t i = 0; i < 8; ++i) v = (v << 8U) | static_cast<std::uint64_t>(d[i]);
    return v;
}

namespace {

/// One line of fields.inc.
struct ReferenceRow {
    std::string_view size;
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t scale_bits;
    std::string_view field;
    std::uint64_t noise;
    std::uint64_t kept;
};

// NOLINTBEGIN(cppcoreguidelines-macro-usage): fields.inc's lines, read as this table's rows
#define ATLAS_LATTICE(seed, selector, gradients)
#define ATLAS_FIELD(size, width, height, scale, field, noise, kept)                                                    \
    ReferenceRow{size, width, height, scale, field, noise, kept},
constexpr std::array reference_rows{
#include "fx/tables/fields.inc"
};
#undef ATLAS_FIELD
#undef ATLAS_LATTICE
// NOLINTEND(cppcoreguidelines-macro-usage)

[[nodiscard]] std::optional<std::size_t> field_index(std::string_view name) noexcept {
    for (std::size_t f = 0; f < field_count; ++f) {
        if (screen_fields[f].name == name) return f;
    }
    return std::nullopt;
}

} // namespace

std::span<const FieldReference> field_references() noexcept {
    static const std::vector<FieldReference> refs = [] {
        std::vector<FieldReference> out;
        for (const ReferenceRow& row : reference_rows) {
            const FieldSize size{
                .width = row.width, .height = row.height, .scale = std::bit_cast<float>(row.scale_bits)};
            auto it = std::ranges::find_if(out, [&](const FieldReference& r) { return r.name == row.size; });
            if (it == out.end()) {
                out.push_back(FieldReference{.name = row.size, .size = size});
                it = out.end() - 1;
            }
            const std::optional<std::size_t> f = field_index(row.field);
            if (!f || it->size != size) ATLAS_UNREACHABLE("fields.inc names the core's fields, and one size a name");
            it->noise[*f] = row.noise;
            it->kept[*f] = row.kept;
        }
        return out;
    }();
    return refs;
}

const FieldReference* reference_of(FieldSize size) noexcept {
    for (const FieldReference& r : field_references()) {
        if (r.size == size) return &r;
    }
    return nullptr;
}

std::string fields_line(FieldSize size, unsigned threads, const GpuPart& gpu) {
    const auto started = clock::now();
    const std::shared_ptr<const ScreenFields> fields = make_fields(size, MakeOptions{.threads = threads});
    const double ms = std::chrono::duration<double, std::milli>(clock::now() - started).count();
    std::array<std::uint64_t, field_count> kept{};
    std::uint64_t all = 0xCBF29CE484222325ULL;
    for (std::size_t f = 0; f < field_count; ++f) {
        kept[f] = hash_of(fields->bytes_of(static_cast<Field>(f)));
        all = (all ^ kept[f]) * 0x100000001B3ULL;
    }
    const std::string on_gpu = gpu.fields != nullptr ? std::to_string(static_cast<long long>(pymath::round(gpu.ms)))
                               : gpu.off.empty()     ? std::string("off")
                                                     : std::format("off ({})", gpu.off);
    std::string line = std::format("fields {}x{} cpu {} gpu {} hash {:016x}", size.width, size.height,
                                   static_cast<long long>(pymath::round(ms)), on_gpu, all);
    if (gpu.fields != nullptr &&
        (gpu.fields->size() != size || !std::ranges::equal(gpu.fields->rgba(), fields->rgba()) ||
         !std::ranges::equal(gpu.fields->rg(), fields->rg()) || !std::ranges::equal(gpu.fields->r(), fields->r()))) {
        // The GPU's bytes are not the CPU's: the first field that differs.
        for (std::size_t f = 0; f < field_count; ++f) {
            const std::uint64_t h =
                gpu.fields->size() == size ? hash_of(gpu.fields->bytes_of(static_cast<Field>(f))) : 0;
            if (h != kept[f]) {
                return line + std::format(" FAIL gpu {} {:016x} expected {:016x}", screen_fields[f].name, h, kept[f]);
            }
        }
    }
    const FieldReference* ref = reference_of(size);
    if (ref == nullptr) return line + " unchecked";
    for (std::size_t f = 0; f < field_count; ++f) {
        if (kept[f] != ref->kept[f]) {
            return line +
                   std::format(" FAIL {} {:016x} expected {:016x}", screen_fields[f].name, kept[f], ref->kept[f]);
        }
    }
    return line + " ok";
}

// ---- On the GPU (fx/FieldCache.hpp)

namespace {

/// The images' formats and bytes a pixel: rgba, rg and r, four bytes each since the crowns' and the leaves' fields,
/// and the canopy's one (fx/Fields.hpp's placement_of).
constexpr std::array<vk::Format, 4> image_formats{vk::Format::eR8G8B8A8Unorm, vk::Format::eR8G8B8A8Unorm,
                                                  vk::Format::eR8G8B8A8Unorm, vk::Format::eR8Unorm};
constexpr std::array<std::uint32_t, 4> image_bytes{rgba_bytes, rg_bytes, r_bytes, canopy_bytes};
constexpr std::array<frame::Global, 4> image_globals{frame::Global::noise_rgba, frame::Global::noise_rg,
                                                     frame::Global::noise_r, frame::Global::noise_canopy};
/// The canopy's image, the one with mipmaps.
constexpr std::size_t canopy_image = 3;
/// The most the canopy's sampler takes of a footprint's length over its width (the device's limit may be less).
constexpr float canopy_anisotropy = 8.0F;

/// The stages that read the fields.
constexpr vk::PipelineStageFlags2 reading_stages = vk::PipelineStageFlagBits2::eVertexShader |
                                                   vk::PipelineStageFlagBits2::eFragmentShader |
                                                   vk::PipelineStageFlagBits2::eComputeShader;

} // namespace

gpu::Result<std::unique_ptr<FieldImages>> FieldImages::create(const gpu::Device& device) {
    std::unique_ptr<FieldImages> self(new FieldImages(device)); // NOLINT(cppcoreguidelines-owning-memory)
    ATLAS_GPU_LET(
        tables, device.memory().buffer(tables_bytes, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload));
    const std::vector<std::uint8_t> t = gpu_tables();
    std::memcpy(tables.bytes().data(), t.data(), t.size());
    tables.flush();
    self->tables_ = std::move(tables);
    // The shaders fetch texels (Load): the sampler never filters.
    ATLAS_VK_LET(sampler,
                 device.handle().createSampler({.magFilter = vk::Filter::eNearest,
                                                .minFilter = vk::Filter::eNearest,
                                                .mipmapMode = vk::SamplerMipmapMode::eNearest,
                                                .addressModeU = vk::SamplerAddressMode::eClampToEdge,
                                                .addressModeV = vk::SamplerAddressMode::eClampToEdge,
                                                .addressModeW = vk::SamplerAddressMode::eClampToEdge,
                                                .maxLod = 0.0F}),
                 "the noise fields' sampler");
    self->sampler_ = std::move(sampler);
    // The canopy's, where the app's map reads it on the ground: between its texels and its levels, past its edges in
    // mirror images.
    const bool anisotropic = device.enabled().sampler_anisotropy;
    ATLAS_VK_LET(canopy_sampler,
                 device.handle().createSampler(
                     {.magFilter = vk::Filter::eLinear,
                      .minFilter = vk::Filter::eLinear,
                      .mipmapMode = vk::SamplerMipmapMode::eLinear,
                      .addressModeU = vk::SamplerAddressMode::eMirroredRepeat,
                      .addressModeV = vk::SamplerAddressMode::eMirroredRepeat,
                      .addressModeW = vk::SamplerAddressMode::eMirroredRepeat,
                      .anisotropyEnable = anisotropic ? vk::True : vk::False,
                      .maxAnisotropy =
                          anisotropic ? std::min(canopy_anisotropy, device.facts().limits.maxSamplerAnisotropy) : 1.0F,
                      .maxLod = vk::LodClampNone}),
                 "the canopy's sampler");
    self->canopy_sampler_ = std::move(canopy_sampler);
    // The mipmaps are blits of each level into the next, filtered: where the device cannot, the canopy has one level
    // (R8's blits and linear filter are among the formats' required features).
    constexpr vk::FormatFeatureFlags blits = vk::FormatFeatureFlagBits::eBlitSrc | vk::FormatFeatureFlagBits::eBlitDst |
                                             vk::FormatFeatureFlagBits::eSampledImageFilterLinear;
    self->canopy_mipmaps_ =
        (device.physical().getFormatProperties(image_formats[canopy_image]).optimalTilingFeatures & blits) == blits;
    return self;
}

FieldImages::~FieldImages() = default;

std::uint32_t FieldImages::canopy_levels(FieldSize size) noexcept {
    return static_cast<std::uint32_t>(std::bit_width(std::max({size.width, size.height, 1U})));
}

vk::ImageView FieldImages::view(std::uint32_t image) const noexcept {
    ATLAS_ASSERT(ready_ && image < 4, "the fields have four images once staged");
    return *current_.views[image];
}

vk::Image FieldImages::image(std::uint32_t image) const noexcept {
    ATLAS_ASSERT(ready_ && image < 4, "the fields have four images once staged");
    return current_.images[image].handle();
}

gpu::Result<void> FieldImages::images_for(FieldSize size) {
    if (!ready_ || current_.size != size) {
        Images next;
        next.size = size;
        for (std::size_t i = 0; i < 4; ++i) {
            const bool canopy = i == canopy_image;
            const std::uint32_t levels = canopy && canopy_mipmaps_ ? canopy_levels(size) : 1;
            vk::ImageUsageFlags usage = vk::ImageUsageFlagBits::eSampled | vk::ImageUsageFlagBits::eTransferDst;
            if (levels > 1) usage |= vk::ImageUsageFlagBits::eTransferSrc;
            ATLAS_GPU_LET(image,
                          device_.memory().image({.imageType = vk::ImageType::e2D,
                                                  .format = image_formats[i],
                                                  .extent = {.width = size.width, .height = size.height, .depth = 1},
                                                  .mipLevels = levels,
                                                  .arrayLayers = 1,
                                                  .samples = vk::SampleCountFlagBits::e1,
                                                  .tiling = vk::ImageTiling::eOptimal,
                                                  .usage = usage,
                                                  .initialLayout = vk::ImageLayout::eUndefined},
                                                 gpu::MemoryUse::gpu));
            ATLAS_VK_LET(
                view,
                device_.handle().createImageView({.image = image.handle(),
                                                  .viewType = vk::ImageViewType::e2D,
                                                  .format = image_formats[i],
                                                  .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                                       .levelCount = levels,
                                                                       .layerCount = 1}}),
                "a noise field's view");
            next.images[i] = std::move(image);
            next.views[i] = std::move(view);
        }
        // The frames before the upload's read the old images.
        if (ready_) replaced_.push_back(std::move(current_));
        current_ = std::move(next);
        ready_ = true;
    }
    return {};
}

gpu::Result<void> FieldImages::stage(const ScreenFields& fields) {
    const FieldSize size = fields.size();
    ATLAS_GPU_CHECK(images_for(size));
    const std::size_t pixels = static_cast<std::size_t>(size.width) * size.height;
    ATLAS_GPU_LET(staging, device_.memory().buffer(pixels * pixel_bytes, vk::BufferUsageFlagBits::eTransferSrc,
                                                   gpu::MemoryUse::upload));
    const std::array<std::span<const std::uint8_t>, 4> planes{fields.rgba(), fields.rg(), fields.r(), fields.canopy()};
    std::size_t offset = 0;
    for (std::size_t i = 0; i < 4; ++i) {
        ATLAS_ASSERT(planes[i].size() == pixels * image_bytes[i], "a field's image holds its bytes a pixel");
        std::memcpy(staging.bytes().subspan(offset).data(), planes[i].data(), planes[i].size());
        offset += planes[i].size();
    }
    staging.flush();
    // Staged bytes no frame recorded are let go at once.
    staging_ = std::make_shared<const gpu::Buffer>(std::move(staging));
    return {};
}

gpu::Result<void> FieldImages::stage(FieldSize size, std::shared_ptr<const gpu::Buffer> bytes) {
    ATLAS_ASSERT(bytes != nullptr &&
                     bytes->size() >= static_cast<vk::DeviceSize>(size.width) * size.height * pixel_bytes,
                 "the GPU's fields hold the four images' bytes");
    ATLAS_GPU_CHECK(images_for(size));
    staging_ = std::move(bytes);
    return {};
}

void FieldImages::record_upload(const vk::raii::CommandBuffer& cmd, const gpu::Sync& sync, std::uint64_t frame) {
    if (staging_ == nullptr) return;
    const FieldSize size = current_.size;
    const vk::DeviceSize pixels = static_cast<vk::DeviceSize>(size.width) * size.height;
    const std::uint32_t levels = canopy_mipmaps_ ? canopy_levels(size) : 1;
    std::array<gpu::ImageDependency, 4> to_copy{};
    std::array<gpu::ImageDependency, 5> to_read{};
    for (std::size_t i = 0; i < 4; ++i) {
        // Earlier frames' reads of the image come first; what it held is not kept.
        to_copy[i] = {.image = current_.images[i].handle(),
                      .src_stage = reading_stages,
                      .src_access = {},
                      .dst_stage = vk::PipelineStageFlagBits2::eTransfer,
                      .dst_access = vk::AccessFlagBits2::eTransferWrite,
                      .old_layout = vk::ImageLayout::eUndefined,
                      .new_layout = vk::ImageLayout::eTransferDstOptimal};
        // the canopy's last level is the last its mipmaps write; the levels above it were read
        to_read[i] = {.image = current_.images[i].handle(),
                      .src_stage = vk::PipelineStageFlagBits2::eTransfer,
                      .src_access = vk::AccessFlagBits2::eTransferWrite,
                      .dst_stage = reading_stages,
                      .dst_access = vk::AccessFlagBits2::eShaderRead,
                      .old_layout = vk::ImageLayout::eTransferDstOptimal,
                      .new_layout = vk::ImageLayout::eShaderReadOnlyOptimal,
                      .base_level = i == canopy_image ? levels - 1 : 0,
                      .levels = 1};
    }
    to_read[4] = {.image = current_.images[canopy_image].handle(),
                  .src_stage = vk::PipelineStageFlagBits2::eTransfer,
                  .src_access = vk::AccessFlagBits2::eTransferRead,
                  .dst_stage = reading_stages,
                  .dst_access = vk::AccessFlagBits2::eShaderRead,
                  .old_layout = vk::ImageLayout::eTransferSrcOptimal,
                  .new_layout = vk::ImageLayout::eShaderReadOnlyOptimal,
                  .base_level = 0,
                  .levels = levels - 1};
    sync.barrier(cmd, {}, to_copy);
    vk::DeviceSize offset = 0;
    for (std::size_t i = 0; i < 4; ++i) {
        const vk::BufferImageCopy region{
            .bufferOffset = offset,
            .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
            .imageExtent = {.width = size.width, .height = size.height, .depth = 1}};
        cmd.copyBufferToImage(staging_->handle(), current_.images[i].handle(), vk::ImageLayout::eTransferDstOptimal,
                              region);
        offset += pixels * image_bytes[i];
    }
    // the canopy's mipmaps: each level the mean of the one above, a filtered blit of it at half its size
    const vk::Image canopy = current_.images[canopy_image].handle();
    auto w = static_cast<std::int32_t>(size.width);
    auto h = static_cast<std::int32_t>(size.height);
    for (std::uint32_t level = 1; level < levels; ++level) {
        sync.barrier(cmd, gpu::ImageDependency{.image = canopy,
                                               .src_stage = vk::PipelineStageFlagBits2::eTransfer,
                                               .src_access = vk::AccessFlagBits2::eTransferWrite,
                                               .dst_stage = vk::PipelineStageFlagBits2::eTransfer,
                                               .dst_access = vk::AccessFlagBits2::eTransferRead,
                                               .old_layout = vk::ImageLayout::eTransferDstOptimal,
                                               .new_layout = vk::ImageLayout::eTransferSrcOptimal,
                                               .base_level = level - 1,
                                               .levels = 1});
        const std::int32_t half_w = std::max(w / 2, 1);
        const std::int32_t half_h = std::max(h / 2, 1);
        const vk::ImageBlit blit{
            .srcSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .mipLevel = level - 1, .layerCount = 1},
            .srcOffsets = {{vk::Offset3D{.x = 0, .y = 0, .z = 0}, vk::Offset3D{.x = w, .y = h, .z = 1}}},
            .dstSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .mipLevel = level, .layerCount = 1},
            .dstOffsets = {{vk::Offset3D{.x = 0, .y = 0, .z = 0}, vk::Offset3D{.x = half_w, .y = half_h, .z = 1}}}};
        cmd.blitImage(canopy, vk::ImageLayout::eTransferSrcOptimal, canopy, vk::ImageLayout::eTransferDstOptimal, blit,
                      vk::Filter::eLinear);
        w = half_w;
        h = half_h;
    }
    sync.barrier(cmd, {}, std::span(to_read).first(levels > 1 ? 5 : 4));
    for (Images& old : replaced_)
        retired_.push_back(Retired{.images = std::move(old), .last_frame = frame > 0 ? frame - 1 : 0});
    replaced_.clear();
    retired_.push_back(Retired{.staging = std::move(staging_), .last_frame = frame});
    staging_.reset();
}

void FieldImages::collect(std::uint64_t finished) noexcept {
    std::erase_if(retired_, [finished](const Retired& r) { return r.last_frame <= finished; });
}

vk::DeviceSize FieldImages::bytes() const noexcept {
    vk::DeviceSize total = tables_.size() + (staging_ != nullptr ? staging_->size() : 0);
    for (const gpu::Image& i : current_.images) total += i.bytes();
    for (const Images& r : replaced_) {
        for (const gpu::Image& i : r.images) total += i.bytes();
    }
    for (const Retired& r : retired_) {
        total += r.staging != nullptr ? r.staging->size() : 0;
        for (const gpu::Image& i : r.images.images) total += i.bytes();
    }
    return total;
}

// ---- FieldCache, the provider

gpu::Result<std::unique_ptr<FieldCache>> FieldCache::create(const gpu::Device& device, FieldCacheOptions options) {
    std::unique_ptr<FieldCache> self(new FieldCache()); // NOLINT(cppcoreguidelines-owning-memory)
    ATLAS_GPU_LET(images, FieldImages::create(device));
    self->images_ = std::move(images);
    self->disk_ = options.dir.empty() ? nullptr : std::make_shared<const FieldDisk>(std::move(options.dir));
    self->worker_ = std::make_unique<FieldWorker>(self->disk_, options.threads);
    self->device_ = &device;
    self->gpu_ = options.gpu && options.pipelines != nullptr;
    self->pipelines_ = options.pipelines;
    self->watch_ = options.watch;
    return self;
}

FieldCache::~FieldCache() = default;

bool FieldCache::bound_for(FieldSize size) const noexcept {
    return uploaded_ && images_->ready() && images_->size() == size;
}

bool FieldCache::gpu_due(FieldSize size) const {
    return gpu_ && gpu_off_.empty() && !bound_for(size) && (disk_ == nullptr || !disk_->holds(size));
}

bool FieldCache::gpu_failed(std::string_view what, const gpu::Error& error) {
    gpu_off_ = std::format("{}: {}", what, error.text());
    gpu_fields_.reset();
    log::error("vk fields: the GPU's fields are off ({}): the CPU makes them", gpu_off_);
    return error.code != vk::Result::eErrorDeviceLost;
}

bool FieldCache::make_on_gpu(FieldSize size) {
    if (!gpu_due(size)) return true;
    const trace::Section traced("atlas fields gpu");
    const auto started = clock::now();
    if (gpu_fields_ == nullptr) {
        // Its pipeline, then the byte check, once: the GPU's fields only where every byte of it is the CPU's.
        auto made = GpuFields::create(*device_, *pipelines_, watch_);
        if (!made) return gpu_failed("their pipeline", made.error());
        auto checked = (*made)->check();
        if (!checked) return gpu_failed("the byte check", checked.error());
        const double check_ms = std::chrono::duration<double, std::milli>(clock::now() - started).count();
        if (!checked->exact) {
            gpu_off_ = "the byte check: " + checked->first;
            log::warn("vk fields: the GPU's noise is not the CPU's ({} of {} pixels differ; the first, {}): the CPU "
                      "makes the fields",
                      checked->differ, checked->pixels, checked->first);
            return true;
        }
        log::info("vk fields: the GPU's noise passed the byte check ({} pixels, {} ms)", checked->pixels,
                  static_cast<long long>(pymath::round(check_ms)));
        gpu_fields_ = std::move(*made);
    }
    auto made = gpu_fields_->make(size);
    if (!made) return gpu_failed(std::format("the fields of {}x{}", size.width, size.height), made.error());
    if (auto staged = images_->stage(size, made->buffer); !staged) {
        // No images for them: the worker makes them, staged as it publishes them (tried again each frame).
        return gpu_failed(std::format("the images of {}x{}", size.width, size.height), staged.error());
    }
    uploaded_ = true;
    const double ms = std::chrono::duration<double, std::milli>(clock::now() - started).count();
    // Published as the worker's latest, so it asks no more for them, and kept on disk by it.
    worker_->keep(made->fields, ms);
    log::info("vk fields {}x{} made on the GPU in {} ms ({} ms its commands)", size.width, size.height,
              static_cast<long long>(pymath::round(ms)), static_cast<long long>(pymath::round(made->ms)));
    return true;
}

void FieldCache::update(const frame::FrameState& state, frame::Globals& globals) {
    // Every frame, each binding (an unchanged one costs a comparison): every frame builder drawing the world gets them.
    globals.buffer(frame::Global::byte_tables, images_->tables());
    const style::CameraState& c = state.camera;
    if (c.width_px > 0 && c.height_px > 0 && c.density > 0.0) {
        const FieldSize size = size_of(c.width_px, c.height_px, c.density);
        wanted_ = size;
        // Nothing to do when they are published or under way; those the GPU makes (make_on_gpu) the worker leaves.
        if (!gpu_ || !(bound_for(size) || gpu_due(size))) worker_->request(size);
        take(size);
    }
    if (!images_->ready()) return;
    for (std::size_t i = 0; i < 4; ++i) {
        globals.image(image_globals[i], images_->view(static_cast<std::uint32_t>(i)),
                      i == canopy_image ? images_->canopy_sampler() : images_->sampler());
    }
}

void FieldCache::take(FieldSize size) {
    const std::uint64_t version = worker_->version();
    if (version == taken_version_) return;
    const std::shared_ptr<const ScreenFields> fields = worker_->latest();
    if (fields == nullptr || fields->size() != size) return; // another size's: the frame's own come later
    if (bound_for(size)) {                                   // the same size again: the images hold its fields
        taken_version_ = version;
        return;
    }
    // Images of their size and their bytes staged now, their upload recorded before the next frame's passes (record).
    if (auto staged = images_->stage(*fields); !staged) {
        if (failed_version_ != version) {
            log::error("vk fields {}x{}: not on the GPU: {}", size.width, size.height, staged.error().text());
        }
        failed_version_ = version; // tried again next frame
        uploaded_ = false;
        return;
    }
    taken_version_ = version;
    uploaded_ = true;
    log::info("vk fields {}x{} {} in {} ms, on the GPU", size.width, size.height,
              worker_->last_from_disk() ? "mapped" : "made", static_cast<long long>(pymath::round(worker_->last_ms())));
}

std::uint32_t FieldCache::frame_flags(const frame::FrameState& state) const noexcept {
    (void)state;
    return wanted_ && bound_for(*wanted_) ? shaders::FRAME_FIELDS : 0U;
}

void FieldCache::record(const gpu::FrameContext& frame) {
    // The renderer's frame numbers: frames up to `finished` are done once this one records.
    images_->collect(frame.number >= gpu::Frames::in_flight ? frame.number - gpu::Frames::in_flight : 0);
    images_->record_upload(frame.cmd, frame.sync, frame.number);
}

} // namespace atlas::fx
