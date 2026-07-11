package com.quickskin.mod.client.services;

//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for cape animation ID derivation and frame resolution.
 * Centralizes the pattern of converting capeId -> animationId so that
 * all callers use consistent logic.
 */
public final class CapeAnimationHelper {

    private CapeAnimationHelper() {}

    /**
     * Derives the animation ID from a cape ID string.
     * <ul>
     *   <li>{@code "local_cape:hash"} &rarr; {@code "cape_hash"}</li>
     *   <li>{@code "known:id"}        &rarr; {@code "cape_known_id"}</li>
     * </ul>
     *
     * @param capeId The cape ID (e.g. "local_cape:abc123" or "known:rickroll")
     * @return The animation ID, or {@code null} if the capeId format is unrecognized
     */
    @Nullable
    public static String deriveAnimationId(@Nullable String capeId) {
        if (capeId == null || capeId.isEmpty()) {
            return null;
        }
        if (capeId.startsWith("local_cape:")) {
            return "cape_" + capeId.substring("local_cape:".length());
        }
        if (capeId.startsWith("known:")) {
            return "cape_known_" + capeId.substring("known:".length());
        }
        return null;
    }

    /**
     * Resolves the current animation frame for an animated cape, or returns the
     * atlas location unchanged for non-animated capes.
     * <p>
     * This allows any code that reads the cape texture (including third-party mods
     * like WaveyCapes) to get the correct current frame instead of the full atlas.
     *
     * @param atlasLocation The atlas Identifier (all frames stacked)
     * @param capeId        The cape ID string
     * @return The current frame Identifier if animated, otherwise {@code atlasLocation}
     */
    //? if <1.21.11 {
    public static ResourceLocation resolveCurrentFrame(ResourceLocation atlasLocation, @Nullable String capeId) {
    //?} else {
    public static Identifier resolveCurrentFrame(Identifier atlasLocation, @Nullable String capeId) {
    //?}
        if (atlasLocation == null) {
            return null;
        }

        String animationId = deriveAnimationId(capeId);
        if (animationId == null) {
            return atlasLocation;
        }

        AnimatedTextureManager atm = AnimatedTextureManager.getInstance();
        if (!atm.isAnimated(animationId)) {
            return atlasLocation;
        }

        //? if <1.21.11 {
        ResourceLocation currentFrame = atm.getCurrentFrameTexture(animationId);
        //?} else {
        Identifier currentFrame = atm.getCurrentFrameTexture(animationId);
        //?}
        return currentFrame != null ? currentFrame : atlasLocation;
    }
}
