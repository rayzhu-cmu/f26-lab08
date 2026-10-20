# PLAN.md

## Milestone 1: The plan

Prepared before implementation. This plan covers the full change request; the lab run will implement exactly Task 1. Tasks 2–4 remain planned work, so completing Task 1 does not claim that the complete feature is ready.

### Task 1 — Modify a booking safely in the domain

**Behavior and scope.** In `core-domain`, allow an owner to change the interval, attendee count, or both of an upcoming confirmed booking without changing its ID, room, owner, or creation time. Add the service operation, result type, immutable history entries, and `BookingModifiedEvent`. Primary locations are `core/service/BookingService.java`, `core/domain/Booking.java`, `core/policy/ConflictChecker.java`, and `core/events/`. Add tests under `core-domain/src/test/`; do not edit shipped tests. Update the event-type lists in `AGENTS.md` and `ARCHITECTURE.md` when the event is introduced, distinguishing its domain declaration from the log integration still pending in Task 2. Dependencies: none.

**Design decisions.** Store history as an immutable list on `Booking`; each entry contains the old and new interval, old and new attendee count, and modification time from the injected clock. Preserve existing constructor/factory call sites and retain history when cancelling. Return a typed success/rejection result using existing `RejectionReason` values where applicable. Check ownership and upcoming/confirmed status before accepting a no-op; an unchanged request succeeds without a write, history entry, event, or promotion pass. A real change must pass `BookingRules.checkRequest`. Filter out the booking's ID from both room and owner calendars before calling the existing conflict checker; waive only the owner's booking-count limit. Validate before saving anything. Save the updated booking before promoting waiters. Reuse `BookingService`'s promotion pass whenever the interval changes, including extension-only changes; attendee-only changes do not run it. Publish old and new details through the existing fire-and-forget path.

**Checkpoint.** From the repository root, run `mvn -pl core-domain -am test`. New tests must demonstrate shortening, extension and movement; a self-overlapping move and adjacent intervals; rejection of another room booking or the owner's other booking without changing bookings, history, waitlist or events; all request-rule restrictions; missing/non-owner/started/cancelled cases; successful modification at the booking-count cap; ordered history across repeated modifications; no-op silence; FIFO promotion with both calendars rechecked; no promotion pass for attendee-only changes; and publisher failure without undoing success. The core-domain test count must increase and failures/errors must be zero. Review the changed source for the architecture constraints below, then run `mvn -B clean test` to check downstream compatibility.

**A green-gate blind spot.** Tests might verify the latest history entry but miss aliasing: an earlier returned booking's history could change after a second modification. The refuter should retain an earlier booking reference and try repeated modifications and attempted list mutation.

**Scope correction discovered during Task 1's cold build (October 20).** The original core-only implementation passed its gate but broke the exhaustive sealed-event switches in `notifications/NotificationKind.java` and `NotificationService.java`. Preserve the first failed build in `evidence/task1-clean-build-initial-failure.log`. Before repairing it, expand Task 1 only to add explicit unsupported-modification branches in these two switches and a new notification test proving that the existing skipped-event mechanism records the pending support without escaping into booking flows. Task 3 still owns the actual notification kind, values and template, and must replace this temporary pending behavior and its new test. Add `mvn -pl notifications -am test` to this revised Task 1 checkpoint. The three excluded modules and guardrail remain unchanged; do not weaken the sealed hierarchy or add a catch-all default.

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

**Recipe — configured and demonstrated on October 20, 2026.** Recipe C from `guardrails/README.md`. `guardrails/protected-trees.txt` now lists `billing`, `cli`, and `accounts`. Ran `guardrails/lockdown.sh --list` and `guardrails/lockdown.sh`, which reported all three trees locked. Exact protected paths: `billing/src/`, `billing/pom.xml`, `cli/src/`, `cli/pom.xml`, `accounts/src/`, and `accounts/pom.xml`. Their `target/` directories remain writable. `PLAN.md`, the guardrail configuration/evidence, and the later README disclosure are lab bookkeeping outside feature-code scope.

**Captured block.** See [the actual agent-turn excerpt](evidence/guardrail-block.md): it includes the user's request, the agent's announced concrete write attempt, the actual tool invocation, and its returned traceback. The agent attempted to append a harmless comment to `billing/src/main/java/edu/cmu/cs214/campusreserve/billing/InvoiceOutcome.java`, without changing permissions. The filesystem returned `PermissionError: [Errno 13] Permission denied` and exit code 1. This was a real write attempt, not a voluntary refusal. Recipe C was exercised through a shell subprocess; the Claude-specific Manual-mode setup for Recipes A/B was not applicable.

**Unchanged-file verification.** The SHA-256 before and after was `b86cce3a5af06118da1820c8f101fd353c466c6c5015e57e5083fe9248370ef6`. `git diff --exit-code -- billing cli accounts` exited zero with no output. Permission inspection found zero writable protected paths across billing (40 paths), cli (38), and accounts (34); all three existing `target/` directories retained owner write permission. No build result is claimed for this configuration-only step.

**Limit and current state.** The lock catches accidental writes to protected paths, not incorrect behavior in allowed files, and an agent with shell access could undo it with chmod. Pair it with a task-scope diff audit. The lock remained active through Task 1, refuter verification and the final cold build. After the supervised work finished, `guardrails/unlock.sh` restored write permissions to all three modules ([unlock log](evidence/guardrail-unlock.log)). Git does not preserve these write-permission bits, so a fresh checkout needs the lock script run again.

## Milestone 3: The run

**Selected task.** Task 1 only: safe domain-level booking modification, history, promotion and the domain event, with new tests and accurate event-declaration documentation. Includes the recorded minimal notification-switch compatibility correction, not Task 3 notification delivery. Tasks 2–4 were not executed.

**Takeover point — specified before execution.** If the agent edits a shipped test to make the gate pass, duplicates overlap arithmetic outside `TimeInterval.overlaps`, or adds a production waitlist write outside `BookingService`, I will stop it, inspect and revert the offending change, and implement that correction myself with a new regression test before rerunning the gate. If it tries to remove the filesystem lock, I will stop the run and restore the boundary before continuing.

**Evidence — executed October 20.**

- `mvn -pl core-domain -am test`: `[INFO] Tests run: 196, Failures: 0, Errors: 0, Skipped: 0` and `[INFO] BUILD SUCCESS` ([log](evidence/task1-gate.log)); 29 new core tests above the starter's 167.
- Revised-scope gate `mvn -pl notifications -am test`: notifications `[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0` and `[INFO] BUILD SUCCESS` ([log](evidence/task1-notifications-gate.log)); one new compatibility test.
- Closing `mvn -B clean test`: `[INFO] BUILD SUCCESS`; all seven modules succeeded, 448 Java tests total, zero failures/errors ([log](evidence/task1-clean-build.log)).
- The first cold build failed on the newly non-exhaustive notification switch ([original failure](evidence/task1-clean-build-initial-failure.log)); the scope correction above explains the repair. This is why the module gate alone was insufficient. No Python production code changed; no Python run is claimed in this task.
- Guardrail remained active. No shipped test was modified, no protected module content changed, and `git diff --check` passed. No stated takeover trigger occurred.

**Diff — agent audit prepared for student review.** New source/test files were included using `git add -N`; this does not commit them. Paths below use the package suffix under each module's `src/main/java/edu/cmu/cs214/campusreserve/`, unless identified as tests.

| File | Mapping to scope |
|---|---|
| `core-domain/.../core/domain/Booking.java` | Immutable history, compatibility constructor, modified copies, history-preserving cancellation. |
| `core-domain/.../core/domain/BookingModification.java` | Old/new details and timestamp for one history entry. |
| `core-domain/.../core/domain/ModificationResult.java` | Typed success, promotions and rejection reason. |
| `core-domain/.../core/events/BookingModifiedEvent.java` | New state plus old details for downstream consumers. |
| `core-domain/.../core/events/DomainEvent.java` | Permits the new event in the sealed hierarchy. |
| `core-domain/.../core/service/BookingService.java` | Checks before writes, self-exclusion, save/event/promotion sequence, no-op handling. |
| `core-domain/src/test/.../core/service/BookingServiceModifyTest.java` | 29 new test cases covering rules, atomic rejection, history, conflict boundaries, promotion and publisher failure. |
| `notifications/.../notifications/NotificationKind.java` | Unexpected original scope dependency: explicit pending-support branch restores exhaustive compilation. |
| `notifications/.../notifications/NotificationService.java` | Same sealed-switch compatibility correction, with explicit pending support. |
| `notifications/src/test/.../notifications/ModificationPendingTest.java` | One new test verifies pending support is recorded as skipped without emitting an incorrect message or throwing into booking flows. |
| `AGENTS.md`, `ARCHITECTURE.md` | Event declaration lists updated; wire integration and HTTP support explicitly marked pending. |
| `PLAN.md`, `evidence/task1-*.log` | Lab plan correction and actual execution evidence. |

The notification files were the only unexpected feature-code expansion and are explained in the dated scope correction. There were no changes to shipped tests, module dependency declarations, `billing`, `cli`, `accounts`, `storage`, `analytics`, or `http-api`.

**Line selected for close review:** `.filter(booking -> !booking.id().equals(bookingId)).toList();` in both room and owner checks in `BookingService`. The audit checks that only this booking is excluded, both calendars still use `ConflictChecker`, and no duplicate overlap arithmetic was introduced. All new conflict checks flow through the existing predicate; production waitlist writes remain in `BookingService`. Save occurs before the reused promotion pass so it sees the new interval. The student must personally inspect this line and the full diff before presenting this as their own review.

**Refuter brief and context isolation.** A separate refuter was started without inherited implementation conversation after the closing clean build. Its initial materials were only `CHANGE-REQUEST.md`, the saved task diff, and the scope/request. It was allowed to inspect relevant existing source to validate claims, but not `PLAN.md` or implementation logs. The [actual prompt](evidence/refuter-brief.md) and [actual response and settlement](evidence/refuter-result.md) are saved; the task diff is the pre-refuter snapshot.

**Finding settled.** No concrete defect was established. The strongest suspicion was that a later modification could mutate a retained earlier history snapshot. The refuter rejected that claim by inspecting the copying and immutable fields, then proposed an accepted → rejected → accepted sequence for stronger evidence. Added `core-domain/src/test/java/edu/cmu/cs214/campusreserve/core/service/ModificationRefuterTest.java` and verified that rejection preserves bookings/waitlist/events, retained history is unchanged after another successful change, and the final history has exactly two accepted changes. This new test is the only post-review code addition; no production or shipped test was changed.

**Verification evidence.** `mvn -pl core-domain -am -Dtest=ModificationRefuterTest test` returned `[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` and `[INFO] BUILD SUCCESS` ([log](evidence/refuter-verification.log)). This dismisses the concrete suspicion under clauses 2 and 5; it is not a claim that the whole feature has no bugs. No failure was fabricated. The refuter created no temporary probe files. Keep the new test as regression coverage.

**Final post-refuter build and cleanup.** After adding the verification test, `mvn -B clean test` passed again: core-domain 197 tests; all seven modules 449 tests total, zero failures/errors ([log](evidence/post-refuter-clean-build.log)). The earlier 448-test log remains the pre-refuter checkpoint. The filesystem lock stayed active through this final build and was then released using the supplied unlock script. Protected module contents remain unchanged. The README tooling disclosure and refuter evidence are lab bookkeeping additions.

**Before recitation:** The README records Codex desktop and GPT-6 (model name confirmed by the student). All three milestone records and the refuter settlement are now populated. The student must still personally review the diff and the selected line, understand the recorded scope correction, and commit/push the work to their fork for the TA. No human review, commit, push or TA acceptance is claimed by this document.
