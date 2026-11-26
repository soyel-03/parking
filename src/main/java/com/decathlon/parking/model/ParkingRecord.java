package com.decathlon.parking.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "parking_records")
public class ParkingRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String vehicleType;
    @Column(nullable = false) private String brand;
    @Column(nullable = false) private String model;
    @Column(nullable = false) private String numberPlate;
    @Column(nullable = false) private String slotNumber;
    @Column(nullable = false) private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private long durationMinutes;
    private BigDecimal amount;
    @Column(nullable = false) private String status = "PARKED";

    public Long getId(){return id;}
    public String getVehicleType(){return vehicleType;}
    public void setVehicleType(String v){vehicleType=v;}
    public String getBrand(){return brand;}
    public void setBrand(String v){brand=v;}
    public String getModel(){return model;}
    public void setModel(String v){model=v;}
    public String getNumberPlate(){return numberPlate;}
    public void setNumberPlate(String v){numberPlate=v;}
    public String getSlotNumber(){return slotNumber;}
    public void setSlotNumber(String v){slotNumber=v;}
    public LocalDateTime getEntryTime(){return entryTime;}
    public void setEntryTime(LocalDateTime v){entryTime=v;}
    public LocalDateTime getExitTime(){return exitTime;}
    public void setExitTime(LocalDateTime v){exitTime=v;}
    public long getDurationMinutes(){return durationMinutes;}
    public void setDurationMinutes(long v){durationMinutes=v;}
    public BigDecimal getAmount(){return amount;}
    public void setAmount(BigDecimal v){amount=v;}
    public String getStatus(){return status;}
    public void setStatus(String v){status=v;}
}
