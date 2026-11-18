package io.github.wpfyorg.afhelper.log;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LogQueueTest {
    @Test public void acknowledgementKeepsRecordsAppendedAfterSnapshot() throws Exception {
        File dir = Files.createTempDirectory("afble-log").toFile();
        LogQueue queue = new LogQueue(new File(dir, "queue"));
        queue.append("event-one");
        queue.append("event-two");
        LogQueue.Snapshot sent = queue.snapshot();

        queue.append("event-three");
        queue.acknowledge(sent.throughSequence);

        LogQueue.Snapshot remaining = queue.snapshot();
        assertEquals(1, remaining.queuedCount);
        assertEquals("event-three\n", new String(remaining.body, "UTF-8"));
    }

    @Test public void snapshotIsBoundedAndAcknowledgesOnlyIncludedRecords() throws Exception {
        File dir = Files.createTempDirectory("afble-log-bound").toFile();
        LogQueue queue = new LogQueue(new File(dir, "queue"));
        String event = repeat('x', 20_000);
        for (int i = 0; i < 40; i++) queue.append(event + i);

        LogQueue.Snapshot snapshot = queue.snapshot();
        assertTrue(snapshot.body.length <= LogQueue.MAX_SNAPSHOT_BYTES);
        assertTrue(snapshot.queuedCount < 40);
        queue.acknowledge(snapshot.throughSequence);
        assertEquals(0, queue.snapshot().queuedCount);
    }

    @Test public void retrySnapshotHasStableBodyAndCursor() throws Exception {
        File dir = Files.createTempDirectory("afble-log-retry").toFile();
        LogQueue queue = new LogQueue(new File(dir, "queue"));
        queue.append("event-one");
        LogQueue.Snapshot firstAttempt = queue.snapshot();
        LogQueue.Snapshot retryAttempt = queue.snapshot();

        assertEquals(firstAttempt.throughSequence, retryAttempt.throughSequence);
        org.junit.Assert.assertArrayEquals(firstAttempt.body, retryAttempt.body);
    }

    @Test public void damagedRecordIsQuarantinedAndOtherRecordsSurvive() throws Exception {
        File dir = Files.createTempDirectory("afble-corrupt").toFile();
        File file = new File(dir, "queue");
        LogQueue queue = new LogQueue(file);
        queue.append("event-one");
        queue.append("event-two");

        byte[] bytes = Files.readAllBytes(file.toPath());
        int checksum = indexOf(bytes, (byte) '\t', 2);
        bytes[checksum + 1] = bytes[checksum + 1] == '0' ? (byte) '1' : (byte) '0';
        Files.write(file.toPath(), bytes);

        LogQueue.Snapshot recovered = queue.snapshot();
        assertEquals("event-two\n", new String(recovered.body, StandardCharsets.UTF_8));
        assertTrue(new File(dir, "queue.corrupt").length() > 0);
        assertEquals(1, queue.size());
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static int indexOf(byte[] bytes, byte target, int occurrence) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == target) {
                if (--occurrence == 0) return i;
            }
        }
        throw new AssertionError("tab not found");
    }

    @Test public void viewerReportShowsCountHeaderAndMostRecentRows() throws Exception {
        File dir = Files.createTempDirectory("afble-viewer").toFile();
        LogQueue queue = new LogQueue(new File(dir, "queue"));
        queue.append("event-one");
        queue.append("ble_diag phase=pan_result code=na bytes=12 result=valid_address");
        LogQueue.Snapshot snapshot = queue.snapshot();
        String report = LogQueue.reportOf(snapshot.body, snapshot.queuedCount, 500);
        assertTrue(report.startsWith("2 events queued.\n"));
        assertTrue(report.contains("event-one\n"));
        assertTrue(report.endsWith(
                "ble_diag phase=pan_result code=na bytes=12 result=valid_address\n"));
    }

    @Test public void viewerReportCapsRowsAndFlagsTruncation() throws Exception {
        File dir = Files.createTempDirectory("afble-viewer-cap").toFile();
        LogQueue queue = new LogQueue(new File(dir, "queue"));
        for (int i = 0; i < 12; i++) queue.append("event-" + i);

        LogQueue.Snapshot snapshot = queue.snapshot();
        String report = LogQueue.reportOf(snapshot.body, snapshot.queuedCount, 5);
        assertTrue(report.startsWith("12 events queued.\n"));
        assertTrue(report.contains("Showing the most recent 5.\n"));
        assertTrue(report.contains("event-11\n"));
        assertTrue(!report.contains("event-6\n")); // capped: only the newest five survive
    }

    @Test public void emptyQueueRendersEmptyReport() {
        assertEquals("", LogQueue.reportOf(new byte[0], 0, 500));
    }

    @Test public void recentReportSurvivesUploadAcknowledgement() throws Exception {
        File dir = Files.createTempDirectory("afble-history").toFile();
        LogQueue queue = new LogQueue(new File(dir, "queue"), new File(dir, "history"));
        queue.append("2026-10-21T15:42:13.000Z ble_connect_started");
        queue.append("2026-10-21T15:42:15.000Z ble_failed");
        String beforeUpload = queue.recentReport(500);
        assertEquals(beforeUpload.indexOf("ble_connect_started"),
                beforeUpload.lastIndexOf("ble_connect_started"));

        LogQueue.Snapshot sent = queue.snapshot();
        queue.acknowledge(sent.throughSequence);
        assertEquals(0, queue.snapshot().queuedCount);

        String afterUpload = queue.recentReport(500);
        assertTrue(afterUpload.contains("ble_connect_started\n"));
        assertTrue(afterUpload.contains("ble_failed\n"));
    }

    @Test public void recentReportFallsBackToUnsentQueueWithoutHistory() throws Exception {
        File dir = Files.createTempDirectory("afble-history-fallback").toFile();
        LogQueue queue = new LogQueue(new File(dir, "queue"));
        queue.append("2026-10-21T15:42:15.000Z ble_failed");
        String report = queue.recentReport(500);
        assertTrue(report.contains("ble_failed\n"));
    }

    @Test public void recentReportShowsNewestRowsChronologically() throws Exception {
        File dir = Files.createTempDirectory("afble-history-cap").toFile();
        LogQueue queue = new LogQueue(new File(dir, "queue"), new File(dir, "history"));
        for (int i = 0; i < 12; i++) {
            queue.append("2026-10-21T15:42:" + (i < 10 ? "0" : "") + i + ".000Z event-" + i);
        }
        String report = queue.recentReport(5);
        assertTrue(report.contains("Showing the most recent 5 of 12 app events."));
        assertTrue(report.contains("event-11\n"));
        assertTrue(!report.contains("event-6\n"));
        // Chronological order, not the lexicographic event-number order.
        assertTrue(report.indexOf("event-7\n") < report.indexOf("event-11\n"));
    }

    @Test public void historyIsTrimmedToItsByteBudget() throws Exception {
        File dir = Files.createTempDirectory("afble-history-trim").toFile();
        File history = new File(dir, "history");
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 3_000; i++) {
            oversized.append("2026-10-21T15:42:00.000Z old-event-").append(i).append('\n');
        }
        Files.write(history.toPath(), oversized.toString().getBytes(StandardCharsets.UTF_8));

        LogQueue queue = new LogQueue(new File(dir, "queue"), history);
        queue.append("2026-10-21T15:43:00.000Z newest-event");

        assertTrue(history.length() <= LogQueue.MAX_HISTORY_BYTES);
        String report = queue.recentReport(3);
        assertTrue(report.contains("newest-event\n"));
        assertTrue(!report.contains("old-event-0\n"));
    }
}
