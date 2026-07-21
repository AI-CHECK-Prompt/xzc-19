package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 责任段判定规则。决定"哪段对最终结论负责"。
 */
@Data
@Entity
@Table(name = "responsibility_rule")
public class ResponsibilityRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String code;
    @Column(name = "exception_type", nullable = false)
    private String exceptionType;
    @Column(name = "rule_expr", nullable = false)
    private String ruleExpr;
    @Column(name = "default_party", nullable = false)
    private String defaultParty;
    @Column(nullable = false)
    private Integer priority = 100;
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
