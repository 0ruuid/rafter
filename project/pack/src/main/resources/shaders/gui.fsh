@HEADER@
@TRANSFORMS@
@INPUTS@
@OUTPUTS@
#moj_import <rafter:dialog_focus.glsl>

void main() {
    vec4 color = vertexColor * ColorModulator;
    if (color.a == 0.0) discard;
    if (rafterDialogFocus(color)) discard;
    fragColor = color;
}
