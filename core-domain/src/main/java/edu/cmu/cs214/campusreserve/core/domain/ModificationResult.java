package edu.cmu.cs214.campusreserve.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The updated booking and promotions, or a rejection with no state changes. */
public sealed interface ModificationResult {
    /** Includes successful no-ops; their booking and history remain unchanged. */
    record Modified(Booking booking, List<CancellationResult.Promotion> promotions)
        implements ModificationResult {
        public Modified {
            Objects.requireNonNull(booking, "booking");
            promotions = List.copyOf(promotions);
        }
    }

    record Rejected(RejectionReason reason) implements ModificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static ModificationResult rejected(RejectionReason reason) {
        return new Rejected(reason);
    }

    default Optional<RejectionReason> rejectionReason() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }
}
