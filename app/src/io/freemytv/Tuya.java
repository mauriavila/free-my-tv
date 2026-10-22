package io.freemytv;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tuya's cloud API (the platform under LEDVANCE SMART+ WiFi, Smart Life and many other smart lights), signed with
 * the person's own cloud project keys. The keys live on the TV, never in the build:
 * adb push tuya_key.txt /sdcard/Android/data/io.freemytv/files/tuya_key.txt
 * One line, ACCESS_ID:ACCESS_SECRET, optionally :REGION (us = Western America, the default; ueaz, eu, weaz, in, cn).
 * Network: background threads only.
 */
final class Tuya {
    private final String id, secret, host;
    private String token;
    private long tokenUntil;

    private Tuya(String id, String secret, String host) {
        this.id = id;
        this.secret = secret;
        this.host = host;
    }

    private static Tuya instance;

    /** The client for the keys on this TV, or null without keys. */
    static synchronized Tuya get(Context c) {
        if (instance != null) return instance;
        String[] k = keys(c);
        if (k == null) return null;
        String region = k.length > 2 ? k[2].toLowerCase(Locale.ROOT) : "us";
        String host;
        switch (region) {
            case "eu": host = "openapi.tuyaeu.com"; break;
            case "weaz": host = "openapi-weaz.tuyaeu.com"; break;
            case "ueaz": host = "openapi-ueaz.tuyaus.com"; break;
            case "in": host = "openapi.tuyain.com"; break;
            case "cn": host = "openapi.tuyacn.com"; break;
            default: host = "openapi.tuyaus.com";
        }
        instance = new Tuya(k[0], k[1], host);
        return instance;
    }

    static boolean configured(Context c) {
        return keys(c) != null;
    }

    JSONObject getJson(String path) throws Exception {
        return call("GET", path, "");
    }

    JSONObject post(String path, String body) throws Exception {
        return call("POST", path, body);
    }

    private synchronized String token() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenUntil) return token;
        JSONObject o = request("GET", "/v1.0/token?grant_type=1", "", null);
        if (!o.optBoolean("success")) throw new java.io.IOException("Tuya: " + o.optString("msg", "no token"));
        JSONObject r = o.getJSONObject("result");
        token = r.getString("access_token");
        tokenUntil = System.currentTimeMillis() + (r.optLong("expire_time", 7200) - 120) * 1000;
        return token;
    }

    private JSONObject call(String method, String path, String body) throws Exception {
        JSONObject o = request(method, path, body, token());
        if (!o.optBoolean("success") && o.optInt("code") == 1010) {   // token expired early: once more with a new one
            synchronized (this) { token = null; }
            o = request(method, path, body, token());
        }
        if (!o.optBoolean("success")) throw new java.io.IOException("Tuya " + o.optInt("code") + ": " + o.optString("msg"));
        return o;
    }

    /** Tuya's signature: HMAC-SHA256 over id, token, time, nonce and method + body hash + path. */
    private JSONObject request(String method, String path, String body, String accessToken) throws Exception {
        String t = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String toSign = method + "\n" + sha256(body) + "\n\n" + path;
        String sign = hmac(id + (accessToken == null ? "" : accessToken) + t + nonce + toSign);
        HttpURLConnection c = (HttpURLConnection) new URL("https://" + host + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10_000);
        c.setRequestMethod(method);
        c.setRequestProperty("client_id", id);
        c.setRequestProperty("t", t);
        c.setRequestProperty("nonce", nonce);
        c.setRequestProperty("sign_method", "HMAC-SHA256");
        c.setRequestProperty("sign", sign);
        if (accessToken != null) c.setRequestProperty("access_token", accessToken);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            if (!body.isEmpty()) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes("UTF-8")); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new JSONObject(read(in));
        } finally {
            c.disconnect();
        }
    }

    private String hmac(String s) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        return hex(mac.doFinal(s.getBytes("UTF-8"))).toUpperCase(Locale.ROOT);
    }

    private static String sha256(String s) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static String[] keys(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return null;
            File f = new File(dir, "tuya_key.txt");
            if (!f.isFile() || f.length() > 4096) return null;
            String s;
            try (InputStream in = new FileInputStream(f)) { s = read(in); }
            String[] parts = s.trim().split("[:\\s]+");
            return parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty() ? parts : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "{}";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
