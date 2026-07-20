package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;

@Data
@Entity
@Table(name = "customs_batch_item")
public class CustomsBatchItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "declaration_id", nullable = false)
    private Long declarationId;
    @Column(name = "declared_batch_no", nullable = false)
    private String declaredBatchNo;
    @Column(name = "declared_qty")
    private Integer declaredQty;
    @Column(name = "declared_product")
    private String declaredProduct;
    @Column(name = "matched_batch_id")
    private Long matchedBatchId;
    @Column(name = "match_status", nullable = false)
    private String matchStatus = "PENDING";
    @Column(name = "match_detail", columnDefinition = "text")
    private String matchDetail;
}
