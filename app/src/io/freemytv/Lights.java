package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The room's smart lights through Tuya's cloud (see Tuya): on, off, movie, warm, cool, and the karaoke's party mode,
 * which puts back exactly how they were afterwards. Tuya's free cloud plan allows about 26,000 calls a month, so the
 * party is one color program per bulb (scene_data_v2, the bulb runs it by itself), never a stream of commands:
 * a whole karaoke costs about a dozen calls. Standard Tuya light codes: v2 (0-1000) and the older ones (0-255).
 */
final class Lights {
    private Lights() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Bulb> BULBS = new ArrayList<>();
    /** Whether the last look turned them on (the toggle), without asking the cloud. */
    private static volatile boolean on = true;
    private static boolean partying;   // only touched on IO

    static final class Bulb {
        final String id, name;
        boolean v2 = true;
        JSONArray saved;   // its status before the party
        Bulb(String id, String name) { this.id = id; this.name = name; }
    }

    static boolean configured(Context c) {
        return Tuya.configured(c);
    }

    private interface Job {
        void run(Tuya t, List<Bulb> bulbs) throws Exception;
    }

    /** Runs `job` with the bulbs (looked up once per process), on the lights' own thread. */
    private static void withBulbs(Context c, final Job job) {
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            Tuya t = Tuya.get(app);
            if (t == null) return;
            try {
                if (BULBS.isEmpty()) load(t);
                job.run(t, BULBS);
            } catch (Exception e) {
                AppLog.w("lights: " + e.getMessage());
                toast(app, Ui.tr("Luzes: ", "Lights: ", "Luces: ") + e.getMessage());
            }
        });
    }

    private static void load(Tuya t) throws Exception {
        JSONArray list = null;
        try {
            list = t.getJson("/v2.0/cloud/thing/device?page_size=20").optJSONArray("result");
        } catch (Exception e) {
            AppLog.w("lights: device list v2: " + e.getMessage());
        }
        if (list == null) {
            JSONObject r = t.getJson("/v1.0/iot-01/associated-users/devices?size=20").optJSONObject("result");
            list = r == null ? null : r.optJSONArray("devices");
        }
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject d = list.getJSONObject(i);
            String category = d.optString("category", "");
            // Lights and light strips only (dj bulbs, dd/fwd strips, xdd ceiling, dc string lights).
            if (!category.isEmpty() && !category.matches("dj|dd|xdd|fwd|dc|tgq|tgkg")) continue;
            String name = d.optString("customName", "");
            if (name.isEmpty()) name = d.optString("name", d.optString("id"));
            BULBS.add(new Bulb(d.getString("id"), name));
        }
        AppLog.w("lights: " + BULBS.size() + " found");
        if (BULBS.isEmpty()) throw new java.io.IOException(Ui.tr("nenhuma luz na nuvem da Tuya", "no lights in the Tuya cloud", "no hay luces en la nube de Tuya"));
    }

    private static void send(Tuya t, Bulb b, Object... codeValues) throws Exception {
        JSONArray cmds = new JSONArray();
        for (int i = 0; i + 1 < codeValues.length; i += 2) {
            cmds.put(new JSONObject().put("code", codeValues[i]).put("value", codeValues[i + 1]));
        }
        t.post("/v1.0/iot-03/devices/" + b.id + "/commands", new JSONObject().put("commands", cmds).toString());
    }

    private interface Command {
        Object[] of(Bulb b) throws Exception;
    }

    /** Sends to every bulb; a bulb that turns down the v2 codes gets the older ones once (and keeps them). */
    private static void each(Tuya t, List<Bulb> bulbs, Command cmd) throws Exception {
        Exception last = null;
        int ok = 0;
        for (Bulb b : bulbs) {
            try {
                send(t, b, cmd.of(b));
                ok++;
            } catch (Exception e) {
                if (b.v2 && String.valueOf(e.getMessage()).contains("2008")) {   // "command or value not supported"
                    b.v2 = false;
                    try { send(t, b, cmd.of(b)); ok++; continue; } catch (Exception e2) { e = e2; }
                }
                AppLog.w("lights " + b.name + ": " + e.getMessage());
                last = e;
            }
        }
        if (ok == 0 && last != null) throw last;
    }

    // MARK: - Looks

    static void power(Context c, final boolean turnOn) {
        on = turnOn;
        withBulbs(c, (t, bulbs) -> { endParty(); each(t, bulbs, b -> new Object[] {"switch_led", turnOn}); });
    }

    static void toggle(Context c) {
        power(c, !on);
    }

    /** White light: temperature 0 (warm) to 1 (cool), brightness 0..1. */
    static void white(Context c, final float temp, final float bright) {
        on = true;
        withBulbs(c, (t, bulbs) -> {
            endParty();
            each(t, bulbs, b -> {
                int max = b.v2 ? 1000 : 255, min = b.v2 ? 10 : 25;
                return new Object[] {"switch_led", true, "work_mode", "white",
                        b.v2 ? "bright_value_v2" : "bright_value", Math.max(min, Math.round(bright * max)),
                        b.v2 ? "temp_value_v2" : "temp_value", Math.round(temp * max)};
            });
        });
    }

    /** Movie: very low, warm. */
    static void movie(Context c) {
        white(c, 0f, 0.05f);
    }

    /** One color for all: hue 0..360, saturation and brightness 0..1. */
    static void colour(Context c, final int hue, final float sat, final float val) {
        on = true;
        withBulbs(c, (t, bulbs) -> {
            endParty();
            each(t, bulbs, b -> new Object[] {"switch_led", true, "work_mode", "colour", colourCode(b), colourValue(b, hue, sat, val)});
        });
    }

    private static String colourCode(Bulb b) {
        return b.v2 ? "colour_data_v2" : "colour_data";
    }

    private static JSONObject colourValue(Bulb b, int hue, float sat, float val) throws Exception {
        int max = b.v2 ? 1000 : 255;
        return new JSONObject().put("h", ((hue % 360) + 360) % 360).put("s", Math.round(sat * max)).put("v", Math.max(1, Math.round(val * max)));
    }

    // MARK: - Party

    private static final int[] PARTY_HUES = {300, 200, 330, 120, 260, 30};   // magenta, cyan, pink, green, violet, orange

    /** Saves how every bulb is, then each one runs the party colors on its own, out of step with the others. */
    static void partyStart(Context c) {
        if (!configured(c)) return;
        withBulbs(c, (t, bulbs) -> {
            if (partying) return;
            partying = true;
            on = true;
            for (Bulb b : bulbs) {
                try {
                    b.saved = t.getJson("/v1.0/iot-03/devices/" + b.id + "/status").optJSONArray("result");
                    b.v2 = has(b.saved, "colour_data_v2") || has(b.saved, "bright_value_v2") || !has(b.saved, "colour_data");
                } catch (Exception e) {
                    b.saved = null;
                }
            }
            for (int i = 0; i < bulbs.size(); i++) {
                Bulb b = bulbs.get(i);
                try {
                    if (b.saved == null || has(b.saved, "scene_data_v2")) {
                        send(t, b, "switch_led", true, "work_mode", "scene", "scene_data_v2", scene(i));
                    } else {   // no color programs: a fixed color each
                        send(t, b, "switch_led", true, "work_mode", "colour", colourCode(b), colourValue(b, PARTY_HUES[i % PARTY_HUES.length], 1, 1));
                    }
                } catch (Exception e) {
                    AppLog.w("lights party " + b.name + ": " + e.getMessage());
                    try {
                        send(t, b, "switch_led", true, "work_mode", "colour", colourCode(b), colourValue(b, PARTY_HUES[i % PARTY_HUES.length], 1, 1));
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /** Six saturated colors fading into each other, starting at a different one on each bulb. */
    private static JSONObject scene(int bulb) throws Exception {
        JSONArray units = new JSONArray();
        for (int j = 0; j < PARTY_HUES.length; j++) {
            units.put(new JSONObject()
                    .put("unit_change_mode", "gradient")
                    .put("unit_switch_duration", 8)
                    .put("unit_gradient_duration", 8)
                    .put("h", PARTY_HUES[(j + bulb * 2) % PARTY_HUES.length])
                    .put("s", 1000).put("v", 1000).put("bright", 0).put("temperature", 0));
        }
        return new JSONObject().put("scene_num", 8).put("scene_units", units);
    }

    private static boolean has(JSONArray status, String code) {
        for (int i = 0; status != null && i < status.length(); i++) {
            if (code.equals(status.optJSONObject(i).optString("code"))) return true;
        }
        return false;
    }

    /** Ends the party and puts every bulb back as it was before it. */
    static void partyStop(Context c) {
        if (!configured(c)) return;
        withBulbs(c, (t, bulbs) -> {
            if (!partying) return;
            partying = false;
            for (Bulb b : bulbs) {
                if (b.saved == null) continue;
                try {
                    send(t, b, restore(b));
                } catch (Exception e) {
                    AppLog.w("lights restore " + b.name + ": " + e.getMessage());
                }
                b.saved = null;
            }
        });
    }

    /** A look chosen during the party replaces it (nothing to put back). */
    private static void endParty() {
        if (!partying) return;
        partying = false;
        for (Bulb b : BULBS) b.saved = null;
    }

    private static Object[] restore(Bulb b) throws Exception {
        Map<String, Object> s = new HashMap<>();
        for (int i = 0; i < b.saved.length(); i++) {
            JSONObject o = b.saved.getJSONObject(i);
            s.put(o.getString("code"), o.opt("value"));
        }
        List<Object> cmd = new ArrayList<>();
        String mode = String.valueOf(s.get("work_mode"));
        if (s.containsKey("work_mode")) { cmd.add("work_mode"); cmd.add(mode); }
        String[] keep = "colour".equals(mode) ? new String[] {"colour_data_v2", "colour_data"}
                : "scene".equals(mode) ? new String[] {"scene_data_v2"}
                : new String[] {"bright_value_v2", "temp_value_v2", "bright_value", "temp_value"};
        for (String k : keep) {
            if (!s.containsKey(k)) continue;
            Object v = s.get(k);
            if (v instanceof String && ((String) v).startsWith("{")) v = new JSONObject((String) v);
            cmd.add(k);
            cmd.add(v);
        }
        if (s.containsKey("switch_led")) { cmd.add("switch_led"); cmd.add(s.get("switch_led")); }
        return cmd.toArray();
    }

    // MARK: - The menu

    /** The drawer's Lights: a short list, focus on the first. */
    static void open(final Activity a) {
        if (!configured(a)) {
            toast(a, Ui.tr("Falta a chave da Tuya: tuya_key.txt (veja o README)", "Missing the Tuya key: tuya_key.txt (see the README)",
                    "Falta la clave de Tuya: tuya_key.txt (mirá el README)"));
            return;
        }
        final String[] names = {
                Ui.tr("Ligar", "Turn on", "Encender"),
                Ui.tr("Desligar", "Turn off", "Apagar"),
                Ui.tr("Modo cinema", "Movie mode", "Modo película"),
                Ui.tr("Luz quente", "Warm light", "Luz cálida"),
                Ui.tr("Luz fria", "Cool light", "Luz fría"),
                Ui.tr("Festa", "Party", "Fiesta"),
        };
        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Luzes", "Lights", "Luces"))
                .setItems(names, (dialog, which) -> {
                    switch (which) {
                        case 0: power(a, true); break;
                        case 1: power(a, false); break;
                        case 2: movie(a); break;
                        case 3: white(a, 0f, 0.8f); break;
                        case 4: white(a, 1f, 1f); break;
                        default: partyStart(a);
                    }
                    toast(a, names[which]);
                })
                .create();
        d.show();
        if (d.getListView() != null) d.getListView().setSelection(on ? 1 : 0);
    }

    private static void toast(final Context c, final String text) {
        MAIN.post(() -> Toast.makeText(c.getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }
}
