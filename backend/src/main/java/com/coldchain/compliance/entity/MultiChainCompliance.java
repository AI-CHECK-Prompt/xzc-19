package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 多式联运全链合规结论。按段聚合，每段贡献一项。
 */
@Data
@Entity
@Table(name = "multi_chain_compliance")
public class MultiChainCompliance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "overall_status", nullable = false)
    private String overallStatus;        // PASS / REVIEW / BLOCK
    @Column(name = "overall_decision", nullable = false)
    private String overallDecision;      // RELEASE / CONDITIONAL_RELEASE / BLOCK
    @Column(name = "segment_count", nullable = false)
    private Integer segmentCount;
    @Column(name = "critical_segments", columnDefinition = "TEXT")
    private String criticalSegments;      // JSON
    @Column(name = "contribution_json", columnDefinition = "TEXT")
    private String contributionJson;      // JSON
    @Column(name = "audit_id")
    private Long auditId;
    @Column(name = "summary_text", columnDefinition = "TEXT")
    private String summaryText;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
