// Planted failure (plan-vector T10): a data race. Never part of a normal build (CMakeLists.txt leaves tests/planted/
// out); CI builds it into atlas_tests under TSan and requires the run to fail with TSan's report.
#include <doctest/doctest.h>

#include <thread>

namespace {
int shared_counter = 0; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): the race is the point
} // namespace

TEST_CASE("planted: a data race") {
    std::thread writer([] {
        for (int i = 0; i < 1000; ++i) ++shared_counter; // TSan: data race with the loop below
    });
    for (int i = 0; i < 1000; ++i) ++shared_counter;
    writer.join();
    CHECK(shared_counter > 0);
}
