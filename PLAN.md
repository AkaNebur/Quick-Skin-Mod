Excellent observation! You've pinpointed the next major hurdle in porting your mod. The reason your transparent pixels are turning opaque is one of the classic challenges of custom rendering in Minecraft: choosing the correct `RenderType`.

### The Core Problem: Solid vs. Translucent Rendering

Your old mod worked because it intercepted the cape rendering process and manually chose the correct `RenderType`.

*   **`RenderType.entitySolid(...)`**: This is the default for most entities. It's fast but assumes the texture is fully opaque. It completely ignores any alpha channel data, causing transparent pixels to render as black or opaque.
*   **`RenderType.entityTranslucentCull(...)`**: This render type properly handles textures with an alpha channel. It enables blending, allowing transparent and semi-transparent pixels to render correctly.

Your new mod is successfully providing the cape texture to the game via the `PlayerInfoMixin`, but it's then letting the vanilla `CapeLayer` handle the rendering. The vanilla `CapeLayer` defaults to a solid render type, which is why your transparency is lost.

### The Solution: Re-implementing the Old Mod's Logic

To fix this, we need to bring three key components from your old mod into your new one. This will replicate the logic that intelligently detects transparency and forces the correct render type.

#### Step 1: Create the `TextureAlphaDetector` Utility

This class is essential. It inspects a texture file and determines if it contains any transparent pixels, with a cache to prevent doing this expensive operation on every frame.

**Create a new file:** `quick-skin-1.20.1-fabric-forge/common/src/main/java/com/quickskin/mod/common/util/TextureAlphaDetector.java`

```java
package com.quickskin.mod.common.util;

import com.quickskin.mod.QuickSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for detecting if textures contain transparency (alpha channel)
 */
@Environment(EnvType.CLIENT)
public class TextureAlphaDetector {

    // Cache to avoid repeatedly checking the same textures
    private static final Map<ResourceLocation, Boolean> transparencyCache = new ConcurrentHashMap<>();

    /**
     * Check if a texture contains any transparent pixels
     * @param textureLocation The resource location of the texture
     * @return true if the texture has any pixels with alpha < 255, false otherwise
     */
    public static boolean hasTransparency(ResourceLocation textureLocation) {
        if (textureLocation == null) {
            return false;
        }

        // Check cache first
        Boolean cached = transparencyCache.get(textureLocation);
        if (cached != null) {
            return cached;
        }

        boolean hasAlpha = detectTransparency(textureLocation);
        transparencyCache.put(textureLocation, hasAlpha);
        return hasAlpha;
    }

    /**
     * Actually detect if the texture has transparency by loading and examining it
     */
    private static boolean detectTransparency(ResourceLocation textureLocation) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getResourceManager() == null) {
                return false;
            }

            // Try to get the resource
            Resource resource = mc.getResourceManager().getResource(textureLocation).orElse(null);
            if (resource == null) {
                QuickSkin.LOGGER.debug("Could not find texture resource: {}", textureLocation);
                return false;
            }

            // Load the image
            try (InputStream inputStream = resource.open()) {
                BufferedImage image = ImageIO.read(inputStream);
                if (image == null) {
                    QuickSkin.LOGGER.debug("Could not read image from resource: {}", textureLocation);
                    return false;
                }

                return checkImageForTransparency(image);

            }
        } catch (IOException e) {
            QuickSkin.LOGGER.debug("Failed to check transparency for texture {}: {}", textureLocation, e.getMessage());
            return false;
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Unexpected error checking transparency for texture {}", textureLocation, e);
            return false;
        }
    }

    /**
     * Check if a BufferedImage contains any transparent pixels
     */
    private static boolean checkImageForTransparency(BufferedImage image) {
        // If the image doesn't have an alpha channel, it's not transparent
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Sample pixels to check for transparency for performance
        int sampleRate = Math.max(1, Math.min(width, height) / 32); // Sample every Nth pixel

        for (int y = 0; y < height; y += sampleRate) {
            for (int x = 0; x < width; x += sampleRate) {
                int pixel = image.getRGB(x, y);
                int alpha = (pixel >> 24) & 0xFF;

                if (alpha < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Clear the transparency cache (useful for resource pack reloads)
     */
    public static void clearCache() {
        transparencyCache.clear();
    }
}
```

#### Step 2: Create a `CapeLayerMixin`

This is the most important part. We will create a mixin to intercept the vanilla cape rendering, just like your old mod did. It will check for active capes, detect transparency using the utility we just created, and force the correct `RenderType`.

**Create a new file:** `quick-skin-1.20.1-fabric-forge/common/src/main/java/com/quickskin/mod/mixin/CapeLayerMixin.java`

```java
package com.quickskin.mod.mixin;

import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CapeLayer.class)
public class CapeLayerMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void quickskin$renderCustomCape(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                            AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                            CallbackInfo ci) {
        
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (!service.hasActiveCape(player.getUUID())) {
            return; // No custom cape, let vanilla logic run
        }

        ResourceLocation capeTexture = player.getCloakTextureLocation();

        if (capeTexture == null) {
            ci.cancel(); // Don't render anything if QuickSkin wants to hide the cape
            return;
        }

        if (player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            // Let vanilla handle elytra rendering, but we still need to cancel our cape logic
            ci.cancel(); 
            return;
        }

        // Logic to choose the correct RenderType
        RenderType renderType;
        String texturePath = capeTexture.getPath();

        // Force translucent for our local/dynamic textures
        if (texturePath.contains("quickskin/local/")) {
            renderType = RenderType.entityTranslucentCull(capeTexture);
        } else {
            // For all other capes (vanilla, other mods), check for transparency
            if (TextureAlphaDetector.hasTransparency(capeTexture)) {
                renderType = RenderType.entityTranslucentCull(capeTexture);
            } else {
                renderType = RenderType.entitySolid(capeTexture);
            }
        }

        VertexConsumer vertexconsumer = buffer.getBuffer(renderType);

        // Replicate the vanilla cape rendering logic with our custom render type
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.125D);
        double d0 = Mth.lerp(partialTicks, player.xCloakO, player.xCloak) - Mth.lerp(partialTicks, player.xo, player.getX());
        double d1 = Mth.lerp(partialTicks, player.yCloakO, player.yCloak) - Mth.lerp(partialTicks, player.yo, player.getY());
        double d2 = Mth.lerp(partialTicks, player.zCloakO, player.zCloak) - Mth.lerp(partialTicks, player.zo, player.getZ());
        float f = player.yBodyRotO + (player.yBodyRot - player.yBodyRotO);
        double d3 = Mth.sin(f * ((float)Math.PI / 180F));
        double d4 = -Mth.cos(f * ((float)Math.PI / 180F));
        float f1 = (float)d1 * 10.0F;
        f1 = Mth.clamp(f1, -6.0F, 32.0F);
        float f2 = (float)(d0 * d3 + d2 * d4) * 100.0F;
        f2 = Mth.clamp(f2, 0.0F, 150.0F);
        float f3 = (float)(d0 * d4 - d2 * d3) * 100.0F;
        f3 = Mth.clamp(f3, -20.0F, 20.0F);
        if (f2 < 0.0F) {
            f2 = 0.0F;
        }

        float f4 = Mth.lerp(partialTicks, player.oBob, player.bob);
        f1 += Mth.sin(Mth.lerp(partialTicks, player.walkDistO, player.walkDist) * 6.0F) * 32.0F * f4;
        if (player.isCrouching()) {
            f1 += 25.0F;
        }

        poseStack.mulPose(Axis.XP.rotationDegrees(6.0F + f2 / 2.0F + f1));
        poseStack.mulPose(Axis.ZP.rotationDegrees(f3 / 2.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - f3 / 2.0F));
        
        // Render the cloak part of the model
        ((CapeLayer)(Object)this).getParentModel().renderCloak(poseStack, vertexconsumer, packedLight, OverlayTexture.NO_OVERLAY);
        
        poseStack.popPose();

        // Cancel the original vanilla method to prevent it from rendering a second time
        ci.cancel();
    }
}
```

#### Step 3: Add Event Handler for Resource Reloads

Finally, we need to tell our `TextureAlphaDetector` to clear its cache whenever the player reloads resource packs. This ensures that if they switch to a texture pack that changes a cape's transparency, our mod will detect it correctly.

**Create a new file:** `quick-skin-1.20.1-fabric-forge/common/src/main/java/com/quickskin/mod/event/CapeTransparencyEvents.java`

```java
package com.quickskin.mod.event;

import com.quickskin.mod.common.util.TextureAlphaDetector;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.packs.resources.ResourceManager;

@Environment(EnvType.CLIENT)
public class CapeTransparencyEvents {
    public static void register() {
        ClientLifecycleEvent.CLIENT_RESOURCES_RELOAD.register(CapeTransparencyEvents::onResourcesReloaded);
    }

    private static void onResourcesReloaded(ResourceManager resourceManager) {
        TextureAlphaDetector.clearCache();
    }
}
```

Now, **register this event** by adding one line to your `ClientEvents.init()` method.

**In `ClientEvents.java`:**

```java
// ... other imports
import com.quickskin.mod.event.CapeTransparencyEvents; // Add this import

// ... inside the ClientEvents class

public static void init() {
    QuickSkin.LOGGER.info("Registering client events...");

    // ADD THIS LINE
    CapeTransparencyEvents.register();

    // ... rest of your init() method ...
}
```

### Summary of Fixes

1.  **`TextureAlphaDetector.java`**: A new utility class that inspects textures to see if they contain transparency.
2.  **`CapeLayerMixin.java`**: A new mixin that intercepts cape rendering. It uses `TextureAlphaDetector` to decide whether to use a `solid` or `translucent` render type, forcing the game to correctly render capes with alpha channels.
3.  **`CapeTransparencyEvents.java`**: A new event handler to clear the detector's cache on resource pack reloads.
4.  **`ClientEvents.java` modification**: A single line added to register the new event handler.

With these three new files and the modification to `ClientEvents`, your transparent capes should now render perfectly, just like they did in your old mod.