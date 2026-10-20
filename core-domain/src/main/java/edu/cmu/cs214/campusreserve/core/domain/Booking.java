package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;

/**
 * A booking: one user holds one room over one interval.
 *
 * <p>Bookings reference the room and the user by id rather than by object, so a
 * stored booking stays valid when a room is renamed or taken out of service.
 */
public record Booking(
    String id,
    String roomId,
    String userId,
    TimeInterval interval,
    int attendees,
    BookingStatus status,
    Instant createdAt,
    List<BookingModification> history) {

    /** Compatibility constructor for bookings with no modification history. */
    public Booking(String id, String roomId, String userId, TimeInterval interval,
                   int attendees, BookingStatus status, Instant createdAt) {
        this(id, roomId, userId, interval, attendees, status, createdAt, List.of());
    }

    public Booking {
        history = List.copyOf(history);
        id = Identifiers.require(id, "booking id");
        roomId = Identifiers.require(roomId, "room id");
        userId = Identifiers.require(userId, "user id");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (attendees <= 0) {
            throw new IllegalArgumentException("Attendees must be positive: " + attendees);
        }
    }

    /** Builds a confirmed booking. */
    public static Booking confirmed(
        String id, String roomId, String userId, TimeInterval interval, int attendees, Instant createdAt) {
        return new Booking(id, roomId, userId, interval, attendees, BookingStatus.CONFIRMED, createdAt);
    }

    /** Returns a cancelled copy of this booking. */
    public Booking cancelled() {
        return new Booking(id, roomId, userId, interval, attendees, BookingStatus.CANCELLED, createdAt, history);
    }

    /** Returns a new snapshot, leaving earlier snapshots and their history unchanged. */
    public Booking modified(TimeInterval newInterval, int newAttendees, Instant modifiedAt) {
        Objects.requireNonNull(newInterval, "newInterval");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        if (interval.equals(newInterval) && attendees == newAttendees) {
            return this;
        }
        List<BookingModification> changes = new ArrayList<>(history);
        changes.add(new BookingModification(interval, attendees, newInterval, newAttendees, modifiedAt));
        return new Booking(id, roomId, userId, newInterval, newAttendees, status, createdAt, changes);
    }

    /** Does this booking still hold the room? */
    public boolean holdsRoom() {
        return status.holdsRoom();
    }

    /** Is {@code candidateUserId} the booker? */
    public boolean isOwnedBy(String candidateUserId) {
        return userId.equals(candidateUserId);
    }

    /** Has the booking started by {@code now}? */
    public boolean hasStartedBy(Instant now) {
        return !interval.start().isAfter(now);
    }

    /** How long the booking runs, in whole minutes. */
    public long durationMinutes() {
        return interval.durationMinutes();
    }
}
