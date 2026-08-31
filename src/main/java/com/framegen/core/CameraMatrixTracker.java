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

    private double currentCamX, currentCamY, currentCamZ;
    private double previousCamX, previousCamY, previousCamZ;

    private float angularDeltaDegrees = 0.0f;
    private float translationDelta = 0.0f;
    private float angularVelocityDegPerSec = 0.0f;
    private float translationVelocityBlocksPerSec = 0.0f;

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

            // Dedicated Viewmodel rotation-only reprojection
            viewmodelReprojectionMatrix.set(previousViewProjectionMatrix)
                    .mul(currentInvViewProjectionMatrix);

            if (!viewmodelReprojectionMatrix.isFinite()) {
                viewmodelReprojectionMatrix.identity();
            }
        } else {
            this.angularDeltaDegrees = 0.0f;
            this.translationDelta = 0.0f;
            this.angularVelocityDegPerSec = 0.0f;
            this.translationVelocityBlocksPerSec = 0.0f;
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
    }

    public boolean hasPreviousFrame() {
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

    public synchronized boolean isVelocityGated(float maxAngularVelocityDegPerSec, float maxTranslationVelocityBlocksPerSec) {
        if (!hasPreviousFrame) {
            return false;
        }
        return angularVelocityDegPerSec > maxAngularVelocityDegPerSec || translationVelocityBlocksPerSec > maxTranslationVelocityBlocksPerSec;
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
}
