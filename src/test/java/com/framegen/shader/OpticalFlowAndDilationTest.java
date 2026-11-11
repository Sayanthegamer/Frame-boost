package com.framegen.shader;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OpticalFlowAndDilationTest {

    @Test
    void testReverseZDepthDilationSelectsForegroundMaxDepth() {
        // In Reverse-Z (GL_ZERO_TO_ONE), 1.0 = near plane, 0.0 = far sky.
        // Center texel (index 4) hits background (0.02), while neighbor texel (index 5) hits foreground fence/limb (0.65).
        float[] depthGrid = new float[] {
                0.02f, 0.02f, 0.02f,
                0.02f, 0.02f, 0.65f,
                0.02f, 0.02f, 0.10f
        };

        // When dilationRadius == 0.0, returns un-dilated center depth (0.02)
        float undilated = ReprojectionShaderPass.evaluateDilatedDepth3x3(depthGrid, 0.0f);
        assertThat(undilated).isCloseTo(0.02f, within(1e-5f));

        // When dilationRadius == 1.0, returns foreground max depth (0.65)
        float dilated = ReprojectionShaderPass.evaluateDilatedDepth3x3(depthGrid, 1.0f);
        assertThat(dilated).isCloseTo(0.65f, within(1e-5f));
    }

    @Test
    void testSymmetricOpticalFlowRecoversMovingParticleAtMidpointInBothPerfAndQualityModes() {
        int width = 17;
        int height = 17;
        float[][] prevPatch = new float[width * height][3];
        float[][] currPatch = new float[width * height][3];

        // Fill background with dark stone (0.15, 0.15, 0.18)
        for (int i = 0; i < width * height; i++) {
            prevPatch[i] = new float[] { 0.15f, 0.15f, 0.18f };
            currPatch[i] = new float[] { 0.15f, 0.15f, 0.18f };
        }

        // Bright torch/entity feature (0.95, 0.80, 0.30) moves right by +4 pixels:
        // In Frame N-1 (prevPatch), it is centered at x = 6, y = 8 (3x3 block).
        // In Frame N   (currPatch), it is centered at x = 10, y = 8 (3x3 block).
        // True midpoint at T = 0.5 is centerX = 8, centerY = 8, with true flow du = +4.0, dv = 0.0.
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                prevPatch[(8 + dy) * width + (6 + dx)] = new float[] { 0.95f, 0.80f, 0.30f };
                currPatch[(8 + dy) * width + (10 + dx)] = new float[] { 0.95f, 0.80f, 0.30f };
            }
        }

        // Evaluate at midpoint (8, 8) with searchRadius = 0 (disabled):
        float[] noFlow = ReprojectionShaderPass.evaluateOpticalFlowSynthesis(
                currPatch, prevPatch, width, height, 8, 8, 0.0f, 0.0f, 0.5f, 0
        );
        assertThat(noFlow[0]).isCloseTo(0.15f, within(0.01f));

        // 1. Performance Mode (FLOW_QUALITY_PERFORMANCE = 1, 4-tap cardinal diamond + LK):
        float[] perfFlow = ReprojectionShaderPass.evaluateOpticalFlowSynthesis(
                currPatch, prevPatch, width, height, 8, 8, 0.0f, 0.0f, 0.5f, 4,
                ShaderConstants.FLOW_QUALITY_PERFORMANCE
        );
        assertThat(perfFlow[3]).isCloseTo(4.0f, within(1.0f));
        assertThat(perfFlow[0]).isGreaterThan(0.85f);
        assertThat(perfFlow[5]).isLessThan(0.05f);

        // 2. Quality Mode (FLOW_QUALITY_ULTRA = 2, 12-tap coarse+fine diamond + LK):
        float[] qualFlow = ReprojectionShaderPass.evaluateOpticalFlowSynthesis(
                currPatch, prevPatch, width, height, 8, 8, 0.0f, 0.0f, 0.5f, 4,
                ShaderConstants.FLOW_QUALITY_ULTRA
        );
        assertThat(qualFlow[3]).isCloseTo(4.0f, within(1.0f));
        assertThat(qualFlow[0]).isGreaterThan(0.85f);
        assertThat(qualFlow[1]).isGreaterThan(0.70f);
        assertThat(qualFlow[5]).isLessThan(0.05f);
    }

    @Test
    void testStaticEdgeDoesNotTriggerFalseOpticalFlow() {
        int width = 17;
        int height = 17;
        float[][] prevPatch = new float[width * height][3];
        float[][] currPatch = new float[width * height][3];

        // Static vertical wall edge: left half (x < 8) is dark (0.15), right half (x >= 8) is bright (0.90) in BOTH frames
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float val = (x < 8) ? 0.15f : 0.90f;
                prevPatch[y * width + x] = new float[] { val, val, val };
                currPatch[y * width + x] = new float[] { val, val, val };
            }
        }

        float[] result = ReprojectionShaderPass.evaluateOpticalFlowSynthesis(
                currPatch, prevPatch, width, height, 8, 8, 0.0f, 0.0f, 0.5f, 4
        );

        // Static edge must keep zero displacement (du = 0, dv = 0)
        assertThat(result[3]).isCloseTo(0.0f, within(0.05f));
        assertThat(result[4]).isCloseTo(0.0f, within(0.05f));
        assertThat(result[0]).isCloseTo(0.90f, within(0.02f));
    }

    @Test
    void testStationaryNoisyFoliageTextureProducesZeroFlowAndZeroShimmer() {
        int width = 17;
        int height = 17;
        float[][] prevPatch = new float[width * height][3];
        float[][] currPatch = new float[width * height][3];

        // High-contrast noisy jungle leaf texture (alternating dark green 0.12 and bright green 0.55)
        // that is 100% stationary between Frame N-1 and Frame N (prevPatch == currPatch everywhere).
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean brightLeaf = ((x + y) % 2 == 0);
                float r = brightLeaf ? 0.22f : 0.06f;
                float g = brightLeaf ? 0.58f : 0.18f;
                float b = brightLeaf ? 0.14f : 0.04f;
                prevPatch[y * width + x] = new float[] { r, g, b };
                currPatch[y * width + x] = new float[] { r, g, b };
            }
        }

        float[] centerExpected = currPatch[8 * width + 8];
        float[] result = ReprojectionShaderPass.evaluateOpticalFlowSynthesis(
                currPatch, prevPatch, width, height, 8, 8, 0.0f, 0.0f, 0.5f, 4
        );

        assertThat(result[3]).isEqualTo(0.0f);
        assertThat(result[4]).isEqualTo(0.0f);
        assertThat(result[0]).isCloseTo(centerExpected[0], within(1e-5f));
        assertThat(result[1]).isCloseTo(centerExpected[1], within(1e-5f));
        assertThat(result[2]).isCloseTo(centerExpected[2], within(1e-5f));
    }

    @Test
    void testOccludedEntityBehindForegroundTreeIsRejectedByReverseZDepthCheck() {
        // In Reverse-Z (1.0 = near, 0.0 = far):
        // Foreground jungle tree leaf is at pixelDepth = 0.08 (near).
        // Underground cave mob is at entityDepth = 0.01 (farther away behind the tree/ground).
        assertThat(ReprojectionShaderPass.isEntityVelocityVisible(0.08f, 0.01f, 1.0f)).isFalse();

        // Sky pixel (pixelDepth = 0.0) never accepts entity velocity quads
        assertThat(ReprojectionShaderPass.isEntityVelocityVisible(0.0f, 0.05f, 1.0f)).isFalse();

        // Unoccluded visible mob where scene depth matches entity depth (0.05 vs 0.05) is accepted
        assertThat(ReprojectionShaderPass.isEntityVelocityVisible(0.05f, 0.05f, 1.0f)).isTrue();
    }

    @Test
    void testNeighborhoodVarianceClampingSuppressesDisocclusionGhosting() {
        int width = 9;
        int height = 9;
        float[][] prevPatch = new float[width * height][3];
        float[][] currPatch = new float[width * height][3];

        for (int i = 0; i < width * height; i++) {
            prevPatch[i] = new float[] { 1.0f, 1.0f, 1.0f };
            currPatch[i] = new float[] { 0.10f, 0.10f, 0.10f };
        }

        float[] result = ReprojectionShaderPass.evaluateOpticalFlowSynthesis(
                currPatch, prevPatch, width, height, 4, 4, 0.0f, 0.0f, 0.5f, 3
        );

        assertThat(result[0]).isLessThan(0.15f);
        assertThat(result[1]).isLessThan(0.15f);
        assertThat(result[2]).isLessThan(0.15f);
    }

    @Test
    void testReprojectShaderContainsPhase1AndPhase2Functions() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/assets/framegen/shaders/reproject.fsh")) {
            assertThat(in).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("uniform int uOpticalFlowEnabled;");
            assertThat(content).contains("uniform int uOpticalFlowQuality;");
            assertThat(content).contains("uniform float uOpticalFlowRadius;");
            assertThat(content).contains("uniform float uEntityVelMaxUV;");
            assertThat(content).contains("sampleDilatedDepth");
            assertThat(content).contains("sampleEntityVelocity");
            assertThat(content).contains("computeSymmetricFlowOffset");
            assertThat(content).contains("clampToNeighborhoodAABB");
            assertThat(content).contains("temporalSalience");
        }
    }
}
