package io.github.wpfyorg.afhelper.net;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the text payload of the ODU's sticker QR code.
 *
 * <p>The sticker is printed as a small XML document on most units, but some
 * labels only carry a bare or colon-separated Bluetooth address. Values stay
 * in memory for the current attempt; the parser never logs them.
 */
public final class OduQrCode {
    private static final Pattern SEPARATED_MAC =
            Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}");
    private static final String[] MAC_TAGS = {
            "BTMAC", "BT_MAC", "MAC", "ODUMAC", "BLE_MAC"
    };
    private static final String[] MODEL_TAGS = {
            "MODELNO", "MODEL_NO", "MODEL", "ODU_MODEL"
    };

    /** Upper-case colon-separated Bluetooth address, or an empty string. */
    public final String bluetoothAddress;
    /** Model reported by the sticker, for example {@code JODU52140}. */
    public final String model;

    private OduQrCode(String bluetoothAddress, String model) {
        this.bluetoothAddress = bluetoothAddress;
        this.model = model;
    }

    public boolean isValid() {
        return !bluetoothAddress.isEmpty();
    }

    public static OduQrCode parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new OduQrCode("", defaultModel(""));
        }
        String text = raw;
        String address = "";
        for (String tag : MAC_TAGS) {
            address = normalizeMac(extractTag(text, tag));
            if (!address.isEmpty()) break;
        }
        if (address.isEmpty()) {
            Matcher separated = SEPARATED_MAC.matcher(text);
            if (separated.find()) address = normalizeMac(separated.group());
        }
        if (address.isEmpty()) {
            String compact = text.trim();
            if (compact.matches("[0-9A-Fa-f]{12}")) address = normalizeMac(compact);
        }
        if (address.isEmpty()) {
            Matcher labelled = Pattern.compile(
                    "(?:MAC|BT)[^0-9A-Fa-f]{0,4}([0-9A-Fa-f]{12})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (labelled.find()) address = normalizeMac(labelled.group(1));
        }

        String model = "";
        for (String tag : MODEL_TAGS) {
            model = normalizeModel(extractTag(text, tag));
            if (!model.isEmpty()) break;
        }
        if (model.isEmpty()) model = defaultModel(text);
        return new OduQrCode(address, model);
    }

    /**
     * Restricts the model to the characters the ODU auth payload expects, so a
     * scanned label cannot inject JSON or grow the request without a bound.
     */
    public static String normalizeModel(String value) {
        if (value == null) return "";
        String trimmed = value.trim().toUpperCase(Locale.US);
        if (trimmed.isEmpty() || trimmed.length() > 32) return "";
        return trimmed.matches("[A-Z0-9_-]+") ? trimmed : "";
    }

    static String normalizeMac(String value) {
        if (value == null) return "";
        String hex = value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        if (hex.length() != 12) return "";
        StringBuilder address = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) address.append(':');
            address.append(hex, i, i + 2);
        }
        return address.toString();
    }

    private static String extractTag(String text, String tag) {
        Pattern pattern = Pattern.compile(
                "<\\s*" + tag + "\\s*>([^<]+)</\\s*" + tag + "\\s*>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String defaultModel(String text) {
        return text != null && text.toUpperCase(Locale.US).contains("52240")
                ? "JODU52240" : "JODU52140";
    }
}
