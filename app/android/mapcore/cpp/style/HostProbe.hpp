// light.slang's compute probes (shaders/style/light_probe.slang, light_probe_f32.slang) on a Vulkan device, for the
// light's parity tests (plan-vector K5): lavapipe on CI and on this Mac (T10); a phone's or the emulator's own driver
// when atlas_tests runs there. The phone's app runs the same probes through VK check (K12).
//
// It makes its own few Vulkan calls through the loader (the C API): the device layer (CP/gpu, K1) has no way yet to
// enable shaderFloat64, which the exact probe needs. Where Vulkan comes from: ATLAS_VULKAN_LOADER, else the Android
// SDK's emulator package on a host (its loader, lavapipe and the validation layer, which it points the loader at when
// the environment names no driver or layer path), else the platform's loader. The Khronos validation layer, with
// synchronization validation, runs whenever the loader has it and is required when ATLAS_REQUIRE_VULKAN=1; any message
// it sends fails the run.
#pragma once

#include "style/Light.hpp"

#include <array>
#include <cstdint>
#include <expected>
#include <span>
#include <string>
#include <vector>

namespace atlas::style {

/// One input of the exact probe, as light_probe.slang's LightInput lays it out (std430).
struct LightInput {
    std::array<double, 3> base;   // the surface's colour, 0-255 (modes 1 and 2: the arguments)
    std::array<double, 3> normal; // the unit normal
    std::uint32_t lit;            // 1 where its sun bit is set
    std::uint32_t theme;          // the index of its theme
};

static_assert(sizeof(LightInput) == 56);

/// One input of the binary32 probe, as light_probe_f32.slang's LightInputF32 lays it out.
struct LightInputF32 {
    shaders::float4 base;   // rgb: the surface's colour (0-255); a: 1 where its sun bit is set
    shaders::float4 normal; // xyz: the unit normal; w: the index of its theme
};

static_assert(sizeof(LightInputF32) == 32);

/// What the exact probe computes (light_probe.slang's LightProbeParams.mode).
enum class ProbeMode : std::uint8_t {
    light = 0,      ///< light() and tohex of each surface
    operations = 1, ///< light_exp(base[0]), light_div255(base[1]), light_div50(base[2])
    quotient = 2,   ///< light_quotient_near2(normal[0], normal[1])
};

/// The exact probe's results: per input, tohex packed r | g << 8 | b << 16 (mode light), and three doubles.
struct LightProbeOutput {
    std::vector<std::uint32_t> bytes;
    std::vector<double> values;
    std::string device; ///< the device's name and driver, and whether the validation layer ran
};

/// The binary32 probe's results: per input, tohex_f32 packed, and light_f32's value.
struct LightProbeF32Output {
    std::vector<std::uint32_t> bytes;
    std::vector<shaders::float4> values;
    std::string device;
};

/// Why a probe did not run.
struct ProbeError {
    /// No Vulkan here: no loader, no device with a compute queue, or (for the exact probe) none with shaderFloat64. A
    /// skip, unless Vulkan is required; anything else is a failure.
    bool unavailable = false;
    std::string what;
};

/// Runs the exact probe over `inputs`, lit by `themes`.
[[nodiscard]] std::expected<LightProbeOutput, ProbeError> run_light_probe(std::span<const LightInput> inputs,
                                                                          std::span<const shaders::Theme> themes,
                                                                          ProbeMode mode = ProbeMode::light);

/// Runs the binary32 probe over `inputs`, lit by `themes`.
[[nodiscard]] std::expected<LightProbeF32Output, ProbeError>
run_light_probe_f32(std::span<const LightInputF32> inputs, std::span<const shaders::Theme> themes);

/// Whether the environment demands Vulkan and its validation layer (ATLAS_REQUIRE_VULKAN=1, as CI and the presets set).
[[nodiscard]] bool vulkan_required() noexcept;

} // namespace atlas::style
