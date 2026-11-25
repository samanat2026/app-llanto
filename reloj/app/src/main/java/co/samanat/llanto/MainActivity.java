package co.samanat.llanto;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App de Llanto para el reloj: los botones principales (llanto, pecho, tetero, pañal, sueño).
 * Guarda en la misma base de Firebase que la app del celular, con el mismo formato.
 */
public class MainActivity extends Activity {
    static final long MIN = 60000L;
    static final int PINK = 0xFFE0569B, BLUE = 0xFF3D5A80, PURPLE = 0xFF6A4C93, GREEN = 0xFF2A9D8F,
            ORANGE = 0xFFE07A3F, GRAY = 0xFF3A3A48, BG = 0xFF14141C;

    interface Op { void run() throws Exception; }

    SharedPreferences sp;
    Fb fb;
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final Handler h = new Handler(Looper.getMainLooper());
    List<JSONObject> evs = new ArrayList<>();
    String fam, me;
    boolean busy = false, loaded = false, onMain = false;
    long loadedAt = 0;
    final List<Op> undo = new ArrayList<>();
    String undoLabel = null;
    long undoUntil = 0;
    ScrollView scroll;
    LinearLayout root;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (onMain && !busy) {
                if (System.currentTimeMillis() - loadedAt > 60000) load(); else renderMain();
            }
            h.postDelayed(this, 30000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("llanto", MODE_PRIVATE);
        fb = new Fb(sp);
        fam = sp.getString("fam", null);
        me = sp.getString("name", null);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        setContentView(scroll);
        if (fam == null) showSetup(); else { onMain = true; renderMain(); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (fam != null && onMain) load();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 30000);
    }

    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    /* ====================== Utilidades de pantalla ====================== */
    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    LinearLayout page() {
        int w = getResources().getDisplayMetrics().widthPixels;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        // Pantalla redonda: márgenes para que nada se corte en los bordes
        root.setPadding((int) (w * 0.11), (int) (w * 0.14), (int) (w * 0.11), (int) (w * 0.24));
        scroll.removeAllViews();
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.scrollTo(0, 0);
        return root;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    TextView button(String s, int color, float size, Runnable fn) {
        TextView t = text(s, size, Color.WHITE, true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(22));
        t.setBackground(g);
        t.setPadding(dp(8), dp(11), dp(8), dp(11));
        t.setClickable(true);
        t.setOnClickListener(v -> { if (busy) return; v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); fn.run(); });
        return t;
    }

    void add(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        root.addView(v, lp);
    }

    void row(View... vs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < vs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(3), 0, i == vs.length - 1 ? 0 : dp(3), 0);
            r.addView(vs[i], lp);
        }
        add(r);
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    static String fmt(long m) {
        if (m < 1) return "menos de 1 min";
        if (m < 60) return m + " min";
        return (m / 60) + " h " + String.format(Locale.US, "%02d", m % 60);
    }

    static String hhmm(long t) { return new SimpleDateFormat("h:mm a", new Locale("es", "CO")).format(new Date(t)); }

    static String newId() {
        String a = "0123456789abcdefghijklmnopqrstuvwxyz";
        Random r = new Random();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) s.append(a.charAt(r.nextInt(36)));
        String ts = Long.toString(System.currentTimeMillis(), 36);
        return s + ts.substring(Math.max(0, ts.length() - 4));
    }

    /* ====================== Configuración (una sola vez) ====================== */
    void showSetup() {
        onMain = false;
        page();
        add(text("App de Llanto ⌚", 17, Color.WHITE, true));
        add(text("Código de la familia (en la app del celular: menú ⋯)", 12, 0xFFB8B8C8, false));
        final EditText code = new EditText(this);
        code.setHint("K7P3-Q9XD-M2RT");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(0xFF77778A);
        code.setGravity(Gravity.CENTER);
        add(code);
        add(text("Tu nombre", 12, 0xFFB8B8C8, false));
        final EditText name = new EditText(this);
        name.setText("Natalia (reloj)");
        name.setTextColor(Color.WHITE);
        name.setGravity(Gravity.CENTER);
        add(name);
        add(button("Entrar", PINK, 16, () -> {
            String c = code.getText().toString().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            final String n = name.getText().toString().trim().isEmpty() ? "Reloj" : name.getText().toString().trim();
            if (c.length() != 12) { toast("El código debe tener 12 letras/números"); return; }
            final String f = c.substring(0, 4) + "-" + c.substring(4, 8) + "-" + c.substring(8);
            busy = true;
            toast("Conectando…");
            ex.execute(() -> {
                try {
                    fb.ensureAuth();
                    fb.member(f, n);
                    if (!fb.familyExists(f)) throw new IllegalStateException("nofam");
                    sp.edit().putString("fam", f).putString("name", n).apply();
                    fam = f; me = n;
                    h.post(() -> { busy = false; onMain = true; renderMain(); load(); });
                } catch (IllegalStateException e) {
                    h.post(() -> { busy = false; toast("No encontré esa familia. Revisa el código"); });
                } catch (Exception e) {
                    h.post(() -> { busy = false; toast("Sin conexión. Intenta otra vez"); });
                }
            });
        }));
    }

    /* ====================== Datos ====================== */
    void load() {
        if (fam == null) return;
        ex.execute(() -> {
            try {
                if (fb.ensureAuth()) fb.member(fam, me);   // la sesión se renovó con otro usuario: volver a registrarse
                List<JSONObject> r = fb.eventsSince(fam, System.currentTimeMillis() - 36 * 60 * MIN);
                // Fecha de nacimiento (para la duración del ciclo de sueño según la edad): se revisa cada 6 h
                if (System.currentTimeMillis() - sp.getLong("babyAt", 0) > 6 * 60 * MIN) {
                    JSONObject baby = fb.get(fam, "events", "baby");
                    sp.edit().putString("birth", baby == null ? "" : baby.optString("birth", "")).putLong("babyAt", System.currentTimeMillis()).apply();
                }
                h.post(() -> { evs = r; loaded = true; loadedAt = System.currentTimeMillis(); if (onMain && !busy) renderMain(); });
            } catch (Exception e) {
                h.post(() -> { if (onMain && !busy) { renderMain(); toast("Sin conexión"); } });
            }
        });
    }

    static boolean empty(JSONObject o, String k) { return !o.has(k) || o.isNull(k) || o.optLong(k, 0) == 0; }

    JSONObject last(String... types) {
        JSONObject r = null;
        for (JSONObject e : evs) {
            String ty = e.optString("type");
            for (String t : types) if (t.equals(ty) && (r == null || e.optLong("t") > r.optLong("t"))) r = e;
        }
        return r;
    }

    boolean asleep() { JSONObject l = last("sleep", "wake"); return l != null && "sleep".equals(l.optString("type")); }

    JSONObject ongoingFeed() {
        long now = System.currentTimeMillis();
        for (JSONObject e : evs)
            if ("feed".equals(e.optString("type")) && "breast".equals(e.optString("mode")) && empty(e, "end") && now - e.optLong("t") < 3 * 60 * MIN)
                return e;
        return null;
    }

    static String lastSegSide(JSONObject f) {
        JSONArray s = f == null ? null : f.optJSONArray("segs");
        return s == null || s.length() == 0 ? null : s.optJSONObject(s.length() - 1).optString("s", null);
    }

    String lastSide() {
        JSONObject best = null;
        for (JSONObject e : evs)
            if ("feed".equals(e.optString("type")) && "breast".equals(e.optString("mode")) && lastSegSide(e) != null
                    && (best == null || e.optLong("t") > best.optLong("t"))) best = e;
        return lastSegSide(best);
    }

    static boolean pausedNow(JSONObject f) {
        JSONArray p = f.optJSONArray("pauses");
        return p != null && p.length() > 0 && empty(p.optJSONObject(p.length() - 1), "t1");
    }

    static long activeMs(JSONObject f, long end) {
        long ms = end - f.optLong("t");
        JSONArray p = f.optJSONArray("pauses");
        if (p != null) for (int i = 0; i < p.length(); i++) {
            JSONObject x = p.optJSONObject(i);
            long t1 = empty(x, "t1") ? end : x.optLong("t1");
            ms -= Math.max(0, t1 - x.optLong("t0"));
        }
        return Math.max(0, ms);
    }

    long minsSince(JSONObject e) { return e == null ? -1 : (System.currentTimeMillis() - e.optLong("t")) / MIN; }

    /* ====================== Pantalla principal ====================== */
    void renderMain() {
        onMain = true;
        page();
        long now = System.currentTimeMillis();
        JSONObject lf = last("feed"), dp = last("diaper"), sw = last("sleep", "wake"), of = ongoingFeed();
        boolean sleeping = asleep();
        StringBuilder st = new StringBuilder();
        if (!loaded) st.append("Cargando…");
        else {
            // Desde el INICIO de la toma (como cuentan los pediatras); si fue larga, también cuándo terminó
            st.append("🍼 ").append(lf == null ? "sin tomas" : "empezó hace " + fmt(minsSince(lf)));
            if (lf != null && !empty(lf, "end") && lf.optLong("end") - lf.optLong("t") >= 15 * MIN)
                st.append("\n   terminó hace ").append(fmt((now - lf.optLong("end")) / MIN));
            st.append("\n").append(sleeping ? "😴 dormida " : "☀️ despierta ").append(sw == null ? "" : fmt(minsSince(sw)));
            st.append("\n🧷 ").append(dp == null ? "sin pañal" : "hace " + fmt(minsSince(dp)));
        }
        add(text(st.toString(), 13, 0xFFE8E8F0, false));
        if (loaded && sleeping) cycleBlock(sw);

        add(button("😢  Está llorando", PINK, 18, this::cry));

        if (of != null) {
            String side = lastSegSide(of);
            add(text("🤱 Pecho " + ("der".equals(side) ? "derecho" : "izquierdo") + " · " + fmt(activeMs(of, now) / MIN), 14, Color.WHITE, true));
            row(button("Cambiar lado", BLUE, 13, this::switchSide), button("Terminar", GREEN, 13, () -> finishBreast(false)));
            add(button("Terminar y se durmió 😴", PURPLE, 13, () -> finishBreast(true)));
        } else {
            String next = "izq".equals(lastSide()) ? "der" : "izq";
            row(button(("izq".equals(next) ? "👉 " : "") + "🤱 Izq", BLUE, 14, () -> startBreast("izq")),
                button(("der".equals(next) ? "👉 " : "") + "🤱 Der", BLUE, 14, () -> startBreast("der")));
        }

        add(button(sleeping ? "☀️  Se despertó" : "😴  Se durmió", PURPLE, 16, this::sleepToggle));
        row(button("🍼 Tetero", ORANGE, 14, this::showOz), button("🧷 Pañal", GREEN, 14, this::showDiaper));

        if (undoLabel != null && now < undoUntil)
            add(button("↩ Deshacer: " + undoLabel, GRAY, 12, this::doUndo));

        TextView foot = text(loaded ? "Actualizado " + hhmm(loadedAt) + " · tocar para actualizar" : "", 10, 0xFF8A8A9A, false);
        foot.setOnClickListener(v -> load());
        add(foot);
        TextView out = text("Cambiar familia", 10, 0xFF6A6A7A, false);
        out.setOnLongClickListener(v -> { sp.edit().remove("fam").apply(); fam = null; showSetup(); return true; });
        out.setOnClickListener(v -> toast("Mantén presionado para cambiar de familia"));
        add(out);
    }

    /* ====================== Ciclos de sueño (igual que en el celular) ======================
       Cada ciclo dura según la edad (menos de 3 meses: 45 min; 3-6: 50; 6-12: 60). Dentro de cada ciclo:
       primer 22% quedándose dormida, hasta el 67% sueño profundo, y al final sueño ligero, cuando puede despertarse. */
    int cycleMin() {
        String b = sp.getString("birth", "");
        if (b == null || b.length() < 10) return 45;
        try {
            Calendar bd = Calendar.getInstance(), now = Calendar.getInstance();
            bd.set(Integer.parseInt(b.substring(0, 4)), Integer.parseInt(b.substring(5, 7)) - 1, Integer.parseInt(b.substring(8, 10)));
            int m = (now.get(Calendar.YEAR) - bd.get(Calendar.YEAR)) * 12 + now.get(Calendar.MONTH) - bd.get(Calendar.MONTH);
            if (now.get(Calendar.DAY_OF_MONTH) < bd.get(Calendar.DAY_OF_MONTH)) m--;
            return m < 3 ? 45 : m < 6 ? 50 : m < 12 ? 60 : 70;
        } catch (Exception e) { return 45; }
    }

    void cycleBlock(JSONObject ls) {
        long now = System.currentTimeMillis(), start = ls.optLong("t");
        // Si comió en medio del sueño, el sueño actual empieza cuando terminó la toma
        for (JSONObject e : evs)
            if ("feed".equals(e.optString("type")) && e.optLong("t") > start + MIN) start = Math.max(start, Math.max(e.optLong("t"), e.optLong("end", 0)));
        int len = cycleMin();
        int pre = 0;
        long base = start;
        // Despertar breve: al volverse a dormir arranca un ciclo nuevo
        JSONArray stirs = ls.optJSONArray("stirs");
        if (stirs != null && stirs.length() > 0) {
            JSONObject z = stirs.optJSONObject(stirs.length() - 1);
            if (z != null && z.optLong("t1") > start) { pre = Math.max(1, Math.round((z.optLong("t0") - start) / (float) MIN / len)); base = z.optLong("t1"); }
        }
        double mm = (now - base) / (double) MIN;
        int d1 = (int) Math.floor(mm / len), done = pre + d1;
        double into = mm - d1 * len, frac = into / len;
        long left = Math.max(1, (long) Math.ceil(len - into));
        long endAt = now + left * MIN;
        add(text((done == 0 ? "1.er ciclo" : done + (done == 1 ? " ciclo completo" : " ciclos completos") + " · va en el " + (done + 1) + ".º")
                + "\nTermina a las " + hhmm(endAt) + " (en " + left + " min)", 12, 0xFFB8B8C8, false));
        String ph; int col;
        if (frac < 0.22) {
            ph = "🌙 Quedándose dormida\nEspera ~" + Math.max(1, (long) Math.ceil(0.22 * len - into)) + " min para acostarla o pasarla a la cuna";
            col = 0xFFB9C6FF;
        } else if (frac < 0.67) {
            ph = "💤 Sueño profundo\nBuen momento para pasarla a la cuna (~" + Math.max(1, (long) Math.ceil(0.67 * len - into)) + " min)";
            col = 0xFF8FE3C9;
        } else if (left <= 6) {
            ph = "🔄 Pasando de un ciclo a otro\nNo hagas bulla: puede despertarse a las " + hhmm(endAt) + ". Si se mueve o se queja, espera un poco antes de cargarla";
            col = 0xFFFFB74D;
        } else {
            ph = "🌤️ Sueño ligero\nPuede despertarse a las " + hhmm(endAt) + ". Haz poco ruido para que pase al siguiente ciclo";
            col = 0xFFFFD98A;
        }
        TextView t = text(ph, 13, col, false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF22222E);
        g.setCornerRadius(dp(14));
        t.setBackground(g);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        add(t);
    }

    /* ====================== Guardar ====================== */
    JSONObject newEvent(String type, long t) throws Exception {
        return new JSONObject().put("id", newId()).put("type", type).put("t", t).put("by", me);
    }

    /** Crea el documento y deja cómo deshacerlo. */
    void create(String col, JSONObject e) throws Exception {
        fb.set(fam, col, e);
        if ("events".equals(col)) evs.add(e);
        final String id = e.getString("id");
        undo.add(() -> { fb.del(fam, col, id); });
    }

    /** Guarda un cambio y deja cómo volver a como estaba. */
    void update(JSONObject e, JSONObject before) throws Exception {
        fb.set(fam, "events", e);
        undo.add(() -> fb.set(fam, "events", before));
    }

    static JSONObject copy(JSONObject o) throws Exception { return new JSONObject(o.toString()); }

    /** Si estaba dormida, una toma o un llanto la despiertan (como en el celular). */
    void wakeIfAsleep(String why, long t) throws Exception {
        JSONObject l = last("sleep", "wake");
        if (l != null && "sleep".equals(l.optString("type")) && l.optLong("t") < t)
            create("events", newEvent("wake", t).put("auto", why));
    }

    void run(String label, Op op, Runnable after) {
        busy = true;
        undo.clear();
        ex.execute(() -> {
            try {
                fb.ensureAuth();
                op.run();
                h.post(() -> {
                    busy = false;
                    undoLabel = label; undoUntil = System.currentTimeMillis() + 60000;
                    getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    toast("✓ " + label);
                    if (after != null) after.run(); else { renderMain(); load(); }
                });
            } catch (Exception e) {
                h.post(() -> { busy = false; toast("No se guardó: sin conexión"); renderMain(); load(); });
            }
        });
    }

    void doUndo() {
        final List<Op> ops = new ArrayList<>(undo);
        undo.clear(); undoLabel = null;
        busy = true;
        ex.execute(() -> {
            try {
                for (int i = ops.size() - 1; i >= 0; i--) ops.get(i).run();
                h.post(() -> { busy = false; toast("Deshecho"); load(); });
            } catch (Exception e) {
                h.post(() -> { busy = false; toast("No se pudo deshacer: sin conexión"); load(); });
            }
        });
    }

    /* ====================== Acciones ====================== */
    void startBreast(String side) {
        run("Pecho " + ("izq".equals(side) ? "izquierdo" : "derecho"), () -> {
            long now = System.currentTimeMillis();
            wakeIfAsleep("feed", now);
            JSONObject e = newEvent("feed", now).put("mode", "breast")
                    .put("segs", new JSONArray().put(new JSONObject().put("s", side).put("t", now))).put("end", JSONObject.NULL);
            create("events", e);
        }, null);
    }

    void switchSide() {
        run("Cambio de lado", () -> {
            JSONObject x = ongoingFeed();
            if (x == null) return;
            JSONObject before = copy(x);
            String other = "izq".equals(lastSegSide(x)) ? "der" : "izq";
            JSONArray segs = x.optJSONArray("segs");
            if (segs == null) { segs = new JSONArray(); x.put("segs", segs); }
            segs.put(new JSONObject().put("s", other).put("t", System.currentTimeMillis()));
            update(x, before);
        }, null);
    }

    void finishBreast(boolean withSleep) {
        run(withSleep ? "Terminó y se durmió" : "Terminó el pecho", () -> {
            JSONObject x = ongoingFeed();
            if (x == null) return;
            JSONObject before = copy(x);
            long now = System.currentTimeMillis();
            if (pausedNow(x)) { JSONArray p = x.getJSONArray("pauses"); p.getJSONObject(p.length() - 1).put("t1", now); }
            x.put("end", now).put("mins", Math.max(1, Math.round(activeMs(x, now) / (double) MIN)));
            update(x, before);
            if (withSleep && !asleep()) create("events", newEvent("sleep", now + 1000));
        }, null);
    }

    void bottle(int oz) {
        run("Tetero " + oz + " oz", () -> {
            long now = System.currentTimeMillis();
            wakeIfAsleep("feed", now);
            create("events", newEvent("feed", now).put("mode", "bottle").put("oz", oz));
        }, null);
    }

    void diaper(boolean poop) {
        run(poop ? "Pañal con popó" : "Pañal", () -> {
            JSONObject e = newEvent("diaper", System.currentTimeMillis());
            if (poop) e.put("poop", true);
            create("events", e);
        }, null);
    }

    /* Despertar breve: si se despertó hace menos de 15 min (sin comer en medio) y se vuelve a dormir,
       es el mismo sueño. Se quita el "se despertó" y se anota en el sueño (igual que en el celular). */
    void sleepToggle() {
        if (asleep()) { run("Se despertó", () -> create("events", newEvent("wake", System.currentTimeMillis())), null); return; }
        final String[] label = {"Se durmió"};
        run("Se durmió", () -> {
            long now = System.currentTimeMillis();
            JSONObject w = last("sleep", "wake"), s = null;
            boolean brief = w != null && "wake".equals(w.optString("type")) && !"feed".equals(w.optString("auto"))
                    && now - w.optLong("t") <= 15 * MIN && ongoingFeed() == null;
            if (brief) {
                for (JSONObject e : evs)
                    if ("feed".equals(e.optString("type")) && Math.max(e.optLong("end", 0), e.optLong("t")) >= w.optLong("t") - MIN) brief = false;
                for (JSONObject e : evs)
                    if ("sleep".equals(e.optString("type")) && e.optLong("t") < w.optLong("t") && (s == null || e.optLong("t") > s.optLong("t"))) s = e;
            }
            if (brief && s != null) {
                JSONObject before = copy(s), wCopy = copy(w);
                JSONArray stirs = s.optJSONArray("stirs");
                if (stirs == null) { stirs = new JSONArray(); s.put("stirs", stirs); }
                stirs.put(new JSONObject().put("t0", w.optLong("t")).put("t1", now).put("by", me));
                fb.set(fam, "events", s);
                fb.del(fam, "events", w.getString("id"));
                evs.remove(w);
                undo.add(() -> { fb.set(fam, "events", before); fb.set(fam, "events", wCopy); });
                label[0] = "Se volvió a dormir: mismo sueño";
            } else create("events", newEvent("sleep", now));
        }, () -> { if (!"Se durmió".equals(label[0])) toast("😴 " + label[0]); renderMain(); load(); });
    }

    void showOz() {
        onMain = false;
        page();
        add(text("🍼 ¿Cuántas onzas?", 15, Color.WHITE, true));
        for (int i = 1; i <= 8; i += 2) {
            final int a = i, b = i + 1;
            row(button(a + " oz", ORANGE, 15, () -> bottle(a)), button(b + " oz", ORANGE, 15, () -> bottle(b)));
        }
        add(button("Cancelar", GRAY, 13, this::renderMain));
    }

    void showDiaper() {
        onMain = false;
        page();
        add(text("🧷 Cambio de pañal", 15, Color.WHITE, true));
        add(button("💧 Solo pipí", GREEN, 15, () -> diaper(false)));
        add(button("💩 Con popó", 0xFF8D6E63, 15, () -> diaper(true)));
        add(button("Cancelar", GRAY, 13, this::renderMain));
    }

    /* ====================== Llanto ======================
       El reloj no graba audio: anota el llanto (el celular calcula la sugerencia completa con lo aprendido)
       y aquí muestra una pista rápida con los horarios. */
    void cry() {
        final String[] id = {null};
        final String hint = quickHint();
        run("Llanto anotado", () -> {
            long now = System.currentTimeMillis();
            wakeIfAsleep("cry", now);
            JSONObject c = new JSONObject().put("id", newId()).put("t", now).put("ctx", JSONObject.NULL)
                    .put("audio", JSONObject.NULL).put("noAudio", "watch").put("label", JSONObject.NULL)
                    .put("by", me).put("src", "reloj");
            id[0] = c.getString("id");
            create("cries", c);
        }, () -> showCry(id[0], hint));
    }

    String quickHint() {
        long sf = minsSince(last("feed")), sd = minsSince(last("diaper"));
        JSONObject sw = last("sleep", "wake");
        long aw = sw != null && "wake".equals(sw.optString("type")) ? minsSince(sw) : 0;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        long target = hour >= 6 && hour < 19 ? 180 : 240;   // de día, cada 3 h (pediatra)
        List<double[]> sc = new ArrayList<>();
        List<String> tx = new ArrayList<>();
        if (sf >= 0 && sf >= target * 0.8) { sc.add(new double[]{sf / (double) target, tx.size()}); tx.add("🍼 Hambre: comió hace " + fmt(sf) + ".\n🤱😴 Si es para dormirse, dale seno."); }
        if (aw >= 70) { sc.add(new double[]{aw / 80.0, tx.size()}); tx.add("😴 Sueño: lleva " + fmt(aw) + " despierta."); }
        if (sd >= 150) { sc.add(new double[]{sd / 180.0, tx.size()}); tx.add("🧷 Pañal: cambiado hace " + fmt(sd) + "."); }
        if (sc.isEmpty()) return "🫂 Puede ser brazos, gases o mimos. Revisa también frío o calor (nuca).";
        sc.sort((a, b) -> Double.compare(b[0], a[0]));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(2, sc.size()); i++) s.append(i > 0 ? "\n\n" : "").append(tx.get((int) sc.get(i)[1]));
        return s.toString();
    }

    static final String[][] LBL = {{"hambre", "🍼 Hambre"}, {"sueno", "😴 Sueño"}, {"panal", "🧷 Pañal"}, {"frio", "🥶 Frío"},
            {"calor", "🥵 Calor"}, {"colico", "😣 Cólico / gases"}, {"otro", "🫂 Brazos, mimos"}};

    void showCry(String cryId, String hint) {
        onMain = false;
        page();
        add(text("😢 Llanto anotado", 16, Color.WHITE, true));
        add(text("Pista rápida", 11, 0xFFB8B8C8, false));
        add(text(hint, 14, Color.WHITE, false));
        final TextView sug = text("Marca en el celular qué la calmó (Pendientes ⌚)", 11, 0xFFB8B8C8, false);
        add(sug);
        add(button("Listo", PINK, 15, () -> { renderMain(); load(); }));
        // Si un celular tiene la app abierta, calcula la sugerencia completa en segundos: se muestra aquí
        for (long delay : new long[]{5000, 12000, 25000}) h.postDelayed(() -> {
            if (onMain || cryId == null) return;
            ex.execute(() -> {
                try {
                    JSONObject c = fb.get(fam, "cries", cryId);
                    JSONObject p = c == null ? null : c.optJSONObject("pred");
                    JSONObject pp = p == null ? null : p.optJSONObject("p");
                    if (pp == null) return;
                    String best = null; double bv = -1;
                    for (String[] l : LBL) { double v = pp.optDouble(l[0], 0); if (v > bv) { bv = v; best = l[1]; } }
                    final String t = "Sugerencia de la app: " + best + " " + Math.round(bv * 100) + "%";
                    h.post(() -> { if (!onMain) { sug.setText(t); sug.setTextColor(Color.WHITE); sug.setTextSize(14); } });
                } catch (Exception ignored) { }
            });
        }, delay);
    }

    @Override public void onBackPressed() {
        if (!onMain && fam != null) renderMain(); else super.onBackPressed();
    }
}
