package io.github.wpfyorg.afhelper.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLHandshakeException;

/**
 * ODU eSIM, signal, and device actions for a discovered private ODU host.
 *
 * <p>Two ODU WebUI families are supported behind one login flow:
 * <ul>
 *   <li>JFE ({@code /cgi-bin/app/*}) with a session cookie, used by the
 *       JioHome / JFE app and the handset-verified AF BLE flow.</li>
 *   <li>CM ({@code /cgi-bin/cgi/*}) with a rotating one-time token, used by
 *       the Foxconn WebUI on CM-family units.</li>
 * </ul>
 *
 * <p>Endpoint, request, and response shapes are taken from the decompiled
 * vendor apps. Session cookies, tokens, passwords, ICCIDs, and response
 * payloads stay in memory for one action; they are never logged or persisted.
 */
public final class OduWebActions {
    public enum ErrorKind {
        AUTH_REJECTED, ROUTE_UNAVAILABLE, CLIENT_CERT_REQUIRED, HTTP, UNSUPPORTED, OTHER
    }

    /** Which ODU WebUI API family answered the login. */
    public enum Family { JFE, CM }

    /** One ODU eSIM profile row as the WebUI reports it. */
    public static final class ProfileRow {
        public final String iccid;
        public final String id;
        public final String name;
        public final String provider;
        public final int profileClass;
        public final boolean enabled;

        ProfileRow(String iccid, String id, String name, String provider, int profileClass,
                   boolean enabled) {
            this.iccid = iccid;
            this.id = id;
            this.name = name;
            this.provider = provider;
            this.profileClass = profileClass;
            this.enabled = enabled;
        }
    }

    /** Device identity and profile snapshot read after one login. */
    public static final class DeviceDetails {
        public final Family family;
        public final String eid;
        public final String model;
        public final String software;
        public final List<ProfileRow> profiles;

        DeviceDetails(Family family, String eid, String model, String software,
                      List<ProfileRow> profiles) {
            this.family = family;
            this.eid = eid;
            this.model = model;
            this.software = software;
            this.profiles = profiles;
        }
    }

    /** Serving-cell NR signal values plus the vendor's coverage classification. */
    public static final class SignalReport {
        public final String cellId;
        public final Double rsrp;
        public final Double rsrq;
        public final Double snr;
        public final String band;
        public final String bandwidth;
        public final String pci;
        public final String tac;
        public final Quality quality;

        enum Quality { STRONG, MODERATE, WEAK, UNKNOWN }

        SignalReport(String cellId, Double rsrp, Double rsrq, Double snr, String band,
                     String bandwidth, String pci, String tac) {
            this.cellId = cellId;
            this.rsrp = rsrp;
            this.rsrq = rsrq;
            this.snr = snr;
            this.band = band;
            this.bandwidth = bandwidth;
            this.pci = pci;
            this.tac = tac;
            this.quality = classify();
        }

        private Quality classify() {
            if (rsrp == null || snr == null) return Quality.UNKNOWN;
            if (rsrp > -90 && snr > 0) return Quality.STRONG;
            if (rsrp > -109 && snr > 0) return Quality.MODERATE;
            return Quality.WEAK;
        }

        public String qualityLabel() {
            switch (quality) {
                case STRONG: return "Strong ODU signal";
                case MODERATE: return "Moderate ODU signal";
                case WEAK: return "Weak ODU signal — consider repositioning the ODU";
                default: return "ODU signal values are not reported by this unit right now";
            }
        }

        /**
         * Maps the reported RSRP/SINR pair to a 0–100 meter score. RSRP carries
         * the base reading; SINR raises or lowers it. Returns -1 when neither
         * value is available.
         */
        public int strengthPercent() {
            if (rsrp == null && snr == null) return -1;
            double score;
            if (rsrp != null) {
                double clamped = Math.max(-130, Math.min(-60, rsrp));
                score = (clamped + 130) / 70.0 * 100.0;
            } else {
                double clamped = Math.max(-10, Math.min(30, snr));
                score = (clamped + 10) / 40.0 * 100.0;
            }
            if (rsrp != null && snr != null) {
                if (snr < 0) score -= 20;
                else if (snr < 5) score -= 8;
                else if (snr >= 20) score += 8;
                else if (snr >= 13) score += 4;
            }
            return (int) Math.max(0, Math.min(100, Math.round(score)));
        }

        public String strengthLabel() {
            int percent = strengthPercent();
            if (percent < 0) return "signal values not reported";
            if (percent >= 80) return "strong";
            if (percent >= 60) return "good";
            if (percent >= 40) return "fair";
            return "weak";
        }
    }

    public interface Listener {
        /** Progress text only; never identifiers, credentials, cookies, or payloads. */
        void onState(String message);

        void onDetails(DeviceDetails details);

        void onSignal(SignalReport report);

        void onProfiles(String headline, List<ProfileRow> rows);

        void onProfileActionDone(String message, List<ProfileRow> rows);

        /** A completed non-profile action, such as an accepted reboot. */
        void onOperationDone(String message);

        void onError(ErrorKind kind, String message);
    }

    private static final long INSTALL_POLL_INTERVAL_MS = 2_000;
    private static final int INSTALL_POLL_MAX_SECONDS = 90;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OduEndpoints endpoints;
    private Listener listener;
    private volatile boolean closed;
    private volatile long generation;
    private volatile String authModel = "OD503";

    public OduWebActions(Context context, Listener listener) {
        this.endpoints = new OduEndpoints(context);
        this.listener = listener;
    }

    private String authPayload(String username, String password) {
        return "{\"usr\":" + OduProtocol.jsonString(username)
                + ",\"pwd\":" + OduProtocol.jsonString(password)
                + ",\"model\":" + OduProtocol.jsonString(authModel) + "}";
    }

    /** Selects the model reported by the sticker QR; the vendor auth field is model-sensitive. */
    public void setAuthModel(String model) {
        String normalized = OduQrCode.normalizeModel(model);
        authModel = normalized.isEmpty() ? "OD503" : normalized;
    }

    /** One authenticated ODU session; valid for one action only. */
    private static final class Session {
        final String host;
        final String cookie;
        final String password;
        final Family family;
        final OduCmClient cm;
        final long generation;

        Session(String host, String cookie, String password, Family family, OduCmClient cm,
                long generation) {
            this.host = host;
            this.cookie = cookie;
            this.password = password;
            this.family = family;
            this.cm = cm;
            this.generation = generation;
        }
    }

    private interface Action {
        void run(Session session) throws Exception;
    }

    /** Authenticates once, then runs the requested action with that session. */
    private synchronized void start(String host, String username, char[] password, Action action) {
        if (password == null) return;
        if (closed) {
            Arrays.fill(password, '\0');
            return;
        }
        if (!OduProtocol.isOduPanHost(host)) {
            Arrays.fill(password, '\0');
            postError(generation, ErrorKind.OTHER, "Connect to a supported ODU first.");
            return;
        }
        if (username == null || username.trim().isEmpty() || password.length == 0) {
            Arrays.fill(password, '\0');
            postError(generation, ErrorKind.AUTH_REJECTED,
                    "Enter the ODU username and password to continue.");
            return;
        }
        final long requestGeneration = ++generation;
        final char[] secret = password.clone();
        Arrays.fill(password, '\0');
        final String user = username.trim();
        postState(requestGeneration, "Signing in to the ODU");
        endpoints.beginSession();
        io.submit(() -> {
            try {
                String passCopy = new String(secret);
                Session session = authenticate(host, user, passCopy, requestGeneration);
                action.run(session);
            } catch (InterruptedException e) {
                // Superseded or closed; post nothing.
            } catch (Exception e) {
                reportCatch(requestGeneration, e);
            } finally {
                Arrays.fill(secret, '\0');
            }
        });
    }

    private Session authenticate(String host, String user, String password, long expectedGeneration)
            throws Exception {
        OduEndpoints.Response jfe = endpoints.request(host, "/cgi-bin/app/account/auth", "POST",
                authPayload(user, password), null);
        ensureCurrent(expectedGeneration);
        boolean jfeAccepted = !OduProtocol.isAuthRejected(jfe.code, jfe.body)
                && !jfe.cookie.isEmpty();
        if (jfeAccepted) {
            return new Session(host, jfe.cookie, password, Family.JFE, null, expectedGeneration);
        }

        // CM-family firmware does not expose the JFE account endpoint. A 404/405
        // (or a non-JSON landing page with no session cookie) is the family hint;
        // a JSON result:false or 401/403 means the credentials were rejected.
        boolean tryCm = jfe.code == 404 || jfe.code == 405
                || (!OduProtocol.isStrictJsonDocument(jfe.body) && jfe.cookie.isEmpty());
        if (!tryCm) {
            throw new AuthRejectedException(
                    "ODU login was rejected. Check the credentials and try again.");
        }
        try {
            OduCmClient cm = new OduCmClient(endpoints);
            if (cm.login(host, user, password)) {
                return new Session(host, "", password, Family.CM, cm, expectedGeneration);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception cmFailure) {
            throw new AuthRejectedException(
                    "This ODU did not accept the WebUI login. Check the credentials and retry.");
        }
        throw new AuthRejectedException(
                "ODU login was rejected. Check the credentials and try again.");
    }

    // ------------------------------------------------------------------ details

    /** Reads EID, model/software, and the installed eSIM profiles after one login. */
    public void loadDetails(String host, String username, char[] password) {
        start(host, username, password, session -> {
            DeviceDetails details = readDetails(session);
            ensureCurrent(session.generation);
            post(result -> listener.onDetails(details), session.generation);
        });
    }

    private DeviceDetails readDetails(Session session) throws Exception {
        if (session.family == Family.CM) {
            List<ProfileRow> rows = fetchProfiles(session);
            return new DeviceDetails(Family.CM, null, null, null, rows);
        }
        ensureCurrent(session.generation);
        OduEndpoints.Response info = endpoints.request(session.host,
                "/cgi-bin/app/esim/device_info", "GET", null, session.cookie);
        ensureCurrent(session.generation);
        OduEndpoints.Response eid = endpoints.request(session.host,
                "/cgi-bin/app/esim/eid", "GET", null, session.cookie);
        ensureCurrent(session.generation);
        List<ProfileRow> rows = fetchProfiles(session);
        return new DeviceDetails(Family.JFE,
                OduProtocol.isHttpSuccess(eid.code) ? parseEid(eid.body) : null,
                OduProtocol.isHttpSuccess(info.code) ? parseModel(info.body) : null,
                OduProtocol.isHttpSuccess(info.code) ? parseSoftware(info.body) : null,
                rows);
    }

    public void listProfiles(String host, String username, char[] password) {
        start(host, username, password, session -> {
            List<ProfileRow> rows = fetchProfiles(session);
            ensureCurrent(session.generation);
            post(result -> listener.onProfiles(describe(rows), rows), session.generation);
        });
    }

    private List<ProfileRow> fetchProfiles(Session session) throws Exception {
        ensureCurrent(session.generation);
        if (session.family == Family.CM) {
            String raw = session.cm.profiles(session.host);
            ensureCurrent(session.generation);
            List<ProfileRow> rows = parseCmProfiles(raw);
            if (rows == null) throw new OduWebActionsHttpException("the ODU eSIM profiles");
            return rows;
        }
        OduEndpoints.Response list = endpoints.request(session.host,
                "/cgi-bin/app/esim/profiles", "GET", null, session.cookie);
        ensureCurrent(session.generation);
        if (!OduProtocol.isHttpSuccess(list.code)) {
            throw new OduWebActionsHttpException("the ODU eSIM profiles (HTTP " + list.code + ")");
        }
        List<ProfileRow> rows = parseProfiles(list.body);
        if (rows == null) throw new OduWebActionsHttpException("the ODU eSIM profiles");
        return rows;
    }

    // ------------------------------------------------------------------ signal

    public void checkSignal(String host, String username, char[] password) {
        start(host, username, password, session -> {
            SignalReport report = readSignal(session);
            ensureCurrent(session.generation);
            post(result -> listener.onSignal(report), session.generation);
        });
    }

    private SignalReport readSignal(Session session) throws Exception {
        if (session.family == Family.CM) {
            Map<String, String> raw = session.cm.netStatus(session.host);
            ensureCurrent(session.generation);
            return parseCmSignal(raw);
        }
        OduEndpoints.Response list = endpoints.request(session.host,
                "/cgi-bin/app/installer/cur_cell_id_list", "GET", null, session.cookie);
        ensureCurrent(session.generation);
        if (!OduProtocol.isHttpSuccess(list.code)) {
            throw new OduWebActionsHttpException(
                    "the ODU cellular cells (HTTP " + list.code + ")");
        }
        String cellId = servingCellId(list.body);
        if (cellId == null) {
            throw new OduWebActionsHttpException("a nearby cellular cell");
        }
        postState(session.generation, "Reading the serving cell info");
        OduEndpoints.Response info = endpoints.request(session.host,
                "/cgi-bin/app/installer/cell_id_info", "POST",
                "{\"cell_id\":" + OduProtocol.jsonString(cellId) + "}", session.cookie);
        ensureCurrent(session.generation);
        if (!OduProtocol.isHttpSuccess(info.code)) {
            throw new OduWebActionsHttpException(
                    "the ODU cell info (HTTP " + info.code + ")");
        }
        return parseSignal(cellId, info.body);
    }

    private static String servingCellId(String body) {
        return parseServingCellId(body);
    }

    /** Package-visible for pure JVM tests; pure parsing with no device access. */
    static String parseServingCellId(String body) {
        if (!strictJson(body)) return null;
        try {
            JSONArray cells = new JSONObject(body).optJSONArray("CELL_LIST");
            if (cells == null || cells.length() == 0) return null;
            String first = null;
            for (int i = 0; i < cells.length(); i++) {
                JSONObject cell = cells.optJSONObject(i);
                if (cell == null) continue;
                String id = optText(cell, "CELL_ID");
                if (id == null) continue;
                if (first == null) first = id;
                if (cell.optInt("IS_SERVING", 0) == 1) return id;
            }
            return first;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Package-visible for pure JVM tests; pure parsing with no device access. */
    static SignalReport parseSignal(String cellId, String body) {
        if (!strictJson(body)) {
            return new SignalReport(cellId, null, null, null, null, null, null, null);
        }
        try {
            JSONObject nr = new JSONObject(body).optJSONObject("NR_5G");
            if (nr == null) {
                return new SignalReport(cellId, null, null, null, null, null, null, null);
            }
            return new SignalReport(cellId,
                    optMeasure(nr, "nr_rsrp"), optMeasure(nr, "nr_rsrq"),
                    optMeasure(nr, "nr_snr"),
                    optText(nr, "nr_band"), optText(nr, "nr_band_width"),
                    optText(nr, "nr_pci"), optText(nr, "nr_tac"));
        } catch (JSONException | RuntimeException e) {
            return new SignalReport(cellId, null, null, null, null, null, null, null);
        }
    }

    /** CM net-status keys, tolerant of firmware naming differences. */
    static SignalReport parseCmSignal(Map<String, String> raw) {
        if (raw == null) {
            return new SignalReport(null, null, null, null, null, null, null, null);
        }
        return new SignalReport(
                first(raw, "cell_id", "cellId", "CELL_ID", "cellid"),
                measure(first(raw, "rsrp", "RSRP", "nr_rsrp")),
                measure(first(raw, "rsrq", "RSRQ", "nr_rsrq")),
                measure(first(raw, "sinr", "SINR", "snr", "nr_snr")),
                first(raw, "band", "BAND", "nr_band", "band_info"),
                first(raw, "bandwidth", "band_width", "BANDWIDTH", "nr_band_width"),
                first(raw, "pcid", "pci", "PCI", "nr_pci"),
                first(raw, "tac", "TAC", "nr_tac"));
    }

    private static String first(Map<String, String> map, String... keys) {
        for (String key : keys) {
            String value = map.get(key);
            if (value != null && !value.trim().isEmpty()
                    && !value.trim().equalsIgnoreCase("NA")) {
                return value.trim();
            }
        }
        return null;
    }

    private static Double measure(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replace("dBm", "").replace("dB", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double optMeasure(JSONObject object, String name) {
        String raw = optText(object, name);
        if (raw == null || raw.trim().isEmpty() || raw.contains("NA")) return null;
        return measure(raw);
    }

    private static String optText(JSONObject object, String name) {
        Object value = object.opt(name);
        if (value == null || JSONObject.NULL.equals(value)) return null;
        String text = String.valueOf(value).trim();
        // The ODU reports unavailable values as the token NA; treat them as absent.
        if (text.isEmpty() || text.equalsIgnoreCase("NA")) return null;
        return text;
    }

    // ---------------------------------------------------------------- eSIM actions

    public void installDefaultProfile(String host, String username, char[] password) {
        start(host, username, password, session -> {
            if (session.family == Family.CM) {
                postError(session.generation, ErrorKind.UNSUPPORTED,
                        "This ODU needs a full LPA:1 activation code. Use Add eSIM and paste the code.");
                return;
            }
            List<ProfileRow> before = fetchProfiles(session);
            for (ProfileRow row : before) {
                if (row.enabled && row.profileClass == 1) {
                    post(result -> listener.onProfileActionDone(
                            "An eSIM profile is already active on the ODU.", before),
                            session.generation);
                    return;
                }
            }
            OduEndpoints.Response installed = endpoints.request(session.host,
                    "/cgi-bin/app/esim/profile_install_by_default_smdp", "POST",
                    "{\"auto_enable_profile\":true}", session.cookie,
                    OduEndpoints.LONG_TIMEOUT_MS);
            ensureCurrent(session.generation);
            if (!OduProtocol.isHttpSuccess(installed.code)
                    || OduProtocol.hasResultFalse(installed.body)) {
                postError(session.generation, ErrorKind.HTTP,
                        "The ODU did not accept the profile install request."
                                + vendorMessage(installed.body));
                return;
            }
            postState(session.generation, "Waiting for the ODU to activate the profile");
            awaitNewActiveProfile(session, before);
        });
    }

    /** CM-family personal eSIM install from a full {@code LPA:1$...} activation code. */
    public void installByActivationCode(String host, String username, char[] password,
                                        String activationCode) {
        final String code = activationCode == null ? "" : activationCode.trim();
        start(host, username, password, session -> {
            if (!isActivationCode(code)) {
                postError(session.generation, ErrorKind.OTHER,
                        "Enter a full LPA:1 activation code, for example LPA:1$sm-dp+$matching-id.");
                return;
            }
            if (session.family != Family.CM) {
                postError(session.generation, ErrorKind.UNSUPPORTED,
                        "This ODU installs its default Jio profile. Use Add default Jio eSIM.");
                return;
            }
            List<ProfileRow> before = fetchProfiles(session);
            OduCmClient.Result installed = session.cm.installByCode(session.host, code, true);
            ensureCurrent(session.generation);
            if (!installed.ok() || !installed.resultTrue()) {
                postError(session.generation, ErrorKind.HTTP,
                        "The ODU did not accept the activation code."
                                + vendorMessage(installed.body));
                return;
            }
            postState(session.generation, "The ODU accepted the code. Waiting for the profile");
            awaitNewActiveProfile(session, before);
        });
    }

    private void awaitNewActiveProfile(Session session, List<ProfileRow> before) throws Exception {
        long deadline = System.currentTimeMillis() + INSTALL_POLL_MAX_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(INSTALL_POLL_INTERVAL_MS);
            ensureCurrent(session.generation);
            List<ProfileRow> now = fetchProfiles(session);
            ProfileRow active = becameActive(before, now, null);
            if (active != null) {
                post(result -> listener.onProfileActionDone(
                        "The ODU reported the new eSIM profile is active.", now),
                        session.generation);
                return;
            }
            long secondsLeft = Math.max(1,
                    (deadline - System.currentTimeMillis() + 999) / 1000);
            postState(session.generation, "ODU is still installing the profile; waiting up to "
                    + secondsLeft + " s more");
        }
        List<ProfileRow> timeoutRows = fetchProfiles(session);
        post(result -> listener.onProfileActionDone(
                "The ODU accepted the install, but no active eSIM profile appeared within this "
                        + "app's wait window. Reopen this view later to check.", timeoutRows),
                session.generation);
    }

    public void setProfileEnabled(String host, String username, char[] password,
                                  ProfileRow row, boolean enable) {
        start(host, username, password, session -> {
            if (row == null) {
                postError(session.generation, ErrorKind.OTHER,
                        "The selected eSIM profile is unavailable.");
                return;
            }
            boolean accepted = false;
            String vendor = "";
            if (session.family == Family.CM) {
                if (!isIccid(row.iccid)) {
                    postError(session.generation, ErrorKind.OTHER,
                            "The selected eSIM profile has no valid ICCID.");
                    return;
                }
                OduCmClient.Result result = session.cm.config(
                        session.host, row.iccid, enable);
                ensureCurrent(session.generation);
                accepted = result.ok() && result.resultTrue();
                vendor = vendorMessage(result.body);
            } else {
                String[] targets = profileTargets(row);
                if (targets.length == 0) {
                    postError(session.generation, ErrorKind.OTHER,
                            "The selected eSIM profile has no valid WebUI id.");
                    return;
                }
                /* Firmware differs in how it addresses a profile: some take the
                 * numeric WebUI id, some only the ICCID (a numeric id there answers
                 * result:false with msg_id 4352). Try ICCID first, then the id. */
                for (String target : targets) {
                    OduEndpoints.Response result = endpoints.request(session.host,
                            "/cgi-bin/app/esim/profile/" + target, "PUT",
                            "{\"state\":" + (enable ? "true" : "false") + "}", session.cookie);
                    ensureCurrent(session.generation);
                    accepted = OduProtocol.isHttpSuccess(result.code)
                            && !OduProtocol.hasResultFalse(result.body);
                    vendor = vendorMessage(result.body);
                    if (accepted) break;
                }
            }
            if (!accepted) {
                postError(session.generation, ErrorKind.HTTP,
                        "The ODU did not accept the profile change." + vendor);
                return;
            }
            List<ProfileRow> rows = fetchProfiles(session);
            post(result -> listener.onProfileActionDone(
                    enable ? "The ODU enabled the selected eSIM profile."
                            : "The ODU disabled the selected eSIM profile.", rows),
                    session.generation);
        });
    }

    public void deleteProfile(String host, String username, char[] password, String iccIdDigits) {
        start(host, username, password, session -> {
            if (!isIccid(iccIdDigits)) {
                postError(session.generation, ErrorKind.OTHER,
                        "The selected eSIM profile has no valid ICCID.");
                return;
            }
            if (session.family == Family.CM) {
                OduCmClient.Result deleted = session.cm.delete(
                        session.host, iccIdDigits, session.password);
                ensureCurrent(session.generation);
                if (!deleted.ok() || !deleted.resultTrue()) {
                    postError(session.generation, ErrorKind.HTTP,
                            "The ODU declined to delete that eSIM profile."
                                    + vendorMessage(deleted.body));
                    return;
                }
            } else {
                OduEndpoints.Response deleted = endpoints.request(session.host,
                        "/cgi-bin/app/esim/profile/" + iccIdDigits, "DELETE", null,
                        session.cookie);
                ensureCurrent(session.generation);
                if (!OduProtocol.isHttpSuccess(deleted.code)
                        || OduProtocol.hasResultFalse(deleted.body)) {
                    postError(session.generation, ErrorKind.HTTP,
                            "The ODU declined to delete that eSIM profile."
                                    + vendorMessage(deleted.body));
                    return;
                }
            }
            List<ProfileRow> rows = fetchProfiles(session);
            post(result -> listener.onProfileActionDone(
                    "The ODU deleted the selected eSIM profile.", rows), session.generation);
        });
    }

    public void reboot(String host, String username, char[] password) {
        start(host, username, password, session -> {
            if (session.family == Family.CM) {
                postError(session.generation, ErrorKind.UNSUPPORTED,
                        "This ODU's WebUI does not expose a reboot action to this app.");
                return;
            }
            OduEndpoints.Response rebooted = endpoints.request(session.host,
                    "/cgi-bin/app/esim/reboot", "POST", null, session.cookie);
            ensureCurrent(session.generation);
            if (!OduProtocol.isHttpSuccess(rebooted.code)
                    || OduProtocol.hasResultFalse(rebooted.body)) {
                postError(session.generation, ErrorKind.HTTP,
                        "The ODU did not accept the reboot request."
                                + vendorMessage(rebooted.body));
                return;
            }
            post(result -> listener.onOperationDone(
                    "The ODU accepted the reboot request. Bluetooth may drop while it restarts."),
                    session.generation);
        });
    }

    // ------------------------------------------------------------- pure parsing

    /** Package-visible for pure JVM tests. */
    static boolean isActivationCode(String code) {
        if (code == null) return false;
        String value = code.trim();
        if (!value.startsWith("LPA:1$")) return false;
        if (value.length() < 12 || value.length() > 512) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) return false;
        }
        String[] parts = value.split("\\$", -1);
        return parts.length == 3 && parts[0].equals("LPA:1")
                && !parts[1].isEmpty() && !parts[2].isEmpty();
    }

    /** WebUI identifiers for one profile, ICCID first: firmware addressing differs. */
    static String[] profileTargets(ProfileRow row) {
        List<String> targets = new ArrayList<>();
        if (row != null) {
            if (isIccid(row.iccid)) targets.add(row.iccid);
            if (isProfileId(row.id) && !targets.contains(row.id)) targets.add(row.id);
        }
        return targets.toArray(new String[0]);
    }

    private static boolean isIccid(String iccid) {
        return iccid != null && iccid.matches("[0-9]{8,24}");
    }

    private static boolean isProfileId(String id) {
        return id != null && id.matches("[0-9]{1,32}(?:-[0-9]{1,32})?");
    }

    /** Package-visible for pure JVM tests. */
    static String parseEid(String body) {
        if (!strictJson(body)) return null;
        try {
            JSONObject root = new JSONObject(body);
            if (Boolean.FALSE.equals(root.opt("result"))) return null;
            String eid = optText(root, "eid");
            return eid != null && eid.matches("[0-9]{32}") ? eid : null;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Package-visible for pure JVM tests. */
    static String parseModel(String body) {
        return deviceInfoField(body, "model");
    }

    /** Package-visible for pure JVM tests. */
    static String parseSoftware(String body) {
        return deviceInfoField(body, "CurrentSoftVer");
    }

    private static String deviceInfoField(String body, String field) {
        if (!strictJson(body)) return null;
        try {
            JSONObject root = new JSONObject(body);
            if (Boolean.FALSE.equals(root.opt("result"))) return null;
            return optText(root, field);
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Package-visible for pure JVM tests; pure parsing with no device access. */
    static List<ProfileRow> parseProfiles(String body) {
        if (!strictJson(body)) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONArray list = root.optJSONArray("profile_info_list");
            if (list == null) return null;
            return parseProfileArray(list);
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** CM pages render the same profile objects as a JSON array. */
    static List<ProfileRow> parseCmProfiles(String body) {
        String raw = body == null ? "" : body.trim();
        if (raw.isEmpty()) return new ArrayList<>();
        if (!strictJson(raw)) return null;
        try {
            if (raw.startsWith("[")) {
                return parseProfileArray(new JSONArray(raw));
            }
            JSONArray list = new JSONObject(raw).optJSONArray("profile_info_list");
            return list == null ? null : parseProfileArray(list);
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    private static List<ProfileRow> parseProfileArray(JSONArray list) {
        List<ProfileRow> rows = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String iccid = optText(item, "iccid");
            String id = optText(item, "id");
            int profileClass = item.optInt("profile_class", -1);
            String name = optText(item, "profile_name");
            String provider = optText(item, "service_provider_name");
            int state = item.optInt("profile_state", -1);
            rows.add(new ProfileRow(iccid, id, name, provider, profileClass, state == 1));
        }
        return rows;
    }

    private static String describe(List<ProfileRow> rows) {
        if (rows.isEmpty()) return "The ODU eSIM has no installed profiles.";
        return "The ODU eSIM has " + rows.size()
                + (rows.size() == 1 ? " profile." : " profiles.");
    }

    private static String vendorMessage(String body) {
        if (!strictJson(body)) return "";
        try {
            String message = new JSONObject(body).optString("message", "").trim();
            return message.isEmpty() ? "" : " The ODU said: " + message;
        } catch (JSONException | RuntimeException e) {
            return "";
        }
    }

    /** Package-visible for pure JVM tests. */
    static ProfileRow becameActiveForTest(List<ProfileRow> before, List<ProfileRow> now,
                                          String targetIccid) {
        return becameActive(before, now, targetIccid);
    }

    /** Vendor's success predicate: an enabled provisioning (class 1) profile. */
    private static ProfileRow becameActive(List<ProfileRow> before, List<ProfileRow> now,
                                           String targetIccid) {
        for (ProfileRow row : now) {
            if (!row.enabled || row.profileClass != 1) continue;
            if (row.iccid != null && row.iccid.equals(targetIccid)) return row;
            boolean wasEnabledBefore = false;
            for (ProfileRow prior : before) {
                if (prior.iccid != null && prior.iccid.equals(row.iccid) && prior.enabled) {
                    wasEnabledBefore = true;
                }
            }
            if (!wasEnabledBefore) return row;
        }
        return null;
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean strictJson(String text) {
        return OduProtocol.isStrictJsonDocument(text);
    }

    private static final class AuthRejectedException extends Exception {
        AuthRejectedException(String message) {
            super(message);
        }
    }

    private static final class OduWebActionsHttpException extends RuntimeException {
        OduWebActionsHttpException(String subject) {
            super("The ODU did not return " + subject + ".");
        }
    }

    private void ensureCurrent(long expectedGeneration) throws InterruptedException {
        if (closed || generation != expectedGeneration || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Session ended");
        }
    }

    private void reportCatch(long requestGeneration, Exception error) {
        if (findException(error, AuthRejectedException.class) != null) {
            postError(requestGeneration, ErrorKind.AUTH_REJECTED,
                    error.getMessage() == null
                            ? "ODU login was rejected. Check the credentials and try again."
                            : error.getMessage());
            return;
        }
        if (findException(error, OduWebActionsHttpException.class) != null) {
            postError(requestGeneration, ErrorKind.HTTP,
                    error.getMessage() == null
                            ? "The ODU request could not be completed." : error.getMessage());
            return;
        }
        if (findException(error, SSLHandshakeException.class) != null) {
            postError(requestGeneration, ErrorKind.CLIENT_CERT_REQUIRED,
                    "This unit may require a TLS client certificate. This app does not include one; use an authorized vendor-supported method.");
            return;
        }
        if (findException(error, UnknownHostException.class) != null
                || findException(error, ConnectException.class) != null
                || findException(error, NoRouteToHostException.class) != null
                || findException(error, SocketTimeoutException.class) != null) {
            postError(requestGeneration, ErrorKind.ROUTE_UNAVAILABLE,
                    "Could not reach the ODU over the local network. Check Bluetooth tethering/PAN in Settings, return here, and retry.");
            return;
        }
        postError(requestGeneration, ErrorKind.OTHER,
                "Could not complete the ODU request. Reconnect and retry.");
    }

    private static Throwable findException(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return current;
        }
        return null;
    }

    /** Supersedes and stops any running action. */
    public synchronized void cancel() {
        generation++;
        endpoints.cancel();
    }

    public synchronized void release() {
        cancel();
        closed = true;
        io.shutdownNow();
        listener = null;
        main.removeCallbacksAndMessages(null);
    }

    private interface Callback {
        void apply(Listener listener);
    }

    private void post(final Callback callback, final long expectedGeneration) {
        main.post(() -> {
            if (closed || generation != expectedGeneration) return;
            if (listener != null) callback.apply(listener);
        });
    }

    private void postState(long expectedGeneration, String message) {
        post(result -> listener.onState(message), expectedGeneration);
    }

    private void postError(long expectedGeneration, ErrorKind kind, String message) {
        post(result -> listener.onError(kind, message), expectedGeneration);
    }
}
