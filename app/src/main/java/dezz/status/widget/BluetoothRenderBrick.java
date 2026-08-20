/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package dezz.status.widget;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * Bluetooth status icon. Owns the connected-device set, which the platform will not hand over in
 * one call: it is seeded from bonded devices, refreshed from profile proxies, and kept current by
 * ACL broadcasts.
 */
final class BluetoothRenderBrick extends IconRenderBrick {

    private static final String TAG = "BluetoothRenderBrick";

    enum State {
        OFF, NO_DEVICE, CONNECTED
    }

    private static final int[][] DESIGNS = {
            { R.drawable.ic_status_bt_off, R.drawable.ic_status_bt_no_device, R.drawable.ic_status_bt_connected },
            { R.drawable.ic_status_filled_bt_off, R.drawable.ic_status_filled_bt_no_device, R.drawable.ic_status_filled_bt_connected },
            { R.drawable.ic_status_bars_bt_off, R.drawable.ic_status_bars_bt_no_device, R.drawable.ic_status_bars_bt_connected }
    };

    private static final int[] STATE_COLORS = {
            R.color.status_off,
            R.color.status_off,
            R.color.status_bluetooth
    };

    private final Preferences.BluetoothBrickPrefs btPrefs;

    private State state = State.OFF;
    private final Set<String> connected = new HashSet<>();
    private boolean receiverRegistered = false;

    BluetoothRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.BLUETOOTH, host.prefs().bluetooth);
        this.btPrefs = host.prefs().bluetooth;
    }

    @NonNull
    @Override
    protected OutlineImageView iconOf(@NonNull OverlayStatusWidgetBinding binding) {
        return binding.bluetoothStatusIcon;
    }

    @NonNull
    @Override
    protected int[][] designs() {
        return DESIGNS;
    }

    @NonNull
    @Override
    protected int[] stateColors() {
        return STATE_COLORS;
    }

    @Override
    protected int stateOrdinal() {
        return state.ordinal();
    }

    @Nullable
    @Override
    protected IconRenderBrick.TextBadge textBadge(int stateIdx, int styleBg) {
        if (!btPrefs.showDeviceCountBadge.get() || state != State.CONNECTED || connected.isEmpty()) {
            return null;
        }
        return new TextBadge(String.valueOf(connected.size()), styleBg, defaultBadgeForeground());
    }

    /**
     * Recomputes the state from the adapter before repainting. Unlike the other two icons the
     * Bluetooth state is not pushed by a callback alone — the adapter can be switched off without
     * an ACL broadcast, so every repaint re-reads it.
     */
    @Override
    void refreshContent() {
        BluetoothAdapter adapter = getAdapter();
        boolean enabled;
        try {
            enabled = adapter != null && adapter.isEnabled();
        } catch (Throwable t) {
            enabled = false;
        }
        if (!enabled) {
            state = State.OFF;
            connected.clear();
        } else if (connected.isEmpty()) {
            state = State.NO_DEVICE;
        } else {
            state = State.CONNECTED;
        }
        super.refreshContent();
    }

    @Override
    void syncSource(boolean active) {
        if (active) {
            registerReceiver();
            refreshFromProfileProxies();
        } else {
            unregisterReceiver();
            connected.clear();
        }
        refreshContent();
    }

    @Override
    void onDestroy() {
        unregisterReceiver();
    }

    @Nullable
    private static BluetoothAdapter getAdapter() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            host.context().registerReceiver(receiver, filter);
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register Bluetooth receiver", t);
        }
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        try {
            host.context().unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiverRegistered = false;
    }

    /**
     * Seed the connected set from whatever the system can tell us synchronously, with an async
     * profile-proxy refresh on top.
     *
     * <p>The synchronous path reflects on the hidden {@code BluetoothDevice.isConnected()} over
     * bonded devices — it works on AOSP and the car-HU ROMs derived from it, returns instantly,
     * and crucially covers the "brick was just added, Bluetooth is already on and the device is
     * paired" case that pure profile-proxy seeding misses. The async path keeps querying HEADSET /
     * A2DP proxies as a safety net for ROMs where the reflection trick is unavailable, and for
     * unbonded but momentarily connected devices. ACL broadcasts handle live updates afterwards.
     */
    private void refreshFromProfileProxies() {
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null) return;
        try {
            if (!adapter.isEnabled()) {
                connected.clear();
                return;
            }
        } catch (Throwable t) {
            return;
        }

        seedFromBondedDevices(adapter);

        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    for (BluetoothDevice d : proxy.getConnectedDevices()) {
                        if (d != null && d.getAddress() != null) {
                            connected.add(d.getAddress());
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    adapter.closeProfileProxy(profile, proxy);
                } catch (Throwable ignored) {
                }
                refreshContent();
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        };
        try {
            adapter.getProfileProxy(host.context(), listener, BluetoothProfile.HEADSET);
            adapter.getProfileProxy(host.context(), listener, BluetoothProfile.A2DP);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to query Bluetooth profile proxies", t);
        }
    }

    /**
     * Populate the connected set from bonded devices via the hidden
     * {@code BluetoothDevice.isConnected()}. Safe to repeat — the set is a union, so a stale entry
     * is only cleared by an ACL_DISCONNECTED broadcast or a full Bluetooth-off transition.
     */
    private void seedFromBondedDevices(BluetoothAdapter adapter) {
        java.lang.reflect.Method isConnected;
        try {
            isConnected = BluetoothDevice.class.getMethod("isConnected");
        } catch (Throwable t) {
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (Throwable t) {
            return;
        }
        if (bonded == null) return;
        for (BluetoothDevice device : bonded) {
            if (device == null || device.getAddress() == null) continue;
            try {
                Object result = isConnected.invoke(device);
                if (result instanceof Boolean && (Boolean) result) {
                    connected.add(device.getAddress());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    connected.clear();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    refreshFromProfileProxies();
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getAddress() != null) {
                    connected.add(device.getAddress());
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getAddress() != null) {
                    connected.remove(device.getAddress());
                }
            }
            refreshContent();
        }
    };
}
