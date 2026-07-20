package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

@Data
@Entity
@Table(name = "hash_anchor")
public class HashAnchor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "last_log_id", nullable = false)
    private Long lastLogId;
    @Column(name = "chain_root", nullable = false, length = 128)
    private String chainRoot;
    @Column(name = "prev_anchor_hash", length = 128)
    private String prevAnchorHash;
    @Column(name = "anchor_hash", nullable = false, length = 128)
    private String anchorHash;
    @Column(nullable = false, columnDefinition = "text")
    private String signature;
    @Column(nullable = false)
    private Integer count;
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
