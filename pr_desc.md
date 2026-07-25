## Summary
Fixes F1 (critical f0/classifier value-mismatch bug) and hardens the
baseline data-integrity path (B5/Item 3/4) with a Room/SQLite migration.

## Included fixes
- F1: root-cause fix — single-source-of-truth f0 via evaluatePhysics
  lambda injection (core/modal)
- Item B: baseline RESET action fully gated by BuildConfig.DEBUG
  (button + dialog), confirmation dialog, audit logging
- Item C: real-incident reset — NOT performed by this agent (no device
  DB access); see handoff instructions in PR comments
- Item D: qualityScorePct<50 gate now covers history ring-buffer, not
  just Welford mean/std
- Item E: BaselineProfile/BaselineHistoryEntry migrated from flat-file
  to Room entities per Architectural Principle #3

## Manual verification needed post-merge
- [ ] User to reset the actual incident buildingHash on-device using the
      new debug-only RESET action (see Item C instructions)
