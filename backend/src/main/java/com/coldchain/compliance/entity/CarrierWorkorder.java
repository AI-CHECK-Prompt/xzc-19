package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 承运商整改工单。多式联运协同引擎在某段发生异常时，自动给责任段承运商开具。
 */
@Data
@Entity
@Table(name = "carrier_workorder")
public class CarrierWorkorder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "workorder_no", nullable = false, unique = true)
    private String workorderNo;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "segment_id", nullable = false)
    private Long segmentId;
    @Column(name = "carrier_id", nullable = false)
    private Long carrierId;
    @Column(name = "exception_type", nullable = false)
    private String exceptionType;
    @Column(nullable = false)
    private String severity;
    @Column(nullable = false)
    private String title;
    private String description;
    @Column(name = "prescription_id")
    private Long prescriptionId;
    @Column(name = "affected_qty")
    private Integer affectedQty;
    @Column(name = "responsible_party")
    private String responsibleParty;
    @Column(name = "response_deadline")
    private OffsetDateTime responseDeadline;
    @Column(nullable = false)
    private String status = "OPEN"; // OPEN/IN_PROGRESS/RESOLVED/CLOSED/OVERDUE
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
    @Column(name = "resolved_note")
    private String resolvedNote;
}
