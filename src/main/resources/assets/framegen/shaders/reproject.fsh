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
uniform int uOpticalFlowEnabled;
uniform int uOpticalFlowQuality;
uniform float uOpticalFlowRadius;
uniform float uEntityVelMaxUV;

// Phase 1: Reverse-Z Foreground Depth Dilation (5-tap cross kernel for high GPU cache efficiency)
// In Blaze3D glClipControl(GL_ZERO_TO_ONE) Reverse-Z, 1.0 = near plane, 0.0 = far sky plane.
// Taking the maximum depth locks onto the foreground silhouette edge without heavy 9-tap bandwidth stalls.
float sampleDilatedDepth(sampler2D depthTex, vec2 uv, vec2 dilationStep) {
    float centerDepth = texture(depthTex, uv).r;
    if (DepthDilationRadius <= 0.001) {
        return centerDepth;
    }
    float dN = texture(depthTex, clamp(uv + vec2(0.0, dilationStep.y), vec2(0.0005), vec2(0.9995))).r;
    float dS = texture(depthTex, clamp(uv - vec2(0.0, dilationStep.y), vec2(0.0005), vec2(0.9995))).r;
    float dE = texture(depthTex, clamp(uv + vec2(dilationStep.x, 0.0), vec2(0.0005), vec2(0.9995))).r;
    float dW = texture(depthTex, clamp(uv - vec2(dilationStep.x, 0.0), vec2(0.0005), vec2(0.9995))).r;
    return max(centerDepth, max(max(dN, dS), max(dE, dW)));
}

// Phase 2: Depth-Occluded Entity Velocity Mask Lookup & Decoding
// Compares the entity's Reverse-Z depth (encoded in bestMask.b) against the visible scene surface depth (pixelDepth).
// Rejects underground or behind-wall entities (where pixelDepth > entityDepth + tolerance) so occluded mobs never warp foreground trees or sky.
vec3 sampleEntityVelocity(vec2 uv, vec2 texelSize, float pixelDepth) {
    // Sky pixels (pixelDepth <= 0.0001) have no rendered entity geometry
    if (pixelDepth <= 0.0001) {
        return vec3(0.0);
    }

    vec4 bestMask = texture(EntityMaskSampler, uv);
    if (bestMask.a <= 0.01) {
        vec2 step2 = texelSize * 2.0;
        vec4 mN = texture(EntityMaskSampler, clamp(uv + vec2(0.0, step2.y), vec2(0.0005), vec2(0.9995)));
        vec4 mS = texture(EntityMaskSampler, clamp(uv - vec2(0.0, step2.y), vec2(0.0005), vec2(0.9995)));
        vec4 mE = texture(EntityMaskSampler, clamp(uv + vec2(step2.x, 0.0), vec2(0.0005), vec2(0.9995)));
        vec4 mW = texture(EntityMaskSampler, clamp(uv - vec2(step2.x, 0.0), vec2(0.0005), vec2(0.9995)));
        if (mN.a > bestMask.a) bestMask = mN;
        if (mS.a > bestMask.a) bestMask = mS;
        if (mE.a > bestMask.a) bestMask = mE;
        if (mW.a > bestMask.a) bestMask = mW;
    }

    if (bestMask.a <= 0.01) {
        return vec3(0.0);
    }

    // Reverse-Z occlusion culling (1.0 = near, 0.0 = far):
    // If the visible scene pixel is nearer than the entity's bounding-box depth, the entity is occluded behind terrain/trees.
    float entityDepth = bestMask.b;
    float depthTolerance = max(0.08 * max(entityDepth, 0.005), 0.008);
    if (pixelDepth > entityDepth + depthTolerance) {
        return vec3(0.0);
    }

    float maxVel = max(uEntityVelMaxUV, 0.001);
    vec2 decodedVel = (bestMask.rg - vec2(0.5)) * (2.0 * maxVel);
    return vec3(decodedVel, bestMask.a);
}

float computePhotometricCost(vec3 cCurr, vec3 cPrev) {
    vec3 absDiff = abs(cCurr - cPrev);
    float l1 = (absDiff.r + absDiff.g + absDiff.b) * 0.333333;
    float lumCurr = dot(cCurr, vec3(0.299, 0.587, 0.114));
    float lumPrev = dot(cPrev, vec3(0.299, 0.587, 0.114));
    return 0.65 * l1 + 0.35 * abs(lumCurr - lumPrev);
}

// Phase 1 & 2: Coarse-to-Fine Symmetric Midpoint Screen-Space Optical Flow
// Supports Performance Mode (uOpticalFlowQuality == 1: fast 4-tap cardinal diamond + LK for MX330/iGPU)
// and Quality Mode (uOpticalFlowQuality == 2: full 8-tap coarse + 4-tap fine diamond + LK).
vec3 computeSymmetricFlowOffset(
        vec2 uvNBase,
        vec2 uvPBase,
        vec2 priorVel,
        vec2 texelSize,
        float alpha,
        float searchRadiusPixels
) {
    vec2 bestVel = priorVel;
    vec2 initUvN = clamp(uvNBase + alpha * bestVel, vec2(0.0005), vec2(0.9995));
    vec2 initUvP = clamp(uvPBase - (1.0 - alpha) * bestVel, vec2(0.0005), vec2(0.9995));

    vec3 cN0 = texture(CurrentColorSampler, initUvN).rgb;
    vec3 cP0 = texture(PreviousColorSampler, initUvP).rgb;
    float bestRawCost = computePhotometricCost(cN0, cP0);

    if (uOpticalFlowEnabled == 0 || uOpticalFlowQuality <= 0 || searchRadiusPixels <= 0.05) {
        return vec3(bestVel, bestRawCost);
    }

    float bestScore = bestRawCost;
    float rCoarse = max(1.5, min(searchRadiusPixels, 8.0));

    // Stage 1A: Cardinal 4-tap diamond search (executed in both Performance Mode == 1 and Quality Mode == 2)
    vec2 cardinalOffsets[4] = vec2[4](
        vec2( rCoarse,  0.0),
        vec2(-rCoarse,  0.0),
        vec2( 0.0,  rCoarse),
        vec2( 0.0, -rCoarse)
    );

    for (int i = 0; i < 4; ++i) {
        vec2 candVel = priorVel + cardinalOffsets[i] * texelSize;
        vec2 candUvN = clamp(uvNBase + alpha * candVel, vec2(0.0005), vec2(0.9995));
        vec2 candUvP = clamp(uvPBase - (1.0 - alpha) * candVel, vec2(0.0005), vec2(0.9995));
        vec3 cN = texture(CurrentColorSampler, candUvN).rgb;
        vec3 cP = texture(PreviousColorSampler, candUvP).rgb;
        float rawCost = computePhotometricCost(cN, cP);

        vec3 cPrevAtN = texture(PreviousColorSampler, candUvN).rgb;
        vec3 cCurrAtP = texture(CurrentColorSampler, candUvP).rgb;
        float temporalSalience = min(computePhotometricCost(cN, cPrevAtN), computePhotometricCost(cP, cCurrAtP));
        float spatialSalience = min(computePhotometricCost(cN, cN0), computePhotometricCost(cP, cP0));
        float salience = min(spatialSalience, temporalSalience);

        float score = rawCost + 0.012 - 0.25 * salience;
        if (score < bestScore) {
            bestScore = score;
            bestRawCost = rawCost;
            bestVel = candVel;
        }
    }

    // Fast stationary / aligned pixel bypass after 4-tap cardinal probe:
    // If center already aligns (bestRawCost <= 0.025) and no cardinal moving feature or entity prior was found,
    // bypass diagonal, fine, and Lucas-Kanade stages to preserve mobile GPU bandwidth.
    if (bestRawCost <= 0.025 && dot(bestVel, bestVel) < 1e-10) {
        return vec3(bestVel, bestRawCost);
    }

    // Stage 1B & Stage 2: Diagonal coarse taps + 4-tap 1-texel fine diamond (executed in Quality Mode == 2)
    if (uOpticalFlowQuality >= 2) {
        vec2 diagOffsets[4] = vec2[4](
            vec2( 0.7071 * rCoarse,  0.7071 * rCoarse),
            vec2(-0.7071 * rCoarse,  0.7071 * rCoarse),
            vec2( 0.7071 * rCoarse, -0.7071 * rCoarse),
            vec2(-0.7071 * rCoarse, -0.7071 * rCoarse)
        );

        for (int d = 0; d < 4; ++d) {
            vec2 candVel = priorVel + diagOffsets[d] * texelSize;
            vec2 candUvN = clamp(uvNBase + alpha * candVel, vec2(0.0005), vec2(0.9995));
            vec2 candUvP = clamp(uvPBase - (1.0 - alpha) * candVel, vec2(0.0005), vec2(0.9995));
            vec3 cN = texture(CurrentColorSampler, candUvN).rgb;
            vec3 cP = texture(PreviousColorSampler, candUvP).rgb;
            float rawCost = computePhotometricCost(cN, cP);

            vec3 cPrevAtN = texture(PreviousColorSampler, candUvN).rgb;
            vec3 cCurrAtP = texture(CurrentColorSampler, candUvP).rgb;
            float temporalSalience = min(computePhotometricCost(cN, cPrevAtN), computePhotometricCost(cP, cCurrAtP));
            float spatialSalience = min(computePhotometricCost(cN, cN0), computePhotometricCost(cP, cP0));
            float salience = min(spatialSalience, temporalSalience);

            float score = rawCost + 0.012 - 0.25 * salience;
            if (score < bestScore) {
                bestScore = score;
                bestRawCost = rawCost;
                bestVel = candVel;
            }
        }

        vec2 centerVel = bestVel;
        vec2 fineOffsets[4] = vec2[4](
            vec2( 1.0,  0.0),
            vec2(-1.0,  0.0),
            vec2( 0.0,  1.0),
            vec2( 0.0, -1.0)
        );

        for (int j = 0; j < 4; ++j) {
            vec2 candVel = centerVel + fineOffsets[j] * texelSize;
            vec2 candUvN = clamp(uvNBase + alpha * candVel, vec2(0.0005), vec2(0.9995));
            vec2 candUvP = clamp(uvPBase - (1.0 - alpha) * candVel, vec2(0.0005), vec2(0.9995));
            vec3 cN = texture(CurrentColorSampler, candUvN).rgb;
            vec3 cP = texture(PreviousColorSampler, candUvP).rgb;
            float rawCost = computePhotometricCost(cN, cP);

            vec3 cPrevAtN = texture(PreviousColorSampler, candUvN).rgb;
            vec3 cCurrAtP = texture(CurrentColorSampler, candUvP).rgb;
            float temporalSalience = min(computePhotometricCost(cN, cPrevAtN), computePhotometricCost(cP, cCurrAtP));
            float spatialSalience = min(computePhotometricCost(cN, cN0), computePhotometricCost(cP, cP0));
            float salience = min(spatialSalience, temporalSalience);

            float score = rawCost + 0.004 - 0.25 * salience;
            if (score < bestScore) {
                bestScore = score;
                bestRawCost = rawCost;
                bestVel = candVel;
            }
        }
    }

    // Stage 3: Sub-pixel Lucas-Kanade optical flow fractional refinement
    vec2 refUvN = clamp(uvNBase + alpha * bestVel, vec2(0.001), vec2(0.999));
    vec2 refUvP = clamp(uvPBase - (1.0 - alpha) * bestVel, vec2(0.001), vec2(0.999));
    float lumN = dot(texture(CurrentColorSampler, refUvN).rgb, vec3(0.299, 0.587, 0.114));
    float lumP = dot(texture(PreviousColorSampler, refUvP).rgb, vec3(0.299, 0.587, 0.114));
    float it = lumP - lumN;

    float lumNxPos = dot(texture(CurrentColorSampler, refUvN + vec2(texelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
    float lumNxNeg = dot(texture(CurrentColorSampler, refUvN - vec2(texelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
    float lumNyPos = dot(texture(CurrentColorSampler, refUvN + vec2(0.0, texelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
    float lumNyNeg = dot(texture(CurrentColorSampler, refUvN - vec2(0.0, texelSize.y)).rgb, vec3(0.299, 0.587, 0.114));

    vec2 grad = vec2(lumNxPos - lumNxNeg, lumNyPos - lumNyNeg) * 0.5;
    float gradMagSq = dot(grad, grad) + 0.002;
    vec2 subPixelStep = clamp((it / gradMagSq) * grad, vec2(-0.75), vec2(0.75)) * texelSize;

    vec2 lkVel = bestVel + subPixelStep;
    vec2 lkUvN = clamp(uvNBase + alpha * lkVel, vec2(0.0005), vec2(0.9995));
    vec2 lkUvP = clamp(uvPBase - (1.0 - alpha) * lkVel, vec2(0.0005), vec2(0.9995));
    vec3 cLkN = texture(CurrentColorSampler, lkUvN).rgb;
    vec3 cLkP = texture(PreviousColorSampler, lkUvP).rgb;
    float lkRawCost = computePhotometricCost(cLkN, cLkP);
    if (lkRawCost < bestRawCost) {
        bestRawCost = lkRawCost;
        bestVel = lkVel;
    }

    return vec3(bestVel, bestRawCost);
}

// 3x3 Neighborhood Variance Color Box Clamping for Disocclusion Ghost Prevention
vec3 clampToNeighborhoodAABB(vec3 synthColor, vec2 centerUv, vec2 texelSize, float clampStrength) {
    if (clampStrength <= 0.001) {
        return synthColor;
    }
    vec3 cMin = vec3(1.0);
    vec3 cMax = vec3(0.0);
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            vec2 sUv = clamp(centerUv + vec2(float(dx), float(dy)) * texelSize, vec2(0.0005), vec2(0.9995));
            vec3 sCol = texture(CurrentColorSampler, sUv).rgb;
            cMin = min(cMin, sCol);
            cMax = max(cMax, sCol);
        }
    }
    vec3 clamped = clamp(synthColor, cMin - vec3(0.02), cMax + vec3(0.02));
    return mix(synthColor, clamped, clamp(clampStrength, 0.0, 1.0));
}

void main() {
    vec2 safeScreen = max(ScreenSize, vec2(1.0));
    vec2 texelSize = 1.0 / safeScreen;
    vec2 dilationStep = texelSize * max(DepthDilationRadius, 0.0);

    // 1. Initial 5-tap Reverse-Z dilated depth seeds from both frames at current midpoint screen coordinate
    float dN = sampleDilatedDepth(CurrentDepthSampler, texCoord, dilationStep);
    float dP = sampleDilatedDepth(PreviousDepthSampler, texCoord, dilationStep);

    // Iterative refinement pass 1 & 2 for Frame N candidate (single-tap inside loop to preserve GPU memory bandwidth)
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
            vec3 diff = abs(colCurr.rgb - colPrev.rgb);
            float deltaC = max(diff.r, max(diff.g, diff.b));
            float ramp = smoothstep(0.05, 0.25, deltaC);
            float wPrev = BlendFactor * (1.0 - ramp);
            fragColor = vec4(mix(colCurr.rgb, colPrev.rgb, wPrev), 1.0);
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

    // Phase 2: Query depth-occluded per-entity velocity mask & Phase 1: Coarse-to-Fine Optical Flow
    vec3 entityVel = sampleEntityVelocity(texCoord, texelSize, dMid);
    vec2 baseUvN = uvInBoundsN ? uvN : texCoord;
    vec2 baseUvP = uvInBoundsP ? uvP : texCoord;
    vec3 flowResult = computeSymmetricFlowOffset(
            baseUvN,
            baseUvP,
            entityVel.xy,
            texelSize,
            BlendFactor,
            uOpticalFlowRadius
    );

    vec2 flowUvN = clamp(baseUvN + BlendFactor * flowResult.xy, vec2(0.0005), vec2(0.9995));
    vec2 flowUvP = clamp(baseUvP - (1.0 - BlendFactor) * flowResult.xy, vec2(0.0005), vec2(0.9995));

    float tauN = max(0.05 * max(expectedDepthN, 0.001), 0.005);
    float tauP = max(0.05 * max(expectedDepthP, 0.001), 0.005);

    bool validN = false;
    if (uvInBoundsN) {
        float actualDepthN = sampleDilatedDepth(CurrentDepthSampler, flowUvN, dilationStep);
        validN = abs(actualDepthN - expectedDepthN) <= tauN;
    }

    bool validP = false;
    if (uvInBoundsP) {
        float actualDepthP = sampleDilatedDepth(PreviousDepthSampler, flowUvP, dilationStep);
        validP = abs(actualDepthP - expectedDepthP) <= tauP;
    }

    // Dynamic objects shift depth across frames; promote to motion-compensated synthesis only when
    // an unoccluded entity velocity mask is present or optical flow actively displaced and matched a moving feature
    bool hasActiveFlowDisplacement = dot(flowResult.xy, flowResult.xy) > 1e-10 && flowResult.z < 0.12;
    bool hasMotionMatch = (uvInBoundsN && uvInBoundsP) && (entityVel.z > 0.01 || (uOpticalFlowEnabled != 0 && hasActiveFlowDisplacement));

    // 3. Synthesis Ladder
    if ((validN && validP) || hasMotionMatch) {
        // Case A (Both Valid or Motion-Compensated Match): Bidirectional flow warp + variance clamp
        vec4 currColor = texture(CurrentColorSampler, flowUvN);
        vec4 prevColor = texture(PreviousColorSampler, flowUvP);

        vec3 diff = abs(currColor.rgb - prevColor.rgb);
        float deltaC = max(diff.r, max(diff.g, diff.b));
        float ramp = smoothstep(0.08, 0.32, deltaC);

        float wBase = BlendFactor;
        float wPrev = wBase * (1.0 - ramp);
        float wCurr = 1.0 - wPrev;

        vec3 synth = wCurr * currColor.rgb + wPrev * prevColor.rgb;
        float clampWeight = smoothstep(0.06, 0.25, deltaC);
        synth = clampToNeighborhoodAABB(synth, flowUvN, texelSize, clampWeight);
        fragColor = vec4(synth, 1.0);
    } else if (validN) {
        // Case B (Only N Valid): Newly appearing terrain
        vec4 currColor = texture(CurrentColorSampler, flowUvN);
        if (uDebugLadderMode == 1) {
            fragColor = vec4(mix(currColor.rgb, vec3(0.0, 1.0, 0.0), 0.7), 1.0); // Tint Green
        } else {
            fragColor = vec4(currColor.rgb, 1.0);
        }
    } else if (validP) {
        // Case C (Only N-1 Valid): Terrain rotating out
        vec4 prevColor = texture(PreviousColorSampler, flowUvP);
        if (uDebugLadderMode == 1) {
            fragColor = vec4(mix(prevColor.rgb, vec3(0.0, 0.0, 1.0), 0.7), 1.0); // Tint Blue
        } else {
            fragColor = vec4(prevColor.rgb, 1.0);
        }
    } else {
        // Case D (Neither Valid): Fallback to motion-compensated current frame
        vec4 fallbackColor = texture(CurrentColorSampler, flowUvN);
        if (uDebugLadderMode == 1) {
            fragColor = vec4(mix(fallbackColor.rgb, vec3(1.0, 0.0, 0.0), 0.7), 1.0); // Tint Red
        } else {
            fragColor = vec4(fallbackColor.rgb, 1.0);
        }
    }
}
