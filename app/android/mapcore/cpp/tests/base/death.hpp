// Death tests: runs a function in a forked child and reports how the child ended and what it wrote to stderr, so a
// test can prove that a broken invariant aborts with its reason (doctest has no death tests).
#pragma once

#include <array>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/wait.h>
#include <unistd.h>
#include <utility>

namespace atlas::test {

/// How a child process ended.
struct Death {
    bool aborted = false; ///< ended by SIGABRT
    std::string stderr_text;
};

/// Runs `body` in a child process; returns whether it aborted and what it printed to stderr.
template <class Body>
Death run_in_child(Body&& body) {
    std::array<int, 2> pipe_fds{};
    if (::pipe(pipe_fds.data()) != 0) std::abort();
    (void)std::fflush(nullptr);
    const pid_t pid = ::fork();
    if (pid < 0) std::abort();
    if (pid == 0) {
        // The test runner's crash handlers would report the child's abort as a crash of its own: undo them.
        (void)std::signal(SIGABRT, SIG_DFL);
        ::close(pipe_fds[0]);
        ::dup2(pipe_fds[1], STDERR_FILENO);
        std::forward<Body>(body)();
        std::_Exit(0);
    }
    ::close(pipe_fds[1]);
    Death death;
    std::array<char, 4096> chunk{};
    ssize_t n = 0;
    while ((n = ::read(pipe_fds[0], chunk.data(), chunk.size())) > 0) {
        death.stderr_text.append(chunk.data(), static_cast<std::size_t>(n));
    }
    ::close(pipe_fds[0]);
    int status = 0;
    ::waitpid(pid, &status, 0);
    death.aborted = WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT;
    return death;
}

} // namespace atlas::test
