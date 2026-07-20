package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "customs_declaration")
public class CustomsDeclaration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "decl_no", nullable = false, unique = true)
    private String declNo;
    @Column(name = "task_id")
    private Long taskId;
    @Column(nullable = false)
    private String language = "zh-CN";
    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;
    @Column(name = "parsed_json", columnDefinition = "text")
    private String parsedJson;
    private String consignor;
    private String consignee;
    @Column(name = "decl_date")
    private OffsetDateTime declDate;
    @Column(name = "total_value", precision = 15, scale = 2)
    private BigDecimal totalValue;
    private String currency;
    @Column(nullable = false)
    private String status = "PARSED";
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
