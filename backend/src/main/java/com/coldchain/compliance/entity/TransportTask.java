package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "transport_task")
public class TransportTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_no", nullable = false, unique = true)
    private String taskNo;
    private String origin;
    private String destination;
    @Column(name = "origin_country")
    private String originCountry;
    @Column(name = "dest_country")
    private String destCountry;
    @Column(name = "departure_at")
    private OffsetDateTime departureAt;
    @Column(name = "arrival_at")
    private OffsetDateTime arrivalAt;
    @Column(name = "expected_arrival_at")
    private OffsetDateTime expectedArrivalAt;
    @Column(nullable = false)
    private String status = "CREATED"; // CREATED/IN_TRANSIT/ARRIVED/AUDITING/RELEASED/BLOCKED
    @Column(name = "driver_name")
    private String driverName;
    @Column(name = "vehicle_no")
    private String vehicleNo;
    @Column(name = "enterprise_id")
    private Long enterpriseId;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
