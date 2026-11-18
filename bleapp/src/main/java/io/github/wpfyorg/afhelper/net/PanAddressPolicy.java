package io.github.wpfyorg.afhelper.net;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.List;

/** Restricts ODU hosts to the legacy range or an on-link private Bluetooth PAN peer. */
final class PanAddressPolicy {
    private PanAddressPolicy() { }

    static OduProtocol.PanAddressCategory classify(String host) {
        OduProtocol.PanAddressCategory syntax = OduProtocol.classifyOduPanAddress(host);
        if (syntax == OduProtocol.PanAddressCategory.EMPTY
                || syntax == OduProtocol.PanAddressCategory.INVALID_IPV4_FORMAT
                || syntax == OduProtocol.PanAddressCategory.VALID_ADDRESS) return syntax;

        int[] candidate = OduProtocol.parseIpv4(host);
        if (!isRfc1918Unicast(candidate)) return OduProtocol.PanAddressCategory.IPV4_OUTSIDE_POLICY;

        InventoryResult result = findActivePanPeer(host, candidate);
        if (result == InventoryResult.MATCH) return OduProtocol.PanAddressCategory.VALID_ADDRESS;
        if (result == InventoryResult.UNAVAILABLE) return OduProtocol.PanAddressCategory.PAN_INTERFACE_UNAVAILABLE;
        if (result == InventoryResult.MISSING) return OduProtocol.PanAddressCategory.PAN_INTERFACE_MISSING;
        if (result == InventoryResult.AMBIGUOUS) return OduProtocol.PanAddressCategory.AMBIGUOUS_ROUTE;
        return OduProtocol.PanAddressCategory.IPV4_OUTSIDE_POLICY;
    }

    private static InventoryResult findActivePanPeer(String candidateText, int[] candidate) {
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException | SecurityException | NullPointerException unavailable) {
            return InventoryResult.UNAVAILABLE;
        }
        if (interfaces == null) return InventoryResult.UNAVAILABLE;

        boolean activePan = false;
        boolean usablePrivateIpv4 = false;
        boolean panPeer = false;
        boolean ambiguousRoute = false;
        boolean queryFailed = false;
        try {
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                String interfaceName = networkInterface.getName();
                boolean bluetoothPan = isBluetoothPanName(interfaceName);
                try {
                    if (!networkInterface.isUp()) continue;
                    if (bluetoothPan) activePan = true;
                    List<InterfaceAddress> addresses = networkInterface.getInterfaceAddresses();
                    for (InterfaceAddress address : addresses) {
                        if (!(address.getAddress() instanceof Inet4Address)) continue;
                        String localAddress = address.getAddress().getHostAddress();
                        int prefix = address.getNetworkPrefixLength();
                        int[] local = OduProtocol.parseIpv4(localAddress);
                        if (bluetoothPan) {
                            if (isUsablePrivatePanAddress(local, prefix)) usablePrivateIpv4 = true;
                            if (isOnLinkPrivateIpv4(candidateText, interfaceName, true,
                                    localAddress, prefix)) panPeer = true;
                        } else if (isOnLinkUnicastIpv4(candidateText, true, localAddress, prefix)) {
                            ambiguousRoute = true;
                        }
                    }
                } catch (SocketException | SecurityException | NullPointerException unavailable) {
                    queryFailed = true;
                }
            }
        } catch (SecurityException | NullPointerException unavailable) {
            queryFailed = true;
        }
        if (queryFailed) return InventoryResult.UNAVAILABLE;
        if (panPeer) return resolveRoute(panPeer, ambiguousRoute);
        if (!activePan || !usablePrivateIpv4) return InventoryResult.MISSING;
        return InventoryResult.NO_MATCH;
    }

    static InventoryResult resolveRoute(boolean panPeer, boolean ambiguousRoute) {
        if (!panPeer) return InventoryResult.NO_MATCH;
        return ambiguousRoute ? InventoryResult.AMBIGUOUS : InventoryResult.MATCH;
    }

    static boolean isOnLinkPrivateIpv4(String candidate, String interfaceName, boolean isUp,
                                       String interfaceAddress, int prefixLength) {
        if (!isBluetoothPanName(interfaceName) || !isUp || prefixLength < 1 || prefixLength > 30) return false;
        int[] peer = OduProtocol.parseIpv4(candidate);
        int[] local = OduProtocol.parseIpv4(interfaceAddress);
        if (!isRfc1918Unicast(peer) || !isRfc1918Unicast(local)) return false;
        if (toAddress(peer) == toAddress(local)) return false;

        return isOnLinkUnicastIpv4(candidate, isUp, interfaceAddress, prefixLength);
    }

    static boolean isOnLinkUnicastIpv4(String candidate, boolean isUp,
                                       String interfaceAddress, int prefixLength) {
        if (!isUp || prefixLength < 1 || prefixLength > 30) return false;
        int[] peer = OduProtocol.parseIpv4(candidate);
        int[] local = OduProtocol.parseIpv4(interfaceAddress);
        if (!isRfc1918Unicast(peer) || local == null) return false;

        long peerValue = toAddress(peer);
        long localValue = toAddress(local);
        long mask = (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
        long network = localValue & mask;
        long broadcast = network | (~mask & 0xffffffffL);
        return peerValue != network && peerValue != broadcast && (peerValue & mask) == network;
    }

    private static boolean isUsablePrivatePanAddress(int[] address, int prefixLength) {
        return prefixLength >= 1 && prefixLength <= 30 && isRfc1918Unicast(address);
    }

    private static boolean isBluetoothPanName(String name) {
        if ("bt-pan".equals(name)) return true;
        if (name == null || !name.startsWith("bnep") || name.length() == 4) return false;
        for (int i = 4; i < name.length(); i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return false;
        }
        return true;
    }

    private static boolean isRfc1918Unicast(int[] octets) {
        if (octets == null || octets.length != 4) return false;
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    private static long toAddress(int[] octets) {
        return ((long) octets[0] << 24) | ((long) octets[1] << 16)
                | ((long) octets[2] << 8) | octets[3];
    }

    enum InventoryResult { MATCH, NO_MATCH, MISSING, UNAVAILABLE, AMBIGUOUS }
}
