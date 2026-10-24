// The twin's check on the phones' Bionic (ENGINE.md's checks: VK facts' hash lines; D16's L1 compared by hash): the
// app's frame over the APK's bundled Westerkerk (tiles/amsterdam.atlas) at the engine references' home (amsterdam.py's
// main camera: 430 m, bearing 52°, map pitch 52°) on S1 (1080 × 2400 px at 1080/412), at rest, at the sunny moment of
// 1 October 2026, 16:00 (the port's sun bits), in full mode, with the app's map's choices (the coarser pack around the
// active one, the drawn pack's profile on the theme: frame/MapFrame.hpp), settled without drawing (FrameBuilder::emit
// runs stage F alone: the world's tiles built and drawn, their sun bits in, every fade over, as host_frame::draw
// settles), and the twin's stream of the frame (twin/Twin.hpp) hashed with SHA-256 (its first 8 bytes). So the core's
// stage T, its stage F and the vertex stage's maths compiled as C++ are held, bit for bit, to the host build's on each
// phone and on the emulator.
//
// The frame's passes are the ground, the buildings, the trees, the street furniture and the composite. The canal names
// are shaped under the platform's kerning rule (text::platform_rounding, which VK facts measures with Android's Paint),
// so they are left out: VK facts' text.glyphs line holds their shaping to Android's. The check makes no noise fields,
// so the composite emits no grain. The host build computes the same (tests/frame/twin_check_test.cpp), and
// twin_check_expected is its hash.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/PipelineCache.hpp"
#include "style/CameraState.hpp"
#include "style/Theme.hpp"
#include "tiles/Store.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace atlas::frame {

/// The passes the check's frame has (frame/PassRegistry.cpp's names).
inline constexpr std::array<std::string_view, 5> twin_check_passes{"composite", "ground", "buildings", "trees",
                                                                   "things"};

/// The check's camera: the engine references' home on S1 at rest, as the app writes it (no engine mode; the world's
/// choice of pack).
[[nodiscard]] style::CameraState twin_check_camera() noexcept;

/// The check's moment: amsterdam.py's sunny moment of 1 October 2026, 16:00, as Kotlin writes it (tests/style/
/// ThemeTest.cpp's values), its sun the port's bits (sun::bundled_sun).
[[nodiscard]] style::ThemeBlock twin_check_theme() noexcept;

/// What the check emitted.
struct TwinCheck {
    std::uint64_t hash = 0;   ///< the stream's SHA-256, its first 8 bytes (big-endian)
    std::size_t bytes = 0;    ///< the stream's size
    std::uint32_t frames = 0; ///< frames of stage F until the world settled
    std::size_t tiles = 0;    ///< tiles drawn
    std::size_t elements = 0; ///< the buildings' elements emitted
    std::size_t sunlit = 0;   ///< of them, those their sun bit lit
};

/// The twin's check over `westerkerk` (the bundled Westerkerk's render pack) on `device`, its frame builder's
/// pipelines through `cache` (none are made: nothing is drawn). It runs the world's two workers and its sun, and takes
/// a few seconds of a phone's cores.
[[nodiscard]] gpu::Result<TwinCheck> twin_check(const gpu::Device& device, const gpu::PipelineCache& cache,
                                                tiles::PackRef westerkerk);

/// The host build's hash of the twin's check.
inline constexpr std::uint64_t twin_check_expected = 0xF8B5'15B5'EEC5'153DULL;

/// `twin <hash> ok`, or `twin <hash> FAIL expected <hash>`, or `twin unavailable: <why>`.
[[nodiscard]] std::string twin_line(const gpu::Device& device, const gpu::PipelineCache& cache,
                                    tiles::PackRef westerkerk);

} // namespace atlas::frame
