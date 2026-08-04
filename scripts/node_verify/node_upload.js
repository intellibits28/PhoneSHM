const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const fs = require("fs");

async function main() {
  const args = process.argv.slice(2);
  const sessionId = args[0];
  const binFile = args[1];
  const metaFile = args[2];

  if (!sessionId || !binFile || !metaFile) {
    console.error("Usage: node node_upload.js <session-id> <bin-file> <meta-file>");
    process.exit(1);
  }

  initializeApp({
    projectId: 'phoneshm-research'
  });
  const db = getFirestore();

  const metaJson = JSON.parse(fs.readFileSync(metaFile, "utf-8"));
  const metadata = metaJson.metadata || {};
  metadata.sessionId = sessionId;

  const binData = fs.readFileSync(binFile);
  const chunkSize = 500 * 1024;
  const fileLength = binData.length;
  const totalChunks = fileLength === 0 ? 0 : Math.ceil(fileLength / chunkSize);

  metadata.totalChunks = totalChunks;

  console.log(`Uploading ${totalChunks} chunks for session ${sessionId}...`);

  for (let i = 0; i < totalChunks; i++) {
    const start = i * chunkSize;
    const end = Math.min((i + 1) * chunkSize, fileLength);
    const chunkBytes = binData.slice(start, end);
    const base64Data = chunkBytes.toString("base64");

    await db.collection("sessions").doc(sessionId).collection("chunks").doc(i.toString()).set({
      data: base64Data,
      chunkIndex: i,
      totalChunks: totalChunks
    });
    console.log(`Uploaded chunk ${i}`);
  }

  await db.collection("sessions").doc(sessionId).set(metadata);
  console.log("Uploaded metadata");
}

main().catch(console.error);
