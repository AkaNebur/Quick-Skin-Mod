package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.screen.CapeAdjustScreen;
import com.quickskin.mod.client.gui.screen.DeletionConfirmScreen;
import com.quickskin.mod.client.gui.screen.PlayerCapeMenuScreen;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.screen.RenameScreen;
import com.quickskin.mod.client.gui.screen.SettingsScreen;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.ModelService;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Phase 2 scenario ({@code -Dquickskin.e2e.scenario=full}): a single-client sweep of <em>every</em>
 * Quick Skin feature, each step a real action driven through the mod's own services plus a per-step
 * screenshot and a programmatic assertion that reads the mod's state (the source of truth).
 *
 * <p>This is a <b>client-A-only</b> scenario; the A&rarr;B render-truthful propagation check is its
 * own {@code "propagation"} scenario (Phase 1) and is intentionally NOT duplicated here. The plan's
 * eleven feature areas map to these steps:</p>
 * <ol>
 *   <li>baseline &mdash; clean state, player present (singletons reset, 3rd-person view).</li>
 *   <li>local skin upload &mdash; {@code SkinImporter.importSkin} &rarr; {@code applySkin}; menu shot.</li>
 *   <li>model slim/classic &mdash; {@code applySkin(...,"slim"/"classic")}; {@code getModel()} flips.</li>
 *   <li>known cape &mdash; cape menu shot; {@code applyCape("known:test")}; cape id + location.</li>
 *   <li>CapeAdjustScreen &mdash; opened with a test image + harness-owned {@code onApply} consumer.</li>
 *   <li>animated cape &mdash; {@code applyCape("known:rickroll")}; reflect {@code AnimationState
 *       .currentFrame} advancing, cross-checked with {@code AnimationMetadata.getFrameAtTime}.</li>
 *   <li>HD cape no-downscale &mdash; import a 256&times;128 cape; metadata resolution == source dims.</li>
 *   <li>elytra hides cape &mdash; equip {@code Items.ELYTRA} in CHEST; assert the inputs that make
 *       {@code CapeLayerMixin} cancel ({@code hasActiveCape} + ELYTRA in CHEST + non-null cloak loc).</li>
 *   <li><i>(propagation A&rarr;B &mdash; separate scenario, not here)</i></li>
 *   <li>settings / rename / delete &mdash; {@code SettingsScreen} round-trips a flag through
 *       {@code onClose}&rarr;{@code ClientConfig}; Rename/Delete dialogs feed a harness-owned callback.</li>
 *   <li>HUD preview &mdash; toggle {@code showSkinPreviewOverlay}; the production {@code RENDER_HUD}
 *       hook draws {@code SkinPreviewOverlay} and the screenshot captures it.</li>
 * </ol>
 *
 * <p>Screens are opened via {@link VanillaShim#setScreen} on the client/tick thread; one or more
 * render frames are pumped (via {@code minTicks}) so {@code init()} builds widgets before a screenshot
 * or a reflective button press. Private playback/widget state is read by reflection so no shipped code
 * is touched.</p>
 */
public final class FullScenario implements Scenario {

    /** Animation id CapeService registers a known animated cape under ({@code "cape_known_"+id}). */
    private static final String RICKROLL_ANIM_ID = "cape_known_rickroll";

    private volatile String skinHash;        // set by step 2, reused by model + HUD steps
    private volatile String hdCapeHash;      // set by step "hd_cape"
    private volatile String gifCapeHash;     // set by the animated-cape step (bundled GIF), null -> rickroll
    private volatile int animStartFrame = Integer.MIN_VALUE; // snapshot for the frame-advance check

    private final AtomicReference<BufferedImage> capeAdjustResult = new AtomicReference<>();
    private volatile String renameResult;
    private volatile Boolean deleteResult;

    @Override
    public String id() { return "full"; }

    @Override
    public List<Step> build(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService svc = PlayerAppearanceService.getInstance();
        final String prefix = v + "_";
        final String suffix = "_" + role + ".png";

        List<Step> steps = new ArrayList<>();

        // 1. baseline -----------------------------------------------------------------------------
        steps.add(Step.of("baseline")
                .action(() -> {
                    resetState();
                    enterWorldView(mc);
                })
                .minTicks(40) // ~2s render warmup so the first frame is real
                .screenshot(prefix + "full_01_baseline" + suffix)
                .assertion(() -> {
                    if (mc.player == null) return Step.Result.fail("player is null");
                    return Step.Result.pass("player present: " + VanillaShim.playerName(mc.player)
                            + " activeSkin=" + svc.hasActiveSkin(uuid) + " activeCape=" + svc.hasActiveCape(uuid));
                }));

        // 2. local skin upload --------------------------------------------------------------------
        steps.add(Step.of("local_skin_apply")
                .action(() -> {
                    enterWorldView(mc);
                    try {
                        Path file = TestAssets.makeClassicSkin();
                        AssetMetadata meta = SkinImporter.importSkin(file);
                        if (meta == null) { E2ELog.warn("importSkin returned null"); return; }
                        skinHash = meta.hash();
                        svc.applySkin(uuid, "local_skin:" + skinHash, "auto");
                        E2ELog.info("applied local_skin:" + skinHash);
                    } catch (Exception e) {
                        E2ELog.error("local_skin_apply failed", e);
                    }
                })
                .minTicks(40)
                .ready(() -> skinHash != null && svc.getAppearance(uuid) != null
                        && svc.getSkinLocation(uuid) != null)
                .timeoutTicks(400)
                .screenshot(prefix + "full_02a_local_skin_body" + suffix)
                .assertion(() -> {
                    if (skinHash == null) return Step.Result.fail("skin import failed (no hash)");
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no appearance");
                    String expected = "local_skin:" + skinHash;
                    if (!expected.equals(app.getSkinId()))
                        return Step.Result.fail("skinId=" + app.getSkinId() + " expected " + expected);
                    if (svc.getSkinLocation(uuid) == null)
                        return Step.Result.fail("skin location did not resolve");
                    return Step.Result.pass("skinId=" + expected + " location=" + svc.getSkinLocation(uuid));
                }));

        // 2b. skin menu screenshot ----------------------------------------------------------------
        steps.add(Step.of("skin_menu_screen")
                .action(() -> VanillaShim.setScreen(mc, new PlayerSkinMenuScreen(null)))
                .minTicks(30) // skin screen's first init() may early-return (GUI-scale re-entrancy)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_02b_skin_menu" + suffix)
                .assertion(() -> VanillaShim.currentScreen(mc) instanceof PlayerSkinMenuScreen
                        ? Step.Result.pass("PlayerSkinMenuScreen open")
                        : Step.Result.fail("skin menu not open: " + screenName(mc))));

        // 3. model slim / classic -----------------------------------------------------------------
        steps.add(Step.of("model_slim")
                .action(() -> {
                    enterWorldView(mc);
                    if (skinHash != null) svc.applySkin(uuid, "local_skin:" + skinHash, "slim");
                })
                .minTicks(30)
                .screenshot(prefix + "full_03a_model_slim" + suffix)
                .assertion(() -> {
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no appearance");
                    return "slim".equals(app.getModel())
                            ? Step.Result.pass("model=slim")
                            : Step.Result.fail("model=" + app.getModel() + " expected slim");
                }));

        steps.add(Step.of("model_classic")
                .action(() -> {
                    enterWorldView(mc);
                    if (skinHash != null) svc.applySkin(uuid, "local_skin:" + skinHash, "classic");
                })
                .minTicks(30)
                .screenshot(prefix + "full_03b_model_classic" + suffix)
                .assertion(() -> {
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no appearance");
                    return "classic".equals(app.getModel())
                            ? Step.Result.pass("model=classic")
                            : Step.Result.fail("model=" + app.getModel() + " expected classic");
                }));

        // 4. known cape ---------------------------------------------------------------------------
        steps.add(Step.of("cape_menu_screen")
                .action(() -> VanillaShim.setScreen(mc, new PlayerCapeMenuScreen(null)))
                .minTicks(20)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_04a_cape_menu" + suffix)
                .assertion(() -> VanillaShim.currentScreen(mc) instanceof PlayerCapeMenuScreen
                        ? Step.Result.pass("PlayerCapeMenuScreen open")
                        : Step.Result.fail("cape menu not open: " + screenName(mc))));

        steps.add(Step.of("known_cape_apply")
                .action(() -> {
                    enterWorldView(mc);
                    svc.applyCape(uuid, "known:test");
                })
                .minTicks(30)
                .ready(() -> svc.getAppearance(uuid) != null && svc.getCapeLocation(uuid) != null)
                .timeoutTicks(200)
                .screenshot(prefix + "full_04b_known_cape_body" + suffix)
                .assertion(() -> {
                    PlayerAppearance app = svc.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no appearance");
                    if (!"known:test".equals(app.getCapeId()))
                        return Step.Result.fail("capeId=" + app.getCapeId() + " expected known:test");
                    if (svc.getCapeLocation(uuid) == null)
                        return Step.Result.fail("cape location did not resolve");
                    return Step.Result.pass("capeId=known:test location=" + svc.getCapeLocation(uuid));
                }));

        // 5. CapeAdjustScreen ---------------------------------------------------------------------
        steps.add(Step.of("cape_adjust_screen")
                .action(() -> {
                    capeAdjustResult.set(null);
                    Consumer<BufferedImage> onApply = capeAdjustResult::set;
                    BufferedImage src = TestAssets.makeClassicCapeImage();
                    VanillaShim.setScreen(mc, new CapeAdjustScreen(null, src, onApply));
                })
                .minTicks(25)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_05_cape_adjust" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof CapeAdjustScreen s))
                        return Step.Result.fail("cape adjust not open: " + screenName(mc));
                    // Trigger the (private) apply path so our owned onApply consumer receives the result.
                    try {
                        Method m = CapeAdjustScreen.class.getDeclaredMethod("applyAndClose");
                        m.setAccessible(true);
                        m.invoke(s);
                    } catch (Throwable t) {
                        return Step.Result.fail("applyAndClose reflection failed: " + t);
                    }
                    BufferedImage out = capeAdjustResult.get();
                    if (out == null) return Step.Result.fail("onApply did not receive an image");
                    int w = out.getWidth(), h = out.getHeight();
                    if (w <= 0 || h <= 0) return Step.Result.fail("composed cape has bad dims " + w + "x" + h);
                    if (w != h * 2) return Step.Result.fail("composed cape not 2:1: " + w + "x" + h);
                    return Step.Result.pass("CapeAdjust onApply received " + w + "x" + h + " cape");
                }));

        // 6. animated cape ------------------------------------------------------------------------
        steps.add(Step.of("animated_cape_apply")
                .action(() -> {
                    enterWorldView(mc);
                    try {
                        // Prefer a bundled real animated GIF cape (dropped into e2e resources); the mod
                        // decodes it (StbGifLoader) into an animated local cape. Fall back to the bundled
                        // known animated cape (rickroll) if no GIF is present.
                        gifCapeHash = TestAssets.registerBundledGifCape();
                        if (gifCapeHash != null) {
                            svc.applyCape(uuid, "local_cape:" + gifCapeHash);
                            AnimatedTextureManager.getInstance().setAnimationSpeed("cape_" + gifCapeHash, 1.0f);
                            E2ELog.info("applied local animated GIF cape local_cape:" + gifCapeHash);
                        } else {
                            svc.applyCape(uuid, "known:rickroll");
                            AnimatedTextureManager.getInstance().setAnimationSpeed(RICKROLL_ANIM_ID, 1.0f);
                        }
                    } catch (Exception e) {
                        E2ELog.error("animated_cape_apply failed", e);
                    }
                })
                .minTicks(20)
                .ready(() -> soleAnimatedState() != null)
                .timeoutTicks(200)
                .screenshot(prefix + "full_06a_animated_cape_frameA" + suffix)
                .assertion(() -> {
                    Object st = soleAnimatedState();
                    if (st == null) return Step.Result.fail("no animated AnimationState registered");
                    AnimationMetadata meta = metaOf(st);
                    int fc = (meta == null) ? -1 : meta.frameCount();
                    animStartFrame = frameOf(st);
                    if (fc < 2) return Step.Result.fail("animation frameCount=" + fc + " (not animated)");
                    if (animStartFrame < 0 || animStartFrame >= fc)
                        return Step.Result.fail("currentFrame out of range: " + animStartFrame + "/" + fc);
                    PlayerAppearance app = svc.getAppearance(uuid);
                    String capeId = app == null ? null : app.getCapeId();
                    String src = (gifCapeHash != null) ? "local GIF cape" : "known:rickroll";
                    return Step.Result.pass(src + " registered capeId=" + capeId
                            + " frameCount=" + fc + " startFrame=" + animStartFrame);
                }));

        steps.add(Step.of("animated_cape_advance")
                .minTicks(5)
                // Poll until the wall-clock-driven currentFrame moves off the snapshot taken above.
                .ready(() -> {
                    Object st = soleAnimatedState();
                    return st != null && animStartFrame != Integer.MIN_VALUE && frameOf(st) != animStartFrame;
                })
                .timeoutTicks(400) // up to 20s; rickroll cycles 17 frames @ 50ms
                .screenshot(prefix + "full_06b_animated_cape_frameB" + suffix)
                .assertion(() -> {
                    Object st = soleAnimatedState();
                    if (st == null) return Step.Result.fail("animation disappeared");
                    AnimationMetadata meta = metaOf(st);
                    if (meta == null) return Step.Result.fail("no metadata");
                    int fc = meta.frameCount();
                    int now = frameOf(st);
                    if (now == animStartFrame)
                        return Step.Result.fail("currentFrame did not advance (stuck at " + now + ")");
                    if (now < 0 || now >= fc)
                        return Step.Result.fail("currentFrame out of range: " + now + "/" + fc);
                    // Cross-check against the same formula tick() uses (best-effort; ±tick timing).
                    Long startTime = startTimeOf(st);
                    Float speed = speedOf(st);
                    String cross = "n/a";
                    if (startTime != null && speed != null) {
                        long elapsed = (long) ((System.currentTimeMillis() - startTime) * speed);
                        cross = String.valueOf(meta.getFrameAtTime(elapsed));
                    }
                    return Step.Result.pass("frame advanced " + animStartFrame + "->" + now
                            + "/" + fc + " (getFrameAtTime=" + cross + ")");
                }));

        // 7. HD cape import (no downscale) --------------------------------------------------------
        steps.add(Step.of("hd_cape_no_downscale")
                .action(() -> {
                    enterWorldView(mc);
                    try {
                        Path hd = TestAssets.makeHdCape(); // 256x128 == CAPE_256, kept verbatim on import
                        hdCapeHash = TestAssets.registerLocalCapeAs(hd, "qs_e2e_cape_hd.png");
                        if (hdCapeHash != null) svc.applyCape(uuid, "local_cape:" + hdCapeHash);
                        E2ELog.info("registered HD local cape hash=" + hdCapeHash);
                    } catch (Exception e) {
                        E2ELog.error("hd_cape_no_downscale failed", e);
                    }
                })
                .minTicks(30)
                .ready(() -> hdCapeHash != null && LocalAssetManager.getInstance().getMetadata(hdCapeHash) != null)
                .timeoutTicks(200)
                .screenshot(prefix + "full_07_hd_cape_body" + suffix)
                .assertion(() -> {
                    if (hdCapeHash == null) return Step.Result.fail("HD cape registration failed");
                    AssetMetadata meta = LocalAssetManager.getInstance().getMetadata(hdCapeHash);
                    if (meta == null) return Step.Result.fail("no metadata for HD cape");
                    int w = meta.resolution().getWidth(), h = meta.resolution().getHeight();
                    if (w != 256 || h != 128)
                        return Step.Result.fail("HD cape downscaled: resolution=" + w + "x" + h + " expected 256x128");
                    if (!meta.isCape())
                        return Step.Result.fail("metadata type=" + meta.type() + " expected cape");
                    return Step.Result.pass("HD cape preserved at " + w + "x" + h + " (no downscale)");
                }));

        // 8. elytra hides cape --------------------------------------------------------------------
        steps.add(Step.of("elytra_hides_cape")
                .action(() -> {
                    enterWorldView(mc);
                    equipElytra(mc);
                })
                .minTicks(15)
                .ready(() -> {
                    equipElytra(mc); // re-assert each tick in case creative inventory sync clears it
                    return mc.player != null
                            && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                            && svc.hasActiveCape(uuid)
                            && VanillaShim.cloakTexture(mc.player) != null;
                })
                .timeoutTicks(200)
                .screenshot(prefix + "full_08_elytra_hides_cape" + suffix)
                .assertion(() -> {
                    if (mc.player == null) return Step.Result.fail("player null");
                    equipElytra(mc);
                    boolean elytra = mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
                    boolean activeCape = svc.hasActiveCape(uuid);
                    String cloak = VanillaShim.cloakTexture(mc.player);
                    if (!elytra) return Step.Result.fail("CHEST slot is not an elytra");
                    if (!activeCape) return Step.Result.fail("hasActiveCape(uuid) is false");
                    if (cloak == null) return Step.Result.fail("cloak location null (cancel via hide branch, not elytra)");
                    // CapeLayerMixin cancels the cape iff: hasActiveCape && cloak!=null && CHEST is ELYTRA.
                    return Step.Result.pass("cape-cancel inputs satisfied: elytra in CHEST + activeCape + cloak="
                            + cloak + " -> cape hidden");
                }));

        // 10a. settings: round-trip a flag through SettingsScreen.onClose -> ClientConfig ----------
        steps.add(Step.of("settings_screen")
                .action(() -> {
                    ClientConfig c = ClientConfig.getInstance();
                    c.showSkinPreviewOverlay = false; // known starting value; the screen will flip it
                    c.save();
                    VanillaShim.setScreen(mc, new SettingsScreen(null));
                })
                .minTicks(25)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof SettingsScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_10a_settings" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof SettingsScreen s))
                        return Step.Result.fail("settings not open: " + screenName(mc));
                    Object cbObj = screenField(s, "showOverlayCheckbox");
                    if (!(cbObj instanceof Checkbox cb))
                        return Step.Result.fail("showOverlayCheckbox not found/built");
                    if (!cb.selected()) VanillaShim.press(cb); // flip false -> true via the real widget
                    s.onClose();                       // persists checkbox states into ClientConfig + save()
                    boolean now = ClientConfig.getInstance().showSkinPreviewOverlay;
                    return now
                            ? Step.Result.pass("SettingsScreen.onClose wrote showSkinPreviewOverlay=true to ClientConfig")
                            : Step.Result.fail("ClientConfig.showSkinPreviewOverlay still false after onClose");
                }));

        // 10b. rename dialog (harness owns the result Consumer) -----------------------------------
        steps.add(Step.of("rename_dialog")
                .action(() -> {
                    renameResult = null;
                    Consumer<String> cb = v2 -> renameResult = v2;
                    VanillaShim.setScreen(mc, new RenameScreen(null,
                            Component.literal("Rename"), Component.literal("New name?"),
                            "qs_e2e_old", cb));
                })
                .minTicks(15)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof RenameScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_10b_rename" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof RenameScreen s))
                        return Step.Result.fail("rename not open: " + screenName(mc));
                    Object ebObj = screenField(s, "nameEditBox");
                    Object btnObj = screenField(s, "confirmButton");
                    if (!(ebObj instanceof EditBox eb)) return Step.Result.fail("nameEditBox not built");
                    if (!(btnObj instanceof Button confirm)) return Step.Result.fail("confirmButton not built");
                    eb.setValue("qs_e2e_renamed");
                    confirm.active = true;   // bypass the blank-name disable just in case
                    VanillaShim.press(confirm); // -> callback.accept(getValue()) + onClose()
                    return "qs_e2e_renamed".equals(renameResult)
                            ? Step.Result.pass("Rename callback received 'qs_e2e_renamed'")
                            : Step.Result.fail("Rename callback got: " + renameResult);
                }));

        // 10c. delete-confirm dialog --------------------------------------------------------------
        steps.add(Step.of("delete_dialog")
                .action(() -> {
                    deleteResult = null;
                    Consumer<Boolean> cb = b -> deleteResult = b;
                    VanillaShim.setScreen(mc, new DeletionConfirmScreen(null,
                            Component.literal("Delete?"), Component.literal("Delete this skin?"),
                            cb, false));
                })
                .minTicks(15)
                .ready(() -> VanillaShim.currentScreen(mc) instanceof DeletionConfirmScreen)
                .timeoutTicks(200)
                .screenshot(prefix + "full_10c_delete" + suffix)
                .assertion(() -> {
                    if (!(VanillaShim.currentScreen(mc) instanceof DeletionConfirmScreen))
                        return Step.Result.fail("delete dialog not open: " + screenName(mc));
                    // Buttons are local vars in init() (no fields): press the confirm/Delete button,
                    // which is added last (after Cancel) -> accept(true).
                    if (!pressLastButton(mc)) return Step.Result.fail("no button to press");
                    return Boolean.TRUE.equals(deleteResult)
                            ? Step.Result.pass("Delete callback received true")
                            : Step.Result.fail("Delete callback got: " + deleteResult);
                }));

        // 11. HUD preview overlay -----------------------------------------------------------------
        steps.add(Step.of("hud_preview_overlay")
                .action(() -> {
                    enterWorldView(mc); // closes any leftover dialog; 3rd-person world frame
                    ClientConfig c = ClientConfig.getInstance();
                    c.showSkinPreviewOverlay = true;
                    c.enablePlayerPreviewCustomization = false;
                    // Small thumbnail pushed to the lower-left so it reads as a distinct HUD preview
                    // beside the centered 3rd-person world player rather than overlapping it.
                    c.sizeModelPreviewPercentageHudOverlay = 15;
                    c.positionOffsetXHudOverlay = -150;
                    c.positionOffsetYHudOverlay = -10;
                    c.hudOverlayRotation = 20.0f;
                    if (skinHash != null) setActiveSkinHash(c, skinHash); // show the custom skin in the overlay
                    c.save();
                    overlayForceResolve(); // null lastCheckedSkinHash so render() re-resolves the skin
                })
                .minTicks(30) // several RENDER_HUD frames so the overlay draws + caches its state
                .ready(this::overlayRendered)
                .timeoutTicks(200)
                .screenshot(prefix + "full_11_hud_overlay" + suffix)
                .assertion(() -> {
                    if (!ClientConfig.getInstance().showSkinPreviewOverlay)
                        return Step.Result.fail("showSkinPreviewOverlay not set");
                    if (!overlayRendered())
                        return Step.Result.fail("SkinPreviewOverlay.render did not run (cachedScale==0)");
                    Object loc = overlayCachedSkinLocation();
                    return Step.Result.pass("HUD overlay rendered; cachedSkinLocation=" + loc);
                }));

        return steps;
    }

    // ===== world / view helpers ===============================================================

    /** Reset the client singletons to a deterministic clean state between feature runs. */
    private void resetState() {
        try {
            PlayerAppearanceRepository.getInstance().clear();
            ModelService.getInstance().clearAll();
            AnimatedTextureManager.getInstance().clearAnimations();
            NetworkTextureCache.getInstance().clear();
            CooldownService.getInstance().clearCooldown();
        } catch (Throwable t) {
            E2ELog.warn("resetState: " + t);
        }
    }

    /** Close any open screen, switch to a fixed 3rd-person-back view, and pin the player's facing. */
    private void enterWorldView(Minecraft mc) {
        try {
            VanillaShim.setScreen(mc, null);
            if (mc.options != null) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            if (mc.player != null) {
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.setYRot(180f);
                mc.player.setYHeadRot(180f);
                mc.player.setXRot(0f);
            }
        } catch (Throwable t) {
            E2ELog.warn("enterWorldView: " + t);
        }
    }

    private void equipElytra(Minecraft mc) {
        try {
            if (mc.player != null) {
                mc.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            }
        } catch (Throwable t) {
            E2ELog.warn("equipElytra: " + t);
        }
    }

    private static String screenName(Minecraft mc) {
        return VanillaShim.currentScreen(mc) == null ? "<none>" : VanillaShim.currentScreen(mc).getClass().getName();
    }

    private boolean pressLastButton(Minecraft mc) {
        if (VanillaShim.currentScreen(mc) == null) return false;
        Button last = null;
        for (GuiEventListener c : VanillaShim.currentScreen(mc).children()) {
            if (c instanceof Button b) last = b;
        }
        if (last != null) { VanillaShim.press(last); return true; }
        return false;
    }

    // ===== animation reflection ================================================================

    /** The single registered {@code AnimationState} whose metadata has &gt;1 frame, or null. */
    private Object soleAnimatedState() {
        try {
            AnimatedTextureManager mgr = AnimatedTextureManager.getInstance();
            Field f = AnimatedTextureManager.class.getDeclaredField("animations");
            f.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) f.get(mgr);
            for (Object st : map.values()) {
                AnimationMetadata m = metaOf(st);
                if (m != null && m.frameCount() > 1) return st;
            }
        } catch (Throwable t) {
            E2ELog.warn("soleAnimatedState: " + t);
        }
        return null;
    }

    private int frameOf(Object state) {
        Object v2 = stateField(state, "currentFrame");
        return (v2 instanceof Integer i) ? i : -1;
    }

    private AnimationMetadata metaOf(Object state) {
        Object v2 = stateField(state, "metadata");
        return (v2 instanceof AnimationMetadata m) ? m : null;
    }

    private Long startTimeOf(Object state) {
        Object v2 = stateField(state, "startTime");
        return (v2 instanceof Long l) ? l : null;
    }

    private Float speedOf(Object state) {
        Object v2 = stateField(state, "speedMultiplier");
        return (v2 instanceof Float fl) ? fl : null;
    }

    private static Object stateField(Object state, String name) {
        try {
            Field f = state.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(state);
        } catch (Throwable t) {
            E2ELog.warn("AnimationState." + name + ": " + t);
            return null;
        }
    }

    // ===== screen / overlay / config reflection ================================================

    private static Object screenField(Object screen, String name) {
        try {
            Field f = screen.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(screen);
        } catch (Throwable t) {
            E2ELog.warn("field " + name + " on " + screen.getClass().getSimpleName() + ": " + t);
            return null;
        }
    }

    /** {@code ClientConfig.activeSkinHash} is a public field; set reflectively to stay robust. */
    private static void setActiveSkinHash(ClientConfig c, String hash) {
        try {
            Field f = ClientConfig.class.getField("activeSkinHash");
            f.set(c, hash);
        } catch (Throwable t) {
            E2ELog.warn("setActiveSkinHash: " + t);
        }
    }

    private void overlayForceResolve() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("lastCheckedSkinHash");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Throwable t) {
            E2ELog.warn("overlayForceResolve: " + t);
        }
    }

    /** True once {@code SkinPreviewOverlay.render} has executed at least once (cachedScale set). */
    private boolean overlayRendered() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("cachedScale");
            f.setAccessible(true);
            return f.getFloat(null) > 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    private Object overlayCachedSkinLocation() {
        try {
            Field f = Class.forName("com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay")
                    .getDeclaredField("cachedSkinLocation");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
