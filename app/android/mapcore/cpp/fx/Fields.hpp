// The screen-wide noise fields (plan-vector D10, D5.5, N16, N17, K17a): the renders' eight noises whose turbulence is
// fixed to the screen and spans it, made once per surface size on the CPU, exactly, and kept on disk.
//
// The fields (render/amsterdam.py, all fractal noise anchored at the user-space origin, so the same at every camera):
//   mat            seed 13, base frequency .16 × .75, 2 octaves: the walls', roofs' and pavements' texture
//   grain          seed 8, .9, 1 octave: fin's paper grain, by overlay over everything
//   ripple         seed 11, .012 × .26, 2 octaves: the water's displacement (K17)
//   glints         seed 5, .02 × .3, 2 octaves: the water's glints at map pitches (glint0, K17)
//   cloud shadows  seed 23, .0026 × .0058, 3 octaves: wfx's cloud shadows (K17)
//   crown          seed 5, .15, 2 octaves: the crowns' displacement (render/closeup.py's crown_filter, `n`; K17)
//   crown tone     seed 2, .42, 2 octaves: the crowns' mottled tone (crown_filter's `m`; K17)
//   leaf           seed 3, .55, 2 octaves: the reflected trees' leaves (amsterdam.py's `leaf` filter; K17)
// Each filter region is whole filter pixels from the user-space origin (SvgFilterRaster), so a tree's crown filter
// reads the crowns' noise at its pixels as the screen's field holds it there.
// A field's pixel (x, y) of a W × H px frame is fx/Noise.hpp's noise at the filter pixel (x, y), the filter scale
// being the frame's density as a float (the oracle's: SvgCanvas takes the canvas's scale, as drawScaled sets it).
//
// What the core keeps of a pixel is what the filter's next primitive reads of the noise, through SvgFilterRaster's own
// steps, so nothing theme- or camera-dependent is baked in: `mat`'s grey as its colour matrix leaves it (linear RGB,
// opaque), the grain's filter result (premultiplied sRGB grey and alpha, which fin overlays at its opacity), the
// ripple's, the crowns' and the leaves' red and green as their displacements read them, and the glints', the cloud
// shadows' and the crown tone's red as their colour matrices read it (the noise premultiplied, then unpremultiplied;
// the matrices' rows, which depend on the moment, are tables of that byte for K17). Twelve bytes a pixel, the GPU's
// three images (D5.5):
//   rgba: mat's grey, the grain's grey, the grain's alpha, the cloud shadows' red
//   rg:   the ripple's red and green, the crown's red and green (four bytes: the image is RGBA8)
//   r:    the glints' red, the crown tone's red, the leaf's red and green (four bytes: the image is RGBA8)
// 31.1 MB at S1, 53.9 MB at S3 (N16).
//
// The fields are made on worker threads at the first start on a size (bands of rows, fx/Noise.hpp's lanes), or on the
// GPU where the device passes its byte check (fx/GpuFields.hpp: the same bytes), and kept in cacheDir/fields, one file
// a size, so a warm start maps them. Mapped or made, a size's fields are immutable and shared; FieldWorker publishes
// the latest size asked for (or made on the GPU), and fx/FieldCache.hpp puts it on the GPU.
#pragma once

#include "fx/Noise.hpp"

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <stop_token>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

namespace atlas::fx {

/// The renders' screen-wide fields.
enum class Field : std::uint8_t { mat, grain, ripple, glints, cloud_shadows, crown, crown_tone, leaf };

/// The number of screen-wide fields.
inline constexpr std::size_t field_count = 8;

/// A screen-wide field: its name, its turbulence, the channels its filter's next primitive reads, and how many bytes
/// the core keeps of a pixel.
struct FieldSpec {
    std::string_view name;
    Turbulence noise;
    std::uint8_t need = channels_all;
    std::uint8_t bytes = 1;
};

/// The eight, in Field's order (the parameters amsterdam.py and closeup.py write; mapview's ByteTablesExportTest holds
/// them to it).
inline constexpr std::array<FieldSpec, field_count> screen_fields{{
    {.name = "mat", .noise = {.seed = 13.0F, .base_x = 0.16F, .base_y = 0.75F, .octaves = 2}, .bytes = 1},
    {.name = "grain", .noise = {.seed = 8.0F, .base_x = 0.9F, .base_y = 0.9F, .octaves = 1}, .bytes = 2},
    {.name = "ripple",
     .noise = {.seed = 11.0F, .base_x = 0.012F, .base_y = 0.26F, .octaves = 2},
     .need = channel_red | channel_green,
     .bytes = 2},
    {.name = "glints", .noise = {.seed = 5.0F, .base_x = 0.02F, .base_y = 0.3F, .octaves = 2}, .bytes = 1},
    {.name = "cloud_shadows", .noise = {.seed = 23.0F, .base_x = 0.0026F, .base_y = 0.0058F, .octaves = 3}, .bytes = 1},
    {.name = "crown",
     .noise = {.seed = 5.0F, .base_x = 0.15F, .base_y = 0.15F, .octaves = 2},
     .need = channel_red | channel_green,
     .bytes = 2},
    {.name = "crown_tone", .noise = {.seed = 2.0F, .base_x = 0.42F, .base_y = 0.42F, .octaves = 2}, .bytes = 1},
    {.name = "leaf",
     .noise = {.seed = 3.0F, .base_x = 0.55F, .base_y = 0.55F, .octaves = 2},
     .need = channel_red | channel_green,
     .bytes = 2},
}};

/// The spec of field `f`.
[[nodiscard]] constexpr const FieldSpec& spec_of(Field f) noexcept {
    return screen_fields[static_cast<std::size_t>(f)];
}

/// The bytes the core keeps of field `f`'s noise pixel `p` (packed a r g b, its `need` channels set), into `out`
/// (spec_of(f).bytes of them), by SvgFilterRaster's steps.
void keep(Field f, Argb p, std::span<std::uint8_t> out) noexcept;

/// What fields are made for: a frame of `width` × `height` device px (upright) at filter scale `scale` (its density,
/// as a float).
struct FieldSize {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    float scale = 1.0F;

    friend bool operator==(const FieldSize&, const FieldSize&) = default;
};

/// The size of a frame `width` × `height` px at `density` px per CSS px (the camera's): the scale as the oracle takes
/// it, the density rounded to a float.
[[nodiscard]] constexpr FieldSize size_of(std::uint32_t width, std::uint32_t height, double density) noexcept {
    return FieldSize{.width = width, .height = height, .scale = static_cast<float>(density)};
}

/// The bytes a pixel of each image holds.
inline constexpr std::uint32_t rgba_bytes = 4;
inline constexpr std::uint32_t rg_bytes = 4;
inline constexpr std::uint32_t r_bytes = 4;

/// Where field `f`'s bytes lie in the three images: the image (0 rgba, 1 rg, 2 r) and the first channel.
struct Placement {
    std::uint32_t image = 0;
    std::uint32_t channel = 0;
};
[[nodiscard]] constexpr Placement placement_of(Field f) noexcept {
    switch (f) {
    case Field::mat: return {.image = 0, .channel = 0};
    case Field::grain: return {.image = 0, .channel = 1};
    case Field::cloud_shadows: return {.image = 0, .channel = 3};
    case Field::ripple: return {.image = 1, .channel = 0};
    case Field::crown: return {.image = 1, .channel = 2};
    case Field::glints: return {.image = 2, .channel = 0};
    case Field::crown_tone: return {.image = 2, .channel = 1};
    case Field::leaf: return {.image = 2, .channel = 2};
    }
    return {};
}

/// A size's eight fields, as the GPU's images take them: rows from the top, the images' bytes one after the other.
/// Made in memory, mapped from the disk cache, or read back from the GPU; immutable.
class ScreenFields {
public:
    /// Fields of `size` in memory, zeroed (made by make_fields).
    explicit ScreenFields(FieldSize size);
    /// Fields of `size` viewed in the bytes `owner` holds and keeps alive with them: a mapping of the disk cache's
    /// file (`mapped`), or the GPU's buffer they were made in.
    ScreenFields(FieldSize size, std::shared_ptr<const void> owner, bool mapped, std::span<const std::uint8_t> rgba,
                 std::span<const std::uint8_t> rg, std::span<const std::uint8_t> r);

    [[nodiscard]] FieldSize size() const noexcept { return size_; }
    [[nodiscard]] std::span<const std::uint8_t> rgba() const noexcept { return rgba_; }
    [[nodiscard]] std::span<const std::uint8_t> rg() const noexcept { return rg_; }
    [[nodiscard]] std::span<const std::uint8_t> r() const noexcept { return r_; }
    /// Whether they came from the disk cache.
    [[nodiscard]] bool mapped() const noexcept { return mapped_; }

    /// Field `f`'s bytes at pixel (x, y): the first, and the second for the two-byte fields.
    [[nodiscard]] std::array<std::uint8_t, 2> at(Field f, std::uint32_t x, std::uint32_t y) const noexcept;
    /// Field `f`'s bytes over the frame in its own layout (spec_of(f).bytes a pixel, rows from the top): what
    /// fields.inc's hashes are taken over.
    [[nodiscard]] std::vector<std::uint8_t> bytes_of(Field f) const;

    /// The images' bytes to write (make_fields).
    [[nodiscard]] std::span<std::uint8_t> rgba_mut() noexcept { return owned_rgba_; }
    [[nodiscard]] std::span<std::uint8_t> rg_mut() noexcept { return owned_rg_; }
    [[nodiscard]] std::span<std::uint8_t> r_mut() noexcept { return owned_r_; }

private:
    FieldSize size_;
    std::vector<std::uint8_t> owned_rgba_;
    std::vector<std::uint8_t> owned_rg_;
    std::vector<std::uint8_t> owned_r_;
    std::shared_ptr<const void> owner_;
    bool mapped_ = false;
    std::span<const std::uint8_t> rgba_;
    std::span<const std::uint8_t> rg_;
    std::span<const std::uint8_t> r_;
};

/// How fields are made.
struct MakeOptions {
    /// Threads in all, the calling one among them (1: on the calling thread alone).
    unsigned threads = 1;
    /// The lanes (fill_lanes) or the scalar reference (fill): the same bytes.
    bool lanes = true;
    /// Rows a worker takes at a time.
    std::uint32_t band_rows = 32;
};

/// Makes the eight fields of `size`.
[[nodiscard]] std::shared_ptr<const ScreenFields> make_fields(FieldSize size, const MakeOptions& options = {});

/// make_fields' bytes of the pixels of `block` of a frame at filter scale `scale`, as fields of the block's size, by
/// the scalar reference (what the GPU's byte check holds its fields to, fx/GpuFields.hpp).
[[nodiscard]] std::shared_ptr<const ScreenFields> make_block(Block block, float scale);

/// make_fields' keep tables as the GPU reads them (shaders/fx/fields.slang): a channel premultiplied and
/// unpremultiplied again at each alpha (entry a · 256 + c), then the grain's grey from its matrix written as
/// premultiplied sRGB at each alpha (65536 + a · 256 + grey).
[[nodiscard]] std::vector<std::uint8_t> keep_tables_bytes();

/// The fields kept on disk (cacheDir/fields): one file a size, written whole by a rename, its head and checksum checked
/// when it is mapped; the least recently written dropped past a budget.
class FieldDisk {
public:
    explicit FieldDisk(std::filesystem::path dir, std::uint64_t budget = std::uint64_t{96} << 20U);

    /// The fields of `size` kept here, mapped, or null.
    [[nodiscard]] std::shared_ptr<const ScreenFields> read(FieldSize size) const;
    /// Whether a file of `size` is here (read may still refuse it).
    [[nodiscard]] bool holds(FieldSize size) const;
    /// Keeps `fields`; returns whether they were written.
    bool write(const ScreenFields& fields) const;
    /// The file of `size`.
    [[nodiscard]] std::filesystem::path path(FieldSize size) const;

private:
    void trim() const;

    std::filesystem::path dir_;
    std::uint64_t budget_;
    mutable std::mutex mutex_;
};

/// The version of the fields' bytes: bump it whenever a kept byte could change (the turbulence, a step, the layout),
/// so files made by older code are never read as this code's.
inline constexpr std::uint32_t fields_algorithm = 3;

/// The workers that make the fields: the latest size asked for, from the disk cache when it holds them, else made on
/// `threads` threads, published for the frame to take, then kept there.
class FieldWorker {
public:
    /// `disk` may be null (nothing kept); `threads` in all, the worker's own among them.
    FieldWorker(std::shared_ptr<const FieldDisk> disk, unsigned threads);
    ~FieldWorker();
    FieldWorker(const FieldWorker&) = delete;
    FieldWorker& operator=(const FieldWorker&) = delete;
    FieldWorker(FieldWorker&&) = delete;
    FieldWorker& operator=(FieldWorker&&) = delete;

    /// Asks for the fields of `size` (a no-op when they are published or under way); replaces a request not begun.
    void request(FieldSize size);
    /// Fields made elsewhere in `ms` (the GPU's, fx/GpuFields.hpp): published at once as the latest, as though made
    /// here (without the wake: their maker takes them), then kept on disk on the worker's thread.
    void keep(std::shared_ptr<const ScreenFields> fields, double ms);
    /// The fields published last (null before the first).
    [[nodiscard]] std::shared_ptr<const ScreenFields> latest() const;
    /// How many times fields were published; the frame takes them again when it grows.
    [[nodiscard]] std::uint64_t version() const noexcept { return version_.load(std::memory_order_acquire); }
    /// Called on the worker's thread each time fields are published (cheap and never blocking: the frame loop's wake).
    void on_publish(std::function<void()> wake);
    /// Waits until every request made so far is done (published, and kept on disk).
    void wait_idle();
    /// The last publication's milliseconds and whether it came from disk.
    [[nodiscard]] double last_ms() const;
    [[nodiscard]] bool last_from_disk() const;

private:
    void loop(const std::stop_token& stop);

    std::shared_ptr<const FieldDisk> disk_;
    unsigned threads_;
    mutable std::mutex mutex_;
    std::condition_variable_any wake_;
    std::condition_variable idle_;
    std::optional<FieldSize> next_;
    std::optional<FieldSize> current_; ///< under way or published
    bool busy_ = false;
    std::shared_ptr<const ScreenFields> latest_;
    std::shared_ptr<const ScreenFields> keeping_; ///< made elsewhere, to keep on disk
    std::function<void()> wake_on_publish_;
    std::atomic<std::uint64_t> version_{0};
    double last_ms_ = 0;
    bool last_from_disk_ = false;
    std::jthread thread_; ///< last: it starts once every member above is made, and stops before they go
};

/// The first 8 bytes of the SHA-256 of `bytes`, as a number (fields.inc's hashes).
[[nodiscard]] std::uint64_t hash_of(std::span<const std::uint8_t> bytes) noexcept;

/// A reference size of fields.inc (D16's S1–S4 and the owner's phone's family) and its fields' hashes, by
/// ByteTablesExportTest from the port's code: of the noise, and of the bytes kept.
struct FieldReference {
    std::string_view name;
    FieldSize size;
    std::array<std::uint64_t, field_count> noise{};
    std::array<std::uint64_t, field_count> kept{};
};

/// fields.inc's sizes.
[[nodiscard]] std::span<const FieldReference> field_references() noexcept;

/// The reference of `size`, when fields.inc has it.
[[nodiscard]] const FieldReference* reference_of(FieldSize size) noexcept;

/// What fields_line says of the GPU: the fields it made and its milliseconds, or why it made none.
struct GpuPart {
    std::shared_ptr<const ScreenFields> fields; ///< null: none made
    double ms = 0;
    std::string off; ///< why none ("" for none said)
};

/// `fields <w>x<h> cpu <ms> gpu <gpu> hash <h> ok` (plan-vector Appendix C), or `... FAIL <field> <h> expected <h>`
/// where a reference size's field differs from the port's, or `... unchecked` for a size fields.inc lacks: the eight
/// fields of `size` made on `threads` threads, timed, for VK facts on the emulator and the phone. `<gpu>` is the GPU's
/// milliseconds where it made them too (fx/GpuFields.hpp's fields_line), and then the line ends `FAIL gpu <field> <h>
/// expected <h>` where its bytes are not the CPU's; else `off`, or `off (<why>)`.
[[nodiscard]] std::string fields_line(FieldSize size, unsigned threads, const GpuPart& gpu = {});

} // namespace atlas::fx
