package com.decathlon.parking.controller;

import com.decathlon.parking.model.ParkingRecord;
import com.decathlon.parking.service.ParkingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ParkingController {
    private final ParkingService service;

    public ParkingController(ParkingService service) {
        this.service = service;
    }

    public record EntryRequest(
        @NotBlank String vehicleType,
        @NotBlank String brand,
        @NotBlank String model,
        @NotBlank @Size(max = 20) String numberPlate
    ) {}

    public record RateRequest(
        @NotNull @DecimalMin("0.0") BigDecimal carRatePerHour,
        @NotNull @DecimalMin("0.0") BigDecimal bikeRatePerHour
    ) {}

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "storageMode", service.storageMode());
    }

    @PostMapping("/parking/entry")
    public ResponseEntity<ParkingRecord> entry(@Valid @RequestBody EntryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.enter(req.vehicleType(), req.brand(), req.model(), req.numberPlate()));
    }

    @GetMapping("/parking/active")
    public List<ParkingRecord> active() {
        return service.active();
    }

    @GetMapping("/parking/history")
    public List<ParkingRecord> history() {
        return service.history();
    }

    @PostMapping("/parking/{id}/checkout")
    public Map<String, Object> checkout(@PathVariable Long id) {
        return service.checkout(id);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return service.dashboard();
    }

    @GetMapping("/settings/rates")
    public Map<String, Object> rates() {
        return service.rates();
    }

    @PutMapping("/settings/rates")
    public Map<String, Object> updateRates(@Valid @RequestBody RateRequest r) {
        return service.updateRates(r.carRatePerHour(), r.bikeRatePerHour());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Invalid request.";
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validationError(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .orElse("Validation failed.");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Resource not found.";
        return ResponseEntity.status(404).body(Map.of("error", msg));
    }
}
