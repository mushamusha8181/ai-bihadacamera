package jp.mushamusha.naturalskincamera;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import jp.mushamusha.mushinglass.MushinGlass;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 2001;
    private static final String STATE_USE_FRONT = "state_use_front";
    private static final String STATE_FACING_INITIALIZED = "state_facing_initialized";

    private PreviewView previewView;
    private FrameLayout previewHost;
    private FrameLayout previewFrame;
    private CompositionOverlayView compositionOverlay;
    private TextView countdownLabel;
    private TextView strengthLabel;
    private Button timerButton;
    private Button ratioButton;
    private Button galleryButton;
    private Button flipButton;
    private Button permissionButton;
    private Switch beautySwitch;
    private SeekBar strengthBar;

    private ProcessCameraProvider cameraProvider;
    private boolean cameraProviderRequestInFlight = false;
    private Runnable deferredCameraBindRunnable;
    private boolean cameraBound = false;
    private int lastBoundLensFacing = -1;
    private String lastBoundAspectRatio = null;
    private Preview previewUseCase;
    private ImageCapture imageCapture;
    private boolean useFront = true;
    private boolean cameraFacingInitialized = false;
    private boolean timerRunning = false;
    private Runnable countdownRunnable;
    private volatile boolean capturing = false;
    private volatile boolean destroyPending = false;
    private boolean processingResourcesReleased = false;
    private boolean suppressPreferenceCallbacks = false;
    private int beautyStrength = 12;
    private boolean beautyEnabled = true;
    private boolean gridEnabled = false;
    private String aspectRatio = AppPreferences.RATIO_4_3;
    private Uri lastSavedUri;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final MediaActionSound shutterSound = new MediaActionSound();
    private FaceDetector faceDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (capturing) {
                    Toast.makeText(MainActivity.this, "写真を保存しています", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (timerRunning) {
                    cancelCountdown(true);
                    Toast.makeText(MainActivity.this, "タイマーをキャンセルしました", Toast.LENGTH_SHORT).show();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        if (savedInstanceState != null) {
            useFront = savedInstanceState.getBoolean(STATE_USE_FRONT, true);
            cameraFacingInitialized = savedInstanceState.getBoolean(STATE_FACING_INITIALIZED, false);
        }
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);

        try {
            FaceDetectorOptions detectorOptions = new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.10f)
                    .build();
            faceDetector = FaceDetection.getClient(detectorOptions);
        } catch (RuntimeException ignored) {
            // Camera remains usable; beauty processing will fall back to the raw JPEG.
            faceDetector = null;
        }

        setContentView(buildCameraUi());

        String savedUri = AppPreferences.getString(this, AppPreferences.LAST_SAVED_URI, "");
        if (!savedUri.isEmpty()) {
            try {
                lastSavedUri = Uri.parse(savedUri);
            } catch (Exception ignored) {
                lastSavedUri = null;
                AppPreferences.putString(this, AppPreferences.LAST_SAVED_URI, "");
            }
        }

        if (!AppPreferences.isOnboardingDone(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_USE_FRONT, useFront);
        outState.putBoolean(STATE_FACING_INITIALIZED, cameraFacingInitialized);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPreferenceUi();
        if (!AppPreferences.isOnboardingDone(this)) {
            setPermissionButtonVisible(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setPermissionButtonVisible(false);
            startCamera();
        } else {
            unbindCameraUseCases();
            setPermissionButtonVisible(true);
        }
    }

    @Override
    protected void onPause() {
        cancelDeferredCameraBind();
        if (timerRunning && !capturing) {
            cancelCountdown(true);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyPending = true;
        cancelDeferredCameraBind();
        unbindCameraUseCases();
        shutterSound.release();
        if (!capturing) releaseProcessingResources();
        super.onDestroy();
    }

    private synchronized void releaseProcessingResources() {
        if (processingResourcesReleased) return;
        processingResourcesReleased = true;
        imageExecutor.shutdown();
        if (faceDetector != null) {
            faceDetector.close();
            faceDetector = null;
        }
    }

    private View buildCameraUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        MushinGlass.Config appearance = MushinGlass.config(this);

        int screenHeightDp = getResources().getConfiguration().screenHeightDp;
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        boolean landscapeUi = screenWidthDp > screenHeightDp;
        boolean tinyUi = !landscapeUi && (screenHeightDp < 720 || screenWidthDp < 380);
        boolean compactUi = landscapeUi || tinyUi || screenHeightDp < 800 || screenWidthDp < 420;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), 0);
        top.setBackground(MushinGlass.glassDrawable(this, Color.WHITE, Math.max(46, appearance.glassOpacity - 12), 14));
        int topHeight = landscapeUi ? 48 : (compactUi ? 50 : 54);
        content.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(topHeight)));

        flipButton = topButton("前後");
        ratioButton = topButton("4:3");
        timerButton = topButton("タイマー");
        Button settingsButton = topButton("設定");
        top.addView(flipButton, topButtonParams());
        top.addView(ratioButton, topButtonParams());
        top.addView(timerButton, topButtonParams());
        top.addView(settingsButton, topButtonParams());
        flipButton.setOnClickListener(v -> switchCamera());
        ratioButton.setOnClickListener(v -> showAspectRatioDialog());
        timerButton.setOnClickListener(v -> cycleTimer());
        settingsButton.setOnClickListener(v -> {
            if (capturing || timerRunning) return;
            startActivity(new Intent(this, SettingsActivity.class));
        });

        previewHost = new FrameLayout(this);
        previewHost.setBackgroundColor(Color.BLACK);
        content.addView(previewHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        previewHost.addView(previewFrame, new FrameLayout.LayoutParams(
                dp(1), dp(1), Gravity.CENTER));

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        // 通常プレビューはCameraXの高速経路を優先する。
        // 重ねるタイマーは最初からVISIBLE + alpha=0で作るため、SurfaceViewでも後出し表示問題を避けられる。
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        compositionOverlay = new CompositionOverlayView();
        previewFrame.addView(compositionOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        countdownLabel = new TextView(this);
        countdownLabel.setTag("keep_text_color");
        countdownLabel.setTextColor(Color.WHITE);
        countdownLabel.setTextSize(68);
        countdownLabel.setGravity(Gravity.CENTER);
        countdownLabel.setShadowLayer(8f, 0f, 2f, Color.BLACK);
        countdownLabel.setAlpha(0f);
        countdownLabel.setVisibility(View.VISIBLE);
        countdownLabel.setClickable(false);
        previewFrame.addView(countdownLabel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        int horizontalPadding = landscapeUi ? 6 : (tinyUi ? 8 : 10);
        int verticalPadding = landscapeUi ? 2 : (tinyUi ? 5 : 6);
        bottom.setPadding(dp(horizontalPadding), dp(verticalPadding), dp(horizontalPadding), dp(verticalPadding));
        bottom.setBackground(MushinGlass.glassDrawable(this, Color.WHITE, Math.max(46, appearance.glassOpacity - 12), 14));
        int bottomHeight = landscapeUi ? 108 : (tinyUi ? 126 : (compactUi ? 190 : 200));
        content.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(bottomHeight)));

        beautySwitch = new Switch(this);
        beautySwitch.setText("美肌");
        beautySwitch.setTextColor(Color.WHITE);
        beautySwitch.setTextSize(compactUi ? 14 : 15);
        beautySwitch.setMinHeight(dp(48));

        strengthLabel = new TextView(this);
        strengthLabel.setTextColor(Color.WHITE);
        strengthLabel.setTextSize(compactUi ? 13 : 14);
        strengthLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        strengthBar = new SeekBar(this);
        strengthBar.setMax(30);
        strengthBar.setMinHeight(dp(48));

        LinearLayout beautyRow = new LinearLayout(this);
        beautyRow.setOrientation(LinearLayout.HORIZONTAL);
        beautyRow.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(beautyRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        beautyRow.addView(beautySwitch, new LinearLayout.LayoutParams(dp(72), dp(48)));
        beautyRow.addView(strengthBar, new LinearLayout.LayoutParams(0, dp(48), 1f));
        beautyRow.addView(strengthLabel, new LinearLayout.LayoutParams(dp(62), dp(48)));

        if (!landscapeUi && !tinyUi) {
            LinearLayout presetRow = new LinearLayout(this);
            presetRow.setGravity(Gravity.CENTER);
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            bottom.addView(presetRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

            Button natural = actionButton("ナチュラル", 12);
            Button medium = actionButton("やや美肌", 12);
            Button strong = actionButton("しっかり", 12);
            presetRow.addView(natural, presetParams());
            presetRow.addView(medium, presetParams());
            presetRow.addView(strong, presetParams());
            natural.setOnClickListener(v -> applyPreset(8));
            medium.setOnClickListener(v -> applyPreset(15));
            strong.setOnClickListener(v -> applyPreset(22));
        }

        LinearLayout captureRow = new LinearLayout(this);
        captureRow.setOrientation(LinearLayout.HORIZONTAL);
        captureRow.setGravity(Gravity.CENTER);
        int captureHeight = landscapeUi ? 56 : (tinyUi ? 66 : 70);
        bottom.addView(captureRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(captureHeight)));

        galleryButton = actionButton("写真", 13);
        Button shutter = new Button(this);
        shutter.setTag("keep_text_color keep_mushin_shape");
        shutter.setText("●");
        shutter.setTextSize(compactUi ? 27 : 29);
        shutter.setTextColor(Color.WHITE);
        shutter.setContentDescription("撮影");
        shutter.setPadding(0, 0, 0, 0);
        shutter.setMinWidth(0);
        shutter.setMinHeight(0);
        shutter.setBackground(makeCircle(0xFF5A8F7B));
        int shutterSize = landscapeUi ? 56 : (tinyUi ? 62 : (compactUi ? 64 : 68));
        LinearLayout.LayoutParams shutterLp = new LinearLayout.LayoutParams(dp(shutterSize), dp(shutterSize));
        shutterLp.setMargins(dp(10), 0, dp(10), 0);

        if (landscapeUi || tinyUi) {
            Button presetButton = actionButton("プリセット", 12);
            captureRow.addView(presetButton, new LinearLayout.LayoutParams(dp(100), dp(48)));
            presetButton.setOnClickListener(v -> showPresetDialog());
        }
        captureRow.addView(shutter, shutterLp);
        captureRow.addView(galleryButton, new LinearLayout.LayoutParams(dp(92), dp(48)));
        shutter.setOnClickListener(v -> startTimedCapture());
        galleryButton.setOnClickListener(v -> openPhotos());

        beautySwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            beautyEnabled = checked;
            strengthBar.setEnabled(checked);
            if (!suppressPreferenceCallbacks) {
                AppPreferences.putBoolean(this, AppPreferences.BEAUTY_ENABLED, checked);
            }
        });
        strengthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                beautyStrength = progress;
                strengthLabel.setText(progress + " / 30");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                AppPreferences.putInt(MainActivity.this,
                        AppPreferences.BEAUTY_STRENGTH, seekBar.getProgress());
            }
        });

        permissionButton = actionButton("カメラ権限を許可", 14);
        permissionButton.setVisibility(View.GONE);
        permissionButton.setOnClickListener(v -> showCameraPermissionDialog());
        FrameLayout.LayoutParams permissionLp = new FrameLayout.LayoutParams(
                dp(180), dp(52), Gravity.CENTER);
        root.addView(permissionButton, permissionLp);

        previewHost.addOnLayoutChangeListener((v, left, topPos, right, bottomPos,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            updatePreviewFrameLayout();
        });
        SystemBarInsets.apply(root);
        return root;
    }

    private void updatePreviewFrameLayout() {
        if (previewHost == null || previewFrame == null) return;
        int hostW = previewHost.getWidth();
        int hostH = previewHost.getHeight();
        if (hostW <= 0 || hostH <= 0) return;

        boolean portrait = hostH >= hostW;
        float target = AppPreferences.RATIO_16_9.equals(aspectRatio)
                ? (portrait ? 9f / 16f : 16f / 9f)
                : AppPreferences.RATIO_1_1.equals(aspectRatio)
                ? 1f : (portrait ? 3f / 4f : 4f / 3f);

        int frameW = hostW;
        int frameH = Math.round(frameW / target);
        if (frameH > hostH) {
            frameH = hostH;
            frameW = Math.round(frameH * target);
        }
        frameW = Math.max(1, frameW);
        frameH = Math.max(1, frameH);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewFrame.getLayoutParams();
        boolean sizeChanged = lp.width != frameW || lp.height != frameH;
        if (sizeChanged) {
            cameraBound = false;
            lp.width = frameW;
            lp.height = frameH;
            lp.gravity = Gravity.CENTER;
            previewFrame.setLayoutParams(lp);
        }
        if ((sizeChanged || !cameraBound) && cameraProvider != null
                && !capturing && !timerRunning && !isFinishing() && !isDestroyed()
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            previewFrame.post(() -> {
                compositionOverlay.invalidate();
                bindCameraUseCases();
            });
        }
    }

    private void refreshPreferenceUi() {
        beautyEnabled = AppPreferences.getBoolean(this, AppPreferences.BEAUTY_ENABLED, true);
        gridEnabled = AppPreferences.getBoolean(this, AppPreferences.GRID, false);
        int storedStrength = AppPreferences.getInt(this, AppPreferences.BEAUTY_STRENGTH, 12);
        beautyStrength = Math.max(0, Math.min(30, storedStrength));
        if (beautyStrength != storedStrength) {
            AppPreferences.putInt(this, AppPreferences.BEAUTY_STRENGTH, beautyStrength);
        }
        aspectRatio = AppPreferences.getString(this, AppPreferences.ASPECT_RATIO, AppPreferences.RATIO_4_3);
        if (!isValidRatio(aspectRatio)) {
            aspectRatio = AppPreferences.RATIO_4_3;
            AppPreferences.putString(this, AppPreferences.ASPECT_RATIO, aspectRatio);
        }

        suppressPreferenceCallbacks = true;
        beautySwitch.setChecked(beautyEnabled);
        strengthBar.setProgress(beautyStrength);
        strengthBar.setEnabled(beautyEnabled);
        suppressPreferenceCallbacks = false;
        strengthLabel.setText(beautyStrength + " / 30");
        ratioButton.setText(aspectRatio);
        updateTimerButton();
        compositionOverlay.invalidate();
        updatePreviewFrameLayout();
    }

    private void startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (cameraProvider != null) {
            try {
                initializeCameraFacingIfNeeded();
                updateFlipButtonState();
                if (!cameraBound) bindCameraUseCases();
            } catch (Exception e) {
                showError("カメラを開始できませんでした", e);
            }
            return;
        }
        if (cameraProviderRequestInFlight) return;
        cameraProviderRequestInFlight = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            cameraProviderRequestInFlight = false;
            if (isFinishing() || isDestroyed()) return;
            try {
                cameraProvider = future.get();
                initializeCameraFacingIfNeeded();
                updateFlipButtonState();
                bindCameraUseCases();
            } catch (Exception e) {
                showError("カメラを開始できませんでした", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void initializeCameraFacingIfNeeded() throws Exception {
        if (cameraProvider == null) return;
        boolean hasFront = hasCamera(CameraSelector.LENS_FACING_FRONT);
        boolean hasBack = hasCamera(CameraSelector.LENS_FACING_BACK);
        if (!hasFront && !hasBack) {
            throw new IllegalStateException("利用できるカメラがありません");
        }
        if (cameraFacingInitialized) {
            int restoredFacing = useFront ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            if (hasCamera(restoredFacing)) return;
        }
        useFront = hasFront;
        cameraFacingInitialized = true;
    }

    private boolean hasCamera(int lensFacing) {
        if (cameraProvider == null) return false;
        try {
            CameraSelector selector = new CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build();
            return cameraProvider.hasCamera(selector);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateFlipButtonState() {
        if (flipButton == null) return;
        boolean canFlip = hasCamera(CameraSelector.LENS_FACING_FRONT)
                && hasCamera(CameraSelector.LENS_FACING_BACK);
        flipButton.setEnabled(canFlip);
        flipButton.setAlpha(canFlip ? 1f : 0.45f);
    }

    private void bindCameraUseCases() {
        bindCameraUseCases(true);
    }

    private void bindCameraUseCases(boolean allowOneShotDeferredBind) {
        if (isFinishing() || isDestroyed() || capturing || timerRunning
                || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        if (cameraProvider == null
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (previewView.getWidth() <= 0 || previewView.getHeight() <= 0) {
            if (allowOneShotDeferredBind) scheduleOneShotCameraBind();
            return;
        }

        int requestedFacing = useFront ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        if (cameraBound && requestedFacing == lastBoundLensFacing
                && aspectRatio.equals(lastBoundAspectRatio)) {
            return;
        }

        ViewPort viewPort = previewView.getViewPort();
        if (viewPort == null) {
            // getViewPort() is null while PreviewView is not attached yet.
            // Retry once on the next frame instead of polling every few milliseconds.
            if (allowOneShotDeferredBind) scheduleOneShotCameraBind();
            return;
        }

        int cameraAspect = AppPreferences.RATIO_16_9.equals(aspectRatio)
                ? AspectRatio.RATIO_16_9 : AspectRatio.RATIO_4_3;
        int rotation = previewView.getDisplay() != null
                ? previewView.getDisplay().getRotation() : Surface.ROTATION_0;

        Preview newPreview = new Preview.Builder()
                .setTargetAspectRatio(cameraAspect)
                .setTargetRotation(rotation)
                .build();

        ImageCapture newImageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(cameraAspect)
                .setTargetRotation(rotation)
                .setJpegQuality(95)
                .build();

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(requestedFacing)
                .build();

        UseCaseGroup group = new UseCaseGroup.Builder()
                .addUseCase(newPreview)
                .addUseCase(newImageCapture)
                .setViewPort(viewPort)
                .build();

        unbindCameraUseCases();
        try {
            newPreview.setSurfaceProvider(previewView.getSurfaceProvider());
            cameraProvider.bindToLifecycle(this, selector, group);
            previewUseCase = newPreview;
            imageCapture = newImageCapture;
            cameraBound = true;
            lastBoundLensFacing = requestedFacing;
            lastBoundAspectRatio = aspectRatio;
        } catch (Exception e) {
            try {
                cameraProvider.unbind(newPreview, newImageCapture);
            } catch (RuntimeException ignored) {
                // Best effort cleanup for a partially configured session.
            }
            previewUseCase = null;
            imageCapture = null;
            cameraBound = false;
            showError("このカメラを使用できません", e);
        }
    }

    private void scheduleOneShotCameraBind() {
        if (deferredCameraBindRunnable != null || previewView == null
                || isFinishing() || isDestroyed()
                || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        deferredCameraBindRunnable = () -> {
            deferredCameraBindRunnable = null;
            if (isFinishing() || isDestroyed()
                    || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
            bindCameraUseCases(false);
        };
        previewView.postOnAnimation(deferredCameraBindRunnable);
    }

    private void cancelDeferredCameraBind() {
        if (previewView != null && deferredCameraBindRunnable != null) {
            previewView.removeCallbacks(deferredCameraBindRunnable);
        }
        deferredCameraBindRunnable = null;
    }

    private void unbindCameraUseCases() {
        if (cameraProvider != null) {
            try {
                if (previewUseCase != null && imageCapture != null) {
                    cameraProvider.unbind(previewUseCase, imageCapture);
                } else if (previewUseCase != null) {
                    cameraProvider.unbind(previewUseCase);
                } else if (imageCapture != null) {
                    cameraProvider.unbind(imageCapture);
                }
            } catch (RuntimeException ignored) {
                // The provider can already have detached a lifecycle-bound use case.
            }
        }
        previewUseCase = null;
        imageCapture = null;
        cameraBound = false;
    }

    private void switchCamera() {
        if (capturing || timerRunning || cameraProvider == null) return;
        boolean requestedFront = !useFront;
        int requestedFacing = requestedFront ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        if (!hasCamera(requestedFacing)) {
            Toast.makeText(this, "反対側のカメラはこの端末では利用できません", Toast.LENGTH_SHORT).show();
            return;
        }
        useFront = requestedFront;
        cameraBound = false;
        bindCameraUseCases();
    }

    private void showAspectRatioDialog() {
        if (capturing || timerRunning) return;
        String[] choices = {AppPreferences.RATIO_4_3, AppPreferences.RATIO_16_9, AppPreferences.RATIO_1_1};
        int checked = AppPreferences.RATIO_16_9.equals(aspectRatio) ? 1
                : AppPreferences.RATIO_1_1.equals(aspectRatio) ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle("写真の比率")
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    setAspectRatio(choices[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void setAspectRatio(String value) {
        if (!isValidRatio(value) || value.equals(aspectRatio)) return;
        aspectRatio = value;
        AppPreferences.putString(this, AppPreferences.ASPECT_RATIO, value);
        ratioButton.setText(value);
        compositionOverlay.invalidate();
        cameraBound = false;
        updatePreviewFrameLayout();
    }

    private boolean isValidRatio(String value) {
        return AppPreferences.RATIO_4_3.equals(value)
                || AppPreferences.RATIO_16_9.equals(value)
                || AppPreferences.RATIO_1_1.equals(value);
    }

    private void showPresetDialog() {
        if (capturing || timerRunning) return;
        String[] names = {"ナチュラル（8）", "やや美肌（15）", "しっかり美肌（22）"};
        new AlertDialog.Builder(this)
                .setTitle("美肌プリセット")
                .setItems(names, (dialog, which) -> {
                    if (which == 0) applyPreset(8);
                    else if (which == 1) applyPreset(15);
                    else applyPreset(22);
                })
                .show();
    }

    private void applyPreset(int value) {
        if (capturing || timerRunning) return;
        beautySwitch.setChecked(true);
        strengthBar.setProgress(value);
        AppPreferences.putInt(this, AppPreferences.BEAUTY_STRENGTH, value);
    }

    private void cycleTimer() {
        if (capturing || timerRunning) return;
        int current = getTimerSeconds();
        int next = current == 0 ? 3 : current == 3 ? 5 : current == 5 ? 10 : 0;
        AppPreferences.putInt(this, AppPreferences.TIMER_SECONDS, next);
        updateTimerButton();
    }

    private int getTimerSeconds() {
        int seconds = AppPreferences.getInt(this, AppPreferences.TIMER_SECONDS, 0);
        if (seconds == 0 || seconds == 3 || seconds == 5 || seconds == 10) return seconds;
        AppPreferences.putInt(this, AppPreferences.TIMER_SECONDS, 0);
        return 0;
    }

    private void updateTimerButton() {
        int seconds = getTimerSeconds();
        timerButton.setText(seconds == 0 ? "タイマー" : seconds + "秒");
    }

    private void startTimedCapture() {
        if (capturing || timerRunning) return;
        if (!cameraBound || imageCapture == null) {
            if (isUiInteractive()) {
                Toast.makeText(this, "カメラを準備しています", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        int seconds = getTimerSeconds();
        if (seconds <= 0) {
            capturePhoto();
            return;
        }
        timerRunning = true;
        lockOrientationForCapture();
        runCountdown(seconds);
    }

    private void runCountdown(int remaining) {
        if (!timerRunning) return;
        if (remaining <= 0) {
            timerRunning = false;
            countdownRunnable = null;
            hideCountdown();
            capturePhoto();
            return;
        }
        countdownLabel.setText(String.valueOf(remaining));
        countdownLabel.setAlpha(1f);
        countdownRunnable = () -> runCountdown(remaining - 1);
        uiHandler.postDelayed(countdownRunnable, 1000);
    }

    private void cancelCountdown(boolean unlockOrientation) {
        timerRunning = false;
        if (countdownRunnable != null) {
            uiHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
        hideCountdown();
        if (unlockOrientation && !capturing) unlockOrientation();
    }

    private void hideCountdown() {
        if (countdownLabel != null) {
            countdownLabel.setText("");
            countdownLabel.setAlpha(0f);
        }
    }

    private void lockOrientationForCapture() {
        if (!isFinishing() && !isDestroyed()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
    }

    private void unlockOrientation() {
        if (!isFinishing() && !isDestroyed()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }

    private void capturePhoto() {
        if (capturing) return;
        if (!cameraBound || imageCapture == null) {
            unlockOrientation();
            if (isUiInteractive()) {
                Toast.makeText(this, "カメラの準備が完了していません", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        capturing = true;
        lockOrientationForCapture();
        galleryButton.setEnabled(false);
        int rotation = previewView.getDisplay() != null ? previewView.getDisplay().getRotation() : Surface.ROTATION_0;
        imageCapture.setTargetRotation(rotation);

        File tempFile;
        try {
            tempFile = File.createTempFile("capture_", ".jpg", getCacheDir());
        } catch (IOException e) {
            finishCaptureState();
            showError("一時ファイルを作れませんでした", e);
            return;
        }

        final boolean frontAtCapture = useFront;
        final boolean beautyAtCapture = beautyEnabled;
        final int strengthAtCapture = beautyStrength;
        final boolean mirrorAtCapture = AppPreferences.getBoolean(this, AppPreferences.MIRROR_FRONT, true);
        final boolean keepOriginalAtCapture = AppPreferences.getBoolean(this, AppPreferences.SAVE_ORIGINAL, false);

        ImageCapture.Metadata metadata = new ImageCapture.Metadata();
        metadata.setReversedHorizontal(frontAtCapture && mirrorAtCapture);
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(tempFile)
                .setMetadata(metadata)
                .build();
        ImageCapture.OnImageSavedCallback callback = new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                processCapturedFile(tempFile, beautyAtCapture, strengthAtCapture, keepOriginalAtCapture);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                tempFile.delete();
                runOnUiThread(() -> {
                    finishCaptureState();
                    showError("撮影できませんでした", exception);
                });
            }
        };

        try {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK);
            imageCapture.takePicture(outputOptions, imageExecutor, callback);
        } catch (Exception e) {
            tempFile.delete();
            finishCaptureState();
            showError("撮影を開始できませんでした", e);
        }
    }

    private void processCapturedFile(File tempFile,
                                     boolean beautyAtCapture, int strengthAtCapture,
                                     boolean keepOriginalAtCapture) {
        try {
            // CameraX already applies the requested crop and front-camera mirror metadata.
            // With beauty off, keep its JPEG byte-for-byte instead of decoding/recompressing.
            if (!beautyAtCapture || strengthAtCapture <= 0) {
                Uri uri = saveRawAsPrimary(tempFile);
                tempFile.delete();
                onSaveFinished(uri, false, false);
                return;
            }

            Bitmap composed = loadComposedBitmap(tempFile);
            detectFacesAndProcess(composed, tempFile, strengthAtCapture, keepOriginalAtCapture);
        } catch (OutOfMemoryError memoryError) {
            saveRawFallback(tempFile,
                    "端末のメモリ不足を避けるため、美肌処理を省略して元写真を保存しました");
        } catch (Exception e) {
            saveRawFallback(tempFile,
                    "美肌処理を完了できなかったため、元写真を保存しました");
        }
    }

    private Bitmap loadComposedBitmap(File tempFile) throws IOException {
        Bitmap decoded = decodeForProcessing(tempFile, NaturalSkinProcessor.MAX_PROCESS_DIMENSION);
        if (decoded == null) throw new IOException("JPEGを読み込めませんでした");

        try {
            Bitmap oriented = applyExifOrientation(decoded, tempFile);
            if (oriented != decoded) decoded.recycle();
            // ImageCapture already applies the ViewPort crop before saving to disk.
            return oriented;
        } catch (IOException | RuntimeException e) {
            if (!decoded.isRecycled()) decoded.recycle();
            throw e;
        } catch (OutOfMemoryError e) {
            if (!decoded.isRecycled()) decoded.recycle();
            throw e;
        }
    }

    private void detectFacesAndProcess(Bitmap composed, File tempFile,
                                       int strengthAtCapture, boolean keepOriginalAtCapture) {
        FaceDetector detector = faceDetector;
        if (detector == null) {
            if (!composed.isRecycled()) composed.recycle();
            saveRawFallback(tempFile, "顔検出を開始できなかったため、元写真を保存しました");
            return;
        }
        InputImage image = InputImage.fromBitmap(composed, 0);
        try {
            detector.process(image)
                .addOnSuccessListener(imageExecutor, faces -> {
                    try {
                        List<RectF> faceRects = new ArrayList<>();
                        for (Face face : faces) {
                            Rect r = face.getBoundingBox();
                            faceRects.add(new RectF(r));
                        }

                        if (faceRects.isEmpty()) {
                            saveRawWithoutBeauty(composed, tempFile);
                            return;
                        }

                        Bitmap processed = NaturalSkinProcessor.process(composed, strengthAtCapture, faceRects);
                        if (processed != composed && !composed.isRecycled()) composed.recycle();

                        Uri uri;
                        try {
                            uri = saveBeautyBitmap(processed);
                        } finally {
                            if (!processed.isRecycled()) processed.recycle();
                        }

                        boolean originalSaveFailed = false;
                        if (keepOriginalAtCapture) {
                            try {
                                saveRawOriginal(tempFile);
                            } catch (Exception ignored) {
                                originalSaveFailed = true;
                            }
                        }

                        tempFile.delete();
                        onSaveFinished(uri, false, originalSaveFailed);
                    } catch (OutOfMemoryError memoryError) {
                        if (!composed.isRecycled()) composed.recycle();
                        saveRawFallback(tempFile,
                                "端末のメモリ不足を避けるため、美肌処理を省略して元写真を保存しました");
                    } catch (Exception e) {
                        if (!composed.isRecycled()) composed.recycle();
                        saveRawFallback(tempFile,
                                "美肌処理を完了できなかったため、元写真を保存しました");
                    }
                })
                .addOnFailureListener(imageExecutor, e -> saveRawWithoutBeauty(composed, tempFile))
                .addOnCanceledListener(imageExecutor, () -> saveRawWithoutBeauty(composed, tempFile));
        } catch (RuntimeException e) {
            if (!composed.isRecycled()) composed.recycle();
            saveRawFallback(tempFile, "顔検出を開始できなかったため、元写真を保存しました");
        }
    }

    private void saveRawWithoutBeauty(Bitmap composed, File tempFile) {
        try {
            Uri uri = saveRawAsPrimary(tempFile);
            if (!composed.isRecycled()) composed.recycle();
            tempFile.delete();
            onSaveFinished(uri, true, false);
        } catch (Exception saveError) {
            if (!composed.isRecycled()) composed.recycle();
            tempFile.delete();
            runOnUiThread(() -> {
                finishCaptureState();
                showError("写真の保存に失敗しました", saveError);
            });
        }
    }

    private void saveRawFallback(File tempFile, String notice) {
        try {
            Uri uri = saveRawAsPrimary(tempFile);
            tempFile.delete();
            lastSavedUri = uri;
            AppPreferences.putString(getApplicationContext(), AppPreferences.LAST_SAVED_URI, uri.toString());
            runOnUiThread(() -> {
                finishCaptureState();
                if (isUiInteractive()) {
                    Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception saveError) {
            tempFile.delete();
            runOnUiThread(() -> {
                finishCaptureState();
                showError("写真の保存に失敗しました", saveError);
            });
        }
    }

    private void onSaveFinished(Uri uri, boolean noFace, boolean originalSaveFailed) {
        lastSavedUri = uri;
        AppPreferences.putString(getApplicationContext(), AppPreferences.LAST_SAVED_URI, uri.toString());
        runOnUiThread(() -> {
            finishCaptureState();
            if (!isUiInteractive()) return;

            String message;
            if (originalSaveFailed) {
                message = "保存しました（補正前JPEGだけ保存できませんでした）";
            } else if (noFace) {
                message = "保存しました（顔を検出できなかったため美肌処理は省略）";
            } else {
                message = "保存しました";
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    private void finishCaptureState() {
        capturing = false;
        if (galleryButton != null && !isDestroyed()) galleryButton.setEnabled(true);
        unlockOrientation();
        if (destroyPending) releaseProcessingResources();
    }

    private Bitmap decodeForProcessing(File file, int maxDimension) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while ((max + sample - 1) / sample > maxDimension && sample <= 32) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = true;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private Bitmap applyExifOrientation(Bitmap source, File file) throws IOException {
        ExifInterface exif = new ExifInterface(file);
        boolean flipped = exif.isFlipped();
        int rotation = exif.getRotationDegrees();
        if (!flipped && rotation == 0) return source;

        Matrix matrix = new Matrix();
        // ExifInterface defines rotation degrees assuming a horizontal flip is applied first.
        if (flipped) matrix.postScale(-1f, 1f);
        if (rotation != 0) matrix.postRotate(rotation);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private Uri saveBeautyBitmap(Bitmap bitmap) throws IOException {
        Uri uri = createPendingJpeg("AIBeauty_", "Pictures/AI美肌カメラ");
        try {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
                    throw new IOException("JPEGを書き込めませんでした");
                }
            }
            finalizePendingImage(uri);
            return uri;
        } catch (IOException e) {
            deleteMediaUriQuietly(uri);
            throw e;
        } catch (RuntimeException e) {
            deleteMediaUriQuietly(uri);
            throw e;
        } catch (OutOfMemoryError e) {
            deleteMediaUriQuietly(uri);
            throw e;
        }
    }

    private Uri saveRawAsPrimary(File file) throws IOException {
        return copyRawJpegToMediaStore(file, "AIBeauty_", "Pictures/AI美肌カメラ");
    }

    private Uri saveRawOriginal(File file) throws IOException {
        return copyRawJpegToMediaStore(file, "AIBeauty_original_", "Pictures/AI美肌カメラ/Original");
    }

    private Uri copyRawJpegToMediaStore(File file, String filePrefix, String relativePath) throws IOException {
        Uri uri = createPendingJpeg(filePrefix, relativePath);
        try {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("JPEGを書き込めませんでした");
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            finalizePendingImage(uri);
            return uri;
        } catch (IOException e) {
            deleteMediaUriQuietly(uri);
            throw e;
        } catch (RuntimeException e) {
            deleteMediaUriQuietly(uri);
            throw e;
        }
    }

    private void deleteMediaUriQuietly(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().delete(uri, null, null);
        } catch (RuntimeException ignored) {
            // Best effort cleanup for a failed pending MediaStore row.
        }
    }

    private Uri createPendingJpeg(String filePrefix, String relativePath) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filePrefix + stamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStoreへの保存先を作れませんでした");
        return uri;
    }

    private void finalizePendingImage(Uri uri) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (getContentResolver().update(uri, values, null, null) <= 0) {
            throw new IOException("写真の保存を確定できませんでした");
        }
    }

    private void openPhotos() {
        if (capturing || timerRunning) return;
        if (lastSavedUri != null) {
            try {
                Intent latest = new Intent(Intent.ACTION_VIEW);
                latest.setDataAndType(lastSavedUri, "image/jpeg");
                latest.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(latest);
                return;
            } catch (Exception ignored) {
                lastSavedUri = null;
                AppPreferences.putString(this, AppPreferences.LAST_SAVED_URI, "");
            }
        }

        try {
            Intent gallery = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            gallery.setType("image/*");
            startActivity(gallery);
        } catch (Exception e) {
            Toast.makeText(this, "写真アプリを開けませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private void showCameraPermissionDialog() {
        boolean requestedBefore = AppPreferences.getBoolean(
                this, AppPreferences.CAMERA_PERMISSION_REQUESTED, false);
        boolean rationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);

        String message;
        if (requestedBefore && !rationale) {
            message = "カメラ権限が無効です。もう一度許可を試すか、Androidのアプリ設定からカメラを許可してください。";
        } else {
            message = "AI美肌カメラで撮影するため、カメラへのアクセスを許可してください。";
        }

        new AlertDialog.Builder(this)
                .setTitle("カメラ権限が必要です")
                .setMessage(message)
                .setPositiveButton("許可する", (d, w) -> {
                    AppPreferences.putBoolean(this, AppPreferences.CAMERA_PERMISSION_REQUESTED, true);
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                })
                .setNeutralButton("設定を開く", (d, w) -> openAppSettings())
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;

        AppPreferences.putBoolean(this, AppPreferences.CAMERA_PERMISSION_REQUESTED, true);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        setPermissionButtonVisible(!granted);
        if (granted) {
            cameraBound = false;
            startCamera();
        } else {
            Toast.makeText(this, "撮影するにはカメラ権限が必要です", Toast.LENGTH_LONG).show();
        }
    }

    private void setPermissionButtonVisible(boolean visible) {
        if (permissionButton != null) {
            permissionButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && AppPreferences.getBoolean(this, AppPreferences.VOLUME_SHUTTER, true)
                && cameraBound && imageCapture != null && !capturing && !timerRunning) {
            startTimedCapture();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showError(String title, Throwable error) {
        if (!isUiInteractive()) return;
        String detail = error == null || error.getMessage() == null ? "原因を確認できませんでした。" : error.getMessage();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(detail)
                .setPositiveButton("OK", null)
                .show();
    }

    private boolean isUiInteractive() {
        return !isFinishing() && !isDestroyed()
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private Button topButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinHeight(dp(48));
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private LinearLayout.LayoutParams topButtonParams() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private Button actionButton(String text, int textSize) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(textSize);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinHeight(dp(48));
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    private LinearLayout.LayoutParams presetParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private GradientDrawable makeCircle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(2), Color.WHITE);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class CompositionOverlayView extends View {
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CompositionOverlayView() {
            super(MainActivity.this);
            setClickable(false);
            gridPaint.setColor(0x99FFFFFF);
            gridPaint.setStrokeWidth(dp(1));
            framePaint.setColor(0xCCFFFFFF);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(dp(1));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            RectF frame = new RectF(0, 0, getWidth(), getHeight());
            if (frame.width() <= 0 || frame.height() <= 0) return;

            canvas.drawRect(frame, framePaint);

            if (gridEnabled) {
                float x1 = frame.left + frame.width() / 3f;
                float x2 = frame.left + frame.width() * 2f / 3f;
                float y1 = frame.top + frame.height() / 3f;
                float y2 = frame.top + frame.height() * 2f / 3f;
                canvas.drawLine(x1, frame.top, x1, frame.bottom, gridPaint);
                canvas.drawLine(x2, frame.top, x2, frame.bottom, gridPaint);
                canvas.drawLine(frame.left, y1, frame.right, y1, gridPaint);
                canvas.drawLine(frame.left, y2, frame.right, y2, gridPaint);
            }
        }
    }
}
