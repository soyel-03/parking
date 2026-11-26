package com.decathlon.parking.repo;

import com.decathlon.parking.model.ParkingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSettingsRepository extends JpaRepository<ParkingSettings, Long> {
}
