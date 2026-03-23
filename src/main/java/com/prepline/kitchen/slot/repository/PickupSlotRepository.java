package com.prepline.kitchen.slot.repository;

import com.prepline.kitchen.slot.domain.PickupSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PickupSlotRepository extends JpaRepository<PickupSlot, UUID> {

    // FIX BUG-2: Only return slots whose time is in the future.
    // Previously getBoardSnapshot() called findAll() which returned every slot
    // ever seeded — including past ones from yesterday, last week, test data, etc.
    // The frontend filtered by time client-side, but the stale data still inflated
    // the board payload on every 10s poll and confused the slot picker.
    //
    // NOTE: PickupSlot.slotTime is a LocalDateTime in system timezone.
    // Instant.now() is converted to LocalDateTime in the caller if needed,
    // but Spring Data can handle Instant → LocalDateTime comparison if the
    // entity field is LocalDateTime — adjust the parameter type to match your entity.
    List<PickupSlot> findBySlotTimeAfterOrderBySlotTimeAsc(LocalDateTime after);

    // Overload for Instant callers (OrderQueryService passes Instant.now()).
    // Spring Data will handle the type — keep whichever matches your entity field.
    // If PickupSlot.slotTime is Instant, remove the LocalDateTime variant above.
    default List<PickupSlot> findBySlotTimeAfter(Instant after) {
        // Delegate to LocalDateTime variant using system timezone.
        // If your PickupSlot.slotTime is already Instant, rename the method above
        // to findBySlotTimeAfter(Instant) and delete this default method.
        java.time.LocalDateTime ldt = after
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime();
        return findBySlotTimeAfterOrderBySlotTimeAsc(ldt);
    }
}