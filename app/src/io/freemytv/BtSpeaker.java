package io.freemytv;

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

/**
 * Fire TV: the "Speaker" shortcut in the drawer connects the paired Bluetooth speaker (or headphones) in one press,
 * or disconnects it if it's connected. The speaker is the one used last from here, else the first paired audio device.
 * Connecting goes through BluetoothA2dp's connect/disconnect, hidden but open to apps up to Android 8 (Fire OS 6-7).
 */
final class BtSpeaker {
    private BtSpeaker() {}

    /** Only where the hidden A2DP calls are allowed to apps (and needs no runtime permission). */
    static boolean supported() {
        return Ui.FIRE && Build.VERSION.SDK_INT < 28 && BluetoothAdapter.getDefaultAdapter() != null;
    }

    static void toggle(Context context) {
        final Context c = context.getApplicationContext();
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        if (!adapter.isEnabled()) {
            adapter.enable();
            toast(c, Ui.tr("Ligando o Bluetooth… aperte de novo em instantes", "Turning Bluetooth on… press again in a moment"));
            return;
        }
        final BluetoothDevice device = pick(c, adapter);
        if (device == null) {
            toast(c, Ui.tr("Nenhuma caixa de som pareada. Pareie em Ajustes da TV › Bluetooth.",
                    "No paired speaker. Pair it in TV Settings › Bluetooth."));
            return;
        }
        adapter.getProfileProxy(c, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                String name = device.getName() != null ? device.getName() : device.getAddress();
                boolean connected = a2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
                boolean ok = call(a2dp, connected ? "disconnect" : "connect", device);
                if (ok) Prefs.setSpeaker(device.getAddress());
                toast(c, !ok ? Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + name
                        : connected ? Ui.tr("Desconectado: ", "Disconnected: ") + name
                        : Ui.tr("Conectando a ", "Connecting to ") + name + "…");
                adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
            }

            @Override public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
    }

    /** The speaker used last from here if still paired, else the first paired audio device. */
    private static BluetoothDevice pick(Context c, BluetoothAdapter adapter) {
        String last = Prefs.speaker();
        BluetoothDevice first = null;
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            BluetoothClass cls = d.getBluetoothClass();
            if (cls == null || cls.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
            if (d.getAddress().equals(last)) return d;
            if (first == null) first = d;
        }
        return first;
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
