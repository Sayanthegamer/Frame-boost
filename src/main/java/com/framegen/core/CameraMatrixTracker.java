package com.framegen.core;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class CameraMatrixTracker {

    private final Matrix4f currentViewMatrix = new Matrix4f();
    private final Matrix4f currentProjectionMatrix = new Matrix4f();
    private final Matrix4f currentViewProjectionMatrix = new Matrix4f();
    private final Matrix4f currentInvViewProjectionMatrix = new Matrix4f();

    private final Matrix4f previousViewMatrix = new Matrix4f();
    private final Matrix4f previousProjectionMatrix = new Matrix4f();
    private final Matrix4f previousViewProjectionMatrix = new Matrix4f();

    private final Matrix4f reprojectionMatrix = new Matrix4f();
    private final Matrix4f viewmodelReprojectionMatrix = new Matrix4f();
    private final Matrix4f midToPrevMatrix = new Matrix4f();
    private final Matrix4f midToCurrMatrix = new Matrix4f();
    private final Matrix4f midToPrevSkyMatrix = new Matrix4f();
    private final Matrix4f midToCurrSkyMatrix = new Matrix4f();
    private final Matrix4f currentViewmodelMatrix = new Matrix4f();
    private final Matrix4f previousViewmodelMatrix = new Matrix4f();

    private double currentCamX, currentCamY, currentCamZ;
    private double previousCamX, previousCamY, previousCamZ;

    private float angularDeltaDegrees = 0.0f;
    private float translationDelta = 0.0f;
    private float angularVelocityDegPerSec = 0.0f;
    private float translationVelocityBlocksPerSec = 0.0f;
    private float lastFrameDurationSeconds = 0.016666f;

    private boolean hasPreviousFrame = false;
    private boolean initialized = false;

    private static CameraMatrixTracker INSTANCE;

    public CameraMatrixTracker() {
    }

    public static synchronized CameraMatrixTracker getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CameraMatrixTracker();
        }
        return INSTANCE;
    }

    public synchronized void updateMatrices(Matrix4f viewMatrix, Matrix4f projectionMatrix, double camX, double camY, double camZ) {
        updateMatrices(viewMatrix, projectionMatrix, camX, camY, camZ, 1.0 / 60.0);
    }

    public synchronized void updateMatrices(Matrix4f viewMatrix, Matrix4f projectionMatrix, double camX, double camY, double camZ, double frameDeltaSeconds) {
        this.lastFrameDurationSeconds = (float) frameDeltaSeconds;
        if (initialized) {
            previousViewMatrix.set(currentViewMatrix);
            previousProjectionMatrix.set(currentProjectionMatrix);
            previousViewProjectionMatrix.set(currentViewProjectionMatrix);
            previousCamX = currentCamX;
            previousCamY = currentCamY;
            previousCamZ = currentCamZ;
            hasPreviousFrame = true;
        }

        currentViewMatrix.set(viewMatrix);
        currentProjectionMatrix.set(projectionMatrix);
        currentViewProjectionMatrix.set(projectionMatrix).mul(viewMatrix);
        currentInvViewProjectionMatrix.set(currentViewProjectionMatrix).invert();

        if (!currentInvViewProjectionMatrix.isFinite()) {
            currentInvViewProjectionMatrix.identity();
        }

        currentCamX = camX;
        currentCamY = camY;
        currentCamZ = camZ;
        initialized = true;

        if (hasPreviousFrame) {
            float deltaX = (float) (currentCamX - previousCamX);
            float deltaY = (float) (currentCamY - previousCamY);
            float deltaZ = (float) (currentCamZ - previousCamZ);
            this.translationDelta = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);

            Vector3f prevForward = new Vector3f(-previousViewMatrix.m02(), -previousViewMatrix.m12(), -previousViewMatrix.m22());
            Vector3f currForward = new Vector3f(-currentViewMatrix.m02(), -currentViewMatrix.m12(), -currentViewMatrix.m22());
            if (prevForward.lengthSquared() > 0.0001f) {
                prevForward.normalize();
            }
            if (currForward.lengthSquared() > 0.0001f) {
                currForward.normalize();
            }

            float dot = Math.max(-1.0f, Math.min(1.0f, prevForward.dot(currForward)));
            this.angularDeltaDegrees = (float) Math.toDegrees(Math.acos(dot));

            double safeDt = Math.max(frameDeltaSeconds, 0.0001);
            this.angularVelocityDegPerSec = (float) (this.angularDeltaDegrees / safeDt);
            this.translationVelocityBlocksPerSec = (float) (this.translationDelta / safeDt);

            // Static world terrain reprojection
            Matrix4f camTranslation = new Matrix4f().translation(deltaX, deltaY, deltaZ);
            reprojectionMatrix.set(previousViewProjectionMatrix)
                    .mul(camTranslation)
                    .mul(currentInvViewProjectionMatrix);

            if (!reprojectionMatrix.isFinite()) {
                reprojectionMatrix.identity();
            }

            // Dedicated Viewmodel rotation-only fallback (if pose not explicitly set via updateViewmodelPose)
            viewmodelReprojectionMatrix.set(previousViewProjectionMatrix)
                    .mul(currentInvViewProjectionMatrix);

            if (!viewmodelReprojectionMatrix.isFinite()) {
                viewmodelReprojectionMatrix.identity();
            }

            // Midpoint pose synthesis & dual reprojection matrices
            computeMidpointMatrices(deltaX, deltaY, deltaZ);
        } else {
            this.angularDeltaDegrees = 0.0f;
            this.translationDelta = 0.0f;
            this.angularVelocityDegPerSec = 0.0f;
            this.translationVelocityBlocksPerSec = 0.0f;
            this.midToPrevMatrix.identity();
            this.midToCurrMatrix.identity();
            this.midToPrevSkyMatrix.identity();
            this.midToCurrSkyMatrix.identity();
        }
    }

    public synchronized void updateViewmodelPose(Matrix4f viewmodelPoseMatrix, Matrix4f projectionMatrix) {
        if (hasPreviousFrame) {
            previousViewmodelMatrix.set(currentViewmodelMatrix);
        }
        currentViewmodelMatrix.set(viewmodelPoseMatrix);

        if (hasPreviousFrame) {
            Matrix4f prevVMVP = new Matrix4f(previousProjectionMatrix).mul(previousViewmodelMatrix);
            Matrix4f currVMVP = new Matrix4f(projectionMatrix).mul(currentViewmodelMatrix);
            Matrix4f currInvVMVP = new Matrix4f(currVMVP).invert();
            if (currInvVMVP.isFinite()) {
                viewmodelReprojectionMatrix.set(prevVMVP).mul(currInvVMVP);
            }
        }
    }

    public synchronized Matrix4f getInterpolatedViewProjection(float t) {
        if (!hasPreviousFrame) {
            return new Matrix4f(currentViewProjectionMatrix);
        }

        // Interpolate View Matrix between previous and current
        Matrix4f interpView = new Matrix4f();
        previousViewMatrix.lerp(currentViewMatrix, t, interpView);

        Matrix4f interpProj = new Matrix4f();
        previousProjectionMatrix.lerp(currentProjectionMatrix, t, interpProj);

        return new Matrix4f(interpProj).mul(interpView);
    }

    public synchronized void reset() {
        hasPreviousFrame = false;
        initialized = false;
        angularDeltaDegrees = 0.0f;
        translationDelta = 0.0f;
        angularVelocityDegPerSec = 0.0f;
        translationVelocityBlocksPerSec = 0.0f;
        currentViewMatrix.identity();
        currentProjectionMatrix.identity();
        currentViewProjectionMatrix.identity();
        currentInvViewProjectionMatrix.identity();
        previousViewMatrix.identity();
        previousProjectionMatrix.identity();
        previousViewProjectionMatrix.identity();
        reprojectionMatrix.identity();
        viewmodelReprojectionMatrix.identity();
        midToPrevMatrix.identity();
        midToCurrMatrix.identity();
        midToPrevSkyMatrix.identity();
        midToCurrSkyMatrix.identity();
    }

    public synchronized boolean hasPreviousFrame() {
        return hasPreviousFrame;
    }

    public synchronized float getAngularDeltaDegrees() {
        return angularDeltaDegrees;
    }

    public synchronized float getTranslationDelta() {
        return translationDelta;
    }

    public synchronized float getAngularVelocityDegPerSec() {
        return angularVelocityDegPerSec;
    }

    public synchronized float getTranslationVelocityBlocksPerSec() {
        return translationVelocityBlocksPerSec;
    }

    public synchronized float getLastFrameDurationSeconds() {
        return lastFrameDurationSeconds;
    }

    public synchronized boolean isVelocityGated(float maxAngularVelocityDegPerSec, float maxTranslationVelocityBlocksPerSec) {
        return isVelocityGated(maxAngularVelocityDegPerSec, maxTranslationVelocityBlocksPerSec, 15.0f, 50.0f);
    }

    public synchronized boolean isVelocityGated(
            float maxAngularVelocityDegPerSec,
            float maxTranslationVelocityBlocksPerSec,
            float maxAbsoluteAngleThreshold,
            float maxFrameDurationThresholdMs
    ) {
        if (!hasPreviousFrame) {
            return true;
        }
        boolean rateGated = angularVelocityDegPerSec > maxAngularVelocityDegPerSec
                || translationVelocityBlocksPerSec > maxTranslationVelocityBlocksPerSec;
        boolean lagOrSnapGated = angularDeltaDegrees > maxAbsoluteAngleThreshold
                || lastFrameDurationSeconds > (maxFrameDurationThresholdMs / 1000.0f);
        return rateGated || lagOrSnapGated;
    }

    public Matrix4f getCurrentViewMatrix() {
        return new Matrix4f(currentViewMatrix);
    }

    public Matrix4f getCurrentProjectionMatrix() {
        return new Matrix4f(currentProjectionMatrix);
    }

    public Matrix4f getCurrentViewProjectionMatrix() {
        return new Matrix4f(currentViewProjectionMatrix);
    }

    public Matrix4f getCurrentInvViewProjectionMatrix() {
        return new Matrix4f(currentInvViewProjectionMatrix);
    }

    public Matrix4f getPreviousViewMatrix() {
        return new Matrix4f(previousViewMatrix);
    }

    public Matrix4f getPreviousProjectionMatrix() {
        return new Matrix4f(previousProjectionMatrix);
    }

    public Matrix4f getPreviousViewProjectionMatrix() {
        return new Matrix4f(previousViewProjectionMatrix);
    }

    public Matrix4f getReprojectionMatrix() {
        return new Matrix4f(reprojectionMatrix);
    }

    public Matrix4f getViewmodelReprojectionMatrix() {
        return new Matrix4f(viewmodelReprojectionMatrix);
    }

    public double getCurrentCamX() {
        return currentCamX;
    }

    public double getCurrentCamY() {
        return currentCamY;
    }

    public double getCurrentCamZ() {
        return currentCamZ;
    }

    public double getPreviousCamX() {
        return previousCamX;
    }

    public double getPreviousCamY() {
        return previousCamY;
    }

    public double getPreviousCamZ() {
        return previousCamZ;
    }

    private void computeMidpointMatrices(float deltaX, float deltaY, float deltaZ) {
        Quaternionf qPrev = new Quaternionf().setFromNormalized(previousViewMatrix);
        Quaternionf qCurr = new Quaternionf().setFromNormalized(currentViewMatrix);
        Quaternionf qMid = qPrev.slerp(qCurr, 0.5f, new Quaternionf());
        Matrix4f midViewMatrix = new Matrix4f().rotation(qMid);

        Matrix4f midProjMatrix = new Matrix4f(currentProjectionMatrix);
        float m00 = previousProjectionMatrix.m00() + (currentProjectionMatrix.m00() - previousProjectionMatrix.m00()) * 0.5f;
        float m11 = previousProjectionMatrix.m11() + (currentProjectionMatrix.m11() - previousProjectionMatrix.m11()) * 0.5f;
        midProjMatrix.m00(m00);
        midProjMatrix.m11(m11);

        Matrix4f midVP = new Matrix4f(midProjMatrix).mul(midViewMatrix);
        Matrix4f invMidVP = new Matrix4f(midVP).invert();
        if (!invMidVP.isFinite()) {
            invMidVP.identity();
        }

        Matrix4f transToPrev = new Matrix4f().translation(0.5f * deltaX, 0.5f * deltaY, 0.5f * deltaZ);
        midToPrevMatrix.set(previousViewProjectionMatrix).mul(transToPrev).mul(invMidVP);
        if (!midToPrevMatrix.isFinite()) {
            midToPrevMatrix.identity();
        }

        Matrix4f transToCurr = new Matrix4f().translation(-0.5f * deltaX, -0.5f * deltaY, -0.5f * deltaZ);
        midToCurrMatrix.set(currentViewProjectionMatrix).mul(transToCurr).mul(invMidVP);
        if (!midToCurrMatrix.isFinite()) {
            midToCurrMatrix.identity();
        }

        midToPrevSkyMatrix.set(previousViewProjectionMatrix).mul(invMidVP);
        if (!midToPrevSkyMatrix.isFinite()) {
            midToPrevSkyMatrix.identity();
        }

        midToCurrSkyMatrix.set(currentViewProjectionMatrix).mul(invMidVP);
        if (!midToCurrSkyMatrix.isFinite()) {
            midToCurrSkyMatrix.identity();
        }
    }

    public synchronized Matrix4f getMidToPrevMatrix() {
        return new Matrix4f(midToPrevMatrix);
    }

    public synchronized Matrix4f getMidToCurrMatrix() {
        return new Matrix4f(midToCurrMatrix);
    }

    public synchronized Matrix4f getMidToPrevSkyMatrix() {
        return new Matrix4f(midToPrevSkyMatrix);
    }

    public synchronized Matrix4f getMidToCurrSkyMatrix() {
        return new Matrix4f(midToCurrSkyMatrix);
    }
}
