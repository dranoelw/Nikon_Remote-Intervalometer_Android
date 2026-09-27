package com.openai.nikonintervalometer;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class NikonBulbRemote {
    private static final int NIKON_VENDOR_ID = 0x04B0;

    private static final int CONTAINER_COMMAND = 1;
    private static final int CONTAINER_DATA = 2;
    private static final int CONTAINER_RESPONSE = 3;

    private static final int OC_OPEN_SESSION = 0x1002;
    private static final int OC_CLOSE_SESSION = 0x1003;
    private static final int OC_GET_DEVICE_PROP_DESC = 0x1014;
    private static final int OC_GET_DEVICE_PROP_VALUE = 0x1015;
    private static final int OC_SET_DEVICE_PROP_VALUE = 0x1016;
    private static final int OC_GET_OBJECT_HANDLES = 0x1007;
    private static final int OC_GET_OBJECT = 0x1009;
    private static final int OC_GET_THUMB = 0x100A;

    private static final int OC_NIKON_CHANGE_CAMERA_MODE = 0x90C2;
    private static final int OC_NIKON_GET_EVENT = 0x90C7;
    private static final int OC_NIKON_DEVICE_READY = 0x90C8;
    private static final int OC_NIKON_START_LIVE_VIEW = 0x9201;
    private static final int OC_NIKON_END_LIVE_VIEW = 0x9202;
    private static final int OC_NIKON_GET_LIVE_VIEW_IMAGE = 0x9203;
    private static final int OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA = 0x9207;
    private static final int OC_NIKON_TERMINATE_CAPTURE = 0x920C;

    private static final int PROP_BATTERY_LEVEL = 0x5001;
    private static final int PROP_FOCUS_MODE = 0x500A;
    private static final int PROP_EXPOSURE_TIME = 0x500D;
    private static final int PROP_EXPOSURE_PROGRAM = 0x500E;
    private static final int PROP_EXPOSURE_INDEX = 0x500F;
    private static final int PROP_LIVE_VIEW_STATUS = 0xD1A2;
    private static final int PROP_D3400_PROBE_D1B5 = 0xD1B5;
    private static final int PROP_D3400_PROBE_D1F1 = 0xD1F1;
    private static final String TAG_EXPOSURE_PROBE = "NikonExposureProbe";

    private static final int EXPOSURE_PROGRAM_MANUAL = 0x0001;
    private static final int FOCUS_MODE_MANUAL = 0x0001;
    private static final long EXPOSURE_TIME_BULB = NikonExposureUtils.BULB;
    private static final long EXPOSURE_TIME_TIME = NikonExposureUtils.TIME;
    private static final int PTP_TYPE_UINT16 = 0x0004;
    private static final int PTP_TYPE_UINT32 = 0x0006;
    private static final int PTP_FORM_NONE = 0x00;
    private static final int PTP_FORM_RANGE = 0x01;
    private static final int PTP_FORM_ENUM = 0x02;

    private static final int RC_OK = NikonPtpCodes.OK;
    private static final int RC_ACCESS_DENIED = NikonPtpCodes.ACCESS_DENIED;
    private static final int RC_DEVICE_BUSY = NikonPtpCodes.DEVICE_BUSY;
    private static final int RC_SESSION_ALREADY_OPEN = NikonPtpCodes.SESSION_ALREADY_OPEN;
    private static final int RC_NIKON_CHANGE_CAMERA_MODE_FAILED = NikonPtpCodes.CHANGE_CAMERA_MODE_FAILED;
    private static final int RC_NIKON_INVALID_STATUS = NikonPtpCodes.INVALID_STATUS;

    private static final int NIKON_NO_AF = 0xFFFFFFFF;
    private static final int NIKON_CARD = 0;
    private static final int NIKON_CONTROL_MODE_CAMERA = 0;
    private static final int NIKON_CONTROL_MODE_PC = 1;

    private final UsbManager manager;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface cameraInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;

    private int transactionId = 1;
    private boolean sessionOpen = false;
    private volatile boolean captureOpen = false;
    private volatile boolean pcControlActive = false;
    private volatile boolean captureEnteredPcControl = false;

    public NikonBulbRemote(UsbManager manager) {
        this.manager = manager;
    }

    public boolean isCameraDevice(UsbDevice candidate) {
        if (candidate == null || candidate.getVendorId() != NIKON_VENDOR_ID) return false;
        for (int i = 0; i < candidate.getInterfaceCount(); i++) {
            if (candidate.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return true;
            }
        }
        return false;
    }

    public UsbDevice findCamera() {
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (candidate.getVendorId() != NIKON_VENDOR_ID) continue;
            for (int i = 0; i < candidate.getInterfaceCount(); i++) {
                if (candidate.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public synchronized void connect(UsbDevice candidate) throws Exception {
        disconnect();

        if (candidate == null) throw new Exception("No camera found");
        if (candidate.getVendorId() != NIKON_VENDOR_ID) {
            throw new Exception("Connected USB camera is not a Nikon");
        }
        if (!manager.hasPermission(candidate)) throw new Exception("USB permission not granted");

        UsbInterface foundInterface = null;
        UsbEndpoint foundIn = null;
        UsbEndpoint foundOut = null;

        for (int i = 0; i < candidate.getInterfaceCount(); i++) {
            UsbInterface intf = candidate.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_STILL_IMAGE) continue;

            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;

                if (ep.getDirection() == UsbConstants.USB_DIR_IN) foundIn = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) foundOut = ep;
            }

            if (foundIn != null && foundOut != null) {
                foundInterface = intf;
                break;
            }
        }

        if (foundInterface == null || foundIn == null || foundOut == null) {
            throw new Exception("PTP bulk endpoints not found");
        }

        UsbDeviceConnection c = manager.openDevice(candidate);
        if (c == null) throw new Exception("Could not open USB camera");

        if (!c.claimInterface(foundInterface, true)) {
            c.close();
            throw new Exception("Could not claim camera interface");
        }

        device = candidate;
        connection = c;
        cameraInterface = foundInterface;
        bulkIn = foundIn;
        bulkOut = foundOut;
        transactionId = 1;

        Response open = transact(OC_OPEN_SESSION, new int[]{1}, 10000);
        if (open.code != RC_OK && open.code != RC_SESSION_ALREADY_OPEN) {
            disconnect();
            throw ptpException("OpenSession", open.code);
        }

        sessionOpen = true;
        pcControlActive = false;
        waitUntilReady(30000);
    }

    public synchronized CameraSetup readCameraSetup() throws Exception {
        ensureConnected();

        int batteryLevel = getUint8Property(PROP_BATTERY_LEVEL);
        int exposureProgram = getUint16Property(PROP_EXPOSURE_PROGRAM);
        int focusMode = getUint16Property(PROP_FOCUS_MODE);

        ShutterInfo shutter;
        try {
            shutter = getShutterInfo();
        } catch (Exception descriptorError) {
            // ExposureTime itself is a standard PTP property, but a few Nikon
            // bodies are less cooperative about returning its descriptor.
            // Keep native capture usable by falling back to the current value.
            long currentExposureTime = getUint32Property(PROP_EXPOSURE_TIME);
            shutter = new ShutterInfo(
                    currentExposureTime,
                    currentExposureTime,
                    false,
                    new long[]{currentExposureTime});
        }

        IsoInfo iso;
        try {
            iso = getIsoInfo();
        } catch (Exception descriptorError) {
            int currentIso = getUint16Property(PROP_EXPOSURE_INDEX);
            iso = new IsoInfo(currentIso, currentIso, false, new int[]{currentIso});
        }

        return new CameraSetup(
                exposureProgram == EXPOSURE_PROGRAM_MANUAL,
                shutter.currentValue == EXPOSURE_TIME_BULB,
                focusMode == FOCUS_MODE_MANUAL,
                batteryLevel,
                shutter.currentValue,
                shutter.writable,
                shutter.supportedValues,
                iso.currentValue,
                iso.writable,
                iso.supportedValues);
    }

    public ShutterInfo getShutterInfo() throws Exception {
        ensureConnected();

        byte[] data = dataOperation(
                OC_GET_DEVICE_PROP_DESC,
                new int[]{PROP_EXPOSURE_TIME},
                8000);

        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 13) {
            throw new Exception("ExposureTime descriptor is too short");
        }

        int propertyCode = b.getShort() & 0xFFFF;
        int dataType = b.getShort() & 0xFFFF;
        int getSet = b.get() & 0xFF;

        if (propertyCode != PROP_EXPOSURE_TIME) {
            throw new Exception("Camera returned the wrong property descriptor");
        }
        if (dataType != PTP_TYPE_UINT32) {
            throw new Exception(String.format(
                    "ExposureTime uses unsupported PTP type 0x%04X", dataType));
        }

        long defaultValue = b.getInt() & 0xFFFFFFFFL;
        long currentValue = b.getInt() & 0xFFFFFFFFL;
        int formFlag = b.get() & 0xFF;

        long[] supportedValues;
        if (formFlag == PTP_FORM_ENUM) {
            if (b.remaining() < 2) throw new Exception("Invalid ExposureTime enum descriptor");
            int count = b.getShort() & 0xFFFF;
            if (b.remaining() < count * 4) {
                throw new Exception("Incomplete ExposureTime enum descriptor");
            }
            supportedValues = new long[count];
            for (int i = 0; i < count; i++) {
                supportedValues[i] = b.getInt() & 0xFFFFFFFFL;
            }
        } else if (formFlag == PTP_FORM_RANGE) {
            if (b.remaining() < 12) throw new Exception("Invalid ExposureTime range descriptor");
            long minimum = b.getInt() & 0xFFFFFFFFL;
            long maximum = b.getInt() & 0xFFFFFFFFL;
            long step = b.getInt() & 0xFFFFFFFFL;
            supportedValues = buildRange(minimum, maximum, step, currentValue);
        } else if (formFlag == PTP_FORM_NONE) {
            supportedValues = new long[]{currentValue};
        } else {
            supportedValues = new long[]{currentValue};
        }

        if (!containsValue(supportedValues, currentValue)) {
            long[] expanded = Arrays.copyOf(supportedValues, supportedValues.length + 1);
            expanded[expanded.length - 1] = currentValue;
            supportedValues = expanded;
        }

        return new ShutterInfo(currentValue, defaultValue, getSet != 0, supportedValues);
    }

    public IsoInfo getIsoInfo() throws Exception {
        ensureConnected();

        byte[] data = dataOperation(
                OC_GET_DEVICE_PROP_DESC,
                new int[]{PROP_EXPOSURE_INDEX},
                8000);

        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 9) throw new Exception("ISO descriptor is too short");

        int propertyCode = b.getShort() & 0xFFFF;
        int dataType = b.getShort() & 0xFFFF;
        int getSet = b.get() & 0xFF;
        if (propertyCode != PROP_EXPOSURE_INDEX) {
            throw new Exception("Camera returned the wrong ISO property descriptor");
        }
        if (dataType != PTP_TYPE_UINT16) {
            throw new Exception(String.format("ISO uses unsupported PTP type 0x%04X", dataType));
        }

        int defaultValue = b.getShort() & 0xFFFF;
        int currentValue = b.getShort() & 0xFFFF;
        int formFlag = b.get() & 0xFF;

        int[] supportedValues;
        if (formFlag == PTP_FORM_ENUM) {
            if (b.remaining() < 2) throw new Exception("Invalid ISO enum descriptor");
            int count = b.getShort() & 0xFFFF;
            if (b.remaining() < count * 2) throw new Exception("Incomplete ISO enum descriptor");
            supportedValues = new int[count];
            for (int i = 0; i < count; i++) supportedValues[i] = b.getShort() & 0xFFFF;
        } else if (formFlag == PTP_FORM_RANGE) {
            if (b.remaining() < 6) throw new Exception("Invalid ISO range descriptor");
            int minimum = b.getShort() & 0xFFFF;
            int maximum = b.getShort() & 0xFFFF;
            int step = b.getShort() & 0xFFFF;
            supportedValues = buildIsoRange(minimum, maximum, step, currentValue);
        } else {
            supportedValues = new int[]{currentValue};
        }

        if (!containsIsoValue(supportedValues, currentValue)) {
            int[] expanded = Arrays.copyOf(supportedValues, supportedValues.length + 1);
            expanded[expanded.length - 1] = currentValue;
            supportedValues = expanded;
        }

        return new IsoInfo(currentValue, defaultValue, getSet != 0, supportedValues);
    }

    public synchronized void setIso(int iso) throws Exception {
        ensureConnected();
        waitUntilReady(15000);
        setUint16Property(PROP_EXPOSURE_INDEX, iso);
        waitUntilReady(15000);
    }

    public static String formatIso(int iso) {
        return NikonExposureUtils.formatIso(iso);
    }

    private int[] buildIsoRange(int minimum, int maximum, int step, int currentValue) {
        if (step <= 0 || maximum < minimum) return new int[]{currentValue};
        long countLong = ((long) maximum - minimum) / step + 1;
        if (countLong <= 0 || countLong > 512) return new int[]{currentValue};
        int[] values = new int[(int) countLong];
        int value = minimum;
        for (int i = 0; i < values.length; i++) {
            values[i] = value;
            value += step;
        }
        return values;
    }

    private boolean containsIsoValue(int[] values, int needle) {
        for (int value : values) if (value == needle) return true;
        return false;
    }

    public synchronized void setExposureTime(long exposureTime) throws Exception {
        ensureConnected();
        waitUntilReady(15000);
        setUint32Property(PROP_EXPOSURE_TIME, exposureTime);
        waitUntilReady(15000);
    }

    public static boolean isBulbExposure(long exposureTime) {
        return NikonExposureUtils.isBulb(exposureTime);
    }

    public static boolean isTimeExposure(long exposureTime) {
        return NikonExposureUtils.isTime(exposureTime);
    }

    public static boolean isSpecialExposure(long exposureTime) {
        return NikonExposureUtils.isSpecial(exposureTime);
    }

    public static long bulbExposureTime() {
        return NikonExposureUtils.BULB;
    }

    public static double exposureTimeSeconds(long exposureTime) {
        return NikonExposureUtils.seconds(exposureTime);
    }

    public static String formatExposureTime(long exposureTime) {
        return NikonExposureUtils.formatExposure(exposureTime);
    }

    private long[] buildRange(long minimum, long maximum, long step, long currentValue) {
        if (step <= 0 || maximum < minimum) return new long[]{currentValue};

        long countLong = ((maximum - minimum) / step) + 1;
        if (countLong <= 0 || countLong > 512) return new long[]{currentValue};

        int count = (int) countLong;
        long[] values = new long[count];
        long value = minimum;
        for (int i = 0; i < count; i++) {
            values[i] = value;
            value += step;
        }
        return values;
    }

    private boolean containsValue(long[] values, long needle) {
        for (long value : values) {
            if (value == needle) return true;
        }
        return false;
    }

    private void enterRemoteControlMode() throws Exception {
        ensureConnected();
        if (pcControlActive) return;
        waitUntilReady(15000);

        Response response = transact(
                OC_NIKON_CHANGE_CAMERA_MODE,
                new int[]{NIKON_CONTROL_MODE_PC},
                10000);

        if (response.code != RC_OK) {
            throw ptpException("Enter Nikon remote-control mode", response.code);
        }

        pcControlActive = true;
        waitUntilReady(15000);
    }

    private void leaveRemoteControlMode() throws Exception {
        ensureConnected();
        if (!pcControlActive) return;

        Response response = transact(
                OC_NIKON_CHANGE_CAMERA_MODE,
                new int[]{NIKON_CONTROL_MODE_CAMERA},
                10000);

        if (response.code != RC_OK && response.code != RC_NIKON_INVALID_STATUS) {
            throw ptpException("Return camera control", response.code);
        }

        pcControlActive = false;
        waitUntilReady(15000);
    }

    private void refreshRemoteControlMode() throws Exception {
        try {
            enterRemoteControlMode();
        } catch (Exception first) {
            // Some Nikon bodies can be left in a stale control state after
            // a failed capture or Live View transition. Explicitly return
            // control to the camera once, then request PC control again.
            Response reset = transact(
                    OC_NIKON_CHANGE_CAMERA_MODE,
                    new int[]{NIKON_CONTROL_MODE_CAMERA},
                    10000);
            if (reset.code != RC_OK && reset.code != RC_NIKON_INVALID_STATUS) {
                throw first;
            }
            waitUntilReady(15000);
            enterRemoteControlMode();
        }
    }

    private void prepareForRemoteCapture() throws Exception {
        ensureConnected();
        // Prefer the pre-PR9 behavior: do not force PC-control mode just to
        // capture. The D3400 can usually capture directly from the open PTP
        // session, which lets the rear LCD sleep normally.
        waitUntilReady(15000);
    }

    public synchronized void logExposureDiagnostic(String label) {
        if (!isConnected()) return;

        StringBuilder line = new StringBuilder(label);
        line.append(" captureOpen=").append(captureOpen);
        line.append(" pcControl=").append(pcControlActive);

        try {
            line.append(" liveView=").append(getUint8Property(PROP_LIVE_VIEW_STATUS));
        } catch (Exception e) {
            line.append(" liveView=ERR(").append(e.getMessage()).append(")");
        }

        try {
            line.append(String.format(" d1b5=0x%04X", getUint16Property(PROP_D3400_PROBE_D1B5)));
        } catch (Exception e) {
            line.append(" d1b5=ERR(").append(e.getMessage()).append(")");
        }

        try {
            line.append(String.format(" d1f1=0x%04X", getUint16Property(PROP_D3400_PROBE_D1F1)));
        } catch (Exception e) {
            line.append(" d1f1=ERR(").append(e.getMessage()).append(")");
        }

        try {
            Response ready = transact(OC_NIKON_DEVICE_READY, new int[]{}, 3000);
            line.append(String.format(" deviceReady=0x%04X", ready.code));
        } catch (Exception e) {
            line.append(" deviceReady=ERR(").append(e.getMessage()).append(")");
        }

        Log.i(TAG_EXPOSURE_PROBE, line.toString());
    }

    public synchronized boolean isLiveViewActive() throws Exception {
        ensureConnected();
        return getUint8Property(PROP_LIVE_VIEW_STATUS) != 0;
    }

    public synchronized void startLiveView() throws Exception {
        ensureConnected();
        if (isLiveViewActive()) return;

        waitUntilReady(15000);

        // Try the older direct Live View path first so the camera does not show
        // "Connected to PC" unless this body actually requires PC control.
        Response response = transact(
                OC_NIKON_START_LIVE_VIEW,
                new int[]{},
                10000);

        if (response.code == RC_NIKON_INVALID_STATUS
                || response.code == RC_NIKON_CHANGE_CAMERA_MODE_FAILED) {
            try {
                dataOperation(OC_NIKON_GET_EVENT, new int[]{}, 3000);
            } catch (Exception ignored) {}

            refreshRemoteControlMode();
            waitUntilReady(15000);
            response = transact(
                    OC_NIKON_START_LIVE_VIEW,
                    new int[]{},
                    10000);
        }

        if (response.code != RC_OK) {
            throw ptpException("Start Live View", response.code);
        }

        waitUntilReady(15000);
    }

    public synchronized void stopLiveView() throws Exception {
        ensureConnected();
        if (!isLiveViewActive()) return;

        Response response = transact(
                OC_NIKON_END_LIVE_VIEW,
                new int[]{},
                10000);

        if (response.code != RC_OK) {
            throw ptpException("End Live View", response.code);
        }

        waitUntilReady(15000);

        // If Live View needed temporary Nikon PC control, release it as soon as
        // Live View ends so the D3400 can return to its normal display/sleep behavior.
        if (pcControlActive && !captureOpen) {
            try { leaveRemoteControlMode(); } catch (Exception ignored) {}
        }
    }

    public synchronized void settleAfterCapture() throws Exception {
        ensureConnected();

        // Nikon bodies can report DeviceReady before all post-capture vendor
        // events have been consumed. Drain the Nikon event queue, then verify
        // readiness again without changing Live View state.
        waitUntilReady(30000);

        for (int i = 0; i < 3; i++) {
            try {
                byte[] eventPayload = dataOperation(
                        OC_NIKON_GET_EVENT,
                        new int[]{},
                        3000);
                Log.i(TAG_EXPOSURE_PROBE,
                        "settle-event[" + i + "] bytes=" + eventPayload.length
                                + " hex=" + toHex(eventPayload, 96));
            } catch (Exception ignored) {
                // Some bodies return no event data or time out once the queue is
                // empty. That is not fatal for the next capture.
                break;
            }

            waitUntilReady(10000);

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Interrupted while settling camera");
            }
        }

        waitUntilReady(30000);
    }

    public synchronized int[] getImageHandles() throws Exception {
        ensureConnected();
        waitUntilReady(30000);

        byte[] data = dataOperation(
                OC_GET_OBJECT_HANDLES,
                new int[]{0xFFFFFFFF, 0, 0},
                30000);

        if (data.length < 4) return new int[0];
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long countLong = b.getInt() & 0xFFFFFFFFL;
        int count = (int)Math.min(countLong, (data.length - 4) / 4);
        int[] handles = new int[count];
        for (int i = 0; i < count; i++) handles[i] = b.getInt();
        return handles;
    }

    public synchronized byte[] getThumbnail(int objectHandle) throws Exception {
        ensureConnected();
        waitUntilReady(30000);
        return dataOperation(OC_GET_THUMB, new int[]{objectHandle}, 30000);
    }

    public synchronized byte[] getObject(int objectHandle) throws Exception {
        ensureConnected();
        waitUntilReady(30000);
        return dataOperation(OC_GET_OBJECT, new int[]{objectHandle}, 60000);
    }

    public synchronized byte[] getLiveViewJpeg() throws Exception {
        ensureConnected();

        // The frame request itself is authoritative. Avoid an extra
        // GetDevicePropValue(LiveViewStatus) transaction before every frame;
        // if Live View has ended, Nikon will reject the frame operation.
        byte[] payload = dataOperation(
                OC_NIKON_GET_LIVE_VIEW_IMAGE,
                new int[]{},
                8000);

        byte[] jpeg = extractJpeg(payload);
        if (jpeg == null) throw new Exception("Live View frame contained no JPEG");
        return jpeg;
    }

    private static String toHex(byte[] data, int maxBytes) {
        if (data == null) return "";
        int count = Math.min(data.length, Math.max(0, maxBytes));
        StringBuilder out = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02X", data[i] & 0xFF));
        }
        if (data.length > count) out.append(" …");
        return out.toString();
    }

    private byte[] extractJpeg(byte[] payload) {
        if (payload == null || payload.length < 4) return null;

        int start = -1;
        for (int i = 0; i < payload.length - 1; i++) {
            if ((payload[i] & 0xFF) == 0xFF && (payload[i + 1] & 0xFF) == 0xD8) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;

        int end = -1;
        for (int i = payload.length - 2; i >= start; i--) {
            if ((payload[i] & 0xFF) == 0xFF && (payload[i + 1] & 0xFF) == 0xD9) {
                end = i + 2;
                break;
            }
        }
        if (end <= start) return null;
        return Arrays.copyOfRange(payload, start, end);
    }

    public synchronized void captureNativeNoAf() throws Exception {
        if (captureOpen) throw new Exception("Bulb capture is already open");
        initiateCapture(false, "CAPTURE");
    }

    public synchronized void startCaptureNoAf() throws Exception {
        if (captureOpen) throw new Exception("Capture is already open");
        initiateCapture(true, "START");
    }

    private void initiateCapture(boolean keepOpen, String operationLabel) throws Exception {
        ensureConnected();
        prepareForRemoteCapture();
        waitUntilReady(30000);

        long deadline = System.currentTimeMillis() + 30000;
        Response response;
        boolean enteredPcForThisCapture = false;

        do {
            response = transact(
                    OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA,
                    new int[]{NIKON_NO_AF, NIKON_CARD},
                    15000);

            if (response.code == RC_OK) {
                if (keepOpen) {
                    captureOpen = true;
                    captureEnteredPcControl = enteredPcForThisCapture;
                } else {
                    settleAfterCapture();
                    if (enteredPcForThisCapture && !isLiveViewActive()) {
                        try { leaveRemoteControlMode(); } catch (Exception ignored) {}
                    }
                }
                return;
            }

            if (response.code == RC_ACCESS_DENIED) {
                settleAfterCapture();
                continue;
            }

            if (response.code == RC_NIKON_INVALID_STATUS
                    || response.code == RC_NIKON_CHANGE_CAMERA_MODE_FAILED) {
                if (!isLiveViewActive() && !pcControlActive) {
                    refreshRemoteControlMode();
                    enteredPcForThisCapture = true;
                } else {
                    waitUntilReady(15000);
                }
                continue;
            }

            if (!isBusy(response.code)) {
                throw ptpException(operationLabel + " rejected", response.code);
            }

            waitUntilReady(Math.max(1000, deadline - System.currentTimeMillis()));
        } while (System.currentTimeMillis() < deadline);

        if (enteredPcForThisCapture && !captureOpen && !isLiveViewActive()) {
            try { leaveRemoteControlMode(); } catch (Exception ignored) {}
        }
        throw ptpException(operationLabel + " stayed busy", response.code);
    }

    public synchronized void stopCapture() throws Exception {
        ensureConnected();
        if (!captureOpen) return;

        Response response = transact(
                OC_NIKON_TERMINATE_CAPTURE,
                new int[]{NIKON_NO_AF, NIKON_CARD},
                15000);

        captureOpen = false;

        if (response.code != RC_OK) {
            throw ptpException("STOP rejected", response.code);
        }

        settleAfterCapture();

        if (captureEnteredPcControl && !isLiveViewActive()) {
            try { leaveRemoteControlMode(); } catch (Exception ignored) {}
        }
        captureEnteredPcControl = false;
    }

    public boolean isCaptureOpen() {
        return captureOpen;
    }

    public boolean isConnected() {
        return connection != null && cameraInterface != null && bulkIn != null && bulkOut != null;
    }

    public String getDeviceName() {
        if (device == null) return "Nikon Camera";
        String product = device.getProductName();
        if (product != null && !product.trim().isEmpty()) return product;
        return "Nikon Camera";
    }

    public void disconnect() {
        if (connection != null) {
            if (captureOpen) {
                try { stopCapture(); } catch (Exception ignored) {}
            }

            if (sessionOpen) {
                if (pcControlActive) {
                    try { leaveRemoteControlMode(); } catch (Exception ignored) {}
                }
                try { transact(OC_CLOSE_SESSION, new int[]{}, 5000); } catch (Exception ignored) {}
            }

            if (cameraInterface != null) {
                try { connection.releaseInterface(cameraInterface); } catch (Exception ignored) {}
            }

            try { connection.close(); } catch (Exception ignored) {}
        }

        device = null;
        connection = null;
        cameraInterface = null;
        bulkIn = null;
        bulkOut = null;
        transactionId = 1;
        sessionOpen = false;
        captureOpen = false;
        pcControlActive = false;
        captureEnteredPcControl = false;
    }

    private int getUint8Property(int propertyCode) throws Exception {
        byte[] data = getPropertyData(propertyCode);
        if (data.length < 1) throw new Exception(String.format("Property 0x%04X returned no value", propertyCode));
        return data[0] & 0xFF;
    }

    private int getUint16Property(int propertyCode) throws Exception {
        byte[] data = getPropertyData(propertyCode);
        if (data.length < 2) throw new Exception(String.format("Property 0x%04X returned no value", propertyCode));
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private long getUint32Property(int propertyCode) throws Exception {
        byte[] data = getPropertyData(propertyCode);
        if (data.length < 4) throw new Exception(String.format("Property 0x%04X returned no value", propertyCode));
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private void setUint16Property(int propertyCode, int value) throws Exception {
        int tid = transactionId++;
        sendCommand(OC_SET_DEVICE_PROP_VALUE, tid, new int[]{propertyCode}, 5000);

        ByteBuffer payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        payload.putShort((short)(value & 0xFFFF));
        sendDataContainer(OC_SET_DEVICE_PROP_VALUE, tid, payload.array(), 5000);

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            UsbContainer container = readContainer(Math.max(1, deadline - System.currentTimeMillis()));
            if (container.transactionId != tid) continue;
            if (container.type == CONTAINER_RESPONSE) {
                if (container.code != RC_OK) throw ptpException("Set camera property", container.code);
                return;
            }
        }
        throw new Exception(String.format("Property 0x%04X write timed out", propertyCode));
    }

    private void setUint32Property(int propertyCode, long value) throws Exception {
        int tid = transactionId++;
        sendCommand(OC_SET_DEVICE_PROP_VALUE, tid, new int[]{propertyCode}, 5000);

        ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt((int)(value & 0xFFFFFFFFL));
        sendDataContainer(OC_SET_DEVICE_PROP_VALUE, tid, payload.array(), 5000);

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            UsbContainer container = readContainer(Math.max(1, deadline - System.currentTimeMillis()));
            if (container.transactionId != tid) continue;
            if (container.type == CONTAINER_RESPONSE) {
                if (container.code != RC_OK) {
                    throw ptpException("Set camera property", container.code);
                }
                return;
            }
        }

        throw new Exception(String.format("Property 0x%04X write timed out", propertyCode));
    }

    private void sendDataContainer(int operationCode, int tid, byte[] payload, int timeoutMs)
            throws Exception {
        int length = 12 + payload.length;
        ByteBuffer data = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(length);
        data.putShort((short) CONTAINER_DATA);
        data.putShort((short) operationCode);
        data.putInt(tid);
        data.put(payload);

        byte[] out = data.array();
        int written = connection.bulkTransfer(bulkOut, out, out.length, timeoutMs);
        if (written != out.length) throw new Exception("USB data write failed");
    }

    private byte[] getPropertyData(int propertyCode) throws Exception {
        int tid = transactionId++;
        sendCommand(OC_GET_DEVICE_PROP_VALUE, tid, new int[]{propertyCode}, 5000);

        byte[] value = null;
        byte[] input = new byte[1024];
        long deadline = System.currentTimeMillis() + 8000;

        while (System.currentTimeMillis() < deadline) {
            int remaining = (int)Math.max(1, deadline - System.currentTimeMillis());
            int n = connection.bulkTransfer(bulkIn, input, input.length, Math.min(3000, remaining));
            if (n < 12) continue;

            int offset = 0;
            while (offset + 12 <= n) {
                ByteBuffer header = ByteBuffer.wrap(input, offset, n - offset).order(ByteOrder.LITTLE_ENDIAN);
                int length = header.getInt();
                int type = header.getShort() & 0xFFFF;
                int code = header.getShort() & 0xFFFF;
                int responseTid = header.getInt();

                if (length < 12 || offset + length > n) break;

                if (responseTid == tid && type == CONTAINER_DATA && code == OC_GET_DEVICE_PROP_VALUE) {
                    int payloadLength = length - 12;
                    value = new byte[payloadLength];
                    System.arraycopy(input, offset + 12, value, 0, payloadLength);
                } else if (responseTid == tid && type == CONTAINER_RESPONSE) {
                    if (code != RC_OK) throw ptpException("Read camera property", code);
                    if (value == null) throw new Exception("Camera property returned no data");
                    return value;
                }

                offset += length;
            }
        }

        throw new Exception(String.format("Property 0x%04X timed out", propertyCode));
    }

    private byte[] dataOperation(int operationCode, int[] params, int timeoutMs) throws Exception {
        int tid = transactionId++;
        sendCommand(operationCode, tid, params, timeoutMs);

        byte[] payload = null;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            UsbContainer container = readContainer(Math.max(1, deadline - System.currentTimeMillis()));
            if (container.transactionId != tid) continue;

            if (container.type == CONTAINER_DATA && container.code == operationCode) {
                payload = container.payload;
            } else if (container.type == CONTAINER_RESPONSE) {
                if (container.code != RC_OK) {
                    throw ptpException("PTP data operation", container.code);
                }
                if (payload == null) return new byte[0];
                return payload;
            }
        }

        throw new Exception(String.format("PTP 0x%04X timed out", operationCode));
    }

    private UsbContainer readContainer(long timeoutMs) throws Exception {
        byte[] first = new byte[16384];
        int timeout = (int)Math.min(Integer.MAX_VALUE, Math.max(1, timeoutMs));

        int n;
        do {
            n = connection.bulkTransfer(bulkIn, first, first.length, Math.min(3000, timeout));
            if (n < 0) throw new Exception("USB read failed");
        } while (n < 12);

        ByteBuffer header = ByteBuffer.wrap(first, 0, n).order(ByteOrder.LITTLE_ENDIAN);
        int length = header.getInt();
        int type = header.getShort() & 0xFFFF;
        int code = header.getShort() & 0xFFFF;
        int tid = header.getInt();

        if (length < 12 || length > 32 * 1024 * 1024) {
            throw new Exception("Invalid PTP container length");
        }

        byte[] container = new byte[length];
        int copied = Math.min(n, length);
        System.arraycopy(first, 0, container, 0, copied);

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (copied < length && System.currentTimeMillis() < deadline) {
            int want = Math.min(16384, length - copied);
            byte[] chunk = new byte[want];
            int remaining = (int)Math.max(1, deadline - System.currentTimeMillis());
            int got = connection.bulkTransfer(bulkIn, chunk, want, Math.min(3000, remaining));
            if (got <= 0) continue;
            System.arraycopy(chunk, 0, container, copied, got);
            copied += got;
        }

        if (copied < length) throw new Exception("PTP data transfer timed out");

        return new UsbContainer(
                type,
                code,
                tid,
                length > 12 ? Arrays.copyOfRange(container, 12, length) : new byte[0]);
    }

    private void waitUntilReady(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Response last = null;

        while (System.currentTimeMillis() < deadline) {
            last = transact(OC_NIKON_DEVICE_READY, new int[]{}, 5000);

            if (last.code == RC_OK) return;

            if (!isBusy(last.code)) {
                throw ptpException("Camera readiness check", last.code);
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Interrupted while waiting for camera");
            }
        }

        if (last != null) throw ptpException("Camera stayed busy too long", last.code);
        throw new Exception("Camera readiness check timed out");
    }

    private boolean isBusy(int code) {
        return NikonPtpCodes.isBusy(code);
    }

    private Response transact(int operationCode, int[] params, int timeoutMs) throws Exception {
        int tid = transactionId++;
        sendCommand(operationCode, tid, params, timeoutMs);

        byte[] input = new byte[512];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int remaining = (int)Math.max(1, deadline - System.currentTimeMillis());
            int n = connection.bulkTransfer(bulkIn, input, input.length, Math.min(3000, remaining));
            if (n < 12) continue;

            int offset = 0;
            while (offset + 12 <= n) {
                ByteBuffer response = ByteBuffer.wrap(input, offset, n - offset).order(ByteOrder.LITTLE_ENDIAN);
                int length = response.getInt();
                int type = response.getShort() & 0xFFFF;
                int code = response.getShort() & 0xFFFF;
                int responseTid = response.getInt();

                if (length < 12 || offset + length > n) break;

                if (type == CONTAINER_RESPONSE && responseTid == tid) {
                    return new Response(code);
                }
                offset += length;
            }
        }

        throw new Exception(String.format("PTP 0x%04X timed out", operationCode));
    }

    private void sendCommand(int operationCode, int tid, int[] params, int timeoutMs) throws Exception {
        int commandLength = 12 + params.length * 4;
        ByteBuffer command = ByteBuffer.allocate(commandLength).order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(commandLength);
        command.putShort((short) CONTAINER_COMMAND);
        command.putShort((short) operationCode);
        command.putInt(tid);
        for (int p : params) command.putInt(p);

        byte[] out = command.array();
        int written = connection.bulkTransfer(bulkOut, out, out.length, timeoutMs);
        if (written != out.length) throw new Exception("USB command write failed");
    }

    private void ensureConnected() throws Exception {
        if (!isConnected()) throw new Exception("Camera not connected");
    }

    private Exception ptpException(String operation, int code) {
        return NikonPtpCodes.exception(operation, code);
    }

    public static final class CameraSetup {
        public final boolean manualMode;
        public final boolean bulb;
        public final boolean manualFocus;
        public final int batteryLevel;
        public final long exposureTime;
        public final boolean exposureTimeWritable;
        public final long[] supportedExposureTimes;
        public final int iso;
        public final boolean isoWritable;
        public final int[] supportedIsoValues;

        CameraSetup(
                boolean manualMode,
                boolean bulb,
                boolean manualFocus,
                int batteryLevel,
                long exposureTime,
                boolean exposureTimeWritable,
                long[] supportedExposureTimes,
                int iso,
                boolean isoWritable,
                int[] supportedIsoValues) {
            this.manualMode = manualMode;
            this.bulb = bulb;
            this.manualFocus = manualFocus;
            this.batteryLevel = batteryLevel;
            this.exposureTime = exposureTime;
            this.exposureTimeWritable = exposureTimeWritable;
            this.supportedExposureTimes = supportedExposureTimes;
            this.iso = iso;
            this.isoWritable = isoWritable;
            this.supportedIsoValues = supportedIsoValues;
        }
    }

    public static final class IsoInfo {
        public final int currentValue;
        public final int defaultValue;
        public final boolean writable;
        public final int[] supportedValues;

        IsoInfo(int currentValue, int defaultValue, boolean writable, int[] supportedValues) {
            this.currentValue = currentValue;
            this.defaultValue = defaultValue;
            this.writable = writable;
            this.supportedValues = supportedValues;
        }
    }

    public static final class ShutterInfo {
        public final long currentValue;
        public final long defaultValue;
        public final boolean writable;
        public final long[] supportedValues;

        ShutterInfo(long currentValue, long defaultValue, boolean writable, long[] supportedValues) {
            this.currentValue = currentValue;
            this.defaultValue = defaultValue;
            this.writable = writable;
            this.supportedValues = supportedValues;
        }
    }

    private static final class UsbContainer {
        final int type;
        final int code;
        final int transactionId;
        final byte[] payload;

        UsbContainer(int type, int code, int transactionId, byte[] payload) {
            this.type = type;
            this.code = code;
            this.transactionId = transactionId;
            this.payload = payload;
        }
    }

    private static final class Response {
        final int code;
        Response(int code) { this.code = code; }
    }
}
