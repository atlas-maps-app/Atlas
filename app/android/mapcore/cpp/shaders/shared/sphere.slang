// The bend's sphere in the vertex stage (ENGINE.md step 5, "The bend"; view/Bend.hpp): the sphere of 6,371 km tangent
// to the ground at the camera's target, which the world pass blends its tiles to by w₂ (shaders/world/world.slang),
// shared with the C++ tests (after base/hlsl.hpp) and mirrored in double by scene/WorldBend.cpp.
//
// A point is placed relative to the target, in the frame's axes (x east, y up, z south), from its latitude and
// longitude less the target's, δ and Λ: x = R·cos φ·sin Λ, y = −2R(sin²(δ/2) + cos φ·cos φt·sin²(Λ/2)),
// z = −R(sin δ + 2 sin φt·cos φ·sin²(Λ/2)), cos φ = cos φt·cos δ − sin φt·sin δ, which loses nothing to cancellation
// near the target (the textbook R(cos φt·cos φ·cos Λ + sin φt·sin φ − 1) loses all of a float's digits there). The
// sines and cosines are float polynomials (Cephes' sinf and cosf) after a reduction by π/2 in three parts, good to
// a few float ulps: the GPU's own sin and cos are good to 2⁻¹¹ only, 3 km on the sphere.
//
// Shared with C++: only the subset base/hlsl.hpp describes.
#pragma once

/// sin x and cos x for |x| up to a few π, in float: x less the nearest multiple j of π/2 (π/2 in three parts, the first
/// two short enough that j times them is exact), the polynomials of the remainder y (|y| ≤ π/4), and j's quadrant.
inline float2 sin_cos(float x) {
    float j = floor(x * 0.636619772367581343f + 0.5f); // 2/π
    float y = ((x - j * 1.5703125f) - j * 4.837512969970703125e-4f) - j * 7.54978995489188216e-8f;
    float z = y * y;
    float s = y + y * z * ((-1.9515295891e-4f * z + 8.3321608736e-3f) * z - 1.6666654611e-1f);
    float c = 1.0f - 0.5f * z + z * z * ((2.443315711809948e-5f * z - 1.388731625493765e-3f) * z + 4.166664568298827e-2f);
    uint q = uint(int(j)) & 3u;
    float2 r = {s, c};
    if (q == 1u) {
        float2 t = {c, -s};
        r = t;
    } else if (q == 2u) {
        float2 t = {-s, -c};
        r = t;
    } else if (q == 3u) {
        float2 t = {-c, s};
        r = t;
    }
    return r;
}

/// The point of the sphere of radius `radius` tangent to the ground at a target of latitude φt (`sin_t`, `cos_t`),
/// `d` radians north of it and `l` radians east of it, from the target (x east, y up, z south).
inline float3 on_sphere(float d, float l, float sin_t, float cos_t, float radius) {
    float2 hd = sin_cos(0.5f * d);
    float2 hl = sin_cos(0.5f * l);
    float sin_d = 2.0f * hd.x * hd.y;
    float cos_d = 1.0f - 2.0f * hd.x * hd.x;
    float half_l = hl.x * hl.x; // sin²(Λ/2)
    float cos_p = cos_t * cos_d - sin_t * sin_d;
    float3 p = {radius * cos_p * (2.0f * hl.x * hl.y), -2.0f * radius * (hd.x * hd.x + cos_p * cos_t * half_l),
                -radius * (sin_d + 2.0f * sin_t * cos_p * half_l)};
    return p;
}
