package edu.cmu.cs214.campusreserve.core.events;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import java.time.Instant;
import java.util.Objects;

/** Current state plus the old details needed by modification subscribers. */
public record BookingModifiedEvent(Instant occurredAt, String bookingId, String roomId,
                                   String userId, TimeInterval interval, int attendees,
                                   TimeInterval oldInterval, int oldAttendees) implements DomainEvent {
    public static final String TYPE = "booking-modified";

    public BookingModifiedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(oldInterval, "oldInterval");
    }

    public static BookingModifiedEvent of(Booking before, Booking after, Instant occurredAt) {
        return new BookingModifiedEvent(occurredAt, after.id(), after.roomId(), after.userId(),
            after.interval(), after.attendees(), before.interval(), before.attendees());
    }

    @Override
    public String type() {
        return TYPE;
    }
}
