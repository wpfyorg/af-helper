package io.github.wpfyorg.afhelper.ble;

public final class OduBle {
    public static final String SERVICE = "0000ca77-0000-1000-a435-311b63657f1f";
    public static final String DISCOVERY = "0000ca78-0000-1000-a435-311b63657f1f";
    public static final String PAN_IP = "0000ca7a-0000-1000-a435-311b63657f1f";
    public static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";
    public static final byte[] KICK = {0x01};
    public static final byte TETHERING_OFF = (byte) 0xff;
    public static final byte BUSY = (byte) 0xfe;

    private OduBle() {}
}
