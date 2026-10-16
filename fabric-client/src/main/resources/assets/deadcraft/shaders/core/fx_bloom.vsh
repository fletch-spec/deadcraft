#version 330
#extension GL_ARB_separate_shader_objects : require

// Deadcraft's bloom source (fx/Bloom.java): the glowing effect cards again, positions relative to the camera,
// into a float layer that is blurred and added back over the frame.

layout(std140) uniform BloomView {
    mat4 ViewProj;
    vec4 Params;  // x: brightness setting
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in float Brightness;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out float brightness;

void main() {
    gl_Position = ViewProj * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
    brightness = Brightness * Params.x;
}
