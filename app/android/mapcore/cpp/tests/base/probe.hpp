// K0's feature probe: the C++ language and library features of the toolchain the tests were built with.
#pragma once

#include <cstdio>

namespace atlas::test {

/// Prints every feature-test macro the core cares about, with its value or "-" when the toolchain lacks it.
void print_features(std::FILE* out);

} // namespace atlas::test
