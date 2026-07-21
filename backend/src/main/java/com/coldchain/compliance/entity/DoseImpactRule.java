package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 影响剂量估算规则。按剂型+异常类型索引，用于把异常转换为"受影响药品剂量"。
 */
@Data
@Entity
@Table(name = "dose_impact_rule")
public class DoseImpactRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String code;
    @Column(name = "dosage_form", nullable = false)
    private String dosageForm;
    @Column(name = "exception_type", nullable = false)
    private String exceptionType;
    @Column(name = "base_factor", nullable = false)
    private BigDecimal baseFactor;
    @Column(name = "per_minute_factor", nullable = false)
    private BigDecimal perMinuteFactor = BigDecimal.ZERO;
    @Column(name = "per_degree_factor", nullable = false)
    private BigDecimal perDegreeFactor = BigDecimal.ZERO;
    private String formula;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
