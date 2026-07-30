package com.quickskin.mod.networking;

import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.networking.packets.PacketHelper;
import com.quickskin.mod.server.concurrent.ServerTextureIngressExecutor;
import com.quickskin.mod.server.data.QuickSkinPlayerTracker;
import com.quickskin.mod.server.data.ServerCooldownManager;
import com.quickskin.mod.server.data.ServerAppearanceSyncCoordinator;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import com.quickskin.mod.server.data.ServerTextureResponseCoordinator;
import com.quickskin.mod.server.data.ServerUploadCoordinator;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerTextureCache;
import com.quickskin.mod.server.storage.TextureChunkAssembler;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Server-side network packet handlers
 * Handles all C2S (Client to Server) packets
 */
public class ServerNetworkHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerNetworkHandler.class);
    private static final ServerUploadCoordinator UPLOAD_COORDINATOR =
            ServerUploadCoordinator.getInstance();
    private static final ServerTextureResponseCoordinator RESPONSE_COORDINATOR =
            ServerTextureResponseCoordinator.getInstance();
    private static final ServerAppearanceSyncCoordinator APPEARANCE_COORDINATOR =
            ServerAppearanceSyncCoordinator.getInstance();
    private static final Deque<PacedTextureResponse> PACED_TEXTURE_RESPONSES =
            new ConcurrentLinkedDeque<>();
    private static final Deque<PreparedTextureUpload> PREPARED_TEXTURE_UPLOADS =
            new ConcurrentLinkedDeque<>();
    private static final int MAX_UPLOAD_COMMITS_PER_TICK = 4;
    private static final int MAX_RESPONSE_PACKETS_PER_TICK = 64;
    private static final int MAX_RESPONSE_BYTES_PER_TICK = 2 * 1024 * 1024;

    /**
     * Checks if a player's client has QuickSkin installed and can receive our packets.
     * Used to skip sending S2C packets to vanilla clients that don't have the mod.
     */
    private static boolean canReceiveQuickSkin(ServerPlayer player) {
        return NetworkTransport.INSTANCE.canPlayerReceiveQuickSkin(player)
                || QuickSkinPlayerTracker.getInstance().isConfirmed(player.getUUID());
    }

    /**
     * Tracks that a player has QuickSkin installed (they sent us a C2S packet).
     * On first confirmation, triggers full sync for the player.
     */
    private static void trackModPresence(ServerPlayer player) {
        if (QuickSkinPlayerTracker.getInstance().markConfirmed(player.getUUID())) {
            // Send all existing appearances to this newly confirmed player
            sendAllAppearancesToPlayer(player);

            // Send server config
            sendServerConfigToPlayer(player);

            // Send cooldown status if applicable
            int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;
            if (cooldownSeconds > 0 && ServerCooldownManager.getInstance().isPlayerOnCooldown(player.getUUID())) {
                long cooldownEndTime = ServerCooldownManager.getInstance().getCooldownEndTime(player.getUUID());
                NetworkTransport.INSTANCE.sendCooldownToPlayer(player, cooldownEndTime);
            }

            // Broadcast this player's appearance to all other players
            sendAppearanceToAllPlayers(player);
        }
    }

    /**
     * Handles skin/cape upload from client
     * Packet format: UUID (player) + String (textureType) + byte[] (imageData)
     */
    public static void handleUploadTexture(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        // Read data from buffer
        UUID playerId = PacketHelper.readPlayerId(buf);
        String textureType = PacketHelper.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        byte[] imageData = PacketHelper.readByteArray(buf, TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES);

        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !sender.getUUID().equals(playerId)
                || !NetworkSecurity.isValidTextureType(textureType)
                || !TextureTransferRateLimiter.getInstance().allowUploadBytes(
                        sender.getUUID(), sender.connection, imageData.length)
                || !reserveDecodedPixels(sender, textureType, imageData)
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        submitTextureUpload(
                sender.server,
                sender.getUUID(),
                sender.connection,
                null,
                textureType,
                imageData);
    }

    /**
     * Handles appearance update from client
     * Packet format: UUID (player) + String (skinId) + String (capeId) + String (model)
     */
    public static void handleUpdateAppearance(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UUID playerId = PacketHelper.readPlayerId(buf);
        String skinId = PacketHelper.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
        String capeId = PacketHelper.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
        String model = PacketHelper.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES);

        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !sender.getUUID().equals(playerId)
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.server;
        Object connection = sender.connection;

        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);

            if (player == null || !player.getUUID().equals(playerId)) {
                return;
            }

            trackModPresence(player);

            acceptOrDeferAppearance(
                    player,
                    new ServerUploadCoordinator.PendingAppearance(skinId, capeId, model),
                    true);
        });
    }

    /**
     * Handles texture request from client
     * Packet format: UUID (player) + String (textureType) + String (hash)
     */
    public static void handleRequestTexture(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UUID playerId = PacketHelper.readPlayerId(buf);
        String textureType = PacketHelper.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        String hash = PacketHelper.readString(buf, TextureTransferLimits.CONTENT_ID_LENGTH);

        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || !sender.getUUID().equals(playerId)
                || !TextureTransferRateLimiter.getInstance().allowTextureRequest(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.server;
        Object connection = sender.connection;

        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);

            if (player == null || !player.getUUID().equals(playerId)) {
                return;
            }

            trackModPresence(player);

            if (!NetworkSecurity.isValidTextureType(textureType)
                    || !NetworkSecurity.isValidContentId(hash)
                    || !ServerTextureCache.getInstance().isRequestable(hash, textureType)) return;

            // Phase 5: Load texture from server storage and send to client
            sendCachedTextureToClient(player, textureType, hash);
        });
    }

    /**
     * Handles chunked texture upload from client
     * Packet format: String (hash) + String (textureType) + int (chunkIndex) + int (totalChunks) + byte[] (chunkData)
     */
    public static void handleTextureChunk(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String hash = PacketHelper.readString(buf, TextureTransferLimits.CONTENT_ID_LENGTH);
        String textureType = PacketHelper.readString(buf, TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES);
        int chunkIndex = buf.readInt();
        int totalChunks = buf.readInt();
        byte[] chunkData = PacketHelper.readByteArray(buf, TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);

        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null
                || !NetworkSecurity.isValidTextureType(textureType)
                || !NetworkSecurity.isValidContentId(hash)
                || !TextureTransferRateLimiter.getInstance().allowUploadBytes(
                        sender.getUUID(), sender.connection, chunkData.length)) return;

        // The assembler is synchronized and bounded. Keeping its final large array copy off the
        // server thread prevents a completed maximum-size upload from stalling a tick.
        byte[] completeTexture = TextureChunkAssembler.getInstance().addChunk(
                sender.getUUID(), sender.connection, textureType, hash,
                chunkIndex, totalChunks, chunkData);
        if (completeTexture == null
                || !reserveDecodedPixels(sender, textureType, completeTexture)
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        submitTextureUpload(
                sender.server,
                sender.getUUID(),
                sender.connection,
                hash,
                textureType,
                completeTexture);
    }

    /** Handles a retryable request for a complete, paced appearance roster. */
    public static void handleRequestAppearanceSnapshot(
            FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UUID playerId = buf.readUUID();
        long requestId = buf.readLong();
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null || requestId <= 0L || !sender.getUUID().equals(playerId)
                || !TextureTransferRateLimiter.getInstance()
                        .allowAppearanceSnapshotRequest(
                                sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.server;
        Object connection = sender.connection;
        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);
            if (player == null) return;
            trackModPresence(player);
            APPEARANCE_COORDINATOR.requestSnapshot(
                    player.getUUID(), connection, requestId);
        });
    }

    private static boolean reserveDecodedPixels(
            ServerPlayer player, String textureType, byte[] textureData) {
        long pixels = NetworkSecurity.getTexturePixelCount(textureData, textureType);
        return pixels > 0 && TextureTransferRateLimiter.getInstance().allowDecodedPixels(
                player.getUUID(), player.connection, pixels);
    }

    /** Runs hash/decode/staging off-thread, then commits only for the original live session. */
    private static void submitTextureUpload(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            String expectedHash,
            String textureType,
            byte[] textureData
    ) {
        ServerUploadCoordinator.UploadTicket ticket =
                UPLOAD_COORDINATOR.beginUpload(
                        playerId, connection, textureType, expectedHash, textureData.length);
        if (ticket == null) {
            LOGGER.debug("Rejected texture upload because the per-session ingress bound was reached");
            return;
        }
        boolean accepted = ServerTextureIngressExecutor.getInstance().submit(
                textureData.length,
                () -> {
                    ServerTextureCache.PreparedTexture prepared = null;
                    boolean completionScheduled = false;
                    try {
                        prepared = ServerTextureCache.getInstance().prepareTexture(
                                expectedHash, playerId, textureType, textureData);
                        if (prepared != null) {
                            UPLOAD_COORDINATOR.identifyUpload(ticket, prepared.hash());
                        }
                        if (UPLOAD_COORDINATOR.isCanceled(ticket)) {
                            if (prepared != null) prepared.close();
                            prepared = null;
                            UPLOAD_COORDINATOR.finishUpload(ticket);
                            completionScheduled = true;
                            return;
                        }
                        scheduleTextureUploadCompletion(
                                server, playerId, connection, ticket, prepared);
                        prepared = null;
                        completionScheduled = true;
                    } catch (RuntimeException | LinkageError error) {
                        LOGGER.warn("Texture ingress preparation failed", error);
                    } finally {
                        if (!completionScheduled) {
                            if (prepared != null) prepared.close();
                            scheduleTextureUploadCompletion(
                                    server, playerId, connection, ticket, null);
                        }
                    }
                }, () -> {
                    UPLOAD_COORDINATOR.removeSession(playerId, connection);
                    UPLOAD_COORDINATOR.finishUpload(ticket);
                });
        if (!accepted) {
            LOGGER.debug("Rejected texture upload because the bounded ingress queue is full");
            scheduleTextureUploadCompletion(
                    server, playerId, connection, ticket, null);
        }
    }

    private static void scheduleTextureUploadCompletion(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            ServerUploadCoordinator.UploadTicket ticket,
            ServerTextureCache.PreparedTexture prepared
    ) {
        PreparedTextureUpload handoff = new PreparedTextureUpload(
                server, playerId, connection, ticket, prepared);
        PREPARED_TEXTURE_UPLOADS.addLast(handoff);
        if (UPLOAD_COORDINATOR.isCanceled(ticket)
                && PREPARED_TEXTURE_UPLOADS.remove(handoff)) {
            discardPreparedTextureUpload(handoff);
        }
    }

    private static void completeTextureUpload(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            ServerUploadCoordinator.UploadTicket ticket,
            ServerTextureCache.PreparedTexture prepared
    ) {
        ServerPlayer player = activePlayer(server, playerId, connection);
        if (player == null || UPLOAD_COORDINATOR.isCanceled(ticket)) {
            if (prepared != null) prepared.close();
            UPLOAD_COORDINATOR.removeSession(playerId, connection);
            UPLOAD_COORDINATOR.finishUpload(ticket);
            return;
        }

        trackModPresence(player);
        try {
            if (prepared != null) {
                try (prepared) {
                    ServerTextureCache.getInstance().storePreparedTexture(prepared);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            LOGGER.warn("Unable to commit a prepared texture upload", error);
        } finally {
            retryDeferredPackets(player, UPLOAD_COORDINATOR.finishUpload(ticket));
        }
    }

    private static ServerPlayer activePlayer(
            MinecraftServer server, UUID playerId, Object connection) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && player.connection == connection ? player : null;
    }

    /**
     * Handles animation metadata upload from client
     * Packet format: String (hash) + String (metadataJson)
     */
    public static void handleUploadAnimationMetadata(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String hash = PacketHelper.readString(buf, TextureTransferLimits.CONTENT_ID_LENGTH);
        String metadataJson = PacketHelper.readString(buf, TextureTransferLimits.MAX_JSON_BYTES);

        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.server;
        UUID playerId = sender.getUUID();
        Object connection = sender.connection;

        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);

            if (player == null) {
                return;
            }

            trackModPresence(player);

            acceptOrDeferAnimationMetadata(
                    player,
                    new ServerUploadCoordinator.PendingMetadata(hash, metadataJson),
                    true);
        });
    }

    /**
     * Handles server config update from admin client
     * Packet format: String (key) + boolean (value)
     */
    public static void handleUpdateServerConfig(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String key = PacketHelper.readString(buf, TextureTransferLimits.MAX_CONFIG_KEY_BYTES);
        boolean value = buf.readBoolean();
        ServerPlayer sender = (ServerPlayer) context.getPlayer();
        if (sender == null
                || !TextureTransferRateLimiter.getInstance().allowStorageMutation(
                        sender.getUUID(), sender.connection)) return;
        MinecraftServer server = sender.server;
        UUID playerId = sender.getUUID();
        Object connection = sender.connection;

        context.queue(() -> {
            ServerPlayer player = activePlayer(server, playerId, connection);

            if (player == null) {
                return;
            }

            trackModPresence(player);

            // Check if player has admin permissions (operator level 2+)
            if (!player.hasPermissions(2)) {
                return;
            }

            // Update server config based on key
            com.quickskin.mod.config.ServerConfig serverConfig =
                com.quickskin.mod.config.ServerConfig.getInstance();

            switch (key) {
                case "disableSkinTransparency":
                    serverConfig.disableSkinTransparency = value;
                    break;
                default:
                    return;
            }

            // Save config to disk
            serverConfig.save();

            // Broadcast config change to ALL clients (including the admin who made the change)
            broadcastServerConfigToAllPlayers(player.server);
        });
    }

    /**
     * Broadcasts a player's appearance and echoes the accepted state to its sender.
     * @param player The player whose appearance changed
     * @param skinId The skin ID
     * @param capeId The cape ID
     * @param model The model type
     */
    private static void broadcastAppearanceToPlayers(ServerPlayer player, String skinId, String capeId, String model) {
        // The coordinator coalesces per-target broadcasts and preserves an exact sender echo as
        // the upload acknowledgement without allowing a mass update to burst control packets.
        APPEARANCE_COORDINATOR.notifyAppearance(player.getUUID());

    }

    /**
     * Sends a player's appearance to a specific client
     * Used when players join or respawn
     * @param recipient The player to send the appearance to
     * @param targetPlayerId The player whose appearance to send
     */
    public static void sendAppearanceToPlayer(ServerPlayer recipient, UUID targetPlayerId) {
        if (!canReceiveQuickSkin(recipient)) {
            return;
        }

        PlayerAppearance appearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(targetPlayerId);

        if (appearance != null) {
            if (!NetworkSecurity.isValidLocalAppearanceId(appearance.getSkinId(), "skin")
                    || !NetworkSecurity.isValidLocalAppearanceId(appearance.getCapeId(), "cape")
                    || !NetworkSecurity.isValidModel(appearance.getModel())) {
                LOGGER.warn("Skipping invalid stored appearance for {}", targetPlayerId);
                return;
            }
            // Send the appearance metadata
            NetworkTransport.INSTANCE.sendAppearanceToPlayer(
                    recipient,
                    targetPlayerId,
                    appearance.getSkinId(),
                    appearance.getCapeId(),
                    appearance.getModel()
            );

            // Texture bytes are demand-driven: the appearance hash lets a cache-missing client
            // request only what it needs. Animation metadata is small and can accompany the hash.
            String capeId = appearance.getCapeId();
            if (capeId != null && capeId.startsWith("local_cape:")) {
                String hash = capeId.substring("local_cape:".length());
                String metadata = ServerAnimationCache.getInstance().getMetadata(hash);
                if (metadata != null) {
                    NetworkTransport.INSTANCE.sendAnimationMetadataToPlayer(
                            recipient, hash, metadata);
                }
            }

        }
    }

    /**
     * Sends all player appearances to a newly joined player
     * @param player The player who just joined
     */
    public static void sendAllAppearancesToPlayer(ServerPlayer player) {
        if (player == null || !canReceiveQuickSkin(player)) return;
        APPEARANCE_COORDINATOR.requestSnapshot(
                player.getUUID(), player.connection,
                ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID);

    }

    /**
     * Sends a specific player's appearance to all OTHER players on the server
     * Used when a player joins to notify existing players of the new player's appearance
     * @param player The player whose appearance to send
     */
    public static void sendAppearanceToAllPlayers(ServerPlayer player) {
        if (player != null
                && ServerPlayerAppearanceRepository.getInstance()
                        .hasAppearance(player.getUUID())) {
            APPEARANCE_COORDINATOR.notifyAppearance(player.getUUID());
        }
    }

    private static boolean sendCachedTextureToClient(
            ServerPlayer player, String textureType, String hash) {
        int size = ServerTextureCache.getInstance().getTextureSize(hash);
        if (!canReceiveQuickSkin(player) || !NetworkSecurity.isValidTextureType(textureType)
                || size <= 0 || size > TextureTransferLimits.MAX_TEXTURE_BYTES) return false;
        TextureTransferRateLimiter.DownloadReservation downloadReservation =
                TextureTransferRateLimiter.getInstance().reserveDownloadBytes(
                        player.getUUID(), player.connection, size);
        if (downloadReservation == null) return false;
        ServerTextureResponseCoordinator.ResponseTicket ticket =
                RESPONSE_COORDINATOR.reserve(player.getUUID(), player.connection, size);
        if (ticket == null) {
            TextureTransferRateLimiter.getInstance()
                    .refundDownloadBytes(downloadReservation);
            return false;
        }

        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        Object connection = player.connection;
        boolean accepted = ServerTextureIngressExecutor.getInstance().submit(size, () -> {
            PreparedTextureResponse response = null;
            try {
                byte[] textureData = ServerTextureCache.getInstance().getTexture(hash);
                if (textureData != null && textureData.length == size) {
                    response = prepareTextureResponse(textureData);
                }
            } catch (RuntimeException | LinkageError error) {
                LOGGER.warn("Unable to prepare a requested texture response", error);
            } finally {
                scheduleTextureResponse(
                        server, playerId, connection, textureType, hash,
                        ticket, downloadReservation, response);
            }
        }, () -> releaseTextureResponse(ticket, downloadReservation, false));
        if (!accepted) releaseTextureResponse(ticket, downloadReservation, false);
        return accepted;
    }

    private static PreparedTextureResponse prepareTextureResponse(byte[] textureData) {
        if (textureData.length <= TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES) {
            return new PreparedTextureResponse(textureData, null);
        }
        int totalChunks = (textureData.length + TextureTransferLimits.CHUNK_BYTES - 1)
                / TextureTransferLimits.CHUNK_BYTES;
        byte[][] chunks = new byte[totalChunks][];
        for (int index = 0; index < totalChunks; index++) {
            int from = index * TextureTransferLimits.CHUNK_BYTES;
            int to = Math.min(textureData.length, from + TextureTransferLimits.CHUNK_BYTES);
            chunks[index] = Arrays.copyOfRange(textureData, from, to);
        }
        return new PreparedTextureResponse(null, chunks);
    }

    private static void scheduleTextureResponse(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            String textureType,
            String hash,
            ServerTextureResponseCoordinator.ResponseTicket ticket,
            TextureTransferRateLimiter.DownloadReservation downloadReservation,
            PreparedTextureResponse response
    ) {
        if (response == null || RESPONSE_COORDINATOR.isCanceled(ticket)) {
            releaseTextureResponse(ticket, downloadReservation, false);
            return;
        }
        PacedTextureResponse handoff = new PacedTextureResponse(
                server, playerId, connection, textureType, hash,
                ticket, downloadReservation, response);
        PACED_TEXTURE_RESPONSES.addLast(handoff);
        if (RESPONSE_COORDINATOR.isCanceled(ticket)
                && PACED_TEXTURE_RESPONSES.remove(handoff)) {
            releaseTextureResponse(ticket, downloadReservation, false);
        }
    }

    /** Emits a bounded, round-robin slice of requested texture packets after each server tick. */
    public static void tickTextureResponses(MinecraftServer server) {
        tickAppearanceControls(server);
        drainPreparedTextureUploads(server);
        int packetsRemaining = MAX_RESPONSE_PACKETS_PER_TICK;
        int bytesRemaining = MAX_RESPONSE_BYTES_PER_TICK;
        while (packetsRemaining > 0 && bytesRemaining > 0
                && !PACED_TEXTURE_RESPONSES.isEmpty()) {
            PacedTextureResponse pending = PACED_TEXTURE_RESPONSES.removeFirst();
            ServerPlayer player = activePlayer(
                    server, pending.playerId, pending.connection);
            if (pending.server != server || player == null || !canReceiveQuickSkin(player)
                    || RESPONSE_COORDINATOR.isCanceled(pending.ticket)
                    || !ServerTextureCache.getInstance().isRequestable(
                            pending.hash, pending.textureType)) {
                releaseTextureResponse(
                        pending.ticket, pending.downloadReservation, pending.nextPacket > 0);
                continue;
            }

            byte[] packet = pending.packet();
            if (packet.length > bytesRemaining) {
                PACED_TEXTURE_RESPONSES.addFirst(pending);
                break;
            }
            try {
                sendPreparedTexturePacket(player, pending, packet);
            } catch (RuntimeException | LinkageError error) {
                releaseTextureResponse(
                        pending.ticket, pending.downloadReservation, pending.nextPacket > 0);
                LOGGER.warn("Unable to enqueue a paced texture response packet", error);
                continue;
            }
            pending.nextPacket++;
            packetsRemaining--;
            bytesRemaining -= packet.length;
            if (pending.isComplete()) {
                releaseTextureResponse(pending.ticket, pending.downloadReservation, true);
            } else {
                PACED_TEXTURE_RESPONSES.addLast(pending);
            }
        }
    }

    private static void tickAppearanceControls(MinecraftServer server) {
        ServerPlayerAppearanceRepository.AppearanceRoster roster =
                ServerPlayerAppearanceRepository.getInstance().snapshotRoster();
        var actions = APPEARANCE_COORDINATOR.tick(
                new ServerAppearanceSyncCoordinator.RosterView(
                        roster.revision(), roster.playerIds()));
        for (ServerAppearanceSyncCoordinator.Action action : actions) {
            ServerPlayer recipient = activePlayer(
                    server, action.playerId(), action.connection());
            if (recipient == null || !canReceiveQuickSkin(recipient)) {
                APPEARANCE_COORDINATOR.cancelSession(
                        action.playerId(), action.connection());
                continue;
            }
            try {
                if (action.type()
                        == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE) {
                    sendAppearanceToPlayer(recipient, action.targetPlayerId());
                } else {
                    NetworkTransport.INSTANCE.sendAppearanceSnapshotCompleteToPlayer(
                            recipient, action.requestId());
                }
            } catch (RuntimeException | LinkageError error) {
                if (action.type()
                        == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE) {
                    APPEARANCE_COORDINATOR.retryAppearance(
                            action.playerId(), action.connection(),
                            action.targetPlayerId());
                }
                LOGGER.warn("Unable to enqueue a paced appearance control packet", error);
            }
        }
    }

    private static void drainPreparedTextureUploads(MinecraftServer server) {
        for (int count = 0; count < MAX_UPLOAD_COMMITS_PER_TICK; count++) {
            PreparedTextureUpload pending = PREPARED_TEXTURE_UPLOADS.pollFirst();
            if (pending == null) return;
            if (pending.server != server) {
                discardPreparedTextureUpload(pending);
                continue;
            }
            completeTextureUpload(
                    server, pending.playerId, pending.connection,
                    pending.ticket, pending.prepared);
        }
    }

    private static void discardPreparedTextureUploads(UUID playerId, Object connection) {
        for (PreparedTextureUpload pending : PREPARED_TEXTURE_UPLOADS) {
            if (pending.playerId.equals(playerId) && pending.connection == connection
                    && PREPARED_TEXTURE_UPLOADS.remove(pending)) {
                discardPreparedTextureUpload(pending);
            }
        }
    }

    private static void discardAllPreparedTextureUploads() {
        PreparedTextureUpload pending;
        while ((pending = PREPARED_TEXTURE_UPLOADS.pollFirst()) != null) {
            discardPreparedTextureUpload(pending);
        }
    }

    private static void discardPreparedTextureUpload(PreparedTextureUpload pending) {
        if (pending.prepared != null) pending.prepared.close();
        UPLOAD_COORDINATOR.finishUpload(pending.ticket);
    }

    private static void sendPreparedTexturePacket(
            ServerPlayer player, PacedTextureResponse pending, byte[] packet) {
        if (pending.response.direct != null) {
            NetworkTransport.INSTANCE.sendTextureToPlayer(
                    player, pending.textureType, pending.hash, packet);
            return;
        }
        NetworkTransport.INSTANCE.sendTextureChunkToPlayer(
                player, pending.hash, pending.textureType, pending.nextPacket,
                pending.response.chunks.length, packet);
    }

    private static void releaseTextureResponse(
            ServerTextureResponseCoordinator.ResponseTicket ticket,
            TextureTransferRateLimiter.DownloadReservation downloadReservation,
            boolean emittedAnyPacket
    ) {
        RESPONSE_COORDINATOR.release(ticket);
        if (emittedAnyPacket) {
            TextureTransferRateLimiter.getInstance()
                    .commitDownloadBytes(downloadReservation);
        } else {
            TextureTransferRateLimiter.getInstance()
                    .refundDownloadBytes(downloadReservation);
        }
    }

    private static void discardPacedTextureResponses(UUID playerId, Object connection) {
        for (PacedTextureResponse pending : PACED_TEXTURE_RESPONSES) {
            if (pending.playerId.equals(playerId) && pending.connection == connection
                    && PACED_TEXTURE_RESPONSES.remove(pending)) {
                releaseTextureResponse(
                        pending.ticket, pending.downloadReservation, pending.nextPacket > 0);
            }
        }
    }

    private static void discardAllPacedTextureResponses() {
        while (!PACED_TEXTURE_RESPONSES.isEmpty()) {
            PacedTextureResponse pending = PACED_TEXTURE_RESPONSES.removeFirst();
            releaseTextureResponse(
                    pending.ticket, pending.downloadReservation, pending.nextPacket > 0);
        }
    }

    private record PreparedTextureResponse(byte[] direct, byte[][] chunks) {
    }

    private static final class PacedTextureResponse {
        private final MinecraftServer server;
        private final UUID playerId;
        private final Object connection;
        private final String textureType;
        private final String hash;
        private final ServerTextureResponseCoordinator.ResponseTicket ticket;
        private final TextureTransferRateLimiter.DownloadReservation downloadReservation;
        private final PreparedTextureResponse response;
        private int nextPacket;

        private PacedTextureResponse(
                MinecraftServer server,
                UUID playerId,
                Object connection,
                String textureType,
                String hash,
                ServerTextureResponseCoordinator.ResponseTicket ticket,
                TextureTransferRateLimiter.DownloadReservation downloadReservation,
                PreparedTextureResponse response
        ) {
            this.server = server;
            this.playerId = playerId;
            this.connection = connection;
            this.textureType = textureType;
            this.hash = hash;
            this.ticket = ticket;
            this.downloadReservation = downloadReservation;
            this.response = response;
        }

        private byte[] packet() {
            return response.direct != null ? response.direct : response.chunks[nextPacket];
        }

        private boolean isComplete() {
            return response.direct != null || nextPacket >= response.chunks.length;
        }
    }

    private record PreparedTextureUpload(
            MinecraftServer server,
            UUID playerId,
            Object connection,
            ServerUploadCoordinator.UploadTicket ticket,
            ServerTextureCache.PreparedTexture prepared) {
    }

    private static void acceptOrDeferAppearance(
            ServerPlayer player,
            ServerUploadCoordinator.PendingAppearance appearance,
            boolean supersedeOlder
    ) {
        if (!NetworkSecurity.isValidModel(appearance.model())
                || !NetworkSecurity.isValidLocalAppearanceId(appearance.skinId(), "skin")
                || !NetworkSecurity.isValidLocalAppearanceId(appearance.capeId(), "cape")) {
            LOGGER.warn("Rejected invalid appearance update from {}", player.getUUID());
            return;
        }
        if (supersedeOlder) {
            UPLOAD_COORDINATOR.supersedeAppearance(player.getUUID(), player.connection);
        }

        String missingSkinHash = missingOwnedTextureHash(
                player, appearance.skinId(), "local_skin:", "skin");
        String missingCapeHash = missingOwnedTextureHash(
                player, appearance.capeId(), "local_cape:", "cape");
        if (missingSkinHash != null || missingCapeHash != null) {
            if (UPLOAD_COORDINATOR.deferAppearance(
                    player.getUUID(), player.connection, appearance,
                    missingSkinHash, missingCapeHash)) {
                return;
            }
            LOGGER.warn("Rejected unauthorized appearance update from {}", player.getUUID());
            return;
        }

        applyAppearance(player, appearance);
    }

    private static void applyAppearance(
            ServerPlayer player, ServerUploadCoordinator.PendingAppearance appearance) {
        UUID playerId = player.getUUID();
        int cooldownSeconds =
                com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;
        PlayerAppearance currentAppearance =
                ServerPlayerAppearanceRepository.getInstance().getAppearance(playerId);
        boolean isSkinChanging = appearance.skinId() != null
                && !appearance.skinId().isEmpty()
                && (currentAppearance == null
                        || !appearance.skinId().equals(currentAppearance.getSkinId()));

        if (isSkinChanging && cooldownSeconds > 0
                && ServerCooldownManager.getInstance().isPlayerOnCooldown(playerId)) return;

        if (!ServerPlayerAppearanceRepository.getInstance().tryUpdateAppearance(
                playerId, appearance.skinId(), appearance.capeId(), appearance.model())) {
            LOGGER.warn("Refused appearance update from {} because the global active-texture byte cap was reached",
                    playerId);
            sendAppearanceToPlayer(player, playerId);
            return;
        }

        if (isSkinChanging && cooldownSeconds > 0) {
            ServerCooldownManager.getInstance().recordSkinChange(playerId);
            long cooldownEndTime =
                    ServerCooldownManager.getInstance().getCooldownEndTime(playerId);
            NetworkTransport.INSTANCE.sendCooldownToPlayer(player, cooldownEndTime);
        }
        // Peers receive only content hashes; cache misses use the texture request packet.
        broadcastAppearanceToPlayers(
                player, appearance.skinId(), appearance.capeId(), appearance.model());
    }

    private static String missingOwnedTextureHash(
            ServerPlayer player, String appearanceId, String prefix, String textureType) {
        if (appearanceId == null || !appearanceId.startsWith(prefix)) return null;
        String hash = appearanceId.substring(prefix.length());
        return ServerTextureCache.getInstance().isOwnedBy(
                hash, player.getUUID(), textureType) ? null : hash;
    }

    private static void acceptOrDeferAnimationMetadata(
            ServerPlayer player,
            ServerUploadCoordinator.PendingMetadata metadata,
            boolean supersedeOlder
    ) {
        if (!NetworkSecurity.isValidContentId(metadata.hash())
                || !NetworkSecurity.isValidAnimationMetadata(metadata.metadataJson())) {
            LOGGER.warn("Rejected invalid animation metadata from {}", player.getUUID());
            return;
        }
        if (supersedeOlder) {
            UPLOAD_COORDINATOR.supersedeMetadata(
                    player.getUUID(), player.connection, metadata.hash());
        }

        if (!ServerTextureCache.getInstance().isOwnedBy(
                metadata.hash(), player.getUUID(), "cape")) {
            if (UPLOAD_COORDINATOR.deferMetadata(
                    player.getUUID(), player.connection, metadata)) return;
            LOGGER.warn("Rejected unauthorized animation metadata from {}", player.getUUID());
            return;
        }
        if (!ServerTextureCache.getInstance().isAnimationMetadataCompatible(
                metadata.hash(), metadata.metadataJson())) {
            LOGGER.warn("Rejected animation metadata that does not match its PNG identity from {}",
                    player.getUUID());
            return;
        }
        if (!ServerAnimationCache.getInstance().storeMetadata(
                metadata.hash(), metadata.metadataJson(), player.getUUID())) {
            LOGGER.warn("Rejected animation metadata storage from {}", player.getUUID());
            return;
        }
        broadcastAnimationMetadataToOtherPlayers(
                player, metadata.hash(), metadata.metadataJson());
    }

    private static void retryDeferredPackets(
            ServerPlayer player, ServerUploadCoordinator.RetryBatch batch) {
        // Metadata is committed/echoed first so its acknowledgement is causally ordered before
        // an appearance starts referencing the newly committed animated cape.
        for (ServerUploadCoordinator.PendingMetadata metadata : batch.metadata()) {
            acceptOrDeferAnimationMetadata(player, metadata, false);
        }
        if (batch.appearance() != null) {
            acceptOrDeferAppearance(player, batch.appearance(), false);
        }
    }

    public static void onPlayerDisconnected(UUID playerId, Object connection) {
        APPEARANCE_COORDINATOR.cancelSession(playerId, connection);
        RESPONSE_COORDINATOR.cancelSession(playerId, connection);
        discardPacedTextureResponses(playerId, connection);
        UPLOAD_COORDINATOR.removeSession(playerId, connection);
        discardPreparedTextureUploads(playerId, connection);
        TextureTransferRateLimiter.getInstance().removeSession(playerId, connection);
        TextureChunkAssembler.getInstance().discardSession(playerId, connection);
    }

    public static void clearTransientNetworkState() {
        APPEARANCE_COORDINATOR.cancelAll();
        RESPONSE_COORDINATOR.cancelAll();
        discardAllPacedTextureResponses();
        UPLOAD_COORDINATOR.clear();
        discardAllPreparedTextureUploads();
        TextureTransferRateLimiter.getInstance().clear();
        TextureChunkAssembler.getInstance().clear();
    }

    /**
     * Broadcasts animation metadata to all other players on the server
     * @param player The player who uploaded the metadata
     * @param hash The texture hash
     * @param metadataJson The animation metadata JSON
     */
    private static void broadcastAnimationMetadataToOtherPlayers(ServerPlayer player, String hash, String metadataJson) {
        // Echo the exact hash+JSON to acknowledge storage, and notify peers with the same packet.
        for (ServerPlayer recipient : player.server.getPlayerList().getPlayers()) {
            if (canReceiveQuickSkin(recipient)) {
                NetworkTransport.INSTANCE.sendAnimationMetadataToPlayer(
                        recipient, hash, metadataJson);
            }
        }

    }

    /**
     * Sends server config to a specific player (called on player join)
     * @param player The player to send config to
     */
    public static void sendServerConfigToPlayer(ServerPlayer player) {
        if (!canReceiveQuickSkin(player)) {
            return;
        }
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        NetworkTransport.INSTANCE.sendServerConfigToPlayer(player, configJson);
    }

    /**
     * Broadcasts server config to ALL players on the server
     * Called when an admin changes a server setting
     * @param server The server instance
     */
    private static void broadcastServerConfigToAllPlayers(net.minecraft.server.MinecraftServer server) {
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        // Send to all players that have QuickSkin (including the admin who made the change)
        // IMPORTANT: Create a fresh packet buffer for each player to avoid buffer exhaustion
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (canReceiveQuickSkin(player)) {
                NetworkTransport.INSTANCE.sendServerConfigToPlayer(player, configJson);
            }
        }

    }
}
