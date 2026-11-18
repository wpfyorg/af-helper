package io.github.wpfyorg.afhelper.net;

import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for the Foxconn CM-family WebUI CGI API ({@code /cgi-bin/cgi/*.cgi}).
 *
 * <p>Unlike the JFE {@code /cgi-bin/app/*} family, every CM call carries a
 * {@code QSESSIONID} cookie plus a one-time {@code RequestVerifyToken}: fetch a
 * fresh token, use it immediately, and the response hands back the next cookie.
 * The token and cookies stay in memory for one action and are never logged.
 *
 * <p>Transport, origin validation, self-signed TLS, and the optional packaged
 * mTLS identity all come from {@link OduEndpoints}, so this class only adds the
 * CM request shapes.
 */
final class OduCmClient {
    private static final Pattern TOKEN =
            Pattern.compile("\"RequestVerifyToken\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PROFILE_LIST =
            Pattern.compile("esim_profile_list_tmp\\s*=\\s*\"(.*?)\";", Pattern.DOTALL);
    /** Flat {@code "key": "value"} pairs of the status payloads. */
    private static final Pattern FLAT_PAIR =
            Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"?([^\",}]+)\"?");

    private static final String TOKEN_QUERY = "/cgi-bin/cgi/token_query.cgi";
    private static final String LOGIN = "/cgi-bin/cgi/login_req.cgi";
    private static final String NET_STATUS = "/cgi-bin/cgi/net_status_retrieve.cgi";
    private static final String ESIM_PAGE = "/cgi-bin/cm/esim-settings.html";
    private static final String INSTALL = "/cgi-bin/cgi/req_esim_install_profile.cgi";
    private static final String CONFIG = "/cgi-bin/cgi/req_esim_config_profile.cgi";
    private static final String DELETE = "/cgi-bin/cgi/req_esim_delete_profile.cgi";

    /** One CM CGI response; the body is never logged or persisted. */
    static final class Result {
        final int code;
        final String body;

        Result(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return OduProtocol.isHttpSuccess(code);
        }

        boolean resultTrue() {
            return resultIsTrue(body);
        }
    }

    private final OduEndpoints endpoints;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private boolean loggedIn;

    OduCmClient(OduEndpoints endpoints) {
        this.endpoints = endpoints;
    }

    /** Token + credential login. Safe to call once per action. */
    boolean login(String host, String user, String password) throws Exception {
        String token = token(host);
        String body = "{\"RequestVerifyToken\":" + OduProtocol.jsonString(token)
                + ",\"usr\":" + OduProtocol.jsonString(user)
                + ",\"pwd\":" + OduProtocol.jsonString(password) + "}";
        OduEndpoints.Response response = call(host, LOGIN, "POST", body);
        loggedIn = response.code >= 200 && response.code < 300 && resultIsTrue(response.body);
        return loggedIn;
    }

    /** Serving-cell stats: rsrp, rsrq, sinr, band, arfcn, pcid, mcs, ... */
    Map<String, String> netStatus(String host) throws Exception {
        ensureLoggedIn(host);
        String token = token(host);
        OduEndpoints.Response response = call(host, NET_STATUS, "POST", tokenPayload(token));
        if (!OduProtocol.isHttpSuccess(response.code)) {
            throw new IOException("net_status_retrieve HTTP " + response.code);
        }
        return flatPairs(response.body);
    }

    /**
     * The profile list is server-rendered into {@code esim-settings.html};
     * returns the raw {@code profile_info_list} JSON, or "" when the page has
     * no list.
     */
    String profiles(String host) throws Exception {
        ensureLoggedIn(host);
        OduEndpoints.Response response = call(host, ESIM_PAGE, "GET", null);
        if (!OduProtocol.isHttpSuccess(response.code)) {
            throw new IOException("esim-settings HTTP " + response.code);
        }
        return extractProfileList(response.body);
    }

    /** Installs an LPA:1 activation-code profile. */
    Result installByCode(String host, String activationCode, boolean autoEnable) throws Exception {
        ensureLoggedIn(host);
        String token = token(host);
        String body = "{\"RequestVerifyToken\":" + OduProtocol.jsonString(token)
                + ",\"isActive_status\":1,\"auto_enable_profile\":"
                + (autoEnable ? "true" : "false")
                + ",\"activation_code\":" + OduProtocol.jsonString(activationCode) + "}";
        return result(host, INSTALL, body, OduEndpoints.LONG_TIMEOUT_MS);
    }

    /** Enables (true) or disables (false) the profile with this ICCID. */
    Result config(String host, String iccid, boolean enable) throws Exception {
        ensureLoggedIn(host);
        String token = token(host);
        String body = "{\"RequestVerifyToken\":" + OduProtocol.jsonString(token)
                + ",\"ICCID\":" + OduProtocol.jsonString(iccid)
                + ",\"state\":" + (enable ? "1" : "0") + "}";
        return result(host, CONFIG, body);
    }

    /**
     * Deletes a profile. The unit asks for the login password again as
     * {@code del_code}, so the in-memory session password is passed.
     */
    Result delete(String host, String iccid, String loginPassword) throws Exception {
        ensureLoggedIn(host);
        String token = token(host);
        String body = "{\"RequestVerifyToken\":" + OduProtocol.jsonString(token)
                + ",\"ICCID\":" + OduProtocol.jsonString(iccid)
                + ",\"del_code\":" + OduProtocol.jsonString(loginPassword) + "}";
        return result(host, DELETE, body);
    }

    // ------------------------------------------------------------------ http

    private void ensureLoggedIn(String host) throws Exception {
        if (!loggedIn) throw new IOException("CM session is not signed in");
    }

    private Result result(String host, String path, String body) throws Exception {
        return result(host, path, body, OduEndpoints.DEFAULT_TIMEOUT_MS);
    }

    private Result result(String host, String path, String body, int timeoutMs) throws Exception {
        OduEndpoints.Response response = call(host, path, "POST", body, timeoutMs);
        return new Result(response.code, response.body);
    }

    private String token(String host) throws Exception {
        OduEndpoints.Response response = call(host, TOKEN_QUERY, "POST", "{}");
        if (!OduProtocol.isHttpSuccess(response.code)) {
            throw new IOException("token_query HTTP " + response.code);
        }
        String token = parseToken(response.body);
        if (token.isEmpty()) throw new IOException("token_query returned no token");
        return token;
    }

    private OduEndpoints.Response call(String host, String path, String method, String payload)
            throws Exception {
        return call(host, path, method, payload, OduEndpoints.DEFAULT_TIMEOUT_MS);
    }

    private OduEndpoints.Response call(String host, String path, String method, String payload,
                                       int timeoutMs) throws Exception {
        OduEndpoints.Response response = endpoints.request(host, path, method, payload,
                cookieHeader(), timeoutMs);
        mergeCookies(response.cookie);
        return response;
    }

    private String cookieHeader() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(cookie.getKey()).append('=').append(cookie.getValue());
        }
        return out.toString();
    }

    /** Last value wins: the unit rotates QSESSIONID across calls. */
    private void mergeCookies(String header) {
        if (header == null || header.isEmpty()) return;
        for (String item : header.split(";")) {
            String pair = item.trim();
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String name = pair.substring(0, equals).trim();
            if (name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                cookies.put(name, pair.substring(equals + 1).trim());
            }
        }
    }

    private static String tokenPayload(String token) {
        return "{\"RequestVerifyToken\":" + OduProtocol.jsonString(token) + "}";
    }

    // ------------------------------------------------------------- pure parsing

    /** Package-visible for pure JVM tests. */
    static String parseToken(String body) {
        if (body == null) return "";
        Matcher matcher = TOKEN.matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** Package-visible for pure JVM tests. */
    static boolean resultIsTrue(String body) {
        if (body == null) return false;
        if (OduProtocol.isStrictJsonDocument(body)) {
            try {
                return Boolean.TRUE.equals(new JSONObject(body).opt("result"));
            } catch (Exception ignored) {
                // Fall through to the tolerant vendor-page check.
            }
        }
        return Pattern.compile("\"result\"\\s*:\\s*true").matcher(body).find();
    }

    /**
     * Extracts and unescapes the server-rendered profile list. The page embeds
     * it as a quoted JavaScript string, so both JSON and HTML escaping apply.
     * Package-visible for pure JVM tests.
     */
    static String extractProfileList(String html) {
        if (html == null) return "";
        Matcher matcher = PROFILE_LIST.matcher(html);
        if (!matcher.find()) return "";
        return unescapePage(matcher.group(1));
    }

    /** Package-visible for pure JVM tests. */
    static String unescapePage(String raw) {
        if (raw == null) return "";
        String value = raw.replace("\\\"", "\"").replace("\\/", "/");
        value = value.replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
        return value;
    }

    /** Package-visible for pure JVM tests. */
    static Map<String, String> flatPairs(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) return out;
        Matcher matcher = FLAT_PAIR.matcher(json);
        while (matcher.find()) out.put(matcher.group(1), matcher.group(2).trim());
        return out;
    }
}
