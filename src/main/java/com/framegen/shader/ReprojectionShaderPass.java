package com.framegen.shader;

import com.framegen.config.FrameGenConfig;
import com.framegen.core.EntityVelocityTracker;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public class ReprojectionShaderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("ReprojectionShaderPass");

    private static final String DEFAULT_VSH = """
        #version 330 core
        layout (location = 0) in vec2 Position;
        layout (location = 1) in vec2 UV;
        out vec2 texCoord;
        void main() {
            texCoord = UV;
            gl_Position = vec4(Position, 0.0, 1.0);
        }
        """;

    private static final String DEFAULT_FSH = """
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
        """;

    private static final String DEFAULT_HUD_FSH = """
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
        """;

    private int programId = 0;
    private int hudProgramId = 0;
    private int vaoId = 0;
    private int vboId = 0;
    private int fboId = 0;
    private int colorSamplerId = 0;
    private int depthSamplerId = 0;
    private boolean initialized = false;

    // Uniform locations - Reprojection
    private int uCurrentColor = -1;
    private int uPreviousColor = -1;
    private int uCurrentDepth = -1;
    private int uPreviousDepth = -1;
    private int uEntityMask = -1;
    private int uReprojectionMatrix = -1;
    private int uViewModelReprojectionMatrix = -1;
    private int uScreenSize = -1;
    private int uBlendFactor = -1;
    private int uDepthDilation = -1;
    private int uMidToPrevMatrix = -1;
    private int uMidToCurrMatrix = -1;
    private int uMidToPrevSkyMatrix = -1;
    private int uMidToCurrSkyMatrix = -1;
    private int uDebugLadderMode = -1;
    private int uOpticalFlowEnabled = -1;
    private int uOpticalFlowQuality = -1;
    private int uOpticalFlowRadius = -1;
    private int uEntityVelMaxUV = -1;

    // Uniform locations - HUD Composite
    private int uHudIntermediate = -1;
    private int uHudMain = -1;
    private int uHudWorldOnly = -1;
    private int uHudCurrPostHand = -1;
    private int uHudPrevPostHand = -1;
    private int uHudPrevWorldOnly = -1;
    private int uHudScreenSize = -1;
    private int uHudHandInterpEnabled = -1;
    private int uHudBlendFactor = -1;

    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer vmMatrixBuffer = BufferUtils.createFloatBuffer(16);

    public ReprojectionShaderPass() {
    }

    public synchronized void initialize() {
        if (initialized) return;

        try {
            String vshSource = loadShaderResource("/assets/framegen/shaders/reproject.vsh", DEFAULT_VSH);
            String fshSource = loadShaderResource("/assets/framegen/shaders/reproject.fsh", DEFAULT_FSH);

            int vsh = compileShader(GL20.GL_VERTEX_SHADER, vshSource);
            int fsh = compileShader(GL20.GL_FRAGMENT_SHADER, fshSource);

            programId = GL20.glCreateProgram();
            GL20.glAttachShader(programId, vsh);
            GL20.glAttachShader(programId, fsh);
            GL20.glLinkProgram(programId);

            if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(programId);
                throw new RuntimeException("Failed to link reprojection shader program: " + log);
            }

            GL20.glDeleteShader(fsh);

            // Compile HUD Composite Shader
            String hudFshSource = loadShaderResource("/assets/framegen/shaders/hud_composite.fsh", DEFAULT_HUD_FSH);
            int hudFsh = compileShader(GL20.GL_FRAGMENT_SHADER, hudFshSource);
            hudProgramId = GL20.glCreateProgram();
            GL20.glAttachShader(hudProgramId, vsh);
            GL20.glAttachShader(hudProgramId, hudFsh);
            GL20.glLinkProgram(hudProgramId);

            if (GL20.glGetProgrami(hudProgramId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(hudProgramId);
                throw new RuntimeException("Failed to link HUD composite shader program: " + log);
            }

            GL20.glDeleteShader(vsh);
            GL20.glDeleteShader(hudFsh);

            // Query uniform locations - Reprojection
            uCurrentColor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_CURRENT_COLOR);
            uPreviousColor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_PREVIOUS_COLOR);
            uCurrentDepth = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_CURRENT_DEPTH);
            uPreviousDepth = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_PREVIOUS_DEPTH);
            uEntityMask = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_ENTITY_MASK);
            uReprojectionMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_REPROJECTION_MATRIX);
            uViewModelReprojectionMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_VIEWMODEL_REPROJECTION_MATRIX);
            uScreenSize = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_SCREEN_SIZE);
            uBlendFactor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_BLEND_FACTOR);
            uDepthDilation = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_DEPTH_DILATION);
            uMidToPrevMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_MID_TO_PREV_MATRIX);
            uMidToCurrMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_MID_TO_CURR_MATRIX);
            uMidToPrevSkyMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_MID_TO_PREV_SKY_MATRIX);
            uMidToCurrSkyMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_MID_TO_CURR_SKY_MATRIX);
            uDebugLadderMode = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_DEBUG_LADDER_MODE);
            uOpticalFlowEnabled = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_OPTICAL_FLOW_ENABLED);
            uOpticalFlowQuality = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_OPTICAL_FLOW_QUALITY);
            uOpticalFlowRadius = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_OPTICAL_FLOW_RADIUS);
            uEntityVelMaxUV = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_ENTITY_VEL_MAX_UV);

            // Query uniform locations - HUD Composite
            uHudIntermediate = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_INTERMEDIATE);
            uHudMain = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_MAIN);
            uHudWorldOnly = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_WORLD_ONLY);
            uHudCurrPostHand = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_CURR_POST_HAND);
            uHudPrevPostHand = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_PREV_POST_HAND);
            uHudPrevWorldOnly = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_PREV_WORLD_ONLY);
            uHudScreenSize = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_SCREEN_SIZE);
            uHudHandInterpEnabled = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_HAND_INTERP_ENABLED);
            uHudBlendFactor = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_BLEND_FACTOR);

            // OpenGL 3.3 Sampler Objects (Zero texture parameter mutation)
            colorSamplerId = GL33.glGenSamplers();
            GL33.glSamplerParameteri(colorSamplerId, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(colorSamplerId, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(colorSamplerId, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(colorSamplerId, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            depthSamplerId = GL33.glGenSamplers();
            GL33.glSamplerParameteri(depthSamplerId, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL33.glSamplerParameteri(depthSamplerId, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL33.glSamplerParameteri(depthSamplerId, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(depthSamplerId, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(depthSamplerId, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);

            setupFullscreenQuad();
            fboId = GL30.glGenFramebuffers();
            initialized = true;
            LOGGER.info("ReprojectionShaderPass, HUD Composite Shader, and Samplers initialized successfully.");
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize ReprojectionShaderPass", t);
            destroy();
        }
    }

    private void setupFullscreenQuad() {
        try (GlStateSnapshot ignored = GlStateSnapshot.capture()) {
            float[] quadVertices = {
                    -1.0f, -1.0f,  0.0f, 0.0f,
                     1.0f, -1.0f,  1.0f, 0.0f,
                     1.0f,  1.0f,  1.0f, 1.0f,
                    -1.0f, -1.0f,  0.0f, 0.0f,
                     1.0f,  1.0f,  1.0f, 1.0f,
                    -1.0f,  1.0f,  0.0f, 1.0f
            };

            vaoId = GL30.glGenVertexArrays();
            vboId = GL15.glGenBuffers();

            GL30.glBindVertexArray(vaoId);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);

            FloatBuffer buffer = BufferUtils.createFloatBuffer(quadVertices.length);
            buffer.put(quadVertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);

            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);

            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    /**
     * Phase 2: Rasterizes screen-space residual entity velocity bounding boxes into the EntityMaskFBO
     * using hardware scissor clears (zero extra shader or VBO overhead).
     */
    public synchronized boolean renderEntityVelocityMask(
            GpuTexture entityMaskTexture,
            int width,
            int height,
            List<EntityVelocityTracker.EntityVelocityQuad> velocityQuads
    ) {
        if (entityMaskTexture == null || width <= 0 || height <= 0) {
            return false;
        }
        if (!initialized) {
            initialize();
            if (!initialized) return false;
        }

        int maskTexId = getGlTextureId(entityMaskTexture);
        if (maskTexId == 0) return false;

        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColorMask(true, true, true, true);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, maskTexId, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                return false;
            }

            // Clear entire mask buffer to neutral zero velocity (R=0.5, G=0.5, B=0.0, A=0.0 inactive)
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glClearColor(0.5f, 0.5f, 0.0f, 0.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            if (velocityQuads != null && !velocityQuads.isEmpty()) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                for (EntityVelocityTracker.EntityVelocityQuad quad : velocityQuads) {
                    int x0 = Math.max(0, Math.min(width, Math.round(quad.minU() * width)));
                    int y0 = Math.max(0, Math.min(height, Math.round(quad.minV() * height)));
                    int x1 = Math.max(0, Math.min(width, Math.round(quad.maxU() * width)));
                    int y1 = Math.max(0, Math.min(height, Math.round(quad.maxV() * height)));
                    int scWidth = x1 - x0;
                    int scHeight = y1 - y0;
                    if (scWidth > 0 && scHeight > 0) {
                        GL11.glScissor(x0, y0, scWidth, scHeight);
                        GL11.glClearColor(quad.encodedR(), quad.encodedG(), quad.encodedB(), quad.encodedA());
                        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    }
                }
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }

            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.renderEntityVelocityMask", t);
            return false;
        }
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture outputColor,
            Matrix4f reprojectMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation
    ) {
        return render(currentColor, previousColor, currentDepth, previousDepth, null, outputColor, reprojectMatrix, reprojectMatrix, width, height, blendFactor, depthDilation);
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture entityMask,
            GpuTexture outputColor,
            Matrix4f reprojectMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation
    ) {
        return render(currentColor, previousColor, currentDepth, previousDepth, entityMask, outputColor, reprojectMatrix, reprojectMatrix, width, height, blendFactor, depthDilation);
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture entityMask,
            GpuTexture outputColor,
            Matrix4f reprojectMatrix,
            Matrix4f viewmodelReprojectMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation
    ) {
        return render(currentColor, previousColor, currentDepth, previousDepth, entityMask, outputColor,
                reprojectMatrix, reprojectMatrix, reprojectMatrix, reprojectMatrix,
                width, height, blendFactor, depthDilation, false);
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture entityMask,
            GpuTexture outputColor,
            Matrix4f midToPrevMatrix,
            Matrix4f midToCurrMatrix,
            Matrix4f midToPrevSkyMatrix,
            Matrix4f midToCurrSkyMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation,
            boolean debugLadderMode
    ) {
        FrameGenConfig cfg = FrameGenConfig.getInstance();
        boolean opticalFlow = cfg == null || cfg.isOpticalFlowEnabled();
        int flowQuality = cfg != null ? cfg.getOpticalFlowQualityMode() : ShaderConstants.FLOW_QUALITY_PERFORMANCE;
        float flowRadius = cfg != null ? cfg.getOpticalFlowSearchRadius() : 3.0f;
        float maxVelUv = cfg != null ? cfg.getEntityVelocityMaxUv() : ShaderConstants.DEFAULT_ENTITY_VELOCITY_MAX_UV;
        return render(
                currentColor, previousColor, currentDepth, previousDepth, entityMask, outputColor,
                midToPrevMatrix, midToCurrMatrix, midToPrevSkyMatrix, midToCurrSkyMatrix,
                width, height, blendFactor, depthDilation, debugLadderMode,
                opticalFlow, flowQuality, flowRadius, maxVelUv
        );
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture entityMask,
            GpuTexture outputColor,
            Matrix4f midToPrevMatrix,
            Matrix4f midToCurrMatrix,
            Matrix4f midToPrevSkyMatrix,
            Matrix4f midToCurrSkyMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation,
            boolean debugLadderMode,
            boolean opticalFlowEnabled,
            float opticalFlowRadius,
            float entityVelMaxUv
    ) {
        FrameGenConfig cfg = FrameGenConfig.getInstance();
        int flowQuality = cfg != null ? cfg.getOpticalFlowQualityMode() : ShaderConstants.FLOW_QUALITY_PERFORMANCE;
        return render(
                currentColor, previousColor, currentDepth, previousDepth, entityMask, outputColor,
                midToPrevMatrix, midToCurrMatrix, midToPrevSkyMatrix, midToCurrSkyMatrix,
                width, height, blendFactor, depthDilation, debugLadderMode,
                opticalFlowEnabled, flowQuality, opticalFlowRadius, entityVelMaxUv
        );
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture entityMask,
            GpuTexture outputColor,
            Matrix4f midToPrevMatrix,
            Matrix4f midToCurrMatrix,
            Matrix4f midToPrevSkyMatrix,
            Matrix4f midToCurrSkyMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation,
            boolean debugLadderMode,
            boolean opticalFlowEnabled,
            int opticalFlowQuality,
            float opticalFlowRadius,
            float entityVelMaxUv
    ) {
        if (!initialized) {
            initialize();
            if (!initialized) return false;
        }

        int targetTexId = getGlTextureId(outputColor != null ? outputColor : currentColor);
        if (targetTexId == 0) return false;

        GlStateProbe probeBefore = (FrameGenConfig.getInstance() != null && FrameGenConfig.getInstance().isDebugLogGlDrift()) ? GlStateProbe.capture() : null;

        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            // Establish clean quad pipeline state
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);

            // Bind destination FBO
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexId, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                LOGGER.warn("Intermediate framebuffer incomplete: 0x{}", Integer.toHexString(status));
                return false;
            }

            GL11.glViewport(0, 0, width, height);
            GL20.glUseProgram(programId);

            // Bind Textures & Sampler Objects (Zero texture mutation)
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR, currentColor, uCurrentColor, ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR, colorSamplerId);
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR, previousColor, uPreviousColor, ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR, colorSamplerId);
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH, currentDepth, uCurrentDepth, ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH, depthSamplerId);
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH, previousDepth, uPreviousDepth, ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH, depthSamplerId);
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_ENTITY_MASK, entityMask, uEntityMask, ShaderConstants.TEXTURE_UNIT_ENTITY_MASK, colorSamplerId);

            // Upload Midpoint & Sky Uniforms
            if (uMidToPrevMatrix != -1 && midToPrevMatrix != null) {
                matrixBuffer.clear();
                midToPrevMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uMidToPrevMatrix, false, matrixBuffer);
            }

            if (uMidToCurrMatrix != -1 && midToCurrMatrix != null) {
                matrixBuffer.clear();
                midToCurrMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uMidToCurrMatrix, false, matrixBuffer);
            }

            if (uMidToPrevSkyMatrix != -1 && midToPrevSkyMatrix != null) {
                matrixBuffer.clear();
                midToPrevSkyMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uMidToPrevSkyMatrix, false, matrixBuffer);
            }

            if (uMidToCurrSkyMatrix != -1 && midToCurrSkyMatrix != null) {
                matrixBuffer.clear();
                midToCurrSkyMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uMidToCurrSkyMatrix, false, matrixBuffer);
            }

            // Backwards-compatible legacy uniforms
            if (uReprojectionMatrix != -1 && midToPrevMatrix != null) {
                matrixBuffer.clear();
                midToPrevMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uReprojectionMatrix, false, matrixBuffer);
            }

            if (uViewModelReprojectionMatrix != -1 && midToPrevMatrix != null) {
                vmMatrixBuffer.clear();
                midToPrevMatrix.get(vmMatrixBuffer);
                GL20.glUniformMatrix4fv(uViewModelReprojectionMatrix, false, vmMatrixBuffer);
            }

            if (uScreenSize != -1) {
                GL20.glUniform2f(uScreenSize, (float) width, (float) height);
            }

            if (uBlendFactor != -1) {
                GL20.glUniform1f(uBlendFactor, blendFactor);
            }

            if (uDepthDilation != -1) {
                GL20.glUniform1f(uDepthDilation, depthDilation);
            }

            if (uDebugLadderMode != -1) {
                GL20.glUniform1i(uDebugLadderMode, debugLadderMode ? 1 : 0);
            }

            if (uOpticalFlowEnabled != -1) {
                GL20.glUniform1i(uOpticalFlowEnabled, opticalFlowEnabled ? 1 : 0);
            }

            if (uOpticalFlowQuality != -1) {
                GL20.glUniform1i(uOpticalFlowQuality, opticalFlowQuality);
            }

            if (uOpticalFlowRadius != -1) {
                GL20.glUniform1f(uOpticalFlowRadius, opticalFlowRadius);
            }

            if (uEntityVelMaxUV != -1) {
                GL20.glUniform1f(uEntityVelMaxUV, entityVelMaxUv);
            }

            // Draw Fullscreen Quad
            GL30.glBindVertexArray(vaoId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.render", t);
            return false;
        } finally {
            if (probeBefore != null) {
                GlStateProbe probeAfter = GlStateProbe.capture();
                probeBefore.compareAndLog("ReprojectionShaderPass.render", probeAfter);
            }
        }
    }

    public synchronized boolean compositeHud(
            GpuTexture intermediateTexture,
            GpuTexture mainTextureWithHud,
            GpuTexture worldTextureOnly,
            GpuTexture outputTexture,
            int width,
            int height
    ) {
        return compositeHud(
                intermediateTexture,
                mainTextureWithHud,
                worldTextureOnly,
                null,
                null,
                null,
                outputTexture,
                width,
                height,
                false,
                0.5f
        );
    }

    public synchronized boolean compositeHud(
            GpuTexture intermediateTexture,
            GpuTexture mainTextureWithHud,
            GpuTexture worldTextureOnly,
            GpuTexture currPostHandTexture,
            GpuTexture prevPostHandTexture,
            GpuTexture prevWorldOnlyTexture,
            GpuTexture outputTexture,
            int width,
            int height,
            boolean handInterpEnabled,
            float blendFactor
    ) {
        if (!initialized) {
            initialize();
            if (!initialized) return false;
        }

        int targetTexId = getGlTextureId(outputTexture != null ? outputTexture : intermediateTexture);
        if (targetTexId == 0) return false;

        GlStateProbe probeBefore = (FrameGenConfig.getInstance() != null && FrameGenConfig.getInstance().isDebugLogGlDrift()) ? GlStateProbe.capture() : null;

        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            // Establish clean quad pipeline state
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexId, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                LOGGER.warn("HUD composite framebuffer incomplete: 0x{}", Integer.toHexString(status));
                return false;
            }

            GL11.glViewport(0, 0, width, height);
            GL20.glUseProgram(hudProgramId);

            boolean canInterpolateHand = handInterpEnabled
                    && currPostHandTexture != null
                    && prevPostHandTexture != null
                    && prevWorldOnlyTexture != null;

            bindTexture(GL13.GL_TEXTURE0, intermediateTexture, uHudIntermediate, 0, colorSamplerId);
            bindTexture(GL13.GL_TEXTURE1, mainTextureWithHud, uHudMain, 1, colorSamplerId);
            bindTexture(GL13.GL_TEXTURE2, worldTextureOnly, uHudWorldOnly, 2, colorSamplerId);

            if (canInterpolateHand) {
                bindTexture(GL13.GL_TEXTURE0 + 3, currPostHandTexture, uHudCurrPostHand, 3, colorSamplerId);
                bindTexture(GL13.GL_TEXTURE0 + 4, prevPostHandTexture, uHudPrevPostHand, 4, colorSamplerId);
                bindTexture(GL13.GL_TEXTURE0 + 5, prevWorldOnlyTexture, uHudPrevWorldOnly, 5, colorSamplerId);
            }

            if (uHudScreenSize != -1) {
                GL20.glUniform2f(uHudScreenSize, (float) width, (float) height);
            }
            if (uHudHandInterpEnabled != -1) {
                GL20.glUniform1i(uHudHandInterpEnabled, canInterpolateHand ? 1 : 0);
            }
            if (uHudBlendFactor != -1) {
                GL20.glUniform1f(uHudBlendFactor, blendFactor);
            }

            GL30.glBindVertexArray(vaoId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.compositeHud", t);
            return false;
        } finally {
            if (probeBefore != null) {
                GlStateProbe probeAfter = GlStateProbe.capture();
                probeBefore.compareAndLog("ReprojectionShaderPass.compositeHud", probeAfter);
            }
        }
    }

    /**
     * Forces the alpha channel of the given color texture to 1.0 (fully opaque) without modifying RGB values.
     * Prevents Windows DWM / translucent window compositors from bleeding desktop wallpaper through sky/fog pixels
     * where Minecraft clears or renders fog with alpha = 0.0, and eliminates opaque-vs-transparent frame strobing.
     */
    public synchronized boolean sanitizeAlpha(GpuTexture targetTexture, int width, int height) {
        if (targetTexture == null || width <= 0 || height <= 0) {
            return false;
        }
        if (!initialized) {
            initialize();
            if (!initialized) return false;
        }

        int targetTexId = getGlTextureId(targetTexture);
        if (targetTexId == 0) return false;

        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexId, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                return false;
            }

            GL11.glColorMask(false, false, false, true);
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.sanitizeAlpha", t);
            return false;
        }
    }

    /**
     * Forces the alpha channel of the default window backbuffer (FBO 0) to 1.0 immediately prior to presentation.
     */
    public synchronized void sanitizeDefaultFramebufferAlpha() {
        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL11.glColorMask(false, false, false, true);
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.sanitizeDefaultFramebufferAlpha", t);
        }
    }

    private void bindTexture(int textureUnit, GpuTexture texture, int uniformLocation, int unitIndex, int samplerId) {
        if (texture != null && uniformLocation != -1) {
            GL13.glActiveTexture(textureUnit);
            int glId = getGlTextureId(texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
            if (samplerId != 0) {
                GL33.glBindSampler(unitIndex, samplerId);
            }
            GL20.glUniform1i(uniformLocation, unitIndex);
        }
    }

    private int getGlTextureId(GpuTexture texture) {
        if (texture instanceof GlTexture glTexture) {
            return glTexture.glId();
        }
        return 0;
    }

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("Shader compilation error (" + type + "): " + log);
        }
        return shader;
    }

    private String loadShaderResource(String path, String fallback) {
        try {
            InputStream in = getClass().getResourceAsStream(path);
            if (in == null) {
                in = ReprojectionShaderPass.class.getClassLoader().getResourceAsStream(path.startsWith("/") ? path.substring(1) : path);
            }
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load shader resource from {}, using built-in fallback: {}", path, e.getMessage());
        }
        return fallback;
    }

    public synchronized void destroy() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        if (hudProgramId != 0) {
            GL20.glDeleteProgram(hudProgramId);
            hudProgramId = 0;
        }
        if (vboId != 0) {
            GL15.glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (vaoId != 0) {
            GL30.glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        if (fboId != 0) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (colorSamplerId != 0) {
            GL33.glDeleteSamplers(colorSamplerId);
            colorSamplerId = 0;
        }
        if (depthSamplerId != 0) {
            GL33.glDeleteSamplers(depthSamplerId);
            depthSamplerId = 0;
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public static class GlStateSnapshot implements AutoCloseable {
        private final int drawFbo;
        private final int readFbo;
        private final int program;
        private final int vao;
        private final int vbo;
        private final int activeTexture;
        private final int[] textureBindings = new int[6];
        private final int[] samplerBindings = new int[6];
        private final int[] viewport = new int[4];
        private final float[] clearColor = new float[4];
        private final boolean colorMaskR;
        private final boolean colorMaskG;
        private final boolean colorMaskB;
        private final boolean colorMaskA;
        private final boolean scissor;
        private final boolean blend;
        private final boolean depthTest;
        private final boolean cullFace;
        private final boolean stencilTest;
        private final boolean srgb;

        public static GlStateSnapshot capture() {
            return new GlStateSnapshot();
        }

        private GlStateSnapshot() {
            this.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            this.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            this.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            this.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            this.vbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            this.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, this.clearColor);

            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                ByteBuffer maskBuf = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, maskBuf);
                this.colorMaskR = maskBuf.get(0) != 0;
                this.colorMaskG = maskBuf.get(1) != 0;
                this.colorMaskB = maskBuf.get(2) != 0;
                this.colorMaskA = maskBuf.get(3) != 0;
            }

            for (int i = 0; i < 6; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                this.textureBindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                try {
                    this.samplerBindings[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                } catch (Throwable ignored) {
                }
            }
            GL13.glActiveTexture(this.activeTexture);

            this.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            this.blend = GL11.glIsEnabled(GL11.GL_BLEND);
            this.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            this.cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            this.stencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            boolean srgbVal = false;
            try {
                srgbVal = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
            } catch (Throwable ignored) {
            }
            this.srgb = srgbVal;
        }

        @Override
        public void close() {
            // Restore framebuffer bindings
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFbo);

            // Restore shader program and buffer/VAO bindings
            GL20.glUseProgram(this.program);
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);

            // Restore per-unit texture and sampler bindings
            for (int i = 0; i < 6; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureBindings[i]);
                try {
                    GL33.glBindSampler(i, this.samplerBindings[i]);
                } catch (Throwable ignored) {
                }
            }
            GL13.glActiveTexture(this.activeTexture);

            // Restore viewport, clear color & rasterizer state
            GL11.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
            GL11.glClearColor(this.clearColor[0], this.clearColor[1], this.clearColor[2], this.clearColor[3]);
            GL11.glColorMask(
                    this.colorMaskR,
                    this.colorMaskG,
                    this.colorMaskB,
                    this.colorMaskA
            );

            if (this.scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (this.blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
            if (this.depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (this.cullFace) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
            if (this.stencilTest) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
            try {
                if (this.srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB); else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            } catch (Throwable ignored) {
            }
        }
    }

    public static class GlStateProbe {
        public final int drawFbo;
        public final int readFbo;
        public final int program;
        public final int vao;
        public final int vbo;
        public final int activeTexture;
        public final int[] textureBindings = new int[6];
        public final int[] samplerBindings = new int[6];
        public final boolean scissor;
        public final boolean blend;
        public final boolean depthTest;
        public final boolean cullFace;

        public static GlStateProbe capture() {
            int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int vbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int activeTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int[] texUnits = new int[6];
            int[] samplers = new int[6];

            for (int i = 0; i < 6; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                texUnits[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                try {
                    samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                } catch (Throwable ignored) {
                }
            }
            GL13.glActiveTexture(activeTex);

            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);

            return new GlStateProbe(drawFbo, readFbo, program, vao, vbo, activeTex, texUnits, samplers, scissor, blend, depthTest, cullFace);
        }

        public GlStateProbe(int drawFbo, int readFbo, int program, int vao, int vbo, int activeTexture, int[] texUnits, int[] samplers, boolean scissor, boolean blend, boolean depthTest, boolean cullFace) {
            this.drawFbo = drawFbo;
            this.readFbo = readFbo;
            this.program = program;
            this.vao = vao;
            this.vbo = vbo;
            this.activeTexture = activeTexture;
            System.arraycopy(texUnits, 0, this.textureBindings, 0, 6);
            System.arraycopy(samplers, 0, this.samplerBindings, 0, 6);
            this.scissor = scissor;
            this.blend = blend;
            this.depthTest = depthTest;
            this.cullFace = cullFace;
        }

        public void compareAndLog(String passName, GlStateProbe after) {
            if (after == null) return;
            StringBuilder sb = new StringBuilder();
            if (this.drawFbo != after.drawFbo) sb.append(String.format(" drawFBO: %d -> %d;", this.drawFbo, after.drawFbo));
            if (this.readFbo != after.readFbo) sb.append(String.format(" readFBO: %d -> %d;", this.readFbo, after.readFbo));
            if (this.program != after.program) sb.append(String.format(" program: %d -> %d;", this.program, after.program));
            if (this.vao != after.vao) sb.append(String.format(" vao: %d -> %d;", this.vao, after.vao));
            if (this.vbo != after.vbo) sb.append(String.format(" vbo: %d -> %d;", this.vbo, after.vbo));
            if (this.activeTexture != after.activeTexture) sb.append(String.format(" activeTex: 0x%X -> 0x%X;", this.activeTexture, after.activeTexture));
            for (int i = 0; i < 6; i++) {
                if (this.textureBindings[i] != after.textureBindings[i]) {
                    sb.append(String.format(" texUnit%d: %d -> %d;", i, this.textureBindings[i], after.textureBindings[i]));
                }
                if (this.samplerBindings[i] != after.samplerBindings[i]) {
                    sb.append(String.format(" sampler%d: %d -> %d;", i, this.samplerBindings[i], after.samplerBindings[i]));
                }
            }
            if (this.scissor != after.scissor) sb.append(String.format(" scissor: %b -> %b;", this.scissor, after.scissor));
            if (this.blend != after.blend) sb.append(String.format(" blend: %b -> %b;", this.blend, after.blend));
            if (this.depthTest != after.depthTest) sb.append(String.format(" depthTest: %b -> %b;", this.depthTest, after.depthTest));
            if (this.cullFace != after.cullFace) sb.append(String.format(" cullFace: %b -> %b;", this.cullFace, after.cullFace));

            if (sb.length() > 0) {
                LOGGER.warn("[GL State Drift Probe] State mutation detected in {}:{}", passName, sb.toString());
            }
        }
    }

    /**
     * Phase 1 Mathematical Reference Evaluator: 5-Tap Cardinal Cross Reverse-Z Foreground Depth Dilation.
     * In Reverse-Z (1.0 = near, 0.0 = far), returns the center depth when dilationRadius <= 0.001,
     * or the maximum depth across the 5-tap cardinal cross (Center=4, N=1, S=7, E=5, W=3) when dilationRadius > 0.001.
     */
    public static float evaluateDilatedDepth3x3(float[] depthGrid3x3, float dilationRadius) {
        if (depthGrid3x3 == null || depthGrid3x3.length < 9) {
            throw new IllegalArgumentException("depthGrid3x3 must contain 9 elements (3x3 neighborhood)");
        }
        float center = depthGrid3x3[4];
        if (dilationRadius <= 0.001f) {
            return center;
        }
        int[] cardinalIndices = new int[] { 1, 3, 4, 5, 7 };
        float maxDepth = center;
        for (int idx : cardinalIndices) {
            if (depthGrid3x3[idx] > maxDepth) {
                maxDepth = depthGrid3x3[idx];
            }
        }
        return maxDepth;
    }

    /**
     * Phase 2 Mathematical Reference Evaluator: Reverse-Z Depth Occlusion Check for Entity Velocity Mask.
     * Returns true if the entity at entityDepth is visible at pixelDepth, or false if occluded behind a nearer surface.
     */
    public static boolean isEntityVelocityVisible(float pixelDepth, float entityDepth, float maskAlpha) {
        if (pixelDepth <= 0.0001f || maskAlpha <= 0.01f) {
            return false;
        }
        float depthTolerance = Math.max(0.08f * Math.max(entityDepth, 0.005f), 0.008f);
        return pixelDepth <= entityDepth + depthTolerance;
    }

    /**
     * Phase 1 & 2 Mathematical Reference Evaluator: Symmetric Midpoint Coarse-to-Fine Optical Flow
     * with Temporal Motion Salience + post-cardinal aligned-pixel bypass + Stage 3 Lucas-Kanade sub-pixel refinement
     * + 3x3 Neighborhood Variance Clamping (100% parity with computeSymmetricFlowOffset in reproject.fsh).
     */
    public static float[] evaluateOpticalFlowSynthesis(
            float[][] currPatch,
            float[][] prevPatch,
            int width,
            int height,
            int centerX,
            int centerY,
            float priorDuPixels,
            float priorDvPixels,
            float blendFactor,
            int searchRadiusPixels
    ) {
        return evaluateOpticalFlowSynthesis(
                currPatch, prevPatch, width, height, centerX, centerY,
                priorDuPixels, priorDvPixels, blendFactor, searchRadiusPixels,
                ShaderConstants.FLOW_QUALITY_ULTRA
        );
    }

    public static float[] evaluateOpticalFlowSynthesis(
            float[][] currPatch,
            float[][] prevPatch,
            int width,
            int height,
            int centerX,
            int centerY,
            float priorDuPixels,
            float priorDvPixels,
            float blendFactor,
            int searchRadiusPixels,
            int qualityMode
    ) {
        float bestDu = priorDuPixels;
        float bestDv = priorDvPixels;

        float[] cN0 = samplePatchBilinear(currPatch, width, height, centerX + blendFactor * bestDu, centerY + blendFactor * bestDv);
        float[] cP0 = samplePatchBilinear(prevPatch, width, height, centerX - (1.0f - blendFactor) * bestDu, centerY - (1.0f - blendFactor) * bestDv);
        float bestRawCost = photometricCost(cN0, cP0);
        float bestScore = bestRawCost;

        if (searchRadiusPixels > 0 && qualityMode > 0) {
            float rCoarse = Math.max(1.5f, Math.min((float) searchRadiusPixels, 8.0f));
            float[][] cardinalOffsets = new float[][] {
                    {  rCoarse, 0.0f },
                    { -rCoarse, 0.0f },
                    { 0.0f,  rCoarse },
                    { 0.0f, -rCoarse }
            };

            for (float[] offset : cardinalOffsets) {
                float candDu = priorDuPixels + offset[0];
                float candDv = priorDvPixels + offset[1];
                float xN = centerX + blendFactor * candDu;
                float yN = centerY + blendFactor * candDv;
                float xP = centerX - (1.0f - blendFactor) * candDu;
                float yP = centerY - (1.0f - blendFactor) * candDv;

                float[] cN = samplePatchBilinear(currPatch, width, height, xN, yN);
                float[] cP = samplePatchBilinear(prevPatch, width, height, xP, yP);
                float rawCost = photometricCost(cN, cP);

                float[] cPrevAtN = samplePatchBilinear(prevPatch, width, height, xN, yN);
                float[] cCurrAtP = samplePatchBilinear(currPatch, width, height, xP, yP);
                float temporalSalience = Math.min(photometricCost(cN, cPrevAtN), photometricCost(cP, cCurrAtP));
                float spatialSalience = Math.min(photometricCost(cN, cN0), photometricCost(cP, cP0));
                float salience = Math.min(spatialSalience, temporalSalience);

                float score = rawCost + 0.012f - 0.25f * salience;
                if (score < bestScore) {
                    bestScore = score;
                    bestRawCost = rawCost;
                    bestDu = candDu;
                    bestDv = candDv;
                }
            }

            boolean bypassRemainingStages = (bestRawCost <= 0.025f && (bestDu * bestDu + bestDv * bestDv) < 1e-10f);

            if (!bypassRemainingStages) {
                if (qualityMode >= ShaderConstants.FLOW_QUALITY_ULTRA) {
                    float[][] diagOffsets = new float[][] {
                            {  0.7071f * rCoarse,  0.7071f * rCoarse },
                            { -0.7071f * rCoarse,  0.7071f * rCoarse },
                            {  0.7071f * rCoarse, -0.7071f * rCoarse },
                            { -0.7071f * rCoarse, -0.7071f * rCoarse }
                    };
                    for (float[] offset : diagOffsets) {
                        float candDu = priorDuPixels + offset[0];
                        float candDv = priorDvPixels + offset[1];
                        float xN = centerX + blendFactor * candDu;
                        float yN = centerY + blendFactor * candDv;
                        float xP = centerX - (1.0f - blendFactor) * candDu;
                        float yP = centerY - (1.0f - blendFactor) * candDv;

                        float[] cN = samplePatchBilinear(currPatch, width, height, xN, yN);
                        float[] cP = samplePatchBilinear(prevPatch, width, height, xP, yP);
                        float rawCost = photometricCost(cN, cP);

                        float[] cPrevAtN = samplePatchBilinear(prevPatch, width, height, xN, yN);
                        float[] cCurrAtP = samplePatchBilinear(currPatch, width, height, xP, yP);
                        float temporalSalience = Math.min(photometricCost(cN, cPrevAtN), photometricCost(cP, cCurrAtP));
                        float spatialSalience = Math.min(photometricCost(cN, cN0), photometricCost(cP, cP0));
                        float salience = Math.min(spatialSalience, temporalSalience);

                        float score = rawCost + 0.012f - 0.25f * salience;
                        if (score < bestScore) {
                            bestScore = score;
                            bestRawCost = rawCost;
                            bestDu = candDu;
                            bestDv = candDv;
                        }
                    }

                    float stage1Du = bestDu;
                    float stage1Dv = bestDv;
                    float[][] fineOffsets = new float[][] {
                            {  1.0f, 0.0f },
                            { -1.0f, 0.0f },
                            { 0.0f,  1.0f },
                            { 0.0f, -1.0f }
                    };
                    for (float[] offset : fineOffsets) {
                        float candDu = stage1Du + offset[0];
                        float candDv = stage1Dv + offset[1];
                        float xN = centerX + blendFactor * candDu;
                        float yN = centerY + blendFactor * candDv;
                        float xP = centerX - (1.0f - blendFactor) * candDu;
                        float yP = centerY - (1.0f - blendFactor) * candDv;

                        float[] cN = samplePatchBilinear(currPatch, width, height, xN, yN);
                        float[] cP = samplePatchBilinear(prevPatch, width, height, xP, yP);
                        float rawCost = photometricCost(cN, cP);

                        float[] cPrevAtN = samplePatchBilinear(prevPatch, width, height, xN, yN);
                        float[] cCurrAtP = samplePatchBilinear(currPatch, width, height, xP, yP);
                        float temporalSalience = Math.min(photometricCost(cN, cPrevAtN), photometricCost(cP, cCurrAtP));
                        float spatialSalience = Math.min(photometricCost(cN, cN0), photometricCost(cP, cP0));
                        float salience = Math.min(spatialSalience, temporalSalience);

                        float score = rawCost + 0.004f - 0.25f * salience;
                        if (score < bestScore) {
                            bestScore = score;
                            bestRawCost = rawCost;
                            bestDu = candDu;
                            bestDv = candDv;
                        }
                    }
                }

                // Stage 3: Sub-pixel Lucas-Kanade optical flow fractional refinement
                float refX_N = centerX + blendFactor * bestDu;
                float refY_N = centerY + blendFactor * bestDv;
                float refX_P = centerX - (1.0f - blendFactor) * bestDu;
                float refY_P = centerY - (1.0f - blendFactor) * bestDv;

                float lumN = luminance(samplePatchBilinear(currPatch, width, height, refX_N, refY_N));
                float lumP = luminance(samplePatchBilinear(prevPatch, width, height, refX_P, refY_P));
                float it = lumP - lumN;

                float lumNxPos = luminance(samplePatchBilinear(currPatch, width, height, refX_N + 1.0f, refY_N));
                float lumNxNeg = luminance(samplePatchBilinear(currPatch, width, height, refX_N - 1.0f, refY_N));
                float lumNyPos = luminance(samplePatchBilinear(currPatch, width, height, refX_N, refY_N + 1.0f));
                float lumNyNeg = luminance(samplePatchBilinear(currPatch, width, height, refX_N, refY_N - 1.0f));

                float gx = (lumNxPos - lumNxNeg) * 0.5f;
                float gy = (lumNyPos - lumNyNeg) * 0.5f;
                float gradMagSq = gx * gx + gy * gy + 0.002f;
                float stepX = clamp((it / gradMagSq) * gx, -0.75f, 0.75f);
                float stepY = clamp((it / gradMagSq) * gy, -0.75f, 0.75f);

                float lkDu = bestDu + stepX;
                float lkDv = bestDv + stepY;
                float[] cLkN = samplePatchBilinear(currPatch, width, height, centerX + blendFactor * lkDu, centerY + blendFactor * lkDv);
                float[] cLkP = samplePatchBilinear(prevPatch, width, height, centerX - (1.0f - blendFactor) * lkDu, centerY - (1.0f - blendFactor) * lkDv);
                float lkRawCost = photometricCost(cLkN, cLkP);
                if (lkRawCost < bestRawCost) {
                    bestRawCost = lkRawCost;
                    bestDu = lkDu;
                    bestDv = lkDv;
                }
            }
        }

        float flowX_N = centerX + blendFactor * bestDu;
        float flowY_N = centerY + blendFactor * bestDv;
        float flowX_P = centerX - (1.0f - blendFactor) * bestDu;
        float flowY_P = centerY - (1.0f - blendFactor) * bestDv;

        float[] currCol = samplePatchBilinear(currPatch, width, height, flowX_N, flowY_N);
        float[] prevCol = samplePatchBilinear(prevPatch, width, height, flowX_P, flowY_P);

        float diffR = Math.abs(currCol[0] - prevCol[0]);
        float diffG = Math.abs(currCol[1] - prevCol[1]);
        float diffB = Math.abs(currCol[2] - prevCol[2]);
        float deltaC = Math.max(diffR, Math.max(diffG, diffB));
        float ramp = smoothstep(0.08f, 0.32f, deltaC);

        float wPrev = blendFactor * (1.0f - ramp);
        float wCurr = 1.0f - wPrev;

        float[] synth = new float[] {
                wCurr * currCol[0] + wPrev * prevCol[0],
                wCurr * currCol[1] + wPrev * prevCol[1],
                wCurr * currCol[2] + wPrev * prevCol[2]
        };

        // 3x3 Neighborhood Variance AABB Clamp around flowX_N, flowY_N
        float clampStrength = smoothstep(0.06f, 0.25f, deltaC);
        if (clampStrength > 0.001f) {
            float[] cMin = new float[] { 1.0f, 1.0f, 1.0f };
            float[] cMax = new float[] { 0.0f, 0.0f, 0.0f };
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    float[] sCol = samplePatchBilinear(currPatch, width, height, flowX_N + dx, flowY_N + dy);
                    for (int c = 0; c < 3; c++) {
                        cMin[c] = Math.min(cMin[c], sCol[c]);
                        cMax[c] = Math.max(cMax[c], sCol[c]);
                    }
                }
            }
            for (int c = 0; c < 3; c++) {
                float clamped = clamp(synth[c], cMin[c] - 0.02f, cMax[c] + 0.02f);
                synth[c] = (1.0f - clampStrength) * synth[c] + clampStrength * clamped;
            }
        }

        return new float[] { synth[0], synth[1], synth[2], bestDu, bestDv, bestRawCost };
    }

    /**
     * Phase 3 & 4 Mathematical Reference Evaluator: 3-Layer World + T=0.5 First-Person Hand + 2D GUI Composite
     * (100% parity with synthesizeMidpointHand and main() in hud_composite.fsh).
     */
    public static float[] evaluateThreeLayerHudAndHandComposite(
            float[][] interWorldPatch,
            float[][] currWorldPatch,
            float[][] prevWorldPatch,
            float[][] currPostHandPatch,
            float[][] prevPostHandPatch,
            float[][] mainWithGuiPatch,
            int width,
            int height,
            int x,
            int y,
            float alpha
    ) {
        int idx = y * width + x;
        float[] interWorld = interWorldPatch[idx];
        float[] mainColor = mainWithGuiPatch[idx];
        float[] postHandCurr = currPostHandPatch[idx];

        float lumPostHand = luminance(postHandCurr);
        float lumMain = luminance(mainColor);
        float vRatio = lumMain / Math.max(lumPostHand, 0.0001f);
        float vClamped = clamp(vRatio, 0.0f, 1.0f);
        float lumWeight = smoothstep(0.001f, 0.01f, lumPostHand);
        float V = (1.0f - lumWeight) * 1.0f + lumWeight * vClamped;

        float brightenGui = Math.max(0.0f, Math.max(
                mainColor[0] - postHandCurr[0],
                Math.max(mainColor[1] - postHandCurr[1], mainColor[2] - postHandCurr[2])
        ));
        float guiBright = smoothstep(0.02f, 0.05f, brightenGui);

        float maxDiffGui = Math.max(
                Math.abs(mainColor[0] - postHandCurr[0] * V),
                Math.max(Math.abs(mainColor[1] - postHandCurr[1] * V), Math.abs(mainColor[2] - postHandCurr[2] * V))
        );
        float guiDiff = smoothstep(0.03f, 0.07f, maxDiffGui);
        float guiFactor = clamp(Math.max(guiBright, guiDiff), 0.0f, 1.0f);

        float[] hCurr0 = sampleHandLayerAt(currPostHandPatch, currWorldPatch, width, height, x, y);
        float[] hPrev0 = sampleHandLayerAt(prevPostHandPatch, prevWorldPatch, width, height, x, y);

        float[] midHand = null;
        if (hCurr0[3] > 0.35f && hPrev0[3] > 0.35f) {
            float maxCDiff0 = Math.max(
                    Math.abs(hCurr0[0] - hPrev0[0]),
                    Math.max(Math.abs(hCurr0[1] - hPrev0[1]), Math.abs(hCurr0[2] - hPrev0[2]))
            ) + Math.abs(hCurr0[3] - hPrev0[3]);
            if (maxCDiff0 <= 0.025f) {
                midHand = new float[] {
                        (1.0f - alpha) * hCurr0[0] + alpha * hPrev0[0],
                        (1.0f - alpha) * hCurr0[1] + alpha * hPrev0[1],
                        (1.0f - alpha) * hCurr0[2] + alpha * hPrev0[2],
                        Math.max(hCurr0[3], hPrev0[3])
                };
            }
        }

        if (midHand == null) {
            int[][] searchOffsets = new int[][] {
                    { 0, 0 }, { 3, 0 }, { -3, 0 }, { 0, 3 }, { 0, -3 },
                    { 6, 0 }, { -6, 0 }, { 0, 6 }, { 0, -6 }
            };
            float bestScore = 10.0f;
            int[] bestVel = new int[] { 0, 0 };
            boolean foundSymmetric = false;

            for (int i = 0; i < searchOffsets.length; i++) {
                int[] off = searchOffsets[i];
                float xN = x + alpha * off[0];
                float yN = y + alpha * off[1];
                float[] hN = (i == 0) ? hCurr0 : sampleHandLayerAt(currPostHandPatch, currWorldPatch, width, height, Math.round(xN), Math.round(yN));
                if (hN[3] <= 0.35f) {
                    continue;
                }

                float xP = x - (1.0f - alpha) * off[0];
                float yP = y - (1.0f - alpha) * off[1];
                float[] hP = (i == 0) ? hPrev0 : sampleHandLayerAt(prevPostHandPatch, prevWorldPatch, width, height, Math.round(xP), Math.round(yP));
                if (hP[3] > 0.35f) {
                    float rgbDiff = (Math.abs(hN[0] - hP[0]) + Math.abs(hN[1] - hP[1]) + Math.abs(hN[2] - hP[2])) / 3.0f;
                    float len = (float) Math.sqrt(off[0] * off[0] + off[1] * off[1]);
                    float cost = rgbDiff + 0.004f * len;
                    if (cost < bestScore) {
                        bestScore = cost;
                        bestVel = off;
                        foundSymmetric = true;
                    }
                }
            }

            if (foundSymmetric && bestScore < 0.22f) {
                float[] winN = sampleHandLayerAt(currPostHandPatch, currWorldPatch, width, height, Math.round(x + alpha * bestVel[0]), Math.round(y + alpha * bestVel[1]));
                float[] winP = sampleHandLayerAt(prevPostHandPatch, prevWorldPatch, width, height, Math.round(x - (1.0f - alpha) * bestVel[0]), Math.round(y - (1.0f - alpha) * bestVel[1]));
                midHand = new float[] {
                        (1.0f - alpha) * winN[0] + alpha * winP[0],
                        (1.0f - alpha) * winN[1] + alpha * winP[1],
                        (1.0f - alpha) * winN[2] + alpha * winP[2],
                        clamp(0.5f * (winN[3] + winP[3]), 0.0f, 1.0f)
                };
            } else {
                midHand = new float[] { hCurr0[0], hCurr0[1], hCurr0[2], 0.0f };
            }
        }

        float[] worldPlusHand = new float[3];
        for (int c = 0; c < 3; c++) {
            float wVig = interWorld[c] * V;
            float hVig = midHand[c] * V;
            float wh = (1.0f - midHand[3]) * wVig + midHand[3] * hVig;
            worldPlusHand[c] = (1.0f - guiFactor) * wh + guiFactor * mainColor[c];
        }

        return new float[] { worldPlusHand[0], worldPlusHand[1], worldPlusHand[2], midHand[3], guiFactor };
    }

    private static float[] sampleHandLayerAt(float[][] postHandPatch, float[][] worldOnlyPatch, int width, int height, int x, int y) {
        int cx = Math.max(0, Math.min(width - 1, x));
        int cy = Math.max(0, Math.min(height - 1, y));
        float[] ph = postHandPatch[cy * width + cx];
        float[] wo = worldOnlyPatch[cy * width + cx];
        float maxD = Math.max(Math.abs(ph[0] - wo[0]), Math.max(Math.abs(ph[1] - wo[1]), Math.abs(ph[2] - wo[2])));
        float mask = smoothstep(0.012f, 0.035f, maxD);
        return new float[] { ph[0], ph[1], ph[2], mask };
    }

    private static float luminance(float[] rgb) {
        return 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2];
    }

    private static float[] samplePatchBilinear(float[][] patch, int width, int height, float x, float y) {
        float cx = clamp(x, 0.0f, width - 1.0f);
        float cy = clamp(y, 0.0f, height - 1.0f);
        int x0 = (int) Math.floor(cx);
        int y0 = (int) Math.floor(cy);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        float fx = cx - x0;
        float fy = cy - y0;

        float[] c00 = patch[y0 * width + x0];
        float[] c10 = patch[y0 * width + x1];
        float[] c01 = patch[y1 * width + x0];
        float[] c11 = patch[y1 * width + x1];

        float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            float top = c00[c] * (1.0f - fx) + c10[c] * fx;
            float bot = c01[c] * (1.0f - fx) + c11[c] * fx;
            out[c] = top * (1.0f - fy) + bot * fy;
        }
        return out;
    }

    private static float photometricCost(float[] cCurr, float[] cPrev) {
        float l1 = (Math.abs(cCurr[0] - cPrev[0]) + Math.abs(cCurr[1] - cPrev[1]) + Math.abs(cCurr[2] - cPrev[2])) / 3.0f;
        float lumCurr = 0.299f * cCurr[0] + 0.587f * cCurr[1] + 0.114f * cCurr[2];
        float lumPrev = 0.299f * cPrev[0] + 0.587f * cPrev[1] + 0.114f * cPrev[2];
        return 0.65f * l1 + 0.35f * Math.abs(lumCurr - lumPrev);
    }

    /**
     * Mathematical model of the HUD/Vignette composite fragment shader for headless verification.
     * Computes the blended RGB output and hudFactor given intermediate world, full main with HUD, and base world-only color.
     */
    public static float[] evaluateHudCompositeMath(float[] interRgb, float[] mainRgb, float[] worldRgb) {
        float lumWorld = 0.299f * worldRgb[0] + 0.587f * worldRgb[1] + 0.114f * worldRgb[2];
        float lumMain = 0.299f * mainRgb[0] + 0.587f * mainRgb[1] + 0.114f * mainRgb[2];

        float vRatio = lumMain / Math.max(lumWorld, 0.0001f);
        float vClamped = clamp(vRatio, 0.0f, 1.0f);
        float lumWeight = smoothstep(0.001f, 0.01f, lumWorld);
        float V = (1.0f - lumWeight) * 1.0f + lumWeight * vClamped;

        float[] interVignetted = new float[] {
                interRgb[0] * V,
                interRgb[1] * V,
                interRgb[2] * V
        };

        float brighten = Math.max(0.0f, Math.max(
                mainRgb[0] - worldRgb[0],
                Math.max(mainRgb[1] - worldRgb[1], mainRgb[2] - worldRgb[2])
        ));
        float hudBright = smoothstep(0.02f, 0.05f, brighten);

        float[] expectedVignette = new float[] {
                worldRgb[0] * V,
                worldRgb[1] * V,
                worldRgb[2] * V
        };
        float maxDiffV = Math.max(
                Math.abs(mainRgb[0] - expectedVignette[0]),
                Math.max(Math.abs(mainRgb[1] - expectedVignette[1]), Math.abs(mainRgb[2] - expectedVignette[2]))
        );
        float hudDiff = smoothstep(0.03f, 0.07f, maxDiffV);

        float hudFactor = clamp(Math.max(hudBright, hudDiff), 0.0f, 1.0f);

        return new float[] {
                (1.0f - hudFactor) * interVignetted[0] + hudFactor * mainRgb[0],
                (1.0f - hudFactor) * interVignetted[1] + hudFactor * mainRgb[1],
                (1.0f - hudFactor) * interVignetted[2] + hudFactor * mainRgb[2],
                hudFactor
        };
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
