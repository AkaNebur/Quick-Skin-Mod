from __future__ import annotations

import math
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402


class PackagedRuntimeVisualContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_skin_menu(self, name: str, *, washed_out: bool) -> Path:
        width, height = 640, 360
        if washed_out:
            pixels = []
            for y in range(height):
                normalized_y = (y - height / 2) / (height / 2)
                for x in range(width):
                    normalized_x = (x - width / 2) / (width / 2)
                    distance = math.sqrt(normalized_x**2 + normalized_y**2)
                    luma = min(150, int(12 + 115 * distance))
                    pixels.append((luma, luma, luma))
            image = Image.new("RGB", (width, height))
            image.putdata(pixels)
        else:
            image = Image.new("RGB", (width, height), (0, 0, 0))
            draw = ImageDraw.Draw(image)
            for y in range(15, height, 30):
                for x in range(10, width, 32):
                    draw.rectangle((x, y, x + 3, y + 3), fill=(12, 12, 12))

        # A representative dark menu panel with several substantial control colours keeps the
        # fixture non-blank without touching the left-side background region under test.
        draw = ImageDraw.Draw(image)
        draw.rectangle((160, 35, 480, 325), fill=(5, 5, 5), outline=(105, 105, 105), width=2)
        draw.rectangle((174, 70, 350, 94), fill=(25, 25, 25), outline=(150, 150, 150))
        draw.rectangle((174, 104, 350, 130), fill=(95, 8, 130), outline=(190, 70, 225))
        draw.rectangle((180, 170, 345, 250), fill=(28, 28, 28), outline=(220, 220, 220))
        draw.rectangle((365, 145, 445, 270), fill=(35, 65, 115))
        draw.rectangle((174, 285, 460, 310), fill=(85, 85, 85), outline=(185, 185, 185))
        path = self.root / name
        image.save(path, format="PNG")
        return path

    def test_dark_starred_skin_menu_passes_semantic_pixel_contract(self) -> None:
        path = self.write_skin_menu("dark-stars.png", washed_out=False)

        metrics = packaged_runtime.inspect_screenshot_for_step(
            path, "full", "client_a", "skin_menu_screen"
        )

        self.assertEqual((640, 360), (metrics["width"], metrics["height"]))

    def test_radial_wash_fails_even_though_generic_integrity_passes(self) -> None:
        path = self.write_skin_menu("radial-wash.png", washed_out=True)

        packaged_runtime.inspect_screenshot(path)
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure,
            "OPAQUE_STARS background is unexpectedly bright or washed out",
        ):
            packaged_runtime.inspect_screenshot_for_step(
                path, "full", "client_a", "skin_menu_screen"
            )


if __name__ == "__main__":
    unittest.main()
