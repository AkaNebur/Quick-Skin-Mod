package com.quickskin.mod.e2e;

import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.util.HashUtil;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Generates deterministic test textures at runtime so no binary assets need to be committed and the
 * file-upload flow can be exercised. Skins are written to a temp file and handed to
 * {@code SkinImporter.importSkin(Path)}; capes are registered headlessly via
 * {@link #registerLocalCape(Path)} (bypassing the interactive {@code CapeAdjustScreen}).
 */
public final class TestAssets {

    private TestAssets() {}

    /** Classpath location of an optional real skin bundled into the e2e resources (see makeClassicSkin). */
    private static final String BUNDLED_SKIN = "/qs_e2e_test_skin.png";

    /**
     * The skin used by every scenario. Prefers a real skin bundled at {@link #BUNDLED_SKIN} (a 64x64
     * PNG dropped into {@code common/src/e2e/resources/}) so screenshots show a realistic player; if
     * that resource is absent it falls back to a synthetic, unmistakable magenta skin (opaque
     * everywhere -> auto-detection resolves to classic). Either way the scenarios derive the content
     * hash dynamically from {@code SkinImporter.importSkin}, so all assertions stay valid.
     */
    public static Path makeClassicSkin() throws Exception {
        try (InputStream in = TestAssets.class.getResourceAsStream(BUNDLED_SKIN)) {
            if (in != null) {
                Path tmp = Files.createTempFile("qs_e2e_skin_", ".png");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                E2ELog.info("using bundled real skin " + BUNDLED_SKIN);
                return tmp;
            }
        } catch (Exception e) {
            E2ELog.warn("bundled skin load failed, using synthetic magenta: " + e);
        }
        return makeSyntheticMagentaSkin();
    }

    /** The original synthetic skin: flat magenta + cyan face patch + yellow stripe (fallback). */
    private static Path makeSyntheticMagentaSkin() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Base: a flat, unmistakable magenta so the player stands out against the flat world.
        g.setColor(new Color(0xCC, 0x22, 0x99));
        g.fillRect(0, 0, 64, 64);
        // Head front (8,8..16,16) painted a contrasting cyan as a visual landmark.
        g.setColor(new Color(0x22, 0xCC, 0xCC));
        g.fillRect(8, 8, 8, 8);
        // A yellow stripe across the body for orientation in screenshots.
        g.setColor(new Color(0xEE, 0xDD, 0x22));
        g.fillRect(0, 20, 64, 4);
        g.dispose();
        Path tmp = Files.createTempFile("qs_e2e_skin_", ".png");
        ImageIO.write(img, "png", tmp.toFile());
        return tmp;
    }

    /**
     * A valid 64x32 opaque cape {@link BufferedImage} (the vanilla cape format; a 2:1 frame ratio so
     * {@code LocalAssetManager} accepts it). Distinctive colors so the cape is obvious in screenshots.
     * Exposed as an image so it can be fed directly to {@code CapeAdjustScreen}.
     */
    public static BufferedImage makeClassicCapeImage() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Base: deep blue covering the whole atlas (incl. the elytra region) so the import does NOT
        // composite the vanilla elytra under it (which would re-encode and change the hash).
        g.setColor(new Color(0x22, 0x33, 0xAA));
        g.fillRect(0, 0, 64, 32);
        // The visible cape "front" is the (1,1)-(10,16) region. Paint it a bright orange landmark.
        g.setColor(new Color(0xEE, 0x88, 0x11));
        g.fillRect(1, 1, 10, 16);
        // A green stripe across the front for orientation.
        g.setColor(new Color(0x22, 0xCC, 0x44));
        g.fillRect(1, 5, 10, 3);
        g.dispose();
        return img;
    }

    /** {@link #makeClassicCapeImage()} written to a temp PNG file. */
    public static Path makeClassicCape() throws Exception {
        Path tmp = Files.createTempFile("qs_e2e_cape_", ".png");
        ImageIO.write(makeClassicCapeImage(), "png", tmp.toFile());
        return tmp;
    }

    /**
     * A valid <b>256x128</b> opaque cape PNG. 256x128 is exactly {@code SkinResolution.CAPE_256}
     * (and {@code height % (width/2) == 128 % 128 == 0}, a single static frame), so
     * {@code LocalAssetManager.processPngAsset} keeps it verbatim instead of resizing — letting the
     * "HD cape import preserves source resolution (no downscale)" property be asserted on the metadata.
     */
    public static Path makeHdCape() throws Exception {
        final int w = 256, h = 128;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Deep teal base over the whole HD atlas so no elytra compositing kicks in on import.
        g.setColor(new Color(0x11, 0x77, 0x88));
        g.fillRect(0, 0, w, h);
        // The visible cape front scales 4x from the 64x32 layout: (4,4)-(40,64). Bright magenta landmark.
        g.setColor(new Color(0xDD, 0x22, 0xAA));
        g.fillRect(4, 4, 36, 60);
        // A yellow stripe across the front for orientation in screenshots.
        g.setColor(new Color(0xEE, 0xDD, 0x22));
        g.fillRect(4, 20, 36, 10);
        g.dispose();
        Path tmp = Files.createTempFile("qs_e2e_cape_hd_", ".png");
        ImageIO.write(img, "png", tmp.toFile());
        return tmp;
    }

    /** Classpath location of an optional real animated GIF cape bundled into the e2e resources. */
    private static final String BUNDLED_CAPE_GIF = "/qs_e2e_test_cape.gif";

    /**
     * Extract the optional bundled animated GIF cape (dropped into {@code common/src/e2e/resources/})
     * to a temp {@code .gif} file, or {@code null} if none is bundled.
     */
    public static Path makeGifCape() throws Exception {
        try (InputStream in = TestAssets.class.getResourceAsStream(BUNDLED_CAPE_GIF)) {
            if (in == null) return null;
            Path tmp = Files.createTempFile("qs_e2e_cape_", ".gif");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    /**
     * Register the bundled GIF as a LOCAL <b>animated</b> cape headlessly. {@code LocalAssetManager}
     * decodes the GIF (via {@code StbGifLoader}) into a vertical frame atlas + {@code AnimationMetadata}
     * keyed by the GIF file's content hash, so this returns the same {@code "local_cape:" + hash} id the
     * rest of the harness uses. Returns {@code null} if no GIF is bundled or registration failed.
     */
    public static String registerBundledGifCape() throws Exception {
        Path gif = makeGifCape();
        if (gif == null) {
            E2ELog.warn("no bundled GIF cape at " + BUNDLED_CAPE_GIF);
            return null;
        }
        return registerLocalCapeAs(gif, "qs_e2e_cape.gif");
    }

    /**
     * Register a cape PNG as a LOCAL cape headlessly: copy the exact bytes into
     * {@code LocalAssetManager}'s capes directory, reload the asset index, and return the SHA-1 content
     * hash (usable as {@code "local_cape:" + hash}). Copying verbatim (rather than going through the
     * interactive {@code PlayerCapeMenuScreen.processDroppedFile}/{@code CapeAdjustScreen}) keeps the
     * on-disk bytes — and therefore the hash — fully deterministic.
     *
     * @return the SHA-1 hex hash, or {@code null} if registration failed (cape not discovered).
     */
    public static String registerLocalCape(Path capePng) throws Exception {
        return registerLocalCapeAs(capePng, "qs_e2e_cape.png");
    }

    /**
     * As {@link #registerLocalCape(Path)} but with an explicit on-disk filename, so multiple distinct
     * capes (e.g. a standard and an HD one) can coexist in the capes directory with different hashes.
     */
    public static String registerLocalCapeAs(Path capePng, String filename) throws Exception {
        LocalAssetManager mgr = LocalAssetManager.getInstance();
        Path capesDir = mgr.getCapesDirectory();
        if (capesDir == null) {
            E2ELog.warn("LocalAssetManager.getCapesDirectory() is null (not initialized yet)");
            return null;
        }
        Files.createDirectories(capesDir);
        Path target = capesDir.resolve(filename);
        Files.copy(capePng, target, StandardCopyOption.REPLACE_EXISTING);
        mgr.reload();
        String hash = HashUtil.computeFileHash(target);
        if (hash == null || mgr.getMetadata(hash) == null) {
            E2ELog.warn("cape not discovered after reload (file=" + filename + " hash=" + hash + ")");
            return null;
        }
        return hash;
    }
}
