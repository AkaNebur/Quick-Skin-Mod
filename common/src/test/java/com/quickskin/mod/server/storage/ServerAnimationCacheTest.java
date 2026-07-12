package com.quickskin.mod.server.storage;

import com.quickskin.mod.common.util.HashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAnimationCacheTest {
    private static final UUID OWNER = UUID.fromString("6fb31f80-9a3e-4a4f-bdb8-79ef0e2c0c88");

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearSingletons() {
        ServerAnimationCache.getInstance().clear();
        ServerTextureCache.getInstance().clear();
    }

    @Test
    void legacyIdentitySurvivesDeliveryEvictionAndRestart() throws Exception {
        Path textures = Files.createDirectories(tempDirectory.resolve("textures"));
        Path animations = Files.createDirectories(tempDirectory.resolve("animations"));
        ServerTextureCache textureCache = ServerTextureCache.getInstance();
        ServerAnimationCache animationCache = ServerAnimationCache.getInstance();
        textureCache.clear();
        animationCache.clear();
        setField(textureCache, "storageDirectory", textures);
        setField(animationCache, "storageDirectory", animations);

        byte[] cape = legacyCapePng();
        String hash = HashUtil.computeHash(cape);
        String original = metadata(50);
        String retimed = metadata(125);
        assertTrue(textureCache.storeTexture(hash, OWNER, "cape", cape));
        assertTrue(animationCache.storeMetadata(hash, original, OWNER));

        // Force the same delivery-only LRU path used when the bounded JSON cache fills.
        setField(animationCache, "cachedBytes", 16L * 1024 * 1024 + 1);
        invoke(animationCache, "evictToLimits");
        assertNull(animationCache.getMetadata(hash));
        assertTrue(Files.isRegularFile(animations.resolve(hash + ".identity")));
        assertFalse(animationCache.isMetadataIdentityCompatible(hash, retimed));
        assertFalse(animationCache.storeMetadata(hash, retimed, UUID.randomUUID()));

        // Restart with no delivery JSON: the independent identity must still load and reject a
        // different timing claim while the backing cape remains cached.
        animationCache.clear();
        setField(animationCache, "storageDirectory", animations);
        invoke(animationCache, "loadCachedMetadata");
        assertFalse(animationCache.isMetadataIdentityCompatible(hash, retimed));
        assertFalse(animationCache.storeMetadata(hash, retimed, UUID.randomUUID()));
        assertTrue(animationCache.storeMetadata(hash, original, UUID.randomUUID()));
    }

    private static String metadata(int delay) {
        return "{\"frames\":[{\"delay\":" + delay
                + ",\"index\":0}],\"frameCount\":1}";
    }

    private static byte[] legacyCapePng() throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x7f336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }
}
