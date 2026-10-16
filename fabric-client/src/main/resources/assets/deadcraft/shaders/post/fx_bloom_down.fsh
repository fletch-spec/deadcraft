#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom: halves the layer with a 13-tap filter (Jimenez, "Next generation post processing in Call of Duty").

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 at(vec2 offset) {
    return texture(InSampler, texCoord + offset / vec2(textureSize(InSampler, 0))).rgb;
}

void main() {
    vec3 c = at(vec2(0.0)) * 0.125;
    c += (at(vec2(-2.0, 2.0)) + at(vec2(2.0, 2.0)) + at(vec2(-2.0, -2.0)) + at(vec2(2.0, -2.0))) * 0.03125;
    c += (at(vec2(0.0, 2.0)) + at(vec2(-2.0, 0.0)) + at(vec2(2.0, 0.0)) + at(vec2(0.0, -2.0))) * 0.0625;
    c += (at(vec2(-1.0, 1.0)) + at(vec2(1.0, 1.0)) + at(vec2(-1.0, -1.0)) + at(vec2(1.0, -1.0))) * 0.125;
    fragColor = vec4(c, 1.0);
}
