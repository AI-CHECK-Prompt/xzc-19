package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 药品批次。dosage_form 取值：COLD(2-8℃冷藏) / FROZEN(-20℃以下冷冻) / NORMAL(15-25℃常温)
 */
@Data
@Entity
@Table(name = "drug_batch")
public class DrugBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "batch_no", nullable = false, unique = true)
    private String batchNo;
    @Column(name = "product_name", nullable = false)
    private String productName;
    @Column(name = "dosage_form", nullable = false)
    private String dosageForm;
    private String specification;
    private Integer quantity;
    private String manufacturer;
    @Column(name = "production_date")
    private LocalDate productionDate;
    @Column(name = "expiry_date")
    private LocalDate expiryDate;
    @Column(name = "enterprise_id")
    private Long enterpriseId;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
