package com.openai.nikonintervalometer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.openai.nikonintervalometer.USB_PERMISSION";

    private static final int OLED_BLACK = Color.BLACK;
    private static final int RED = Color.rgb(255, 45, 45);
    private static final int GREY = Color.rgb(170, 170, 170);
    private static final int DARK_BUTTON = Color.rgb(22, 22, 22);
    private static final int DISABLED_GREY = Color.rgb(110, 110, 110);
    private static final int DISABLED_BUTTON = Color.rgb(12, 12, 12);

    private UsbManager usbManager;
    private NikonBulbRemote camera;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private volatile boolean manualExposureOpen = false;
    private volatile boolean exposureActive = false;
    private volatile boolean liveViewPreviewRequested = false;
    private volatile boolean mirrorLockUpEnabled = false;
    private volatile boolean mirrorLockSuspendedForPlayback = false;
    private volatile boolean liveViewFrameInFlight = false;
    private volatile long manualExposureStartedAt = 0L;
    private volatile boolean cancelRequested = false;
    private volatile boolean statusCheckInFlight = false;
    private volatile boolean cameraSetupReady = false;
    private volatile boolean manualModeReady = false;
    private volatile boolean focusReady = false;
    private volatile boolean suppressShutterSelectionCallback = false;
    private volatile boolean suppressIsoSelectionCallback = false;
    private volatile boolean shutterWheelInteracting = false;
    private volatile boolean isoWheelInteracting = false;
    private volatile boolean cameraSettingInFlight = false;
    private volatile long selectedExposureChoice = 0;
    private volatile long cameraExposureTime = 0;
    private volatile boolean exposureTimeWritable = false;
    private volatile int selectedIsoChoice = -1;
    private volatile int cameraIso = -1;
    private volatile boolean isoWritable = false;
    private long[] shutterWheelValues = new long[0];
    private int[] isoWheelValues = new int[0];
    private Runnable pendingShutterApply;
    private Runnable pendingIsoApply;
    private boolean destroyed = false;
    private int completed = 0;
    private CaptureMode selectedMode = CaptureMode.NATIVE;
    private long lastNativeExposureChoice = 0;

    private static final int TAB_CAPTURE = 0;
    private static final int TAB_LIVE = 1;
    private static final int TAB_PLAYBACK = 2;
    private int activeTab = TAB_CAPTURE;

    private TextView status;
    private TextView headerBattery;
    private TextView countdown;
    private TextView cameraModeCheck;
    private TextView focusCheck;
    private TextView totalTimeView;
    private TextView timeLeftView;
    private NumberPicker shutterPicker;
    private NumberPicker isoPicker;
    private EditText exposureField;
    private EditText pauseField;
    private EditText countField;
    private TextView timeDurationLabel;
    private TextView countLabel;
    private TextView pauseLabel;
    private LinearLayout nativeShutterContainer;
    private Switch mirrorLockUpToggle;
    private Button startButton;
    private Button captureTabButton;
    private Button liveTabButton;
    private Button playbackTabButton;
    private Button nativeModeButton;
    private Button timeModeButton;
    private Button bulbModeButton;
    private LinearLayout capturePage;
    private LinearLayout livePage;
    private LinearLayout playbackPage;
    private ZoomableImageView liveViewImage;
    private TextView liveViewFrameStatus;
    private Button previousButton;
    private Button nextButton;
    private ZoomableImageView playbackImage;
    private TextView playbackStatus;
    private LinearLayout playbackNav;
    private int[] playbackHandles = new int[0];
    private int playbackIndex = -1;

    private final Runnable liveViewFramePoller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;

            boolean canFetch = liveViewPreviewRequested
                    && activeTab == TAB_LIVE
                    && camera != null
                    && camera.isConnected()
                    && !running
                    && !statusCheckInFlight
                    && !cameraSettingInFlight
                    && !liveViewFrameInFlight;

            if (!canFetch) {
                main.postDelayed(this, 80);
                return;
            }

            liveViewFrameInFlight = true;
            io.execute(() -> {
                try {
                    byte[] jpeg = camera.getLiveViewJpeg();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (bitmap == null) {
                        throw new Exception("Live View image could not be decoded");
                    }

                    main.post(() -> {
                        boolean first = liveViewImage.getDrawable() == null;
                        liveViewImage.setImageBitmap(bitmap);
                        if (first) liveViewImage.fitAfterNextLayout();
                        liveViewFrameStatus.setText(
                                bitmap.getWidth() + " × " + bitmap.getHeight());
                    });
                } catch (Exception e) {
                    main.post(() -> liveViewFrameStatus.setText(
                            "Preview: " + e.getMessage()));
                } finally {
                    liveViewFrameInFlight = false;
                    if (!destroyed) {
                        main.postDelayed(liveViewFramePoller, 20);
                    }
                }
            });
        }
    };

    private final Runnable exposureElapsedPoller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (manualExposureOpen && manualExposureStartedAt > 0 && countdown != null) {
                long elapsedMs = SystemClock.elapsedRealtime() - manualExposureStartedAt;
                countdown.setText("Bulb exposure — " + SequenceTiming.formatDuration(elapsedMs));
                timeLeftView.setText("Elapsed: " + SequenceTiming.formatDuration(elapsedMs));
            }
            main.postDelayed(this, 200);
        }
    };

    private final Runnable cameraStatusPoller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (camera != null && camera.isConnected() && !running && !statusCheckInFlight
                    && !liveViewPreviewRequested
                    && !shutterWheelInteracting && !isoWheelInteracting && !cameraSettingInFlight) {
                refreshCameraChecklist();
            }
            main.postDelayed(this, 1500);
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) connectTo(device);
                else setStatus("USB permission denied");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (!camera.isConnected()) {
                    if (camera.isCameraDevice(device)) {
                        connectOrRequestPermission(device);
                    } else {
                        // Hubs and adapters can attach before the camera behind them.
                        // Never request permission for those; rescan for the Nikon instead.
                        main.postDelayed(MainActivity.this::scanAndConnect, 250);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                cancelRequested = true;
                running = false;
                manualExposureOpen = false;
                exposureActive = false;
                liveViewPreviewRequested = false;
                mirrorLockUpEnabled = false;
                mirrorLockSuspendedForPlayback = false;
                camera.disconnect();
                setChecklistUnknown();
                setStatus("Camera disconnected");
                updateButtons();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(OLED_BLACK);
        getWindow().setNavigationBarColor(OLED_BLACK);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        camera = new NikonBulbRemote(usbManager);
        selectedMode = CaptureMode.NATIVE;
        buildUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        main.post(cameraStatusPoller);
        main.post(liveViewFramePoller);
        main.post(exposureElapsedPoller);

        UsbDevice attached = null;
        Intent launchIntent = getIntent();
        if (launchIntent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(launchIntent.getAction())) {
            attached = launchIntent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        if (camera.isCameraDevice(attached)) {
            connectOrRequestPermission(attached);
        } else {
            // The launch intent may belong to the USB-C hub rather than the camera.
            scanAndConnect();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(OLED_BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(OLED_BLACK);
        scroll.addView(root);

        buildHeader(root);
        buildTabs(root);

        capturePage = page();
        livePage = page();
        playbackPage = page();
        root.addView(capturePage, fullWrap());
        root.addView(livePage, fullWrap());
        root.addView(playbackPage, fullWrap());

        buildExposureControls(capturePage);
        buildModeControls(capturePage);
        buildTimingControls(capturePage);
        buildCaptureControls(capturePage);
        buildCameraChecklist(capturePage);
        buildLivePage(livePage);
        buildPlaybackPage(playbackPage);

        setChecklistUnknown();
        setContentView(scroll);
        showTab(TAB_CAPTURE);
        updateExposureModeControls();
        updatePlannedTimes();
        updateButtons();
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setBackgroundColor(OLED_BLACK);
        return page;
    }

    private void buildHeader(LinearLayout root) {
        TextView title = text("Remote-Intervalometer", 27);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWrap());

        status = text("Looking for camera…", 15);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = fullWrap();
        statusLp.setMargins(0, dp(8), 0, 0);
        root.addView(status, statusLp);

        headerBattery = text("Battery: --%", 13);
        headerBattery.setTextColor(GREY);
        headerBattery.setGravity(Gravity.CENTER);
        root.addView(headerBattery, fullWrap());

        Button reconnectButton = button("RECONNECT CAMERA");
        reconnectButton.setOnClickListener(v -> reconnectCamera());
        LinearLayout.LayoutParams reconnectLp = fullWrap();
        reconnectLp.setMargins(0, dp(8), 0, dp(8));
        root.addView(reconnectButton, reconnectLp);
    }

    private void buildTabs(LinearLayout root) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);

        captureTabButton = button("CAPTURE");
        liveTabButton = button("LIVE");
        playbackTabButton = button("PLAYBACK");

        captureTabButton.setOnClickListener(v -> showTab(TAB_CAPTURE));
        liveTabButton.setOnClickListener(v -> showTab(TAB_LIVE));
        playbackTabButton.setOnClickListener(v -> showTab(TAB_PLAYBACK));

        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tabLp.setMargins(dp(2), 0, dp(2), dp(8));
        tabs.addView(captureTabButton, tabLp);
        tabs.addView(liveTabButton, tabLp);
        tabs.addView(playbackTabButton, tabLp);
        root.addView(tabs, fullWrap());
    }

    private void showTab(int tab) {
        int previousTab = activeTab;
        activeTab = tab;
        if (capturePage == null) return;

        capturePage.setVisibility(tab == TAB_CAPTURE ? View.VISIBLE : View.GONE);
        livePage.setVisibility(tab == TAB_LIVE ? View.VISIBLE : View.GONE);
        playbackPage.setVisibility(tab == TAB_PLAYBACK ? View.VISIBLE : View.GONE);

        styleTab(captureTabButton, tab == TAB_CAPTURE);
        styleTab(liveTabButton, tab == TAB_LIVE);
        styleTab(playbackTabButton, tab == TAB_PLAYBACK);

        if (tab == TAB_LIVE) {
            startLiveViewPreview();
        } else {
            stopLiveViewPreview(false);
        }

        if (tab == TAB_PLAYBACK && camera != null && camera.isConnected() && !running) {
            openPlayback();
        } else if (previousTab == TAB_PLAYBACK && mirrorLockSuspendedForPlayback
                && mirrorLockUpEnabled && camera != null && camera.isConnected() && !running) {
            restoreMirrorLockAfterPlayback();
        }
    }

    private void styleTab(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? RED : GREY);
        button.setBackgroundTintList(ColorStateList.valueOf(selected ? DARK_BUTTON : OLED_BLACK));
    }

    private void buildExposureControls(LinearLayout root) {
        root.addView(label("ISO"), topMargin(8));

        isoPicker = createIsoPicker();
        root.addView(isoPicker, fullWrap());

        mirrorLockUpToggle = new Switch(this);
        mirrorLockUpToggle.setText("Mirror lock up");
        mirrorLockUpToggle.setTextSize(16);
        mirrorLockUpToggle.setTextColor(RED);
        mirrorLockUpToggle.setOnCheckedChangeListener((buttonView, checked) -> {
            if (running) return;
            setMirrorLockUpEnabled(checked);
        });
        root.addView(mirrorLockUpToggle, topMargin(8));
    }

    private NumberPicker createIsoPicker() {
        NumberPicker picker = new NumberPicker(this);
        picker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        picker.setWrapSelectorWheel(true);
        picker.setMinValue(0);
        picker.setMaxValue(0);
        picker.setDisplayedValues(new String[]{"Waiting…"});
        picker.setEnabled(false);
        picker.setOnValueChangedListener((view, oldVal, newVal) -> {
            if (suppressIsoSelectionCallback || running) return;
            if (newVal < 0 || newVal >= isoWheelValues.length) return;
            selectedIsoChoice = isoWheelValues[newVal];
            updateReadinessStatus();
            updateButtons();
            scheduleIsoApply();
        });
        picker.setOnScrollListener((view, scrollState) -> {
            isoWheelInteracting = scrollState != NumberPicker.OnScrollListener.SCROLL_STATE_IDLE;
            if (!isoWheelInteracting) scheduleIsoApply();
        });
        return picker;
    }

    private NumberPicker createShutterPicker() {
        NumberPicker picker = new NumberPicker(this);
        picker.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        picker.setWrapSelectorWheel(true);
        picker.setMinValue(0);
        picker.setMaxValue(0);
        picker.setDisplayedValues(new String[]{"Waiting…"});
        picker.setEnabled(false);
        picker.setOnValueChangedListener((view, oldVal, newVal) -> {
            if (suppressShutterSelectionCallback || running || selectedMode != CaptureMode.NATIVE) return;
            if (newVal < 0 || newVal >= shutterWheelValues.length) return;
            long requested = shutterWheelValues[newVal];
            if (NikonBulbRemote.isSpecialExposure(requested)) return;
            selectedExposureChoice = requested;
            lastNativeExposureChoice = requested;
            updateExposureModeControls();
            updatePlannedTimes();
            scheduleShutterApply();
        });
        picker.setOnScrollListener((view, scrollState) -> {
            shutterWheelInteracting = scrollState != NumberPicker.OnScrollListener.SCROLL_STATE_IDLE;
            if (!shutterWheelInteracting && selectedMode == CaptureMode.NATIVE) scheduleShutterApply();
        });
        return picker;
    }

    private void buildModeControls(LinearLayout root) {
        root.addView(label("Capture mode"), topMargin(12));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        nativeModeButton = button("Native");
        timeModeButton = button("Timed Bulb");
        bulbModeButton = button("Bulb");

        nativeModeButton.setOnClickListener(v -> selectCaptureMode(CaptureMode.NATIVE));
        timeModeButton.setOnClickListener(v -> selectCaptureMode(CaptureMode.TIMED_BULB));
        bulbModeButton.setOnClickListener(v -> selectCaptureMode(CaptureMode.BULB));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(nativeModeButton, lp);
        row.addView(timeModeButton, lp);
        row.addView(bulbModeButton, lp);
        root.addView(row, fullWrap());

        nativeShutterContainer = new LinearLayout(this);
        nativeShutterContainer.setOrientation(LinearLayout.VERTICAL);
        nativeShutterContainer.addView(label("Shutter speed"), topMargin(8));
        shutterPicker = createShutterPicker();
        nativeShutterContainer.addView(shutterPicker, fullWrap());
        root.addView(nativeShutterContainer, fullWrap());

        updateModeButtons();
    }

    private void buildTimingControls(LinearLayout root) {
        timeDurationLabel = label("Timed Bulb duration (seconds)");
        root.addView(timeDurationLabel, topMargin(12));
        exposureField = numberField("30", true);
        root.addView(exposureField, fullWrap());

        countLabel = label("Number of exposures");
        root.addView(countLabel, topMargin(12));
        countField = numberField("", false);
        countField.setHint("1");
        root.addView(countField, fullWrap());

        pauseLabel = label("Pause after each exposure (seconds)");
        root.addView(pauseLabel, topMargin(12));
        pauseField = numberField("1", true);
        root.addView(pauseField, fullWrap());

        totalTimeView = text("Total time: --:--:--", 16);
        totalTimeView.setGravity(Gravity.CENTER);
        root.addView(totalTimeView, topMargin(16));

        timeLeftView = text("Time left: --:--:--", 17);
        timeLeftView.setGravity(Gravity.CENTER);
        root.addView(timeLeftView, topMargin(4));

        TextWatcher timingWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!running) updatePlannedTimes();
            }
            @Override public void afterTextChanged(Editable value) {}
        };
        exposureField.addTextChangedListener(timingWatcher);
        pauseField.addTextChangedListener(timingWatcher);
        countField.addTextChangedListener(timingWatcher);
    }

    private void buildCaptureControls(LinearLayout root) {
        countdown = text("Not Ready", 20);
        countdown.setTextColor(RED);
        countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countLp = fullWrap();
        countLp.setMargins(0, dp(18), 0, dp(12));
        root.addView(countdown, countLp);

        startButton = button("TAKE PICTURE");
        startButton.setTextSize(22);
        startButton.setMinHeight(dp(64));
        startButton.setOnClickListener(v -> primaryCaptureAction());
        root.addView(startButton, fullWrap());
    }

    private void buildLivePage(LinearLayout root) {
        liveViewFrameStatus = text("Live View", 14);
        liveViewFrameStatus.setGravity(Gravity.CENTER);
        root.addView(liveViewFrameStatus, topMargin(8));

        FrameLayout frame = imageFrame();
        liveViewImage = new ZoomableImageView(this);
        liveViewImage.setBackgroundColor(OLED_BLACK);
        liveViewImage.setMaximumScale(6f);
        liveViewImage.setCenterCropOnReset(false);
        liveViewImage.setBaseRotationDegrees(90f);
        frame.addView(liveViewImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(frame, imageFrameParams());
        setTallRotatedViewerHeight(frame);
    }

    private void buildPlaybackPage(LinearLayout root) {
        playbackStatus = text("Camera playback", 14);
        playbackStatus.setGravity(Gravity.CENTER);
        root.addView(playbackStatus, topMargin(8));

        FrameLayout frame = imageFrame();
        playbackImage = new ZoomableImageView(this);
        playbackImage.setBackgroundColor(OLED_BLACK);
        playbackImage.setMaximumScale(8f);
        playbackImage.setCenterCropOnReset(false);
        playbackImage.setBaseRotationDegrees(90f);
        frame.addView(playbackImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(frame, imageFrameParams());
        setTallRotatedViewerHeight(frame);

        playbackNav = new LinearLayout(this);
        playbackNav.setOrientation(LinearLayout.HORIZONTAL);
        playbackNav.setGravity(Gravity.CENTER);

        previousButton = button("PREVIOUS");
        nextButton = button("NEXT");
        previousButton.setOnClickListener(v -> {
            if (playbackIndex == 0) refreshPlaybackAndShowLast();
            else showPlaybackIndex(playbackIndex - 1);
        });
        nextButton.setOnClickListener(v -> {
            if (playbackIndex == playbackHandles.length - 1) refreshPlaybackAndShowFirst();
            else showPlaybackIndex(playbackIndex + 1);
        });

        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        navLp.setMargins(dp(4), dp(8), dp(4), 0);
        playbackNav.addView(previousButton, navLp);
        playbackNav.addView(nextButton, navLp);
        root.addView(playbackNav, fullWrap());
    }

    private FrameLayout imageFrame() {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable border = new GradientDrawable();
        border.setColor(OLED_BLACK);
        border.setStroke(dp(2), Color.rgb(92, 22, 22));
        border.setCornerRadius(dp(5));
        frame.setBackground(border);
        frame.setPadding(dp(2), dp(2), dp(2), dp(2));
        return frame;
    }

    private LinearLayout.LayoutParams imageFrameParams() {
        int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
        int portraitHeight = Math.round(availableWidth * 1.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, portraitHeight);
        lp.setMargins(0, dp(8), 0, dp(8));
        return lp;
    }

    private void setTallRotatedViewerHeight(View view) {
        view.post(() -> {
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (view.getWidth() > 0) {
                // Nikon Live View is 3:2 landscape; ZoomableImageView rotates
                // it 90° at draw time inside the corresponding 2:3 frame.
                lp.height = Math.round(view.getWidth() * 1.5f);
                view.setLayoutParams(lp);
            }
        });
    }

    private void buildCameraChecklist(LinearLayout root) {
        LinearLayout checklist = new LinearLayout(this);
        checklist.setOrientation(LinearLayout.VERTICAL);
        checklist.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(checklist, topMargin(22));

        TextView checkTitle = text("CAMERA CHECK", 14);
        checklist.addView(checkTitle, fullWrap());

        cameraModeCheck = checklistItem("Camera Mode: Manual");
        checklist.addView(cameraModeCheck, topMargin(8));
        focusCheck = checklistItem("Autofocus: MF");
        checklist.addView(focusCheck, topMargin(6));
    }

    private void reconnectCamera() {
        if (running) {
            Toast.makeText(this, "Stop the current capture first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (pendingShutterApply != null) main.removeCallbacks(pendingShutterApply);
        if (pendingIsoApply != null) main.removeCallbacks(pendingIsoApply);

        cameraSettingInFlight = false;
        statusCheckInFlight = false;
        shutterWheelInteracting = false;
        isoWheelInteracting = false;

        try {
            camera.disconnect();
        } catch (Exception ignored) {}

        setChecklistUnknown();
        setStatus("Reconnecting…");
        main.postDelayed(this::scanAndConnect, 150);
    }

    private void scanAndConnect() {
        UsbDevice device = camera.findCamera();
        if (device == null) {
            setStatus("No Nikon/PTP camera detected");
            setChecklistUnknown();
            updateButtons();
            return;
        }

        connectOrRequestPermission(device);
    }

    private void connectOrRequestPermission(UsbDevice device) {
        if (device == null || camera.isConnected()) return;

        if (usbManager.hasPermission(device)) {
            connectTo(device);
        } else {
            setStatus("Camera found — requesting USB permission…");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    flags);
            usbManager.requestPermission(device, pi);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (camera.isCameraDevice(device)) connectOrRequestPermission(device);
            else main.postDelayed(this::scanAndConnect, 250);
        }
    }

    private void connectTo(UsbDevice device) {
        setStatus("Connecting…");
        io.execute(() -> {
            try {
                camera.connect(device);
                main.post(() -> {
                    setStatus("Connected: " + camera.getDeviceName());
                    updateButtons();
                    refreshCameraChecklist();
                });
            } catch (Exception e) {
                main.post(() -> {
                    setStatus("Connection failed: " + e.getMessage());
                    setChecklistUnknown();
                    updateButtons();
                });
            }
        });
    }

    private void refreshCameraChecklist() {
        if (!camera.isConnected() || running || statusCheckInFlight) return;
        statusCheckInFlight = true;

        io.execute(() -> {
            try {
                NikonBulbRemote.CameraSetup setup = camera.readCameraSetup();
                boolean liveViewActive = false;
                boolean liveViewReadable = false;
                try {
                    liveViewActive = camera.isLiveViewActive();
                    liveViewReadable = true;
                } catch (Exception ignored) {
                    // Some bodies may not expose the Live View status property.
                }
                boolean finalLiveViewActive = liveViewActive;
                boolean finalLiveViewReadable = liveViewReadable;
                main.post(() -> {
                    applyChecklist(setup);
                    if (activeTab == TAB_LIVE && finalLiveViewReadable && finalLiveViewActive) {
                        liveViewPreviewRequested = true;
                    }
                });
            } catch (Exception ignored) {
                // A transient busy state should not turn a previously valid checklist into an error.
            } finally {
                statusCheckInFlight = false;
            }
        });
    }

    private void applyChecklist(NikonBulbRemote.CameraSetup setup) {
        manualModeReady = setup.manualMode;
        focusReady = setup.manualFocus;
        exposureTimeWritable = setup.exposureTimeWritable;
        cameraExposureTime = setup.exposureTime;
        isoWritable = setup.isoWritable;
        cameraIso = setup.iso;

        if (selectedExposureChoice == 0) {
            selectedMode = CaptureMode.NATIVE;

            if (!NikonBulbRemote.isSpecialExposure(setup.exposureTime)) {
                selectedExposureChoice = setup.exposureTime;
                lastNativeExposureChoice = setup.exposureTime;
            } else {
                long nativeDefault = firstNativeExposure(setup.supportedExposureTimes);
                if (nativeDefault != 0) {
                    selectedExposureChoice = nativeDefault;
                    lastNativeExposureChoice = nativeDefault;
                }
            }
            updateModeButtons();
        }
        if (selectedIsoChoice < 0) selectedIsoChoice = setup.iso;

        setCheck(cameraModeCheck, "Camera Mode: Manual", manualModeReady);
        setCheck(focusCheck, "Autofocus: MF", focusReady);
        if (headerBattery != null) {
            headerBattery.setText("Battery: " + setup.batteryLevel + "%");
            headerBattery.setTextColor(setup.batteryLevel <= 20 ? RED : GREY);
        }

        populateShutterWheel(setup);
        populateIsoWheel(setup);

        if (selectedMode == CaptureMode.NATIVE
                && selectedExposureChoice != 0
                && selectedExposureChoice != setup.exposureTime
                && setup.exposureTimeWritable) {
            scheduleShutterApply();
        }

        boolean shutterReady = selectedExposureChoice == setup.exposureTime;
        boolean isoReady = selectedIsoChoice == setup.iso;
        cameraSetupReady = manualModeReady && shutterReady && isoReady && focusReady;
        updateExposureModeControls();
        updatePlannedTimes();
        updateReadinessStatus();
        updateButtons();
    }

    private void populateShutterWheel(NikonBulbRemote.CameraSetup setup) {
        if (shutterPicker == null) return;

        int nativeCount = 0;
        for (long value : setup.supportedExposureTimes) {
            if (!NikonBulbRemote.isSpecialExposure(value)) nativeCount++;
        }
        if (nativeCount == 0) return;

        long[] values = new long[nativeCount];
        int out = 0;
        for (long value : setup.supportedExposureTimes) {
            if (!NikonBulbRemote.isSpecialExposure(value)) values[out++] = value;
        }

        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = NikonBulbRemote.formatExposureTime(values[i]);
        }

        boolean optionsChanged = !java.util.Arrays.equals(shutterWheelValues, values);
        if (optionsChanged && !shutterWheelInteracting) {
            shutterWheelValues = values;
            suppressShutterSelectionCallback = true;
            shutterPicker.setDisplayedValues(null);
            shutterPicker.setMinValue(0);
            shutterPicker.setMaxValue(values.length - 1);
            shutterPicker.setDisplayedValues(labels);
            shutterPicker.setWrapSelectorWheel(values.length > 1);
            suppressShutterSelectionCallback = false;
        }

        long displayed = lastNativeExposureChoice;
        if (displayed == 0 || indexOfShutterValue(displayed) < 0) {
            displayed = !NikonBulbRemote.isSpecialExposure(setup.exposureTime)
                    ? setup.exposureTime
                    : values[0];
        }
        lastNativeExposureChoice = displayed;

        int desiredIndex = indexOfShutterValue(displayed);
        if (desiredIndex < 0) desiredIndex = 0;

        if (selectedMode == CaptureMode.NATIVE) {
            selectedExposureChoice = shutterWheelValues[desiredIndex];
        }

        if (!shutterWheelInteracting) {
            suppressShutterSelectionCallback = true;
            shutterPicker.setValue(desiredIndex);
            suppressShutterSelectionCallback = false;
        }
    }

    private int indexOfShutterValue(long value) {
        for (int i = 0; i < shutterWheelValues.length; i++) {
            if (shutterWheelValues[i] == value) return i;
        }
        return -1;
    }

    private void scheduleShutterApply() {
        if (running || shutterWheelInteracting) return;
        if (pendingShutterApply != null) main.removeCallbacks(pendingShutterApply);
        pendingShutterApply = () -> {
            long requested = selectedExposureChoice;
            if (requested != cameraExposureTime) setShutterFromUi(requested);
        };
        main.postDelayed(pendingShutterApply, 350);
    }

    private void setShutterFromUi(long requested) {
        if (!camera.isConnected() || cameraSettingInFlight) return;

        if (!exposureTimeWritable) {
            updateReadinessStatus();
            updateButtons();
            return;
        }

        cameraSettingInFlight = true;
        setStatus("Setting shutter to " + NikonBulbRemote.formatExposureTime(requested) + "…");
        io.execute(() -> {
            try {
                camera.setExposureTime(requested);
                NikonBulbRemote.CameraSetup setup = camera.readCameraSetup();
                main.post(() -> {
                    cameraSettingInFlight = false;
                    selectedExposureChoice = setup.exposureTime;
                    if (!NikonBulbRemote.isSpecialExposure(setup.exposureTime)) {
                        lastNativeExposureChoice = setup.exposureTime;
                    }
                    applyChecklist(setup);
                    setStatus("Shutter set to "
                            + NikonBulbRemote.formatExposureTime(setup.exposureTime));
                });
            } catch (Exception e) {
                main.post(() -> {
                    cameraSettingInFlight = false;
                    setStatus("Shutter setting error: " + e.getMessage());
                    refreshCameraChecklist();
                });
            }
        });
    }

    private void populateIsoWheel(NikonBulbRemote.CameraSetup setup) {
        if (isoPicker == null || isoWheelInteracting) return;

        int[] values = setup.supportedIsoValues;
        if (values.length == 0) values = new int[]{setup.iso};

        boolean optionsChanged = !java.util.Arrays.equals(isoWheelValues, values);
        if (optionsChanged) {
            isoWheelValues = java.util.Arrays.copyOf(values, values.length);
            String[] labels = new String[values.length];
            for (int i = 0; i < values.length; i++) labels[i] = NikonBulbRemote.formatIso(values[i]);

            suppressIsoSelectionCallback = true;
            isoPicker.setDisplayedValues(null);
            isoPicker.setMinValue(0);
            isoPicker.setMaxValue(values.length - 1);
            isoPicker.setDisplayedValues(labels);
            isoPicker.setWrapSelectorWheel(values.length > 1);
            suppressIsoSelectionCallback = false;
        }

        int desiredIndex = indexOfIsoValue(selectedIsoChoice);
        if (desiredIndex < 0) {
            selectedIsoChoice = setup.iso;
            desiredIndex = indexOfIsoValue(selectedIsoChoice);
        }
        if (desiredIndex < 0) desiredIndex = 0;

        if (optionsChanged) {
            suppressIsoSelectionCallback = true;
            isoPicker.setValue(desiredIndex);
            suppressIsoSelectionCallback = false;
        }
    }

    private int indexOfIsoValue(int value) {
        for (int i = 0; i < isoWheelValues.length; i++) {
            if (isoWheelValues[i] == value) return i;
        }
        return -1;
    }

    private void scheduleIsoApply() {
        if (running || isoWheelInteracting) return;
        if (pendingIsoApply != null) main.removeCallbacks(pendingIsoApply);
        pendingIsoApply = () -> {
            int requested = selectedIsoChoice;
            if (requested != cameraIso) setIsoFromUi(requested);
        };
        main.postDelayed(pendingIsoApply, 350);
    }

    private void setIsoFromUi(int requested) {
        if (!camera.isConnected() || cameraSettingInFlight) return;

        if (!isoWritable) {
            updateReadinessStatus();
            updateButtons();
            return;
        }

        cameraSettingInFlight = true;
        setStatus("Setting " + NikonBulbRemote.formatIso(requested) + "…");
        io.execute(() -> {
            try {
                camera.setIso(requested);
                NikonBulbRemote.CameraSetup setup = camera.readCameraSetup();
                main.post(() -> {
                    cameraSettingInFlight = false;
                    selectedIsoChoice = setup.iso;
                    applyChecklist(setup);
                    setStatus(NikonBulbRemote.formatIso(setup.iso) + " set");
                });
            } catch (Exception e) {
                main.post(() -> {
                    cameraSettingInFlight = false;
                    setStatus("ISO setting error: " + e.getMessage());
                    refreshCameraChecklist();
                });
            }
        });
    }

    private CaptureMode selectedCaptureMode() {
        return selectedMode;
    }

    private boolean isNativeMode() {
        return selectedMode == CaptureMode.NATIVE;
    }

    private void selectCaptureMode(CaptureMode mode) {
        if (running || manualExposureOpen) return;
        selectedMode = mode;

        if (mode == CaptureMode.NATIVE) {
            long candidate = lastNativeExposureChoice;
            if (candidate == 0 || NikonBulbRemote.isSpecialExposure(candidate)) {
                candidate = firstNativeExposure();
            }
            if (candidate != 0) {
                selectedExposureChoice = candidate;
                int index = indexOfShutterValue(candidate);
                if (index >= 0 && shutterPicker != null) {
                    suppressShutterSelectionCallback = true;
                    shutterPicker.setValue(index);
                    suppressShutterSelectionCallback = false;
                }
                scheduleShutterApply();
            }
        } else {
            selectedExposureChoice = NikonBulbRemote.bulbExposureTime();
            scheduleShutterApply();
        }

        updateModeButtons();
        updateExposureModeControls();
        updatePlannedTimes();
        updateReadinessStatus();
        updateButtons();
    }

    private long firstNativeExposure() {
        return firstNativeExposure(shutterWheelValues);
    }

    private long firstNativeExposure(long[] values) {
        if (values == null) return 0;
        for (long value : values) {
            if (!NikonBulbRemote.isSpecialExposure(value)) return value;
        }
        return 0;
    }

    private void updateModeButtons() {
        styleModeButton(nativeModeButton, selectedMode == CaptureMode.NATIVE);
        styleModeButton(timeModeButton, selectedMode == CaptureMode.TIMED_BULB);
        styleModeButton(bulbModeButton, selectedMode == CaptureMode.BULB);
    }

    private void styleModeButton(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? RED : GREY);
        button.setBackgroundTintList(ColorStateList.valueOf(selected ? DARK_BUTTON : OLED_BLACK));
    }

    private void updateExposureModeControls() {
        if (exposureField == null || shutterPicker == null) return;

        boolean timeMode = selectedMode == CaptureMode.TIMED_BULB;
        boolean nativeMode = selectedMode == CaptureMode.NATIVE;
        boolean manualBulb = selectedMode == CaptureMode.BULB;

        exposureField.setVisibility(timeMode ? View.VISIBLE : View.GONE);
        if (timeDurationLabel != null) {
            timeDurationLabel.setVisibility(timeMode ? View.VISIBLE : View.GONE);
        }
        exposureField.setEnabled(!running && timeMode);

        if (nativeShutterContainer != null) {
            nativeShutterContainer.setVisibility(nativeMode ? View.VISIBLE : View.GONE);
        }
        shutterPicker.setEnabled(nativeMode && !running
                && camera != null && camera.isConnected());

        if (mirrorLockUpToggle != null) {
            mirrorLockUpToggle.setVisibility(View.VISIBLE);
            mirrorLockUpToggle.setEnabled(!running
                    && camera != null && camera.isConnected());
        }

        if (isoPicker != null) {
            isoPicker.setEnabled(!running && camera != null && camera.isConnected());
        }

        int timingVisibility = manualBulb ? View.GONE : View.VISIBLE;
        if (countLabel != null) countLabel.setVisibility(timingVisibility);
        if (countField != null) {
            countField.setVisibility(timingVisibility);
            countField.setEnabled(!running);
        }
        if (pauseLabel != null) pauseLabel.setVisibility(timingVisibility);
        if (pauseField != null) {
            pauseField.setVisibility(timingVisibility);
            pauseField.setEnabled(!running);
        }
    }

    private void setChecklistUnknown() {
        cameraSetupReady = false;
        manualExposureOpen = false;
        manualModeReady = false;
        focusReady = false;
        exposureTimeWritable = false;
        selectedExposureChoice = 0;
        cameraExposureTime = 0;
        shutterWheelValues = new long[0];
        selectedIsoChoice = -1;
        cameraIso = -1;
        isoWritable = false;
        isoWheelValues = new int[0];
        if (cameraModeCheck == null) return;
        setCheck(cameraModeCheck, "Camera Mode: Manual", false);
        setCheck(focusCheck, "Autofocus: MF", false);
        liveViewPreviewRequested = false;
        mirrorLockUpEnabled = false;
        if (mirrorLockUpToggle != null && mirrorLockUpToggle.isChecked()) {
            mirrorLockUpToggle.setChecked(false);
        }
        if (headerBattery != null) {
            headerBattery.setText("Battery: --%");
            headerBattery.setTextColor(GREY);
        }
        updateReadinessStatus();
        updateButtons();
    }

    private void setCheck(TextView view, String label, boolean good) {
        view.setText((good ? "✓  " : "✕  ") + label);
        view.setTextColor(good ? RED : GREY);
    }

    private void setMirrorLockUpEnabled(boolean enabled) {
        if (mirrorLockUpToggle == null) return;

        if (!enabled) {
            mirrorLockUpEnabled = false;
            mirrorLockSuspendedForPlayback = false;
            if (mirrorLockUpToggle.isChecked()) {
                mirrorLockUpToggle.setChecked(false);
            }

            if (camera != null && camera.isConnected() && activeTab != TAB_LIVE && !running) {
                io.execute(() -> {
                    try {
                        if (camera.isLiveViewActive()) camera.stopLiveView();
                        main.post(() -> setStatus("Mirror lock up off"));
                    } catch (Exception e) {
                        main.post(() -> setStatus("Live View stop error: " + e.getMessage()));
                    }
                });
            }
            return;
        }

        if (camera == null || !camera.isConnected()) {
            mirrorLockUpEnabled = false;
            if (mirrorLockUpToggle.isChecked()) mirrorLockUpToggle.setChecked(false);
            Toast.makeText(this, "Connect the camera first", Toast.LENGTH_SHORT).show();
            return;
        }

        mirrorLockUpEnabled = true;
        setStatus("Starting mirror lock up…");
        io.execute(() -> {
            try {
                camera.startLiveView();
                main.post(() -> {
                    mirrorLockUpEnabled = true;
                    if (!mirrorLockUpToggle.isChecked()) mirrorLockUpToggle.setChecked(true);
                    setStatus("Mirror lock up on");
                });
            } catch (Exception e) {
                main.post(() -> {
                    mirrorLockUpEnabled = false;
                    if (mirrorLockUpToggle.isChecked()) mirrorLockUpToggle.setChecked(false);
                    setStatus("Mirror lock up error: " + e.getMessage());
                });
            }
        });
    }

    private void startLiveViewPreview() {
        if (liveViewImage == null || liveViewFrameStatus == null) return;
        if (camera == null || !camera.isConnected()) {
            liveViewPreviewRequested = false;
            liveViewFrameStatus.setText("Connect camera for Live View");
            return;
        }
        if (running) {
            liveViewFrameStatus.setText("Live View paused during capture");
            return;
        }

        liveViewFrameStatus.setText("Starting Live View…");
        io.execute(() -> {
            try {
                camera.startLiveView();
                liveViewPreviewRequested = true;
                main.post(() -> {
                    liveViewFrameStatus.setText("Live View");
                    setStatus("Live View on");
                });
            } catch (Exception e) {
                liveViewPreviewRequested = false;
                main.post(() -> {
                    liveViewFrameStatus.setText("Live View error: " + e.getMessage());
                    setStatus("Live View error: " + e.getMessage());
                });
            }
        });
    }

    private void stopLiveViewPreview(boolean forceCameraStop) {
        liveViewPreviewRequested = false;

        boolean preserveForMirrorLock = mirrorLockUpEnabled;
        if ((forceCameraStop || activeTab != TAB_LIVE) && !preserveForMirrorLock) {
            if (camera != null && camera.isConnected() && !running) {
                io.execute(() -> {
                    try {
                        if (camera.isLiveViewActive()) camera.stopLiveView();
                    } catch (Exception ignored) {}
                });
            }
        }
    }

    private void restoreMirrorLockAfterPlayback() {
        if (!mirrorLockUpEnabled || !mirrorLockSuspendedForPlayback
                || camera == null || !camera.isConnected() || running) {
            mirrorLockSuspendedForPlayback = false;
            return;
        }

        setStatus("Restoring mirror lock up…");
        io.execute(() -> {
            try {
                camera.startLiveView();
                mirrorLockSuspendedForPlayback = false;
                main.post(() -> setStatus("Mirror lock up on"));
            } catch (Exception e) {
                main.post(() -> setStatus(
                        "Mirror lock up restore error: " + e.getMessage()));
            }
        });
    }

    private void openPlayback() {
        if (playbackStatus == null || playbackImage == null) return;
        if (!camera.isConnected()) {
            playbackStatus.setText("Connect camera for playback");
            return;
        }
        if (running) return;

        playbackStatus.setText("Loading photos…");
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);

        io.execute(() -> {
            try {
                mirrorLockSuspendedForPlayback = mirrorLockUpEnabled;
                try {
                    if (camera.isLiveViewActive()) camera.stopLiveView();
                } catch (Exception ignored) {}

                int[] handles = buildPlayableHandleList();
                if (handles.length == 0) {
                    main.post(() -> playbackStatus.setText("No playable images found"));
                    return;
                }
                playbackHandles = handles;
                playbackIndex = handles.length - 1;
                loadPlaybackImage(playbackIndex, true);
            } catch (Exception e) {
                showPlaybackError(e);
            }
        });
    }

    private void showPlaybackIndex(int index) {
        if (running || index < 0 || index >= playbackHandles.length) return;
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
        io.execute(() -> loadPlaybackImage(index, true));
    }

    private void refreshPlaybackAndShowFirst() {
        if (running) return;
        io.execute(() -> {
            try {
                int[] handles = buildPlayableHandleList();
                if (handles.length == 0) throw new Exception("No playable images found");
                playbackHandles = handles;
                loadPlaybackImage(0, true);
            } catch (Exception e) {
                showPlaybackError(e);
            }
        });
    }

    private void refreshPlaybackAndShowLast() {
        if (running) return;
        io.execute(() -> {
            try {
                int[] handles = buildPlayableHandleList();
                if (handles.length == 0) throw new Exception("No playable images found");
                playbackHandles = handles;
                loadPlaybackImage(handles.length - 1, true);
            } catch (Exception e) {
                showPlaybackError(e);
            }
        });
    }

    private int[] buildPlayableHandleList() throws Exception {
        int[] allHandles = camera.getImageHandles();
        if (allHandles.length == 0) return allHandles;

        int[] validHandles = new int[allHandles.length];
        int validCount = 0;
        for (int handle : allHandles) {
            try {
                byte[] jpeg = camera.getThumbnail(handle);
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bitmap != null) validHandles[validCount++] = handle;
            } catch (Exception ignored) {}
        }

        int[] result = new int[validCount];
        System.arraycopy(validHandles, 0, result, 0, validCount);
        return result;
    }

    private void loadPlaybackImage(int index, boolean retryOnStaleHandle) {
        try {
            int handle = playbackHandles[index];

            byte[] thumb = camera.getThumbnail(handle);
            Bitmap preview = BitmapFactory.decodeByteArray(thumb, 0, thumb.length);
            if (preview == null) throw new Exception("Camera thumbnail could not be decoded");

            main.post(() -> {
                playbackIndex = index;
                playbackImage.setImageBitmap(preview);
                playbackImage.fitAfterNextLayout();
                playbackStatus.setText("Photo " + (index + 1) + " / " + playbackHandles.length
                        + " — loading full image…");
            });

            try {
                byte[] full = camera.getObject(handle);
                Bitmap bitmap = decodePlaybackBitmap(full);
                if (bitmap != null) {
                    main.post(() -> {
                        playbackImage.setImageBitmap(bitmap);
                        playbackImage.fitAfterNextLayout();
                        playbackStatus.setText("Photo " + (index + 1) + " / " + playbackHandles.length
                                + " — " + bitmap.getWidth() + " × " + bitmap.getHeight()
                                + " — pinch to zoom");
                    });
                } else {
                    main.post(() -> playbackStatus.setText("Photo " + (index + 1) + " / "
                            + playbackHandles.length + " — thumbnail preview"));
                }
            } catch (Exception ignored) {
                main.post(() -> playbackStatus.setText("Photo " + (index + 1) + " / "
                        + playbackHandles.length + " — thumbnail preview"));
            }

            main.post(() -> {
                previousButton.setEnabled(playbackHandles.length > 0);
                nextButton.setEnabled(playbackHandles.length > 0);
            });
        } catch (Exception e) {
            String message = e.getMessage();
            boolean staleHandle = retryOnStaleHandle
                    && message != null
                    && message.toLowerCase(Locale.US).contains("0x2009");
            if (staleHandle) {
                try {
                    int[] handles = buildPlayableHandleList();
                    if (handles.length == 0) throw new Exception("No playable images found");
                    playbackHandles = handles;
                    loadPlaybackImage(Math.min(index, handles.length - 1), false);
                    return;
                } catch (Exception retryError) {
                    showPlaybackError(retryError);
                    return;
                }
            }
            showPlaybackError(e);
        }
    }

    private Bitmap decodePlaybackBitmap(byte[] data) {
        if (data == null || data.length == 0) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private void showPlaybackError(Exception e) {
        String message = e.getMessage();
        main.post(() -> {
            playbackStatus.setText("Playback error: " + message);
            previousButton.setEnabled(playbackHandles.length > 0 && playbackIndex >= 0);
            nextButton.setEnabled(playbackHandles.length > 0 && playbackIndex >= 0);
        });
    }

    private void primaryCaptureAction() {
        if (manualExposureOpen) {
            closeManualBulbExposure();
            return;
        }
        if (running) {
            stopCurrentOperation();
            return;
        }
        startSequence();
    }

    private void startSequence() {
        if (!camera.isConnected()) {
            Toast.makeText(this, "Connect the camera first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isReadyToStart()) {
            updateReadinessStatus();
            Toast.makeText(this, countdown.getText(), Toast.LENGTH_SHORT).show();
            return;
        }

        final CaptureMode mode = selectedMode;
        if (mode == CaptureMode.BULB) {
            openManualBulbExposure(mirrorLockUpEnabled);
            return;
        }

        final double exposureSeconds;
        final double pauseSeconds;
        final int shots;
        try {
            String countText = countField.getText().toString().trim();
            shots = countText.isEmpty() ? 1 : Math.max(1, Integer.parseInt(countText));
            pauseSeconds = Math.max(0.0, Double.parseDouble(pauseField.getText().toString().trim()));

            if (mode == CaptureMode.NATIVE) {
                exposureSeconds = NikonBulbRemote.exposureTimeSeconds(selectedExposureChoice);
                if (!Double.isFinite(exposureSeconds) || exposureSeconds <= 0.0) {
                    throw new IllegalArgumentException("No native shutter speed selected");
                }
            } else {
                exposureSeconds = Math.max(
                        0.2,
                        Double.parseDouble(exposureField.getText().toString().trim()));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Check all timing values", Toast.LENGTH_LONG).show();
            return;
        }

        running = true;
        cancelRequested = false;
        exposureActive = false;
        completed = 0;
        countdown.setTextColor(RED);
        updateButtons();

        long totalMs = SequenceTiming.plannedTotalMs(exposureSeconds, pauseSeconds, shots);
        totalTimeView.setText("Total time: " + SequenceTiming.formatDuration(totalMs));
        timeLeftView.setText("Time left: " + SequenceTiming.formatDuration(totalMs));

        io.execute(() -> runSequence(exposureSeconds, pauseSeconds, shots, mode));
    }

    private void openManualBulbExposure(boolean mirrorUp) {
        if (!camera.isConnected()) return;

        running = true;
        cancelRequested = false;
        exposureActive = false;
        countdown.setTextColor(RED);
        countdown.setText(mirrorUp ? "Raising mirror…" : "Opening Bulb exposure…");
        setStatus(mirrorUp ? "Starting mirror-up Bulb" : "Starting Bulb exposure");
        updateButtons();

        io.execute(() -> {
            try {
                if (mirrorUp) {
                    if (!camera.isLiveViewActive()) camera.startLiveView();
                mirrorLockSuspendedForPlayback = false;
                } else {
                    try {
                        if (camera.isLiveViewActive()) camera.stopLiveView();
                    } catch (Exception ignored) {}
                }

                camera.startCaptureNoAf();
                manualExposureStartedAt = SystemClock.elapsedRealtime();
                manualExposureOpen = true;
                exposureActive = true;
                main.post(() -> {
                    countdown.setText("Bulb exposure — 00:00:00");
                    totalTimeView.setText("Total time: manual");
                    timeLeftView.setText("Elapsed: 00:00:00");
                    setStatus(mirrorUp
                            ? "Bulb mirror lock up — press STOP EXPOSURE"
                            : "Bulb open — press STOP EXPOSURE");
                    updateButtons();
                });
            } catch (Exception e) {
                running = false;
                manualExposureOpen = false;
                exposureActive = false;
                manualExposureStartedAt = 0L;
                main.post(() -> {
                    countdown.setText("Ready");
                    setStatus("Bulb error: " + e.getMessage());
                    updateButtons();
                    refreshCameraChecklist();
                });
            }
        });
    }

    private void closeManualBulbExposure() {
        if (!manualExposureOpen && !camera.isCaptureOpen()) return;

        countdown.setText("Stopping exposure…");
        setStatus("Closing shutter");
        updateButtons();

        io.execute(() -> {
            try {
                camera.stopCapture();
                exposureActive = false;
                manualExposureOpen = false;
                running = false;
                manualExposureStartedAt = 0L;
                completed = 1;

                if (!mirrorLockUpEnabled) {
                    try {
                        if (camera.isLiveViewActive() && activeTab != TAB_LIVE) camera.stopLiveView();
                    } catch (Exception ignored) {}
                }

                main.post(() -> {
                    countdown.setText("Finished — 1 exposure");
                    timeLeftView.setText("Elapsed: complete");
                    setStatus("Exposure saved");
                    updateButtons();
                    refreshCameraChecklist();
                });
            } catch (Exception e) {
                main.post(() -> {
                    manualExposureOpen = camera.isCaptureOpen();
                    running = manualExposureOpen;
                    exposureActive = manualExposureOpen;
                    setStatus("Stop exposure error: " + e.getMessage());
                    updateButtons();
                });
            }
        });
    }

    private void runSequence(
            double exposureSeconds,
            double pauseSeconds,
            int shots,
            CaptureMode mode) {
        try {
            if (mirrorLockUpEnabled) {
                // Global mirror lock-up: use hidden Nikon Live View as the
                // raised-mirror state for Native, Time, and Bulb capture.
                if (!camera.isLiveViewActive()) camera.startLiveView();
                mirrorLockSuspendedForPlayback = false;
            } else {
                try {
                    if (camera.isLiveViewActive() && activeTab != TAB_LIVE) camera.stopLiveView();
                } catch (Exception ignored) {}
            }

            for (int shot = 1; shot <= shots && !cancelRequested; shot++) {
                int shotNo = shot;
                boolean fullExposure;

                if (mode == CaptureMode.NATIVE) {
                    exposureActive = true;
                    main.post(() -> {
                        countdown.setText(String.format(
                                Locale.US,
                                "Exposure %d / %d — %s",
                                shotNo,
                                shots,
                                NikonBulbRemote.formatExposureTime(selectedExposureChoice)));
                        setStatus("Capturing — camera timed");
                        updateButtons();
                    });
                    camera.captureNativeNoAf();
                    exposureActive = false;
                    fullExposure = !cancelRequested;
                } else {
                    camera.logExposureDiagnostic("time-" + shotNo + "-before-start");
                    camera.startCaptureNoAf();
                    camera.logExposureDiagnostic("time-" + shotNo + "-after-start-accepted");
                    exposureActive = true;
                    main.post(() -> {
                        setStatus("Capturing — Timed Bulb");
                        updateButtons();
                    });
                    fullExposure = exposureCountdown(
                            shotNo, shots, exposureSeconds, pauseSeconds);
                    camera.logExposureDiagnostic("time-" + shotNo + "-before-stop");
                    main.post(() -> setStatus("Closing shutter"));
                    camera.stopCapture();
                    camera.logExposureDiagnostic("time-" + shotNo + "-after-stop");
                    exposureActive = false;
                }

                if (!fullExposure || cancelRequested) break;

                completed = shot;
                main.post(() -> {
                    setStatus("Exposure saved");
                    updateButtons();
                });

                if (shot < shots && pauseSeconds > 0) {
                    if (!pauseCountdown(shotNo, shots, exposureSeconds, pauseSeconds)) break;
                }
            }

            if (!cancelRequested && completed == shots) {
                main.post(() -> {
                    countdown.setText("Finished — " + completed + " exposures");
                    timeLeftView.setText("Time left: 00:00:00");
                    setStatus("Ready");
                });
            } else {
                main.post(() -> {
                    countdown.setText("Stopped after " + completed + " completed exposures");
                    setStatus("Ready");
                });
            }
        } catch (Exception e) {
            try { camera.stopCapture(); } catch (Exception ignored) {}
            exposureActive = false;
            main.post(() -> {
                countdown.setText("Stopped");
                setStatus("Camera error: " + e.getMessage());
            });
        } finally {
            running = false;
            exposureActive = false;
            cancelRequested = false;
            main.post(() -> {
                updateButtons();
                refreshCameraChecklist();
            });
        }
    }

    private boolean exposureCountdown(int shot, int total, double exposureSeconds, double pauseSeconds) {
        long exposureMs = (long)(exposureSeconds * 1000.0);
        long pauseMs = (long)(pauseSeconds * 1000.0);
        long end = SystemClock.elapsedRealtime() + exposureMs;

        while (!cancelRequested) {
            long remaining = end - SystemClock.elapsedRealtime();
            if (remaining <= 0) return true;

            long futureMs = (long)(total - shot) * exposureMs
                    + (long)(total - shot) * pauseMs;
            long sequenceRemaining = remaining + futureMs;
            double remainingSec = remaining / 1000.0;

            main.post(() -> {
                countdown.setText(String.format(
                        Locale.US, "Exposure %d / %d — %.1f s", shot, total, remainingSec));
                timeLeftView.setText("Time left: " + SequenceTiming.formatDuration(sequenceRemaining));
            });

            try {
                Thread.sleep(Math.min(100, Math.max(1, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean pauseCountdown(int shot, int total, double exposureSeconds, double pauseSeconds) {
        long exposureMs = (long)(exposureSeconds * 1000.0);
        long pauseMs = (long)(pauseSeconds * 1000.0);
        long end = SystemClock.elapsedRealtime() + pauseMs;

        while (!cancelRequested) {
            long remaining = end - SystemClock.elapsedRealtime();
            if (remaining <= 0) return true;

            long futureExposureMs = (long)(total - shot) * exposureMs;
            long futurePauseMs = (long)Math.max(0, total - shot - 1) * pauseMs;
            long sequenceRemaining = remaining + futureExposureMs + futurePauseMs;
            double remainingSec = remaining / 1000.0;

            main.post(() -> {
                countdown.setText(String.format(
                        Locale.US, "Next exposure in %.1f s", remainingSec));
                timeLeftView.setText("Time left: " + SequenceTiming.formatDuration(sequenceRemaining));
            });

            try {
                Thread.sleep(Math.min(100, Math.max(1, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void updatePlannedTimes() {
        if (totalTimeView == null || timeLeftView == null) return;

        if (selectedMode == CaptureMode.BULB) {
            totalTimeView.setText("Total time: manual");
            timeLeftView.setText("Elapsed: --:--:--");
            updateReadinessStatus();
            updateButtons();
            return;
        }

        try {
            double exposureSeconds;
            if (selectedMode == CaptureMode.NATIVE) {
                exposureSeconds = NikonBulbRemote.exposureTimeSeconds(selectedExposureChoice);
                if (!Double.isFinite(exposureSeconds) || exposureSeconds <= 0.0) {
                    throw new IllegalArgumentException("No native shutter speed");
                }
            } else {
                exposureSeconds = Math.max(0.2,
                        Double.parseDouble(exposureField.getText().toString().trim()));
            }

            double pauseSeconds = Math.max(0.0,
                    Double.parseDouble(pauseField.getText().toString().trim()));
            String countText = countField.getText().toString().trim();
            int shots = countText.isEmpty() ? 1 : Math.max(1, Integer.parseInt(countText));

            long totalMs = SequenceTiming.plannedTotalMs(exposureSeconds, pauseSeconds, shots);
            totalTimeView.setText("Total time: " + SequenceTiming.formatDuration(totalMs));
            timeLeftView.setText("Time left: " + SequenceTiming.formatDuration(totalMs));
        } catch (Exception ignored) {
            totalTimeView.setText("Total time: --:--:--");
            timeLeftView.setText("Time left: --:--:--");
        }
        updateReadinessStatus();
        updateButtons();
    }

    private void stopCurrentOperation() {
        if (manualExposureOpen) {
            closeManualBulbExposure();
            return;
        }
        if (!running) return;

        cancelRequested = true;
        if (selectedMode == CaptureMode.TIMED_BULB && exposureActive) {
            countdown.setText("Stopping exposure…");
            setStatus("STOP EXPOSURE requested");
        } else {
            countdown.setText("Stopping sequence…");
            setStatus("STOP SEQUENCE requested");
        }
        updateButtons();
    }

    private boolean hasValidInputs() {
        try {
            if (selectedMode == CaptureMode.BULB) {
                return true;
            }

            String countText = countField.getText().toString().trim();
            String pauseText = pauseField.getText().toString().trim();
            if (pauseText.isEmpty()) return false;

            if (selectedMode == CaptureMode.NATIVE) {
                double exposure = NikonBulbRemote.exposureTimeSeconds(selectedExposureChoice);
                if (!Double.isFinite(exposure) || exposure <= 0.0) return false;
            } else {
                String exposureText = exposureField.getText().toString().trim();
                if (exposureText.isEmpty()) return false;
                double exposure = Double.parseDouble(exposureText);
                if (exposure <= 0.0) return false;
            }

            int shots = countText.isEmpty() ? 1 : Integer.parseInt(countText);
            double pause = Double.parseDouble(pauseText);
            return shots >= 1 && pause >= 0.0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isReadyToStart() {
        return camera != null && camera.isConnected() && cameraSetupReady && hasValidInputs();
    }

    private void updateReadinessStatus() {
        if ((running && !manualExposureOpen) || countdown == null || exposureField == null
                || countField == null || pauseField == null) return;

        if (manualExposureOpen) {
            countdown.setTextColor(RED);
            return;
        }

        String reason = null;
        if (camera == null || !camera.isConnected()) {
            reason = "Connect camera";
        } else if (!manualModeReady) {
            reason = "Camera check: set Manual mode";
        } else if (selectedExposureChoice != cameraExposureTime) {
            reason = "Setting camera shutter to "
                    + NikonBulbRemote.formatExposureTime(selectedExposureChoice);
        } else if (selectedIsoChoice != cameraIso) {
            reason = "Set camera to " + NikonBulbRemote.formatIso(selectedIsoChoice);
        } else if (!focusReady) {
            reason = "Camera check: set MF";
        } else if (selectedMode == CaptureMode.NATIVE) {
            double nativeSeconds = NikonBulbRemote.exposureTimeSeconds(selectedExposureChoice);
            if (!Double.isFinite(nativeSeconds) || nativeSeconds <= 0.0) {
                reason = "Choose a native shutter speed";
            }
        } else if (selectedMode == CaptureMode.TIMED_BULB) {
            String exposureText = exposureField.getText().toString().trim();
            if (exposureText.isEmpty()) {
                reason = "Set Timed Bulb duration";
            } else {
                try {
                    if (Double.parseDouble(exposureText) <= 0.0) {
                        reason = "Timed Bulb duration must be above 0";
                    }
                } catch (Exception e) {
                    reason = "Check Timed Bulb duration";
                }
            }
        }

        if (reason == null && selectedMode != CaptureMode.BULB) {
            String countText = countField.getText().toString().trim();
            String pauseText = pauseField.getText().toString().trim();
            if (!countText.isEmpty()) {
                try {
                    if (Integer.parseInt(countText) < 1) {
                        reason = "Number of exposures must be at least 1";
                    }
                } catch (Exception e) {
                    reason = "Check number of exposures";
                }
            }
            if (reason == null) {
                if (pauseText.isEmpty()) {
                    reason = "Set pause time";
                } else {
                    try {
                        if (Double.parseDouble(pauseText) < 0.0) reason = "Pause cannot be negative";
                    } catch (Exception e) {
                        reason = "Check pause time";
                    }
                }
            }
        }

        if (reason == null) {
            countdown.setText("Ready");
            countdown.setTextColor(RED);
        } else {
            countdown.setText(reason);
            countdown.setTextColor(GREY);
        }
    }

    private void updateButtons() {
        if (startButton == null || exposureField == null
                || pauseField == null || countField == null) return;

        boolean connected = camera != null && camera.isConnected();
        boolean idleReady = !running && isReadyToStart();
        boolean actionable = manualExposureOpen || running || idleReady;

        startButton.setEnabled(actionable);
        if (manualExposureOpen) {
            startButton.setText("STOP EXPOSURE");
        } else if (running) {
            if (selectedMode == CaptureMode.TIMED_BULB && exposureActive) {
                startButton.setText("STOP EXPOSURE");
            } else {
                startButton.setText("STOP SEQUENCE");
            }
        } else {
            boolean manualBulb = selectedMode == CaptureMode.BULB;
            int shots = requestedShotCount();
            startButton.setText(manualBulb || shots <= 1 ? "TAKE PICTURE" : "START SEQUENCE");
        }

        startButton.setTextColor(actionable ? RED : DISABLED_GREY);
        startButton.setBackgroundTintList(ColorStateList.valueOf(
                actionable ? DARK_BUTTON : DISABLED_BUTTON));

        updateExposureModeControls();

        if (captureTabButton != null) captureTabButton.setEnabled(true);
        if (liveTabButton != null) liveTabButton.setEnabled(connected && !running);
        if (playbackTabButton != null) playbackTabButton.setEnabled(connected && !running);

        if (previousButton != null) previousButton.setEnabled(connected && !running
                && playbackHandles.length > 0 && playbackIndex >= 0);
        if (nextButton != null) nextButton.setEnabled(connected && !running
                && playbackHandles.length > 0 && playbackIndex >= 0);

        updateModeButtons();
    }

    private int requestedShotCount() {
        if (countField == null) return 1;
        String text = countField.getText().toString().trim();
        if (text.isEmpty()) return 1;
        try {
            return Math.max(1, Integer.parseInt(text));
        } catch (Exception ignored) {
            return 1;
        }
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(RED);
        return view;
    }

    private TextView label(String value) {
        return text(value, 14);
    }

    private TextView checklistItem(String value) {
        TextView view = text(value, 18);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private EditText numberField(String value, boolean decimal) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setTextSize(22);
        field.setSingleLine(true);
        field.setTextColor(RED);
        field.setHintTextColor(Color.rgb(120, 20, 20));
        field.setBackgroundTintList(ColorStateList.valueOf(RED));
        field.setInputType(InputType.TYPE_CLASS_NUMBER |
                (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        return field;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(RED);
        button.setBackgroundTintList(ColorStateList.valueOf(DARK_BUTTON));
        return button;
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams lp = fullWrap();
        lp.setMargins(0, dp(marginDp), 0, 0);
        return lp;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacks(cameraStatusPoller);
        main.removeCallbacks(liveViewFramePoller);
        main.removeCallbacks(exposureElapsedPoller);
        if (pendingShutterApply != null) main.removeCallbacks(pendingShutterApply);
        if (pendingIsoApply != null) main.removeCallbacks(pendingIsoApply);
        cancelRequested = true;
        try {
            if (camera.isCaptureOpen()) camera.stopCapture();
        } catch (Exception ignored) {}
        try {
            if (camera.isConnected() && camera.isLiveViewActive()) camera.stopLiveView();
        } catch (Exception ignored) {}
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        camera.disconnect();
        io.shutdownNow();
        super.onDestroy();
    }
}
