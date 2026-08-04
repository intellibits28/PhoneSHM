const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

async function main() {
  initializeApp({
    projectId: 'phoneshm-research'
  });
  const db = getFirestore();

  console.log(`Fetching all sessions...`);
  const sessionsSnapshot = await db.collection("sessions").orderBy("uploadTimestamp", "desc").limit(10).get();
  
  if (sessionsSnapshot.empty) {
    console.log("No sessions found.");
    return;
  }

  sessionsSnapshot.forEach(doc => {
    console.log(`Session: ${doc.id}`);
    console.log(JSON.stringify(doc.data(), null, 2));
    console.log("--------------------------------------------------");
  });
}

main().catch(console.error);
