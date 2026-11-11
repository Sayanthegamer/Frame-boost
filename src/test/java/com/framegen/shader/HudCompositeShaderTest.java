package com.framegen.shader;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HudCompositeShaderTest {

    @Test
    void testVignetteSmoothGraduationNoOutlineDiscontinuity() {
        // Rainy sky color
        float[] worldColor = new float[] { 0.20f, 0.25f, 0.35f };
        float[] interColor = new float[] { 0.21f, 0.26f, 0.36f };

        float prevBrightness = -1.0f;

        // Sweep vignette factor V from 1.0 (center) down to 0.4 (dark corners) in fine steps
        for (int step = 0; step <= 120; step++) {
            float V = 1.0f - (step / 120.0f) * 0.6f; // V in [0.4, 1.0]
            float[] mainColor = new float[] { worldColor[0] * V, worldColor[1] * V, worldColor[2] * V };

            float[] result = ReprojectionShaderPass.evaluateHudCompositeMath(interColor, mainColor, worldColor);
            float hudFactor = result[3];

            // 1. Invariant: Vignette darkening must NEVER be classified as HUD
            assertThat(hudFactor)
                    .as("Vignette at V=%.3f must not be detected as HUD", V)
                    .isLessThan(0.01f);

            // 2. Invariant: Output must closely track interColor * V
            float expectedR = interColor[0] * V;
            float expectedG = interColor[1] * V;
            float expectedB = interColor[2] * V;
            assertThat(result[0]).isCloseTo(expectedR, within(0.005f));
            assertThat(result[1]).isCloseTo(expectedG, within(0.005f));
            assertThat(result[2]).isCloseTo(expectedB, within(0.005f));

            // 3. Continuity Invariant: Step-to-step brightness difference must be smooth and bounded (< 0.01)
            float brightness = 0.299f * result[0] + 0.587f * result[1] + 0.114f * result[2];
            if (prevBrightness >= 0.0f) {
                float stepDelta = Math.abs(brightness - prevBrightness);
                assertThat(stepDelta)
                        .as("Step delta at V=%.3f must be smooth with zero jump discontinuity (no outline artifact)", V)
                        .isLessThan(0.01f);
            }
            prevBrightness = brightness;
        }
    }

    @Test
    void testWhiteCrosshairDetected() {
        float[] worldColor = new float[] { 0.3f, 0.3f, 0.3f };
        float[] interColor = new float[] { 0.3f, 0.3f, 0.3f };
        float[] mainColor = new float[] { 1.0f, 1.0f, 1.0f }; // White crosshair

        float[] result = ReprojectionShaderPass.evaluateHudCompositeMath(interColor, mainColor, worldColor);
        float hudFactor = result[3];

        assertThat(hudFactor).isGreaterThan(0.95f);
        assertThat(result[0]).isCloseTo(1.0f, within(0.05f));
        assertThat(result[1]).isCloseTo(1.0f, within(0.05f));
        assertThat(result[2]).isCloseTo(1.0f, within(0.05f));
    }

    @Test
    void testRedHeartDetected() {
        float[] worldColor = new float[] { 0.2f, 0.5f, 0.1f }; // Green foliage
        float[] interColor = new float[] { 0.2f, 0.5f, 0.1f };
        float[] mainColor = new float[] { 0.9f, 0.1f, 0.1f }; // Red heart icon

        float[] result = ReprojectionShaderPass.evaluateHudCompositeMath(interColor, mainColor, worldColor);
        float hudFactor = result[3];

        assertThat(hudFactor).isGreaterThan(0.95f);
        assertThat(result[0]).isCloseTo(0.9f, within(0.05f));
        assertThat(result[1]).isCloseTo(0.1f, within(0.05f));
        assertThat(result[2]).isCloseTo(0.1f, within(0.05f));
    }

    @Test
    void testPitchBlackCaveContinuity() {
        float[] worldColor = new float[] { 0.0f, 0.0f, 0.0f };
        float[] mainColor = new float[] { 0.0f, 0.0f, 0.0f };
        float[] interColor = new float[] { 0.05f, 0.05f, 0.05f };

        float[] result = ReprojectionShaderPass.evaluateHudCompositeMath(interColor, mainColor, worldColor);
        float hudFactor = result[3];

        assertThat(hudFactor).isEqualTo(0.0f);
        assertThat(result[0]).isCloseTo(interColor[0], within(0.001f));
        assertThat(result[1]).isCloseTo(interColor[1], within(0.001f));
        assertThat(result[2]).isCloseTo(interColor[2], within(0.001f));
    }

    @Test
    void testThreeLayerMidpointHandInterpolationMovesHandWithoutGhosting() {
        int w = 17;
        int h = 17;
        float[][] interWorld = new float[w * h][3];
        float[][] currWorld = new float[w * h][3];
        float[][] prevWorld = new float[w * h][3];
        float[][] currPostHand = new float[w * h][3];
        float[][] prevPostHand = new float[w * h][3];
        float[][] mainWithGui = new float[w * h][3];

        // Fill background world with sky blue (0.20, 0.45, 0.85)
        for (int i = 0; i < w * h; i++) {
            interWorld[i] = new float[] { 0.20f, 0.45f, 0.85f };
            currWorld[i] = new float[] { 0.20f, 0.45f, 0.85f };
            prevWorld[i] = new float[] { 0.20f, 0.45f, 0.85f };
            currPostHand[i] = new float[] { 0.20f, 0.45f, 0.85f };
            prevPostHand[i] = new float[] { 0.20f, 0.45f, 0.85f };
            mainWithGui[i] = new float[] { 0.20f, 0.45f, 0.85f };
        }

        // First-person hand (skin tone 0.85, 0.60, 0.45) swings left by -6 pixels:
        // In Frame N-1 (prevPostHand): centered at x = 11, y = 8 (3x3 block)
        // In Frame N   (currPostHand & mainWithGui): centered at x = 5, y = 8 (3x3 block)
        // Expected T=0.5 midpoint hand position: centered at x = 8, y = 8!
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                prevPostHand[(8 + dy) * w + (11 + dx)] = new float[] { 0.85f, 0.60f, 0.45f };
                currPostHand[(8 + dy) * w + (5 + dx)] = new float[] { 0.85f, 0.60f, 0.45f };
                mainWithGui[(8 + dy) * w + (5 + dx)] = new float[] { 0.85f, 0.60f, 0.45f };
            }
        }

        // 1. At midpoint (8, 8): 3-layer composite synthesizes the hand (0.85, 0.60, 0.45) at T=0.5!
        float[] midResult = ReprojectionShaderPass.evaluateThreeLayerHudAndHandComposite(
                interWorld, currWorld, prevWorld, currPostHand, prevPostHand, mainWithGui, w, h, 8, 8, 0.5f
        );
        assertThat(midResult[3]).isGreaterThan(0.9f); // midHandMask ~ 1.0
        assertThat(midResult[4]).isLessThan(0.05f);   // guiFactor ~ 0.0 (hand is NOT treated as 2D GUI!)
        assertThat(midResult[0]).isCloseTo(0.85f, within(0.05f));
        assertThat(midResult[1]).isCloseTo(0.60f, within(0.05f));

        // 2. At Frame N's leading hand position (5, 8): since the hand moved from x=11, at T=0.5 it is at x=8,
        // so (5, 8) exposes the unoccluded T=0.5 sky background (0.20, 0.45, 0.85) with zero double-image!
        float[] vacatedResult = ReprojectionShaderPass.evaluateThreeLayerHudAndHandComposite(
                interWorld, currWorld, prevWorld, currPostHand, prevPostHand, mainWithGui, w, h, 5, 8, 0.5f
        );
        assertThat(vacatedResult[3]).isCloseTo(0.0f, within(0.01f)); // midHandMask == 0.0
        assertThat(vacatedResult[0]).isCloseTo(0.20f, within(0.02f));
        assertThat(vacatedResult[2]).isCloseTo(0.85f, within(0.02f));
    }

    @Test
    void testHudCompositeShaderResourceAvailable() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/assets/framegen/shaders/hud_composite.fsh")) {
            assertThat(in).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("#version 330 core");
            assertThat(content).contains("uniform sampler2D IntermediateSampler;");
            assertThat(content).contains("uniform sampler2D MainColorSampler;");
            assertThat(content).contains("uniform sampler2D WorldOnlySampler;");
            assertThat(content).contains("uniform sampler2D CurrPostHandSampler;");
            assertThat(content).contains("uniform sampler2D PrevPostHandSampler;");
            assertThat(content).contains("synthesizeMidpointHand");
            assertThat(content).contains("vRatio");
            assertThat(content).contains("smoothstep");
        }
    }

    @Test
    void testReprojectShaderResourceSkyDivergenceAndOpaqueAlpha() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/assets/framegen/shaders/reproject.fsh")) {
            assertThat(in).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("#version 330 core");
            assertThat(content).contains("vec3 diff = abs(colCurr.rgb - colPrev.rgb);");
            assertThat(content).contains("fragColor = vec4(mix(colCurr.rgb, colPrev.rgb, wPrev), 1.0);");
        }
    }
}
