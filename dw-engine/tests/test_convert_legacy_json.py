"""The legacy converter must produce the same whole-session layout as the plugin."""

import importlib.util
import json
from pathlib import Path

import pyarrow.parquet as pq

from dw_engine.dataset import build_windows

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "convert_legacy_json.py"


def load_converter():
    spec = importlib.util.spec_from_file_location("convert_legacy_json", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_json(path: Path, started: int) -> int:
    """Write a 8 s, 20 Hz session and return its packet count."""
    packets = [
        {
            "type": "PacketPlayInPosition",
            "timestamp": started + offset,
            "data": {
                "x": 0.0,
                "y": 64.0,
                "z": 0.0,
                "yaw": 0.0,
                "pitch": 0.0,
                "f": True,
                "hasPos": True,
                "hasLook": False,
            },
            "context": {},
        }
        for offset in range(0, 8_001, 50)
    ]
    with open(path, "w") as f:
        json.dump(
            {
                "id": started,
                "started": started,
                "player": "player-1",
                "packets": packets,
            },
            f,
        )
    return len(packets)


def test_converter_writes_one_whole_session_per_recording(tmp_path: Path) -> None:
    converter = load_converter()
    src = tmp_path / "src"
    out = tmp_path / "out"
    (src / "speed").mkdir(parents=True)

    started = 1_674_590_000_000
    packet_count = write_json(src / "speed" / f"{started}.json", started)

    converter.convert(src, out)

    files = sorted(out.rglob("*.parquet"))
    assert [path.name for path in files] == [f"{started}.parquet"]

    table = pq.read_table(files[0])
    assert table.num_rows == packet_count
    assert set(table.column("packet_type").to_pylist()) == {"PacketPlayInPosition"}

    # The trainer, not the converter, cuts the windows. The whole 8 s
    # session must still produce the expected two 3.5 s windows.
    assert len(build_windows(out, "speed")) == 2
