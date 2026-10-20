package edu.cmu.cs214.campusreserve.core.service;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingModification;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cmu.cs214.campusreserve.core.support.Fixtures.interval;

/** Settles the fresh-context refuter's strongest history-aliasing suspicion. */
class ModificationRefuterTest extends BookingServiceTestBase {
    @Test
    void acceptedRejectedAcceptedChangesPreserveRetainedHistoryAndRejectedState() {
        String id = bookAndGetId("r-1", "u-1", interval("10:00", "11:00"));
        bookAndGetId("r-1", "u-2", interval("12:00", "13:00"));
        Booking first = assertInstanceOf(ModificationResult.Modified.class,
            service.modifyBooking(id, "u-1", interval("10:15", "11:15"), 5)).booking();
        List<BookingModification> retainedHistory = first.history();
        BookingModification retainedEntry = retainedHistory.getFirst();
        book("r-1", "u-3", interval("10:15", "10:45"));
        var bookingsBeforeRejection = bookings.findAll();
        var waitlistBeforeRejection = waitlist.findAll();
        var eventsBeforeRejection = publisher.published();
        assertEquals(1, waitlistBeforeRejection.size());

        var rejected = service.modifyBooking(id, "u-1", interval("12:00", "13:00"), 6);
        assertEquals(RejectionReason.ROOM_UNAVAILABLE, rejected.rejectionReason().orElseThrow());
        assertEquals(bookingsBeforeRejection, bookings.findAll());
        assertEquals(waitlistBeforeRejection, waitlist.findAll());
        assertEquals(eventsBeforeRejection, publisher.published());
        assertSame(first, service.findBooking(id).orElseThrow());
        assertEquals(List.of(retainedEntry), retainedHistory);

        clock.advanceMinutes(1);
        Booking last = assertInstanceOf(ModificationResult.Modified.class,
            service.modifyBooking(id, "u-1", interval("11:00", "12:00"), 7)).booking();
        assertEquals(List.of(retainedEntry), first.history());
        assertEquals(List.of(retainedEntry), retainedHistory);
        assertEquals(interval("10:15", "11:15"), first.interval());
        assertEquals(5, first.attendees());
        assertEquals(2, last.history().size());
        assertEquals(retainedEntry, last.history().getFirst());
        BookingModification latest = last.history().get(1);
        assertEquals(first.interval(), latest.oldInterval());
        assertEquals(first.attendees(), latest.oldAttendees());
        assertEquals(last.interval(), latest.newInterval());
        assertEquals(last.attendees(), latest.newAttendees());
        assertTrue(latest.modifiedAt().isAfter(retainedEntry.modifiedAt()));
        assertThrows(UnsupportedOperationException.class, retainedHistory::clear);
        assertEquals(2, last.history().size());
    }
}
