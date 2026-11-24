// The gate between the map's wake and the core's close (frame/WakeGate.hpp, jni/Bridge.cpp's Core): once close
// returns, no wake is posting nor will post, so the core may free the render loop a wake posts to. The loop here is a
// fake whose post waits on a semaphore, so a wake is held mid-post while the core closes.
#include "frame/WakeGate.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <chrono>
#include <semaphore>
#include <thread>

using namespace atlas;

namespace {

/// The core's render loop as a wake sees it: a post, and whether the loop still lives (the core frees it once close
/// returns).
struct FakeLoop {
    std::atomic<bool> alive{true};
    std::atomic<int> posts{0};
    std::atomic<int> posts_to_a_freed_loop{0};

    void post() {
        if (!alive.load()) ++posts_to_a_freed_loop;
        ++posts;
    }
};

} // namespace

TEST_CASE("wake gate: close waits for a wake that is posting, so the loop may go once close returns") {
    frame::WakeGate gate;
    FakeLoop loop;
    std::binary_semaphore in_post{0};
    std::binary_semaphore finish_post{0};
    std::binary_semaphore close_returned{0};
    // A worker's wake: it finds the gate open and is held mid-post.
    std::atomic<bool> posted{false};
    std::thread waker([&] {
        posted.store(gate.wake([&] {
            in_post.release();
            finish_post.acquire();
            loop.post();
        }));
    });
    in_post.acquire();
    // The core closes, then frees its loop (~Core's loop_.reset()).
    std::thread closer([&] {
        gate.close();
        loop.alive.store(false);
        close_returned.release();
    });
    // Close must not return while the wake posts: it is given time to (without the gate it returns at once).
    const bool returned_during_post = close_returned.try_acquire_for(std::chrono::milliseconds(200));
    finish_post.release();
    waker.join();
    if (!returned_during_post) close_returned.acquire();
    closer.join();
    CHECK_FALSE(returned_during_post);
    CHECK(posted.load());
    CHECK(loop.posts.load() == 1);
    CHECK(loop.posts_to_a_freed_loop.load() == 0);
    CHECK(gate.closed());
}

TEST_CASE("wake gate: a wake posts while the gate is open and posts nothing once it is shut") {
    frame::WakeGate gate;
    int posts = 0;
    CHECK_FALSE(gate.closed());
    CHECK(gate.wake([&] { ++posts; }));
    CHECK(gate.wake([&] { ++posts; }));
    gate.close();
    CHECK(gate.closed());
    CHECK_FALSE(gate.wake([&] { ++posts; }));
    CHECK(posts == 2);
}
