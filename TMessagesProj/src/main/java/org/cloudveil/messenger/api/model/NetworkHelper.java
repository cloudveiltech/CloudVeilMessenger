package org.cloudveil.messenger.api.model;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import androidx.annotation.NonNull;

import org.telegram.messenger.FileLog;

import io.sentry.Scope;

public class NetworkHelper {

    public static boolean hasAnyInternetConnection(@NonNull Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network[] networks = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            networks = connectivityManager.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
                if(networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    FileLog.d("hasAnyInternetConnection => true");
                    return true;
                }
            }
        }
        FileLog.d("hasAnyInternetConnection => false");
        return false;
    }

    public static void addNetworkDataToSentry(@NonNull Context context, @NonNull Scope scope) {
        ConnectivityManager connectivityManager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network[] networks = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            networks = connectivityManager.getAllNetworks();
            for (Network network : networks) {
                NetworkInfo networkInfo = connectivityManager.getNetworkInfo(network);
                if (networkInfo != null && networkInfo.isConnectedOrConnecting()) {
                    NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
                    if(networkCapabilities != null) {
                        scope.setExtra("VPN", networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) + "");
                        FileLog.e("CloudVeilSyncWorker VPN: " + networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                    }

                    LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                    if (linkProperties != null) {
                        scope.setExtra("DNS", "dns = " + linkProperties.getDnsServers());
                        scope.setExtra("PROXY", "" + linkProperties.getHttpProxy());
                    }
                    FileLog.e("CloudVeilSyncWorker: " + "dns=" + linkProperties.getDnsServers());
                }
            }
        }
    }
}
