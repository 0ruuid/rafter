// Generated from compiled Canvas bodies and Native text/item bodies only.
// Input controls/buttons use their unchanged textured Sprite pipeline.
// Geometric opt-in, not a menu ID: identical geometry in other UI can still match.
@LAYOUTS@
@NATIVE_LAYOUTS@
const float RAFTER_NATIVE_LINE_HEIGHT = @NATIVE_LINE_HEIGHT@;
const float RAFTER_NATIVE_TEXT_PADDING = @NATIVE_TEXT_PADDING@;
const float RAFTER_HEADER_HEIGHT = @HEADER_HEIGHT@;
const float RAFTER_PREFERRED_TOP = @PREFERRED_TOP@;

bool rafterScrollingEdge(float pixel, float restingEdge, float travel) {
    return pixel >= restingEdge - travel - 0.6 && pixel <= restingEdge + 0.6;
}

bool rafterDialogFocus(vec4 color) {
    if (any(lessThan(color, vec4(254.5 / 255.0)))) return false;
    // A shared mixed-format vertex buffer can start a draw at any corner phase.
    // Recover the full affine mapping: component-wise fwidth ratios swap axes
    // at phases 1/3. GPU scissoring changes coverage, not this interpolation.
    vec2 qx = dFdx(rafterQuad), qy = dFdy(rafterQuad);
    vec2 px = dFdx(rafterGuiPosition), py = dFdy(rafterGuiPosition);
    float determinant = qx.x * qy.y - qx.y * qy.x;
    if (abs(determinant) < 1.0e-10) return false;
    vec2 u = (px * qy.y - py * qx.y) / determinant;
    vec2 v = (py * qx.x - px * qy.x) / determinant;
    vec2 halfExtent = abs(u) + abs(v);
    vec2 center = rafterGuiPosition - u * rafterQuad.x - v * rafterQuad.y;
    vec2 lower = center - halfExtent, upper = center + halfExtent;
    vec2 dimensions = 2.0 * halfExtent;
    bool horizontalStrip = dimensions.y > 0.0 && dimensions.y <= 1.15;
    bool verticalStrip = dimensions.x > 0.0 && dimensions.x <= 1.15;
    if (!horizontalStrip && !verticalStrip) return false;
    vec2 viewport = ceil(rafterViewport - vec2(0.001));
    for (int i = 0; i < RAFTER_FOCUS_LAYOUT_COUNT; ++i) {
        vec4 metrics = RAFTER_FOCUS_LAYOUTS[i];
        float width = metrics.x, height = metrics.y;
        float footer = metrics.z, tail = metrics.w;
        if (viewport.x <= 0.0 || viewport.y <= RAFTER_HEADER_HEIGHT + footer) continue;
        float visibleHeight = max(0.0, viewport.y - RAFTER_HEADER_HEIGHT - footer);
        float layoutHeight = height + tail;
        float top = min(RAFTER_PREFERRED_TOP,
            viewport.y - footer - min(layoutHeight, visibleHeight));
        float travel = max(0.0, layoutHeight - visibleHeight);
        // Symmetric scrollbar reserve preserves centering. Native integer
        // truncation can choose either neighbor when a centered parent overflows.
        float idealLeft = (viewport.x - width) * 0.5;
        float leftFloor = floor(idealLeft), leftCeil = ceil(idealLeft);
        if (rafterGuiPosition.y < RAFTER_HEADER_HEIGHT - 0.6
            || rafterGuiPosition.y > viewport.y - footer + 0.6) continue;
        bool fullHorizontal = abs(dimensions.x - width) < 0.15
            && (abs(lower.x - leftFloor) < 0.15 || abs(lower.x - leftCeil) < 0.15);
        // Also accept a backend's explicit CPU crop at the screen's x boundary;
        // do not treat arbitrary short interior lines as full body borders.
        bool viewportHorizontal = width > viewport.x && abs(lower.x) < 0.15
            && abs(upper.x - viewport.x) < 0.15;
        bool horizontal = horizontalStrip && (fullHorizontal || viewportHorizontal)
            && (rafterScrollingEdge(rafterGuiPosition.y, top + 0.5, travel)
                || rafterScrollingEdge(rafterGuiPosition.y, top + height - 0.5, travel));
        bool sideBand = (lower.x >= leftFloor - 0.15 && upper.x <= leftCeil + 1.15)
            || (lower.x >= leftFloor + width - 1.15 && upper.x <= leftCeil + width + 0.15);
        bool vertical = verticalStrip && dimensions.y <= height - 1.85 && sideBand
            && rafterGuiPosition.y >= top - travel - 0.6
            && rafterGuiPosition.y <= top + height + 0.6;
        if (horizontal || vertical) return true;
    }
    for (int i = 0; i < RAFTER_NATIVE_FOCUS_LAYOUT_COUNT; ++i) {
        vec4 metrics = RAFTER_NATIVE_FOCUS_LAYOUTS[i];
        float width = metrics.x;
        // One-pixel native item carriers have only horizontal outline strips.
        if (metrics.w > 1.5) {
            if (viewport.y <= RAFTER_HEADER_HEIGHT + 5.0 ||
                rafterGuiPosition.y < RAFTER_HEADER_HEIGHT - 0.6 ||
                rafterGuiPosition.y > viewport.y - 5.0 + 0.6) continue;
            float span = round(dimensions.x);
            float left = floor((viewport.x - span) * 0.5);
            if (horizontalStrip && span >= 1.0 && span <= width &&
                abs(mod(span, 2.0) - 1.0) < 0.1 &&
                abs(lower.x - left) <= 1.15) return true;
            continue;
        }
        if (viewport.y <= RAFTER_HEADER_HEIGHT + 5.0
            || rafterGuiPosition.y < RAFTER_HEADER_HEIGHT - 0.6
            || rafterGuiPosition.y > viewport.y - 5.0 + 0.6) continue;
        float idealLeft = (viewport.x - width + metrics.z) * 0.5;
        // A Native item row is centered inside the body column. Two nested
        // integer center operations can place a fitting child one pixel earlier.
        float leftFloor = floor(idealLeft), leftCeil = ceil(idealLeft);
        bool fullHorizontal = abs(dimensions.x - width) < 0.15
            && (abs(lower.x - leftFloor + 1.0) < 0.15
                || abs(lower.x - leftFloor) < 0.15 || abs(lower.x - leftCeil) < 0.15);
        bool screenCrop = width > viewport.x && abs(lower.x) < 0.15
            && abs(upper.x - viewport.x) < 0.15;
        bool horizontal = horizontalStrip && (fullHorizontal || screenCrop);
        // Text Widget: total height = 9*n + 8, side strips = 9*n + 6.
        // This deliberately does NOT match the 9px caret or 20/48px input area.
        float rows = (dimensions.y + 2.0 - RAFTER_NATIVE_TEXT_PADDING) / RAFTER_NATIVE_LINE_HEIGHT;
        bool textSide = rows >= -0.001
            && abs(rows - round(rows)) * RAFTER_NATIVE_LINE_HEIGHT < 0.15;
        bool itemSide = abs(dimensions.y - (metrics.y - 2.0)) < 0.15;
        bool sideBand = (lower.x >= leftFloor - 1.15 && upper.x <= leftCeil + 1.15)
            || (lower.x >= leftFloor + width - 2.15 && upper.x <= leftCeil + width + 0.15);
        bool vertical = verticalStrip && sideBand
            && (metrics.w < 0.5 ? textSide : itemSide);
        if (horizontal || vertical) return true;
    }
    return false;
}
