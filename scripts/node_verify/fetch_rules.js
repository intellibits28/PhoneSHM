const { initializeApp } = require("firebase-admin/app");
const { getSecurityRules } = require("firebase-admin/security-rules");

async function main() {
  initializeApp({
    projectId: 'phoneshm-research'
  });
  const securityRules = getSecurityRules();
  const ruleset = await securityRules.getFirestoreRuleset();
  console.log("=== LIVE FIRESTORE RULES ===");
  console.log(ruleset.source.files[0].content);
}

main().catch(console.error);
