# Title-frame shader baselines (not client-runtime validation)

Only 26.2 / pack format **88.0** and 26.3 / **97.1** receive text overrides.
`DialogFrameShaders.supportsPackFormat(major, minor)` is the exact runtime capability helper;
`supportedPackFormats` contains pairs, not protocol IDs or open-ended version ranges.
For unsupported clients the transport must send the ordinary title, never marker glyphs.
Existing focus shaders and ordinary Native/Canvas paths keep their own profile coverage.

The four baseline files here are byte-for-byte extracts from the local original client JARs:
`26.2-0.19.5/26.2-0.19.5.jar` and `26.3-0.19.5/26.3-0.19.5.jar`,
under `assets/minecraft/shaders/core/`. No client was started.

| File | SHA-256 |
| --- | --- |
| 26_2/text.vsh | 4bae202567d0e3020ab948c24dcd8acffaa23460622dc01f8cbc929301e062e5 |
| 26_2/text.fsh | 3ef2d2825459867537446ad0497e9b29356162c6fdcd138a0a258678f7c1e597 |
| 26_3/text.vsh | c5aab3569c07200a3d24c40f6ed2a5716b6694acd015b81e763398b7ff3ddc1f |
| 26_3/text.fsh | 08a3b585cf52e39b66e2689132b6a996d81446e9cc6ab1eab73bbcca38186d43 |

26.2 uses `#moj_import`, implicit stage locations and GLSL 330. 26.3 uses `#include`,
explicit stage locations, a changed DynamicTransforms layout and the vanilla OIT path.
The writer adds locations 4..7 only in the GUI/non-grayscale variant and leaves each
version's original path intact. The baseline's `#version` remains the first bytes.

Protocol 1 tiles are 256x256 RGBA with a 4px metadata gutter and <=248x248 artwork.
Opaque corner markers `(82,70,corner,255)` encode corner x/y bits. Row 2 stores a
signature, version and six low-R/high-G 16-bit fields: full physical content width/height,
tile source x/y, payload width/height. The original image's content dimensions and density
are authoritative; cell-layout padding is excluded. Every payload RGBA pixel is copied.

Both local FontTexture implementations were inspected with javap: glyph UVs use a 0.01
texel inward bias. Taking floor(UV * atlasSize) finds the correct corner without a vertex
index or probing neighbouring glyphs. Fragment sampling uses texelFetch into the payload;
encoded vertex RGB is not a tint, while source alpha / title alpha / ColorModulator remain.

Depth follows FutureUI 8156d456: bitmap height=4096, ascent=7, all four bounds retained;
vertex projection uses `vec4(point, Position.z, 1.0)` without a depth override. Each square
tile advances 4097; cancel every advance before appending the ordinary title. Center the
large logical bounds using -2048 space before the entire tile sequence and +2048 after
it; FutureUI also centers its marker this way. The net advance remains zero. Only
post-cancelling each glyph leaves its logical rectangle biased to the right, which does
not establish the same sorting relationship with left-hand controls. This is a
native GUI ordering strategy, **not proof of runtime occlusion correctness**. It does not
reposition controls or disable scissoring: the frame must be submitted in the title layer.
No GPU/client validation, including Vulkan, clipping or unusual GUI transforms, is claimed.
