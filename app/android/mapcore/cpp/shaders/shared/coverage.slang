// What the overlays drawn as the canvas draws them share (shaders/overlay/: the location's puck, the markers' bubbles):
// an antialiased edge's coverage, as Skia's antialiased circles take it, a disc blurred as the canvas's blur and shadow
// layers blur it, and premultiplied colour over premultiplied colour, as the canvas blends sRGB values.
#pragma once

/// The coverage of a pixel whose centre lies `d` px inside an edge (negative outside): Skia's antialiased edge.
float coverage_edge(float d) { return saturate(d + 0.5f); }

/// erf(x) within 1.5e-7 (Abramowitz and Stegun 7.1.26).
float coverage_erf(float x) {
    float a = abs(x);
    float t = 1.0f / (1.0f + 0.3275911f * a);
    float poly = ((((1.061405429f * t - 1.453152027f) * t + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t;
    return sign(x) * (1.0f - poly * exp(-a * a));
}

/// A disc of radius `r` blurred by a Gaussian of sigma `s`, at `d` from its centre (all px): the disc's profile across
/// its centre blurred, which the canvas's blurred circle comes within a few hundredths of.
float coverage_blurred_disc(float d, float r, float s) {
    float k = 1.0f / (s * 1.41421356f);
    return 0.5f * (coverage_erf((r - d) * k) + coverage_erf((r + d) * k));
}

/// `top` over `under`, premultiplied.
float4 premultiplied_over(float4 top, float4 under) { return top + under * (1.0f - top.a); }
