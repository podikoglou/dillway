package com.dillway;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Holds the in-progress state of one player recording. */
public class RecordingSession {

    public final UUID playerId;
    public final long started;
    public final RecordingType type;
    public final List<Object> packets = new ArrayList<>();

    public RecordingSession(UUID playerId, RecordingType type) {
        this.playerId = playerId;
        this.started  = System.currentTimeMillis();
        this.type     = type;
    }
}
