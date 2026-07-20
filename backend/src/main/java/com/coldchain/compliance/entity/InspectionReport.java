package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "inspection_report")
public class InspectionReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "report_no", nullable = false, unique = true)
    private String reportNo;
    @Column(name = "task_id")
    private Long taskId;
    @Column(name = "batch_id")
    private Long batchId;
    private String inspector;
    @Column(name = "inspected_at")
    private OffsetDateTime inspectedAt;
    private String conclusion;
    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;
    @Column(name = "parsed_json", columnDefinition = "text")
    private String parsedJson;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
