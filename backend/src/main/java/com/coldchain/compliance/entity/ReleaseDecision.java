package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "release_decision")
public class ReleaseDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(nullable = false)
    private String decision;  // RELEASE / BLOCK / CONDITIONAL_RELEASE
    @Column(name = "decided_by", nullable = false)
    private String decidedBy;
    @Column(name = "decided_at", insertable = false, updatable = false)
    private OffsetDateTime decidedAt;
    @Column(columnDefinition = "text")
    private String basis;
    @Column(name = "audit_report_id")
    private Long auditReportId;
    @Column(name = "customs_ok", nullable = false)
    private Boolean customsOk = false;
    @Column(name = "inspection_ok", nullable = false)
    private Boolean inspectionOk = false;
    @Column(name = "temperature_ok", nullable = false)
    private Boolean temperatureOk = false;
    @Column(columnDefinition = "text")
    private String comment;
    @Column(name = "payload_hash", length = 128)
    private String payloadHash;
    @Column(columnDefinition = "text")
    private String signature;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
