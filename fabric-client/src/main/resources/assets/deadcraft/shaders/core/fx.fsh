#version 330
#extension GL_ARB_separate_shader_objects : require

// Deadlock's spritecard colour in linear light: texture x colour x brightness (overbright, self-illumination,
// add-self), times alpha; then rolled off into 0..1 keeping the hue (Minecraft has no HDR or bloom) and
// written as sRGB. Additive cards are blended (one, one) by their pipeline; blended ones by their alpha.

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float brightness;

layout(location = 0) out vec4 fragColor;

vec3 toLinear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 toSrgb(vec3 c) {
    return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
}

vec3 rollOff(vec3 c) {
    const float knee = 0.6;
    float m = max(c.r, max(c.g, c.b));
    if (m <= knee) return c;
    float rolled = knee + (1.0 - knee) * (1.0 - exp(-(m - knee) / (1.0 - knee)));
    vec3 scaled = c * (rolled / m);
    // Very bright colours whiten a little, as Deadlock's tone mapping and bloom do.
    float white = clamp((m - 1.0) / (m + 4.0), 0.0, 1.0) * 0.25;
    return mix(scaled, vec3(rolled), white);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    float alpha = tex.a * vertexColor.a;
    if (alpha <= 0.002) discard;
    vec3 lin = toLinear(tex.rgb) * vertexColor.rgb * brightness;
#ifdef ADDITIVE
    fragColor = vec4(toSrgb(rollOff(lin * alpha)), 1.0);
#else
    fragColor = vec4(toSrgb(rollOff(lin)), alpha);
#endif
}
