package io.github.wpfyorg.afhelper.ble;

/** Tracks the one scan attempt allowed to publish callbacks at a time. */
final class ScanGeneration {
    private long next;
    private long active = -1;

    long begin() {
        active = ++next;
        return active;
    }

    void end(long id) {
        if (active == id) active = -1;
    }

    boolean isCurrent(long id) { return active == id; }
}
