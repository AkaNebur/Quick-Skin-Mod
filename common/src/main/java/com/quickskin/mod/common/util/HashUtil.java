package com.quickskin.mod.common.util;

import com.quickskin.mod.QuickSkin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Utility for computing file hashes
 * Used for asset identification and deduplication
 */
public class HashUtil {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Compute SHA1 hash of a file
     * Optimized for large files with buffered reading
     *
     * @param path Path to file
     * @return Hex string of SHA1 hash, or null on error
     */
    public static String computeFileHash(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");

            try (InputStream is = Files.newInputStream(path)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return bytesToHex(digest.digest());

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compute SHA1 hash of byte array
     */
    public static String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(data);
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Produces a stable local asset identity. Cape IDs are domain-separated from raw skin IDs,
     * because the same valid PNG bytes can legitimately be imported in both rendering roles.
     * Network content hashes remain hashes of the canonical transmitted PNG itself.
     */
    public static String computeAssetHash(byte[] data, String assetType) {
        if (!"cape".equals(assetType)) return computeHash(data);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update("quickskin:cape\0".getBytes(StandardCharsets.UTF_8));
            digest.update(data);
            return bytesToHex(digest.digest());
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * Convert byte array to hex string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

}
