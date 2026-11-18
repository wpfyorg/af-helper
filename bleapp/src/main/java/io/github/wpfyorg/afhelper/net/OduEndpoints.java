package io.github.wpfyorg.afhelper.net;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Performs HTTP requests against one validated local ODU host only.
 *
 * Keep the origin guard for every hop: the destination must stay the same
 * validated ODU PAN host over HTTPS with no user info. This machinery is moved
 * from the original {@link OduWebUiController} request loop so that the WebUI,
 * signal, and eSIM actions all share one guarded transport.
 */
public final class OduEndpoints {
    public static final int DEFAULT_TIMEOUT_MS = 8_000;
    /** Profile installs can take up to two minutes on the ODU's SM-DP+ path. */
    public static final int LONG_TIMEOUT_MS = 120_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_REDIRECTS = 3;

    public static final class Response {
        public final int code;
        public final String body;
        public final String cookie;
        public final String location;
        Response(int code, String body, String cookie, String location) {
            this.code = code;
            this.body = body;
            this.cookie = cookie;
            this.location = location;
        }
    }

    private final OduClientTls tls;
    private final Cancellation cancellation = new Cancellation();
    private volatile HttpsURLConnection activeConnection;

    public OduEndpoints(Context context) {
        this.tls = new OduClientTls(context);
    }

    /**
     * Starts a new logical request. Cancellation only aborts the request that was
     * in flight when {@link #cancel()} ran; a later user action must be able to
     * use the same transport again.
     */
    public void beginSession() {
        cancellation.begin();
    }

    /**
     * Sends one request, following only same-origin redirects to the same
     * validated host. Throws {@link InterruptedException} when cancelled or
     * superseded, and {@link ClientIdentityRequired} on the TLS identity path.
     */
    public Response request(String host, String path, String method, String payload, String cookie)
            throws Exception {
        return request(host, path, method, payload, cookie, DEFAULT_TIMEOUT_MS,
                "application/json");
    }

    public Response request(String host, String path, String method, String payload, String cookie,
                            int timeoutMs) throws Exception {
        return request(host, path, method, payload, cookie, timeoutMs, "application/json");
    }

    /**
     * Sends one request, trying both WebUI ports. A refusal on the first port
     * does not end the attempt, and a non-2xx answer from one port does not hide
     * a success on the other. Redirects stay same-origin on the validated host.
     */
    public Response request(String host, String path, String method, String payload, String cookie,
                            int timeoutMs, String contentType) throws Exception {
        if (!OduProtocol.isOduPanHost(host)) {
            throw new java.io.IOException("ODU PAN route is unavailable");
        }
        Exception firstFailure = null;
        Response lastResponse = null;
        for (int port : OduProtocol.ODU_WEBUI_PORTS) {
            try {
                Response response = requestOnPort(host, port, path, method, payload, cookie,
                        timeoutMs, contentType);
                if (response.code >= 200 && response.code < 300) return response;
                lastResponse = response;
            } catch (java.net.ConnectException | java.net.NoRouteToHostException unreachablePort) {
                /* Some firmware answers the CGI only on its own port while 443
                 * refuses, so a refused front door must not end the attempt. */
                if (firstFailure == null) firstFailure = unreachablePort;
            }
        }
        if (lastResponse != null) return lastResponse;
        throw firstFailure != null ? firstFailure
                : new java.io.IOException("ODU WebUI is unavailable");
    }

    private Response requestOnPort(String host, int port, String path, String method, String payload,
                                   String cookie, int timeoutMs, String contentType) throws Exception {
        long session = cancellation.current();
        URL current = new URL("https://" + host + (port == 443 ? "" : ":" + port) + path);
        List<String> setCookieHeaders = new ArrayList<>();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!OduProtocol.isOduPanHost(host)) {
                throw new java.io.IOException("ODU PAN route is unavailable");
            }
            if (!isLocalHttpsOrigin(host, current)) {
                throw new SecurityException("ODU redirected outside its local HTTPS origin");
            }
            URL requestUrl = current;
            HttpsURLConnection connection = (HttpsURLConnection) requestUrl.openConnection();
            activeConnection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(Math.min(timeoutMs, CONNECT_TIMEOUT_MS));
            connection.setReadTimeout(timeoutMs);
            connection.setSSLSocketFactory(socketFactory());
            HostnameVerifier verifier = (requestedHost, tlsSession) ->
                    host.equalsIgnoreCase(requestedHost) && host.equalsIgnoreCase(requestUrl.getHost());
            connection.setHostnameVerifier(verifier);
            connection.setRequestProperty("Accept", "application/json");
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            if (payload != null) {
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        contentType == null ? "application/json" : contentType);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            try {
                if (cancellation.isCancelled(session)) throw new InterruptedException("Session ended");
                int code = connection.getResponseCode();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() != null && "Set-Cookie".equalsIgnoreCase(header.getKey())
                            && header.getValue() != null) {
                        setCookieHeaders.addAll(header.getValue());
                    }
                }
                String location = connection.getHeaderField("Location");
                if (isRedirect(code) && location != null && !location.trim().isEmpty()) {
                    if (hop == MAX_REDIRECTS) throw new java.io.IOException("Too many ODU redirects");
                    URL target = OduProtocol.oduRedirectUrl(host, requestUrl, location);
                    if (target == null) {
                        throw new SecurityException("ODU redirected outside its local HTTPS origin");
                    }
                    current = target;
                    continue;
                }
                return new Response(code, readBody(connection),
                        OduProtocol.cookieHeader(setCookieHeaders), location);
            } finally {
                connection.disconnect();
                if (activeConnection == connection) activeConnection = null;
            }
        }
        throw new java.io.IOException("Too many ODU redirects");
    }

    /** Every hop must stay on the validated ODU host over HTTPS on a WebUI port. */
    private static boolean isLocalHttpsOrigin(String host, URL url) {
        return "https".equalsIgnoreCase(url.getProtocol())
                && host.equals(url.getHost())
                && url.getUserInfo() == null
                && OduProtocol.isOduWebUiPort(url.getPort() == -1 ? 443 : url.getPort());
    }

    /** Signals every in-flight or following request of the current session to stop. */
    public void cancel() {
        cancellation.cancel();
        HttpsURLConnection connection = activeConnection;
        activeConnection = null;
        if (connection != null) connection.disconnect();
    }

    private SSLSocketFactory socketFactory() {
        SSLSocketFactory identity = tls.socketFactory();
        return identity != null ? identity : localOnlySocketFactory();
    }

    private static SSLSocketFactory localOnlySocketFactory() {
        try {
            TrustManager[] trustManagers = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("TLS is unavailable on this Android runtime", e);
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
        InputStream input = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_BODY_BYTES) {
                    throw new java.io.IOException("ODU response exceeded size limit");
                }
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
