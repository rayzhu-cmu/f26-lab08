# Repository conventions

Rules for anyone working in this repository. Every rule here is
true of the code today. If a change would break one of these, the change is
wrong, not the rule.

`CLAUDE.md` imports this file so Claude Code loads these rules every session
with no setup. Any other tool reads this file directly.

## 1. Module dependency rules

These are the only allowed edges. A module may use its own dependencies and
nothing else. Adding an edge means editing this list first and saying why.

| Module | May depend on |
|---|---|
| `core-domain` | nothing |
| `storage` | `core-domain` |
| `notifications` | `core-domain` |
| `accounts` | `core-domain` |
| `billing` | `core-domain`, `storage` |
| `http-api` | `core-domain`, `storage`, `notifications`, `accounts` |
| `cli` | `core-domain`, `storage`, `billing` |

Nothing depends on `http-api`. The Python `analytics` package depends on no Java
module at all (rule 6).

`core-domain` declares the store interfaces it needs under
`core/ports/`. `storage` implements them. That is why `storage` depends on
`core-domain`.

## 2. Time semantics

- Intervals are half-open, `[start, end)`. A booking that ends at 11:00 and one
  that starts at 11:00 do not collide.
- Every instant falls on a whole minute. `TimeInterval` rejects seconds and
  nanoseconds outright.
- Everything is UTC. There is no local time zone anywhere in the domain, and no
  code should introduce one. Rendering for a person is the caller's problem.
- A booking stays inside one UTC day. The reports rely on this, so do not relax
  it without reading `analytics/` first.

## 3. One conflict predicate

`TimeInterval.overlaps` in `core-domain` is the only overlap implementation in
the platform. Room conflict checks, user double-booking checks, availability,
and waitlist promotion all route through it, most of them by way of
`ConflictChecker`.

Never write a second one. If you find yourself comparing two starts and two ends
anywhere else, you are writing the second one.

## 4. Waitlist writes

Waitlist rows are written in exactly one class: `BookingService` in
`core-domain`. Nothing else in production code calls `WaitlistStore.save` or
`WaitlistStore.remove`. Storage's own tests do, because that is how you test a
store against its interface. (Storage's other plumbing is not a booking write
path either.
`StoreRegistry.clearStores` empties every store at once for a caller that wants
a clean platform, but it never decides who holds a slot.)

Promotion is first in, first out by arrival sequence, and every candidate is
re-checked against the room's current bookings and against the waiter's own
calendar before it is promoted. A promotion can never create a double booking.
Keep both of those properties in whatever you add.

## 5. Notifications are fire and forget

A booking flow never depends on a notification succeeding. `BookingService`
swallows publisher failures, and `EventLogPublisher` records a failing
subscriber and carries on to the next one. Do not make a booking path wait on,
or fail on, delivery.

## 6. The event log has one writer and one reader

- Only `storage` writes the event log. `EventLogFormat` is the single place the
  line shape is decided, and the event types (currently `booking-created`,
  `booking-cancelled`, `waitlist-promoted`, and `booking-modified`) are declared in `core-domain`.
  The modification event is declared by Task 1; its log-format registration and
  Python reader support are pending Task 2, so the full event contract is not yet complete.
- Only `analytics` reads `events.jsonl`. The Python package never opens a Java
  store, never imports Java, and never writes the log back.
- The Java side writes JSON by hand and parses none. There is no JSON library in
  this build. Do not add one.

Adding an event type, or a field to a log line, changes a published contract.
Change `EventLogFormat` and `analytics` as part of the same change, add new
tests beside the shipped ones in both modules, and update the event-type lists in
this file and in `ARCHITECTURE.md` so they stay true. Separate tasks are fine.
What matters is that both gates are green before the change is done.

## 7. Behavior changes come with a test in the owning module

Every change to behavior comes with a test in the module that owns the
behavior, and that module's gate goes green before the change is done. Do not
edit a shipped test to make a gate pass.

Per-module gates, run from the repository root:

```
mvn -q -pl core-domain -am test
mvn -q -pl storage -am test
mvn -q -pl notifications -am test
mvn -q -pl accounts -am test
mvn -q -pl billing -am test
mvn -q -pl http-api -am test
mvn -q -pl cli -am test
```

The Python gate, run from `analytics/`:

```
pytest
```

The whole build, run from the repository root:

```
mvn -B test
```
