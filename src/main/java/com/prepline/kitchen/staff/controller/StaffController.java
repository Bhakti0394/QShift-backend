package com.prepline.kitchen.staff.controller;

import com.prepline.kitchen.staff.domain.KitchenStaff;
import com.prepline.kitchen.staff.domain.KitchenStaff.StaffStatus;
import com.prepline.kitchen.staff.dto.StaffRemovalValidationDto;
import com.prepline.kitchen.staff.dto.StaffWorkloadDto;
import com.prepline.kitchen.staff.repository.KitchenStaffRepository;
import com.prepline.kitchen.staff.service.StaffCapacityService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/kitchen/staff")
@RequiredArgsConstructor
public class StaffController {

    private final KitchenStaffRepository staffRepository;
    private final StaffCapacityService staffCapacityService;

    // ── Inner DTOs ────────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateStaffRequest {
        private String name;
        private int maxConcurrentOrders;
        private StaffStatus status;
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @GetMapping
    public List<StaffWorkloadDto> getAllStaff() {
        return staffCapacityService.getWorkloadSnapshot();
    }

    @PostMapping
    public ResponseEntity<StaffWorkloadDto> createStaff(@RequestBody CreateStaffRequest req) {
        KitchenStaff staff = KitchenStaff.builder()
                .name(req.getName())
                .maxConcurrentOrders(req.getMaxConcurrentOrders() > 0 ? req.getMaxConcurrentOrders() : 5)
                .status(req.getStatus() != null ? req.getStatus() : StaffStatus.BACKUP)
                .build();
        KitchenStaff saved = staffRepository.save(staff);
        return ResponseEntity.ok(staffCapacityService.toWorkloadDto(saved));
    }

    @GetMapping("/{id}/validate-removal")
    public ResponseEntity<StaffRemovalValidationDto> validateRemoval(@PathVariable UUID id) {
        return ResponseEntity.ok(staffCapacityService.validateRemoval(id));
    }

    @PatchMapping("/{id}/remove-from-shift")
    public ResponseEntity<List<StaffWorkloadDto>> removeFromShift(@PathVariable UUID id) {
        staffCapacityService.removeFromShift(id);
        return ResponseEntity.ok(staffCapacityService.getWorkloadSnapshot());
    }

    /**
     * PATCH /api/kitchen/staff/{id}/activate
     * Activates a BACKUP chef → ACTIVE.
     */
    @PatchMapping("/{id}/activate")
    public ResponseEntity<List<StaffWorkloadDto>> activateChef(@PathVariable UUID id) {
        staffCapacityService.activateChef(id);   // matches StaffCapacityService.activateChef()
        return ResponseEntity.ok(staffCapacityService.getWorkloadSnapshot());
    }

    /**
     * PATCH /api/kitchen/staff/{id}/return-to-shift
     * Alias for activate — kept for backward-compat with existing frontend calls.
     */
    @PatchMapping("/{id}/return-to-shift")
    public ResponseEntity<StaffWorkloadDto> returnToShift(@PathVariable UUID id) {
        staffCapacityService.activateChef(id);   // matches StaffCapacityService.activateChef()
        KitchenStaff chef = staffRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Staff not found: " + id));
        return ResponseEntity.ok(staffCapacityService.toWorkloadDto(chef));
    }

    /**
     * PATCH /api/kitchen/staff/{id}/toggle-active
     * Legacy shift-planning toggle — kept for DataSeeder and admin panel compat.
     */
    @PatchMapping("/{id}/toggle-active")
    public ResponseEntity<Void> toggleActive(@PathVariable UUID id) {
        KitchenStaff chef = staffRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Staff not found: " + id));

        if (chef.getStatus() == StaffStatus.ACTIVE) {
            chef.setStatus(StaffStatus.BACKUP);
        } else if (chef.getStatus() == StaffStatus.BACKUP) {
            chef.setStatus(StaffStatus.ACTIVE);
        }

        staffRepository.save(chef);
        return ResponseEntity.noContent().build();
    }
}