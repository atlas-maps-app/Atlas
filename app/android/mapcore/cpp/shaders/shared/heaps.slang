// The geometry heaps' second buffer (scene/Heaps.hpp): a range at or beyond scene::heap_high_base, 2^31 bytes of the
// heaps' offsets, lies in it at its offset less that, so a word's index alone says which buffer holds it. Set 1 binds
// the first buffer at each kind's binding and the second at binding 4 (frame::Global::heap_high), read through it as
// each kind's own; the points kind lies in the first alone (its compute stages read only it).
#pragma once

static const uint HEAP_HIGH_WORDS = 1u << 29u;   // heap_high_base in words (the ground's and the lines' index)
static const uint HEAP_HIGH_VECTORS = 1u << 27u; // and in uint4 (the walls')
