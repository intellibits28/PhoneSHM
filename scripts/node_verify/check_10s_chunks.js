const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

async function main() {
  initializeApp({
    projectId: 'phoneshm-research'
  });
  const db = getFirestore();
  const sessionId = "dd313e1f-f7fd-4230-8efb-539ab2ebb4bb";
  
  const chunksSnapshot = await db.collection("sessions").doc(sessionId).collection("chunks").get();
  console.log(`Found ${chunksSnapshot.size} chunks for 10s session.`);
}

main().catch(console.error);
