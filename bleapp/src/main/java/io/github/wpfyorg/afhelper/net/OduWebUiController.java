package io.github.wpfyorg.afhelper.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.SSLHandshakeException;

/**
 * Performs the authenticated GUI-state/enable/disable sequence for a discovered
 * private ODU host. Only the validated local host and the two WebUI ports are
 * addressed; credentials and cookies stay in memory for one action.
 */
public final class OduWebUiController {
    public enum ErrorKind { AUTH_REJECTED, API_FAMILY_NOT_FOUND, ROUTE_UNAVAILABLE, CLIENT_CERT_REQUIRED, HTTP, OTHER }

    public interface Listener {
        void onState(String message);
        void onReady(String privateHost);
        void onError(ErrorKind kind, String message);

        /** Result of an explicit WebUI state check. */
        default void onWebUiState(boolean enabled) { }

        /** Whether this firmware exposes a WebUI enable/disable switch. */
        default void onWebUiToggleSupport(boolean supported) { }

        /** The ODU accepted an enable or disable request. */
        default void onOperationDone(boolean enabled) { }

        /** Link-test latency in milliseconds. */
        default void onPing(long latencyMs) { }

        /** No recognized WebUI page was served; the caller may prompt for credentials. */
        default void onWebUiNotServed() { }
    }

    private interface Action {
        void run(Session session) throws Exception;
    }

    private static final class Session {
        final String host;
        final String cookie;
        final long generation;

        Session(String host, String cookie, long generation) {
            this.host = host;
            this.cookie = cookie;
            this.generation = generation;
        }
    }

    private static final class AuthRejectedException extends Exception { }

    private static final class ApiFamilyNotFoundException extends Exception { }

    private static final class UiProbe {
        final OduProtocol.UiFamily family;
        final String path;

        UiProbe(OduProtocol.UiFamily family, String path) {
            this.family = family;
            this.path = path;
        }

        boolean served() {
            return family != null && family.isServed();
        }

        boolean toggleSupported() {
            return served() && !family.isSpa();
        }
    }

    private static final class OduHttpException extends Exception {
        OduHttpException(String message) { super(message); }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OduEndpoints endpoints;
    private Listener listener;
    private volatile boolean closed;
    private volatile String activeHost;
    private volatile String sessionCookie;
    private Future<?> activeRequest;
    private volatile long generation;

    public OduWebUiController(Context context, Listener listener) {
        this.endpoints = new OduEndpoints(context);
        this.listener = listener;
    }

    public void enableAndOpen(String discoveredHost, String username, char[] password) {
        enableAndOpen(discoveredHost, username, password, null);
    }

    public synchronized void enableAndOpen(String discoveredHost, String username, char[] password,
                                           String model) {
        start(discoveredHost, username, password, model, true, true, session -> {
            OduEndpoints.Response before = request(session, "/cgi-bin/app/esim/gui_state",
                    "GET", null, null);
            ensureCurrent(session.generation);
            boolean stateReadable = OduProtocol.isHttpSuccess(before.code)
                    && OduProtocol.isStrictJsonDocument(before.body);
            boolean alreadyEnabled = stateReadable && OduProtocol.isGuiEnabled(before.body);
            postWebUiToggleSupport(session.generation, stateReadable);
            if (alreadyEnabled) {
                postWebUiState(session.generation, true);
                postReady(session.generation, session.host);
                return;
            }
            /* Some SPA builds serve the WebUI directly and have no gui_state
             * CGI. If the page answers, do not send enable requests that this
             * firmware does not implement. */
            if (!stateReadable
                    && probeWebUi(session.host, session.generation).served()) {
                postWebUiState(session.generation, true);
                postReady(session.generation, session.host);
                return;
            }

            boolean enableAccepted = false;
            postState(session.generation, "Enabling the ODU WebUI");
            OduEndpoints.Response enabled = request(session,
                    "/cgi-bin/app/esim/enable_gui", "POST",
                    "{\"enable_gui\":true}", null);
            ensureCurrent(session.generation);
            enableAccepted = accepted(enabled);
            if (!enableAccepted) {
                enableAccepted = enableOverEth(session, true);
                ensureCurrent(session.generation);
            }
            postWebUiToggleSupport(session.generation, stateReadable || enableAccepted);

            OduEndpoints.Response after = request(session, "/cgi-bin/app/esim/gui_state",
                    "GET", null, null);
            ensureCurrent(session.generation);
            boolean guiEnabled = OduProtocol.isHttpSuccess(after.code)
                    && OduProtocol.isGuiEnabled(after.body);
            boolean webReachable = guiEnabled
                    || probeWebUi(session.host, session.generation).served();
            if (!webReachable) {
                if (!stateReadable && !enableAccepted) {
                    throw new OduHttpException(
                            "This ODU firmware does not report a WebUI state, and its "
                                    + "WebUI page did not answer.");
                }
                throw new OduHttpException("ODU did not confirm that its WebUI is enabled.");
            }
            postWebUiState(session.generation, true);
            postReady(session.generation, session.host);
        });
    }

    /** Reads the current WebUI state without changing it. */
    public synchronized void checkState(String discoveredHost, String username, char[] password,
                                        String model) {
        start(discoveredHost, username, password, model, true, false, session -> {
            OduEndpoints.Response state = request(session, "/cgi-bin/app/esim/gui_state",
                    "GET", null, null);
            ensureCurrent(session.generation);
            boolean stateReadable = OduProtocol.isHttpSuccess(state.code)
                    && OduProtocol.isStrictJsonDocument(state.body);
            postWebUiToggleSupport(session.generation, stateReadable);
            if (stateReadable) {
                boolean enabled = OduProtocol.isGuiEnabled(state.body);
                postState(session.generation,
                        enabled ? "ODU WebUI is enabled" : "ODU WebUI is disabled");
                postWebUiState(session.generation, enabled);
                return;
            }
            /* Newer SPA firmware can serve the WebUI without exposing the
             * legacy gui_state CGI. Treat a reachable landing page as enabled. */
            if (probeWebUi(session.host, session.generation).served()) {
                postState(session.generation, "ODU WebUI page is reachable");
                postWebUiState(session.generation, true);
                return;
            }
            if (state.code == 404 || state.code == 405) {
                throw new OduHttpException(
                        "This ODU firmware does not report a WebUI state, and its WebUI "
                                + "page did not answer.");
            }
            if (!OduProtocol.isHttpSuccess(state.code)) {
                throw new OduHttpException(
                        "ODU could not report its WebUI state (HTTP " + state.code + ").");
            }
            throw new OduHttpException("ODU did not report a readable WebUI state.");
        });
    }

    /** Enables or disables the ODU WebUI and verifies the reported state. */
    public synchronized void setEnabled(String discoveredHost, String username, char[] password,
                                        boolean enable, String model) {
        start(discoveredHost, username, password, model, false, false, session -> {
            postState(session.generation,
                    enable ? "Enabling the ODU WebUI" : "Disabling the ODU WebUI");
            OduEndpoints.Response direct = request(session, "/cgi-bin/app/esim/enable_gui",
                    "POST", enable ? "{\"enable_gui\":true}" : "{\"enable_gui\":false}", null);
            ensureCurrent(session.generation);
            boolean accepted = accepted(direct);
            if (!accepted) {
                accepted = enableOverEth(session, enable);
                ensureCurrent(session.generation);
            }
            if (!accepted) {
                throw new OduHttpException(enable
                        ? "ODU did not accept the WebUI enable request."
                        : "ODU did not accept the WebUI disable request.");
            }

            OduEndpoints.Response state = request(session, "/cgi-bin/app/esim/gui_state",
                    "GET", null, null);
            ensureCurrent(session.generation);
            if (OduProtocol.isHttpSuccess(state.code)
                    && OduProtocol.isStrictJsonDocument(state.body)
                    && OduProtocol.isGuiEnabled(state.body) != enable) {
                throw new OduHttpException(enable
                        ? "ODU did not confirm that its WebUI is enabled."
                        : "ODU did not confirm that its WebUI is disabled.");
            }
            postWebUiState(session.generation, enable);
            postOperationDone(session.generation, enable);
        });
    }

    /** Measures whether the validated local ODU host answers at all. */
    public synchronized void ping(String discoveredHost) {
        if (!OduProtocol.isOduPanHost(discoveredHost)) {
            postError(generation, ErrorKind.OTHER,
                    "The ODU returned an invalid local address. Reconnect and retry.");
            return;
        }
        if (activeRequest != null && !activeRequest.isDone()) {
            postState(generation, "A WebUI request is already running");
            return;
        }
        final long requestGeneration = ++generation;
        endpoints.beginSession();
        activeRequest = io.submit(() -> {
            long started = System.currentTimeMillis();
            try {
                OduEndpoints.Response response = endpoints.request(discoveredHost, "/", "GET",
                        null, null, 5_000, "application/json");
                ensureCurrent(requestGeneration);
                if (response == null) throw new java.io.IOException("No response");
                postPing(requestGeneration, System.currentTimeMillis() - started);
            } catch (InterruptedException ended) {
                // Superseded or closed; post nothing.
            } catch (Exception e) {
                reportCatch(requestGeneration, e);
            }
        });
    }

    /**
     * Probes the recognized WebUI landing pages without credentials. If one is
     * served, the viewer can open directly; otherwise the caller may prompt for
     * credentials and run the legacy enable sequence.
     */
    public synchronized void probeAndOpen(String discoveredHost) {
        if (!OduProtocol.isOduPanHost(discoveredHost)) {
            postError(generation, ErrorKind.OTHER,
                    "The ODU returned an invalid local address. Reconnect and retry.");
            return;
        }
        if (activeRequest != null && !activeRequest.isDone()) {
            postState(generation, "A WebUI request is already running");
            return;
        }
        final long requestGeneration = ++generation;
        endpoints.beginSession();
        activeRequest = io.submit(() -> {
            try {
                UiProbe probe = probeWebUi(discoveredHost, requestGeneration);
                ensureCurrent(requestGeneration);
                if (probe.served()) {
                    postWebUiToggleSupport(requestGeneration, probe.toggleSupported());
                    postWebUiState(requestGeneration, true);
                    postState(requestGeneration, "ODU WebUI is already served");
                    postReady(requestGeneration, discoveredHost);
                } else {
                    postNotServed(requestGeneration);
                }
            } catch (InterruptedException ended) {
                // Superseded or closed; post nothing.
            } catch (Exception e) {
                reportCatch(requestGeneration, e);
            }
        });
    }

    private synchronized void start(String discoveredHost, String username, char[] password,
                                    String model, boolean probeFirst, boolean openIfServed,
                                    Action action) {
        if (password == null) return;
        if (closed) {
            Arrays.fill(password, '\0');
            return;
        }
        OduProtocol.PanAddressCategory hostCategory = PanAddressPolicy.classify(discoveredHost);
        if (hostCategory != OduProtocol.PanAddressCategory.VALID_ADDRESS) {
            Arrays.fill(password, '\0');
            if (hostCategory == OduProtocol.PanAddressCategory.PAN_INTERFACE_MISSING
                    || hostCategory == OduProtocol.PanAddressCategory.PAN_INTERFACE_UNAVAILABLE
                    || hostCategory == OduProtocol.PanAddressCategory.AMBIGUOUS_ROUTE) {
                postError(generation, ErrorKind.ROUTE_UNAVAILABLE,
                        "Could not reach the ODU over the local network. Check Bluetooth tethering/PAN in Settings, return here, and retry.");
            } else {
                postError(generation, ErrorKind.OTHER,
                        "The ODU returned an invalid local address. Reconnect and retry.");
            }
            return;
        }
        if (username == null || username.trim().isEmpty() || password.length == 0) {
            Arrays.fill(password, '\0');
            postError(generation, ErrorKind.AUTH_REJECTED,
                    "Enter the ODU username and password to continue.");
            return;
        }
        if (activeRequest != null && !activeRequest.isDone()) {
            Arrays.fill(password, '\0');
            postState(generation, "WebUI request is already running");
            return;
        }
        activeHost = discoveredHost;
        sessionCookie = null;
        final long requestGeneration = ++generation;
        char[] secret = password.clone();
        Arrays.fill(password, '\0');
        String user = username.trim();
        String requestedModel = OduQrCode.normalizeModel(model);
        endpoints.beginSession();
        activeRequest = io.submit(() -> {
            try {
                if (probeFirst) {
                    postState(requestGeneration, "Checking the ODU WebUI");
                    UiProbe probe = probeWebUi(discoveredHost, requestGeneration);
                    ensureCurrent(requestGeneration);
                    if (probe.served()) {
                        postWebUiToggleSupport(requestGeneration, probe.toggleSupported());
                        postWebUiState(requestGeneration, true);
                        postState(requestGeneration, "ODU WebUI is already served");
                        if (openIfServed) postReady(requestGeneration, discoveredHost);
                        return;
                    }
                }
                postState(requestGeneration, "Signing in to the ODU");
                Session session = authenticate(discoveredHost, user, new String(secret),
                        requestedModel, requestGeneration);
                action.run(session);
            } catch (InterruptedException e) {
                // Superseded or closed; post nothing.
            } catch (ApiFamilyNotFoundException e) {
                reportCatch(requestGeneration, e);
            } catch (Exception e) {
                reportCatch(requestGeneration, e);
            } finally {
                Arrays.fill(secret, '\0');
                if (generation == requestGeneration) {
                    sessionCookie = null;
                    activeHost = null;
                }
            }
        });
    }

    private Session authenticate(String host, String user, String password, String model,
                                 long expectedGeneration) throws Exception {
        OduEndpoints.Response auth = endpoints.request(host, "/cgi-bin/app/account/auth", "POST",
                authPayload(user, password, model), null, OduEndpoints.DEFAULT_TIMEOUT_MS,
                "application/json");
        ensureCurrent(expectedGeneration);
        if (auth.code == 404 || auth.code == 405) {
            throw new ApiFamilyNotFoundException();
        }
        if (auth.code >= 500) {
            throw new OduHttpException(
                    "ODU login endpoint returned HTTP " + auth.code + ".");
        }
        if (!OduProtocol.isStrictJsonDocument(auth.body) && auth.cookie.isEmpty()) {
            throw new ApiFamilyNotFoundException();
        }
        if (OduProtocol.isAuthRejected(auth.code, auth.body) || auth.cookie.isEmpty()) {
            throw new AuthRejectedException();
        }
        sessionCookie = auth.cookie;
        return new Session(host, auth.cookie, expectedGeneration);
    }

    private static String authPayload(String username, String password, String model) {
        String selected = model == null || model.isEmpty() ? "OD503" : model;
        return "{\"usr\":" + OduProtocol.jsonString(username)
                + ",\"pwd\":" + OduProtocol.jsonString(password)
                + ",\"model\":" + OduProtocol.jsonString(selected) + "}";
    }

    private OduEndpoints.Response request(Session session, String path, String method,
                                          String payload, String contentType) throws Exception {
        return endpoints.request(session.host, path, method, payload, session.cookie,
                OduEndpoints.DEFAULT_TIMEOUT_MS,
                contentType == null ? "application/json" : contentType);
    }

    private static boolean accepted(OduEndpoints.Response response) {
        return OduProtocol.isHttpSuccess(response.code)
                && OduProtocol.isGuiEnabled(response.body);
    }

    /**
     * Probes the landing pages used by the different ODU WebUI builds. A 2xx
     * page means the WebUI is served even when the legacy gui_state CGI does
     * not exist on this firmware.
     */
    /**
     * Probes the landing pages used by the different ODU WebUI builds. The
     * 51642 unit has served a meta-refresh shell, a JioODU Angular SPA, and a
     * React Jio application SPA at different times; a recognized page means the
     * WebUI is served even when the legacy gui_state CGI is absent.
     */
    private UiProbe probeWebUi(String host, long expectedGeneration) throws Exception {
        for (String page : new String[]{"/", "/login.html", "/login.htm", "/index.htm",
                "/index.html"}) {
            OduEndpoints.Response probe = endpoints.request(host, page, "GET", null, null,
                    4_000, "application/json");
            ensureCurrent(expectedGeneration);
            if (!OduProtocol.isHttpSuccess(probe.code)) continue;
            OduProtocol.UiFamily family = OduProtocol.detectUiFamily(probe.body);
            if (family == OduProtocol.UiFamily.META_REFRESH_LOGIN) {
                String target = OduProtocol.metaRefreshTarget(probe.body);
                if (target != null) {
                    OduEndpoints.Response next = endpoints.request(host, target, "GET", null,
                            null, 4_000, "application/json");
                    ensureCurrent(expectedGeneration);
                    if (OduProtocol.isHttpSuccess(next.code)) {
                        OduProtocol.UiFamily nextFamily =
                                OduProtocol.detectUiFamily(next.body);
                        if (nextFamily.isServed()) return new UiProbe(nextFamily, target);
                        return new UiProbe(family, page);
                    }
                }
                return new UiProbe(family, page);
            }
            if (family.isServed()) return new UiProbe(family, page);
        }
        return new UiProbe(OduProtocol.UiFamily.UNKNOWN, null);
    }

    /** Older firmware exposes the switch only through the OSS/CC endpoint. */
    private boolean enableOverEth(Session session, boolean enable) throws Exception {
        OduEndpoints.Response response = request(session,
                "/jiooscc/api/EnableWebGUIOverEthPort", "POST",
                enable ? "enable=1" : "enable=0", "application/x-www-form-urlencoded");
        ensureCurrent(session.generation);
        if (!OduProtocol.isHttpSuccess(response.code)) return false;
        String body = response.body == null ? "" : response.body;
        return body.contains("SUCCESS")
                || body.contains("\"result\":true")
                || body.contains("\"result\": true")
                || "true".equalsIgnoreCase(body.trim());
    }

    private void reportCatch(long requestGeneration, Exception e) {
        if (e instanceof ApiFamilyNotFoundException) {
            postError(requestGeneration, ErrorKind.API_FAMILY_NOT_FOUND,
                    "This ODU did not expose a recognized WebUI page or the legacy login API. "
                            + "Reconnect or reboot the unit and retry.");
            return;
        }
        if (e instanceof AuthRejectedException) {
            postError(requestGeneration, ErrorKind.AUTH_REJECTED,
                    "ODU login was rejected. Check the credentials and try again.");
            return;
        }
        if (e instanceof OduHttpException) {
            postError(requestGeneration, ErrorKind.HTTP, e.getMessage());
            return;
        }
        if (findException(e, SSLHandshakeException.class) != null) {
            postError(requestGeneration, ErrorKind.CLIENT_CERT_REQUIRED,
                    "This unit may require a TLS client certificate. This build does not include one; use an authorized vendor-supported method.");
            return;
        }
        if (findException(e, UnknownHostException.class) != null
                || findException(e, ConnectException.class) != null
                || findException(e, NoRouteToHostException.class) != null
                || findException(e, SocketTimeoutException.class) != null) {
            postError(requestGeneration, ErrorKind.ROUTE_UNAVAILABLE,
                    "Could not reach the ODU over the local network. Check Bluetooth tethering/PAN in Settings, return here, and retry.");
            return;
        }
        postError(requestGeneration, ErrorKind.OTHER,
                "Could not complete the ODU WebUI request. Reconnect and retry.");
    }

    /** Signals the running sequence to stop and supersedes its callbacks. */
    public synchronized void cancelSession() {
        generation++;
        activeHost = null;
        sessionCookie = null;
        endpoints.cancel();
        if (activeRequest != null) activeRequest.cancel(true);
        activeRequest = null;
    }

    public synchronized void release() {
        cancelSession();
        closed = true;
        io.shutdownNow();
        listener = null;
        main.removeCallbacksAndMessages(null);
    }

    private static Exception findException(Throwable error, Class<? extends Exception> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return (Exception) current;
        }
        return null;
    }

    private void ensureCurrent(long expectedGeneration) throws InterruptedException {
        if (closed || generation != expectedGeneration || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Session ended");
        }
    }

    private void postState(long expectedGeneration, String message) {
        main.post(() -> { if (!closed && generation == expectedGeneration && listener != null) listener.onState(message); });
    }

    private void postReady(long expectedGeneration, String host) {
        main.post(() -> { if (!closed && generation == expectedGeneration && listener != null) listener.onReady(host); });
    }

    private void postWebUiState(long expectedGeneration, boolean enabled) {
        main.post(() -> { if (!closed && generation == expectedGeneration && listener != null) listener.onWebUiState(enabled); });
    }

    private void postWebUiToggleSupport(long expectedGeneration, boolean supported) {
        main.post(() -> { if (!closed && generation == expectedGeneration && listener != null) listener.onWebUiToggleSupport(supported); });
    }

    private void postOperationDone(long expectedGeneration, boolean enabled) {
        main.post(() -> { if (!closed && generation == expectedGeneration && listener != null) listener.onOperationDone(enabled); });
    }

    private void postPing(long expectedGeneration, long latencyMs) {
        main.post(() -> { if (!closed && generation == expectedGeneration && listener != null) listener.onPing(latencyMs); });
    }

    private void postNotServed(long expectedGeneration) {
        main.post(() -> { if (!closed && generation == expectedGeneration && listener != null) listener.onWebUiNotServed(); });
    }

    private void postError(long expectedGeneration, ErrorKind kind, String message) {
        main.post(() -> { if (!closed && generation == expectedGeneration && listener != null) listener.onError(kind, message); });
    }
}
