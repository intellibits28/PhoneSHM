const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

async function main() {
  initializeApp({
    projectId: 'phoneshm-research'
  });
  const db = getFirestore();
  const sessions = await db.collection("sessions").get();
  const ids = [];
  sessions.forEach(doc => {
    ids.push(doc.id);
  });
  console.log("Sessions:", JSON.stringify(ids));
}

main().catch(console.error);
