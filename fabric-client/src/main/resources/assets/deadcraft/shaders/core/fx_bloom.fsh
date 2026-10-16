#version 330
#extension GL_ARB_separate_shader_objects : require

// The card's light as fx.fsh computes it (linear: texture x colour x brightness x alpha), before the roll-off:
// what Deadlock's bloom spreads. Additive cards give all their light; blended ones (negative brightness) only
// what is brighter than white, so a white-hot spot glows and smoke doesn't. Very bright cores are softened so
// they don't flare the whole halo.

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float brightness;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    float alpha = tex.a * vertexColor.a;
    if (alpha <= 0.002) discard;
    vec3 lin = pow(max(tex.rgb, vec3(0.0)), vec3(2.2)) * vertexColor.rgb * abs(brightness);
    lin = (brightness < 0.0 ? max(lin - vec3(1.0), vec3(0.0)) : lin) * alpha;
    float m = max(lin.r, max(lin.g, lin.b));
    fragColor = vec4(lin / (1.0 + m / 8.0), 1.0);
}
