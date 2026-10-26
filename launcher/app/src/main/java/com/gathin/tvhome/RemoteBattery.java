package com.gathin.tvhome;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Battery level of the paired Bluetooth remote, for the top-bar pill.
 *
 * The SDK has no public call for it: TvSettings reads BluetoothDevice#getBatteryLevel(), which is hidden. Android 10
 * lets apps reach it by reflection; where a newer Android blocks that, the level comes from the Bluetooth app's
 * battery broadcast instead, and until one arrives the pill stays hidden.
 */
final class RemoteBattery {
    /** BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED and EXTRA_BATTERY_LEVEL, both hidden in the SDK. */
    static final String ACTION_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL";

    private static Method getBatteryLevel;
    private static boolean lookedUp;
    /** The last level a remote broadcast, for platforms where getBatteryLevel() cannot be reached. */
    private static int broadcastLevel = -1;

    private RemoteBattery() {}

    /** A new level, and a remote that connects, drops, or goes with Bluetooth. */
    static IntentFilter filter() {
        IntentFilter filter = new IntentFilter(ACTION_LEVEL_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        return filter;
    }

    /** Keeps the broadcast level; call for every intent filter() matched, before level(). */
    static void onBroadcast(Intent intent) {
        String action = intent.getAction();
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            broadcastLevel = -1;
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || !isRemote(device)) return;
        if (ACTION_LEVEL_CHANGED.equals(action)) broadcastLevel = intent.getIntExtra(EXTRA_LEVEL, -1);
        else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) broadcastLevel = -1;
    }

    /** The battery percent of the first paired remote that reports one, or -1 when none does. */
    @SuppressLint("MissingPermission")   // allowed() checks it
    static int level(Context context) {
        if (!allowed(context)) return -1;
        Method method = method();
        if (method == null) return valid(broadcastLevel) ? broadcastLevel : -1;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) return -1;
        try {
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            if (paired == null) return -1;
            for (BluetoothDevice device : paired) {
                if (!isRemote(device)) continue;
                // -1 while the remote is asleep or out of range: try the next one.
                int level = (Integer) method.invoke(device);
                if (valid(level)) return level;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
        return -1;
    }

    private static boolean valid(int level) {
        return level >= 0 && level <= 100;
    }

    /** Headphones and speakers report a battery too; anything paired that is not audio counts as a remote. */
    @SuppressLint("MissingPermission")   // reached only through level() and onBroadcast(), after the permission
    private static boolean isRemote(BluetoothDevice device) {
        try {
            BluetoothClass type = device.getBluetoothClass();
            return type == null || type.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean allowed(Context context) {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** BluetoothDevice#getBatteryLevel(), looked up once, or null where the platform blocks it. */
    @SuppressLint({"DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi"})
    private static Method method() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                getBatteryLevel = BluetoothDevice.class.getMethod("getBatteryLevel");
            } catch (NoSuchMethodException | RuntimeException e) {
                getBatteryLevel = null;
            }
        }
        return getBatteryLevel;
    }
}
