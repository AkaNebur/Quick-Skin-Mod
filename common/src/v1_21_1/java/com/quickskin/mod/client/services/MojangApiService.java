package com.quickskin.mod.client.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for interacting with Mojang's API to fetch player skins
 */
@Environment(EnvType.CLIENT)
public class MojangApiService {
    private static MojangApiService instance;

    private static final String MOJANG_API_BASE = "https://api.mojang.com";
    private static final String SESSION_SERVER_BASE = "https://sessionserver.mojang.com";

    private MojangApiService() {}

    public static MojangApiService getInstance() {
        if (instance == null) {
            instance = new MojangApiService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
        QuickSkin.LOGGER.info("MojangApiService initialized");
    }

    /**
     * Fetch a player's UUID from their username
     * @param username The player's username
     * @return CompletableFuture containing the UUID, or null if not found
     */
    public CompletableFuture<UUID> getUuidFromUsername(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = MOJANG_API_BASE + "/users/profiles/minecraft/" + username;
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                    );
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                    String uuidString = json.get("id").getAsString();

                    // Add dashes to UUID string
                    String formattedUuid = uuidString.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                        "$1-$2-$3-$4-$5"
                    );

                    QuickSkin.LOGGER.info("Found UUID for {}: {}", username, formattedUuid);
                    return UUID.fromString(formattedUuid);
                } else if (responseCode == 204 || responseCode == 404) {
                    QuickSkin.LOGGER.warn("Player not found: {}", username);
                    return null;
                } else {
                    QuickSkin.LOGGER.error("Unexpected response code: {}", responseCode);
                    return null;
                }
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to fetch UUID for username: {}", username, e);
                return null;
            }
        });
    }

    /**
     * Fetch a player's skin texture data from their UUID
     * @param uuid The player's UUID
     * @return CompletableFuture containing the skin texture data
     */
    public CompletableFuture<SkinTextureData> getSkinTextureData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uuidString = uuid.toString().replace("-", "");
                String urlString = SESSION_SERVER_BASE + "/session/minecraft/profile/" + uuidString;
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                    );
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                    JsonObject properties = json.getAsJsonArray("properties").get(0).getAsJsonObject();
                    String texturesBase64 = properties.get("value").getAsString();

                    // Decode the base64 textures
                    String texturesJson = new String(Base64.getDecoder().decode(texturesBase64), StandardCharsets.UTF_8);
                    JsonObject texturesObject = JsonParser.parseString(texturesJson).getAsJsonObject();
                    JsonObject textures = texturesObject.getAsJsonObject("textures");

                    if (textures.has("SKIN")) {
                        JsonObject skinObject = textures.getAsJsonObject("SKIN");
                        String skinUrl = skinObject.get("url").getAsString();

                        // Determine model type (slim/default)
                        String modelType = "default";
                        if (skinObject.has("metadata")) {
                            JsonObject metadata = skinObject.getAsJsonObject("metadata");
                            if (metadata.has("model") && metadata.get("model").getAsString().equals("slim")) {
                                modelType = "slim";
                            }
                        }

                        QuickSkin.LOGGER.info("Found skin URL for UUID {}: {}", uuid, skinUrl);
                        return new SkinTextureData(skinUrl, modelType);
                    } else {
                        QuickSkin.LOGGER.warn("No skin found for UUID: {}", uuid);
                        return null;
                    }
                } else {
                    QuickSkin.LOGGER.error("Unexpected response code: {}", responseCode);
                    return null;
                }
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to fetch skin texture data for UUID: {}", uuid, e);
                return null;
            }
        });
    }

    /**
     * Download a skin image from a URL
     * @param skinUrl The URL to download from
     * @return CompletableFuture containing the BufferedImage
     */
    public CompletableFuture<BufferedImage> downloadSkinImage(String skinUrl) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(skinUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    InputStream inputStream = connection.getInputStream();
                    BufferedImage image = ImageIO.read(inputStream);
                    inputStream.close();

                    QuickSkin.LOGGER.info("Successfully downloaded skin image from: {}", skinUrl);
                    return image;
                } else {
                    QuickSkin.LOGGER.error("Failed to download skin image, response code: {}", responseCode);
                    return null;
                }
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to download skin image from: {}", skinUrl, e);
                return null;
            }
        });
    }

    /**
     * Fetch and download a player's skin by username
     * Combines UUID lookup, texture data fetch, and image download
     * @param username The player's username
     * @return CompletableFuture containing the MojangSkinData
     */
    public CompletableFuture<MojangSkinData> fetchSkinByUsername(String username) {
        return getUuidFromUsername(username)
            .thenCompose(uuid -> {
                if (uuid == null) {
                    return CompletableFuture.completedFuture(null);
                }
                return getSkinTextureData(uuid)
                    .thenCompose(textureData -> {
                        if (textureData == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return downloadSkinImage(textureData.url)
                            .thenApply(image -> {
                                if (image == null) {
                                    return null;
                                }
                                return new MojangSkinData(username, uuid, image, textureData.modelType);
                            });
                    });
            });
    }

    /**
     * Container for skin texture data
     */
    public static class SkinTextureData {
        public final String url;
        public final String modelType;

        public SkinTextureData(String url, String modelType) {
            this.url = url;
            this.modelType = modelType;
        }
    }

    /**
     * Container for complete Mojang skin data
     */
    public static class MojangSkinData {
        public final String username;
        public final UUID uuid;
        public final BufferedImage image;
        public final String modelType;

        public MojangSkinData(String username, UUID uuid, BufferedImage image, String modelType) {
            this.username = username;
            this.uuid = uuid;
            this.image = image;
            this.modelType = modelType;
        }
    }
}
