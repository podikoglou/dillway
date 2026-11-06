"""Parquet recording reader.

Recordings are stored as Parquet with one row per packet: the recording
header columns (which are identical for every row of a file) plus the
packet's timestamp, type, payload, and captured player context.
Payload columns are null where the packet kind has no such payload;
context columns are always present.

A file normally holds one whole recording; windowing happens later in
dataset.py, not in the reader.

The engine's feature/window code consumes the nested per-packet shape
(type / timestamp / data / context dicts), so load_session() rebuilds
that shape from the flat rows. Columns are matched by name, so column
order in the file is irrelevant.
"""

from pathlib import Path

import pyarrow.parquet as pq

# Recording/packet identity columns — present on every row.
HEADER_FIELDS = ("id", "started", "player", "recording_type", "packet_type", "timestamp")
# Position/look payload columns (three movement packet kinds).
MOVE_FIELDS = ("x", "y", "z", "yaw", "pitch", "f", "hasPos", "hasLook")
ANIMATION_FIELD = "animation_timestamp"
HELD_FIELD = "itemInHandIndex"
# The packet kinds currently written.
MOVE_TYPES = ("PacketPlayInPosition", "PacketPlayInPositionLook", "PacketPlayInLook")
ANIMATION_TYPE = "PacketPlayInArmAnimation"
HELD_TYPE = "PacketPlayInHeldItemSlot"


def _payload_fields(packet_type: str) -> tuple[str, ...]:
    if packet_type in MOVE_TYPES:
        return MOVE_FIELDS
    if packet_type == ANIMATION_TYPE:
        return (ANIMATION_FIELD,)
    if packet_type == HELD_TYPE:
        return (HELD_FIELD,)
    return ()  # unknown type: its payload columns are treated as context


def load_session(path: Path) -> dict | None:
    """Read one recording parquet and return nested session shape:

        {"id", "started", "player", "type", "packets": [...]}

    Each packet is {"type", "timestamp", "data", "context"}; `data`
    contains only the payload fields present on the row. Header values
    are taken from the first row (they are constant within a file).

    Returns None for a zero-row file, i.e. an empty recording.
    """
    table = pq.read_table(path)
    if table.num_rows == 0:
        return None

    columns = {
        name: table.column(name).to_pylist() for name in table.schema.names
    }

    # Column classification is schema-only: header and payload names are
    # fixed, every other column is context.
    known = set(HEADER_FIELDS) | set(MOVE_FIELDS) | {ANIMATION_FIELD, HELD_FIELD}
    context_names = [name for name in columns if name not in known]

    packets = []
    for i in range(table.num_rows):
        packet_type = columns["packet_type"][i]
        payload_fields = _payload_fields(packet_type)
        row = {name: columns[name][i] for name in columns}
        packets.append(
            {
                "type": packet_type,
                "timestamp": row["timestamp"],
                "data": {
                    name: row[name]
                    for name in payload_fields
                    if row[name] is not None
                },
                "context": {
                    name: value
                    for name in context_names
                    if (value := row[name]) is not None
                },
            }
        )

    return {
        "id": columns["id"][0] if "id" in columns else None,
        "started": (
            columns["started"][0]
            if "started" in columns
            else columns["timestamp"][0]
        ),
        "player": columns["player"][0] if "player" in columns else None,
        "type": columns["recording_type"][0],
        "packets": packets,
    }
