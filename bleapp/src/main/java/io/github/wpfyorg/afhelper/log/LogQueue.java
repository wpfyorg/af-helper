package io.github.wpfyorg.afhelper.log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/** Small durable append queue. Each record is self-contained and acknowledged by sequence. */
final class LogQueue {
    static final int MAX_QUEUE_BYTES = 256 * 1024;
    static final int MAX_SNAPSHOT_BYTES = 512 * 1024;
    static final int MAX_HISTORY_BYTES = 128 * 1024;
    static final int MAX_HISTORY_ROWS = 2_000;
    private static final Object LOCK = new Object();
    private final File file;
    private final File historyFile;

    LogQueue(File file) { this(file, null); }

    LogQueue(File file, File historyFile) {
        this.file = file;
        this.historyFile = historyFile;
    }

    long append(String event) throws IOException {
      synchronized (LOCK) {
        long seq = read().nextSequence;
        String row = encode(seq, event);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(row.getBytes(StandardCharsets.US_ASCII));
            out.getFD().sync();
        }
        appendHistory(event);
        trim();
        return seq;
      }
    }

    Snapshot snapshot() throws IOException {
      synchronized (LOCK) {
        State state = read();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long last = 0;
        for (Entry entry : state.entries) {
            byte[] row = (entry.event + "\n").getBytes(StandardCharsets.UTF_8);
            if (out.size() + row.length > MAX_SNAPSHOT_BYTES) break;
            out.write(row);
            last = entry.sequence;
        }
        return new Snapshot(out.toByteArray(), last, state.entries.size());
      }
    }

    void acknowledge(long throughSequence) throws IOException {
      synchronized (LOCK) {
        State state = read();
        List<Entry> remain = new ArrayList<>();
        for (Entry entry : state.entries) if (entry.sequence > throughSequence) remain.add(entry);
        rewrite(remain);
      }
    }

    int size() throws IOException { synchronized (LOCK) { return read().entries.size(); } }

    /**
     * Read-only on-screen report that survives upload acknowledgement. History is a
     * best-effort convenience copy; unsent queue records are merged in so a failed
     * history write can never hide the events the uploader still owns.
     */
    String recentReport(int maxRows) throws IOException {
        synchronized (LOCK) {
            if (maxRows <= 0) return "";
            List<String> lines = new ArrayList<>(readHistoryLines());
            Set<String> seen = new HashSet<>(lines);
            for (Entry entry : read().entries) if (seen.add(entry.event)) lines.add(entry.event);
            if (lines.isEmpty()) return "";
            // Every event starts with a fixed UTC ISO-8601 timestamp, so lexical
            // order is chronological even when history and queue are merged.
            Collections.sort(lines);
            int from = Math.max(0, lines.size() - maxRows);
            StringBuilder report = new StringBuilder();
            report.append("Showing the most recent ").append(lines.size() - from)
                    .append(" of ").append(lines.size()).append(" app events.\n\n");
            for (int i = from; i < lines.size(); i++) report.append(lines.get(i)).append('\n');
            return report.toString();
        }
    }

    private void appendHistory(String event) {
        if (historyFile == null) return;
        try {
            try (FileOutputStream out = new FileOutputStream(historyFile, true)) {
                out.write((event + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (historyFile.length() > MAX_HISTORY_BYTES) trimHistory();
        } catch (IOException ignored) {
            // The durable upload queue remains authoritative if history cannot be written.
        }
    }

    private List<String> readHistoryLines() {
        List<String> lines = new ArrayList<>();
        if (historyFile == null || !historyFile.exists()) return lines;
        try (FileInputStream in = new FileInputStream(historyFile)) {
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) copy.write(buffer, 0, count);
            for (String line : new String(copy.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isEmpty()) lines.add(line);
            }
        } catch (IOException ignored) {
            // Fall back to the queue report rather than blocking the viewer.
        }
        return lines;
    }

    private void trimHistory() throws IOException {
        List<String> lines = readHistoryLines();
        int fromRow = Math.max(0, lines.size() - MAX_HISTORY_ROWS);
        int bytes = 0;
        int start = lines.size();
        for (int i = lines.size() - 1; i >= fromRow; i--) {
            int size = (lines.get(i) + "\n").getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > MAX_HISTORY_BYTES) break;
            bytes += size;
            start = i;
        }
        File tmp = new File(historyFile.getParentFile(), historyFile.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            for (int i = start; i < lines.size(); i++) {
                out.write((lines.get(i) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            out.getFD().sync();
        }
        if (!tmp.renameTo(historyFile)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Atomic log history replacement failed");
        }
    }

    private void trim() throws IOException {
        if (file.length() <= MAX_QUEUE_BYTES) return;
        State state = read();
        List<Entry> keep = new ArrayList<>();
        int bytes = 0;
        for (int i = state.entries.size() - 1; i >= 0; i--) {
            Entry e = state.entries.get(i);
            int n = serializedSize(e);
            if (bytes + n > MAX_QUEUE_BYTES) break;
            keep.add(0, e);
            bytes += n;
        }
        rewrite(keep);
    }

    private State read() throws IOException {
        List<Entry> entries = new ArrayList<>();
        long next = 1;
        if (file.exists()) {
            byte[] bytes;
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream copy = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) copy.write(buffer, 0, count);
                bytes = copy.toByteArray();
            }
            String text = new String(bytes, StandardCharsets.US_ASCII);
            ByteArrayOutputStream corrupt = new ByteArrayOutputStream();
            for (String line : text.split("\n", -1)) {
                if (line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                if (tab < 1) {
                    corrupt.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
                    continue;
                }
                try {
                    long seq = Long.parseLong(line.substring(0, tab));
                    next = Math.max(next, seq + 1);
                    int checksumSeparator = line.indexOf('\t', tab + 1);
                    if (checksumSeparator < 0) throw new IllegalArgumentException("missing checksum");
                    String event = new String(unhex(line.substring(tab + 1, checksumSeparator)),
                            StandardCharsets.UTF_8);
                    String checksum = line.substring(checksumSeparator + 1);
                    if (!checksum.equals(checksum(event))) throw new IllegalArgumentException("bad checksum");
                    entries.add(new Entry(seq, event));
                } catch (IllegalArgumentException ignored) {
                    corrupt.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
                }
            }
            if (corrupt.size() > 0) {
                quarantine(corrupt.toByteArray());
                rewrite(entries);
            }
        }
        return new State(entries, next);
    }

    private void rewrite(List<Entry> entries) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            for (Entry e : entries) {
                String row = encode(e.sequence, e.event);
                out.write(row.getBytes(StandardCharsets.US_ASCII));
            }
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            // Keep the original queue intact if the filesystem cannot atomically replace it.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Atomic log queue replacement failed");
        }
    }

    private void quarantine(byte[] damaged) throws IOException {
        File saved = new File(file.getParentFile(), file.getName() + ".corrupt");
        byte[] old = new byte[0];
        if (saved.exists()) {
            try (FileInputStream in = new FileInputStream(saved)) {
                ByteArrayOutputStream copy = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048]; int n;
                while ((n = in.read(buffer)) != -1) copy.write(buffer, 0, n);
                old = copy.toByteArray();
            }
        }
        if (endsWith(old, damaged)) return; // A failed primary rewrite may replay this same damaged suffix.
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        combined.write(old); combined.write(damaged);
        byte[] all = combined.toByteArray();
        if (all.length > MAX_QUEUE_BYTES) {
            byte[] tail = new byte[MAX_QUEUE_BYTES];
            System.arraycopy(all, all.length - tail.length, tail, 0, tail.length);
            all = tail;
        }
        File temp = new File(saved.getParentFile(), saved.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(all); out.getFD().sync(); }
        if (!temp.renameTo(saved)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Atomic damaged-log preservation failed");
        }
    }

    private static boolean endsWith(byte[] value, byte[] suffix) {
        if (suffix.length > value.length) return false;
        int offset = value.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) if (value[offset + i] != suffix[i]) return false;
        return true;
    }

    private static String encode(long sequence, String event) {
        return sequence + "\t" + hex(event.getBytes(StandardCharsets.UTF_8)) + "\t"
                + checksum(event) + "\n";
    }

    private static String checksum(String event) {
        CRC32 crc = new CRC32();
        crc.update(event.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            out[i * 2] = digits[b >>> 4]; out[i * 2 + 1] = digits[b & 15];
        }
        return new String(out);
    }

    private static int serializedSize(Entry entry) {
        int payloadLength = entry.event.getBytes(StandardCharsets.UTF_8).length;
        return Long.toString(entry.sequence).length() + 1 + payloadLength * 2 + 1
                + checksum(entry.event).length() + 1;
    }

    private static byte[] unhex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("bad hex");
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Read-only viewer report: count header plus the most recent event text. Never mutates the queue. */
    static String reportOf(byte[] body, int queuedCount, int maxRows) {
        if (queuedCount <= 0 || maxRows <= 0) return "";
        String[] rows = new String(body, StandardCharsets.UTF_8).split("\n");
        List<String> shown = new ArrayList<>();
        for (int i = rows.length - 1; i >= 0 && shown.size() < maxRows; i--) {
            if (!rows[i].isEmpty()) shown.add(rows[i]);
        }
        StringBuilder report = new StringBuilder();
        report.append(queuedCount).append(queuedCount == 1 ? " event queued.\n" : " events queued.\n");
        if (shown.size() < queuedCount) {
            report.append("Showing the most recent ").append(shown.size()).append(".\n");
        }
        report.append('\n');
        for (int i = shown.size() - 1; i >= 0; i--) report.append(shown.get(i)).append('\n');
        return report.toString();
    }

    static final class Snapshot {
        final byte[] body;
        final long throughSequence;
        final int queuedCount;
        Snapshot(byte[] body, long throughSequence, int queuedCount) {
            this.body = body; this.throughSequence = throughSequence; this.queuedCount = queuedCount;
        }
    }
    private static final class Entry {
        final long sequence; final String event;
        Entry(long sequence, String event) { this.sequence = sequence; this.event = event; }
    }
    private static final class State {
        final List<Entry> entries; final long nextSequence;
        State(List<Entry> entries, long nextSequence) { this.entries = entries; this.nextSequence = nextSequence; }
    }
}
