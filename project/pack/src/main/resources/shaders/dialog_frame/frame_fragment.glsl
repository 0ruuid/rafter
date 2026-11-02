vec4 rafter_frame_color() {
    // Viewport and native-modal tiles share the same exact source-pixel sampling.
    // Never sample the gutter, never use filtered texture(), never multiply by encoded RGB.
    ivec2 pixel = clamp(ivec2(floor(rafterFrameUV)), ivec2(0), rafterFrameSize - ivec2(1));
    vec4 color = texelFetch(Sampler0, rafterFrameOrigin + ivec2(4) + pixel, 0);
    color.a *= vertexColor.a;
    return color * ColorModulator;
}
