package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 段-审计证据关联。一条 finding 可归属到具体段。
 */
@Data
@Entity
@Table(name = "segment_finding_rel")
public class SegmentFindingRel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "segment_id", nullable = false)
    private Long segmentId;
    @Column(name = "finding_id", nullable = false)
    private Long findingId;
    @Column(name = "affected_qty")
    private Integer affectedQty;
    @Column(name = "contribution_score")
    private BigDecimal contributionScore;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
