const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

async function main() {
  initializeApp({
    projectId: 'phoneshm-research'
  });
  const db = getFirestore();
  const sessionId = "724d53b9-a1d2-4456-9999-8560b2791691";
  
  const chunksSnapshot = await db.collection("sessions").doc(sessionId).collection("chunks").get();
  console.log(`Found ${chunksSnapshot.size} chunks.`);
  chunksSnapshot.forEach(doc => {
    console.log(`Chunk document ID: ${doc.id}, chunkIndex: ${doc.data().chunkIndex}`);
  });
}

main().catch(console.error);
