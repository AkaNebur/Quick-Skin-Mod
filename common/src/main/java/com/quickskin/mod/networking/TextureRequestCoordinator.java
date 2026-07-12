package com.quickskin.mod.networking;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Deduplicates renderer-driven texture misses while allowing bounded retries. */
@Environment(EnvType.CLIENT)
public final class TextureRequestCoordinator {
    private static final TextureRequestCoordinator INSTANCE = new TextureRequestCoordinator();

    private final LinkedHashMap<RequestKey, Long> pending = new LinkedHashMap<>();
    private Object connectionIdentity;

    private TextureRequestCoordinator() {
    }

    public static TextureRequestCoordinator getInstance() {
        return INSTANCE;
    }

    /**
     * Runs the request only when it is not already pending. A request becomes
     * eligible again after the retry timeout, and changing server sessions resets all state.
     */
    public synchronized boolean requestIfNeeded(String textureType, String hash, Runnable request) {
        if (!NetworkSecurity.isValidTextureType(textureType)
                || !NetworkSecurity.isValidContentId(hash) || request == null) {
            return false;
        }
        refreshSession();
        long now = System.currentTimeMillis();
        purgeExpired(now);
        RequestKey key = new RequestKey(textureType, hash);
        Long requestedAt = pending.get(key);
        if (requestedAt != null && now - requestedAt < TextureTransferLimits.REQUEST_RETRY_MILLIS) {
            return false;
        }
        if (pending.size() >= TextureTransferLimits.MAX_PENDING_REQUESTS) {
            Iterator<RequestKey> iterator = pending.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        pending.put(key, now);
        try {
            request.run();
            return true;
        } catch (RuntimeException e) {
            pending.remove(key);
            throw e;
        }
    }

    public synchronized void markFulfilled(String textureType, String hash) {
        if (NetworkSecurity.isValidTextureType(textureType) && NetworkSecurity.isValidContentId(hash)) {
            refreshSession();
            pending.remove(new RequestKey(textureType, hash));
        }
    }

    public synchronized void clear() {
        pending.clear();
        connectionIdentity = Minecraft.getInstance().getConnection();
    }

    private void refreshSession() {
        Object current = Minecraft.getInstance().getConnection();
        if (current != connectionIdentity) {
            pending.clear();
            connectionIdentity = current;
        }
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<RequestKey, Long>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() >= TextureTransferLimits.REQUEST_RETRY_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static final class RequestKey {
        private final String textureType;
        private final String hash;

        private RequestKey(String textureType, String hash) {
            this.textureType = textureType;
            this.hash = hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RequestKey)) return false;
            RequestKey key = (RequestKey) other;
            return textureType.equals(key.textureType) && hash.equals(key.hash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(textureType, hash);
        }
    }
}
