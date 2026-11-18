package io.github.wpfyorg.afhelper.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure checks for the ported vendor endpoint parsing and classification. */
public class OduWebActionsTest {

    @Test public void servingCell_isPreferred_overOtherCells() {
        String body = "{\"result\":true,\"CELL_LIST\":["
                + "{\"CELL_ID\":\"5117701\",\"IS_SERVING\":0},"
                + "{\"CELL_ID\":\"5117709\",\"IS_SERVING\":1}]}";
        assertEquals("5117709", OduWebActions.parseServingCellId(body));
    }

    @Test public void servingCell_fallsBackToFirstCell() {
        String body = "{\"result\":true,\"CELL_LIST\":["
                + "{\"CELL_ID\":\"5117701\",\"IS_SERVING\":0}]}";
        assertEquals("5117701", OduWebActions.parseServingCellId(body));
    }

    @Test public void servingCell_missingOrInvalidListIsNull() {
        assertNull(OduWebActions.parseServingCellId(null));
        assertNull(OduWebActions.parseServingCellId(""));
        assertNull(OduWebActions.parseServingCellId("{\"result\":true}"));
        assertNull(OduWebActions.parseServingCellId("{\"CELL_LIST\":\"5117701\"}"));
        assertNull(OduWebActions.parseServingCellId("; invalid"));
    }

    @Test public void signalReport_valuesAndVendorThresholds() throws Exception {
        OduWebActions.SignalReport strong = OduWebActions.parseSignal(
                "5117709", "{\"result\":true,\"NR_5G\":{\"nr_rsrp\":\"-84\",\"nr_snr\":\"20\","
                        + "\"nr_band\":\"78\",\"nr_band_width\":\"100\",\"nr_pci\":\"352\",\"nr_tac\":\"14408\"}}");
        assertEquals(0, reportQualityOrdinal(strong));
        assertEquals(-84.0, reportRsrp(strong), 0.001);
        assertEquals(20.0, reportSnr(strong), 0.001);
        assertEquals("78", strong.band);
        assertEquals("100", strong.bandwidth);
        assertEquals("352", strong.pci);
        assertEquals("14408", strong.tac);
        assertEquals(74, strong.strengthPercent());
        assertEquals("good", strong.strengthLabel());

        OduWebActions.SignalReport moderate = OduWebActions.parseSignal(
                "5117709", "{\"result\":true,\"NR_5G\":{\"nr_rsrp\":\"-95\",\"nr_snr\":\"5\",\"nr_band\":\"78\"}}");
        assertEquals(1, reportQualityOrdinal(moderate));
        assertEquals(50, moderate.strengthPercent());
        assertEquals("fair", moderate.strengthLabel());

        OduWebActions.SignalReport weak = OduWebActions.parseSignal(
                "5117709", "{\"result\":true,\"NR_5G\":{\"nr_rsrp\":\"-112\",\"nr_snr\":\"-2\",\"nr_band\":\"78\"}}");
        assertEquals(2, reportQualityOrdinal(weak));
        assertEquals(6, weak.strengthPercent());
        assertEquals("weak", weak.strengthLabel());
    }

    @Test public void signalReport_naOrMissingValuesAreUnknown() throws Exception {
        OduWebActions.SignalReport unknown = OduWebActions.parseSignal(
                "5117709", "{\"result\":true,\"NR_5G\":{\"nr_rsrp\":\"NA\",\"nr_snr\":\"NA\",\"nr_band\":\"NA\"}}");
        assertNull(unknown.rsrp);
        assertNull(unknown.snr);
        assertNull(unknown.band);
        assertEquals(3, reportQualityOrdinal(unknown));
        assertEquals(-1, unknown.strengthPercent());
        assertEquals("signal values not reported", unknown.strengthLabel());

        OduWebActions.SignalReport noNr = OduWebActions.parseSignal("5117709", "{\"result\":true}");
        assertNull(noNr.rsrp);
        assertEquals(3, reportQualityOrdinal(noNr));
    }

    @Test public void profiles_parseRowsAndClasses() {
        String body = "{\"result\":true,\"profile_info_list\":["
                + "{\"iccid\":\"89911900460166520348\",\"id\":\"73260741245810186317-46520348\","
                + "\"profile_class\":1,\"profile_name\":\"Jio Tariff Plan\","
                + "\"profile_state\":1,\"service_provider_name\":\"Jio\"},"
                + "{\"iccid\":\"89911900460166520349\",\"profile_class\":2,"
                + "\"profile_name\":\"Backup\",\"profile_state\":0}],"
                + "\"number_profile_found\":2}";
        OduWebActions.ProfileRow first = OduWebActions.parseProfiles(body).get(0);
        assertEquals("89911900460166520348", first.iccid);
        assertEquals("73260741245810186317-46520348", first.id);
        assertEquals("Jio Tariff Plan", first.name);
        assertEquals("Jio", first.provider);
        assertEquals(1, first.profileClass);
        assertTrue(first.enabled);
        OduWebActions.ProfileRow second = OduWebActions.parseProfiles(body).get(1);
        assertFalse(second.enabled);
        assertEquals(2, second.profileClass);
        assertEquals(2, OduWebActions.parseProfiles(body).size());
    }

    @Test public void profiles_invalidOrMissingListFails() {
        assertNull(OduWebActions.parseProfiles(null));
        assertNull(OduWebActions.parseProfiles("; not json"));
        assertNull(OduWebActions.parseProfiles("{\"result\":true}"));
        assertEquals(0, OduWebActions.parseProfiles("{\"profile_info_list\":[]}").size());
    }

    @Test public void install_predicateRequiresNewlyEnabledProvisioningProfile() {        OduWebActions.ProfileRow previouslyEnabled = row("100", 1, true);
        OduWebActions.ProfileRow previouslyDisabled = row("200", 1, false);
        OduWebActions.ProfileRow functionalEnabled = row("300", 2, true);

        // A profile enabled before install is not a new activation.
        assertNull(OduWebActions.becameActiveForTest(list(previouslyEnabled), list(previouslyEnabled), null));
        // A newly enabled provisioning profile activates the predicate.
        assertTrue(OduWebActions.becameActiveForTest(list(previouslyDisabled), list(previouslyEnabled), null) != null);
        // Targeted ICCID match activates the predicate.
        assertTrue(OduWebActions.becameActiveForTest(list(previouslyDisabled), list(previouslyEnabled), "100") != null);
        // Functional profiles do not satisfy the vendor provisioning predicate.
        assertNull(OduWebActions.becameActiveForTest(list(), list(functionalEnabled), null));
    }

    @Test public void details_parseEidOnlyWhenThirtyTwoDigits() {
        assertEquals("89049032000000000000000000000001",
                OduWebActions.parseEid("{\"result\":true,\"eid\":\"89049032000000000000000000000001\"}"));
        assertNull(OduWebActions.parseEid("{\"result\":true,\"eid\":\"1234\"}"));
        assertNull(OduWebActions.parseEid("{\"result\":false,\"eid\":\"89049032000000000000000000000001\"}"));
        assertNull(OduWebActions.parseEid("; not json"));
    }

    @Test public void details_readModelAndSoftware() {
        String body = "{\"result\":true,\"model\":\"JODU51741\","
                + "\"SerialNo\":\"REDACTED\",\"CurrentSoftVer\":\"1.2.3\"}";
        assertEquals("JODU51741", OduWebActions.parseModel(body));
        assertEquals("1.2.3", OduWebActions.parseSoftware(body));
        assertNull(OduWebActions.parseModel("{\"result\":false,\"model\":\"JODU51741\"}"));
        assertNull(OduWebActions.parseSoftware("{\"result\":true}"));
    }

    @Test public void cmProfiles_parseRenderedArray() {
        String raw = "[{\"iccid\":\"89911900460166520348\",\"id\":\"100-200\","
                + "\"profile_class\":1,\"profile_name\":\"Jio\","
                + "\"profile_state\":1,\"service_provider_name\":\"Jio\"},"
                + "{\"iccid\":\"89911900460166520349\",\"profile_class\":2,"
                + "\"profile_name\":\"Backup\",\"profile_state\":0}]";
        java.util.List<OduWebActions.ProfileRow> rows = OduWebActions.parseCmProfiles(raw);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).enabled);
        assertFalse(rows.get(1).enabled);
        assertEquals("100-200", rows.get(0).id);

        assertEquals(0, OduWebActions.parseCmProfiles("").size());
        assertNull(OduWebActions.parseCmProfiles("; not json"));
    }

    @Test public void cmSignal_tolerantKeyNamesAndUnits() {
        java.util.Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("rsrp", "-88 dBm");
        raw.put("rsrq", "-11");
        raw.put("sinr", "18");
        raw.put("band", "n78");
        raw.put("bandwidth", "100 MHz");
        raw.put("pcid", "352");
        raw.put("tac", "14408");
        raw.put("cell_id", "5117709");
        OduWebActions.SignalReport report = OduWebActions.parseCmSignal(raw);
        assertEquals(-88.0, report.rsrp, 0.001);
        assertEquals(-11.0, report.rsrq, 0.001);
        assertEquals(18.0, report.snr, 0.001);
        assertEquals("n78", report.band);
        assertEquals("352", report.pci);
        assertEquals("5117709", report.cellId);
        assertEquals(0, report.quality.ordinal());

        raw.put("rsrp", "NA");
        assertEquals(3, OduWebActions.parseCmSignal(raw).quality.ordinal());
        assertEquals(3, OduWebActions.parseCmSignal(null).quality.ordinal());
    }

    @Test public void activationCode_requiresFullLpaShape() {
        assertTrue(OduWebActions.isActivationCode("LPA:1$sm-dp.example.com$ABC123"));
        assertFalse(OduWebActions.isActivationCode("LPA:1$sm-dp.example.com"));
        assertFalse(OduWebActions.isActivationCode("sm-dp.example.com$ABC123"));
        assertFalse(OduWebActions.isActivationCode("LPA:1$$ABC123"));
        assertFalse(OduWebActions.isActivationCode("LPA:1$sm-dp.example.com$"));
        assertFalse(OduWebActions.isActivationCode(null));
    }

    private static java.util.List<OduWebActions.ProfileRow> list(OduWebActions.ProfileRow... rows) {
        java.util.List<OduWebActions.ProfileRow> out = new java.util.ArrayList<>();
        for (OduWebActions.ProfileRow row : rows) out.add(row);
        return out;
    }

    private static OduWebActions.ProfileRow row(String iccid, int profileClass, boolean enabled) {
        return new OduWebActions.ProfileRow(iccid, "id-" + iccid, "Profile " + iccid,
                "Jio", profileClass, enabled);
    }

    private static int reportQualityOrdinal(OduWebActions.SignalReport report) throws Exception {
        java.lang.reflect.Field field = OduWebActions.SignalReport.class.getDeclaredField("quality");
        field.setAccessible(true);
        return ((Enum<?>) field.get(report)).ordinal();
    }

    private static double reportRsrp(OduWebActions.SignalReport report) throws Exception {
        java.lang.reflect.Field field = OduWebActions.SignalReport.class.getDeclaredField("rsrp");
        field.setAccessible(true);
        return (Double) field.get(report);
    }

    private static double reportSnr(OduWebActions.SignalReport report) throws Exception {
        java.lang.reflect.Field field = OduWebActions.SignalReport.class.getDeclaredField("snr");
        field.setAccessible(true);
        return (Double) field.get(report);
    }

    @Test public void profileTargets_addressIccidFirstThenNumericId() {
        // Firmware that only accepts the ICCID (numeric id answers msg_id 4352).
        assertArrayEquals(new String[]{"89918660400389997354", "3"},
                OduWebActions.profileTargets(
                        new OduWebActions.ProfileRow("89918660400389997354", "3", "Jio", "Jio", 2, true)));
        // Firmware that reports no ICCID: the numeric id is all we have.
        assertArrayEquals(new String[]{"3"},
                OduWebActions.profileTargets(
                        new OduWebActions.ProfileRow("", "3", "Jio", "Jio", 2, false)));
        // A value valid as both must not be sent twice.
        assertArrayEquals(new String[]{"12345678"},
                OduWebActions.profileTargets(
                        new OduWebActions.ProfileRow("12345678", "12345678", "Jio", "Jio", 2, false)));
        assertArrayEquals(new String[0], OduWebActions.profileTargets(null));
    }
}
