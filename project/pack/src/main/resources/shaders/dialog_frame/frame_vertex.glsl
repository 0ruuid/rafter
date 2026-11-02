// Protocol 2: square 256px bitmaps, 4px gutter, 248px payload; never a vertex-ID phase.
// FontTexture in both verified clients insets glyph UVs by 0.01 texel. floor therefore
// addresses the vertex's own corner texel, without probing a neighbouring glyph.
vec2 rafter_frame_gui() {
    return ceil(abs(vec2(
        2.0 / (ProjMat[0][0] * ModelViewMat[0][0]),
        2.0 / (ProjMat[1][1] * ModelViewMat[1][1]))) - vec2(0.001));
}

float rafter_modal_body_top(vec2 gui, float height, float footer) {
    return min(RAFTER_MODAL_PREFERRED_TOP,
        gui.y - footer - min(height, max(gui.y - RAFTER_MODAL_HEADER_HEIGHT - footer, 0.0)));
}

bool rafter_modal_backdrop_position() {
    // The bounded font shift and reserved X domain must both match; ordinary fonts keep vanilla rendering.
    if (RAFTER_MODAL_BACKDROP_COUNT == 0 || Position.x > -RAFTER_MODAL_X_BASE * 0.5) return false;
    float glyphLocalY = Position.y - RAFTER_MODAL_TITLE_Y - RAFTER_MODAL_FONT_Y_SHIFT;
    if (abs(glyphLocalY) > 256.0) return false;

    vec2 gui = rafter_frame_gui();
    float anchorX = floor((gui.x - RAFTER_MODAL_TITLE_GROUP_WIDTH) * 0.5);
    for (int index = 0; index < RAFTER_MODAL_BACKDROP_COUNT; index++) {
        vec4 sourceLayout = RAFTER_MODAL_BACKDROPS[index];
        float xBase = sourceLayout.w;
        int row = int(floor((anchorX - xBase - Position.x +
            RAFTER_MODAL_ROW_STRIDE * 0.5) / RAFTER_MODAL_ROW_STRIDE));
        int rows = int((sourceLayout.y - RAFTER_MODAL_TEXT_PADDING * 2.0) / RAFTER_MODAL_ROW_HEIGHT);
        if (row < -1 || row >= rows) continue;
        float localX = Position.x - anchorX + xBase + float(row) * RAFTER_MODAL_ROW_STRIDE;
        vec2 point;
        if (row == -1) {
            if (localX < -512.0 || localX > 1024.0) continue;
            point = vec2(anchorX + localX, RAFTER_MODAL_TITLE_Y + glyphLocalY);
        } else {
            float sourceWidth = sourceLayout.x - RAFTER_MODAL_TEXT_PADDING * 2.0 - RAFTER_MODAL_WRAP_SLACK;
            if (localX < -128.0 || localX > sourceWidth + 128.0) continue;
            point = vec2(floor((gui.x - sourceLayout.x) * 0.5) + RAFTER_MODAL_TEXT_PADDING + localX,
                rafter_modal_body_top(gui, sourceLayout.z, RAFTER_MODAL_FOOTER_HEIGHT) + RAFTER_MODAL_TEXT_PADDING +
                float(row) * RAFTER_MODAL_ROW_HEIGHT + glyphLocalY);
        }
        gl_Position = ProjMat * ModelViewMat * vec4(point, Position.z, 1.0);
        return true;
    }
    return false;
}

int rafter_frame_u16(ivec2 origin, int field) {
    ivec2 rg = ivec2(round(texelFetch(Sampler0, origin + ivec2(field + 2, 2), 0).rg * 255.0));
    return rg.x | (rg.y << 8);
}

void rafter_frame_position() {
    rafterFrame = 0;
    rafterFrameOrigin = ivec2(0);
    rafterFrameSize = ivec2(1);
    rafterFrameUV = vec2(0.0);
    if (rafter_modal_backdrop_position()) return;
    ivec2 atlas = textureSize(Sampler0, 0);
    ivec2 pixel = ivec2(floor(UV0 * vec2(atlas)));
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, atlas))) return;
    ivec4 marker = ivec4(round(texelFetch(Sampler0, pixel, 0) * 255.0));
    if (marker.r != 82 || marker.g != 70 || marker.b > 3 || marker.a != 255) return;
    ivec2 corner = ivec2(marker.b & 1, (marker.b >> 1) & 1);
    ivec2 origin = pixel - corner * 255;
    if (any(lessThan(origin, ivec2(0))) || any(greaterThan(origin + ivec2(256), atlas))) return;
    ivec4 signature = ivec4(round(texelFetch(Sampler0, origin + ivec2(0, 2), 0) * 255.0));
    ivec4 version = ivec4(round(texelFetch(Sampler0, origin + ivec2(1, 2), 0) * 255.0));
    if (any(notEqual(signature, ivec4(167, 211, 91, 255))) ||
        any(notEqual(version, ivec4(2, 4, 248, 255)))) return;

    // Only this fully identified private bitmap interprets RGB as runtime parameters.
    ivec3 rgb = ivec3(round(Color.rgb * 255.0));
    float requestedWidth = float(rgb.r | (rgb.g << 8));
    float inset = float(rgb.b);
    ivec2 source = ivec2(rafter_frame_u16(origin, 0), rafter_frame_u16(origin, 1));
    ivec2 offset = ivec2(rafter_frame_u16(origin, 2), rafter_frame_u16(origin, 3));
    ivec2 size = ivec2(rafter_frame_u16(origin, 4), rafter_frame_u16(origin, 5));
    int kind = rafter_frame_u16(origin, 6);
    int layoutIndex = rafter_frame_u16(origin, 7);
    rafterFrame = 1;
    rafterFrameOrigin = origin;
    rafterFrameSize = size;
    // Malformed private data is culled, never allowed to expose its giant logical quad.
    if (any(lessThan(source, ivec2(1))) ||
        any(lessThan(size, ivec2(1))) || any(greaterThan(size, ivec2(248))) ||
        any(greaterThan(offset + size, source)) || kind > 1 ||
        (kind == 0 && (requestedWidth < 1.0 || inset > 4.0)) ||
        (kind == 1 && layoutIndex >= RAFTER_MODAL_LAYOUT_COUNT)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    // Same viewport recovery and Position.z policy as FutureUI's title background.
    vec2 gui = rafter_frame_gui();
    vec2 extent;
    vec2 topLeft;
    if (kind == 0) {
        extent = max(vec2(0.0), gui - vec2(2.0 * inset));
        extent.x = min(requestedWidth, extent.x);
        topLeft = vec2((gui.x - extent.x) * 0.5, inset);
    } else {
        vec4 panelLayout = RAFTER_MODAL_LAYOUTS[layoutIndex];
        // Native controls do not shrink or move their hit boxes on a small viewport.
        extent = panelLayout.xy;
        topLeft = vec2((gui.x - extent.x) * 0.5,
            rafter_modal_body_top(gui, panelLayout.z, RAFTER_MODAL_FOOTER_HEIGHT) + panelLayout.w);
    }
    vec2 imagePoint = vec2(offset) + vec2(corner * size);
    vec2 point = topLeft + imagePoint / vec2(source) * extent;
    gl_Position = ProjMat * ModelViewMat * vec4(point, Position.z, 1.0);
    rafterFrameUV = vec2(corner * size);
}
