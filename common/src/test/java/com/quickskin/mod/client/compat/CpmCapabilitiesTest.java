package com.quickskin.mod.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CpmCapabilitiesTest {
    @Test
    void currentBandUsesTheExactExtractorRelease() {
        assertEquals(CpmCapabilities.Band.MC_26_1_1, CpmCapabilities.currentBand());
        assertEquals(
                CpmCapabilities.RenderPipeline.EXTRACTOR,
                CpmCapabilities.current().renderPipeline()
        );
    }
}
