package io.github.wpfyorg.afhelper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal QR reader for the ODU sticker.
 *
 * <p>Only the decoded text is returned to the caller. The camera frames are
 * never written to disk and the payload is never logged. The activity asks for
 * the camera permission itself so the main screen does not need to pre-request
 * a permission that may not be used.
 */
public final class QrScanActivity extends Activity
        implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String EXTRA_QR_RESULT = "io.github.wpfyorg.afhelper.extra.QR_RESULT";
    public static final String EXTRA_ERROR = "io.github.wpfyorg.afhelper.extra.QR_ERROR";

    private static final int REQUEST_CAMERA = 6101;
    private static final int MAX_RESULT_CHARS = 4096;

    private SurfaceView preview;
    private SurfaceHolder holder;
    private TextView flashButton;
    private Camera camera;
    private MultiFormatReader reader;
    private boolean surfaceReady;
    private boolean permissionRequested;
    private boolean flashOn;
    private int previewWidth;
    private int previewHeight;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final AtomicBoolean delivered = new AtomicBoolean(false);
    private final Runnable autoFocus = new Runnable() {
        @Override public void run() {
            if (camera == null || delivered.get()) return;
            try {
                camera.cancelAutoFocus();
                camera.autoFocus(null);
            } catch (RuntimeException ignored) {
                // Some fixed-focus cameras reject this; preview keeps running.
            }
            main.postDelayed(this, 1800);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildScreen();
        reader = new MultiFormatReader();
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera();
        }
    }

    private void buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new SurfaceView(this);
        holder = preview.getHolder();
        holder.addCallback(this);
        preview.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN && camera != null) {
                    try {
                        camera.cancelAutoFocus();
                        camera.autoFocus(null);
                    } catch (RuntimeException ignored) {
                    }
                }
                return true;
            }
        });
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView guide = new TextView(this);
        guide.setText("Point the camera at the ODU sticker QR code");
        guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        guide.setTextColor(Color.WHITE);
        guide.setGravity(Gravity.CENTER);
        guide.setBackgroundColor(0x99000000);
        guide.setPadding(dp(16), dp(12), dp(16), dp(12));
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        guideParams.topMargin = dp(36);
        root.addView(guide, guideParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(16), dp(14), dp(16), dp(20));

        flashButton = overlayButton("Flash");
        flashButton.setOnClickListener(v -> toggleFlash());
        actions.addView(flashButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        Button cancel = overlayButton("Cancel");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        cancelParams.leftMargin = dp(10);
        actions.addView(cancel, cancelParams);

        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(actions, actionParams);

        setContentView(root);
    }

    private Button overlayButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(0xCC1E323A);
        return button;
    }

    private void requestCamera() {
        if (permissionRequested) return;
        permissionRequested = true;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                    int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) openCameraIfAllowed();
        else finishWithError("Camera permission is needed to scan the ODU sticker.");
    }

    private void openCameraIfAllowed() {
        if (!surfaceReady || camera != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera();
            return;
        }
        try {
            camera = openBackCamera();
            if (camera == null) throw new IOException("no camera");
            try {
                camera.setDisplayOrientation(90);
            } catch (RuntimeException ignored) {
            }
            Camera.Parameters parameters = camera.getParameters();
            try {
                List<String> scenes = parameters.getSupportedSceneModes();
                if (scenes != null && scenes.contains(Camera.Parameters.SCENE_MODE_BARCODE)) {
                    parameters.setSceneMode(Camera.Parameters.SCENE_MODE_BARCODE);
                }
            } catch (RuntimeException ignored) {
            }
            try {
                List<String> focus = parameters.getSupportedFocusModes();
                if (focus != null) {
                    if (focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    } else if (focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    }
                }
            } catch (RuntimeException ignored) {
            }
            try {
                if (parameters.getMaxNumFocusAreas() > 0) {
                    parameters.setFocusAreas(Collections.singletonList(
                            new Camera.Area(new Rect(-400, -400, 400, 400), 1000)));
                }
            } catch (RuntimeException ignored) {
            }
            try {
                List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
                Camera.Size chosen = null;
                if (sizes != null) {
                    int best = Integer.MAX_VALUE;
                    for (Camera.Size size : sizes) {
                        if (size.width > 1920 || size.height > 1080) continue;
                        int distance = Math.abs(size.height - 720);
                        if (distance < best) {
                            chosen = size;
                            best = distance;
                        }
                    }
                }
                if (chosen != null) parameters.setPreviewSize(chosen.width, chosen.height);
            } catch (RuntimeException ignored) {
            }
            try {
                parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
            } catch (RuntimeException ignored) {
            }
            try {
                camera.setParameters(parameters);
            } catch (RuntimeException ignored) {
                Camera.Parameters fallback = camera.getParameters();
                fallback.setPreviewFormat(android.graphics.ImageFormat.NV21);
                camera.setParameters(fallback);
            }
            Camera.Size size = camera.getParameters().getPreviewSize();
            previewWidth = size.width;
            previewHeight = size.height;
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
            main.postDelayed(autoFocus, 1000);
        } catch (Throwable unavailable) {
            finishWithError("The camera is not available on this device.");
        }
    }

    @SuppressWarnings("deprecation")
    private static Camera openBackCamera() {
        int cameras = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int index = 0; index < cameras; index++) {
            Camera.getCameraInfo(index, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return Camera.open(index);
            }
        }
        return cameras > 0 ? Camera.open() : null;
    }

    @Override public void surfaceCreated(SurfaceHolder surfaceHolder) {
        surfaceReady = true;
        openCameraIfAllowed();
    }

    @Override public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width,
                                         int height) {
    }

    @Override public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        surfaceReady = false;
        releaseCamera();
    }

    @Override public void onPreviewFrame(byte[] data, Camera source) {
        if (data == null || delivered.get() || !decoding.compareAndSet(false, true)) return;
        final int width = previewWidth;
        final int height = previewHeight;
        if (width <= 0 || height <= 0 || data.length < width * height) {
            decoding.set(false);
            return;
        }
        final byte[] frame = new byte[width * height];
        System.arraycopy(data, 0, frame, 0, frame.length);
        decoder.execute(() -> decodeFrame(width, height, frame));
    }

    private void decodeFrame(int width, int height, byte[] frame) {
        try {
            int side = ((int) (Math.min(width, height) * 0.72)) & ~1;
            int left = ((width - side) / 2) & ~1;
            int top = ((height - side) / 2) & ~1;
            byte[] crop = new byte[side * side];
            for (int row = 0; row < side; row++) {
                int source = ((top + row) * width) + left;
                int column = (side - 1) - row;
                for (int x = 0; x < side; x++) {
                    crop[(x * side) + column] = frame[source + x];
                }
            }
            PlanarYUVLuminanceSource source =
                    new PlanarYUVLuminanceSource(crop, side, side, 0, 0, side, side, false);
            Result result = null;
            try {
                result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            } catch (NotFoundException retry) {
                try {
                    result = reader.decodeWithState(
                            new BinaryBitmap(new HybridBinarizer(source.invert())));
                } catch (NotFoundException ignored) {
                }
            } finally {
                reader.reset();
            }
            if (result != null && result.getText() != null) {
                String text = result.getText().trim();
                if (!text.isEmpty() && delivered.compareAndSet(false, true)) {
                    if (text.length() > MAX_RESULT_CHARS) text = text.substring(0, MAX_RESULT_CHARS);
                    final String value = text;
                    runOnUiThread(() -> deliver(value));
                }
            }
        } catch (Throwable ignored) {
            // A malformed frame is not an error; keep scanning.
        } finally {
            decoding.set(false);
        }
    }

    private void deliver(String value) {
        if (isFinishing()) return;
        Intent data = new Intent();
        data.putExtra(EXTRA_QR_RESULT, value);
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishWithError(String message) {
        if (isFinishing()) return;
        Intent data = new Intent();
        data.putExtra(EXTRA_ERROR, message);
        setResult(RESULT_CANCELED, data);
        finish();
    }

    private void toggleFlash() {
        if (camera == null) return;
        try {
            Camera.Parameters parameters = camera.getParameters();
            List<String> modes = parameters.getSupportedFlashModes();
            if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                flashButton.setText("No flash");
                return;
            }
            flashOn = !flashOn;
            parameters.setFlashMode(flashOn
                    ? Camera.Parameters.FLASH_MODE_TORCH
                    : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(parameters);
            flashButton.setText(flashOn ? "Flash on" : "Flash");
        } catch (RuntimeException ignored) {
            flashOn = false;
            flashButton.setText("Flash");
        }
    }

    @SuppressWarnings("deprecation")
    private void releaseCamera() {
        main.removeCallbacks(autoFocus);
        Camera current = camera;
        camera = null;
        if (current == null) return;
        try {
            current.setPreviewCallback(null);
            current.stopPreview();
        } catch (RuntimeException ignored) {
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
        }
    }

    @Override protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (surfaceReady) openCameraIfAllowed();
    }

    @Override protected void onDestroy() {
        decoder.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
