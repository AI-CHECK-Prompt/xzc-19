package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "temp_recorder")
public class TempRecorder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "device_no", nullable = false, unique = true)
    private String deviceNo;
    private String model;
    private String vendor;
    @Column(name = "sample_interval_sec", nullable = false)
    private Integer sampleIntervalSec = 60;
    @Column(name = "clock_skew_ms", nullable = false)
    private Long clockSkewMs = 0L;
    @Column(name = "public_key", columnDefinition = "text")
    private String publicKey;
    @Column(nullable = false)
    private String status = "ACTIVE";
    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
