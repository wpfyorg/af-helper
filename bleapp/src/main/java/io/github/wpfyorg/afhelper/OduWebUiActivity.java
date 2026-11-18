package io.github.wpfyorg.afhelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ClientCertRequest;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import io.github.wpfyorg.afhelper.net.OduProtocol;
import io.github.wpfyorg.afhelper.net.OduWebIdentity;

/**
 * Shows the ODU's own WebUI inside the app.
 *
 * Chrome cannot present the ODU's client identity and rejects its self-signed
 * certificate, so the viewer accepts that certificate and the packaged identity
 * only for the connected ODU host. Every other origin stays blocked.
 */
public final class OduWebUiActivity extends Activity {
    public static final String EXTRA_HOST = "io.github.wpfyorg.afhelper.extra.ODU_HOST";

    private static final int INK = Color.rgb(11, 26, 32);
    private static final int MUTED = Color.rgb(92, 111, 114);
    private static final int SIGNAL = Color.rgb(14, 124, 134);
    private static final int ALERT = Color.rgb(179, 64, 46);
    private static final int PAPER = Color.rgb(242, 244, 243);
    private static final int LINE = Color.rgb(219, 226, 226);

    private String host;
    private WebView webView;
    private ProgressBar progress;
    private TextView noticeView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_HOST);
        if (host == null || host.trim().isEmpty()) {
            finish();
            return;
        }
        host = host.trim();
        if (!OduProtocol.isOduPanHost(host)) {
            finish();
            return;
        }
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(INK);
        header.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("ODU WebUI");
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setTextSize(13);
        close.setTextColor(Color.WHITE);
        close.setBackground(rounded(Color.rgb(30, 50, 58), dp(10)));
        close.setPadding(dp(14), 0, dp(14), 0);
        close.setMinHeight(dp(38));
        close.setOnClickListener(v -> finish());
        titleRow.addView(close);
        header.addView(titleRow);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView local = new TextView(this);
        local.setText(host + " · local link");
        local.setTextSize(12);
        local.setTextColor(Color.rgb(150, 173, 176));
        addressRow.addView(local, new LinearLayout.LayoutParams(0, -2, 1));
        Button reload = new Button(this);
        reload.setText("Reload");
        reload.setAllCaps(false);
        reload.setTextSize(12);
        reload.setTextColor(Color.rgb(119, 204, 207));
        reload.setBackground(rounded(Color.rgb(30, 50, 58), dp(10)));
        reload.setPadding(dp(12), 0, dp(12), 0);
        reload.setMinHeight(dp(34));
        reload.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });
        addressRow.addView(reload);
        header.addView(addressRow, topMargin(addressRow, 6));
        root.addView(header);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        noticeView = new TextView(this);
        noticeView.setTextSize(12);
        noticeView.setTextColor(ALERT);
        noticeView.setBackgroundColor(Color.rgb(252, 240, 237));
        noticeView.setPadding(dp(18), dp(8), dp(18), dp(8));
        noticeView.setVisibility(View.GONE);
        root.addView(noticeView);

        try {
            webView = new WebView(this);
        } catch (Throwable unavailable) {
            showWebViewUnavailable();
            return;
        }
        configure(webView);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        webView.loadUrl("https://" + host + "/");
    }

    @SuppressLint("SetJavaScriptEnabled") // The vendor WebUI is a JavaScript app; it only loads the validated ODU host.
    private void configure(WebView view) {
        view.setBackgroundColor(PAPER);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSaveFormData(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        final OduWebIdentity identity = OduWebIdentity.load(this);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (isOduUrl(target)) return false;
                showNotice("Only this ODU's local WebUI can open here.");
                return true;
            }

            @Override public void onPageStarted(WebView web, String url, android.graphics.Bitmap icon) {
                progress.setVisibility(View.VISIBLE);
                noticeView.setVisibility(View.GONE);
            }

            @Override public void onPageFinished(WebView web, String url) {
                progress.setVisibility(View.GONE);
            }

            @SuppressLint("WebViewClientOnReceivedSslError") // Scoped to the validated ODU host below.
            @Override public void onReceivedSslError(WebView web, SslErrorHandler handler,
                                                     SslError error) {
                if (isOduUrl(Uri.parse(error.getUrl()))) {
                    noticeView.setVisibility(View.GONE);
                    handler.proceed();
                } else {
                    handler.cancel();
                    showNotice("Blocked a certificate from an unexpected host.");
                }
            }

            @Override public void onReceivedClientCertRequest(WebView web, ClientCertRequest request) {
                if (!host.equalsIgnoreCase(request.getHost()) || identity == null) {
                    request.cancel();
                    showNotice(identity == null
                            ? "This ODU requires a client certificate that is not packaged in this build."
                            : "Blocked a client certificate request from an unexpected host.");
                    return;
                }
                request.proceed(identity.key, identity.chain);
            }

            @Override public void onReceivedError(WebView web, WebResourceRequest request,
                                                  WebResourceError error) {
                if (request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    showNotice("Could not load the ODU WebUI. Check that Bluetooth tethering "
                            + "is on and the ODU is still connected, then reload.");
                }
            }
        });
        view.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                                  String mimeType, long contentLength) {
                showNotice("Downloads from this ODU are not supported in the app.");
            }
        });
    }

    private boolean isOduUrl(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && host.equalsIgnoreCase(uri.getHost());
    }

    private void showNotice(String message) {
        if (noticeView == null) return;
        noticeView.setText(message);
        noticeView.setVisibility(View.VISIBLE);
    }

    private void showWebViewUnavailable() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        TextView body = new TextView(this);
        body.setText("This device has no usable WebView, so the ODU WebUI cannot be shown in the "
                + "app. A browser will warn about the ODU certificate.");
        body.setTextSize(14);
        box.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("WebUI viewer unavailable")
                .setView(box)
                .setPositiveButton("Open in browser", (dialog, which) -> openExternal())
                .setNegativeButton("Close", (dialog, which) -> finish())
                .setOnDismissListener(dialog -> finish())
                .show();
    }

    private void openExternal() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + host + "/")));
        } catch (RuntimeException unavailable) {
            // Nothing else to offer.
        }
        finish();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.clearFormData();
            webView.clearCache(true);
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        try {
            CookieManager cookies = CookieManager.getInstance();
            cookies.removeAllCookies(null);
            cookies.flush();
            WebStorage.getInstance().deleteAllData();
        } catch (RuntimeException ignored) {
            // WebView may already be torn down.
        }
        super.onDestroy();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private LinearLayout.LayoutParams topMargin(View view, int valueDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(valueDp);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
