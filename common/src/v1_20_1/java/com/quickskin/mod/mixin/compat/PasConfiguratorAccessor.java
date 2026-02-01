package com.quickskin.mod.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for PAS's PasConfiguratorScreen.
 * Provides access to private fields for updating the screen state.
 *
 * Based on PAS 0.7.1 structure:
 * - entityName: The name/hash of the skin file
 * - skinProvider: The provider code ("F" for file, "M" for Mojang, etc.)
 * - isSlim: Whether to use slim arm model
 */
@Pseudo
@Mixin(targets = "com.danrus.pas.render.gui.PasConfiguratorScreen", remap = false)
public interface PasConfiguratorAccessor {

    @Accessor("entityName")
    String quickskin$getEntityName();

    @Accessor("entityName")
    void quickskin$setEntityName(String name);

    @Accessor("skinProvider")
    String quickskin$getSkinProvider();

    @Accessor("skinProvider")
    void quickskin$setSkinProvider(String provider);

    @Accessor("isSlim")
    boolean quickskin$isSlim();

    @Accessor("isSlim")
    void quickskin$setIsSlim(boolean slim);
}
