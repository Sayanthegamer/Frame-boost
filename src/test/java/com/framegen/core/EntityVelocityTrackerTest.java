package com.framegen.core;

import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EntityVelocityTrackerTest {

    private CameraMatrixTracker cameraTracker;
    private EntityVelocityTracker entityTracker;
    private Matrix4f proj;

    @BeforeEach
    void setUp() {
        cameraTracker = new CameraMatrixTracker();
        entityTracker = new EntityVelocityTracker();
        proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);
    }

    @Test
    void testVelocityRgbaEncodeDecodeRoundTrip() {
        float maxVelUv = 0.125f;
        float du = 0.042f;
        float dv = -0.018f;

        float[] rgba = EntityVelocityTracker.encodeVelocityToRgba(du, dv, 0.8f, maxVelUv);
        assertThat(rgba[0]).isBetween(0.0f, 1.0f);
        assertThat(rgba[1]).isBetween(0.0f, 1.0f);
        assertThat(rgba[2]).isCloseTo(0.8f, within(1e-4f));
        assertThat(rgba[3]).isEqualTo(1.0f);

        float[] decoded = EntityVelocityTracker.decodeVelocityFromRgba(rgba[0], rgba[1], rgba[3], maxVelUv);
        assertThat(decoded[0]).isCloseTo(du, within(1e-4f));
        assertThat(decoded[1]).isCloseTo(dv, within(1e-4f));

        // Inactive alpha (0.0) decodes strictly to zero velocity
        float[] inactive = EntityVelocityTracker.decodeVelocityFromRgba(rgba[0], rgba[1], 0.0f, maxVelUv);
        assertThat(inactive[0]).isEqualTo(0.0f);
        assertThat(inactive[1]).isEqualTo(0.0f);
    }

    @Test
    void testStationaryEntityProducesZeroVelocityQuadsEvenWhenCameraRotates() {
        Matrix4f view1 = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        float rad5 = (float) Math.toRadians(5.0);
        Matrix4f view2 = new Matrix4f().lookAt(0, 0, 0, (float) Math.sin(rad5), 0, -(float) Math.cos(rad5), 0, 1, 0);

        // Frame 1: Entity #42 at (0, 0, -8)
        cameraTracker.updateMatrices(view1, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        entityTracker.beginFrame();
        entityTracker.recordEntityPose(42, 0.0, 0.0, -8.0, 0.6f, 1.8f);

        // Frame 2: Camera rotates 5 deg, Entity #42 remains stationary at (0, 0, -8)
        cameraTracker.updateMatrices(view2, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        entityTracker.beginFrame();
        entityTracker.recordEntityPose(42, 0.0, 0.0, -8.0, 0.6f, 1.8f);

        List<EntityVelocityTracker.EntityVelocityQuad> quads = entityTracker.computeVelocityQuads(cameraTracker);
        // Stationary entities are handled by rigid camera reprojection and emit 0 residual quads
        assertThat(quads).isEmpty();
    }

    @Test
    void testMovingEntityProducesPositiveHorizontalResidualVelocityQuad() {
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);

        // Frame 1: Camera at origin, Entity #7 at (-0.5, -0.9, -6.0)
        cameraTracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        entityTracker.beginFrame();
        entityTracker.recordEntityPose(7, -0.5, -0.9, -6.0, 0.6f, 1.8f);

        // Frame 2: Camera at origin, Entity #7 moves right (+X) to (+0.5, -0.9, -6.0)
        cameraTracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        entityTracker.beginFrame();
        entityTracker.recordEntityPose(7, 0.5, -0.9, -6.0, 0.6f, 1.8f);

        List<EntityVelocityTracker.EntityVelocityQuad> quads = entityTracker.computeVelocityQuads(cameraTracker, 0.125f);
        assertThat(quads).hasSize(1);

        EntityVelocityTracker.EntityVelocityQuad quad = quads.get(0);
        assertThat(quad.entityId()).isEqualTo(7);
        // Moving right (+X) in front of camera yields positive horizontal screen UV velocity (residualDu > 0)
        assertThat(quad.residualDu()).isGreaterThan(0.05f);
        assertThat(quad.residualDv()).isCloseTo(0.0f, within(1e-4f));
        assertThat(quad.minU()).isLessThan(0.5f);
        assertThat(quad.maxU()).isGreaterThan(0.5f);
        assertThat(quad.encodedR()).isGreaterThan(0.5f);
        assertThat(quad.encodedA()).isEqualTo(1.0f);
    }

    @Test
    void testEntitySceneExtractorExcludesCameraEntityViaGetterAndInterpolatesPartialTick() {
        MockEntity playerCamera = new MockEntity(100, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.6f, 1.8f);
        MockEntity movingMob = new MockEntity(202, -1.0, -0.9, -6.0, 1.0, -0.9, -6.0, 0.6f, 1.95f);
        MockClientLevel level = new MockClientLevel(List.of(playerCamera, movingMob));
        MockMinecraft mc = new MockMinecraft(level, playerCamera);

        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);

        // Frame 1 at partialTick = 0.25 -> movingMob interpolated X = -1.0 + (1.0 - (-1.0)) * 0.25 = -0.5
        cameraTracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        EntitySceneExtractor.extractEntities(mc, 0.25f, entityTracker);

        // Frame 2 at partialTick = 0.75 -> movingMob interpolated X = -1.0 + (1.0 - (-1.0)) * 0.75 = +0.5
        cameraTracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        EntitySceneExtractor.extractEntities(mc, 0.75f, entityTracker);

        List<EntityVelocityTracker.EntityVelocityQuad> quads = entityTracker.computeVelocityQuads(cameraTracker, 0.125f);
        // Player camera entity (#100) must be excluded via getCameraEntity(), leaving only movingMob (#202)
        assertThat(quads).hasSize(1);
        assertThat(quads.get(0).entityId()).isEqualTo(202);
        assertThat(quads.get(0).residualDu()).isGreaterThan(0.05f);
    }

    @Test
    void testResetClearsEntityHistory() {
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        cameraTracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        entityTracker.beginFrame();
        entityTracker.recordEntityPose(1, 0.0, 0.0, -5.0, 0.6f, 1.8f);

        cameraTracker.updateMatrices(view, proj, 0.0, 0.0, 0.0, 1.0 / 60.0);
        entityTracker.beginFrame();
        entityTracker.recordEntityPose(1, 1.0, 0.0, -5.0, 0.6f, 1.8f);
        assertThat(entityTracker.computeVelocityQuads(cameraTracker)).hasSize(1);

        entityTracker.reset();
        assertThat(entityTracker.getActiveMovingEntityCount()).isEqualTo(0);
        assertThat(entityTracker.getActiveVelocityQuads()).isEmpty();
    }

    static class MockEntity {
        private final int id;
        public final double xo;
        public final double yo;
        public final double zo;
        private final double x;
        private final double y;
        private final double z;
        private final float bbWidth;
        private final float bbHeight;

        MockEntity(int id, double xo, double yo, double zo, double x, double y, double z, float bbWidth, float bbHeight) {
            this.id = id;
            this.xo = xo;
            this.yo = yo;
            this.zo = zo;
            this.x = x;
            this.y = y;
            this.z = z;
            this.bbWidth = bbWidth;
            this.bbHeight = bbHeight;
        }

        public int getId() { return id; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public float getBbWidth() { return bbWidth; }
        public float getBbHeight() { return bbHeight; }
    }

    static class MockClientLevel {
        private final List<MockEntity> entities;
        MockClientLevel(List<MockEntity> entities) { this.entities = entities; }
        public Iterable<MockEntity> entitiesForRendering() { return entities; }
    }

    static class MockMinecraft {
        public final MockClientLevel level;
        private final MockEntity activeCam;
        MockMinecraft(MockClientLevel level, MockEntity activeCam) {
            this.level = level;
            this.activeCam = activeCam;
        }
        // Matches Minecraft 26.3 signature: no cameraEntity field, exposes getCameraEntity() method
        public MockEntity getCameraEntity() { return activeCam; }
    }
}
