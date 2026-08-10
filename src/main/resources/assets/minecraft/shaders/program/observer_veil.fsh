#version 150

uniform sampler2D DiffuseSampler;

uniform float Fade;
uniform float Time;

in vec2 texCoord;

out vec4 fragColor;

void main(){
    vec2 uv = texCoord;

    // Subtle temporal distortion (horizontal wave)
    float wave = sin(uv.y * 60.0 + Time * 3.0) * 0.0012 * Fade;
    uv.x += wave;

    // Chromatic aberration: slight per-channel offset
    vec2 spread = vec2(0.0010 * Fade, 0.0);
    vec3 color;
    color.r = texture(DiffuseSampler, uv + spread).r;
    color.g = texture(DiffuseSampler, uv).g;
    color.b = texture(DiffuseSampler, uv - spread).b;

    // Very slight tint, keep the frame readable
    color = mix(color, vec3(0.24, 0.34, 0.48), Fade * 0.10);
    fragColor = vec4(color, 1.0);
}
