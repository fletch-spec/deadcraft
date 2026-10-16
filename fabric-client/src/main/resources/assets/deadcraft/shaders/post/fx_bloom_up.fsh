#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom: a 3x3 tent of the smaller layer, added (one, one) onto the next larger one.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 at(vec2 offset) {
    return texture(InSampler, texCoord + offset / vec2(textureSize(InSampler, 0))).rgb;
}

void main() {
    vec3 c = at(vec2(0.0)) * 4.0;
    c += (at(vec2(0.0, 1.0)) + at(vec2(-1.0, 0.0)) + at(vec2(1.0, 0.0)) + at(vec2(0.0, -1.0))) * 2.0;
    c += at(vec2(-1.0, 1.0)) + at(vec2(1.0, 1.0)) + at(vec2(-1.0, -1.0)) + at(vec2(1.0, -1.0));
    fragColor = vec4(c / 16.0, 1.0);
}
