package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "compliance_rule")
public class ComplianceRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String category;  // CONTINUITY / CUMULATIVE / RANGE / DOOR / TRACK / SMOOTH
    @Column(name = "dosage_form")
    private String dosageForm;
    @Column(nullable = false)
    private String severity = "MAJOR";  // CRITICAL / MAJOR / MINOR
    @Column(nullable = false)
    private String action = "BLOCK";  // BLOCK / REVIEW
    @Column(nullable = false, columnDefinition = "text")
    private String expression;
    @Column(columnDefinition = "text")
    private String description;
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(nullable = false)
    private Integer version = 1;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
