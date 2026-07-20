package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "audit_report")
public class AuditReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;
    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
    @Column(nullable = false)
    private String status = "RUNNING";  // RUNNING / PASS / BLOCK / REVIEW
    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion = 1;
    @Column(name = "finding_count", nullable = false)
    private Integer findingCount = 0;
    @Column(columnDefinition = "text")
    private String summary;
    @Column(columnDefinition = "text")
    private String payload;
    @Column(name = "payload_hash", length = 128)
    private String payloadHash;
    @Column(columnDefinition = "text")
    private String signature;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
