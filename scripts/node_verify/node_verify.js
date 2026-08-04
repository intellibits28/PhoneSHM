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
  
  if (!sessionDoc.exists) {
    console.error(`Session document ${sessionId} not found!`);
    process.exit(1);
  }
  
  const metadata = sessionDoc.data();
  const totalChunks = metadata.totalChunks;
  if (totalChunks === undefined) {
    console.error("totalChunks not found in metadata");
    process.exit(1);
  }

  console.log(`Metadata indicates ${totalChunks} chunks.`);
  
  const chunksSnapshot = await db.collection("sessions").doc(sessionId).collection("chunks").get();
  if (chunksSnapshot.size !== totalChunks) {
    console.error(`Expected ${totalChunks} chunks but found ${chunksSnapshot.size}`);
    process.exit(1);
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
