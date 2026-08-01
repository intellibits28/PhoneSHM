## Follow-up (non-blocking, tracked separately)
- [ ] Item A: add test case where persistence-adjusted f0 diverges from
      raw single-window peak (current test covers consistency but not
      divergence)
- [ ] Item E: confirm Room migration idempotency on repeated app launch;
      add real in-memory Room instance test (current tests use
      FakeBaselineDao); define behavior on migration parse failure
