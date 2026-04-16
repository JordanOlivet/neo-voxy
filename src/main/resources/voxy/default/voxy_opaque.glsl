// Voxy default opaque fragment patch.
// Writes raw albedo * tint to colortex0 only. Shader pack composite stages
// apply lighting/shadows/fog using depth + colortex0, like for Sodium terrain.
// Packs that use deferred PBR (Complementary, Photon, ...) need normals/specular
// in additional gbuffers and should ship their own voxy.json + GLSL patches —
// writing extra colortex from here is unsafe because pack-specific colortex
// formats/sizes can mismatch and break the framebuffer.

layout(location = 0) out vec4 voxyColortex0;

void voxy_emitFragment(VoxyFragmentParameters params) {
    vec4 color = params.sampledColour * params.tinting;
    voxyColortex0 = color;
}
