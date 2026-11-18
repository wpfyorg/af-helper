package io.github.wpfyorg.afhelper.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manual, time-bounded, unfiltered nearby BLE scan. */
public final class BleScannerController {
    public static final long SCAN_WINDOW_MS = 20_000L;
    public static final long TARGETED_SCAN_WINDOW_MS = 8_000L;

    public interface Listener {
        void onDevice(Device device);
        void onScanState(boolean scanning, String message);
        void onError(String message);
    }

    public static final class Device {
        public final BluetoothDevice handle;
        public final String name;
        public final int rssi;

        Device(BluetoothDevice handle, String name, int rssi) {
            this.handle = handle;
            this.name = name == null || name.trim().isEmpty() ? null : name.trim();
            this.rssi = rssi;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final ScanGeneration generation = new ScanGeneration();
    private Listener listener;
    private BluetoothLeScanner scanner;
    private ScanAttempt activeAttempt;
    private Runnable activeTimeout;
    private boolean scanning;
    private String targetAddress;

    private final class ScanAttempt extends ScanCallback {
        final long id;
        ScanAttempt(long id) { this.id = id; }

        @Override public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> handleResult(this, result));
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult result : results) main.post(() -> handleResult(this, result));
        }

        @Override public void onScanFailed(int errorCode) {
            main.post(() -> finishAttempt(this, "Scan failed (" + errorCode + ")", true));
        }
    }

    public BleScannerController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void startScan(boolean clearResults) {
        startScanInternal(clearResults, null);
    }

    /**
     * Looks only for one Bluetooth address, for example the address printed on
     * the ODU sticker. The address is used as a scan filter and is never shown
     * or logged; the first matching result is reported through {@link
     * Listener#onDevice} and the scan stops.
     */
    @SuppressLint("MissingPermission")
    public void startTargetedScan(String address) {
        String normalized = address == null ? "" : address.trim().toUpperCase(java.util.Locale.US);
        if (normalized.isEmpty()) {
            notifyError("That QR code did not contain an ODU Bluetooth address");
            return;
        }
        startScanInternal(false, normalized);
    }

    @SuppressLint("MissingPermission")
    private void startScanInternal(boolean clearResults, String address) {
        stopScan(null);
        targetAddress = address;
        BluetoothManager manager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            targetAddress = null;
            notifyError("Turn Bluetooth on to scan");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            targetAddress = null;
            notifyError("BLE scanning is unavailable on this device");
            return;
        }
        if (clearResults) devices.clear();
        ScanAttempt attempt = new ScanAttempt(generation.begin());
        activeAttempt = attempt;
        scanning = true;
        try {
            if (address == null) {
                scanner.startScan(attempt);
            } else {
                List<ScanFilter> filters = new ArrayList<>();
                filters.add(new ScanFilter.Builder().setDeviceAddress(address).build());
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                scanner.startScan(filters, settings, attempt);
            }
            boolean targeted = address != null;
            activeTimeout = () -> finishAttempt(attempt,
                    targeted ? "ODU not found nearby" : "Scan timed out", false);
            main.postDelayed(activeTimeout,
                    targeted ? TARGETED_SCAN_WINDOW_MS : SCAN_WINDOW_MS);
            if (listener != null) {
                listener.onScanState(true, targeted
                        ? "Looking for the ODU from the sticker"
                        : "Scanning nearby devices");
            }
        } catch (SecurityException e) {
            targetAddress = null;
            finishAttempt(attempt, "Bluetooth scan permission is required", true);
        } catch (RuntimeException e) {
            targetAddress = null;
            finishAttempt(attempt, "Could not start Bluetooth scanning", true);
        }
    }

    public void refresh() { startScan(true); }

    @SuppressLint("MissingPermission")
    public void stopScan(String message) {
        ScanAttempt attempt = activeAttempt;
        boolean wasScanning = scanning;
        scanning = false;
        activeAttempt = null;
        targetAddress = null;
        if (attempt != null) generation.end(attempt.id);
        if (activeTimeout != null) main.removeCallbacks(activeTimeout);
        activeTimeout = null;
        if (wasScanning && scanner != null && attempt != null) {
            try {
                scanner.stopScan(attempt);
            } catch (SecurityException ignored) {
                // Permission loss must not prevent controller cleanup.
            } catch (RuntimeException ignored) {
            }
        }
        if ((wasScanning || message != null) && listener != null) {
            listener.onScanState(false, message == null ? "Scan stopped" : message);
        }
    }

    public List<Device> snapshot() {
        List<Device> snapshot = new ArrayList<>(devices.values());
        Collections.sort(snapshot, Comparator.comparingInt((Device d) -> d.rssi).reversed());
        return snapshot;
    }

    public boolean isScanning() { return scanning; }

    public void close() {
        stopScan(null);
        listener = null;
        devices.clear();
        scanner = null;
    }

    private void notifyError(String message) {
        if (listener != null) listener.onError(message);
    }

    private boolean isCurrent(ScanAttempt attempt) {
        return scanning && activeAttempt == attempt && generation.isCurrent(attempt.id);
    }

    @SuppressLint("MissingPermission")
    private void handleResult(ScanAttempt attempt, ScanResult result) {
        if (!isCurrent(attempt) || result == null) return;
        BluetoothDevice handle = result.getDevice();
        if (handle == null) return;
        String address;
        try {
            address = handle.getAddress();
        } catch (SecurityException e) {
            notifyError("Bluetooth permission was revoked");
            finishAttempt(attempt, "Scan stopped", false);
            return;
        }
        String name = result.getScanRecord() == null
                ? null : result.getScanRecord().getDeviceName();
        Device device = new Device(handle, name, result.getRssi());
        devices.put(address, device);
        boolean targeted = targetAddress != null
                && targetAddress.equalsIgnoreCase(address);
        if (targeted) finishAttempt(attempt, "ODU found", false);
        if (listener != null) listener.onDevice(device);
    }

    @SuppressLint("MissingPermission")
    private void finishAttempt(ScanAttempt attempt, String message, boolean error) {
        if (!isCurrent(attempt)) return;
        scanning = false;
        activeAttempt = null;
        targetAddress = null;
        generation.end(attempt.id);
        if (activeTimeout != null) main.removeCallbacks(activeTimeout);
        activeTimeout = null;
        if (scanner != null) {
            try { scanner.stopScan(attempt); }
            catch (RuntimeException ignored) {}
        }
        if (listener != null) {
            listener.onScanState(false, message);
            if (error) listener.onError("Could not scan for nearby Bluetooth devices");
        }
    }
}
