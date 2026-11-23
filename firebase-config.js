// Configuración del proyecto Firebase "app-llanto".
// Estos datos no son secretos; la seguridad la dan las reglas de Firestore (firestore.rules).
window.FIREBASE_CONFIG = {
  apiKey: "AIzaSyBX5ugBzv-li6Vb_hai7NkhGsr-Krrj7U0",
  authDomain: "app-llanto.firebaseapp.com",
  projectId: "app-llanto",
  storageBucket: "app-llanto.firebasestorage.app",
  messagingSenderId: "60177458911",
  appId: "1:60177458911:web:bed062e103bced8ebfeb6a"
};

// Clave pública para los avisos (Firebase → Configuración del proyecto → Cloud Messaging → Certificados push web).
// No es secreta. Mientras esté vacía, el botón de avisos no aparece.
window.FIREBASE_VAPID = '';
