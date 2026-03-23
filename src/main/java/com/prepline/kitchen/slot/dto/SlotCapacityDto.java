package com.prepline.kitchen.slot.dto;

import java.time.Instant;
import java.util.UUID;

// FIX [SLOT-TZ]: Changed slotTime from LocalDateTime to Instant.
//
// Root cause: LocalDateTime has no timezone. When the frontend received a
// string like "2026-03-15T18:00" (no Z, no offset), new Date("2026-03-15T18:00")
// parsed it as LOCAL BROWSER TIME — not IST. For a user in UTC+5:30 this was
// fine, but any other timezone produced wrong slot times, breaking:
//   - pickNormalSlot()   → "20–90 min ahead" filter returned no candidates
//   - pickScheduledSlot() → "6+ hours ahead" filter returned no candidates
// Both fell through to null → reservePickupSlot() never called →
// order saved with pickupSlot = null → OrderCardDto.pickupSlotTime = null →
// resolvePickupTime() hit the no-slot branch → showed "ASAP" / "Tomorrow".
//
// Fix: send slotTime as an Instant (ISO-8601 with Z suffix, e.g.
// "2026-03-15T12:30:00Z"). new Date("2026-03-15T12:30:00Z") always parses
// correctly in every browser timezone.
//
// Callers that previously used slotTime as LocalDateTime must now use Instant.
// OrderQueryService.toSlotCapacityDto() converts PickupSlot.slotTime
// (LocalDateTime in IST) → Instant using KITCHEN_ZONE. See that file.
public record SlotCapacityDto(
        UUID    slotId,
        Instant slotTime,      // was LocalDateTime — now Instant (UTC, Z suffix)
        int     maxCapacity,
        int     currentBookings,
        int     remaining
) {}