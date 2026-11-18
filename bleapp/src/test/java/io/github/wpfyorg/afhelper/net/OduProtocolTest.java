package io.github.wpfyorg.afhelper.net;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class OduProtocolTest {
    @Test public void acceptsOnlyPrivateNumericIpv4Hosts() {
        assertTrue(OduProtocol.isOduPanHost("192.168.44.12"));
        assertTrue(OduProtocol.isOduPanHost("192.168.44.1"));
        assertTrue(OduProtocol.isOduPanHost("192.168.44.254"));
        assertEquals(OduProtocol.PanAddressCategory.IPV4_OUTSIDE_POLICY,
                OduProtocol.classifyOduPanAddress("192.168.44.0"));
        assertEquals(OduProtocol.PanAddressCategory.IPV4_OUTSIDE_POLICY,
                OduProtocol.classifyOduPanAddress("192.168.44.255"));
        assertEquals(OduProtocol.PanAddressCategory.IPV4_OUTSIDE_POLICY,
                OduProtocol.classifyOduPanAddress("10.0.0.2"));
        assertEquals(OduProtocol.PanAddressCategory.IPV4_OUTSIDE_POLICY,
                OduProtocol.classifyOduPanAddress("192.168.45.12"));
        assertFalse(OduProtocol.isOduPanHost("example.test"));
        assertFalse(OduProtocol.isOduPanHost("https://192.168.44.12"));
        assertFalse(OduProtocol.isOduPanHost("192.168.44.256"));
    }

    @Test public void classifiesPanAddressSyntaxSeparatelyFromHostPolicy() {
        assertEquals(OduProtocol.PanAddressCategory.EMPTY,
                OduProtocol.classifyOduPanAddress(null));
        assertEquals(OduProtocol.PanAddressCategory.EMPTY,
                OduProtocol.classifyOduPanAddress("  ".trim()));
        assertEquals(OduProtocol.PanAddressCategory.VALID_ADDRESS,
                OduProtocol.classifyOduPanAddress(" 192.168.44.12 ".trim()));
        assertEquals(OduProtocol.PanAddressCategory.IPV4_OUTSIDE_POLICY,
                OduProtocol.classifyOduPanAddress("192.168.45.12"));
        assertEquals(OduProtocol.PanAddressCategory.IPV4_OUTSIDE_POLICY,
                OduProtocol.classifyOduPanAddress("127.0.0.1"));
        assertEquals(OduProtocol.PanAddressCategory.INVALID_IPV4_FORMAT,
                OduProtocol.classifyOduPanAddress("192.168.44.x"));
        assertEquals(OduProtocol.PanAddressCategory.INVALID_IPV4_FORMAT,
                OduProtocol.classifyOduPanAddress("192.168.44.256"));
    }

    @Test public void guiEnabledRequiresAnExplicitTrueResult() {
        assertTrue(OduProtocol.isGuiEnabled("{\"result\":true}"));
        assertTrue(OduProtocol.isGuiEnabled("{ \"result\" : true, \"extra\": 1 }"));
        assertFalse(OduProtocol.isGuiEnabled("{\"result\":false}"));
        assertFalse(OduProtocol.isGuiEnabled("{\"other\":true}"));
        assertFalse(OduProtocol.isGuiEnabled("not json"));
        assertFalse(OduProtocol.isGuiEnabled("{\"result\":\"true\"}"));
        assertFalse(OduProtocol.isGuiEnabled("{\"nested\":{\"result\":true}}"));
        assertFalse(OduProtocol.isGuiEnabled("{\"result\":true,}"));
        assertFalse(OduProtocol.isGuiEnabled("[]"));
        assertFalse(OduProtocol.hasResultFalse("{\"result\":\"false\"}"));
    }

    @Test public void guiEnableDecisionAndHttpStatusAreExplicit() {
        assertFalse(OduProtocol.shouldEnableGui(200, "{\"result\":true}"));
        assertTrue(OduProtocol.shouldEnableGui(200, "{\"result\":false}"));
        assertTrue(OduProtocol.shouldEnableGui(503, "{\"result\":true}"));
        assertTrue(OduProtocol.isHttpSuccess(201));
        assertFalse(OduProtocol.isHttpSuccess(302));
        assertTrue(OduProtocol.isAuthRejected(200, "{\"result\":false}"));
    }

    @Test public void jsonStringEscapesCredentialsForRequestBody() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", OduProtocol.jsonString("a\"b\\c\nd"));
    }

    @Test public void cookieHeaderKeepsPairsAcrossMultipleCookiesAndExpiresComma() {
        assertEquals("SID=abc; PREF=dark", OduProtocol.cookieHeader(Arrays.asList(
                "SID=abc; Path=/; HttpOnly",
                "PREF=dark; Expires=Wed, 21 Oct 2030 07:28:00 GMT; Secure")));
        assertEquals("SID=abc; PREF=dark", OduProtocol.cookieHeader(Arrays.asList(
                "SID=abc; Expires=Wed, 21 Oct 2030 07:28:00 GMT, PREF=dark; Path=/")));
    }

    @Test public void acceptsOnlyTheWebUiPorts() throws Exception {
        assertTrue(OduProtocol.isOduWebUiPort(443));
        assertTrue(OduProtocol.isOduWebUiPort(4443));
        assertFalse(OduProtocol.isOduWebUiPort(80));
        assertFalse(OduProtocol.isOduWebUiPort(8443));
        assertFalse(OduProtocol.isOduWebUiPort(0));
        assertFalse(OduProtocol.isOduWebUiPort(-1));
    }

    @Test public void redirectsStayOnTheOduHostOverHttpsAndAWebUiPort() throws Exception {
        java.net.URL base = new java.net.URL("https://192.168.44.12/cgi-bin/app/account/auth");
        assertEquals("192.168.44.12", OduProtocol.oduRedirectUrl("192.168.44.12", base,
                "https://192.168.44.12:4443/cgi-bin/app/account/auth").getHost());
        assertEquals(4443, OduProtocol.oduRedirectUrl("192.168.44.12", base,
                "https://192.168.44.12:4443/cgi-bin/app/account/auth").getPort());
        assertEquals(443, OduProtocol.oduRedirectUrl("192.168.44.12", base,
                "https://192.168.44.12:443/login.htm").getPort());
        java.net.URL relative = OduProtocol.oduRedirectUrl("192.168.44.12", base, "/login.htm");
        assertNotNull(relative);
        assertEquals(-1, relative.getPort()); // implicit https default, treated as 443
        assertEquals("192.168.44.12", relative.getHost());

        assertNull(OduProtocol.oduRedirectUrl("192.168.44.12", base, "http://192.168.44.12/login.htm"));
        assertNull(OduProtocol.oduRedirectUrl("192.168.44.12", base, "https://192.168.44.13/login.htm"));
        assertNull(OduProtocol.oduRedirectUrl("192.168.44.12", base, "https://user@192.168.44.12/login.htm"));
        assertNull(OduProtocol.oduRedirectUrl("192.168.44.12", base, "https://192.168.44.12:8443/login.htm"));
        assertNull(OduProtocol.oduRedirectUrl("192.168.44.12", base, "https://example.test/login.htm"));
        assertNull(OduProtocol.oduRedirectUrl("192.168.44.12", base, "   "));
        assertNull(OduProtocol.oduRedirectUrl("192.168.44.12", base, null));
        assertNull(OduProtocol.oduRedirectUrl(null, base, "/login.htm"));
    }

    @Test public void detectsThe51642UiShells() {
        assertEquals(OduProtocol.UiFamily.META_REFRESH_LOGIN, OduProtocol.detectUiFamily(
                "<html><head><meta http-equiv=\"refresh\" content=\"0; url=login.html\">"
                        + "</head><body><img src=\"JioImages/logo.png\"><link href=\"jioCss.css\">"
                        + "</body></html>"));
        assertEquals(OduProtocol.UiFamily.LEGACY_JIOJS, OduProtocol.detectUiFamily(
                "<html><head><script src=\"jioJs/app.js\"></script><script src=\"sjcl.js\">"
                        + "</script></head><body><a href=\"login.html\">Login</a></body></html>"));
        assertEquals(OduProtocol.UiFamily.JIOODU_SPA, OduProtocol.detectUiFamily(
                "<html><head><title>JioODU</title></head><body><app-root></app-root></body></html>"));
        assertEquals(OduProtocol.UiFamily.JIO_APPLICATION_SPA, OduProtocol.detectUiFamily(
                "<html><head><title>Jio application</title></head><body><mainapp></mainapp>"
                        + "<script src=\"/static/js/main.abc123.js\"></script></body></html>"));
        assertEquals(OduProtocol.UiFamily.UNKNOWN, OduProtocol.detectUiFamily(
                "<html><head><title>Something else</title></head><body>ok</body></html>"));
        assertEquals(OduProtocol.UiFamily.UNKNOWN, OduProtocol.detectUiFamily(null));
    }

    @Test public void extractsSafeMetaRefreshTargets() {
        assertEquals("/login.html", OduProtocol.metaRefreshTarget(
                "<meta http-equiv=\"refresh\" content=\"0; url=login.html\">"));
        assertEquals("/login.html", OduProtocol.metaRefreshTarget(
                "<META HTTP-EQUIV='refresh' CONTENT='0; URL=login.html'>"));
        assertNull(OduProtocol.metaRefreshTarget(
                "<meta http-equiv=\"refresh\" content=\"0; url=https://evil.test/login.html\">"));
        assertNull(OduProtocol.metaRefreshTarget(
                "<meta http-equiv=\"refresh\" content=\"0; url=../login.html\">"));
        assertNull(OduProtocol.metaRefreshTarget("<html>no refresh</html>"));
        assertNull(OduProtocol.metaRefreshTarget(null));
    }
}
