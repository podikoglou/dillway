"""Dataset loading and windowing.

Recordings are Parquet files, one file per recording, grouped by label
directory:

    <root>/<type>/<started>.parquet   e.g. legit/1674591086943.parquet

Per data-main/docs/DATASET.md, training samples are windows of (a few)
seconds of packets, not whole recordings. split_windows() slices each
recording on movement-packet timestamps; a gap longer than the window
length closes the current window. Windows shorter than the target
length, or with fewer usable movement packets, are discarded.

Every window keeps its metadata: label, player UUID (the group key used
by grouped cross-validation), and the source recording id.
"""

from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path

from . import config
from .features import MOVEMENT_TYPES
from .parquet import load_session


@dataclass
class Window:
    """One labeled training sample (feature extraction happens later)."""

    packets: list[dict]
    label: int
    player: str
    recording_id: str
    start_ts: int
    end_ts: int
    duration_s: float = field(init=False)

    def __post_init__(self) -> None:
        self.duration_s = (self.end_ts - self.start_ts) / 1000.0


def _load_recording(path: Path) -> tuple[dict, str] | None:
    """Return (recording, class_dir_name), or None if the file is unusable.

    class_dir_name is the subdirectory under the dataset root: 'legit'
    or the name of the cheat recorded there ('speed', ...).
    """
    recording = load_session(path)
    if not recording or not recording.get("packets"):
        return None
    class_dir = path.parent.name.lower()
    if not class_dir:
        return None
    return recording, class_dir


def iter_recordings(root: Path) -> Iterator[tuple[dict, str, str]]:
    """Yield (recording, class_dir, recording_id) for every usable file."""
    for path in sorted(root.rglob("*.parquet")):
        loaded = _load_recording(path)
        if loaded is None:
            continue
        recording, class_dir = loaded
        recording_id = str(recording.get("id") or path.stem)
        yield recording, class_dir, recording_id


def split_windows(recording: dict, window_s: float) -> list[tuple[int, int]]:
    """Return (start_ts, end_ts) epoch-ms bounds for each full window.

    Boundaries come from movement packets: a gap longer than the window
    size closes the current window. Only windows that actually span
    window_s of movement time are kept.
    """
    stamps = [
        p["timestamp"]
        for p in recording["packets"]
        if p.get("type") in MOVEMENT_TYPES
    ]
    if not stamps:
        return []
    bounds: list[tuple[int, int]] = []
    window_ms = window_s * 1000.0
    start = stamps[0]
    for t in stamps[1:]:
        if t - start >= window_ms:
            bounds.append((start, t))
            start = t
    return bounds


def build_windows(root: Path, cheat: str) -> list[Window]:
    """Slice every recording under root into labeled windows for one cheat.

    Only 'legit' recordings (negative) and '<cheat>' recordings
    (positive) are used — a "speed" specialist must not train on, say,
    killaura recordings, since a specialist's negatives must be
    genuinely cheat-free for that check (data-main/docs/ARCHITECTURE.md).
    """
    window_s = config.WINDOW_SECONDS.get(cheat)
    if window_s is None:
        raise RuntimeError(
            f"No WINDOW_SECONDS entry for cheat '{cheat}'. "
            f"Known cheats: {sorted(config.WINDOW_SECONDS)}"
        )
    cheat_dir = cheat.lower()

    windows: list[Window] = []
    for recording, class_dir, recording_id in iter_recordings(root):
        if class_dir == "legit":
            label = 0
        elif class_dir == cheat_dir:
            label = 1
        else:
            continue  # recorded for some other specialist's training
        player = str(recording.get("player") or "unknown")
        for start_ts, end_ts in split_windows(recording, window_s):
            packets = [
                p
                for p in recording["packets"]
                if start_ts <= p.get("timestamp", 0) <= end_ts
            ]
            movement = sum(1 for p in packets if p.get("type") in MOVEMENT_TYPES)
            if movement < config.MIN_MOVEMENT_PACKETS:
                continue
            windows.append(
                Window(
                    packets=packets,
                    label=label,
                    player=player,
                    recording_id=recording_id,
                    start_ts=start_ts,
                    end_ts=end_ts,
                )
            )
    return windows


def design_matrix(
    windows: list[Window],
) -> tuple[list[list[float]], list[int], list[str]]:
    """Extract features for all windows; returns (X, y, group) lists."""
    from .features import extract_features

    X: list[list[float]] = []
    y: list[int] = []
    groups: list[str] = []
    for w in windows:
        feat = extract_features({"packets": w.packets})
        if feat is None:
            continue
        X.append(feat)
        y.append(w.label)
        groups.append(w.player)
    return X, y, groups
