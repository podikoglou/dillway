"""Convert JSON recordings to parquet, split into windows.

The parquet ingester (dw_engine/parquet.py) reads one parquet file per
window. This script converts the older JSON recordings to that format
up front, so the training data can be uploaded as parquet:

Input: one JSON file per recording, packets nested under "packets",
each packet a {type, timestamp, data, context} dict:

    ../data-main/legit/<started>.json
    ../data-main/speed/<started>.json

Output: one parquet file per window, already split with the same
windowing the training pipeline applies, so the ingester reads each
file back as exactly one window:

    <out>/<type>/<started>.parquet     e.g. converted/recordings/legit/1674591086943.parquet

Window boundaries come from `split_windows`, the same function the
trainer uses. Windows with fewer movement packets than
`MIN_MOVEMENT_PACKETS` are dropped here, the same threshold the
trainer applies.

Usage (from dw-engine/):

    uv run python scripts/convert_legacy_json.py \
        --src ../data-main --out converted/recordings
"""

import argparse
import json
from collections import Counter
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

from dw_engine import config
from dw_engine.dataset import split_windows
from dw_engine.features import MOVEMENT_TYPES

# Flat recording schema: recording header + packet row + move payload +
# player context. Header and context columns are required (present on
# every row); payload columns are optional — null on rows whose packet
# kind carries no such payload.
SCHEMA = pa.schema(
    [
        # Recording header (repeated on every row).
        ("id", pa.int64(), False),
        ("started", pa.int64(), False),
        ("player", pa.string(), False),
        ("recording_type", pa.string(), False),
        # Packet row.
        ("packet_type", pa.string(), False),
        ("timestamp", pa.int64(), False),
        # Move payload.
        ("x", pa.float64(), True),
        ("y", pa.float64(), True),
        ("z", pa.float64(), True),
        ("yaw", pa.float32(), True),
        ("pitch", pa.float32(), True),
        ("f", pa.bool_(), True),
        ("hasPos", pa.bool_(), True),
        ("hasLook", pa.bool_(), True),
        # Animation / held-item payloads.
        ("animation_timestamp", pa.int64(), True),
        ("itemInHandIndex", pa.int32(), True),
        # PlayerContext snapshot (captured with the packet).
        ("fly_speed", pa.float32(), False),
        ("velocity_z", pa.float64(), False),
        ("location_x", pa.float64(), False),
        ("location_y", pa.float64(), False),
        ("location_direction_y", pa.float64(), False),
        ("location_direction_x", pa.float64(), False),
        ("velocity_x", pa.float64(), False),
        ("velocity_y", pa.float64(), False),
        ("saturation", pa.float32(), False),
        ("location_direction_z", pa.float64(), False),
        ("location_z", pa.float64(), False),
        ("food_level", pa.int32(), False),
        ("eye_location_yaw", pa.float32(), False),
        ("walk_speed", pa.float32(), False),
        ("eye_height", pa.float64(), False),
        ("allow_flight", pa.bool_(), False),
        ("exhaustion", pa.float32(), False),
        ("health", pa.float64(), False),
        ("eye_location_pitch", pa.float32(), False),
        ("location_pitch", pa.float32(), False),
        ("fall_distance", pa.float32(), False),
        ("location_yaw", pa.float32(), False),
        ("health_scale", pa.float64(), False),
        ("sprinting", pa.bool_(), False),
        ("eye_location_y", pa.float64(), False),
        ("eye_location_x", pa.float64(), False),
        ("flying", pa.bool_(), False),
        ("eye_location_z", pa.float64(), False),
        ("sneaking", pa.bool_(), False),
        ("on_ground", pa.bool_(), False),
    ]
)

# Move payload lives in packet["data"] under these keys.
MOVE_DATA_KEYS = ("x", "y", "z", "yaw", "pitch", "f", "hasPos", "hasLook")
NON_CONTEXT_FIELDS = (
    "id", "started", "player", "recording_type", "packet_type", "timestamp",
    *MOVE_DATA_KEYS, "animation_timestamp", "itemInHandIndex",
)
CONTEXT_FIELDS = [f.name for f in SCHEMA if f.name not in NON_CONTEXT_FIELDS]

DEFAULTS = {
    pa.int64(): 0,
    pa.int32(): 0,
    pa.float32(): 0.0,
    pa.float64(): 0.0,
    pa.string(): "",
    pa.bool_(): False,
}


def _coerce(field: pa.Field, value) -> object:
    """Coerce a JSON leaf to the parquet column type, defaulting on missing."""
    pa_type = field.type
    if value is None:
        return None if field.nullable else DEFAULTS[pa_type]
    if pa.types.is_floating(pa_type):
        return float(value)
    if pa.types.is_integer(pa_type):
        return int(value)
    if pa.types.is_string(pa_type):
        return str(value)
    if pa.types.is_boolean(pa_type):
        return bool(value)
    return value


def _rows_for(recording: dict, packets: list[dict]) -> list[dict]:
    """Flatten the packets of one window into schema rows."""
    player = recording.get("player") or ""
    recording_type = str(recording.get("type") or "").upper()
    session_id = recording.get("id") or 0
    rows = []
    for p in packets:
        ptype = p.get("type") or ""
        row = {
            "id": session_id,
            "started": recording.get("started") or session_id,
            "player": player,
            "recording_type": recording_type,
            "packet_type": ptype,
            "timestamp": p.get("timestamp") or 0,
        }
        data = p.get("data") or {}
        if ptype.startswith("PacketPlayInPosition") or ptype == "PacketPlayInLook":
            for key in MOVE_DATA_KEYS:
                row[key] = _coerce(SCHEMA.field(key), data.get(key))
        elif ptype == "PacketPlayInArmAnimation":
            row["animation_timestamp"] = _coerce(
                SCHEMA.field("animation_timestamp"), data.get("timestamp")
            )
        elif ptype == "PacketPlayInHeldItemSlot":
            row["itemInHandIndex"] = _coerce(
                SCHEMA.field("itemInHandIndex"), data.get("itemInHandIndex")
            )
        context = p.get("context") or {}
        for name in CONTEXT_FIELDS:
            row[name] = _coerce(SCHEMA.field(name), context.get(name))
        rows.append(row)
    return rows


def _write_window(
    out_dir: Path, recording: dict, window_packets: list[dict], start_ts: int
) -> Path:
    columnar = {f.name: [] for f in SCHEMA}
    for row in _rows_for(recording, window_packets):
        # Rows of packet kinds without a payload leave those keys unset;
        # the columns are nullable, so a plain None entry is correct.
        for name in columnar:
            columnar[name].append(row.get(name))
    table = pa.table(
        {name: pa.array(values, type=SCHEMA.field(name).type) for name, values in columnar.items()},
        schema=SCHEMA,
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{start_ts}.parquet"
    pq.write_table(table, path)
    return path


def convert(src: Path, out: Path) -> None:
    """Convert every label directory under src into windowed parquet."""
    total_windows = Counter()
    # Window size per directory: cheats use their own entry. Legit
    # recordings are the negative class shared by every specialist, and
    # the trainer re-slices each legit file to that specialist's window
    # size, so legit files are cut to the largest configured window and
    # never shorter than any specialist needs.
    max_window_s = max(config.WINDOW_SECONDS.values())
    for class_dir in sorted(p for p in src.iterdir() if p.is_dir()):
        cheat = class_dir.name.lower()
        window_s = max_window_s if cheat == "legit" else config.WINDOW_SECONDS.get(cheat)
        if window_s is None:
            print(f"  !! {cheat}: no WINDOW_SECONDS entry, skipping this directory")
            continue
        out_dir = out / cheat
        converted = skipped_empty = 0
        for path in sorted(class_dir.glob("*.json")):
            with open(path) as f:
                recording = json.load(f)
            if not recording or not isinstance(recording.get("packets"), list):
                skipped_empty += 1
                continue
            # Normalize once here: each output file carries its label and
            # player in the header, so the trainer never needs the folder
            # name to know what a file is.
            recording.setdefault("type", cheat.upper())
            windows = split_windows(recording, window_s)
            if not windows:
                skipped_empty += 1
                continue
            for start_ts, end_ts in windows:
                window_packets = [
                    p
                    for p in recording["packets"]
                    if start_ts <= p.get("timestamp", 0) <= end_ts
                ]
                # Same threshold the loader applies; drop here instead of
                # shipping windows the training run would reject anyway.
                movement = sum(
                    1
                    for p in window_packets
                    if p.get("type", "") in MOVEMENT_TYPES
                )
                if movement < config.MIN_MOVEMENT_PACKETS:
                    skipped_empty += 1
                    continue
                _write_window(out_dir, recording, window_packets, start_ts)
                converted += 1
        total_windows[cheat] += converted
        print(
            f"  {cheat}: {converted} window files written "
            f"({skipped_empty} recordings/windows skipped)"
        )
    return total_windows


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src", type=Path, default=Path("../data-main"))
    parser.add_argument("--out", type=Path, default=Path("converted/recordings"))
    args = parser.parse_args(argv)

    if not args.src.is_dir():
        parser.error(f"source directory not found: {args.src}")
    counts = convert(args.src, args.out)
    print(f"\nWrote {sum(counts.values())} files under {args.out}")


if __name__ == "__main__":
    main()
