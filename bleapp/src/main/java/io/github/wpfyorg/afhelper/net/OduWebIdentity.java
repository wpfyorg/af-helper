package io.github.wpfyorg.afhelper.net;

import android.content.Context;

import io.github.wpfyorg.afhelper.BuildConfig;

import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;

/**
 * Loads the packaged ODU TLS client identity for the in-app WebUI viewer.
 *
 * The vendor WebUI port requires the same client identity the HTTP actions use.
 * WebView cannot share a socket factory, so the viewer receives the key pair
 * directly and presents it only to the connected ODU host.
 */
public final class OduWebIdentity {
    private static final String ASSET = "af_ble_client.p12";

    public final PrivateKey key;
    public final X509Certificate[] chain;

    private OduWebIdentity(PrivateKey key, X509Certificate[] chain) {
        this.key = key;
        this.chain = chain;
    }

    /** Returns null when no identity is packaged or the keystore cannot be read. */
    public static OduWebIdentity load(Context context) {
        if (!BuildConfig.HAS_CLIENT_IDENTITY) return null;
        char[] password = BuildConfig.CLIENT_IDENTITY_PASSWORD.toCharArray();
        try (InputStream input = context.getApplicationContext().getAssets().open(ASSET)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(input, password);
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!store.isKeyEntry(alias)) continue;
                Key key = store.getKey(alias, password);
                Certificate[] chain = store.getCertificateChain(alias);
                if (!(key instanceof PrivateKey) || chain == null || chain.length == 0) continue;
                X509Certificate[] certificates = new X509Certificate[chain.length];
                boolean allX509 = true;
                for (int i = 0; i < chain.length; i++) {
                    if (!(chain[i] instanceof X509Certificate)) {
                        allX509 = false;
                        break;
                    }
                    certificates[i] = (X509Certificate) chain[i];
                }
                if (allX509) return new OduWebIdentity((PrivateKey) key, certificates);
            }
        } catch (Exception unavailable) {
            // The viewer reports the TLS failure rather than hiding it.
        } finally {
            Arrays.fill(password, '\0');
        }
        return null;
    }
}
