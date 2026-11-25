/*
 * Avisos de la app de llantos — Cloudflare Worker
 *
 * Cada 10 minutos revisa los registros de la familia en Firebase y le manda
 * una notificación SOLO a la administradora (la mamá) cuando:
 *   🍼 ya le va tocando comer
 *   🧷 pasó el tiempo con que suelen cambiarle el pañal (y está despierta)
 *   😴 lleva despierta más de lo que suele aguantar
 *   ☀️ se acerca la hora en que suele despertar (noche o siesta)
 *   🟢 entra a la app alguien que ella marcó con 🔔 en «Quién está en línea»
 *
 * Secretos que se configuran en Cloudflare (Settings → Variables and Secrets):
 *   FAM      → el código de familia (ej. K7P3-Q9XD-M2RT)
 *   SA_JSON  → todo el contenido del archivo .json de la cuenta de servicio de Firebase
 *
 * Disparador (Configuración → Activadores cron):  * /10 * * * *   (sin el espacio)
 *
 * Lecturas de Firebase: cada revisión lee solo las últimas 24 h; los promedios (cada cuánto come,
 * noches, siestas) se recalculan cada 3 h con 8 días y se guardan. Total aprox. 9.000 lecturas al día,
 * lejos del límite gratis de 50.000.
 *
 * Probar desde el navegador:
 *   https://TU-WORKER.workers.dev/?prueba=CODIGO-DE-FAMILIA   → manda una notificación de prueba
 *   https://TU-WORKER.workers.dev/?revisar=CODIGO-DE-FAMILIA  → revisa ahora y dice qué encontró
 */

const PROJECT = 'app-llanto';
const TZ_OFFSET_H = -5;                 // Colombia, sin horario de verano
const FS = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents`;
const NIGHT_FROM = 17, NIGHT_TO = 22.5, NIGHT_MIN = 90;   // igual que en la app

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(check(env).then(r => console.log(r)).catch(e => console.error(e.stack || e)));
  },
  async fetch(req, env) {
    const u = new URL(req.url);
    const key = u.searchParams.get('prueba') || u.searchParams.get('revisar');
    if (!key || !env.FAM || key.toUpperCase() !== env.FAM.toUpperCase()) return new Response('No encontrado', { status: 404 });
    try {
      const r = await check(env, u.searchParams.has('prueba'));
      return new Response(r, { headers: { 'content-type': 'text/plain; charset=utf-8' } });
    } catch (e) {
      return new Response('Error: ' + (e.message || e), { status: 500, headers: { 'content-type': 'text/plain; charset=utf-8' } });
    }
  }
};

/* ---------- Acceso a Google con la cuenta de servicio ---------- */
let cachedTok = null, cachedExp = 0;
const b64url = bytes => btoa(String.fromCharCode(...bytes)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
const b64json = o => b64url(new TextEncoder().encode(JSON.stringify(o)));

async function accessToken(env) {
  if (cachedTok && Date.now() < cachedExp - 120000) return cachedTok;
  const sa = JSON.parse(env.SA_JSON);
  const iat = Math.floor(Date.now() / 1000);
  const unsigned = b64json({ alg: 'RS256', typ: 'JWT' }) + '.' + b64json({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token', iat, exp: iat + 3600
  });
  const der = Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----/g, '').replace(/\s/g, '')), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned)));
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=' + unsigned + '.' + b64url(sig)
  });
  const j = await r.json();
  if (!j.access_token) throw new Error('No pude entrar a Firebase: ' + JSON.stringify(j));
  cachedTok = j.access_token; cachedExp = Date.now() + j.expires_in * 1000;
  return cachedTok;
}

/* ---------- Firestore (REST) ---------- */
function val(v) {
  if ('integerValue' in v) return Number(v.integerValue);
  if ('doubleValue' in v) return v.doubleValue;
  if ('stringValue' in v) return v.stringValue;
  if ('booleanValue' in v) return v.booleanValue;
  if ('timestampValue' in v) return Date.parse(v.timestampValue);
  if ('mapValue' in v) return fields(v.mapValue.fields || {});
  if ('arrayValue' in v) return (v.arrayValue.values || []).map(val);
  return null;
}
const fields = f => Object.fromEntries(Object.entries(f).map(([k, v]) => [k, val(v)]));

async function getDoc(path, H) {
  const r = await fetch(`${FS}/${path}`, { headers: H });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`Firestore ${r.status}: ${await r.text()}`);
  return fields((await r.json()).fields || {});
}
async function eventsSince(fam, since, H) {
  const r = await fetch(`${FS}/families/${fam}:runQuery`, {
    method: 'POST', headers: { ...H, 'content-type': 'application/json' },
    body: JSON.stringify({ structuredQuery: {
      from: [{ collectionId: 'events' }],
      where: { fieldFilter: { field: { fieldPath: 't' }, op: 'GREATER_THAN_OR_EQUAL', value: { integerValue: String(since) } } }
    } })
  });
  if (!r.ok) throw new Error(`Firestore ${r.status}: ${await r.text()}`);
  return (await r.json()).filter(x => x.document).map(x => fields(x.document.fields || {}));
}
async function listMembers(fam, H) {
  const r = await fetch(`${FS}/families/${fam}/members?pageSize=100`, { headers: H });
  if (!r.ok) throw new Error(`Firestore ${r.status}: ${await r.text()}`);
  return ((await r.json()).documents || []).map(d => ({ id: d.name.split('/').pop(), ...fields(d.fields || {}) }));
}
async function patchMember(fam, uid, data, H) {
  const enc = v => v == null ? { nullValue: null } : typeof v === 'string' ? { stringValue: v }
    : typeof v === 'number' ? (Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v })
    : { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, enc(x)])) } };
  const mask = Object.keys(data).map(k => 'updateMask.fieldPaths=' + k).join('&');
  const r = await fetch(`${FS}/families/${fam}/members/${uid}?${mask}`, {
    method: 'PATCH', headers: { ...H, 'content-type': 'application/json' },
    body: JSON.stringify({ fields: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, enc(v)])) })
  });
  if (!r.ok) throw new Error(`Firestore ${r.status}: ${await r.text()}`);
}

/* ---------- Notificación (Firebase Cloud Messaging) ---------- */
async function send(token, title, body, tag, H) {
  const r = await fetch(`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`, {
    method: 'POST', headers: { ...H, 'content-type': 'application/json' },
    body: JSON.stringify({ message: { token, data: { title, body, tag }, webpush: { headers: { Urgency: 'high', TTL: '3600' } } } })
  });
  if (r.ok) return 'ok';
  const t = await r.text();
  if (r.status === 404 || /UNREGISTERED|INVALID_ARGUMENT.*token/i.test(t)) return 'gone';
  throw new Error(`FCM ${r.status}: ${t}`);
}

/* ---------- Lógica de los avisos (la misma que usa la app) ---------- */
const fmt = m => { m = Math.round(m); return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`; };
const localDate = t => new Date(t + TZ_OFFSET_H * 3600e3);              // usar getUTC* sobre esto
const hourOf = t => { const d = localDate(t); return d.getUTCHours() + d.getUTCMinutes() / 60; };
const hhmm = t => { const d = localDate(t), h = d.getUTCHours(); return `${h % 12 || 12}:${String(d.getUTCMinutes()).padStart(2, '0')} ${h < 12 ? 'am' : 'pm'}`; };
const median = a => { if (!a.length) return null; const s = [...a].sort((x, y) => x - y), m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };

const clean = (events, now) => events.filter(e => e.type && e.type !== 'baby' && e.t > 0 && e.t <= now).sort((a, b) => a.t - b.t);
const isNightStart = a => hourOf(a) >= NIGHT_FROM && hourOf(a) < NIGHT_TO;

// Promedios de los últimos días (se recalculan cada 3 h)
function computeStats(events, now = Date.now()) {
  const ev = clean(events, now);

  const fts = ev.filter(e => e.type === 'feed').map(e => e.t), fg = [];
  for (let i = 1; i < fts.length; i++) { const x = (fts[i] - fts[i - 1]) / 60000; if (x >= 30 && x <= 360) fg.push(x); }
  const feedInt = median(fg.slice(-15)) || 180;

  // cada cuánto le cambian el pañal normalmente (mientras no haya 5 intervalos, 3 h)
  const dts = ev.filter(e => e.type === 'diaper').map(e => e.t), dg = [];
  for (let i = 1; i < dts.length; i++) { const x = (dts[i] - dts[i - 1]) / 60000; if (x >= 45 && x <= 480) dg.push(x); }
  const diaperInt = dg.length >= 5 ? Math.max(90, Math.min(360, median(dg.slice(-15)))) : 180;

  const sw = ev.filter(e => e.type === 'sleep' || e.type === 'wake'), wg = [];
  for (let i = 1; i < sw.length; i++)
    if (sw[i - 1].type === 'wake' && sw[i].type === 'sleep') { const x = (sw[i].t - sw[i - 1].t) / 60000; if (x >= 20 && x <= 240) wg.push(x); }
  const wakeWin = median(wg.slice(-15)) || 80;

  // tramos de sueño completos
  const sleeps = []; let st = null;
  for (const e of sw) {
    if (e.type === 'sleep') { if (!st) st = e; }
    else if (st) { sleeps.push({ a: st.t, b: e.t }); st = null; }
  }
  const seen = new Set(), nights = [], naps = [];
  for (const s of sleeps) {
    const day = localDate(s.a).toISOString().slice(0, 10);
    if (isNightStart(s.a) && s.b - s.a >= NIGHT_MIN * 60000 && !seen.has(day)) { seen.add(day); nights.push(s.b - s.a); }
    else if (!isNightStart(s.a) && s.b - s.a >= 15 * 60000 && s.b - s.a <= 4 * 3600e3) naps.push(s.b - s.a);
  }
  const avg = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0;
  const n7 = nights.slice(-7), n10 = naps.slice(-10);
  return { feedInt, wakeWin, diaperInt, nightAvg: avg(n7), nightN: n7.length, napAvg: avg(n10), napN: n10.length, at: now };
}

// Avisos con los registros de las últimas 24 h + los promedios guardados
// feedMax: indicación del pediatra en minutos (0 = sin indicación), desde que empezó la toma anterior
function computeAlerts(events, S, notified = {}, now = Date.now(), feedMax = 180) {
  const mins = t => (now - t) / 60000;
  const ev = clean(events, now);
  const last = types => { for (let i = ev.length - 1; i >= 0; i--) if (types.includes(ev[i].type)) return ev[i]; return null; };
  const { feedInt, wakeWin } = S;
  const out = [];
  const lsw = last(['sleep', 'wake']), asleep = !!lsw && lsw.type === 'sleep';
  const lf = last(['feed']), ld = last(['diaper']);
  const feeding = lf && lf.mode === 'breast' && !lf.end && mins(lf.t) < 180;

  // De noche (7 pm a 6 am) la indicación del pediatra no aplica: como antes, con lo que ella suele aguantar
  { const h = localDate(now).getUTCHours(); if (h >= 19 || h < 6) feedMax = 0; }
  const fTh = feedMax ? Math.min(feedInt * 0.9, feedMax - 10) : feedInt * 0.9;
  if (lf && !feeding && mins(lf.t) >= fTh && mins(lf.t) < 10 * 60 && notified.feed !== lf.id) {
    const byDoc = feedMax && feedMax - 10 <= feedInt * 0.9;
    out.push({ k: 'feed', id: lf.id, title: byDoc ? `🍼 Le toca comer a las ${hhmm(lf.t + feedMax * 60000)}` : '🍼 Ya le va tocando comer',
      body: `Comió hace ${fmt(mins(lf.t))} (${hhmm(lf.t)}). ${byDoc ? `El pediatra indicó cada ${feedMax % 60 ? fmt(feedMax) : feedMax / 60 + ' h'}.` : `Suele comer cada ~${fmt(feedInt)}.`}${asleep ? ' Ahora está dormida.' : ''}` });
  }

  const dInt = S.diaperInt || 180;
  if (ld && !asleep && mins(ld.t) >= dInt && mins(ld.t) < 10 * 60 && notified.diaper !== ld.id)
    out.push({ k: 'diaper', id: ld.id, title: '🧷 Revisa el pañal',
      body: `El último cambio fue hace ${fmt(mins(ld.t))} (${hhmm(ld.t)}). Suelen cambiarla cada ~${fmt(dInt)}.` });

  if (lsw && !asleep && mins(lsw.t) >= wakeWin && mins(lsw.t) < wakeWin * 3 && notified.awake !== lsw.id)
    out.push({ k: 'awake', id: lsw.id, title: `😴 Lleva ${fmt(mins(lsw.t))} despierta`,
      body: `Suele aguantar ~${fmt(wakeWin)}. Busca señales de sueño: bostezos, se frota los ojos o se pone inquieta.` });

  if (asleep && notified.wake !== lsw.id) {
    const night = isNightStart(lsw.t);
    const ref = night ? (S.nightN >= 2 ? S.nightAvg : null) : (S.napN >= 3 ? S.napAvg : null);
    if (ref) {
      const exp = lsw.t + ref;
      if (now >= exp - 10 * 60000 && now <= exp + 30 * 60000)
        out.push({ k: 'wake', id: lsw.id, title: '☀️ Ya se va a despertar',
          body: `Lleva ${fmt(mins(lsw.t))} dormida. ${night ? 'El primer tramo de sus noches' : 'Sus siestas'} suele durar ~${fmt(ref / 60000)}, así que despertaría hacia las ${hhmm(exp)}.` });
    }
  }
  return { alerts: out, feedInt, wakeWin, asleep };
}

/* ---------- Revisión principal ---------- */
async function check(env, test = false) {
  if (!env.FAM || !env.SA_JSON) return 'Faltan los secretos FAM o SA_JSON';
  const fam = env.FAM.trim().toUpperCase();
  const H = { authorization: 'Bearer ' + await accessToken(env) };
  const famDoc = await getDoc(`families/${fam}`, H);
  if (!famDoc || !famDoc.owner) return 'La familia no tiene administradora todavía';
  const me = await getDoc(`families/${fam}/members/${famDoc.owner}`, H);
  if (!me || !me.fcm) return 'La administradora no ha activado las notificaciones en la app';
  if (me.notif === false) return 'Notificaciones desactivadas desde la app';

  if (test) {
    const r = await send(me.fcm, '🔔 Prueba', 'Las notificaciones de la app de llantos funcionan ✓', 'prueba', H);
    if (r === 'gone') { await patchMember(fam, famDoc.owner, { fcm: null }, H); return 'El celular ya no está registrado. Vuelve a activar las notificaciones en la app.'; }
    return 'Notificación de prueba enviada ✓';
  }

  let S = me.stats, upd = {};
  if (!S || !S.at || Date.now() - S.at > 3 * 3600e3) {
    S = computeStats(await eventsSince(fam, Date.now() - 8 * 864e5, H));
    upd.stats = S;
  }
  const events = await eventsSince(fam, Date.now() - 864e5, H);
  const baby = await getDoc(`families/${fam}/events/baby`, H).catch(() => null);
  const feedMax = baby && baby.feedMax != null ? Number(baby.feedMax) || 0 : 180;
  const notified = me.notified || {};
  const { alerts, feedInt, wakeWin, asleep } = computeAlerts(events, S, notified, Date.now(), feedMax);
  for (const a of alerts) {
    const r = await send(me.fcm, a.title, a.body, a.k, H);
    if (r === 'gone') { await patchMember(fam, famDoc.owner, { fcm: null }, H); return 'El celular ya no está registrado. Vuelve a activar las notificaciones en la app.'; }
    notified[a.k] = a.id;
  }
  if (alerts.length) upd.notified = notified;

  // 🟢 Avisar cuando entra alguien que la administradora marcó con 🔔 (por nombre, aunque entre desde varios celulares)
  const watch = (me.watch || []).map(n => String(n).trim().toLowerCase()).filter(Boolean);
  const entered = [];
  if (watch.length) {
    const members = await listMembers(fam, H), prev = me.watchSeen || {}, seenNow = {};
    for (const n of watch) {
      const seen = Math.max(0, ...members.filter(m => m.id !== famDoc.owner && String(m.name || '').trim().toLowerCase() === n).map(m => m.seen || m.t || 0));
      if (!seen) continue;
      seenNow[n] = seen;
      const recent = Date.now() - seen < 11 * 60000;               // estuvo en la app desde la última revisión
      const newSession = prev[n] == null || seen - prev[n] > 12 * 60000;
      if (recent && newSession) {
        const who = members.find(m => String(m.name || '').trim().toLowerCase() === n).name;
        const r = await send(me.fcm, `🟢 ${who} entró a la app`, `Se conectó a las ${hhmm(seen)}.`, 'online-' + n, H);
        if (r === 'gone') { await patchMember(fam, famDoc.owner, { fcm: null }, H); return 'El celular ya no está registrado. Vuelve a activar las notificaciones en la app.'; }
        entered.push(who);
      }
    }
    upd.watchSeen = seenNow;
  }
  if (Object.keys(upd).length) await patchMember(fam, famDoc.owner, upd, H);
  return `Revisé ${events.length} registros · ${asleep ? 'dormida' : 'despierta'} · come cada ~${fmt(feedInt)} · aguanta despierta ~${fmt(wakeWin)} · avisos enviados: ${alerts.length ? alerts.map(a => a.title).join(' | ') : 'ninguno'}${entered.length ? ` · entraron: ${entered.join(', ')}` : ''}`;
}
