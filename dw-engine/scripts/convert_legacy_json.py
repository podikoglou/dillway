"""Convert JSON recordings to the parquet dataset layout.

The engine stores one parquet file per recording and slices windows at
training time (dw_engine.dataset.build_windows()). This script converts
the older JSON recordings to the same layout, so legacy and plugin data
look identical:

Input: one JSON file per recording, packets nested under "packets",
each packet a {type, timestamp, data, context} dict:

    <json-root>/<label>/<started>.json

Output: one parquet file per recording, not per window:

    <out>/<label>/<started>.parquet

Keep the sessions whole. The trainer owns the window size, so changing
`WINDOW_SECONDS` or adding a specialist with a different window needs
no reconversion.

Usage (from dw-engine/):

    uv run python scripts/convert_legacy_json.py \
        --src <json-root> --out converted/recordings
"""

import argparse
import json
from collections import Counter
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

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


def _write_recording(out_dir: Path, recording: dict, fallback_id: str) -> Path:
    """Write one whole recording to a parquet file and return its path."""
    session_id = recording.get("started") or recording.get("id") or fallback_id
    columnar = {f.name: [] for f in SCHEMA}
    for row in _rows_for(recording, recording["packets"]):
        # Rows of packet kinds without a payload leave those keys unset;
        # the columns are nullable, so a plain None entry is correct.
        for name in columnar:
            columnar[name].append(row.get(name))
    table = pa.table(
        {name: pa.array(values, type=SCHEMA.field(name).type) for name, values in columnar.items()},
        schema=SCHEMA,
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{session_id}.parquet"
    pq.write_table(table, path)
    return path


def convert(src: Path, out: Path) -> Counter:
    """Convert every label directory under src into whole-session parquet."""
    total_recordings = Counter()
    for class_dir in sorted(p for p in src.iterdir() if p.is_dir()):
        cheat = class_dir.name.lower()
        out_dir = out / cheat
        converted = skipped_empty = 0
        for path in sorted(class_dir.glob("*.json")):
            with open(path) as f:
                recording = json.load(f)
            if (
                not recording
                or not isinstance(recording.get("packets"), list)
                or not recording["packets"]
            ):
                skipped_empty += 1
                continue
            # Normalize once here: each output file carries its label and
            # player in the header, so the trainer never needs the folder
            # name to know what a file is.
            recording.setdefault("type", cheat.upper())
            _write_recording(out_dir, recording, path.stem)
            converted += 1
        total_recordings[cheat] += converted
        print(
            f"  {cheat}: {converted} recordings written "
            f"({skipped_empty} skipped)"
        )
    return total_recordings


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("converted/recordings"))
    args = parser.parse_args(argv)

    if not args.src.is_dir():
        parser.error(f"source directory not found: {args.src}")
    counts = convert(args.src, args.out)
    print(f"\nWrote {sum(counts.values())} files under {args.out}")


if __name__ == "__main__":
    main()
