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
            // Disoccluded geometry: clean fallback to current color
            prevColor = currColor;
        }
    } else {
        // Out of viewport bounds: Strict sub-pixel rejection (no streak dragging or barcode stretching)
        prevColor = currColor;
    }

    // 4. Synthesize intermediate frame (t = BlendFactor)
    fragColor = mix(currColor, prevColor, BlendFactor);
}
