// Voxy default translucent fragment patch.
// Same as opaque: writes raw albedo * tint to colortex0. Blending state is
// left at Iris defaults so translucent LODs composite like vanilla terrain.

layout(location = 0) out vec4 voxyColortex0;

void voxy_emitFragment(VoxyFragmentParameters params) {
    vec4 color = params.sampledColour * params.tinting;
    voxyColortex0 = color;
}
