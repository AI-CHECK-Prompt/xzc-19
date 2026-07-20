package com.coldchain.compliance.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 操作日志。GxP 全留痕 + 哈希链 + 签名。
 */
@Data
@Entity
@Table(name = "operation_log")
public class OperationLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private String userId;
    @Column(name = "user_role", nullable = false)
    private String userRole;
    @Column(nullable = false)
    private String action;
    @Column(name = "resource_type")
    private String resourceType;
    @Column(name = "resource_id")
    private String resourceId;
    private String ip;
    @Column(name = "user_agent", length = 500)
    private String userAgent;
    @Column(columnDefinition = "text")
    private String payload;
    private String result;
    @Column(name = "payload_hash", length = 128)
    private String payloadHash;
    @Column(name = "prev_hash", length = 128)
    private String prevHash;
    @Column(columnDefinition = "text")
    private String signature;
    @Column(name = "occurred_at", insertable = false, updatable = false)
    private OffsetDateTime occurredAt;
}
