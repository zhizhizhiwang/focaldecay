#version 150

// Two Pi spelled out: driver preprocessors do not reliably provide it.
#define TAU 6.28318530718

uniform sampler2D DiffuseSampler;

uniform float Fade;
// Continuous, monotonically increasing time in SECONDS, supplied by us.
//
// NOT the built-in "Time" uniform. PostChain overwrites that one every frame:
//
//     this.time += partialTicks;
//     while (this.time > 20.0F) { this.time -= 20.0F; }
//     postpass.process(this.time / 20.0F);      // -> Time in [0, 1), wraps every second
//
// Because Time makes a hard jump from just under 1 back to 0 once per second, the
// phase difference at that boundary is 2*PI*f for a term `Time * TAU * f`. That is
// continuous only when f is an integer. In other words, with the built-in Time the
// slowest smooth animation possible is one full cycle per second -- anything slower
// necessarily snaps once a second. Hence TotalTime.
uniform float TotalTime;

in vec2 texCoord;

out vec4 fragColor;

// Cycles per second. Now that the clock never wraps, these may be any value;
// 0.35 and 0.20 give periods of about 2.9s and 5.0s.
#define DRIFT_CYCLES_PER_SECOND 0.35
#define GRAIN_CYCLES_PER_SECOND 0.20

void main(){
    vec2 uv = texCoord;

    // Temporal distortion: a slow, very low amplitude drift.
    //
    //  * Spatial frequency is LOW (13.0, plus a slower 5.0 component): under two
    //    periods down the screen. The original used 60.0 (~9.5 periods), where
    //    neighbouring pixels landed on opposite phases, so any movement made the
    //    whole screen shimmer; 18.0 still read as "repeating ripples".
    //
    //  * Amplitude is deeply sub-pixel (~0.85px at 1920 wide, combined). The effect
    //    should make the image feel unsettled, never actually displaced: if you can
    //    watch a wave travel, it is too strong.
    float wave = sin(uv.y * 13.0 + TotalTime * TAU * DRIFT_CYCLES_PER_SECOND) * 0.0007;
    wave += sin(uv.y * 5.0 - TotalTime * TAU * GRAIN_CYCLES_PER_SECOND) * 0.0003;
    uv.x += wave * Fade;

    // Chromatic aberration: slight per-channel offset
    vec2 spread = vec2(0.0007 * Fade, 0.0);
    vec3 color;
    color.r = texture(DiffuseSampler, uv + spread).r;
    color.g = texture(DiffuseSampler, uv).g;
    color.b = texture(DiffuseSampler, uv - spread).b;

    // Very slight tint, keep the frame readable
    color = mix(color, vec3(0.24, 0.34, 0.48), Fade * 0.08);
    fragColor = vec4(color, 1.0);
}
