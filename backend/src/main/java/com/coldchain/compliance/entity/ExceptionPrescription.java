package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 异常处置预案。按剂型+异常类型索引；包含阈值、影响剂量估算、建议动作、责任方、处置时效、是否触发监管报告。
 */
@Data
@Entity
@Table(name = "exception_prescription")
public class ExceptionPrescription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String code;
    @Column(name = "dosage_form", nullable = false)
    private String dosageForm;       // COLD/FROZEN/NORMAL
    @Column(name = "exception_type", nullable = false)
    private String exceptionType;    // OVERHEAT/UNDERCOOL/DOOR_OPEN/TRACK_DEVIATION/DEVICE_OFFLINE/SAMPLING_GAP
    @Column(nullable = false)
    private String title;
    @Column(name = "threshold_json", nullable = false)
    private String thresholdJson;
    @Column(name = "impact_rule_json")
    private String impactRuleJson;
    @Column(name = "actions_json", nullable = false)
    private String actionsJson;
    @Column(name = "responsible_party", nullable = false)
    private String responsibleParty; // CARRIER/ENTERPRISE/SUPPLIER
    @Column(name = "response_hours", nullable = false)
    private Integer responseHours = 24;
    @Column(name = "regulatory_report", nullable = false)
    private Boolean regulatoryReport = false;
    @Column(nullable = false)
    private String severity = "MAJOR";
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(nullable = false)
    private Integer version = 1;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
