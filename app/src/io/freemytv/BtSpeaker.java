package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Fire TV: the "Speaker" shortcut in the drawer connects a paired Bluetooth speaker (or headphones) in one or two
 * presses. With one paired audio device it connects it, or disconnects it if it's connected; with several, a short
 * list shows them (the connected one marked) and the one picked is connected (the others first disconnected), or
 * disconnected if it was already on. Connecting goes through BluetoothA2dp's connect/disconnect, hidden but open to
 * apps up to Android 8 (Fire OS 6-7).
 */
final class BtSpeaker {
    private BtSpeaker() {}

    /** Only where the hidden A2DP calls are allowed to apps (and need no runtime permission). */
    static boolean supported() {
        return Ui.FIRE && Build.VERSION.SDK_INT < 28 && BluetoothAdapter.getDefaultAdapter() != null;
    }

    static void open(final Activity activity) {
        final Context c = activity.getApplicationContext();
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        if (!adapter.isEnabled()) {
            adapter.enable();
            toast(c, Ui.tr("Ligando o Bluetooth… aperte de novo em instantes", "Turning Bluetooth on… press again in a moment"));
            return;
        }
        final List<BluetoothDevice> speakers = speakers(adapter);
        if (speakers.isEmpty()) {
            toast(c, Ui.tr("Nenhuma caixa de som pareada. Pareie em Ajustes da TV › Bluetooth.",
                    "No paired speaker. Pair it in TV Settings › Bluetooth."));
            return;
        }
        adapter.getProfileProxy(c, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                final BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                if (speakers.size() == 1) {
                    toggle(c, adapter, a2dp, speakers, speakers.get(0));
                    return;
                }
                if (activity.isFinishing()) {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
                    return;
                }
                String[] names = new String[speakers.size()];
                for (int i = 0; i < names.length; i++) {
                    BluetoothDevice d = speakers.get(i);
                    names[i] = name(d) + (a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED
                            ? "  ·  " + Ui.tr("conectado", "connected") : "");
                }
                AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(Ui.tr("Caixa de som", "Speaker"))
                        .setItems(names, (d, which) -> toggle(c, adapter, a2dp, speakers, speakers.get(which)))
                        .setOnCancelListener(d -> adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp))
                        .create();
                dialog.show();
                // Focus starts on the one used last.
                int last = indexOf(speakers, Prefs.speaker());
                if (last > 0 && dialog.getListView() != null) dialog.getListView().setSelection(last);
            }

            @Override public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
    }

    /** Connects `device` (disconnecting the other speakers first), or disconnects it if it's already connected. */
    private static void toggle(Context c, BluetoothAdapter adapter, BluetoothA2dp a2dp, List<BluetoothDevice> all,
                               BluetoothDevice device) {
        String name = name(device);
        boolean connected = a2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
        if (!connected) {
            for (BluetoothDevice other : all) {
                if (other != device && a2dp.getConnectionState(other) == BluetoothProfile.STATE_CONNECTED) {
                    call(a2dp, "disconnect", other);
                }
            }
        }
        boolean ok = call(a2dp, connected ? "disconnect" : "connect", device);
        if (ok) Prefs.setSpeaker(device.getAddress());
        toast(c, !ok ? Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + name
                : connected ? Ui.tr("Desconectado: ", "Disconnected: ") + name
                : Ui.tr("Conectando a ", "Connecting to ") + name + "…");
        adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
    }

    /** Paired audio devices (speakers, headphones), the one used last first. */
    private static List<BluetoothDevice> speakers(BluetoothAdapter adapter) {
        List<BluetoothDevice> out = new ArrayList<>();
        String last = Prefs.speaker();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            BluetoothClass cls = d.getBluetoothClass();
            if (cls == null || cls.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
            if (d.getAddress().equals(last)) out.add(0, d); else out.add(d);
        }
        return out;
    }

    private static int indexOf(List<BluetoothDevice> list, String address) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).getAddress().equals(address)) return i;
        return -1;
    }

    private static String name(BluetoothDevice d) {
        return d.getName() != null ? d.getName() : d.getAddress();
    }

    private static boolean call(BluetoothA2dp a2dp, String method, BluetoothDevice device) {
        try {
            Method m = BluetoothA2dp.class.getMethod(method, BluetoothDevice.class);
            Object r = m.invoke(a2dp, device);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Exception | LinkageError e) {
            AppLog.w("speaker " + method + ": " + e);
            return false;
        }
    }

    private static void toast(final Context c, final String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c, text, Toast.LENGTH_LONG).show());
    }
}
