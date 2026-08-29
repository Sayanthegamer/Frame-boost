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

    private double currentCamX, currentCamY, currentCamZ;
    private double previousCamX, previousCamY, previousCamZ;

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

            Matrix4f camTranslation = new Matrix4f().translation(deltaX, deltaY, deltaZ);
            reprojectionMatrix.set(previousViewProjectionMatrix)
                    .mul(camTranslation)
                    .mul(currentInvViewProjectionMatrix);

            if (!reprojectionMatrix.isFinite()) {
                reprojectionMatrix.identity();
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
        currentViewMatrix.identity();
        currentProjectionMatrix.identity();
        currentViewProjectionMatrix.identity();
        currentInvViewProjectionMatrix.identity();
        previousViewMatrix.identity();
        previousProjectionMatrix.identity();
        previousViewProjectionMatrix.identity();
        reprojectionMatrix.identity();
    }

    public boolean hasPreviousFrame() {
        return hasPreviousFrame;
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
