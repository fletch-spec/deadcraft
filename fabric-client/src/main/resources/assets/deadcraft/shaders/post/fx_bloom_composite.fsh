#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom: the blurred light, rolled off into 0..1 keeping the hue (as fx.fsh), added (one, one) over the frame.

uniform sampler2D InSampler;

layout(std140) uniform BloomConfig {
    vec4 Config;  // x: strength
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 rollOff(vec3 c) {
    const float knee = 0.6;
    float m = max(c.r, max(c.g, c.b));
    if (m <= knee) return c;
    float rolled = knee + (1.0 - knee) * (1.0 - exp(-(m - knee) / (1.0 - knee)));
    return c * (rolled / m);
}

void main() {
    vec3 lin = texture(InSampler, texCoord).rgb * Config.x;
    fragColor = vec4(pow(clamp(rollOff(lin), 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
