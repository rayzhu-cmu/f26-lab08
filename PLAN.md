# PLAN.md

## Milestone 1: The plan

Prepared before implementation. This plan covers the full change request; the lab run will implement exactly Task 1. Tasks 2–4 remain planned work, so completing Task 1 does not claim that the complete feature is ready.

### Task 1 — Modify a booking safely in the domain

**Behavior and scope.** In `core-domain`, allow an owner to change the interval, attendee count, or both of an upcoming confirmed booking without changing its ID, room, owner, or creation time. Add the service operation, result type, immutable history entries, and `BookingModifiedEvent`. Primary locations are `core/service/BookingService.java`, `core/domain/Booking.java`, `core/policy/ConflictChecker.java`, and `core/events/`. Add tests under `core-domain/src/test/`; do not edit shipped tests. Update the event-type lists in `AGENTS.md` and `ARCHITECTURE.md` when the event is introduced, distinguishing its domain declaration from the log integration still pending in Task 2. Dependencies: none.

**Design decisions.** Store history as an immutable list on `Booking`; each entry contains the old and new interval, old and new attendee count, and modification time from the injected clock. Preserve existing constructor/factory call sites and retain history when cancelling. Return a typed success/rejection result using existing `RejectionReason` values where applicable. Check ownership and upcoming/confirmed status before accepting a no-op; an unchanged request succeeds without a write, history entry, event, or promotion pass. A real change must pass `BookingRules.checkRequest`. Filter out the booking's ID from both room and owner calendars before calling the existing conflict checker; waive only the owner's booking-count limit. Validate before saving anything. Save the updated booking before promoting waiters. Reuse `BookingService`'s promotion pass whenever the interval changes, including extension-only changes; attendee-only changes do not run it. Publish old and new details through the existing fire-and-forget path.

**Checkpoint.** From the repository root, run `mvn -pl core-domain -am test`. New tests must demonstrate shortening, extension and movement; a self-overlapping move and adjacent intervals; rejection of another room booking or the owner's other booking without changing bookings, history, waitlist or events; all request-rule restrictions; missing/non-owner/started/cancelled cases; successful modification at the booking-count cap; ordered history across repeated modifications; no-op silence; FIFO promotion with both calendars rechecked; no promotion pass for attendee-only changes; and publisher failure without undoing success. The core-domain test count must increase and failures/errors must be zero. Review the changed source for the architecture constraints below, then run `mvn -B clean test` to check downstream compatibility.

**A green-gate blind spot.** Tests might verify the latest history entry but miss aliasing: an earlier returned booking's history could change after a second modification. The refuter should retain an earlier booking reference and try repeated modifications and attempted list mutation.

### Task 2 — Carry modifications through the event log and utilization ledger

**Behavior and scope.** In `storage/EventLogFormat.java`, recognize and serialize `booking-modified`, keeping the common fields as the new booking state and adding the old details consistently with Task 1. In `analytics/campusreserve_analytics/events.py`, parse the event and update the shared ledger by booking ID rather than adding another booking. Adjust utilization only if necessary. Add new tests/fixtures in both modules, and finish the event-contract descriptions in `AGENTS.md` and `ARCHITECTURE.md`. Dependencies: Task 1 and its event fields. Do not add a Java JSON dependency or a second report-specific ledger.

**Checkpoint.** Use one combined acceptance checkpoint: `mvn -pl storage -am test`, followed by `(cd analytics && ../.venv/bin/pytest)`, with both required to pass. New writer and reader tests use the same agreed wire example. Verify create → modify → modify counts only the latest interval once, movement between UTC days changes the utilization day, attendee-only changes do not add time, and a later cancellation removes the booking from active utilization. Existing logs remain readable, and both reports continue consuming the same ledger. Confirm both modules' test counts increase.

**A green-gate blind spot.** Separately handwritten Java and Python fixtures could agree with their own implementations while disagreeing with each other; inspect an actual Java-emitted line against the Python reader before accepting the task.

### Task 3 — Notify the owner of old and new details

**Behavior and scope.** In `notifications/src/main/`, connect `BookingModifiedEvent` to a modification template and the existing outbox path. Include both intervals and both attendee counts. Preserve promotion notifications and delivery retry behavior. Add new tests under `notifications/src/test/`. Dependencies: Task 1; Task 2 is not needed for this module's gate.

**Checkpoint.** Run `mvn -pl notifications -am test`. New tests inspect the actual outbox recipient and rendered message for a modification event, verify both old/new details, and check that failed delivery follows the existing retry behavior. Existing promotion tests remain green. Task 1's no-op test establishes that no modification event is sent for an unchanged request; its publisher-failure test establishes booking independence from notification success.

**A green-gate blind spot.** A template unit test could pass while the event dispatch branch never selects it; test through the notification service, not only the template.

### Task 4 — Expose owner-only PATCH and readable history

**Behavior and scope.** In `http-api`, extend routing, `BookingsHandler`, and `JsonViews` to accept `PATCH /bookings/{bookingId}` and expose history on the existing booking read surface. Reuse `RequestAuth` and `ErrorMapping`; no account-management change or history endpoint. Use existing form/query input conventions. Omitted interval or attendee fields retain their current values; require start/end together when changing the interval. Successful changes and no-ops return 200. Malformed input returns 400, missing/invalid credentials 401, non-owner actions 403, missing bookings 404, and state/calendar conflicts 409 under the existing mapping. An administrator may read another owner's history but cannot modify that booking. Update the HTTP table in `ARCHITECTURE.md`. Dependencies: Tasks 1–3 for the complete exposed behavior.

**Checkpoint.** Run `mvn -pl http-api -am test`. Add endpoint tests for interval-only, attendee-only and combined changes; malformed/partial intervals; the stated error cases; administrator read versus modification permissions; and history in the existing GET response. Verify a rejected PATCH leaves GET unchanged, a no-op adds no history, and a shortened booking can promote a waiter. Run the complete Java and Python suites before declaring the full change request complete.

**A green-gate blind spot.** Handler tests using a manually constructed context could pass even if the actual router does not register PATCH; include a request through the running test server.

**AGENTS.md rules 3 and 4.** Task 1's checkpoint includes a source/diff audit that all interval-overlap decisions still reach `TimeInterval.overlaps`, and all production waitlist `save`/`remove` calls remain in `BookingService`; behavior tests alone cannot prove these structural rules. Repeat that audit for later tasks' diffs.

**Out of scope, with reasons.** `billing`: the change request explicitly freezes the original price and forbids re-invoicing. `cli`: it explicitly requests no command changes. `accounts`: it explicitly reuses existing tokens and ownership checks. Moving rooms is excluded. Revenue-report logic is excluded, but the report must still read the same updated ledger; do not filter modified bookings or fork parsing. No new module dependency edges (AGENTS rule 1), duplicate overlap predicate (rule 3), notification dependency on successful delivery (rule 5), Java JSON library (rule 6), or edits to shipped tests (rule 7).

## Milestone 2: The guardrail

**Planned recipe — not yet configured or demonstrated.** Use Recipe C from `guardrails/README.md`. Replace the placeholder in `guardrails/protected-trees.txt` with `billing`, `cli`, and `accounts`. It will protect each module's `src/` tree and `pom.xml`, leaving `target/` writable. Run `guardrails/lockdown.sh --list`, then `guardrails/lockdown.sh` before the task. `PLAN.md`, guardrail configuration, and the later README tool/model disclosure are lab bookkeeping outside feature-code scope.

**Demonstration to perform.** Select an existing source file under `billing/src/`. Ask the agent to add a harmless comment without changing permissions. Capture the request, the agent's actual tool call, and the permission-denied result together. Verify the file is unchanged. A voluntary refusal or a bare terminal error is not the required evidence. Do not unlock or bypass the boundary during the task; run `guardrails/unlock.sh` after the supervised work.

**Limit.** This lock catches accidental writes to those paths, not incorrect behavior in allowed files, and an agent with shell access could undo it with chmod. Pair it with the task-scope diff audit.

**Captured block:** PENDING — paste the actual agent-turn excerpt after the demonstration.

**Unchanged-file verification:** PENDING — record the actual check and result.

## Milestone 3: The run

**Selected task.** Task 1 only: implement safe domain-level booking modification, history, promotion and the domain event, with new core-domain tests and accurate event-declaration documentation. Do not proceed to Tasks 2–4.

**Takeover point — specified before execution.** If the agent edits a shipped test to make the gate pass, duplicates overlap arithmetic outside `TimeInterval.overlaps`, or adds a production waitlist write outside `BookingService`, I will stop it, inspect and revert the offending change, and implement that correction myself with a new regression test before rerunning the gate. If it tries to remove the filesystem lock, I will stop the run and restore the boundary before continuing.

**Evidence:** PENDING — after implementation, paste the core-domain result line from `mvn -pl core-domain -am test` and the final result line from `mvn -B clean test`. Record actual counts and results, not expected ones.

**Diff:** PENDING — run `git add -N .` and inspect `git diff --stat` and `git diff`. List each changed/new file and its Task 1 purpose. Record unexpected changes and whether any shipped test was touched. Quote the one line read most carefully and explain the invariant checked.

**Prepared refuter brief — not yet sent:**

> Review only Task 1: safe domain-level booking modification, history, promotion and its domain event. You receive CHANGE-REQUEST.md, the complete task diff, and this one-line scope, without the implementation session's conversation. Find concrete inputs or call sequences that violate this scope despite a green module gate. Start with repeated modifications and retained history references, then examine rejection atomicity, self-conflicts, no-ops and waitlist rechecks. Give a minimal reproducer and the relevant requirement for your strongest finding. Do not treat unimplemented HTTP, notification or log-reader integration as Task 1 defects, and do not modify shipped tests.

**Refuter execution and finding:** PENDING — after the closing build, start a fresh session with only the stated materials and record the actual brief used. Settle one finding with a new failing test and its red output, or dismiss it with a test, run, or exact requirement. If all claims fail, document the check of the strongest claim. A confirmed finding need not be fixed or committed for this lab. Remove temporary probe files and preserve the evidence.

**Before recitation:** Add the actual tool/model names to the fork's README, save the evidence, and make the completed work available in the fork. These steps and Milestones 2–3 remain unfinished until their actual results are recorded.
