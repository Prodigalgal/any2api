from __future__ import annotations

import cv2
import numpy as np

from any2api_automation.captcha.object_placement import estimate_blurred_object_placement


def png(image: np.ndarray) -> bytes:
    ok, encoded = cv2.imencode(".png", image)
    assert ok
    return encoded.tobytes()


def test_blur_placement_locates_inpainted_residual() -> None:
    rng = np.random.default_rng(42)
    background = rng.integers(20, 60, size=(180, 300, 3), dtype=np.uint8)
    piece = np.zeros((180, 24, 4), dtype=np.uint8)
    object_pixels = np.zeros((72, 24, 3), dtype=np.uint8)
    object_alpha = np.zeros((72, 24), dtype=np.uint8)
    cv2.ellipse(object_alpha, (12, 15), (5, 14), 0, 0, 360, 255, -1)
    cv2.rectangle(object_alpha, (5, 27), (19, 69), 255, -1)
    object_pixels[object_alpha > 0] = (30, 130, 235)
    object_pixels[5:25, 9:15] = (220, 240, 255)
    object_pixels[35:68, 7:17] = (20, 80, 190)
    piece[55:127, :, :3] = object_pixels
    piece[55:127, :, 3] = object_alpha
    blurred = cv2.GaussianBlur(object_pixels, (0, 0), 7)
    blurred_alpha = (cv2.GaussianBlur(object_alpha, (0, 0), 7) / 255.0)[:, :, None]
    blurred_roi = background[35:107, 208:232].astype(np.float32)
    background[35:107, 208:232] = (
        blurred_roi * (1 - blurred_alpha) + blurred * blurred_alpha
    ).astype(np.uint8)

    estimate = estimate_blurred_object_placement(png(background), png(piece))

    assert estimate is not None
    assert estimate.accepted is True
    assert abs(estimate.center_x - 220 / 300) < 0.06
    assert estimate.votes == 3


def test_blur_placement_rejects_scene_without_consistent_blur_residual() -> None:
    rng = np.random.default_rng(7)
    background = rng.integers(0, 255, size=(180, 300, 3), dtype=np.uint8)
    piece = np.zeros((180, 20, 4), dtype=np.uint8)
    piece[60:120, :, :3] = (50, 150, 240)
    piece[60:120, :, 3] = 255

    estimate = estimate_blurred_object_placement(png(background), png(piece))

    assert estimate is not None
    assert estimate.accepted is False
