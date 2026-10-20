# Architecture

CampusReserve is seven Maven modules plus a Python package. The Java side has no
runtime dependencies and the Python side is standard library only.

## Module graph

Arrows point from a module to what it depends on. These are all the edges there
are. `AGENTS.md` rule 1 states the same list, and the build enforces it through
the module poms.

Each block below names a module and then every module that depends on it. Read a
line as an arrow running from the module on the right up to the module heading
the block. `storage` under `core-domain` means storage depends on core-domain.

```
core-domain   (depends on nothing)
 ^  ^  ^  ^  ^  ^
 |  |  |  |  |  +--- cli
 |  |  |  |  +------ http-api
 |  |  |  +--------- billing
 |  |  +------------ accounts
 |  +--------------- notifications
 +------------------ storage

storage
 ^  ^  ^
 |  |  +--- cli
 |  +------ http-api
 +--------- billing

notifications          accounts               billing
 ^                      ^                      ^
 +--- http-api          +--- http-api          +--- cli
```

Nothing depends on `http-api` or on `cli`. They are the two entry points, and
they are where the wiring lives.

| Module | Depends on | Depended on by |
|---|---|---|
| `core-domain` | nothing | every other module |
| `storage` | `core-domain` | `billing`, `http-api`, `cli` |
| `notifications` | `core-domain` | `http-api` |
| `accounts` | `core-domain` | `http-api` |
| `billing` | `core-domain`, `storage` | `cli` |
| `http-api` | `core-domain`, `storage`, `notifications`, `accounts` | nothing |
| `cli` | `core-domain`, `storage`, `billing` | nothing |

`analytics/` is a Python package outside the Maven build. It depends on no Java
module. Its only input is `events.jsonl`.

## Which way the store dependency points

`core-domain` declares the store interfaces it needs, under
`core/ports/`: `RoomStore`, `UserStore`, `BookingStore`, `WaitlistStore`,
`EventPublisher`, and `IdGenerator`. `storage` implements them
(`InMemoryRoomStore` and the rest, all built on `InMemoryRecordStore`).

That is why the arrow runs from `storage` to `core-domain`. The domain says
what it needs, and storage supplies it. Swapping the
in-memory stores for something durable is a change to `storage` and to the
wiring, but to nothing in `core-domain`.

## Where the pieces of a booking live

`BookingService` in `core-domain` is the single entry point for the booking
flow. It is also the only production-code caller of `WaitlistStore.save` and
`WaitlistStore.remove`. Storage's own tests write through the interface, which is
how a store gets tested, and `StoreRegistry.clearStores` resets whole stores at
once, which is plumbing rather than a booking write path.

```
HTTP request                     CLI command
     |                                |
     v                                v
BookingsHandler                  BookCommand
     |   (owner check via accounts)   |
     +---------------+----------------+
                     v
              BookingService  ---> BookingRules      (duration, alignment,
                     |              BookingPolicy     opening hours, one UTC day)
                     |
                     +----------> ConflictChecker ---> TimeInterval.overlaps
                     |                                 (the only overlap
                     |                                  implementation)
                     +----------> BookingStore, WaitlistStore  (storage)
                     |
                     +----------> EventPublisher
                                        |
                                        v
                                EventLogPublisher (storage)
                                   |            |
                                   v            v
                              event log     NotificationService
                              (JSONL)       (outbox, fire and forget)
```

### Booking a room

1. The handler or command authenticates the caller and builds a
   `BookingRequest`.
2. `BookingService.requestBooking` checks that the room and user exist, runs
   `BookingRules`, checks the asker's own limits (booking cap, own calendar),
   and then asks `ConflictChecker` whether the room is free.
3. Free: a `Booking` is stored and a `booking-created` event is published.
4. Taken: a `WaitlistEntry` is stored with the next arrival number, and the
   caller is told they are waiting. No event is published for a waitlisting.

### Cancelling, and the promotion that follows

1. `BookingService.cancelBooking` checks ownership, that the booking still holds
   the room, and that it has not started.
2. The booking is stored as `CANCELLED` and a `booking-cancelled` event is
   published.
3. The room's waitlist is then walked in arrival order. Each entry is re-checked
   against the room's current bookings and against that waiter's own calendar
   before it is promoted, so a promotion can never create a double booking.
   Entries whose slot has passed, or whose room has left service, are dropped.
4. Each promotion stores a `Booking` and publishes a `waitlist-promoted` event.

## The event log

`storage` owns the log. `EventLogFormat` is the one place a line's shape is
decided, and it writes JSON by hand. The event types, currently four, are
declared in `core-domain` (`BookingCreatedEvent`, `BookingCancelledEvent`,
`WaitlistPromotedEvent`, `BookingModifiedEvent`) and their wire names are `booking-created`,
`booking-cancelled`, `waitlist-promoted`, and `booking-modified`.
Task 1 declares and publishes the modification event in the domain. Task 2
will register its wire format and add Python reader support; that integration
is not complete yet. The HTTP modification surface is also pending.

Two writers ship:

- `InMemoryEventLog` keeps events in a list and writes the whole log out on
  demand. The CLI's `export-events` command drains one of these into
  `events.jsonl`.
- `JsonlFileEventLogWriter` appends to a file as events happen, creating it if it
  is missing and continuing it if it is not.

`EventLogPublisher` sits in front of whichever writer is in use. It writes the
log first, then hands the event to each subscriber in turn. A subscriber that
throws is recorded and the next one still runs, which is how notifications stay
fire and forget.

## Where the log goes

```
cli export-events  ->  events.jsonl  ->  analytics (Python)
                                            |
                                            +--> utilization report
                                            +--> revenue report
```

`analytics` reads the file with the standard library's `json` module, folds the
events into one record per booking (`build_ledger`), and reports over a window of
whole UTC days. It never opens a Java store and never writes the log back.

Because the platform keeps every booking inside one UTC day and inside opening
hours, the reports can attribute a booking wholly to the day it starts on. There
is no interval arithmetic on the Python side.

## HTTP surface

`http-api` puts the booking service on `com.sun.net.httpserver`. `Router`
matches a method and a path, `RequestContext` carries the parameters and headers,
and `JsonViews` builds every response body. Input arrives as query or form
parameters, which is why nothing in this platform parses JSON.

| Method | Path | Credentials |
|---|---|---|
| GET | `/health` | none |
| GET | `/rooms` | none |
| GET | `/rooms/{roomId}` | none |
| GET | `/rooms/{roomId}/availability` | none |
| POST | `/bookings` | bearer token |
| GET | `/bookings` | bearer token |
| GET | `/bookings/{bookingId}` | owner or administrator |
| DELETE | `/bookings/{bookingId}` | owner |
| GET | `/waitlist` | bearer token |
| DELETE | `/waitlist/{entryId}` | owner |

Tokens come from `accounts`. `CampusReservePlatform` builds the
`AuthorizationService` and holds it. `RequestAuth` is where an authorization
decision is made and turned into a status, and no handler reaches past it.
`ErrorMapping` is the only place a domain rejection becomes a status code.

`AccountsUserDirectory` adapts `accounts` to the narrow `UserDirectory` port that
`notifications` declares. That adapter lives in `http-api` because `http-api` is
the module that depends on both, and it is the only place the two meet.

## What is in memory

Every store in this build is in memory, and `StoreRegistry` builds a full set.
Stopping a process throws the data away. The event log is the one thing that can
outlive a process, because `JsonlFileEventLogWriter` appends to a file and the
CLI can export one.
