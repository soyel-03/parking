package com.decathlon.parking.repo;

import com.decathlon.parking.model.ParkingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ParkingRecordRepository extends JpaRepository<ParkingRecord, Long> {
    List<ParkingRecord> findByStatusOrderByEntryTimeDesc(String status);
    List<ParkingRecord> findTop50ByStatusOrderByExitTimeDesc(String status);
    Optional<ParkingRecord> findFirstByNumberPlateIgnoreCaseAndStatus(String plate, String status);
    boolean existsBySlotNumberAndStatus(String slot, String status);
    long countByStatusAndVehicleType(String status, String vehicleType);
    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM ParkingRecord r WHERE r.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") String status);
}
