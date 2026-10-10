#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D CurrentColorSampler;
uniform sampler2D PreviousColorSampler;
uniform sampler2D CurrentDepthSampler;
uniform sampler2D PreviousDepthSampler;
uniform sampler2D EntityMaskSampler;

uniform mat4 MidToPrevMatrix;
uniform mat4 MidToCurrMatrix;
uniform mat4 MidToPrevSkyMatrix;
uniform mat4 MidToCurrSkyMatrix;
uniform vec2 ScreenSize;
uniform float BlendFactor;
uniform float DepthDilationRadius;
uniform int uDebugLadderMode;

void main() {
    // 1. Initial depth seeds from both frames at current midpoint screen coordinate
    float dN = texture(CurrentDepthSampler, texCoord).r;
    float dP = texture(PreviousDepthSampler, texCoord).r;

    // Iterative refinement pass 1 & 2 for Frame N candidate
    // In Blaze3D with glClipControl(GL_ZERO_TO_ONE), zNDC == depth in [0, 1]
    for (int i = 0; i < 2; ++i) {
        float zN = dN;
        vec4 pN = vec4(texCoord * 2.0 - 1.0, zN, 1.0);
        vec4 cN = MidToCurrMatrix * pN;
        if (cN.w > 0.0) {
            vec2 uvN = (cN.xy / max(cN.w, 0.00001)) * 0.5 + 0.5;
            if (uvN.x >= 0.0 && uvN.x <= 1.0 && uvN.y >= 0.0 && uvN.y <= 1.0) {
                dN = texture(CurrentDepthSampler, uvN).r;
            }
        }
    }

    // Iterative refinement pass 1 & 2 for Frame N-1 candidate
    for (int i = 0; i < 2; ++i) {
        float zP = dP;
        vec4 pP = vec4(texCoord * 2.0 - 1.0, zP, 1.0);
        vec4 cP = MidToPrevMatrix * pP;
        if (cP.w > 0.0) {
            vec2 uvP = (cP.xy / max(cP.w, 0.00001)) * 0.5 + 0.5;
            if (uvP.x >= 0.0 && uvP.x <= 1.0 && uvP.y >= 0.0 && uvP.y <= 1.0) {
                dP = texture(PreviousDepthSampler, uvP).r;
            }
        }
    }

    // Arbitrate Single Midpoint Surface:
    // In Reverse-Z, larger depth is nearer surface: dMid = max(dN, dP)
    float dMid = max(dN, dP);

    // Sky Decision: Sky (dMid <= 0.0001) is evaluated strictly after refinement
    // In GL_ZERO_TO_ONE Reverse-Z, far sky plane is z = 0.0
    if (dMid <= 0.0001) {
        // Pure rotation sky warping at far plane z = 0.0
        vec4 skyPoint = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
        vec4 cSkyCurr = MidToCurrSkyMatrix * skyPoint;
        vec4 cSkyPrev = MidToPrevSkyMatrix * skyPoint;

        vec2 uvSkyCurr = (cSkyCurr.xy / max(cSkyCurr.w, 0.00001)) * 0.5 + 0.5;
        vec2 uvSkyPrev = (cSkyPrev.xy / max(cSkyPrev.w, 0.00001)) * 0.5 + 0.5;

        bool skyCurrValid = (uvSkyCurr.x >= 0.0 && uvSkyCurr.x <= 1.0 && uvSkyCurr.y >= 0.0 && uvSkyCurr.y <= 1.0 && cSkyCurr.w > 0.0);
        bool skyPrevValid = (uvSkyPrev.x >= 0.0 && uvSkyPrev.x <= 1.0 && uvSkyPrev.y >= 0.0 && uvSkyPrev.y <= 1.0 && cSkyPrev.w > 0.0);

        if (skyCurrValid && skyPrevValid) {
            vec4 colCurr = texture(CurrentColorSampler, uvSkyCurr);
            vec4 colPrev = texture(PreviousColorSampler, uvSkyPrev);
            fragColor = vec4(mix(colCurr.rgb, colPrev.rgb, BlendFactor), 1.0);
        } else if (skyCurrValid) {
            fragColor = vec4(texture(CurrentColorSampler, uvSkyCurr).rgb, 1.0);
            if (uDebugLadderMode == 1) fragColor = mix(fragColor, vec4(0.0, 1.0, 0.0, 1.0), 0.7);
        } else if (skyPrevValid) {
            fragColor = vec4(texture(PreviousColorSampler, uvSkyPrev).rgb, 1.0);
            if (uDebugLadderMode == 1) fragColor = mix(fragColor, vec4(0.0, 0.0, 1.0, 1.0), 0.7);
        } else {
            fragColor = vec4(texture(CurrentColorSampler, texCoord).rgb, 1.0);
            if (uDebugLadderMode == 1) fragColor = mix(fragColor, vec4(1.0, 0.0, 0.0, 1.0), 0.7);
        }
        return;
    }

    // 2. Validate BOTH Sources against single mid-surface point P_mid
    // In GL_ZERO_TO_ONE, zNDC == dMid
    float zNDC = dMid;
    vec4 pMid = vec4(texCoord * 2.0 - 1.0, zNDC, 1.0);

    // Reproject P_mid into Frame N (Current)
    vec4 clipN = MidToCurrMatrix * pMid;
    vec3 ndcN = clipN.xyz / max(clipN.w, 0.00001);
    vec2 uvN = ndcN.xy * 0.5 + 0.5;
    float expectedDepthN = ndcN.z;

    // Reproject P_mid into Frame N-1 (Previous)
    vec4 clipP = MidToPrevMatrix * pMid;
    vec3 ndcP = clipP.xyz / max(clipP.w, 0.00001);
    vec2 uvP = ndcP.xy * 0.5 + 0.5;
    float expectedDepthP = ndcP.z;

    bool uvInBoundsN = (uvN.x >= 0.0 && uvN.x <= 1.0 && uvN.y >= 0.0 && uvN.y <= 1.0 && clipN.w > 0.0);
    bool uvInBoundsP = (uvP.x >= 0.0 && uvP.x <= 1.0 && uvP.y >= 0.0 && uvP.y <= 1.0 && clipP.w > 0.0);

    float tauN = max(0.05 * max(expectedDepthN, 0.001), 0.005);
    float tauP = max(0.05 * max(expectedDepthP, 0.001), 0.005);

    bool validN = false;
    if (uvInBoundsN) {
        float actualDepthN = texture(CurrentDepthSampler, uvN).r;
        validN = abs(actualDepthN - expectedDepthN) <= tauN;
    }

    bool validP = false;
    if (uvInBoundsP) {
        float actualDepthP = texture(PreviousDepthSampler, uvP).r;
        validP = abs(actualDepthP - expectedDepthP) <= tauP;
    }

    // 3. Synthesis Ladder
    if (validN && validP) {
        // Case A (Both Valid): Untinted, smooth divergence ramp
        vec4 currColor = texture(CurrentColorSampler, uvN);
        vec4 prevColor = texture(PreviousColorSampler, uvP);

        vec3 diff = abs(currColor.rgb - prevColor.rgb);
        float deltaC = max(diff.r, max(diff.g, diff.b));
        float ramp = smoothstep(0.05, 0.25, deltaC);

        float wBase = BlendFactor;
        float wPrev = wBase * (1.0 - ramp);
        float wCurr = 1.0 - wPrev;

        vec3 synth = wCurr * currColor.rgb + wPrev * prevColor.rgb;
        fragColor = vec4(synth, 1.0);
    } else if (validN) {
        // Case B (Only N Valid): Newly appearing terrain
        vec4 currColor = texture(CurrentColorSampler, uvN);
        if (uDebugLadderMode == 1) {
            fragColor = vec4(mix(currColor.rgb, vec3(0.0, 1.0, 0.0), 0.7), 1.0); // Tint Green
        } else {
            fragColor = vec4(currColor.rgb, 1.0);
        }
    } else if (validP) {
        // Case C (Only N-1 Valid): Terrain rotating out
        vec4 prevColor = texture(PreviousColorSampler, uvP);
        if (uDebugLadderMode == 1) {
            fragColor = vec4(mix(prevColor.rgb, vec3(0.0, 0.0, 1.0), 0.7), 1.0); // Tint Blue
        } else {
            fragColor = vec4(prevColor.rgb, 1.0);
        }
    } else {
        // Case D (Neither Valid): Fallback to current frame center
        vec4 fallbackColor = texture(CurrentColorSampler, texCoord);
        if (uDebugLadderMode == 1) {
            fragColor = vec4(mix(fallbackColor.rgb, vec3(1.0, 0.0, 0.0), 0.7), 1.0); // Tint Red
        } else {
            fragColor = vec4(fallbackColor.rgb, 1.0);
        }
    }
}
