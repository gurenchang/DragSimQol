# Mining Bot review progress

2026-09-20

- Fixed `MiningAttemptLedger.confirm`: it now reads without removing and rejects `now >= deadline`; the record remains available for the shared expiry path.
- Added regression coverage for deadline-exact and deadline-late updates, EARLY expiry, `recordEarlyFailure`, and lead reset.
- Latest verification: `cleanTest test build` passed with 164 tests, zero failures/errors/skips; `git diff --check` reported no errors (only existing line-ending warnings).
- This remains client-observed block-state timing, not server-side break time. Real Minecraft/server and Sodium/Iris verification remain outstanding.
