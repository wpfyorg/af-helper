package io.github.wpfyorg.afhelper.net;

import android.content.Context;

import io.github.wpfyorg.afhelper.BuildConfig;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Builds the ODU HTTPS socket factory, adding the optional packaged mTLS client identity when present. */
final class OduClientTls {
    private static final String ASSET = "af_ble_client.p12";

    private final Context appContext;

    OduClientTls(Context context) {
        this.appContext = context.getApplicationContext();
    }

    SSLSocketFactory socketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(clientKeyManagers(), new TrustManager[]{trustLocalServer()}, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception unavailable) {
            return null;
        }
    }

    private KeyManager[] clientKeyManagers() {
        if (!BuildConfig.HAS_CLIENT_IDENTITY) return null;
        char[] password = BuildConfig.CLIENT_IDENTITY_PASSWORD.toCharArray();
        try (InputStream input = appContext.getAssets().open(ASSET)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, password);
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, password);
            return factory.getKeyManagers();
        } catch (Exception unavailable) {
            return null;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static X509TrustManager trustLocalServer() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }
}
