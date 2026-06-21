package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 0 spike scenario: prove the whole pipeline end to end.
 * <ol>
 *   <li>baseline — confirm we joined the world; capture a screenshot.</li>
 *   <li>apply_local_skin — generate a skin PNG, import it via the real {@code SkinImporter}, apply
 *       it through {@code PlayerAppearanceService}, capture a screenshot, and assert the appearance
 *       state (skinId + resolved ResourceLocation).</li>
 * </ol>
 */
public final class Phase0Smoke implements Scenario {

    private volatile String skinHash;

    @Override
    public String id() { return "phase0-smoke"; }

    @Override
    public List<Step> build(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService appearance = PlayerAppearanceService.getInstance();

        List<Step> steps = new ArrayList<>();

        steps.add(Step.of("baseline")
                .minTicks(40) // ~2s render warmup so the first frame is real
                .screenshot(v + "_01_baseline_" + role + ".png")
                .assertion(() -> mc.player != null
                        ? Step.Result.pass("player present: " + VanillaShim.playerName(mc.player))
                        : Step.Result.fail("player is null")));

        steps.add(Step.of("apply_local_skin")
                .action(() -> {
                    try {
                        Path file = TestAssets.makeClassicSkin();
                        AssetMetadata meta = SkinImporter.importSkin(file);
                        if (meta == null) {
                            E2ELog.warn("SkinImporter.importSkin returned null");
                            return;
                        }
                        skinHash = meta.hash();
                        appearance.applySkin(uuid, "local_skin:" + skinHash, "auto");
                        E2ELog.info("applied local_skin:" + skinHash);
                    } catch (Exception e) {
                        E2ELog.error("apply_local_skin action failed", e);
                    }
                })
                .minTicks(40)
                .ready(() -> skinHash != null && appearance.getAppearance(uuid) != null)
                .screenshot(v + "_02_local_skin_" + role + ".png")
                .assertion(() -> {
                    if (skinHash == null) return Step.Result.fail("skin import failed (no hash)");
                    PlayerAppearance app = appearance.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no appearance for local player");
                    String expected = "local_skin:" + skinHash;
                    if (!expected.equals(app.getSkinId())) {
                        return Step.Result.fail("skinId=" + app.getSkinId() + " expected " + expected);
                    }
                    if (appearance.getSkinLocation(uuid) == null) {
                        return Step.Result.fail("skin ResourceLocation did not resolve");
                    }
                    return Step.Result.pass("skinId=" + app.getSkinId() + " location resolved");
                }));

        return steps;
    }
}
