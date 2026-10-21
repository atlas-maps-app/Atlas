// The box blurs (plan-vector D5.1, D5.5): WebKit's three box blurs along each axis, its approximation of a Gaussian,
// on the GPU as one compute dispatch per axis over a list of rectangles of one image, and on the CPU as the
// reference the GPU is held to bit for bit. The frosted backdrops use them (passes/Frosted.hpp, K11b), and the shadow
// mask will (K17).
//
// The sizes are WebKit's (SvgFilterRaster.kernel and kernelPosition): a deviation σ gives boxes of d = ⌊σ · 3√(2π)/4 +
// ½⌋ pixels, at least 2, run three times; an even d runs first with one more pixel on the right, then with one more
// on the left, then as d + 1 pixels centred, so that the three together are centred. A box sums its window of 8-bit
// texels and divides once: `float(sum) · (1/d)` in single precision, plus ½ to round to the nearest level (the frosted
// glass), or not (WebKit's NEON code truncates), and keeps 8 bits, as WebKit's buffers do between boxes. Past a line's
// ends lies transparent black (WebKit's separable passes), or the end texel again (vImage's edge extend).
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Memory.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <span>
#include <string_view>

namespace atlas::fx {

/// WebKit's box size for a deviation of `sigma` pixels: 0 for none (σ ≤ 0, or NaN), else ⌊σ · 3√(2π)/4 + ½⌋, at
/// least 2 and at most 500, in single precision as SvgFilterRaster.kernel computes it.
[[nodiscard]] std::uint32_t box_size(float sigma) noexcept;

/// The factor box_size multiplies σ by, 3√(2π)/4, in single precision as SvgFilterRaster.kernel computes it.
[[nodiscard]] float webkit_factor() noexcept;

/// One box: it writes at p the mean of [p − left, p + right − 1], left + right texels.
struct Box {
    std::uint32_t left = 0;
    std::uint32_t right = 0;

    [[nodiscard]] constexpr std::uint32_t size() const noexcept { return left + right; }
    friend constexpr bool operator==(const Box&, const Box&) = default;
};

/// Up to three boxes run one after another along one axis (none: the texels as they are).
struct Run {
    std::array<Box, 3> boxes{};
    std::uint32_t count = 0;

    /// How far the run reads to the left and to the right of a texel it writes.
    [[nodiscard]] std::uint32_t reach_left() const noexcept;
    [[nodiscard]] std::uint32_t reach_right() const noexcept;
    /// The larger of the two.
    [[nodiscard]] std::uint32_t reach() const noexcept;
    friend constexpr bool operator==(const Run&, const Run&) = default;
};

/// WebKit's three boxes of size `d` (kernelPosition); an empty run for d = 0.
[[nodiscard]] Run webkit_boxes(std::uint32_t d) noexcept;

/// WebKit's three boxes for a deviation of `sigma` pixels.
[[nodiscard]] inline Run webkit_run(float sigma) noexcept { return webkit_boxes(box_size(sigma)); }

/// vImage's square box for WebKit's size `d` when the sizes along both axes are equal: d, made odd (0 for none).
[[nodiscard]] constexpr std::uint32_t vimage_box(std::uint32_t d) noexcept { return d == 0 || d % 2 != 0 ? d : d + 1; }

/// vImage's square boxes on one 8-bit channel (SvgFilterRaster.boxBlurs when both sizes are equal: WebKit's blur on
/// macOS, vImageBoxConvolve_ARGB8888 with the edge extended, three times): each pass replaces every texel of the
/// `width` × `height` image (rows of `width` bytes) with the sum of the `box` × `box` texels around it (`box` odd),
/// the image's edge texels repeated past its edges, divided once and rounded to the nearest level, (sum + box² / 2) /
/// box² in whole numbers. `passes` passes, in place.
void square_blur_cpu(std::span<std::uint8_t> image, std::uint32_t width, std::uint32_t height, std::uint32_t box,
                     std::uint32_t passes = 3);

/// How a box rounds its mean.
enum class Rounding : std::uint8_t {
    nearest,  ///< to the nearest level: the frosted glass
    truncate, ///< down, as WebKit's NEON code (SvgFilterRaster's separable passes)
};

/// What lies past a line's ends.
enum class Edge : std::uint8_t {
    transparent, ///< transparent black: WebKit's separable passes
    extend,      ///< the end texel again: vImage's edge extend
};

/// The axis a dispatch runs along.
enum class Axis : std::uint8_t { x, y };

/// The texels the blur reads and writes.
enum class BlurFormat : std::uint8_t {
    rgba8, ///< four 8-bit channels, premultiplied
    r8,    ///< one 8-bit channel (a coverage mask)
};

/// One rectangle of a dispatch: its place in the image and the run along the dispatch's axis.
struct BlurRect {
    std::int32_t x = 0;
    std::int32_t y = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    Run run;
};

/// A dispatch's settings, the same for all its rectangles.
struct BlurPass {
    Axis axis = Axis::x;
    Rounding rounding = Rounding::nearest;
    Edge edge = Edge::transparent;
    /// Reads every texel as opaque (alpha 255) before the first box: the frosted copies of the frame, whose alpha
    /// means nothing (a frame may be rgb10a2, with 2 bits of it).
    bool opaque = false;
};

/// The CPU reference of one dispatch over `rect`: `source` and `target` are images `stride` texels wide, packed as
/// the GPU reads them (r | g << 8 | b << 16 | a << 24; r8 in the low byte); the rectangle's texels of `target` are
/// written, the others left as they are. `source` and `target` may be the same image.
void blur_cpu(std::span<const std::uint32_t> source, std::span<std::uint32_t> target, std::uint32_t stride,
              const BlurRect& rect, const BlurPass& pass, BlurFormat format);

/// The GPU's box blurs: a compute pipeline of shaders/frosted/boxes.slang for one format, its set layout (the source
/// and the target as storage images in GENERAL, the dispatch's jobs in a storage buffer) and a pool for the sets its
/// users bind.
class BoxBlur {
public:
    /// The most a run reaches on either side, in texels (boxes.slang's REACH).
    static constexpr std::uint32_t max_reach = 128;
    /// The most rectangles one dispatch takes (the jobs' room per dispatch).
    static constexpr std::uint32_t max_rects = 32;
    /// The bytes of one job (boxes.slang's BoxJob).
    static const std::size_t job_bytes;

    /// The blur of `format`, its module and pipeline made through `cache`, each announced to `watch` (may be null);
    /// room for `sets` sets. `ms` (may be null) gets the driver's time for the pipeline added.
    [[nodiscard]] static gpu::Result<std::unique_ptr<BoxBlur>> create(const gpu::Device& device,
                                                                      const gpu::PipelineCache& cache,
                                                                      gpu::PipelineWatch* watch, BlurFormat format,
                                                                      std::uint32_t sets, double* ms = nullptr);
    BoxBlur(const BoxBlur&) = delete;
    BoxBlur& operator=(const BoxBlur&) = delete;
    BoxBlur(BoxBlur&&) = delete;
    BoxBlur& operator=(BoxBlur&&) = delete;
    ~BoxBlur();

    /// A set reading `source` and writing `target` (storage views of images in GENERAL), with the jobs in `jobs`.
    [[nodiscard]] gpu::Result<vk::raii::DescriptorSet> bind(vk::ImageView source, vk::ImageView target,
                                                            vk::Buffer jobs) const;

    /// Writes the jobs of `rects` (at most max_rects) into `out` (at least job_bytes each), as the shader reads them;
    /// false, writing nothing, when a run reaches further than max_reach or has more than three boxes.
    [[nodiscard]] static bool write_jobs(std::span<std::byte> out, std::span<const BlurRect> rects, Rounding rounding);

    /// Records one dispatch over `rects`, whose jobs lie at job index `first` of the set's buffer (write_jobs, then
    /// flushed), with `pass`'s axis, edge and opacity (its rounding is in the jobs).
    void record(const vk::raii::CommandBuffer& cmd, vk::DescriptorSet set, std::uint32_t first,
                std::span<const BlurRect> rects, const BlurPass& pass) const;

    [[nodiscard]] BlurFormat format() const noexcept { return format_; }

private:
    BoxBlur() = default;

    const gpu::Device* device_ = nullptr;
    BlurFormat format_ = BlurFormat::rgba8;
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

} // namespace atlas::fx
