#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D CurrentColorSampler;
uniform sampler2D PreviousColorSampler;
uniform sampler2D CurrentDepthSampler;
uniform sampler2D PreviousDepthSampler;
uniform sampler2D EntityMaskSampler;

uniform mat4 ReprojectionMatrix;
uniform mat4 ViewModelReprojectionMatrix;
uniform vec2 ScreenSize;
uniform float BlendFactor;
uniform float DepthDilationRadius;

void main() {
    vec2 texelSize = 1.0 / ScreenSize;
    float closestDepth = texture(CurrentDepthSampler, texCoord).r;

    // Fast-path: Sky / infinite background in Reverse-Z is depth 0.0 (requires no reprojection)
    if (closestDepth <= 0.0001) {
        fragColor = texture(CurrentColorSampler, texCoord);
        return;
    }

    vec4 maskSample = texture(EntityMaskSampler, texCoord);
    // In Minecraft Reverse-Z, viewmodel hand is in the extreme foreground (depth > 0.95 or mask.g > 0.5)
    bool isViewmodel = (maskSample.g > 0.5) || (maskSample.r > 0.5 && closestDepth > 0.95);
    bool isDynamicMob = (maskSample.r > 0.5 && !isViewmodel);

    // 0. Dynamic World Entities: Pass through at base framerate (avoids heavy optical flow and maintains Iris compatibility)
    if (isDynamicMob) {
        fragColor = texture(CurrentColorSampler, texCoord);
        return;
    }

    if (!isViewmodel && DepthDilationRadius > 0.0) {
        for (int y = -1; y <= 1; ++y) {
            for (int x = -1; x <= 1; ++x) {
                vec2 offset = vec2(float(x), float(y)) * texelSize * DepthDilationRadius;
                float d = texture(CurrentDepthSampler, texCoord + offset).r;
                closestDepth = max(closestDepth, d);
            }
        }
    }

    // 1. Unproject from NDC
    // Reverse-Z: depth 1.0 (near) -> zNDC -1.0, depth 0.0 (far) -> zNDC +1.0
    float zNDC = 1.0 - closestDepth * 2.0;
    vec4 currClip = vec4(texCoord * 2.0 - 1.0, zNDC, 1.0);

    // Select dedicated viewmodel rotation matrix vs world matrix
    mat4 activeReproj = isViewmodel ? ViewModelReprojectionMatrix : ReprojectionMatrix;
    vec4 prevClip = activeReproj * currClip;
    vec3 prevNDC = prevClip.xyz / max(prevClip.w, 0.00001);
    vec2 prevUV = prevNDC.xy * 0.5 + 0.5;

    // 2. Current center color & 3x3 color neighborhood AABB clamping
    vec4 currColor = texture(CurrentColorSampler, texCoord);
    vec4 minColor = currColor;
    vec4 maxColor = currColor;

    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            vec2 offset = vec2(float(x), float(y)) * texelSize;
            vec4 neighbor = texture(CurrentColorSampler, texCoord + offset);
            minColor = min(minColor, neighbor);
            maxColor = max(maxColor, neighbor);
        }
    }

    // 3. Sample previous frame with strict sub-pixel border rejection & depth disocclusion validation
    bool validUV = (prevUV.x >= 0.0 && prevUV.x <= 1.0 && prevUV.y >= 0.0 && prevUV.y <= 1.0 && prevClip.w > 0.0);
    vec4 prevColor = currColor;

    if (validUV) {
        float actualPrevDepth = texture(PreviousDepthSampler, prevUV).r;
        float expectedPrevDepth = (1.0 - prevNDC.z) * 0.5;

        // Depth discrepancy test in Reverse-Z space
        float depthDiff = abs(actualPrevDepth - expectedPrevDepth);
        float depthThreshold = isViewmodel ? 0.08 : max(0.02 * max(expectedPrevDepth, 0.001), 0.005);

        if (depthDiff <= depthThreshold) {
            vec4 rawPrev = texture(PreviousColorSampler, prevUV);
            prevColor = clamp(rawPrev, minColor, maxColor);
        } else {
            // Parallax Disocclusion: True Background Epipolar Line Projection
            // Compute motion vector at background depth (actualPrevDepth) to align search on true background trajectory
            float zNDC_bg = 1.0 - actualPrevDepth * 2.0;
            vec4 bgClip = vec4(texCoord * 2.0 - 1.0, zNDC_bg, 1.0);
            vec4 prevBgClip = ReprojectionMatrix * bgClip;
            vec2 prevBgUV = (prevBgClip.xy / max(prevBgClip.w, 0.00001)) * 0.5 + 0.5;

            vec2 bgMotionVec = prevBgUV - texCoord;
            float bgMotionLen = length(bgMotionVec);
            vec2 bgMotionDir = bgMotionLen > 0.0001 ? bgMotionVec / bgMotionLen : vec2(1.0, 0.0);

            // Scale search along background epipolar trajectory
            float parallaxScale = max(bgMotionLen * 1.5, length(texelSize) * 2.0) * max(DepthDilationRadius, 1.0);

            vec2 uv0 = clamp(prevBgUV + bgMotionDir * (parallaxScale * 0.5), 0.0, 1.0);
            vec2 uv1 = clamp(prevBgUV + bgMotionDir * (parallaxScale * 1.0), 0.0, 1.0);
            vec2 uv2 = clamp(prevBgUV + bgMotionDir * (parallaxScale * 2.0), 0.0, 1.0);
            vec2 uv3 = clamp(prevBgUV - bgMotionDir * (parallaxScale * 0.5), 0.0, 1.0);

            float d0 = texture(PreviousDepthSampler, uv0).r;
            float d1 = texture(PreviousDepthSampler, uv1).r;
            float d2 = texture(PreviousDepthSampler, uv2).r;
            float d3 = texture(PreviousDepthSampler, uv3).r;

            float invTwoSigmaSq = 1.0 / (2.0 * max(depthThreshold * depthThreshold * 2.25, 0.0001));
            float diff0 = d0 - actualPrevDepth;
            float diff1 = d1 - actualPrevDepth;
            float diff2 = d2 - actualPrevDepth;
            float diff3 = d3 - actualPrevDepth;

            float w0 = exp(-(diff0 * diff0) * invTwoSigmaSq);
            float w1 = exp(-(diff1 * diff1) * invTwoSigmaSq);
            float w2 = exp(-(diff2 * diff2) * invTwoSigmaSq);
            float w3 = exp(-(diff3 * diff3) * invTwoSigmaSq);

            vec4 c0 = texture(PreviousColorSampler, uv0);
            vec4 c1 = texture(PreviousColorSampler, uv1);
            vec4 c2 = texture(PreviousColorSampler, uv2);
            vec4 c3 = texture(PreviousColorSampler, uv3);

            float sumWeights = w0 + w1 + w2 + w3;
            vec4 infilledColor = (c0 * w0 + c1 * w1 + c2 * w2 + c3 * w3) / max(sumWeights, 0.0001);

            // Continuous Parallax Cosine Phase Damping: Smoothly blend confidence without box-blur smudging
            float confidence = clamp(sumWeights * 0.5, 0.0, 1.0);
            float cosineWeight = 0.5 - 0.5 * cos(3.14159265 * confidence);
            vec4 blendedPrev = mix(currColor, infilledColor, cosineWeight);
            prevColor = clamp(blendedPrev, minColor, maxColor);
        }
    } else {
        // Out of viewport bounds: Strict sub-pixel rejection (no streak dragging or barcode stretching)
        prevColor = currColor;
    }

    // 4. Synthesize intermediate frame (t = BlendFactor)
    fragColor = mix(currColor, prevColor, BlendFactor);
}
