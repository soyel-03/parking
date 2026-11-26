package com.decathlon.parking.service;

import com.decathlon.parking.model.ParkingRecord;
import com.decathlon.parking.model.ParkingSettings;
import com.decathlon.parking.repo.ParkingRecordRepository;
import com.decathlon.parking.repo.ParkingSettingsRepository;
import jakarta.annotation.PostConstruct;
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
    private final SupabaseRestClient supabase;
    private final boolean useSupabaseRest;
    private final String jpaStorageMode;
    private final Object settingsLock = new Object();

    public ParkingService(
        ParkingRecordRepository repo,
        ParkingSettingsRepository settingsRepo,
        SupabaseRestClient supabase,
        @Value("${spring.datasource.url:}") String datasourceUrl
    ) {
        this.repo = repo;
        this.settingsRepo = settingsRepo;
        this.supabase = supabase;
        boolean explicitPostgres = datasourceUrl != null
            && datasourceUrl.toLowerCase(Locale.ROOT).contains("postgresql");
        this.useSupabaseRest = !explicitPostgres && supabase.isConfigured();
        this.jpaStorageMode = explicitPostgres ? "postgresql" : "h2";
    }

    @PostConstruct
    void initSettings() {
        if (!useSupabaseRest) {
            getOrCreateSettings();
        }
    }

    private ParkingSettings getOrCreateSettings() {
        Optional<ParkingSettings> existing = settingsRepo.findById(SETTINGS_ID);
        if (existing.isPresent()) {
            return existing.get();
        }
        synchronized (settingsLock) {
            return settingsRepo.findById(SETTINGS_ID).orElseGet(() -> {
                ParkingSettings defaults = new ParkingSettings();
                defaults.setId(SETTINGS_ID);
                defaults.setCarRatePerHour(new BigDecimal("30.00"));
                defaults.setBikeRatePerHour(new BigDecimal("15.00"));
                defaults.setUpdatedAt(LocalDateTime.now());
                return settingsRepo.saveAndFlush(defaults);
            });
        }
    }

    @Transactional
    public ParkingRecord enter(String type, String brand, String model, String plate) {
        String vehicleType = type.trim().toUpperCase(Locale.ROOT);
        if (!vehicleType.equals("CAR") && !vehicleType.equals("BIKE")) {
            throw new IllegalArgumentException("Vehicle type must be CAR or BIKE.");
        }
        String normalizedPlate = plate.trim().toUpperCase(Locale.ROOT);
        if (useSupabaseRest) {
            return supabase.enter(vehicleType, brand, model, normalizedPlate);
        }

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
        if (useSupabaseRest) {
            return supabase.checkout(id);
        }
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
        if (useSupabaseRest) {
            return supabase.active();
        }
        return repo.findByStatusOrderByEntryTimeDesc("PARKED");
    }

    @Transactional(readOnly = true)
    public List<ParkingRecord> history() {
        if (useSupabaseRest) {
            return supabase.history();
        }
        return repo.findTop50ByStatusOrderByExitTimeDesc("PAID");
    }

    @Transactional
    public Map<String, Object> dashboard() {
        if (useSupabaseRest) {
            return supabase.dashboard();
        }
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
        m.put("storageMode", jpaStorageMode);
        return m;
    }

    @Transactional
    public Map<String, Object> rates() {
        if (useSupabaseRest) {
            try {
                return new LinkedHashMap<>(supabase.getOrCreateRates());
            } catch (SupabaseRestClient.MissingTableException e) {
                return Map.of(
                    "carRatePerHour", new BigDecimal("30.00"),
                    "bikeRatePerHour", new BigDecimal("15.00")
                );
            }
        }
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
        if (useSupabaseRest) {
            return supabase.updateRates(car, bike);
        }
        ParkingSettings settings = getOrCreateSettings();
        settings.setCarRatePerHour(car.setScale(2, RoundingMode.HALF_UP));
        settings.setBikeRatePerHour(bike.setScale(2, RoundingMode.HALF_UP));
        settings.setUpdatedAt(LocalDateTime.now());
        settingsRepo.save(settings);
        return rates();
    }

    public String storageMode() {
        return useSupabaseRest ? supabase.storageMode() : jpaStorageMode;
    }

    public Map<String, Object> publicConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("supabaseConfigured", supabase.isConfigured());
        cfg.put("supabaseUrl", supabase.getSupabaseUrl().isBlank() ? null : supabase.getSupabaseUrl());
        cfg.put("supabasePublishableKey", supabase.getPublishableKey().isBlank() ? null : supabase.getPublishableKey());
        cfg.put("supabaseJwksUrl", supabase.getJwksUrl().isBlank() ? null : supabase.getJwksUrl());
        cfg.put("storageMode", storageMode());
        return cfg;
    }
}
