package io.github.wpfyorg.afhelper.net;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure protocol checks shared by BLE host parsing, HTTP, and local tests. */
public final class OduProtocol {
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern COMBINED_COOKIE = Pattern.compile(",(?=\\s*[!#$%&'*+.^_`|~0-9A-Za-z-]+=)");

    private OduProtocol() {}

    public enum PanAddressCategory {
        EMPTY, INVALID_IPV4_FORMAT, IPV4_OUTSIDE_POLICY, PAN_INTERFACE_MISSING,
        PAN_INTERFACE_UNAVAILABLE, AMBIGUOUS_ROUTE, VALID_ADDRESS
    }

    public static boolean isOduPanHost(String host) {
        return PanAddressPolicy.classify(host) == PanAddressCategory.VALID_ADDRESS;
    }

    public static PanAddressCategory classifyOduPanHost(String host) {
        return PanAddressPolicy.classify(host);
    }

    /** Classifies syntax separately from the existing allowed ODU PAN host range. */
    public static PanAddressCategory classifyOduPanAddress(String host) {
        if (host == null || host.isEmpty()) return PanAddressCategory.EMPTY;
        int[] octets = parseIpv4(host);
        if (octets == null) return PanAddressCategory.INVALID_IPV4_FORMAT;
        return isLegacyPanOctets(octets)
                ? PanAddressCategory.VALID_ADDRESS : PanAddressCategory.IPV4_OUTSIDE_POLICY;
    }

    static int[] parseIpv4(String host) {
        if (host == null) return null;
        Matcher matcher = IPV4.matcher(host);
        if (!matcher.matches()) return null;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(matcher.group(i + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (octets[i] < 0 || octets[i] > 255) return null;
        }
        return octets;
    }

    static boolean isLegacyPanOctets(int[] octets) {
        return octets != null && octets.length == 4 && octets[0] == 192
                && octets[1] == 168 && octets[2] == 44 && octets[3] >= 1 && octets[3] <= 254;
    }

    public static boolean isGuiEnabled(String body) {
        if (!isStrictJsonDocument(body)) return false;
        try {
            Object result = new JSONObject(body).opt("result");
            return Boolean.TRUE.equals(result);
        } catch (JSONException | RuntimeException e) {
            return false;
        }
    }

    /** WebUI shell/family served by an ODU landing page. */
    public enum UiFamily {
        LEGACY_JIOJS, JIOODU_SPA, JIO_APPLICATION_SPA, META_REFRESH_LOGIN, UNKNOWN;

        public boolean isSpa() {
            return this == JIOODU_SPA || this == JIO_APPLICATION_SPA;
        }

        public boolean isServed() {
            return this != UNKNOWN;
        }
    }

    /**
     * Classifies a landing-page body by its shipped UI markers. The 51642
     * family has changed shells across reboots, so callers must not assume one
     * fixed page or the legacy gui_state CGI.
     */
    public static UiFamily detectUiFamily(String body) {
        if (body == null || body.trim().isEmpty()) return UiFamily.UNKNOWN;
        String text = body.toLowerCase(java.util.Locale.US);
        boolean metaRefresh = text.contains("http-equiv") && text.contains("refresh")
                && text.contains("url=");
        boolean jioCss = text.contains("jioimages") || text.contains("jiocss");
        if (metaRefresh && jioCss && text.contains("login.html")) {
            return UiFamily.META_REFRESH_LOGIN;
        }
        if (text.contains("jioodu") || text.contains("<app-root")) {
            return UiFamily.JIOODU_SPA;
        }
        if (text.contains("jio application") || text.contains("<mainapp")
                || text.contains("/static/js/main")) {
            return UiFamily.JIO_APPLICATION_SPA;
        }
        if (text.contains("jiojs") || text.contains("sjcl")
                || (text.contains("login.html") && text.contains("jio"))) {
            return UiFamily.LEGACY_JIOJS;
        }
        return UiFamily.UNKNOWN;
    }

    /**
     * Extracts the relative target of a meta-refresh login page. Absolute or
     * parent-traversing targets are refused; the caller re-requests the target
     * through the same validated ODU host.
     */
    public static String metaRefreshTarget(String body) {
        if (body == null) return null;
        String text = body.toLowerCase(java.util.Locale.US);
        int marker = text.indexOf("http-equiv");
        if (marker < 0 || !text.contains("refresh")) return null;
        int url = text.indexOf("url=", marker);
        if (url < 0) url = text.indexOf("url=");
        if (url < 0) return null;
        String target = body.substring(url + 4).trim();
        int end = target.length();
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '"' || c == '\'' || c == '>' || c == ';' || Character.isWhitespace(c)) {
                end = i;
                break;
            }
        }
        target = target.substring(0, end).trim();
        if (target.isEmpty() || target.contains("://") || target.startsWith("//")
                || target.contains("..")) return null;
        return target.startsWith("/") ? target : "/" + target;
    }

    public static boolean isAuthRejected(int code, String body) {
        return code < 200 || code >= 300 || hasResultFalse(body);
    }

    public static boolean hasResultFalse(String body) {
        if (!isStrictJsonDocument(body)) return false;
        try {
            Object result = new JSONObject(body).opt("result");
            return Boolean.FALSE.equals(result);
        } catch (JSONException | RuntimeException e) {
            return false;
        }
    }

    public static boolean shouldEnableGui(int stateHttpCode, String stateBody) {
        return stateHttpCode < 200 || stateHttpCode >= 300 || !isGuiEnabled(stateBody);
    }

    public static boolean isHttpSuccess(int code) { return code >= 200 && code < 300; }

    /**
     * Ports the ODU answers its WebUI and CGI on. Firmware varies: some units
     * serve everything on 443 and 308-redirect the CGI to the other port, some
     * answer the CGI directly on it while 443 only relays.
     */
    public static final int[] ODU_WEBUI_PORTS = {443, 4443};

    public static boolean isOduWebUiPort(int port) {
        for (int candidate : ODU_WEBUI_PORTS) {
            if (candidate == port) return true;
        }
        return false;
    }

    /**
     * Resolves a redirect for one validated ODU host: HTTPS, the same host, no
     * user info, and confined to {@link #ODU_WEBUI_PORTS}. Returns null when the
     * target would leave that local origin, so callers can refuse it.
     */
    public static java.net.URL oduRedirectUrl(String host, java.net.URL from, String location) {
        if (host == null || from == null || location == null) return null;
        String trimmed = location.trim();
        if (trimmed.isEmpty()) return null;
        try {
            java.net.URL target = new java.net.URL(from, trimmed);
            if (!"https".equalsIgnoreCase(target.getProtocol())) return null;
            if (!host.equals(target.getHost())) return null;
            if (target.getUserInfo() != null) return null;
            return isOduWebUiPort(target.getPort() == -1 ? 443 : target.getPort()) ? target : null;
        } catch (java.net.MalformedURLException outsideOrigin) {
            return null;
        }
    }

    /** Keeps only cookie name=value pairs; attributes never enter the request Cookie header. */
    public static String cookieHeader(List<String> setCookieHeaders) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (setCookieHeaders == null) return "";
        for (String header : setCookieHeaders) {
            if (header == null) continue;
            for (String item : COMBINED_COOKIE.split(header)) {
                String pair = item.split(";", 2)[0].trim();
                int equals = pair.indexOf('=');
                if (equals <= 0) continue;
                String name = pair.substring(0, equals).trim();
                String value = pair.substring(equals + 1).trim();
                if (name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) cookies.put(name, value);
            }
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(cookie.getKey()).append('=').append(cookie.getValue());
        }
        return result.toString();
    }

    public static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /** Public pure view of the strict-document check; keeps HTTP parsing consistent. */
    public static boolean isStrictJsonDocument(String text) {
        return StrictJson.isDocument(text);
    }

    /** org.json accepts a few non-standard forms; reject them before extracting the field. */
    private static final class StrictJson {
        private final String text;
        private int at;
        private StrictJson(String text) { this.text = text; }

        static boolean isDocument(String text) {
            if (text == null) return false;
            try {
                StrictJson parser = new StrictJson(text);
                parser.space();
                parser.value(0);
                parser.space();
                return parser.at == text.length();
            } catch (IllegalArgumentException e) { return false; }
        }

        private void value(int depth) {
            if (depth > 64 || at >= text.length()) fail();
            char c = text.charAt(at);
            if (c == '{') object(depth + 1);
            else if (c == '[') array(depth + 1);
            else if (c == '"') string();
            else if (c == 't') literal("true");
            else if (c == 'f') literal("false");
            else if (c == 'n') literal("null");
            else number();
        }

        private void object(int depth) {
            at++; space();
            if (take('}')) return;
            while (true) {
                if (at >= text.length() || text.charAt(at) != '"') fail();
                string(); space(); require(':'); space(); value(depth); space();
                if (take('}')) return;
                require(','); space();
            }
        }

        private void array(int depth) {
            at++; space();
            if (take(']')) return;
            while (true) {
                value(depth); space();
                if (take(']')) return;
                require(','); space();
            }
        }

        private void string() {
            require('"');
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return;
                if (c < 0x20) fail();
                if (c == '\\') {
                    if (at >= text.length()) fail();
                    char escape = text.charAt(at++);
                    if (escape == 'u') {
                        for (int i = 0; i < 4; i++)
                            if (at >= text.length() || Character.digit(text.charAt(at++), 16) < 0) fail();
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) fail();
                }
            }
            fail();
        }

        private void number() {
            int start = at;
            take('-');
            if (!take('0')) {
                if (!digit19()) fail();
                while (digit()) at++;
            }
            if (take('.')) {
                if (!digit()) fail();
                while (digit()) at++;
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                if (!digit()) fail();
                while (digit()) at++;
            }
            if (at == start) fail();
        }

        private void literal(String literal) {
            if (!text.startsWith(literal, at)) fail();
            at += literal.length();
        }
        private boolean digit19() { return at < text.length() && text.charAt(at) >= '1' && text.charAt(at) <= '9' && ++at > 0; }
        private boolean digit() { return at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9'; }
        private boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        private void require(char c) { if (!take(c)) fail(); }
        private void space() { while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\n' || text.charAt(at) == '\r' || text.charAt(at) == '\t')) at++; }
        private void fail() { throw new IllegalArgumentException("Invalid JSON"); }
    }
}
