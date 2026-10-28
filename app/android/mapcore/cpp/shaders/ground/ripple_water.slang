// The water's ripple on the water, for the app's map (passes/Ripple.cpp, FrameState::grounded): ripple.slang with
// RIPPLE_ON_WATER, each pixel's field read where the water under it lies in the fields (passes::water_map, as the glints
// read theirs), so the ripple keeps its place on the water while the map moves and scales with the zoom. The map's
// division a pixel makes this module inexact; the renders' own frames, and the tests that hold the ripple to the CPU's
// word for word, keep ripple.slang's.
#define RIPPLE_ON_WATER 1
#include "ground/ripple.slang"
