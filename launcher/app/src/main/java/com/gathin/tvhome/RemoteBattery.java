package com.gathin.tvhome;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

/**
 * Battery level of the Bluetooth remote, for the top-bar pill.
 *
 * The SDK has no public call for it. BluetoothDevice#getBatteryLevel() is hidden, reached here by reflection, and
 * some firmware leaves it at -1 for a BLE remote because only the vendor's own app reads the level (HDBOX Z10 Pro
 * Max, Allwinner, Android 10, Qstar remote). So a connected remote is also asked over GATT: the standard Battery
 * Service's Battery Level, read once, then the client is closed again. A BLE remote drops its link while idle, so
 * the last level read is kept, across restarts too, and shown until the next read.
 */
final class RemoteBattery {
    /** BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED and EXTRA_BATTERY_LEVEL, both hidden in the SDK. */
    private static final String ACTION_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final String PREFS = "remote_battery";
    private static final String KEY_LEVEL = "level";
    /** A read that never finishes is closed, so clients cannot pile up on the remote. */
    private static final long GATT_TIMEOUT_MS = 15_000;
    /** After a remote wakes, give the HID host its own GATT setup first. */
    private static final long AFTER_CONNECT_MS = 1_500;

    private static Method getBatteryLevel;
    private static boolean lookedUp;

    private final Context context;
    private final Runnable onChange;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable giveUp = this::closeGatt;
    private Runnable pending;
    private BluetoothGatt gatt;
    private int level;

    /** onChange runs on the main thread whenever a new level is read. */
    RemoteBattery(Context context, Runnable onChange) {
        this.context = context.getApplicationContext();
        this.onChange = onChange;
        level = prefs().getInt(KEY_LEVEL, -1);
    }

    /** A new level from the Bluetooth app, and a remote that wakes up. */
    static IntentFilter filter() {
        IntentFilter filter = new IntentFilter(ACTION_LEVEL_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        return filter;
    }

    /** The last level read, or -1 before the first one. */
    int level() {
        return level;
    }

    /** For every intent filter() matched. */
    void onBroadcast(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || !isRemote(device)) return;
        if (ACTION_LEVEL_CHANGED.equals(intent.getAction())) {
            store(intent.getIntExtra(EXTRA_LEVEL, -1));
        } else {
            main.removeCallbacks(pending);
            pending = () -> refresh(device);
            main.postDelayed(pending, AFTER_CONNECT_MS);
        }
    }

    /** Reads the level now, from a remote that is connected; a sleeping one is read when it wakes. */
    void refresh() {
        refresh(null);
    }

    /** Home went to the background: no read waiting and no GATT client left open. */
    void stop() {
        main.removeCallbacks(pending);
        closeGatt();
    }

    @SuppressLint("MissingPermission")   // allowed() checks it
    private void refresh(BluetoothDevice connected) {
        if (!allowed() || gatt != null) return;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) return;
        try {
            Method method = method();
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            if (method != null && paired != null) {
                for (BluetoothDevice device : paired) {
                    if (!isRemote(device)) continue;
                    int reported = (Integer) method.invoke(device);
                    if (valid(reported)) {
                        store(reported);
                        return;
                    }
                }
            }
            BluetoothDevice target = connected;
            if (target == null) {
                for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT)) {
                    if (isRemote(device)) {
                        target = device;
                        break;
                    }
                }
            }
            if (target == null || target.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) return;
            gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt != null) main.postDelayed(giveUp, GATT_TIMEOUT_MS);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A SecurityException, a blocked hidden method or a Bluetooth stack that went away: keep the last level.
        }
    }

    // Callbacks arrive on a binder thread; every outcome goes back to the main thread through finish().
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) finish(g, -1);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                finish(g, -1);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService service = g.getService(BATTERY_SERVICE);
            BluetoothGattCharacteristic characteristic = service != null ? service.getCharacteristic(BATTERY_LEVEL) : null;
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || !g.readCharacteristic(characteristic)) {
                finish(g, -1);
            }
        }

        // Android 13 and later pass the value here.
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value,
                int status) {
            finish(g, status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0 ? value[0] & 0xFF : -1);
        }

        // Before Android 13 the value is on the characteristic.
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            onCharacteristicRead(g, characteristic, characteristic.getValue(), status);
        }
    };

    private void finish(BluetoothGatt g, int read) {
        main.post(() -> {
            if (gatt != g) return;
            closeGatt();
            store(read);
        });
    }

    @SuppressLint("MissingPermission")   // gatt only exists after allowed()
    private void closeGatt() {
        main.removeCallbacks(giveUp);
        if (gatt == null) return;
        try {
            gatt.close();
        } catch (RuntimeException e) {
            // The stack went away with the client; nothing is left open.
        }
        gatt = null;
    }

    /** Keeps a valid level and tells Home; anything else leaves the last one. */
    private void store(int read) {
        if (!valid(read) || read == level) return;
        level = read;
        prefs().edit().putInt(KEY_LEVEL, read).apply();
        onChange.run();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean valid(int level) {
        return level >= 0 && level <= 100;
    }

    /** Headphones and speakers report a battery too; anything paired that is not audio counts as a remote. */
    @SuppressLint("MissingPermission")   // reached only after allowed(), or from a broadcast that needs the permission
    private static boolean isRemote(BluetoothDevice device) {
        try {
            BluetoothClass type = device.getBluetoothClass();
            return type == null || type.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean allowed() {
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
