package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "audit_finding")
public class AuditFinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "audit_id", nullable = false)
    private Long auditId;
    @Column(name = "rule_code", nullable = false)
    private String ruleCode;
    @Column(nullable = false)
    private String severity;
    @Column(nullable = false)
    private String action;
    @Column(name = "time_range_start")
    private OffsetDateTime timeRangeStart;
    @Column(name = "time_range_end")
    private OffsetDateTime timeRangeEnd;
    @Column(name = "affected_batch_ids", columnDefinition = "text")
    private String affectedBatchIds;
    @Column(name = "affected_qty")
    private Integer affectedQty;
    @Column(name = "temperature_min", precision = 6, scale = 3)
    private BigDecimal temperatureMin;
    @Column(name = "temperature_max", precision = 6, scale = 3)
    private BigDecimal temperatureMax;
    @Column(nullable = false, columnDefinition = "text")
    private String description;
    @Column(nullable = false, columnDefinition = "text")
    private String evidence;
    @Column(name = "payload_hash", length = 128)
    private String payloadHash;
    @Column(columnDefinition = "text")
    private String signature;
}
