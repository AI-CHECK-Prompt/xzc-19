package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 监管报告。多式联运协同引擎在异常触发监管阈值时自动生成。
 */
@Data
@Entity
@Table(name = "regulatory_report")
public class RegulatoryReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "report_no", nullable = false, unique = true)
    private String reportNo;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "report_type", nullable = false)
    private String reportType;            // INCIDENT / SUMMARY / EXCEPTION
    private String title;
    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;
    @Column(name = "body_json", columnDefinition = "TEXT")
    private String bodyJson;
    @Column(name = "triggered_by")
    private String triggeredBy;
    @Column(nullable = false)
    private String status = "DRAFT";      // DRAFT / SUBMITTED / ACKNOWLEDGED
    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;
    @Column(name = "payload_hash")
    private String payloadHash;
    private String signature;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
