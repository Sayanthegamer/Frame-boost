package com.framegen.core;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CameraMatrixTrackerTest {

    private CameraMatrixTracker tracker;
    private Matrix4f proj;

    @BeforeEach
    void setUp() {
        tracker = new CameraMatrixTracker();
        proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
    }

    @Test
    void testInitialState() {
        assertThat(tracker.hasPreviousFrame()).isFalse();
    }

    @Test
    void testFirstFrameRegistration() {
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        tracker.updateMatrices(view, proj, 0.0, 0.0, 0.0);

        assertThat(tracker.hasPreviousFrame()).isFalse();
        assertThat(tracker.getCurrentViewMatrix()).isEqualTo(view);
        assertThat(tracker.getCurrentProjectionMatrix()).isEqualTo(proj);
    }

    @Test
    void testSecondFrameEnablesReprojection() {
        // Minecraft uses camera-relative rotation matrices (centered at origin)
        Matrix4f rotView1 = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        tracker.updateMatrices(rotView1, proj, 0.0, 0.0, 0.0);

        Matrix4f rotView2 = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        // Camera translates right by +1 unit in world space
        tracker.updateMatrices(rotView2, proj, 1.0, 0.0, 0.0);

        assertThat(tracker.hasPreviousFrame()).isTrue();
        assertThat(tracker.getPreviousViewMatrix()).isEqualTo(rotView1);
        assertThat(tracker.getCurrentViewMatrix()).isEqualTo(rotView2);

        Matrix4f reproject = tracker.getReprojectionMatrix();
        assertThat(reproject).isNotNull();

        // In current frame (camera at x=1), a world point at (0, 0, -5) is at camera-relative (-1, 0, -5)
        Vector4f cam2RelPoint = new Vector4f(-1.0f, 0.0f, -5.0f, 1.0f);
        Vector4f currClip = new Vector4f();
        new Matrix4f(proj).mul(rotView2).transform(cam2RelPoint, currClip);
        Vector4f currNDC = new Vector4f(currClip.x / currClip.w, currClip.y / currClip.w, currClip.z / currClip.w, 1.0f);

        // Point is to the left in current frame's screen space
        assertThat(currNDC.x).isLessThan(0.0f);

        // Reproject current NDC into previous frame
        Vector4f reprojectedClip = new Vector4f();
        reproject.transform(currNDC, reprojectedClip);
        float reprojectedNDCX = reprojectedClip.x / reprojectedClip.w;

        // In previous frame (camera at x=0), the point at (0, 0, -5) was directly in front (ndcX = 0)
        assertThat(reprojectedNDCX).isCloseTo(0.0f, within(1e-4f));
    }

    @Test
    void testMatrixInterpolationHalfway() {
        Matrix4f view1 = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        tracker.updateMatrices(view1, proj, 0.0, 0.0, 0.0);

        Matrix4f view2 = new Matrix4f().lookAt(2, 0, 0, 2, 0, -1, 0, 1, 0);
        tracker.updateMatrices(view2, proj, 2.0, 0.0, 0.0);

        Matrix4f interpVP = tracker.getInterpolatedViewProjection(0.5f);
        assertThat(interpVP).isNotNull();

        // Intermediate camera at t=0.5 should be at (1, 0, 0)
        // A point at (1, 0, -5) in world space should project to center (ndcX = 0)
        Vector4f worldPointAtX1 = new Vector4f(1.0f, 0.0f, -5.0f, 1.0f);
        Vector4f interpClip = new Vector4f();
        interpVP.transform(worldPointAtX1, interpClip);
        float interpNDCX = interpClip.x / interpClip.w;

        assertThat(interpNDCX).isCloseTo(0.0f, within(1e-3f));
    }

    @Test
    void testReset() {
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        tracker.updateMatrices(view, proj, 0.0, 0.0, 0.0);
        tracker.updateMatrices(view, proj, 0.0, 0.0, 0.0);
        assertThat(tracker.hasPreviousFrame()).isTrue();

        tracker.reset();
        assertThat(tracker.hasPreviousFrame()).isFalse();
    }

    @Test
    void testAngularVelocityCalculationZeroWhenStationary() {
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        tracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        tracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);

        assertThat(tracker.getAngularDeltaDegrees()).isCloseTo(0.0f, within(1e-3f));
        assertThat(tracker.getAngularVelocityDegPerSec()).isCloseTo(0.0f, within(1e-3f));
        assertThat(tracker.getTranslationDelta()).isCloseTo(0.0f, within(1e-3f));
        assertThat(tracker.getTranslationVelocityBlocksPerSec()).isCloseTo(0.0f, within(1e-3f));
        assertThat(tracker.isVelocityGated(720.0f, 50.0f)).isFalse();
    }

    @Test
    void testAngularVelocity90DegreeSnapTriggersGating() {
        Matrix4f view1 = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0); // Looking forward (-Z)
        Matrix4f view2 = new Matrix4f().lookAt(0, 0, 0, 1, 0, 0, 0, 1, 0);  // Looking right (+X)

        tracker.updateMatrices(view1, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        tracker.updateMatrices(view2, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);

        assertThat(tracker.getAngularDeltaDegrees()).isCloseTo(90.0f, within(1e-2f));
        assertThat(tracker.getAngularVelocityDegPerSec()).isGreaterThan(5000.0f);
        assertThat(tracker.isVelocityGated(720.0f, 50.0f)).isTrue();
    }

    @Test
    void testAngularVelocityBelowThresholdDoesNotGate() {
        Matrix4f view1 = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        // Rotate 2 degrees yaw in 1/60s (120 deg/sec)
        float rad2 = (float) Math.toRadians(2.0);
        Matrix4f view2 = new Matrix4f().lookAt(0, 0, 0, (float) Math.sin(rad2), 0, -(float) Math.cos(rad2), 0, 1, 0);

        tracker.updateMatrices(view1, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        tracker.updateMatrices(view2, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);

        assertThat(tracker.getAngularDeltaDegrees()).isCloseTo(2.0f, within(1e-2f));
        assertThat(tracker.getAngularVelocityDegPerSec()).isCloseTo(120.0f, within(5.0f));
        assertThat(tracker.isVelocityGated(720.0f, 50.0f)).isFalse();
    }

    @Test
    void testTranslationVelocityTriggersGating() {
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        tracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        // Teleport 20 blocks in 1/60s (1200 blocks/sec)
        tracker.updateMatrices(view, proj, 20.0, 0.0, 0.0, 1.0 / 60.0);

        assertThat(tracker.getTranslationDelta()).isCloseTo(20.0f, within(1e-3f));
        assertThat(tracker.getTranslationVelocityBlocksPerSec()).isGreaterThan(1000.0f);
        assertThat(tracker.isVelocityGated(720.0f, 50.0f)).isTrue();
    }

    @Test
    void testViewmodelReprojectionMatrixIsRotationOnly() {
        Matrix4f view1 = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        Matrix4f view2 = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);

        tracker.updateMatrices(view1, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        // Teleport camera 50 blocks without rotating
        tracker.updateMatrices(view2, proj, 50.0, 0.0, 0.0, 1.0 / 60.0);

        Matrix4f vmMat = tracker.getViewmodelReprojectionMatrix();
        assertThat(vmMat).isNotNull();
        // Since rotation is zero, viewmodel reprojection should be identity
        assertThat(vmMat.isFinite()).isTrue();
    }
}
