"""Lock the trainer-owned windowing rules.

The plugin and the legacy converter both upload whole sessions. These
tests use in-memory recordings so the window bounds are checked without
parquet files; a separate test covers the whole-session parquet layout.
"""

from pathlib import Path

import pytest

from dw_engine import config
from dw_engine.dataset import build_windows, split_windows

MOVEMENT = "PacketPlayInPosition"


def packet(timestamp: int, packet_type: str = MOVEMENT) -> dict:
    return {
        "type": packet_type,
        "timestamp": timestamp,
        "data": {},
        "context": {},
    }


def movement_stream(duration_ms: int, step_ms: int = 50) -> list[dict]:
    """Movement packets at 20 Hz from 0 through duration_ms."""
    return [packet(t) for t in range(0, duration_ms + 1, step_ms)]


def recording(
    packets: list[dict],
    *,
    label: str = "legit",
    player: str = "player-1",
    recording_id: int = 1674590000000,
) -> dict:
    return {
        "id": recording_id,
        "started": recording_id,
        "player": player,
        "type": label.upper(),
        "packets": packets,
    }


def use_recording(monkeypatch: pytest.MonkeyPatch, rec: dict, class_dir: str) -> None:
    monkeypatch.setattr(
        "dw_engine.dataset.iter_recordings",
        lambda _root: [(rec, class_dir, str(rec["id"]))],
    )


def test_split_windows_closes_at_the_window_length() -> None:
    rec = recording(movement_stream(8_000))

    assert split_windows(rec, 3.5) == [(0, 3_500), (3_500, 7_000)]


def test_split_windows_drops_the_trailing_partial_window() -> None:
    rec = recording(movement_stream(5_000))

    assert split_windows(rec, 3.5) == [(0, 3_500)]


def test_split_windows_returns_nothing_for_a_short_recording() -> None:
    rec = recording(movement_stream(2_000))

    assert split_windows(rec, 3.5) == []


def test_split_windows_ignores_non_movement_packets() -> None:
    packets = movement_stream(8_000) + [
        packet(3_600, "PacketPlayInArmAnimation"),
    ]

    assert split_windows(recording(packets), 3.5) == [(0, 3_500), (3_500, 7_000)]


def test_split_windows_accepts_one_precut_window() -> None:
    """A file from the old converter re-slices to itself."""
    rec = recording(movement_stream(3_500))

    assert split_windows(rec, 3.5) == [(0, 3_500)]


def test_build_windows_slices_a_whole_legit_recording(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    rec = recording(movement_stream(8_000), player="alice")
    use_recording(monkeypatch, rec, "legit")

    windows = build_windows(tmp_path, "speed")

    assert len(windows) == 2
    assert [w.label for w in windows] == [0, 0]
    assert [w.player for w in windows] == ["alice", "alice"]
    assert [w.recording_id for w in windows] == [str(rec["id"])] * 2
    # Both bounds include the closing packet, so the boundary packet is
    # shared and each window holds 3.5 s at 20 Hz (71 packets).
    assert [len(w.packets) for w in windows] == [71, 71]
    assert [w.duration_s for w in windows] == [3.5, 3.5]


def test_build_windows_labels_the_matching_cheat_directory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    rec = recording(movement_stream(8_000), label="SPEED")
    use_recording(monkeypatch, rec, "speed")

    windows = build_windows(tmp_path, "speed")

    assert [w.label for w in windows] == [1, 1]


def test_build_windows_keeps_the_minimum_movement_threshold(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """A full-length window with too few movement packets is dropped."""
    # Packets every 200 ms close a window at 3.6 s, but only 19 movement
    # packets fit, below config.MIN_MOVEMENT_PACKETS (20).
    rec = recording(movement_stream(3_600, step_ms=200))
    use_recording(monkeypatch, rec, "legit")

    assert config.MIN_MOVEMENT_PACKETS == 20
    assert build_windows(tmp_path, "speed") == []


def test_build_windows_drops_a_short_session(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    rec = recording(movement_stream(2_000))
    use_recording(monkeypatch, rec, "legit")

    assert build_windows(tmp_path, "speed") == []


def test_build_windows_ignores_another_specialists_recordings(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    rec = recording(movement_stream(8_000), label="KILLAURA")
    use_recording(monkeypatch, rec, "killaura")

    assert build_windows(tmp_path, "speed") == []
