#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D IntermediateSampler;
uniform sampler2D MainColorSampler;
uniform sampler2D WorldOnlySampler;

void main() {
    vec4 interColor = texture(IntermediateSampler, texCoord);
    vec4 mainColor = texture(MainColorSampler, texCoord);
    vec4 worldColor = texture(WorldOnlySampler, texCoord);

    // Compute luminance for world and main
    float lumWorld = dot(worldColor.rgb, vec3(0.299, 0.587, 0.114));
    float lumMain = dot(mainColor.rgb, vec3(0.299, 0.587, 0.114));

    // Continuous Vignette Transmission Factor V:
    // In vanilla Minecraft, the vignette is purely subtractive/multiplicative: V <= 1.0.
    // Using smoothstep transition near pitch black avoids any 8-bit division noise.
    float vRatio = lumMain / max(lumWorld, 0.0001);
    float vClamped = clamp(vRatio, 0.0, 1.0);
    float lumWeight = smoothstep(0.001, 0.01, lumWorld);
    float V = mix(1.0, vClamped, lumWeight);

    // Intermediate world with continuous vignette applied:
    vec3 interVignetted = interColor.rgb * V;

    // Detect HUD / Viewmodel elements:
    // 1. Any channel brightening beyond world color (vignette NEVER brightens)
    float brighten = max(0.0, max(mainColor.r - worldColor.r, max(mainColor.g - worldColor.g, mainColor.b - worldColor.b)));
    float hudBright = smoothstep(0.02, 0.05, brighten);

    // 2. Chromatic divergence from expected scalar vignette darkening
    vec3 expectedVignette = worldColor.rgb * V;
    vec3 diffV = abs(mainColor.rgb - expectedVignette);
    float maxDiffV = max(diffV.r, max(diffV.g, diffV.b));
    float hudDiff = smoothstep(0.03, 0.07, maxDiffV);

    float hudFactor = clamp(max(hudBright, hudDiff), 0.0, 1.0);

    fragColor = vec4(mix(interVignetted, mainColor.rgb, hudFactor), 1.0);
}
