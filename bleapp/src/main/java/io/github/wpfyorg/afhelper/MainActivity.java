package io.github.wpfyorg.afhelper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.wpfyorg.afhelper.ble.BleScannerController;
import io.github.wpfyorg.afhelper.ble.OduBleSession;
import io.github.wpfyorg.afhelper.log.AfBleLog;
import io.github.wpfyorg.afhelper.net.OduProtocol;
import io.github.wpfyorg.afhelper.net.OduQrCode;
import io.github.wpfyorg.afhelper.net.OduWebActions;
import io.github.wpfyorg.afhelper.net.OduWebUiController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 4201;
    private static final int REQUEST_ENABLE_BLUETOOTH = 4202;
    private static final int REQUEST_LOCATION_SETTINGS = 4203;
    private static final int REQUEST_QR_SCAN = 4204;

    private static final int INK = Color.rgb(11, 26, 32);
    private static final int MUTED = Color.rgb(92, 111, 114);
    private static final int SIGNAL = Color.rgb(14, 124, 134);
    private static final int WARN = Color.rgb(196, 138, 33);
    private static final int ALERT = Color.rgb(179, 64, 46);
    private static final int SURFACE = Color.WHITE;
    private static final int PAPER = Color.rgb(242, 244, 243);
    private static final int LINE = Color.rgb(219, 226, 226);
    private static final int QUIET = Color.rgb(230, 238, 238);
    private static final int HEAD_SUB = Color.rgb(150, 173, 176);
    private static final int HEAD_ACCENT = Color.rgb(119, 204, 207);
    private static final int PANEL = Color.rgb(246, 249, 249);

    private LinearLayout deviceList;
    private LinearLayout devicesSection;
    private LinearLayout dashboardSection;
    private LinearLayout detailsCard;
    private LinearLayout profilesCard;
    private LinearLayout signalCard;
    private LinearLayout powerCard;
    private LinearLayout detailsValues;
    private LinearLayout profileList;
    private LinearLayout signalValues;
    private LinearLayout signalMeterBlock;
    private LinearLayout signalMeter;
    private TextView signalMeterValue;
    private final List<View> signalMeterSegments = new ArrayList<>();
    private TextView statusText;
    private TextView statusDetail;
    private TextView scanState;
    private TextView bluetoothState;
    private TextView connectionIndicator;
    private TextView deviceCount;
    private TextView webUiState;
    private TextView tetheringState;
    private TextView detailsState;
    private TextView modelValue;
    private TextView softwareValue;
    private TextView eidValue;
    private TextView activeProfile;
    private TextView signalQuality;
    private TextView signalHint;
    private Button scanButton;
    private Button stopButton;
    private Button scanRefreshButton;
    private Button qrScanButton;
    private Button webUiButton;
    private Button checkWebUiButton;
    private Button disableWebUiButton;
    private Button pingButton;
    private Button disconnectButton;
    private Button tetheringButton;
    private Button logSettingsButton;
    private Button signInButton;
    private Button refreshDetailsButton;
    private Button addProfileButton;
    private Button signalButton;
    private Button rebootButton;

    private BleScannerController scanner;
    private OduBleSession session;
    private OduWebUiController webUi;
    private OduWebActions actions;
    private BluetoothDevice sessionDevice;
    private String oduHost;
    private boolean connecting;
    private boolean supported;
    private boolean scanResultRecorded;
    private boolean actionRunning;
    private boolean signalLoaded;
    private String lastScanMessage = "Scanning is stopped";
    private String pendingAction;
    private OduWebActions.Family family;
    private OduWebActions.DeviceDetails details;
    private List<OduWebActions.ProfileRow> profileRows = new ArrayList<>();
    private String sessionUser;
    private char[] sessionPassword;
    private String qrModel;
    private String pendingQrTarget;
    private String pendingQrModel;
    private String webUiAction;
    private boolean webUiToggleSupported = true;
    private final List<BleScannerController.Device> seenDevices = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(INK);
        window.setNavigationBarColor(INK);
        window.getDecorView().setSystemUiVisibility(0);
        buildScreen();

        scanner = new BleScannerController(this, new BleScannerController.Listener() {
            @Override public void onDevice(BleScannerController.Device device) {
                int index = findDevice(device.handle);
                if (index >= 0) seenDevices.set(index, device);
                else seenDevices.add(device);
                if (!scanResultRecorded) {
                    scanResultRecorded = true;
                    record("scan_succeeded");
                }
                String address = deviceAddress(device.handle);
                if (pendingQrTarget != null && pendingQrTarget.equalsIgnoreCase(address)) {
                    qrModel = pendingQrModel;
                    pendingQrTarget = null;
                    pendingQrModel = null;
                    connect(device);
                    return;
                }
                scanButton.setText(seenDevices.isEmpty() ? "Scan for ODU" : "Scan again");
                refreshScanState();
                renderDevices();
            }

            @Override public void onScanState(boolean scanning, String message) {
                if (scanning) {
                    scanResultRecorded = false;
                    record("scan_started");
                    setDevicesVisible(true);
                } else if ("ODU not found nearby".equals(message)) {
                    pendingQrTarget = null;
                    pendingQrModel = null;
                    setStatus("No ODU answered for that sticker address. Move closer "
                            + "and scan again, or use Scan for ODU.", true);
                }
                lastScanMessage = message;
                scanButton.setText(scanning ? "Scanning…"
                        : (seenDevices.isEmpty() ? "Scan for ODU" : "Scan again"));
                if (scanning) fadeIn(stopButton); else fadeOutToGone(stopButton);
                refreshScanState();
                renderDevices();
                scanButton.setEnabled(!scanning);
                stopButton.setEnabled(scanning);
                scanRefreshButton.setEnabled(!scanning);
            }

            @Override public void onError(String message) {
                record("scan_failed");
                setStatus(message, true);
            }
        });
        session = new OduBleSession(this, new OduBleSession.Listener() {
            @Override public void onState(String message) { setStatus(message, false); }

            @Override public void onDiagnostic(String phase, int code, String result) {
                try {
                    AfBleLog.recordBleDiagnostic(MainActivity.this, phase, code, result);
                } catch (RuntimeException ignored) {
                    // A logging failure must never abort a Bluetooth callback.
                }
            }

            @Override public void onPanDiagnostic(int payloadBytes, String category) {
                try {
                    AfBleLog.recordPanDiagnostic(MainActivity.this, payloadBytes, category);
                } catch (RuntimeException ignored) {
                    // A logging failure must never abort a Bluetooth callback.
                }
            }

            @Override public void onSupported() {
                supported = true;
                refreshConnectionControls();
            }

            @Override public void onHostReady(String privateHost) {
                oduHost = privateHost;
                connecting = false;
                supported = true;
                record("ble_connected");
                setStatus("AirFiber ODU connected. Sign in to read its eSIM and signal.",
                        false);
                resetDashboard();
                refreshConnectionControls();
                refreshBluetoothState();
                if (hasBakedCredentials()) {
                    rememberSession(BuildConfig.ODU_ADMIN_USER,
                            BuildConfig.ODU_ADMIN_PASSWORD.toCharArray());
                    loadDetails();
                }
            }

            @Override public void onUnsupported(String message) {
                connecting = false;
                supported = false;
                oduHost = null;
                sessionDevice = null;
                if (actions != null) actions.cancel();
                clearSession();
                record("ble_failed");
                setStatus(message, true);
                refreshConnectionControls();
            }

            @Override public void onError(OduBleSession.ErrorKind kind, String message) {
                connecting = false;
                oduHost = null;
                supported = false;
                sessionDevice = null;
                webUi.cancelSession();
                if (actions != null) actions.cancel();
                clearSession();
                record("ble_failed");
                setStatus(message, true);
                if (kind == OduBleSession.ErrorKind.TETHERING_OFF) showTetheringPrompt();
                refreshConnectionControls();
            }
        });
        webUi = new OduWebUiController(this, new OduWebUiController.Listener() {
            @Override public void onState(String message) { setStatus(message, false); }

            @Override public void onReady(String privateHost) {
                webUiAction = null;
                record("provision_succeeded");
                updateWebUiBadge(true);
                setStatus("ODU WebUI is ready. Opening it in the app.", false);
                webUiButton.setText("Open WebUI again");
                openWebUiViewer(privateHost);
            }

            @Override public void onWebUiNotServed() {
                startWebUiWithCredentials();
            }

            @Override public void onWebUiState(boolean enabled) {
                updateWebUiBadge(enabled);
                if ("check".equals(webUiAction)) {
                    record("webui_check_succeeded");
                    webUiAction = null;
                    setStatus(enabled ? "ODU WebUI is enabled." : "ODU WebUI is disabled.",
                            false);
                }
            }

            @Override public void onWebUiToggleSupport(boolean supported) {
                webUiToggleSupported = supported;
                refreshDashboardControls();
            }

            @Override public void onOperationDone(boolean enabled) {
                updateWebUiBadge(enabled);
                if ("disable".equals(webUiAction)) record("webui_disable_succeeded");
                else if ("enable".equals(webUiAction)) record("provision_succeeded");
                webUiAction = null;
                setStatus(enabled ? "ODU confirmed the WebUI is enabled."
                        : "ODU confirmed the WebUI is disabled.", false);
            }

            @Override public void onPing(long latencyMs) {
                webUiAction = null;
                setStatus("ODU answered the link test in " + latencyMs + " ms.", false);
            }

            @Override public void onError(OduWebUiController.ErrorKind kind, String message) {
                if ("check".equals(webUiAction)) record("webui_check_failed");
                else if ("disable".equals(webUiAction)) record("webui_disable_failed");
                else if (!"ping".equals(webUiAction)) {
                    record("provision_failed");
                    record(provisionFailureEvent(kind));
                }
                webUiAction = null;
                setStatus(message, true);
                if (kind == OduWebUiController.ErrorKind.ROUTE_UNAVAILABLE) {
                    statusDetail.setText("Android does not enable ODU Bluetooth tethering through the app. Open Settings, turn it on if available, return here, and retry.");
                    statusDetail.setVisibility(View.VISIBLE);
                }
            }
        });
        actions = new OduWebActions(this, new OduWebActions.Listener() {
            @Override public void onState(String message) { setStatus(message, false); }

            @Override public void onDetails(OduWebActions.DeviceDetails loaded) {
                actionRunning = false;
                details = loaded;
                family = loaded.family;
                fillDetails(loaded);
                record("odu_details_succeeded");
                pendingAction = null;
                setStatus("ODU details loaded.", false);
                refreshDashboardControls();
                if (!signalLoaded) startSignal(true);
            }

            @Override public void onSignal(OduWebActions.SignalReport report) {
                actionRunning = false;
                signalLoaded = true;
                renderSignal(report);
                record("signal_succeeded");
                pendingAction = null;
                setStatus(report.qualityLabel(), false);
                refreshDashboardControls();
            }

            @Override public void onProfiles(String headline, List<OduWebActions.ProfileRow> rows) {
                actionRunning = false;
                profileRows = rows == null ? new ArrayList<>() : rows;
                renderProfiles();
                record("esim_list_succeeded");
                pendingAction = null;
                setStatus(headline, false);
                refreshDashboardControls();
            }

            @Override public void onProfileActionDone(String message,
                                                      List<OduWebActions.ProfileRow> rows) {
                actionRunning = false;
                profileRows = rows == null ? new ArrayList<>() : rows;
                renderProfiles();
                record(completedEvent());
                pendingAction = null;
                setStatus(message, false);
                refreshDashboardControls();
            }

            @Override public void onOperationDone(String message) {
                actionRunning = false;
                record(completedEvent());
                pendingAction = null;
                setStatus(message, false);
                refreshDashboardControls();
            }

            @Override public void onError(OduWebActions.ErrorKind kind, String message) {
                actionRunning = false;
                record(failedEvent());
                pendingAction = null;
                setStatus(message, true);
                if (kind == OduWebActions.ErrorKind.AUTH_REJECTED) signOutLocally();
                if (kind == OduWebActions.ErrorKind.ROUTE_UNAVAILABLE) {
                    statusDetail.setText("Android does not enable ODU Bluetooth tethering through the app. Open Settings, turn it on if available, return here, and retry.");
                    statusDetail.setVisibility(View.VISIBLE);
                }
                renderProfiles();
                refreshDashboardControls();
            }
        });
        refreshBluetoothState();
    }

    private void buildScreen() {
        boolean compact = compactLayout();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(INK);
        header.setPadding(dp(20), dp(20), dp(20), dp(22));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("AIRFIBER  /  LOCAL CONTROL", 10, HEAD_ACCENT, true);
        eyebrow.setLetterSpacing(.10f);
        titleBlock.addView(eyebrow);
        TextView appTitle = display("AF Helper", 26, Color.WHITE);
        titleBlock.addView(appTitle, marginTop(appTitle, 4));
        if (compact) titleRow.addView(titleBlock);
        else titleRow.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        logSettingsButton = quietButton("Logs", true);
        logSettingsButton.setOnClickListener(v -> showLog());
        if (compact) titleRow.addView(logSettingsButton, marginTop(logSettingsButton, 10));
        else titleRow.addView(logSettingsButton);
        header.addView(titleRow);
        TextView tag = text("Connect to your ODU, then manage its SIM, signal, and WebUI.",
                14, HEAD_SUB, false);
        tag.setLineSpacing(dp(2), 1f);
        header.addView(tag, marginTop(tag, 12));
        page.addView(header);

        LinearLayout phoneRow = new LinearLayout(this);
        phoneRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        phoneRow.setGravity(compact ? Gravity.START : Gravity.CENTER_VERTICAL);
        LinearLayout phoneInfo = new LinearLayout(this);
        phoneInfo.setOrientation(LinearLayout.VERTICAL);
        phoneInfo.addView(text("PHONE CONNECTION", 11, MUTED, true));
        bluetoothState = display("Checking Bluetooth…", 16, INK);
        phoneInfo.addView(bluetoothState, marginTop(bluetoothState, 3));
        tetheringState = text("Checking Bluetooth tethering…", 12, MUTED, false);
        phoneInfo.addView(tetheringState, marginTop(tetheringState, 3));
        if (compact) phoneRow.addView(phoneInfo);
        else phoneRow.addView(phoneInfo, new LinearLayout.LayoutParams(0, -2, 1));
        tetheringButton = quietButton("Tethering settings", false);
        tetheringButton.setOnClickListener(v -> openTetheringSettings());
        if (compact) phoneRow.addView(tetheringButton, marginTop(tetheringButton, 10));
        else phoneRow.addView(tetheringButton);
        LinearLayout phoneCard = card();
        phoneCard.addView(phoneRow);
        page.addView(phoneCard, padded(14));

        scanButton = button("Scan for ODU", true);
        scanButton.setOnClickListener(v -> startScan());
        stopButton = button("Stop scan", false);
        stopButton.setEnabled(false);
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> scanner.stopScan("Scan stopped"));
        scanRefreshButton = button("Refresh", false);
        scanRefreshButton.setContentDescription("Clear results and scan again");
        scanRefreshButton.setOnClickListener(v -> refreshScan());
        LinearLayout scanActions = new LinearLayout(this);
        scanActions.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (compact) {
            scanActions.addView(scanButton, new LinearLayout.LayoutParams(-1, dp(52)));
            scanActions.addView(scanRefreshButton, marginTop(scanRefreshButton, 8));
        } else {
            scanActions.addView(scanButton, new LinearLayout.LayoutParams(0, dp(52), 1));
            LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(104), dp(52));
            refreshParams.leftMargin = dp(10);
            scanActions.addView(scanRefreshButton, refreshParams);
        }
        page.addView(scanActions, padded(14));
        LinearLayout stickerActions = new LinearLayout(this);
        stickerActions.setOrientation(compactLayout()
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        qrScanButton = quietButton("Scan sticker QR", false);
        qrScanButton.setOnClickListener(v -> startQrScan());
        Button pasteSticker = quietButton("Paste sticker text", false);
        pasteSticker.setOnClickListener(v -> showPasteStickerDialog());
        if (compactLayout()) {
            stickerActions.addView(qrScanButton, new LinearLayout.LayoutParams(-1, -2));
            stickerActions.addView(pasteSticker, marginTop(pasteSticker, 8));
        } else {
            stickerActions.addView(qrScanButton, new LinearLayout.LayoutParams(0, -2, 3));
            LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(0, -2, 2);
            pasteParams.leftMargin = dp(8);
            stickerActions.addView(pasteSticker, pasteParams);
        }
        page.addView(stickerActions, padded(10));
        page.addView(stopButton, padded(8));

        scanState = text("Scanning is stopped", 13, MUTED, false);
        scanState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(scanState, padded(10));

        LinearLayout statusCard = card();
        LinearLayout statusHeading = new LinearLayout(this);
        statusHeading.setGravity(Gravity.CENTER_VERTICAL);
        statusHeading.addView(text("CONNECTION", 11, MUTED, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        connectionIndicator = text("●", 16, MUTED, false);
        connectionIndicator.setContentDescription("Not connected");
        statusHeading.addView(connectionIndicator);
        statusCard.addView(statusHeading);
        statusText = display("Ready to connect", 17, INK);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusCard.addView(statusText, marginTop(statusText, 8));
        statusDetail = text("Scan and select an ODU to get started.", 13, MUTED, false);
        statusCard.addView(statusDetail, marginTop(statusDetail, 6));
        webUiState = data("WebUI state not checked", 12, MUTED);
        webUiState.setVisibility(View.GONE);
        statusCard.addView(webUiState, marginTop(webUiState, 8));
        LinearLayout webUiTools = new LinearLayout(this);
        webUiTools.setOrientation(LinearLayout.HORIZONTAL);
        checkWebUiButton = quietButton("Check WebUI", false);
        checkWebUiButton.setVisibility(View.GONE);
        checkWebUiButton.setOnClickListener(v -> checkWebUi());
        webUiTools.addView(checkWebUiButton, new LinearLayout.LayoutParams(0, -2, 1));
        pingButton = quietButton("Test link", false);
        pingButton.setVisibility(View.GONE);
        pingButton.setOnClickListener(v -> testLink());
        LinearLayout.LayoutParams pingParams = new LinearLayout.LayoutParams(0, -2, 1);
        pingParams.leftMargin = dp(8);
        webUiTools.addView(pingButton, pingParams);
        statusCard.addView(webUiTools, marginTop(webUiTools, 8));
        disableWebUiButton = quietButton("Disable WebUI", false);
        disableWebUiButton.setVisibility(View.GONE);
        disableWebUiButton.setOnClickListener(v -> confirmDisableWebUi());
        statusCard.addView(disableWebUiButton, marginTop(disableWebUiButton, 8));
        webUiButton = button("Enable WebUI and open", true);
        webUiButton.setVisibility(View.GONE);
        webUiButton.setOnClickListener(v -> startWebUi());
        statusCard.addView(webUiButton, marginTop(webUiButton, 12));
        disconnectButton = button("Disconnect", false);
        disconnectButton.setVisibility(View.GONE);
        disconnectButton.setOnClickListener(v -> disconnect());
        statusCard.addView(disconnectButton, marginTop(disconnectButton, 8));
        page.addView(statusCard, padded(16));

        buildDashboard(page);

        devicesSection = new LinearLayout(this);
        devicesSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nearbyRow = new LinearLayout(this);
        nearbyRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        nearbyRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView nearby = display("Nearby devices", 18, INK);
        if (compact) nearbyRow.addView(nearby);
        else nearbyRow.addView(nearby, new LinearLayout.LayoutParams(0, -2, 1));
        deviceCount = data("0 FOUND", 12, MUTED);
        if (compact) nearbyRow.addView(deviceCount, marginTop(deviceCount, 4));
        else nearbyRow.addView(deviceCount);
        devicesSection.addView(nearbyRow, padded(20));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        devicesSection.addView(deviceList, padded(4));
        page.addView(devicesSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.setPadding(0, 0, 0, dp(28));
        setContentView(scroll);
        renderDevices();
    }

    private void buildDashboard(LinearLayout page) {
        boolean compact = compactLayout();
        dashboardSection = new LinearLayout(this);
        dashboardSection.setOrientation(LinearLayout.VERTICAL);

        detailsCard = card();
        LinearLayout detailsHeading = new LinearLayout(this);
        detailsHeading.setGravity(Gravity.CENTER_VERTICAL);
        detailsHeading.addView(text("ODU DETAILS", 11, MUTED, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        detailsState = data("NOT SIGNED IN", 11, MUTED);
        detailsHeading.addView(detailsState);
        detailsCard.addView(detailsHeading);
        detailsCard.addView(text(
                "Sign in to read the eSIM ID, installed profiles, and signal from this ODU.",
                13, MUTED, false), marginTop(detailsHeading, 6));
        signInButton = button("Sign in to ODU", true);
        signInButton.setOnClickListener(v -> promptSignIn());
        detailsCard.addView(signInButton, marginTop(signInButton, 12));
        detailsValues = new LinearLayout(this);
        detailsValues.setOrientation(LinearLayout.VERTICAL);
        detailsValues.setVisibility(View.GONE);
        modelValue = addValueRow(detailsValues, "Model");
        softwareValue = addValueRow(detailsValues, "Software");
        LinearLayout eidBlock = new LinearLayout(this);
        eidBlock.setOrientation(LinearLayout.VERTICAL);
        eidBlock.addView(text("EID", 11, MUTED, true));
        eidValue = text("Not reported", 13, INK, false);
        eidValue.setTypeface(Typeface.MONOSPACE);
        eidValue.setTextIsSelectable(true);
        eidBlock.addView(eidValue, marginTop(eidValue, 3));
        Button copyEid = quietButton("Copy EID", false);
        copyEid.setOnClickListener(v -> copyEid());
        eidBlock.addView(copyEid, marginTop(copyEid, 8));
        detailsValues.addView(eidBlock, marginTop(eidBlock, 12));
        detailsCard.addView(detailsValues, marginTop(detailsValues, 12));
        refreshDetailsButton = quietButton("Refresh ODU details", false);
        refreshDetailsButton.setVisibility(View.GONE);
        refreshDetailsButton.setOnClickListener(v -> withSession(
                "Refresh ODU details",
                "Sign in to reload the ODU details.",
                (username, password) -> startDetails(username, password)));
        detailsCard.addView(refreshDetailsButton, marginTop(refreshDetailsButton, 10));
        TextView privacy = text(
                "Credentials stay in memory only and are cleared on disconnect.",
                11, MUTED, false);
        detailsCard.addView(privacy, marginTop(privacy, 8));
        dashboardSection.addView(detailsCard, padded(0));

        profilesCard = card();
        LinearLayout profilesHeading = new LinearLayout(this);
        profilesHeading.setGravity(Gravity.CENTER_VERTICAL);
        profilesHeading.addView(text("SIM & eSIM", 11, MUTED, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        profilesCard.addView(profilesHeading);
        activeProfile = display("No profile information yet", 15, INK);
        profilesCard.addView(activeProfile, marginTop(activeProfile, 6));
        profileList = new LinearLayout(this);
        profileList.setOrientation(LinearLayout.VERTICAL);
        profilesCard.addView(profileList, marginTop(profileList, 4));
        addProfileButton = button("Add eSIM profile", true);
        addProfileButton.setOnClickListener(v -> startAddProfile());
        profilesCard.addView(addProfileButton, marginTop(addProfileButton, 10));
        profilesCard.setVisibility(View.GONE);
        dashboardSection.addView(profilesCard, padded(12));

        signalCard = card();
        LinearLayout signalHeading = new LinearLayout(this);
        signalHeading.setGravity(Gravity.CENTER_VERTICAL);
        signalHeading.addView(text("SIGNAL & CELL", 11, MUTED, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        signalButton = quietButton("Refresh", false);
        signalButton.setOnClickListener(v -> withSession(
                "Check the ODU signal",
                "Sign in to read the serving-cell signal report.",
                (username, password) -> startSignal(false, username, password)));
        signalHeading.addView(signalButton);
        signalCard.addView(signalHeading);
        signalQuality = display("Not read yet", 16, INK);
        signalCard.addView(signalQuality, marginTop(signalQuality, 6));
        signalHint = text("Signal values come from the ODU's serving cell.",
                12, MUTED, false);
        signalCard.addView(signalHint, marginTop(signalHint, 4));
        buildSignalMeter();
        signalCard.addView(signalMeterBlock, marginTop(signalMeterBlock, 12));
        signalValues = new LinearLayout(this);
        signalValues.setOrientation(LinearLayout.VERTICAL);
        signalCard.addView(signalValues, marginTop(signalValues, 8));
        signalValues.setVisibility(View.GONE);
        signalCard.setVisibility(View.GONE);
        dashboardSection.addView(signalCard, padded(12));

        powerCard = card();
        powerCard.addView(text("ODU POWER", 11, MUTED, true));
        powerCard.addView(text(
                "Rebooting restarts the ODU. Its Bluetooth link and data connection drop "
                        + "until it is back online.",
                12, MUTED, false), marginTop(powerCard, 6));
        rebootButton = quietButton("Reboot ODU", false);
        rebootButton.setOnClickListener(v -> confirmReboot());
        powerCard.addView(rebootButton, marginTop(rebootButton, 10));
        powerCard.setVisibility(View.GONE);
        dashboardSection.addView(powerCard, padded(12));

        dashboardSection.setVisibility(View.GONE);
        page.addView(dashboardSection, padded(16));
        if (compact) {
            // The dashboard is already vertical; nothing extra to lay out.
        }
    }

    private TextView addValueRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 12, MUTED, false);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = data("Not reported", 13, INK);
        value.setGravity(Gravity.END);
        row.addView(value);
        parent.addView(row, marginTop(row, 4));
        return value;
    }

    @SuppressLint("MissingPermission") // ensureBluetoothPermissions() runs first; the intent is guarded.
    private void startScan() {
        pendingQrTarget = null;
        pendingQrModel = null;
        if (!ensureBluetoothPermissions()) return;
        if (!bluetoothEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BLUETOOTH);
            } catch (RuntimeException e) {
                setStatus("Turn Bluetooth on in Android Settings, then scan again.", true);
            }
            return;
        }
        if (Build.VERSION.SDK_INT <= 30 && !locationEnabled()) {
            setStatus("Android location services must be on for BLE scanning on this Android version.",
                    true);
            try {
                startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                        REQUEST_LOCATION_SETTINGS);
            } catch (RuntimeException ignored) {}
            return;
        }
        setStatus("Scanning for nearby devices…", false);
        scanner.startScan(false);
    }

    private void refreshScan() {
        pendingQrTarget = null;
        pendingQrModel = null;
        if (!ensureBluetoothPermissions()) return;
        if (!bluetoothEnabled()) {
            startScan();
            return;
        }
        if (Build.VERSION.SDK_INT <= 30 && !locationEnabled()) {
            startScan();
            return;
        }
        seenDevices.clear();
        renderDevices();
        setStatus("Scanning for nearby devices…", false);
        scanner.refresh();
    }

    private boolean ensureBluetoothPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (missing.isEmpty()) return true;
        requestPermissions(missing.toArray(new String[0]), REQUEST_BLUETOOTH);
        return false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                    int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (pendingQrTarget != null) {
                if (!bluetoothEnabled()) {
                    pendingQrTarget = null;
                    pendingQrModel = null;
                    setStatus("Turn Bluetooth on in Android Settings, then scan the sticker again.",
                            true);
                } else {
                    setStatus("Looking for the ODU from the sticker…", false);
                    scanner.startTargetedScan(pendingQrTarget);
                }
            } else {
                startScan();
            }
        } else {
            pendingQrTarget = null;
            pendingQrModel = null;
            setStatus("Bluetooth scan/connect permission is needed to use this app.", true);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean bluetoothEnabled() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean locationEnabled() {
        try {
            LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (location == null) return false;
            if (Build.VERSION.SDK_INT >= 28) return location.isLocationEnabled();
            return location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void refreshBluetoothState() {
        boolean enabled = bluetoothEnabled();
        bluetoothState.setText(enabled ? "Bluetooth ready" : "Bluetooth is off");
        bluetoothState.setTextColor(enabled ? SIGNAL : ALERT);
        if (tetheringState != null) {
            boolean tethering = isBluetoothTetheringOn();
            if (tethering) {
                tetheringState.setText("Bluetooth tethering route active");
                tetheringState.setTextColor(SIGNAL);
            } else if (oduHost == null) {
                tetheringState.setText(
                        "Bluetooth tethering is needed after the ODU connects");
                tetheringState.setTextColor(MUTED);
            } else {
                tetheringState.setText(
                        "No Bluetooth tethering route yet — open Settings");
                tetheringState.setTextColor(ALERT);
            }
        }
    }

    private static boolean isBluetoothTetheringOn() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                String name = network.getName() == null
                        ? "" : network.getName().toLowerCase(Locale.US);
                if ((name.startsWith("bt-pan") || name.startsWith("bnep")
                        || name.startsWith("bt_pan")) && network.isUp()) {
                    return true;
                }
            }
        } catch (RuntimeException | java.net.SocketException ignored) {
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private void connect(BleScannerController.Device found) {
        if (!ensureBluetoothPermissions()) return;
        scanner.stopScan("Scan stopped for connection");
        pendingQrTarget = null;
        pendingQrModel = null;
        webUi.cancelSession();
        actions.cancel();
        clearSession();
        oduHost = null;
        sessionDevice = found.handle;
        connecting = true;
        supported = false;
        if (actions != null) actions.setAuthModel(qrModel);
        refreshConnectionControls();
        setStatus("Connecting and checking ODU support…", false);
        record("ble_connect_started");
        session.connect(found.handle);
    }

    private void disconnect() {
        record("ble_disconnect_requested");
        if (scanner != null) scanner.stopScan(null);
        if (webUi != null) webUi.cancelSession();
        if (actions != null) actions.cancel();
        if (session != null) session.disconnect();
        sessionDevice = null;
        oduHost = null;
        connecting = false;
        supported = false;
        actionRunning = false;
        pendingAction = null;
        webUiAction = null;
        qrModel = null;
        pendingQrTarget = null;
        pendingQrModel = null;
        webUiToggleSupported = true;
        if (actions != null) actions.setAuthModel(null);
        clearSession();
        resetDashboard();
        setStatus("Disconnected. Scan to connect again.", false);
        refreshConnectionControls();
        refreshBluetoothState();
    }

    private void resetDashboard() {
        details = null;
        family = null;
        profileRows = new ArrayList<>();
        signalLoaded = false;
        actionRunning = false;
        pendingAction = null;
        webUiToggleSupported = true;
        if (detailsValues != null) detailsValues.setVisibility(View.GONE);
        if (detailsState != null) {
            detailsState.setText("NOT SIGNED IN");
            detailsState.setTextColor(MUTED);
        }
        if (signInButton != null) signInButton.setVisibility(View.VISIBLE);
        if (refreshDetailsButton != null) refreshDetailsButton.setVisibility(View.GONE);
        if (profilesCard != null) profilesCard.setVisibility(View.GONE);
        if (signalCard != null) signalCard.setVisibility(View.GONE);
        if (powerCard != null) powerCard.setVisibility(View.GONE);
        if (eidValue != null) eidValue.setText("Not reported");
        if (modelValue != null) modelValue.setText("Not reported");
        if (softwareValue != null) softwareValue.setText("Not reported");
        if (signalQuality != null) signalQuality.setText("Not read yet");
        clearSignalMeter();
        if (signalValues != null) {
            signalValues.removeAllViews();
            signalValues.setVisibility(View.GONE);
        }
        if (profileList != null) profileList.removeAllViews();
        if (activeProfile != null) activeProfile.setText("No profile information yet");
        if (webUiState != null) {
            webUiState.setText("WebUI state not checked");
            webUiState.setTextColor(MUTED);
        }
    }

    private void refreshConnectionControls() {
        boolean active = connecting || supported || oduHost != null;
        if (active) fadeIn(disconnectButton); else fadeOutToGone(disconnectButton);
        if (oduHost == null) fadeOutToGone(webUiButton); else fadeIn(webUiButton);
        int webUiToolVisibility = oduHost == null ? View.GONE : View.VISIBLE;
        if (webUiState != null) webUiState.setVisibility(webUiToolVisibility);
        if (oduHost == null && webUiState != null) {
            webUiState.setText("WebUI state not checked");
            webUiState.setTextColor(MUTED);
        }
        if (checkWebUiButton != null) checkWebUiButton.setVisibility(webUiToolVisibility);
        if (pingButton != null) pingButton.setVisibility(webUiToolVisibility);
        if (disableWebUiButton != null) disableWebUiButton.setVisibility(webUiToolVisibility);
        if (dashboardSection != null) {
            if (oduHost == null) fadeOutToGone(dashboardSection); else fadeIn(dashboardSection);
        }
        if (connectionIndicator != null && active) {
            connectionIndicator.setTextColor(SIGNAL);
            connectionIndicator.setContentDescription(
                    oduHost == null ? "Connecting" : "ODU connected");
        }
        webUiButton.setEnabled(oduHost != null);
        scanButton.setEnabled(!connecting);
        scanRefreshButton.setEnabled(!connecting);
        setDevicesVisible(oduHost == null);
        refreshDashboardControls();
        renderDevices();
    }

    private void refreshDashboardControls() {
        if (signInButton == null) return;
        boolean connected = oduHost != null;
        boolean signedIn = sessionPassword != null;
        signInButton.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        signInButton.setEnabled(connected && !actionRunning);
        refreshDetailsButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        refreshDetailsButton.setEnabled(connected && signedIn && !actionRunning);
        addProfileButton.setEnabled(connected && signedIn && !actionRunning);
        signalButton.setEnabled(connected && signedIn && !actionRunning);
        rebootButton.setEnabled(connected && signedIn && !actionRunning
                && family == OduWebActions.Family.JFE);
        webUiButton.setEnabled(connected);
        if (checkWebUiButton != null) checkWebUiButton.setEnabled(connected && !actionRunning);
        if (pingButton != null) pingButton.setEnabled(connected && !actionRunning);
        if (disableWebUiButton != null) disableWebUiButton.setEnabled(connected && !actionRunning && webUiToggleSupported);
        if (activeProfile != null) activeProfile.setAlpha(actionRunning ? 0.6f : 1f);
    }

    private void setDevicesVisible(boolean visible) {
        if (devicesSection == null) return;
        if (visible) fadeIn(devicesSection); else fadeOutToGone(devicesSection);
    }

    private void fadeIn(View view) {
        view.animate().cancel();
        if (view.getVisibility() == View.VISIBLE && view.getAlpha() == 1f) return;
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate().alpha(1f).setDuration(180).start();
    }

    private void fadeOutToGone(View view) {
        view.animate().cancel();
        if (view.getVisibility() != View.VISIBLE) return;
        view.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> view.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------------------- ODU session

    private boolean hasBakedCredentials() {
        return !BuildConfig.ODU_ADMIN_USER.isEmpty()
                && !BuildConfig.ODU_ADMIN_PASSWORD.isEmpty();
    }

    private void promptSignIn() {
        withSession("Sign in to the ODU",
                "Sign in to read the eSIM ID, profiles, and signal.",
                (username, password) -> startDetails(username, password));
    }

    private void loadDetails() {
        if (sessionPassword == null) {
            promptSignIn();
            return;
        }
        startDetails(sessionUser, sessionPassword.clone());
    }

    private void startDetails(String username, char[] password) {
        if (oduHost == null) {
            password = clear(password);
            setStatus("Connect to a supported ODU first.", true);
            return;
        }
        pendingAction = "details";
        actionRunning = true;
        detailsState.setText("LOADING");
        detailsState.setTextColor(MUTED);
        record("odu_details_started");
        refreshDashboardControls();
        actions.loadDetails(oduHost, username, password);
    }

    private void fillDetails(OduWebActions.DeviceDetails loaded) {
        modelValue.setText(loaded.model == null ? "Not reported" : loaded.model);
        softwareValue.setText(loaded.software == null ? "Not reported" : loaded.software);
        eidValue.setText(loaded.eid == null
                ? "Not reported by this ODU" : loaded.eid);
        detailsState.setText(loaded.family == OduWebActions.Family.CM
                ? "SIGNED IN · CM" : "SIGNED IN · JFE");
        detailsState.setTextColor(SIGNAL);
        detailsValues.setVisibility(View.VISIBLE);
        signInButton.setVisibility(View.GONE);
        refreshDetailsButton.setVisibility(View.VISIBLE);
        profilesCard.setVisibility(View.VISIBLE);
        signalCard.setVisibility(View.VISIBLE);
        powerCard.setVisibility(loaded.family == OduWebActions.Family.JFE
                ? View.VISIBLE : View.GONE);
        addProfileButton.setText(loaded.family == OduWebActions.Family.CM
                ? "Add eSIM with activation code" : "Add default Jio eSIM");
        profileRows = loaded.profiles == null ? new ArrayList<>() : loaded.profiles;
        renderProfiles();
        refreshDashboardControls();
    }

    private void copyEid() {
        String eid = eidValue == null ? "" : eidValue.getText().toString();
        if (!eid.matches("[0-9]{32}")) {
            setStatus("No EID is available to copy yet.", true);
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("clipboard unavailable");
            clipboard.setPrimaryClip(ClipData.newPlainText("ODU EID", eid));
            setStatus("EID copied to the clipboard.", false);
        } catch (RuntimeException unavailable) {
            setStatus("Could not copy the EID on this device.", true);
        }
    }

    private interface CredentialsCallback {
        void use(String username, char[] password);
    }

    /** Reuses the in-memory session; otherwise asks once for the ODU credentials. */
    private void withSession(String title, String note, CredentialsCallback action) {
        if (oduHost == null) {
            setStatus("Connect to a supported ODU first.", true);
            return;
        }
        if (sessionPassword != null && sessionPassword.length > 0) {
            action.use(sessionUser, sessionPassword.clone());
            return;
        }
        if (hasBakedCredentials()) {
            rememberSession(BuildConfig.ODU_ADMIN_USER,
                    BuildConfig.ODU_ADMIN_PASSWORD.toCharArray());
            action.use(sessionUser, sessionPassword.clone());
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText user = new EditText(this);
        user.setSingleLine(true);
        user.setHint("ODU username");
        EditText pass = new EditText(this);
        pass.setSingleLine(true);
        pass.setHint("ODU password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(user);
        box.addView(pass);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("ODU address: " + oduHost + "\n" + note
                        + " Credentials stay in memory for this connection only.")
                .setView(box)
                .setPositiveButton("Continue", (dialog, which) -> {
                    String username = user.getText().toString().trim();
                    char[] password = pass.getText().toString().toCharArray();
                    user.setText("");
                    pass.setText("");
                    rememberSession(username, password);
                    action.use(sessionUser, sessionPassword.clone());
                    Arrays.fill(password, '\0');
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rememberSession(String username, char[] password) {
        clearSession();
        sessionUser = username;
        sessionPassword = password == null ? null : password.clone();
    }

    private void clearSession() {
        sessionUser = null;
        if (sessionPassword != null) {
            Arrays.fill(sessionPassword, '\0');
            sessionPassword = null;
        }
    }

    private void signOutLocally() {
        clearSession();
        family = null;
        profileRows = new ArrayList<>();
        if (detailsState != null) {
            detailsState.setText("NOT SIGNED IN");
            detailsState.setTextColor(MUTED);
        }
        if (detailsValues != null) detailsValues.setVisibility(View.GONE);
        if (profilesCard != null) profilesCard.setVisibility(View.GONE);
        if (signalCard != null) signalCard.setVisibility(View.GONE);
        clearSignalMeter();
        if (powerCard != null) powerCard.setVisibility(View.GONE);
        if (signInButton != null) signInButton.setVisibility(View.VISIBLE);
        if (refreshDetailsButton != null) refreshDetailsButton.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ signal

    private void startSignal(boolean automatic) {
        if (sessionPassword == null) {
            promptSignIn();
            return;
        }
        startSignal(automatic, sessionUser, sessionPassword.clone());
    }

    private void startSignal(boolean automatic, String username, char[] password) {
        if (oduHost == null) {
            password = clear(password);
            setStatus("Connect to a supported ODU first.", true);
            return;
        }
        pendingAction = "signal";
        actionRunning = true;
        signalHint.setText(automatic ? "Reading the serving cell…" : "Refreshing the serving cell…");
        record("signal_started");
        refreshDashboardControls();
        actions.checkSignal(oduHost, username, password);
    }

    private void renderSignal(OduWebActions.SignalReport report) {
        signalQuality.setText(report.qualityLabel());
        updateSignalMeter(report);
        signalValues.removeAllViews();
        addSignalRow("RSRP", report.rsrp == null
                ? "not reported" : String.format(Locale.US, "%.0f dBm", report.rsrp));
        addSignalRow("RSRQ", report.rsrq == null
                ? "not reported" : String.format(Locale.US, "%.0f dB", report.rsrq));
        addSignalRow("SINR", report.snr == null
                ? "not reported" : String.format(Locale.US, "%.1f dB", report.snr));
        addSignalRow("Band", report.band == null ? "not reported" : report.band);
        addSignalRow("Bandwidth", report.bandwidth == null
                ? "not reported" : report.bandwidth);
        addSignalRow("PCI", report.pci == null ? "not reported" : report.pci);
        addSignalRow("TAC", report.tac == null ? "not reported" : report.tac);
        addSignalRow("Cell ID", report.cellId == null ? "not reported" : report.cellId);
        signalValues.setVisibility(View.VISIBLE);
        signalHint.setText("Values come from the ODU's serving cell.");
    }

    private void addSignalRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(4), 0, dp(4));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label, 12, MUTED, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView readout = data(value, 13, INK);
        readout.setGravity(Gravity.END);
        row.addView(readout);
        signalValues.addView(row);
    }

    private void buildSignalMeter() {
        signalMeterBlock = new LinearLayout(this);
        signalMeterBlock.setOrientation(LinearLayout.VERTICAL);
        signalMeterBlock.setVisibility(View.GONE);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("SIGNAL STRENGTH", 10, MUTED, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        signalMeterValue = data("--", 12, MUTED);
        heading.addView(signalMeterValue);
        signalMeterBlock.addView(heading);

        signalMeter = new LinearLayout(this);
        signalMeter.setOrientation(LinearLayout.HORIZONTAL);
        signalMeter.setGravity(Gravity.CENTER_VERTICAL);
        signalMeterSegments.clear();
        for (int i = 0; i < 16; i++) {
            View segment = new View(this);
            segment.setBackground(meterSegment(LINE));
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, dp(16), 1f);
            if (i > 0) params.leftMargin = dp(2);
            signalMeter.addView(segment, params);
            signalMeterSegments.add(segment);
        }
        signalMeterBlock.addView(signalMeter, marginTop(signalMeter, 6));

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        TextView weak = text("WEAK", 9, MUTED, true);
        weak.setGravity(Gravity.START);
        TextView fair = text("FAIR", 9, MUTED, true);
        fair.setGravity(Gravity.CENTER);
        TextView strong = text("STRONG", 9, MUTED, true);
        strong.setGravity(Gravity.END);
        legend.addView(weak, new LinearLayout.LayoutParams(0, -2, 1));
        legend.addView(fair, new LinearLayout.LayoutParams(0, -2, 1));
        legend.addView(strong, new LinearLayout.LayoutParams(0, -2, 1));
        signalMeterBlock.addView(legend, marginTop(legend, 4));
    }

    private GradientDrawable meterSegment(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(2));
        return background;
    }

    private void updateSignalMeter(OduWebActions.SignalReport report) {
        if (signalMeterBlock == null || signalMeter == null) return;
        signalMeterBlock.setVisibility(View.VISIBLE);
        int percent = report.strengthPercent();
        if (percent < 0) {
            signalMeterValue.setText("Not reported");
            signalMeterValue.setTextColor(MUTED);
            for (View segment : signalMeterSegments) segment.setBackground(meterSegment(LINE));
            signalMeter.setContentDescription("Signal strength not reported");
            return;
        }
        int color = percent >= 65 ? SIGNAL : percent >= 35 ? WARN : ALERT;
        int filled = Math.max(1, Math.round(percent / 100f * signalMeterSegments.size()));
        for (int i = 0; i < signalMeterSegments.size(); i++) {
            signalMeterSegments.get(i).setBackground(meterSegment(i < filled ? color : LINE));
        }
        signalMeterValue.setText(percent + "%");
        signalMeterValue.setTextColor(color);
        signalMeter.setContentDescription(
                "Signal strength " + percent + " percent, " + report.strengthLabel());
    }

    private void clearSignalMeter() {
        if (signalMeterBlock != null) signalMeterBlock.setVisibility(View.GONE);
        if (signalMeterValue != null) {
            signalMeterValue.setText("--");
            signalMeterValue.setTextColor(MUTED);
        }
        if (signalMeterSegments != null) {
            for (View segment : signalMeterSegments) segment.setBackground(meterSegment(LINE));
        }
    }

    // ---------------------------------------------------------------- profiles

    private void renderProfiles() {
        if (profileList == null) return;
        profileList.removeAllViews();
        OduWebActions.ProfileRow enabled = null;
        int enabledCount = 0;
        for (OduWebActions.ProfileRow row : profileRows) {
            if (row.enabled) {
                enabledCount++;
                if (enabled == null) enabled = row;
            }
        }
        if (enabledCount == 0) {
            activeProfile.setText("No eSIM profile is enabled");
        } else if (enabledCount == 1 && enabled != null) {
            String name = enabled.name == null || enabled.name.trim().isEmpty()
                    ? "eSIM profile" : enabled.name;
            activeProfile.setText("Active: " + name
                    + (enabled.iccid == null ? "" : " · ICCID …" + tail(enabled.iccid, 6)));
        } else {
            activeProfile.setText(enabledCount + " eSIM profiles are enabled");
        }
        if (profileRows.isEmpty()) {
            TextView none = text(
                    "No eSIM profiles are installed on this ODU. Add one below.",
                    13, MUTED, false);
            profileList.addView(none, marginTop(none, 8));
            return;
        }
        for (OduWebActions.ProfileRow row : profileRows) {
            profileList.addView(profileRowView(row), padded(6));
        }
    }

    private View profileRowView(OduWebActions.ProfileRow row) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), LINE);
        panel.setBackground(background);

        String classLabel = row.profileClass == 0 ? "Test"
                : row.profileClass == 1 ? "Live (Jio)"
                : row.profileClass == 2 ? "Functional" : "Unknown type";
        TextView state = text(row.enabled ? "ENABLED" : "DISABLED", 10,
                row.enabled ? SIGNAL : MUTED, true);
        state.setLetterSpacing(0.06f);
        panel.addView(state);
        TextView name = display(row.name == null || row.name.trim().isEmpty()
                ? "Unnamed profile" : row.name, 15, INK);
        panel.addView(name, marginTop(name, 2));
        TextView detail = text(classLabel
                + (row.provider == null ? "" : " · " + row.provider)
                + (row.iccid == null ? "" : " · ICCID …" + tail(row.iccid, 6)),
                12, MUTED, false);
        panel.addView(detail, marginTop(detail, 2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(compactLayout() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        Button toggle = button(row.enabled ? "Disable" : "Enable", !row.enabled);
        toggle.setEnabled(!actionRunning);
        toggle.setOnClickListener(v -> confirmProfileToggle(row));
        if (compactLayout()) {
            buttons.addView(toggle, new LinearLayout.LayoutParams(-1, dp(44)));
        } else {
            buttons.addView(toggle, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        if (row.iccid != null) {
            Button delete = quietButton("Delete", false);
            delete.setEnabled(!actionRunning);
            delete.setOnClickListener(v -> confirmEsimDelete(row));
            if (compactLayout()) {
                buttons.addView(delete, marginTop(delete, 8));
            } else {
                LinearLayout.LayoutParams deleteParams =
                        new LinearLayout.LayoutParams(dp(104), dp(44));
                deleteParams.leftMargin = dp(8);
                buttons.addView(delete, deleteParams);
            }
        }
        panel.addView(buttons, marginTop(buttons, 8));
        return panel;
    }

    private void confirmProfileToggle(OduWebActions.ProfileRow row) {
        boolean enable = !row.enabled;
        new AlertDialog.Builder(this)
                .setTitle(enable ? "Enable this eSIM profile?" : "Disable this eSIM profile?")
                .setMessage((enable
                        ? "The ODU may switch its active service to "
                        : "The ODU may lose its data connection while ")
                        + (row.name == null || row.name.trim().isEmpty()
                        ? "this profile" : "“" + row.name + "”")
                        + " becomes " + (enable ? "active." : "inactive."))
                .setPositiveButton(enable ? "Enable" : "Disable", (dialog, which) ->
                        withSession(enable ? "Enable the profile" : "Disable the profile",
                                "Sign in to change the profile state.",
                                (username, password) -> startProfileToggle(
                                        username, password, row, enable)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startProfileToggle(String username, char[] password,
                                    OduWebActions.ProfileRow row, boolean enable) {
        pendingAction = enable ? "enable" : "disable";
        actionRunning = true;
        record(enable ? "esim_enable_started" : "esim_disable_started");
        refreshDashboardControls();
        renderProfiles();
        actions.setProfileEnabled(oduHost, username, password, row, enable);
    }

    private void confirmEsimDelete(OduWebActions.ProfileRow row) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this eSIM profile?")
                .setMessage("The ODU will permanently delete the profile "
                        + (row.name == null || row.name.trim().isEmpty()
                        ? "" : "“" + row.name + "” ")
                        + "from its eSIM. This cannot be undone; you would need to download it again.")
                .setPositiveButton("Delete", (dialog, which) -> withSession(
                        "Delete from the ODU",
                        "Sign in to delete the selected profile.",
                        (username, password) -> startEsimDelete(username, password, row.iccid)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startEsimDelete(String username, char[] password, String iccid) {
        pendingAction = "delete";
        actionRunning = true;
        record("esim_delete_started");
        refreshDashboardControls();
        renderProfiles();
        actions.deleteProfile(oduHost, username, password, iccid);
    }

    private void startAddProfile() {
        if (family == OduWebActions.Family.CM) {
            showActivationCodeDialog();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Add the default Jio eSIM profile")
                .setMessage("The ODU will download and enable its default Jio profile over the "
                        + "operator SM-DP+ server. This can take up to two minutes and may replace "
                        + "the currently active profile.")
                .setPositiveButton("Install", (dialog, which) -> withSession(
                        "Install on the ODU",
                        "Sign in to start the profile install.",
                        (username, password) -> startEsimInstall(username, password)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showActivationCodeDialog() {
        EditText code = new EditText(this);
        code.setSingleLine(false);
        code.setHint("LPA:1$sm-dp+$matching-id");
        code.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(text("Paste the full activation code for the eSIM profile. The ODU checks "
                + "the SM-DP+ host before downloading.", 12, MUTED, false));
        box.addView(code, marginTop(code, 8));
        new AlertDialog.Builder(this)
                .setTitle("Add eSIM with activation code")
                .setView(box)
                .setPositiveButton("Install", (dialog, which) -> {
                    String value = code.getText().toString().trim();
                    code.setText("");
                    withSession("Install on the ODU",
                            "Sign in to submit the activation code.",
                            (username, password) -> startEsimCode(username, password, value));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startEsimInstall(String username, char[] password) {
        pendingAction = "install";
        actionRunning = true;
        record("esim_install_started");
        refreshDashboardControls();
        renderProfiles();
        actions.installDefaultProfile(oduHost, username, password);
    }

    private void startEsimCode(String username, char[] password, String code) {
        pendingAction = "code";
        actionRunning = true;
        record("esim_code_started");
        refreshDashboardControls();
        renderProfiles();
        actions.installByActivationCode(oduHost, username, password, code);
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reboot the ODU?")
                .setMessage("The ODU will restart. Bluetooth and the data connection drop until "
                        + "it comes back. Reconnect afterwards.")
                .setPositiveButton("Reboot", (dialog, which) -> withSession(
                        "Reboot the ODU",
                        "Sign in to send the reboot request.",
                        (username, password) -> startReboot(username, password)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startReboot(String username, char[] password) {
        pendingAction = "reboot";
        actionRunning = true;
        record("esim_reboot_started");
        refreshDashboardControls();
        actions.reboot(oduHost, username, password);
    }

    private static String tail(String value, int count) {
        return value.length() <= count ? value : value.substring(value.length() - count);
    }

    private String completedEvent() {
        if ("details".equals(pendingAction)) return "odu_details_succeeded";
        if ("signal".equals(pendingAction)) return "signal_succeeded";
        if ("install".equals(pendingAction)) return "esim_install_succeeded";
        if ("code".equals(pendingAction)) return "esim_code_succeeded";
        if ("delete".equals(pendingAction)) return "esim_delete_succeeded";
        if ("enable".equals(pendingAction)) return "esim_enable_succeeded";
        if ("disable".equals(pendingAction)) return "esim_disable_succeeded";
        if ("reboot".equals(pendingAction)) return "esim_reboot_succeeded";
        return "esim_list_succeeded";
    }

    private String failedEvent() {
        if ("details".equals(pendingAction)) return "odu_details_failed";
        if ("signal".equals(pendingAction)) return "signal_failed";
        if ("install".equals(pendingAction)) return "esim_install_failed";
        if ("code".equals(pendingAction)) return "esim_code_failed";
        if ("delete".equals(pendingAction)) return "esim_delete_failed";
        if ("enable".equals(pendingAction)) return "esim_enable_failed";
        if ("disable".equals(pendingAction)) return "esim_disable_failed";
        if ("reboot".equals(pendingAction)) return "esim_reboot_failed";
        return "esim_list_failed";
    }

    // ------------------------------------------------------------------- WebUI

    private void startWebUi() {
        if (oduHost == null) {
            setStatus("Connect to a supported ODU first.", true);
            return;
        }
        if (family == OduWebActions.Family.CM) {
            record("provision_started");
            setStatus("Opening this ODU's WebUI in the app. The viewer accepts this ODU's "
                    + "self-signed certificate and presents the packaged client identity.",
                    false);
            openWebUiViewer(oduHost);
            return;
        }
        webUiAction = "enable";
        setStatus("Checking whether this ODU's WebUI is already served…", false);
        webUi.probeAndOpen(oduHost);
    }

    private void startWebUiWithCredentials() {
        if (oduHost == null) {
            setStatus("Connect to a supported ODU first.", true);
            return;
        }
        if (sessionPassword != null) {
            webUi.enableAndOpen(oduHost, sessionUser, sessionPassword.clone(), qrModel);
            record("provision_started");
            return;
        }
        withSession("Enable the ODU WebUI",
                "Sign in to enable and open the WebUI.",
                (username, password) -> {
                    webUi.enableAndOpen(oduHost, username, password, qrModel);
                    record("provision_started");
                });
    }

    private void startQrScan() {
        try {
            startActivityForResult(new Intent(this, QrScanActivity.class), REQUEST_QR_SCAN);
        } catch (RuntimeException unavailable) {
            setStatus("The camera could not be opened on this device.", true);
        }
    }

    private void handleQrResult(Intent data) {
        if (data == null) return;
        String error = data.getStringExtra(QrScanActivity.EXTRA_ERROR);
        if (error != null) {
            setStatus(error, true);
            return;
        }
        connectToSticker(OduQrCode.parse(data.getStringExtra(QrScanActivity.EXTRA_QR_RESULT)));
    }

    private void showPasteStickerDialog() {
        EditText input = new EditText(this);
        input.setHint("<BTMAC>A4:C1:38:…</BTMAC>");
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Paste sticker text")
                .setMessage("Paste the text from the ODU sticker QR code. It is parsed in memory "
                        + "for the Bluetooth address and model only.")
                .setView(box)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String raw = input.getText().toString();
                    input.setText("");
                    connectToSticker(OduQrCode.parse(raw));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectToSticker(OduQrCode code) {
        if (!code.isValid()) {
            setStatus("That sticker text did not contain an ODU Bluetooth address.", true);
            return;
        }
        pendingQrTarget = code.bluetoothAddress;
        pendingQrModel = code.model;
        if (!ensureBluetoothPermissions()) return;
        if (!bluetoothEnabled()) {
            setStatus("Turn Bluetooth on in Android Settings, then scan the sticker again.", true);
            pendingQrTarget = null;
            pendingQrModel = null;
            return;
        }
        setStatus("Looking for the ODU from the sticker…", false);
        scanner.startTargetedScan(code.bluetoothAddress);
    }

    private void checkWebUi() {
        if (oduHost == null) {
            setStatus("Connect to a supported ODU first.", true);
            return;
        }
        withSession("Check the ODU WebUI",
                "Sign in to read the current WebUI state.",
                (username, password) -> {
                    webUiAction = "check";
                    record("webui_check_started");
                    webUi.checkState(oduHost, username, password, qrModel);
                });
    }

    private void testLink() {
        if (oduHost == null) {
            setStatus("Connect to a supported ODU first.", true);
            return;
        }
        webUiAction = "ping";
        setStatus("Testing the local link to the ODU…", false);
        webUi.ping(oduHost);
    }

    private void confirmDisableWebUi() {
        new AlertDialog.Builder(this)
                .setTitle("Disable the ODU WebUI?")
                .setMessage("The WebUI stops answering until it is enabled again over "
                        + "Bluetooth. You can reconnect here and re-enable it.")
                .setPositiveButton("Disable", (dialog, which) -> disableWebUi())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disableWebUi() {
        if (oduHost == null) {
            setStatus("Connect to a supported ODU first.", true);
            return;
        }
        if (!webUiToggleSupported) {
            setStatus("This ODU firmware does not expose a WebUI disable switch.", true);
            return;
        }
        withSession("Disable the ODU WebUI",
                "Sign in to disable the WebUI.",
                (username, password) -> {
                    webUiAction = "disable";
                    record("webui_disable_started");
                    webUi.setEnabled(oduHost, username, password, false, qrModel);
                });
    }

    private void updateWebUiBadge(boolean enabled) {
        if (webUiState == null) return;
        webUiState.setText(enabled ? "WebUI: enabled" : "WebUI: disabled");
        webUiState.setTextColor(enabled ? SIGNAL : MUTED);
    }

    private void openWebUiViewer(String host) {
        try {
            startActivity(new Intent(this, OduWebUiActivity.class)
                    .putExtra(OduWebUiActivity.EXTRA_HOST, host));
        } catch (RuntimeException e) {
            setStatus("WebUI is enabled, but Android could not open the viewer.", true);
        }
    }

    // --------------------------------------------------------------- log/UI

    /** Read-only recent-event viewer. */
    private void showLog() {
        final int maxRows = 500;
        new Thread(() -> {
            final String report = readRecentLog(maxRows);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                String shown = report;
                if (shown == null) {
                    shown = "Could not read the app log right now.";
                } else if (shown.isEmpty()) {
                    shown = "No app log events yet. Connect to an ODU, then check again.";
                }
                TextView content = text(shown, 12, INK, false);
                content.setTypeface(Typeface.MONOSPACE);
                content.setTextIsSelectable(true);
                int pad = dp(20);
                content.setPadding(pad, dp(8), pad, dp(8));
                ScrollView scroll = new ScrollView(this);
                scroll.setFillViewport(true);
                scroll.addView(content);
                new AlertDialog.Builder(this)
                        .setTitle("App log (v" + BuildConfig.VERSION_NAME + ")")
                        .setView(scroll)
                        .setPositiveButton("Refresh", (d, w) -> {
                            d.dismiss();
                            showLog();
                        })
                        .setNegativeButton("Close", null)
                        .show();
            });
        }, "afble-log-view").start();
    }

    private String readRecentLog(int maxRows) {
        try {
            return AfBleLog.recentLogReport(this, maxRows);
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    /** Fixed event ID per failure class so the queued log distinguishes auth, route, TLS, and HTTP. */
    private static String provisionFailureEvent(OduWebUiController.ErrorKind kind) {
        switch (kind) {
            case AUTH_REJECTED: return "provision_failed_auth";
            case API_FAMILY_NOT_FOUND: return "provision_failed_family";
            case ROUTE_UNAVAILABLE: return "provision_failed_route";
            case CLIENT_CERT_REQUIRED: return "provision_failed_tls";
            case HTTP: return "provision_failed_http";
            default: return "provision_failed_other";
        }
    }

    private void openTetheringSettings() {
        try {
            startActivity(new Intent("android.settings.TETHER_SETTINGS"));
        } catch (RuntimeException unavailable) {
            openBluetoothSettings();
        }
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } catch (RuntimeException first) {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (RuntimeException second) {
                setStatus("Open Android Settings and check Bluetooth and Bluetooth tethering.", true);
            }
        }
    }

    private void showTetheringPrompt() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("Bluetooth tethering is off")
                .setMessage("Turn on Bluetooth tethering on this phone, then retry the connection.")
                .setPositiveButton("Open tethering settings", (dialog, which) -> openTetheringSettings())
                .setNegativeButton("Close", null)
                .show();
    }

    private void renderDevices() {
        if (deviceList == null) return;
        deviceList.removeAllViews();
        if (deviceCount != null) {
            deviceCount.setText(seenDevices.size() + " FOUND");
        }
        if (seenDevices.isEmpty()) {
            LinearLayout emptyCard = card();
            emptyCard.setPadding(dp(16), dp(18), dp(16), dp(18));
            boolean scanning = scanner != null && scanner.isScanning();
            emptyCard.addView(display(scanning ? "Looking for nearby devices…"
                    : "No devices found yet", 15, INK));
            TextView hint = text(scanning
                    ? "Keep your ODU powered on and nearby. Results appear here."
                    : "Tap Scan for ODU. Make sure Bluetooth is on and the ODU is nearby.",
                    13, MUTED, false);
            emptyCard.addView(hint, marginTop(hint, 6));
            deviceList.addView(emptyCard, padded(0));
            return;
        }
        List<BleScannerController.Device> ordered = new ArrayList<>(seenDevices);
        Collections.sort(ordered, Comparator.comparingInt(
                (BleScannerController.Device d) -> isOduName(d.name) ? 0 : 1));
        for (BleScannerController.Device found : ordered) {
            deviceList.addView(deviceRowWide(found), padded(8));
        }
    }

    private LinearLayout deviceRowWide(BleScannerController.Device found) {
        LinearLayout row = card();
        TextView type = text(isOduName(found.name) ? "POSSIBLE AIRFIBER ODU" : "BLUETOOTH DEVICE",
                10, isOduName(found.name) ? SIGNAL : MUTED, true);
        type.setLetterSpacing(.06f);
        row.addView(type);
        LinearLayout line = new LinearLayout(this);
        boolean compact = compactLayout();
        line.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        line.setGravity(compact ? Gravity.START : Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = display(displayName(found.name), 17, INK);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(name);
        LinearLayout readout = new LinearLayout(this);
        readout.setGravity(Gravity.CENTER_VERTICAL);
        readout.addView(meter(found.rssi), new LinearLayout.LayoutParams(dp(52), -2));
        TextView dbm = data(found.rssi + " dBm", 12, MUTED);
        LinearLayout.LayoutParams dbmParams = new LinearLayout.LayoutParams(-2, -2);
        dbmParams.leftMargin = dp(8);
        readout.addView(dbm, dbmParams);
        words.addView(readout, marginTop(readout, 6));
        if (compact) line.addView(words);
        else line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Button connect = button(connectLabel(found), true);
        connect.setEnabled(sessionDevice == null || !sessionDevice.equals(found.handle));
        connect.setOnClickListener(v -> {
            qrModel = null;
            connect(found);
        });
        if (compact) line.addView(connect, marginTop(connect, 10));
        else {
            LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-2, dp(50));
            connectParams.leftMargin = dp(12);
            line.addView(connect, connectParams);
        }
        row.addView(line, marginTop(line, 6));
        return row;
    }

    private String connectLabel(BleScannerController.Device found) {
        if (sessionDevice != null && sessionDevice.equals(found.handle)) {
            return connecting ? "Connecting" : (oduHost != null ? "Connected" : "Selected");
        }
        return "Connect";
    }

    private int findDevice(BluetoothDevice device) {
        for (int i = 0; i < seenDevices.size(); i++) {
            if (seenDevices.get(i).handle.equals(device)) return i;
        }
        return -1;
    }

    private static String deviceAddress(BluetoothDevice device) {
        if (device == null) return null;
        try {
            return device.getAddress();
        } catch (SecurityException unavailable) {
            return null;
        }
    }

    private static boolean isOduName(String name) {
        return name != null && name.regionMatches(true, 0, "JODU", 0, 4);
    }

    private static String displayName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Unnamed Bluetooth device";
        String clean = raw.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (clean.isEmpty()) return "Unnamed Bluetooth device";
        return clean.length() > 36 ? clean.substring(0, 36) : clean;
    }

    private void setStatus(String message, boolean error) {
        boolean changed = !message.contentEquals(statusText.getText());
        statusText.setTextColor(error ? ALERT : INK);
        statusDetail.setVisibility(View.GONE);
        statusText.setText(message);
        if (connectionIndicator != null) {
            connectionIndicator.setTextColor(error ? ALERT
                    : (connecting || supported || oduHost != null ? SIGNAL : MUTED));
            connectionIndicator.setContentDescription(error ? "Connection needs attention"
                    : (oduHost == null ? "Not connected" : "ODU connected"));
        }
        if (changed) {
            statusText.setAlpha(0f);
            statusText.animate().alpha(1f).setDuration(200).start();
        }
        refreshBluetoothState();
    }

    private void refreshScanState() {
        if (scanState == null) return;
        int count = seenDevices.size();
        if (count == 0) {
            scanState.setText(lastScanMessage);
            return;
        }
        scanState.setText(lastScanMessage + " (" + count
                + (count == 1 ? " device)" : " devices)"));
    }

    private void record(String event) {
        try {
            AfBleLog.record(this, event);
        } catch (RuntimeException ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        if (bluetoothState != null) refreshBluetoothState();
    }

    @Override protected void onStop() {
        if (scanner != null) scanner.stopScan(null);
        AfBleLog.onBackground(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        record("app_closed");
        if (scanner != null) scanner.close();
        if (session != null) session.release();
        if (webUi != null) webUi.release();
        if (actions != null) actions.release();
        clearSession();
        oduHost = null;
        sessionDevice = null;
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_QR_SCAN) {
            if (resultCode == RESULT_OK) handleQrResult(data);
            else if (data != null && data.getStringExtra(QrScanActivity.EXTRA_ERROR) != null) {
                setStatus(data.getStringExtra(QrScanActivity.EXTRA_ERROR), true);
            }
            return;
        }
        if (requestCode == REQUEST_ENABLE_BLUETOOTH
                || requestCode == REQUEST_LOCATION_SETTINGS) {
            refreshBluetoothState();
            if (requestCode == REQUEST_ENABLE_BLUETOOTH && resultCode == RESULT_OK) startScan();
        }
    }

    private static char[] clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
        return value;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(13), dp(12), dp(13), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE);
        background.setCornerRadius(dp(14));
        layout.setBackground(background);
        layout.setElevation(dp(1));
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private TextView display(String value, int size, int color) {
        TextView text = text(value, size, color, false);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private TextView data(String value, int size, int color) {
        TextView text = text(value, size, color, false);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        return text;
    }

    private LinearLayout meter(int rssi) {
        int level = rssi >= -55 ? 4 : rssi >= -70 ? 3 : rssi >= -85 ? 2 : rssi >= -95 ? 1 : 0;
        int[] heights = {6, 9, 12, 15, 18};
        LinearLayout meter = new LinearLayout(this);
        meter.setOrientation(LinearLayout.HORIZONTAL);
        meter.setGravity(Gravity.BOTTOM);
        for (int i = 0; i < heights.length; i++) {
            View bar = new View(this);
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(i <= level ? SIGNAL : LINE);
            fill.setCornerRadius(dp(2));
            bar.setBackground(fill);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, dp(heights[i]), 1f);
            if (i > 0) params.leftMargin = dp(3);
            meter.addView(bar, params);
        }
        return meter;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {MUTED, primary ? Color.WHITE : INK}));
        button.setBackground(touchFeedback(primary ? SIGNAL : QUIET, dp(12), 0x40FFFFFF));
        button.setMinHeight(dp(50));
        button.setPadding(dp(16), 0, dp(16), 0);
        return button;
    }

    private Button quietButton(String label, boolean onDark) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(onDark ? Color.WHITE : SIGNAL);
        button.setBackground(touchFeedback(onDark ? Color.rgb(30, 50, 58) : QUIET, dp(12),
                onDark ? 0x33FFFFFF : 0x1F0E7C86));
        button.setMinHeight(dp(42));
        button.setPadding(dp(18), 0, dp(18), 0);
        return button;
    }

    private RippleDrawable touchFeedback(int fillColor, int radius, int rippleColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {LINE, fillColor}));
        shape.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null);
    }

    private LinearLayout.LayoutParams padded(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams marginTop(View view, int valueDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                view == null ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                view == null ? dp(valueDp) : ViewGroup.LayoutParams.WRAP_CONTENT);
        if (view != null) params.topMargin = dp(valueDp);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean compactLayout() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        return metrics.widthPixels / metrics.density < 360
                || getResources().getConfiguration().fontScale >= 1.25f;
    }
}
