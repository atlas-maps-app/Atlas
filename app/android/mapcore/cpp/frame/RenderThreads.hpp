// What the frame tells the platform of the threads its work runs on (ENGINE.md gain 22): the render thread, and the
// helpers a pass hands its tiles' work to beside it (passes/Team.hpp), which the frame waits for as for its own work.
// On Android the render thread's hint session takes them in (platform::HintSession::threads:
// APerformanceHint_setThreads, API 34), so that the boost a frame asks for reaches the work it hands them; elsewhere
// none.
#pragma once

#include <cstdint>
#include <span>

namespace atlas::frame {

/// The threads the frame's work runs on.
class RenderThreads {
public:
    RenderThreads() = default;
    RenderThreads(const RenderThreads&) = delete;
    RenderThreads& operator=(const RenderThreads&) = delete;
    RenderThreads(RenderThreads&&) = delete;
    RenderThreads& operator=(RenderThreads&&) = delete;
    virtual ~RenderThreads() = default;

    /// The threads the frame's work runs on from now on, by their kernel ids (passes::thread_id): the render thread
    /// first, then the helpers of the ground's team, told once each time the pass makes its team, at its first use (the
    /// render thread alone in a team of none). Called on the render thread during the frame, which it must not keep
    /// waiting; `tids` holds for the call alone.
    virtual void took(std::span<const std::int32_t> tids) = 0;
};

} // namespace atlas::frame
