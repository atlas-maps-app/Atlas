// The ground shadows' blur with its group memory poisoned before each tile is loaded (shadow_blur.slang's
// SHADOW_BLUR_POISON): the tests' and VK facts' blur check (CP/passes/BlurCheck.hpp) that the blur reads no word of it
// a tile did not write, which group memory zeroed or left from an earlier group (as lavapipe and a GPU may) would hide.
//
// atlas: exact

#define SHADOW_BLUR_POISON
#include "ground/shadow_blur.slang"
