package com.framegen.core;

import com.framegen.shader.ShaderConstants;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks 3D world-space positions and bounding boxes of dynamic entities across consecutive
 * render frames and projects their non-rigid residual screen-space velocity vectors (after
 * subtracting rigid camera reprojection) along with their Reverse-Z front-surface depth
 * into normalized screen-space quads for the EntityMaskSampler velocity attachment.
 */
public class EntityVelocityTracker {

    private static final float MIN_WORLD_DISPLACEMENT_SQ = 1.0e-5f;
    private static final float MAX_TRACKING_DISTANCE_SQ = 32.0f * 32.0f;
    private static final float SCREEN_BBOX_MARGIN_UV = 0.006f;

    private static EntityVelocityTracker INSTANCE;

    public record EntityPoseSnapshot(
            int entityId,
            double x,
            double y,
            double z,
            float width,
            float height
    ) {
    }

    public record EntityVelocityQuad(
            int entityId,
            float minU,
            float minV,
            float maxU,
            float maxV,
            float residualDu,
            float residualDv,
            float encodedR,
            float encodedG,
            float encodedB,
            float encodedA,
            float depthWeight
    ) {
    }

    private final Map<Integer, EntityPoseSnapshot> previousFrameEntities = new HashMap<>();
    private final Map<Integer, EntityPoseSnapshot> currentFrameEntities = new HashMap<>();
    private final List<EntityVelocityQuad> activeVelocityQuads = new ArrayList<>();

    public EntityVelocityTracker() {
    }

    public static synchronized EntityVelocityTracker getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new EntityVelocityTracker();
        }
        return INSTANCE;
    }

    public synchronized void beginFrame() {
        previousFrameEntities.clear();
        previousFrameEntities.putAll(currentFrameEntities);
        currentFrameEntities.clear();
        activeVelocityQuads.clear();
    }

    public synchronized void recordEntityPose(int entityId, double x, double y, double z, float width, float height) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }
        float safeWidth = Math.max(0.1f, Math.min(width, 16.0f));
        float safeHeight = Math.max(0.1f, Math.min(height, 16.0f));
        currentFrameEntities.put(entityId, new EntityPoseSnapshot(entityId, x, y, z, safeWidth, safeHeight));
    }

    public synchronized List<EntityVelocityQuad> computeVelocityQuads(CameraMatrixTracker matrixTracker) {
        return computeVelocityQuads(matrixTracker, ShaderConstants.DEFAULT_ENTITY_VELOCITY_MAX_UV);
    }

    public synchronized List<EntityVelocityQuad> computeVelocityQuads(
            CameraMatrixTracker matrixTracker,
            float maxVelUv
    ) {
        activeVelocityQuads.clear();
        if (matrixTracker == null || !matrixTracker.hasPreviousFrame() || previousFrameEntities.isEmpty()) {
            return List.copyOf(activeVelocityQuads);
        }

        float safeMaxVelUv = Math.max(0.005f, maxVelUv);

        Matrix4f currVP = matrixTracker.getCurrentViewProjectionMatrix();
        Matrix4f prevVP = matrixTracker.getPreviousViewProjectionMatrix();
        Matrix4f midVP = matrixTracker.getInterpolatedViewProjection(0.5f);

        double currCamX = matrixTracker.getCurrentCamX();
        double currCamY = matrixTracker.getCurrentCamY();
        double currCamZ = matrixTracker.getCurrentCamZ();

        double prevCamX = matrixTracker.getPreviousCamX();
        double prevCamY = matrixTracker.getPreviousCamY();
        double prevCamZ = matrixTracker.getPreviousCamZ();

        double midCamX = 0.5 * (currCamX + prevCamX);
        double midCamY = 0.5 * (currCamY + prevCamY);
        double midCamZ = 0.5 * (currCamZ + prevCamZ);

        for (Map.Entry<Integer, EntityPoseSnapshot> entry : currentFrameEntities.entrySet()) {
            EntityPoseSnapshot curr = entry.getValue();
            EntityPoseSnapshot prev = previousFrameEntities.get(entry.getKey());
            if (prev == null) {
                continue;
            }

            float dx = (float) (curr.x() - prev.x());
            float dy = (float) (curr.y() - prev.y());
            float dz = (float) (curr.z() - prev.z());
            float distSq = dx * dx + dy * dy + dz * dz;

            // Stationary entities in world space have zero residual velocity relative to camera matrices
            if (distSq < MIN_WORLD_DISPLACEMENT_SQ || distSq > 64.0f) {
                continue;
            }

            double centerOffsetY = 0.5 * curr.height();
            double midWorldX = 0.5 * (curr.x() + prev.x());
            double midWorldY = 0.5 * (curr.y() + prev.y()) + centerOffsetY;
            double midWorldZ = 0.5 * (curr.z() + prev.z());

            float camDistSq = (float) ((midWorldX - midCamX) * (midWorldX - midCamX)
                    + (midWorldY - midCamY) * (midWorldY - midCamY)
                    + (midWorldZ - midCamZ) * (midWorldZ - midCamZ));
            if (camDistSq > MAX_TRACKING_DISTANCE_SQ || camDistSq < 0.01f) {
                continue;
            }

            // 1. Project entity center in Frame N relative to Camera N
            float[] uvCurrActual = projectWorldToUvAndDepth(
                    currVP,
                    (float) (curr.x() - currCamX),
                    (float) (curr.y() + centerOffsetY - currCamY),
                    (float) (curr.z() - currCamZ)
            );
            if (uvCurrActual == null) {
                continue;
            }

            // Frustum check: ignore entities outside the screen viewport
            if (uvCurrActual[0] < -0.05f || uvCurrActual[0] > 1.05f || uvCurrActual[1] < -0.05f || uvCurrActual[1] > 1.05f) {
                continue;
            }

            // 2. Project entity center in Frame N-1 relative to Camera N-1
            float[] uvPrevActual = projectWorldToUvAndDepth(
                    prevVP,
                    (float) (prev.x() - prevCamX),
                    (float) (prev.y() + centerOffsetY - prevCamY),
                    (float) (prev.z() - prevCamZ)
            );
            if (uvPrevActual == null) {
                continue;
            }

            // 3. Project midpoint world point as if it were static terrain in both frames
            float[] uvCurrStatic = projectWorldToUvAndDepth(
                    currVP,
                    (float) (midWorldX - currCamX),
                    (float) (midWorldY - currCamY),
                    (float) (midWorldZ - currCamZ)
            );
            float[] uvPrevStatic = projectWorldToUvAndDepth(
                    prevVP,
                    (float) (midWorldX - prevCamX),
                    (float) (midWorldY - prevCamY),
                    (float) (midWorldZ - prevCamZ)
            );
            if (uvCurrStatic == null || uvPrevStatic == null) {
                continue;
            }

            // Residual screen-space motion vector after subtracting rigid camera motion
            float totalDu = uvCurrActual[0] - uvPrevActual[0];
            float totalDv = uvCurrActual[1] - uvPrevActual[1];
            float camDu = uvCurrStatic[0] - uvPrevStatic[0];
            float camDv = uvCurrStatic[1] - uvPrevStatic[1];

            float residualDu = totalDu - camDu;
            float residualDv = totalDv - camDv;

            if (Math.abs(residualDu) < 2.5e-4f && Math.abs(residualDv) < 2.5e-4f) {
                continue;
            }

            // 4. Compute projected screen-space bounding box & nearest Reverse-Z depth
            float halfW = 0.5f * curr.width() + 0.5f * Math.max(Math.abs(dx), Math.abs(dz));
            float fullH = curr.height() + 0.5f * Math.abs(dy);
            double baseMidY = 0.5 * (curr.y() + prev.y()) - 0.25 * Math.abs(dy);

            float minU = 1.0f;
            float minV = 1.0f;
            float maxU = 0.0f;
            float maxV = 0.0f;
            float maxReverseZDepth = Math.max(uvCurrActual[2], uvPrevActual[2]);
            int validCorners = 0;

            for (int ix = -1; ix <= 1; ix += 2) {
                for (int iy = 0; iy <= 1; iy++) {
                    for (int iz = -1; iz <= 1; iz += 2) {
                        float relX = (float) (midWorldX + ix * halfW - midCamX);
                        float relY = (float) (baseMidY + iy * fullH - midCamY);
                        float relZ = (float) (midWorldZ + iz * halfW - midCamZ);
                        float[] cornerUv = projectWorldToUvAndDepth(midVP, relX, relY, relZ);
                        if (cornerUv != null) {
                            minU = Math.min(minU, cornerUv[0]);
                            minV = Math.min(minV, cornerUv[1]);
                            maxU = Math.max(maxU, cornerUv[0]);
                            maxV = Math.max(maxV, cornerUv[1]);
                            maxReverseZDepth = Math.max(maxReverseZDepth, cornerUv[2]);
                            validCorners++;
                        }
                    }
                }
            }

            if (validCorners == 0) {
                continue;
            }

            minU = clamp01(Math.min(minU, Math.min(uvCurrActual[0], uvPrevActual[0])) - SCREEN_BBOX_MARGIN_UV);
            minV = clamp01(Math.min(minV, Math.min(uvCurrActual[1], uvPrevActual[1])) - SCREEN_BBOX_MARGIN_UV);
            maxU = clamp01(Math.max(maxU, Math.max(uvCurrActual[0], uvPrevActual[0])) + SCREEN_BBOX_MARGIN_UV);
            maxV = clamp01(Math.max(maxV, Math.max(uvCurrActual[1], uvPrevActual[1])) + SCREEN_BBOX_MARGIN_UV);

            if (maxU <= minU || maxV <= minV) {
                continue;
            }

            // Encode entity's nearest Reverse-Z depth into channel B (used by reproject.fsh to reject occluded entities)
            float entityReverseZDepth = clamp01(maxReverseZDepth);
            float[] rgba = encodeVelocityToRgba(residualDu, residualDv, entityReverseZDepth, safeMaxVelUv);

            activeVelocityQuads.add(new EntityVelocityQuad(
                    curr.entityId(),
                    minU, minV, maxU, maxV,
                    residualDu, residualDv,
                    rgba[0], rgba[1], rgba[2], rgba[3],
                    entityReverseZDepth
            ));
        }

        // Sort back-to-front (smaller Reverse-Z depth = farther away drawn first; nearer entities overwrite)
        activeVelocityQuads.sort(Comparator.comparingDouble(EntityVelocityQuad::depthWeight));
        return List.copyOf(activeVelocityQuads);
    }

    public synchronized List<EntityVelocityQuad> getActiveVelocityQuads() {
        return List.copyOf(activeVelocityQuads);
    }

    public synchronized int getActiveMovingEntityCount() {
        return activeVelocityQuads.size();
    }

    public synchronized void reset() {
        previousFrameEntities.clear();
        currentFrameEntities.clear();
        activeVelocityQuads.clear();
    }

    /**
     * Encodes screen-space residual UV velocity (du, dv) and Reverse-Z depth into normalized [0, 1] RGBA8 values.
     * R = encoded du (0.5 = zero motion)
     * G = encoded dv (0.5 = zero motion)
     * B = entity Reverse-Z depth in [0, 1] (for per-pixel depth occlusion culling in reproject.fsh)
     * A = 1.0 (active entity velocity mask flag)
     */
    public static float[] encodeVelocityToRgba(float du, float dv, float depthWeight, float maxVelUv) {
        float safeMax = Math.max(0.001f, maxVelUv);
        float r = clamp01(0.5f + (du / (2.0f * safeMax)));
        float g = clamp01(0.5f + (dv / (2.0f * safeMax)));
        float b = clamp01(depthWeight);
        return new float[] { r, g, b, 1.0f };
    }

    /**
     * Decodes normalized [0, 1] RGBA values from EntityMaskSampler back into screen-space (du, dv).
     * Returns {0, 0} when alpha <= 0.01 (unmasked static pixel).
     */
    public static float[] decodeVelocityFromRgba(float r, float g, float a, float maxVelUv) {
        if (a <= 0.01f) {
            return new float[] { 0.0f, 0.0f };
        }
        float safeMax = Math.max(0.001f, maxVelUv);
        float du = (r - 0.5f) * 2.0f * safeMax;
        float dv = (g - 0.5f) * 2.0f * safeMax;
        return new float[] { du, dv };
    }

    private static float[] projectWorldToUvAndDepth(Matrix4f vpMatrix, float relX, float relY, float relZ) {
        Vector4f clip = new Vector4f(relX, relY, relZ, 1.0f);
        vpMatrix.transform(clip);
        if (clip.w <= 0.05f || !Float.isFinite(clip.w)) {
            return null;
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        float ndcZ = clip.z / clip.w;
        float u = ndcX * 0.5f + 0.5f;
        float v = ndcY * 0.5f + 0.5f;
        if (!Float.isFinite(u) || !Float.isFinite(v) || !Float.isFinite(ndcZ)) {
            return null;
        }
        // Handle both Reverse-Z [0, 1] and standard [-1, 1] test projection matrices gracefully
        float reverseZDepth = ndcZ >= 0.0f ? clamp01(ndcZ) : clamp01(0.5f * (1.0f - ndcZ));
        return new float[] { u, v, reverseZDepth };
    }

    private static float clamp01(float val) {
        return Math.max(0.0f, Math.min(1.0f, val));
    }
}
