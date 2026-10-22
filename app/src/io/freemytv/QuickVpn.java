package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.widget.Toast;

/**
 * Fire TV: the "VPN" shortcut in the drawer. Off, it asks NordVPN to connect (its own nordvpn://connect link, the
 * quick connect of its TV app); on, it opens NordVPN to disconnect there (the app has no link for that).
 */
final class QuickVpn {
    private QuickVpn() {}

    static final String NORD = "com.nordvpn.android";

    static boolean supported(Context c) {
        return Ui.FIRE && HomeApps.launchIntent(c.getPackageManager(), NORD) != null;
    }

    static void open(Activity a) {
        boolean on = vpnActive(a);
        Intent i = on ? HomeApps.launchIntent(a.getPackageManager(), NORD)
                : new Intent(Intent.ACTION_VIEW, Uri.parse("nordvpn://connect")).setPackage(NORD);
        if (i == null) return;
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(a, on ? Ui.tr("VPN ligada: desconecte no NordVPN", "VPN is on: disconnect it in NordVPN")
                    : Ui.tr("Conectando a VPN…", "Connecting the VPN…"), Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("vpn: " + e.getMessage());
            Toast.makeText(a, Ui.tr("Não deu para abrir o NordVPN", "Couldn't open NordVPN"), Toast.LENGTH_LONG).show();
        }
    }

    /** A VPN is up (any app's). */
    static boolean vpnActive(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }
}
