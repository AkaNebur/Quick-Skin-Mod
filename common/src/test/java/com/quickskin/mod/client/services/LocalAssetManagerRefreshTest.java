package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the manager side of the external-folder poll. The screens tick before anything guarantees
 * {@code init()} has run, so a poll landing on an uninitialized manager must be inert rather than
 * walking null directories.
 */
class LocalAssetManagerRefreshTest {

    @Test
    void refreshIsSkippedUntilTheManagerHasBeenInitialized() {
        LocalAssetManager manager = LocalAssetManager.getInstance();

        // Any fingerprint at all, including one that differs from the initial zero.
        assertFalse(manager.refreshIfChanged(0L));
        assertFalse(manager.refreshIfChanged(1234567L));
    }

    /**
     * The watch list must stay empty before {@code init()}, which also keeps a pre-bootstrap poll
     * from triggering the optional CPM classpath probe.
     */
    @Test
    void nothingIsWatchedBeforeInitialization() {
        LocalAssetManager manager = LocalAssetManager.getInstance();

        assertTrue(manager.getScannedDirectories().isEmpty());
        assertEquals(0L, LocalAssetFolderWatch.fingerprint(manager.getScannedDirectories()));
    }
}
