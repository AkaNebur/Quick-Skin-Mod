package com.quickskin.mod.e2e;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * A named E2E scenario: an ordered list of {@link Step}s the harness runs once the client is
 * in-world. Implementations are loader-agnostic and live in {@code common/src/e2e}.
 */
public interface Scenario {

    /** Stable id, also used as the {@code -Dquickskin.e2e.scenario} selector value. */
    String id();

    /** Build the ordered steps. Called once, after the client has joined a world. */
    List<Step> build(Minecraft mc);
}
