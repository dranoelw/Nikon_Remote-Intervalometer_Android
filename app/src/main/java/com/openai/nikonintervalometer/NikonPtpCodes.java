package com.openai.nikonintervalometer;

final class NikonPtpCodes {
    private NikonPtpCodes() {}

    static final int OK = 0x2001;
    static final int ACCESS_DENIED = 0x200F;
    static final int DEVICE_BUSY = 0x2019;
    static final int SESSION_ALREADY_OPEN = 0x201E;

    static final int HARDWARE_ERROR = 0xA001;
    static final int OUT_OF_FOCUS = 0xA002;
    static final int CHANGE_CAMERA_MODE_FAILED = 0xA003;
    static final int INVALID_STATUS = 0xA004;
    static final int SET_PROPERTY_NOT_SUPPORTED = 0xA005;
    static final int WB_RESET_ERROR = 0xA006;
    static final int DUST_REFERENCE_ERROR = 0xA007;
    static final int BULB_RELEASE_BUSY = 0xA200;
    static final int SILENT_RELEASE_BUSY = 0xA201;

    static boolean isBusy(int code) {
        return code == DEVICE_BUSY || code == BULB_RELEASE_BUSY || code == SILENT_RELEASE_BUSY;
    }

    static Exception exception(String operation, int code) {
        String hint = "";
        if (code == ACCESS_DENIED) hint = " — access denied / camera state not ready";
        else if (code == DEVICE_BUSY) hint = " — camera busy";
        else if (code == HARDWARE_ERROR) hint = " — Nikon hardware error";
        else if (code == OUT_OF_FOCUS) hint = " — Nikon out of focus";
        else if (code == CHANGE_CAMERA_MODE_FAILED) hint = " — Nikon camera-mode change failed";
        else if (code == INVALID_STATUS) hint = " — Nikon invalid status / camera not ready for this operation";
        else if (code == SET_PROPERTY_NOT_SUPPORTED) hint = " — Nikon property change not supported";
        else if (code == WB_RESET_ERROR) hint = " — Nikon white-balance reset error";
        else if (code == DUST_REFERENCE_ERROR) hint = " — Nikon dust-reference error";
        else if (code == BULB_RELEASE_BUSY) hint = " — Nikon Bulb release busy";
        else if (code == SILENT_RELEASE_BUSY) hint = " — Nikon silent release busy";
        else if (code == 0x2005) hint = " — operation not supported";
        else if (code == 0x200A) hint = " — invalid parameter";
        return new Exception(String.format("%s: PTP 0x%04X%s", operation, code, hint));
    }
}
