"""Parquet recording reader.

Recordings are stored as Parquet with one row per packet: the recording
header columns (which are identical for every row of a file) plus the
packet's timestamp, type, payload, and captured player context.
Payload columns are null where the packet kind has no such payload;
context columns are always present.

A source file holds one whole recording. A compacted shard holds several:
the flat layout lets the shard repeat the header on every row, so rows are
grouped by recording id and each group becomes one session. Windowing
happens later in dataset.py, not in the reader.

The engine's feature/window code consumes the nested per-packet shape
(type / timestamp / data / context dicts), so load_sessions() rebuilds
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


def load_sessions(path: Path) -> list[dict]:
    """Read one parquet file and return every recording it holds.

    Each session has the nested shape:

        {"id", "started", "player", "type", "packets": [...]}

    A source file yields one session. A compacted shard yields several,
    split on the recording header that repeats on each row. Header values
    are taken from the first row of the session.

    Returns an empty list for a zero-row file, i.e. an empty recording.
    """
    table = pq.read_table(path)
    if table.num_rows == 0:
        return []

    columns = {
        name: table.column(name).to_pylist() for name in table.schema.names
    }

    # Column classification is schema-only: header and payload names are
    # fixed, every other column is context.
    known = set(HEADER_FIELDS) | set(MOVE_FIELDS) | {ANIMATION_FIELD, HELD_FIELD}
    context_names = [name for name in columns if name not in known]

    # Group rows by the recording identity column. Older files always carry
    # `id`; `started` is the fallback if it is ever absent.
    group_column = "id" if "id" in columns else "started"
    groups: dict[object, list[int]] = {}
    order: list[object] = []
    for i in range(table.num_rows):
        key = columns[group_column][i] if group_column in columns else 0
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append(i)

    sessions = []
    for key in order:
        rows = groups[key]
        first = rows[0]
        packets = []
        for i in rows:
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

        sessions.append(
            {
                "id": columns["id"][first] if "id" in columns else None,
                "started": (
                    columns["started"][first]
                    if "started" in columns
                    else columns["timestamp"][first]
                ),
                "player": columns["player"][first] if "player" in columns else None,
                "type": columns["recording_type"][first],
                "packets": packets,
            }
        )
    return sessions
