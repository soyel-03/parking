package com.decathlon.parking.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "parking_settings")
public class ParkingSettings {
    @Id
    private Long id = 1L;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal carRatePerHour = new BigDecimal("30.00");

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal bikeRatePerHour = new BigDecimal("15.00");

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BigDecimal getCarRatePerHour() { return carRatePerHour; }
    public void setCarRatePerHour(BigDecimal carRatePerHour) { this.carRatePerHour = carRatePerHour; }
    public BigDecimal getBikeRatePerHour() { return bikeRatePerHour; }
    public void setBikeRatePerHour(BigDecimal bikeRatePerHour) { this.bikeRatePerHour = bikeRatePerHour; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
