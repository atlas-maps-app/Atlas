// What the session draws in VK bench's stand-in's place (plan-vector K11): the app's map, frame/MapFrame.hpp's frame
// builder over the world. The session holds it while the map is on (Session::map) and draws it in the facts' own pick
// of configuration (gpu/Facts.hpp's choice: the frame the map draws), at the frame's 8 bits. It makes what the map
// draws with on its device behind the pipelines stage, lets go of it before that device goes (a loss, the close), each
// thing announced, and makes it again on the device made after a loss; what the renderer prepares in it (its pipelines)
// comes and goes with the renderer, as the stand-in's does.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"
#include "style/CameraState.hpp"

#include <cstdint>
#include <string>

namespace atlas::standin {

/// One map frame's counts, for its record (standin/FrameRecord.hpp).
struct MapCounts {
    double stage_f_ms = 0; ///< stage F on the render thread: the camera, the screen layers, the cover, the ranks
    double record_ms = 0;  ///< recording the frame's commands
    std::uint32_t draws = 0;
    std::uint64_t triangles = 0;
};

/// The map, as the session draws it.
class SessionMap {
public:
    SessionMap() = default;
    SessionMap(const SessionMap&) = delete;
    SessionMap& operator=(const SessionMap&) = delete;
    SessionMap(SessionMap&&) = delete;
    SessionMap& operator=(SessionMap&&) = delete;
    virtual ~SessionMap() = default;

    /// Makes what the map draws with on `device` (its pipelines are the renderer's to prepare, through `cache`), each
    /// thing announced to `watch`; the session runs it behind the pipelines stage. "" or why not.
    [[nodiscard]] virtual std::string make(const gpu::Device& device, const gpu::PipelineCache& cache,
                                           gpu::PipelineWatch* watch) = 0;
    /// Whether it holds what make made.
    [[nodiscard]] virtual bool made() const noexcept = 0;
    /// Lets go of everything make made, each thing announced to `watch` (its device is about to go).
    virtual void release(gpu::PipelineWatch* watch) noexcept = 0;
    /// What the renderer draws (once made).
    [[nodiscard]] virtual gpu::Content& content() noexcept = 0;
    /// Whether there is a frame to draw: made, and the app's moment received.
    [[nodiscard]] virtual bool ready() const noexcept = 0;
    /// The next frame's camera, as the app wrote it for its vsync, and its time (seconds on the session's clock).
    virtual void next(const style::CameraState& camera, double seconds) = 0;
    /// The last frame's counts.
    [[nodiscard]] virtual MapCounts counts() const noexcept = 0;
};

} // namespace atlas::standin
