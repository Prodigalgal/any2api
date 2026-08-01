from __future__ import annotations

import math
import statistics
from dataclasses import dataclass, replace
from typing import Any


@dataclass(frozen=True)
class BlurPlacementEstimate:
    center_x: float
    confidence: float
    votes: int
    gain: float
    separation: float
    high_frequency_percentile: float
    sharp_similarity: float
    vertical_offset: float
    accepted: bool
    method: str = "legacy"

    @property
    def detail(self) -> str:
        return (
            f"x={self.center_x:.4f}:confidence={self.confidence:.3f}:votes={self.votes}:"
            f"gain={self.gain:.4f}:separation={self.separation:.4f}:"
            f"hf_percentile={self.high_frequency_percentile:.3f}:"
            f"sharp={self.sharp_similarity:.3f}:y_offset={self.vertical_offset:.3f}:"
            f"accepted={self.accepted}:method={self.method}"
        )


def estimate_blurred_object_placement(
    background: bytes,
    piece: bytes,
) -> BlurPlacementEstimate | None:
    """Locate an inpainted object slot using blur gain at the piece's fixed vertical band."""
    centered: BlurPlacementEstimate | None = None
    try:
        import cv2
        import numpy as np

        scene = cv2.imdecode(np.frombuffer(background, np.uint8), cv2.IMREAD_UNCHANGED)
        foreground = cv2.imdecode(np.frombuffer(piece, np.uint8), cv2.IMREAD_UNCHANGED)
        if scene is None or foreground is None or scene.ndim != 3 or foreground.ndim != 3:
            return None
        scene_bgr = scene[:, :, :3]
        foreground_bgr = foreground[:, :, :3]
        alpha = (
            foreground[:, :, 3]
            if foreground.shape[2] == 4
            else np.full(foreground.shape[:2], 255, dtype=np.uint8)
        )
        points = cv2.findNonZero((alpha > 16).astype(np.uint8))
        if points is None:
            return None
        left, top, width, height = cv2.boundingRect(points)
        if width < 3 or height < 3 or width > scene.shape[1] / 2 or height > scene.shape[0]:
            return None
        template = foreground_bgr[top : top + height, left : left + width]
        mask = alpha[top : top + height, left : left + width]
        binary_mask = (mask > 16).astype(np.uint8) * 255
        result_height = scene.shape[0] - height + 1
        result_width = scene.shape[1] - width + 1
        y_radius = max(3, round(height * 0.5))
        y_start = max(0, top - y_radius)
        y_stop = min(result_height, top + y_radius + 1)
        x_margin = max(width, round(scene.shape[1] * 0.04))
        x_start = max(0, x_margin - width // 2)
        x_stop = min(result_width, scene.shape[1] - x_margin - width // 2)
        if y_start >= y_stop or x_start >= x_stop:
            return None
        centered = _estimate_centered_placement(
            cv2,
            np,
            scene_bgr,
            template,
            binary_mask,
            top,
            width,
            height,
            x_start,
            x_stop,
        )
        sharp = cv2.matchTemplate(
            scene_bgr,
            template,
            cv2.TM_CCORR_NORMED,
            mask=binary_mask,
        )
        sharp_column_peaks = np.nanmax(
            sharp[y_start:y_stop, x_start:x_stop],
            axis=0,
        )
        samples: list[tuple[float, float, float, float]] = []
        sigma_base = max(2.0, min(10.0, height * 0.11))
        for sigma in (sigma_base * 0.65, sigma_base, sigma_base * 1.35):
            blurred_template = cv2.GaussianBlur(template, (0, 0), sigma)
            blurred = cv2.matchTemplate(
                scene_bgr,
                blurred_template,
                cv2.TM_CCORR_NORMED,
                mask=binary_mask,
            )
            gain = blurred - sharp
            window = gain[y_start:y_stop, x_start:x_stop].copy()
            sharp_matches = (sharp_column_peaks >= 0.9).astype(np.uint8)
            sharp_matches = cv2.dilate(
                sharp_matches.reshape(1, -1),
                np.ones((1, max(3, width)), dtype=np.uint8),
            ).reshape(-1)
            window[:, sharp_matches > 0] = -math.inf
            if not np.isfinite(window).any():
                return centered
            flat_index = int(np.nanargmax(window))
            local_y, local_x = np.unravel_index(flat_index, window.shape)
            target_x = x_start + int(local_x)
            target_y = y_start + int(local_y)
            best = float(gain[target_y, target_x])
            distinct = window.copy()
            center_in_window = target_x - x_start
            suppression = max(width * 2, round(scene.shape[1] * 0.05))
            distinct[
                :,
                max(0, center_in_window - suppression) : min(
                    distinct.shape[1], center_in_window + suppression + 1
                ),
            ] = -math.inf
            second = float(np.nanmax(distinct))
            samples.append(
                (
                    (target_x + width / 2) / scene.shape[1],
                    best,
                    best - second,
                    abs(target_y - top) / max(1, height),
                )
            )
        tolerance = max(width * 1.5 / scene.shape[1], 0.035)
        clusters: list[list[tuple[float, float, float, float]]] = []
        for sample in samples:
            matching = next(
                (
                    cluster
                    for cluster in clusters
                    if abs(statistics.median(item[0] for item in cluster) - sample[0]) <= tolerance
                ),
                None,
            )
            if matching is None:
                clusters.append([sample])
            else:
                matching.append(sample)
        cluster = max(
            clusters, key=lambda values: (len(values), statistics.median(v[1] for v in values))
        )
        center_x = statistics.median(value[0] for value in cluster)
        gain = statistics.median(value[1] for value in cluster)
        separation = statistics.median(value[2] for value in cluster)
        vertical_offset = statistics.median(value[3] for value in cluster)
        spread = max(value[0] for value in cluster) - min(value[0] for value in cluster)

        gray = cv2.cvtColor(scene_bgr, cv2.COLOR_BGR2GRAY)
        laplacian = np.abs(cv2.Laplacian(gray, cv2.CV_32F))
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
        boundary = cv2.subtract(
            cv2.dilate(binary_mask, kernel),
            cv2.erode(binary_mask, kernel),
        )
        weights = (boundary / 255).astype(np.float32)
        energy = cv2.matchTemplate(laplacian, weights, cv2.TM_CCORR) / max(
            1.0, float(np.sum(weights))
        )
        fixed_y = max(0, min(energy.shape[0] - 1, top))
        energy_row = energy[fixed_y, x_start:x_stop]
        target_left = max(0, min(energy.shape[1] - 1, round(center_x * scene.shape[1] - width / 2)))
        target_energy = float(energy[fixed_y, target_left])
        high_frequency_percentile = float(np.mean(energy_row <= target_energy))
        sharp_index = max(0, min(sharp_column_peaks.shape[0] - 1, target_left - x_start))
        sharp_similarity = float(sharp_column_peaks[sharp_index])

        votes = len(cluster)
        accepted = (
            votes == 3
            and gain >= 0.035
            and math.isfinite(separation)
            and spread <= tolerance
            and high_frequency_percentile <= 0.45
            and sharp_similarity <= 0.86
        )
        separation_score = min(0.08, max(0.0, separation) * 2) if math.isfinite(separation) else 0.0
        confidence = max(
            0.0,
            min(
                0.96,
                0.52
                + 0.1 * (votes - 2)
                + min(0.18, gain)
                + separation_score
                + 0.08 * (1 - high_frequency_percentile),
            ),
        )
        legacy = BlurPlacementEstimate(
            center_x=center_x,
            confidence=confidence,
            votes=votes,
            gain=gain,
            separation=separation,
            high_frequency_percentile=high_frequency_percentile,
            sharp_similarity=sharp_similarity,
            vertical_offset=vertical_offset,
            accepted=accepted,
        )
        if legacy.accepted and legacy.gain >= 0.25 and legacy.sharp_similarity <= 0.75:
            return replace(legacy, method="legacy_strong")
        return centered or legacy
    except Exception:  # noqa: BLE001 - optional native solver failures trigger a fresh challenge
        return centered


def _estimate_centered_placement(
    cv2: Any,
    np: Any,
    scene: Any,
    template: Any,
    mask: Any,
    top: int,
    width: int,
    height: int,
    x_start: int,
    x_stop: int,
) -> BlurPlacementEstimate | None:
    scene_float = scene.astype(np.float32)
    template_float = template.astype(np.float32)
    alpha = (mask.astype(np.float32) / 255.0)[:, :, None]
    sharp = cv2.matchTemplate(
        scene_float,
        template_float,
        cv2.TM_CCOEFF_NORMED,
        mask=mask,
    )
    sharp = np.nan_to_num(sharp, nan=-1.0, posinf=-1.0, neginf=-1.0)

    small_glyph = width <= 20 and height <= 24
    y_radius = max(3, round(height * 0.6)) if small_glyph else max(3, min(10, round(height * 0.5)))
    y_start = max(0, top - y_radius)
    y_stop = min(sharp.shape[0], top + y_radius + 1)
    if y_start >= y_stop or x_start >= x_stop:
        return None
    sharp_column_peaks = np.nanmax(sharp[y_start:y_stop, x_start:x_stop], axis=0)
    sharp_matches = (sharp_column_peaks >= 0.94).astype(np.uint8).reshape(1, -1)
    sharp_matches = cv2.dilate(
        sharp_matches,
        np.ones((1, max(5, round(width * 4))), dtype=np.uint8),
    ).reshape(-1)

    gray = cv2.cvtColor(scene, cv2.COLOR_BGR2GRAY)
    laplacian = np.abs(cv2.Laplacian(gray, cv2.CV_32F))
    weights = mask.astype(np.float32) / 255.0
    energy = cv2.matchTemplate(laplacian, weights, cv2.TM_CCORR) / max(1.0, float(np.sum(weights)))
    search_energy = energy[y_start:y_stop, x_start:x_stop]
    low_energy, high_energy = np.percentile(search_energy, (10, 90))
    energy_scale = max(0.001, float(high_energy - low_energy))
    normalized_energy = np.clip((energy - low_energy) / energy_scale, 0.0, 1.0)
    energy_weight = 0.0 if small_glyph else 0.32
    vertical_weight = 0.04 if small_glyph else 0.24
    vertical_penalty = np.abs(np.arange(sharp.shape[0], dtype=np.float32)[:, None] - top) / max(
        1, y_radius
    )

    base_sigma = max(1.2, min(8.0, height * 0.09))
    samples: list[tuple[float, float, float, float, float, float, float]] = []
    for multiplier in (0.7, 1.0, 1.3):
        sigma = base_sigma * multiplier
        blurred_alpha = cv2.GaussianBlur(alpha[:, :, 0], (0, 0), sigma)[:, :, None]
        blurred_template = cv2.GaussianBlur(template_float * alpha, (0, 0), sigma)
        blurred_template = blurred_template / np.maximum(blurred_alpha, 0.001)
        blurred_scene = cv2.GaussianBlur(scene_float, (0, 0), sigma)
        low_frequency = cv2.matchTemplate(
            blurred_scene,
            blurred_template,
            cv2.TM_CCOEFF_NORMED,
            mask=mask,
        )
        low_frequency = np.nan_to_num(low_frequency, nan=-1.0, posinf=-1.0, neginf=-1.0)
        gain = low_frequency - sharp
        score = (
            gain
            + 0.2 * low_frequency
            - energy_weight * normalized_energy
            - vertical_weight * vertical_penalty
        )
        window = score[y_start:y_stop, x_start:x_stop].copy()
        window[:, sharp_matches > 0] = -math.inf
        if not np.isfinite(window).any():
            continue
        flat_index = int(np.nanargmax(window))
        local_y, local_x = np.unravel_index(flat_index, window.shape)
        target_x = x_start + int(local_x)
        target_y = y_start + int(local_y)
        best = float(score[target_y, target_x])
        distinct = window.copy()
        center_in_window = target_x - x_start
        suppression = max(width, round(scene.shape[1] * 0.04))
        distinct[
            :,
            max(0, center_in_window - suppression) : min(
                distinct.shape[1], center_in_window + suppression + 1
            ),
        ] = -math.inf
        finite = distinct[np.isfinite(distinct)]
        second = float(np.max(finite)) if finite.size else best
        energy_percentile = float(np.mean(search_energy <= energy[target_y, target_x]))
        samples.append(
            (
                (target_x + width / 2) / scene.shape[1],
                best,
                float(gain[target_y, target_x]),
                best - second,
                energy_percentile,
                float(sharp[target_y, target_x]),
                abs(target_y - top) / max(1, height),
            )
        )
    if not samples:
        return None

    tolerance = max(0.025, min(0.08, width * 0.35 / scene.shape[1]))
    clusters: list[list[tuple[float, float, float, float, float, float, float]]] = []
    for sample in samples:
        cluster = next(
            (
                values
                for values in clusters
                if abs(statistics.median(item[0] for item in values) - sample[0]) <= tolerance
            ),
            None,
        )
        if cluster is None:
            clusters.append([sample])
        else:
            cluster.append(sample)
    winner = max(
        clusters,
        key=lambda values: (len(values), statistics.median(item[1] for item in values)),
    )
    center_x = statistics.median(item[0] for item in winner)
    score = statistics.median(item[1] for item in winner)
    gain = statistics.median(item[2] for item in winner)
    separation = statistics.median(item[3] for item in winner)
    high_frequency_percentile = statistics.median(item[4] for item in winner)
    sharp_similarity = statistics.median(item[5] for item in winner)
    vertical_offset = statistics.median(item[6] for item in winner)
    spread = max(item[0] for item in winner) - min(item[0] for item in winner)
    votes = len(winner)
    accepted = votes >= 2 and spread <= tolerance and score >= -0.35 and sharp_similarity <= 0.92
    confidence = max(
        0.0,
        min(
            0.96,
            0.48
            + 0.12 * (votes - 1)
            + min(0.16, max(0.0, gain))
            + min(0.08, max(0.0, separation))
            + 0.08 * (1 - high_frequency_percentile),
        ),
    )
    return BlurPlacementEstimate(
        center_x=center_x,
        confidence=confidence,
        votes=votes,
        gain=gain,
        separation=separation,
        high_frequency_percentile=high_frequency_percentile,
        sharp_similarity=sharp_similarity,
        vertical_offset=vertical_offset,
        accepted=accepted,
        method="centered_low_frequency",
    )
