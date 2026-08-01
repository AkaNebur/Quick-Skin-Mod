#!/usr/bin/env python3
"""
Build a manifest of E2E screenshots paired with what each one SHOULD show, for an AI vision pass.

The programmatic assertions verify mod STATE (ids, resolved ResourceLocations, cached bytes) but never
look at the rendered pixels. This manifest feeds an AI reviewer that opens each screenshot and checks
the actual image against an expectation — catching render bugs state checks can't: wrong/garbled
textures, a cape clipping through an elytra, transparency artifacts, a black/empty frame, an animated
frame that didn't actually advance, etc.

Usage:
  python3 e2e/visual_review.py                 # key (visually-rich) kinds, all combos -> JSON manifest
  python3 e2e/visual_review.py --all           # every screenshot
  python3 e2e/visual_review.py --combos 1.21.1/fabric,1.21.1/neoforge
The JSON ([{path,label,kind,expectation}]) is printed to stdout; an orchestrating agent passes it to
the AI review workflow.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT_ROOT = REPO / "e2e-out"

# Per screenshot "kind" (filename with the v<ver>_ prefix and _client_<role> suffix stripped), what the
# rendered frame should show.
#
# The harness now applies REAL bundled assets (see TestAssets). The player SKIN is a realistic
# "Steve-with-a-jacket": BROWN hair, a dark NAVY-BLUE plaid/checkered jacket over the torso+arms, and
# BLUE trouser legs (NOT a flat magenta skin, and NOT the default vanilla skin). The ANIMATED cape is
# decoded from a 16-frame GIF and renders predominantly WHITE/light with a RED circular motif that
# moves between frames (the GIF is square, so the design looks cropped to the 2:1 cape shape). The
# remaining capes are still synthetic, distinctly-colored landmarks: the local/propagation cape is
# deep BLUE with an ORANGE/GREEN front; the HD cape is a TEAL base with a MAGENTA front; the bundled
# "known:test" cape is ORANGE/GOLD. Most in-world THIRD-person shots also show a small duplicate
# player-preview thumbnail in a lower corner — that is the mod's HUD preview overlay and is EXPECTED.
# The two SUBJECT "applied" shots (propagation_applied, live_03_applied) are FIRST-person: the custom
# look shows only in that small lower-left preview thumbnail, not on a main body.
EXPECTATIONS: dict[str, str] = {
    "baseline": "In-world view of a flat grassy superflat world under a clear daytime sky, DEFAULT (non-custom) vanilla appearance, no menu open. Usually FIRST-PERSON (centered crosshair + the player's own held item in a lower corner); a small player-preview thumbnail showing a DEFAULT skin may appear in a lower corner — left or right, varies by version (HUD overlay — expected). The key is a normal default world view with NO custom skin/cape and no GUI.",
    "full_01_baseline": "Third-person player viewed from BEHIND in a flat grassy daytime superflat world; state was just reset so the skin should look like a DEFAULT vanilla skin (reddish/teal tones), NOT the custom navy-plaid-jacket skin. No GUI open. A small duplicate player thumbnail may appear in a lower corner (the mod's HUD preview overlay) — that is expected.",
    "live_00_baseline": "First-person observer baseline in a flat grassy daytime world (observer just joined); default vanilla appearance, no menu. The observer's own held item may be visible in a lower corner.",
    "local_skin": "The SUBJECT's own client just after applying a local skin (phase0 smoke). FIRST-PERSON flat-world view (centered crosshair + the player's own held item in the lower-right); the player's own body is NOT shown, but a SMALL HUD player-preview thumbnail in a LOWER corner (left or right — varies by version/config) shows the custom skin (BROWN hair, dark NAVY-BLUE plaid jacket, BLUE legs) — clearly not the default vanilla skin. PASS if it is a normal first-person world view with that small custom-skin preview thumbnail (no garbled/black frame).",
    "full_02a_local_skin_body": "Third-person view from BEHIND the main centered player wearing the CUSTOM skin: BROWN hair/head, a dark NAVY-BLUE plaid/checkered jacket over the torso and arms, and BLUE/indigo trouser legs. This realistic custom skin (NOT the default vanilla skin, NOT a flat magenta block, NOT a black/empty frame) confirms the texture loaded. Flat grass, daytime. A small duplicate player thumbnail may appear in a lower corner (HUD preview) — expected. PASS as long as the central player clearly wears this custom navy-plaid skin.",
    "propagation_applied": "The SUBJECT's own client just after applying its look. This is a FIRST-PERSON flat-world view (centered crosshair + the player's own held item in the lower-right); the subject's own body is NOT shown, but a SMALL HUD player-preview thumbnail in a LOWER corner (left or right — varies by version/config) displays the custom navy-plaid skin. PASS if it is a normal first-person world view with that small custom-skin preview thumbnail (no garbled/black frame).",
    "full_02b_skin_menu": "A Quick Skin in-game GUI (the player skin menu) — a custom mod menu with panels/buttons, NOT the in-world view.",
    "full_03a_model_slim": "Third-person main player (from behind) with the CUSTOM navy-plaid-jacket skin (brown hair, blue legs) using the SLIM (Alex, 3px) arm model — arms slightly thinner. A small corner HUD preview thumbnail may also be present (expected).",
    "full_03b_model_classic": "Third-person main player (from behind) with the CUSTOM navy-plaid-jacket skin (brown hair, blue legs) using the CLASSIC (Steve, 4px) wider arm model. A small corner HUD preview thumbnail may also be present (expected).",
    "full_04a_cape_menu": "A Quick Skin in-game GUI (the cape menu) — custom mod menu, NOT in-world.",
    "full_04b_known_cape_body": "Third-person main player viewed from BEHIND wearing the bundled 'known:test' CAPE hanging flat on its back; that cape is predominantly ORANGE/GOLD. The player wears the custom navy-plaid skin underneath. A small corner HUD preview may also be present (expected).",
    "full_05_cape_adjust": "A Quick Skin 'cape adjust' GUI showing a cape image/grid editor (the edited cape is deep blue with an orange/green front), NOT in-world.",
    "full_06a_animated_cape_frameA": "Third-person main player from behind wearing an ANIMATED cape decoded from a GIF: predominantly WHITE/light with a RED circular motif (one animation frame; the square GIF looks cropped to the cape shape). It must be a real rendered cape, not a black/blank or garbled surface.",
    "full_06b_animated_cape_frameB": "Third-person main player from behind wearing the SAME animated GIF cape on a LATER frame — predominantly WHITE/light with RED marking(s) whose position/shape DIFFERS from frame A (the red has visibly moved/changed), demonstrating the animation advanced.",
    "full_07_hd_cape_body": "Third-person main player from BEHIND wearing an HD cape whose outward-facing surface is a bright MAGENTA/pink front panel with TEAL as the base/trim around the edges (a faint yellow stripe may show). PASS if a cape with these custom magenta+teal colors renders. A small corner HUD preview may also be present (expected).",
    "full_08_elytra_hides_cape": "Third-person main player from BEHIND wearing an ELYTRA — a pair of angled WINGS (here tinted teal, since the elytra adopts the cape texture). CRITICAL: on the MAIN centered player there must be NO flat rectangular cape cloth hanging straight down — the elytra wings replace it. (A small corner preview thumbnail may still show a flat cape; ignore it — only the main centered player matters.) PASS if the main player shows angled elytra wings instead of a flat hanging cape.",
    "full_10a_settings": "A Quick Skin settings GUI with toggles/checkboxes, NOT in-world.",
    "full_10b_rename": "A small rename dialog GUI with a text input field and a confirm button.",
    "full_10c_delete": "A small delete-confirmation dialog GUI with cancel/delete buttons.",
    "full_11_hud_overlay": "In-world third-person view (the main player may still be wearing the elytra/cape) WITH a SMALL player-preview thumbnail rendered in a LOWER corner (left or right — varies by version/config) as a HUD overlay, distinct from the main centered player. PASS if a small extra preview thumbnail is present in a bottom corner.",
    "full_12_title_splash_order": "The vanilla MINECRAFT TITLE SCREEN (logo, Singleplayer/Multiplayer buttons, rotating panorama) with the mod's 3D player preview enlarged and moved on top of the yellow rotating SPLASH text. CRITICAL: no yellow splash lettering may cross the model's body - the splash must be cut off where the player model begins, with the model in front. PASS only if the model occludes the splash rather than the splash running over the model.",
    "live_01_before": "First-person OBSERVER view (the observer's own hand/held item is visible in the lower-right) looking at ANOTHER player (nametag 'Alice') who has a DEFAULT/vanilla skin (reddish/brown tones) and NO cape — the 'before' of a live change. Alice must look plain/default here, NOT the navy-plaid custom skin.",
    "live_02_after": "First-person OBSERVER view (observer's held item visible lower-right) of the SAME other player 'Alice', now wearing the CUSTOM skin (brown hair, dark navy-blue plaid jacket, blue legs) AND a cape whose outward face shows ORANGE/GREEN (its base is deep blue), seen from a 3/4-rear vantage — the 'after' of a live change. It must be clearly DIFFERENT from the plain default 'before': a custom-skinned, caped player.",
    "live_03_applied": "The SUBJECT 'Alice' own client just after the live apply: a FIRST-PERSON flat-world view (crosshair + own held item lower-right) with a SMALL HUD player-preview thumbnail in a LOWER corner (left or right — varies by version/config) showing the custom navy-plaid skin. PASS if it is a normal first-person world view with that small custom-skin preview thumbnail.",
    "propagation_observe": "First-person OBSERVER view (observer's held item visible lower-right) of another player 'Alice' wearing the CUSTOM navy-plaid skin (brown hair, blue legs) and a cape whose outward face shows ORANGE/GREEN (deep-blue base), rendered on the observer's own client from a 3/4-rear vantage. Alice must clearly be custom-skinned and caped, not default.",
}

# Visually-rich kinds worth an AI pass by default (skip plain menus/baselines unless --all).
KEY_KINDS = [
    "full_02a_local_skin_body", "full_03a_model_slim", "full_03b_model_classic",
    "full_04b_known_cape_body", "full_06a_animated_cape_frameA", "full_06b_animated_cape_frameB",
    "full_07_hd_cape_body", "full_08_elytra_hides_cape", "full_11_hud_overlay",
    "full_12_title_splash_order",
    "live_01_before", "live_02_after", "propagation_observe",
]

_PREFIX = re.compile(r"^v?[0-9]+(?:[._][0-9]+)*_")
_ORDINAL = re.compile(r"^[0-9]+[a-z]?_")
_SUFFIX = re.compile(r"_client_[ab]\.png$")


def kind_of(png: Path) -> str:
    kind = _SUFFIX.sub("", _PREFIX.sub("", png.name))
    # Smoke/propagation names begin with a capture ordinal (01_, 02_, 03_, ...), while full_* and
    # live_* include their ordinal in the expectation key. Normalize only the leading bare ordinal.
    return _ORDINAL.sub("", kind)


def screenshot_rows() -> list[tuple[Path, str, str, str]]:
    """Return only harness screenshots, excluding texture/cache PNGs inside E2E profiles."""

    matrix_path = REPO / "release" / "release-matrix.json"
    matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
    runtime_rows = matrix["runtimes"]
    screenshots: list[tuple[Path, str, str, str]] = []
    profiles_root = OUT_ROOT / "profiles"
    if profiles_root.is_dir():
        for profile in sorted(path for path in profiles_root.iterdir() if path.is_dir()):
            identity = next(
                (
                    row
                    for row in runtime_rows
                    if profile.name.startswith(
                        f"{row['artifact_node']}--{row['runtime_version']}--"
                    )
                ),
                None,
            )
            if identity is None:
                continue
            for client in sorted(profile.glob("client_[ab]")):
                for png in sorted((client / "screenshots").glob("*.png")):
                    screenshots.append(
                        (
                            png,
                            identity["runtime_version"],
                            identity["loader"],
                            client.name.removeprefix("client_"),
                        )
                    )
    return screenshots


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="include every screenshot, not just key kinds")
    ap.add_argument(
        "--combos",
        help="comma-separated <version>/<loader> filter (e.g. 1.21.1/fabric,1.21.1/neoforge)",
    )
    args = ap.parse_args()

    combo_filter = set(args.combos.split(",")) if args.combos else None
    manifest = []
    for png, version, loader, role in screenshot_rows():
        if combo_filter and f"{version}/{loader}" not in combo_filter:
            continue
        kind = kind_of(png)
        if not args.all and kind not in KEY_KINDS:
            continue
        expectation = EXPECTATIONS.get(kind, "(no expectation defined — describe what is shown)")
        manifest.append({
            "path": str(png),
            "label": f"{version}/{loader}/{role}:{kind}",
            "kind": kind,
            "expectation": expectation,
        })

    json.dump(manifest, sys.stdout, indent=2)
    print(f"\n# {len(manifest)} screenshots", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
