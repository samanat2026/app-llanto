# ¿Qué tiene? — App de llantos compartida

PWA que sugiere por qué llora la bebé (hambre, sueño, pañal, frío/calor, otro) y aprende con cada llanto que marca cualquiera de los cuidadores.

## Uso (todo en un toque)
- **Está llorando**: graba 6 s, analiza el llanto en el celular y muestra la causa más probable. Si estaba dormida, la pasa a despierta sola.
- **¿Qué la calmó?**: un toque. Hambre, Pañal o Sueño también registran la toma, el cambio o la siesta.
- **🍼 Comió / 😴 Se durmió–☀️ Se despertó / 🧷 Pañal**: un toque. "Fue hace 15 min" corrige la hora.
- **😴 Se volvió a dormir**: si se despierta y se vuelve a dormir en menos de 15 min (sin comer en medio), es un *despertar breve*: sigue el mismo sueño, no cuenta como siesta nueva ni reinicia el tiempo despierta.
- Lo que no se marque queda en **Pendientes**.
- Todos ven lo mismo en tiempo real y quién registró cada cosa. Sin señal, se guarda y se sube después.

## Archivos
- `index.html` — la app
- `firebase-config.js` — configuración de Firebase (null = modo solo un celular)
- `firestore.rules` — reglas de seguridad para pegar en Firebase
- `sw.js`, `manifest.webmanifest`, `icon.svg` — instalación y modo sin internet

## Configurar Firebase
1. https://console.firebase.google.com → Agregar proyecto (sin Google Analytics).
2. Build → Authentication → Comenzar → Método de acceso → **Anónimo** → Habilitar.
3. Build → Firestore Database → Crear base de datos → ubicación `southamerica-east1` (o `us-east1`) → modo producción.
4. Firestore → pestaña **Reglas** → pegar `firestore.rules` → Publicar.
5. ⚙️ Configuración del proyecto → Tus apps → icono Web `</>` → registrar → copiar `firebaseConfig` a `firebase-config.js`.
6. Authentication → Configuración → **Dominios autorizados** → agregar `TU-USUARIO.github.io`.

## Primer uso
- La primera persona abre la app → escribe su nombre → **Crear familia nueva**.
- Menú ⋯ → **Invitar por WhatsApp**. Los demás abren el link, escriben su nombre y tocan **Unirme**.
- En cada celular: "Agregar a pantalla de inicio".

## Quién está en línea (solo la mamá)
- Quien crea la familia queda como administradora. En familias ya creadas, la mamá toca una vez **Menú ⋯ → 👑 Soy la mamá: activar mis opciones**.
- Después ve en el menú **🟢 Quién está en línea**: quién tiene la app abierta ahora y hace cuánto entró cada uno.
- Los demás cuidadores no ven esa lista (lo bloquean las reglas de Firestore).

## Avisos para la mamá (notificaciones)
El archivo `worker/avisos.js` va en Cloudflare Workers (plan gratis) y cada 10 minutos revisa los registros y le avisa solo a la administradora: hora de comer, revisar el pañal, mucho tiempo despierta y que ya se va a despertar.
Necesita: la clave pública de Cloud Messaging en `firebase-config.js` (`FIREBASE_VAPID`), los secretos `FAM` y `SA_JSON` en el Worker y un Cron Trigger `*/10 * * * *`. En la app: Menú ⋯ → 🔔 Activar avisos.

## Privacidad
El audio se analiza en el celular y nunca se sube. En la nube solo quedan horas, etiquetas, el nombre de quien registró y unos números que describen el llanto. Solo entra quien tenga el código de familia.
