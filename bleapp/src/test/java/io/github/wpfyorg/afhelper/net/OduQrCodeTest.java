package io.github.wpfyorg.afhelper.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OduQrCodeTest {
    @Test public void parsesStickerXmlAndNormalizesAddress() {
        OduQrCode code = OduQrCode.parse("<ODU><BTMAC>a4-c1-38-12-34-56</BTMAC>"
                + "<MODELNO>jodu52240</MODELNO><SRNO>0001</SRNO></ODU>");
        assertTrue(code.isValid());
        assertEquals("A4:C1:38:12:34:56", code.bluetoothAddress);
        assertEquals("JODU52240", code.model);
    }

    @Test public void acceptsBareAddressAndDefaultsModel() {
        OduQrCode code = OduQrCode.parse("a4:c1:38:12:34:56");
        assertTrue(code.isValid());
        assertEquals("A4:C1:38:12:34:56", code.bluetoothAddress);
        assertEquals("JODU52140", code.model);
    }

    @Test public void acceptsCompactAddress() {
        OduQrCode code = OduQrCode.parse("A4C138123456");
        assertTrue(code.isValid());
        assertEquals("A4:C1:38:12:34:56", code.bluetoothAddress);
    }

    @Test public void rejectsTextWithoutAddress() {
        OduQrCode code = OduQrCode.parse("<MODELNO>JODU52140</MODELNO>");
        assertFalse(code.isValid());
        assertEquals("", code.bluetoothAddress);
    }

    @Test public void rejectsUnsafeModelText() {
        OduQrCode code = OduQrCode.parse("<BTMAC>A4:C1:38:12:34:56</BTMAC>"
                + "<MODELNO>JODU52140\"}</MODELNO>");
        assertTrue(code.isValid());
        assertEquals("JODU52140", code.model);
    }

    @Test public void nullAndBlankAreSafe() {
        assertFalse(OduQrCode.parse(null).isValid());
        assertFalse(OduQrCode.parse("   ").isValid());
    }
}
