#version 330
#extension GL_ARB_separate_shader_objects : require

// Deadcraft's effect cards (fabric-client/.../fx/Effects.java): position, texture coordinate, colour (linear,
// alpha in a), and the card's brightness in the normal's x (0..1 for 0..32, Deadlock's overbright and
// self-illumination, applied per pixel in fx.fsh).

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in vec3 Normal;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out float brightness;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
    brightness = max(Normal.x, 0.0) * 32.0;
}
