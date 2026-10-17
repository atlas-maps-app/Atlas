// Planted failure (plan-vector T3): a call of the platform's sin in the core. Never part of a normal build
// (CMakeLists.txt leaves tests/planted/ out); CI runs clang-tidy with the core's .clang-tidy on this file alone and
// requires it to fail with bugprone-unsafe-functions.
#include <cmath>

double planted_platform_sin(double x) { return std::sin(x); }
