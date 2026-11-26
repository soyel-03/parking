package com.decathlon.parking.service;

import com.decathlon.parking.model.ParkingRecord;
import com.decathlon.parking.repo.ParkingRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ParkingService {
    private final ParkingRecordRepository repo;
    private volatile BigDecimal carRate = new BigDecimal("30.00");
    private volatile BigDecimal bikeRate = new BigDecimal("15.00");
    private static final int CAR_CAPACITY = 30, BIKE_CAPACITY = 20;

    public ParkingService(ParkingRecordRepository repo) { this.repo = repo; }

    @Transactional
    public ParkingRecord enter(String type, String brand, String model, String plate) {
        String vehicleType = type.trim().toUpperCase(Locale.ROOT);
        if (!vehicleType.equals("CAR") && !vehicleType.equals("BIKE"))
            throw new IllegalArgumentException("Vehicle type must be CAR or BIKE.");
        String normalizedPlate = plate.trim().toUpperCase(Locale.ROOT);
        if (repo.findFirstByNumberPlateIgnoreCaseAndStatus(normalizedPlate, "PARKED").isPresent())
            throw new IllegalArgumentException("This number plate already has an active parking session.");

        int capacity = vehicleType.equals("CAR") ? CAR_CAPACITY : BIKE_CAPACITY;
        String prefix = vehicleType.equals("CAR") ? "C" : "B";
        String slot = null;
        for (int i = 1; i <= capacity; i++) {
            String candidate = prefix + String.format("%02d", i);
            if (!repo.existsBySlotNumberAndStatus(candidate, "PARKED")) { slot = candidate; break; }
        }
        if (slot == null) throw new IllegalStateException("No available " + vehicleType.toLowerCase() + " spaces.");

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
    public Map<String,Object> checkout(Long id) {
        ParkingRecord r = repo.findById(id).orElseThrow(() -> new NoSuchElementException("Parking record not found."));
        if (!"PARKED".equals(r.getStatus())) throw new IllegalArgumentException("This vehicle has already checked out.");
        LocalDateTime now = LocalDateTime.now();
        long minutes = Math.max(1, Duration.between(r.getEntryTime(), now).toMinutes());
        long billableHours = Math.max(1, (long)Math.ceil(minutes / 60.0));
        BigDecimal rate = r.getVehicleType().equals("CAR") ? carRate : bikeRate;
        BigDecimal amount = rate.multiply(BigDecimal.valueOf(billableHours)).setScale(2, RoundingMode.HALF_UP);
        r.setExitTime(now); r.setDurationMinutes(minutes); r.setAmount(amount); r.setStatus("PAID");
        repo.save(r);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("record", r); result.put("billableHours", billableHours);
        result.put("message", "Demo checkout completed. Connect a payment gateway before accepting real payments.");
        return result;
    }

    public List<ParkingRecord> active() { return repo.findByStatusOrderByEntryTimeDesc("PARKED"); }

    public Map<String,Object> dashboard() {
        Map<String,Object> m = new LinkedHashMap<>();
        long cars = repo.countByStatusAndVehicleType("PARKED", "CAR");
        long bikes = repo.countByStatusAndVehicleType("PARKED", "BIKE");
        m.put("parkedCars", cars); m.put("parkedBikes", bikes);
        m.put("totalParked", cars+bikes);
        m.put("availableCarSlots", CAR_CAPACITY-cars); m.put("availableBikeSlots", BIKE_CAPACITY-bikes);
        m.put("carCapacity", CAR_CAPACITY); m.put("bikeCapacity", BIKE_CAPACITY);
        m.put("carRatePerHour", carRate); m.put("bikeRatePerHour", bikeRate);
        return m;
    }

    public Map<String,Object> rates() {
        return Map.of("carRatePerHour", carRate, "bikeRatePerHour", bikeRate);
    }

    public Map<String,Object> updateRates(BigDecimal car, BigDecimal bike) {
        if (car == null || bike == null || car.signum() < 0 || bike.signum() < 0)
            throw new IllegalArgumentException("Rates must be zero or greater.");
        carRate = car.setScale(2, RoundingMode.HALF_UP);
        bikeRate = bike.setScale(2, RoundingMode.HALF_UP);
        return rates();
    }
}
