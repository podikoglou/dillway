"""Compacted shards: several sessions in one file, no double counting.

The plugin writes one session per source file and merges many of them into a
shard whose rows repeat the recording header. These tests build that layout
with pyarrow, so the reader is checked without the plugin.
"""

from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

from dw_engine.dataset import build_windows, iter_recordings
from dw_engine.parquet import load_sessions

SCHEMA = pa.schema(
    [
        ("id", pa.int64()),
        ("started", pa.int64()),
        ("player", pa.string()),
        ("recording_type", pa.string()),
        ("packet_type", pa.string()),
        ("timestamp", pa.int64()),
        ("x", pa.float64()),
        ("y", pa.float64()),
        ("z", pa.float64()),
        ("yaw", pa.float32()),
        ("pitch", pa.float32()),
        ("f", pa.bool_()),
        ("hasPos", pa.bool_()),
        ("hasLook", pa.bool_()),
    ]
)


def session_rows(
    recording_id: int,
    *,
    player: str = "player-1",
    label: str = "LEGIT",
    packets: int = 4,
) -> list[dict]:
    return [
        {
            "id": recording_id,
            "started": recording_id,
            "player": player,
            "recording_type": label,
            "packet_type": "PacketPlayInPosition",
            "timestamp": recording_id + i * 50,
            "x": 0.0,
            "y": 64.0,
            "z": 0.0,
            "yaw": 0.0,
            "pitch": 0.0,
            "f": True,
            "hasPos": True,
            "hasLook": False,
        }
        for i in range(packets)
    ]


def write(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(pa.Table.from_pylist(rows, schema=SCHEMA), path)


def test_load_sessions_splits_a_shard_on_the_recording_id(tmp_path: Path) -> None:
    path = tmp_path / "legit" / "compacted-100-200.parquet"
    write(
        path,
        session_rows(100, player="alice") + session_rows(200, player="bob", packets=2),
    )

    sessions = load_sessions(path)

    assert [(s["id"], s["player"]) for s in sessions] == [(100, "alice"), (200, "bob")]
    assert [len(s["packets"]) for s in sessions] == [4, 2]
    assert [p["timestamp"] for p in sessions[0]["packets"]] == [100, 150, 200, 250]


def test_load_sessions_keeps_payload_less_packets(tmp_path: Path) -> None:
    """Legacy files carry packet kinds with no payload columns; keep them."""
    rows = session_rows(100)
    rows.append(
        {
            "id": 100,
            "started": 100,
            "player": "player-1",
            "recording_type": "LEGIT",
            "packet_type": "PacketPlayInFlying",
            "timestamp": 101,
            "x": None,
            "y": None,
            "z": None,
            "yaw": None,
            "pitch": None,
            "f": None,
            "hasPos": None,
            "hasLook": None,
        }
    )
    path = tmp_path / "legit" / "100.parquet"
    write(path, rows)

    packets = load_sessions(path)[0]["packets"]

    assert packets[-1]["type"] == "PacketPlayInFlying"
    assert packets[-1]["data"] == {}
    assert packets[-1]["timestamp"] == 101


def test_iter_recordings_yields_every_session_in_a_shard(tmp_path: Path) -> None:
    write(
        tmp_path / "legit" / "compacted-100-300.parquet",
        session_rows(100) + session_rows(300, player="carol"),
    )

    found = list(iter_recordings(tmp_path))

    assert [(class_dir, recording_id) for _, class_dir, recording_id in found] == [
        ("legit", "100"),
        ("legit", "300"),
    ]


def test_iter_recordings_drops_a_source_that_a_shard_already_holds(
    tmp_path: Path,
) -> None:
    """An interrupted compaction leaves both copies; they must count once."""
    write(tmp_path / "legit" / "100.parquet", session_rows(100))
    write(tmp_path / "legit" / "compacted-100-100.parquet", session_rows(100))

    found = list(iter_recordings(tmp_path))

    assert [recording_id for _, _, recording_id in found] == ["100"]


def test_build_windows_reads_each_session_in_a_shard(tmp_path: Path) -> None:
    rows = session_rows(100, player="alice", packets=161) + session_rows(
        200, player="bob", packets=161
    )
    write(tmp_path / "legit" / "compacted-100-200.parquet", rows)

    windows = build_windows(tmp_path, "speed")

    assert len(windows) == 4
    assert sorted({w.recording_id for w in windows}) == ["100", "200"]
    assert sorted({w.player for w in windows}) == ["alice", "bob"]


def test_iter_recordings_keeps_one_id_under_two_labels(tmp_path: Path) -> None:
    """Relabeling writes the new directory; both labels stay independent."""
    write(tmp_path / "legit" / "100.parquet", session_rows(100))
    write(tmp_path / "speed" / "100.parquet", session_rows(100, label="SPEED"))

    found = list(iter_recordings(tmp_path))

    assert sorted((class_dir, recording_id) for _, class_dir, recording_id in found) == [
        ("legit", "100"),
        ("speed", "100"),
    ]
