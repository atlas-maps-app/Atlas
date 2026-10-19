// The map's frame drawn in the host build for the JVM's tests (plan-vector K7; the likeness harness's candidate, K4,
// CP/jni/TestCases.cpp; L1's twin, CP/jni/TestTwin.cpp): a session on the host's Vulkan (lavapipe on this Mac and on
// CI), the world over render packs, a frame builder with every registered pass, and a camera state and a theme block as
// the app writes them. The frame is drawn until the world settles (the cover's tiles built and drawn, the sun's bits
// in, every fade over), then read back at 8 bits a channel (in the frame's own colour, 10 bits where the device draws
// in them), its owner map drawn in the owner mode at one sample, its counts taken (and in the overdraw mode the
// fragments it shades), and its twin emitted. Implemented in TestTwin.cpp.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace atlas::jni::host_frame {

/// What to draw.
struct Request {
    /// The render packs (the store's id and the file), in lookup order, and the one to draw (empty: the world's choice
    /// for the camera's target).
    std::vector<std::pair<std::string, std::string>> packs;
    std::string active;
    /// The camera state and the theme block, as Kotlin writes them (CameraWriter, ThemeBlock).
    std::span<const std::byte> camera;
    std::span<const std::byte> theme;
    /// Full mode (every effect), else structural.
    bool full = false;
    /// The clear colour (0 to 1, R G B A) where nothing draws.
    std::array<float, 4> clear{0.92F, 0.90F, 0.86F, 1.0F};
    /// What to give back.
    bool pixels = true;
    bool owners = false;
    bool twin = false;
    /// The frame's counts in the overdraw mode too (at one sample): its shaded fragments, full-screen equivalents.
    bool overdraw = false;
};

/// What was drawn.
struct Result {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    /// The frame, R G B A rows at 8 bits a channel.
    std::vector<std::uint8_t> rgba;
    /// The owner map: a key per pixel (scene::owner_key: the owner's kind in the top byte, its OSM id below; 0 none).
    std::vector<std::uint64_t> owners;
    /// The twin's stream (twin/Twin.hpp).
    std::vector<std::byte> twin;
    /// The frame's counts: a `pass <name> <counts>` line per pass, its kinds' `kind <pass> <kind> <counts>` lines and a
    /// `total <counts>` line (frame/Counters.hpp's text_of); with the overdraw mode, the opaque and translucent
    /// fragments shaded over the frame's pixels.
    std::vector<std::string> counters;
    double opaque_fse = 0;
    double translucent_fse = 0;
    /// The device and the configuration it drew with, the passes, and what settling took.
    std::string device;
    std::string passes;
    std::string notes;
    /// Why nothing was drawn (empty when it was).
    std::string error;
};

/// Draws `request`.
[[nodiscard]] Result draw(const Request& request);

} // namespace atlas::jni::host_frame
