# Implementation Plan: 3D Skin Layers Integration

## Overview
Add 3D skin layer support to your manual player rendering in `PlayerModelRenderer.java` by creating an integration class that uses the 3D-skin-layers mod API.

---

## Step 1: Create the Integration Class

**File:** `SkinLayers3DIntegration.java` (new file in same package as `PlayerModelRenderer.java`)

### 1.1 Package and Imports
```java
package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.tr7zw.skinlayers.SkinLayersModBase;
import dev.tr7zw.skinlayers.api.Mesh;
import dev.tr7zw.skinlayers.api.SkinLayersAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.Map;
```

### 1.2 Class Structure and Cache
```java
public class SkinLayers3DIntegration {
    private static final Map<ResourceLocation, PlayerMeshes> meshCache = new HashMap<>();
    private static boolean MOD_AVAILABLE = false;
    
    static {
        try {
            Class.forName("dev.tr7zw.skinlayers.SkinLayersModBase");
            MOD_AVAILABLE = true;
        } catch (ClassNotFoundException e) {
            MOD_AVAILABLE = false;
        }
    }
    
    private static class PlayerMeshes {
        Mesh headMesh, torsoMesh, leftArmMesh, rightArmMesh, leftLegMesh, rightLegMesh;
        boolean thinArms;
        
        boolean isValid() {
            return headMesh != null && torsoMesh != null && 
                   leftArmMesh != null && rightArmMesh != null &&
                   leftLegMesh != null && rightLegMesh != null;
        }
    }
```

### 1.3 Main Render Method
```java
    public static void render3DLayers(PoseStack poseStack, MultiBufferSource buffer,
                                     int light, ResourceLocation skinLocation,
                                     PlayerModel<?> model, boolean thinArms) {
        if (!MOD_AVAILABLE || !is3DSkinLayersEnabled()) return;
        
        PlayerMeshes meshes = getOrCreateMeshes(skinLocation, thinArms);
        if (meshes == null || !meshes.isValid()) return;
        
        VertexConsumer vertices = buffer.getBuffer(RenderType.entityTranslucent(skinLocation));
        int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
        
        if (model.head.visible && SkinLayersModBase.config.enableHat)
            renderHeadLayer(poseStack, vertices, light, overlay, model, meshes.headMesh);
        
        if (model.body.visible && SkinLayersModBase.config.enableJacket)
            renderBodyLayer(poseStack, vertices, light, overlay, model, meshes.torsoMesh);
        
        if (model.leftArm.visible && SkinLayersModBase.config.enableLeftSleeve)
            renderArmLayer(poseStack, vertices, light, overlay, model.leftArm, meshes.leftArmMesh, false, thinArms);
        
        if (model.rightArm.visible && SkinLayersModBase.config.enableRightSleeve)
            renderArmLayer(poseStack, vertices, light, overlay, model.rightArm, meshes.rightArmMesh, true, thinArms);
        
        if (model.leftLeg.visible && SkinLayersModBase.config.enableLeftPants)
            renderLegLayer(poseStack, vertices, light, overlay, model.leftLeg, meshes.leftLegMesh);
        
        if (model.rightLeg.visible && SkinLayersModBase.config.enableRightPants)
            renderLegLayer(poseStack, vertices, light, overlay, model.rightLeg, meshes.rightLegMesh);
    }
```

### 1.4 Mesh Creation Method
```java
    private static PlayerMeshes getOrCreateMeshes(ResourceLocation skinLocation, boolean thinArms) {
        PlayerMeshes cached = meshCache.get(skinLocation);
        if (cached != null && cached.thinArms == thinArms && cached.isValid()) return cached;
        
        PlayerMeshes meshes = new PlayerMeshes();
        meshes.thinArms = thinArms;
        
        try {
            NativeImage skin = getSkinTexture(skinLocation);
            if (skin == null || skin.getWidth() != 64 || skin.getHeight() != 64) return null;
            
            meshes.headMesh = SkinLayersAPI.getMeshHelper().create3DMesh(skin, 8, 8, 8, 32, 0, false, 0.6f);
            meshes.torsoMesh = SkinLayersAPI.getMeshHelper().create3DMesh(skin, 8, 12, 4, 16, 32, true, 0f);
            meshes.leftLegMesh = SkinLayersAPI.getMeshHelper().create3DMesh(skin, 4, 12, 4, 0, 48, true, 0f);
            meshes.rightLegMesh = SkinLayersAPI.getMeshHelper().create3DMesh(skin, 4, 12, 4, 0, 32, true, 0f);
            
            if (thinArms) {
                meshes.leftArmMesh = SkinLayersAPI.getMeshHelper().create3DMesh(skin, 3, 12, 4, 48, 48, true, -2f);
                meshes.rightArmMesh = SkinLayersAPI.getMeshHelper().create3DMesh(skin, 3, 12, 4, 40, 32, true, -2f);
            } else {
                meshes.leftArmMesh = SkinLayersAPI.getMeshHelper().create3DMesh(skin, 4, 12, 4, 48, 48, true, -2f);
                meshes.rightArmMesh = SkinLayersAPI.getMeshHelper().create3DMesh(skin, 4, 12, 4, 40, 32, true, -2f);
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
```

### 1.5 Texture Loading Method
```java
    private static NativeImage getSkinTexture(ResourceLocation skinLocation) {
        try {
            Minecraft mc = Minecraft.getInstance();
            
            // Try resource manager first
            var optionalRes = mc.getResourceManager().getResource(skinLocation);
            if (optionalRes.isPresent()) {
                return NativeImage.read(optionalRes.get().open());
            }
            
            // Try texture manager for downloaded skins
            AbstractTexture texture = mc.getTextureManager().getTexture(skinLocation);
            if (texture == null) return null;
            
            // Handle HttpTexture using reflection
            if (texture instanceof HttpTexture) {
                try {
                    java.lang.reflect.Field fileField = HttpTexture.class.getDeclaredField("file");
                    fileField.setAccessible(true);
                    java.io.File file = (java.io.File) fileField.get(texture);
                    
                    if (file != null && file.isFile()) {
                        return NativeImage.read(new java.io.FileInputStream(file));
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
```

### 1.6 Layer Render Methods
```java
    private static void renderHeadLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, PlayerModel<?> model, Mesh headMesh) {
        float voxelSize = SkinLayersModBase.config.headVoxelSize;
        poseStack.pushPose();
        model.head.translateAndRotate(poseStack);
        poseStack.translate(0, -0.25, 0);
        poseStack.scale(voxelSize, voxelSize, voxelSize);
        poseStack.translate(0, 0.25, 0);
        poseStack.translate(0, -0.04, 0);
        headMesh.render(model.head, poseStack, vertices, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
        poseStack.popPose();
    }
    
    private static void renderBodyLayer(PoseStack poseStack, VertexConsumer vertices,
                                       int light, int overlay, PlayerModel<?> model, Mesh torsoMesh) {
        float widthScaling = SkinLayersModBase.config.bodyVoxelWidthSize;
        float heightScaling = 1.035f;
        float pixelScaling = SkinLayersModBase.config.baseVoxelSize;
        
        poseStack.pushPose();
        model.body.translateAndRotate(poseStack);
        poseStack.scale(widthScaling, heightScaling, pixelScaling);
        torsoMesh.setPosition(0, -0.2f, 0);
        torsoMesh.render(model.body, poseStack, vertices, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
        poseStack.popPose();
    }
    
    private static void renderArmLayer(PoseStack poseStack, VertexConsumer vertices,
                                      int light, int overlay, ModelPart arm,
                                      Mesh armMesh, boolean isRightArm, boolean thinArms) {
        float pixelScaling = SkinLayersModBase.config.baseVoxelSize;
        float heightScaling = 1.035f;
        
        poseStack.pushPose();
        arm.translateAndRotate(poseStack);
        poseStack.scale(pixelScaling, heightScaling, pixelScaling);
        
        float x = thinArms ? 0.499f : 0.998f;
        if (isRightArm) x *= -1;
        
        armMesh.setPosition(x, -0.1f, 0);
        armMesh.render(arm, poseStack, vertices, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
        poseStack.popPose();
    }
    
    private static void renderLegLayer(PoseStack poseStack, VertexConsumer vertices,
                                      int light, int overlay, ModelPart leg, Mesh legMesh) {
        float pixelScaling = SkinLayersModBase.config.baseVoxelSize;
        float heightScaling = 1.035f;
        
        poseStack.pushPose();
        leg.translateAndRotate(poseStack);
        poseStack.scale(pixelScaling, heightScaling, pixelScaling);
        legMesh.setPosition(0, -0.2f, 0);
        legMesh.render(leg, poseStack, vertices, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
        poseStack.popPose();
    }
```

### 1.7 Utility Methods
```java
    private static boolean is3DSkinLayersEnabled() {
        try {
            return SkinLayersModBase.config != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    public static void clearCache() {
        meshCache.clear();
    }
}
```

---

## Step 2: Modify PlayerModelRenderer.java

### 2.1 Find the Manual Render Method
Locate the `renderPlayerModelManual()` method (around line 189).

### 2.2 Find the Model Rendering Section
Find where the model is rendered (around line 550-560):
```java
model.renderToBuffer(poseStack, vertexConsumer, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
```

### 2.3 Add 3D Layer Rendering
**Immediately after** the `model.renderToBuffer()` call, add:
```java
// Render 3D skin layers (if mod is installed)
try {
    SkinLayers3DIntegration.render3DLayers(
        poseStack,
        buffer,
        light,
        playerData.getSkinLocation(),
        model,
        isSlimModel
    );
} catch (NoClassDefFoundError | Exception e) {
    // 3D-skin-layers mod not installed - skip layers
}
```

### 2.4 Optional: Hide Vanilla Overlays
**Before** `model.renderToBuffer()`, add this to prevent double-rendering:
```java
// Hide vanilla overlay layers if 3D-skin-layers is active
try {
    if (SkinLayersModBase.config != null) {
        model.hat.visible = false;
        model.jacket.visible = false;
        model.leftSleeve.visible = false;
        model.rightSleeve.visible = false;
        model.leftPants.visible = false;
        model.rightPants.visible = false;
    }
} catch (NoClassDefFoundError e) {
    // Mod not installed, keep vanilla layers visible
}
```

---

## Step 3: Optional Improvements

### 3.1 Add Periodic Cache Clearing
In your main mod class or wherever you have tick events:
```java
public void onClientTick(Minecraft client) {
    if (client.level != null && client.level.getGameTime() % 6000 == 0) {
        SkinLayers3DIntegration.clearCache();
    }
}
```

### 3.2 Better HttpTexture Access (Recommended)
Instead of reflection, create a mixin accessor:

**File:** `HttpTextureAccessor.java` (in your accessor/mixin package)
```java
package com.quickskin.mod.accessor;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.FileNotFoundException;

public interface HttpTextureAccessor {
    NativeImage getImage() throws FileNotFoundException;
}
```

**File:** `HttpTextureMixin.java`
```java
package com.quickskin.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.accessor.HttpTextureAccessor;
import net.minecraft.client.renderer.texture.HttpTexture;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

@Mixin(HttpTexture.class)
public abstract class HttpTextureMixin implements HttpTextureAccessor {
    @Shadow private File file;
    @Shadow public abstract NativeImage load(InputStream inputStream);
    
    @Override
    public NativeImage getImage() throws FileNotFoundException {
        if (this.file != null && this.file.isFile()) {
            return load(new FileInputStream(this.file));
        }
        return null;
    }
}
```

Then update `getSkinTexture()` in `SkinLayers3DIntegration.java`:
```java
// Replace the reflection block with:
if (texture instanceof HttpTextureAccessor) {
    HttpTextureAccessor accessor = (HttpTextureAccessor) texture;
    try {
        return accessor.getImage();
    } catch (Exception e) {
        return null;
    }
}
```

And add the mixin to your `yourmod.mixins.json`:
```json
{
  "required": true,
  "package": "com.quickskin.mod.mixin",
  "compatibilityLevel": "JAVA_17",
  "mixins": [
    "HttpTextureMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

---

## Step 4: Testing

### 4.1 Test Without Mod
1. Launch game without 3D-skin-layers installed
2. Verify your preview still works normally
3. Check logs for no errors

### 4.2 Test With Mod
1. Install 3D-skin-layers mod
2. Launch game on title screen
3. Open your skin preview - should see 3D layers
4. Join a world - verify 3D layers still work
5. Test with both Steve and Alex (slim) models

### 4.3 Verify Config Respect
1. Open 3D-skin-layers config
2. Disable individual layers (hat, jacket, etc.)
3. Verify your preview respects the settings

---

## Step 5: Troubleshooting

### Issue: 3D layers not appearing
- Check if mod is actually installed (check mod menu)
- Verify skin is 64x64 (HD skins not supported)
- Check logs for exceptions
- Try with default Steve skin first

### Issue: Double rendering (both vanilla and 3D layers)
- Make sure you added the vanilla overlay hiding code (Step 2.4)

### Issue: Crashes with mod not installed
- Verify you're catching `NoClassDefFoundError` in addition to `Exception`
- Check that `MOD_AVAILABLE` flag is working

### Issue: HttpTexture reflection fails
- Implement the mixin accessor solution (Step 3.2)
- Or wait for texture to be ready (it may take a frame or two)

---

## Summary

**Total files to create:** 1 new file (`SkinLayers3DIntegration.java`)  
**Files to modify:** 1 existing file (`PlayerModelRenderer.java`)  
**Lines to add:** ~10-15 lines in PlayerModelRenderer  
**Optional improvements:** Mixin accessor for cleaner HttpTexture access  

This integration will make your manual player rendering show 3D skin layers exactly like real players in-game, while gracefully handling when the mod isn't installed.