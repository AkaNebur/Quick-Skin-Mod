package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional immediate-buffer 3D Skin Layers bridge for the 26.1.2 family. */
public final class SkinLayers3DIntegration {
    private static final Logger SKIN_LAYERS_LOG = LoggerFactory.getLogger("QuickSkin-SkinLayers3D");
    private static final String BASE_CLASS = "dev.tr7zw.skinlayers.SkinLayersModBase";
    private static final String BASE_RESOURCE = "dev/tr7zw/skinlayers/SkinLayersModBase.class";
    private static final String API_CLASS = "dev.tr7zw.skinlayers.api.SkinLayersAPI";
    private static final String MESH_HELPER_CLASS = "dev.tr7zw.skinlayers.api.MeshHelper";
    private static final String MESH_CLASS = "dev.tr7zw.skinlayers.api.Mesh";
    private static final long CPM_MODEL_PROBE_TTL_NANOS = 500_000_000L;

    private static final Object MESH_INIT_LOCK = new Object();
    private static final Object REFRESH_INIT_LOCK = new Object();
    private static final ConcurrentMap<MeshCacheKey, PlayerMeshes> MESH_CACHE = new ConcurrentHashMap<>();

    private static volatile CapabilityState meshCapability = CapabilityState.UNCHECKED;
    private static volatile CapabilityState refreshCapability = CapabilityState.UNCHECKED;

    private static Object configInstance;
    private static Object meshHelperInstance;
    private static Method create3DMeshMethod;
    private static boolean create3DMeshSupportsMirror;
    private static Method meshRenderMethod;
    private static Method meshSetPositionMethod;

    private static Field headVoxelSizeField;
    private static Field bodyVoxelWidthSizeField;
    private static Field baseVoxelSizeField;
    private static Field enableHatField;
    private static Field enableJacketField;
    private static Field enableLeftSleeveField;
    private static Field enableRightSleeveField;
    private static Field enableLeftPantsField;
    private static Field enableRightPantsField;

    private static Field refreshInstanceField;
    private static Method refreshMethod;
    private static boolean refreshMethodIsStatic;

    private static final AtomicBoolean meshCapabilityLogged = new AtomicBoolean();
    private static final AtomicBoolean meshCapabilityFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean meshCreationFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean renderFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean immediateRenderSuccessLogged = new AtomicBoolean();
    private static final AtomicBoolean configReadFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshCapabilityLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshCapabilityFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshInvocationFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshInstanceMissingLogged = new AtomicBoolean();
    private static final AtomicBoolean cpmSuppressionLogged = new AtomicBoolean();
    private static final AtomicBoolean cpmProbeFailureLogged = new AtomicBoolean();

    private static volatile boolean cachedCpmLocalModelActive;
    private static volatile long nextCpmModelProbeNanos;

    private SkinLayers3DIntegration() {
    }

    public static boolean isAvailable() {
        return ensureMeshCapability();
    }

    public static void render3DLayers(PoseStack poseStack, MultiBufferSource bufferSource,
                                      int light, int overlay, PlayerModel model,
                                      Identifier skinLocation, boolean thinArms) {
        if (poseStack == null || bufferSource == null || model == null || skinLocation == null
                || shouldSuppressManualLayers() || !ensureMeshCapability()) {
            return;
        }

        PlayerMeshes meshes = getOrCreateMeshes(skinLocation, thinArms);
        if (meshes == null || !meshes.isValid()) {
            return;
        }

        try {
            VertexConsumer vertices = bufferSource.getBuffer(RenderTypes.entityTranslucent(skinLocation));
            if (getBooleanConfig(enableHatField)) {
                renderHeadLayer(poseStack, vertices, light, overlay, model, meshes.headMesh);
            }
            if (getBooleanConfig(enableJacketField)) {
                renderBodyLayer(poseStack, vertices, light, overlay, model, meshes.torsoMesh);
            }
            if (getBooleanConfig(enableLeftSleeveField)) {
                renderArmLayer(poseStack, vertices, light, overlay, model.leftArm,
                        meshes.leftArmMesh, false, thinArms);
            }
            if (getBooleanConfig(enableRightSleeveField)) {
                renderArmLayer(poseStack, vertices, light, overlay, model.rightArm,
                        meshes.rightArmMesh, true, thinArms);
            }
            if (getBooleanConfig(enableLeftPantsField)) {
                renderLegLayer(poseStack, vertices, light, overlay, model.leftLeg, meshes.leftLegMesh);
            }
            if (getBooleanConfig(enableRightPantsField)) {
                renderLegLayer(poseStack, vertices, light, overlay, model.rightLeg, meshes.rightLegMesh);
            }
            if (!renderFailureLogged.get() && immediateRenderSuccessLogged.compareAndSet(false, true)) {
                SKIN_LAYERS_LOG.info(
                        "3D Skin Layers immediate manual-preview rendering executed successfully on the 26.1.2 backend"
                );
            }
        } catch (RuntimeException | LinkageError e) {
            logRenderFailure(e);
        }
    }

    public static void refreshPlayer(Player player) {
        if (player == null || !ensureRefreshCapability()) {
            return;
        }
        try {
            Object target = null;
            if (!refreshMethodIsStatic) {
                target = refreshInstanceField.get(null);
                if (target == null) {
                    if (refreshInstanceMissingLogged.compareAndSet(false, true)) {
                        SKIN_LAYERS_LOG.warn(
                                "3D Skin Layers refreshLayers is present but SkinLayersModBase.instance is not initialized yet; "
                                        + "QuickSkin will leave the current third-party cache untouched"
                        );
                    }
                    return;
                }
            }
            refreshMethod.invoke(target, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (refreshInvocationFailureLogged.compareAndSet(false, true)) {
                SKIN_LAYERS_LOG.warn(
                        "3D Skin Layers player refresh failed; preview mesh support remains enabled and only refresh is degraded",
                        e
                );
            }
        }
    }

    public static void clearCache() {
        MESH_CACHE.clear();
        cachedCpmLocalModelActive = false;
        nextCpmModelProbeNanos = 0L;
    }

    private static boolean ensureMeshCapability() {
        CapabilityState state = meshCapability;
        if (state != CapabilityState.UNCHECKED) {
            return state == CapabilityState.AVAILABLE;
        }
        synchronized (MESH_INIT_LOCK) {
            if (meshCapability != CapabilityState.UNCHECKED) {
                return meshCapability == CapabilityState.AVAILABLE;
            }
            if (!classFileExists(BASE_RESOURCE)) {
                meshCapability = CapabilityState.UNAVAILABLE;
                return false;
            }
            try {
                initializeMeshCapability();
                meshCapability = CapabilityState.AVAILABLE;
                if (meshCapabilityLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.info(
                            "3D Skin Layers manual-preview mesh capability ready: backend=immediate-26.1.2, create3DMesh={} argument(s)",
                            create3DMeshSupportsMirror ? 9 : 8
                    );
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                meshCapability = CapabilityState.UNAVAILABLE;
                if (meshCapabilityFailureLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.warn(
                            "Detected 3D Skin Layers, but its public mesh/config API is incompatible. "
                                    + "QuickSkin's manual 3D-layer preview is disabled; normal skin/cape rendering is unaffected",
                            e
                    );
                }
                return false;
            }
        }
    }

    private static void initializeMeshCapability() throws ReflectiveOperationException {
        Class<?> baseClass = loadClass(BASE_CLASS);
        Class<?> apiClass = loadClass(API_CLASS);
        Class<?> meshHelperClass = loadClass(MESH_HELPER_CLASS);
        Class<?> meshClass = loadClass(MESH_CLASS);

        Object resolvedConfig = baseClass.getField("config").get(null);
        if (resolvedConfig == null) {
            throw new IllegalStateException("SkinLayersModBase.config is null");
        }
        Object resolvedMeshHelper = apiClass.getMethod("getMeshHelper").invoke(null);
        if (resolvedMeshHelper == null || !meshHelperClass.isInstance(resolvedMeshHelper)) {
            throw new IllegalStateException("SkinLayersAPI.getMeshHelper returned an incompatible value");
        }

        Method resolvedCreateMethod;
        boolean supportsMirror;
        try {
            resolvedCreateMethod = meshHelperClass.getMethod(
                    "create3DMesh", NativeImage.class,
                    int.class, int.class, int.class, int.class, int.class,
                    boolean.class, float.class, boolean.class
            );
            supportsMirror = true;
        } catch (NoSuchMethodException missingNineArgumentApi) {
            resolvedCreateMethod = meshHelperClass.getMethod(
                    "create3DMesh", NativeImage.class,
                    int.class, int.class, int.class, int.class, int.class,
                    boolean.class, float.class
            );
            supportsMirror = false;
        }

        Class<?> configClass = resolvedConfig.getClass();
        Field resolvedHeadVoxelSize = configClass.getField("headVoxelSize");
        Field resolvedBodyVoxelWidth = configClass.getField("bodyVoxelWidthSize");
        Field resolvedBaseVoxelSize = configClass.getField("baseVoxelSize");
        Field resolvedEnableHat = configClass.getField("enableHat");
        Field resolvedEnableJacket = configClass.getField("enableJacket");
        Field resolvedEnableLeftSleeve = configClass.getField("enableLeftSleeve");
        Field resolvedEnableRightSleeve = configClass.getField("enableRightSleeve");
        Field resolvedEnableLeftPants = configClass.getField("enableLeftPants");
        Field resolvedEnableRightPants = configClass.getField("enableRightPants");
        Method resolvedRender = meshClass.getMethod(
                "render", ModelPart.class, PoseStack.class, VertexConsumer.class,
                int.class, int.class, float.class, float.class, float.class, float.class
        );
        Method resolvedSetPosition = meshClass.getMethod(
                "setPosition", float.class, float.class, float.class
        );

        configInstance = resolvedConfig;
        meshHelperInstance = resolvedMeshHelper;
        create3DMeshMethod = resolvedCreateMethod;
        create3DMeshSupportsMirror = supportsMirror;
        headVoxelSizeField = resolvedHeadVoxelSize;
        bodyVoxelWidthSizeField = resolvedBodyVoxelWidth;
        baseVoxelSizeField = resolvedBaseVoxelSize;
        enableHatField = resolvedEnableHat;
        enableJacketField = resolvedEnableJacket;
        enableLeftSleeveField = resolvedEnableLeftSleeve;
        enableRightSleeveField = resolvedEnableRightSleeve;
        enableLeftPantsField = resolvedEnableLeftPants;
        enableRightPantsField = resolvedEnableRightPants;
        meshRenderMethod = resolvedRender;
        meshSetPositionMethod = resolvedSetPosition;
    }

    private static boolean ensureRefreshCapability() {
        CapabilityState state = refreshCapability;
        if (state != CapabilityState.UNCHECKED) {
            return state == CapabilityState.AVAILABLE;
        }
        synchronized (REFRESH_INIT_LOCK) {
            if (refreshCapability != CapabilityState.UNCHECKED) {
                return refreshCapability == CapabilityState.AVAILABLE;
            }
            if (!classFileExists(BASE_RESOURCE)) {
                refreshCapability = CapabilityState.UNAVAILABLE;
                return false;
            }
            try {
                Class<?> baseClass = loadClass(BASE_CLASS);
                try {
                    Field instanceField = baseClass.getField("instance");
                    Method currentMethod = baseClass.getMethod("refreshLayers", Player.class);
                    if (!Modifier.isStatic(instanceField.getModifiers())
                            || Modifier.isStatic(currentMethod.getModifiers())) {
                        throw new NoSuchMethodException("Expected static instance field and non-static refreshLayers(Player)");
                    }
                    refreshInstanceField = instanceField;
                    refreshMethod = currentMethod;
                    refreshMethodIsStatic = false;
                } catch (NoSuchFieldException | NoSuchMethodException missingCurrentApi) {
                    Method legacyMethod = baseClass.getMethod("refreshPlayer", Player.class);
                    if (!Modifier.isStatic(legacyMethod.getModifiers())) {
                        throw new NoSuchMethodException("Legacy refreshPlayer(Player) is not static");
                    }
                    refreshInstanceField = null;
                    refreshMethod = legacyMethod;
                    refreshMethodIsStatic = true;
                }
                refreshCapability = CapabilityState.AVAILABLE;
                if (refreshCapabilityLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.info(
                            "3D Skin Layers player-refresh capability ready: {}",
                            refreshMethodIsStatic ? "legacy static refreshPlayer" : "instance refreshLayers"
                    );
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                refreshCapability = CapabilityState.UNAVAILABLE;
                if (refreshCapabilityFailureLogged.compareAndSet(false, true)) {
                    SKIN_LAYERS_LOG.warn(
                            "Detected 3D Skin Layers, but no supported player-refresh API was found. "
                                    + "Only cache refresh is disabled; preview rendering remains independent",
                            e
                    );
                }
                return false;
            }
        }
    }

    private static PlayerMeshes getOrCreateMeshes(Identifier skinLocation, boolean thinArms) {
        if (skinLocation == null || !ensureMeshCapability()) {
            return null;
        }
        MeshCacheKey key = new MeshCacheKey(skinLocation, thinArms);
        PlayerMeshes cached = MESH_CACHE.get(key);
        if (cached != null && cached.isValid()) {
            return cached;
        }
        try {
            NativeImage skin = getSkinTexture(skinLocation);
            if (skin == null || skin.getWidth() != 64 || skin.getHeight() != 64) {
                return null;
            }
            PlayerMeshes created = new PlayerMeshes(
                    createMesh(skin, 8, 8, 8, 32, 0, false, 0.6f),
                    createMesh(skin, 8, 12, 4, 16, 32, true, 0f),
                    createMesh(skin, thinArms ? 3 : 4, 12, 4, 48, 48, true, -2f),
                    createMesh(skin, thinArms ? 3 : 4, 12, 4, 40, 32, true, -2f),
                    createMesh(skin, 4, 12, 4, 0, 48, true, 0f),
                    createMesh(skin, 4, 12, 4, 0, 32, true, 0f)
            );
            if (!created.isValid()) {
                return null;
            }
            PlayerMeshes raced = MESH_CACHE.putIfAbsent(key, created);
            return raced != null ? raced : created;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (meshCreationFailureLogged.compareAndSet(false, true)) {
                SKIN_LAYERS_LOG.warn(
                        "3D Skin Layers mesh creation failed for a 64x64 preview skin; "
                                + "this preview will use the normal flat overlay",
                        e
                );
            }
            return null;
        }
    }

    private static Object createMesh(NativeImage skin, int width, int height, int depth,
                                     int textureU, int textureV, boolean topPivot, float rotationOffset)
            throws ReflectiveOperationException {
        if (create3DMeshSupportsMirror) {
            return create3DMeshMethod.invoke(
                    meshHelperInstance, skin, width, height, depth,
                    textureU, textureV, topPivot, rotationOffset, false
            );
        }
        return create3DMeshMethod.invoke(
                meshHelperInstance, skin, width, height, depth,
                textureU, textureV, topPivot, rotationOffset
        );
    }

    private static NativeImage getSkinTexture(Identifier skinLocation) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            var resource = minecraft.getResourceManager().getResource(skinLocation);
            if (resource.isPresent()) {
                try (InputStream stream = resource.get().open()) {
                    return NativeImage.read(stream);
                }
            }
            AbstractTexture texture = minecraft.getTextureManager().getTexture(skinLocation);
            if (texture instanceof DynamicTexture dynamicTexture) {
                NativeImage pixels = dynamicTexture.getPixels();
                if (pixels != null) {
                    return pixels;
                }
            }
            File backingFile = findTextureBackingFile(texture);
            if (backingFile != null && backingFile.isFile()) {
                try (FileInputStream stream = new FileInputStream(backingFile)) {
                    return NativeImage.read(stream);
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
        return null;
    }

    private static File findTextureBackingFile(Object texture) {
        if (texture == null) {
            return null;
        }
        for (Class<?> current = texture.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!File.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    if (!field.canAccess(texture) && !field.trySetAccessible()) {
                        continue;
                    }
                    Object value = field.get(texture);
                    if (value instanceof File file) {
                        return file;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static void renderHeadLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay, PlayerModel model, Object mesh) {
        poseStack.pushPose();
        try {
            float voxelSize = getFloatConfig(headVoxelSizeField);
            model.head.translateAndRotate(poseStack);
            poseStack.translate(0, -0.25, 0);
            poseStack.scale(voxelSize, voxelSize, voxelSize);
            poseStack.translate(0, 0.25, 0);
            poseStack.translate(0, -0.04, 0);
            meshRenderMethod.invoke(mesh, model.head, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderBodyLayer(PoseStack poseStack, VertexConsumer vertices,
                                        int light, int overlay, PlayerModel model, Object mesh) {
        poseStack.pushPose();
        try {
            model.body.translateAndRotate(poseStack);
            poseStack.scale(
                    getFloatConfig(bodyVoxelWidthSizeField),
                    1.035f,
                    getFloatConfig(baseVoxelSizeField)
            );
            meshSetPositionMethod.invoke(mesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(mesh, model.body, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderArmLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart arm, Object mesh,
                                       boolean rightArm, boolean thinArms) {
        poseStack.pushPose();
        try {
            float pixelScale = getFloatConfig(baseVoxelSizeField);
            arm.translateAndRotate(poseStack);
            poseStack.scale(pixelScale, 1.035f, pixelScale);
            float x = thinArms ? 0.499f : 0.998f;
            meshSetPositionMethod.invoke(mesh, rightArm ? -x : x, -0.1f, 0f);
            meshRenderMethod.invoke(mesh, arm, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderLegLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, ModelPart leg, Object mesh) {
        poseStack.pushPose();
        try {
            float pixelScale = getFloatConfig(baseVoxelSizeField);
            leg.translateAndRotate(poseStack);
            poseStack.scale(pixelScale, 1.035f, pixelScale);
            meshSetPositionMethod.invoke(mesh, 0f, -0.2f, 0f);
            meshRenderMethod.invoke(mesh, leg, poseStack, vertices, light, overlay,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logRenderFailure(e);
        } finally {
            poseStack.popPose();
        }
    }

    private static void logRenderFailure(Throwable throwable) {
        if (renderFailureLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.warn(
                    "3D Skin Layers immediate preview rendering failed; remaining QuickSkin preview rendering continues",
                    throwable
            );
        }
    }

    private static boolean getBooleanConfig(Field field) {
        try {
            return field != null && field.getBoolean(configInstance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logConfigReadFailure(e);
            return false;
        }
    }

    private static float getFloatConfig(Field field) {
        try {
            return field != null ? field.getFloat(configInstance) : 1.0f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logConfigReadFailure(e);
            return 1.0f;
        }
    }

    private static void logConfigReadFailure(Throwable throwable) {
        if (configReadFailureLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.warn(
                    "3D Skin Layers config values could not be read; affected manual layers are disabled safely",
                    throwable
            );
        }
    }

    private static boolean shouldSuppressManualLayers() {
        try {
            ClientConfig config = ClientConfig.getInstance();
            String activeCpmHash = config != null ? config.activeCpmModelHash : null;
            if (activeCpmHash != null && !activeCpmHash.isEmpty()) {
                return logCpmSuppression("an explicit .cpmmodel selection is active");
            }
            if (CPMCompatIntegration.shouldDeferToCPM()) {
                return logCpmSuppression("a CPM-owned screen is active");
            }
            if (CPMCompatIntegration.isCPMActivelyRendering()) {
                return logCpmSuppression("CPM is currently rendering a custom player model");
            }
            long now = System.nanoTime();
            if (now >= nextCpmModelProbeNanos) {
                cachedCpmLocalModelActive = CPMCompatIntegration.isLocalPlayerWearingCpmModel();
                nextCpmModelProbeNanos = now + CPM_MODEL_PROBE_TTL_NANOS;
            }
            return cachedCpmLocalModelActive
                    && logCpmSuppression("CPM's local-player model cache reports an active custom model");
        } catch (RuntimeException | LinkageError e) {
            if (cpmProbeFailureLogged.compareAndSet(false, true)) {
                SKIN_LAYERS_LOG.warn(
                        "CPM activity could not be checked safely; suppressing QuickSkin's manual 3D layers to avoid model overlap",
                        e
                );
            }
            return true;
        }
    }

    private static boolean logCpmSuppression(String reason) {
        if (cpmSuppressionLogged.compareAndSet(false, true)) {
            SKIN_LAYERS_LOG.info("Suppressing manual 3D Skin Layers preview because {}", reason);
        }
        return true;
    }

    private static boolean classFileExists(String classFilePath) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null && contextLoader.getResource(classFilePath) != null) {
            return true;
        }
        ClassLoader ownLoader = SkinLayers3DIntegration.class.getClassLoader();
        return ownLoader != null && ownLoader.getResource(classFilePath) != null;
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(className, true, contextLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(className, true, SkinLayers3DIntegration.class.getClassLoader());
    }

    private enum CapabilityState {
        UNCHECKED,
        AVAILABLE,
        UNAVAILABLE
    }

    private record MeshCacheKey(Identifier skinLocation, boolean thinArms) {
    }

    private static final class PlayerMeshes {
        private final Object headMesh;
        private final Object torsoMesh;
        private final Object leftArmMesh;
        private final Object rightArmMesh;
        private final Object leftLegMesh;
        private final Object rightLegMesh;

        private PlayerMeshes(Object headMesh, Object torsoMesh,
                             Object leftArmMesh, Object rightArmMesh,
                             Object leftLegMesh, Object rightLegMesh) {
            this.headMesh = headMesh;
            this.torsoMesh = torsoMesh;
            this.leftArmMesh = leftArmMesh;
            this.rightArmMesh = rightArmMesh;
            this.leftLegMesh = leftLegMesh;
            this.rightLegMesh = rightLegMesh;
        }

        private boolean isValid() {
            return headMesh != null && torsoMesh != null
                    && leftArmMesh != null && rightArmMesh != null
                    && leftLegMesh != null && rightLegMesh != null;
        }
    }
}
