// The overdraw mode's fragment stage (plan-vector §3, frame/Counters.hpp): in place of a scene pipeline's own, it adds
// 1/255 of red for each opaque fragment shaded and 1/255 of green for each translucent one (the frame builder's
// pipelines blend it ONE, ONE onto red, green and blue only), so the frame read back counts the fragments each pixel
// shaded: CI's "opaque shaded fragments at most 1.6 FSE" (frame/Pass.hpp's make_pipeline makes the twins).

struct OverdrawOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
OverdrawOut overdraw_opaque_fs() {
    OverdrawOut o;
    o.colour = float4(1.0f / 255.0f, 0.0f, 0.0f, 1.0f);
    return o;
}

[shader("fragment")]
OverdrawOut overdraw_translucent_fs() {
    OverdrawOut o;
    o.colour = float4(0.0f, 1.0f / 255.0f, 0.0f, 1.0f);
    return o;
}
