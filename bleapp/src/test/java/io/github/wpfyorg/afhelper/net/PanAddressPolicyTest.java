package io.github.wpfyorg.afhelper.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanAddressPolicyTest {
    @Test public void acceptsOnlyPrivateOnLinkPeerOnActiveBluetoothPanInterface() {
        assertTrue(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.45.22", "bt-pan", true, "192.168.45.1", 24));
        assertTrue(PanAddressPolicy.isOnLinkPrivateIpv4(
                "10.7.0.9", "bnep12", true, "10.7.0.1", 24));
    }

    @Test public void rejectsOffLinkAndNonPrivateCandidates() {
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.46.22", "bt-pan", true, "192.168.45.1", 24));
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "8.8.8.8", "bt-pan", true, "8.8.8.1", 24));
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "239.1.2.3", "bt-pan", true, "192.168.45.1", 24));
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "127.0.0.1", "bt-pan", true, "127.0.0.2", 24));
    }

    @Test public void rejectsInterfaceAddressNetworkAndBroadcast() {
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.45.1", "bt-pan", true, "192.168.45.1", 24));
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.45.0", "bt-pan", true, "192.168.45.1", 24));
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.45.255", "bt-pan", true, "192.168.45.1", 24));
    }

    @Test public void rejectsInactiveInvalidOrNonBluetoothInterfaces() {
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.45.22", "bt-pan", false, "192.168.45.1", 24));
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.45.22", "wlan0", true, "192.168.45.1", 24));
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.45.22", "bnepx", true, "192.168.45.1", 24));
        assertFalse(PanAddressPolicy.isOnLinkPrivateIpv4(
                "192.168.45.22", "bt-pan", true, "192.168.45.1", 0));
    }

    @Test public void keepsLegacyOduPanRangeAccepted() {
        assertTrue(OduProtocol.isOduPanHost("192.168.44.12"));
    }

    @Test public void detectsOnlyActiveOverlappingIpv4Routes() {
        assertTrue(PanAddressPolicy.isOnLinkUnicastIpv4(
                "192.168.45.22", true, "192.168.45.8", 24));
        assertFalse(PanAddressPolicy.isOnLinkUnicastIpv4(
                "192.168.45.22", true, "192.168.46.8", 24));
        assertFalse(PanAddressPolicy.isOnLinkUnicastIpv4(
                "192.168.45.22", false, "192.168.45.8", 24));
        assertEquals(PanAddressPolicy.InventoryResult.AMBIGUOUS,
                PanAddressPolicy.resolveRoute(true, true));
        assertEquals(PanAddressPolicy.InventoryResult.MATCH,
                PanAddressPolicy.resolveRoute(true, false));
    }
}
