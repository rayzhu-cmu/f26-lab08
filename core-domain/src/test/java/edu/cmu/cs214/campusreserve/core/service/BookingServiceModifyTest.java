package edu.cmu.cs214.campusreserve.core.service;

import edu.cmu.cs214.campusreserve.core.domain.*;
import edu.cmu.cs214.campusreserve.core.events.BookingModifiedEvent;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cmu.cs214.campusreserve.core.support.Fixtures.interval;

/** Regression tests added for Task 1; shipped tests remain unchanged. */
class BookingServiceModifyTest extends BookingServiceTestBase {
    private String initial() {
        return bookAndGetId("r-1", "u-1", interval("10:00", "11:00"));
    }

    private ModificationResult.Modified change(String id, TimeInterval time, int attendees) {
        return assertInstanceOf(ModificationResult.Modified.class,
            service.modifyBooking(id, "u-1", time, attendees));
    }

    private void rejectedUnchanged(String id, String user, TimeInterval time,
                                   int attendees, RejectionReason reason) {
        var oldBookings = bookings.findAll();
        var oldWaitlist = waitlist.findAll();
        var oldEvents = publisher.published();
        assertEquals(reason, service.modifyBooking(id, user, time, attendees).rejectionReason().orElseThrow());
        assertEquals(oldBookings, bookings.findAll());
        assertEquals(oldWaitlist, waitlist.findAll());
        assertEquals(oldEvents, publisher.published());
    }

    static Stream<Arguments> successfulIntervals() {
        return Stream.of(
            Arguments.of("10:00", "10:30"),
            Arguments.of("10:00", "11:30"),
            Arguments.of("10:15", "11:15"),
            Arguments.of("12:00", "13:00"));
    }

    @ParameterizedTest
    @MethodSource("successfulIntervals")
    void shorteningExtensionSelfOverlappingAndDisjointMovement(String start, String end) {
        String id = initial();
        Booking before = service.findBooking(id).orElseThrow();
        var result = change(id, interval(start, end), 6);
        Booking after = result.booking();
        assertEquals(interval(start, end), after.interval());
        assertEquals(6, after.attendees());
        assertEquals(before.id(), after.id());
        assertEquals(before.roomId(), after.roomId());
        assertEquals(before.userId(), after.userId());
        assertEquals(before.createdAt(), after.createdAt());
        assertEquals(before.status(), after.status());
        assertEquals(after, service.findBooking(id).orElseThrow());
        assertEquals(1, bookings.count());
        BookingModification entry = after.history().getFirst();
        assertEquals(before.interval(), entry.oldInterval());
        assertEquals(before.attendees(), entry.oldAttendees());
        assertEquals(after.interval(), entry.newInterval());
        assertEquals(6, entry.newAttendees());
        assertEquals(clock.instant(), entry.modifiedAt());
        BookingModifiedEvent event = publisher.ofType(BookingModifiedEvent.class).getFirst();
        assertEquals(entry.oldInterval(), event.oldInterval());
        assertEquals(entry.oldAttendees(), event.oldAttendees());
        assertEquals(after.interval(), event.interval());
        assertEquals(6, event.attendees());
        assertEquals(entry.modifiedAt(), event.occurredAt());
        assertEquals(id, event.bookingId());
        assertEquals("booking-modified", event.type());
    }

    @Test
    void roomConflictRejectsWithoutWaitlistingOrSideEffects() {
        String id = initial();
        bookAndGetId("r-1", "u-2", interval("11:00", "12:00"));
        book("r-1", "u-3", interval("10:00", "10:30"));
        rejectedUnchanged(id, "u-1", interval("10:30", "11:30"), 4, RejectionReason.ROOM_UNAVAILABLE);
    }

    @Test
    void ownOtherRoomConflictRejects() {
        String id = initial();
        bookAndGetId("r-2", "u-1", interval("11:00", "12:00"));
        rejectedUnchanged(id, "u-1", interval("10:30", "11:30"), 4, RejectionReason.USER_DOUBLE_BOOKED);
    }

    @Test
    void halfOpenBoundariesAreAllowedInBothCalendars() {
        String id = initial();
        bookAndGetId("r-1", "u-2", interval("11:30", "12:30"));
        bookAndGetId("r-2", "u-1", interval("11:30", "12:30"));
        change(id, interval("10:30", "11:30"), 4);
    }

    @Test
    void bookingCapDoesNotApplyToModification() {
        build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
        String id = initial();
        change(id, interval("10:15", "11:15"), 5);
        assertEquals(1, bookings.count());
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
            Arguments.of(interval("10:00", "10:15"), 4, RejectionReason.INTERVAL_TOO_SHORT),
            Arguments.of(interval("10:00", "15:00"), 4, RejectionReason.INTERVAL_TOO_LONG),
            Arguments.of(interval("10:01", "11:01"), 4, RejectionReason.INTERVAL_NOT_ALIGNED),
            Arguments.of(TimeInterval.parse("2026-10-20T23:30:00Z", "2026-10-21T00:30:00Z"), 4,
                RejectionReason.INTERVAL_SPANS_DAYS),
            Arguments.of(interval("05:00", "06:00"), 4, RejectionReason.OUTSIDE_OPENING_HOURS),
            Arguments.of(Fixtures.intervalOn("2026-10-18", "10:00", "11:00"), 4,
                RejectionReason.INTERVAL_IN_PAST),
            Arguments.of(Fixtures.intervalOn("2027-01-20", "10:00", "11:00"), 4,
                RejectionReason.TOO_FAR_IN_ADVANCE),
            Arguments.of(interval("10:00", "11:00"), 11, RejectionReason.ATTENDEES_EXCEED_CAPACITY));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void requestRulesStillApply(TimeInterval time, int attendees, RejectionReason reason) {
        rejectedUnchanged(initial(), "u-1", time, attendees, reason);
    }

    @Test
    void inactiveRoomRejectsRealChange() {
        String id = initial();
        Room r = rooms.findById("r-1").orElseThrow();
        rooms.save(new Room(r.id(), r.name(), r.building(), r.capacity(), r.kind(), false));
        rejectedUnchanged(id, "u-1", interval("10:00", "11:00"), 5, RejectionReason.ROOM_INACTIVE);
    }

    @Test
    void unknownBookingRejects() {
        rejectedUnchanged("missing", "u-1", interval("10:00", "11:00"), 4,
            RejectionReason.BOOKING_NOT_FOUND);
    }

    @Test
    void nonOwnerCannotEvenRequestANoop() {
        rejectedUnchanged(initial(), "u-2", interval("10:00", "11:00"), 4,
            RejectionReason.NOT_BOOKING_OWNER);
    }

    @Test
    void cancelledBookingRejectsNoop() {
        String id = initial();
        service.cancelBooking(id, "u-1");
        rejectedUnchanged(id, "u-1", interval("10:00", "11:00"), 4,
            RejectionReason.BOOKING_ALREADY_CANCELLED);
    }

    @Test
    void bookingAtItsStartRejectsNoop() {
        String id = initial();
        clock.setTo("2026-10-20T10:00:00Z");
        rejectedUnchanged(id, "u-1", interval("10:00", "11:00"), 4,
            RejectionReason.BOOKING_ALREADY_STARTED);
    }

    @Test
    void noopPreservesObjectHistoryEventsAndWaitlist() {
        String id = initial();
        book("r-1", "u-2", interval("10:00", "10:30"));
        Booking before = service.findBooking(id).orElseThrow();
        var oldWaitlist = waitlist.findAll();
        var oldEvents = publisher.published();
        assertSame(before, change(id, before.interval(), before.attendees()).booking());
        assertSame(before, service.findBooking(id).orElseThrow());
        assertEquals(oldWaitlist, waitlist.findAll());
        assertEquals(oldEvents, publisher.published());
        assertTrue(before.history().isEmpty());
    }

    @Test
    void historySnapshotsStayImmutableAndSurviveCancellation() {
        String id = initial();
        Booking original = service.findBooking(id).orElseThrow();
        Booking first = change(id, interval("10:15", "11:15"), 5).booking();
        clock.advanceMinutes(1);
        Booking second = change(id, interval("10:30", "11:30"), 6).booking();
        assertTrue(original.history().isEmpty());
        assertEquals(1, first.history().size());
        assertEquals(2, second.history().size());
        assertEquals(first.history().getFirst(), second.history().getFirst());
        assertEquals(first.interval(), second.history().get(1).oldInterval());
        assertTrue(second.history().get(1).modifiedAt().isAfter(first.history().getFirst().modifiedAt()));
        assertThrows(UnsupportedOperationException.class, () -> second.history().clear());
        var source = new ArrayList<>(second.history());
        Booking copy = new Booking(second.id(), second.roomId(), second.userId(), second.interval(),
            second.attendees(), second.status(), second.createdAt(), source);
        source.clear();
        assertEquals(2, copy.history().size());
        service.cancelBooking(id, "u-1");
        assertEquals(second.history(), service.findBooking(id).orElseThrow().history());
    }

    @Test
    void shorteningPromotesFirstWaiterAndRechecksRoomForNext() {
        String id = initial();
        book("r-1", "u-2", interval("10:00", "10:30"));
        book("r-1", "u-3", interval("10:00", "10:30"));
        var result = change(id, interval("10:30", "11:00"), 4);
        assertEquals(1, result.promotions().size());
        assertEquals("u-2", result.promotions().getFirst().booking().userId());
        assertEquals("u-3", waitlist.findAll().getFirst().userId());
        assertEquals(List.of("booking-created", "booking-modified", "waitlist-promoted"), publisher.types());
    }

    @Test
    void promotionSkipsWaiterWithNewOwnCalendarConflict() {
        String id = initial();
        book("r-1", "u-2", interval("10:00", "10:30"));
        book("r-1", "u-3", interval("10:00", "10:30"));
        bookAndGetId("r-2", "u-2", interval("10:00", "10:30"));
        var result = change(id, interval("10:30", "11:00"), 4);
        assertEquals("u-3", result.promotions().getFirst().booking().userId());
        assertEquals("u-2", waitlist.findAll().getFirst().userId());
    }

    @Test
    void extensionRunsPassButCannotPromoteOverlappingWaiter() {
        String id = initial();
        book("r-1", "u-2", interval("10:00", "10:30"));
        var oldWaitlist = waitlist.findAll();
        assertTrue(change(id, interval("10:00", "11:30"), 4).promotions().isEmpty());
        assertEquals(oldWaitlist, waitlist.findAll());
    }

    @Test
    void attendeeOnlyAndNoopDoNotRunPromotionButIntervalChangeDoes() {
        // An expired waitlist entry makes a promotion pass observable even without promotion.
        String past = bookAndGetId("r-1", "u-2", interval("08:00", "09:00"));
        book("r-1", "u-3", interval("08:00", "09:00"));
        String id = initial();
        clock.setTo("2026-10-20T09:00:00Z");
        change(id, interval("10:00", "11:00"), 4);
        assertEquals(1, waitlist.count());
        assertTrue(change(id, interval("10:00", "11:00"), 5).promotions().isEmpty());
        assertEquals(1, waitlist.count());
        change(id, interval("10:00", "11:30"), 5);
        assertEquals(0, waitlist.count());
        assertTrue(service.findBooking(past).orElseThrow().holdsRoom());
    }

    @Test
    void failingPublisherDoesNotUndoChangeOrPreventPromotion() {
        String id = initial();
        book("r-1", "u-2", interval("10:00", "10:30"));
        publisher.failWith(new IllegalStateException("offline"));
        var result = change(id, interval("10:30", "11:00"), 4);
        assertEquals(result.booking(), service.findBooking(id).orElseThrow());
        assertEquals(1, result.promotions().size());
        assertEquals(1, result.booking().history().size());
    }

    @Test
    void invalidAttendeeCountThrowsWithoutSideEffects() {
        String id = initial();
        var old = bookings.findAll();
        var events = publisher.published();
        assertThrows(IllegalArgumentException.class, () -> service.modifyBooking(id, "u-1", interval("10:00", "11:00"), 0));
        assertEquals(old, bookings.findAll());
        assertEquals(events, publisher.published());
    }
}
