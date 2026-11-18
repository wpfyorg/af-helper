package io.github.wpfyorg.afhelper.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/** Pure checks for the CM-family token, page, and status parsing. */
public class OduCmClientTest {

    @Test public void token_extractsQuotedValue() {
        assertEquals("abc123", OduCmClient.parseToken(
                "{\"RequestVerifyToken\":\"abc123\",\"result\":true}"));
        assertEquals("", OduCmClient.parseToken("{\"result\":false}"));
        assertEquals("", OduCmClient.parseToken(null));
    }

    @Test public void result_acceptsJsonAndServerRenderedPage() {
        assertTrue(OduCmClient.resultIsTrue("{\"result\":true}"));
        assertTrue(OduCmClient.resultIsTrue("<html><body>\"result\": true</body></html>"));
        assertFalse(OduCmClient.resultIsTrue("{\"result\":false}"));
        assertFalse(OduCmClient.resultIsTrue("{\"result\":\"true\"}"));
        assertFalse(OduCmClient.resultIsTrue(null));
    }

    @Test public void profiles_extractEscapedJavaScriptString() {
        String page = "<script>var esim_profile_list_tmp = "
                + "\"[{&quot;iccid&quot;:&quot;8991&quot;,&quot;profile_state&quot;:1}]\";"
                + "</script>";
        assertEquals("[{\"iccid\":\"8991\",\"profile_state\":1}]",
                OduCmClient.extractProfileList(page));
        assertEquals("", OduCmClient.extractProfileList("<html>none</html>"));
        assertEquals("", OduCmClient.extractProfileList(null));
    }

    @Test public void profiles_unescapeJsonEscapes() {
        assertEquals("[{\"a\":\"b/c\"}]", OduCmClient.unescapePage("[{\\\"a\\\":\\\"b\\/c\\\"}]"));
    }

    @Test public void flatPairs_readsMixedValues() {
        Map<String, String> values = OduCmClient.flatPairs(
                "{\"rsrp\":\"-88\",\"sinr\": 18,\"band\":\"n78\",\"empty\":\"\"}");
        assertEquals("-88", values.get("rsrp"));
        assertEquals("18", values.get("sinr"));
        assertEquals("n78", values.get("band"));
        assertFalse(values.containsKey("missing"));
    }
}
