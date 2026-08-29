#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D CurrentColorSampler;
uniform sampler2D PreviousColorSampler;
uniform sampler2D CurrentDepthSampler;
uniform sampler2D PreviousDepthSampler;

uniform mat4 ReprojectionMatrix;
uniform vec2 ScreenSize;
uniform float BlendFactor;
uniform float DepthDilationRadius;

void main() {
    vec2 texelSize = 1.0 / ScreenSize;

    // 1. Depth sampling with 3x3 dilation (pick closest depth to prevent foreground ghosting)
    float closestDepth = texture(CurrentDepthSampler, texCoord).r;

    // Fast-path: Sky / infinite background in Reverse-Z is depth 0.0 (requires no reprojection)
    if (closestDepth <= 0.0001) {
        fragColor = texture(CurrentColorSampler, texCoord);
        return;
    }

    if (DepthDilationRadius > 0.0) {
        for (int y = -1; y <= 1; ++y) {
            for (int x = -1; x <= 1; ++x) {
                vec2 offset = vec2(float(x), float(y)) * texelSize * DepthDilationRadius;
                float d = texture(CurrentDepthSampler, texCoord + offset).r;
                // In Reverse-Z, closer foreground objects have LARGER depth values
                closestDepth = max(closestDepth, d);
            }
        }
    }

    // 2. Unproject from NDC using the precomputed ReprojectionMatrix (PrevVP * CurrInvVP)
    // Reverse-Z: depth 1.0 (near) -> zNDC -1.0, depth 0.0 (far) -> zNDC +1.0
    float zNDC = 1.0 - closestDepth * 2.0;
    vec4 currClip = vec4(texCoord * 2.0 - 1.0, zNDC, 1.0);

    vec4 prevClip = ReprojectionMatrix * currClip;
    vec3 prevNDC = prevClip.xyz / max(prevClip.w, 0.00001);
    vec2 prevUV = prevNDC.xy * 0.5 + 0.5;

    // 3. Current center color & 3x3 color neighborhood AABB clamping
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

    // 4. Sample previous frame and clamp
    bool validUV = (prevUV.x >= 0.0 && prevUV.x <= 1.0 && prevUV.y >= 0.0 && prevUV.y <= 1.0 && prevClip.w > 0.0);
    vec4 prevColor;

    if (validUV) {
        vec4 rawPrev = texture(PreviousColorSampler, prevUV);
        prevColor = clamp(rawPrev, minColor, maxColor);
    } else {
        prevColor = currColor;
    }

    // 5. Synthesize intermediate frame (t = BlendFactor)
    fragColor = mix(currColor, prevColor, BlendFactor);
}
