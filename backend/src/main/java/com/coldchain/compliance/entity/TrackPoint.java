package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "track_point")
public class TrackPoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "device_no", nullable = false)
    private String deviceNo;
    @Column(name = "task_id")
    private Long taskId;
    @Column(name = "sample_at", nullable = false)
    private OffsetDateTime sampleAt;
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal latitude;
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal longitude;
    @Column(name = "speed_kmh", precision = 6, scale = 2)
    private BigDecimal speedKmh;
    @Column(precision = 6, scale = 2)
    private BigDecimal heading;
    @Column(name = "payload_hash", nullable = false, length = 128)
    private String payloadHash;
    @Column(name = "prev_hash", length = 128)
    private String prevHash;
    @Column(nullable = false, columnDefinition = "text")
    private String signature;
    @Column(name = "seq_no", nullable = false)
    private Long seqNo;
}
