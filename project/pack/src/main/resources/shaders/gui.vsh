@HEADER@
@TRANSFORMS@
layout(std140) uniform Projection {
    mat4 ProjMat;
};
@INPUTS@
@OUTPUTS@

void main() {
    vec4 position = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * position;
    vertexColor = Color;
@CORNER_INDEX@
    // gl_VertexID/Index has a draw-dependent phase in mixed vertex buffers.
    // The fragment stage solves the affine mapping; it must not assume axis order.
    rafterQuad = vec2(corner >= 2 ? 1.0 : -1.0,
        corner == 1 || corner == 2 ? 1.0 : -1.0);
    rafterGuiPosition = position.xy;
    rafterViewport = abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1]));
}
