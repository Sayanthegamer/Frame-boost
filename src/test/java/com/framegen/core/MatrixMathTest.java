package com.framegen.core;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MatrixMathTest {

    @Test
    void testUnprojectAndReprojectIdentity() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);

        Matrix4f vp = new Matrix4f(proj).mul(view);
        Matrix4f invVP = new Matrix4f(vp).invert();

        // Screen center NDC (0, 0) at a depth corresponding to z = -10
        Vector4f worldTarget = new Vector4f(0, 0, -10.0f, 1.0f);
        Vector4f clipTarget = new Vector4f();
        vp.transform(worldTarget, clipTarget);
        
        float ndcX = clipTarget.x / clipTarget.w;
        float ndcY = clipTarget.y / clipTarget.w;
        float ndcZ = clipTarget.z / clipTarget.w;

        // Unproject
        Vector4f unprojectedClip = new Vector4f(ndcX, ndcY, ndcZ, 1.0f);
        Vector4f unprojectedWorld = new Vector4f();
        invVP.transform(unprojectedClip, unprojectedWorld);
        unprojectedWorld.div(unprojectedWorld.w);

        assertThat(unprojectedWorld.x).isCloseTo(0.0f, within(1e-4f));
        assertThat(unprojectedWorld.y).isCloseTo(0.0f, within(1e-4f));
        assertThat(unprojectedWorld.z).isCloseTo(-10.0f, within(1e-4f));
    }

    @Test
    void testCameraMotionReprojection() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
        
        // Camera at frame N-1: at (0, 0, 0)
        Matrix4f prevView = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        Matrix4f prevVP = new Matrix4f(proj).mul(prevView);

        // Camera at frame N: translated right by +1 unit to (1, 0, 0)
        Matrix4f currView = new Matrix4f().lookAt(1, 0, 0, 1, 0, -1, 0, 1, 0);
        Matrix4f currVP = new Matrix4f(proj).mul(currView);
        Matrix4f currInvVP = new Matrix4f(currVP).invert();

        // Reprojection Matrix = PrevVP * CurrInvVP
        Matrix4f reprojectMatrix = new Matrix4f(prevVP).mul(currInvVP);

        // Point at (0, 0, -5) in world space
        Vector4f worldPoint = new Vector4f(0, 0, -5.0f, 1.0f);
        Vector4f currClip = new Vector4f();
        currVP.transform(worldPoint, currClip);
        Vector4f currNDC = new Vector4f(currClip.x / currClip.w, currClip.y / currClip.w, currClip.z / currClip.w, 1.0f);

        // Reproject to previous clip
        Vector4f reprojectedClip = new Vector4f();
        reprojectMatrix.transform(currNDC, reprojectedClip);
        Vector4f reprojectedNDC = new Vector4f(
                reprojectedClip.x / reprojectedClip.w,
                reprojectedClip.y / reprojectedClip.w,
                reprojectedClip.z / reprojectedClip.w,
                1.0f
        );

        // In previous frame, the camera was at (0,0,0), so (0,0,-5) should be exactly in the center (x=0)
        assertThat(reprojectedNDC.x).isCloseTo(0.0f, within(1e-4f));
        // But in current frame (camera at x=1), (0,0,-5) was to the left (negative x NDC)
        assertThat(currNDC.x).isLessThan(0.0f);
    }

    @Test
    void testCameraRelativeTranslationReprojection() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);

        // In camera-relative rendering, view matrices contain rotation only (centered at 0,0,0)
        Matrix4f prevRotView = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        Matrix4f prevVP = new Matrix4f(proj).mul(prevRotView);

        Matrix4f currRotView = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        Matrix4f currVP = new Matrix4f(proj).mul(currRotView);
        Matrix4f currInvVP = new Matrix4f(currVP).invert();

        // Camera moved +1.5 along X, +0.5 along Y
        float deltaX = 1.5f;
        float deltaY = 0.5f;
        float deltaZ = 0.0f;
        Matrix4f camTranslation = new Matrix4f().translation(deltaX, deltaY, deltaZ);

        // Reprojection Matrix = PrevVP * CamTranslation * CurrInvVP
        Matrix4f reprojectMatrix = new Matrix4f(prevVP).mul(camTranslation).mul(currInvVP);

        // Point at (0, 0, -10) in world space.
        // Current camera is at (1.5, 0.5, 0), so camera-relative point is (-1.5, -0.5, -10)
        Vector4f cam2RelPoint = new Vector4f(-1.5f, -0.5f, -10.0f, 1.0f);
        Vector4f currClip = new Vector4f();
        currVP.transform(cam2RelPoint, currClip);
        Vector4f currNDC = new Vector4f(currClip.x / currClip.w, currClip.y / currClip.w, currClip.z / currClip.w, 1.0f);

        Vector4f reprojectedClip = new Vector4f();
        reprojectMatrix.transform(currNDC, reprojectedClip);
        Vector4f reprojectedNDC = new Vector4f(
                reprojectedClip.x / reprojectedClip.w,
                reprojectedClip.y / reprojectedClip.w,
                reprojectedClip.z / reprojectedClip.w,
                1.0f
        );

        // In previous frame (camera at 0,0,0), world point (0,0,-10) was at center (ndcX = 0, ndcY = 0)
        assertThat(reprojectedNDC.x).isCloseTo(0.0f, within(1e-4f));
        assertThat(reprojectedNDC.y).isCloseTo(0.0f, within(1e-4f));
    }
}
