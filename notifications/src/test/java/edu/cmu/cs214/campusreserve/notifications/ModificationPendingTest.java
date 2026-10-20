package edu.cmu.cs214.campusreserve.notifications;

import edu.cmu.cs214.campusreserve.core.events.BookingModifiedEvent;
import edu.cmu.cs214.campusreserve.notifications.support.Fixtures;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Task 1 compatibility checkpoint; Task 3 replaces pending support with delivery. */
class ModificationPendingTest {
    @Test
    void pendingModificationIsRecordedWithoutFailingPublisherOrSendingWrongMessage() {
        var created = Fixtures.created();
        var event = new BookingModifiedEvent(created.occurredAt(), created.bookingId(),
            created.roomId(), created.userId(), created.interval(), 6, created.interval(), 4);
        var outbox = new InMemoryOutbox();
        var service = new NotificationService(Fixtures.directory(), outbox, Clock.systemUTC());
        assertDoesNotThrow(() -> service.publish(event));
        assertEquals(0, service.queuedCount());
        assertTrue(outbox.pending().isEmpty());
        assertEquals(1, service.skippedCount());
        assertEquals("booking-modified", service.skipped().getFirst().eventType());
        assertEquals(event.bookingId(), service.skipped().getFirst().bookingId());
        assertEquals("booking-modified notification support is pending Task 3",
            service.skipped().getFirst().reason());
    }
}
