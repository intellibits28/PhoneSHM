const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const fs = require("fs");
const crypto = require("crypto");

async function main() {
  const args = process.argv.slice(2);
  const sessionId = args[0];
  const originalFile = args[1];

  if (!sessionId || !originalFile) {
    console.error("Usage: node node_verify.js <session-id> <original-file>");
    process.exit(1);
  }

  initializeApp({
    projectId: 'phoneshm-research'
  });
  const db = getFirestore();

  console.log(`Fetching session metadata for ${sessionId}...`);
  const sessionDoc = await db.collection("sessions").doc(sessionId).get();
  
  let expectedChunks = -1;
  if (!sessionDoc.exists) {
    console.log(`⚠️ Parent document ${sessionId} not found!`);
    console.log(`(This can happen if the background worker was killed after uploading chunks but before saving metadata).`);
    console.log(`Attempting to verify based on existing chunks...`);
  } else {
    const metadata = sessionDoc.data();
    expectedChunks = metadata.totalChunks;
    console.log(`Metadata indicates ${expectedChunks} chunks.`);
  }

  const chunksSnapshot = await db.collection("sessions").doc(sessionId).collection("chunks").get();
  const foundChunks = chunksSnapshot.size;
  
  if (foundChunks === 0) {
    console.error("No chunks found in subcollection!");
    process.exit(1);
  }

  if (expectedChunks !== -1 && foundChunks !== expectedChunks) {
    console.error(`Expected ${expectedChunks} chunks but found ${foundChunks}`);
    process.exit(1);
  } else if (expectedChunks === -1) {
    console.log(`Found ${foundChunks} chunks in subcollection.`);
  }
  
  const chunks = [];
  chunksSnapshot.forEach(doc => {
    chunks.push(doc.data());
  });
  
  chunks.sort((a, b) => a.chunkIndex - b.chunkIndex);
  
  const hasher = crypto.createHash('sha256');
  for (let i = 0; i < chunks.length; i++) {
    const chunk = chunks[i];
    if (chunk.chunkIndex !== i) {
      console.error(`Missing chunk index ${i}`);
      process.exit(1);
    }
    const buf = Buffer.from(chunk.data, "base64");
    hasher.update(buf);
  }
  
  const finalHash = hasher.digest("hex");
  console.log(`Reconstructed SHA-256: ${finalHash}`);
  
  console.log(`\nComparing with original file: ${originalFile}`);
  const origBuffer = fs.readFileSync(originalFile);
  const origHash = crypto.createHash("sha256").update(origBuffer).digest("hex");
  console.log(`Original SHA-256:      ${origHash}`);
  
  if (finalHash === origHash) {
    console.log("\nMATCH — reconstruction equals original");
  } else {
    console.log("\nMISMATCH — reconstruction does not equal original");
    process.exit(1);
  }
}

main().catch(console.error);
