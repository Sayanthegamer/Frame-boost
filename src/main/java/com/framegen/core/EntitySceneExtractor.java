package com.framegen.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-resilient scene extractor that harvests rendered dynamic entities and their
 * sub-tick interpolated 3D world positions from Minecraft's active ClientLevel
 * without binding to fragile internal obfuscation signatures.
 */
public final class EntitySceneExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger("EntitySceneExtractor");

    private static volatile boolean reflectionInitialized = false;
    private static volatile boolean reflectionAvailable = true;

    private static Field mcLevelField;
    private static Method mcGetCameraEntityMethod;
    private static Field mcCameraEntityField;
    private static Field mcPlayerField;
    private static Method entitiesForRenderingMethod;

    private static Method entityGetIdMethod;
    private static Method entityGetXMethod;
    private static Method entityGetYMethod;
    private static Method entityGetZMethod;
    private static Method entityGetBbWidthMethod;
    private static Method entityGetBbHeightMethod;
    private static Field entityXoField;
    private static Field entityYoField;
    private static Field entityZoField;

    private EntitySceneExtractor() {
    }

    public static void extractEntities(Object minecraftInstance, float partialTick, EntityVelocityTracker tracker) {
        if (minecraftInstance == null || tracker == null) {
            return;
        }

        tracker.beginFrame();

        if (!reflectionAvailable) {
            return;
        }

        try {
            if (!reflectionInitialized) {
                initializeReflection(minecraftInstance.getClass());
            }
            if (!reflectionAvailable || mcLevelField == null || entitiesForRenderingMethod == null) {
                return;
            }

            Object clientLevel = mcLevelField.get(minecraftInstance);
            if (clientLevel == null) {
                return;
            }

            Object cameraEntity = null;
            if (mcGetCameraEntityMethod != null) {
                cameraEntity = mcGetCameraEntityMethod.invoke(minecraftInstance);
            }
            if (cameraEntity == null && mcCameraEntityField != null) {
                cameraEntity = mcCameraEntityField.get(minecraftInstance);
            }
            if (cameraEntity == null && mcPlayerField != null) {
                cameraEntity = mcPlayerField.get(minecraftInstance);
            }

            if (cameraEntity != null && entityGetIdMethod == null) {
                initializeEntityAccessors(cameraEntity.getClass());
            }

            int cameraEntityId = -1;
            if (cameraEntity != null && entityGetIdMethod != null) {
                cameraEntityId = (int) entityGetIdMethod.invoke(cameraEntity);
            }

            Object iterableObj = entitiesForRenderingMethod.invoke(clientLevel);
            if (!(iterableObj instanceof Iterable<?> entities)) {
                return;
            }

            float clampedPartial = Math.max(0.0f, Math.min(1.0f, partialTick));

            for (Object entity : entities) {
                if (entity == null) {
                    continue;
                }
                if (entityGetIdMethod == null) {
                    initializeEntityAccessors(entity.getClass());
                    if (entityGetIdMethod == null) {
                        break;
                    }
                    if (cameraEntity != null && cameraEntityId == -1) {
                        cameraEntityId = (int) entityGetIdMethod.invoke(cameraEntity);
                    }
                }

                int id = (int) entityGetIdMethod.invoke(entity);
                if (id == cameraEntityId) {
                    // Exclude the first-person camera player entity from world entity velocity quads
                    continue;
                }

                double currX = (double) entityGetXMethod.invoke(entity);
                double currY = (double) entityGetYMethod.invoke(entity);
                double currZ = (double) entityGetZMethod.invoke(entity);

                double renderX = currX;
                double renderY = currY;
                double renderZ = currZ;

                if (entityXoField != null && entityYoField != null && entityZoField != null) {
                    double xo = entityXoField.getDouble(entity);
                    double yo = entityYoField.getDouble(entity);
                    double zo = entityZoField.getDouble(entity);
                    renderX = xo + (currX - xo) * clampedPartial;
                    renderY = yo + (currY - yo) * clampedPartial;
                    renderZ = zo + (currZ - zo) * clampedPartial;
                }

                float width = entityGetBbWidthMethod != null ? (float) entityGetBbWidthMethod.invoke(entity) : 0.6f;
                float height = entityGetBbHeightMethod != null ? (float) entityGetBbHeightMethod.invoke(entity) : 1.8f;

                tracker.recordEntityPose(id, renderX, renderY, renderZ, width, height);
            }
        } catch (Throwable t) {
            LOGGER.debug("EntitySceneExtractor fallback (unable to read entity list this frame): {}", t.getMessage());
        }
    }

    private static synchronized void initializeReflection(Class<?> mcClass) {
        if (reflectionInitialized) {
            return;
        }
        try {
            mcLevelField = findField(mcClass, "level");
            mcGetCameraEntityMethod = findNoArgMethod(mcClass, "getCameraEntity");
            mcCameraEntityField = findField(mcClass, "cameraEntity");
            mcPlayerField = findField(mcClass, "player");
            if (mcLevelField != null) {
                Class<?> levelType = mcLevelField.getType();
                entitiesForRenderingMethod = findNoArgMethod(levelType, "entitiesForRendering");
            }
            if (mcLevelField == null || entitiesForRenderingMethod == null) {
                reflectionAvailable = false;
            }
        } catch (Throwable t) {
            reflectionAvailable = false;
        } finally {
            reflectionInitialized = true;
        }
    }

    private static synchronized void initializeEntityAccessors(Class<?> entityClass) {
        try {
            entityGetIdMethod = findNoArgMethod(entityClass, "getId");
            entityGetXMethod = findNoArgMethod(entityClass, "getX");
            entityGetYMethod = findNoArgMethod(entityClass, "getY");
            entityGetZMethod = findNoArgMethod(entityClass, "getZ");
            entityGetBbWidthMethod = findNoArgMethod(entityClass, "getBbWidth");
            entityGetBbHeightMethod = findNoArgMethod(entityClass, "getBbHeight");
            entityXoField = findField(entityClass, "xo");
            entityYoField = findField(entityClass, "yo");
            entityZoField = findField(entityClass, "zo");
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> curr = clazz;
        while (curr != null && curr != Object.class) {
            try {
                Field f = curr.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                curr = curr.getSuperclass();
            }
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> clazz, String name) {
        Class<?> curr = clazz;
        while (curr != null && curr != Object.class) {
            try {
                Method m = curr.getDeclaredMethod(name);
                if (m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
            curr = curr.getSuperclass();
        }
        return null;
    }
}
