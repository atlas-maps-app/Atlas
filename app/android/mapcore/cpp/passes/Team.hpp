// The threads a pass hands its tiles' work to beside the render thread (ENGINE.md gain 22): made from the render
// thread, so at its priority (a thread takes the priority of the one that makes it; Light.cpp lowers its own), each
// run hands its items out in turn to them and to the calling thread, and returns once every item is done. Each item's
// work writes only its own results, which the caller merges in the items' order after, so a run's result is the same
// on any number of threads and in any order they take the items.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <stop_token>
#include <thread>
#include <vector>

namespace atlas::passes {

class Team {
public:
    /// A team of `helpers` threads beside the caller's (0: the caller alone).
    explicit Team(unsigned helpers);
    Team(const Team&) = delete;
    Team& operator=(const Team&) = delete;
    Team(Team&&) = delete;
    Team& operator=(Team&&) = delete;
    ~Team() = default; // the helpers stop and join first (the last member)

    /// The threads a run takes items on: the caller (taker 0) and the helpers (1 on).
    [[nodiscard]] unsigned takers() const noexcept { return static_cast<unsigned>(helpers_.size()) + 1; }

    /// `job(item, taker)` for each item below `items`, once each, on whichever thread takes it first; returns once
    /// every one is done, with the helpers' CPU time over their items (milliseconds: the caller's is its own thread's).
    /// One run at a time (the caller's thread's).
    double run(std::size_t items, const std::function<void(std::size_t, unsigned)>& job);

private:
    using Job = std::function<void(std::size_t, unsigned)>;
    void work(const std::stop_token& stop, unsigned taker);
    /// Takes run `round`'s items (of `items`) until none is left: `job` lives until the run's last item is done, and
    /// none is taken of the run once that one is, so a helper late for it never calls it.
    void take(std::uint32_t round, std::size_t items, const Job* job, unsigned taker);

    std::mutex mutex_;
    std::condition_variable_any wake_; ///< the helpers wait on it for the next run
    std::condition_variable done_;     ///< the caller waits on it for the run's last item
    const Job* job_ = nullptr;
    std::size_t items_ = 0;
    std::uint32_t round_ = 0;
    /// The next item to take, its run's round in the high 32 bits: a helper late for its run takes none of the next's.
    std::atomic<std::uint64_t> next_{0};
    std::atomic<std::size_t> finished_{0}; ///< the run's items done
    std::atomic<std::uint64_t> helped_{0}; ///< the helpers' CPU time over the run's items, nanoseconds
    std::vector<std::jthread> helpers_; ///< last: they start once every member above is made, and stop before they go
};

} // namespace atlas::passes
