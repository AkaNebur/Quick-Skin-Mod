package com.quickskin.mod.e2e.scenario;

import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.NetworkTextureCache;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.e2e.E2ELog;
import com.quickskin.mod.e2e.Scenario;
import com.quickskin.mod.e2e.Step;
import com.quickskin.mod.e2e.TestAssets;
import com.quickskin.mod.e2e.VanillaShim;
import com.quickskin.mod.networking.NetworkSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1+ scenario ({@code -Dquickskin.e2e.scenario=propagation-live}): the strict <b>live</b>
 * propagation test — both players already in-world, the observer watching, and the subject changes
 * its skin+cape <em>while being watched</em>, with the observer asserting it witnesses the
 * <b>transition</b> (default &rarr; custom), not merely a back-fill on join.
 *
 * <p>The two clients run in separate JVMs with no IPC; they self-coordinate through <b>in-world
 * entity presence</b> and the server's appearance relay — timing is by ticks/conditions, never
 * wall-clock sleeps:</p>
 *
 * <h3>Subject (A)</h3>
 * <ol>
 *   <li><b>baseline</b> — joined, default skin.</li>
 *   <li><b>await_observer</b> — wait until B's entity has loaded in A's world (B has joined).</li>
 *   <li><b>settle_before_change</b> — a tick-gated margin so B has captured its clean "before"
 *       frame (B still sees A as default) before A mutates. If this margin is ever too short, B's
 *       "before" assertion fails loudly rather than passing wrongly.</li>
 *   <li><b>apply_live</b> — import a local skin + register a local cape and {@code applyLook} them.
 *       Applying to the local UUID triggers {@code NetworkSyncService.syncAppearance}, uploading the
 *       bytes+metadata; the server broadcasts to confirmed observers (B). A then idles connected.</li>
 * </ol>
 *
 * <h3>Observer (B)</h3>
 * <ol>
 *   <li><b>baseline</b>.</li>
 *   <li><b>confirm_self</b> — one C2S ({@code syncAppearance(B,"","","classic")}) so the server marks
 *       B confirmed and will relay A's <em>live</em> update to it.</li>
 *   <li><b>observe_before</b> — A present and rendering a <b>non-custom</b> skin (no
 *       {@code quickskin:network/} location yet). Screenshot "before". Records that a clean
 *       pre-change state was seen.</li>
 *   <li><b>await_live_change</b> — walk to a 3/4-rear vantage while polling until A's
 *       {@link AbstractClientPlayer} skin <em>and</em> cape resolve to {@code quickskin:network/<hash>}
 *       with the bytes cached (the render-truthful check). Screenshot "after"; the assertion requires
 *       both a captured "before" and the now-custom render — i.e. a witnessed live transition.</li>
 * </ol>
 */
public final class PropagationLiveScenario implements Scenario {

    /** Set by A's apply action; read by A's ready/assert. */
    private volatile String skinHash;
    private volatile String capeHash;

    /** B's cached observation vantage (computed once from A's pose) + walk/settle bookkeeping. */
    private double tgtX, tgtY, tgtZ;
    private boolean vantageSet = false;
    private int settleTicks = 0;

    /** B latches that it observed a clean pre-change ("before") state, so "after" proves a transition. */
    private volatile boolean sawBefore = false;

    /** A's tracking of B's position, to detect when B has reached its vantage and gone still. */
    private double bPrevX = Double.NaN, bPrevZ;
    private int bStillTicks = 0;

    @Override
    public String id() { return "propagation-live"; }

    @Override
    public List<Step> build(Minecraft mc) {
        String role = System.getProperty("quickskin.e2e.role", "client_a");
        return "client_b".equals(role) ? buildObserver(mc) : buildSubject(mc);
    }

    // ===== A: subject (changes LIVE once B is present and has captured "before") ===============
    private List<Step> buildSubject(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_a");
        final UUID uuid = mc.player.getUUID();
        final PlayerAppearanceService appearance = PlayerAppearanceService.getInstance();

        List<Step> steps = new ArrayList<>();
        steps.add(baseline(mc, v, role));

        // 1. Wait until B has joined, walked to its observation vantage, and gone still (so B has
        //    framed + captured its clean "before" frame). Tying the apply to B being stationary —
        //    rather than a fixed delay — keeps the default->custom ordering robust; if it ever races,
        //    B's "before" check fails loudly instead of passing wrongly.
        steps.add(Step.of("await_observer_settled")
                .minTicks(40)
                .ready(() -> {
                    AbstractClientPlayer b = findOther(mc);
                    if (b == null || mc.player == null) { bStillTicks = 0; return false; }
                    double x = b.getX(), z = b.getZ();
                    if (!Double.isNaN(bPrevX) && Math.hypot(x - bPrevX, z - bPrevZ) < 0.03) bStillTicks++;
                    else bStillTicks = 0;
                    bPrevX = x; bPrevZ = z;
                    // Gate the apply on B having ARRIVED at its ~5-block vantage (distance) AND held
                    // still ~3s — never on a blind timer (a fixed-tick fallback raced B's slower
                    // arrival on heavier clients). The distance gate means we never fire while B is
                    // still en route; 3s of A-ticks comfortably exceeds B's ~12-tick "before"-capture
                    // settle even if B's tick rate dipped while loading. The honest "before" check
                    // still fails loudly if the order ever slips, rather than passing wrongly.
                    double dist = Math.hypot(b.getX() - mc.player.getX(), b.getZ() - mc.player.getZ());
                    return dist < 7.0 && bStillTicks >= 60;
                })
                .timeoutTicks(20 * 150) // up to 150s for B to launch, join, walk into position
                .assertion(() -> {
                    AbstractClientPlayer b = findOther(mc);
                    return b != null
                            ? Step.Result.pass("observer settled at vantage: " + VanillaShim.playerName(b))
                            : Step.Result.fail("observer B never appeared/settled");
                }));

        // 2. THE LIVE CHANGE — both players in-world, B watching from its vantage; A swaps skin+cape now.
        steps.add(Step.of("apply_live")
                .action(() -> {
                    try {
                        Path skinFile = TestAssets.makeClassicSkin();
                        AssetMetadata skinMeta = SkinImporter.importSkin(skinFile);
                        if (skinMeta == null) { E2ELog.warn("SkinImporter.importSkin returned null"); return; }
                        skinHash = skinMeta.hash();

                        Path capeFile = TestAssets.makeClassicCape();
                        String ch = TestAssets.registerLocalCape(capeFile);
                        if (ch == null) { E2ELog.warn("registerLocalCape returned null"); return; }
                        capeHash = ch;

                        appearance.applyLook(uuid, "local_skin:" + skinHash, "local_cape:" + capeHash, "auto");
                        E2ELog.info("A applied LIVE (B watching) local_skin:" + skinHash + " local_cape:" + capeHash);
                    } catch (Exception e) {
                        E2ELog.error("apply_live action failed", e);
                    }
                })
                .minTicks(40)
                .ready(() -> skinHash != null && capeHash != null
                        && appearance.getSkinLocation(uuid) != null
                        && appearance.getCapeLocation(uuid) != null)
                .timeoutTicks(400)
                .screenshot(v + "_live_03_applied_" + role + ".png")
                .assertion(() -> {
                    if (skinHash == null) return Step.Result.fail("skin import failed (no hash)");
                    if (capeHash == null) return Step.Result.fail("cape register failed (no hash)");
                    PlayerAppearance app = appearance.getAppearance(uuid);
                    if (app == null) return Step.Result.fail("no local appearance");
                    return Step.Result.pass("A applied LIVE skin=local_skin:" + skinHash
                            + " cape=local_cape:" + capeHash);
                }));

        // A idles in DONE (harness never quits the client), staying connected so B finishes observing.
        return steps;
    }

    // ===== B: observer (witnesses the transition default -> custom) ===========================
    private List<Step> buildObserver(Minecraft mc) {
        final String v = System.getProperty("quickskin.e2e.version", "v1_20_1");
        final String role = System.getProperty("quickskin.e2e.role", "client_b");
        final UUID me = mc.player.getUUID();

        List<Step> steps = new ArrayList<>();
        steps.add(baseline(mc, v, role));

        // 1. Speak first so the server marks B confirmed and relays A's live update to B.
        steps.add(Step.of("confirm_self")
                .action(() -> {
                    try {
                        NetworkSyncService.getInstance().syncAppearance(me, "", "", "classic");
                        E2ELog.info("B sent confirm C2S (empty appearance)");
                    } catch (Throwable t) {
                        E2ELog.error("confirm_self failed", t);
                    }
                })
                .minTicks(10)
                .ready(() -> mc.getConnection() != null)
                .timeoutTicks(200)
                .assertion(() -> mc.getConnection() != null
                        ? Step.Result.pass("connected; sent confirm C2S")
                        : Step.Result.fail("no server connection")));

        // 2. Walk to a 3/4-rear vantage of A and capture a clean BEFORE: A framed, still rendering a
        //    NON-custom skin (no network loc). B then holds position; A (watching B go still) applies.
        steps.add(Step.of("observe_before")
                .action(() -> stepTowardVantage(mc))
                .minTicks(2) // walk continuously (poll every tick) so A never sees a false "still" mid-approach
                .ready(() -> {
                    stepTowardVantage(mc);
                    if (!vantageSet || mc.player == null || findOther(mc) == null) return false;
                    boolean atVantage = Math.hypot(mc.player.getX() - tgtX, mc.player.getZ() - tgtZ) < 0.4;
                    return atVantage && ++settleTicks >= 12; // settle so the "before" frame is stable + framed
                })
                .timeoutTicks(20 * 60)
                .screenshot(v + "_live_01_before_" + role + ".png")
                .assertion(() -> {
                    AbstractClientPlayer a = findOther(mc);
                    if (a == null) return Step.Result.fail("A's entity not present yet");
                    String skin = VanillaShim.skinTexture(a);
                    String cloak = VanillaShim.cloakTexture(a);
                    boolean customSkin = skin != null && skin.startsWith("quickskin:network/");
                    boolean customCape = cloak != null && cloak.startsWith("quickskin:network/");
                    if (customSkin || customCape)
                        return Step.Result.fail("A already custom BEFORE the live change (skin=" + skin
                                + " cape=" + cloak + ") — ordering race, raise A's still-threshold");
                    sawBefore = true;
                    return Step.Result.pass("BEFORE: A(" + VanillaShim.playerName(a)
                            + ") framed at vantage, non-custom skin=" + skin + " cape=" + cloak);
                }));

        // 3. Hold the vantage and await the LIVE change; capture AFTER from the SAME camera and assert
        //    the witnessed transition (before non-custom -> after render-truthful custom).
        steps.add(Step.of("await_live_change")
                .action(() -> stepTowardVantage(mc))
                .minTicks(5)
                .ready(() -> {
                    stepTowardVantage(mc); // keep position + aim steady on A
                    return checkPropagation(mc).pass(); // the live change must have landed
                })
                .timeoutTicks(20 * 90) // up to 90s: B watching, waiting for A to apply + relay
                .screenshot(v + "_live_02_after_" + role + ".png")
                .assertion(() -> {
                    if (!sawBefore)
                        return Step.Result.fail("no clean 'before' state was captured — cannot prove a live transition");
                    logObserveGeometry(mc);
                    Step.Result r = checkPropagation(mc);
                    if (!r.pass()) return r;
                    return Step.Result.pass("LIVE transition witnessed (before: non-custom -> after: "
                            + r.message() + ")");
                }));

        return steps;
    }

    // ===== shared =============================================================================
    private Step baseline(Minecraft mc, String v, String role) {
        return Step.of("baseline")
                .minTicks(40) // ~2s render warmup so the first frame is real
                .screenshot(v + "_live_00_baseline_" + role + ".png")
                .assertion(() -> mc.player != null
                        ? Step.Result.pass("player present: " + VanillaShim.playerName(mc.player))
                        : Step.Result.fail("player is null"));
    }

    /**
     * The full A->B render-truthful check (also used as B's live-change ready predicate): A's entity
     * present, its appearance received with network ids, its texture bytes cached on B, and A's
     * {@link AbstractClientPlayer} skin/cape locations resolving to {@code quickskin:network/<hash>}.
     */
    private Step.Result checkPropagation(Minecraft mc) {
        if (mc.player == null || mc.level == null) return Step.Result.fail("not in world");
        AbstractClientPlayer a = findOther(mc);
        if (a == null) return Step.Result.fail("A's entity not present in B's world");
        UUID aUuid = a.getUUID();

        PlayerAppearance app = PlayerAppearanceRepository.getInstance().getAppearance(aUuid);
        if (app == null) return Step.Result.fail("no appearance received for A yet (" + aUuid + ")");

        String skinId = app.getSkinId();
        String capeId = app.getCapeId();
        if (skinId == null || !skinId.startsWith("local_skin:"))
            return Step.Result.fail("A skinId not a network skin yet: " + skinId);
        if (capeId == null || !capeId.startsWith("local_cape:"))
            return Step.Result.fail("A capeId not a network cape yet: " + capeId);
        String skinHash = skinId.substring("local_skin:".length());
        String capeHash = capeId.substring("local_cape:".length());

        NetworkTextureCache cache = NetworkTextureCache.getInstance();
        if (!cache.hasTexture(skinHash)) return Step.Result.fail("skin bytes not cached on B yet: " + skinHash);
        if (!cache.hasTexture(capeHash)) return Step.Result.fail("cape bytes not cached on B yet: " + capeHash);

        String skinLoc = VanillaShim.skinTexture(a);
        String cloakLoc = VanillaShim.cloakTexture(a);
        String expectedSkin = "quickskin:network/" + skinHash;
        String expectedCape = "quickskin:network/" + capeHash;
        if (skinLoc == null || !expectedSkin.equals(skinLoc))
            return Step.Result.fail("render skin=" + skinLoc + " expected " + expectedSkin);
        if (cloakLoc == null || !expectedCape.equals(cloakLoc))
            return Step.Result.fail("render cape=" + cloakLoc + " expected " + expectedCape);

        return Step.Result.pass("A(" + VanillaShim.playerName(a) + ") observed: skin=" + expectedSkin
                + " cape=" + expectedCape + "; bytes cached + render-truthful");
    }

    /** The single other player in B's (or A's) world; null if not yet loaded. */
    private static AbstractClientPlayer findOther(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        UUID me = mc.player.getUUID();
        for (Player p : mc.level.players()) {
            if (p instanceof AbstractClientPlayer acp && !acp.getUUID().equals(me)) {
                return acp;
            }
        }
        return null;
    }

    /**
     * Walk B toward a 3/4-rear vantage of A at walking speed (small per-tick deltas the server accepts),
     * aiming the camera at A's torso each tick, so the "after" frame shows A's full body. The
     * programmatic assertion does not depend on framing.
     */
    private void stepTowardVantage(Minecraft mc) {
        try {
            AbstractClientPlayer a = findOther(mc);
            if (a == null || mc.player == null) return;

            if (!vantageSet) {
                double rad = Math.toRadians(a.getYRot());
                double fx = -Math.sin(rad), fz = Math.cos(rad);
                final double dist = 5.0, side = 1.5;
                tgtX = a.getX() - fx * dist + fz * side;
                tgtY = a.getY();
                tgtZ = a.getZ() - fz * dist - fx * side;
                vantageSet = true;
            }

            double cx = mc.player.getX(), cz = mc.player.getZ();
            double dx = tgtX - cx, dz = tgtZ - cz;
            double d = Math.sqrt(dx * dx + dz * dz);
            final double step = 0.25;
            double nx = (d > step) ? cx + dx / d * step : tgtX;
            double nz = (d > step) ? cz + dz / d * step : tgtZ;

            mc.player.setDeltaMovement(0, 0, 0);
            mc.player.setPos(nx, tgtY, nz);

            double ax = a.getX() - nx, az = a.getZ() - nz;
            double ah = Math.sqrt(ax * ax + az * az);
            double ayTorso = (a.getY() + 1.0) - (tgtY + mc.player.getEyeHeight());
            float yaw = (float) Math.toDegrees(Math.atan2(-ax, az));
            float pitch = (float) (-Math.toDegrees(Math.atan2(ayTorso, ah < 0.01 ? 0.01 : ah)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.setYHeadRot(yaw);
        } catch (Throwable ignored) {
        }
    }

    /** Rich one-line diagnostic of the observation geometry + resolved textures (greppable). */
    private void logObserveGeometry(Minecraft mc) {
        try {
            AbstractClientPlayer a = findOther(mc);
            if (a == null || mc.player == null) return;
            double rad = Math.toRadians(mc.player.getYRot());
            double lx = -Math.sin(rad), lz = Math.cos(rad);
            double ax = a.getX() - mc.player.getX(), az = a.getZ() - mc.player.getZ();
            double ah = Math.sqrt(ax * ax + az * az);
            double faceCos = ah < 1e-6 ? 0 : (lx * ax + lz * az) / ah;
            E2ELog.info(String.format(
                    "observe(live): Bpos=(%.1f,%.1f,%.1f) Apos=(%.1f,%.1f,%.1f) dist=%.2f faceCos=%.2f aAlive=%b aInvis=%b Bskin=%s Askin=%s Acloak=%s",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    a.getX(), a.getY(), a.getZ(), mc.player.distanceTo(a), faceCos,
                    a.isAlive(), a.isInvisible(),
                    VanillaShim.skinTextureStr(mc.player),
                    VanillaShim.skinTextureStr(a),
                    VanillaShim.cloakTextureStr(a)));
        } catch (Throwable ignored) {
        }
    }
}
