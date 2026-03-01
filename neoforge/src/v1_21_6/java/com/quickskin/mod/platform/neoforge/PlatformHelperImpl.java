package com.quickskin.mod.platform.neoforge;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge implementation of PlatformHelper
 * This class provides NeoForge-specific implementations for @ExpectPlatform methods
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {

    public static String getPlatformName() {
        return "NeoForge";
    }

    public static Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path getSkinsDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin").resolve("uploads").resolve("skins");
    }

    public static Path getCapesDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin").resolve("uploads").resolve("capes");
    }

    public static Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path getCacheDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin_cache");
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static String getModVersion() {
        return ModList.get()
            .getModContainerById("quickskin")
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("UNKNOWN");
    }

    public static boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    private static java.lang.reflect.Method setPixelMethod;
    private static java.lang.reflect.Method getPixelMethod;
    private static java.lang.reflect.Field youngField;
    private static java.lang.reflect.Field crouchingField;
    private static java.lang.reflect.Field ridingField;
    private static java.lang.reflect.Field attackTimeField;
    private static java.lang.reflect.Method blitMethod;
    private static java.lang.reflect.Method blitMethod2;
    private static java.lang.reflect.Method renderCloakMethod;
    private static boolean methodsInitialized = false;
    private static boolean modelFieldsChecked = false;
    private static boolean blitMethodChecked = false;
    private static boolean blitMethod2Checked = false;
    private static boolean renderCloakMethodChecked = false;
    private static boolean usesNewPixelFormat = false; // True if MC 1.21.2+ (uses ARGB instead of ABGR)

    private static void initializeMethods() {
        if (methodsInitialized) return;
        methodsInitialized = true;

        try {
            // Try to find the 1.21.2+ methods first
            try {
                setPixelMethod = NativeImage.class.getMethod("setPixel", int.class, int.class, int.class);
                getPixelMethod = NativeImage.class.getMethod("getPixel", int.class, int.class);
                // MC 1.21.2+ uses setPixel/getPixel and changed color format from ABGR to ARGB
                usesNewPixelFormat = true;
            } catch (NoSuchMethodException e) {
                // Fall back to 1.21.1 methods
                setPixelMethod = NativeImage.class.getMethod("setPixelRGBA", int.class, int.class, int.class);
                getPixelMethod = NativeImage.class.getMethod("getPixelRGBA", int.class, int.class);
                // MC 1.21.1 uses setPixelRGBA/getPixelRGBA with ABGR format
                usesNewPixelFormat = false;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize NativeImage methods", e);
        }
    }

    /**
     * Converts ABGR color to ARGB color by swapping red and blue channels
     */
    private static int abgrToArgb(int abgr) {
        int a = (abgr >> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Converts ARGB color to ABGR color by swapping red and blue channels
     */
    private static int argbToAbgr(int argb) {
        // Same operation as abgrToArgb since it's just swapping R and B
        return abgrToArgb(argb);
    }

    private static void initializeModelFields() {
        if (modelFieldsChecked) return;
        modelFieldsChecked = true;

        // Try to find fields (1.21.1) - may not exist in 1.21.2+
        try {
            youngField = PlayerModel.class.getField("young");
        } catch (NoSuchFieldException e) {
            youngField = null;
        }

        try {
            crouchingField = PlayerModel.class.getField("crouching");
        } catch (NoSuchFieldException e) {
            crouchingField = null;
        }

        try {
            ridingField = PlayerModel.class.getField("riding");
        } catch (NoSuchFieldException e) {
            ridingField = null;
        }

        try {
            attackTimeField = PlayerModel.class.getField("attackTime");
        } catch (NoSuchFieldException e) {
            attackTimeField = null;
        }
    }

    /**
     * Sets a pixel in a NativeImage
     * Compatible with both MC 1.21.1 (setPixelRGBA, ABGR format) and 1.21.2+ (setPixel, ARGB format)
     * @param color Color in ABGR format (our standard format used throughout the codebase)
     */
    public static void setPixel(NativeImage image, int x, int y, int color) {
        initializeMethods();
        try {
            // Convert color format if needed
            int colorToSet = color;
            if (usesNewPixelFormat) {
                // MC 1.21.2+ expects ARGB, but we're passing ABGR, so convert
                colorToSet = abgrToArgb(color);
            }
            setPixelMethod.invoke(image, x, y, colorToSet);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set pixel", e);
        }
    }

    /**
     * Gets a pixel from a NativeImage
     * Compatible with both MC 1.21.1 (getPixelRGBA, ABGR format) and 1.21.2+ (getPixel, ARGB format)
     * @return Color in ABGR format (our standard format used throughout the codebase)
     */
    public static int getPixel(NativeImage image, int x, int y) {
        initializeMethods();
        try {
            int color = (int) getPixelMethod.invoke(image, x, y);
            // Convert color format if needed
            if (usesNewPixelFormat) {
                // MC 1.21.2+ returns ARGB, but we need ABGR, so convert
                color = argbToAbgr(color);
            }
            return color;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get pixel", e);
        }
    }

    /**
     * Sets the 'young' field on a PlayerModel
     * Compatible with both MC 1.21.1 (has 'young' field) and 1.21.2+ (field removed)
     */
    public static void setYoung(PlayerModel model, boolean young) {
        initializeModelFields();
        if (youngField != null) {
            try {
                youngField.setBoolean(model, young);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set young field", e);
            }
        }
        // If field doesn't exist (1.21.2+), silently ignore
    }

    /**
     * Sets the 'crouching' field on a PlayerModel
     * Compatible with both MC 1.21.1 (has 'crouching' field) and 1.21.2+ (field removed)
     */
    public static void setCrouching(PlayerModel model, boolean crouching) {
        initializeModelFields();
        if (crouchingField != null) {
            try {
                crouchingField.setBoolean(model, crouching);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set crouching field", e);
            }
        }
        // If field doesn't exist (1.21.2+), silently ignore
    }

    /**
     * Sets the 'riding' field on a PlayerModel
     * Compatible with both MC 1.21.1 (has 'riding' field) and 1.21.2+ (field removed)
     */
    public static void setRiding(PlayerModel model, boolean riding) {
        initializeModelFields();
        if (ridingField != null) {
            try {
                ridingField.setBoolean(model, riding);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set riding field", e);
            }
        }
        // If field doesn't exist (1.21.2+), silently ignore
    }

    /**
     * Sets the 'attackTime' field on a PlayerModel
     * Compatible with both MC 1.21.1 (has 'attackTime' field) and 1.21.2+ (field removed)
     */
    public static void setAttackTime(PlayerModel model, float attackTime) {
        initializeModelFields();
        if (attackTimeField != null) {
            try {
                attackTimeField.setFloat(model, attackTime);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set attackTime field", e);
            }
        }
        // If field doesn't exist (1.21.2+), silently ignore
    }

    private static void initializeBlitMethod() {
        if (blitMethodChecked) return;
        blitMethodChecked = true;

        // Try multiple possible signatures to support different MC versions

        // 1.21.1: blit(ResourceLocation, int x, int y, int blitOffset, float u, float v, int width, int height, int textureWidth, int textureHeight)
        try {
            blitMethod = GuiGraphics.class.getMethod("blit",
                ResourceLocation.class, int.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            return;
        } catch (NoSuchMethodException e) {
            // Try next signature
        }

        // 1.21.1: blit(ResourceLocation, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight)
        try {
            blitMethod = GuiGraphics.class.getMethod("blit",
                ResourceLocation.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            return;
        } catch (NoSuchMethodException e) {
            // Try next signature
        }

        // 1.21.2+: blit(Function, ResourceLocation, int x, int y, int z, float u, float v, int width, int height, int textureWidth, int textureHeight)
        try {
            blitMethod = GuiGraphics.class.getMethod("blit",
                java.util.function.Function.class, ResourceLocation.class, int.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            return;
        } catch (NoSuchMethodException e) {
            // Try next signature
        }

        // 1.21.2+: blit(Function, ResourceLocation, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight)
        try {
            blitMethod = GuiGraphics.class.getMethod("blit",
                java.util.function.Function.class, ResourceLocation.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            return;
        } catch (NoSuchMethodException e) {
            // Try next signature
        }

        // List all available blit methods for debugging
        StringBuilder availableMethods = new StringBuilder("Available blit methods in GuiGraphics:\n");
        for (java.lang.reflect.Method method : GuiGraphics.class.getMethods()) {
            if (method.getName().equals("blit")) {
                availableMethods.append("  - blit(");
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) availableMethods.append(", ");
                    availableMethods.append(params[i].getSimpleName());
                }
                availableMethods.append(")\n");
            }
        }

        throw new RuntimeException("Could not find any compatible blit method. " + availableMethods.toString());
    }

    /**
     * Blits a texture to the screen
     * Compatible with both MC 1.21.1 (has blitOffset parameter) and 1.21.2+ (removed blitOffset)
     */
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        initializeBlitMethod();
        try {
            Class<?>[] paramTypes = blitMethod.getParameterTypes();

            // Check if first parameter is Function (1.21.2+)
            if (paramTypes.length > 0 && paramTypes[0].getName().equals("java.util.function.Function")) {
                // 1.21.2+ version with Function parameter
                // Get RenderType.guiTextured() method reference
                Object renderTypeFunction = getRenderTypeGuiTextured();

                if (paramTypes.length == 11) {
                    // Version with z parameter: blit(Function, ResourceLocation, x, y, z, u, v, width, height, textureWidth, textureHeight)
                    blitMethod.invoke(graphics, renderTypeFunction, texture, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight);
                } else if (paramTypes.length == 10) {
                    // Version without z: blit(Function, ResourceLocation, x, y, u, v, width, height, textureWidth, textureHeight)
                    blitMethod.invoke(graphics, renderTypeFunction, texture, x, y, u, v, width, height, textureWidth, textureHeight);
                } else {
                    throw new RuntimeException("Unexpected 1.21.2 blit method parameter count: " + paramTypes.length);
                }
            } else if (paramTypes.length == 10) {
                // 1.21.1 version with blitOffset: blit(ResourceLocation, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight)
                blitMethod.invoke(graphics, texture, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight);
            } else if (paramTypes.length == 9) {
                // 1.21.1 version without blitOffset: blit(ResourceLocation, x, y, u, v, width, height, textureWidth, textureHeight)
                blitMethod.invoke(graphics, texture, x, y, u, v, width, height, textureWidth, textureHeight);
            } else {
                throw new RuntimeException("Unexpected blit method parameter count: " + paramTypes.length);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to call blit method", e);
        }
    }

    private static Object renderTypeGuiTextured = null;
    private static boolean renderTypeChecked = false;

    /**
     * Gets a Function wrapper for RenderType.gui() for 1.21.2+
     * The blit method needs Function<ResourceLocation, RenderType>, not just RenderType
     */
    private static Object getRenderTypeGuiTextured() {
        if (renderTypeChecked) {
            return renderTypeGuiTextured;
        }
        renderTypeChecked = true;

        try {
            Class<?> renderTypeClass = Class.forName("net.minecraft.client.renderer.RenderType");

            // Find RenderType.gui() method - it returns RenderType
            java.lang.reflect.Method guiMethod = null;
            String[] preferredNames = {"gui", "guiTextured", "guiTexture", "guiOverlay"};

            for (String methodName : preferredNames) {
                try {
                    guiMethod = renderTypeClass.getMethod(methodName);
                    if (java.lang.reflect.Modifier.isStatic(guiMethod.getModifiers()) &&
                        guiMethod.getParameterCount() == 0 &&
                        guiMethod.getReturnType().getName().equals(renderTypeClass.getName())) {
                        break;
                    }
                    guiMethod = null;
                } catch (NoSuchMethodException e) {
                    // Try next method name
                }
            }

            if (guiMethod == null) {
                // Debug: list all available methods
                StringBuilder debug = new StringBuilder("Could not find suitable RenderType method. Available methods:\n");
                for (java.lang.reflect.Method m : renderTypeClass.getMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
                        debug.append("  - ").append(m.getName()).append("() -> ").append(m.getReturnType().getName()).append("\n");
                    }
                }
                throw new RuntimeException(debug.toString());
            }

            // Create a Function<ResourceLocation, RenderType> that ignores the ResourceLocation parameter
            // and always returns the result of RenderType.gui()
            final java.lang.reflect.Method finalGuiMethod = guiMethod;
            renderTypeGuiTextured = new java.util.function.Function<ResourceLocation, Object>() {
                @Override
                public Object apply(ResourceLocation resourceLocation) {
                    try {
                        return finalGuiMethod.invoke(null);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to invoke RenderType.gui()", e);
                    }
                }
            };

            return renderTypeGuiTextured;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to find RenderType class", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RenderType function wrapper", e);
        }
    }

    private static void initializeBlitMethod2() {
        if (blitMethod2Checked) return;
        blitMethod2Checked = true;

        // 1.21.1: blit(ResourceLocation, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight)
        try {
            blitMethod2 = GuiGraphics.class.getMethod("blit",
                ResourceLocation.class, int.class, int.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            return;
        } catch (NoSuchMethodException e) {
            // Try next signature
        }

        // 1.21.2+: 12-param with separate regionWidth/regionHeight (Function)
        try {
            blitMethod2 = GuiGraphics.class.getMethod("blit",
                java.util.function.Function.class, ResourceLocation.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class, int.class, int.class);
            return;
        } catch (NoSuchMethodException e) {
            // Try next signature
        }

        // 1.21.6+: 12-param with RenderPipeline instead of Function
        try {
            Class<?> renderPipelineClass = Class.forName("com.mojang.blaze3d.pipeline.RenderPipeline");
            blitMethod2 = GuiGraphics.class.getMethod("blit",
                renderPipelineClass, ResourceLocation.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class, int.class, int.class);
            return;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // Try next signature
        }

        // Fallback: 10-param (no separate region sizes)
        try {
            blitMethod2 = GuiGraphics.class.getMethod("blit",
                java.util.function.Function.class, ResourceLocation.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            return;
        } catch (NoSuchMethodException e) {
            // Try next signature
        }

        // Fallback: 10-param with RenderPipeline
        try {
            Class<?> renderPipelineClass = Class.forName("com.mojang.blaze3d.pipeline.RenderPipeline");
            blitMethod2 = GuiGraphics.class.getMethod("blit",
                renderPipelineClass, ResourceLocation.class, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            return;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // Try next signature
        }

        // List all available blit methods for debugging
        StringBuilder availableMethods = new StringBuilder("Available blit methods in GuiGraphics for overload 2:\n");
        for (java.lang.reflect.Method method : GuiGraphics.class.getMethods()) {
            if (method.getName().equals("blit")) {
                availableMethods.append("  - blit(");
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) availableMethods.append(", ");
                    availableMethods.append(params[i].getSimpleName());
                }
                availableMethods.append(")\n");
            }
        }

        throw new RuntimeException("Could not find any compatible blit method (overload 2). " + availableMethods.toString());
    }

    /**
     * Blits a texture to the screen with width/height before UV coordinates
     * Compatible with MC 1.21.1 and 1.21.2+ (Function/RenderPipeline parameter)
     *
     * In 1.21.1: width/height are for screen size, regionWidth/regionHeight are for texture sampling size
     * In 1.21.2+: uses 12-param overload with separate region sizes
     */
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        initializeBlitMethod2();
        try {
            Class<?>[] paramTypes = blitMethod2.getParameterTypes();

            if (paramTypes[0] == ResourceLocation.class) {
                // 1.21.1 version (11 params total)
                blitMethod2.invoke(graphics, texture, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
            } else if (paramTypes.length == 12) {
                // 1.21.2+: 12-param with separate regionWidth/regionHeight
                Object renderTypeArg = getRenderTypeGuiTextured();
                blitMethod2.invoke(graphics, renderTypeArg, texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight);
            } else {
                // 10-param fallback (no separate region sizes)
                Object renderTypeArg = getRenderTypeGuiTextured();
                blitMethod2.invoke(graphics, renderTypeArg, texture, x, y, u, v, width, height, textureWidth, textureHeight);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to call blit method (overload 2)", e);
        }
    }

    private static java.lang.reflect.Field cloakField;
    private static boolean cloakFieldChecked = false;

    private static void initializeRenderCloakMethod() {
        if (renderCloakMethodChecked) return;
        renderCloakMethodChecked = true;

        // Try multiple possible signatures to support different MC versions

        // 1.21.1: renderCloak(PoseStack, VertexConsumer, int packedLight, int packedOverlay)
        try {
            renderCloakMethod = PlayerModel.class.getMethod("renderCloak",
                PoseStack.class, VertexConsumer.class, int.class, int.class);
            return;
        } catch (NoSuchMethodException e) {
            // renderCloak method doesn't exist - will use direct ModelPart rendering in 1.21.2+
            renderCloakMethod = null;
        }
    }

    private static void initializeCloakField() {
        if (cloakFieldChecked) return;
        cloakFieldChecked = true;

        // Try to find the cloak ModelPart field
        try {
            cloakField = PlayerModel.class.getField("cloak");
        } catch (NoSuchFieldException e) {
            cloakField = null;
        }
    }

    /**
     * Renders a cloak on a PlayerModel
     * Compatible with both MC 1.21.1 (uses renderCloak method) and 1.21.2+ (renders cloak ModelPart directly)
     */
    public static void renderCloak(PlayerModel model, PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay) {
        initializeRenderCloakMethod();

        if (renderCloakMethod != null) {
            // 1.21.1: Use the renderCloak method
            try {
                renderCloakMethod.invoke(model, poseStack, vertexConsumer, packedLight, packedOverlay);
            } catch (Exception e) {
                throw new RuntimeException("Failed to call renderCloak method", e);
            }
        } else {
            // 1.21.2+: renderCloak method doesn't exist, render the cloak ModelPart directly
            initializeCloakField();
            if (cloakField != null) {
                try {
                    Object cloakPart = cloakField.get(model);
                    if (cloakPart != null) {
                        // The cloak is a ModelPart, call its render method
                        java.lang.reflect.Method renderMethod = cloakPart.getClass().getMethod("render",
                            PoseStack.class, VertexConsumer.class, int.class, int.class);
                        renderMethod.invoke(cloakPart, poseStack, vertexConsumer, packedLight, packedOverlay);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to render cloak ModelPart directly", e);
                }
            }
            // If cloak field doesn't exist, silently skip rendering (shouldn't happen)
        }
    }
}
