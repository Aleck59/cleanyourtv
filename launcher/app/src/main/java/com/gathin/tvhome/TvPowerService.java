package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;

/**
 * The remote's power button turns the TV off and on over HDMI-CEC while the box itself stays awake, on an HDMI player
 * (an Android TV box) whose power key would otherwise put it to sleep.
 *
 * Android 10 has no setting for the power key: a short press always sends the box to sleep, and the HDMI-CEC service
 * answers that by sending the TV <Standby> when hdmi_control_auto_device_off_enabled is on. So the press is left to do
 * just that, and the box is woken again a moment later: the TV is off and the box keeps running. Waking sends the TV
 * only <Report Physical Address> and <Device Vendor Id>, which leave it off (HDBOX Z10 Pro Max, Allwinner, Android 10).
 *
 * The next press, with no other key in between, is the user turning the TV back on: the box is woken at once, which
 * cancels its own <Standby> before it goes out, and a Home press makes Android send <Text View On> and
 * <Active Source> (PhoneWindowManager.handleShortPressOnHome). Any other key pressed while the TV is off means it was
 * turned on another way (Home, the TV's own remote), so the next power press turns it off again.
 *
 * It acts only on an HDMI playback device (ro.hdmi.device_type 4): on a TV the screen going off is the TV itself.
 *
 * Enable over adb:
 *   settings put global hdmi_control_auto_device_off_enabled 1
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.TvPowerService
 *   settings put secure accessibility_enabled 1
 */
public class TvPowerService extends AccessibilityService {
    private static final String TAG = "tvhome:TvPowerService";
    /** Settings.Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED, hidden in the SDK. */
    private static final String AUTO_DEVICE_OFF = "hdmi_control_auto_device_off_enabled";
    /** The CEC service sends <Standby> within a second of the screen going off; wake the box after it. */
    private static final long TV_OFF_WAKE_MS = 2_000;
    /** Waking this soon stops the CEC standby before its <Standby>, so the TV hears nothing on its way back on. */
    private static final long TV_ON_WAKE_MS = 200;
    /** Let the CEC service finish allocating its address after the wake before Home asks it for One Touch Play. */
    private static final long HOME_AFTER_WAKE_MS = 1_500;
    /** A screen-off this soon after this service woke the box is the TV answering with its own <Standby>, not a press. */
    private static final long ECHO_MS = 3_000;
    /** Holds the CPU from the screen going off until it is back on, so the box never suspends in between. */
    private static final long HOLD_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock hold;
    private BroadcastReceiver screenOff;
    private boolean tvOff;
    private boolean keysSinceTvOff;
    private long lastWake = -ECHO_MS;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!isHdmiPlayer()) {
            // Nothing to do on a TV, so don't filter its keys either.
            RemoteKeys.applyKeyFilter(this, false);
            return;
        }
        RemoteKeys.applyKeyFilter(this, Config.load(this).keyFilter);
        autoDeviceOff();
        hold = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        hold.setReferenceCounted(false);
        screenOff = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onScreenOff();
            }
        };
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    private void onScreenOff() {
        hold.acquire(HOLD_MS);
        handler.removeCallbacksAndMessages(null);
        if (SystemClock.uptimeMillis() - lastWake < ECHO_MS) {
            wakeAfter(TV_OFF_WAKE_MS, false);
        } else if (!tvOff || keysSinceTvOff) {
            tvOff = true;
            keysSinceTvOff = false;
            wakeAfter(TV_OFF_WAKE_MS, false);
        } else {
            tvOff = false;
            wakeAfter(TV_ON_WAKE_MS, true);
        }
    }

    private void wakeAfter(long delay, boolean tvOn) {
        handler.postDelayed(() -> {
            wakeScreen();
            hold.release();
            if (tvOn) handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), HOME_AFTER_WAKE_MS);
        }, delay);
    }

    /** ACQUIRE_CAUSES_WAKEUP on a screen wake lock is the one way an app can turn the screen on. */
    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        PowerManager.WakeLock screen = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, TAG);
        screen.acquire(1_000);
        lastWake = SystemClock.uptimeMillis();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (tvOff && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() != KeyEvent.KEYCODE_POWER) {
            keysSinceTvOff = true;
        }
        return false;
    }

    /** The CEC service sends the TV <Standby> only with this on; set here when WRITE_SECURE_SETTINGS was granted. */
    private void autoDeviceOff() {
        try {
            if (Settings.Global.getInt(getContentResolver(), AUTO_DEVICE_OFF, 0) != 1) {
                Settings.Global.putInt(getContentResolver(), AUTO_DEVICE_OFF, 1);
            }
        } catch (SecurityException e) {
            // Not granted: it is set over adb instead (see the class comment).
        }
    }

    /** ro.hdmi.device_type lists 4 (playback) on a box and 0 on a TV. */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static boolean isHdmiPlayer() {
        try {
            String types = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, "ro.hdmi.device_type");
            return types != null && Arrays.asList(types.split(",")).contains("4");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (screenOff != null) unregisterReceiver(screenOff);
        if (hold != null) hold.release();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
