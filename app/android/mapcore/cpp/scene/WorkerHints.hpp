// What the residency's workers tell the platform (ENGINE.md gain 8): they run at background priority while nothing the
// frame shows waits on them (the prefetch, and the windows and the sun on threads of their own stay there), and at
// normal priority, with a hint session of their own, while a tile in view is missing. On Android the platform layer's
// (platform::WorkerSession: the threads' nice and ADPF); elsewhere none. The residency calls it outside its lock, from
// its workers and the render thread at once: it must be thread-safe, and never keep the render thread waiting.
#pragma once

#include <cstdint>

namespace atlas::scene {

/// The workers' hints.
class WorkerHints {
public:
    WorkerHints() = default;
    WorkerHints(const WorkerHints&) = delete;
    WorkerHints& operator=(const WorkerHints&) = delete;
    WorkerHints(WorkerHints&&) = delete;
    WorkerHints& operator=(WorkerHints&&) = delete;
    virtual ~WorkerHints() = default;

    /// A worker has started: called on its own thread, before it takes any work, which waits at background priority.
    virtual void joined() = 0;
    /// Whether the frame waits on the workers from now on (a tile it wants in view is queued or under way) or no
    /// longer: their priority and their session follow. Called on the thread that saw the change (the render thread's
    /// wants, a worker's tile done), once per change, one call at a time.
    virtual void urgent(bool on) = 0;
    /// A piece of the work the frame waits on, done on a worker (a tile's decoding or a pass's stage T): when it began
    /// (the steady clock's nanoseconds, CLOCK_MONOTONIC on Android) and how long it took. On the worker, while others
    /// report theirs.
    virtual void report(std::int64_t start_ns, std::int64_t ns) = 0;
};

} // namespace atlas::scene
