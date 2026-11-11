#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D IntermediateSampler;
uniform sampler2D MainColorSampler;
uniform sampler2D WorldOnlySampler;
uniform sampler2D CurrPostHandSampler;
uniform sampler2D PrevPostHandSampler;
uniform sampler2D PrevWorldOnlySampler;
uniform vec2 HudScreenSize;
uniform int uHandInterpEnabled;
uniform float HudBlendFactor;

// Phase 3: Extracts the 3D First-Person Hand / Held Item layer (RGB + exact silhouette mask in A)
// by comparing the post-render3dHud buffer against the pre-render3dHud WorldOnly buffer.
vec4 sampleHandLayer(sampler2D postHandTex, sampler2D worldOnlyTex, vec2 uv) {
    vec3 ph = texture(postHandTex, uv).rgb;
    vec3 wo = texture(worldOnlyTex, uv).rgb;
    vec3 diff = abs(ph - wo);
    float maxDiff = max(diff.r, max(diff.g, diff.b));
    float mask = smoothstep(0.012, 0.035, maxDiff);
    return vec4(ph, mask);
}

// Phase 3 & 4: Synthesizes the T = alpha midpoint 3D First-Person Hand / Viewmodel pose
// between Frame N-1 (PrevPostHandSampler) and Frame N (CurrPostHandSampler) using symmetric
// midpoint trajectory matching over the unoccluded T = 0.5 world background.
vec4 synthesizeMidpointHand(vec2 uv, vec2 texelSize, float alpha) {
    vec4 hCurr0 = sampleHandLayer(CurrPostHandSampler, WorldOnlySampler, uv);
    vec4 hPrev0 = sampleHandLayer(PrevPostHandSampler, PrevWorldOnlySampler, uv);

    // Fast path for stationary hand interior: both frames have hand geometry at uv with matching color
    if (hCurr0.a > 0.35 && hPrev0.a > 0.35) {
        vec3 cDiff0 = abs(hCurr0.rgb - hPrev0.rgb);
        float maxCDiff0 = max(cDiff0.r, max(cDiff0.g, cDiff0.b)) + abs(hCurr0.a - hPrev0.a);
        if (maxCDiff0 <= 0.025) {
            return vec4(mix(hCurr0.rgb, hPrev0.rgb, alpha), max(hCurr0.a, hPrev0.a));
        }
    }

    vec2 bestVel = vec2(0.0);
    float bestScore = 10.0;
    bool foundSymmetricHand = false;

    // 9-tap viewmodel swing & bobbing search across 0px, 3px, and 6px radii.
    // On background terrain/sky pixels where CurrPostHand has no hand (hN.a <= 0.35),
    // the loop skips sampling PrevPostHand/PrevWorldOnly to preserve mobile GPU memory bandwidth.
    vec2 searchOffsets[9] = vec2[9](
        vec2( 0.0,  0.0),
        vec2( 3.0,  0.0),
        vec2(-3.0,  0.0),
        vec2( 0.0,  3.0),
        vec2( 0.0, -3.0),
        vec2( 6.0,  0.0),
        vec2(-6.0,  0.0),
        vec2( 0.0,  6.0),
        vec2( 0.0, -6.0)
    );

    for (int i = 0; i < 9; ++i) {
        vec2 candVel = searchOffsets[i] * texelSize;
        vec2 uvN = clamp(uv + alpha * candVel, vec2(0.001), vec2(0.999));
        vec4 hN = (i == 0) ? hCurr0 : sampleHandLayer(CurrPostHandSampler, WorldOnlySampler, uvN);
        if (hN.a <= 0.35) {
            continue;
        }

        vec2 uvP = clamp(uv - (1.0 - alpha) * candVel, vec2(0.001), vec2(0.999));
        vec4 hP = (i == 0) ? hPrev0 : sampleHandLayer(PrevPostHandSampler, PrevWorldOnlySampler, uvP);
        if (hP.a > 0.35) {
            vec3 rgbDiff = abs(hN.rgb - hP.rgb);
            float cost = (rgbDiff.r + rgbDiff.g + rgbDiff.b) * 0.333333 + 0.004 * length(searchOffsets[i]);
            if (cost < bestScore) {
                bestScore = cost;
                bestVel = candVel;
                foundSymmetricHand = true;
            }
        }
    }

    if (foundSymmetricHand && bestScore < 0.22) {
        vec2 winUvN = clamp(uv + alpha * bestVel, vec2(0.001), vec2(0.999));
        vec2 winUvP = clamp(uv - (1.0 - alpha) * bestVel, vec2(0.001), vec2(0.999));
        vec4 winN = sampleHandLayer(CurrPostHandSampler, WorldOnlySampler, winUvN);
        vec4 winP = sampleHandLayer(PrevPostHandSampler, PrevWorldOnlySampler, winUvP);
        return vec4(mix(winN.rgb, winP.rgb, alpha), clamp(0.5 * (winN.a + winP.a), 0.0, 1.0));
    }

    // No symmetric midpoint hand match at uv (either pure background or vacated Frame N / Frame N-1 hand endpoint):
    // Return mask = 0.0 so the unoccluded T=0.5 world (IntermediateSampler) shows through without ghosting.
    return vec4(hCurr0.rgb, 0.0);
}

void main() {
    vec4 interColor = texture(IntermediateSampler, texCoord);
    vec4 mainColor = texture(MainColorSampler, texCoord);
    vec4 worldColor = texture(WorldOnlySampler, texCoord);

    if (uHandInterpEnabled != 0) {
        // 3-Layer Pipeline: (1) T=0.5 World, (2) T=0.5 First-Person Hand, (3) Static 2D GUI + Vignette
        vec4 postHandCurr = texture(CurrPostHandSampler, texCoord);

        float lumPostHand = dot(postHandCurr.rgb, vec3(0.299, 0.587, 0.114));
        float lumMain = dot(mainColor.rgb, vec3(0.299, 0.587, 0.114));

        float vRatio = lumMain / max(lumPostHand, 0.0001);
        float vClamped = clamp(vRatio, 0.0, 1.0);
        float lumWeight = smoothstep(0.001, 0.01, lumPostHand);
        float V = mix(1.0, vClamped, lumWeight);

        // Isolate 2D GUI elements (crosshair, hotbar, hearts, chat, F3 text) drawn after render3dHud
        float brightenGui = max(0.0, max(mainColor.r - postHandCurr.r, max(mainColor.g - postHandCurr.g, mainColor.b - postHandCurr.b)));
        float guiBright = smoothstep(0.02, 0.05, brightenGui);

        vec3 expectedPostHandVignette = postHandCurr.rgb * V;
        vec3 diffGui = abs(mainColor.rgb - expectedPostHandVignette);
        float maxDiffGui = max(diffGui.r, max(diffGui.g, diffGui.b));
        float guiDiff = smoothstep(0.03, 0.07, maxDiffGui);
        float guiFactor = clamp(max(guiBright, guiDiff), 0.0, 1.0);

        // Synthesize T=0.5 First-Person Hand / Held Item layer
        vec2 texelSize = 1.0 / max(HudScreenSize, vec2(1.0));
        float alpha = clamp(HudBlendFactor, 0.0, 1.0);
        vec4 midHand = synthesizeMidpointHand(texCoord, texelSize, alpha);

        vec3 interWorldVignetted = interColor.rgb * V;
        vec3 midHandVignetted = midHand.rgb * V;
        vec3 worldPlusHand = mix(interWorldVignetted, midHandVignetted, midHand.a);

        fragColor = vec4(mix(worldPlusHand, mainColor.rgb, guiFactor), 1.0);
        return;
    }

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
