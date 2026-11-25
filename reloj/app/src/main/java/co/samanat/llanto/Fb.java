package co.samanat.llanto;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Firebase por REST (sin librerías): inicio de sesión anónimo y Firestore.
 * Usa el mismo proyecto "app-llanto" que la app del celular; la seguridad la dan las reglas de Firestore.
 */
public class Fb {
    static final String KEY = "AIzaSyBX5ugBzv-li6Vb_hai7NkhGsr-Krrj7U0";
    static final String FS = "https://firestore.googleapis.com/v1/projects/app-llanto/databases/(default)/documents";
    static final String REFERER = "https://samanat2026.github.io/";

    static class HttpError extends IOException {
        final int code;
        HttpError(int code, String body) { super("HTTP " + code + ": " + body); this.code = code; }
    }

    private final SharedPreferences sp;
    private String idToken;
    private long expires;

    Fb(SharedPreferences sp) { this.sp = sp; }

    String uid() { return sp.getString("uid", null); }

    /* ---------- HTTP ---------- */
    private String req(String method, String url, String body, String contentType, boolean auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        // HttpURLConnection no soporta PATCH: las APIs de Google aceptan este encabezado
        if ("PATCH".equals(method)) { c.setRequestMethod("POST"); c.setRequestProperty("X-HTTP-Method-Override", "PATCH"); }
        else c.setRequestMethod(method);
        c.setRequestProperty("Referer", REFERER);
        if (auth) c.setRequestProperty("Authorization", "Bearer " + idToken);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = "";
        if (in != null) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            txt = b.toString("UTF-8");
        }
        c.disconnect();
        if (code >= 400) throw new HttpError(code, txt);
        return txt;
    }

    private String json(String method, String url, JSONObject body) throws IOException {
        return req(method, url, body == null ? null : body.toString(), "application/json", true);
    }

    /* ---------- Sesión anónima ---------- */
    /** Devuelve true si se creó un usuario nuevo (hay que registrarlo otra vez como miembro). */
    synchronized boolean ensureAuth() throws Exception {
        if (idToken != null && System.currentTimeMillis() < expires - 60000) return false;
        String rt = sp.getString("refresh", null);
        if (rt != null) {
            try {
                JSONObject r = new JSONObject(req("POST", "https://securetoken.googleapis.com/v1/token?key=" + KEY,
                        "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8"),
                        "application/x-www-form-urlencoded", false));
                idToken = r.getString("id_token");
                expires = System.currentTimeMillis() + Long.parseLong(r.optString("expires_in", "3600")) * 1000;
                sp.edit().putString("refresh", r.getString("refresh_token")).putString("uid", r.getString("user_id")).apply();
                return false;
            } catch (HttpError e) {
                if (e.code != 400) throw e;   // 400 = sesión vencida: se crea otra
            }
        }
        JSONObject r = new JSONObject(req("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + KEY,
                "{\"returnSecureToken\":true}", "application/json", false));
        idToken = r.getString("idToken");
        expires = System.currentTimeMillis() + Long.parseLong(r.optString("expiresIn", "3600")) * 1000;
        sp.edit().putString("refresh", r.getString("refreshToken")).putString("uid", r.getString("localId")).apply();
        return true;
    }

    /* ---------- Firestore ---------- */
    String path(String fam, String col, String id) { return FS + "/families/" + fam + "/" + col + "/" + id; }

    /** Crea o reemplaza el documento completo (igual que setDoc en la app del celular). */
    void set(String fam, String col, JSONObject o) throws Exception {
        JSONObject body = new JSONObject().put("fields", encFields(o));
        json("PATCH", path(fam, col, o.getString("id")), body);
    }

    void del(String fam, String col, String id) throws Exception {
        json("DELETE", path(fam, col, id), null);
    }

    JSONObject get(String fam, String col, String id) throws Exception {
        try {
            JSONObject d = new JSONObject(json("GET", path(fam, col, id), null));
            return decFields(d.optJSONObject("fields"));
        } catch (HttpError e) {
            if (e.code == 404) return null;
            throw e;
        }
    }

    /** ¿Existe la familia? (la crea la administradora; si no existe, el código está mal). */
    boolean familyExists(String fam) throws Exception {
        try { json("GET", FS + "/families/" + fam, null); return true; }
        catch (HttpError e) { if (e.code == 404) return false; throw e; }
    }

    void member(String fam, String name) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject f = new JSONObject().put("name", name).put("t", now).put("seen", now).put("on", false).put("device", "reloj");
        String mask = "updateMask.fieldPaths=name&updateMask.fieldPaths=t&updateMask.fieldPaths=seen&updateMask.fieldPaths=on&updateMask.fieldPaths=device";
        json("PATCH", FS + "/families/" + fam + "/members/" + uid() + "?" + mask, new JSONObject().put("fields", encFields(f)));
    }

    List<JSONObject> eventsSince(String fam, long since) throws Exception {
        JSONObject q = new JSONObject().put("structuredQuery", new JSONObject()
                .put("from", new JSONArray().put(new JSONObject().put("collectionId", "events")))
                .put("where", new JSONObject().put("fieldFilter", new JSONObject()
                        .put("field", new JSONObject().put("fieldPath", "t"))
                        .put("op", "GREATER_THAN_OR_EQUAL")
                        .put("value", new JSONObject().put("integerValue", String.valueOf(since))))));
        JSONArray r = new JSONArray(json("POST", FS + "/families/" + fam + ":runQuery", q));
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject d = r.getJSONObject(i).optJSONObject("document");
            if (d != null) out.add(decFields(d.optJSONObject("fields")));
        }
        return out;
    }

    /* ---------- Conversión JSON <-> valores de Firestore ---------- */
    static JSONObject encFields(JSONObject o) throws Exception {
        JSONObject f = new JSONObject();
        Iterator<String> it = o.keys();
        while (it.hasNext()) { String k = it.next(); f.put(k, enc(o.get(k))); }
        return f;
    }

    static JSONObject enc(Object v) throws Exception {
        if (v == null || v == JSONObject.NULL) return new JSONObject().put("nullValue", JSONObject.NULL);
        if (v instanceof Boolean) return new JSONObject().put("booleanValue", v);
        if (v instanceof Integer || v instanceof Long) return new JSONObject().put("integerValue", String.valueOf(((Number) v).longValue()));
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 9e15) return new JSONObject().put("integerValue", String.valueOf((long) d));
            return new JSONObject().put("doubleValue", d);
        }
        if (v instanceof JSONObject) return new JSONObject().put("mapValue", new JSONObject().put("fields", encFields((JSONObject) v)));
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v, vals = new JSONArray();
            for (int i = 0; i < a.length(); i++) vals.put(enc(a.get(i)));
            return new JSONObject().put("arrayValue", new JSONObject().put("values", vals));
        }
        return new JSONObject().put("stringValue", String.valueOf(v));
    }

    static JSONObject decFields(JSONObject f) throws Exception {
        JSONObject o = new JSONObject();
        if (f == null) return o;
        Iterator<String> it = f.keys();
        while (it.hasNext()) { String k = it.next(); o.put(k, dec(f.getJSONObject(k))); }
        return o;
    }

    static Object dec(JSONObject v) throws Exception {
        if (v.has("integerValue")) return Long.parseLong(v.getString("integerValue"));
        if (v.has("doubleValue")) return v.getDouble("doubleValue");
        if (v.has("booleanValue")) return v.getBoolean("booleanValue");
        if (v.has("stringValue")) return v.getString("stringValue");
        if (v.has("mapValue")) return decFields(v.getJSONObject("mapValue").optJSONObject("fields"));
        if (v.has("arrayValue")) {
            JSONArray vals = v.getJSONObject("arrayValue").optJSONArray("values"), a = new JSONArray();
            if (vals != null) for (int i = 0; i < vals.length(); i++) a.put(dec(vals.getJSONObject(i)));
            return a;
        }
        if (v.has("timestampValue")) return v.getString("timestampValue");
        return JSONObject.NULL;
    }
}
