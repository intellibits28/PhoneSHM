const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

async function main() {
  initializeApp({
    projectId: 'phoneshm-research'
  });
  const db = getFirestore();
  const sessionId = "724d53b9-a1d2-4456-9999-8560b2791691";
  
  const chunkDoc = await db.collection("sessions").doc(sessionId).collection("chunks").doc("0").get();
  if (chunkDoc.exists) {
    const data = chunkDoc.data().data;
    console.log(`Chunk 0 length: ${data.length} characters`);
  } else {
    console.log("Chunk 0 not found");
  }
}

main().catch(console.error);
