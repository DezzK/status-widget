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

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * Wi-Fi status icon. Owns the connectivity callback and the periodic reachability probe that
 * tells a captive/whitelisted network apart from a working one — the system only reports whether
 * a network CLAIMS internet capability.
 */
final class WifiRenderBrick extends IconRenderBrick {

    private static final String TAG = "WifiRenderBrick";
    private static final long PROBE_INTERVAL_MS = 30_000L;

    enum State {
        OFF, NO_INTERNET, LIMITED_INTERNET, INTERNET
    }

    private static final int[][] DESIGNS = {
            {   // classic
                    R.drawable.ic_status_wifi_off,
                    R.drawable.ic_status_wifi_no_internet,
                    R.drawable.ic_status_wifi_whitelist,
                    R.drawable.ic_status_wifi_internet
            },
            {   // solid
                    R.drawable.ic_status_filled_wifi_off,
                    R.drawable.ic_status_filled_wifi_no_internet,
                    R.drawable.ic_status_filled_wifi_whitelist,
                    R.drawable.ic_status_filled_wifi_internet
            },
            {   // bars
                    R.drawable.ic_status_bars_wifi_off,
                    R.drawable.ic_status_bars_wifi_no_internet,
                    R.drawable.ic_status_bars_wifi_whitelist,
                    R.drawable.ic_status_bars_wifi_internet
            }
    };

    private static final int[] STATE_COLORS = {
            R.color.status_off,
            R.color.status_error,
            R.color.status_warning,
            R.color.status_ok
    };

    private State state = State.OFF;
    @Nullable
    private ConnectivityManager connectivityManager;
    @Nullable
    private ReachabilityChecker reachabilityChecker;

    WifiRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.WIFI, host.prefs().wifi);
    }

    @NonNull
    @Override
    protected OutlineImageView iconOf(@NonNull OverlayStatusWidgetBinding binding) {
        return binding.wifiStatusIcon;
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

    /** Whitelist (Russian-only internet): a small flag badge, regardless of icon style. */
    @Nullable
    @Override
    protected Drawable badgeDrawable(int stateIdx) {
        if (stateIdx != State.LIMITED_INTERNET.ordinal()) return null;
        Drawable flag = ContextCompat.getDrawable(host.context(), R.drawable.ic_badge_ru_flag);
        // mutate() so setBounds() cannot reach a shared cached instance.
        return flag != null ? flag.mutate() : null;
    }

    @Override
    void syncSource(boolean active) {
        if (active) {
            if (connectivityManager == null) {
                connectivityManager = host.context().getSystemService(ConnectivityManager.class);

                // Initial state: assume "no internet" until the async probe determines whether the
                // connection is full / whitelisted / broken.
                boolean wifiPresent = false;
                for (Network net : connectivityManager.getAllNetworks()) {
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        setState(State.NO_INTERNET);
                        wifiPresent = true;
                        break;
                    }
                }

                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
                // Deliver callbacks on the main thread: they touch the overlay views and the
                // themed context, neither of which may be read from ConnectivityThread.
                connectivityManager.registerNetworkCallback(request, networkCallback, host.handler());

                if (wifiPresent) {
                    probeReachability();
                }
                host.handler().postDelayed(probeRunnable, PROBE_INTERVAL_MS);
            }
            refreshContent();
        } else if (connectivityManager != null) {
            host.handler().removeCallbacks(probeRunnable);
            connectivityManager.unregisterNetworkCallback(networkCallback);
            connectivityManager = null;
        }
    }

    @Override
    void onDestroy() {
        host.handler().removeCallbacks(probeRunnable);
        if (connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignored) {
            }
            connectivityManager = null;
        }
    }

    private void setState(State newState) {
        if (state == newState) return;
        state = newState;
        refreshContent();
    }

    private void probeReachability() {
        if (reachabilityChecker == null) {
            reachabilityChecker = new ReachabilityChecker(host.handler());
        }
        reachabilityChecker.check(reach -> {
            if (state == State.OFF) return;
            switch (reach) {
                case FULL -> setState(State.INTERNET);
                case WHITELIST -> setState(State.LIMITED_INTERNET);
                case NONE -> setState(State.NO_INTERNET);
            }
        });
    }

    private final Runnable probeRunnable = new Runnable() {
        @Override
        public void run() {
            if (state != State.OFF) {
                probeReachability();
            }
            host.handler().postDelayed(this, PROBE_INTERVAL_MS);
        }
    };

    @SuppressLint("MissingPermission")
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    Log.d(TAG, "Wi-Fi is connected");
                    if (state == State.OFF) {
                        setState(State.NO_INTERNET);
                    }
                    host.handler().post(() -> probeReachability());
                }

                @Override
                public void onLost(@NonNull Network network) {
                    Log.d(TAG, "Wi-Fi is lost");
                    setState(State.OFF);
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                  NetworkCapabilities capabilities) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        boolean hasInternet = capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        Log.d(TAG, "Wi-Fi capabilities changed, has internet = " + hasInternet);
                        if (hasInternet) {
                            // The network claims internet — probe to tell FULL / WHITELIST / NONE
                            // apart.
                            host.handler().post(() -> probeReachability());
                        } else {
                            setState(State.NO_INTERNET);
                        }
                    } else {
                        setState(State.OFF);
                    }
                }
            };
}
