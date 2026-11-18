package io.github.wpfyorg.afhelper.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import io.github.wpfyorg.afhelper.net.OduProtocol;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** ODU-only GATT handshake. It never writes the vendor Bluetooth-reset characteristic. */
public final class OduBleSession {
    private static final int NO_CODE = io.github.wpfyorg.afhelper.log.AfBleLog.BLE_CODE_UNAVAILABLE;
    private static final long DISCOVERY_DELAY_MS = 2_000L;
    // The vendor client reads the PAN address 10 s after BOND_BONDED; the ODU
    // needs that settle time before it answers the protected read.
    private static final long BOND_SETTLE_MS = 10_000L;
    private static final long DIRECT_READ_SETTLE_MS = 250L;
    private static final long RETRY_DELAY_MS = 10_000L;
    private static final long RECONNECT_DELAY_MS = 500L;
    private static final long TIMEOUT_MS = 120_000L;
    private static final int MAX_ADDRESS_READS = 2;
    private static final int MAX_RECONNECTS = 2;
    private static final long READ_TIMEOUT_MS = 8_000L;
    private static final long BOND_POLL_MS = 1_000L;
    private static final int BOND_POLL_MAX = 25;
    private static final long SUBSCRIBE_RETRY_MS = 1_000L;
    private static final int MAX_SUBSCRIBE_ATTEMPTS = 5;
    private static final String STALE_BOND_MESSAGE =
            "The ODU's saved Bluetooth pairing is out of date. Forget the ODU in Android "
                    + "Bluetooth settings, then reconnect.";

    public interface Listener {
        void onState(String message);
        default void onDiagnostic(String phase, int code, String result) { }
        default void onPanDiagnostic(int payloadBytes, String category) { }
        void onSupported();
        void onHostReady(String privateHost);
        void onUnsupported(String message);
        void onError(ErrorKind kind, String message);
    }

    public enum ErrorKind { BUSY, TETHERING_OFF, TIMEOUT, PERMISSION, DISCONNECTED, OTHER }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private BluetoothGatt gatt;
    private BluetoothDevice device;
    private BluetoothGattCharacteristic discovery;
    private BluetoothGattCharacteristic pan;
    private BluetoothGattDescriptor pendingCccd;
    private boolean receiverRegistered;
    private boolean supported;
    private boolean finished;
    private boolean connected;
    private boolean bondConfirmed;
    private boolean bondRequested;
    private boolean cccdSubscribed;
    private boolean preferDirectRead;
    private boolean writePending;
    private int reconnects;
    private boolean reconnectScheduled;
    private boolean securityReconnectTried;
    private boolean hostDelivered;
    private int readAttempts;
    private int subscribeAttempts;
    private String lastPhase = "gatt_connect";
    private int lastCode = io.github.wpfyorg.afhelper.log.AfBleLog.BLE_CODE_UNAVAILABLE;

    private final Runnable timeout = () -> fail(ErrorKind.TIMEOUT,
            "ODU Bluetooth handshake timed out. Retry the connection.");
    private final Runnable discoverLater = this::discoverServices;
    private final Runnable readLater = this::readPanAddress;
    private final Runnable reconnectLater = this::reconnectGatt;
    private final Runnable bondPoll = this::pollBondState;
    private final Runnable readWatchdog = this::onReadWatchdog;
    private final Runnable subscribeRetry = this::retrySubscribe;
    private int bondPolls;

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (finished || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
            BluetoothDevice changed = getChangedDevice(intent);
            if (changed == null || device == null || !device.equals(changed)) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
            diagnostic("bond_state", state, bondResult(state));
            if (state == BluetoothDevice.BOND_BONDED) onBondConfirmed();
            else if (state == BluetoothDevice.BOND_NONE
                    && previous == BluetoothDevice.BOND_BONDED) {
                // The ODU rejected our stored key (typically right after an ODU
                // reboot) and Android dropped the bond silently. The link-drop
                // handler re-handshakes from scratch; record why it happened.
                diagnostic("bond_state", state, "bond_lost_remote");
            }
            else if (bondRequested && state == BluetoothDevice.BOND_NONE
                    && previous == BluetoothDevice.BOND_BONDING) {
                main.removeCallbacks(bondPoll);
                fail(ErrorKind.OTHER, "Pairing with the ODU failed. Retry the connection.");
            }
        }
    };

    public OduBleSession(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice target) {
        disconnect();
        if (target == null) {
            error(ErrorKind.OTHER, "Select a nearby Bluetooth device first");
            return;
        }
        device = target;
        finished = false;
        diagnostic("gatt_connect", NO_CODE, "started");
        notifyState("Connecting to nearby device");
        try {
            registerBondReceiver();
            BluetoothManager manager = (BluetoothManager)
                    context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
                fail(ErrorKind.OTHER, "Turn Bluetooth on and retry");
                return;
            }
            gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                fail(ErrorKind.OTHER, "Could not open a Bluetooth connection");
                return;
            }
            main.postDelayed(timeout, TIMEOUT_MS);
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
        } catch (RuntimeException e) {
            fail(ErrorKind.OTHER, "Could not connect to this Bluetooth device");
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
            main.post(() -> {
                if (finished || source != gatt) return;
                String result = status != BluetoothGatt.GATT_SUCCESS ? "failed"
                        : newState == BluetoothProfile.STATE_CONNECTED ? "connected" : "disconnected";
                diagnostic("gatt_state", status, result);
                connected = status == BluetoothGatt.GATT_SUCCESS
                        && newState == BluetoothProfile.STATE_CONNECTED;
                if (connected) {
                    notifyState("Connected. Checking ODU support");
                    main.postDelayed(discoverLater, DISCOVERY_DELAY_MS);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cccdSubscribed = false;
                    handleRemoteDisconnect();
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
            main.post(() -> handleServices(source, status));
        }

        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> handleDescriptorWrite(source, status));
        }

        @Override public void onCharacteristicWrite(
                BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            main.post(() -> handleKickWrite(source, status));
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicRead(
                BluetoothGatt source, BluetoothGattCharacteristic characteristic, int status) {
            byte[] value = characteristic == null ? null : characteristic.getValue();
            dispatchPanRead(source, characteristic == null ? null : characteristic.getUuid(), status,
                    value == null ? null : value.clone());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic characteristic,
                                         byte[] value, int status) {
            dispatchPanRead(source, characteristic == null ? null : characteristic.getUuid(), status,
                    value == null ? null : value.clone());
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic == null ? null : characteristic.getValue();
            dispatchPanChanged(source, characteristic == null ? null : characteristic.getUuid(),
                    value == null ? null : value.clone());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            dispatchPanChanged(source, characteristic == null ? null : characteristic.getUuid(),
                    value == null ? null : value.clone());
        }
    };

    private void dispatchPanRead(BluetoothGatt source, UUID uuid, int status, byte[] value) {
        main.post(() -> {
            if (finished || source != gatt || pan == null || uuid == null
                    || !pan.getUuid().equals(uuid) || !bondConfirmed) return;
            main.removeCallbacks(readWatchdog);
            diagnostic("pan_read", status,
                    status == BluetoothGatt.GATT_SUCCESS && value != null ? "success" : "read_failed");
            if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
                if (status != BluetoothGatt.GATT_SUCCESS && isSecurityStatus(status)) {
                    recoverFromSecurityRead();
                } else {
                    scheduleReadRetry();
                }
                return;
            }
            handlePanValue(value);
        });
    }

    private void dispatchPanChanged(BluetoothGatt source, UUID uuid, byte[] value) {
        main.post(() -> {
            if (finished || source != gatt || pan == null || uuid == null || value == null
                    || !pan.getUuid().equals(uuid) || !bondConfirmed) return;
            main.removeCallbacks(readWatchdog);
            handlePanValue(value);
        });
    }

    /**
     * A bonded unit can still refuse a protected read with status 3/5/15. Refresh
     * the encrypted link once; never remove the bond or reset Bluetooth.
     */
    @SuppressLint("MissingPermission")
    private void recoverFromSecurityRead() {
        if (finished || hostDelivered) return;
        try {
            if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED) {
                scheduleReadRetry();
                return;
            }
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
            return;
        }
        if (!securityReconnectTried && reconnects < MAX_RECONNECTS) {
            securityReconnectTried = true;
            preferDirectRead = true;
            beginReconnect("Refreshing the ODU Bluetooth pairing",
                    ErrorKind.OTHER, STALE_BOND_MESSAGE);
            return;
        }
        fail(ErrorKind.OTHER, STALE_BOND_MESSAGE);
    }

    private void handleServices(BluetoothGatt source, int status) {
        if (finished || source != gatt) return;
        diagnostic("service_discovery", status,
                status == BluetoothGatt.GATT_SUCCESS ? "success" : "failed");
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(ErrorKind.OTHER, "Could not inspect Bluetooth services");
            return;
        }
        BluetoothGattService service = source.getService(UUID.fromString(OduBle.SERVICE));
        if (service == null) {
            diagnostic("service_discovery", status, "unsupported");
            unsupported("This device does not support the AirFiber ODU WebUI flow");
            return;
        }
        discovery = service.getCharacteristic(UUID.fromString(OduBle.DISCOVERY));
        pan = service.getCharacteristic(UUID.fromString(OduBle.PAN_IP));
        BluetoothGattDescriptor cccd = pan == null ? null
                : pan.getDescriptor(UUID.fromString(OduBle.CCCD));
        if (discovery == null || pan == null || cccd == null) {
            diagnostic("service_discovery", status, "missing_characteristics");
            unsupported("AirFiber service found, but required ODU characteristics are missing");
            return;
        }
        supported = true;
        pendingCccd = cccd;
        cccdSubscribed = false;
        subscribeAttempts = 0;
        if (listener != null) listener.onSupported();
        notifyState("AirFiber ODU found. Starting its discovery handshake");
        int bondState;
        try {
            bondState = device == null ? BluetoothDevice.BOND_NONE : device.getBondState();
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
            return;
        }
        if (bondState == BluetoothDevice.BOND_BONDED) {
            // A stored bond usually lets the ODU accept the address subscription
            // first. After a fresh pair, however, the ODU can still reject the
            // descriptor with status 3 while allowing the PAN read; the vendor
            // client reads the address directly on bond confirmation.
            diagnostic("bond_state", bondState, "existing_bond");
            bondConfirmed = true;
            if (preferDirectRead) {
                readPanAddress();
            } else {
                subscribe(source, cccd);
            }
            return;
        }
        // A bond can disappear while the ODU reboots, so never let a stale
        // flag stop requestBond() on the new link.
        bondConfirmed = false;
        if (bondState == BluetoothDevice.BOND_BONDING) {
            // The link came back while Android was still pairing; the bond
            // poll or broadcast resumes the handshake once it completes.
            diagnostic("bond_state", bondState, "bonding");
            notifyState("Confirm Bluetooth pairing if Android asks");
            ensureBondPoll();
            return;
        }
        // First contact: the ODU requires the discovery kick and bonding before
        // it accepts the notification descriptor (status 3 on some units). Enable
        // local notifications now, as the vendor client does; the descriptor
        // write itself is optional and its failure is ignored.
        try {
            source.setCharacteristicNotification(pan, true);
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
            return;
        } catch (RuntimeException ignored) {
            // A local notification flag must not stop the kick/bond handshake.
        }
        writeKick();
    }

    @SuppressLint("MissingPermission")
    private void discoverServices() {
        if (finished || gatt == null) return;
        try {
            diagnostic("service_discovery", NO_CODE, "started");
            if (!gatt.discoverServices()) {
                fail(ErrorKind.OTHER, "Could not start ODU service discovery");
            }
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void subscribe(BluetoothGatt source, BluetoothGattDescriptor cccd) {
        if (finished || source == null || cccd == null || pan == null) return;
        if (subscribeAttempts >= MAX_SUBSCRIBE_ATTEMPTS) {
            fail(ErrorKind.OTHER, "Could not subscribe to ODU address updates");
            return;
        }
        subscribeAttempts++;
        pendingCccd = cccd;
        try {
            diagnostic("cccd_write", NO_CODE, "started");
            if (!source.setCharacteristicNotification(pan, true)) {
                scheduleSubscribeRetry();
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!source.writeDescriptor(cccd)) {
                scheduleSubscribeRetry();
            }
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
        }
    }

    private void scheduleSubscribeRetry() {
        if (finished || cccdSubscribed) return;
        if (!connected || gatt == null) {
            if (!reconnectScheduled) {
                preferDirectRead = true;
                beginReconnect("Reconnecting to finish the ODU pairing");
            }
            return;
        }
        if (subscribeAttempts >= MAX_SUBSCRIBE_ATTEMPTS) {
            fail(ErrorKind.OTHER, "Could not subscribe to ODU address updates");
            return;
        }
        main.removeCallbacks(subscribeRetry);
        main.postDelayed(subscribeRetry, SUBSCRIBE_RETRY_MS);
    }

    private void retrySubscribe() {
        if (finished || cccdSubscribed) return;
        if (!connected || gatt == null || pan == null || pendingCccd == null) {
            if (!reconnectScheduled) {
                preferDirectRead = true;
                beginReconnect("Reconnecting to finish the ODU pairing");
            }
            return;
        }
        subscribe(gatt, pendingCccd);
    }

    @SuppressLint("MissingPermission")
    private void handleDescriptorWrite(BluetoothGatt source, int status) {
        if (finished || source != gatt) return;
        diagnostic("cccd_write", status,
                status == BluetoothGatt.GATT_SUCCESS ? "success" : "failed");
        if (status == BluetoothGatt.GATT_SUCCESS) {
            cccdSubscribed = true;
            subscribeAttempts = 0;
            try {
                if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    diagnostic("bond_state", BluetoothDevice.BOND_BONDED, "existing_bond");
                    bondConfirmed = true;
                    main.removeCallbacks(readLater);
                    notifyState("Paired. Reading the ODU network address");
                    readPanAddress();
                    return;
                }
            } catch (SecurityException e) {
                fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
                return;
            }
            writeKick();
            return;
        }
        // The vendor client ignores this descriptor's result and reads the PAN
        // address directly. For any bonded unit, try the read before treating
        // the descriptor failure as fatal.
        if (tryDirectReadAfterDescriptorFailure()) return;

        if (isSecurityStatus(status)) {
            // An unbonded unit needs the kick/bond path. A bonded unit that is
            // still refused has already tried the direct read, so refresh the
            // GATT link once to renegotiate encryption, then give actionable
            // guidance. Never remove the bond or reset Bluetooth here.
            try {
                if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    if (!securityReconnectTried && reconnects < MAX_RECONNECTS) {
                        securityReconnectTried = true;
                        beginReconnect("Refreshing the ODU Bluetooth pairing",
                                ErrorKind.OTHER, STALE_BOND_MESSAGE);
                        return;
                    }
                    fail(ErrorKind.OTHER, STALE_BOND_MESSAGE);
                    return;
                }
            } catch (SecurityException e) {
                fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
                return;
            }
            writeKick();
            return;
        }
        fail(ErrorKind.OTHER, "Could not subscribe to ODU address updates");
    }

    /**
     * The vendor client ignores the notification descriptor's write result and
     * reads the PAN address on bond. A bonded ODU can still allow that read when
     * it rejects the descriptor, so try it once before reconnecting or failing.
     */
    @SuppressLint("MissingPermission")
    private boolean tryDirectReadAfterDescriptorFailure() {
        try {
            if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED) {
                return false;
            }
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
            return true;
        }
        if (preferDirectRead) return false;
        preferDirectRead = true;
        main.removeCallbacks(readLater);
        main.postDelayed(readLater, DIRECT_READ_SETTLE_MS);
        notifyState("Reading the ODU network address");
        return true;
    }

    private static boolean isSecurityStatus(int status) {
        return status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                || status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void writeKick() {
        if (finished || gatt == null || discovery == null) return;
        setPhase("discovery_write");
        if (writePending) {
            main.postDelayed(this::writeKick, 100L);
            return;
        }
        try {
            discovery.setValue(OduBle.KICK);
            if (gatt.writeCharacteristic(discovery)) {
                writePending = true;
                diagnostic("discovery_write", NO_CODE, "started");
            } else {
                main.postDelayed(this::writeKick, 100L);
            }
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
        }
    }

    private void handleKickWrite(BluetoothGatt source, int status) {
        if (finished || source != gatt || !writePending) return;
        writePending = false;
        diagnostic("discovery_write", status,
                status == BluetoothGatt.GATT_SUCCESS ? "success" : "failed");
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (isSecurityStatus(status)) {
                notifyState("Confirm Bluetooth pairing if Android asks");
                requestBond();
                return;
            }
            main.postDelayed(this::writeKick, 100L);
            return;
        }
        // Vendor evidence: bonding is requested as soon as the kick write is
        // acknowledged. Waiting (the ODU drops the link ~3.4s after the kick)
        // kills an unpaired session before it can pair.
        notifyState("Confirm Bluetooth pairing if Android asks");
        requestBond();
    }

    @SuppressLint("MissingPermission")
    private void requestBond() {
        if (finished || bondConfirmed || device == null) return;
        try {
            bondRequested = true;
            int state = device.getBondState();
            diagnostic("bond_state", state, bondResult(state));
            if (state == BluetoothDevice.BOND_BONDED) {
                onBondConfirmed();
                return;
            }
            notifyState("Confirm Bluetooth pairing if Android asks");
            if (state == BluetoothDevice.BOND_BONDING) {
                ensureBondPoll();
                return;
            }
            boolean started = device.createBond();
            if (started) {
                diagnostic("bond_state", device.getBondState(), "request_started");
                bondPolls = 0;
                ensureBondPoll();
            } else if (device.getBondState() != BluetoothDevice.BOND_BONDING) {
                diagnostic("bond_state", device.getBondState(), "request_failed");
                fail(ErrorKind.OTHER, "Could not start Bluetooth pairing");
            } else {
                ensureBondPoll();
            }
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
        }
    }

    private void onBondConfirmed() {
        if (finished || bondConfirmed) return;
        bondConfirmed = true;
        securityReconnectTried = false;
        preferDirectRead = true;
        main.removeCallbacks(bondPoll);

        // Schedule the next handshake step before touching the UI. A slow or
        // stopped Activity must not be able to leave the session parked here.
        if (cccdSubscribed) {
            main.removeCallbacks(readLater);
            main.postDelayed(readLater, BOND_SETTLE_MS);
        } else if (connected && gatt != null && pan != null) {
            // The ODU may reject the notification descriptor after a fresh bond
            // even though the PAN address characteristic is readable. Follow the
            // vendor client: read the address directly on bond confirmation.
            main.removeCallbacks(subscribeRetry);
            main.removeCallbacks(readLater);
            main.postDelayed(readLater, BOND_SETTLE_MS);
        } else if (!reconnectScheduled) {
            // The link can disappear while the controller finishes pairing. If
            // the disconnect callback was missed, recover with one bounded
            // reconnect instead of waiting for the absolute timeout.
            beginReconnect("Reconnecting to finish the ODU pairing");
        }
        if (!finished) notifyState("Paired. Reading the ODU network address");
    }

    private void ensureBondPoll() {
        main.removeCallbacks(bondPoll);
        main.postDelayed(bondPoll, BOND_POLL_MS);
    }

    /**
     * Bond-confirmation fallback: while pairing churns the LE link the
     * handoff broadcast may not reach the context receiver, so briefly poll
     * the bond state after createBond() instead of waiting forever.
     */
    @SuppressLint("MissingPermission")
    private void pollBondState() {
        if (finished || bondConfirmed || device == null) return;
        int state;
        try { state = device.getBondState(); }
        catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
            return;
        }
        if (state == BluetoothDevice.BOND_BONDED) {
            diagnostic("bond_state", state, "bonded_poll");
            onBondConfirmed();
            return;
        }
        if (state == BluetoothDevice.BOND_NONE) {
            fail(bondRequested ? ErrorKind.OTHER : ErrorKind.TIMEOUT,
                    "Pairing with the ODU failed. Retry the connection.");
            return;
        }
        bondPolls++;
        if (bondPolls > BOND_POLL_MAX) {
            diagnostic("bond_state", state, "pairing_stall");
            fail(ErrorKind.TIMEOUT, "ODU pairing did not finish. Retry the connection.");
            return;
        }
        main.postDelayed(bondPoll, BOND_POLL_MS);
    }

    /** A PAN read that never answers must not park the handshake until the absolute timeout. */
    private void onReadWatchdog() {
        if (finished || hostDelivered) return;
        diagnostic("pan_read", NO_CODE, "read_timeout");
        scheduleReadRetry();
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void readPanAddress() {
        if (finished || hostDelivered || !bondConfirmed || gatt == null || pan == null) return;
        readAttempts++;
        setPhase("pan_read");
        try {
            if (gatt.readCharacteristic(pan)) {
                diagnostic("pan_read", NO_CODE, "read_started");
                main.removeCallbacks(readWatchdog);
                main.postDelayed(readWatchdog, READ_TIMEOUT_MS);
            } else {
                diagnostic("pan_read", NO_CODE, "read_failed");
                scheduleReadRetry();
            }
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
        }
    }

    private void handlePanValue(byte[] value) {
        if (finished || hostDelivered) return;
        if (value == null) {
            panDiagnostic(0, "empty");
            scheduleReadRetry();
            return;
        }
        if (value.length == 1 && value[0] == OduBle.BUSY) {
            panDiagnostic(value.length, "busy");
            fail(ErrorKind.BUSY, "ODU Bluetooth is busy. Retry later or reboot the ODU, then reconnect.");
            return;
        }
        if (value.length == 1 && value[0] == OduBle.TETHERING_OFF) {
            panDiagnostic(value.length, "tethering_off");
            if (readAttempts < MAX_ADDRESS_READS) {
                notifyState("ODU reports Bluetooth tethering is unavailable. Checking once more");
                scheduleReadRetry();
            } else {
                fail(ErrorKind.TETHERING_OFF,
                        "Bluetooth tethering is off on this phone. Turn it on, then retry.");
            }
            return;
        }
        String host = new String(value, StandardCharsets.UTF_8).trim();
        OduProtocol.PanAddressCategory category = OduProtocol.classifyOduPanHost(host);
        panDiagnostic(value.length, panCategory(category));
        if (category != OduProtocol.PanAddressCategory.VALID_ADDRESS) {
            scheduleReadRetry();
            return;
        }
        hostDelivered = true;
        main.removeCallbacks(timeout);
        main.removeCallbacks(readLater);
        main.removeCallbacks(readWatchdog);
        unregisterBondReceiver();
        notifyState("ODU connected. Ready to enable its WebUI");
        if (listener != null) listener.onHostReady(host);
    }

    private void scheduleReadRetry() {
        if (finished || hostDelivered) return;
        if (readAttempts >= MAX_ADDRESS_READS) {
            fail(ErrorKind.OTHER, "ODU did not return a usable local network address");
            return;
        }
        main.removeCallbacks(readLater);
        main.postDelayed(readLater, RETRY_DELAY_MS);
    }

    /**
     * The ODU closes the Bluetooth link on its own around the discovery kick and
     * while pairing re-keys the link. Vendor clients tolerate that with a
     * bounded reconnect; a non-destructive in-session retry is allowed. No
     * bond removal and no Bluetooth reset happen here.
     */
    private void handleRemoteDisconnect() {
        if (finished) return;
        if (hostDelivered) {
            // The ODU already delivered its address; a late link drop must not
            // discard the connected state the WebUI flow depends on.
            finished = true;
            cancelTimers();
            closeGatt();
            return;
        }
        beginReconnect("The ODU dropped its Bluetooth link. Reconnecting");
    }

    private void beginReconnect(String message) {
        beginReconnect(message, ErrorKind.DISCONNECTED,
                "Bluetooth connection ended. Reconnect to continue.");
    }

    private void beginReconnect(String message, ErrorKind exhaustedKind, String exhaustedMessage) {
        if (finished || reconnectScheduled) return;
        if (reconnects >= MAX_RECONNECTS) {
            fail(exhaustedKind, exhaustedMessage);
            return;
        }
        reconnects++;
        cancelHandshakeTimers();
        diagnostic("gatt_connect", NO_CODE, "started");
        notifyState(message);
        reconnectScheduled = true;
        main.postDelayed(reconnectLater, RECONNECT_DELAY_MS);
    }

    @SuppressLint("MissingPermission")
    private void reconnectGatt() {
        if (finished) return;
        reconnectScheduled = false;
        BluetoothDevice target = device;
        BluetoothGatt stale = gatt;
        gatt = null;
        connected = false;
        writePending = false;
        cccdSubscribed = false;
        subscribeAttempts = 0;
        discovery = null;
        pan = null;
        pendingCccd = null;
        if (stale != null) {
            try { stale.disconnect(); } catch (SecurityException ignored) {}
            try { stale.close(); } catch (RuntimeException ignored) {}
        }
        if (target == null) {
            fail(ErrorKind.DISCONNECTED, "Bluetooth connection ended. Reconnect to continue.");
            return;
        }
        try {
            gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            fail(ErrorKind.PERMISSION, "Bluetooth connect permission is required");
            return;
        } catch (RuntimeException e) {
            fail(ErrorKind.OTHER, "Could not connect to this Bluetooth device");
            return;
        }
        if (gatt == null) {
            fail(ErrorKind.OTHER, "Could not open a Bluetooth connection");
        }
        // The single absolute handshake timeout from connect() still bounds every retry.
    }

    @SuppressLint("MissingPermission")
    private static BluetoothDevice getChangedDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private void registerBondReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(bondReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (!receiverRegistered) return;
        try { context.unregisterReceiver(bondReceiver); }
        catch (IllegalArgumentException ignored) {}
        receiverRegistered = false;
    }

    private void unsupported(String message) {
        finished = true;
        cancelTimers();
        closeGatt();
        if (listener != null) listener.onUnsupported(message);
    }

    private void fail(ErrorKind kind, String message) {
        if (finished) return;
        String result = kind == ErrorKind.TIMEOUT ? "timed_out"
                : kind == ErrorKind.BUSY ? "busy"
                : kind == ErrorKind.TETHERING_OFF ? "tethering_off" : "error";
        diagnostic(lastPhase, lastCode, result);
        finished = true;
        cancelTimers();
        closeGatt();
        error(kind, message);
    }

    private void error(ErrorKind kind, String message) {
        if (listener != null) listener.onError(kind, message);
    }

    private void notifyState(String message) {
        if (listener != null) listener.onState(message);
    }

    private void setPhase(String phase) {
        if (!phase.equals(lastPhase)) {
            lastPhase = phase;
            lastCode = NO_CODE;
        }
    }

    private void diagnostic(String phase, int code, String result) {
        setPhase(phase);
        if (code != NO_CODE) lastCode = code;
        if (listener != null) listener.onDiagnostic(phase, code, result);
    }

    private void panDiagnostic(int payloadBytes, String category) {
        setPhase("pan_result");
        lastCode = NO_CODE;
        if (listener != null) listener.onPanDiagnostic(payloadBytes, category);
    }

    private static String panCategory(OduProtocol.PanAddressCategory category) {
        switch (category) {
            case EMPTY: return "empty";
            case INVALID_IPV4_FORMAT: return "invalid_ipv4_format";
            case IPV4_OUTSIDE_POLICY: return "ipv4_outside_policy";
            case PAN_INTERFACE_MISSING: return "pan_interface_missing";
            case PAN_INTERFACE_UNAVAILABLE: return "pan_interface_unavailable";
            case AMBIGUOUS_ROUTE: return "ambiguous_route";
            case VALID_ADDRESS: return "valid_address";
            default: return "invalid_ipv4_format";
        }
    }

    private static String bondResult(int state) {
        if (state == BluetoothDevice.BOND_BONDING) return "bonding";
        if (state == BluetoothDevice.BOND_BONDED) return "bonded";
        return "unbonded";
    }

    private void cancelTimers() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(discoverLater);
        main.removeCallbacks(readLater);
        main.removeCallbacks(readWatchdog);
        main.removeCallbacks(bondPoll);
        main.removeCallbacks(subscribeRetry);
        main.removeCallbacks(reconnectLater);
        reconnectScheduled = false;
    }

    /**
     * Cancels the state-machine timers but keeps the absolute handshake deadline
     * and the bond poll: pairing can finish while the link is reconnecting.
     */
    private void cancelHandshakeTimers() {
        main.removeCallbacks(discoverLater);
        main.removeCallbacks(readLater);
        main.removeCallbacks(readWatchdog);
        main.removeCallbacks(subscribeRetry);
        main.removeCallbacks(reconnectLater);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        unregisterBondReceiver();
        BluetoothGatt current = gatt;
        gatt = null;
        if (current != null) {
            try { current.disconnect(); } catch (SecurityException ignored) {}
            try { current.close(); } catch (RuntimeException ignored) {}
        }
        connected = false;
        discovery = null;
        pan = null;
        pendingCccd = null;
        device = null;
        writePending = false;
        cccdSubscribed = false;
        subscribeAttempts = 0;
    }

    public boolean isSupported() { return supported && !finished; }

    public boolean hasHost() { return hostDelivered && !finished; }

    public void disconnect() {
        finished = true;
        supported = false;
        hostDelivered = false;
        bondConfirmed = false;
        bondRequested = false;
        preferDirectRead = false;
        reconnects = 0;
        securityReconnectTried = false;
        readAttempts = 0;
        bondPolls = 0;
        cancelTimers();
        closeGatt();
    }

    public void release() {
        disconnect();
        listener = null;
    }
}
