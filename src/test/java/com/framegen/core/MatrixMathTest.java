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

    @Test
    void testMidpointPureRotationAngle() {
        // Contract 1: 0.5 slerp on 10 degree yaw yields exactly 5 degrees
        org.joml.Quaternionf qPrev = new org.joml.Quaternionf(); // 0 yaw
        org.joml.Quaternionf qCurr = new org.joml.Quaternionf().rotateY((float) Math.toRadians(10.0));

        org.joml.Quaternionf qMid = qPrev.slerp(qCurr, 0.5f, new org.joml.Quaternionf());
        Matrix4f midView = new Matrix4f().rotation(qMid);

        // Forward vector (0, 0, -1) transformed by midView
        Vector4f forward = new Vector4f(0.0f, 0.0f, -1.0f, 0.0f);
        Vector4f transformed = new Vector4f();
        midView.transform(forward, transformed);

        double angleDeg = Math.toDegrees(Math.atan2(-transformed.x, -transformed.z));
        assertThat((float) angleDeg).isCloseTo(5.0f, within(1e-4f));
    }

    @Test
    void testMidpointMatrixOrthonormality() {
        // Contract 2: Midpoint view matrix is strictly orthonormal: M * M^T = I and det(M) = 1.0
        org.joml.Quaternionf qPrev = new org.joml.Quaternionf();
        org.joml.Quaternionf qCurr = new org.joml.Quaternionf().rotateXYZ(
                (float) Math.toRadians(17.0),
                (float) Math.toRadians(23.0),
                (float) Math.toRadians(5.0)
        );

        org.joml.Quaternionf qMid = qPrev.slerp(qCurr, 0.5f, new org.joml.Quaternionf());
        Matrix4f midView = new Matrix4f().rotation(qMid);

        // Matrix transpose
        Matrix4f transpose = new Matrix4f(midView).transpose();
        Matrix4f identityCheck = new Matrix4f(midView).mul(transpose);

        Matrix4f identity = new Matrix4f().identity();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                assertThat(identityCheck.getRowColumn(row, col))
                        .isCloseTo(identity.getRowColumn(row, col), within(1e-4f));
            }
        }

        assertThat(midView.determinant()).isCloseTo(1.0f, within(1e-4f));
    }

    @Test
    void testMidpointEndToEndWorldPoint() {
        // Contract 3: Combined rotation + translation dual reprojection
        // Camera moves from P_prev = (0, 0, 0) with yaw=0 to P_curr = (2, 0, 0) with yaw=10 deg
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);

        org.joml.Quaternionf qPrev = new org.joml.Quaternionf();
        Matrix4f prevRotView = new Matrix4f().rotation(qPrev);
        Matrix4f prevVP = new Matrix4f(proj).mul(prevRotView);

        org.joml.Quaternionf qCurr = new org.joml.Quaternionf().rotateY((float) Math.toRadians(10.0));
        Matrix4f currRotView = new Matrix4f().rotation(qCurr);
        Matrix4f currVP = new Matrix4f(proj).mul(currRotView);

        // Delta = P_curr - P_prev = (2, 0, 0)
        float deltaX = 2.0f;
        float deltaY = 0.0f;
        float deltaZ = 0.0f;

        // Midpoint camera pose: P_mid = (1, 0, 0), yaw = 5 deg
        org.joml.Quaternionf qMid = qPrev.slerp(qCurr, 0.5f, new org.joml.Quaternionf());
        Matrix4f midRotView = new Matrix4f().rotation(qMid);
        Matrix4f midVP = new Matrix4f(proj).mul(midRotView);
        Matrix4f invMidVP = new Matrix4f(midVP).invert();

        // Dual reprojection matrices:
        // MidToPrev = PrevVP * Translation(+0.5 * delta) * InvMidVP
        Matrix4f transToPrev = new Matrix4f().translation(0.5f * deltaX, 0.5f * deltaY, 0.5f * deltaZ);
        Matrix4f midToPrev = new Matrix4f(prevVP).mul(transToPrev).mul(invMidVP);

        // MidToCurr = CurrVP * Translation(-0.5 * delta) * InvMidVP
        Matrix4f transToCurr = new Matrix4f().translation(-0.5f * deltaX, -0.5f * deltaY, -0.5f * deltaZ);
        Matrix4f midToCurr = new Matrix4f(currVP).mul(transToCurr).mul(invMidVP);

        // World target at (0, 0, -10)
        // Midpoint camera at (1, 0, 0) sees point at camera-relative (-1, 0, -10)
        Vector4f midRelPoint = new Vector4f(-1.0f, 0.0f, -10.0f, 1.0f);
        Vector4f midClip = new Vector4f();
        midVP.transform(midRelPoint, midClip);
        Vector4f midNDC = new Vector4f(midClip.x / midClip.w, midClip.y / midClip.w, midClip.z / midClip.w, 1.0f);

        // Reproject midNDC to previous frame
        Vector4f reprojectedPrevClip = new Vector4f();
        midToPrev.transform(midNDC, reprojectedPrevClip);
        Vector4f reprojectedPrevNDC = new Vector4f(
                reprojectedPrevClip.x / reprojectedPrevClip.w,
                reprojectedPrevClip.y / reprojectedPrevClip.w,
                reprojectedPrevClip.z / reprojectedPrevClip.w,
                1.0f
        );

        // Direct previous frame projection of world point (0, 0, -10) relative to P_prev (0, 0, 0) -> (0, 0, -10)
        Vector4f prevDirectClip = new Vector4f();
        prevVP.transform(new Vector4f(0.0f, 0.0f, -10.0f, 1.0f), prevDirectClip);
        Vector4f prevDirectNDC = new Vector4f(
                prevDirectClip.x / prevDirectClip.w,
                prevDirectClip.y / prevDirectClip.w,
                prevDirectClip.z / prevDirectClip.w,
                1.0f
        );

        assertThat(reprojectedPrevNDC.x).isCloseTo(prevDirectNDC.x, within(1e-4f));
        assertThat(reprojectedPrevNDC.y).isCloseTo(prevDirectNDC.y, within(1e-4f));
        assertThat(reprojectedPrevNDC.z).isCloseTo(prevDirectNDC.z, within(1e-4f));

        // Reproject midNDC to current frame
        Vector4f reprojectedCurrClip = new Vector4f();
        midToCurr.transform(midNDC, reprojectedCurrClip);
        Vector4f reprojectedCurrNDC = new Vector4f(
                reprojectedCurrClip.x / reprojectedCurrClip.w,
                reprojectedCurrClip.y / reprojectedCurrClip.w,
                reprojectedCurrClip.z / reprojectedCurrClip.w,
                1.0f
        );

        // Direct current frame projection of world point (0, 0, -10) relative to P_curr (2, 0, 0) -> (-2, 0, -10)
        Vector4f currDirectClip = new Vector4f();
        currVP.transform(new Vector4f(-2.0f, 0.0f, -10.0f, 1.0f), currDirectClip);
        Vector4f currDirectNDC = new Vector4f(
                currDirectClip.x / currDirectClip.w,
                currDirectClip.y / currDirectClip.w,
                currDirectClip.z / currDirectClip.w,
                1.0f
        );

        assertThat(reprojectedCurrNDC.x).isCloseTo(currDirectNDC.x, within(1e-4f));
        assertThat(reprojectedCurrNDC.y).isCloseTo(currDirectNDC.y, within(1e-4f));
        assertThat(reprojectedCurrNDC.z).isCloseTo(currDirectNDC.z, within(1e-4f));
    }

    @Test
    void testReverseZGlZeroToOneNDCInterpretation() {
        // Minecraft 26.2 Blaze3D Reverse-Z projection with glClipControl(GL_ZERO_TO_ONE)
        // Standard Reverse-Z matrix:
        // x_clip = x / tan(fov/2) / aspect
        // y_clip = y / tan(fov/2)
        // z_clip = near (at near plane z = -near, z_clip/w = near/near = 1.0; at far, z_clip/w -> 0.0)
        // w_clip = -z
        float fov = (float) Math.toRadians(70.0);
        float aspect = 16.0f / 9.0f;
        float near = 0.05f;
        
        Matrix4f revZProj = new Matrix4f().zero();
        revZProj.m00(1.0f / ((float) Math.tan(fov * 0.5f) * aspect));
        revZProj.m11(1.0f / (float) Math.tan(fov * 0.5f));
        revZProj.m22(0.0f); // far at infinity
        revZProj.m32(near); // z_clip = near
        revZProj.m23(-1.0f); // w_clip = -z

        Matrix4f view = new Matrix4f().identity(); // camera at (0,0,0) looking down -Z
        Matrix4f vp = new Matrix4f(revZProj).mul(view);
        Matrix4f invVP = new Matrix4f(vp).invert();

        // Point at (0, 0, -10.0)
        Vector4f worldP = new Vector4f(0, 0, -10.0f, 1.0f);
        Vector4f clipP = new Vector4f();
        vp.transform(worldP, clipP);

        // In GL_ZERO_TO_ONE Reverse-Z:
        // depth d = clipP.z / clipP.w = near / 10.0 = 0.05 / 10 = 0.005
        float depth = clipP.z / clipP.w;
        assertThat(depth).isCloseTo(0.005f, within(1e-4f));

        // Contract 1: With z_NDC = depth (GL_ZERO_TO_ONE correct mapping):
        Vector4f ndcCorrect = new Vector4f(0, 0, depth, 1.0f);
        Vector4f unprojCorrect = new Vector4f();
        invVP.transform(ndcCorrect, unprojCorrect);
        unprojCorrect.div(unprojCorrect.w);
        assertThat(unprojCorrect.z).isCloseTo(-10.0f, within(1e-3f));

        // Contract 2: With buggy legacy z_NDC = 1.0 - 2.0 * depth:
        // 1.0 - 2.0 * 0.005 = 0.99 (which corresponds to near plane z ~ -0.05m instead of -10m!)
        Vector4f ndcBuggy = new Vector4f(0, 0, 1.0f - 2.0f * depth, 1.0f);
        Vector4f unprojBuggy = new Vector4f();
        invVP.transform(ndcBuggy, unprojBuggy);
        unprojBuggy.div(unprojBuggy.w);
        // Proves that buggy mapping produces distance ~ -0.05m (off by a factor of 200x!), destroying reprojection!
        assertThat(unprojBuggy.z).isNotCloseTo(-10.0f, within(1.0f));
    }
}

