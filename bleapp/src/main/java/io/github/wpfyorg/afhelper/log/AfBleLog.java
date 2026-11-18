package io.github.wpfyorg.afhelper.log;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Privacy-minimal, on-device event log. Nothing here is ever uploaded. */
public final class AfBleLog {
    public static final int BLE_CODE_UNAVAILABLE = Integer.MIN_VALUE;
    private static final String[] EVENTS = {
            "app_open", "app_background", "app_closed", "scan_started", "scan_succeeded",
            "scan_failed", "ble_connect_started", "ble_connected", "ble_failed",
            "ble_disconnect_requested",
            "provision_started", "provision_succeeded", "provision_failed",
            "provision_failed_auth", "provision_failed_family", "provision_failed_route",
            "provision_failed_tls", "provision_failed_http", "provision_failed_other",
            "webui_check_started", "webui_check_succeeded", "webui_check_failed",
            "webui_disable_started", "webui_disable_succeeded", "webui_disable_failed",
            "signal_started", "signal_succeeded", "signal_failed",
            "esim_list_started", "esim_list_succeeded", "esim_list_failed",
            "esim_install_started", "esim_install_succeeded", "esim_install_failed",
            "esim_delete_started", "esim_delete_succeeded", "esim_delete_failed",
            "esim_enable_started", "esim_enable_succeeded", "esim_enable_failed",
            "esim_disable_started", "esim_disable_succeeded", "esim_disable_failed",
            "esim_code_started", "esim_code_succeeded", "esim_code_failed",
            "esim_reboot_started", "esim_reboot_succeeded", "esim_reboot_failed",
            "odu_details_started", "odu_details_succeeded", "odu_details_failed"
    };
    private static final String[] BLE_PHASES = {
            "gatt_connect", "gatt_state", "service_discovery", "cccd_write",
            "discovery_write", "bond_state", "pan_read", "pan_result"
    };
    private static final String[] BLE_RESULTS = {
            "started", "connected", "disconnected", "success", "failed", "unsupported",
            "missing_characteristics", "bonding", "bonded", "unbonded", "existing_bond",
            "bond_lost_remote", "bonded_poll", "pairing_stall",
            "request_started", "request_failed",
            "read_started", "read_failed", "read_timeout", "valid_address", "invalid_address", "empty",
            "invalid_ipv4_format", "ipv4_outside_policy", "pan_interface_missing",
            "pan_interface_unavailable", "ambiguous_route", "busy",
            "tethering_off", "timed_out", "error"
    };
    private static final String[] PAN_CATEGORIES = {
            "empty", "busy", "tethering_off", "invalid_ipv4_format",
            "ipv4_outside_policy", "pan_interface_missing", "pan_interface_unavailable",
            "ambiguous_route", "valid_address"
    };

    private AfBleLog() { }

    /** Call once from Application.onCreate(). No device data or arbitrary message is accepted. */
    public static void initialize(Context context) { record(context, "app_open"); }

    /** Records only a fixed event name. Never pass IDs, labels, addresses, URLs, or payloads. */
    public static void record(Context context, String event) {
        if (!knownEvent(event)) return;
        String stamp = timestamp();
        try {
            queue(context).append(stamp + " " + event);
        } catch (IOException | RuntimeException ignored) { /* Logging cannot break app behavior. */ }
    }

    /** Call from Activity.onStop() when the app has no foreground UI. */
    public static void onBackground(Context context) {
        record(context, "app_background");
    }

    /** Read-only recent-event report for the in-app viewer. It never mutates the log. */
    public static String recentLogReport(Context context, int maxRows) throws IOException {
        return queue(context).recentReport(maxRows);
    }

    /** Records fixed BLE handshake phases, numeric platform status, and coarse result only. */
    public static void recordBleDiagnostic(Context context, String phase, int code, String result) {
        if (!contains(BLE_PHASES, phase) || !contains(BLE_RESULTS, result)) return;
        String value = code == BLE_CODE_UNAVAILABLE ? "na" : Integer.toString(code);
        try {
            queue(context).append(timestamp() + " ble_diag phase=" + phase
                    + " code=" + value + " result=" + result);
        } catch (IOException | RuntimeException ignored) { /* Diagnostics cannot break the handshake. */ }
    }

    /** Logs only PAN payload byte count and an allow-listed classification. */
    public static void recordPanDiagnostic(Context context, int payloadBytes, String category) {
        if (payloadBytes < 0 || !contains(PAN_CATEGORIES, category)) return;
        try {
            queue(context).append(timestamp() + " ble_diag phase=pan_result code=na bytes="
                    + payloadBytes + " result=" + category);
        } catch (IOException | RuntimeException ignored) { /* Diagnostics cannot break the handshake. */ }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US) {
            { setTimeZone(TimeZone.getTimeZone("UTC")); }
        }.format(new Date());
    }

    private static boolean contains(String[] values, String candidate) {
        if (candidate == null) return false;
        for (String value : values) if (value.equals(candidate)) return true;
        return false;
    }

    static LogQueue queue(Context c) {
        File dir = c.getFilesDir();
        return new LogQueue(new File(dir, "afble-log-queue"),
                new File(dir, "afble-log-history"));
    }

    private static boolean knownEvent(String event) {
        if (event == null) return false;
        for (String allowed : EVENTS) if (allowed.equals(event)) return true;
        return false;
    }
}
