// The box blurs (fx/Blur.hpp).
#include "fx/Blur.hpp"

#include "base/assert.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/frosted/boxes.hpp"
#include "gen/spirv/frosted/boxes_r8.hpp"
#include "gpu/Descriptors.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <numbers>
#include <vector>

namespace atlas::fx {

namespace {

using Job = layouts::frosted_boxes::BoxJob;
using Push = layouts::frosted_boxes::BoxPush;

constexpr std::uint32_t mode_extend = 1U; // boxes.slang's MODE_EXTEND
constexpr std::uint32_t mode_opaque = 2U; // boxes.slang's MODE_OPAQUE
constexpr std::uint32_t segment = 256U;   // boxes.slang's SEGMENT: the texels one workgroup writes

/// A texel's four 8-bit channels.
struct Channels {
    std::uint32_t v[4];
};

[[nodiscard]] Channels unpack(std::uint32_t w) noexcept {
    return {{w & 0xFFU, (w >> 8U) & 0xFFU, (w >> 16U) & 0xFFU, w >> 24U}};
}

/// A box's mean of one channel's sum, as boxes.slang computes it: float(sum) · inv + offset, truncated.
[[nodiscard]] std::uint32_t mean(std::uint32_t sum, float inv, float offset) noexcept {
    const float scaled = static_cast<float>(sum) * inv;
    return static_cast<std::uint32_t>(scaled + offset);
}

} // namespace

float webkit_factor() noexcept {
    // SvgFilterRaster.kernel: 3f / 4f * sqrt(2 * Math.PI.toFloat()), Kotlin's sqrt(Float) through the double one.
    const auto two_pi = 2.0F * static_cast<float>(std::numbers::pi);
    const auto root = static_cast<float>(std::sqrt(static_cast<double>(two_pi)));
    return (3.0F / 4.0F) * root;
}

std::uint32_t box_size(float sigma) noexcept {
    if (!(sigma > 0.0F)) return 0; // none, and NaN
    const float scaled = sigma * webkit_factor();
    const auto floored = static_cast<float>(std::floor(static_cast<double>(scaled + 0.5F)));
    if (!(floored < 500.0F)) return 500;
    return std::max(2U, static_cast<std::uint32_t>(floored));
}

std::uint32_t Run::reach_left() const noexcept {
    std::uint32_t n = 0;
    for (std::uint32_t i = 0; i < count; ++i) n += boxes[i].left;
    return n;
}

std::uint32_t Run::reach_right() const noexcept {
    std::uint32_t n = 0;
    for (std::uint32_t i = 0; i < count; ++i) n += boxes[i].right - 1;
    return n;
}

std::uint32_t Run::reach() const noexcept { return std::max(reach_left(), reach_right()); }

Run webkit_boxes(std::uint32_t d) noexcept {
    Run run;
    if (d == 0) return run;
    // kernelPosition: the first box of an even size reaches one texel further right, the second one further left (its
    // left grows, its right shrinks), the third grows by one on the right and is d + 1 wide; an odd box is centred.
    std::uint32_t left = 0;
    std::uint32_t right = 0;
    std::uint32_t size = d;
    for (std::uint32_t i = 0; i < 3; ++i) {
        switch (i) {
        case 0:
            left = size % 2 == 0 ? size / 2 - 1 : size / 2;
            right = size - left;
            break;
        case 1:
            if (size % 2 == 0) {
                ++left;
                --right;
            }
            break;
        default:
            if (size % 2 == 0) {
                ++right;
                ++size;
            }
            break;
        }
        run.boxes[i] = {.left = left, .right = right};
    }
    run.count = 3;
    return run;
}

void blur_cpu(std::span<const std::uint32_t> source, std::span<std::uint32_t> target, std::uint32_t stride,
              const BlurRect& rect, const BlurPass& pass, BlurFormat format) {
    ATLAS_ASSERT(rect.run.count <= 3, "a run of at most three boxes");
    const bool along_x = pass.axis == Axis::x;
    const std::uint32_t length = along_x ? rect.width : rect.height;
    const std::uint32_t lines = along_x ? rect.height : rect.width;
    if (length == 0) return;
    const float offset = pass.rounding == Rounding::nearest ? 0.5F : 0.0F;
    std::vector<std::uint32_t> cur(length);
    std::vector<std::uint32_t> next(length);
    auto index_of = [&](std::uint32_t at, std::uint32_t line) {
        const auto x = static_cast<std::size_t>(rect.x) + (along_x ? at : line);
        const auto y = static_cast<std::size_t>(rect.y) + (along_x ? line : at);
        return (y * stride) + x;
    };
    for (std::uint32_t line = 0; line < lines; ++line) {
        for (std::uint32_t at = 0; at < length; ++at) {
            std::uint32_t texel = source[index_of(at, line)];
            if (format == BlurFormat::r8) texel &= 0xFFU;
            if (pass.opaque) texel |= 0xFF000000U;
            cur[at] = texel;
        }
        for (std::uint32_t i = 0; i < rect.run.count; ++i) {
            const Box box = rect.run.boxes[i];
            const float inv = 1.0F / static_cast<float>(box.size());
            const auto n = static_cast<std::int64_t>(length);
            for (std::int64_t p = 0; p < n; ++p) {
                std::uint32_t sum[4] = {0, 0, 0, 0};
                for (std::int64_t q = p - box.left; q < p + box.right; ++q) {
                    std::uint32_t texel = 0;
                    if (q >= 0 && q < n) {
                        texel = cur[static_cast<std::size_t>(q)];
                    } else if (pass.edge == Edge::extend) {
                        texel = cur[static_cast<std::size_t>(std::clamp<std::int64_t>(q, 0, n - 1))];
                    }
                    const Channels c = unpack(texel);
                    for (int k = 0; k < 4; ++k) sum[k] += c.v[k];
                }
                std::uint32_t out = 0;
                for (std::uint32_t k = 0; k < 4; ++k) out |= mean(sum[k], inv, offset) << (8U * k);
                next[static_cast<std::size_t>(p)] = out;
            }
            cur.swap(next);
        }
        for (std::uint32_t at = 0; at < length; ++at) {
            std::uint32_t texel = cur[at];
            if (format == BlurFormat::r8) texel &= 0xFFU;
            target[index_of(at, line)] = texel;
        }
    }
}

void square_blur_cpu(std::span<std::uint8_t> image, std::uint32_t width, std::uint32_t height, std::uint32_t box,
                     std::uint32_t passes) {
    ATLAS_ASSERT(box % 2 == 1, "vImage's box is odd");
    ATLAS_ASSERT(image.size() >= std::size_t{width} * height, "the image holds width × height texels");
    if (width == 0 || height == 0) return;
    const auto w = static_cast<std::int64_t>(width);
    const auto h = static_cast<std::int64_t>(height);
    const auto half = static_cast<std::int64_t>(box / 2);
    const std::uint32_t area = box * box;
    const std::uint32_t round = area / 2;
    std::vector<std::uint32_t> rows(std::size_t{width} * height);
    for (std::uint32_t p = 0; p < passes; ++p) {
        // the sums along each row, its ends repeated
        for (std::int64_t y = 0; y < h; ++y) {
            const auto row = static_cast<std::size_t>(y * w);
            for (std::int64_t x = 0; x < w; ++x) {
                std::uint32_t sum = 0;
                for (std::int64_t i = x - half; i <= x + half; ++i) {
                    sum += image[row + static_cast<std::size_t>(std::clamp<std::int64_t>(i, 0, w - 1))];
                }
                rows[row + static_cast<std::size_t>(x)] = sum;
            }
        }
        // their sums down each column, its ends repeated, divided once
        for (std::int64_t x = 0; x < w; ++x) {
            for (std::int64_t y = 0; y < h; ++y) {
                std::uint32_t sum = 0;
                for (std::int64_t j = y - half; j <= y + half; ++j) {
                    sum += rows[static_cast<std::size_t>((std::clamp<std::int64_t>(j, 0, h - 1) * w) + x)];
                }
                image[static_cast<std::size_t>((y * w) + x)] = static_cast<std::uint8_t>((sum + round) / area);
            }
        }
    }
}

// ---- The GPU's box blurs.

const std::size_t BoxBlur::job_bytes = sizeof(Job);

BoxBlur::~BoxBlur() = default;

gpu::Result<std::unique_ptr<BoxBlur>> BoxBlur::create(const gpu::Device& device, const gpu::PipelineCache& cache,
                                                      gpu::PipelineWatch* watch, BlurFormat format, std::uint32_t sets,
                                                      double* ms) {
    std::unique_ptr<BoxBlur> self(new BoxBlur()); // NOLINT(cppcoreguidelines-owning-memory)
    self->device_ = &device;
    self->format_ = format;
    const vk::raii::Device& d = device.handle();
    using T = vk::DescriptorType;
    constexpr auto compute = vk::ShaderStageFlagBits::eCompute;
    const std::array bindings{gpu::Binding{.slot = 0, .type = T::eStorageImage, .stages = compute},
                              gpu::Binding{.slot = 1, .type = T::eStorageImage, .stages = compute},
                              gpu::Binding{.slot = 2, .type = T::eStorageBuffer, .stages = compute}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    self->set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, std::max(sets, 1U)));
    self->pool_ = std::move(pool);
    const std::array<vk::DescriptorSetLayout, 1> layouts_used{*self->set_layout_};
    ATLAS_GPU_LET(layout, gpu::make_layout(d, layouts_used, sizeof(Push), compute));
    self->layout_ = std::move(layout);

    const bool r8 = format == BlurFormat::r8;
    const std::string_view symbol = r8 ? "frosted_boxes_r8" : "frosted_boxes";
    auto module = [&] {
        const gpu::Announced named = gpu::announce_module(symbol, watch);
        return gpu::make_module(d, r8 ? std::span<const std::uint32_t>(spirv::frosted_boxes_r8)
                                      : std::span<const std::uint32_t>(spirv::frosted_boxes));
    }();
    ATLAS_GPU_LET(made_module, std::move(module));
    self->module_ = std::move(made_module);
    const gpu::PipelineLabel label{.name = r8 ? "boxes_r8" : "boxes", .module = symbol};
    cache.note_made();
    const auto started = std::chrono::steady_clock::now();
    auto pipeline = [&] {
        const gpu::Announced named = gpu::announce(label, watch);
        return gpu::make_compute(d, cache.handle(), label, *self->module_, "boxes_cs", *self->layout_);
    }();
    if (ms != nullptr) {
        *ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
    }
    ATLAS_GPU_LET(made_pipeline, std::move(pipeline));
    self->pipeline_ = std::move(made_pipeline);
    return self;
}

gpu::Result<vk::raii::DescriptorSet> BoxBlur::bind(vk::ImageView source, vk::ImageView target, vk::Buffer jobs) const {
    const vk::raii::Device& d = device_->handle();
    ATLAS_GPU_LET(set, gpu::allocate_set(d, pool_, set_layout_));
    gpu::SetWriter(*set)
        .image(0, vk::DescriptorType::eStorageImage, source, vk::ImageLayout::eGeneral)
        .image(1, vk::DescriptorType::eStorageImage, target, vk::ImageLayout::eGeneral)
        .buffer(2, vk::DescriptorType::eStorageBuffer, jobs)
        .apply(d);
    return std::move(set);
}

bool BoxBlur::write_jobs(std::span<std::byte> out, std::span<const BlurRect> rects, Rounding rounding) {
    if (rects.size() > max_rects || out.size() < rects.size() * sizeof(Job)) return false;
    for (const BlurRect& r : rects) {
        if (r.run.count > 3 || r.run.reach_left() > max_reach || r.run.reach_right() > max_reach) return false;
        for (std::uint32_t i = 0; i < r.run.count; ++i) {
            if (r.run.boxes[i].right == 0) return false; // a box covers its own texel
        }
    }
    for (std::size_t n = 0; n < rects.size(); ++n) {
        const BlurRect& r = rects[n];
        Job job{};
        job.rect = {r.x, r.y, static_cast<std::int32_t>(r.width), static_cast<std::int32_t>(r.height)};
        job.left = {0U, 0U, 0U, r.run.count};
        job.right = {1U, 1U, 1U, 0U};
        job.inv = {0.0F, 0.0F, 0.0F, rounding == Rounding::nearest ? 0.5F : 0.0F};
        for (std::uint32_t i = 0; i < r.run.count; ++i) {
            job.left[i] = r.run.boxes[i].left;
            job.right[i] = r.run.boxes[i].right;
            job.inv[i] = 1.0F / static_cast<float>(r.run.boxes[i].size());
        }
        std::memcpy(out.data() + (n * sizeof(Job)), &job, sizeof job);
    }
    return true;
}

void BoxBlur::record(const vk::raii::CommandBuffer& cmd, vk::DescriptorSet set, std::uint32_t first,
                     std::span<const BlurRect> rects, const BlurPass& pass) const {
    if (rects.empty()) return;
    const bool along_x = pass.axis == Axis::x;
    std::uint32_t longest = 0;
    std::uint32_t lines = 0;
    for (const BlurRect& r : rects) {
        longest = std::max(longest, along_x ? r.width : r.height);
        lines = std::max(lines, along_x ? r.height : r.width);
    }
    if (longest == 0 || lines == 0) return;
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *layout_, 0, set, {});
    const Push p{.axis = along_x ? 0U : 1U,
                 .mode = (pass.edge == Edge::extend ? mode_extend : 0U) | (pass.opaque ? mode_opaque : 0U),
                 .count = static_cast<std::uint32_t>(rects.size()),
                 .first = first};
    cmd.pushConstants(*layout_, vk::ShaderStageFlagBits::eCompute, 0,
                      vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
    cmd.dispatch((longest + segment - 1) / segment, lines, static_cast<std::uint32_t>(rects.size()));
}

} // namespace atlas::fx
