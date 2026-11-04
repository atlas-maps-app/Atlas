#include "passes/Team.hpp"

#include <ctime>

#if defined(__ANDROID__)
#include <pthread.h>
#endif

namespace atlas::passes {

namespace {

/// The CPU time this thread has run, nanoseconds.
[[nodiscard]] std::uint64_t thread_ns() noexcept {
    timespec t{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return (static_cast<std::uint64_t>(t.tv_sec) * 1'000'000'000U) + static_cast<std::uint64_t>(t.tv_nsec);
}

} // namespace

Team::Team(unsigned helpers) {
    helpers_.reserve(helpers);
    for (unsigned k = 1; k <= helpers; ++k) {
        helpers_.emplace_back([this, k](const std::stop_token& stop) { work(stop, k); });
    }
}

double Team::run(std::size_t items, const Job& job) {
    if (helpers_.empty() || items < 2) {
        for (std::size_t i = 0; i < items; ++i) job(i, 0);
        return 0.0;
    }
    std::uint32_t round = 0;
    {
        const std::lock_guard lock(mutex_);
        round = ++round_;
        job_ = &job;
        items_ = items;
        finished_.store(0, std::memory_order_relaxed);
        helped_.store(0, std::memory_order_relaxed);
        next_.store(std::uint64_t{round} << 32U, std::memory_order_release);
    }
    wake_.notify_all();
    take(round, items, &job, 0);
    std::unique_lock lock(mutex_);
    done_.wait(lock, [&] { return finished_.load(std::memory_order_acquire) == items; });
    return static_cast<double>(helped_.load(std::memory_order_relaxed)) * 1e-6;
}

void Team::work(const std::stop_token& stop, unsigned taker) {
#if defined(__ANDROID__)
    (void)::pthread_setname_np(::pthread_self(), "atlas-team");
#endif
    std::uint32_t seen = 0;
    for (;;) {
        const Job* job = nullptr;
        std::size_t items = 0;
        {
            std::unique_lock lock(mutex_);
            if (!wake_.wait(lock, stop, [&] { return round_ != seen; })) return;
            seen = round_;
            job = job_;
            items = items_;
        }
        take(seen, items, job, taker);
    }
}

void Team::take(std::uint32_t round, std::size_t items, const Job* job, unsigned taker) {
    std::uint64_t at = next_.load(std::memory_order_acquire);
    for (;;) {
        if ((at >> 32U) != round || (at & 0xFFFF'FFFFU) >= items) return;
        if (!next_.compare_exchange_weak(at, at + 1, std::memory_order_acq_rel, std::memory_order_acquire)) continue;
        const std::uint64_t began = taker != 0 ? thread_ns() : 0U;
        (*job)(static_cast<std::size_t>(at & 0xFFFF'FFFFU), taker);
        if (taker != 0) helped_.fetch_add(thread_ns() - began, std::memory_order_relaxed);
        // the item's results (and its time) before its count, which the caller's last read of it takes
        if (finished_.fetch_add(1, std::memory_order_acq_rel) + 1 == items) {
            const std::lock_guard lock(mutex_);
            done_.notify_one();
        }
        at = next_.load(std::memory_order_acquire);
    }
}

} // namespace atlas::passes
