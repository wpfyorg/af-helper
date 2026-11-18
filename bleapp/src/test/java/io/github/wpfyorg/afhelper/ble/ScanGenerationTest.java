package io.github.wpfyorg.afhelper.ble;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScanGenerationTest {
    @Test public void callbacksFromAnEarlierScanBecomeStaleAfterRestart() {
        ScanGeneration scans = new ScanGeneration();
        long first = scans.begin();
        assertTrue(scans.isCurrent(first));

        scans.end(first);
        long second = scans.begin();
        assertNotEquals(first, second);
        assertFalse(scans.isCurrent(first));
        assertTrue(scans.isCurrent(second));
    }

    @Test public void endingAnOldAttemptCannotEndTheCurrentScan() {
        ScanGeneration scans = new ScanGeneration();
        long first = scans.begin();
        long second = scans.begin();

        scans.end(first);
        assertTrue(scans.isCurrent(second));
    }
}
