package com.decathlon.parking.service;

import com.decathlon.parking.model.ParkingRecord;
import com.decathlon.parking.model.ParkingSettings;
import com.decathlon.parking.repo.ParkingRecordRepository;
import com.decathlon.parking.repo.ParkingSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ParkingService {
    private static final long SETTINGS_ID = 1L;
    private static final int CAR_CAPACITY = 30;
    private static final int BIKE_CAPACITY = 20;

    private final ParkingRecordRepository repo;
    private final ParkingSettingsRepository settingsRepo;
    private final String storageMode;

    public ParkingService(
        ParkingRecordRepository repo,
        ParkingSettingsRepository settingsRepo,
        @Value("${spring.datasource.url:}") String datasourceUrl
    ) {
        this.repo = repo;
        this.settingsRepo = settingsRepo;
        this.storageMode = datasourceUrl != null && datasourceUrl.toLowerCase(Locale.ROOT).contains("postgresql")
            ? "postgresql"
            : "h2";
    }

    private ParkingSettings getOrCreateSettings() {
        return settingsRepo.findById(SETTINGS_ID).orElseGet(() -> {
            ParkingSettings defaults = new ParkingSettings();
            defaults.setId(SETTINGS_ID);
            defaults.setCarRatePerHour(new BigDecimal("30.00"));
            defaults.setBikeRatePerHour(new BigDecimal("15.00"));
            defaults.setUpdatedAt(LocalDateTime.now());
            return settingsRepo.save(defaults);
        });
    }

    @Transactional
    public ParkingRecord enter(String type, String brand, String model, String plate) {
        String vehicleType = type.trim().toUpperCase(Locale.ROOT);
        if (!vehicleType.equals("CAR") && !vehicleType.equals("BIKE")) {
            throw new IllegalArgumentException("Vehicle type must be CAR or BIKE.");
        }
        String normalizedPlate = plate.trim().toUpperCase(Locale.ROOT);
        if (repo.findFirstByNumberPlateIgnoreCaseAndStatus(normalizedPlate, "PARKED").isPresent()) {
            throw new IllegalArgumentException("This number plate already has an active parking session.");
        }

        int capacity = vehicleType.equals("CAR") ? CAR_CAPACITY : BIKE_CAPACITY;
        String prefix = vehicleType.equals("CAR") ? "C" : "B";
        String slot = null;
        for (int i = 1; i <= capacity; i++) {
            String candidate = prefix + String.format("%02d", i);
            if (!repo.existsBySlotNumberAndStatus(candidate, "PARKED")) {
                slot = candidate;
                break;
            }
        }
        if (slot == null) {
            throw new IllegalStateException("No available " + vehicleType.toLowerCase(Locale.ROOT) + " spaces.");
        }

        ParkingRecord record = new ParkingRecord();
        record.setVehicleType(vehicleType);
        record.setBrand(brand.trim());
        record.setModel(model.trim());
        record.setNumberPlate(normalizedPlate);
        record.setSlotNumber(slot);
        record.setEntryTime(LocalDateTime.now());
        record.setStatus("PARKED");
        return repo.save(record);
    }

    @Transactional
    public Map<String, Object> checkout(Long id) {
        ParkingRecord r = repo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Parking record not found."));
        if (!"PARKED".equals(r.getStatus())) {
            throw new IllegalArgumentException("This vehicle has already checked out.");
        }
        ParkingSettings settings = getOrCreateSettings();
        LocalDateTime now = LocalDateTime.now();
        long minutes = Math.max(1, Duration.between(r.getEntryTime(), now).toMinutes());
        long billableHours = Math.max(1, (long) Math.ceil(minutes / 60.0));
        BigDecimal rate = "CAR".equals(r.getVehicleType())
            ? settings.getCarRatePerHour()
            : settings.getBikeRatePerHour();
        BigDecimal amount = rate.multiply(BigDecimal.valueOf(billableHours)).setScale(2, RoundingMode.HALF_UP);

        r.setExitTime(now);
        r.setDurationMinutes(minutes);
        r.setAmount(amount);
        r.setStatus("PAID");
        repo.save(r);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", r);
        result.put("billableHours", billableHours);
        result.put("message", "Checkout completed and slot released.");
        return result;
    }

    @Transactional(readOnly = true)
    public List<ParkingRecord> active() {
        return repo.findByStatusOrderByEntryTimeDesc("PARKED");
    }

    @Transactional(readOnly = true)
    public List<ParkingRecord> history() {
        return repo.findTop50ByStatusOrderByExitTimeDesc("PAID");
    }

    @Transactional
    public Map<String, Object> dashboard() {
        ParkingSettings settings = getOrCreateSettings();
        long cars = repo.countByStatusAndVehicleType("PARKED", "CAR");
        long bikes = repo.countByStatusAndVehicleType("PARKED", "BIKE");
        long totalCheckouts = repo.countByStatus("PAID");
        BigDecimal totalRevenue = Optional.ofNullable(repo.sumAmountByStatus("PAID"))
            .orElse(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parkedCars", cars);
        m.put("parkedBikes", bikes);
        m.put("totalParked", cars + bikes);
        m.put("availableCarSlots", CAR_CAPACITY - cars);
        m.put("availableBikeSlots", BIKE_CAPACITY - bikes);
        m.put("carCapacity", CAR_CAPACITY);
        m.put("bikeCapacity", BIKE_CAPACITY);
        m.put("carRatePerHour", settings.getCarRatePerHour());
        m.put("bikeRatePerHour", settings.getBikeRatePerHour());
        m.put("totalCheckouts", totalCheckouts);
        m.put("totalRevenue", totalRevenue);
        m.put("storageMode", storageMode);
        return m;
    }

    @Transactional
    public Map<String, Object> rates() {
        ParkingSettings settings = getOrCreateSettings();
        return Map.of(
            "carRatePerHour", settings.getCarRatePerHour(),
            "bikeRatePerHour", settings.getBikeRatePerHour()
        );
    }

    @Transactional
    public Map<String, Object> updateRates(BigDecimal car, BigDecimal bike) {
        if (car == null || bike == null || car.signum() < 0 || bike.signum() < 0) {
            throw new IllegalArgumentException("Rates must be zero or greater.");
        }
        ParkingSettings settings = getOrCreateSettings();
        settings.setCarRatePerHour(car.setScale(2, RoundingMode.HALF_UP));
        settings.setBikeRatePerHour(bike.setScale(2, RoundingMode.HALF_UP));
        settings.setUpdatedAt(LocalDateTime.now());
        settingsRepo.save(settings);
        return rates();
    }

    public String storageMode() {
        return storageMode;
    }
}
