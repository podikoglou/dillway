package com.dillway;

/** A recording label. The label also selects the output folder. */
public enum RecordingType {
    LEGIT("legit"),
    SPEED("speed");

    private final String folder;

    RecordingType(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
