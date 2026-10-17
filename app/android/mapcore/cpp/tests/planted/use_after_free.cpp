// Planted failure (plan-vector T10): a use after free. Never part of a normal build (CMakeLists.txt leaves
// tests/planted/ out); CI builds it into atlas_tests under ASan and requires the run to fail with ASan's report.
#include <doctest/doctest.h>

#include <memory>

TEST_CASE("planted: a use after free") {
    auto owner = std::make_unique<int[]>(16);
    int* stale = owner.get();
    owner.reset();
    volatile int read = stale[3]; // ASan: heap-use-after-free
    CHECK(read == read);
}
