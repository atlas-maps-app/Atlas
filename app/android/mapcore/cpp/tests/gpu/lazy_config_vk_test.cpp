// Configuration A and lazily allocated memory (plan-vector D5.3; gpu/Facts.hpp): A keeps the frame's 4x samples on
// chip in lazily allocated memory, and its main pass has no other memory to take, so a tiler takes A only where that
// memory holds the samples of the frame's colour and its depth-stencil at 4x. The masks' passes take ordinary memory
// where it refuses theirs (R8, RGBA8), so they count for nothing; VK facts prints every answer
// (`lazily.allocated.samples:`). The device is asked as it opens (Device::open's probe_lazy), after the configuration
// was chosen, which is chosen again where the answer is no, the config's reason naming the format and the samples. None
// of these needs a device: the facts are written by hand, as a tiler's would read (lavapipe and the emulator have no
// lazily allocated memory, so a device's own run shows nothing of it).
#include "gpu/Facts.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <format>
#include <string>
#include <utility>
#include <vector>

using namespace atlas;

namespace {

using F = vk::Format;

/// A tiler with lazily allocated memory, 1x and 4x samples (and 2x where `two`), its 8-bit colour formats asked as a
/// device's are when it is chosen, a depth-stencil of D32S8, and nothing asked yet of lazily allocated memory.
gpu::Facts tiler(bool two = false) {
    gpu::Facts f;
    f.vendor_id = 0x1010;
    f.tiler = true;
    f.lazy_memory = true;
    vk::SampleCountFlags counts = vk::SampleCountFlagBits::e1 | vk::SampleCountFlagBits::e4;
    if (two) counts |= vk::SampleCountFlagBits::e2;
    f.limits.framebufferColorSampleCounts = counts;
    f.limits.framebufferDepthSampleCounts = counts;
    f.limits.framebufferStencilSampleCounts = counts;
    f.depth_stencil = F::eD32SfloatS8Uint;
    for (const F format :
         {F::eA2B10G10R10UnormPack32, F::eA2R10G10B10UnormPack32, F::eR8G8B8A8Unorm, F::eB8G8R8A8Unorm}) {
        const bool eight = !gpu::ten_bit(format);
        f.colour_formats.push_back(
            {.format = format, .queried = eight, .output = eight, .attachment = eight, .samples = counts});
    }
    return f;
}

/// What the device answered about one format: lazily allocated memory holds its 4x samples, or not.
void answer(gpu::Facts& f, F format, bool lazy) {
    if (format == F::eR8Unorm) {
        f.lazy_mask_r8 = {.lazy = lazy, .checked = true};
        return;
    }
    if (format == f.depth_stencil) {
        f.lazy_depth_stencil = {.lazy = lazy, .checked = true};
        return;
    }
    for (gpu::ColourFormat& c : f.colour_formats) {
        if (c.format == format) {
            c.lazy = lazy;
            c.lazy_checked = true;
        }
    }
}

/// What the masks' passes answered of RGBA8 (a colour attachment, without the frame's input).
void answer_mask_rgba8(gpu::Facts& f, bool lazy) { f.lazy_mask_rgba8 = {.lazy = lazy, .checked = true}; }

/// VK facts' line of what lazily allocated memory holds.
std::string samples_line(const gpu::Facts& f) {
    const auto lines = gpu::facts_lines(f);
    const auto it =
        std::ranges::find_if(lines, [](const std::string& l) { return l.starts_with("lazily.allocated.samples: "); });
    return it == lines.end() ? std::string() : *it;
}

/// The configuration chosen for `f`, as the facts alone choose it.
gpu::FrameConfig chosen(gpu::Facts f) {
    gpu::choose_configuration(f, false);
    return f.choice.config;
}

} // namespace

TEST_CASE("configuration A needs lazily allocated memory that holds its 4x samples of the frame's colour and its "
          "depth-stencil, and nothing of the masks") {
    // Not asked yet, or every answer yes: A, as before.
    CHECK(chosen(tiler()) == gpu::FrameConfig::a);
    CHECK(samples_line(tiler()) == "lazily.allocated.samples: 4x, transient: colour rgba8 not asked, depth.stencil "
                                   "d32s8 not asked; the masks (ordinary memory where refused): r8 not asked, rgba8 "
                                   "not asked");
    gpu::Facts yes = tiler();
    for (const F format : {F::eR8G8B8A8Unorm, F::eB8G8R8A8Unorm, F::eD32SfloatS8Uint, F::eR8Unorm}) {
        answer(yes, format, true);
    }
    answer_mask_rgba8(yes, true);
    CHECK(yes.supports(gpu::FrameConfig::a));
    CHECK_FALSE(yes.lazy_refused().has_value());
    CHECK(chosen(yes) == gpu::FrameConfig::a);
    CHECK(samples_line(yes) == "lazily.allocated.samples: 4x, transient: colour rgba8 yes, depth.stencil d32s8 yes; "
                               "the masks (ordinary memory where refused): r8 yes, rgba8 yes");

    // The frame's colour, or its depth-stencil, refused: C (no 2x here), the reason naming the format and the samples.
    for (const auto& [format, name] :
         {std::pair{F::eR8G8B8A8Unorm, "rgba8"}, std::pair{F::eD32SfloatS8Uint, "d32s8"}}) {
        gpu::Facts f = yes;
        answer(f, format, false);
        CAPTURE(name);
        CHECK(f.lazy_refused() == format);
        CHECK_FALSE(f.supports(gpu::FrameConfig::a));
        gpu::choose_configuration(f, false);
        CHECK(f.choice.config == gpu::FrameConfig::c);
        CHECK(f.config_reason ==
              std::format("lazily allocated memory cannot hold configuration A's 4x samples of {}, and the device has "
                          "no 2x samples",
                          name));
    }
    // D24S8 where it is the depth-stencil.
    gpu::Facts d24 = yes;
    d24.depth_stencil = F::eD24UnormS8Uint;
    answer(d24, F::eD24UnormS8Uint, false);
    CHECK(d24.lazy_refused() == F::eD24UnormS8Uint);
    CHECK(chosen(d24) == gpu::FrameConfig::c);
    // With 2x within 0.15 MAD of 4x (K6): B.
    gpu::Facts two = tiler(true);
    answer(two, F::eD32SfloatS8Uint, false);
    gpu::choose_configuration(two, true);
    CHECK(two.choice.config == gpu::FrameConfig::b);
    CHECK(two.config_reason == "lazily allocated memory cannot hold configuration A's 4x samples of d32s8, and 2x is "
                               "within 0.15 MAD of 4x (K6)");

    // The masks refused, R8 and RGBA8 alike: A all the same (their passes take ordinary memory), the line saying so.
    gpu::Facts masks = yes;
    answer(masks, F::eR8Unorm, false);
    answer_mask_rgba8(masks, false);
    CHECK_FALSE(masks.lazy_refused().has_value());
    CHECK(masks.supports(gpu::FrameConfig::a));
    gpu::choose_configuration(masks, false);
    CHECK(masks.choice.config == gpu::FrameConfig::a);
    CHECK(masks.config_reason == "a tiler with lazily allocated memory: 4x on chip, no memory");
    CHECK(samples_line(masks) == "lazily.allocated.samples: 4x, transient: colour rgba8 yes, depth.stencil d32s8 yes; "
                                 "the masks (ordinary memory where refused): r8 no, rgba8 no");

    // The frame's colour is the one the frame picks: bgra8 refused while it draws in rgba8 leaves A; refused while it
    // draws in bgra8, C. RGBA8 refused while it draws in bgra8 leaves A.
    gpu::Facts bgra = yes;
    answer(bgra, F::eB8G8R8A8Unorm, false);
    CHECK(chosen(bgra) == gpu::FrameConfig::a);
    bgra.colour.format = F::eB8G8R8A8Unorm;
    CHECK(bgra.lazy_refused() == F::eB8G8R8A8Unorm);
    CHECK(chosen(bgra) == gpu::FrameConfig::c);
    gpu::Facts rgba = yes;
    rgba.colour.format = F::eB8G8R8A8Unorm;
    answer(rgba, F::eR8G8B8A8Unorm, false);
    CHECK_FALSE(rgba.lazy_refused().has_value());
    CHECK(chosen(rgba) == gpu::FrameConfig::a);
    // A 10-bit format the frame does not draw in counts for nothing.
    gpu::Facts ten = yes;
    answer(ten, F::eA2B10G10R10UnormPack32, false);
    CHECK_FALSE(ten.lazy_refused().has_value());
    CHECK(chosen(ten) == gpu::FrameConfig::a);

    // A device that is not a tiler, or has no lazily allocated memory, keeps its reason.
    gpu::Facts flat = tiler();
    flat.tiler = false;
    answer(flat, F::eD32SfloatS8Uint, false);
    gpu::choose_configuration(flat, false);
    CHECK(flat.choice.config == gpu::FrameConfig::c);
    CHECK(flat.config_reason == "not a tiler, and the device has no 2x samples");
    gpu::Facts none = tiler();
    none.lazy_memory = false;
    gpu::choose_configuration(none, false);
    CHECK(none.config_reason == "no lazily allocated memory, and the device has no 2x samples");
    CHECK(samples_line(none) == "lazily.allocated.samples: none (no lazily allocated memory)");
}

TEST_CASE("configuration A chosen before the device was asked is chosen again where it refuses, and nothing else "
          "moves") {
    // As Device::open finds it: A chosen from the facts (pick), then the device's answers.
    gpu::Facts f = tiler();
    gpu::choose_configuration(f, false);
    REQUIRE(f.choice.config == gpu::FrameConfig::a);
    const gpu::Configuration before = f.choice;
    const std::string reason = f.config_reason;
    const std::string route = f.route_reason;
    const std::string target = f.target_reason;

    // Every answer yes, or only the masks refused: A, every reason as it was.
    gpu::Facts held = f;
    for (const F format : {F::eR8G8B8A8Unorm, F::eB8G8R8A8Unorm, F::eD32SfloatS8Uint}) answer(held, format, true);
    answer(held, F::eR8Unorm, false);
    answer_mask_rgba8(held, false);
    gpu::choose_again_after_probe(held, false);
    CHECK(held.choice.config == before.config);
    CHECK(held.choice.route == before.route);
    CHECK(held.choice.form == before.form);
    CHECK(held.choice.target == before.target);
    CHECK(held.config_reason == reason);
    CHECK(held.route_reason == route);
    CHECK(held.target_reason == target);

    // The depth-stencil's samples refused: C, saying why.
    gpu::Facts refused = f;
    answer(refused, F::eD32SfloatS8Uint, false);
    gpu::choose_again_after_probe(refused, false);
    CHECK(refused.choice.config == gpu::FrameConfig::c);
    CHECK(
        refused.config_reason ==
        "lazily allocated memory cannot hold configuration A's 4x samples of d32s8, and the device has no 2x samples");
    const auto lines = gpu::facts_lines(refused);
    CHECK(std::ranges::find(lines, "config: C (lazily allocated memory cannot hold configuration A's 4x samples of "
                                   "d32s8, and the device has no 2x samples)") != lines.end());
    CHECK(std::ranges::find(lines, "configs.available: A no, B no, C yes, 1x yes") != lines.end());

    // A configuration other than A stays as chosen, whatever the answers.
    gpu::Facts c = tiler();
    c.tiler = false;
    gpu::choose_configuration(c, false);
    REQUIRE(c.choice.config == gpu::FrameConfig::c);
    const std::string c_reason = c.config_reason;
    answer(c, F::eR8Unorm, false);
    gpu::choose_again_after_probe(c, false);
    CHECK(c.choice.config == gpu::FrameConfig::c);
    CHECK(c.config_reason == c_reason);
}
