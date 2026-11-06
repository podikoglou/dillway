"""Feature extraction for the dillway anti-cheat engine.

v1 baseline: per-window summary statistics over movement packets
(30 features, fixed order given by FEATURE_NAMES).

Planned v2 (see TRAINING.md): physics residuals. Predict the next
position from context (velocity, on_ground, gravity and drag) and feed
aggregate residual statistics into the model. Keep each feature set as
its own registry entry; a specialist trains on one set.
"""

import math
from collections import Counter

import numpy as np

MOVEMENT_TYPES = {"PacketPlayInPosition", "PacketPlayInPositionLook"}

FEATURE_NAMES = [
    # 3D speed (units/second between consecutive movement packets)
    "mean_speed", "max_speed", "std_speed",
    "p50_speed", "p75_speed", "p95_speed", "p99_speed",
    # Horizontal speed
    "mean_horiz_speed", "max_horiz_speed", "std_horiz_speed", "p95_horiz_speed",
    # Packet timing
    "mean_dt", "std_dt", "min_dt",
    # Acceleration (change in speed)
    "mean_accel", "max_accel",
    # Per-step movement size
    "mean_dy", "mean_horiz_step", "max_horiz_step",
    # Player state ratios
    "on_ground_ratio", "flying_ratio", "sprinting_ratio",
    # Server-side velocity magnitudes
    "mean_vel_x", "mean_vel_z", "mean_vel_y",
    # Window metadata
    "num_movement_packets", "total_packets",
    # Packet type ratios
    "ratio_position", "ratio_positionlook", "ratio_keepalive",
]


def extract_features(session: dict) -> list[float] | None:
    """Extract a fixed-length feature vector from a session/window dict.

    Returns None if there are fewer than 2 usable movement packets.
    """
    all_packets = session["packets"]
    pkts = [p for p in all_packets if p["type"] in MOVEMENT_TYPES]

    if len(pkts) < 2:
        return None

    ptype_counts = Counter(p["type"] for p in all_packets)
    total = len(all_packets)

    speeds, horiz_speeds, dts, dy_list, horiz_steps = [], [], [], [], []

    for i in range(1, len(pkts)):
        p_prev, p_curr = pkts[i - 1], pkts[i]
        dt = (p_curr["timestamp"] - p_prev["timestamp"]) / 1000.0
        if dt <= 0 or dt > 2.0:
            continue

        x1 = p_prev["data"]["x"]
        z1 = p_prev["data"]["z"]
        y1 = p_prev["data"]["y"]
        x2 = p_curr["data"]["x"]
        z2 = p_curr["data"]["z"]
        y2 = p_curr["data"]["y"]

        dx, dz, dy = x2 - x1, z2 - z1, y2 - y1
        dist_3d = math.sqrt(dx**2 + dy**2 + dz**2)
        dist_horiz = math.sqrt(dx**2 + dz**2)

        speeds.append(dist_3d / dt)
        horiz_speeds.append(dist_horiz / dt)
        dts.append(dt)
        dy_list.append(abs(dy))
        horiz_steps.append(dist_horiz)

    if not speeds:
        return None

    accel = [abs(speeds[i] - speeds[i - 1]) for i in range(1, len(speeds))]

    on_ground_ratio = sum(p["context"]["on_ground"] for p in pkts) / len(pkts)
    flying_ratio = sum(p["context"]["flying"] for p in pkts) / len(pkts)
    sprinting_ratio = sum(p["context"]["sprinting"] for p in pkts) / len(pkts)

    vel_x = [abs(p["context"]["velocity_x"]) for p in pkts]
    vel_z = [abs(p["context"]["velocity_z"]) for p in pkts]
    vel_y = [abs(p["context"]["velocity_y"]) for p in pkts]

    return [
        np.mean(speeds), np.max(speeds), np.std(speeds),
        np.percentile(speeds, 50), np.percentile(speeds, 75),
        np.percentile(speeds, 95), np.percentile(speeds, 99),
        np.mean(horiz_speeds), np.max(horiz_speeds), np.std(horiz_speeds),
        np.percentile(horiz_speeds, 95),
        np.mean(dts), np.std(dts), np.min(dts),
        np.mean(accel) if accel else 0.0, np.max(accel) if accel else 0.0,
        np.mean(dy_list), np.mean(horiz_steps), np.max(horiz_steps),
        on_ground_ratio, flying_ratio, sprinting_ratio,
        np.mean(vel_x), np.mean(vel_z), np.mean(vel_y),
        len(pkts), total,
        ptype_counts.get("PacketPlayInPosition", 0) / total,
        ptype_counts.get("PacketPlayInPositionLook", 0) / total,
        ptype_counts.get("PacketPlayInKeepAlive", 0) / total,
    ]
