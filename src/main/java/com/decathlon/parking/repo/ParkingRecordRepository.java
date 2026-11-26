package com.decathlon.parking.repo;

import com.decathlon.parking.model.ParkingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ParkingRecordRepository extends JpaRepository<ParkingRecord, Long> {
    List<ParkingRecord> findByStatusOrderByEntryTimeDesc(String status);
    Optional<ParkingRecord> findFirstByNumberPlateIgnoreCaseAndStatus(String plate, String status);
    boolean existsBySlotNumberAndStatus(String slot, String status);
    long countByStatusAndVehicleType(String status, String vehicleType);
}
