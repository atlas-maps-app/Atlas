// Planted failure (plan-vector T3): the platform's fmin in the core, which orders -0.0 and +0.0 unlike Python's min.
// Never part of a normal build (CMakeLists.txt leaves tests/planted/ out); CI runs clang-tidy with the core's
// .clang-tidy on this file alone and requires it to fail with bugprone-unsafe-functions.
#include <cmath>

double planted_platform_fmin(double a, double b) { return std::fmin(a, b); }
float planted_platform_fmaxf(float a, float b) { return fmaxf(a, b); }
