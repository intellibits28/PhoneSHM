const admin = require("firebase-admin");
try {
  admin.initializeApp();
  console.log("Firebase initialized");
} catch(e) {
  console.log("Error initializing Firebase:", e.message);
}
