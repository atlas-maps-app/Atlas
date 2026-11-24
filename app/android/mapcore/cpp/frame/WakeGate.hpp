// The gate between the map's wake and the core's close (jni/Bridge.cpp's Core): the map's workers wake the render
// thread from any thread (a tile published), posting to the core's render loop, until the core closes and frees that
// loop. The workers outlive the close (the map goes after the core's destructor body), so a wake that had read the core
// open and then lost the processor posted through a freed loop until 24 November 2026: the check and the post were two
// steps, and close could set its flag and free the loop between them. Here a wake checks and posts under one lock,
// and close shuts the gate under it, so once close returns no wake is posting nor will post: the loop may then go.
// A leaf lock: a wake runs only the post under it (RenderLoop::post queues the task and writes an eventfd, never
// waiting on the render thread), and close holds it only while it shuts the gate. What the render thread asks
// (closed(), in the tasks posted before the close) reads an atomic, never the lock. Portable, so the hosts test it.
#pragma once

#include <atomic>
#include <mutex>
#include <utility>

namespace atlas::frame {

/// Whether the map's wake may still post to the core's render loop: open until close.
class WakeGate {
public:
    /// Runs `post` under the gate's lock unless the gate is shut; whether it ran.
    template <class F>
    bool wake(F&& post) {
        const std::scoped_lock lock(mutex_);
        if (closed_.load()) return false;
        std::forward<F>(post)();
        return true;
    }

    /// Shuts the gate: waits for a wake that is posting, and no wake posts after it returns.
    void close() {
        const std::scoped_lock lock(mutex_);
        closed_.store(true);
    }

    /// Whether the gate is shut (any thread, without the lock: what a task posted before the close asks).
    [[nodiscard]] bool closed() const noexcept { return closed_.load(); }

private:
    std::mutex mutex_;
    std::atomic<bool> closed_{false};
};

} // namespace atlas::frame
