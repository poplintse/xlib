package com.xlib.txtreader;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

final class ConnectivityMonitor {
    interface Listener {
        void onConnectivityChanged(boolean available);
    }

    private final ConnectivityManager manager;
    private final Listener listener;
    private boolean started;
    private Boolean lastAvailable;

    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    publish(queryAvailable());
                }

                @Override public void onLost(Network network) {
                    publish(queryAvailable());
                }

                @Override public void onCapabilitiesChanged(Network network,
                                                             NetworkCapabilities capabilities) {
                    publish(queryAvailable());
                }
            };

    ConnectivityMonitor(Context context, Listener listener) {
        manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    synchronized void start() {
        if (started || manager == null) return;
        started = true;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        manager.registerNetworkCallback(request, callback);
        publish(queryAvailable());
    }

    synchronized void stop() {
        if (!started || manager == null) return;
        started = false;
        try {
            manager.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered by the platform.
        }
    }

    synchronized boolean isAvailable() {
        return lastAvailable == null ? queryAvailable() : lastAvailable;
    }

    private synchronized void publish(boolean available) {
        if (lastAvailable != null && lastAvailable == available) return;
        lastAvailable = available;
        listener.onConnectivityChanged(available);
    }

    private boolean queryAvailable() {
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null
                : manager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
