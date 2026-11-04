#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec4 position = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * position;
    texCoord0 = UV0;
    vertexColor = Color;
    if (abs(ProjMat[3][3] - 1.0) > 0.0001 || abs(gl_Position.w) < 0.0001) return;
    vec2 dimensions = round(abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1])));
    if (any(lessThanEqual(dimensions, vec2(0.0)))) return;
    vec2 point = position.xy;
    /* ROUTES */
}
