package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.QuickSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration with 3D Skin Layers mod using reflection.
 * This allows the code to compile without the mod as a dependency,
 * and gracefully handles when the mod is not installed at runtime.
 */
public class SkinLayers3DIntegration {
    private static final Map<Identifier, PlayerMeshes> meshCache = new HashMap<>();
    private static boolean MOD_AVAILABLE;

    private static Object configInstance;
    private static Object meshHelperInstance;
    private static Method create3DMeshMethod;
    private static Method meshRenderMethod;
    private static Method meshSetPositionMethod;

    // Config field references
    private static Field headVoxelSizeField;
    private static Field bodyVoxelWidthSizeField;
    private static Field baseVoxelSizeField;
    private static Field enableHatField;
    private static Field enableJacketField;
    private static Field enableLeftSleeveField;
    private static Field enableRightSleeveField;
    private static Field enableLeftPantsField;
    private static Field enableRightPantsField;

    static {
        try {
            // Try to load the main mod class
            // Reflected classes and methods (cached after first successful reflection)
            Class<?> skinLayersModBaseClass = Class.forName("dev.tr7zw.skinlayers.SkinLayersModBase");
            Class<?> skinLayersAPIClass = Class.forName("dev.tr7zw.skinlayers.api.SkinLayersAPI");
            Class<?> meshClass = Class.forName("dev.tr7zw.skinlayers.api.Mesh");

            // Get config instance - search through class hierarchy
            Field configField = null;

            // Try public field first (searches entire hierarchy automatically)
            try {
                configField = skinLayersModBaseClass.getField("config");
            } catch (NoSuchFieldException e1) {
                // If not public, search through hierarchy manually
                Class<?> currentClass = skinLayersModBaseClass;
                while (currentClass != null && currentClass != Object.class) {
                    try {
                        configField = currentClass.getDeclaredField("config");
                        break;
                    } catch (NoSuchFieldException e2) {
                        currentClass = currentClass.getSuperclass();
                    }
                }

                if (configField == null) {
                    throw new NoSuchFieldException("config field not found in class hierarchy");
                }
            }

            configField.setAccessible(true);
            configInstance = configField.get(null);  // null because it's a static field

            if (configInstance == null) {
                throw new IllegalStateException("Config instance is null");
            }

            // Get config fields
            Class<?> configClass = configInstance.getClass();
            headVoxelSizeField = configClass.getDeclaredField("headVoxelSize");
            bodyVoxelWidthSizeField = configClass.getDeclaredField("bodyVoxelWidthSize");
            baseVoxelSizeField = configClass.getDeclaredField("baseVoxelSize");
            enableHatField = configClass.getDeclaredField("enableHat");
            enableJacketField = configClass.getDeclaredField("enableJacket");
            enableLeftSleeveField = configClass.getDeclaredField("enableLeftSleeve");
            enableRightSleeveField = configClass.getDeclaredField("enableRightSleeve");
            enableLeftPantsField = configClass.getDeclaredField("enableLeftPants");
            enableRightPantsField = configClass.getDeclaredField("enableRightPants");

            // Get mesh helper
            Method getMeshHelperMethod = skinLayersAPIClass.getDeclaredMethod("getMeshHelper");
            meshHelperInstance = getMeshHelperMethod.invoke(null);

            // Get mesh creation method
            create3DMeshMethod = meshHelperInstance.getClass().getDeclaredMethod(
                    "create3DMesh", NativeImage.class, int.class, int.class, int.class,
                    int.class, int.class, boolean.class, float.class);
            create3DMeshMethod.setAccessible(true); // Required for accessing methods across module boundaries

            // Get mesh methods
            meshRenderMethod = meshClass.getDeclaredMethod(
                    "render", ModelPart.class, PoseStack.class, VertexConsumer.class,
                    int.class, int.class, float.class, float.class, float.class, float.class);
            meshRenderMethod.setAccessible(true); // Required for accessing methods across module boundaries

            meshSetPositionMethod = meshClass.getDeclaredMethod(
                    "setPosition", float.class, float.class, float.class);
            meshSetPositionMethod.setAccessible(true); // Required for accessing methods across module boundaries

            MOD_AVAILABLE = true;
        } catch (Exception e) {
            MOD_AVAILABLE = false;
        }
    }

    public static boolean isAvailable() {
        return MOD_AVAILABLE;
    }

    // NOTE (26.2): render3DLayers(PoseStack, MultiBufferSource, ...) was removed during the port to 26.2.
    // MultiBufferSource no longer exists (the immediate buffer pipeline was replaced by the deferred
    // SubmitNodeCollector), and this entry point had no callers in the current codebase — 3D Skin Layers
    // entity rendering is driven automatically through the mod's entity layers, not this manual path.
    // The mesh-building helpers below are retained for a future reimplementation against the new API.

    private static class PlayerMeshes {
        Object headMesh;
        Object torsoMesh;
        Object leftArmMesh;
        Object rightArmMesh;
        Object leftLegMesh;
        Object rightLegMesh;

        boolean isValid() {
            return headMesh != null && torsoMesh != null && leftArmMesh != null &&
                    rightArmMesh != null && leftLegMesh != null && rightLegMesh != null;
        }
    }

    private static PlayerMeshes getOrCreateMeshes(Identifier skinLocation, boolean thinArms) {
        PlayerMeshes cached = meshCache.get(skinLocation);
        if (cached != null && cached.isValid()) {
            return cached;
        }

        try {
            NativeImage skin = getSkinTexture(skinLocation);
            if (skin == null || skin.getWidth() != 64 || skin.getHeight() != 64) {
                return null;
            }

            PlayerMeshes meshes = new PlayerMeshes();

            // Create meshes using the same parameters as 3D Skin Layers mod
            meshes.headMesh = create3DMeshMethod.invoke(meshHelperInstance, skin, 8, 8, 8, 32, 0, false, 0.6f);
            meshes.torsoMesh = create3DMeshMethod.invoke(meshHelperInstance, skin, 8, 12, 4, 16, 32, true, 0f);
            meshes.leftLegMesh = create3DMeshMethod.invoke(meshHelperInstance, skin, 4, 12, 4, 0, 48, true, 0f);
            meshes.rightLegMesh = create3DMeshMethod.invoke(meshHelperInstance, skin, 4, 12, 4, 0, 32, true, 0f);

            if (thinArms) {
                meshes.leftArmMesh = create3DMeshMethod.invoke(meshHelperInstance, skin, 3, 12, 4, 48, 48, true, -2f);
                meshes.rightArmMesh = create3DMeshMethod.invoke(meshHelperInstance, skin, 3, 12, 4, 40, 32, true, -2f);
            } else {
                meshes.leftArmMesh = create3DMeshMethod.invoke(meshHelperInstance, skin, 4, 12, 4, 48, 48, true, -2f);
                meshes.rightArmMesh = create3DMeshMethod.invoke(meshHelperInstance, skin, 4, 12, 4, 40, 32, true, -2f);
            }

            if (meshes.isValid()) {
                meshCache.put(skinLocation, meshes);
                return meshes;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static NativeImage getSkinTexture(Identifier skinLocation) {
        try {
            Minecraft mc = Minecraft.getInstance();

            // Try resource manager first
            var optionalRes = mc.getResourceManager().getResource(skinLocation);
            if (optionalRes.isPresent()) {
                return NativeImage.read(optionalRes.get().open());
            }

            // Try texture manager for downloaded skins
            AbstractTexture texture = mc.getTextureManager().getTexture(skinLocation);

            // Handle DynamicTexture (used by QuickSkin for local skins)
            if (texture instanceof DynamicTexture) {
                NativeImage pixels = ((DynamicTexture) texture).getPixels();
                if (pixels != null) {
                    return pixels;
                }
            }
            // Use reflection to access HttpTexture file field (class may not exist in all MC versions)
            else {
                try {
                    Class<?> httpTextureClass = Class.forName("net.minecraft.client.renderer.texture.HttpTexture");
                    if (httpTextureClass.isInstance(texture)) {
                        Field fileField = httpTextureClass.getDeclaredField("file");
                        fileField.setAccessible(true);
                        File file = (File) fileField.get(texture);

                        if (file != null && file.isFile()) {
                            return NativeImage.read(new FileInputStream(file));
                        }
                    }
                } catch (Exception e) {
                    return null;
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    private static void renderHeadLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay, PlayerModel model, Object headMesh) {
        try {
            float voxelSize = getFloatConfig(headVoxelSizeField);
            poseStack.pushPose();
            model.head.translateAndRotate(poseStack);
            poseStack.translate(0, -0.25, 0);
            poseStack.scale(voxelSize, voxelSize, voxelSize);
            poseStack.translate(0, 0.25, 0);
            poseStack.translate(0, -0.04, 0);
            meshRenderMethod.invoke(headMesh, model.head, poseStack, vertices, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            poseStack.popPose();
        } catch (Exception e) {
            // Silently fail
        }
    }

    private static void renderBodyLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay, PlayerModel model, Object torsoMesh) {
        try {
            float widthScaling = getFloatConfig(bodyVoxelWidthSizeField);
            float heightScaling = 1.035f;
            float pixelScaling = getFloatConfig(baseVoxelSizeField);

            poseStack.pushPose();
            model.body.translateAndRotate(poseStack);
            poseStack.scale(widthScaling, heightScaling, pixelScaling);
            meshSetPositionMethod.invoke(torsoMesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(torsoMesh, model.body, poseStack, vertices, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            poseStack.popPose();
        } catch (Exception e) {
            // Silently fail
        }
    }

    private static void renderArmLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart arm,
                                       Object armMesh, boolean isRightArm, boolean thinArms) {
        try {
            float pixelScaling = getFloatConfig(baseVoxelSizeField);
            float heightScaling = 1.035f;

            poseStack.pushPose();
            arm.translateAndRotate(poseStack);
            poseStack.scale(pixelScaling, heightScaling, pixelScaling);

            float x = thinArms ? 0.499f : 0.998f;
            if (isRightArm) x *= -1;

            meshSetPositionMethod.invoke(armMesh, x, -0.1f, 0f);
            meshRenderMethod.invoke(armMesh, arm, poseStack, vertices, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            poseStack.popPose();
        } catch (Exception e) {
            // Silently fail
        }
    }

    private static void renderLegLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart leg, Object legMesh) {
        try {
            float pixelScaling = getFloatConfig(baseVoxelSizeField);
            float heightScaling = 1.035f;

            poseStack.pushPose();
            leg.translateAndRotate(poseStack);
            poseStack.scale(pixelScaling, heightScaling, pixelScaling);
            meshSetPositionMethod.invoke(legMesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(legMesh, leg, poseStack, vertices, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            poseStack.popPose();
        } catch (Exception e) {
            // Silently fail
        }
    }

    private static boolean getBooleanConfig(Field field) {
        try {
            return field.getBoolean(configInstance);
        } catch (Exception e) {
            return false;
        }
    }

    private static float getFloatConfig(Field field) {
        try {
            return field.getFloat(configInstance);
        } catch (Exception e) {
            return 1.0f;
        }
    }

    public static void clearCache() {
        meshCache.clear();
    }

}