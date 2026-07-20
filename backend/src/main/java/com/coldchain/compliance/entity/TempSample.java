package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 温控采样点（WORM + 哈希链 + RSA 签名）。
 * 严格不可篡改：DB 触发器禁止 UPDATE/DELETE。
 */
@Data
@Entity
@Table(name = "temp_sample")
public class TempSample {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "device_no", nullable = false)
    private String deviceNo;
    @Column(name = "task_id")
    private Long taskId;
    @Column(name = "sample_at", nullable = false)
    private OffsetDateTime sampleAt;
    @Column(name = "server_received_at", insertable = false, updatable = false)
    private OffsetDateTime serverReceivedAt;
    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal temperature;
    @Column(precision = 5, scale = 2)
    private BigDecimal humidity;
    @Column(name = "door_open", nullable = false)
    private Boolean doorOpen = false;
    @Column(precision = 10, scale = 6)
    private BigDecimal latitude;
    @Column(precision = 10, scale = 6)
    private BigDecimal longitude;
    @Column(name = "driver_event")
    private String driverEvent;
    @Column(name = "payload_hash", nullable = false, length = 128)
    private String payloadHash;
    @Column(name = "prev_hash", length = 128)
    private String prevHash;
    @Column(nullable = false, columnDefinition = "text")
    private String signature;
    @Column(name = "seq_no", nullable = false)
    private Long seqNo;
}
